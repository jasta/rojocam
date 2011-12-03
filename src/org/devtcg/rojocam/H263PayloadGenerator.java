package org.devtcg.rojocam;

import java.io.IOException;
import java.io.InputStream;

/**
 * Generates payload messages from an H263+ video stream to be sent over RTP.
 * <p>
 * This code reflects the subset of RFC4629 that we're interested in for
 * transmitting video. In retrospect, I should have just used libffmpeg to do
 * all this work for me.
 */
public class H263PayloadGenerator {
    private final InputStream mStream;

    private final H263Payload mPayload;
    private boolean mNextHasPictureStart;
    private int mLastPictureStart = -1;

    private static final byte[] PICTURE_START = { 0, 0 };

    /**
     * Size of the H263+ payload header.
     */
    private static final int HEADER_SIZE = 2;

    /**
     * Buffer representing our building or built H263 payload (includes the H263
     * payload header).
     */
    private final byte[] mBuf;

    /**
     * Total number of bytes read into mBuf.
     */
    private int mCount;

    /**
     * Basically, this is {@link #mCount} except it includes
     * {@link #HEADER_SIZE}. Held as a separate variable for convenience
     * purposes.
     */
    private int mPos = HEADER_SIZE;

    public H263PayloadGenerator(InputStream in) {
        this(in, 1400);
    }

    public H263PayloadGenerator(InputStream in, int maxPayloadSize) {
        mStream = in;
        mBuf = new byte[maxPayloadSize];
        mPayload = new H263Payload(mBuf);
    }

    private H263Payload preparePayload(boolean endOfFrame, int len) {
        mPayload.marker = endOfFrame;
        mPayload.hasPictureStart(mNextHasPictureStart);
        mNextHasPictureStart = endOfFrame;
        mPayload.len = len;
        return mPayload;
    }

    /**
     * Returns an internal instance of the payload that can now be delivered
     * via RTP (this is the payload portion of the RTP packet only).
     * <p>
     * The object returned is an internal instance and is only valid until
     * your next call to this method.
     */
    public H263Payload nextPayload() throws IOException {
        /*
         * The previous payload found a picture start code (terminating the
         * last frame) so we need to adjust the buffer to position that next
         * picture start code at the beginning for this next payload.
         */
        if (mLastPictureStart >= 0) {
            int pictureOffset = mLastPictureStart - HEADER_SIZE;
            System.arraycopy(mBuf, mLastPictureStart, mBuf, HEADER_SIZE, mCount - pictureOffset);
            mLastPictureStart = -1;
            mPos -= pictureOffset;
            mCount -= pictureOffset;
        }

        /*
         * Try to keep the buffer full at all times to minimize the number of
         * frames that span multiple packets.
         */
        if (mPos < mBuf.length) {
            int n = mStream.read(mBuf, mPos, mBuf.length - mPos);
            if (n == -1) {
                if (mCount == 0) {
                    return null;
                }
            } else {
                mPos += n;
                mCount += n;
            }
        }

        int findOffset;
        if (mNextHasPictureStart) {
            /*
             * The first part of our video stream has the picture start so
             * search beyond that initial position for the end of the
             * current frame.
             */
            findOffset = PICTURE_START.length;
        } else {
            findOffset = 0;
        }

        int pictureStart = findSeq(mBuf, findOffset + HEADER_SIZE, mCount - findOffset, PICTURE_START);
        if (pictureStart == HEADER_SIZE) {
            /* This must be the first packet since mNextHasPictureStart defaults to false but in this case
             * it's clearly wrong. */
            mNextHasPictureStart = true;
            return nextPayload();
        } else if (pictureStart > HEADER_SIZE) {
            /*
             * We need to shift the array back to position it at picture
             * start for the next payload but we can't modify mBuf until
             * after our next call to this method.
             */
            preparePayload(true, pictureStart);
            mLastPictureStart = pictureStart;
        } else {
            preparePayload(false, mCount + HEADER_SIZE);
            mPos = HEADER_SIZE;
            mCount = 0;
        }
        return mPayload;
    }

    private static int findSeq(byte[] b, int offset, int length, byte[] sequence) {
        for (int n = length - sequence.length + 1; n > 0; n--, offset++) {
            int pos = 0;
            while (b[offset+pos] == sequence[pos]) {
                if (++pos == sequence.length) {
                    return offset;
                }
            }
        }
        return -1;
    }

    public static class H263Payload {
        private byte[] buf;
        private int len;
        private boolean marker;
        private boolean pictureStart;

        private H263Payload(byte[] buf) {
            this.buf = buf;
        }

        public byte[] getData() {
            return buf;
        }

        public int getLength() {
            return len;
        }

        public boolean hasEndOfFrame() {
            return marker;
        }

        public boolean hasPictureStart() {
            return pictureStart;
        }

        private void hasPictureStart(boolean hasIt) {
            /* Set the P bit in the H263 payload header. */
            buf[0] = hasIt ? (byte)4 : 0;
            pictureStart = hasIt;
        }
    }
}
