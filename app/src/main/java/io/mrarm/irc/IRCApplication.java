package io.mrarm.irc;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import coil.ImageLoader;
import okhttp3.OkHttpClient;

import io.mrarm.irc.config.SettingsHelper;

public class IRCApplication extends Application implements Application.ActivityLifecycleCallbacks {

    private final List<Activity> mActivities = new ArrayList<>();
    private final List<PreExitCallback> mPreExitCallbacks = new ArrayList<>();
    private final List<ExitCallback> mExitCallbacks = new ArrayList<>();
    private final List<AutoCloseable> mCloseables = new ArrayList<>();

    @Override
    public void onCreate() {
        super.onCreate();
        // Use standard SSL validation for HTTPS (images)
        // IRC SSL connections use UserOverrideTrustManager separately in ServerConnectionInfo
        OkHttpClient client = new OkHttpClient.Builder()
                .build();
        ImageLoader imageLoader = new ImageLoader.Builder(this)
                .okHttpClient(client)
                .build();
        coil.Coil.setImageLoader(imageLoader);
        SettingsHelper.getInstance(this);
        NotificationManager.createDefaultChannels(this);
        registerActivityLifecycleCallbacks(this);
    }

    /**
     * Register an AutoCloseable (ExecutorService, Timer, etc.) to be shut down on app exit.
     * Thread-safe.
     */
    public void registerCloseable(AutoCloseable closeable) {
        if (closeable == null) return;
        synchronized (mCloseables) {
            mCloseables.add(closeable);
        }
    }

    /**
     * Unregister a previously registered closeable.
     */
    public void unregisterCloseable(AutoCloseable closeable) {
        if (closeable == null) return;
        synchronized (mCloseables) {
            mCloseables.remove(closeable);
        }
    }

    /**
     * Shuts down all registered closeables (executors, timers, etc.).
     * Called during graceful exit.
     */
    private void shutdownAllCloseables() {
        List<AutoCloseable> toClose;
        synchronized (mCloseables) {
            toClose = new ArrayList<>(mCloseables);
            mCloseables.clear();
        }
        for (AutoCloseable closeable : toClose) {
            try {
                if (closeable instanceof ExecutorService) {
                    ExecutorService executor = (ExecutorService) closeable;
                    executor.shutdown();
                    try {
                        if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                            executor.shutdownNow();
                        }
                    } catch (InterruptedException e) {
                        executor.shutdownNow();
                        Thread.currentThread().interrupt();
                    }
                } else {
                    closeable.close();
                }
            } catch (Exception e) {
                Log.w("IRCApplication", "Failed to close: " + closeable.getClass().getName(), e);
            }
        }
    }

    public void addPreExitCallback(PreExitCallback c) {
        synchronized (mPreExitCallbacks) {
            mPreExitCallbacks.add(c);
        }
    }

    public void removePreExitCallback(PreExitCallback c) {
        synchronized (mPreExitCallbacks) {
            mPreExitCallbacks.remove(c);
        }
    }

    public void addExitCallback(ExitCallback c) {
        synchronized (mExitCallbacks) {
            mExitCallbacks.add(c);
        }
    }

    public void removeExitCallback(ExitCallback c) {
        synchronized (mExitCallbacks) {
            mExitCallbacks.remove(c);
        }
    }

    public boolean requestExit() {
        synchronized (mPreExitCallbacks) {
            for (PreExitCallback exitCallback : mPreExitCallbacks) {
                if (!exitCallback.onAppPreExit())
                    return false;
            }
        }
        synchronized (mExitCallbacks) {
            for (ExitCallback exitCallback : mExitCallbacks)
                exitCallback.onAppExiting();
        }
        List<Activity> activities;
        synchronized (mActivities) {
            activities = new ArrayList<>(mActivities);
        }
        for (Activity activity : activities)
            activity.finish();
        ServerConnectionManager.destroyInstance();
        ChatLogStorageManager storageManager = ChatLogStorageManager.getInstance(this);
        storageManager.destroy();
        IRCService.stop(this);
        shutdownAllCloseables();
        shutdownAllCloseables();
        return true;
    }


    @Override
    public void onActivityCreated(Activity activity, Bundle savedInstanceState) {
        synchronized (mActivities) {
            if (!mActivities.contains(activity))
                mActivities.add(activity);
        }
    }

    @Override
    public void onActivityDestroyed(Activity activity) {
        synchronized (mActivities) {
            mActivities.remove(activity);
        }
    }

    @Override
    public void onActivityStarted(Activity activity) {
    }

    @Override
    public void onActivityResumed(Activity activity) {
    }

    @Override
    public void onActivityPaused(Activity activity) {
    }

    @Override
    public void onActivityStopped(Activity activity) {
    }

    @Override
    public void onActivitySaveInstanceState(Activity activity, Bundle outState) {
    }


    public interface PreExitCallback {
        boolean onAppPreExit();
    }


    public interface ExitCallback {
        void onAppExiting();
    }

}
