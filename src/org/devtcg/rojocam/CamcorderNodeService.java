package org.devtcg.rojocam;

import org.devtcg.rojocam.rtsp.MediaHandler;
import org.devtcg.rojocam.rtsp.MediaSession;
import org.devtcg.rojocam.rtsp.RtpTransport;
import org.devtcg.rojocam.rtsp.SimpleRtspServer;
import org.jlibrtp.Participant;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.PowerManager.WakeLock;
import android.util.Log;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;

/**
 * Service active while this device is being used as a camcorder node in the
 * surveillance network. Implements an RTSP server which controls whether the
 * camcorder is enabled (streaming) or idle. When streaming, the service enters
 * a foreground state.
 */
public class CamcorderNodeService extends Service {
    static final String TAG = CamcorderNodeService.class.getSimpleName();

    private SimpleRtspServer mRtspServer;

    private static final int NOTIF_RECORDING = 0;

    /**
     * Wake lock active only when the camera is active (that is, a stream is
     * being sent).
     */
    private WakeLock mCaptureLock;

    public static void activateCameraNode(Context context) {
        context.startService(new Intent(Constants.ACTION_ACTIVATE_CAMERA_NODE, null,
                context, CamcorderNodeService.class));
    }

    public static void deactivateCameraNode(Context context) {
        /* XXX: Lock will be released once stop is handled. */
        context.startService(new Intent(Constants.ACTION_DEACTIVATE_CAMERA_NODE, null,
                context, CamcorderNodeService.class));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent.getAction();
        if (Constants.ACTION_ACTIVATE_CAMERA_NODE.equals(action)) {
            activateNode();
            return START_REDELIVER_INTENT;
        } else {
            if (Constants.ACTION_DEACTIVATE_CAMERA_NODE.equals(action)) {
                deactivateNode();
            } else {
                Log.d(TAG, "Unsupported start command: " + intent);
            }
            return START_NOT_STICKY;
        }
    }

    private void activateNode() {
        if (mRtspServer != null) {
            Log.d(TAG, "Server already started.");
            return;
        }

        try {
            /* XXX: We should only bind on WiFi! */
            mRtspServer = new SimpleRtspServer();
            mRtspServer.bind(new InetSocketAddress((InetAddress)null, 5454));
            mRtspServer.registerMedia("test1.rtp", mMediaHandler);
            mRtspServer.start();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private void deactivateNode() {
        if (mRtspServer != null) {
            mRtspServer.shutdown();
            mRtspServer = null;
        }
        stopSelf();
    }

    /* XXX: This must be decomposed into MediaHandler and MediaSession.  As it stands makes absolutely no sense. */
    private final MediaHandler mMediaHandler = new MediaHandler() {
        private StreamingHeadlessCamcorder mCamcorder;

        public String onDescribe(String feedUri) {
            /*
             * XXX: I haven't bothered to understand this format well enough to
             * create a proper builder class.
             */
            StringBuilder b = new StringBuilder();
            b.append("o=- 0 0 IN IP4 127.0.0.1\n");
            b.append("t=0 0\n");
            b.append("s=No Title\n");
            b.append("m=video 0 RTP/AVP 96\n");
            b.append("a=rtpmap:96 H263-1998/90000\n");
            b.append("a=control:streamid=0\n");
            b.append("a=framesize:96 176-144\n");
            return b.toString();
        }

        public MediaSession createSession(InetAddress client, RtpTransport transport) {
            return new CamcorderSession(client, transport);
        }

        class CamcorderSession implements MediaSession {
            private final Participant mParticipant;
            private final RtpTransport mTransport;

            public CamcorderSession(InetAddress client, RtpTransport transport) {
                mParticipant = new Participant(client.getHostAddress(),
                        transport.clientRtpPort, transport.clientRtcpPort);
                mTransport = new RtpTransport(transport);
            }

            public RtpTransport onSetup(String feedUri) {
                /*
                 * XXX: We need to tie release() in with session expiration rules!
                 * As it stands, we can leak and get into a state of perpetual
                 * streaming!
                 */
                mCamcorder = mCamcorderRef.acquire();

                mTransport.serverRtpPort = mCamcorder.getRtpPort();
                mTransport.serverRtcpPort = mCamcorder.getRtcpPort();

                return mTransport;
            }

            public void onPlay(String feedUri) {
                mCamcorder.addParticipant(mParticipant);
            }

            public void onPause(String feedUri) {
                mCamcorder.removeParticipant(mParticipant);
            }

            public void onTeardown(String feedUri) {
                mCamcorderRef.release();
            }
        }
    };

    private void takeCaptureLock() {
        if (mCaptureLock == null) {
            PowerManager pm = (PowerManager)getSystemService(POWER_SERVICE);
            mCaptureLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG);
        }
        mCaptureLock.acquire();
    }

    private void releaseCaptureLock() {
        if (mCaptureLock == null || !mCaptureLock.isHeld()) {
            throw new IllegalStateException("Releasing capture lock without first acquiring it");
        }
        mCaptureLock.release();
    }

    private void startForeground() {
        Intent intent = new Intent(Constants.ACTION_DEACTIVATE_CAMERA_NODE, null,
                this, CamcorderNodeService.class);

        Notification notif = new Notification();
        notif.icon = android.R.drawable.stat_sys_upload;
        notif.flags = Notification.FLAG_ONGOING_EVENT | Notification.FLAG_NO_CLEAR;
        notif.setLatestEventInfo(this, getString(R.string.recording_notif_title),
                getString(R.string.recording_notif_text),
                PendingIntent.getService(this, 0, intent, 0));
        startForeground(NOTIF_RECORDING, notif);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private final ReferenceCounter<StreamingHeadlessCamcorder> mCamcorderRef =
            new ReferenceCounter<StreamingHeadlessCamcorder>() {
        @Override
        protected StreamingHeadlessCamcorder onCreate() {
            takeCaptureLock();
            startForeground();

            try {
                StreamingHeadlessCamcorder camcorder =
                    new StreamingHeadlessCamcorder(CamcorderNodeService.this);
                camcorder.start();
                return camcorder;
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        protected void onDestroy(StreamingHeadlessCamcorder instance) {
            instance.stop();
            stopForeground(true);
            releaseCaptureLock();
        }
    };

    private static abstract class ReferenceCounter<T> {
        private int mCount;
        private T mInstance;

        public synchronized T acquire() {
            if (mCount == 0) {
                mInstance = onCreate();
            }
            mCount++;
            return mInstance;
        }

        public synchronized void release() {
            if (mCount < 1) {
                throw new IllegalStateException("Unbalanced release calls");
            }
            mCount--;
            if (mCount == 0) {
                onDestroy(mInstance);
                mInstance = null;
            }
        }

        protected abstract T onCreate();
        protected abstract void onDestroy(T instance);
    }
}
