package org.devtcg.rojocam;

import org.devtcg.rojocam.util.DetachableResultReceiver;
import org.devtcg.rojocam.util.DetachableResultReceiver.Receiver;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

public class ControllerActivity extends Activity implements OnClickListener {
    private static final int COMMAND_NONE = 0;
    private static final int COMMAND_START = 1;
    private static final int COMMAND_STOP = 2;

    private WifiManager mWifiMgr;

    private int mPendingCommand;

    private Button mStart;
    private Button mStop;

    private TextView mWifiState;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.controller);

        mWifiMgr = (WifiManager)getSystemService(WIFI_SERVICE);

        mWifiState = (TextView)findViewById(R.id.wifi_state);

        mStart = (Button)findViewById(R.id.start);
        mStop = (Button)findViewById(R.id.stop);
        findViewById(R.id.settings).setOnClickListener(this);
        mStart.setOnClickListener(this);
        mStop.setOnClickListener(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        sStaticReceiver.setReceiver(mReceiver);

        IntentFilter filter = new IntentFilter();
        filter.addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION);
        registerReceiver(mWifiEventReceiver, filter);

        adjustButtonState();
        updateWifiState();
    }

    @Override
    protected void onPause() {
        super.onStop();
        sStaticReceiver.clearReceiver();

        unregisterReceiver(mWifiEventReceiver);
    }

    private static String formatAddr(int ipAddr) {
        StringBuilder ipStr = new StringBuilder();
        ipStr.append(ipAddr & 0xff);
        for (int i = 8; i <= 24; i += 8) {
            ipStr.append('.');
            ipStr.append((ipAddr >> i) & 0xff);
        }
        return ipStr.toString();
    }

    private void updateWifiState() {
        WifiInfo wifiInfo = mWifiMgr.getConnectionInfo();
        if (wifiInfo != null) {
            String ip = formatAddr(wifiInfo.getIpAddress());
            mWifiState.setText(getString(R.string.wifi_state_connected, ip));
        } else {
            mWifiState.setText(getString(R.string.wifi_state_disconnected));
        }
    }

    private final BroadcastReceiver mWifiEventReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (WifiManager.NETWORK_STATE_CHANGED_ACTION.equals(action)) {
                updateWifiState();
            }
        }
    };

    private void adjustButtonState() {
        boolean isActive = CamcorderNodeService.isActive();
        mStart.setEnabled(!isActive);
        mStop.setEnabled(isActive);
    }

    public void onClick(View v) {
        switch (v.getId()) {
            case R.id.start:
                mPendingCommand = COMMAND_START;
                CamcorderNodeService.activateCameraNode(this, sStaticReceiver);
                break;
            case R.id.stop:
                mPendingCommand = COMMAND_STOP;
                CamcorderNodeService.deactivateCameraNode(this, sStaticReceiver);
                break;
            case R.id.settings:
                SettingsActivity.show(this);
                break;
        }
    }

    private final Receiver mReceiver = new Receiver() {
        @Override
        public void onReceiveResult(int resultCode, Bundle resultData) {
            if (mPendingCommand == COMMAND_START) {
                switch (resultCode) {
                    case CamcorderNodeService.RESULT_CODE_ERROR:
                        String errorText = resultData.getString(CamcorderNodeService.RESULT_ERROR_TEXT);
                        Toast.makeText(ControllerActivity.this, errorText, Toast.LENGTH_SHORT).show();
                        break;
                    case CamcorderNodeService.RESULT_CODE_SUCCESS:
                        Toast.makeText(ControllerActivity.this, R.string.started_successfully,
                                Toast.LENGTH_SHORT).show();
                        break;
                }
            }
            mPendingCommand = COMMAND_NONE;
            adjustButtonState();
        }
    };

    private static final DetachableResultReceiver sStaticReceiver =
            new DetachableResultReceiver(new Handler());
}
