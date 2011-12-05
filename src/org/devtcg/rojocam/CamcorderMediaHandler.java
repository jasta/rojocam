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
        Log.d(TAG, "desc=" + desc);
        return desc;
//        /*
//         * XXX: I haven't bothered to understand this format well enough to
//         * create a proper builder class.
//         */
//        StringBuilder b = new StringBuilder();
//        b.append("o=- 0 0 IN IP4 127.0.0.1\n");
//        b.append("t=0 0\n");
//        b.append("s=No Title\n");
//        b.append("m=video 0 RTP/AVP 96\n");
//        b.append("a=rtpmap:96 H263-1998/90000\n");
//        b.append("a=control:streamid=0\n");
//        b.append("a=fmtp:96 profile=0; level=40\n");
//        b.append("a=cliprect:0,0,144,176\n");
//        b.append("a=framesize:96 176-144\n");
//        return b.toString();
    }

    public MediaSession createSession(InetAddress client, RtpTransport transport) {
        return new CamcorderSession(client, transport);
    }

    private class CamcorderSession implements MediaSession {
        private RtpOutputContext mRtpOutputContext;
        private final RtpParticipant mParticipant;
        private final RtpTransport mTransport;

        public CamcorderSession(InetAddress client, RtpTransport transport) {
            System.out.println("New session created for " + client.getHostAddress() +
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
             * streaming!
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
            try {
                mRtpOutputContext.close();
            } catch (IOException e) {
                Log.w(TAG, "Error close RTP output context", e);
            }

            mCamcorderRef.release();
        }
    }
}
