package com.gathin.tvhome;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextClock;

/**
 * config.xml show_overlay_clock: a small clock in the top-right corner over every app, hidden while Home is on
 * screen (Home has its own). Needs the overlay app-op, granted once over ADB:
 * appops set com.gathin.tvhome SYSTEM_ALERT_WINDOW allow
 * A foreground service, so Android 8+ does not stop it while another app is in front.
 */
public final class OverlayClockService extends Service {
    private static final String CHANNEL = "overlay_clock";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    /** True while MainActivity is resumed. Read and written on the main thread only. */
    private static boolean homeVisible;
    private static OverlayClockService running;

    private WindowManager wm;
    private TextClock clock;

    /** Starts the service when the switch is on and the overlay app-op is granted; safe to call repeatedly. */
    static void start(Context ctx) {
        if (running != null || !ctx.getResources().getBoolean(R.bool.show_overlay_clock)) return;
        if (!Settings.canDrawOverlays(ctx)) return;
        ctx.startForegroundService(new Intent(ctx, OverlayClockService.class));
    }

    /** MainActivity's onResume and onPause: the overlay steps aside while Home's own clock is showing. */
    static void setHomeVisible(boolean visible) {
        homeVisible = visible;
        if (running != null) running.applyVisibility();
    }

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, getString(R.string.app_name),
                NotificationManager.IMPORTANCE_MIN));
        startForeground(1, new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentTitle(getString(R.string.app_name))
                .build());

        clock = new TextClock(this);
        boolean is24 = ClockFormat.is24(this, Config.load(this).clockFormat);
        clock.setFormat12Hour(is24 ? "HH:mm" : "h:mm a");
        clock.setFormat24Hour(is24 ? "HH:mm" : "h:mm a");
        clock.setTextSize(TypedValue.COMPLEX_UNIT_SP, getResources().getInteger(R.integer.overlay_clock_sp));
        clock.setTextColor(0xCCFFFFFF);
        // A soft shadow keeps it readable over white scenes without a box around it.
        clock.setShadowLayer(4f, 0f, 1f, 0xCC000000);

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.END;
        float density = getResources().getDisplayMetrics().density;
        lp.x = Math.round(24 * density);
        lp.y = Math.round(14 * density);
        wm = getSystemService(WindowManager.class);
        wm.addView(clock, lp);
        running = this;
        applyVisibility();
    }

    private void applyVisibility() {
        MAIN.post(() -> { if (clock != null) clock.setVisibility(homeVisible ? View.GONE : View.VISIBLE); });
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = null;
        if (clock != null) wm.removeView(clock);
        clock = null;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
