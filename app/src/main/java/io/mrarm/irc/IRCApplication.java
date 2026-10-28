package io.mrarm.irc;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;

import java.util.ArrayList;
import java.util.List;

import coil.ImageLoader;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import okhttp3.OkHttpClient;

import io.mrarm.irc.config.SettingsHelper;

public class IRCApplication extends Application implements Application.ActivityLifecycleCallbacks {

    private final List<Activity> mActivities = new ArrayList<>();
    private final List<PreExitCallback> mPreExitCallbacks = new ArrayList<>();
    private final List<ExitCallback> mExitCallbacks = new ArrayList<>();

    @Override
    public void onCreate() {
        super.onCreate();
        try {
            TrustManager[] trustAllCerts = new TrustManager[] {
                new X509TrustManager() {
                    @Override
                    public void checkClientTrusted(java.security.cert.X509Certificate[] chain, String authType) {}
                    @Override
                    public void checkServerTrusted(java.security.cert.X509Certificate[] chain, String authType) {}
                    @Override
                    public java.security.cert.X509Certificate[] getAcceptedIssuers() {
                        return new java.security.cert.X509Certificate[]{};
                    }
                }
            };
            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, trustAllCerts, new java.security.SecureRandom());
            OkHttpClient client = new OkHttpClient.Builder()
                    .sslSocketFactory(sslContext.getSocketFactory(), (X509TrustManager) trustAllCerts[0])
                    .hostnameVerifier((hostname, session) -> true)
                    .build();
            ImageLoader imageLoader = new ImageLoader.Builder(this)
                    .okHttpClient(client)
                    .build();
            coil.Coil.setImageLoader(imageLoader);
        } catch (Exception e) {
            e.printStackTrace();
        }
        SettingsHelper.getInstance(this);
        NotificationManager.createDefaultChannels(this);
        registerActivityLifecycleCallbacks(this);
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
        IRCService.stop(this);
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
