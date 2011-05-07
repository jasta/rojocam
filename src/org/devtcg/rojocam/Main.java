package org.devtcg.rojocam;

import android.app.Activity;
import android.hardware.Camera;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.view.View;
import android.view.View.OnClickListener;

public class Main extends Activity {
    private boolean mRecording;
    private Camera mCamera;
    private MediaRecorder mRecorder;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);

        findViewById(R.id.button).setOnClickListener(new OnClickListener() {
            public void onClick(View v) {
                if (mRecording) {
                    stopRecording();
                    closeCamera();
                } else {
                    initCamera();
                    startRecording();
                }
            }
        });
    }

    private void initCamera() {
        mCamera = Camera.open();
    }

    private void closeCamera() {
        mCamera.lock();
        mCamera.release();
        mCamera = null;
    }

    private void
}
