package org.devtcg.rojocam.rtsp;

import org.apache.http.HttpException;

public class RtspException extends HttpException {
    private static final long serialVersionUID = -9050519036277898108L;

    public RtspException() {
        super();
    }

    public RtspException(String message) {
        super(message);
    }

    public RtspException(String message, Throwable t) {
        super(message, t);
    }
}
