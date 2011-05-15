package org.devtcg.rojocam;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.media.CamcorderProfile;
import android.media.MediaRecorder;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.PowerManager.WakeLock;
import android.util.Log;

/**
 * Foreground service handling passive recording requests. This service employs
 * a fake system dialog housing our preview surface in order to achieve
 * "headless" video capture on Android.
 */
public class CaptureVideoService extends Service {
    private static final String TAG = CaptureVideoService.class.getSimpleName();

    /* Non-null if recording has been started. */
    private HeadlessCamcorder mCamcorder;

    private static final int NOTIF_RECORDING = 0;

    public static WakeLock sWakeLock;

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
        if (sWakeLock == null) {
            PowerManager pm = (PowerManager)context.getSystemService(POWER_SERVICE);
            sWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG);
        }
        sWakeLock.acquire();
    }

    private static synchronized void releaseCaptureLock() {
        if (sWakeLock == null || !sWakeLock.isHeld()) {
            throw new IllegalStateException("Releasing capture lock without first acquiring it");
        }
        sWakeLock.release();
    }

    private void startCapture() {
        if (mCamcorder != null) {
            Log.w(TAG, "Already capturing, ignoring capture request...");
            return;
        }

        if (sWakeLock == null || !sWakeLock.isHeld()) {
            throw new IllegalStateException("Capturing must hold a wake lock.");
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

        mCamcorder = new StreamingHeadlessCamcorder();
        mCamcorder.start();
    }

    private void stopCapture() {
        if (mCamcorder == null) {
            Log.w(TAG, "Not capturing, ignore stop capture request");
            return;
        }

        mCamcorder.stop();
        stopForeground(true);
        stopSelf();
        releaseCaptureLock();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private class StreamingHeadlessCamcorder extends HeadlessCamcorder
            implements MediaRecorder.OnErrorListener, MediaRecorder.OnInfoListener {
        public StreamingHeadlessCamcorder() {
            super(CaptureVideoService.this);
        }

        @Override
        protected void onRecorderInitialized(MediaRecorder mRecorder) {
            mRecorder.setAudioSource(MediaRecorder.AudioSource.CAMCORDER);
            mRecorder.setVideoSource(MediaRecorder.VideoSource.CAMERA);
            mRecorder.setProfile(CamcorderProfile.get(CamcorderProfile.QUALITY_HIGH));

            mRecorder.setOutputFile("/sdcard/foo.mp4");

            mRecorder.setOnErrorListener(this);
            mRecorder.setOnInfoListener(this);
        }

        public void onError(MediaRecorder mr, int what, int extra) {
            Log.w(TAG, "Media recorder error: what=" + what + "; extra=" + extra);
            if (isStarted()) {
                stop();
            }
        }

        public void onInfo(MediaRecorder mr, int what, int extra) {
            Log.i(TAG, "Media recorder info: what=" + what + "; extra=" + extra);
            if (isStarted()) {
                stop();
            }
        }
    }
}
