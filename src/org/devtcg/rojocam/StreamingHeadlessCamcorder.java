package org.devtcg.rojocam;

import org.devtcg.rojocam.H263PayloadGenerator.H263Payload;
import org.jlibrtp.DataFrame;
import org.jlibrtp.Participant;
import org.jlibrtp.RTPAppIntf;
import org.jlibrtp.RTPSession;

import android.content.Context;
import android.media.CamcorderProfile;
import android.media.MediaRecorder;
import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.SystemClock;
import android.util.Log;

import java.io.BufferedInputStream;
import java.io.FileDescriptor;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.net.DatagramSocket;
import java.net.SocketException;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.HashMap;

public class StreamingHeadlessCamcorder extends HeadlessCamcorder
        implements MediaRecorder.OnErrorListener, MediaRecorder.OnInfoListener {
    private static final String TAG = StreamingHeadlessCamcorder.class.getSimpleName();

    /* Hardcoded for now, should eventually choose ports dynamically based on availability. */
    private static final int RTP_PORT = 5000;
    private static final int RTCP_PORT = 5001;

    private VideoStreamSender mVideoSender;
    private VideoStreamReceiver mVideoReceiver;

    private final RTPSession mRtpSession;

    public StreamingHeadlessCamcorder(Context context) throws SocketException {
        super(context);

        DatagramSocket rtpSocket = new DatagramSocket(RTP_PORT);
        DatagramSocket rtcpSocket = new DatagramSocket(RTCP_PORT);

        mRtpSession = new RTPSession(rtpSocket, rtcpSocket);
        mRtpSession.registerRTPSession(mRtpMonitor, null, null);
    }

    /* Callbacks from jlibrtp that we really don't care about but the API requires we use. */
    private final RTPAppIntf mRtpMonitor = new RTPAppIntf() {
        public void userEvent(int type, Participant[] participant) {
            /* Don't care. */
        }

        public void receiveData(DataFrame frame, Participant participant) {
            /* Don't care. */
        }

        public int frameSize(int payloadType) {
            return -1;
        }
    };

    public int getRtpPort() {
        return RTP_PORT;
    }

    public int getRtcpPort() {
        return RTCP_PORT;
    }

    public void addParticipant(Participant p) {
        synchronized (mRtpSession) {
            mRtpSession.addParticipant(p);
        }
    }

    public void removeParticipant(Participant p) {
        synchronized (mRtpSession) {
            mRtpSession.removeParticipant(p);
        }
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
        mRecorder.setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP);
        mRecorder.setVideoFrameRate(20);
        mRecorder.setVideoSize(176, 144);
        mRecorder.setVideoEncodingBitRate(192000);
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
            Log.d(CamcorderNodeService.TAG, "Fatal error initiating video pipe", e);
            throw new RuntimeException(e);
        }

        mRecorder.setOutputFile(mVideoSender.getFileDescriptor());
//        mRecorder.setOutputFile("/sdcard/foo.mp4");

        mRecorder.setOnErrorListener(this);
        mRecorder.setOnInfoListener(this);
    }

    @Override
    protected void onRecorderStopped(MediaRecorder recorder) {
        if (mVideoSender != null) {
            mVideoSender.shutdown();
        }
        if (mVideoReceiver != null) {
            mVideoReceiver.shutdown();
        }
        synchronized (mRtpSession) {
            mRtpSession.endSession();
        }
    }

    public void onError(MediaRecorder mr, int what, int extra) {
        Log.w(CamcorderNodeService.TAG, "Media recorder error: what=" + what + "; extra=" + extra);
        if (isStarted()) {
            stop();
        }
    }

    public void onInfo(MediaRecorder mr, int what, int extra) {
        Log.i(CamcorderNodeService.TAG, "Media recorder info: what=" + what + "; extra=" + extra);
        if (isStarted()) {
            stop();
        }
    }

    private class VideoStreamReceiver extends Thread {
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
                    BufferedInputStream in = new BufferedInputStream(client.getInputStream(), 4096);
                    positionAtPictureStartCode(in);
                    H263PayloadGenerator payloadGen = new H263PayloadGenerator(in, 1400);
                    H263Payload payload;
                    long timestamp = 0;
                    while ((payload = payloadGen.nextPayload()) != null) {
                        if (payload.hasPictureStart() || timestamp == 0) {
                            /*
                             * XXX: This is clearly not correct. Results in
                             * "laggy" video because this basically has nothing
                             * to do with the appropriate RTP timestamp.
                             */
                            timestamp = System.currentTimeMillis();
                        }
                        sendH263Payload(payload, timestamp);
                    }
                    System.out.println("Video stream closing gracefully...");
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

        /**
         * Positions an MPEG4/3GPP stream at the first H263 picture start code.
         * This is done to fast forward past all the MPEG4 headers and begin
         * sending H263 video RTP packets immediately.
         * <p>
         * TODO: Efficiency leaves something to be desired in this method.
         */
        private void positionAtPictureStartCode(BufferedInputStream in) throws IOException {
            byte[] match1 = "mdat".getBytes();
            byte[] match2 = new byte[] { 0, 0 };
            byte[] match = match1;
            int pos = 0;
            int value;
            while (true) {
                if (pos == 0) {
                    in.mark(match.length);
                }
                if ((value = in.read()) == -1) {
                    break;
                }
                if (value == match[pos]) {
                    if (++pos == match.length) {
                        if (match == match1) {
                            System.out.println("mdat found, searching for picture start code...");
                            match = match2;
                            pos = 0;
                        } else {
                            System.out.println("picture start code found.");
                            in.reset();
                            return;
                        }
                    }
                } else if (pos > 0) {
                    in.reset();
                    in.skip(1);
                    pos = 0;
                }
            }
            if (match == match1) {
                System.out.println("mdat not found!");
            } else {
                System.out.println("picture start code not found!");
            }
        }

        private void sendH263Payload(H263Payload payload, long timestamp) {
            synchronized (mRtpSession) {
                Enumeration<Participant> participants = mRtpSession.getParticipants();
                boolean hasParticipants = false;
                while (participants.hasMoreElements()) {
                    Participant p = participants.nextElement();
                    System.out.println("Sending " + payload.getLength() + " bytes to " + p.getCNAME() +
                            " (M=" + (payload.hasEndOfFrame()?1:0) + " P=" + (payload.hasPictureStart()?1:0) + ")");
                    hasParticipants = true;
                }
                if (!hasParticipants) {
                    System.out.println("Holding " + payload.getLength() + " bytes " +
                            " (M=" + (payload.hasEndOfFrame()?1:0) + " P=" + (payload.hasPictureStart()?1:0) + ")");
                }
                /* XXX: This shitty RTP library requires a data copy to send. */
                byte[] copiedData = Arrays.copyOf(payload.getData(), payload.getLength());
                long[][] badApiRet = mRtpSession.sendData(new byte[][] { copiedData }, null,
                        new boolean[] { payload.hasEndOfFrame() }, timestamp, null);
                long[] ret = (badApiRet != null) ? badApiRet[0] : null;
//                System.out.print("sendData returned: ");
//                if (ret == null) {
//                    System.out.print("null");
//                } else {
//                    System.out.print('{');
//                    if (ret.length > 0) {
//                        System.out.print(ret[0]);
//                        for (int i = 1; i < ret.length; i++) {
//                            System.out.print(',');
//                            System.out.print(ret[i]);
//                        }
//                    }
//                    System.out.print('}');
//                }
//                System.out.println();
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

