package com.ffmpeg.avformat;

public class URLContext {
    private final int mNativeInt;
    private boolean mClosed;

    public static URLContext open(String path, int mode) {
        return new URLContext(path, mode);
    }

    private URLContext(String path, int mode) {
        mNativeInt = native_open(path, mode);
    }

    public int write(byte[] buf) {
        return write(buf, 0, buf.length);
    }

    public int write(byte[] buf, int offset, int num) {
        return native_write(mNativeInt, buf, offset, num);
    }

    public int close() {
        if (!mClosed) {
            mClosed = true;
            return native_close(mNativeInt);
        } else {
            return 0;
        }
    }

    int nativeInt() {
        return mNativeInt;
    }

    @Override
    protected void finalize() throws Throwable {
        try {
            close();
        } finally {
            super.finalize();
        }
    }

    private static native int native_open(String path, int mode);
    private static native int native_write(int nativeInt, byte[] buf, int offset, int num);
    private static native int native_close(int nativeInt);

    static {
        System.loadLibrary("ffmpeg_jni");
    }
}
