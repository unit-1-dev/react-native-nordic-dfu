package com.pilloxa.dfu;

import no.nordicsemi.android.dfu.DfuBaseService;
import android.app.Activity;
import android.app.ForegroundServiceStartNotAllowedException;
import android.content.Intent;
import android.os.Build;
import android.util.Log;
import androidx.annotation.Nullable;

public class DfuService extends DfuBaseService {

    private static final String TAG = "DfuService";

    // Recovery applies once per service instance. Without this, a rejection from a LATER
    // startForegroundService() (the connection/disconnection self-restarts at DfuBaseService
    // :1361/:1424) would be mistaken for the initial one and re-enter onHandleIntent with a
    // DFU already in progress.
    private boolean bgFallbackApplied;

    @Override
    protected Class<? extends Activity> getNotificationTarget() {
        /*
         * As a target activity the NotificationActivity is returned, not the MainActivity. This is because
         * the notification must create a new task:
         *
         * intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
         *
         * when you press it. You can use NotificationActivity to check whether the new activity
         * is a root activity (that means no other activity was open earlier) or that some
         * other activity is already open. In the latter case the NotificationActivity will just be
         * closed. The system will restore the previous activity. However, if the application has been
         * closed during upload and you click the notification, a NotificationActivity will
         * be launched as a root activity. It will create and start the main activity and
         * terminate itself.
         *
         * This method may be used to restore the target activity in case the application
         * was closed or is open. It may also be used to recreate an activity history using
         * startActivities(...).
         */
        return NotificationActivity.class;
    }

    @Override
    protected boolean isDebug() {
        // Here return true if you want the service to print more logs in LogCat.
        // Library's BuildConfig in current version of Android Studio is always set to DEBUG=false, so
        // make sure you return true or your.app.BuildConfig.DEBUG here.
        return true;
    }

    @Override
    protected void onHandleIntent(@Nullable final Intent intent) {
        try {
            super.onHandleIntent(intent);
        } catch (final IllegalStateException e) {
            // ForegroundServiceStartNotAllowedException (API 31+, transitively extends IllegalStateException
            // via ServiceStartNotAllowedException). Nordic 2.4.2 only catches SecurityException around
            // startForeground(), so its own background fallback never runs.
            final boolean fgsRefused = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                    && e instanceof ForegroundServiceStartNotAllowedException;
            if (!fgsRefused || bgFallbackApplied || intent == null || !intent.getBooleanExtra(EXTRA_FOREGROUND_SERVICE, true)) {
                throw e; // not our case, already applied once, or already in background mode -> fail loudly, never loop
            }
            // Re-entering is what Nordic itself does to retry (see DfuBaseService:1196-1199): the initial
            // startForeground() (:1153) refusal happens before any state is written, and a late refusal from
            // the self-restarts (:1361/:1424) has already closed the GATT -- worst case is one retry past the
            // limit (the attempt counter lives in the discarded newIntent) and progress restarting at 0%.
            bgFallbackApplied = true;
            Log.w(TAG, "Foreground start refused (app in background) - continuing this DFU as a background service", e);
            intent.putExtra(EXTRA_FOREGROUND_SERVICE, false); // self-restarts copy extras via fillIn -> inherit it
            super.onHandleIntent(intent);
        }
    }
}

