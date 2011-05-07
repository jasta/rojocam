package org.devtcg.rojocam;

import com.android.camera.R;
import com.android.camera.VideoCamera;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.hardware.Camera;
import android.hardware.Camera.CameraInfo;
import android.media.CamcorderProfile;
import android.media.MediaRecorder;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.PowerManager.WakeLock;
import android.util.Log;
import android.view.OrientationEventListener;
import android.widget.Toast;

import java.io.IOException;

public class CaptureVideoService extends Service implements
        MediaRecorder.OnErrorListener, MediaRecorder.OnInfoListener {
    private static final String TAG = CaptureVideoService.class.getSimpleName();

    private boolean mRecording;
    private Camera mCamera;
    private MediaRecorder mRecorder;

    public static WakeLock mWakeLock;

    public static void startCapture(Context context) {
        /*
         * The lock will be released when stop capture is received by the
         * running service.
         */
        takeCaptureLock(context);
        context.startService(new Intent(Constants.ACTION_START_CAPTURE, null,
                context, CaptureVideoService.class));
    }

    public static void stopCapture(Context context) {
        /* XXX: Lock will be released once stop is handled. */
        context.startService(new Intent(Constants.ACTION_STOP_CAPTURE, null,
                context, CaptureVideoService.class));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent.getAction();
        if (Constants.ACTION_START_CAPTURE.equals(action)) {
            startCapture();
            return START_REDELIVER_INTENT;
        } else {
            if (Constants.ACTION_STOP_CAPTURE.equals(action)) {
                stopCapture();
            } else {
                Log.d(TAG, "Unsupported start command: " + intent);
            }
            return START_NOT_STICKY;
        }
    }

    private static synchronized void takeCaptureLock(Context context) {
        if (mWakeLock == null) {
            PowerManager pm = (PowerManager)context.getSystemService(POWER_SERVICE);
            mWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG);
        }
        mWakeLock.acquire();
    }

    private static synchronized void releaseCaptureLock() {
        if (mWakeLock == null || !mWakeLock.isHeld()) {
            throw new IllegalStateException("Releasing capture lock without first acquiring it");
        }
        mWakeLock.release();
    }

    private void startCapture() {
        if (mRecording) {
            Log.w(TAG, "Already capturing, ignoring capture request...");
            return;
        }

        initCamera();
        try {
            startRecorder();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private void stopCapture() {
        stopSelf();
        stopRecorder();
        closeCamera();
        releaseCaptureLock();
    }

    private void initCamera() {
        if (mCamera != null) {
            throw new IllegalStateException("Camera already initialized?");
        }
        mCamera = Camera.open();
        if (mCamera == null) {
            throw new AssertionError("This device does not seem to have a back-facing camera");
        }
    }

    private void closeCamera() {
        mCamera.release();
        mCamera = null;
    }

    private void startRecorder() throws IOException {
        mRecorder = new MediaRecorder();

        mCamera.unlock();
        mRecorder.setCamera(mCamera);
        mRecorder.setAudioSource(MediaRecorder.AudioSource.CAMCORDER);
        mRecorder.setVideoSource(MediaRecorder.VideoSource.CAMERA);
        mRecorder.setProfile(CamcorderProfile.get(CamcorderProfile.QUALITY_HIGH));
        mRecorder.setMaxDuration(0);

        mRecorder.setOutputFile("/sdcard/foo.3gp");

        try {
            mRecorder.prepare();
        } catch (IOException e) {
            stopRecorder();
            throw e;
        }

        mRecorder.setOnErrorListener(this);
        mRecorder.setOnInfoListener(this);

        try {
            mRecorder.start(); // Recording is now started
        } catch (RuntimeException e) {
            stopRecorder();
            throw e;
        }

        mRecording = true;
    }

    private void stopRecorder() {
        if (mRecorder != null) {
            if (mRecording) {
                mRecorder.setOnErrorListener(null);
                mRecorder.setOnInfoListener(null);
                mRecorder.stop();
                mRecording = false;
            }
            mRecorder.reset();
            mRecorder.release();
            mRecorder = null;
        }
        mCamera.lock();
    }

    public void onError(MediaRecorder mr, int what, int extra) {
        Log.w(TAG, "Media recorder error: what=" + what + "; extra=" + extra);
        if (mRecording) {
            stopRecorder();
        }
    }

    public void onInfo(MediaRecorder mr, int what, int extra) {
        Log.i(TAG, "Media recorder info: what=" + what + "; extra=" + extra);
        if (mRecording) {
            stopRecorder();
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
