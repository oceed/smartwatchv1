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
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.json.JSONException;
import org.json.JSONObject;

public class HeartRateLocationService extends Service {

    private SensorManager sensorManager;
    private Sensor heartRateSensor;
    private SensorEventListener heartRateListener;
    private static final long ACTIVE_INTERVAL_MS = 1000;
    private FusedLocationProviderClient fusedLocationClient;
    private LocationCallback locationCallback;
    private String deviceId; // Menyimpan deviceId yang dinamis
    private String mqttPublishTopic;
    private String mqttSubscribeTopic;
    private static final String MQTT_CONTROL_TOPIC = "safetrip/active";
    private MqttAndroidClient mqttClient;
    private static final String MQTT_SERVER_URI = "tcp://192.168.1.170:1883"; // Ganti dengan IP server Anda

    private float currentHeartRate = 0;
    private double currentLatitude = 0.0, currentLongitude = 0.0;

    private final Handler handler = new Handler();
    private final Handler idleHandler = new Handler();
    private boolean isIdleMode = false;
    private long lastPublishTime = 0;
    private boolean isPublishing = true;
    private boolean isEmergency = false;

    private static final long IDLE_INTERVAL_MS = 3600000;

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d("HeartRateService", "Service started");

        deviceId = retrieveDeviceId();

        mqttPublishTopic = "safetrip/sw/" + deviceId;
        mqttSubscribeTopic = "safetrip/call/" + deviceId;

        initializeMqttClient();

        sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        if (sensorManager != null) {
            heartRateSensor = sensorManager.getDefaultSensor(Sensor.TYPE_HEART_RATE);
        }

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this);

        startMonitoring();

//        startHeartRateMonitoring();
//        startLocationMonitoring();

        createNotificationChannel();
        startForeground(1, getForegroundNotification());
    }

    private void startMonitoring() {
        if (isIdleMode) {
            startIdleMode();
        } else {
            startHeartRateMonitoring();
            startLocationMonitoring();
        }
    }

    private void startIdleMode() {
        if (!isIdleMode) {
            isIdleMode = true;
            isPublishing = false; // Nonaktifkan publikasi aktif
            idleHandler.postDelayed(idleTask, IDLE_INTERVAL_MS);
            Log.d("anjay", "Started idle mode. Data will be sent every 10 seconds.");
        }
    }

    private final Runnable idleTask = new Runnable() {
        @Override
        public void run() {
            if (isIdleMode) {
                Log.d("anjay", "idle mode jalan");
                retrieveDataOnce();
                idleHandler.postDelayed(this, IDLE_INTERVAL_MS);
            }
        }
    };

    private void retrieveDataOnce() {
        // Ambil heart rate satu kali
        if (heartRateSensor != null) {
            SensorEventListener tempListener = new SensorEventListener() {
                @Override
                public void onSensorChanged(SensorEvent event) {
                    if (event.sensor.getType() == Sensor.TYPE_HEART_RATE) {
                        currentHeartRate = event.values[0];
                        sensorManager.unregisterListener(this);
                    }
                }

                @Override
                public void onAccuracyChanged(Sensor sensor, int accuracy) {
                }
            };
            sensorManager.registerListener(tempListener, heartRateSensor, SensorManager.SENSOR_DELAY_NORMAL);
        }

        // Ambil lokasi satu kali
        if (checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            fusedLocationClient.getLastLocation().addOnSuccessListener(location -> {
                if (location != null) {
                    currentLatitude = location.getLatitude();
                    currentLongitude = location.getLongitude();
                }
                publishDataToMqtt();
            });
        }
        Log.d("anjay", "data kekirim");
    }

    private void stopIdleMode() {
        if (isIdleMode) {
            isIdleMode = false;
            isPublishing = true; // Aktifkan kembali publikasi aktif
            idleHandler.removeCallbacks(idleTask);
            Log.d("anjay", "Exited idle mode.");
        }
    }

    private void initializeMqttClient() {
        String clientId = MqttClient.generateClientId();
        mqttClient = new MqttAndroidClient(getApplicationContext(), MQTT_SERVER_URI, clientId);

        try {
            IMqttToken token = mqttClient.connect();
            token.setActionCallback(new IMqttActionListener() {
                @Override
                public void onSuccess(IMqttToken asyncActionToken) {
                    Log.d("MQTT", "Connected to MQTT broker");
                    updateMqttStatus("Connected");

                    try {
                        mqttClient.subscribe(mqttSubscribeTopic, 1);
                        mqttClient.subscribe(MQTT_CONTROL_TOPIC, 1); // Subscribe to control topic
                        Log.d("MQTT", "Subscribed to topics: " + mqttSubscribeTopic + ", " + MQTT_CONTROL_TOPIC);
                    } catch (MqttException e) {
                        Log.e("MQTT", "Failed to subscribe", e);
                    }

                    mqttClient.setCallback(new MqttCallback() {
                        @Override
                        public void connectionLost(Throwable cause) {
                            Log.e("MQTT", "Connection lost", cause);
                        }

                        @Override
                        public void messageArrived(String topic, MqttMessage message) throws Exception {
                            String receivedMessage = new String(message.getPayload());
                            if (topic.equals(mqttSubscribeTopic)) {
                                handleIncomingMessage(receivedMessage);
                            } else if (topic.equals(MQTT_CONTROL_TOPIC)) {
                                handleControlMessage(receivedMessage);
                            }
                        }

                        @Override
                        public void deliveryComplete(IMqttDeliveryToken token) {
                            Log.d("MQTT", "Delivery complete");
                        }
                    });
                }

                @Override
                public void onFailure(IMqttToken asyncActionToken, Throwable exception) {
                    Log.e("MQTT", "Failed to connect to MQTT broker", exception);
                    updateMqttStatus("Disconnected");
                    retryMqttConnection();
                }
            });
        } catch (Exception e) {
            Log.e("MQTT", "Error initializing MQTT client", e);
            updateMqttStatus("Error");
        }
    }

    private void handleControlMessage(String message) {
        if (message.equalsIgnoreCase("stop")) {
            Log.e("anjay", "Received stop command");
            resetMonitoring();
            isPublishing = false; // Hentikan publikasi aktif
            startIdleMode();
        } else if (message.equalsIgnoreCase("start")) {
            Log.e("anjay", "Received start command");
            resetMonitoring();
            isPublishing = true; // Aktifkan publikasi
            startPublishing();
        }
    }

    private void stopPublishing() {
        stopHeartRateMonitoring();
        stopLocationMonitoring();
        stopIdleMode();
        Log.e("anjay", "Publishing and services stopped");
    }

    private void startPublishing() {
        isPublishing = true;
        startHeartRateMonitoring();
        startLocationMonitoring();
        Log.e("anjay", "Publishing and services started");
    }

    private void publishDataToMqtt() {
//        if (!isPublishing) return;

        long currentTime = System.currentTimeMillis();
        long publishInterval = isIdleMode ? IDLE_INTERVAL_MS : ACTIVE_INTERVAL_MS;

        if (currentTime - lastPublishTime >= publishInterval) {
            lastPublishTime = currentTime;
            if (mqttClient != null && mqttClient.isConnected()) {
                try {
                    String payload = String.format(
                            "{\"device\": \"%s\", \"heart_rate\": %.1f, \"latitude\": %.6f, \"longitude\": %.6f, \"emergency\": %d, \"timestamp\": \"%s\"}",
                            deviceId,
                            currentHeartRate,
                            currentLatitude,
                            currentLongitude,
                            isEmergency ? 1 : 0,
                            new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new java.util.Date())
                    );
                    MqttMessage message = new MqttMessage(payload.getBytes());
                    message.setQos(1);
                    mqttClient.publish(mqttPublishTopic, message);
                    Log.d("MQTT", "Data published: " + payload);
                } catch (MqttException e) {
                    Log.e("MQTT", "Failed to publish data", e);
                }
            } else {
                Log.w("MQTT", "Cannot publish, MQTT client not connected");
            }
        }
    }

    private void startHeartRateMonitoring() {
        if (heartRateSensor != null && isPublishing) {
            heartRateListener = new SensorEventListener() {
                @Override
                public void onSensorChanged(SensorEvent event) {
                    if (event.sensor.getType() == Sensor.TYPE_HEART_RATE) {
                        currentHeartRate = event.values[0];
                        handler.post(() -> publishDataToMqtt());
                    }
                }

                @Override
                public void onAccuracyChanged(Sensor sensor, int accuracy) {
                }
            };
            sensorManager.registerListener(heartRateListener, heartRateSensor, SensorManager.SENSOR_DELAY_NORMAL);
        }
    }

    private void stopHeartRateMonitoring() {
        if (sensorManager != null && heartRateListener != null) {
            sensorManager.unregisterListener(heartRateListener);
            heartRateListener = null;
        }
    }

    private void startLocationMonitoring() {
        if (isPublishing) {
            LocationRequest locationRequest = LocationRequest.create();
            locationRequest.setInterval(5000);
            locationRequest.setPriority(LocationRequest.PRIORITY_HIGH_ACCURACY);

            locationCallback = new LocationCallback() {
                @Override
                public void onLocationResult(@NonNull LocationResult locationResult) {
                    Location location = locationResult.getLastLocation();
                    if (location != null) {
                        currentLatitude = location.getLatitude();
                        currentLongitude = location.getLongitude();
                        handler.post(() -> publishDataToMqtt());
                    } else {
                        Log.w("Location", "Location is null");
                    }
                }
            };

            if (checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, null);
            } else {
                Log.e("Location", "Location permission not granted");
            }
        }
    }

    private void stopLocationMonitoring() {
        if (fusedLocationClient != null && locationCallback != null) {
            fusedLocationClient.removeLocationUpdates(locationCallback);
            locationCallback = null;
        }
    }

    // Fungsi untuk mendapatkan ID perangkat yang unik
    private String retrieveDeviceId() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return Settings.Secure.getString(getApplicationContext().getContentResolver(), Settings.Secure.ANDROID_ID);
        } else {
            return Build.SERIAL; // Untuk perangkat lama
        }
    }

    private void resetMonitoring() {
        stopHeartRateMonitoring();
        stopLocationMonitoring();
        stopIdleMode();
    }

    // Metode menyimpan data lokal
    private void saveToLocalStorage(String payload) {
        // Anda bisa menyimpan ke SQLite atau file lokal untuk pengiriman ulang
        Log.d("LocalStorage", "Saving data locally: " + payload);
    }

    private void retryMqttConnection() {
        new android.os.Handler().postDelayed(() -> {
            Log.d("MQTT", "Retrying MQTT connection...");
            initializeMqttClient();
        }, 5000); // Retry after 5 seconds
    }

    private void updateMqttStatus(String status) {
        Log.d("MQTT", "Broadcast sent for status: " + status);
        Intent intent = new Intent("MQTT_STATUS_UPDATE");
        intent.putExtra("status", status);
        sendBroadcast(intent);
    }
    // Fungsi untuk menangani pesan masuk dari topik safetrip/call/{deviceId}
    private void handleIncomingMessage(String message) {
        try {
            JSONObject jsonObject = new JSONObject(message);
            String notificationMessage = jsonObject.optString("message", "No message");
            sendNotification(notificationMessage);
        } catch (JSONException e) {
            Log.e("MQTT", "Invalid message format: " + message, e);
        }
    }

    // Fungsi untuk menampilkan notifikasi
    private void sendNotification(String message) {
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, "HeartRateServiceChannel")
                .setContentTitle("Notification from MQTT")
                .setContentText(message)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true);

        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) {
            manager.notify(1, builder.build());
        }
    }

    private Notification getForegroundNotification() {
        return new NotificationCompat.Builder(this, "HeartRateServiceChannel")
                .setContentTitle("Heart Rate and Location Monitoring")
                .setContentText("Monitoring and sending data to MQTT server.")
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .build();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    "HeartRateServiceChannel",
                    "Heart Rate Service Channel",
                    NotificationManager.IMPORTANCE_LOW // Ubah ke LOW untuk mengurangi gangguan
            );
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && intent.hasExtra("emergency")) {
            isEmergency = intent.getBooleanExtra("emergency", false);
            Log.d("HeartRateService", "Emergency status updated: " + isEmergency);
        }
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null; // We don't provide binding
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        super.onTaskRemoved(rootIntent);
        // Restart service with AlarmManager
        Intent restartServiceIntent = new Intent(this, HeartRateLocationService.class);
        PendingIntent pendingIntent = PendingIntent.getService(
                this,
                1,
                restartServiceIntent,
                PendingIntent.FLAG_IMMUTABLE
        );
        AlarmManager alarmManager = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
        if (alarmManager != null) {
            alarmManager.setExact(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + 1000, pendingIntent);
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (sensorManager != null && heartRateSensor != null) {
            sensorManager.unregisterListener((SensorEventListener) this);
        }
        if (fusedLocationClient != null) {
            fusedLocationClient.removeLocationUpdates(locationCallback);
        }
        if (mqttClient != null && mqttClient.isConnected()) {
            try {
                mqttClient.disconnect();
            } catch (Exception e) {
                Log.e("MQTT", "Error disconnecting MQTT client", e);
            }
        }
    }
}
