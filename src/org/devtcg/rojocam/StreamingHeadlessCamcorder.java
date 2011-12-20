package org.devtcg.rojocam;

import org.devtcg.rojocam.UserAlertHelper.SubjectWarning;
import org.devtcg.rojocam.ffmpeg.RtpOutputContext;
import org.devtcg.rojocam.ffmpeg.SwsScaler;
import org.devtcg.rojocam.util.IOUtils;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.hardware.Camera;
import android.hardware.Camera.Parameters;
import android.hardware.Camera.PreviewCallback;
import android.hardware.Camera.Size;
import android.util.Log;

import java.io.IOException;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArraySet;

public class StreamingHeadlessCamcorder extends HeadlessCamcorder {
    private static final String TAG = StreamingHeadlessCamcorder.class.getSimpleName();

    /**
     * We offer a way to warn the camera subject that they are about to be
     * filmed by playing a sound (see UserAlertHelper) and also by flashing the
     * camera's LED for a period before actually sending the live camera feed.
     * This is part of a soon-to-be configurable policy to protect the privacy
     * of individuals that may be under surveillance (e.g. my girlfriend).
     */
    private SubjectWarning mSubjectWarning;

    /**
     * Generated frame explaining that the subject warning system is in effect.
     */
    private static FrameBuf sPleaseWaitFrame;

    private boolean mCanDoTorch;

    private int mPreviewFormat;
    private Size mPreviewSize;
    private int mPreviewBitsPerPixel;

    private final CopyOnWriteArraySet<RtpOutputContext> mReceivers =
            new CopyOnWriteArraySet<RtpOutputContext>();

    public StreamingHeadlessCamcorder(Context context) throws SocketException {
        super(context);
    }

    /**
     * Add a new peer that is to receive the camera feed.
     */
    public void addReceiver(RtpOutputContext rtpContext) {
        mReceivers.add(rtpContext);
    }

    /**
     * Remove a peer from this feed. The camera remains open when all
     * participants are removed but no data will be delivered to any parties.
     */
    public void removeReceiver(RtpOutputContext rtpContext) {
        mReceivers.remove(rtpContext);
    }

    private static boolean isTorchModeSupported(Camera.Parameters params) {
        List<String> flashModes = params.getSupportedFlashModes();
        for (String flashMode: flashModes) {
            if (flashMode.equals(Camera.Parameters.FLASH_MODE_TORCH)) {
                return true;
            }
        }
        return false;
    }

    @Override
    protected void onRecorderInitialized(Camera camera) {
        Camera.Parameters params = camera.getParameters();

        mCanDoTorch = isTorchModeSupported(params);

        /* Eh, this is a pretty lame way of handling policies... fix later once we have more than 1 policy. */
        boolean subjectWarning = SettingsActivity.getPolicy(getContext()).contains(
                CameraPolicy.POLICY_SUBJECT_WARNING);
        if (subjectWarning) {
            mSubjectWarning = new SubjectWarning();
        } else {
            mSubjectWarning = null;
        }

        if (mCanDoTorch && mSubjectWarning != null) {
            params.setFlashMode(Parameters.FLASH_MODE_TORCH);
        }

        List<Integer> formats = params.getSupportedPreviewFormats();
        Log.d(TAG, "Supported formats:");
        for (Integer format: formats) {
            Log.d(TAG, "  format=" + format);
        }

        List<Size> sizes = params.getSupportedPreviewSizes();
        Log.d(TAG, "Supported sizes:");
        for (Size size: sizes) {
            Log.d(TAG, "  size=" + size.width + "x" + size.height);
        }

        params.setPreviewSize(480, 320);

        /*
         * The documentation says NV21 is for image, and NV17 is for video, but
         * it looks like folks are using NV21 for video recording as well...
         */
        params.setPreviewFormat(ImageFormat.NV21);

        camera.setParameters(params);

        mPreviewFormat = params.getPreviewFormat();
        mPreviewSize = params.getPreviewSize();
        mPreviewBitsPerPixel = ImageFormat.getBitsPerPixel(mPreviewFormat);

        camera.setPreviewCallbackWithBuffer(mPreviewCallback);

        addCallbackBuffers(camera, 2);
    }

    private void addCallbackBuffers(Camera camera, int numBuffers) {
        while (numBuffers-- > 0) {
            Size size = mPreviewSize;
            byte[] buf = new FrameBuf(size.width, size.height, mPreviewFormat).getBuffer();
            camera.addCallbackBuffer(buf);
        }
    }

    @Override
    protected void onRecorderStopped(Camera recorder) {
        for (RtpOutputContext rtpContext: mReceivers) {
            IOUtils.closeQuietly(rtpContext);
        }
    }

    private static synchronized byte[] getPleaseWaitFrame(Context context, Size size, int pixelFormat) {
        if (sPleaseWaitFrame == null ||
                !sPleaseWaitFrame.compatibleWith(size.width, size.height, pixelFormat)) {
            sPleaseWaitFrame = new FrameBuf(size.width, size.height, pixelFormat);
            Bitmap source = BitmapFactory.decodeResource(context.getResources(),
                    R.drawable.subject_warning_image);

            ByteBuffer sourceBuf = ByteBuffer.allocate(source.getRowBytes() * source.getHeight());
            source.copyPixelsToBuffer(sourceBuf);
            sourceBuf.rewind();

            byte[] sourceData = sourceBuf.array();
            byte[] destData = sPleaseWaitFrame.getBuffer();

            SwsScaler.scale(sourceData, SwsScaler.androidBitmapConfigToPixelFormat(source.getConfig()),
                    source.getWidth(), source.getHeight(),
                    destData, SwsScaler.androidImageFormatToPixelFormat(pixelFormat),
                    size.width, size.height);
        }
        return sPleaseWaitFrame.getBuffer();
    }

    private final PreviewCallback mPreviewCallback = new PreviewCallback() {
        private boolean mLedOn;

        private void setLedOn(Camera camera, boolean ledOn) {
            if (mCanDoTorch && ledOn != mLedOn) {
                Camera.Parameters params = camera.getParameters();
                params.setFlashMode(ledOn ? Camera.Parameters.FLASH_MODE_TORCH :
                        Camera.Parameters.FLASH_MODE_OFF);
                camera.setParameters(params);
                mLedOn = ledOn;
            }
        }

        public void onPreviewFrame(byte[] data, Camera camera) {
            if (mSubjectWarning != null) {
                if (!mSubjectWarning.isWarningPeriodActive()) {
                    setLedOn(camera, false);
                    mSubjectWarning = null;
                } else {
                    setLedOn(camera, mSubjectWarning.getLedState());
                }
            }
            if (mSubjectWarning != null) {
                /**
                 * If the subject warning system is active, send a special
                 * "coming soon" type of image to the peer while we give the
                 * subject time to react.
                 */
                sendFrame(getPleaseWaitFrame(getContext(), mPreviewSize, mPreviewFormat));
            } else {
                sendFrame(data);
            }
            camera.addCallbackBuffer(data);
        }

        private void sendFrame(byte[] data) {
            /*
             * XXX: We're attempting to send out the encoded frames to all
             * participants as fast as they come in but obviously this doesn't
             * scale at all. We need to process each participant in its own
             * thread, using a sort of frame ringbuffer to keep the preview
             * frames going.
             */
            long now = System.nanoTime() / 1000;
            ArrayList<RtpOutputContext> toRemove = null;
            for (RtpOutputContext rtpContext: mReceivers) {
                try {
                    rtpContext.writeFrame(data, now,
                            mPreviewFormat, mPreviewSize, mPreviewBitsPerPixel);
                } catch (IOException e) {
                    Log.w(TAG, "Error writing to RTP participant: " + rtpContext.getPeer());
                    IOUtils.closeQuietly(rtpContext);
                    if (toRemove == null) {
                        toRemove = new ArrayList<RtpOutputContext>();
                    }
                    toRemove.add(rtpContext);
                }
            }

            if (toRemove != null) {
                for (int i = 0; i < toRemove.size(); i++) {
                    mReceivers.remove(toRemove.get(i));
                }
            }
        }
    };

    private static class FrameBuf {
        private final int width;
        private final int height;
        private final int pixelFormat;

        private final byte[] buf;

        public FrameBuf(int width, int height, int pixelFormat) {
            float bytesPerPixel = ImageFormat.getBitsPerPixel(pixelFormat) / 8f;
            int bufSize = (int)(width * height * bytesPerPixel);
            buf = new byte[bufSize];

            this.width = width;
            this.height = height;
            this.pixelFormat = pixelFormat;
        }

        public boolean compatibleWith(int width, int height, int pixelFormat) {
            return (this.width == width && this.height == height &&
                    this.pixelFormat == pixelFormat);
        }

        public byte[] getBuffer() {
            return buf;
        }
    }
}
