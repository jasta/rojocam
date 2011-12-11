package org.devtcg.rojocam.ffmpeg;

import android.graphics.Bitmap;
import android.graphics.ImageFormat;

/**
 * Provides limited access to libswscale. Eventually we should extend this API
 * and then handle the resampling and encoding logic in Java.
 */
public class SwsScaler {
    /* XXX: These constants must match libswscale/swscale.h */
    private static final int SWS_FAST_BILINEAR = 1;
    private static final int SWS_BILINEAR = 2;
    private static final int SWS_BICUBIC = 4;

    private final int mNativeInt;

    public static void scale(
            byte[] srcData, int srcFFmpegPixelFormat, int srcWidth, int srcHeight,
            byte[] dstData, int dstFFmpegPixelFormat, int dstWidth, int dstHeight) {
        SwsScaler scaler = new SwsScaler(srcFFmpegPixelFormat, srcWidth, srcHeight,
                dstFFmpegPixelFormat, dstWidth, dstHeight, SWS_BICUBIC);
        nativeScale(scaler.nativeInt(), srcData, dstData);
    }

    public SwsScaler(
            int srcFFmpegPixelFormat, int srcWidth, int srcHeight,
            int dstFFmpegPixelFormat, int dstWidth, int dstHeight,
            int flags) {
        mNativeInt = nativeCreate(
                srcFFmpegPixelFormat, srcWidth, srcHeight,
                dstFFmpegPixelFormat, dstWidth, dstHeight, flags);
    }

    private int nativeInt() {
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

    public static int androidBitmapConfigToPixelFormat(Bitmap.Config config) {
        switch (config) {
            case RGB_565: return nativePixFmtRGB565BE();
            case ARGB_8888: return nativePixFmtRGBA();
            default:
                throw new IllegalArgumentException("Unsupported config=" + config);
        }
    }

    public static int androidImageFormatToPixelFormat(int imageFormat) {
        switch (imageFormat) {
            case ImageFormat.NV21: return nativePixFmtNV21();
            default:
                throw new IllegalArgumentException("Unsupported imageFormat=" + imageFormat);
        }
    }

    private static native int nativeCreate(
            int srcFFmpegPixelFormat, int srcWidth, int srcHeight,
            int dstFFmpegPixelFormat, int dstWidth, int dstHeight, int flags);
    private static native void nativeScale(int nativeInt, byte[] srcData, byte[] dstData);
    private static native void nativeDestroy(int nativeInt);

    private static native int nativePixFmtRGBA();
    private static native int nativePixFmtRGB565BE();
    private static native int nativePixFmtNV21();

    static {
        System.loadLibrary("ffmpeg-jni");
    }
}
