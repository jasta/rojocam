package org.devtcg.rojocam.ffmpeg;

import org.devtcg.rojocam.rtsp.RtpParticipant;

import android.hardware.Camera.Size;
import android.util.Log;

import java.io.Closeable;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

public class RtpOutputContext implements Closeable {
    private static final String TAG = RtpOutputContext.class.getSimpleName();

    private final FFStreamConfig mStreamConfig;
    private final RtpParticipant mPeer;
    private final int mNativeInt;

    private final AtomicBoolean mClosed = new AtomicBoolean();

    private RuntimeException mLeakedException =
            new IllegalStateException("Leaked RtpOutputContext detected!");

    public RtpOutputContext(FFStreamConfig streamConfig, RtpParticipant peer) throws IOException {
        mStreamConfig = streamConfig;
        mPeer = peer;
        mNativeInt = nativeCreate(streamConfig.nativeInt(), System.nanoTime(),
                peer.hostAddress, peer.rtpPort);
    }

    private void checkClosed() throws IllegalStateException {
        if (mClosed.get()) {
            throw new IllegalStateException("This instance is already closed");
        }
    }

    int nativeInt() {
        checkClosed();
        return mNativeInt;
    }

    public RtpParticipant getPeer() {
        checkClosed();
        return mPeer;
    }

    public int getLocalRtpPort() {
        checkClosed();
        return nativeGetLocalRtpPort(mNativeInt);
    }

    public int getLocalRtcpPort() {
        checkClosed();
        return nativeGetLocalRtcpPort(mNativeInt);
    }

    public void writeFrame(byte[] data, long nanoTime, int frameFormat, Size frameSize,
            int frameBitsPerPixel) throws IOException {
        checkClosed();
        nativeWriteFrame(mNativeInt, data, nanoTime / 1000, frameFormat,
                frameSize.width, frameSize.height, frameBitsPerPixel);
    }

    public void close() throws IOException {
        if (!mClosed.getAndSet(true)) {
            nativeClose(mNativeInt);
        }
    }

    @Override
    protected void finalize() throws Throwable {
        try {
            if (!mClosed.get()) {
                Log.w(TAG, "Leaked RtpOutputContext!", mLeakedException);
            }
            mLeakedException = null;
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
