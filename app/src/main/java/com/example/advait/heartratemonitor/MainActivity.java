package com.example.advait.heartratemonitor;

import android.content.DialogInterface;
import android.graphics.Color;
import android.support.v7.app.AlertDialog;
import android.support.v7.app.AppCompatActivity;

import java.util.ArrayList;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.atomic.AtomicBoolean;

import android.content.Context;
import android.content.res.Configuration;
import android.hardware.Camera;
import android.hardware.Camera.PreviewCallback;
import android.os.Bundle;
import android.os.PowerManager;
import android.os.PowerManager.WakeLock;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.widget.TextView;

import com.androidplot.xy.BoundaryMode;
import com.androidplot.xy.LineAndPointFormatter;
import com.androidplot.xy.SimpleXYSeries;
import com.androidplot.xy.XYPlot;

import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;

public class MainActivity extends AppCompatActivity implements SensorEventListener {

    private static final String TAG = "HeartRateMonitor";
    private static final AtomicBoolean processing = new AtomicBoolean(false);

    private static SurfaceHolder previewHolder = null;
    private static Camera camera = null;
    private static TextView bpmText = null;

    private static WakeLock wakeLock = null;

    private static XYPlot plot = null;
    private static final int HISTORY_SIZE = 120;
    private static SimpleXYSeries s1 = null;

    private static List<Double> raw_data = new ArrayList<Double>();
    private static List<Double> dataLpf = new ArrayList<Double>();

    private static List<Integer> bpmArray = new ArrayList<Integer>();

    private static double beats = 0;
    private static long startTime = 0;

    private static String state = "Diastolic";
    static MainActivity ma;

    private static boolean isFingerDialogShowing = false;
    private static boolean isMovementDialogShowing = false;

    AlertDialog alertFinger = null;
    AlertDialog alertMovement = null;

    private SensorManager sensorManager;

    private double oldMagVal = 0;
    private boolean sensorChangedFirst = false;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        SurfaceView preview = (SurfaceView) findViewById(R.id.preview);
        previewHolder = preview.getHolder();
        previewHolder.addCallback(surfaceCallback);
        previewHolder.setType(SurfaceHolder.SURFACE_TYPE_PUSH_BUFFERS);

        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.FULL_WAKE_LOCK, "DoNotDimScreen");

        plot = (XYPlot) findViewById(R.id.plot);

        s1 = new SimpleXYSeries("Mean Red Value over Time");
        s1.useImplicitXVals();

        plot.setDomainBoundaries(0, HISTORY_SIZE, BoundaryMode.FIXED);
        plot.addSeries(s1, new LineAndPointFormatter(
                Color.rgb(245, 0, 87), null, null, null));
        plot.setDomainStepValue(11);
        plot.setDomainLabel("Time");
        plot.setRangeLabel("Mean Red Value");

        bpmText = (TextView) findViewById(R.id.bpmTextView);

        ma = MainActivity.this;

        sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);

        AlertDialog.Builder builder1 = new AlertDialog.Builder(ma);
        builder1.setTitle("Alert")
                .setMessage("Please place the tip of your index finger on the camera flash to start" +
                        " measuring heart beat rate.")
                .setCancelable(true)
                .setPositiveButton(
                "OK",
                new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int id) {
                        isFingerDialogShowing = false;
                        dialog.cancel();
                    }
                });

        AlertDialog.Builder builder2 = new AlertDialog.Builder(ma);
        builder2.setTitle("Alert")
                .setMessage("Movement Detected! Please remain still and avoid moving the phone around for accurate readings.")
                .setCancelable(true)
                .setPositiveButton(
                        "OK",
                        new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface dialog, int id) {
                                isFingerDialogShowing = false;
                                dialog.cancel();
                            }
                        });

        alertFinger = builder1.create();
        alertMovement = builder2.create();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
    }

    @Override
    public void onResume() {
        super.onResume();
        wakeLock.acquire();
        camera = Camera.open();
        startTime = System.currentTimeMillis();
        sensorManager.registerListener(this,
                sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER),
                SensorManager.SENSOR_DELAY_UI);
    }

    @Override
    public void onPause() {
        super.onPause();
        wakeLock.release();
        camera.setPreviewCallback(null);
        camera.stopPreview();
        camera.release();
        camera = null;
        sensorManager.unregisterListener(this);
    }

    private static PreviewCallback previewCallback = new PreviewCallback() {

        @Override
        public void onPreviewFrame(byte[] data, Camera cam) {

            if (data == null) throw new NullPointerException();
            Camera.Size size = cam.getParameters().getPreviewSize();

            String currentState = state;

            if (!processing.compareAndSet(false, true)) return;

            double imgAvg = decodeYUV420SPtoRedSum(data.clone(), size.width , size.height) /
                                                                 (size.width * size.height);

            if (imgAvg < 200 && !isFingerDialogShowing) {
                isFingerDialogShowing = !isFingerDialogShowing;
                ma.showFingerDialog();
                processing.set(false);
                return;
            } else if (imgAvg > 200 && isFingerDialogShowing) {
                ma.hideFingerDialog();
            } else if (imgAvg < 200 && isFingerDialogShowing) {
                processing.set(false);
                return;
            }

            // if should execute first time only
            if (raw_data.size() == 0) {
                raw_data.add(0, imgAvg);
                raw_data.add(1, imgAvg);
                dataLpf.add(0, imgAvg);
                dataLpf.add(1, imgAvg);
            }

            if (raw_data.size() >= 7) {
                raw_data.remove(0);
                dataLpf.remove(0);
                raw_data.add(imgAvg);
                dataLpf.add(lpf(raw_data, dataLpf));

            } else {
                raw_data.add(imgAvg);
                dataLpf.add(lpf(raw_data, dataLpf));
            }

            int totalRedVal = 0;
            for (int i = 0; i < dataLpf.size(); i++) {
                totalRedVal += dataLpf.get(i);
            }

            int averageRed = totalRedVal / dataLpf.size();

            // peak has passed
            if (imgAvg < averageRed) {
                currentState = "Systolic";
                if (!currentState.equals(state)) {
                    beats++;
                }
            // possible peak detected
            } else if (imgAvg > averageRed) {
                currentState = "Diastolic";
            }

            if (!currentState.equals(state)) {
                currentState = state;
            }

            // plot filtered magnitude vs time
            if (s1.size() > HISTORY_SIZE) {
                s1.removeFirst();
            }

            // add the latest history sample
            s1.addLast(null, dataLpf.get(dataLpf.size() - 1));
            plot.redraw();

            long endTime = System.currentTimeMillis();
            double totalTimeInSecs = (endTime - startTime) / 1000d;

            if (totalTimeInSecs >= 10) {

                double bps = (beats / totalTimeInSecs);
                int bpm = (int) (bps * 60d);

                // invalid range - restart calibration
                if (bpm < 20 || bpm > 120) {
                    startTime = System.currentTimeMillis();
                    beats = 0;
                    processing.set(false);
                    return;
                }

                if (bpmArray.size() > 3) {
                    bpmArray.remove(0);
                    bpmArray.add(bpm);
                } else {
                    bpmArray.add(bpm);
                }

                int bpmTotal = 0;
                for (int i = 0; i < bpmArray.size(); i++) {
                    bpmTotal += bpmArray.get(i);
                }

                final int beatsAvg = bpmTotal / bpmArray.size();
                ma.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        bpmText.setText(String.valueOf(beatsAvg));
                    }
                });

                startTime = System.currentTimeMillis();
                beats = 0;
            }
            processing.set(false);
        }
    };

    private static SurfaceHolder.Callback surfaceCallback = new SurfaceHolder.Callback() {
        @Override
        public void surfaceCreated(SurfaceHolder holder) {
            try {
                camera.setPreviewDisplay(previewHolder);
                camera.setPreviewCallback(previewCallback);
            } catch (Throwable t) {
                Log.e("Exception", t.toString());
            }
        }

        @Override
        public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            Camera.Parameters parameters = camera.getParameters();
            parameters.setFlashMode(Camera.Parameters.FLASH_MODE_TORCH);
            Camera.Size size = getSmallestPreviewSize(width, height, parameters);
            if (size != null) {
                parameters.setPreviewSize(size.width, size.height);
            }
            camera.setParameters(parameters);
            camera.setDisplayOrientation(90);
            camera.startPreview();
        }

        @Override
        public void surfaceDestroyed(SurfaceHolder holder) {
        }
    };

    private static Camera.Size getSmallestPreviewSize(int width, int height, Camera.Parameters parameters) {
        Camera.Size result = null;

        for (Camera.Size size : parameters.getSupportedPreviewSizes()) {
            if (size.width <= width && size.height <= height) {
                if (result == null) {
                    result = size;
                } else {
                    int resultArea = result.width * result.height;
                    int newArea = size.width * size.height;

                    if (newArea < resultArea) result = size;
                }
            }
        }
        return result;
    }

    static int decodeYUV420SPtoRedSum(byte[] yuv420sp, int width, int height) {

        final int frameSize = width * height;

        int sum = 0;

        for (int j = 0, yp = 0; j < height; j++) {
            int uvp = frameSize + (j >> 1) * width, u = 0, v = 0;
            for (int i = 0; i < width; i++, yp++) {
                int y = (0xff & yuv420sp[yp]) - 16;
                if (y < 0) y = 0;
                if ((i & 1) == 0) {
                    v = (0xff & yuv420sp[uvp++]) - 128;
                    u = (0xff & yuv420sp[uvp++]) - 128;
                }
                int y1192 = 1192 * y;
                int r = (y1192 + 1634 * v);
                int g = (y1192 - 833 * v - 400 * u);
                int b = (y1192 + 2066 * u);

                if (r < 0) r = 0;
                else if (r > 262143) r = 262143;
                if (g < 0) g = 0;
                else if (g > 262143) g = 262143;
                if (b < 0) b = 0;
                else if (b > 262143) b = 262143;

                int pixel = 0xff000000 | ((r << 6) & 0xff0000) | ((g >> 2) & 0xff00) | ((b >> 10) & 0xff);
                int red = (pixel >> 16) & 0xff;

                sum += red;
            }
        }
        return sum;
    }


    /**
     * Low pass filters input data
     * @param data list of normalized data
     * @param dataLpf list of filtered data
     * @return new data point after filtering
     */
    static double lpf(List<Double> data, List<Double> dataLpf) {

        int size = data.size();
        int sizeLpf = dataLpf.size();
        double ALPHA = 0.7;
        double lastElemLpf = dataLpf.get(sizeLpf - 1);
        return lastElemLpf + ALPHA * (data.get(size - 1) - lastElemLpf);
    }

    void showFingerDialog() {
        ma.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                alertFinger.show();
            }
        });
    }

    void hideFingerDialog() {
        isFingerDialogShowing = false;
        ma.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                alertFinger.dismiss();
            }
        });
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
            getAccelerometer(event);
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }

    private void getAccelerometer(SensorEvent event) {

        double x = event.values[0];
        double y = event.values[1];
        double z = event.values[2];

        double newMagVal = Math.sqrt(Math.pow(x, 2) + Math.pow(y, 2) + Math.pow(z, 2));

        if (!sensorChangedFirst) {
            sensorChangedFirst = true;
            oldMagVal = newMagVal;
        }

        if (((Math.abs(newMagVal) >= (1.2 * Math.abs(oldMagVal)))
             || (Math.abs(newMagVal) <= (0.8 * Math.abs(oldMagVal))))
                && !isMovementDialogShowing) {
            processing.set(false);
            ma.showMovementDialog();
        }

        oldMagVal = newMagVal;
    }

    void showMovementDialog() {
        isMovementDialogShowing = true;
        ma.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                alertMovement.show();
            }
        });
        final Timer timer = new Timer();
        timer.schedule(new TimerTask() {
            public void run() {
                hideMovementDialog();
                isMovementDialogShowing = false;
            }
        }, 2800);
    }

    void hideMovementDialog() {
        ma.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                alertMovement.dismiss();
            }
        });
    }
}
