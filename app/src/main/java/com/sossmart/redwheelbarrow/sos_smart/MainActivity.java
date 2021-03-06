package com.sossmart.redwheelbarrow.sos_smart;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Location;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.support.v4.app.ActivityCompat;
import android.support.v4.app.NotificationCompat;
import android.support.v7.app.AppCompatActivity;
import android.telephony.SmsManager;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;
import android.location.LocationManager;

import java.io.FileOutputStream;


public class MainActivity extends AppCompatActivity implements SensorEventListener {

    private float lastX, lastY, lastZ;

    private SensorManager sensorManager;
    private Sensor accelerometer;

    private float attractionForce = (float) 9.82; // Earth's Attraction Force
    private int thresholdG = 8;

    private float deltaXMax = 0;
    private float deltaYMax = 0;
    private float deltaZMax = 0;

    private float deltaX = 0;
    private float deltaY = 0;
    private float deltaZ = 0;

    private float deltaAccelMag = 0;
    private float deltaImpactG = 0;
    private float previousDeltaAccelMag = 0;
    private float decelerationVelocity = 0;
    boolean deceleration = false;

    // GUI Variables
    private TextView currentX, currentY, currentZ, maxX, maxY, maxZ;
    private TextView accelMag, impactG;

    Button btn;
    private Button smsButton;

    //private NotificationDecision notificationDecision;

    private static final int NOTIFY_ID = 100;
    private static final String YES_ACTION = "com.sossmart.redwheelbarrow.sos_smart.YES_ACTION";
    private static final String MAYBE_ACTION = "com.sossmart.redwheelbarrow.sos_smart.MAYBE_ACTION";
    private static final String NO_ACTION = "com.sossmart.redwheelbarrow.sos_smart.NO_ACTION";

    private NotificationManager notificationManager;

    private boolean cancelTime = false;
    private int timeRemaining = 0;

    // Shared Preferences Settings
    public static final String PREFS_NAME = "EmergencySettings";
    private final String defaultTrustedNumber = "5148315762";
    private final String defaultEmergencyMessage = "HELP! I just got into a car crash!";
    private static String trustedNumber;
    private static String emergencyMessage;
    private static SharedPreferences settings;
    private static Button mapsButton;

    // GPS Location Variables
    private GPSService myGPS;
    public double longitude = 0;
    public double latitude = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        initializeViews();

        sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        if (sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) != null) {
            // success! we have an accelerometer

            accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
            sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_NORMAL);
            //vibrateThreshold = accelerometer.getMaximumRange() / 2;
        } else {
            // fail! we dont have an accelerometer!
        }

        myGPS = new GPSService();


        // Ask for permission to send and receive sms and access location
        ActivityCompat.requestPermissions(MainActivity.this, new String[]{Manifest.permission.SEND_SMS, Manifest.permission.ACCESS_FINE_LOCATION}, 1);

        // Restore settings for trusted contact and emergency message
        updateSettings();

        // This is for the button to change to the SMS activity
        smsButton = (Button) findViewById(R.id.smsButton);
        mapsButton = (Button) findViewById(R.id.mapsButton);

        // Capture button clicks
        smsButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                Intent smsIntent = new Intent(MainActivity.this, SMS.class);
                startActivity(smsIntent);
            }
        });
        mapsButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                Intent mapsIntent = new Intent(MainActivity.this, MapsActivity.class);
                startActivity(mapsIntent);
            }
        });


        //notificationDecision = new NotificationDecision();

        notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);

        processIntentAction(getIntent());
        getSupportActionBar().hide();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String permissions[], int[] grantResults) {
        switch (requestCode) {
            case 1: {
                // If request is cancelled, the result arrays are empty.
                if (grantResults.length > 0
                        && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                } else {
                    Toast.makeText(MainActivity.this, "Permission denied to send SMS messages", Toast.LENGTH_SHORT).show();
                }
                return;
            }
        }
    }

    private void updateSettings() {
        // Restore settings for trusted contact and emergency message
        settings = getSharedPreferences(PREFS_NAME, 0);
        trustedNumber = settings.getString("trustedContact", defaultTrustedNumber);
        emergencyMessage = settings.getString("emergencyMessage", defaultEmergencyMessage);
    }

    public void initializeViews() { //GUI Init
        accelMag = (TextView) findViewById(R.id.accel_mag_text);
        impactG = (TextView) findViewById(R.id.impact_g_text);

        currentX = (TextView) findViewById(R.id.currentX);
        currentY = (TextView) findViewById(R.id.currentY);
        currentZ = (TextView) findViewById(R.id.currentZ);

        maxX = (TextView) findViewById(R.id.maxX);
        maxY = (TextView) findViewById(R.id.maxY);
        maxZ = (TextView) findViewById(R.id.maxZ);

        btn = (Button) findViewById(R.id.bigRedBanner);
    }

    //onResume() register the accelerometer for listening the events
    protected void onResume() {
        super.onResume();
        sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_NORMAL);
    }

    //onPause() unregister the accelerometer for stop listening the events
    protected void onPause() {
        super.onPause();
        sensorManager.unregisterListener(this);
    }

    @Override
    public void onSensorChanged(SensorEvent sensorEvent) {
        // clean current values
        displayCleanValues();
        // display the current x,y,z accelerometer values
        displayCurrentValues();
        // display the max x,y,z accelerometer values
        displayMaxValues();

        // get the change of the x,y,z values of the accelerometer
        deltaX = Math.abs(lastX - sensorEvent.values[0]);
        deltaY = Math.abs(lastY - sensorEvent.values[1]);
        deltaZ = Math.abs(lastZ - sensorEvent.values[2]);

        // alpha is calculated as t / (t + dT)
        // with t, the low-pass filter's time-constant
        // and dT, the event delivery rate

        final float alpha = (float) 0.8;
        float[] gravity = new float[3];

//        gravity[0] = alpha * gravity[0] + (1 - alpha) * sensorEvent.values[0];
//        gravity[1] = alpha * gravity[1] + (1 - alpha) * sensorEvent.values[1];
//        gravity[2] = alpha * gravity[2] + (1 - alpha) * sensorEvent.values[2];
//
//        deltaX = sensorEvent.values[0] - gravity[0];
//        deltaY = sensorEvent.values[1] - gravity[1];
//        deltaZ = sensorEvent.values[2] - gravity[2];
//
//        deltaX = (lastX - sensorEvent.values[0]);
//        deltaY = (lastY - sensorEvent.values[1]);
//        deltaZ = (lastZ - sensorEvent.values[2]);

        deltaAccelMag = (float) Math.sqrt(deltaX * deltaX + deltaY * deltaY + deltaZ * deltaZ);
        deltaImpactG = deltaAccelMag / attractionForce;

        // if the change is below 2, it is just plain noise
//        if (deltaX < 2)
//            deltaX = 0;
//        if (deltaY < 2)
//            deltaY = 0;
//        if (deltaZ  vibrateThreshold) || (deltaY > vibrateThreshold) || (deltaZ > vibrateThreshold)) {
//            v.vibrate(50);
//        }

        decelerationVelocity = previousDeltaAccelMag - deltaAccelMag;
        if (Math.abs(decelerationVelocity) < 1 && decelerationVelocity < 0) {
            deceleration = true;
        }

        // In an event of a crash:
        if (deltaImpactG > thresholdG && deceleration == true) { // If G is above threshold and in deceleration

            btn.setBackgroundColor(Color.GREEN); // Troubleshooting purposes

            // Start timer
            timerNotification(); // Send SMS if time runs out without any user interactions

            // Resets deceleration
            deceleration = false;
        }


        previousDeltaAccelMag = deltaAccelMag;
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int i) {

    }

    public void timerNotification() {

        new CountDownTimer(30000, 1000) {  // Counting down to 30 000ms with intervals of 1 000ms

            public void onTick(long millisUntilFinished) {
                // Ask user if he/she is okay
                //notificationDecision.showActionButtonsNotification();
                //showToast("Count down..." + millisUntilFinished);
                timeRemaining = (int) millisUntilFinished / 1000;
                showActionButtonsNotification(timeRemaining);

                // If answer, cancel timer
                if (cancelTime) {
                    notificationManager.cancel(100);
                    cancel();
                }
            }

            public void onFinish() {
                // Send SMS if no answers
                //showToast("BOOOOM!");
                //getCurrentLocation();
                sendSMS(trustedNumber, emergencyMessage);
            }
        }.start();

        cancelTime = false;

    }

    public void sendSMS(String phoneNumber, String message) {
        updateSettings();
        PendingIntent pi = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class), 0);
        SmsManager sms = SmsManager.getDefault();
        sms.sendTextMessage(phoneNumber, null, message, pi, null);
    }

    private Intent getNotificationIntent() {
        Intent intent = new Intent(MainActivity.this, MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return intent;
    }

    private void showActionButtonsNotification(int timeRemaining) {
        Intent yesIntent = getNotificationIntent();
        yesIntent.setAction(YES_ACTION);

        Intent maybeIntent = getNotificationIntent();
        maybeIntent.setAction(MAYBE_ACTION);

        Intent noIntent = getNotificationIntent();
        noIntent.setAction(NO_ACTION);

        Notification mBuilder =
                new NotificationCompat.Builder(MainActivity.this)
                        .setContentIntent(PendingIntent.getActivity(this, 0, getNotificationIntent(), PendingIntent.FLAG_UPDATE_CURRENT))
                        .setSmallIcon(R.mipmap.ic_launcher)
                        .setTicker("Action Buttons Notification Received")
                        .setContentTitle("Are you okay?")
                        .setContentText("Sending SOS message in: " + timeRemaining)
                        .setWhen(System.currentTimeMillis())
                        .setPriority(2) // Max = 2, Min = -2
                        .setVisibility(1) // 1 = public
                        .setAutoCancel(true)
                        .setFullScreenIntent(PendingIntent.getActivity(this, 0, getNotificationIntent(), PendingIntent.FLAG_UPDATE_CURRENT), true)
                        .addAction(new NotificationCompat.Action(
                                R.mipmap.ic_thumb_up_black_36dp,
                                getString(R.string.yes),
                                PendingIntent.getActivity(this, 0, yesIntent, PendingIntent.FLAG_UPDATE_CURRENT)))
//                        .addAction(new NotificationCompat.Action(
//                                R.mipmap.ic_thumbs_up_down_black_36dp,
//                                getString(R.string.maybe),
//                                PendingIntent.getActivity(this, 0, maybeIntent, PendingIntent.FLAG_UPDATE_CURRENT)))
//                        .addAction(new NotificationCompat.Action(
//                                R.mipmap.ic_thumb_down_black_36dp,
//                                getString(R.string.no),
//                                PendingIntent.getActivity(this, 0, noIntent, PendingIntent.FLAG_UPDATE_CURRENT)))
                        .build();

        notificationManager.notify(NOTIFY_ID, mBuilder);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        processIntentAction(intent);
        super.onNewIntent(intent);
    }

    private void processIntentAction(Intent intent) {
        if (intent.getAction() != null) {
            switch (intent.getAction()) {
                case YES_ACTION:
                    //Toast.makeText(this, "Yes :)", Toast.LENGTH_SHORT).show();
                    cancelTime = true;
                    break;
                case MAYBE_ACTION:
                    Toast.makeText(this, "Maybe :|", Toast.LENGTH_SHORT).show();
                    cancelTime = true;
                    break;
                case NO_ACTION:
                    Toast.makeText(this, "No :(", Toast.LENGTH_SHORT).show();
                    cancelTime = true;
                    break;
            }
        }
    }

    // Clean display values
    public void displayCleanValues() {
        accelMag.setText("0.0");
        impactG.setText("0.0");

        currentX.setText("0.0");
        currentY.setText("0.0");
        currentZ.setText("0.0");
    }

    // display the current x,y,z accelerometer values
    public void displayCurrentValues() {
        accelMag.setText(Float.toString(deltaAccelMag));
        impactG.setText(Float.toString(deltaImpactG));

        currentX.setText(Float.toString(deltaX));
        currentY.setText(Float.toString(deltaY));
        currentZ.setText(Float.toString(deltaZ));
    }

    // display the max x,y,z accelerometer values
    public void displayMaxValues() {
        if (deltaX > deltaXMax) {
            deltaXMax = deltaX;
            maxX.setText(Float.toString(deltaXMax));
        }
        if (deltaY > deltaYMax) {
            deltaYMax = deltaY;
            maxY.setText(Float.toString(deltaYMax));
        }
        if (deltaZ > deltaZMax) {
            deltaZMax = deltaZ;
            maxZ.setText(Float.toString(deltaZMax));
        }
    }

    // Utility toast function cuz why not
    public void showToast(final String msg) {
        runOnUiThread(new Runnable() {
            public void run() {
                Toast.makeText(MainActivity.this, msg, Toast.LENGTH_SHORT).show();
            }
        });
    }
}
