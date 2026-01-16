package com.example.privatelock;

import android.os.Bundle;
import android.view.WindowManager;
import android.widget.Button;

import androidx.appcompat.app.AppCompatActivity;

public class LockActivity extends AppCompatActivity {

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().addFlags(
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                        | WindowManager.LayoutParams.FLAG_FULLSCREEN
                        | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
        );

        setContentView(R.layout.activity_lock);

        Button btn = findViewById(R.id.btnUnlock);
        btn.setOnClickListener(v -> finish());
    }

    @Override public void onBackPressed() {
        // disable back to simulate lock
    }
}
