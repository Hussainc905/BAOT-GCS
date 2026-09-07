package com.example.pixhawkminigcs;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

import androidx.core.app.NotificationCompat;

/**
 * Keeps the زورق MAVLink/Wi-Fi session alive while the UI is in the background.
 * The actual MAVLink client remains in MainActivity in v0.3.5; this service raises
 * process priority and holds conservative CPU/Wi-Fi locks for the active session.
 */
public final class ConnectionKeepAliveService extends Service {
    public static final String ACTION_START = "com.example.zorqboatgcs.KEEPALIVE_START";
    public static final String ACTION_STOP = "com.example.zorqboatgcs.KEEPALIVE_STOP";

    private static final String CHANNEL_ID = "zorq_boat_link";
    private static final int NOTIFICATION_ID = 101;

    private PowerManager.WakeLock cpuLock;
    private WifiManager.WifiLock wifiLock;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            releaseLocks();
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            return START_NOT_STICKY;
        }

        promoteToForeground();
        acquireLocks();
        return START_NOT_STICKY;
    }

    private void promoteToForeground() {
        Intent openIntent = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this,
                0,
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_service_link)
                .setContentTitle("زورق — Pixhawk link active")
                .setContentText("MAVLink connection is kept active in the background")
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private void acquireLocks() {
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                cpuLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ZORQ:PixhawkCpu");
                cpuLock.setReferenceCounted(false);
                if (!cpuLock.isHeld()) cpuLock.acquire();
            }
        } catch (Exception ignored) { }

        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm != null) {
                // HIGH_PERF is mapped by newer Android versions as appropriate by the platform.
                wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "ZORQ:PixhawkWifi");
                wifiLock.setReferenceCounted(false);
                if (!wifiLock.isHeld()) wifiLock.acquire();
            }
        } catch (Exception ignored) { }
    }

    private void releaseLocks() {
        try {
            if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
        } catch (Exception ignored) { }
        wifiLock = null;

        try {
            if (cpuLock != null && cpuLock.isHeld()) cpuLock.release();
        } catch (Exception ignored) { }
        cpuLock = null;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm == null) return;
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "Boat Pixhawk connection",
                NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Keeps the زورق MAVLink connection active while the app is hidden");
        nm.createNotificationChannel(channel);
    }

    @Override
    public void onDestroy() {
        releaseLocks();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
