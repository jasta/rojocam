package org.devtcg.rojocam.ffmpeg;

import org.devtcg.rojocam.rtsp.RtpParticipant;

import android.hardware.Camera.Size;
import android.util.Log;

import java.io.Closeable;
import java.io.IOException;

public class RtpOutputContext implements Closeable {
    private static final String TAG = RtpOutputContext.class.getSimpleName();

    private static final boolean FRAMERATE_DEBUG = true;
    private long mNumFrames;
    private long mStartPTS;
    private int mNumFramesThisSecond;
    private long mStartPTSThisSecond;

    private final FFStreamConfig mStreamConfig;
    private final RtpParticipant mPeer;
    private final int mNativeInt;

    private boolean mClosed;

    private RuntimeException mLeakedException =
            new IllegalStateException("Leaked RtpOutputContext detected!");

    public RtpOutputContext(FFStreamConfig streamConfig, RtpParticipant peer) throws IOException {
        mStreamConfig = streamConfig;
        mPeer = peer;
        mNativeInt = nativeCreate(streamConfig.nativeInt(), System.nanoTime(),
                peer.hostAddress, peer.rtpPort);
    }

    private void checkClosed() throws IllegalStateException {
        if (mClosed) {
            throw new IllegalStateException("This instance is already closed");
        }
    }

    int nativeInt() {
        checkClosed();
        return mNativeInt;
    }

    public synchronized RtpParticipant getPeer() {
        checkClosed();
        return mPeer;
    }

    public synchronized int getLocalRtpPort() {
        checkClosed();
        return nativeGetLocalRtpPort(mNativeInt);
    }

    public synchronized int getLocalRtcpPort() {
        checkClosed();
        return nativeGetLocalRtcpPort(mNativeInt);
    }

    public synchronized void writeFrame(byte[] data, long usecTime, int frameFormat, Size frameSize,
            int frameBitsPerPixel) throws IOException {
        checkClosed();
        if (FRAMERATE_DEBUG) {
            if (mStartPTS == 0) {
                mStartPTS = usecTime;
            }
            if (mStartPTSThisSecond == 0) {
                mStartPTSThisSecond = usecTime;
            }
            long elapsed = usecTime - mStartPTSThisSecond;
            mNumFrames++;
            mNumFramesThisSecond++;
            if (elapsed >= 1000000) {
                double fps = mNumFramesThisSecond / (elapsed / 1000000.0);
                double avgFps = mNumFrames / ((usecTime - mStartPTS) / 1000000.0);
                Log.d(TAG, String.format("Avg. fps=%.02f, current fps=%.02f", avgFps, fps));
                mNumFramesThisSecond = 0;
                mStartPTSThisSecond = usecTime;
            }
        }
        nativeWriteFrame(mNativeInt, data, usecTime, frameFormat,
                frameSize.width, frameSize.height, frameBitsPerPixel);
    }

    public synchronized void close() throws IOException {
        if (!mClosed) {
            mClosed = true;
            nativeClose(mNativeInt);
        }
    }

    @Override
    protected void finalize() throws Throwable {
        try {
            if (!mClosed) {
                Log.w(TAG, "Leaked RtpOutputContext!", mLeakedException);
            }
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
