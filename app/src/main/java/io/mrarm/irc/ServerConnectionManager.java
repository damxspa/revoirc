package io.mrarm.irc;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.os.Build;
import android.util.Log;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.nio.charset.Charset;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;

import io.mrarm.chatlib.irc.IRCConnectionRequest;
import io.mrarm.chatlib.irc.cap.SASLOptions;
import io.mrarm.irc.util.AvatarUtils;
import io.mrarm.irc.config.AppSettings;
import io.mrarm.irc.config.ServerConfigData;
import io.mrarm.irc.config.ServerConfigManager;
import io.mrarm.irc.config.BackupPaths;
import io.mrarm.irc.config.SettingsHelper;
import io.mrarm.irc.setting.ReconnectIntervalSetting;

public class ServerConnectionManager {

    private static final String TAG = "ServerConnectionManager";
    public static final String CONNECTED_SERVERS_FILE_PATH = BackupPaths.CONNECTED_SERVERS_FILE_PATH;

    private static ServerConnectionManager instance;

    private final Context mContext;
    private final File mConnectedServersFile;
    private final HashMap<UUID, ServerConnectionInfo> mConnectionsMap = new HashMap<>();
    private final ArrayList<ServerConnectionInfo> mConnections = new ArrayList<>();
    private final HashMap<UUID, ServerConnectionInfo> mDisconnectingConnections = new HashMap<>();
    private final List<ConnectionsListener> mListeners = new ArrayList<>();
    private final List<ServerConnectionInfo.ChannelListChangeListener> mChannelsListeners = new ArrayList<>();
    private final List<ServerConnectionInfo.InfoChangeListener> mInfoListeners = new ArrayList<>();
    private final Executor mSaveExecutor = Executors.newSingleThreadExecutor();
    private boolean mDestroying = false;
    private boolean mExecutorShutdown = false;
    // Timer for periodic cleanup of disconnected connections
    private final java.util.Timer mCleanupTimer = new java.util.Timer("ServerConnectionManager-Cleanup", true);

    public static boolean hasInstance() {
        return instance != null;
    }

    public static synchronized ServerConnectionManager getInstance(Context context) {
        if (instance == null && context != null)
            instance = new ServerConnectionManager(context.getApplicationContext());
        return instance;
    }

    public static synchronized void destroyInstance() {
        if (instance == null)
            return;
        instance.mDestroying = true;
        // Cleanup timer
        instance.mCleanupTimer.cancel();
        instance.cleanupDisconnectingConnections();
        while (!instance.mConnections.isEmpty()) {
            ServerConnectionInfo connection = instance.mConnections.get(instance.mConnections.size() - 1);
            connection.disconnect();
            instance.removeConnection(connection, false);
            instance.killDisconnectingConnection(connection.getUUID());
        }
        instance.shutdownExecutor();
        instance = null;
    }

    /**
     * Shuts down the save executor gracefully.
     * Should be called when the application is terminating.
     */
    public void shutdownExecutor() {
        if (mExecutorShutdown) return;
        mExecutorShutdown = true;
        if (mSaveExecutor instanceof ExecutorService) {
            ExecutorService executorService = (ExecutorService) mSaveExecutor;
            executorService.shutdown();
            try {
                if (!executorService.awaitTermination(5, TimeUnit.SECONDS)) {
                    executorService.shutdownNow();
                }
            } catch (InterruptedException e) {
                executorService.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }

    public ServerConnectionManager(Context context) {
        mContext = context;

        mConnectedServersFile = new File(context.getFilesDir(), CONNECTED_SERVERS_FILE_PATH);
        ConnectedServersList servers = null;
        try (BufferedReader reader = new BufferedReader(new FileReader(mConnectedServersFile))) {
            servers = SettingsHelper.getGson().fromJson(reader, ConnectedServersList.class);
        } catch (Exception e) {
            Log.e(TAG, "Failed to load connected servers list", e);
        }

        if (servers != null) {
            ServerConfigManager configManager = ServerConfigManager.getInstance(context);
            for (ConnectedServerInfo server : servers.servers) {
                ServerConfigData configData = configManager.findServer(server.uuid);
                if (configData != null) {
                    try {
                        createConnection(configData, server.channels, false);
                    } catch (NickNotSetException e) {
                        Log.w(TAG, "Nick not set for autoconnect server: " + server.uuid);
                    }
                }
            }
        }
    }

    private void saveAutoconnectList() {
        ConnectedServersList list = new ConnectedServersList();
        list.servers = new ArrayList<>();
        for (ServerConnectionInfo connectionInfo : getConnections()) {
            ConnectedServerInfo server = new ConnectedServerInfo();
            server.uuid = connectionInfo.getUUID();
            server.channels = connectionInfo.getChannels();
            list.servers.add(server);
        }
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(mConnectedServersFile))) {
            SettingsHelper.getGson().toJson(list, writer);
        } catch (Exception e) {
            Log.e(TAG, "Failed to save autoconnect list", e);
        }
    }

    void saveAutoconnectListAsync() {
        mSaveExecutor.execute(this::saveAutoconnectList);
    }

    public Context getContext() {
        return mContext;
    }

    public List<ServerConnectionInfo> getConnections() {
        synchronized (this) {
            return new ArrayList<>(mConnections);
        }
    }

    public void addConnection(ServerConnectionInfo connection, boolean saveAutoconnect) {
        synchronized (this) {
            if (mConnectionsMap.containsKey(connection.getUUID()))
                throw new RuntimeException("A connection with this UUID already exists");
            mConnectionsMap.put(connection.getUUID(), connection);
            mConnections.add(connection);
            if (saveAutoconnect)
                saveAutoconnectListAsync();
        }
        synchronized (mListeners) {
            for (ConnectionsListener listener : mListeners)
                listener.onConnectionAdded(connection);
        }
        IRCService.start(mContext);
    }

    public void addConnection(ServerConnectionInfo connection) {
        addConnection(connection, true);
    }

    private ServerConnectionInfo createConnection(ServerConfigData data, List<String> joinChannels, boolean saveAutoconnect) {
        killDisconnectingConnection(data.uuid);

        IRCConnectionRequest request = new IRCConnectionRequest();
        request
                .setServerAddress(data.address, data.port);
        if (data.charset != null) {
            try {
                request.setCharset(Charset.forName(data.charset));
            } catch (IllegalArgumentException ignored) {
            }
        }
        if (data.nicks != null && !data.nicks.isEmpty()) {
            for (String nick : data.nicks)
                request.addNick(nick);
        } else {
            for (String nick : AppSettings.getDefaultNicks())
                request.addNick(nick);
            if (request.getNickList() == null)
                throw new NickNotSetException();
        }
        if (data.user != null)
            request.setUser(data.user);
        else if (AppSettings.getDefaultUser() != null && !AppSettings.getDefaultUser().isEmpty())
            request.setUser(AppSettings.getDefaultUser());
        else
            request.setUser(request.getNickList().get(0));
        if (data.realname != null)
            request.setRealName(data.realname);
        else if (AppSettings.getDefaultRealname() != null && !AppSettings.getDefaultRealname().isEmpty())
            request.setRealName(AppSettings.getDefaultRealname());
        else
            request.setRealName(request.getNickList().get(0));

        if (AvatarUtils.isChateagratisServer(data.address)) {
            String username = data.authUser != null && !data.authUser.trim().isEmpty() ? data.authUser.trim() :
                    (data.user != null && !data.user.trim().isEmpty() ? data.user.trim() : request.getNickList().get(0));
            request.setRealName(AvatarUtils.buildGecosFromAvatarUrl(AvatarUtils.AVATAR_URL_PREFIX + username.toLowerCase(java.util.Locale.ROOT)));
        }

        if (data.pass != null)
            request.setServerPass(data.pass);

        SASLOptions saslOptions = null;
        UserKeyManager userKeyManager = null;
        if (data.authMode != null) {
            if (ServerConfigData.AUTH_SASL.equals(data.authMode) && data.authUser != null &&
                    data.authPass != null)
                saslOptions = SASLOptions.createPlainAuth(data.authUser, data.authPass);
            if (ServerConfigData.AUTH_SASL_EXTERNAL.equals(data.authMode)) {
                saslOptions = SASLOptions.createExternal();
                userKeyManager = new UserKeyManager(data.getAuthCert(), data.getAuthPrivateKey());
            }
        }

        if (data.ssl) {
            UserOverrideTrustManager sslHelper = new UserOverrideTrustManager(mContext, data.uuid);
            try {
                SSLContext sslContext = SSLContext.getInstance("TLS");
                KeyManager[] keyManagers = new KeyManager[0];
                if (userKeyManager != null)
                    keyManagers = new KeyManager[] { userKeyManager };
                sslContext.init(keyManagers, new TrustManager[] { sslHelper }, null);
                request.enableSSL(sslContext.getSocketFactory(), sslHelper);
            } catch (GeneralSecurityException e) {
                throw new RuntimeException(e);
            }
        }
        ServerConnectionInfo connectionInfo = new ServerConnectionInfo(this, data, request, saslOptions, joinChannels);
        connectionInfo.connect();
        addConnection(connectionInfo, saveAutoconnect);
        return connectionInfo;
    }

    public ServerConnectionInfo createConnection(ServerConfigData data) {
        return createConnection(data, null, true);
    }

    public void tryCreateConnection(ServerConfigData data, Context activity) {
        if (ServerConnectionManager.getInstance(getContext()).hasConnection(data.uuid))
            return;
        try {
            createConnection(data);
        } catch (NickNotSetException e) {
            Toast.makeText(activity, R.string.connection_error_no_nick, Toast.LENGTH_SHORT).show();
        }
    }

    public void removeConnection(ServerConnectionInfo connection, boolean saveAutoconnect) {
        NotificationManager.getInstance().clearAllNotifications(mContext, connection);
        synchronized (this) {
            boolean isDisconnecting;
            boolean isConnecting;
            boolean isConnected;
            boolean hasUserDisconnectRequest;
            synchronized (connection.getLock()) {
                isDisconnecting = connection.isDisconnecting();
                isConnecting = connection.isConnecting();
                isConnected = connection.isConnected();
                hasUserDisconnectRequest = connection.hasUserDisconnectRequest();
            }
            if (isDisconnecting) {
                synchronized (mDisconnectingConnections) {
                    if (mDisconnectingConnections.containsKey(connection.getUUID()))
                        throw new RuntimeException("mDisconnectingConnections already contains a disconnecting connection with this UUID");
                    mDisconnectingConnections.put(connection.getUUID(), connection);
                    // Schedule cleanup check
                    scheduleCleanupCheck();
                }
            } else if (isConnecting || isConnected || !hasUserDisconnectRequest) {
                throw new RuntimeException("Trying to remove a non-disconnected connection");
            } else {
                connection.close();
            }
            mConnections.remove(connection);
            mConnectionsMap.remove(connection.getUUID());
            if (saveAutoconnect)
                saveAutoconnectListAsync();
            if (mConnections.isEmpty())
                IRCService.stop(mContext);
            else if (!mDestroying)
                IRCService.start(mContext); // update connection count
        }
        synchronized (mListeners) {
            for (ConnectionsListener listener : mListeners)
                listener.onConnectionRemoved(connection);
        }
    }

    private void scheduleCleanupCheck() {
        mCleanupTimer.schedule(new java.util.TimerTask() {
            @Override
            public void run() {
                cleanupDisconnectingConnections();
            }
        }, 30000); // Check after 30 seconds
    }

    private void cleanupDisconnectingConnections() {
        synchronized (mDisconnectingConnections) {
            if (!mDisconnectingConnections.isEmpty()) {
                Log.w("ServerConnectionManager", "Cleaning up " + mDisconnectingConnections.size() + " stuck disconnecting connections");
                for (ServerConnectionInfo connection : mDisconnectingConnections.values()) {
                    try {
                        connection.close();
                    } catch (Exception e) {
                        Log.e("ServerConnectionManager", "Error closing disconnecting connection", e);
                    }
                }
                mDisconnectingConnections.clear();
            }
        }
    }

    public void removeConnection(ServerConnectionInfo connection) {
        removeConnection(connection, true);
    }

    /**
     * Stop keeping track of a disconnected connection. A call to this function is required if you
     * want to do something with this server's logs to make sure they are properly released.
     */
    public void killDisconnectingConnection(UUID uuid) {
        synchronized (mDisconnectingConnections) {
            ServerConnectionInfo connection = mDisconnectingConnections.get(uuid);
            if (connection == null)
                return;
            connection.close();
            mDisconnectingConnections.remove(uuid);
        }
    }

    public void disconnectAndRemoveAllConnections(boolean kill) {
        synchronized (this) {
            while (!mConnections.isEmpty()) {
                ServerConnectionInfo connection = mConnections.get(mConnections.size() - 1);
                connection.disconnect();
                removeConnection(connection, false);
                if (kill)
                    killDisconnectingConnection(connection.getUUID());
            }
            saveAutoconnectListAsync();
        }
    }

    public ServerConnectionInfo getConnection(UUID uuid) {
        synchronized (this) {
            return mConnectionsMap.get(uuid);
        }
    }

    public boolean hasConnection(UUID uuid) {
        synchronized (this) {
            return mConnectionsMap.containsKey(uuid);
        }
    }

    int getReconnectDelay(int attemptNumber) {
        if (!AppSettings.isReconnectEnabled())
            return -1;
        List<ReconnectIntervalSetting.Rule> rules = AppSettings.getReconnectIntervalRules();
        if (rules.isEmpty())
            return -1;
        int att = 0;
        for (ReconnectIntervalSetting.Rule rule : rules) {
            att += rule.repeatCount;
            if (attemptNumber < att)
                return rule.reconnectDelay;
        }
        return rules.get(rules.size() - 1).reconnectDelay;
    }

    public void addListener(ConnectionsListener listener) {
        synchronized (mListeners) {
            mListeners.add(listener);
        }
    }

    public void removeListener(ConnectionsListener listener) {
        synchronized (mListeners) {
            mListeners.remove(listener);
        }
    }

    public void addGlobalConnectionInfoListener(ServerConnectionInfo.InfoChangeListener listener) {
        synchronized (mInfoListeners) {
            mInfoListeners.add(listener);
        }
    }

    public void removeGlobalConnectionInfoListener(ServerConnectionInfo.InfoChangeListener listener) {
        synchronized (mInfoListeners) {
            mInfoListeners.remove(listener);
        }
    }

    public void addGlobalChannelListListener(ServerConnectionInfo.ChannelListChangeListener listener) {
        synchronized (mChannelsListeners) {
            mChannelsListeners.add(listener);
        }
    }

    public void removeGlobalChannelListListener(ServerConnectionInfo.ChannelListChangeListener listener) {
        synchronized (mChannelsListeners) {
            mChannelsListeners.remove(listener);
        }
    }

    void notifyConnectionInfoChanged(ServerConnectionInfo connection) {
        if (!hasConnection(connection.getUUID()))
            return;
        synchronized (mInfoListeners) {
            for (ServerConnectionInfo.InfoChangeListener listener : mInfoListeners)
                listener.onConnectionInfoChanged(connection);
            if (!mDestroying)
                IRCService.start(mContext);
        }
    }

    void notifyChannelListChanged(ServerConnectionInfo connection, List<String> newChannels) {
        if (!hasConnection(connection.getUUID()))
            return;
        synchronized (mChannelsListeners) {
            for (ServerConnectionInfo.ChannelListChangeListener listener : mChannelsListeners)
                listener.onChannelListChanged(connection, newChannels);
        }
    }

    void notifyConnectionFullyDisconnected(ServerConnectionInfo connection) {
        ServerConnectionInfo removed;
        synchronized (mDisconnectingConnections) {
            removed = mDisconnectingConnections.remove(connection.getUUID());
        }
        if (removed != null)
            connection.close();
    }

    public void notifyConnectivityChanged(boolean hasAnyConnectivity) {
        synchronized (this) {
            for (ServerConnectionInfo server : mConnectionsMap.values())
                server.notifyConnectivityChanged(hasAnyConnectivity);
        }
    }

    public static boolean isWifiConnected(Context context) {
        ConnectivityManager mgr = (ConnectivityManager) context.getSystemService(
                Context.CONNECTIVITY_SERVICE);
        if (mgr == null)
            return false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Network activeNetwork = mgr.getActiveNetwork();
            NetworkCapabilities caps = mgr.getNetworkCapabilities(activeNetwork);
            return caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
        }
        NetworkInfo wifiInfo = mgr.getNetworkInfo(ConnectivityManager.TYPE_WIFI);
        return wifiInfo != null && wifiInfo.isConnected();
    }

    public interface ConnectionsListener {

        void onConnectionAdded(ServerConnectionInfo connection);

        void onConnectionRemoved(ServerConnectionInfo connection);

    }

    private static class ConnectedServerInfo {

        public UUID uuid;
        public List<String> channels;

    }

    private static class ConnectedServersList {

        public List<ConnectedServerInfo> servers;

    }

    public static class NickNotSetException extends RuntimeException {
    }

}
