package org.devtcg.rojocam.ffmpeg;

/**
 * Configuration for the output stream to be sent over RTP. This describes the
 * streams/codecs themselves as the output format is of course RTP.
 */
public class FFStreamConfig {
    private final int mNativeInt;

    /**
     * Create the "default" stream configuration. The actual configuration for
     * this is held at the native layer for now but should be brought up to the
     * Java layer for maintenance.
     */
    public static FFStreamConfig createDefault() {
        return new FFStreamConfig();
    }

    private FFStreamConfig() {
        mNativeInt = nativeCreate();
    }

    public String getSDPDescription() {
        return nativeGetSDPDescription(mNativeInt);
    }

    int nativeInt() {
        return mNativeInt;
    }

    @Override
    protected void finalize() throws Throwable {
        try {
            nativeDestroy(mNativeInt);
        } finally {
            super.finalize();
        }
    }

    private static native int nativeCreate();
    private static native String nativeGetSDPDescription(int nativeInt);
    private static native void nativeDestroy(int nativeInt);

    static {
        System.loadLibrary("ffmpeg-jni");
    }
}
