package io.mrarm.irc.job;

import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.util.Log;

import io.mrarm.irc.ServerConnectionManager;
import io.mrarm.irc.config.AppSettings;
import io.mrarm.irc.config.SettingsHelper;
import io.mrarm.irc.config.UiSettingChangeCallback;
import io.mrarm.irc.util.BatteryOptimizationHelper;

public class ServerPingScheduler {

    private static final String TAG = "ServerPingScheduler";

    public static final int JOB_ID = 1;

    private static ServerPingScheduler instance;

    public static ServerPingScheduler getInstance(Context ctx) {
        if (instance == null)
            instance = new ServerPingScheduler(ctx.getApplicationContext());
        return instance;
    }

    private final Context context;
    private boolean running;
    private boolean enabled = false;
    private long interval = 15 * 60 * 1000; // 15 minutes

    public ServerPingScheduler(Context ctx) {
        this.context = ctx;

        SettingsHelper.registerCallbacks(this);
        onSettingChanged();
    }

    @UiSettingChangeCallback(keys = {AppSettings.PREF_PING_ENABLED,
            AppSettings.PREF_PING_INTERVAL, AppSettings.PREF_PING_INTERVAL_MOBILE,
            AppSettings.PREF_LOW_POWER_MODE_ENABLED})
    public void onSettingChanged() {
        enabled = AppSettings.isPingEnabled();
        if (enabled) {
            if (AppSettings.isLowPowerModeEnabled())
                interval = BatteryOptimizationHelper.getPollingInterval() * 1000L;
            else if (!ServerConnectionManager.isWifiConnected(context))
                interval = AppSettings.getPingIntervalMobile();
            else
                interval = AppSettings.getPingInterval();
        }
        stop();
        startIfEnabled();
    }

    void onJobRan() {
        if (!running) {
            Log.w(TAG, "A job that should have not ran has been started; forcibly stopping");
            forceStop();
        }
    }

    public void startIfEnabled() {
        if (!enabled)
            return;
        start();
    }

    private void start() {
        Log.d(TAG, "Starting/Rescheduling the job");
        running = true;
        startUsingJobService();
    }

    public void stop() {
        if (!running)
            return;
        Log.d(TAG, "Stopping the job");
        forceStop();
    }

    public void forceStop() {
        running = false;
        stopUsingJobService();
    }

    private void startUsingJobService() {
        JobInfo jobInfo = new JobInfo.Builder(JOB_ID,
                new ComponentName(context, ServerPingJobService.class))
                .setPeriodic(interval)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .build();
        JobScheduler scheduler = (JobScheduler)
                context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        assert scheduler != null;
        scheduler.schedule(jobInfo);
    }

    private void stopUsingJobService() {
        JobScheduler scheduler = (JobScheduler)
                context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        assert scheduler != null;
        scheduler.cancel(JOB_ID);
    }

}
