package org.devtcg.rojocam;

import org.devtcg.rojocam.rtp.SimpleRtspServer;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.media.CamcorderProfile;
import android.media.MediaRecorder;
import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.PowerManager.WakeLock;
import android.util.Log;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.HashMap;

/**
 * Foreground service handling passive recording requests. This service employs
 * a fake system dialog housing our preview surface in order to achieve
 * "headless" video capture on Android.
 */
public class CaptureVideoService extends Service {
    private static final String TAG = CaptureVideoService.class.getSimpleName();

    /* Non-null if recording has been started. */
    private HeadlessCamcorder mCamcorder;
    private SimpleRtspServer mServer;

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
            startServer();
            return START_REDELIVER_INTENT;
        } else {
            if (Constants.ACTION_STOP_CAPTURE.equals(action)) {
                stopServer();
            } else {
                Log.d(TAG, "Unsupported start command: " + intent);
            }
            return START_NOT_STICKY;
        }
    }

    private void startServer() {
        if (mServer != null) {
            throw new IllegalStateException("server already started!");
        }

        try {
            /* XXX: We should only bind on WiFi! */
            mServer = new SimpleRtspServer();
            mServer.bind(new InetSocketAddress((InetAddress)null, 5454));
            mServer.start();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private void stopServer() {
        if (mServer != null) {
            mServer.shutdown();
            mServer = null;
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
        mCamcorder = null;
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
        private VideoStreamSender mVideoSender;
        private VideoStreamReceiver mVideoReceiver;

        public StreamingHeadlessCamcorder() {
            super(CaptureVideoService.this);
        }

        private String enumlikeToString(Integer value, Class<?> klass)
                throws IllegalArgumentException, IllegalAccessException {
            for (Field field: klass.getFields()) {
                if (Modifier.isStatic(field.getModifiers()) &&
                        field.getInt(null) == value) {
                    return field.getName();
                }
            }
            return null;
        }

        private void dumpProfile(CamcorderProfile profile) {
            HashMap<String, Class<?>> enumlikeFields = new HashMap<String, Class<?>>();
            enumlikeFields.put("audioCodec", MediaRecorder.AudioEncoder.class);
            enumlikeFields.put("fileFormat", MediaRecorder.OutputFormat.class);
            enumlikeFields.put("videoCodec", MediaRecorder.VideoEncoder.class);

            System.out.println("profile:");
            for (Field field: profile.getClass().getFields()) {
                if (Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                try {
                    String name = field.getName();
                    Object value = field.get(profile);
                    System.out.print("   " + name + ": " + value);
                    Class<?> enumlikeClass = enumlikeFields.get(name);
                    if (enumlikeClass != null) {
                        String pretty = enumlikeToString((Integer)value, enumlikeClass);
                        System.out.print(" (" + pretty + ")");
                    }
                    System.out.println();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
        }

        @Override
        protected void onRecorderInitialized(MediaRecorder mRecorder) {
            mRecorder.setVideoSource(MediaRecorder.VideoSource.CAMERA);

//            CamcorderProfile profile = CamcorderProfile.get(CamcorderProfile.QUALITY_HIGH);
//            dumpProfile(profile);
//            mRecorder.setProfile(profile);
            /*
             * XXX: These values provided from Sipdroid and used as an initial
             * proof of concept.
             */
            mRecorder.setVideoFrameRate(20);
            mRecorder.setVideoSize(176, 144);
            mRecorder.setVideoEncoder(MediaRecorder.VideoEncoder.H263);

            /*
             * XXX: This sender/receiver pairing is blocking in a way that we
             * really should not permit...
             */
            try {
                mVideoReceiver = new VideoStreamReceiver();
                mVideoReceiver.bind();
                mVideoReceiver.start();
                mVideoSender = new VideoStreamSender();
                mVideoSender.connect();
            } catch (IOException e) {
                Log.d(TAG, "Fatal error initiating video pipe", e);
                throw new RuntimeException(e);
            }

            mRecorder.setOutputFile(mVideoSender.getFileDescriptor());
//            mRecorder.setOutputFile("/sdcard/foo.mp4");

            mRecorder.setOnErrorListener(this);
            mRecorder.setOnInfoListener(this);
        }

        @Override
        protected void onRecorderStopped(MediaRecorder recorder) {
//            if (mVideoSender != null) {
//                mVideoSender.shutdown();
//            }
//            if (mVideoReceiver != null) {
//                mVideoReceiver.shutdown();
//            }
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

    private static class VideoStreamReceiver extends Thread {
        public static final String SOCKET_NAME = "video_stream";

        private LocalServerSocket mSocket;

        public void bind() throws IOException {
            mSocket = new LocalServerSocket(SOCKET_NAME);
        }

        public void shutdown() {
            if (mSocket != null) {
                try {
                    mSocket.close();
                } catch (IOException e) {
                }
            }
        }

        public void run() {
            if (mSocket == null) {
                throw new IllegalStateException("Not bound");
            }

            try {
                LocalSocket client = mSocket.accept();
                System.out.println("Accepting video stream client at pid=" + client.getPeerCredentials().getPid());
                try {
                    InputStream in = client.getInputStream();
                    FileOutputStream out = new FileOutputStream("/sdcard/test.mp4");
                    try {
                        byte[] buf = new byte[16384];
                        int n;
                        while ((n = in.read(buf)) != -1) {
                            out.write(buf, 0, n);
                        }
                        System.out.println("Video stream closing gracefully...");
                    } finally {
                        out.close();
                    }
                } finally {
                    client.close();
                }
            } catch (IOException e) {
                Log.d(TAG, "Video stream server error", e);
            } finally {
                try {
                    mSocket.close();
                } catch (IOException e) {
                }
            }
        }
    }

    private static class VideoStreamSender {
        private final LocalSocket mSocket;

        public VideoStreamSender() {
            mSocket = new LocalSocket();
        }

        public void connect() throws IOException {
            mSocket.connect(new LocalSocketAddress(VideoStreamReceiver.SOCKET_NAME));
        }

        private void checkIsConnected() {
            if (mSocket == null || !mSocket.isConnected()) {
                throw new IllegalStateException("Not connected");
            }
        }

        public FileDescriptor getFileDescriptor() {
            checkIsConnected();
            return mSocket.getFileDescriptor();
        }

        public void shutdown() {
            try {
                mSocket.close();
            } catch (IOException e) {
            }
        }
    }
}
