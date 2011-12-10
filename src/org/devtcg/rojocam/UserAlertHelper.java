package org.devtcg.rojocam;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * Responds to camera events with appropriate user-notifying behaviour such as
 * adding and modifying the status bar icon and playing recording sounds.
 */
public class UserAlertHelper {
    private static final String TAG = UserAlertHelper.class.getSimpleName();

    private final Context mContext;

    private final NotificationManager mNotifMgr;
    private CamcorderNodeService.State mCurrentState;

    private static final int NOTIF_ID = 1;

    public UserAlertHelper(Context context) {
        mContext = context.getApplicationContext();
        mNotifMgr = (NotificationManager)context.getSystemService(Context.NOTIFICATION_SERVICE);
    }

    private Context getContext() {
        return mContext;
    }

    public void changeState(CamcorderNodeService.State state) {
        if (mCurrentState == state) {
            Log.w(TAG, "Redundant state change to " + state + " ignored.");
        } else {
            Log.i(TAG, "Alerting for state " + state);

            switch (state) {
                case ACTIVE:
                case STREAMING:
                    setNotification(state);
                    if (state == CamcorderNodeService.State.STREAMING) {
                        playAlertSound();
                        flashLedTwice();
                    }
                    break;
                case DEACTIVE:
                    removeNotification();
                    break;
            }

            mCurrentState = state;
        }
    }

    private void setNotification(CamcorderNodeService.State state) {
        boolean isStreaming = state == CamcorderNodeService.State.STREAMING;

        Intent intent = new Intent(CamcorderNodeService.ACTION_DEACTIVATE_CAMERA_NODE, null,
                getContext(), CamcorderNodeService.class);

        Notification notif = new Notification();
        notif.icon = isStreaming ? android.R.drawable.stat_sys_phone_call :
                android.R.drawable.stat_sys_upload;
        notif.flags = Notification.FLAG_ONGOING_EVENT | Notification.FLAG_NO_CLEAR;
        notif.setLatestEventInfo(getContext(), getContext().getString(R.string.recording_notif_title),
                getContext().getString(R.string.recording_notif_text),
                PendingIntent.getService(getContext(), 0, intent, 0));

        mNotifMgr.notify(NOTIF_ID, notif);
    }

    private void playAlertSound() {
        /* TODO */
    }

    private void flashLedTwice() {
        /* TODO */
    }

    private void removeNotification() {
        mNotifMgr.cancel(NOTIF_ID);
    }
}
