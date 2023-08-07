package de.fhws.indoor.sensorfingerprintapp;

import android.app.AlertDialog;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.net.wifi.rtt.RangingRequest;
import android.os.Build;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.Environment;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.MediaStore;
import android.util.Log;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;
import androidx.preference.PreferenceManager;

import com.github.mikephil.charting.charts.BarChart;
import com.github.mikephil.charting.components.AxisBase;
import com.github.mikephil.charting.components.XAxis;
import com.github.mikephil.charting.data.Entry;
import com.github.mikephil.charting.formatter.ValueFormatter;
import com.github.mikephil.charting.highlight.Highlight;
import com.github.mikephil.charting.listener.OnChartValueSelectedListener;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.time.temporal.ValueRange;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import de.fhws.indoor.libsmartphoneindoormap.model.Fingerprint;
import de.fhws.indoor.libsmartphoneindoormap.model.FingerprintPath;
import de.fhws.indoor.libsmartphoneindoormap.model.FingerprintPosition;
import de.fhws.indoor.libsmartphoneindoormap.model.Floor;
import de.fhws.indoor.libsmartphoneindoormap.model.Map;
import de.fhws.indoor.libsmartphoneindoormap.model.Vec2;
import de.fhws.indoor.libsmartphoneindoormap.model.Vec3;
import de.fhws.indoor.libsmartphoneindoormap.parser.MapSeenSerializer;
import de.fhws.indoor.libsmartphoneindoormap.parser.XMLMapParser;
import de.fhws.indoor.libsmartphoneindoormap.renderer.ColorScheme;
import de.fhws.indoor.libsmartphoneindoormap.renderer.IMapEventListener;
import de.fhws.indoor.libsmartphoneindoormap.renderer.MapView;
import de.fhws.indoor.libsmartphonesensors.SensorDataInterface;
import de.fhws.indoor.libsmartphonesensors.SensorManager;
import de.fhws.indoor.libsmartphonesensors.SensorType;
import de.fhws.indoor.libsmartphonesensors.io.RecordingManager;
import de.fhws.indoor.libsmartphonesensors.io.RecordingSession;
import de.fhws.indoor.libsmartphonesensors.loggers.Logger;
import de.fhws.indoor.libsmartphonesensors.loggers.TimedOrderedLogger;
import de.fhws.indoor.libsmartphonesensors.sensors.DecawaveUWB;
import de.fhws.indoor.libsmartphonesensors.sensors.WiFi;
import de.fhws.indoor.libsmartphonesensors.ui.EventCounterView;
import de.fhws.indoor.libsmartphonesensors.util.permissions.AppCompatMultiPermissionRequester;

/**
 * @author Steffen Kastner
 */
public class MainActivity extends AppCompatActivity {
    public static final String STREAM_TAG = "FileStream";
    public static final String MAP_URI = "map.xml";
    public static final String FINGERPRINTS_URI = "fingerprints.dat";
    public static final String FINGERPRINTS_TMP_DIR = "fingerprints";
    public static final String FINGERPRINTS_TMP_EXTENSION = ".tmp";
    public static final String FINGERPRINTS_EXTENSION = ".dat";
    public static final String MAP_PREFERENCES = "MAP_PREFERENCES";
    public static final String MAP_PREFERENCES_FLOOR = "FloorName";
    public static final String FILE_PROVIDER_AUTHORITY = "de.fhws.indoor.sensorfingerprintapp.fileprovider";
    private static final long DEFAULT_WIFI_SCAN_INTERVAL = (Build.VERSION.SDK_INT == 28 ? 30000 : 1);
    private static final long DEFAULT_FINGERPRINT_DURATION = 0; // infinite

    private AppCompatMultiPermissionRequester permissionRequester = null;
    private RecordingManager recordingManager;

    private class FingerprintFileLocations {
        private final File fingerprintsFile;
        private final File tmpFingerprintsDir;

        private final Logger logger = new TimedOrderedLogger(getApplicationContext());

        public FingerprintFileLocations(Context context, String uri, String tmpDir) {
            // file where exported data will be
            fingerprintsFile = new File(getFilesDir(), uri);

            // create output directory if not exists
            tmpFingerprintsDir = new File(getFilesDir(), tmpDir);
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

            // check if that fingerprint was already recorded
            FingerprintRecordings.Recording recording = fingerprintRecordings.getRecording(selectedFingerprint);
            if (recording == null) {
                recording = new FingerprintRecordings.Recording(selectedFingerprint, null);
                fingerprintRecordings.addRecording(recording);
            }

            recordingManager.startNewNamedSession(recording.getId().toString() + FINGERPRINTS_TMP_EXTENSION);
            writeHeader(recordingManager.getCurrentSession().stream(), selectedFingerprint);
            logger.start(recordingManager.getCurrentSession(), new Logger.FileMetadata("Unknown", "SensorFingerprintApp: " + android.os.Build.MODEL));
        }

        public void stopRecording(Fingerprint recordingFingerprint) throws IOException {
            assert recordingFingerprint != null;

            // stop logger
            logger.stop();
            RecordingSession prevSession = recordingManager.getCurrentSession();
            prevSession.close();

            // get recording from database
            FingerprintRecordings.Recording recording = fingerprintRecordings.getRecording(recordingFingerprint);
            assert recording != null;

            // open output stream as input stream
            File outFile = getFingerprintFile(recording, FINGERPRINTS_EXTENSION);
            if(!prevSession.getFile().renameTo(outFile)) {
                Toast.makeText(getApplicationContext(), "Could not rename to finalized fingerprint file", Toast.LENGTH_LONG)
                        .show();
            }
        }

        public void write(SensorType id, long timestamp, String csv) {
            logger.addCSV(id, timestamp, csv);
        }

        private Uri getUri(String displayName, String mimeType) throws IOException {
            final ContentValues values = new ContentValues();
            values.put(MediaStore.MediaColumns.DISPLAY_NAME, displayName);
            values.put(MediaStore.MediaColumns.MIME_TYPE, mimeType);
            values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOCUMENTS + File.separator + "SensorFingerprintApp");

            final ContentResolver resolver = getContentResolver();
            Uri uri = null;

            final Uri contentUri = MediaStore.Files.getContentUri("external");
            uri = resolver.insert(contentUri, values);

            if (uri == null)
                throw new IOException("Failed to create new MediaStore record.");

            return uri;
        }

        public void export() {
            File[] files = tmpFingerprintsDir.listFiles();
            if (files != null) {
                Uri outputDoc = null;
                try {
                    outputDoc = getUri(FINGERPRINTS_URI, "text/*");
                } catch (IOException e) {
                    Log.e(STREAM_TAG, e.toString());
                    Toast.makeText(getApplicationContext(), "Cannot create output file!", Toast.LENGTH_LONG).show();
                    return;
                }

                try (OutputStream out = getContentResolver().openOutputStream(outputDoc /*Uri.fromFile(fingerprintsFile)*/, "wt")) {
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
                    return;
                } catch (IOException e) {
                    Log.e(STREAM_TAG, e.toString());
                    Toast.makeText(getApplicationContext(), "Cannot close output file!", Toast.LENGTH_LONG).show();
                    return;
                }
                //Uri path = FileProvider.getUriForFile(MainActivity.this, FILE_PROVIDER_AUTHORITY, fingerprintsFile);
                Intent i = new Intent(Intent.ACTION_SEND);
                i.putExtra(Intent.EXTRA_TEXT, "Share Recording");
                i.putExtra(Intent.EXTRA_STREAM, outputDoc);
                i.setType("text/plain");
                List<ResolveInfo> resInfoList = MainActivity.this.getPackageManager().queryIntentActivities(i, PackageManager.MATCH_DEFAULT_ONLY);
                for (ResolveInfo resolveInfo : resInfoList) {
                    String packageName = resolveInfo.activityInfo.packageName;
                    MainActivity.this.grantUriPermission(packageName, outputDoc, Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
                }
                MainActivity.this.startActivity(Intent.createChooser(i, "Share Recording"));
            }
        }

        private File getFingerprintFile(FingerprintRecordings.Recording recording, String extension) {
            return new File(tmpFingerprintsDir, recording.getId().toString() + extension);
        }


        // write header
        private void writeHeader(OutputStream out, Fingerprint fingerprint) {
            try {
                FingerprintFileSerializer.writeHeader(out, fingerprint);
            } catch (IOException e) {
                Log.e(STREAM_TAG, e.toString());
                Toast.makeText(getApplicationContext(), "Cannot write header to output file!", Toast.LENGTH_LONG).show();
            }
        }
    }

    private MapView mapView = null;
    private final MapView.ViewConfig mapViewConfig = new MapView.ViewConfig();
    public static Map currentMap = null;

    private final ArrayList<FingerprintPosition> selectedFingerprints = new ArrayList<>();

    private SensorManager sensorManager;
    // sensorManager status
    private final Timer sensorManagerStatisticsTimer = new Timer();
    private TimerTask sensorManagerStatisticsTask;
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
    private FormattedBarData barChartData = null;
    private SensorType barChartSensorType = SensorType.IBEACON;
    private String barChartSensorId = null;
    private final StatisticsData statisticsData = new StatisticsData();
    private final AtomicBoolean statisticsVisible = new AtomicBoolean(false);

    private FingerprintFileLocations fingerprintFileLocations;
    private final FingerprintRecordings fingerprintRecordings = new FingerprintRecordings();
    private Fingerprint recordingFingerprint = null;
    private TimerTask timeoutTask = null;

    private final Timer timer = new Timer(true);

    private final FilenameFilter tmpFingerprintFileFilter = (file, s) -> s.endsWith(FINGERPRINTS_TMP_EXTENSION);
    private final FilenameFilter fingerprintFileFilter = (file, s) -> s.endsWith(FINGERPRINTS_EXTENSION);

    private Vibrator vibrator = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);

        permissionRequester = new AppCompatMultiPermissionRequester(this);
        recordingManager = new RecordingManager(new File(getFilesDir(), FINGERPRINTS_TMP_DIR), FILE_PROVIDER_AUTHORITY);
        
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

        // configure event counter view
        mapViewConfig.showFingerprint = true;
        EventCounterView eventCounterView = findViewById(R.id.event_counter_view);
        eventCounterView.setClickable(true);
        eventCounterView.setActiveDataChangedCallback(activeData -> {
            mapViewConfig.showBluetooth = activeData.ble;
            mapViewConfig.showUWB = activeData.uwb;
            if(mapViewConfig.showWiFi != activeData.wifi) { activeData.ftm = activeData.wifi; }
            else if(mapViewConfig.showWiFi != activeData.ftm) { activeData.wifi = activeData.ftm; }
            mapViewConfig.showWiFi = activeData.wifi;
            activeData.gps = true;
            mapView.setViewConfig(mapViewConfig);
        });
        eventCounterView.updateActiveData(true, activeData -> {
            activeData.uwb = false; activeData.ble = false; activeData.wifi = false; activeData.ftm = false; activeData.gps = true;
        });

        mapView.addEventListener(new IMapEventListener() {
            @Override
            public void onTap(Vec2 mapPosition) {
                Fingerprint fp = mapView.findNearestFingerprint(mapPosition, 1.0f);

                int lastIdx = selectedFingerprints.size()-1;

                // touched into nothingness
                if (fp == null) {
                    if (!selectedFingerprints.isEmpty()) {
                        Fingerprint last = selectedFingerprints.get(lastIdx);
                        selectedFingerprints.remove(lastIdx);

                        // check if removed element is still in list somewhere
                        if (!selectedFingerprints.contains(last)) {
                            last.selected = false;
                            mapView.invalidate();
                        }
                    }
                } else if (!selectedFingerprints.isEmpty() && fp == selectedFingerprints.get(lastIdx)) {
                    Toast.makeText(getApplicationContext(), "Cannot add same FP to the end again", Toast.LENGTH_LONG).show();
                } else {
                    if (selectedFingerprints.isEmpty() && fp instanceof FingerprintPath) {
                        Toast.makeText(getApplicationContext(), "Cannot add a path to the selected path", Toast.LENGTH_LONG).show();
                    } else if (fp instanceof FingerprintPosition){
                        selectedFingerprints.add((FingerprintPosition) fp);
                        fp.selected = true;
                        mapView.invalidate();
                    }
                }

                if (selectedFingerprints.isEmpty()) {
                    mapView.setHighlightFingerprint(null);
                } else if (selectedFingerprints.size() == 1) {
                    Fingerprint selectedFp = selectedFingerprints.get(0);
                    mapView.setHighlightFingerprint(selectedFp);
                } else {
                    Fingerprint first = selectedFingerprints.get(0);
                    FingerprintPath fpPath = new FingerprintPath(first.floorIdx, first.floorName, true, false, selectedFingerprints);
                    mapView.setHighlightFingerprint(fpPath);
                }

                setBtnStartEnabled();
            }

            @Override
            public void onLongPress(Vec2 mapPosition) {
                FingerprintPosition fp = new FingerprintPosition("rfp_", mapView.getCurrentFloor().getIdx(), mapView.getCurrentFloor().getName(), false, false, new Vec3(mapPosition.x, mapPosition.y, 1.3f));
                openCreateFingerprintDialog(fp);
            }

            private void openCreateFingerprintDialog(FingerprintPosition fp) {
                DialogCreateFingerprint dialogCreateFingerprint = new DialogCreateFingerprint(fp, currentMap);
                DialogInterface.OnClickListener onClickListener = new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int i) {
                        fp.selected = true;

                        Floor fpFloor = null;
                        try {
                            fpFloor = currentMap.getFloors().get(fp.floorIdx);
                        } catch (ArrayIndexOutOfBoundsException exception) {
                            Toast.makeText(getApplicationContext(), "Floor of created fingerprint does not exists!", Toast.LENGTH_LONG).show();
                            openCreateFingerprintDialog(fp);
                            return;
                        }

                        if (fpFloor.getFingerprints().containsKey(fp.name)) {
                            Toast.makeText(getApplicationContext(), "Floor already contains a fingerprint with that name!", Toast.LENGTH_LONG).show();
                            openCreateFingerprintDialog(fp);
                            return;
                        }

                        fpFloor.addFingerprint(fp);
                        mapView.invalidate();

                        selectedFingerprints.add(fp);
                        setBtnStartEnabled();
                    }
                };
                dialogCreateFingerprint.setOnCreateListener(onClickListener);
                dialogCreateFingerprint.show(getSupportFragmentManager(), "CreateFingerprintDialog");
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
                ed.putInt(MAP_PREFERENCES_FLOOR, i);
                ed.apply();

                mapView.selectFloor(i);
            }

            @Override
            public void onNothingSelected(AdapterView<?> adapterView) {}
        });

        // setup custom back button behaviour
        OnBackPressedCallback backPressedCallback = new OnBackPressedCallback(false) {
            @Override
            public void handleOnBackPressed() {
                barChartSensorId = null;
                this.setEnabled(false);
            }
        };
        getOnBackPressedDispatcher().addCallback(this, backPressedCallback);

        ///////////////////////////
        // setup statistics view //
        ///////////////////////////

        // setup sensor type spinner
        Spinner barChartSpinner = findViewById(R.id.spinner_sensorType);
        ArrayList<String> barChartSpinnerItems = new ArrayList<>();
        barChartSpinnerItems.add(SensorType.IBEACON.toString());
        barChartSpinnerItems.add(SensorType.WIFI.toString());
        barChartSpinnerItems.add(SensorType.WIFIRTT.toString());
        barChartSpinner.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, barChartSpinnerItems));
        barChartSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> adapterView, View view, int i, long l) {
                String sensorTypeString = (String) adapterView.getItemAtPosition(i);
                if (sensorTypeString.equals(SensorType.IBEACON.toString())) {
                    barChartSensorType = SensorType.IBEACON;
                } else if (sensorTypeString.equals(SensorType.WIFI.toString())) {
                    barChartSensorType = SensorType.WIFI;
                } else if (sensorTypeString.equals(SensorType.WIFIRTT.toString())) {
                    barChartSensorType = SensorType.WIFIRTT;
                }

                // deselect selected sensor
                barChartSensorId = null;
            }

            @Override
            public void onNothingSelected(AdapterView<?> adapterView) {}
        });

        // setup bar chart
        btnStatistics = findViewById(R.id.btnStatistics);
        btnStatistics.setOnClickListener(view -> {
            if (layoutStatistics.getVisibility() == View.VISIBLE) {
                layoutStatistics.setVisibility(View.GONE);
                statisticsVisible.set(false);
            } else {
                layoutStatistics.setVisibility(View.VISIBLE);
                statisticsVisible.set(true);
            }
        });
        barChartStatistics = findViewById(R.id.BarChartStatistics);
        barChartStatistics.getLegend().setTextColor(getResources().getColor(R.color.text_black, getTheme()));
        barChartStatistics.getXAxis().setTextColor(getResources().getColor(R.color.text_black, getTheme()));
        barChartStatistics.getXAxis().setPosition(XAxis.XAxisPosition.BOTTOM);
        barChartStatistics.getXAxis().setLabelRotationAngle(90);
        barChartStatistics.getAxisLeft().setTextColor(getResources().getColor(R.color.text_black, getTheme()));

        barChartStatistics.setOnChartValueSelectedListener(new OnChartValueSelectedListener() {
            @Override
            public void onValueSelected(Entry e, Highlight h) {
                if (barChartSensorId == null) {
                    AxisBase xAxis = barChartStatistics.getXAxis();
                    barChartSensorId = barChartData.formatter.getAxisLabel(e.getX(), xAxis);
                    backPressedCallback.setEnabled(true);
                }
            }

            @Override
            public void onNothingSelected() {}
        });

        CheckBox barChartFilterByMap = findViewById(R.id.checkBox_filterByMap);

        timer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                if (statisticsVisible.get()) {
                    StatisticsData.SensorTypeData sensorTypeData = statisticsData.getSensorTypeData(barChartSensorType);
                    if (sensorTypeData != null) {
                        // generate translation map if possible
                        final HashMap<String, String> mac2Name;
                        if (currentMap != null) {
                            mac2Name = new HashMap<>();
                            if(barChartSensorType == SensorType.IBEACON) {
                                currentMap.getBeacons().forEach((mac, beacon) -> {
                                    mac2Name.put(mac.toString(), beacon.name);
                                });
                            } else if(barChartSensorType == SensorType.WIFI || barChartSensorType == SensorType.WIFIRTT) {
                                currentMap.getAccessPoints().forEach((mac, ap) -> mac2Name.put(mac.toString(), ap.name));
                            }
                        } else {
                            mac2Name = null;
                        }

                        // create an additional ValueFormatter to translate String identifier to something human readable if required
                        ValueFormatter translationFormatter = null;
                        if (barChartSensorId != null) {
                             StatisticsData.SensorTypeData.SensorData sensorData = sensorTypeData.getSensorData(barChartSensorId);
                            barChartData = sensorData.getBarData();

                            // translate mac
                            if (mac2Name != null) {
                                barChartData.barData.getDataSetByIndex(0).setLabel(mac2Name.get(barChartSensorId));
                            }
                        } else {
                            if (mac2Name != null) {
                                // get data
                                if (barChartFilterByMap.isChecked()) {
                                    barChartData = sensorTypeData.getBarData(mac2Name.keySet());
                                } else {
                                    barChartData = sensorTypeData.getBarData(null);
                                }

                                // translate data
                                translationFormatter = new ValueFormatter() {
                                    @Override
                                    public String getAxisLabel(float value, AxisBase axis) {
                                        String sensorId = barChartData.formatter.getAxisLabel(value, axis);
                                        return mac2Name.getOrDefault(sensorId, sensorId);
                                    }
                                };
                            } else {
                                barChartData = sensorTypeData.getBarData(null);
                            }
                        }

                        // if it can be translated, translate!
                        ValueFormatter formatter = translationFormatter != null ? translationFormatter : barChartData.formatter;
                        runOnUiThread(() -> {
                            barChartStatistics.getXAxis().setValueFormatter(formatter);
                            barChartStatistics.setData(barChartData.barData);
                            barChartStatistics.setFitBars(true);
                            barChartStatistics.invalidate();
                        });
                    }
                }
            }
        }, 1000, 1000);

        //configure sensorManager
        sensorManager = new SensorManager(new SensorDataInterface() {
            @Override
            public long getStartTimestamp() { return fingerprintFileLocations.logger.getStartTS(); }

            @Override
            public void onData(long timestamp, SensorType sensorId, String csv) {
                if(currentMap == null) { return; }
                String[] splitString = csv.split(";");

                if (recordingFingerprint != null) {
                    fingerprintFileLocations.write(sensorId, timestamp, csv);
                }

                if(sensorId == SensorType.IBEACON) {
                    loadCounterBeacon.incrementAndGet();
                    statisticsData.put(sensorId, splitString[0], Float.parseFloat(splitString[1]));
                    currentMap.setSeenBeacon(csv.substring(0, 12));
                } else if(sensorId == SensorType.WIFI) {
                    loadCounterWifi.incrementAndGet();
                    statisticsData.put(sensorId, splitString[0], Float.parseFloat(splitString[2]));
                    currentMap.setSeenWiFi(csv.substring(0, 12));
                } else if(sensorId == SensorType.WIFIRTT) {
                    loadCounterWifiRTT.incrementAndGet();
                    // ftm dist
                    statisticsData.put(sensorId, splitString[1], Float.parseFloat(splitString[2]) / 1000);
                    // ftm RSSI
                    statisticsData.put(SensorType.WIFI, splitString[1], Float.parseFloat(splitString[4]));
                    currentMap.setSeenFtm(csv.substring(2, 14));
                } else if(sensorId == SensorType.DECAWAVE_UWB) {
                    loadCounterUWB.incrementAndGet();
                    // skip initial 4 (x, y, z, quality) - then take every 3rd
                    for(int i = 4; i < splitString.length; i += 3) {
                        int shortDeviceId = Integer.parseInt(splitString[i]);
                        // shortDeviceId is a uint16
                        if(shortDeviceId >= 0 && shortDeviceId <= 65535) {
                            String shortDeviceIdStr = String.format("%04X", shortDeviceId);
                            currentMap.setSeenUWB(shortDeviceIdStr);
                        }
                    }
                } else if(sensorId == SensorType.GPS) {
                    loadCounterGPS.incrementAndGet();
                }
            }

            @Override
            public OutputStream requestAuxiliaryChannel(String id) throws IOException {
                return null;
            }
        });
    }

    @Override
    protected void onStart() {
        super.onStart();
        showMap();
        if(currentMap == null) {
            startActivity(new Intent(getApplicationContext(), SettingsActivity.class));
        } else {
            selectedFingerprints.clear();
            recordingFingerprint = null;
            setBtnStartEnabled();
            setBtnExportEnabled();
            setupSensors();
            // try to recover temporary fingerprint files that were aborted during recording
            recoverTmpFingerprints();
        }
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
        assert !selectedFingerprints.isEmpty();
        assert timeoutTask == null;

        // reset bottom left sensor statistics table
        resetSensorStatistics();

        // reset histogram statistics
        statisticsData.clear();

        try {
            Fingerprint fromSelectedFingerprints;
            if (selectedFingerprints.size() > 1) {
                FingerprintPosition first = selectedFingerprints.get(0);
                fromSelectedFingerprints = new FingerprintPath(first.floorIdx, first.floorName, true, false, selectedFingerprints);
            } else {
                fromSelectedFingerprints = selectedFingerprints.get(0);
            }

            fingerprintFileLocations.startRecording(fromSelectedFingerprints);

            sensorManager.start(this);
            btnStart.setText(R.string.stop_button_text);
            recordingFingerprint = fromSelectedFingerprints;

            setBtnExportEnabled();
            setBtnSettingsEnabled();

            // schedule timeoutTask to stop recording after fingerprintDuration
            long fingerprintDuration = Long.parseLong(PreferenceManager.getDefaultSharedPreferences(this).getString("prefFingerprintDurationMSec", Long.toString(DEFAULT_FINGERPRINT_DURATION)));
            if (fingerprintDuration != 0) {
                timeoutTask = new TimerTask() {
                    @Override
                    public void run() {
                        runOnUiThread(() -> {
                            stopRecording();
                            vibrator.vibrate(VibrationEffect.createOneShot(200, VibrationEffect.DEFAULT_AMPLITUDE));
                        });
                    }
                };
                timer.schedule(timeoutTask, fingerprintDuration);
            }
        } catch (FileNotFoundException e) {
            Log.e(STREAM_TAG, e.toString());
            e.printStackTrace();
            Toast.makeText(getApplicationContext(), "Failed opening output stream!", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(getApplicationContext(), "Failed to start recording", Toast.LENGTH_LONG).show();
            e.printStackTrace();
        }

        sensorManagerStatisticsTask = new TimerTask() {
            @Override
            public void run() {
                updateSensorStatistics();
            }
        };
        sensorManagerStatisticsTimer.schedule(sensorManagerStatisticsTask, 250, 250);
    }

    private void stopRecording() {
        assert fingerprintFileLocations != null;
        assert recordingFingerprint != null;

        sensorManagerStatisticsTask.cancel();
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
            if (timeoutTask != null) {
                timeoutTask.cancel();
                timeoutTask = null;
            }
        } catch (IOException e) {
            Log.e(STREAM_TAG, e.toString());
            Toast.makeText(getApplicationContext(), "Failed closing output stream!", Toast.LENGTH_LONG).show();
        }

        Toast.makeText(getApplicationContext(), "Recording stopped", Toast.LENGTH_LONG).show();
    }

    private void setBtnStartEnabled() {
        btnStart.setEnabled(!selectedFingerprints.isEmpty());
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
                            FingerprintFileParser.WhatToParse.ONLY_HEADER);
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
                                        "%s %s?\nRecorded at: %s", getString(R.string.recover_temporary_fingerprint_msg), r.getFingerprint().name,
                                        new Date(fpFile.lastModified())))
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
            if(fingerprintFileLocations == null) { return; }

            EventCounterView evtCounterView = findViewById(R.id.event_counter_view);
            evtCounterView.updateCounterData(counterData -> {
                DecawaveUWB sensorUWB = sensorManager.getSensor(DecawaveUWB.class);
                WiFi sensorWifi = sensorManager.getSensor(WiFi.class);
                counterData.wifiEvtCnt = loadCounterWifi.get();
                counterData.wifiScanCnt = (sensorWifi != null) ? sensorWifi.getScanResultCount() : 0;
                counterData.bleEvtCnt = loadCounterBeacon.get();
                counterData.ftmEvtCnt = loadCounterWifiRTT.get();
                counterData.gpsEvtCnt = loadCounterGPS.get();
                counterData.uwbEvtCnt = loadCounterUWB.get();
                counterData.uwbState = EventCounterView.UWBState.from(sensorUWB);
            });

            final TextView txtTotalEvtCount = findViewById(R.id.txtEvtCntTotal);
            txtTotalEvtCount.setText(String.format(
                getResources().getString(R.string.event_count_txt),
                fingerprintFileLocations.logger.getEventCnt()
            ));
            txtTotalEvtCount.setText(fingerprintFileLocations.logger.getEventCnt() + "evts");

            { // update UI timer
                final TextView txtClock = findViewById(R.id.txtClock);
                long now = SystemClock.elapsedRealtimeNanos();
                long milliseconds = (now - fingerprintFileLocations.logger.getStartTS()) / 1000000;
                long minutes = TimeUnit.MILLISECONDS.toMinutes(milliseconds);
                milliseconds -= TimeUnit.MINUTES.toMillis(minutes);
                long seconds = TimeUnit.MILLISECONDS.toSeconds(milliseconds);
                milliseconds -= TimeUnit.SECONDS.toMillis(seconds);
                txtClock.setText(String.format("%02d:%02d.%03d", minutes, seconds, milliseconds));
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
                    Uri.fromFile(new File(getFilesDir(), MAP_URI))));
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

        mapView.setViewConfig(mapViewConfig);
        mapView.setMap(currentMap);
        updateFloorNames();

        int floorIdx = mPrefs.getInt(MAP_PREFERENCES_FLOOR, -1);
        if (floorIdx != -1) {
            mapView.selectFloor(floorIdx);
            Spinner spinnerFloor = findViewById(R.id.spinner_selectFloor);
            spinnerFloor.setSelection(floorIdx);
        }
    }

    private void updateFloorNames() {
        if (currentMap != null) {
            mFloorNameAdapter.clear();
            currentMap.getFloors().stream().map(Floor::getName).sorted().forEach(s -> mFloorNameAdapter.add(s));
        }
    }

    private void updateRecordedState() {
        if (currentMap == null) { return; }

        for (FingerprintRecordings.Recording r : fingerprintRecordings.getRecordings().values()) {
            Fingerprint fp = r.getFingerprint();
            Floor floor = currentMap.getFloors().get(fp.floorIdx);
            if (floor == null) {
                Toast.makeText(getApplicationContext(), "Floor of fingerprint recording not found! Floor name: " + fp.floorName, Toast.LENGTH_LONG).show();
                continue;
            }

            Fingerprint floorFp = floor.getFingerprints().get(fp.name);
            if (floorFp != null) {
                floorFp.recorded = true;
            } else {
                // added fingerprint which is only in recording
                floor.addFingerprint(fp);
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

        config.decawaveUWBTagMacAddress = preferences.getString("prefDecawaveUWBTagMacAddress", "AA:BB:CC:DD:EE:FF");
        config.wifiScanIntervalMSec = Long.parseLong(preferences.getString("prefWifiScanIntervalMSec", Long.toString(DEFAULT_WIFI_SCAN_INTERVAL)));
        config.ftmRangingIntervalMSec = Long.parseLong(preferences.getString("prefFtmRangingIntervalMSec", Long.toString(DEFAULT_WIFI_SCAN_INTERVAL)));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            int burstSize = Integer.parseInt(preferences.getString("prefFtmBurstSize", Integer.toString(RangingRequest.getDefaultRttBurstSize())));
            config.ftmBurstSize = (burstSize != 0) ? burstSize : RangingRequest.getDefaultRttBurstSize();
        }

        try {
            sensorManager.configure(this, config, permissionRequester);
            permissionRequester.launch(() -> {});
        } catch (Exception e) {
            Toast.makeText(this, "Failed to configure SensorManager", Toast.LENGTH_SHORT).show();
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