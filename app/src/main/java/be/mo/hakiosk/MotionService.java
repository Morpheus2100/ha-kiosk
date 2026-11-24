package be.mo.hakiosk;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.PowerManager;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;

/**
 * Achtergronddienst van de kiosk. Draait altijd, ook als het scherm uit staat.
 * - Bewegingsdetectie met de camera (als die aan staat): wekt de tablet bij beweging.
 * - Stuurt het batterijniveau naar Home Assistant (webhook), bij elke verandering en elke 5 minuten.
 */
public class MotionService extends Service {

    public interface Listener {
        void onMotion();
    }

    private static final String CHANNEL = "motion";
    private static final int NOTIFICATION_ID = 1;
    static final String WEBHOOK_ID = "hakiosk_battery";
    private static final long HEARTBEAT_MS = 5 * 60_000L;

    /** De kiosk zet zich hier in zolang hij op de voorgrond staat. */
    static volatile Listener listener;
    /** Beweging negeren tot dit tijdstip (bv. vlak na het uitzetten van het scherm). */
    static volatile long ignoreUntil = 0;
    static volatile long lastMotion = 0;
    static volatile boolean running = false;
    /** Voor het instellingenscherm: laatste verzending naar HA. */
    static volatile long lastBatterySend = 0;
    static volatile String lastBatteryResult = "nog niet verstuurd";

    private MotionDetector detector;
    private PowerManager.WakeLock cpuLock;
    private HandlerThread netThread;
    private Handler net;
    private final Handler main = new Handler();
    private int lastLevel = -1;
    private boolean lastCharging = false;

    private final BroadcastReceiver batteryReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            handleBattery(intent, false);
        }
    };

    private final Runnable heartbeat = new Runnable() {
        @Override
        public void run() {
            Intent sticky = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (sticky != null) handleBattery(sticky, true);
            main.postDelayed(this, HEARTBEAT_MS);
        }
    };

    static void start(Context c) {
        Intent i = new Intent(c, MotionService.class);
        if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
        else c.startService(i);
    }

    static void stop(Context c) {
        c.stopService(new Intent(c, MotionService.class));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        running = true;
        try {
            startForeground(NOTIFICATION_ID, buildNotification());
        } catch (Exception ignored) {
            // nieuwere Android-versies kunnen dit weigeren zonder cameratoestemming
        }

        // CPU wakker houden, anders stopt de camera en de batterijmelding zodra het scherm uit is
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        cpuLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "hakiosk:service");
        cpuLock.setReferenceCounted(false);
        cpuLock.acquire();

        netThread = new HandlerThread("net");
        netThread.start();
        net = new Handler(netThread.getLooper());

        SharedPreferences prefs = getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE);
        boolean motionOn = prefs.getBoolean(MainActivity.KEY_MOTION, true);
        boolean cameraOk = Build.VERSION.SDK_INT < 23
                || checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
        if (motionOn && cameraOk) {
            detector = new MotionDetector(prefs.getInt(MainActivity.KEY_SENSITIVITY, 5), this::onMotion);
            detector.start();
        }

        registerReceiver(batteryReceiver, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        main.postDelayed(heartbeat, HEARTBEAT_MS);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    // ---------- Beweging ----------

    private void onMotion() {
        long now = System.currentTimeMillis();
        if (now < ignoreUntil) return;
        lastMotion = now;

        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        Listener l = listener;
        if (pm.isInteractive()) {
            if (l != null) l.onMotion();
        } else {
            wakeScreen(pm);
        }
    }

    @SuppressWarnings("deprecation") // FULL_WAKE_LOCK is de enige manier om het scherm aan te zetten zonder activiteit
    private void wakeScreen(PowerManager pm) {
        PowerManager.WakeLock wl = pm.newWakeLock(PowerManager.FULL_WAKE_LOCK
                | PowerManager.ACQUIRE_CAUSES_WAKEUP
                | PowerManager.ON_AFTER_RELEASE, "hakiosk:wake");
        wl.acquire(5_000);
        Intent a = new Intent(this, MainActivity.class);
        a.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        a.putExtra(MainActivity.EXTRA_WOKE, true);
        try {
            startActivity(a);
        } catch (Exception ignored) {
        }
    }

    // ---------- Batterij naar Home Assistant ----------

    private void handleBattery(Intent i, boolean force) {
        int raw = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
        if (raw < 0 || scale <= 0) return;
        int level = raw * 100 / scale;
        int status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
        boolean charging = status == BatteryManager.BATTERY_STATUS_CHARGING
                || status == BatteryManager.BATTERY_STATUS_FULL;
        int plugged = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
        float temp = i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10f;

        if (!force && level == lastLevel && charging == lastCharging) return;
        lastLevel = level;
        lastCharging = charging;

        final String json = String.format(Locale.US,
                "{\"level\":%d,\"charging\":%s,\"plugged\":%s,\"temperature\":%.1f}",
                level, charging, plugged != 0, temp);
        net.post(() -> send(json));
    }

    private void send(String json) {
        String base = getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE)
                .getString(MainActivity.KEY_URL, null);
        if (base == null) return;
        HttpURLConnection con = null;
        try {
            Uri u = Uri.parse(base);
            String port = u.getPort() > 0 ? ":" + u.getPort() : "";
            URL url = new URL(u.getScheme() + "://" + u.getHost() + port + "/api/webhook/" + WEBHOOK_ID);
            con = (HttpURLConnection) url.openConnection();
            con.setRequestMethod("POST");
            con.setConnectTimeout(10_000);
            con.setReadTimeout(10_000);
            con.setDoOutput(true);
            con.setRequestProperty("Content-Type", "application/json");
            try (OutputStream out = con.getOutputStream()) {
                out.write(json.getBytes("UTF-8"));
            }
            int code = con.getResponseCode();
            lastBatterySend = System.currentTimeMillis();
            lastBatteryResult = (code >= 200 && code < 300) ? "OK" : "HTTP " + code;
        } catch (Exception e) {
            lastBatterySend = System.currentTimeMillis();
            lastBatteryResult = "fout: " + e.getClass().getSimpleName();
        } finally {
            if (con != null) con.disconnect();
        }
    }

    // ---------- Melding ----------

    @SuppressWarnings("deprecation")
    private Notification buildNotification() {
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            NotificationChannel ch = new NotificationChannel(CHANNEL, "Kiosk op de achtergrond",
                    NotificationManager.IMPORTANCE_MIN);
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
            b = new Notification.Builder(this, CHANNEL);
        } else {
            b = new Notification.Builder(this).setPriority(Notification.PRIORITY_MIN);
        }
        int piFlags = Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0;
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class), piFlags);
        return b.setSmallIcon(R.drawable.ic_notify)
                .setContentTitle("HA Kiosk")
                .setContentText("Actief: bewegingsdetectie en batterij naar Home Assistant")
                .setOngoing(true)
                .setContentIntent(open)
                .build();
    }

    @Override
    public void onDestroy() {
        running = false;
        main.removeCallbacksAndMessages(null);
        try {
            unregisterReceiver(batteryReceiver);
        } catch (Exception ignored) {
        }
        if (detector != null) detector.stop();
        if (netThread != null) netThread.quitSafely();
        if (cpuLock != null && cpuLock.isHeld()) cpuLock.release();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
