package io.mrarm.irc;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import io.mrarm.chatlib.ChannelListListener;
import io.mrarm.chatlib.ChatApi;
import io.mrarm.chatlib.NoSuchChannelException;
import io.mrarm.chatlib.android.storage.SQLiteMessageStorageApi;
import io.mrarm.chatlib.android.storage.SQLiteMiscStorage;
import io.mrarm.chatlib.android.storage.SQLiteChannelDataStorage;
import io.mrarm.chatlib.dto.MessageId;
import io.mrarm.chatlib.dto.MessageInfo;
import io.mrarm.chatlib.dto.MessageSenderInfo;
import io.mrarm.chatlib.irc.ChannelData;
import io.mrarm.chatlib.irc.CommandHandler;
import io.mrarm.chatlib.irc.IRCConnection;
import io.mrarm.chatlib.irc.IRCConnectionRequest;
import io.mrarm.chatlib.irc.InvalidMessageException;
import io.mrarm.chatlib.irc.MessagePrefix;
import io.mrarm.chatlib.irc.ServerConnectionApi;
import io.mrarm.chatlib.irc.ServerConnectionData;
import io.mrarm.chatlib.irc.cap.SASLCapability;
import io.mrarm.chatlib.irc.cap.SASLOptions;
import io.mrarm.chatlib.irc.filters.ZNCPlaybackMessageFilter;
import io.mrarm.chatlib.irc.handlers.MessageCommandHandler;
import java.util.concurrent.Executors;

import io.mrarm.chatlib.irc.handlers.QuitCommandHandler;
import io.mrarm.chatlib.message.MessageStorageApi;
import io.mrarm.chatlib.user.UserInfo;
import io.mrarm.irc.chat.ChatUIData;
import io.mrarm.irc.config.AppSettings;
import io.mrarm.irc.config.ServerConfigData;
import io.mrarm.irc.config.ServerConfigManager;
import io.mrarm.irc.util.IRCUtils;
import io.mrarm.irc.util.IgnoreListMessageFilter;
import io.mrarm.irc.util.StubMessageStorageApi;
import io.mrarm.irc.util.UserAutoRunCommandHelper;

public class ServerConnectionInfo {

    private final Object mLock = new Object();
    private final Handler mReconnectHandler = new Handler(Looper.getMainLooper());

    public Object getLock() {
        return mLock;
    }

    private ServerConnectionManager mManager;
    private ServerConfigData mServerConfig;
    private List<String> mChannels;
    private ChatApi mApi;
    private IRCConnectionRequest mConnectionRequest;
    private SASLOptions mSASLOptions;
    private SQLiteMiscStorage mSQLiteMiscStorage;
    private boolean mExpandedInDrawer = true;
    private boolean mConnected = false;
    private boolean mConnecting = false;
    private boolean mDisconnecting = false;
    private boolean mUserDisconnectRequest = false;
    private long mReconnectQueueTime = -1L;
    private NotificationManager.ConnectionManager mNotificationData;
    private UserAutoRunCommandHelper mAutoRunHelper;
    private final List<InfoChangeListener> mInfoListeners = new ArrayList<>();
    private final List<ChannelListChangeListener> mChannelsListeners = new ArrayList<>();
    private int mCurrentReconnectAttempt = -1;
    int mChatLogStorageUpdateCounter = 0;
    private final ChatUIData mChatUIData = new ChatUIData();
    private ChannelListListener mChannelListListener;
    private boolean mIsClosed = false;
    private String mLastErrorReason;

    public String getLastErrorReason() {
        synchronized (mLock) {
            return mLastErrorReason;
        }
    }

    public static String classifyException(Throwable e) {
        if (e == null) return "Unknown error";
        Throwable cause = e;
        while (cause != null) {
            if (cause instanceof UserOverrideTrustManager.UserRejectedCertificateException) {
                return "UserRejectedCertificateException";
            }
            if (cause instanceof java.net.UnknownHostException) {
                return "UnknownHostException";
            }
            if (cause instanceof java.net.ConnectException) {
                return "ConnectException";
            }
            if (cause instanceof java.net.SocketTimeoutException) {
                return "SocketTimeoutException";
            }
            if (cause instanceof javax.net.ssl.SSLException) {
                return "SSLException";
            }
            cause = cause.getCause();
        }
        return e.getClass().getSimpleName();
    }

    public ServerConnectionInfo(ServerConnectionManager manager, ServerConfigData config,
                                IRCConnectionRequest connectionRequest, SASLOptions saslOptions,
                                List<String> joinChannels) {
        mManager = manager;
        mServerConfig = config;
        mConnectionRequest = connectionRequest;
        mSASLOptions = saslOptions;
        mNotificationData = new NotificationManager.ConnectionManager(this);
        mChannels = joinChannels;
        if (mChannels != null)
            Collections.sort(mChannels, String::compareToIgnoreCase);
    }

    private void setApi(ChatApi api) {
        synchronized (mLock) {
            // Unregister listener from the PREVIOUS api before replacing it
            if (mApi != null && mChannelListListener != null) {
                mApi.unsubscribeChannelList(mChannelListListener, null, null);
            }
            mApi = api;
            if (api != null) {
                api.getJoinedChannelList((List<String> channels) -> {
                    setChannels(channels);
                }, null);
            }
            mChannelListListener = new ChannelListListener() {
                @Override
                public void onChannelListChanged(List<String> list) {
                    setChannels(list);
                }

                @Override
                public void onChannelJoined(String s) {
                    addChannelIfAbsent(s);
                }

                @Override
                public void onChannelLeft(String s) {
                    removeChannel(s);
                }
            };
            if (api != null) {
                api.subscribeChannelList(mChannelListListener, null, null);
            }
            if (api != null) {
                mChatUIData.attachToConnection(api);
            }
        }
    }

    public ServerConnectionManager getConnectionManager() {
        return mManager;
    }

    public ServerConfigData getServerConfig() {
        return mServerConfig;
    }

    private static final ExecutorService sDnsExecutor = Executors.newCachedThreadPool();

    public void connect() {
        synchronized (mLock) {
            if (mDisconnecting)
                throw new RuntimeException("Trying to connect with mDisconnecting set");
            if (mConnected || mConnecting)
                return;
            mConnecting = true;
            mUserDisconnectRequest = false;
            mReconnectQueueTime = -1L;
            mLastErrorReason = null;
        }
        Log.i("ServerConnectionInfo", "Connecting to " + getHost() + ":" + mServerConfig.port + "...");

        IRCConnection connection;
        if (mApi == null || !(mApi instanceof IRCConnection)) {
            connection = new IRCConnection();
            boolean connectionSetupComplete = false;
            try {
                ServerConfigManager configManager = ServerConfigManager.getInstance(mManager.getContext());
                connection.getServerConnectionData().setMessageStorageApi(new SQLiteMessageStorageApi(configManager.getServerChatLogDir(getUUID())));
                mSQLiteMiscStorage = new SQLiteMiscStorage(configManager.getServerMiscDataFile(getUUID()));
                connection.getServerConnectionData().setChannelDataStorage(new SQLiteChannelDataStorage(mSQLiteMiscStorage));
                connection.getServerConnectionData().getMessageFilterList().addMessageFilter(new IgnoreListMessageFilter(mServerConfig));
                if (mSASLOptions != null)
                    connection.getServerConnectionData().getCapabilityManager().registerCapability(
                            new SASLCapability(mSASLOptions));
                connection.getServerConnectionData().getMessageFilterList().addMessageFilter(
                        new ZNCPlaybackMessageFilter(connection.getServerConnectionData()));
                MessageCommandHandler messageHandler = connection.getServerConnectionData()
                        .getCommandHandlerList().getHandler(MessageCommandHandler.class);
                DCCManager dccManager = DCCManager.getInstance(getConnectionManager().getContext());
                messageHandler.setDCCServerManager(dccManager.getServer());
                messageHandler.setDCCClientManager(dccManager.createClient(this));
                messageHandler.setCtcpVersionReply(mManager.getContext()
                        .getString(R.string.app_name), BuildConfig.VERSION_NAME, "Android");
                connection.addDisconnectListener((IRCConnection conn, Exception reason) -> {
                    notifyDisconnected();
                });
                ServerConnectionData connData = connection.getServerConnectionData();
                QuitCommandHandler originalQuit = connData.getCommandHandlerList().getHandler(QuitCommandHandler.class);
                if (originalQuit != null) {
                    connData.getCommandHandlerList().unregisterHandler(originalQuit);
                    connData.getCommandHandlerList().registerHandler(new CommandHandler() {
                        @Override
                        public Object[] getHandledCommands() {
                            return new Object[]{"QUIT"};
                        }

                        @Override
                        public void handle(ServerConnectionData conn, MessagePrefix sender, String command,
                                           List<String> params, Map<String, String> tags) throws InvalidMessageException {
                            if (sender != null && sender.getNick() != null) {
                                String nick = sender.getNick();
                                try {
                                    ChannelData pmChannel = conn.getJoinedChannelData(nick);
                                    if (pmChannel != null) {
                                        conn.getUserInfoApi().getUser(sender.getNick(), sender.getUser(), sender.getHost(),
                                                (UserInfo userInfo) -> {
                                                    MessageSenderInfo senderInfo = new MessageSenderInfo(sender.getNick(), sender.getUser(),
                                                            sender.getHost(), null, userInfo.getUUID());
                                                    String message = CommandHandler.getParamOrNull(params, 0);
                                                    if (AppSettings.isNotifyPrivateQuitEnabled())
                                                        pmChannel.addMessage(new MessageInfo.Builder(senderInfo, message, MessageInfo.MessageType.QUIT), tags);
                                                },
                                                (Exception e) -> {
                                                    Log.e("ServerConnectionInfo", "Failed to get user info for QUIT message", e);
                                                });
                                    }
                                } catch (NoSuchChannelException e) {
                                    // No PM channel with this user, nothing to do
                                }
                            }
                            
                            if (mManager.getContext().getSharedPreferences(mManager.getContext().getPackageName() + "_preferences", Context.MODE_PRIVATE).getBoolean(AppSettings.PREF_CLOSE_PRIVATE_QUIT, false)) {
                                 List<String> channels = getChannels();
                                 for (String channel : channels) {
                                     if (sender != null && sender.getNick() != null && !IRCUtils.isChannel(channel) && channel.equalsIgnoreCase(sender.getNick())) {
                                         getApiInstance().leaveChannel(channel, null, (Void v) -> {}, (Exception e) -> {});
                                     }
                                 }
                            }
                            originalQuit.handle(conn, sender, command, params, tags);
                        }
                    });
                }
                connectionSetupComplete = true;
            } finally {
                if (!connectionSetupComplete) {
                    try {
                        connection.disconnect(true);
                    } catch (Exception e) {
                        Log.w("ServerConnectionInfo", "Failed to cleanup connection after setup failure", e);
                    }
                }
            }
            setApi(connection);
        } else {
            connection = (IRCConnection) mApi;
        }

        final IRCConnection fConnection = connection;

        sDnsExecutor.execute(() -> {
            String host = mServerConfig.address;
            long dnsStart = System.currentTimeMillis();
            try {
                java.net.InetAddress[] addresses = java.net.InetAddress.getAllByName(host);
                long dnsDuration = System.currentTimeMillis() - dnsStart;
                StringBuilder sb = new StringBuilder();
                for (java.net.InetAddress addr : addresses) {
                    if (sb.length() > 0) sb.append(", ");
                    sb.append(addr.getHostAddress());
                }
                Log.i("ServerConnectionInfo", "DNS resolution for " + host + " took " + dnsDuration + " ms: [" + sb.toString() + "]");
            } catch (java.net.UnknownHostException e) {
                long dnsDuration = System.currentTimeMillis() - dnsStart;
                String fullReason = "UnknownHostException: " + e.getMessage();
                Log.e("ServerConnectionInfo", "DNS resolution failed for " + host + " after " + dnsDuration + " ms: " + e.getMessage(), e);
                synchronized (mLock) {
                    mLastErrorReason = fullReason;
                    mConnecting = false;
                }
                try {
                    if (fConnection.getServerConnectionData() != null &&
                            fConnection.getServerConnectionData().getServerStatusData() != null) {
                        fConnection.getServerConnectionData().getServerStatusData().addMessage(
                                new io.mrarm.chatlib.dto.StatusMessageInfo("RevoIRC", new java.util.Date(),
                                        io.mrarm.chatlib.dto.StatusMessageInfo.MessageType.NOTICE,
                                        "Connection failed: " + fullReason)
                        );
                    }
                } catch (Exception ex) {
                    Log.w("ServerConnectionInfo", "Failed to add DNS error status message", ex);
                }
                mReconnectHandler.post(this::notifyDisconnected);
                return;
            } catch (Exception e) {
                Log.e("ServerConnectionInfo", "DNS resolution error for " + host, e);
            }

            mReconnectHandler.post(() -> startConnectionInternal(fConnection));
        });
    }

    private void startConnectionInternal(IRCConnection fConnection) {
        synchronized (mLock) {
            if (mIsClosed || mDisconnecting)
                return;
        }

        List<String> rejoinChannels = getChannels();

        fConnection.connect(mConnectionRequest, (Void v) -> {
            synchronized (mLock) {
                mConnecting = false;
                setConnected(true);
                mCurrentReconnectAttempt = 0;

                if (mServerConfig.execCommandsConnected != null) {
                    if (mAutoRunHelper == null)
                        mAutoRunHelper = new UserAutoRunCommandHelper(this);
                    mAutoRunHelper.executeUserCommands(mServerConfig.execCommandsConnected);
                }
            }

            List<String> joinChannels = new ArrayList<>();
            if (mServerConfig.autojoinChannels != null)
                joinChannels.addAll(mServerConfig.autojoinChannels);
            if (rejoinChannels != null && mServerConfig.rejoinChannels)
                joinChannels.addAll(rejoinChannels);
            if (joinChannels.size() > 0) {
                if (IRCUtils.isAntibotServer(mServerConfig.address)) {
                    synchronized (mLock) {
                        if (mAutoRunHelper == null)
                            mAutoRunHelper = new UserAutoRunCommandHelper(this);
                    }
                    mAutoRunHelper.postDelayed(() -> {
                        synchronized (mLock) {
                            if (!isConnected())
                                return;
                        }
                        fConnection.joinChannels(joinChannels, null, null);
                    }, 6000);
                } else {
                    fConnection.joinChannels(joinChannels, null, null);
                }
            }

        }, (Exception e) -> {
            String classified = classifyException(e);
            String detail = e.getLocalizedMessage();
            if (detail == null || detail.isEmpty()) {
                detail = e.getClass().getSimpleName();
            }
            String fullReason = classified + ": " + detail;
            Log.e("ServerConnectionInfo", "Connection failed for " + getHost() + " (" + classified + "): " + detail, e);
            synchronized (mLock) {
                mLastErrorReason = fullReason;
            }

            try {
                if (fConnection.getServerConnectionData() != null &&
                        fConnection.getServerConnectionData().getServerStatusData() != null) {
                    fConnection.getServerConnectionData().getServerStatusData().addMessage(
                            new io.mrarm.chatlib.dto.StatusMessageInfo("RevoIRC", new java.util.Date(),
                                    io.mrarm.chatlib.dto.StatusMessageInfo.MessageType.NOTICE,
                                    "Connection failed: " + fullReason)
                    );
                }
            } catch (Exception ex) {
                Log.w("ServerConnectionInfo", "Failed to add disconnect status message", ex);
            }

            if (e instanceof UserOverrideTrustManager.UserRejectedCertificateException ||
                    (e.getCause() != null && e.getCause() instanceof
                            UserOverrideTrustManager.UserRejectedCertificateException)) {
                Log.d("ServerConnectionInfo", "User rejected the certificate");
                synchronized (mLock) {
                    mUserDisconnectRequest = true;
                }
            }
            notifyDisconnected();
        });
    }

    private void disconnect(boolean userExecutedQuit) {
        synchronized (mLock) {
            mUserDisconnectRequest = true;
            mReconnectHandler.removeCallbacks(mReconnectRunnable);
            if (!isConnected() && isConnecting()) {
                mConnecting = false;
                mDisconnecting = true;
                ChatApi api = getApiInstance();
                if (api instanceof IRCConnection) {
                    Thread disconnectThread = new Thread(() -> {
                        ((IRCConnection) api).disconnect(true);
                    });
                    disconnectThread.setName("Disconnect Thread");
                    disconnectThread.start();
                } else {
                    notifyFullyDisconnected();
                }
            } else if (isConnected()) {
                mDisconnecting = true;
                String message = AppSettings.getDefaultQuitMessage();
                if (userExecutedQuit) {
                    ((IRCConnection) mApi).disconnect(null, null);
                } else {
                    mApi.quit(message, null, (Exception e) -> {
                        ((IRCConnection) getApiInstance()).disconnect(true);
                    });
                }
            } else {
                notifyFullyDisconnected();
            }
        }
    }

    public void disconnect() {
        disconnect(false);
    }

    public void recreateAndConnect() {
        synchronized (mLock) {
            mReconnectHandler.removeCallbacks(mReconnectRunnable);
            ChatApi api = getApiInstance();
            mApi = null;
            mConnecting = false;
            mDisconnecting = true;
            if (api instanceof IRCConnection) {
                new Thread(() -> {
                    synchronized (mLock) {
                        if (mIsClosed)
                            return;
                    }
                    try {
                        ((IRCConnection) api).disconnect(true);
                    } catch (Exception ignored) {}
                    mReconnectHandler.post(() -> {
                        synchronized (mLock) {
                            if (mIsClosed)
                                return;
                            setConnected(false);
                            mConnecting = false;
                            mDisconnecting = false;
                            mUserDisconnectRequest = false;
                        }
                        connect();
                    });
                }, "Reconnect Disconnect Thread").start();
                return;
            }
            setConnected(false);
            mConnecting = false;
            mDisconnecting = false;
            mUserDisconnectRequest = false;
        }
        connect();
    }

    public void notifyUserExecutedQuit() {
        disconnect(true);
    }

    private void notifyDisconnected() {
        synchronized (mLock) {
            if (mAutoRunHelper != null)
                mAutoRunHelper.cancelUserCommandExecution();
        }
        if (isDisconnecting()) {
            notifyFullyDisconnected();
            return;
        }
        synchronized (mLock) {
            setConnected(false);
            mConnecting = false;
            if (mDisconnecting) {
                notifyFullyDisconnected();
                return;
            }
            if (mUserDisconnectRequest)
                return;
        }
        int reconnectDelay = mManager.getReconnectDelay(mCurrentReconnectAttempt);
        if (reconnectDelay == -1)
            return;
        // Only increment counter if we are actually going to reconnect
        mCurrentReconnectAttempt++;
        Log.i("ServerConnectionInfo", "Queuing reconnect in " + reconnectDelay + " ms");
        mReconnectQueueTime = System.nanoTime();
        mReconnectHandler.postDelayed(mReconnectRunnable, reconnectDelay);
    }

    private void notifyFullyDisconnected() {
        synchronized (mLock) {
            setConnected(false);
            mConnecting = false;
            mDisconnecting = false;
        }
        mManager.notifyConnectionFullyDisconnected(this);
    }

    private void detachDataStructures() {
        // Unregister channel list listener to prevent memory leaks
        if (mApi != null && mChannelListListener != null) {
            mApi.unsubscribeChannelList(mChannelListListener, null, null);
            mChannelListListener = null;
        }
        // Clear listeners to prevent holding references to destroyed UI components
        synchronized (mInfoListeners) {
            mInfoListeners.clear();
        }
        synchronized (mChannelsListeners) {
            mChannelsListeners.clear();
        }
    }

    public void close() {
        synchronized (mLock) {
            Log.i("ServerConnectionInfo", "Closing");
            mIsClosed = true;
            // Remove any pending reconnect callbacks
            mReconnectHandler.removeCallbacks(mReconnectRunnable);

            // Unregister listeners and clear listener lists to prevent memory leaks
            detachDataStructures();

            if (getApiInstance() != null) {
                MessageStorageApi m = getApiInstance().getMessageStorageApi();
                if (m instanceof SQLiteMessageStorageApi)
                    ((SQLiteMessageStorageApi) m).close();
                ServerConnectionData connectionData = ((ServerConnectionApi) getApiInstance())
                        .getServerConnectionData();
                connectionData.setMessageStorageApi(new StubMessageStorageApi());
                connectionData.setChannelDataStorage(null);
            }
            if (mSQLiteMiscStorage != null)
                mSQLiteMiscStorage.close();
        }
    }

    public void notifyConnectivityChanged(boolean hasAnyConnectivity) {
        mReconnectHandler.removeCallbacks(mReconnectRunnable);

        Log.i("ServerConnectionInfo", "notifyConnectivityChanged(hasAnyConnectivity=" + hasAnyConnectivity + ") for server " + getName());

        if (!hasAnyConnectivity) {
            Log.i("ServerConnectionInfo", "Skipping reconnect because hasAnyConnectivity is false");
            return;
        }
        if (!AppSettings.isReconnectEnabled()) {
            Log.i("ServerConnectionInfo", "Skipping reconnect because AppSettings.isReconnectEnabled() is false");
            return;
        }

        if (isNetworkMetered(mManager.getContext())) {
            Log.i("ServerConnectionInfo", "Network is metered mobile data, checking reconnect policy");
        }

        if (AppSettings.isReconnectOnConnectivityChangeEnabled()) {
            Log.i("ServerConnectionInfo", "Attempting connect() on connectivity change for " + getName());
            connect(); // this will be ignored if we are already connected
        } else if (mReconnectQueueTime != -1L) {
            long reconnectDelay = mManager.getReconnectDelay(mCurrentReconnectAttempt);
            if (reconnectDelay == -1)
                return;
            // Only increment counter if we are actually going to reconnect
            mCurrentReconnectAttempt++;
            long remainingDelay = reconnectDelay - (System.nanoTime() - mReconnectQueueTime) / 1000000L;
            Log.i("ServerConnectionInfo", "Scheduling reconnect for " + getName() + " in " + remainingDelay + " ms");
            if (remainingDelay <= 0L)
                connect();
            else
                mReconnectHandler.postDelayed(mReconnectRunnable, remainingDelay);
        }
    }
    
    /**
     * Checks if the current network is metered.
     * Note: WiFi and Ethernet are never treated as metered to prevent blocking reconnects on MIUI.
     * null activeNetwork is treated as unknown (returns false) so reconnect is not blocked.
     * @param context Application context
     * @return true if network is metered mobile data, false otherwise
     */
    private static boolean isNetworkMetered(Context context) {
        ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Network activeNetwork = cm.getActiveNetwork();
            if (activeNetwork == null) {
                Log.d("ServerConnectionInfo", "isNetworkMetered: activeNetwork is null (treating as unknown/unmetered)");
                return false;
            }
            NetworkCapabilities caps = cm.getNetworkCapabilities(activeNetwork);
            if (caps == null) {
                Log.d("ServerConnectionInfo", "isNetworkMetered: NetworkCapabilities is null (treating as unknown/unmetered)");
                return false;
            }
            boolean isWifiOrEthernet = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                    || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET);
            if (isWifiOrEthernet) {
                return false; // Never block WiFi/Ethernet
            }
            return !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED);
        } else {
            NetworkInfo activeNetwork = cm.getActiveNetworkInfo();
            if (activeNetwork == null) return false;
            return activeNetwork.isConnected() && activeNetwork.getType() == ConnectivityManager.TYPE_MOBILE;
        }
    }

    public UUID getUUID() {
        return mServerConfig.uuid;
    }

    public String getName() {
        return mServerConfig.name;
    }

    public String getHost() {
        return mServerConfig.address;
    }

    public ChatApi getApiInstance() {
        synchronized (mLock) {
            return mApi;
        }
    }

    public SQLiteMiscStorage getSQLiteMiscStorage() {
        synchronized (mLock) {
            return mSQLiteMiscStorage;
        }
    }

    public MessageId.Parser getMessageIdParser() {
        // NOTE: We hardcode it to to SQLite here, as this is the only storage type we current use.
        // This might need to be changed if we switch storage type in the future.
        return SQLiteMessageStorageApi.getMessageIdParserInstance();
    }

    public boolean isConnected() {
        synchronized (mLock) {
            return mConnected;
        }
    }

    public void setConnected(boolean connected) {
        synchronized (mLock) {
            mConnected = connected;
        }
        notifyInfoChanged();
    }

    public boolean isConnecting() {
        synchronized (mLock) {
            return mConnecting;
        }
    }

    public boolean isDisconnecting() {
        synchronized (mLock) {
            return mDisconnecting;
        }
    }

    public boolean hasUserDisconnectRequest() {
        synchronized (mLock) {
            return mUserDisconnectRequest;
        }
    }

    public List<String> getChannels() {
        synchronized (mLock) {
            if (mChannels == null)
                return Collections.emptyList();
            return Collections.unmodifiableList(new ArrayList<>(mChannels));
        }
    }

    public boolean hasChannel(String channel) {
        synchronized (mLock) {
            for (String c : mChannels) {
                if (c.equalsIgnoreCase(channel))
                    return true;
            }
            return false;
        }
    }

    public void setChannels(List<String> channels) {
        // Make a defensive copy so external modifications don't affect internal state
        List<String> sortedChannels = (channels == null) ? new ArrayList<>() : new ArrayList<>(channels);
        Collections.sort(sortedChannels, String::compareToIgnoreCase);
        synchronized (mLock) {
            mChannels = sortedChannels;
        }
        synchronized (mChannelsListeners) {
            mManager.notifyChannelListChanged(this, sortedChannels);
            mManager.saveAutoconnectListAsync();
            List<ChannelListChangeListener> listeners = new ArrayList<>(mChannelsListeners);
            for (ChannelListChangeListener listener : listeners)
                listener.onChannelListChanged(this, sortedChannels);
        }
    }

    public void addChannelIfAbsent(String channel) {
        if (channel == null)
            return;
        synchronized (mLock) {
            if (hasChannel(channel))
                return;
            List<String> newChannels = new ArrayList<>(getChannels());
            newChannels.add(channel);
            setChannels(newChannels);
        }
    }

    public void removeChannel(String channel) {
        if (channel == null)
            return;
        synchronized (mLock) {
            boolean found = false;
            List<String> newChannels = new ArrayList<>(getChannels());
            for (int i = 0; i < newChannels.size(); i++) {
                if (newChannels.get(i).equalsIgnoreCase(channel)) {
                    newChannels.remove(i);
                    found = true;
                    break;
                }
            }
            if (found) {
                setChannels(newChannels);
            }
        }
    }

    public boolean isExpandedInDrawer() {
        synchronized (mLock) {
            return mExpandedInDrawer;
        }
    }

    public void setExpandedInDrawer(boolean expanded) {
        synchronized (mLock) {
            mExpandedInDrawer = expanded;
        }
    }

    public NotificationManager.ConnectionManager getNotificationManager() {
        return mNotificationData;
    }

    public String getUserNick() {
        return ((ServerConnectionApi) getApiInstance()).getServerConnectionData().getUserNick();
    }

    public ChatUIData getChatUIData() {
        return mChatUIData;
    }

    public void addOnChannelInfoChangeListener(InfoChangeListener listener) {
        synchronized (mInfoListeners) {
            mInfoListeners.add(listener);
        }
    }

    public void removeOnChannelInfoChangeListener(InfoChangeListener listener) {
        synchronized (mInfoListeners) {
            mInfoListeners.remove(listener);
        }
    }

    public void addOnChannelListChangeListener(ChannelListChangeListener listener) {
        synchronized (mChannelsListeners) {
            mChannelsListeners.add(listener);
        }
    }

    public void removeOnChannelListChangeListener(ChannelListChangeListener listener) {
        synchronized (mChannelsListeners) {
            mChannelsListeners.remove(listener);
        }
    }

    private final List<StorageChangeListener> mStorageListeners = new ArrayList<>();

    public void addStorageChangeListener(StorageChangeListener listener) {
        synchronized (mStorageListeners) {
            mStorageListeners.add(listener);
        }
    }

    public void removeStorageChangeListener(StorageChangeListener listener) {
        synchronized (mStorageListeners) {
            mStorageListeners.remove(listener);
        }
    }

    public void notifyStorageApiChanged() {
        synchronized (mStorageListeners) {
            for (StorageChangeListener listener : mStorageListeners)
                listener.onStorageApiChanged(this);
        }
    }

    public interface StorageChangeListener {
        void onStorageApiChanged(ServerConnectionInfo connection);
    }

    private void notifyInfoChanged() {
        synchronized (mInfoListeners) {
            for (InfoChangeListener listener : mInfoListeners)
                listener.onConnectionInfoChanged(this);
            mManager.notifyConnectionInfoChanged(this);
        }
    }

    private final Runnable mReconnectRunnable = () -> {
        synchronized (mLock) {
            if (mIsClosed)
                return;
        }
        mReconnectQueueTime = -1L;
        if (!AppSettings.isReconnectEnabled())
            return;
        this.connect();
    };

    public interface InfoChangeListener {
        void onConnectionInfoChanged(ServerConnectionInfo connection);
    }

    public interface ChannelListChangeListener {
        void onChannelListChanged(ServerConnectionInfo connection, List<String> newChannels);
    }

}
