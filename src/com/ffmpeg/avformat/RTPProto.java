package com.ffmpeg.avformat;

public class RTPProto {
    public static int getLocalRtpPort(URLContext urlContext) {
        return native_getLocalRtpPort(urlContext.nativeInt());
    }

    public static int getLocalRtcpPort(URLContext urlContext) {
        return native_getLocalRtcpPort(urlContext.nativeInt());
    }

    private static native int native_getLocalRtpPort(int nativeInt);
    private static native int native_getLocalRtcpPort(int nativeInt);
}
