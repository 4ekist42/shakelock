package com.example.privatelock;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.app.admin.DevicePolicyManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Build;
import android.os.IBinder;

import androidx.core.app.NotificationCompat;

public class ShakeService extends Service implements SensorEventListener {

    private static final String CHANNEL_ID = "pl_shake";
    private static final int NOTIF_ID = 1001;

    private SensorManager sm;
    private Sensor accel;
    private SharedPreferences prefs;

    private DevicePolicyManager dpm;
    private ComponentName admin;

    private volatile boolean screenOn = true;
    private long lastTriggerMs = 0;

    // фильтр гравитации
    private final float[] gravity = new float[]{0f, 0f, 0f};
    private static final float ALPHA = 0.85f;
    private static final float DEADZONE_G = 0.03f;

    private final BroadcastReceiver screenRx = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (intent == null || intent.getAction() == null) return;
            String a = intent.getAction();
            if (Intent.ACTION_SCREEN_OFF.equals(a)) screenOn = false;
            if (Intent.ACTION_SCREEN_ON.equals(a)) screenOn = true;
        }
    };

    @Override public void onCreate() {
        super.onCreate();

        prefs = getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE);
        prefs.edit().putBoolean(MainActivity.KEY_RUNNING, true).apply();

        dpm = (DevicePolicyManager) getSystemService(Context.DEVICE_POLICY_SERVICE);
        admin = new ComponentName(this, MyDeviceAdminReceiver.class);

        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_SCREEN_ON);
        f.addAction(Intent.ACTION_SCREEN_OFF);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(screenRx, f, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(screenRx, f);

        sm = (SensorManager) getSystemService(SENSOR_SERVICE);
        if (sm != null) {
            accel = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
            if (accel != null) sm.registerListener(this, accel, SensorManager.SENSOR_DELAY_GAME);
        }

        if (Build.VERSION.SDK_INT >= 26) {
            Notification n = buildNotification();
            if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            else startForeground(NOTIF_ID, n);
        }
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override public void onDestroy() {
        super.onDestroy();
        if (sm != null) sm.unregisterListener(this);
        try { unregisterReceiver(screenRx); } catch (Exception ignored) {}
        prefs.edit().putBoolean(MainActivity.KEY_RUNNING, false).apply();
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public void onSensorChanged(SensorEvent e) {
        if (e == null || e.values == null || e.values.length < 3) return;

        float ax = e.values[0], ay = e.values[1], az = e.values[2];

        gravity[0] = ALPHA * gravity[0] + (1f - ALPHA) * ax;
        gravity[1] = ALPHA * gravity[1] + (1f - ALPHA) * ay;
        gravity[2] = ALPHA * gravity[2] + (1f - ALPHA) * az;

        float lx = ax - gravity[0];
        float ly = ay - gravity[1];
        float lz = az - gravity[2];

        float lin = (float) Math.sqrt(lx*lx + ly*ly + lz*lz);
        float shakeG = lin / SensorManager.GRAVITY_EARTH;
        if (shakeG < DEADZONE_G) shakeG = 0f;

        float th = prefs.getFloat(MainActivity.KEY_THRESHOLD, 1.20f);

        long now = System.currentTimeMillis();
        if (shakeG >= th && (now - lastTriggerMs) > 1200) {
            lastTriggerMs = now;

            // экран погашен — НЕ блокируем
            if (!screenOn) return;

            if (dpm != null && dpm.isAdminActive(admin)) {
                dpm.lockNow();
                return;
            }

            Intent i = new Intent(this, LockActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            startActivity(i);
        }
    }

    @Override public void onAccuracyChanged(Sensor sensor, int accuracy) {}

    private Notification buildNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);

        if (Build.VERSION.SDK_INT >= 26 && nm != null) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID,
                    "Shake detector",
                    NotificationManager.IMPORTANCE_LOW
            );
            nm.createNotificationChannel(ch);
        }

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_lock_lock)
                .setContentTitle("PrivateLock running")
                .setContentText("Shake to lock (open app to change threshold)")
                .setOngoing(true)
                .build();
    }
}
