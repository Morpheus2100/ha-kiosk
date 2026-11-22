package be.mo.hakiosk;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

/**
 * Achtergronddienst die de camera laat meekijken, ook als het scherm volledig uit staat.
 * Bij beweging: is het scherm aan, dan krijgt de kiosk een seintje; staat het uit,
 * dan maakt de dienst de tablet wakker en opent hij de kiosk.
 */
public class MotionService extends Service {

    public interface Listener {
        void onMotion();
    }

    private static final String CHANNEL = "motion";
    private static final int NOTIFICATION_ID = 1;

    /** De kiosk zet zich hier in zolang hij op de voorgrond staat. */
    static volatile Listener listener;
    /** Beweging negeren tot dit tijdstip (bv. vlak na het uitzetten van het scherm). */
    static volatile long ignoreUntil = 0;
    static volatile long lastMotion = 0;
    static volatile boolean running = false;

    private MotionDetector detector;
    private PowerManager.WakeLock cpuLock;

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
        startForeground(NOTIFICATION_ID, buildNotification());

        // CPU wakker houden, anders stopt de camera zodra het scherm uit is
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        cpuLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "hakiosk:motion");
        cpuLock.setReferenceCounted(false);
        cpuLock.acquire();

        int sensitivity = getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE)
                .getInt(MainActivity.KEY_SENSITIVITY, 5);
        detector = new MotionDetector(sensitivity, this::onMotion);
        detector.start();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    private void onMotion() {
        long now = System.currentTimeMillis();
        if (now < ignoreUntil) return;
        lastMotion = now;

        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        boolean screenOn = pm.isInteractive();
        Listener l = listener;
        if (screenOn) {
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

    @SuppressWarnings("deprecation")
    private Notification buildNotification() {
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            NotificationChannel ch = new NotificationChannel(CHANNEL, "Bewegingsdetectie",
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
                .setContentText("Bewegingsdetectie actief")
                .setOngoing(true)
                .setContentIntent(open)
                .build();
    }

    @Override
    public void onDestroy() {
        running = false;
        if (detector != null) detector.stop();
        if (cpuLock != null && cpuLock.isHeld()) cpuLock.release();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
