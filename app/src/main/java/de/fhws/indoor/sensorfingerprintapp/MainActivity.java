package de.fhws.indoor.sensorfingerprintapp;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.preference.PreferenceManager;

import com.github.mikephil.charting.charts.BarChart;
import com.github.mikephil.charting.components.AxisBase;
import com.github.mikephil.charting.components.XAxis;
import com.github.mikephil.charting.data.BarData;
import com.github.mikephil.charting.data.BarDataSet;
import com.github.mikephil.charting.data.BarEntry;
import com.github.mikephil.charting.formatter.ValueFormatter;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import de.fhws.indoor.libsmartphoneindoormap.model.Fingerprint;
import de.fhws.indoor.libsmartphoneindoormap.model.FingerprintPath;
import de.fhws.indoor.libsmartphoneindoormap.model.FingerprintPosition;
import de.fhws.indoor.libsmartphoneindoormap.model.Floor;
import de.fhws.indoor.libsmartphoneindoormap.model.Map;
import de.fhws.indoor.libsmartphoneindoormap.model.Vec2;
import de.fhws.indoor.libsmartphoneindoormap.parser.MapSeenSerializer;
import de.fhws.indoor.libsmartphoneindoormap.parser.XMLMapParser;
import de.fhws.indoor.libsmartphoneindoormap.renderer.ColorScheme;
import de.fhws.indoor.libsmartphoneindoormap.renderer.IMapEventListener;
import de.fhws.indoor.libsmartphoneindoormap.renderer.MapView;
import de.fhws.indoor.libsmartphonesensors.SensorManager;
import de.fhws.indoor.libsmartphonesensors.SensorType;
import de.fhws.indoor.libsmartphonesensors.loggers.Logger;
import de.fhws.indoor.libsmartphonesensors.loggers.TimedOrderedLogger;
import de.fhws.indoor.libsmartphonesensors.sensors.DecawaveUWB;
import de.fhws.indoor.libsmartphonesensors.sensors.WiFi;

/**
 * @author Steffen Kastner
 */
public class MainActivity extends AppCompatActivity {
    public static final String STREAM_TAG = "FileStream";
    public static final String MAP_URI = "map.xml";
    public static final String FINGERPRINTS_URI = "fingerprints.dat";
    public static final String FINGERPRINTS_TMP_DIR = "fingerprints";
    public static final String FINGERPRINTS_TMP_EXTENSION = ".dat.tmp";
    public static final String FINGERPRINTS_EXTENSION = ".dat";
    public static final String MAP_PREFERENCES = "MAP_PREFERENCES";
    public static final String MAP_PREFERENCES_FLOOR = "FloorName";
    public static final String FILE_PROVIDER_AUTHORITY = "de.fhws.indoor.sensorfingerprintapp.fileprovider";
    private static final long DEFAULT_WIFI_SCAN_INTERVAL = (Build.VERSION.SDK_INT == 28 ? 30000 : 1);
    private static final long DEFAULT_FINGERPRINT_DURATION = 0; // infinite

    private class FingerprintFileLocations {
        private final File fingerprintsFile;
        private final File tmpFingerprintsDir;
        private File tmpFingerprintsFile = null;
        private BufferedOutputStream tmpFingerprintsOutputStream = null;
        private final TimedOrderedLogger logger;

        public FingerprintFileLocations(Context context, String uri, String tmpDir) {
            logger = new TimedOrderedLogger(context, FILE_PROVIDER_AUTHORITY);

            // file where exported data will be
            fingerprintsFile = new File(getExternalFilesDir(null), uri);

            // create output directory if not exists
            tmpFingerprintsDir = new File(getExternalFilesDir(null), tmpDir);
            if (!tmpFingerprintsDir.isDirectory()) {
                if (!tmpFingerprintsDir.mkdir()) {
                    String msg = "Cannot create output directory: " + tmpFingerprintsDir.getPath();
                    Log.e(STREAM_TAG, msg);
                    Toast.makeText(getApplicationContext(), msg, Toast.LENGTH_LONG).show();
                }
            }
        }

        public void startRecording(Fingerprint selectedFingerprint) throws FileNotFoundException {
            assert recordingFingerprint == null;
            assert tmpFingerprintsDir != null;
            assert tmpFingerprintsFile == null;
            assert tmpFingerprintsOutputStream == null;

            // check if that fingerprint was already recorded
            FingerprintRecordings.Recording recording = fingerprintRecordings.getRecording(selectedFingerprint);
            if (recording == null) {
                recording = new FingerprintRecordings.Recording(selectedFingerprint.name, null);
                fingerprintRecordings.addRecording(recording);
            }

            tmpFingerprintsFile = getFingerprintFile(recording, FINGERPRINTS_TMP_EXTENSION);
            tmpFingerprintsOutputStream = new BufferedOutputStream(getContentResolver().openOutputStream(Uri.fromFile(tmpFingerprintsFile), "wt"));
            writeHeader(tmpFingerprintsOutputStream, selectedFingerprint);
            logger.setCustomOutputStream(tmpFingerprintsOutputStream);
            logger.start(new Logger.FileMetadata("Unknown", "SensorFingerprintApp"));
        }

        public void stopRecording(Fingerprint recordingFingerprint) throws IOException {
            assert recordingFingerprint != null;
            assert tmpFingerprintsFile != null;
            assert tmpFingerprintsOutputStream != null;

            // stop logger
            logger.stop();

            // close output stream
            tmpFingerprintsOutputStream.close();
            tmpFingerprintsOutputStream = null;

            // get recording from database
            FingerprintRecordings.Recording recording = fingerprintRecordings.getRecording(recordingFingerprint);
            assert recording != null;

            // open output stream as input stream
            File outFile = getFingerprintFile(recording, FINGERPRINTS_EXTENSION);
            try (InputStream in = getContentResolver().openInputStream(Uri.fromFile(tmpFingerprintsFile))) {
                try (OutputStream out = getContentResolver().openOutputStream(Uri.fromFile(outFile), "wt")) {
                    // append content
                    appendToOutputStream(in, out);
                } catch (FileNotFoundException e) {
                    Log.e(STREAM_TAG, e.toString());
                    Toast.makeText(getApplicationContext(), "Cannot open output file!", Toast.LENGTH_LONG).show();
                }
            } catch (FileNotFoundException e) {
                Log.e(STREAM_TAG, e.toString());
                Toast.makeText(getApplicationContext(), "Cannot open temporary input file!", Toast.LENGTH_LONG).show();
            }

            if (!tmpFingerprintsFile.delete()) {
                Toast.makeText(getApplicationContext(), "Cannot delete temporary fingerprint file!", Toast.LENGTH_LONG).show();
            }

            tmpFingerprintsFile = null;
        }

        public void write(SensorType id, long timestamp, String csv) {
            if (tmpFingerprintsOutputStream == null) { return; }
            logger.addCSV(id, timestamp, csv);
        }

        public void export() {
            File[] files = tmpFingerprintsDir.listFiles();
            if (files != null) {
                try (OutputStream out = getContentResolver().openOutputStream(Uri.fromFile(fingerprintsFile), "wt")) {
                    for (File fpFile : files) {
                        try (InputStream in = getContentResolver().openInputStream(Uri.fromFile(fpFile))) {
                            appendToOutputStream(in, out);

                            // write newline at the end of each file
                            out.write("\n".getBytes(StandardCharsets.UTF_8));
                        } catch (FileNotFoundException e) {
                            Log.e(STREAM_TAG, e.toString());
                            Toast.makeText(getApplicationContext(), "Cannot open input file!", Toast.LENGTH_LONG).show();
                        }
                    }
                } catch (FileNotFoundException e) {
                    Log.e(STREAM_TAG, e.toString());
                    Toast.makeText(getApplicationContext(), "Cannot open output file!", Toast.LENGTH_LONG).show();
                } catch (IOException e) {
                    Log.e(STREAM_TAG, e.toString());
                    Toast.makeText(getApplicationContext(), "Cannot close output file!", Toast.LENGTH_LONG).show();
                }
            }
        }

        private File getFingerprintFile(FingerprintRecordings.Recording recording, String extension) {
            return new File(tmpFingerprintsDir, recording.getId().toString() + extension);
        }

        private void writeHeader(OutputStream out, Fingerprint fingerprint) {
            try {
                // write header
                if (fingerprint instanceof FingerprintPosition) {
                    out.write(String
                            .format(Locale.US,
                                    "%s\nname=%s\n\n",
                                    FingerprintFileParser.FINGERPRINT_POINT_TAG,
                                    fingerprint.name)
                            .getBytes(StandardCharsets.UTF_8));
                } else if (fingerprint instanceof FingerprintPath) {
                    FingerprintPath path = (FingerprintPath) fingerprint;

                    StringBuilder points = new StringBuilder("[");
                    for (String point : path.fingerprintNames) {
                        points.append(String.format(Locale.US, "\"%s\", ", point));
                    }
                    // delete last comma and insert closing bracket
                    points.delete(points.length() - 2, points.length());
                    points.append("]");

                    out.write(String
                            .format(Locale.US,
                                    "%s\npoints=%s\n\n",
                                    FingerprintFileParser.FINGERPRINT_PATH_TAG,
                                    points)
                            .getBytes(StandardCharsets.UTF_8));
                }
            } catch (IOException e) {
                Log.e(STREAM_TAG, e.toString());
                Toast.makeText(getApplicationContext(), "Cannot write header to output file!", Toast.LENGTH_LONG).show();
            }
        }
    }

    private MapView mapView = null;
    private final MapView.ViewConfig mapViewConfig = new MapView.ViewConfig();
    public static Map currentMap = null;

    private Fingerprint selectedFingerprint = null;

    private final SensorManager sensorManager = new SensorManager();
    // sensorManager status
    private Timer sensorManagerStatisticsTimer;
    private AtomicLong loadCounterWifi = new AtomicLong(0);
    private AtomicLong loadCounterWifiRTT = new AtomicLong(0);
    private AtomicLong loadCounterBeacon = new AtomicLong(0);
    private AtomicLong loadCounterGPS = new AtomicLong(0);
    private AtomicLong loadCounterUWB = new AtomicLong(0);

    ArrayAdapter<String> mFloorNameAdapter;
    private SharedPreferences mPrefs;

    private Button btnStart = null;
    private Button btnExport = null;
    private Button btnSettings = null;
    private Button btnStatistics = null;

    private LinearLayout layoutStatistics = null;
    private BarChart barChartStatistics = null;
    private HashMap<SensorType, HashMap<String, AtomicLong>> statisticsData = new HashMap<>();

    private FingerprintFileLocations fingerprintFileLocations;
    private final FingerprintRecordings fingerprintRecordings = new FingerprintRecordings();
    private Fingerprint recordingFingerprint = null;
    private TimerTask timeoutTask = null;

    private final Timer timer = new Timer(true);

    private final FilenameFilter tmpFingerprintFileFilter = (file, s) -> s.endsWith(FINGERPRINTS_TMP_EXTENSION);
    private final FilenameFilter fingerprintFileFilter = (file, s) -> s.endsWith(FINGERPRINTS_EXTENSION);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // filtered devices enable/disable

        // create output locations
        fingerprintFileLocations = new FingerprintFileLocations(this, FINGERPRINTS_URI, FINGERPRINTS_TMP_DIR);

        // setup export button
        btnExport = findViewById(R.id.btnExport);
        btnExport.setOnClickListener((view) -> exportFingerprints());
        setBtnExportEnabled();

        // setup map view
        mapView = findViewById(R.id.MapView);
        mapView.setColorScheme(new ColorScheme(R.color.wallColor, R.color.unseenColor, R.color.seenColor, R.color.selectedColor));
        mPrefs = getSharedPreferences(MAP_PREFERENCES, MODE_PRIVATE);

        TextView lblCntBeacon = findViewById(R.id.lblCntBeacon);
        lblCntBeacon.setOnClickListener((view) -> {
            mapViewConfig.showBluetooth = !mapViewConfig.showBluetooth;
            mapView.setViewConfig(mapViewConfig);
            lblCntBeacon.setTextColor(getResources().getColor(((mapViewConfig.showBluetooth) ? R.color.white : R.color.unseenColor), getTheme()));
        });
        TextView lblCntUWB = findViewById(R.id.lblCntUWB);
        lblCntUWB.setOnClickListener((view) -> {
            mapViewConfig.showUWB = !mapViewConfig.showUWB;
            mapView.setViewConfig(mapViewConfig);
            lblCntUWB.setTextColor(getResources().getColor(((mapViewConfig.showUWB) ? R.color.white : R.color.unseenColor), getTheme()));
        });
        TextView lblCntWifi = findViewById(R.id.lblCntWifi);
        TextView lblCntWifiRTT = findViewById(R.id.lblCntWifiRTT);
        View.OnClickListener wifiClickListener = (view) -> {
            mapViewConfig.showWiFi = !mapViewConfig.showWiFi;
            mapView.setViewConfig(mapViewConfig);
            lblCntWifi.setTextColor(getResources().getColor(((mapViewConfig.showWiFi) ? R.color.white : R.color.unseenColor), getTheme()));
            lblCntWifiRTT.setTextColor(getResources().getColor(((mapViewConfig.showWiFi) ? R.color.white : R.color.unseenColor), getTheme()));
        };
        lblCntWifi.setOnClickListener(wifiClickListener);
        lblCntWifiRTT.setOnClickListener(wifiClickListener);

        mapView.addEventListener(new IMapEventListener() {
            @Override
            public void onTap(Vec2 mapPosition) {
                if (selectedFingerprint != null) {
                    selectedFingerprint.selected = false;
                    mapView.invalidate();
                }

                Fingerprint fp = mapView.findNearestFingerprint(mapPosition, 1.0f);
                if (fp != null) {
                    selectedFingerprint = fp;
                    fp.selected = true;
                    mapView.invalidate();
                    btnStart.setEnabled(true);
                }
                setBtnStartEnabled();
            }

            @Override
            public void onLongPress(Vec2 mapPosition) {

            }

            @Override
            public void onDragStart(Vec2 mapPosition) {

            }

            @Override
            public void onDragEnd(Vec2 mapPosition) {

            }
        });

        // setup statistic layout
        layoutStatistics = findViewById(R.id.LayoutStatistics);
        layoutStatistics.setVisibility(View.GONE);

        barChartStatistics = findViewById(R.id.BarChartStatistics);

        // setup start fingerprinting button
        btnStart = findViewById(R.id.btnStart);
        btnStart.setEnabled(false);
        btnStart.setOnClickListener((view) -> {
            if (recordingFingerprint == null) {
                startRecording();
            } else {
                stopRecording();
            }
        });

        // setup settings view
        btnSettings = findViewById(R.id.btnSettings);
        btnSettings.setOnClickListener(v -> startActivity(new Intent(getApplicationContext(), SettingsActivity.class)));

        mFloorNameAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, new ArrayList<>());

        // setup floor spinner
        Spinner spinnerFloor = findViewById(R.id.spinner_selectFloor);
        spinnerFloor.setAdapter(mFloorNameAdapter);
        spinnerFloor.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> adapterView, View view, int i, long l) {
                String floorName = (String) adapterView.getItemAtPosition(i);

                SharedPreferences.Editor ed = mPrefs.edit();
                ed.putString(MAP_PREFERENCES_FLOOR, floorName);
                ed.apply();

                mapView.selectFloor(floorName);
            }

            @Override
            public void onNothingSelected(AdapterView<?> adapterView) {}
        });

        // setup statistics view
        btnStatistics = findViewById(R.id.btnStatistics);
        btnStatistics.setOnClickListener(view -> {
            layoutStatistics.setVisibility(layoutStatistics.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
        });
        barChartStatistics = findViewById(R.id.BarChartStatistics);
        barChartStatistics.getXAxis().setTextColor(getResources().getColor(R.color.text_black, getTheme()));
        barChartStatistics.getXAxis().setPosition(XAxis.XAxisPosition.BOTTOM);
        barChartStatistics.getXAxis().setLabelRotationAngle(90);

        timer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                if (layoutStatistics.getVisibility() == View.VISIBLE) {
                    HashMap<String, AtomicLong> sensorData = statisticsData.get(SensorType.IBEACON);
                    if (sensorData != null) {
                        ArrayList<BarEntry> entries = new ArrayList<>();
                        ArrayList<String> ids = new ArrayList<>();
                        for (java.util.Map.Entry<String, AtomicLong> entry : sensorData.entrySet()) {
                            entries.add(new BarEntry(ids.size(), entry.getValue().get()));
                            ids.add(entry.getKey());
                        }
                        BarDataSet set = new BarDataSet(entries, SensorType.IBEACON.toString());
                        BarData data = new BarData(set);
                        ValueFormatter formatter = new ValueFormatter() {
                            @Override
                            public String getAxisLabel(float value, AxisBase axis) {
                                return ids.get(Math.min(Math.max(0, Math.round(value)), ids.size()-1));
                            }
                        };
                        runOnUiThread(() -> {
                            barChartStatistics.getXAxis().setValueFormatter(formatter);
                            barChartStatistics.setData(data);
                            barChartStatistics.setFitBars(true);
                            barChartStatistics.invalidate();
                        });
                    }
                }
            }
        }, 1000, 1000);

        // setup sensorManager callbacks
        sensorManager.addSensorListener((timestamp, sensorId, csv) -> {
            if(currentMap == null) { return; }

            if(sensorId == SensorType.IBEACON) {
                currentMap.setSeenBeacon(csv.substring(0, 12));
            } else if(sensorId == SensorType.WIFI) {
                currentMap.setSeenWiFi(csv.substring(0, 12));
            } else if(sensorId == SensorType.WIFIRTT) {
                currentMap.setSeenFtm(csv.substring(0, 12));
            } else if(sensorId == SensorType.DECAWAVE_UWB) {
                String[] segments = csv.split(";");
                // skip initial 4 (x, y, z, quality) - then take every 3rd
                for(int i = 4; i < segments.length; i += 3) {
                    int shortDeviceId = Integer.parseInt(segments[i]);
                    // shortDeviceId is a uint16
                    if(shortDeviceId >= 0 && shortDeviceId <= 65535) {
                        String shortDeviceIdStr = String.format("%04X", shortDeviceId);
                        currentMap.setSeenUWB(shortDeviceIdStr);
                    }
                }
            }
        });

        //register sensorManager listener for statistics UI
        sensorManager.addSensorListener((timestamp, id, csv) -> {
            // update UI for WIFI/BEACON/GPS
            switch (id) {
                case WIFI: {
                    loadCounterWifi.incrementAndGet();
                    break;
                }
                case WIFIRTT: {
                    loadCounterWifiRTT.incrementAndGet();
                    break;
                }
                case IBEACON: {
                    loadCounterBeacon.incrementAndGet();
                    break;
                }
                case GPS: {
                    loadCounterGPS.incrementAndGet();
                    break;
                }
                case DECAWAVE_UWB: {
                    loadCounterUWB.incrementAndGet();
                    break;
                }
            }

            String[] splitString = csv.split(";");
            if (!statisticsData.containsKey(id)) {
                statisticsData.put(id, new HashMap<>());
            }

            HashMap<String, AtomicLong> sensorStatistics = statisticsData.get(id);
            assert sensorStatistics != null;
            if (!sensorStatistics.containsKey(splitString[0])) {
                sensorStatistics.put(splitString[0], new AtomicLong(0));
            }
            AtomicLong sensorCnt = sensorStatistics.get(splitString[0]);
            assert sensorCnt != null;
            sensorCnt.incrementAndGet();
        });

        sensorManagerStatisticsTimer = new Timer();
        sensorManagerStatisticsTimer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                updateSensorStatistics();
            }
        }, 250, 250);

        // register sensorManager listener for fingerprint recording
        sensorManager.addSensorListener((timestamp, id, csv) -> {
            if (recordingFingerprint == null) { return; }
            fingerprintFileLocations.write(id, timestamp, csv);
        });
    }

    @Override
    protected void onStart() {
        super.onStart();
        showMap();
        selectedFingerprint = null;
        recordingFingerprint = null;
        setBtnStartEnabled();
        setBtnExportEnabled();

        // try to recover temporary fingerprint files that were aborted during recording
        recoverTmpFingerprints();
    }

    @Override
    protected void onStop() {
        super.onStop();

        if (recordingFingerprint != null) {
            Toast.makeText(this, "Stopping recording since app is moved into background!", Toast.LENGTH_LONG).show();
            stopRecording();
        }

        try {
            sensorManager.stop(this);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void startRecording() {
        assert fingerprintFileLocations != null;
        assert selectedFingerprint != null;
        assert timeoutTask == null;

        resetSensorStatistics();

        try {
            fingerprintFileLocations.startRecording(selectedFingerprint);

            setupSensors();
            btnStart.setText(R.string.stop_button_text);
            recordingFingerprint = selectedFingerprint;

            setBtnExportEnabled();
            setBtnSettingsEnabled();

            // schedule timeoutTask to stop recording after fingerprintDuration
            long fingerprintDuration = Long.parseLong(PreferenceManager.getDefaultSharedPreferences(this).getString("prefFingerprintDurationMSec", Long.toString(DEFAULT_FINGERPRINT_DURATION)));
            if (fingerprintDuration != 0) {
                timeoutTask = new TimerTask() {
                    @Override
                    public void run() {
                        runOnUiThread(() -> stopRecording());
                    }
                };
                timer.schedule(timeoutTask, fingerprintDuration);
            }
        } catch (FileNotFoundException e) {
            Log.e(STREAM_TAG, e.toString());
            Toast.makeText(getApplicationContext(), "Failed opening output stream!", Toast.LENGTH_LONG).show();
        }
    }

    private void stopRecording() {
        assert fingerprintFileLocations != null;
        assert recordingFingerprint != null;
        assert timeoutTask != null;
        try {
            sensorManager.stop(this);
        } catch (Exception e) {
            e.printStackTrace();
        }
        try {
            fingerprintFileLocations.stopRecording(recordingFingerprint);

            recordingFingerprint.recorded = true;
            mapView.invalidate();

            recordingFingerprint = null;

            btnStart.setText(R.string.start_button_text);
            setBtnExportEnabled();
            setBtnSettingsEnabled();

            // cancel a maybe still running timeout task, if stop was pressed before timeout
            timeoutTask.cancel();
            timeoutTask = null;
        } catch (IOException e) {
            Log.e(STREAM_TAG, e.toString());
            Toast.makeText(getApplicationContext(), "Failed closing output stream!", Toast.LENGTH_LONG).show();
        }
    }

    private void setBtnStartEnabled() {
        btnStart.setEnabled(selectedFingerprint != null);
    }

    private void setBtnExportEnabled() {
        btnExport.setEnabled(
                Objects.requireNonNull(fingerprintFileLocations.tmpFingerprintsDir.listFiles()).length != 0
                && recordingFingerprint == null
        );
    }

    private void setBtnSettingsEnabled() {
        btnSettings.setEnabled(recordingFingerprint == null);
    }

    private void exportFingerprints() {
        // disable buttons while exporting
        btnStart.setEnabled(false);
        btnExport.setEnabled(false);
        btnSettings.setEnabled(false);

        fingerprintFileLocations.export();

        Toast.makeText(getApplicationContext(), "Fingerprints exported", Toast.LENGTH_LONG).show();

        // conditionally enable buttons again
        setBtnStartEnabled();
        setBtnExportEnabled();
        setBtnSettingsEnabled();
    }

    private void appendToOutputStream(InputStream in, OutputStream out) {
        try {
            // Transfer bytes from in to out
            byte[] buf = new byte[1024];
            int len;
            while ((len = in.read(buf)) > 0) {
                out.write(buf, 0, len);
            }
        } catch (IOException e) {
            Log.e(STREAM_TAG, e.toString());
            Toast.makeText(getApplicationContext(), "Failed while exporting!", Toast.LENGTH_LONG).show();
        }
    }

    private String toMACWithDots(String mac) {
        return String.format(Locale.US, "%s:%s:%s:%s:%s:%s",
                mac.substring(0, 2),
                mac.substring(2, 4),
                mac.substring(4, 6),
                mac.substring(6, 8),
                mac.substring(8, 10),
                mac.substring(10, 12));
    }

    private void recoverTmpFingerprints() {
        // load temporary recordings
        File[] files = fingerprintFileLocations.tmpFingerprintsDir.listFiles(tmpFingerprintFileFilter);
        if (files != null) {
            // open files
            for (File fpFile : files) {
                try {
                    // open input stream
                    InputStream in = getContentResolver().openInputStream(Uri.fromFile(fpFile));

                    // parse header and contents
                    FingerprintFileParser fingerprintFileParser = new FingerprintFileParser(
                            in,
                            FingerprintFileParser.WhatToParse.HEADER_AND_DATA);
                    ArrayList<FingerprintRecordings.Recording> recordingsInFile = fingerprintFileParser.parse();

                    //close input stream
                    in.close();

                    // check if fingerprint file contains only one fingerprint
                    if (recordingsInFile.size() == 1){
                        // file is good
                        FingerprintRecordings.Recording r = recordingsInFile.get(0);
                        r.setId(UUID.fromString(fpFile.getName().replace(MainActivity.FINGERPRINTS_TMP_EXTENSION, "")));
                        // ask if it should be recovered
                        AlertDialog.Builder builder = new AlertDialog.Builder(this);
                        builder
                                .setTitle(R.string.recover_temporary_fingerprint_title)
                                .setMessage(String.format(Locale.getDefault(),
                                        "%s %s?", getString(R.string.recover_temporary_fingerprint_msg), r.fingerprintName))
                                .setNegativeButton(R.string.dialog_no, null)
                                .setPositiveButton(R.string.dialog_yes, (DialogInterface dialog, int id) -> recoverTmpFingerprint(r, fpFile));

                        AlertDialog dialog = builder.create();
                        dialog.show();
                    } else if (recordingsInFile.size() > 1) {
                        String msg = "Multiple recordings in one file: " + Uri.fromFile(fpFile);
                        Log.e(STREAM_TAG, msg);
                        Toast.makeText(getApplicationContext(), msg, Toast.LENGTH_LONG).show();
                    }
                } catch (FileNotFoundException e) {
                    Log.e(STREAM_TAG, e.toString());
                    Toast.makeText(getApplicationContext(), "Cannot open input file: " + Uri.fromFile(fpFile), Toast.LENGTH_LONG).show();
                } catch (IOException e) {
                    Log.e(STREAM_TAG, e.toString());
                    Toast.makeText(getApplicationContext(), "Cannot parse input file: " + Uri.fromFile(fpFile), Toast.LENGTH_LONG).show();
                }
            }
        }
    }

    private void recoverTmpFingerprint(FingerprintRecordings.Recording recording, File fpFile) {
        Uri src = Uri.fromFile(fpFile);
        Uri dst = Uri.fromFile(fingerprintFileLocations.getFingerprintFile(recording, FINGERPRINTS_EXTENSION));
        copyFile(src, dst);
        if (!fpFile.delete()) {
            Toast.makeText(this, "Temporary fingerprint file could not be deleted!", Toast.LENGTH_LONG).show();
        }
        fingerprintRecordings.addRecording(recording);
        updateRecordedState();
        mapView.invalidate();
    }

    private void resetSensorStatistics() {
        loadCounterWifi.set(0);
        loadCounterWifiRTT.set(0);
        loadCounterBeacon.set(0);
        loadCounterGPS.set(0);
        loadCounterUWB.set(0);
    }

    private void updateSensorStatistics() {
        runOnUiThread(() -> {
            WiFi wifiSensor = sensorManager.getSensor(WiFi.class);
            long wifiScanResultCnt = (wifiSensor == null) ? 0 : wifiSensor.getScanResultCount();

            final TextView txtWifi = (TextView) findViewById(R.id.txtEvtCntWifi);
            txtWifi.setText(String.format(Locale.getDefault(),
                    "%s | %d", makeStatusString(loadCounterWifi.get()), wifiScanResultCnt));
            final TextView txtWifiRTT = (TextView) findViewById(R.id.txtEvtCntWifiRTT);
            txtWifiRTT.setText(makeStatusString(loadCounterWifiRTT.get()));
            final TextView txtBeacon = (TextView) findViewById(R.id.txtEvtCntBeacon);
            txtBeacon.setText(makeStatusString(loadCounterBeacon.get()));
            final TextView txtGPS = (TextView) findViewById(R.id.txtEvtCntGPS);

            txtGPS.setText(makeStatusString(loadCounterGPS.get()));
            final TextView txtUWB = (TextView) findViewById(R.id.txtEvtCntUWB);
            DecawaveUWB sensorUWB = sensorManager.getSensor(DecawaveUWB.class);
            if(sensorUWB != null) {
                if(sensorUWB.isConnectedToTag()) {
                    txtUWB.setText(makeStatusString(loadCounterUWB.get()));
                } else {
                    txtUWB.setText(sensorUWB.isCurrentlyConnecting() ? "⌛" : "✖");
                }
            }
        });
    }

    private String makeStatusString(long evtCnt) {
        return (evtCnt == 0) ? "-" : Long.toString(evtCnt);
    }

    private void showMap() {
        XMLMapParser parser = new XMLMapParser(this);
        try {
            currentMap = parser.parse(getContentResolver().openInputStream(
                    Uri.fromFile(new File(getExternalFilesDir(null), MAP_URI))));
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        }

        if (currentMap != null) {
            currentMap.setSerializer(new MapSeenSerializer(getApplicationContext()));

            // load recording states from fingerprints
            File[] files = fingerprintFileLocations.tmpFingerprintsDir.listFiles(fingerprintFileFilter);
            if (files != null) {
                // add recordings to internal structure
                for (File fpFile : files) {
                    try {
                        FingerprintFileParser fingerprintFileParser = new FingerprintFileParser(
                                getContentResolver().openInputStream(Uri.fromFile(fpFile)),
                                FingerprintFileParser.WhatToParse.ONLY_HEADER);
                        ArrayList<FingerprintRecordings.Recording> recordingsInFile = fingerprintFileParser.parse();
                        if (recordingsInFile.size() > 1){
                            throw new RuntimeException("A temporary recording file should only have one recording");
                        }
                        for (FingerprintRecordings.Recording r : recordingsInFile) {
                            // set recording file of recording since it already exists
                            r.setId(UUID.fromString(fpFile.getName().replace(MainActivity.FINGERPRINTS_EXTENSION, "")));
                            fingerprintRecordings.addRecording(r);
                        }
                    } catch (FileNotFoundException e) {
                        Log.e(STREAM_TAG, e.toString());
                        Toast.makeText(getApplicationContext(), "Cannot open input file: " + Uri.fromFile(fpFile), Toast.LENGTH_LONG).show();
                    } catch (IOException e) {
                        Log.e(STREAM_TAG, e.toString());
                        Toast.makeText(getApplicationContext(), "Cannot parse input file: " + Uri.fromFile(fpFile), Toast.LENGTH_LONG).show();
                    } catch (RuntimeException e) {
                        Log.e(STREAM_TAG, e.toString());
                        Toast.makeText(getApplicationContext(), "Multiple recordings in one file: " + Uri.fromFile(fpFile), Toast.LENGTH_LONG).show();
                    }
                }

                updateRecordedState();
            }
        }

        mapView.setMap(currentMap);
        updateFloorNames();

        String floorName = mPrefs.getString(MAP_PREFERENCES_FLOOR, null);
        if (floorName != null) {
            mapView.selectFloor(floorName);
            Spinner spinnerFloor = findViewById(R.id.spinner_selectFloor);
            int position = mFloorNameAdapter.getPosition(floorName);
            spinnerFloor.setSelection(position);
        }
    }

    private void updateFloorNames() {
        if (currentMap != null) {
            mFloorNameAdapter.clear();
            currentMap.getFloors().keySet().stream().sorted().forEach(s -> mFloorNameAdapter.add(s));
        }
    }

    private void updateRecordedState() {
        if (currentMap == null) { return; }

        for (Floor floor : currentMap.getFloors().values()) {
            for (Fingerprint fp : floor.getFingerprints().values()) {
                if (fingerprintRecordings.contains(fp)) {
                    fp.recorded = true;
                }
            }
        }
    }

    protected void setupSensors() {
        try {
            sensorManager.stop(this);
        } catch (Exception e) {
            e.printStackTrace();
        }

        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(this);
        Set<String> activeSensors = preferences.getStringSet("prefActiveSensors", new HashSet<String>());

        SensorManager.Config config = new SensorManager.Config();
        config.hasPhone = activeSensors.contains("PHONE");
        config.hasGPS = activeSensors.contains("GPS");
        config.hasWifi = activeSensors.contains("WIFI");
        config.hasWifiRTT = activeSensors.contains("WIFIRTTSCAN");
        config.hasBluetooth = activeSensors.contains("BLUETOOTH");
        config.hasDecawaveUWB = activeSensors.contains("DECAWAVE_UWB");
        config.hasStepDetector = activeSensors.contains("STEP_DETECTOR");
        config.hasHeadingChange = activeSensors.contains("HEADING_CHANGE");

        config.decawaveUWBTagMacAddress = preferences.getString("prefDecawaveUWBTagMacAddress", "");
        config.wifiScanIntervalMSec = Long.parseLong(preferences.getString("prefWifiScanIntervalMSec", Long.toString(DEFAULT_WIFI_SCAN_INTERVAL)));
        config.ftmRangingIntervalMSec = Long.parseLong(preferences.getString("prefFtmRangingIntervalMSec", Long.toString(DEFAULT_WIFI_SCAN_INTERVAL)));

        try {
            sensorManager.configure(this, config);
            sensorManager.start(this);
        } catch (Exception e) {
            e.printStackTrace();
            //TODO: ui feedback?
        }
    }

    private void copyFile(Uri src, Uri dst) {
        try (InputStream in = getContentResolver().openInputStream(src)) {
            try (OutputStream out = getContentResolver().openOutputStream(dst, "wt")) {
                // Transfer bytes from in to out
                byte[] buf = new byte[1024];
                int len;
                try {
                    while ((len = in.read(buf)) > 0) {
                        out.write(buf, 0, len);
                    }
                } catch (IOException e) {
                    Log.e(STREAM_TAG, e.toString());
                    Toast.makeText(this, "Cannot copy from input to output file!", Toast.LENGTH_LONG).show();
                }
            } catch (FileNotFoundException e) {
                Log.e(STREAM_TAG, e.toString());
                Toast.makeText(this, "Cannot open output file: " + dst, Toast.LENGTH_LONG).show();
            } catch (IOException e) {
                Log.e(STREAM_TAG, e.toString());
                Toast.makeText(this, "Cannot close output file: " + dst, Toast.LENGTH_LONG).show();
            }
        } catch (FileNotFoundException e) {
            Log.e(STREAM_TAG, e.toString());
            Toast.makeText(this, "Cannot open input file: " + src, Toast.LENGTH_LONG).show();
        } catch (IOException e) {
            Log.e(STREAM_TAG, e.toString());
            Toast.makeText(this, "Cannot close input file: " + src, Toast.LENGTH_LONG).show();
        }
    }
}