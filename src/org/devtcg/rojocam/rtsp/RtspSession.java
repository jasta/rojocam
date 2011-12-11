package org.devtcg.rojocam.rtsp;

import java.security.SecureRandom;

public class RtspSession {
    private static final SecureRandom sRandom = new SecureRandom();

    private final String mSessionId;
    private RtspState mState;
    private MediaSession mMediaSession;

    /**
     * RTSP server states according to RFC2326.
     */
    public enum RtspState {
        READY, PLAYING
    }

    public RtspSession(RtspState state) {
        this(generateSessionId(), state);
    }

    public RtspSession(String sessionId, RtspState state) {
        mSessionId = sessionId;
        mState = state;
    }

    public String getSessionId() {
        return mSessionId;
    }

    public RtspState getState() {
        return mState;
    }

    public void setState(RtspState state) {
        mState = state;
    }

    public void setMediaSession(MediaSession media) {
        mMediaSession = media;
    }

    public MediaSession getMediaSession() {
        return mMediaSession;
    }

    private static String generateSessionId() {
        return Long.toHexString(sRandom.nextLong());
    }
}
