package io.mrarm.irc.job;

import android.content.Context;
import android.os.PowerManager;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import io.mrarm.chatlib.ChatApi;
import io.mrarm.chatlib.irc.IRCConnection;
import io.mrarm.irc.ServerConnectionInfo;
import io.mrarm.irc.ServerConnectionManager;

public class ServerPingTask {

    private static final AtomicBoolean isRunning = new AtomicBoolean(false);

    public static void pingServers(Context ctx, DoneCallback cb) {
        if (!isRunning.compareAndSet(false, true)) {
            Log.d("ServerPingTask", "Ping task already running, skipping execution");
            if (cb != null)
                cb.onDone();
            return;
        }

        PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
        PowerManager.WakeLock wakeLock = null;
        if (pm != null) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "revolution-irc:ServerPingTask");
            wakeLock.acquire(30000); // 30 seconds timeout
        }
        final PowerManager.WakeLock finalWakeLock = wakeLock;
        final AtomicBoolean doneCalled = new AtomicBoolean(false);

        // Centralized release logic: ensures the wakelock is always released and the
        // callback is invoked exactly once, even if exceptions occur during server iteration.
        DoneCallback wrappedCb = () -> {
            if (!doneCalled.compareAndSet(false, true))
                return; // already done
            isRunning.set(false);
            if (finalWakeLock != null && finalWakeLock.isHeld()) {
                try {
                    finalWakeLock.release();
                } catch (RuntimeException e) {
                    Log.w("ServerPingTask", "Failed to release wakelock", e);
                }
            }
            if (cb != null)
                cb.onDone();
        };

        try {
            List<ServerConnectionInfo> servers =
                    ServerConnectionManager.getInstance(ctx).getConnections();
            List<IRCConnection> serversToPing = new ArrayList<>();
            for (ServerConnectionInfo c : servers) {
                if (!c.isConnected())
                    continue;
                ChatApi api = c.getApiInstance();
                if (api != null && api instanceof IRCConnection)
                    serversToPing.add((IRCConnection) api);
            }
            if (serversToPing.size() == 0) {
                Log.d("ServerPingTask", "No servers to ping");
                wrappedCb.onDone();
                return;
            }
            Log.d("ServerPingTask", "Pinging " + serversToPing.size() + " servers");
            AtomicInteger countdownInteger = new AtomicInteger(serversToPing.size());
            for (IRCConnection api : serversToPing) {
                Runnable pingCompleteCb = () -> {
                    Log.d("ServerPingTask", "Ping received from a server");
                    if (countdownInteger.decrementAndGet() == 0) {
                        Log.d("ServerPingTask", "Task has been completed");
                        wrappedCb.onDone();
                    }
                };
                api.sendPing((Void v) -> pingCompleteCb.run(),
                        (Exception e) -> pingCompleteCb.run());
                // Sending the ping will either succeed or get us disconnected (we can't get an error
                // from the ping itself, and the disconnect will be handled by other code already).
            }
        } catch (Exception e) {
            Log.e("ServerPingTask", "Error during ping task", e);
            wrappedCb.onDone();
        }
    }


    public interface DoneCallback {

        void onDone();

    }

}
