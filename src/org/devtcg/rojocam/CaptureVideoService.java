package org.devtcg.rojocam;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.media.CamcorderProfile;
import android.media.MediaRecorder;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.PowerManager.WakeLock;
import android.util.Log;
import android.view.Gravity;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.WindowManager;

import java.io.IOException;

/**
 * Foreground service handling passive recording requests. This service employs
 * a fake system dialog housing our preview surface in order to achieve
 * "headless" video capture on Android.
 * <p>
 * TODO: This service currently blocks the main thread as the MediaRecorder is
 * being prepared and started.
 */
public class CaptureVideoService extends Service implements
        MediaRecorder.OnErrorListener, MediaRecorder.OnInfoListener, SurfaceHolder.Callback {
    private static final String TAG = CaptureVideoService.class.getSimpleName();

    /**
     * The Camera infrastructure requires a surface for preview when recording
     * video, but in our case we want to appear as if we're recording headlessly
     * without any specific foreground activity. To achieve this effect, we will
     * satisfy this requirement with a dummy 1x1 SurfaceView attached to the
     * window manager.
     */
    private SurfaceView mDummySurfaceView;

    /**
     * This holder is only active when the surface is created and ready to use
     * (so, recording can begin).
     */
    private SurfaceHolder mDummySurfaceHolder;

    private static final int NOT_RECORDING = 0;
    private static final int WAITING_FOR_SURFACE = 1;
    private static final int RECORDING = 2;

    private int mRecordingState = NOT_RECORDING;
    private MediaRecorder mRecorder;

    private static final int NOTIF_RECORDING = 0;

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
        if (mRecordingState != NOT_RECORDING) {
            Log.w(TAG, "Already capturing, ignoring capture request...");
            return;
        }

        Intent intent = new Intent(Constants.ACTION_STOP_CAPTURE, null,
                this, CaptureVideoService.class);

        Notification notif = new Notification();
        notif.icon = android.R.drawable.stat_sys_upload;
        notif.flags = Notification.FLAG_ONGOING_EVENT | Notification.FLAG_NO_CLEAR;
        notif.setLatestEventInfo(this, getString(R.string.recording_notif_title),
                getString(R.string.recording_notif_text),
                PendingIntent.getService(this, 0, intent, 0));
        startForeground(NOTIF_RECORDING, notif);

        mRecordingState = WAITING_FOR_SURFACE;
        makeAndAddSurfaceView();
    }

    /**
     * Constructs and registers a dummy SurfaceView to be used as the preview
     * surface for the Camera object. We must wait until after the underlying
     * surface is created before we can proceed with the recording process.
     */
    private void makeAndAddSurfaceView() {
        SurfaceView dummyView = new SurfaceView(this);
        SurfaceHolder holder = dummyView.getHolder();
        holder.addCallback(this);
        holder.setType(SurfaceHolder.SURFACE_TYPE_PUSH_BUFFERS);

        WindowManager wm = (WindowManager)getSystemService(Context.WINDOW_SERVICE);

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(1, 1,
                WindowManager.LayoutParams.TYPE_SYSTEM_ALERT,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP;
        wm.addView(dummyView, params);

        mDummySurfaceView = dummyView;
    }

    private void removeSurfaceView() {
        WindowManager wm = (WindowManager)getSystemService(Context.WINDOW_SERVICE);
        wm.removeView(mDummySurfaceView);
        mDummySurfaceView = null;
    }

    private void stopCapture() {
        if (mRecordingState != NOT_RECORDING) {
            if (mRecordingState == RECORDING) {
                /* XXX: stopRecorder will set mRecordingState for us. */
                stopRecorder();
            } else {
                mRecordingState = NOT_RECORDING;
            }
            removeSurfaceView();
            releaseCaptureLock();
        }
        stopForeground(true);
        stopSelf();
    }

    private void startRecorder() throws IOException {
        mRecorder = new MediaRecorder();
        mRecorder.setAudioSource(MediaRecorder.AudioSource.CAMCORDER);
        mRecorder.setVideoSource(MediaRecorder.VideoSource.CAMERA);
        mRecorder.setProfile(CamcorderProfile.get(CamcorderProfile.QUALITY_HIGH));

        mRecorder.setOutputFile("/sdcard/foo.mp4");

        mRecorder.setPreviewDisplay(mDummySurfaceHolder.getSurface());

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

        mRecordingState = RECORDING;
    }

    private void stopRecorder() {
        if (mRecordingState == RECORDING) {
            mRecorder.setOnErrorListener(null);
            mRecorder.setOnInfoListener(null);
            mRecorder.stop();
        }
        mRecorder.reset();
        mRecorder.release();
        mRecorder = null;
        mRecordingState = NOT_RECORDING;
    }

    public void onError(MediaRecorder mr, int what, int extra) {
        Log.w(TAG, "Media recorder error: what=" + what + "; extra=" + extra);
        if (mRecordingState == RECORDING) {
            stopCapture();
        }
    }

    public void onInfo(MediaRecorder mr, int what, int extra) {
        Log.i(TAG, "Media recorder info: what=" + what + "; extra=" + extra);
        if (mRecordingState == RECORDING) {
            stopCapture();
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        Log.d(TAG, "surfaceChanged: format=" + format + "; width=" + width + "; height=" + height);

        /* XXX: com.android.camera does this check, but why? */
        if (holder.getSurface() == null) {
            Log.d(TAG, "holder.getSurface() is null");
            return;
        }

        mDummySurfaceHolder = holder;

        if (mRecordingState == WAITING_FOR_SURFACE) {
            try {
                /* Start recorder is expected to change the state into RECORDING. */
                Log.d(TAG, "Starting recorder...");
                startRecorder();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }

    public void surfaceCreated(SurfaceHolder holder) {
        Log.d(TAG, "surfaceCreated");
    }

    public void surfaceDestroyed(SurfaceHolder holder) {
        Log.d(TAG, "surfaceDestroyed");
        mDummySurfaceHolder = null;
    }
}
