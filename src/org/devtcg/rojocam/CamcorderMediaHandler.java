package org.devtcg.rojocam;

import org.devtcg.rojocam.ffmpeg.FFStreamConfig;
import org.devtcg.rojocam.ffmpeg.RtpOutputContext;
import org.devtcg.rojocam.rtsp.MediaHandler;
import org.devtcg.rojocam.rtsp.MediaSession;
import org.devtcg.rojocam.rtsp.RtpParticipant;
import org.devtcg.rojocam.rtsp.RtpTransport;
import org.devtcg.rojocam.util.ReferenceCounter;

import android.util.Log;

import java.io.IOException;
import java.net.InetAddress;

public class CamcorderMediaHandler implements MediaHandler {
    private static final String TAG = CamcorderMediaHandler.class.getSimpleName();

    private final ReferenceCounter<StreamingHeadlessCamcorder> mCamcorderRef;
    private StreamingHeadlessCamcorder mCamcorder;
    private FFStreamConfig mStreamConfig;

    public CamcorderMediaHandler(ReferenceCounter<StreamingHeadlessCamcorder> camcorderRef) {
        mCamcorderRef = camcorderRef;
        mStreamConfig = FFStreamConfig.createDefault();
    }

    public String onDescribe(String feedUri) {
        String desc = mStreamConfig.getSDPDescription();
        Log.d(TAG, "SDP:");
        Log.d(TAG, desc);
        return desc;
    }

    public MediaSession createSession(InetAddress client, RtpTransport transport) {
        return new CamcorderSession(client, transport);
    }

    private class CamcorderSession implements MediaSession {
        private RtpOutputContext mRtpOutputContext;
        private final RtpParticipant mParticipant;
        private final RtpTransport mTransport;

        public CamcorderSession(InetAddress client, RtpTransport transport) {
            Log.i(TAG, "New session created for " + client.getHostAddress() +
                    ": rtpPort=" + transport.clientRtpPort + ", rtcpPort=" + transport.clientRtcpPort);
            mParticipant = new RtpParticipant(client.getHostAddress(),
                    transport.clientRtpPort, transport.clientRtcpPort);
            mTransport = new RtpTransport(transport);
        }

        public RtpTransport onSetup(String feedUri) throws IOException {
            /*
             * XXX: We're not specifying which stream index we need because
             * currently we only support the video stream, but eventually this
             * should be fixed.
             */
            mRtpOutputContext = new RtpOutputContext(mStreamConfig, mParticipant);

            /*
             * XXX: We need to tie release() in with session expiration rules!
             * As it stands, we can leak and get into a state of perpetual
             * streaming if the peer just disappears and doesn't let us know.
             * What we need is to listen for RTCP traffic as a sort of keep
             * alive and teardown the session if we don't hear back at an
             * expected interval but I don't really see how we can hook into
             * that data from FFmpeg at this time.
             */
            mCamcorder = mCamcorderRef.acquire();

            mTransport.serverRtpPort = mRtpOutputContext.getLocalRtpPort();
            mTransport.serverRtcpPort = mRtpOutputContext.getLocalRtcpPort();

            return mTransport;
        }

        public void onPlay(String feedUri) {
            mCamcorder.addReceiver(mRtpOutputContext);
        }

        public void onPause(String feedUri) {
            mCamcorder.removeReceiver(mRtpOutputContext);
        }

        public void onTeardown(String feedUri) {
            mCamcorder.removeReceiver(mRtpOutputContext);
            try {
                mRtpOutputContext.close();
            } catch (IOException e) {
                Log.w(TAG, "Error close RTP output context", e);
            }

            mCamcorderRef.release();
        }
    }
}
