package io.mrarm.irc;

import android.content.Context;
import android.util.Log;
import androidx.annotation.NonNull;

import java.io.File;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.ZoneId;
import java.util.Calendar;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

import io.mrarm.irc.config.AppSettings;
import io.mrarm.irc.config.ServerConfigData;
import io.mrarm.irc.config.ServerConfigManager;
import io.mrarm.irc.config.SettingChangeCallback;
import io.mrarm.irc.config.SettingsHelper;
import io.mrarm.irc.util.PoolSerialExecutor;
import io.mrarm.chatlib.android.storage.SQLiteMessageStorageApi;

import java.util.concurrent.TimeUnit;

public class ChatLogStorageManager implements ServerConfigManager.ConnectionsListener {

    private static final int MIN_GLOBAL_MESSAGES_UPDATE = 1024;
    private static final int MIN_SERVER_MESSAGES_UPDATE = 128;

    private static final int GLOBAL_DELETION_CANDIDATES = 1024;
    private static final int CONNECTION_DELETION_CANDIDATES = 32;

    // Thread-safe DateTimeFormatter (Java 8+)
    private static final DateTimeFormatter sFileNameFormatter = DateTimeFormatter.ofPattern("'messages-'yyyy-MM-dd'.db'", Locale.US);

    private static String formatFileName(Date date) {
        return sFileNameFormatter.format(date.toInstant().atZone(ZoneId.systemDefault()).toLocalDate());
    }

    private static String formatFileName(long time) {
        return sFileNameFormatter.format(java.time.Instant.ofEpochMilli(time).atZone(ZoneId.systemDefault()).toLocalDate());
    }

    private static long parseFileName(String name) {
        // Parse 'messages-yyyy-MM-dd.db' to get epoch millis
        try {
            String dateStr = name.substring("messages-".length(), "messages-".length() + 10); // yyyy-MM-dd
            LocalDate date = LocalDate.parse(dateStr, DateTimeFormatter.ISO_LOCAL_DATE);
            return date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
        } catch (Exception e) {
            return 0L;
        }
    }

    private static volatile ChatLogStorageManager sInstance;

    public static ChatLogStorageManager getInstance(Context context) {
        if (sInstance == null) {
            synchronized (ChatLogStorageManager.class) {
                if (sInstance == null) {
                    sInstance = new ChatLogStorageManager(context.getApplicationContext());
                }
            }
        }
        return sInstance;
    }

    private final ServerConnectionManager mConnectionManager;
    private final ServerConfigManager mServerConfigManager;
    private final Map<UUID, ServerManager> mServerManagers = new ConcurrentHashMap<>();
    private final AtomicInteger mGlobalMessageCounter = new AtomicInteger(0);
    private final TreeSet<DeletionCandidate> mGlobalDeletionCandidates = new TreeSet<>();
    private final Object mGlobalDeletionCandidatesLock = new Object();
    private long mGlobalTotalSize = 0L;
    private long mGlobalLimit;
    private long mDefaultServerLimit;
    private final Executor mExecutor;
    private volatile boolean mDestroyed = false;

    public ChatLogStorageManager(Context context) {
        mConnectionManager = ServerConnectionManager.getInstance(context);
        mServerConfigManager = ServerConfigManager.getInstance(context);

        SettingsHelper.registerCallbacks(this);
        onSettingChanged();

        mExecutor = new PoolSerialExecutor();

        mServerConfigManager.addListener(this);
        List<ServerConfigData> servers = mServerConfigManager.getServers();
        mExecutor.execute(() -> {
            for (ServerConfigData data : servers)
                mServerManagers.put(data.uuid, new ServerManager(data));
            performUpdate(null);
        });
    }

    /**
     * Destroys this manager, unregistering callbacks and shutting down the executor.
     * Should be called when the application is terminating.
     */
    public void destroy() {
        mDestroyed = true;
        SettingsHelper.unregisterCallbacks(this);
        mServerConfigManager.removeListener(this);
        if (mExecutor instanceof PoolSerialExecutor) {
            ((PoolSerialExecutor) mExecutor).shutdown();
            try {
                if (!((PoolSerialExecutor) mExecutor).awaitTermination(5, TimeUnit.SECONDS)) {
                    ((PoolSerialExecutor) mExecutor).shutdownNow();
                }
            } catch (InterruptedException e) {
                ((PoolSerialExecutor) mExecutor).shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        mServerManagers.clear();
        synchronized (mGlobalDeletionCandidatesLock) {
            mGlobalDeletionCandidates.clear();
        }
    }

    /**
     * @return true if this manager has been destroyed
     */
    public boolean isDestroyed() {
        return mDestroyed;
    }

    @SettingChangeCallback(keys = {
            AppSettings.PREF_STORAGE_LIMIT_GLOBAL,
            AppSettings.PREF_STORAGE_LIMIT_SERVER
    })
    private void onSettingChanged() {
        mGlobalLimit = AppSettings.getStorageLimitGlobal();
        mDefaultServerLimit = AppSettings.getStorageLimitServer();
    }

    public void requestUpdate(UUID serverUUID) {
        if (mDestroyed) return;
        mExecutor.execute(() -> performUpdate(serverUUID));
    }

    public void requestUpdate(UUID serverUUID, Runnable callback) {
        if (mDestroyed) return;
        mExecutor.execute(() -> {
            performUpdate(serverUUID);
            callback.run();
        });
    }

    private void performUpdate(UUID serverUUID) {
        Calendar calendar = Calendar.getInstance();
        int currentYear = calendar.get(Calendar.YEAR);
        int currentMonth = calendar.get(Calendar.MONTH);
        int currentDay = calendar.get(Calendar.DAY_OF_MONTH);
        if (serverUUID == null) {
            for (ServerManager manager : mServerManagers.values())
                manager.update(currentYear, currentMonth, currentDay);
        } else {
            ServerManager manager = mServerManagers.get(serverUUID);
            if (manager != null)
                manager.update(currentYear, currentMonth, currentDay);
        }
        synchronized (mGlobalDeletionCandidatesLock) {
            if (mGlobalLimit != -1L && mGlobalTotalSize > mGlobalLimit)
                performGlobalDeletion(mGlobalTotalSize - mGlobalLimit);
        }
    }

    private void performGlobalDeletion(long size) {
        int maxIterations = 100; // Guard against infinite loop (Bug #7)
        int iterations = 0;
        while (true) {
            if (iterations++ >= maxIterations) {
                Log.w("ChatLogStorageManager", "performGlobalDeletion: max iterations reached, stopping to prevent infinite loop");
                return;
            }
            boolean deletedAnyFile = false;
            DeletionCandidate[] candidatesToDelete;
            synchronized (mGlobalDeletionCandidatesLock) {
                candidatesToDelete = mGlobalDeletionCandidates.toArray(new DeletionCandidate[0]);
            }
            for (DeletionCandidate candidate : candidatesToDelete) {
                synchronized (mGlobalDeletionCandidatesLock) {
                    // Verify candidate still exists (may have been removed by reload)
                    if (!mGlobalDeletionCandidates.contains(candidate)) {
                        continue;
                    }
                    mGlobalTotalSize -= candidate.size;
                    mGlobalDeletionCandidates.remove(candidate);
                }
                File file = new File(candidate.server.mLogsDir, formatFileName(candidate.dateMs));
                size -= getFileSize(file);
                SettingsHelper.deleteSQLiteDatabase(file);
                deletedAnyFile = true;
                if (size <= 0L) {
                    synchronized (mGlobalDeletionCandidatesLock) {
                        if (mGlobalDeletionCandidates.isEmpty()) {
                            // If there are no deletion candidates left, refresh the list so they are
                            // available during the next performGlobalDeletion call.
                            for (ServerManager manager : mServerManagers.values())
                                manager.reload();
                        }
                    }
                    return;
                }
            }
            if (!deletedAnyFile)
                return;
            // Refresh deletion candidates
            for (ServerManager manager : mServerManagers.values())
                manager.reload();
        }
    }

    public void onMessage(ServerConnectionInfo connection) {
        if (mDestroyed) return;
        if (mGlobalMessageCounter.incrementAndGet() >= MIN_GLOBAL_MESSAGES_UPDATE) {
            requestUpdate(null);
            for (ServerConnectionInfo info : mConnectionManager.getConnections())
                info.mChatLogStorageUpdateCounter = 0;
            mGlobalMessageCounter.set(0);
            return;
        }
        if (++connection.mChatLogStorageUpdateCounter >= MIN_SERVER_MESSAGES_UPDATE) {
            requestUpdate(connection.getUUID());
            connection.mChatLogStorageUpdateCounter = 0;
        }
    }

    private void addGlobalDeletionCandidate(DeletionCandidate candidate) {
        synchronized (mGlobalDeletionCandidatesLock) {
            if (mGlobalDeletionCandidates.size() < GLOBAL_DELETION_CANDIDATES) {
                mGlobalDeletionCandidates.add(candidate);
            } else if (mGlobalDeletionCandidates.lower(candidate) != null) {
                mGlobalDeletionCandidates.add(candidate);
                mGlobalDeletionCandidates.remove(mGlobalDeletionCandidates.last());
            }
        }
    }

    /**
     * Gets the file size in bytes. Since we only use this for relative comparisons
     * and quota calculations, we use actual file length instead of block-aligned size.
     * This avoids deprecated StatFs API.
     */
    private long getFileSize(File file) {
        return file.length();
    }

    @Override
    public void onConnectionAdded(ServerConfigData data) {
        mExecutor.execute(() -> {
            mServerManagers.put(data.uuid, new ServerManager(data));
        });
    }

    @Override
    public void onConnectionRemoved(ServerConfigData data) {
        mExecutor.execute(() -> {
            ServerManager manager = mServerManagers.get(data.uuid);
            if (manager != null) {
                manager.remove();
            }
            mServerManagers.remove(data.uuid);
        });
    }

    @Override
    public void onConnectionUpdated(ServerConfigData data) {
        requestUpdate(data.uuid);
    }

    /**
     * Safely deletes all chat logs for a specific server and recreates the storage API.
     * This runs on the ChatLogStorageManager's executor to ensure serialization
     * with other storage operations (performUpdate, onMessage, etc.).
     *
     * @param uuid The server UUID
     * @param callback Callback that receives the new SQLiteMessageStorageApi (or null on error)
     */
    public void deleteServerLogs(UUID uuid, StorageDeletionCallback callback) {
        if (mDestroyed) {
            if (callback != null) callback.onComplete(null);
            return;
        }
        mExecutor.execute(() -> {
            SQLiteMessageStorageApi newStorageApi = null;
            try {
                // Delete the actual database files
                File logsDir = mServerConfigManager.getServerChatLogDir(uuid);
                if (logsDir != null) {
                    logsDir.mkdirs(); // Ensure directory exists for new DB
                    File[] files = logsDir.listFiles();
                    if (files != null) {
                        for (File file : files) {
                            if (file.getName().startsWith("messages-") && file.getName().endsWith(".db")) {
                                SettingsHelper.deleteSQLiteDatabase(file);
                            }
                        }
                    }

                    // Create new storage API for the connection to use
                    newStorageApi = new SQLiteMessageStorageApi(logsDir);
                    newStorageApi.open();
                }

                // Reload or recreate ServerManager so storage manager keeps tracking this server
                ServerManager manager = mServerManagers.get(uuid);
                if (manager != null) {
                    manager.reload();
                } else {
                    ServerConfigData config = mServerConfigManager.findServer(uuid);
                    if (config != null) {
                        mServerManagers.put(uuid, new ServerManager(config));
                    }
                }
            } catch (Exception e) {
                Log.e("ChatLogStorageManager", "Error deleting server logs for " + uuid, e);
                // Close new storage API if it was created but something failed
                if (newStorageApi != null) {
                    try { newStorageApi.close(); } catch (Exception ignored) {}
                }
                newStorageApi = null;
            } finally {
                if (callback != null) callback.onComplete(newStorageApi);
            }
        });
    }

    /**
     * Callback for storage deletion with result.
     */
    public interface StorageDeletionCallback {
        void onComplete(SQLiteMessageStorageApi newStorageApi);
    }

    public class ServerManager {

        private final ServerConfigData mServerConfig;
        private final File mLogsDir;
        private long mTotalSize = 0L;
        private final Calendar mCurrentLogTime;
        private File mCurrentLogFile;
        private long mCurrentLogSize = 0L;
        private final TreeSet<DeletionCandidate> mDeletionCandidates = new TreeSet<>();

        public ServerManager(ServerConfigData config) {
            mServerConfig = config;
            mCurrentLogTime = Calendar.getInstance();
            mLogsDir = mServerConfigManager.getServerChatLogDir(config.uuid);
            reload();
        }

        private void reload() {
            remove();
            File[] files = mLogsDir.listFiles();
            if (files == null)
                return;
            synchronized (mGlobalDeletionCandidatesLock) {
                mTotalSize = 0L;
                int currentYear = mCurrentLogTime.get(Calendar.YEAR);
                int currentMonth = mCurrentLogTime.get(Calendar.MONTH);
                int currentDay = mCurrentLogTime.get(Calendar.DAY_OF_MONTH);
                Calendar calendar = Calendar.getInstance();
                for (File file : files) {
                    long fileSize = getFileSize(file);
                    mTotalSize += fileSize;
                    long dateMs = parseFileName(file.getName());
                    if (dateMs == 0L)
                        continue;
                    calendar.setTimeInMillis(dateMs);
                    if (calendar.get(Calendar.YEAR) == currentYear &&
                            calendar.get(Calendar.MONTH) == currentMonth &&
                            calendar.get(Calendar.DAY_OF_MONTH) == currentDay) {
                        mCurrentLogFile = file;
                        mCurrentLogSize = fileSize;
                        continue;
                    }
                    DeletionCandidate candidate = new DeletionCandidate(this, fileSize, dateMs);
                    addDeletionCandidate(candidate);
                    addGlobalDeletionCandidate(candidate);
                }
                mGlobalTotalSize += mTotalSize;
            }
        }

        public void remove() {
            synchronized (mGlobalDeletionCandidatesLock) {
                mGlobalTotalSize -= mTotalSize;
                mDeletionCandidates.clear();
                for (Iterator<DeletionCandidate> iterator = mGlobalDeletionCandidates.iterator(); iterator.hasNext(); ) {
                    DeletionCandidate candidate = iterator.next();
                    if (candidate.server == this)
                        iterator.remove();
                }
                mTotalSize = 0L;
                mCurrentLogFile = null;
                mCurrentLogSize = 0L;
            }
        }

        public void update(int currentYear, int currentMonth, int currentDay) {
            synchronized (mGlobalDeletionCandidatesLock) {
                mTotalSize -= mCurrentLogSize;
                mGlobalTotalSize -= mCurrentLogSize;
                mCurrentLogSize = mCurrentLogFile == null ? 0L : getFileSize(mCurrentLogFile);
                mTotalSize += mCurrentLogSize;
                mGlobalTotalSize += mCurrentLogSize;

                while (currentYear > mCurrentLogTime.get(Calendar.YEAR) || (currentYear == mCurrentLogTime.get(Calendar.YEAR) &&
                        (currentMonth > mCurrentLogTime.get(Calendar.MONTH) || (currentMonth == mCurrentLogTime.get(Calendar.MONTH) &&
                                currentDay > mCurrentLogTime.get(Calendar.DAY_OF_MONTH))))) {
                    if (mCurrentLogFile != null && mCurrentLogFile.exists()) {
                        DeletionCandidate candidate = new DeletionCandidate(this, mCurrentLogSize,
                                mCurrentLogTime.getTimeInMillis());
                        addDeletionCandidate(candidate);
                        addGlobalDeletionCandidate(candidate);
                    }

                    mCurrentLogTime.add(Calendar.DAY_OF_MONTH, 1);
                    mCurrentLogFile = new File(mLogsDir, formatFileName(mCurrentLogTime.getTime()));
                    mCurrentLogSize = getFileSize(mCurrentLogFile);
                    mTotalSize += mCurrentLogSize;
                    mGlobalTotalSize += mCurrentLogSize;
                }

                long limit = mServerConfig.storageLimit != 0L ? mServerConfig.storageLimit : mDefaultServerLimit;
                if (limit != -1L && mTotalSize >= limit)
                    performDeletion(mTotalSize - limit);
            }
        }

        private void performDeletion(long size) {
            int maxIterations = 100; // Guard against infinite loop
            int iterations = 0;
            while (true) {
                if (iterations++ >= maxIterations) {
                    Log.w("ChatLogStorageManager", "performDeletion: max iterations reached, stopping to prevent infinite loop");
                    return;
                }
                boolean deletedAnyFile = false;
                DeletionCandidate[] candidatesToDelete;
                synchronized (mGlobalDeletionCandidatesLock) {
                    candidatesToDelete = mDeletionCandidates.toArray(new DeletionCandidate[0]);
                }
                for (DeletionCandidate candidate : candidatesToDelete) {
                    synchronized (mGlobalDeletionCandidatesLock) {
                        // Verify candidate still exists
                        if (!mDeletionCandidates.contains(candidate)) {
                            continue;
                        }
                        mGlobalTotalSize -= candidate.size;
                        mDeletionCandidates.remove(candidate);
                        // Also remove from global candidates
                        mGlobalDeletionCandidates.remove(candidate);
                    }
                    File file = new File(candidate.server.mLogsDir, formatFileName(candidate.dateMs));
                    size -= getFileSize(file);
                    SettingsHelper.deleteSQLiteDatabase(file);
                    deletedAnyFile = true;
                    if (size <= 0L) {
                        synchronized (mGlobalDeletionCandidatesLock) {
                            if (mGlobalDeletionCandidates.isEmpty())
                                reload();
                        }
                        return;
                    }
                }
                if (!deletedAnyFile)
                    return;
                reload();
            }
        }

        private void addDeletionCandidate(DeletionCandidate candidate) {
            synchronized (mGlobalDeletionCandidatesLock) {
                if (mDeletionCandidates.size() < CONNECTION_DELETION_CANDIDATES) {
                    mDeletionCandidates.add(candidate);
                } else if (mDeletionCandidates.lower(candidate) != null) {
                    mDeletionCandidates.add(candidate);
                    mDeletionCandidates.remove(mDeletionCandidates.last());
                }
            }
        }

    }


    private static class DeletionCandidate implements Comparable<DeletionCandidate> {

        private ServerManager server;
        private long size;
        private long dateMs;

        public DeletionCandidate(ServerManager server, long size, long dateMs) {
            this.server = server;
            this.size = size;
            this.dateMs = dateMs;
        }

        @Override
        public int compareTo(@NonNull DeletionCandidate o) {
            if (dateMs != o.dateMs)
                return Long.compare(dateMs, o.dateMs);
            if (size != o.size)
                return Long.compare(size, o.size);
            return server.mServerConfig.uuid.compareTo(o.server.mServerConfig.uuid);
        }

    }

}
