package com.example.myapplication;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Location;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.provider.Settings;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;

import org.eclipse.paho.android.service.MqttAndroidClient;
import org.eclipse.paho.client.mqttv3.IMqttActionListener;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.IMqttToken;
import org.eclipse.paho.client.mqttv3.MqttCallback;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.FileInputStream;
import java.io.InputStream;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.TimeZone;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManagerFactory;

public class HeartRateLocationService extends Service {

    private static final String MQTT_SERVER_URI = "ssl://0a9bf6989c7a448d969de0599ad03ed0.s1.eu.hivemq.cloud:8883";
    private static final String MQTT_USERNAME = "sundalink";
    private static final String MQTT_PASSWORD = "@Sundalink123";
    private static final String MQTT_PUBLISH_TOPIC_BASE = "sundalink/sw";
    private static final String NOTIFICATION_CHANNEL_ID = "HeartRateServiceChannel";

    private SensorManager sensorManager;
    private Sensor heartRateSensor;
    private FusedLocationProviderClient fusedLocationClient;
    private LocationCallback locationCallback;

    private MqttAndroidClient mqttClient;
    private String deviceId;
    private float currentHeartRate = 0;
    private double currentLatitude = 0.0, currentLongitude = 0.0;
    private boolean isEmergency = false;
    private long lastPublishTime = 0;

    private final Handler handler = new Handler();

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d("HeartRateService", "Service started");

        deviceId = retrieveDeviceId();
        initializeMqttClient();
        initializeHeartRateSensor();
        initializeLocationMonitoring();

        createNotificationChannel();
        startForeground(1, getForegroundNotification());
    }

    private void initializeMqttClient() {
        mqttClient = new MqttAndroidClient(getApplicationContext(), MQTT_SERVER_URI, MqttClient.generateClientId());

        try {
            MqttConnectOptions options = new MqttConnectOptions();
            options.setUserName(MQTT_USERNAME);
            options.setPassword(MQTT_PASSWORD.toCharArray());
            options.setSocketFactory(getSSLSocketFactory());

            mqttClient.connect(options).setActionCallback(new IMqttActionListener() {
                @Override
                public void onSuccess(IMqttToken asyncActionToken) {
                    Log.d("MQTT", "Connected to HiveMQ broker");
                    subscribeToMqttTopic();
                }

                @Override
                public void onFailure(IMqttToken asyncActionToken, Throwable exception) {
                    Log.e("MQTT", "Failed to connect to HiveMQ broker", exception);
                    retryMqttConnection();
                }
            });

            mqttClient.setCallback(createMqttCallback());
        } catch (Exception e) {
            Log.e("MQTT", "Error initializing MQTT client", e);
        }
    }

    private void retryMqttConnection() {
        handler.postDelayed(() -> {
            Log.d("MQTT", "Retrying MQTT connection...");
            initializeMqttClient();
        }, 5000);
    }

    private SSLSocketFactory getSSLSocketFactory() throws Exception {
        CertificateFactory cf = CertificateFactory.getInstance("X.509");

        // Baca file sertifikat dari res/raw
        InputStream caInput = getResources().openRawResource(R.raw.isrgrootx1);
        Certificate ca;
        try {
            ca = cf.generateCertificate(caInput);
            Log.d("SSL", "Certificate loaded: " + ((X509Certificate) ca).getSubjectDN());
        } finally {
            caInput.close();
        }

        // Buat KeyStore dan masukkan sertifikat
        KeyStore keyStore = KeyStore.getInstance(KeyStore.getDefaultType());
        keyStore.load(null, null);
        keyStore.setCertificateEntry("ca", ca);

        // Buat TrustManagerFactory menggunakan KeyStore
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(keyStore);

        // Buat SSLContext menggunakan TrustManager
        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(null, tmf.getTrustManagers(), null);

        return sslContext.getSocketFactory();
    }

    private void subscribeToMqttTopic() {
        try {
            mqttClient.subscribe(MQTT_PUBLISH_TOPIC_BASE, 1);
            Log.d("MQTT", "Subscribed to topic: " + MQTT_PUBLISH_TOPIC_BASE);
        } catch (MqttException e) {
            Log.e("MQTT", "Failed to subscribe", e);
        }
    }

    private MqttCallback createMqttCallback() {
        return new MqttCallback() {
            @Override
            public void connectionLost(Throwable cause) {
                Log.e("MQTT", "Connection lost", cause);
            }

            @Override
            public void messageArrived(String topic, MqttMessage message) {
                Log.d("MQTT", "Message arrived: " + new String(message.getPayload()));
            }

            @Override
            public void deliveryComplete(IMqttDeliveryToken token) {
                Log.d("MQTT", "Delivery complete");
            }
        };
    }

    private void initializeHeartRateSensor() {
        sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        if (sensorManager != null) {
            heartRateSensor = sensorManager.getDefaultSensor(Sensor.TYPE_HEART_RATE);
            if (heartRateSensor != null) {
                sensorManager.registerListener(createHeartRateListener(), heartRateSensor, SensorManager.SENSOR_DELAY_NORMAL);
            }
        }
    }

    private SensorEventListener createHeartRateListener() {
        return new SensorEventListener() {
            @Override
            public void onSensorChanged(SensorEvent event) {
                if (event.sensor.getType() == Sensor.TYPE_HEART_RATE) {
                    currentHeartRate = event.values[0];
                    publishDataToMqtt();
                }
            }

            @Override
            public void onAccuracyChanged(Sensor sensor, int accuracy) {
                // No implementation needed
            }
        };
    }

    private void initializeLocationMonitoring() {
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this);
        LocationRequest locationRequest = LocationRequest.create()
                .setInterval(5000)
                .setPriority(LocationRequest.PRIORITY_HIGH_ACCURACY);

        locationCallback = new LocationCallback() {
            @Override
            public void onLocationResult(@NonNull LocationResult locationResult) {
                Location location = locationResult.getLastLocation();
                if (location != null) {
                    currentLatitude = location.getLatitude();
                    currentLongitude = location.getLongitude();
                    publishDataToMqtt();
                }
            }
        };

        if (checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, null);
        }
    }

    private void publishDataToMqtt() {
        long currentTime = System.currentTimeMillis();
        if (mqttClient != null && mqttClient.isConnected() && currentTime - lastPublishTime >= 1000) {
            lastPublishTime = currentTime;
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss");
            sdf.setTimeZone(TimeZone.getDefault()); // Zona waktu perangkat
            String localTimestamp = sdf.format(new Date());
            String payload = String.format(
                    "{\"device\": \"%s\", \"heart_rate\": %.1f, \"latitude\": %.6f, \"longitude\": %.6f, \"emergency\": %b, \"timestamp\": \"%s\"}",
                    deviceId, currentHeartRate, currentLatitude, currentLongitude, isEmergency, localTimestamp
            );

            try {
                mqttClient.publish(MQTT_PUBLISH_TOPIC_BASE, new MqttMessage(payload.getBytes()));
                Log.d("MQTT", "Data published: " + payload);
            } catch (MqttException e) {
                Log.e("MQTT", "Failed to publish data", e);
            }
        }
    }

    private String retrieveDeviceId() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ?
                Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID) :
                Build.SERIAL;
    }

    private Notification getForegroundNotification() {
        return new NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
                .setContentTitle("Heart Rate and Location Monitoring")
                .setContentText("Monitoring and sending data to HiveMQ server.")
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .build();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    NOTIFICATION_CHANNEL_ID,
                    "Heart Rate Service Channel",
                    NotificationManager.IMPORTANCE_LOW
            );
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}