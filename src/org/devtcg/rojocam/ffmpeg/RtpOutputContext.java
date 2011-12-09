package org.devtcg.rojocam.ffmpeg;

import org.devtcg.rojocam.rtsp.RtpParticipant;

import android.hardware.Camera.Size;

import java.io.Closeable;
import java.io.IOException;

public class RtpOutputContext implements Closeable {
    private final FFStreamConfig mStreamConfig;
    private final RtpParticipant mPeer;
    private final int mNativeInt;

    private boolean mClosed;

    public RtpOutputContext(FFStreamConfig streamConfig, RtpParticipant peer) throws IOException {
        mStreamConfig = streamConfig;
        mPeer = peer;
        mNativeInt = nativeCreate(streamConfig.nativeInt(), System.nanoTime(),
                peer.hostAddress, peer.rtpPort);
    }

    int nativeInt() {
        return mNativeInt;
    }

    public RtpParticipant getPeer() {
        return mPeer;
    }

    public int getLocalRtpPort() {
        return nativeGetLocalRtpPort(mNativeInt);
    }

    public int getLocalRtcpPort() {
        return nativeGetLocalRtcpPort(mNativeInt);
    }

    public void writeFrame(byte[] data, long nanoTime, int frameFormat, Size frameSize,
            int frameBitsPerPixel) throws IOException {
        nativeWriteFrame(mNativeInt, data, nanoTime / 1000, frameFormat,
                frameSize.width, frameSize.height, frameBitsPerPixel);
    }

    public void close() throws IOException {
        if (!mClosed) {
            mClosed = true;
            nativeClose(mNativeInt);
        }
    }

    @Override
    protected void finalize() throws Throwable {
        try {
            close();
        } finally {
            super.finalize();
        }
    }

    private static native int nativeCreate(int streamConfigNativeInt, long nanoTime,
            String peerAddress, int rtpPort);
    private static native int nativeGetLocalRtpPort(int nativeInt);
    private static native int nativeGetLocalRtcpPort(int nativeInt);
    private static native void nativeWriteFrame(int nativeInt, byte[] data, long frameTimeInUsec,
            int frameFormat, int frameWidth, int frameHeight, int frameBitsPerPixel) throws IOException;
    private static native void nativeClose(int nativeInt) throws IOException;

    static {
        System.loadLibrary("ffmpeg-jni");
    }
}
