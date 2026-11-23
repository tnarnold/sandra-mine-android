package io.sandramine;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

import java.io.File;

/**
 * Keeps the miner alive while other apps are on screen. The mining threads run at SCHED_IDLE /
 * nice 19 (config low_priority), so the TV and its apps always come first.
 */
public final class MinerService extends Service {
    private static final String CHANNEL = "miner";

    /** Starts the service (and the engine) if they aren't running yet. */
    static void ensure(Context c) {
        engine(c);
        Intent i = new Intent(c, MinerService.class);
        try {
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
            else c.startService(i);
        } catch (RuntimeException e) {
            // Android 12+ refuses background starts; the engine still runs while the app is open.
        }
    }

    /** Starts the engine in this process (idempotent). */
    static void engine(Context c) {
        File cfg = new File(c.getFilesDir(), "config.json");
        Native.start(cfg.getAbsolutePath(), Seed.json(c));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        engine(this);
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(new NotificationChannel(CHANNEL, Strings.get(this, "channel"), NotificationManager.IMPORTANCE_MIN));
            b = new Notification.Builder(this, CHANNEL);
        } else {
            b = new Notification.Builder(this);
        }
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        b.setSmallIcon(R.drawable.ic_stat).setContentTitle("SandraMine").setContentText(Strings.get(this, "mining_low"))
                .setContentIntent(open).setOngoing(true);
        startForeground(1, b.build());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
