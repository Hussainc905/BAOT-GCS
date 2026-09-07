package com.example.pixhawkminigcs;

import android.content.SharedPreferences;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.graphics.Color;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import org.osmdroid.config.Configuration;
import org.osmdroid.tileprovider.cachemanager.CacheManager;
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase;
import org.osmdroid.tileprovider.tilesource.TileSourceFactory;
import org.osmdroid.util.BoundingBox;
import org.osmdroid.util.MapTileIndex;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.MapView;
import org.osmdroid.views.overlay.CopyrightOverlay;
import org.osmdroid.views.overlay.Marker;
import org.osmdroid.views.overlay.Polyline;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;
import java.util.List;

public class MainActivity extends AppCompatActivity implements MavlinkClient.Listener {
    private static final String INITIAL_PASSWORD = "223232";
    private static final String ANNUAL_PASSWORD = "22523232";
    private static final String PREFS_NAME = "zorq_boat_access";
    private static final String KEY_INITIAL_UNLOCKED = "initial_unlocked";
    private static final String KEY_ACTIVATED_AT = "activated_at";
    private static final String KEY_LAST_ANNUAL_UNLOCK = "last_annual_unlock";
    private static final long YEAR_MS = 365L * 24L * 60L * 60L * 1000L;
    private static final String MAP_PREFS = "zorq_boat_map";
    private static final String MAP_SOURCE_SATELLITE = "satellite";
    private static final String MAP_SOURCE_STREETS = "streets";
    private static final long MAP_CACHE_MAX_BYTES = 2L * 1024L * 1024L * 1024L;
    private static final long MAP_CACHE_TRIM_BYTES = 1800L * 1024L * 1024L;
    private static final long MAX_SAFE_CACHE_TILES = 6000L;
    private static final long CONFIRM_CACHE_TILES = 2500L;

    private TextView conn, vehicle, mode, arm, hb, log, errors, heading, speed, sat, hdop, ekf,
            battery, link, missionText, waypointsText, fcStatus;
    private EditText ip, port, lat, lon, altEdit;
    private Button armB, disarmB, autoB, rtlB, manualB, steeringB, holdB, loiterB,
            startB, uploadB, readB, connectB, disconnectB;
    private Button navDashboard, navMap, navMessages, navWaypoints, mapCenterB, mapFollowB, mapClearTrackB;
    private Button mapSatelliteB, mapStreetsB, mapOfflineB, mapCacheAreaB, mapClearCacheB;
    private ScrollView pageDashboard, pageMessages, pageWaypoints;
    private View pageMap;
    private TextView mapPosition, mapCacheStatus;
    private MapView mapView;
    private Marker aircraftMarker;
    private Polyline flightTrack;
    private Polyline missionLine;
    private final ArrayList<GeoPoint> trackPoints = new ArrayList<>();
    private final ArrayList<Marker> waypointMapMarkers = new ArrayList<>();
    private boolean followAircraft = true;
    private boolean mapHasAircraft = false;
    private boolean mapOffline = false;
    private String activeMapSource = MAP_SOURCE_SATELLITE;
    private CacheManager cacheManager;
    private CopyrightOverlay copyrightOverlay;
    private OnlineTileSourceBase satelliteTileSource;
    private boolean cacheDownloadActive = false;
    private BoundingBox cacheDownloadArea;
    private int cacheDownloadZoom;
    private int cacheDownloadZoomMax;
    private long cacheDownloadTotal;
    private long cacheDownloadDone;

    private MavlinkClient mav;
    private final ArrayList<MavlinkClient.MissionPoint> points = new ArrayList<>();
    private long lastHeartbeat = 0;
    private int hbCount = 0;
    private boolean pixhawkOnline = false;
    private boolean boatVehicleAccepted = false;
    private long connectStartedAt = 0L;
    private boolean noDataWarningShown = false;
    private final HandlerLike quality = new HandlerLike();

    private final ActivityResultLauncher<String[]> picker =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), this::loadMissionUri);

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Configuration.getInstance().load(getApplicationContext(), getSharedPreferences(MAP_PREFS, MODE_PRIVATE));
        Configuration.getInstance().setUserAgentValue(getPackageName() + "/1.0.0");
        File external = getExternalFilesDir(null);
        File mapBase = new File(external != null ? external : getFilesDir(), "osmdroid");
        File tileCache = new File(mapBase, "tiles");
        mapBase.mkdirs();
        tileCache.mkdirs();
        Configuration.getInstance().setOsmdroidBasePath(mapBase);
        Configuration.getInstance().setOsmdroidTileCache(tileCache);
        Configuration.getInstance().setTileFileSystemCacheMaxBytes(MAP_CACHE_MAX_BYTES);
        Configuration.getInstance().setTileFileSystemCacheTrimBytes(MAP_CACHE_TRIM_BYTES);
        setContentView(R.layout.activity_main);
        applySystemInsets();
        bind();

        mav = new MavlinkClient(this);
        initMap();
        lockCommands(false);
        showPage(0);
        checkAppAccess();

        connectB.setOnClickListener(v -> connect());
        disconnectB.setOnClickListener(v -> disconnect());
        armB.setOnClickListener(v -> confirm("ARM boat?", () -> sendArm(true)));
        disarmB.setOnClickListener(v -> confirm("DISARM boat?", () -> sendArm(false)));
        manualB.setOnClickListener(v -> confirm("Switch boat to MANUAL?", () -> sendMode(0)));
        steeringB.setOnClickListener(v -> confirm("Switch boat to STEERING?", () -> sendMode(3)));
        holdB.setOnClickListener(v -> confirm("Switch boat to HOLD?", () -> sendMode(4)));
        autoB.setOnClickListener(v -> confirm("Switch boat to AUTO?", () -> sendMode(10)));
        loiterB.setOnClickListener(v -> confirm("Switch boat to LOITER?", () -> sendMode(5)));
        rtlB.setOnClickListener(v -> confirm("Switch boat to RTL?", () -> sendMode(11)));
        startB.setOnClickListener(v -> confirm("Start current mission?", () -> thread(() -> mav.startMission())));

        findViewById(R.id.btnLoadMission).setOnClickListener(v ->
                picker.launch(new String[]{"text/*", "application/octet-stream", "*/*"}));
        uploadB.setOnClickListener(v -> confirm(
                "WRITE " + points.size() + " mission items to Pixhawk? This replaces the mission stored in the flight controller.",
                () -> thread(() -> mav.uploadMission(points))));
        readB.setOnClickListener(v -> confirm(
                "READ the mission currently stored in Pixhawk? This replaces the waypoint list shown in the app.",
                () -> thread(() -> mav.downloadMission())));
        findViewById(R.id.btnAddWaypoint).setOnClickListener(v -> addPoint());
        findViewById(R.id.btnClearWaypoints).setOnClickListener(v -> confirm(
                "Clear all waypoints from the app list?",
                this::clearWaypoints));
        findViewById(R.id.btnClearMessages).setOnClickListener(v -> clearMessageDisplay());

        navDashboard.setOnClickListener(v -> showPage(0));
        navMap.setOnClickListener(v -> showPage(1));
        navMessages.setOnClickListener(v -> showPage(2));
        navWaypoints.setOnClickListener(v -> showPage(3));
        mapCenterB.setOnClickListener(v -> centerAircraft());
        mapFollowB.setOnClickListener(v -> toggleMapFollow());
        mapClearTrackB.setOnClickListener(v -> clearFlightTrack());
        mapSatelliteB.setOnClickListener(v -> setMapSource(MAP_SOURCE_SATELLITE));
        mapStreetsB.setOnClickListener(v -> setMapSource(MAP_SOURCE_STREETS));
        mapOfflineB.setOnClickListener(v -> toggleOfflineMap());
        mapCacheAreaB.setOnClickListener(v -> showCacheAreaDialog());
        mapClearCacheB.setOnClickListener(v -> showClearCacheDialog());

        quality.start();
    }

    private void applySystemInsets() {
        View root = findViewById(R.id.rootMain);
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });
        ViewCompat.requestApplyInsets(root);
    }

    private void bind() {
        conn = f(R.id.txtConnection);
        vehicle = f(R.id.txtVehicle);
        mode = f(R.id.txtMode);
        arm = f(R.id.txtArm);
        hb = f(R.id.txtHeartbeat);
        log = f(R.id.txtLog);
        errors = f(R.id.txtErrors);
        heading = f(R.id.txtHeading);
        speed = f(R.id.txtSpeed);
        sat = f(R.id.txtSat);
        hdop = f(R.id.txtHdop);
        ekf = f(R.id.txtEkf);
        battery = f(R.id.txtBattery);
        link = f(R.id.txtLink);
        missionText = f(R.id.txtMission);
        waypointsText = f(R.id.txtWaypoints);
        fcStatus = f(R.id.txtFcStatus);

        ip = e(R.id.editIp);
        port = e(R.id.editPort);
        lat = e(R.id.editLat);
        lon = e(R.id.editLon);
        altEdit = e(R.id.editAlt);

        armB = bt(R.id.btnArm);
        disarmB = bt(R.id.btnDisarm);
        autoB = bt(R.id.btnAuto);
        rtlB = bt(R.id.btnRtl);
        manualB = bt(R.id.btnManual);
        steeringB = bt(R.id.btnSteering);
        holdB = bt(R.id.btnHold);
        loiterB = bt(R.id.btnLoiter);
        startB = bt(R.id.btnStartMission);
        uploadB = bt(R.id.btnUploadMission);
        readB = bt(R.id.btnReadMission);
        connectB = bt(R.id.btnConnect);
        disconnectB = bt(R.id.btnDisconnect);

        pageDashboard = findViewById(R.id.pageDashboard);
        pageMap = findViewById(R.id.pageMap);
        pageMessages = findViewById(R.id.pageMessages);
        pageWaypoints = findViewById(R.id.pageWaypoints);
        navDashboard = bt(R.id.navDashboard);
        navMap = bt(R.id.navMap);
        navMessages = bt(R.id.navMessages);
        navWaypoints = bt(R.id.navWaypoints);
        mapPosition = f(R.id.txtMapPosition);
        mapCacheStatus = f(R.id.txtMapCacheStatus);
        mapCenterB = bt(R.id.btnMapCenter);
        mapFollowB = bt(R.id.btnMapFollow);
        mapClearTrackB = bt(R.id.btnClearTrack);
        mapSatelliteB = bt(R.id.btnMapSatellite);
        mapStreetsB = bt(R.id.btnMapStreets);
        mapOfflineB = bt(R.id.btnMapOffline);
        mapCacheAreaB = bt(R.id.btnMapCacheArea);
        mapClearCacheB = bt(R.id.btnMapClearCache);
        mapView = findViewById(R.id.mapView);
    }

    private TextView f(int i) { return findViewById(i); }
    private EditText e(int i) { return findViewById(i); }
    private Button bt(int i) { return findViewById(i); }

    private void showPage(int page) {
        pageDashboard.setVisibility(page == 0 ? View.VISIBLE : View.GONE);
        pageMap.setVisibility(page == 1 ? View.VISIBLE : View.GONE);
        pageMessages.setVisibility(page == 2 ? View.VISIBLE : View.GONE);
        pageWaypoints.setVisibility(page == 3 ? View.VISIBLE : View.GONE);

        styleNav(navDashboard, page == 0);
        styleNav(navMap, page == 1);
        styleNav(navMessages, page == 2);
        styleNav(navWaypoints, page == 3);
        if (page == 1 && mapView != null) mapView.invalidate();
    }

    private void styleNav(Button b, boolean selected) {
        b.setSelected(selected);
        b.setTextColor(getColor(selected ? R.color.cyan : R.color.muted));
    }

    private void checkAppAccess() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        migratePreviousInstallIfNeeded(prefs);

        if (!prefs.getBoolean(KEY_INITIAL_UNLOCKED, false)) {
            showInitialPassword();
            return;
        }

        long activatedAt = prefs.getLong(KEY_ACTIVATED_AT, System.currentTimeMillis());
        long lastAnnualUnlock = prefs.getLong(KEY_LAST_ANNUAL_UNLOCK, activatedAt);
        long now = System.currentTimeMillis();
        if (now - lastAnnualUnlock >= YEAR_MS) {
            showAnnualLock();
        }
    }

    private void migratePreviousInstallIfNeeded(SharedPreferences prefs) {
        if (prefs.contains(KEY_INITIAL_UNLOCKED)) return;
        try {
            PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), 0);
            if (info.lastUpdateTime > info.firstInstallTime + 2000L) {
                prefs.edit()
                        .putBoolean(KEY_INITIAL_UNLOCKED, true)
                        .putLong(KEY_ACTIVATED_AT, info.firstInstallTime)
                        .putLong(KEY_LAST_ANNUAL_UNLOCK, info.firstInstallTime)
                        .apply();
            }
        } catch (Exception ignored) {
        }
    }

    private void showInitialPassword() {
        final EditText x = passwordField();
        AlertDialog d = new AlertDialog.Builder(this)
                .setTitle("زورق — First Activation")
                .setMessage("Enter the installation password. You will not be asked again on normal app launches.")
                .setView(x)
                .setCancelable(false)
                .setPositiveButton("ACTIVATE", null)
                .setNegativeButton("EXIT", (a, w) -> finish())
                .create();

        d.setOnShowListener(z -> d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            if (INITIAL_PASSWORD.equals(x.getText().toString())) {
                long now = System.currentTimeMillis();
                getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                        .putBoolean(KEY_INITIAL_UNLOCKED, true)
                        .putLong(KEY_ACTIVATED_AT, now)
                        .putLong(KEY_LAST_ANNUAL_UNLOCK, now)
                        .apply();
                d.dismiss();
                toast("زورق activated");
            } else {
                x.setError("Incorrect password");
                x.setText("");
            }
        }));
        d.show();
    }

    private void showAnnualLock() {
        final EditText x = passwordField();
        AlertDialog d = new AlertDialog.Builder(this)
                .setTitle("زورق — YEARLY UPDATE REQUIRED")
                .setMessage("One year has passed since the last activation. Update زورق to the latest APK, then enter the annual password to continue.")
                .setView(x)
                .setCancelable(false)
                .setPositiveButton("UNLOCK", null)
                .setNeutralButton("UPDATE INFO", null)
                .setNegativeButton("EXIT", (a, w) -> finish())
                .create();

        d.setOnShowListener(z -> {
            d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                if (ANNUAL_PASSWORD.equals(x.getText().toString())) {
                    getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                            .putLong(KEY_LAST_ANNUAL_UNLOCK, System.currentTimeMillis())
                            .apply();
                    d.dismiss();
                    toast("Yearly access renewed");
                } else {
                    x.setError("Incorrect annual password");
                    x.setText("");
                }
            });
            d.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v ->
                    new AlertDialog.Builder(this)
                            .setTitle("Update زورق")
                            .setMessage("Install the latest زورق APK from your normal GitHub build/release source. After updating, return here and enter the annual password.")
                            .setPositiveButton("OK", null)
                            .show());
        });
        d.show();
    }

    private EditText passwordField() {
        EditText x = new EditText(this);
        x.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        x.setHint("Password");
        return x;
    }

    private void connect() {
        lockCommands(false);
        pixhawkOnline = false;
        boatVehicleAccepted = false;
        lastHeartbeat = 0L;
        hbCount = 0;
        connectStartedAt = System.currentTimeMillis();
        noDataWarningShown = false;
        fcStatus.setText("ROVER CONTROLLER\nWAITING");
        fcStatus.setTextColor(getColor(R.color.orange));
        conn.setText("CONNECTING");
        conn.setTextColor(getColor(R.color.orange));
        disconnectB.setEnabled(true);

        try {
            int p = Integer.parseInt(port.getText().toString());
            startKeepAliveService();
            thread(() -> mav.connect(ip.getText().toString().trim(), p));
        } catch (Exception ex) {
            stopKeepAliveService();
            toast("Invalid IP/port");
        }
    }

    private void disconnect() {
        thread(() -> mav.disconnect());
    }

    private void startKeepAliveService() {
        try {
            Intent i = new Intent(this, ConnectionKeepAliveService.class);
            i.setAction(ConnectionKeepAliveService.ACTION_START);
            ContextCompat.startForegroundService(this, i);
        } catch (Exception e) {
            onLog("Background link warning: " + e.getMessage());
        }
    }

    private void stopKeepAliveService() {
        try {
            stopService(new Intent(this, ConnectionKeepAliveService.class));
        } catch (Exception ignored) { }
    }

    private void sendArm(boolean a) { thread(() -> mav.arm(a)); }
    private void sendMode(int m) { thread(() -> mav.setRoverMode(m)); }

    private interface ThrowRun { void run() throws Exception; }

    private void thread(ThrowRun r) {
        new Thread(() -> {
            try {
                r.run();
            } catch (Exception e) {
                onLog("ERROR: " + e.getMessage());
            }
        }, "101-gcs").start();
    }

    private void confirm(String s, Runnable r) {
        new AlertDialog.Builder(this)
                .setTitle("Confirm")
                .setMessage(s)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Continue", (d, w) -> r.run())
                .show();
    }

    @Override
    public void onHeartbeat(int s, int c, long m, boolean a, String v) {
        runOnUiThread(() -> {
            lastHeartbeat = System.currentTimeMillis();
            hbCount++;
            pixhawkOnline = true;

            boolean isBoat = v.contains("SURFACE_BOAT") || v.contains("GROUND_ROVER");
            boatVehicleAccepted = isBoat;

            conn.setText(isBoat ? "CONNECTED" : "WRONG VEHICLE");
            conn.setTextColor(getColor(isBoat ? R.color.green : R.color.red));
            fcStatus.setText(isBoat ? "ROVER CONTROLLER\nONLINE" : "NOT A ROVER/BOAT\nBLOCKED");
            fcStatus.setTextColor(getColor(isBoat ? R.color.green : R.color.red));
            vehicle.setText("Boat: " + v + "   SYS " + s + "   COMP " + c);

            mode.setText("BOAT MODE\n" + modeName(m));
            mode.setTextColor(getColor(R.color.cyan));

            arm.setText("ARM STATE: " + (a ? "ARMED" : "DISARMED"));
            arm.setTextColor(getColor(a ? R.color.green : R.color.red));

            hb.setText("Heartbeat: " + new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date()));
            disconnectB.setEnabled(true);
            lockCommands(isBoat);
            if (!isBoat) onLog("Rejected vehicle type: " + v + " — زورق is boat/rover only");
        });
    }

    @Override
    public void onTelemetry(Double a, Double sp, Integer sa, Double h, Double vo, Integer pc, String e) {
        runOnUiThread(() -> {
            if (sp != null) {
                speed.setText(String.format(Locale.US, "GROUND SPEED\n%.1f m/s", sp));
                speed.setTextColor(getColor(R.color.cyan));
            }
            if (sa != null) {
                sat.setText("SATELLITES\n" + sa + (sa >= 6 ? "\nGPS OK" : "\nLOW"));
                sat.setTextColor(getColor(sa >= 6 ? R.color.green : R.color.red));
            }
            if (h != null && !h.isNaN()) {
                hdop.setText(String.format(Locale.US, "HDOP\n%.2f\n%s", h, h <= 1.5 ? "GOOD" : "CHECK"));
                hdop.setTextColor(getColor(h <= 1.5 ? R.color.green : R.color.red));
            }
            if (vo != null) {
                int percent = pc == null ? -1 : pc;
                battery.setText(String.format(Locale.US, "BATTERY\n%.1f V\n%s", vo,
                        percent < 0 ? "--%" : percent + "%"));
                battery.setTextColor(getColor(percent >= 0 && percent <= 20 ? R.color.red : R.color.green));
            }
            if (e != null) {
                ekf.setText("EKF\n" + e);
                ekf.setTextColor(getColor(e.equals("HEALTHY") ? R.color.green : R.color.red));
            }
        });
    }

    @Override
    public void onStatusText(String t, boolean er) {
        runOnUiThread(() -> {
            String time = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
            log.append("\n" + time + "  " + t);
            if (er) {
                if (errors.getText().toString().startsWith("No errors")) errors.setText("");
                errors.append(time + "  " + t + "\n");
                errors.setTextColor(getColor(R.color.red));
                navMessages.setTextColor(getColor(R.color.red));
            }
        });
    }

    @Override
    public void onMissionProgress(String t) {
        runOnUiThread(() -> {
            missionText.setText(t);
            log.append("\n" + t);
        });
    }

    @Override
    public void onLog(String t) {
        runOnUiThread(() -> log.append("\n" + t));
    }

    @Override
    public void onMissionDownloaded(List<MavlinkClient.MissionPoint> downloaded) {
        runOnUiThread(() -> {
            points.clear();
            points.addAll(downloaded);
            missionText.setText("READ complete: " + points.size() + " items loaded from Pixhawk");
            refreshPoints();
            toast("Mission read from Pixhawk: " + points.size() + " items");
        });
    }

    @Override
    public void onDisconnected(String r) {
        runOnUiThread(() -> {
            pixhawkOnline = false;
            boatVehicleAccepted = false;
            connectStartedAt = 0L;
            stopKeepAliveService();
            conn.setText("DISCONNECTED");
            conn.setTextColor(getColor(R.color.red));
            fcStatus.setText("ROVER CONTROLLER\nOFFLINE");
            fcStatus.setTextColor(getColor(R.color.red));
            disconnectB.setEnabled(false);
            link.setText("PIXHAWK LINK\n0%\nCHECK");
            link.setTextColor(getColor(R.color.red));
            resetTelemetryAfterDisconnect();
            lockCommands(false);
            log.append("\nDISCONNECTED: " + r);
        });
    }

    private void resetTelemetryAfterDisconnect() {
        heading.setText("HEADING\n--°");
        speed.setText("GROUND SPEED\n-- m/s");
        sat.setText("SATELLITES\n--");
        hdop.setText("HDOP\n--");
        ekf.setText("EKF\nWAITING");
        battery.setText("BATTERY\n-- V  --%");
        mode.setText("BOAT MODE\n--");
        arm.setText("ARM STATE: --");
        vehicle.setText("Boat: --");
        hb.setText("Heartbeat: --");
        heading.setTextColor(getColor(R.color.cyan));
        speed.setTextColor(getColor(R.color.cyan));
        sat.setTextColor(getColor(R.color.cyan));
        hdop.setTextColor(getColor(R.color.cyan));
        ekf.setTextColor(getColor(R.color.cyan));
        battery.setTextColor(getColor(R.color.cyan));
        mode.setTextColor(getColor(R.color.cyan));
        arm.setTextColor(getColor(R.color.white));
    }

    private void lockCommands(boolean x) {
        armB.setEnabled(x);
        disarmB.setEnabled(x);
        autoB.setEnabled(x);
        rtlB.setEnabled(x);
        manualB.setEnabled(x);
        steeringB.setEnabled(x);
        holdB.setEnabled(x);
        loiterB.setEnabled(x);
        startB.setEnabled(x);
        readB.setEnabled(x);
        uploadB.setEnabled(x && !points.isEmpty());
    }

    private String modeName(long m) {
        switch ((int) m) {
            case 0: return "MANUAL";
            case 1: return "ACRO";
            case 3: return "STEERING";
            case 4: return "HOLD";
            case 5: return "LOITER";
            case 6: return "FOLLOW";
            case 7: return "SIMPLE";
            case 8: return "DOCK";
            case 9: return "CIRCLE";
            case 10: return "AUTO";
            case 11: return "RTL";
            case 12: return "SMART RTL";
            case 15: return "GUIDED";
            case 16: return "INITIALISING";
            default: return "MODE " + m;
        }
    }

    private void addPoint() {
        try {
            points.add(new MavlinkClient.MissionPoint(
                    16,
                    Double.parseDouble(lat.getText().toString()),
                    Double.parseDouble(lon.getText().toString()),
                    Float.parseFloat(altEdit.getText().toString())));
            lat.setText("");
            lon.setText("");
            altEdit.setText("");
            missionText.setText("Waypoint added. Total: " + points.size());
            refreshPoints();
        } catch (Exception e) {
            toast("Enter valid latitude, longitude and altitude");
        }
    }

    private void clearWaypoints() {
        points.clear();
        missionText.setText("No mission loaded");
        refreshPoints();
    }

    private void clearMessageDisplay() {
        errors.setText("No errors received");
        errors.setTextColor(getColor(R.color.white));
        log.setText("ZORQ BOAT GCS ready");
        styleNav(navMessages, pageMessages.getVisibility() == View.VISIBLE);
    }

    private void loadMissionUri(Uri u) {
        if (u == null) return;
        try (BufferedReader br = new BufferedReader(new InputStreamReader(
                getContentResolver().openInputStream(u)))) {
            ArrayList<MavlinkClient.MissionPoint> tmp = new ArrayList<>();
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("QGC")) continue;
                String[] q = line.split("\\s+");
                if (q.length >= 12) {
                    tmp.add(new MavlinkClient.MissionPoint(
                            Integer.parseInt(q[3]),
                            Double.parseDouble(q[8]),
                            Double.parseDouble(q[9]),
                            Float.parseFloat(q[10])));
                }
            }
            points.clear();
            points.addAll(tmp);
            missionText.setText("File loaded: " + points.size() + " items — ready to WRITE to Pixhawk");
            refreshPoints();
        } catch (Exception e) {
            toast("Mission file error: " + e.getMessage());
        }
    }

    private void refreshPoints() {
        StringBuilder s = new StringBuilder("WAYPOINTS: " + points.size() + "\n\n");
        for (int i = 0; i < points.size(); i++) {
            MavlinkClient.MissionPoint p = points.get(i);
            s.append(String.format(Locale.US,
                    "%02d  CMD %-3d  %.7f, %.7f  %.1f m\n",
                    i, p.command, p.lat, p.lon, p.alt));
        }
        waypointsText.setText(s);
        uploadB.setEnabled(pixhawkOnline && !points.isEmpty());
        refreshMapWaypoints();
    }

    private void initMap() {
        satelliteTileSource = new OnlineTileSourceBase(
                "EOX_S2_2024", 0, 16, 256, ".jpg",
                new String[]{"https://tiles.maps.eox.at/wmts/1.0.0/s2cloudless-2024_3857/default/GoogleMapsCompatible/"},
                "EOxCloudless https://cloudless.eox.at by EOX IT Services GmbH (Contains modified Copernicus Sentinel data 2024)") {
            @Override
            public String getTileURLString(long mapTileIndex) {
                int z = MapTileIndex.getZoom(mapTileIndex);
                int x = MapTileIndex.getX(mapTileIndex);
                int y = MapTileIndex.getY(mapTileIndex);
                return getBaseUrl() + z + "/" + y + "/" + x + ".jpg";
            }
        };

        mapView.setTilesScaledToDpi(true);
        mapView.setMultiTouchControls(true);
        mapView.setBuiltInZoomControls(false);
        mapView.setMinZoomLevel(3.0);
        mapView.getController().setZoom(3.0);
        mapView.getController().setCenter(new GeoPoint(0.0, 0.0));

        copyrightOverlay = new CopyrightOverlay(this);
        mapView.getOverlays().add(copyrightOverlay);

        missionLine = new Polyline();
        missionLine.setColor(getColor(R.color.orange));
        missionLine.setWidth(4.0f);
        missionLine.setTitle("Mission");
        mapView.getOverlays().add(missionLine);

        flightTrack = new Polyline();
        flightTrack.setColor(getColor(R.color.cyan));
        flightTrack.setWidth(5.0f);
        flightTrack.setTitle("Aircraft track");
        mapView.getOverlays().add(flightTrack);

        aircraftMarker = new Marker(mapView);
        aircraftMarker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER);
        aircraftMarker.setIcon(getDrawable(R.drawable.ic_aircraft_marker));
        aircraftMarker.setTitle("Boat");
        aircraftMarker.setVisible(false);
        mapView.getOverlays().add(aircraftMarker);

        cacheManager = new CacheManager(mapView);
        activeMapSource = getSharedPreferences(MAP_PREFS, MODE_PRIVATE)
                .getString("active_source", MAP_SOURCE_SATELLITE);
        setMapSource(activeMapSource);
        refreshMapWaypoints();
    }

    private void setMapSource(String source) {
        activeMapSource = MAP_SOURCE_STREETS.equals(source) ? MAP_SOURCE_STREETS : MAP_SOURCE_SATELLITE;
        if (MAP_SOURCE_STREETS.equals(activeMapSource)) {
            mapView.setTileSource(TileSourceFactory.MAPNIK);
            mapView.setMaxZoomLevel(20.0);
            mapCacheAreaB.setEnabled(false);
            mapCacheStatus.setText("STREETS • OpenStreetMap online view • bulk cache is disabled for this source");
        } else {
            mapView.setTileSource(satelliteTileSource);
            mapView.setMaxZoomLevel(16.0);
            if (mapView.getZoomLevelDouble() > 16.0) mapView.getController().setZoom(16.0);
            mapCacheAreaB.setEnabled(true);
            mapCacheStatus.setText("SATELLITE • EOX Sentinel-2 2024 • press CACHE AREA while internet is available");
        }
        if (cacheManager != null && cacheDownloadActive) {
            cacheManager.cancelAllJobs();
            cacheDownloadActive = false;
        }
        cacheManager = new CacheManager(mapView);
        mapView.setUseDataConnection(!mapOffline);
        getSharedPreferences(MAP_PREFS, MODE_PRIVATE).edit().putString("active_source", activeMapSource).apply();
        styleMapSourceButtons();
        mapView.invalidate();
    }

    private void styleMapSourceButtons() {
        mapSatelliteB.setTextColor(getColor(MAP_SOURCE_SATELLITE.equals(activeMapSource) ? R.color.green : R.color.muted));
        mapStreetsB.setTextColor(getColor(MAP_SOURCE_STREETS.equals(activeMapSource) ? R.color.cyan : R.color.muted));
        mapOfflineB.setText(mapOffline ? "OFFLINE: ON" : "OFFLINE: OFF");
        mapOfflineB.setTextColor(getColor(mapOffline ? R.color.green : R.color.orange));
    }

    private void toggleOfflineMap() {
        mapOffline = !mapOffline;
        mapView.setUseDataConnection(!mapOffline);
        styleMapSourceButtons();
        mapCacheStatus.setText(mapOffline
                ? "OFFLINE MODE • only previously cached tiles are used"
                : "ONLINE MODE • map may download missing tiles using the phone internet connection");
        mapView.invalidate();
    }

    private void showCacheAreaDialog() {
        if (!MAP_SOURCE_SATELLITE.equals(activeMapSource)) {
            toast("CACHE AREA is available for the SATELLITE source. Select SATELLITE first.");
            return;
        }
        if (mapOffline) {
            toast("Turn OFFLINE off before downloading map tiles");
            return;
        }
        int currentZoom = (int) Math.round(mapView.getZoomLevelDouble());
        int sourceMax = MAP_SOURCE_SATELLITE.equals(activeMapSource) ? 16 : 20;
        int suggestedMin = Math.max(3, currentZoom - 2);
        int suggestedMax = Math.min(sourceMax, currentZoom + 1);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        box.setPadding(pad, 0, pad, 0);
        EditText minZoom = new EditText(this);
        minZoom.setHint("Minimum zoom");
        minZoom.setInputType(InputType.TYPE_CLASS_NUMBER);
        minZoom.setText(String.valueOf(suggestedMin));
        EditText maxZoom = new EditText(this);
        maxZoom.setHint("Maximum zoom");
        maxZoom.setInputType(InputType.TYPE_CLASS_NUMBER);
        maxZoom.setText(String.valueOf(suggestedMax));
        box.addView(minZoom);
        box.addView(maxZoom);

        new AlertDialog.Builder(this)
                .setTitle("CACHE VISIBLE MAP AREA")
                .setMessage("Pan/zoom to the boat operating area first. زورق will download every tile inside the current screen bounds so the same area can be displayed while connected only to PW-Link. Satellite source maximum zoom is 16.")
                .setView(box)
                .setNegativeButton("CANCEL", null)
                .setPositiveButton("DOWNLOAD", (d, w) -> {
                    try {
                        int zMin = Integer.parseInt(minZoom.getText().toString());
                        int zMax = Integer.parseInt(maxZoom.getText().toString());
                        zMin = Math.max(3, zMin);
                        zMax = Math.min(sourceMax, zMax);
                        if (zMin > zMax) throw new IllegalArgumentException();
                        downloadVisibleMapArea(zMin, zMax);
                    } catch (Exception ex) {
                        toast("Invalid zoom range");
                    }
                })
                .show();
    }

    private void downloadVisibleMapArea(int zoomMin, int zoomMax) {
        if (cacheDownloadActive) {
            toast("A map download is already running");
            return;
        }

        BoundingBox area = mapView.getBoundingBox();
        long total;
        try {
            total = estimateTilesSafely(area, zoomMin, zoomMax);
        } catch (Throwable t) {
            mapCacheStatus.setText("CACHE • unable to calculate selected area");
            toast("Map area calculation failed. Zoom in and try again.");
            return;
        }

        if (total <= 0) {
            toast("No map tiles in the selected area");
            return;
        }

        if (total > MAX_SAFE_CACHE_TILES) {
            new AlertDialog.Builder(this)
                    .setTitle("MAP AREA TOO LARGE")
                    .setMessage("The selected screen area needs about " + total + " tiles. For stability, زورق limits one download to " + MAX_SAFE_CACHE_TILES + " tiles.\n\nZoom in closer to the boat operating area or reduce the zoom range, then try again.")
                    .setPositiveButton("OK", null)
                    .show();
            mapCacheStatus.setText("CACHE • area too large • " + total + " tiles");
            return;
        }

        if (total >= CONFIRM_CACHE_TILES) {
            long approxMb = Math.max(1, (total * 80L) / 1024L);
            new AlertDialog.Builder(this)
                    .setTitle("Download offline map?")
                    .setMessage("Selected area: " + total + " tiles\nApproximate data: " + approxMb + " MB\nZoom: " + zoomMin + "–" + zoomMax + "\n\nThe download is split by zoom level to prevent the app from closing.")
                    .setNegativeButton("CANCEL", null)
                    .setPositiveButton("DOWNLOAD", (d, w) -> startCacheDownloadSequential(area, zoomMin, zoomMax, total))
                    .show();
        } else {
            startCacheDownloadSequential(area, zoomMin, zoomMax, total);
        }
    }

    private long estimateTilesSafely(BoundingBox area, int zoomMin, int zoomMax) {
        long total = 0;
        for (int z = zoomMin; z <= zoomMax; z++) {
            Rect r = CacheManager.getTilesRect(area, z);
            long width = (long) r.right - (long) r.left + 1L;
            long height = (long) r.bottom - (long) r.top + 1L;
            if (width <= 0 || height <= 0) continue;
            long level = width * height;
            if (level < 0 || total > Long.MAX_VALUE - level) return Long.MAX_VALUE;
            total += level;
            if (total > MAX_SAFE_CACHE_TILES * 10L) return total;
        }
        return total;
    }

    private long estimateTilesAtZoom(BoundingBox area, int zoom) {
        Rect r = CacheManager.getTilesRect(area, zoom);
        long width = (long) r.right - (long) r.left + 1L;
        long height = (long) r.bottom - (long) r.top + 1L;
        if (width <= 0 || height <= 0) return 0;
        return width * height;
    }

    private void startCacheDownloadSequential(BoundingBox area, int zoomMin, int zoomMax, long totalTiles) {
        cacheDownloadActive = true;
        cacheDownloadArea = area;
        cacheDownloadZoom = zoomMin;
        cacheDownloadZoomMax = zoomMax;
        cacheDownloadTotal = totalTiles;
        cacheDownloadDone = 0;
        mapCacheAreaB.setEnabled(false);
        mapClearCacheB.setEnabled(false);
        mapCacheStatus.setText("CACHE • preparing " + totalTiles + " tiles");
        startNextCacheZoom();
    }

    private void startNextCacheZoom() {
        if (!cacheDownloadActive) return;
        if (cacheDownloadZoom > cacheDownloadZoomMax) {
            finishCacheDownload(true, 0);
            return;
        }

        final int z = cacheDownloadZoom;
        final long levelTiles = estimateTilesAtZoom(cacheDownloadArea, z);
        if (levelTiles <= 0) {
            cacheDownloadZoom++;
            startNextCacheZoom();
            return;
        }

        try {
            // Recreate the manager after the tile source is selected so the cache task
            // always uses the active satellite source. One zoom level per task avoids
            // very large allocations/jobs on memory-constrained phones.
            cacheManager = new CacheManager(mapView);
            cacheManager.downloadAreaAsyncNoUI(this, cacheDownloadArea, z, z, new CacheManager.CacheManagerCallback() {
                @Override
                public void onTaskComplete() {
                    runOnUiThread(() -> {
                        cacheDownloadDone += levelTiles;
                        cacheDownloadZoom++;
                        startNextCacheZoom();
                    });
                }

                @Override
                public void updateProgress(int progress, int currentZoomLevel, int zMin, int zMax) {
                    runOnUiThread(() -> {
                        long shown = Math.min(cacheDownloadTotal, cacheDownloadDone + Math.max(0, progress));
                        mapCacheStatus.setText("CACHE • " + shown + "/" + cacheDownloadTotal + " tiles • zoom " + currentZoomLevel);
                    });
                }

                @Override
                public void downloadStarted() {
                    runOnUiThread(() -> mapCacheStatus.setText(
                            "CACHE • zoom " + z + " • " + cacheDownloadDone + "/" + cacheDownloadTotal + " tiles"));
                }

                @Override
                public void setPossibleTilesInArea(int total) { }

                @Override
                public void onTaskFailed(int errorsCount) {
                    runOnUiThread(() -> finishCacheDownload(false, errorsCount));
                }
            });
        } catch (Throwable t) {
            runOnUiThread(() -> {
                mapCacheStatus.setText("CACHE FAILED • " + t.getClass().getSimpleName());
                finishCacheDownload(false, 1);
            });
        }
    }

    private void finishCacheDownload(boolean success, int errors) {
        cacheDownloadActive = false;
        mapCacheAreaB.setEnabled(MAP_SOURCE_SATELLITE.equals(activeMapSource));
        mapClearCacheB.setEnabled(true);
        if (success) {
            mapCacheStatus.setText("CACHE READY • " + cacheDownloadTotal + " tiles • switch OFFLINE ON to test");
            toast("Offline map area cached successfully");
        } else {
            mapCacheStatus.setText("CACHE STOPPED • " + errors + " download errors • reduce area/zoom and retry");
            toast("Map cache stopped safely. Try a smaller area or zoom range.");
        }
        mapView.invalidate();
    }

    private void showClearCacheDialog() {
        new AlertDialog.Builder(this)
                .setTitle("CLEAR VISIBLE MAP CACHE")
                .setMessage("Remove cached tiles for the current visible area and current map source?")
                .setNegativeButton("CANCEL", null)
                .setPositiveButton("CLEAR", (d, w) -> {
                    int maxZoom = MAP_SOURCE_SATELLITE.equals(activeMapSource) ? 16 : 20;
                    cacheManager.cleanAreaAsync(this, mapView.getBoundingBox(), 3, maxZoom);
                    mapCacheStatus.setText("CACHE • clearing visible area");
                })
                .show();
    }

    @Override
    public void onHeading(double headingDeg) {
        if (Double.isNaN(headingDeg)) return;
        runOnUiThread(() -> {
            double h = headingDeg % 360.0;
            if (h < 0) h += 360.0;
            heading.setText(String.format(Locale.US, "HEADING\n%03.0f°", h));
            heading.setTextColor(getColor(R.color.green));
        });
    }

    @Override
    public void onPosition(double latitude, double longitude, double relativeAltitude, double headingDeg) {
        runOnUiThread(() -> {
            GeoPoint position = new GeoPoint(latitude, longitude);
            aircraftMarker.setPosition(position);
            aircraftMarker.setVisible(true);
            if (!Double.isNaN(headingDeg)) aircraftMarker.setRotation((float) headingDeg);
            aircraftMarker.setSnippet(String.format(Locale.US, "GPS Alt %.1f m   Hdg %s",
                    relativeAltitude, Double.isNaN(headingDeg) ? "--" : String.format(Locale.US, "%.0f°", headingDeg)));

            trackPoints.add(position);
            if (trackPoints.size() > 500) trackPoints.remove(0);
            flightTrack.setPoints(new ArrayList<>(trackPoints));

            mapPosition.setText(String.format(Locale.US,
                    "BOAT  %.7f, %.7f   GPS ALT %.1f m   HDG %s",
                    latitude, longitude, relativeAltitude,
                    Double.isNaN(headingDeg) ? "--" : String.format(Locale.US, "%.0f°", headingDeg)));

            if (!mapHasAircraft) {
                mapHasAircraft = true;
                mapView.getController().setZoom(16.0);
                mapView.getController().setCenter(position);
            } else if (followAircraft) {
                mapView.getController().animateTo(position);
            }
            mapView.invalidate();
        });
    }

    private void centerAircraft() {
        if (!mapHasAircraft || aircraftMarker.getPosition() == null) {
            toast("Waiting for boat GPS position");
            return;
        }
        mapView.getController().animateTo(aircraftMarker.getPosition());
        if (mapView.getZoomLevelDouble() < 15.0) mapView.getController().setZoom(16.0);
    }

    private void toggleMapFollow() {
        followAircraft = !followAircraft;
        mapFollowB.setText(followAircraft ? "FOLLOW: ON" : "FOLLOW: OFF");
        mapFollowB.setTextColor(getColor(followAircraft ? R.color.green : R.color.muted));
        if (followAircraft) centerAircraft();
    }

    private void clearFlightTrack() {
        trackPoints.clear();
        flightTrack.setPoints(new ArrayList<>());
        mapView.invalidate();
        toast("Boat track cleared");
    }

    private void refreshMapWaypoints() {
        if (mapView == null || missionLine == null) return;
        for (Marker marker : waypointMapMarkers) mapView.getOverlays().remove(marker);
        waypointMapMarkers.clear();

        ArrayList<GeoPoint> missionPoints = new ArrayList<>();
        for (int i = 0; i < points.size(); i++) {
            MavlinkClient.MissionPoint p = points.get(i);
            if (Math.abs(p.lat) > 90.0 || Math.abs(p.lon) > 180.0) continue;
            if (Math.abs(p.lat) < 0.0000001 && Math.abs(p.lon) < 0.0000001) continue;

            GeoPoint gp = new GeoPoint(p.lat, p.lon);
            missionPoints.add(gp);
            Marker marker = new Marker(mapView);
            marker.setPosition(gp);
            marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM);
            marker.setTitle("WP " + i);
            marker.setSnippet(String.format(Locale.US, "CMD %d   ALT %.1f m", p.command, p.alt));
            waypointMapMarkers.add(marker);
            mapView.getOverlays().add(marker);
        }
        missionLine.setPoints(missionPoints);
        mapView.invalidate();
    }

    private void toast(String s) {
        runOnUiThread(() -> Toast.makeText(this, s, Toast.LENGTH_LONG).show());
    }

    private final class HandlerLike {
        private final android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
        private int last = 0;

        void start() {
            h.postDelayed(new Runnable() {
                @Override
                public void run() {
                    int now = hbCount;
                    int delta = now - last;
                    last = now;
                    long age = System.currentTimeMillis() - lastHeartbeat;
                    int q = Math.min(100, delta * 20);
                    if (lastHeartbeat == 0 || age > 3000) q = 0;

                    link.setText("PIXHAWK LINK\n" + q + "%\n" + (q >= 70 ? "GOOD" : "CHECK"));
                    link.setTextColor(getColor(q >= 70 ? R.color.green : R.color.red));

                    if (lastHeartbeat == 0 && connectStartedAt > 0
                            && System.currentTimeMillis() - connectStartedAt > 4500
                            && !noDataWarningShown) {
                        noDataWarningShown = true;
                        conn.setText("NO MAVLINK DATA");
                        conn.setTextColor(getColor(R.color.red));
                        fcStatus.setText("CHECK TELEM / PW-LINK");
                        fcStatus.setTextColor(getColor(R.color.red));
                        log.append("\nNo MAVLink heartbeat received. Close other GCS apps and check CUAV V5 TELEM MAVLink protocol and baud rate.");
                    }

                    if (lastHeartbeat > 0 && age > 3000 && pixhawkOnline) {
                        pixhawkOnline = false;
                        fcStatus.setText("ROVER CONTROLLER\nOFFLINE");
                        fcStatus.setTextColor(getColor(R.color.red));
                        conn.setText("LINK LOST");
                        conn.setTextColor(getColor(R.color.red));
                        lockCommands(false);
                    }

                    h.postDelayed(this, 5000);
                }
            }, 5000);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (mapView != null) mapView.onResume();
    }

    @Override
    protected void onPause() {
        if (mapView != null) mapView.onPause();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        quality.h.removeCallbacksAndMessages(null);
        if (cacheManager != null) cacheManager.cancelAllJobs();
        if (mapView != null) mapView.onDetach();
        if (isFinishing()) {
            mav.close();
            stopKeepAliveService();
        }
        super.onDestroy();
    }
}
