package com.example.myapplication;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.widget.ImageView;
import androidx.appcompat.app.AppCompatActivity;

public class SplashActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_splash);

        ImageView splashImage = findViewById(R.id.splash_image);

        // Tambahkan animasi pudar
        Animation fadeOut = new AlphaAnimation(1, 0);
        fadeOut.setDuration(1000); // Durasi animasi 1 detik
        fadeOut.setFillAfter(true);

        // Pindah ke MainActivity setelah animasi selesai
        new Handler().postDelayed(() -> {
            splashImage.startAnimation(fadeOut);
            Intent intent = new Intent(SplashActivity.this, MainActivity.class);
            startActivity(intent);
            finish();
        }, 2000); // Tunda selama 2 detik
    }
}
