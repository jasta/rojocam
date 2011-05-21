package org.devtcg.rojocam.rtp;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;

public class RtspSession {
    private static final SecureRandom sRandom = new SecureRandom();

    private final String mSessionId;
    private RtspState mState;

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

    private static String generateSessionId() {
        try {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            byte[] rawId = sha1.digest(String.valueOf(sRandom.nextInt()).getBytes());
            return StringUtils.byteArrayToHexString(rawId);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }
}
