package com.example.privatelock;

import android.Manifest;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Build;
import android.os.Bundle;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

public class MainActivity extends AppCompatActivity implements SensorEventListener {

    static final String PREFS = "pl_prefs";
    static final String KEY_THRESHOLD = "threshold_g";
    static final String KEY_RUNNING = "running";

    private SharedPreferences prefs;

    private TextView bigState;
    private ProgressBar prog;
    private TextView gNow;
    private TextView thValue;
    private TextView status;
    private Button btnToggle;

    private SensorManager sm;
    private Sensor accel;

    private DevicePolicyManager dpm;
    private ComponentName admin;

    // low-pass для гравитации
    private final float[] gravity = new float[]{0f, 0f, 0f};
    private static final float ALPHA = 0.85f;
    private static final float DEADZONE_G = 0.03f;

    // сглаживание прогресса (чтоб не дёргалось)
    private float smoothP = 0f;

    // вибрация только при переходе вверх через порог
    private boolean wasAbove = false;
    private long lastVibeMs = 0;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);

        sm = (SensorManager) getSystemService(SENSOR_SERVICE);
        if (sm != null) accel = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);

        dpm = (DevicePolicyManager) getSystemService(Context.DEVICE_POLICY_SERVICE);
        admin = new ComponentName(this, MyDeviceAdminReceiver.class);

        bigState = findViewById(R.id.bigState);
        prog = findViewById(R.id.prog);
        gNow = findViewById(R.id.gNow);
        thValue = findViewById(R.id.thValue);
        status = findViewById(R.id.status);
        btnToggle = findViewById(R.id.btnToggle);
        SeekBar seek = findViewById(R.id.seek);

        // старт
        bigState.setText("SHAKE MORE");
        prog.setProgress(0);
        gNow.setText("Shake: 0.00 g");

        float th = prefs.getFloat(KEY_THRESHOLD, 1.20f);
        seek.setProgress(thToProgress(th));
        renderThreshold(th);
        renderRunning(prefs.getBoolean(KEY_RUNNING, false));

        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int progress, boolean fromUser) {
                float t = progressToTh(progress);
                prefs.edit().putFloat(KEY_THRESHOLD, t).apply();
                renderThreshold(t);
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });

        btnToggle.setOnClickListener(v -> {
            boolean isRunning = prefs.getBoolean(KEY_RUNNING, false);

            if (isRunning) {
                stopService(new Intent(this, ShakeService.class));
                prefs.edit().putBoolean(KEY_RUNNING, false).apply();
                renderRunning(false);
                return;
            }

            if (Build.VERSION.SDK_INT >= 33) {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
            }

            // Device Admin для lockNow()
            if (dpm != null && !dpm.isAdminActive(admin)) {
                Intent i = new Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN);
                i.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, admin);
                i.putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                        "Needed to lock the screen on shake.");
                startActivity(i);
                return;
            }

            Intent svc = new Intent(this, ShakeService.class);
            if (Build.VERSION.SDK_INT >= 26) ContextCompat.startForegroundService(this, svc);
            else startService(svc);

            prefs.edit().putBoolean(KEY_RUNNING, true).apply();
            renderRunning(true);
        });
    }

    @Override protected void onResume() {
        super.onResume();
        if (sm != null && accel != null) {
            sm.registerListener(this, accel, SensorManager.SENSOR_DELAY_UI);
        }
        renderRunning(prefs.getBoolean(KEY_RUNNING, false));
    }

    @Override protected void onPause() {
        super.onPause();
        if (sm != null) sm.unregisterListener(this);
    }

    @Override public void onSensorChanged(SensorEvent e) {
        if (e == null || e.values == null || e.values.length < 3) return;

        float ax = e.values[0], ay = e.values[1], az = e.values[2];

        // low-pass: гравитация
        gravity[0] = ALPHA * gravity[0] + (1f - ALPHA) * ax;
        gravity[1] = ALPHA * gravity[1] + (1f - ALPHA) * ay;
        gravity[2] = ALPHA * gravity[2] + (1f - ALPHA) * az;

        // high-pass: линейное ускорение
        float lx = ax - gravity[0];
        float ly = ay - gravity[1];
        float lz = az - gravity[2];

        float lin = (float) Math.sqrt(lx*lx + ly*ly + lz*lz);
        float shakeG = lin / SensorManager.GRAVITY_EARTH;
        if (shakeG < DEADZONE_G) shakeG = 0f;

        float th = prefs.getFloat(KEY_THRESHOLD, 1.20f);

        float p = shakeG / th;
        if (p < 0f) p = 0f;
        if (p > 1f) p = 1f;

        // сглаживаем
        smoothP = smoothP * 0.82f + p * 0.18f;

        int pct = Math.round(smoothP * 100f);
        prog.setProgress(pct);

        gNow.setText(String.format("Shake: %.2f g", shakeG));

        boolean above = (shakeG >= th);
        bigState.setText(above ? "WILL LOCK" : "SHAKE MORE");

        // короткая вибра только в приложении и только при пересечении порога вверх
        if (!wasAbove && above) vibOnce(25);
        wasAbove = above;
    }

    @Override public void onAccuracyChanged(Sensor sensor, int accuracy) {}

    private void vibOnce(int ms) {
        long now = System.currentTimeMillis();
        if (now - lastVibeMs < 600) return;
        lastVibeMs = now;

        if (Build.VERSION.SDK_INT >= 31) {
            VibratorManager vm = (VibratorManager) getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
            if (vm == null) return;
            Vibrator v = vm.getDefaultVibrator();
            if (Build.VERSION.SDK_INT >= 26) v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE));
            else v.vibrate(ms);
        } else {
            Vibrator v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
            if (v == null) return;
            if (Build.VERSION.SDK_INT >= 26) v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE));
            else v.vibrate(ms);
        }
    }

    private void renderThreshold(float t) {
        thValue.setText(String.format("%.2f g", t));
    }

    private void renderRunning(boolean running) {
        status.setText("Status: " + (running ? "RUNNING" : "STOPPED"));
        btnToggle.setText(running ? R.string.stop : R.string.start);
    }

    // SeekBar: 0..250 -> 0.5..3.0g
    private static float progressToTh(int p) { return 0.5f + (p / 100f); }
    private static int thToProgress(float th) {
        int p = Math.round((th - 0.5f) * 100f);
        if (p < 0) p = 0;
        if (p > 250) p = 250;
        return p;
    }
}
