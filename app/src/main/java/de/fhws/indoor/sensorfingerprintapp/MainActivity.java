package de.fhws.indoor.sensorfingerprintapp;

import androidx.appcompat.app.AppCompatActivity;
import androidx.preference.PreferenceManager;

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
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.atomic.AtomicLong;

import de.fhws.indoor.libsmartphoneindoormap.model.Fingerprint;
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
import de.fhws.indoor.libsmartphonesensors.sensors.DecawaveUWB;
import de.fhws.indoor.libsmartphonesensors.sensors.WiFi;
import de.fhws.sensorfingerprintapp.R;

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
    private static final long DEFAULT_WIFI_SCAN_INTERVAL = (Build.VERSION.SDK_INT == 28 ? 30 : 1);

    private MapView mapView = null;
    private MapView.ViewConfig mapViewConfig = new MapView.ViewConfig();
    private IMapEventListener mapEventListener = null;
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

    private File tmpFingerprintsDir = null;
    private File tmpFingerprintsFile = null;
    private long tmpFingerprintCount = 0;
    private OutputStream tmpFingerprintsOutputStream = null;
    private Fingerprint recordingFingerprint = null;

    private FilenameFilter tmpFingerprintFileFilter = new FilenameFilter() {
        @Override
        public boolean accept(File file, String s) {
            return s.endsWith(FINGERPRINTS_TMP_EXTENSION);
        }
    };

    private FilenameFilter fingerprintFileFilter = new FilenameFilter() {
        @Override
        public boolean accept(File file, String s) {
            return s.endsWith(FINGERPRINTS_EXTENSION);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // create output directory if not exists
        tmpFingerprintsDir = new File(getExternalFilesDir(null), FINGERPRINTS_TMP_DIR);
        if (!tmpFingerprintsDir.isDirectory()) {
            if (!tmpFingerprintsDir.mkdir()) {
                String msg = "Could not create output directory";
                Log.e(STREAM_TAG, msg);
                Toast.makeText(getApplicationContext(), msg, Toast.LENGTH_LONG).show();
            }
        }

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

        mapEventListener = new IMapEventListener() {
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
        };
        mapView.addEventListener(mapEventListener);

        // setup settings view
        btnSettings = findViewById(R.id.btnSettings);
        btnSettings.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(getApplicationContext(), SettingsActivity.class));
            }
        });

        mFloorNameAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, new ArrayList<>());

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
            if(id == SensorType.WIFI) { loadCounterWifi.incrementAndGet(); }
            if(id == SensorType.WIFIRTT) { loadCounterWifiRTT.incrementAndGet(); }
            if(id == SensorType.IBEACON) { loadCounterBeacon.incrementAndGet(); }
            if(id == SensorType.GPS) { loadCounterGPS.incrementAndGet(); }
            if(id == SensorType.DECAWAVE_UWB) { loadCounterUWB.incrementAndGet(); }
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
            if (tmpFingerprintsOutputStream == null) { return; }

            switch (id) {
                case IBEACON:
                    try {
                        String[] splitted = csv.split(";");
                        tmpFingerprintsOutputStream.write(
                                String.format(Locale.US,
                                        "%d %s %s\n",
                                        timestamp,
                                        toMACWithDots(splitted[0]),
                                        splitted[1]).getBytes(StandardCharsets.UTF_8));
                        tmpFingerprintCount++;
                    } catch (IOException e) {
                        Log.e(STREAM_TAG, e.toString());
                    }
                    break;

                default:
                    break;
            }
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
    }

    @Override
    protected void onStop() {
        super.onStop();
        try {
            sensorManager.stop(this);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void startRecording() {
        assert tmpFingerprintsDir != null;
        assert selectedFingerprint != null;
        assert tmpFingerprintsFile == null;
        assert tmpFingerprintsOutputStream == null;
        try {
            tmpFingerprintsFile = new File(tmpFingerprintsDir,
                    selectedFingerprint.position.toString() + FINGERPRINTS_TMP_EXTENSION);
            tmpFingerprintsOutputStream = getContentResolver().openOutputStream(Uri.fromFile(tmpFingerprintsFile), "wt");
            tmpFingerprintCount = 0;

            setupSensors();
            btnStart.setText(R.string.stop_button_text);
            recordingFingerprint = selectedFingerprint;

            setBtnExportEnabled();
            setBtnSettingsEnabled();
        } catch (FileNotFoundException e) {
            Log.e(STREAM_TAG, e.toString());
            Toast.makeText(getApplicationContext(), "Failed opening output stream!", Toast.LENGTH_LONG).show();
        }
    }

    private void stopRecording() {
        assert tmpFingerprintsDir != null;
        assert recordingFingerprint != null;
        assert tmpFingerprintsFile != null;
        assert tmpFingerprintsOutputStream != null;
        try {
            sensorManager.stop(this);
        } catch (Exception e) {
            e.printStackTrace();
        }
        try {
            // close output stream
            tmpFingerprintsOutputStream.close();
            tmpFingerprintsOutputStream = null;

            // open output stream as input stream
            File outFile = new File(tmpFingerprintsDir, recordingFingerprint.position.toString() + FINGERPRINTS_EXTENSION);
            try (InputStream in = getContentResolver().openInputStream(Uri.fromFile(tmpFingerprintsFile))){
                try (OutputStream out = getContentResolver().openOutputStream(Uri.fromFile(outFile), "wt")) {
                    // write header
                    out.write(String.format(Locale.US,
                            "[fingerprint]\n" +
                                    "pos: %s\n" +
                                    "num: %d\n",
                            recordingFingerprint.position.toString(),
                            tmpFingerprintCount).getBytes(StandardCharsets.UTF_8));

                    // append content
                    appendToOutputStream(in, out);
                } catch (FileNotFoundException e) {
                    Log.e(STREAM_TAG, e.toString());
                    Toast.makeText(getApplicationContext(), "Could not open output file!", Toast.LENGTH_LONG).show();
                } catch (IOException e) {
                    Log.e(STREAM_TAG, e.toString());
                    Toast.makeText(getApplicationContext(), "Could not write header to output file!", Toast.LENGTH_LONG).show();
                }
            } catch (FileNotFoundException e) {
                Log.e(STREAM_TAG, e.toString());
                Toast.makeText(getApplicationContext(), "Could not open temporary input file!", Toast.LENGTH_LONG).show();
            }

            if (!tmpFingerprintsFile.delete()) {
                Toast.makeText(getApplicationContext(), "Could not delete temporary fingerprint file!", Toast.LENGTH_LONG).show();
            }

            recordingFingerprint.recorded = true;
            mapView.invalidate();

            tmpFingerprintsFile = null;
            recordingFingerprint = null;

            btnStart.setText(R.string.start_button_text);
            setBtnExportEnabled();
            setBtnSettingsEnabled();
        } catch (IOException e) {
            Log.e(STREAM_TAG, e.toString());
            Toast.makeText(getApplicationContext(), "Failed closing output stream!", Toast.LENGTH_LONG).show();
        }
    }

    private void setBtnStartEnabled() {
        btnStart.setEnabled(selectedFingerprint != null);
    }

    private void setBtnExportEnabled() {
        btnExport.setEnabled(Objects.requireNonNull(tmpFingerprintsDir.listFiles()).length != 0 && recordingFingerprint == null);
    }

    private void setBtnSettingsEnabled() {
        btnSettings.setEnabled(recordingFingerprint == null);
    }

    private void exportFingerprints() {
        // disable buttons while exporting
        btnStart.setEnabled(false);
        btnExport.setEnabled(false);
        btnSettings.setEnabled(false);

        File[] files = tmpFingerprintsDir.listFiles();
        if (files != null) {
            File outputFile = new File(getExternalFilesDir(null), FINGERPRINTS_URI);
            try (OutputStream out = getContentResolver().openOutputStream(Uri.fromFile(outputFile), "wt")) {
                for (File fpFile : files) {
                    try (InputStream in = getContentResolver().openInputStream(Uri.fromFile(fpFile))) {
                        appendToOutputStream(in, out);
                    } catch (FileNotFoundException e) {
                        Log.e(STREAM_TAG, e.toString());
                        Toast.makeText(getApplicationContext(), "Could not open input file!", Toast.LENGTH_LONG).show();
                    }
                }
            } catch (FileNotFoundException e) {
                Log.e(STREAM_TAG, e.toString());
                Toast.makeText(getApplicationContext(), "Could not open output file!", Toast.LENGTH_LONG).show();
            } catch (IOException e) {
                Log.e(STREAM_TAG, e.toString());
                Toast.makeText(getApplicationContext(), "Could not close output file!", Toast.LENGTH_LONG).show();
            }
        }

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
            txtWifi.setText(makeStatusString(loadCounterWifi.get()) + " | " + wifiScanResultCnt);
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
            // TODO: may also recover .dat.tmp files here?
            // load recording states
            File[] files = tmpFingerprintsDir.listFiles(fingerprintFileFilter);
            if (files != null) {
                for (Floor floor : currentMap.getFloors().values()) {
                    for (Fingerprint f : floor.getFingerprints().values()) {
                        for (File fpFile : files) {
                            if (fpFile.getName().equals(f.position.toString() + FINGERPRINTS_EXTENSION)) {
                                f.recorded = true;
                            }
                        }
                    }
                }
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

    protected void setupSensors() {
        try {
            sensorManager.stop(this);
        } catch (Exception e) {
            e.printStackTrace();
        }

        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(this);

        SensorManager.Config config = new SensorManager.Config();
        config.hasWifi = true;
        config.hasWifiRTT = true;
        config.hasBluetooth = true;
        config.hasDecawaveUWB = true;
        config.decawaveUWBTagMacAddress = preferences.getString("prefDecawaveUWBTagMacAddress", "");
        config.wifiScanIntervalMSec = Long.parseLong(preferences.getString("prefWifiScanIntervalMSec", Long.toString(DEFAULT_WIFI_SCAN_INTERVAL))) * 1000;

        try {
            sensorManager.configure(this, config);
            sensorManager.start(this);
        } catch (Exception e) {
            e.printStackTrace();
            //TODO: ui feedback?
        }
    }
}