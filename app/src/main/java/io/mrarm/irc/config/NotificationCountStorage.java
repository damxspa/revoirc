package io.mrarm.irc.config;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteDoneException;
import android.database.sqlite.SQLiteStatement;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;

import java.io.File;
import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import io.mrarm.irc.util.SettableFuture;

public class NotificationCountStorage implements AutoCloseable {

    private static volatile NotificationCountStorage sInstance;

    // Flush delay for notification count changes. Could be made configurable via settings in the future.
    private static final int FLUSH_DELAY = 10 * 1000;

    private static final int DB_VERSION = 2;

    public static NotificationCountStorage getInstance(Context ctx) {
        if (sInstance == null) {
            synchronized (NotificationCountStorage.class) {
                if (sInstance == null) {
                    sInstance = new NotificationCountStorage(getFile(ctx).getAbsolutePath());
                }
            }
        }
        return sInstance;
    }

    public static File getFile(Context ctx) {
        return new File(ctx.getFilesDir(), "notification-count.db");
    }

    private final String mPath;
    private final Object mDatabaseLock = new Object();
    private SQLiteDatabase mDatabase;
    private SQLiteStatement mGetNotificationCountStatement;
    private SQLiteStatement mIncrementNotificationCountStatement;
    private SQLiteStatement mCreateNotificationCountStatement;
    private SQLiteStatement mGetFirstMessageIdStatement;
    private SQLiteStatement mSetFirstMessageIdStatement;
    private SQLiteStatement mResetFirstMessageIdStatement;
    private final Handler mHandler;
    private final HandlerThread mHandlerThread;
    private Map<UUID, Map<String, Integer>> mChangeQueue;
    private boolean mClosed = false;

    public NotificationCountStorage(String path) {
        mPath = path;
        mHandlerThread = new HandlerThread("NotificationCountStorage Thread");
        mHandlerThread.start();
        mHandler = new Handler(mHandlerThread.getLooper());

        // Initialize database synchronously in constructor to avoid race conditions
        open();
    }

    /**
     * Opens or reopens the database. Can be called after close() to reinitialize.
     * This method is thread-safe and idempotent.
     */
    public void open() {
        synchronized (mDatabaseLock) {
            if (mDatabase != null) {
                return;
            }
            if (mClosed) {
                // If closed, reset closed flag to allow reopening
                mClosed = false;
            }
            try {
                mDatabase = SQLiteDatabase.openOrCreateDatabase(mPath, null);
                // Enable WAL mode for better concurrency and crash resilience
                mDatabase.enableWriteAheadLogging();
                
                if (mDatabase.getVersion() < DB_VERSION) {
                    mDatabase.execSQL("DROP TABLE IF EXISTS 'notification_count'");
                }
                mDatabase.execSQL("CREATE TABLE IF NOT EXISTS 'notification_count' (server TEXT, channel TEXT, count INTEGER, firstMessageId TEXT)");
                mGetNotificationCountStatement = mDatabase.compileStatement("SELECT count FROM 'notification_count' WHERE server=?1 AND channel=?2");
                mIncrementNotificationCountStatement = mDatabase.compileStatement("UPDATE 'notification_count' SET count=count+?3 WHERE server=?1 AND channel=?2");
                mCreateNotificationCountStatement = mDatabase.compileStatement("INSERT INTO 'notification_count' (server, channel, count, firstMessageId) VALUES (?1, ?2, ?3, ?4)");
                mGetFirstMessageIdStatement = mDatabase.compileStatement("SELECT firstMessageId FROM 'notification_count' WHERE server=?1 AND channel=?2");
                mSetFirstMessageIdStatement = mDatabase.compileStatement("UPDATE 'notification_count' SET firstMessageId=?3 WHERE server=?1 AND channel=?2 AND firstMessageId IS NULL");
                mResetFirstMessageIdStatement = mDatabase.compileStatement("UPDATE 'notification_count' SET firstMessageId=NULL WHERE server=?1 AND channel=?2");
                mDatabase.setVersion(DB_VERSION);
            } catch (Exception e) {
                Log.e("NotifCountStorage", "Failed to open database", e);
                mDatabase = null;
                throw new RuntimeException("Failed to initialize notification count database", e);
            }
        }
    }

    /**
     * Validates that the database is accessible and has the expected schema.
     * Throws an exception if validation fails.
     */
    public void validateDatabase() {
        synchronized (mDatabaseLock) {
            if (mDatabase == null || !mDatabase.isOpen()) {
                throw new IllegalStateException("Database is not open");
            }
            // Verify table exists and has expected columns
            mDatabase.rawQuery("SELECT count, firstMessageId FROM 'notification_count' LIMIT 1", null).close();
        }
    }

    @Override
    public void close() {
        if (mClosed) {
            return; // Already closed
        }
        mClosed = true;
        
        SettableFuture<Void> s = new SettableFuture<>();
        mHandler.post(() -> {
            mHandler.removeCallbacks(mFlushQueuedChangesRunnable);
            flushQueuedChanges();
            synchronized (mDatabaseLock) {
                if (mDatabase != null) {
                    mDatabase.close();
                    mDatabase = null;
                }
                mGetNotificationCountStatement = null;
                mIncrementNotificationCountStatement = null;
                mCreateNotificationCountStatement = null;
                mGetFirstMessageIdStatement = null;
                mSetFirstMessageIdStatement = null;
                mResetFirstMessageIdStatement = null;
                mDatabaseLock.notifyAll();
            }
            s.set(null);
        });
        try {
            s.get(5, TimeUnit.SECONDS);
        } catch (InterruptedException | TimeoutException | java.util.concurrent.ExecutionException e) {
            Log.w("NotifCountStorage", "Timeout waiting for close", e);
        }
        // Shutdown handler thread after close completes
        mHandlerThread.quit();
    }

    /**
     * Checks if the storage has been closed.
     */
    public boolean isClosed() {
        return mClosed;
    }

    /**
     * Checks if database is open and ready without blocking.
     */
    public boolean isDatabaseReady() {
        synchronized (mDatabaseLock) {
            return mDatabase != null && mDatabase.isOpen();
        }
    }

    private int getChannelCounter(UUID server, String channel) {
        synchronized (mDatabaseLock) {
            if (!isDatabaseReady())
                return 0;
            mGetNotificationCountStatement.bindString(1, server.toString());
            mGetNotificationCountStatement.bindString(2, channel);
            long ret;
            try {
                ret = mGetNotificationCountStatement.simpleQueryForLong();
            } catch (SQLiteDoneException e) {
                ret = 0;
            }
            mGetNotificationCountStatement.clearBindings();
            return (int) ret;
        }
    }

    private void incrementChannelCounter(UUID server, String channel, int i) {
        synchronized (mDatabaseLock) {
            if (!isDatabaseReady())
                return;
            mIncrementNotificationCountStatement.bindString(1, server.toString());
            mIncrementNotificationCountStatement.bindString(2, channel);
            mIncrementNotificationCountStatement.bindLong(3, i);
            int ii = mIncrementNotificationCountStatement.executeUpdateDelete();
            mIncrementNotificationCountStatement.clearBindings();
            if (ii == 0) {
                mCreateNotificationCountStatement.bindString(1, server.toString());
                mCreateNotificationCountStatement.bindString(2, channel);
                mCreateNotificationCountStatement.bindLong(3, i);
                mCreateNotificationCountStatement.bindNull(4);
                mCreateNotificationCountStatement.execute();
                mCreateNotificationCountStatement.clearBindings();
            }
        }
    }

    private void resetChannelCounter(UUID server, String channel) {
        synchronized (mDatabaseLock) {
            if (!isDatabaseReady())
                return;
            mDatabase.execSQL("DELETE FROM 'notification_count' WHERE server=?1 AND channel=?2",
                    new Object[]{server.toString(), channel});
        }
    }

    private void removeServerCounters(UUID server) {
        synchronized (mDatabaseLock) {
            if (!isDatabaseReady())
                return;
            mDatabase.execSQL("DELETE FROM 'notification_count' WHERE server=?1",
                    new Object[]{server.toString()});
        }
    }

    private String getFirstMessageId(UUID server, String channel) {
        synchronized (mDatabaseLock) {
            if (!isDatabaseReady())
                return null;
            mGetFirstMessageIdStatement.bindString(1, server.toString());
            mGetFirstMessageIdStatement.bindString(2, channel);
            String ret;
            try {
                ret = mGetFirstMessageIdStatement.simpleQueryForString();
            } catch (SQLiteDoneException ignored) {
                ret = null;
            }
            mGetFirstMessageIdStatement.clearBindings();
            return ret;
        }
    }

    private void setFirstMessageId(UUID server, String channel, String messageId) {
        synchronized (mDatabaseLock) {
            if (!isDatabaseReady())
                return;
            mSetFirstMessageIdStatement.bindString(1, server.toString());
            mSetFirstMessageIdStatement.bindString(2, channel);
            mSetFirstMessageIdStatement.bindString(3, messageId);
            int ii = mSetFirstMessageIdStatement.executeUpdateDelete();
            mSetFirstMessageIdStatement.clearBindings();
            if (ii == 0) {
                mCreateNotificationCountStatement.bindString(1, server.toString());
                mCreateNotificationCountStatement.bindString(2, channel);
                mCreateNotificationCountStatement.bindLong(3, 0);
                mCreateNotificationCountStatement.bindString(4, messageId);
                mCreateNotificationCountStatement.execute();
                mCreateNotificationCountStatement.clearBindings();
            }
        }
    }

    private void resetFirstMessageId(UUID server, String channel) {
        synchronized (mDatabaseLock) {
            if (!isDatabaseReady())
                return;
            mResetFirstMessageIdStatement.bindString(1, server.toString());
            mResetFirstMessageIdStatement.bindString(2, channel);
            mResetFirstMessageIdStatement.executeUpdateDelete();
            mResetFirstMessageIdStatement.clearBindings();
        }
    }

    private void flushQueuedChanges() {
        Map<UUID, Map<String, Integer>> map;
        synchronized (this) {
            map = mChangeQueue;
            mChangeQueue = null;
        }
        if (map == null)
            return;
        // Iterate over a copy to avoid ConcurrentModificationException if map is modified during iteration
        // (though we already nulled mChangeQueue, defensive copy is safer)
        for (Map.Entry<UUID, Map<String, Integer>> vals : new HashMap<>(map).entrySet()) {
            Map<String, Integer> channelMap = new HashMap<>(vals.getValue());
            for (Map.Entry<String, Integer> v : channelMap.entrySet()) {
                incrementChannelCounter(vals.getKey(), v.getKey(), v.getValue());
            }
        }
    }

    public void requestGetChannelCounter(UUID server, String channel, WeakReference<OnChannelCounterResult> result) {
        mHandler.post(() -> {
            mHandler.removeCallbacks(mFlushQueuedChangesRunnable);
            flushQueuedChanges();

            int res = getChannelCounter(server, channel);
            String msgId = getFirstMessageId(server, channel);
            OnChannelCounterResult cb = result.get();
            if (cb != null)
                cb.onChannelCounterResult(server, channel, res, msgId);
        });
    }

    public void requestIncrementChannelCounter(UUID server, String channel) {
        synchronized (this) {
            boolean requestQueue = false;
            if (mChangeQueue == null) {
                mChangeQueue = new HashMap<>();
                requestQueue = true;
            }
            Map<String, Integer> m = mChangeQueue.get(server);
            if (m == null) {
                m = new HashMap<>();
                mChangeQueue.put(server, m);
            }
            Integer i = m.get(channel);
            m.put(channel, (i == null ? 0 : i) + 1);
            if (requestQueue)
                mHandler.postDelayed(mFlushQueuedChangesRunnable, FLUSH_DELAY);
        }
    }

    public void requestResetChannelCounter(UUID server, String channel) {
        synchronized (this) {
            if (mChangeQueue != null) {
                Map<String, Integer> m = mChangeQueue.get(server);
                if (m != null)
                    m.remove(channel);
            }
        }
        mHandler.post(() -> {
            mHandler.removeCallbacks(mFlushQueuedChangesRunnable);
            flushQueuedChanges();

            resetChannelCounter(server, channel);
        });
    }

    public void requestRemoveServerCounters(UUID server) {
        mHandler.post(() -> {
            mHandler.removeCallbacks(mFlushQueuedChangesRunnable);
            flushQueuedChanges();

            removeServerCounters(server);
        });
    }

    public void requestSetFirstMessageId(UUID server, String channel, String messageId) {
        mHandler.post(() -> setFirstMessageId(server, channel, messageId));
    }

    public void requestResetFirstMessageId(UUID server, String channel) {
        mHandler.post(() -> resetFirstMessageId(server, channel));
    }

    public interface OnChannelCounterResult {
        void onChannelCounterResult(UUID server, String channel, int messages, String firstMessageId);
    }

    private final Runnable mFlushQueuedChangesRunnable = this::flushQueuedChanges;

}