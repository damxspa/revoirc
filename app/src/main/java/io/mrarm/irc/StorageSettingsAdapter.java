package io.mrarm.irc;

import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.os.StatFs;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;
import android.text.style.ForegroundColorSpan;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import java.io.File;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;

import io.mrarm.chatlib.ChatApi;
import io.mrarm.chatlib.android.storage.SQLiteMessageStorageApi;
import io.mrarm.chatlib.irc.ServerConnectionApi;
import io.mrarm.chatlib.irc.ServerConnectionData;
import io.mrarm.irc.config.ChatSettings;
import io.mrarm.irc.config.CommandAliasManager;
import io.mrarm.irc.config.NotificationCountStorage;
import io.mrarm.irc.config.NotificationRuleManager;
import io.mrarm.irc.config.ServerConfigData;
import io.mrarm.irc.config.ServerConfigManager;
import io.mrarm.irc.config.SettingsHelper;
import io.mrarm.irc.dialog.MenuBottomSheetDialog;
import io.mrarm.irc.dialog.ServerStorageLimitDialog;
import io.mrarm.irc.dialog.StorageLimitsDialog;
import io.mrarm.irc.util.ChatExportManager;
import io.mrarm.irc.util.ColoredTextBuilder;
import io.mrarm.irc.util.PoolSerialExecutor;
import io.mrarm.irc.util.StubMessageStorageApi;
import io.mrarm.irc.util.StyledAttributesHelper;
import io.mrarm.irc.view.SimpleBarChart;

public class StorageSettingsAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    public static final int TYPE_SERVER_LOGS_SUMMARY = 0;
    public static final int TYPE_SERVER_LOGS = 1;
    public static final int TYPE_APP_DATA_SUMMARY = 2;
    public static final int TYPE_APP_CACHE_SUMMARY = 3;

    private final List<ServerLogsEntry> mServerLogEntries = new ArrayList<>();
    private final ExecutorService mExecutor = new PoolSerialExecutor();
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());
    private volatile SpaceCalculateTask mCurrentSpaceTask = null;
    private volatile RemoveDataTask mCurrentRemoveTask = null;
    private long mAppDataSize = 0L;
    private long mCacheSize = 0L;
    private int mSecondaryTextColor;
    private boolean mDestroyed = false;

    public StorageSettingsAdapter(Context context) {
        refreshServerLogs(context);

        mSecondaryTextColor = StyledAttributesHelper.getColor(context, android.R.attr.textColorSecondary, Color.BLACK);
    }

    /**
     * Destroys this adapter, shutting down the executor and cleaning up resources.
     * Must be called when the adapter is no longer needed (e.g., in Fragment.onDestroyView).
     */
    public void destroy() {
        mDestroyed = true;
        mMainHandler.removeCallbacksAndMessages(null);
        mExecutor.shutdown();
        try {
            if (!mExecutor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)) {
                mExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            mExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        mCurrentSpaceTask = null;
        mCurrentRemoveTask = null;
    }

    /**
     * @return true if this adapter has been destroyed
     */
    public boolean isDestroyed() {
        return mDestroyed;
    }

    private void refreshServerLogs(Context context) {
        if (mDestroyed) return;
        synchronized (this) {
            if (mCurrentSpaceTask != null)
                return;
            mServerLogEntries.clear();
            notifyDataSetChanged();
            mCurrentSpaceTask = new SpaceCalculateTask(context, this);
            mExecutor.execute(mCurrentSpaceTask);
        }
    }

    private void addEntry(ServerLogsEntry entry) {
        int index = mServerLogEntries.size();
        if (entry.size > 0) {
            int ec = mServerLogEntries.size();
            for (int i = 0; i < ec; i++) {
                if (entry.size > mServerLogEntries.get(i).size) {
                    index = i;
                    break;
                }
            }
        }
        mServerLogEntries.add(index, entry);
        notifyItemInserted(1 + index);
        notifyItemChanged(0);
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        if (viewType == TYPE_SERVER_LOGS_SUMMARY) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.settings_storage_chat_logs_summary, parent, false);
            return new ServerLogsSummaryHolder(view);
        } else if (viewType == TYPE_SERVER_LOGS) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.settings_storage_chat_logs_entry, parent, false);
            return new ServerLogsHolder(view);
        } else if (viewType == TYPE_APP_DATA_SUMMARY) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.settings_storage_app_data_summary, parent, false);
            return new AppDataSummaryHolder(view);
        } else if (viewType == TYPE_APP_CACHE_SUMMARY) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.settings_storage_app_cache_summary, parent, false);
            return new AppCacheSummaryHolder(view);
        }
        throw new IllegalArgumentException("Unknown viewType: " + viewType);
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        int type = holder.getItemViewType();
        if (type == TYPE_SERVER_LOGS_SUMMARY) {
            ((ServerLogsSummaryHolder) holder).bind();
        } else if (type == TYPE_SERVER_LOGS) {
            ((ServerLogsHolder) holder).bind(mServerLogEntries.get(position - 1), position - 1);
        } else if (type == TYPE_APP_DATA_SUMMARY) {
            ((AppDataSummaryHolder) holder).bind();
        } else if (type == TYPE_APP_CACHE_SUMMARY) {
            ((AppCacheSummaryHolder) holder).bind();
        }
    }

    @Override
    public int getItemCount() {
        return 1 + mServerLogEntries.size() + 2;
    }

    @Override
    public int getItemViewType(int position) {
        if (position == 0)
            return TYPE_SERVER_LOGS_SUMMARY;
        if (position >= 1 && position < 1 + mServerLogEntries.size())
            return TYPE_SERVER_LOGS;
        if (position == 1 + mServerLogEntries.size())
            return TYPE_APP_DATA_SUMMARY;
        if (position == 2 + mServerLogEntries.size())
            return TYPE_APP_CACHE_SUMMARY;
        return -1;
    }

    public class ServerLogsSummaryHolder extends RecyclerView.ViewHolder {

        private SimpleBarChart mChart;
        private TextView mTotal;

        public ServerLogsSummaryHolder(View view) {
            super(view);
            mChart = view.findViewById(R.id.chart);
            mTotal = view.findViewById(R.id.total_value);
            view.findViewById(R.id.set_limits).setOnClickListener((View v) -> {
                StorageLimitsDialog dialog = new StorageLimitsDialog(v.getContext());
                dialog.setOnDismissListener((DialogInterface di) -> {
                    ChatLogStorageManager.getInstance(v.getContext()).requestUpdate(null, () -> {
                        v.post(() -> refreshServerLogs(v.getContext()));
                    });
                });
                dialog.show();
            });
            view.findViewById(R.id.clear_chat_logs).setOnClickListener((View v) -> {
                new AlertDialog.Builder(v.getContext())
                        .setTitle(R.string.pref_storage_clear_all_chat_logs)
                        .setMessage(R.string.pref_storage_clear_all_chat_logs_confirm)
                        .setPositiveButton(R.string.action_delete, (DialogInterface di, int i) -> {
                            executeRemoveTask(v.getContext(), false, null);
                        })
                        .setNegativeButton(R.string.action_cancel, null)
                        .show();
            });
        }

        public void bind() {
            int count = Math.min(4, mServerLogEntries.size());
            long total = 0;
            if (count > 0) {
                float[] values = new float[count];
                int[] colors = new int[count];
                colors[0] = ContextCompat.getColor(mChart.getContext(), R.color.storageSettingsChartFirst);
                if (count > 1)
                    colors[1] = ContextCompat.getColor(mChart.getContext(), R.color.storageSettingsChartSecond);
                if (count > 2)
                    colors[2] = ContextCompat.getColor(mChart.getContext(), R.color.storageSettingsChartThird);
                if (count > 3)
                    colors[3] = ContextCompat.getColor(mChart.getContext(), R.color.storageSettingsChartOthers);
                for (int i = mServerLogEntries.size() - 1; i >= 0; --i) {
                    long val = mServerLogEntries.get(i).size;
                    total += val;
                    values[Math.min(i, count - 1)] += (float) (val / 1024.0 / 1024.0);
                }
                mChart.setData(values, colors);
                mChart.setVisibility(View.VISIBLE);
                itemView.setPadding(itemView.getPaddingLeft(), itemView.getPaddingTop(), itemView.getPaddingRight(), itemView.getResources().getDimensionPixelSize(R.dimen.storage_chat_logs_summary_padding_bottom));
            } else {
                mChart.setVisibility(View.GONE);
                itemView.setPadding(itemView.getPaddingLeft(), itemView.getPaddingTop(), itemView.getPaddingRight(), itemView.getResources().getDimensionPixelSize(R.dimen.storage_chat_logs_summary_padding_bottom_no_items));
            }
            mTotal.setText(formatFileSize(total));
        }

    }

    private static class ServerLogsEntry {

        String name;
        UUID uuid;
        long size;

        public ServerLogsEntry(String name, UUID uuid, long size) {
            this.name = name;
            this.uuid = uuid;
            this.size = size;
        }

    }

    public class ServerLogsHolder extends RecyclerView.ViewHolder {

        private TextView mText;

        public ServerLogsHolder(View view) {
            super(view);
            mText = view.findViewById(R.id.text);
            view.setOnClickListener((View v) -> showActionsMenu());
            view.setOnLongClickListener((View v) -> {
                showActionsMenu();
                return true;
            });
        }

        private void showActionsMenu() {
            MenuBottomSheetDialog menu = new MenuBottomSheetDialog(itemView.getContext());
            if (mText.getTag() != null) {
                ServerConfigData server = ServerConfigManager.getInstance(itemView.getContext()).findServer((UUID) mText.getTag());
                if (server != null && server.storageLimit == 0L) {
                    menu.addItem(R.string.pref_storage_set_server_limit, R.drawable.ic_storage, (MenuBottomSheetDialog.Item it) -> {
                        ServerStorageLimitDialog dialog = new ServerStorageLimitDialog(itemView.getContext(), server);
                        dialog.show();
                        return true;
                    });
                } else if (server != null) {
                    ColoredTextBuilder builder = new ColoredTextBuilder();
                    builder.append(mText.getContext().getString(R.string.pref_storage_change_server_limit));
                    builder.setSpan(new ForegroundColorSpan(mSecondaryTextColor));
                    builder.append(" (");
                    if (server.storageLimit == -1L)
                        builder.append(itemView.getContext().getString(R.string.pref_storage_no_limit));
                    else
                        builder.append((server.storageLimit / 1024L / 1024L) + " MB");
                    builder.append(")");
                    menu.addItem(builder.getSpannable(), R.drawable.ic_storage, (MenuBottomSheetDialog.Item it) -> {
                        ServerStorageLimitDialog dialog = new ServerStorageLimitDialog(itemView.getContext(), server);
                        dialog.show();
                        return true;
                    });
                    menu.addItem(R.string.pref_storage_remove_server_limit, 0, (MenuBottomSheetDialog.Item it) -> {
                        server.storageLimit = 0L;
                        try {
                            ServerConfigManager.getInstance(itemView.getContext()).saveServer(server);
                        } catch (IOException e) {
                            Log.e("StorageSettingsAdapter", "Failed to save server storage limit", e);
                        }
                        return true;
                    });
                }
            }
            menu.addItem(R.string.pref_storage_clear_server_chat_logs, R.drawable.ic_delete, (MenuBottomSheetDialog.Item it) -> {
                new AlertDialog.Builder(itemView.getContext())
                        .setTitle(R.string.pref_storage_clear_server_chat_logs)
                        .setMessage(R.string.pref_storage_clear_all_chat_logs_confirm)
                        .setPositiveButton(R.string.action_delete, (DialogInterface di, int i) -> {
                            executeRemoveTask(itemView.getContext(), false, (UUID) mText.getTag());
                        })
                        .setNegativeButton(R.string.action_cancel, null)
                        .show();
                return true;
            });
            menu.show();
        }

        public void bind(ServerLogsEntry entry, int pos) {
            int colorId = R.color.storageSettingsChartOthers;
            if (entry.size > 0L) {
                if (pos == 0)
                    colorId = R.color.storageSettingsChartFirst;
                else if (pos == 1)
                    colorId = R.color.storageSettingsChartSecond;
                else if (pos == 2)
                    colorId = R.color.storageSettingsChartThird;
            }
            ColoredTextBuilder builder = new ColoredTextBuilder();
            builder.append(entry.name, new ForegroundColorSpan(ContextCompat.getColor(mText.getContext(), colorId)));
            builder.append("  ");
            builder.append(formatFileSize(entry.size));
            mText.setText(builder.getSpannable());
            mText.setTag(entry.uuid);
        }

    }

    public class AppDataSummaryHolder extends RecyclerView.ViewHolder {

        private TextView mTotal;

        public AppDataSummaryHolder(View view) {
            super(view);
            mTotal = view.findViewById(R.id.total_value);
            view.findViewById(R.id.clear_app_data).setOnClickListener((View v) -> {
                new AlertDialog.Builder(v.getContext())
                        .setTitle(R.string.pref_storage_clear_app_data)
                        .setMessage(R.string.pref_storage_clear_app_data_confirm)
                        .setPositiveButton(R.string.action_delete, (DialogInterface di, int i) -> {
                            executeRemoveTask(v.getContext(), true, null);
                        })
                        .setNegativeButton(R.string.action_cancel, null)
                        .show();
            });
        }

        public void bind() {
            mTotal.setText(formatFileSize(mAppDataSize));
        }

    }

    public class AppCacheSummaryHolder extends RecyclerView.ViewHolder {

        private TextView mTotal;

        public AppCacheSummaryHolder(View view) {
            super(view);
            mTotal = view.findViewById(R.id.total_value);
            view.findViewById(R.id.clear_app_cache).setOnClickListener((View v) -> {
                new AlertDialog.Builder(v.getContext())
                        .setTitle(R.string.pref_storage_clear_app_cache)
                        .setMessage(R.string.pref_storage_clear_app_cache_confirm)
                        .setPositiveButton(R.string.action_delete, (DialogInterface di, int i) -> {
                            File cacheDir = v.getContext().getCacheDir();
                            if (cacheDir != null)
                                io.mrarm.irc.util.FileUtils.deleteRecursive(cacheDir);
                            refreshServerLogs(v.getContext().getApplicationContext());
                        })
                        .setNegativeButton(R.string.action_cancel, null)
                        .show();
            });
        }

        public void bind() {
            mTotal.setText(formatFileSize(mCacheSize));
        }
    }

    private void executeRemoveTask(Context context, boolean deleteConfig, UUID deleteOnlyServerLogs) {
        if (mDestroyed) return;
        synchronized (this) {
            if (mCurrentRemoveTask != null)
                return;
            mCurrentRemoveTask = new RemoveDataTask(context, deleteConfig, deleteOnlyServerLogs, this);
            mExecutor.execute(mCurrentRemoveTask);
        }
    }

    private class SpaceCalculateTask implements Runnable {

        private final WeakReference<StorageSettingsAdapter> mAdapterRef;
        private final ServerConfigManager mServerManager;
        private final File mDataDir;
        private final File mCacheDir;

        public SpaceCalculateTask(Context context, StorageSettingsAdapter adapter) {
            mServerManager = ServerConfigManager.getInstance(context);
            mDataDir = new File(context.getApplicationInfo().dataDir);
            mCacheDir = context.getCacheDir();
            mAdapterRef = new WeakReference<>(adapter);
        }

        @Override
        public void run() {
            final long dataBlockSize = getBlockSize(mDataDir);
            final long[] dataSize = {0L};
            File[] dataFiles = mDataDir.listFiles();
            if (dataFiles != null) {
                for (File file : dataFiles) {
                    if (file.getName().equals("cache") || file.getName().equals("lib"))
                        continue;
                    dataSize[0] += calculateDirectorySize(file, dataBlockSize);
                }
            }
            final long cacheSize = calculateDirectorySize(mCacheDir, dataBlockSize);

            // Post results to UI thread
            mMainHandler.post(() -> {
                StorageSettingsAdapter adapter = mAdapterRef.get();
                if (adapter != null) {
                    adapter.mAppDataSize = dataSize[0];
                    adapter.mCacheSize = cacheSize;
                    adapter.notifyDataSetChanged();
                }
            });

            List<File> processedDirs = new ArrayList<>();
            for (ServerConfigData data : mServerManager.getServers()) {
                StorageSettingsAdapter adapter = mAdapterRef.get();
                if (adapter == null) {
                    mMainHandler.post(() -> {
                        StorageSettingsAdapter a = mAdapterRef.get();
                        if (a != null) {
                            a.mCurrentSpaceTask = null;
                        }
                    });
                    return;
                }
                File file = mServerManager.getServerChatLogDir(data.uuid);
                processedDirs.add(file);
                if (!file.exists())
                    continue;
                long size = calculateChatLogDirectorySize(file, getBlockSize(file));
                if (size == 0L)
                    continue;
                final ServerLogsEntry entry = new ServerLogsEntry(data.name, data.uuid, size);

                mMainHandler.post(() -> {
                    StorageSettingsAdapter a = mAdapterRef.get();
                    if (a != null) {
                        a.addEntry(entry);
                    }
                });
            }
            File chatLogDir = mServerManager.getChatLogDir();
            if (chatLogDir != null && chatLogDir.exists()) {
                File[] files = chatLogDir.listFiles();
                if (files != null) {
                    for (File file : files) {
                        if (processedDirs.contains(file))
                            continue;
                        long size = calculateChatLogDirectorySize(file, getBlockSize(file));
                        if (size == 0L)
                            continue;
                        UUID fileUuid = null;
                        try {
                            fileUuid = UUID.fromString(file.getName());
                        } catch (IllegalArgumentException ignored) {
                        }
                        final UUID finalFileUuid = fileUuid;
                        final ServerLogsEntry entry = new ServerLogsEntry(file.getName(), finalFileUuid, size);

                        mMainHandler.post(() -> {
                            StorageSettingsAdapter a = mAdapterRef.get();
                            if (a != null) {
                                a.addEntry(entry);
                            }
                        });
                    }
                }
            }

            // Clear current task reference
            mMainHandler.post(() -> {
                StorageSettingsAdapter adapter = mAdapterRef.get();
                if (adapter != null) {
                    adapter.mCurrentSpaceTask = null;
                }
            });
        }

        private long getBlockSize(File file) {
            if (file == null || !file.exists())
                return 4096L;
            try {
                StatFs statFs = new StatFs(file.getAbsolutePath());
                return statFs.getBlockSizeLong();
            } catch (Exception e) {
                return 4096L;
            }
        }

        private long calculateDirectorySize(File file, long blockSize) {
            if (file == null || !file.exists())
                return 0L;
            File[] files = file.listFiles();
            if (files == null)
                return blockSize;
            long ret = blockSize;
            for (File subfile : files) {
                if (subfile.isDirectory())
                    ret += calculateDirectorySize(subfile, blockSize);
                else
                    ret += (subfile.length() + blockSize - 1) / blockSize * blockSize;
            }
            return ret;
        }

        private long calculateChatLogDirectorySize(File file, long blockSize) {
            if (file == null || !file.exists())
                return 0L;
            if (!file.isDirectory()) {
                if (file.getName().startsWith("messages-"))
                    return (file.length() + blockSize - 1) / blockSize * blockSize;
                return 0L;
            }
            File[] files = file.listFiles();
            if (files == null)
                return 0L;
            long ret = 0L;
            for (File subfile : files) {
                if (subfile.isDirectory()) {
                    ret += calculateChatLogDirectorySize(subfile, blockSize);
                } else if (subfile.getName().startsWith("messages-")) {
                    ret += (subfile.length() + blockSize - 1) / blockSize * blockSize;
                }
            }
            return ret;
        }
    }

    private class RemoveDataTask implements Runnable {

        private final Context mContext;
        private final AlertDialog mAlertDialog;
        private final boolean mDeleteConfig;
        private final UUID mDeleteServerLogs;
        private final WeakReference<StorageSettingsAdapter> mAdapterRef;

        public RemoveDataTask(Context context, boolean deleteConfig, UUID deleteOnlyServerLogs,
                              StorageSettingsAdapter adapter) {
            mContext = context;
            mDeleteConfig = deleteConfig;
            mDeleteServerLogs = deleteOnlyServerLogs;
            mAdapterRef = new WeakReference<>(adapter);
            mAlertDialog = new AlertDialog.Builder(context)
                    .setCancelable(false)
                    .setView(R.layout.dialog_please_wait)
                    .create();
        }

        @Override
        public void run() {
            Context ctx = mContext;
            mMainHandler.post(() -> {
                try {
                    mAlertDialog.show();
                } catch (Exception ignored) {
                }
            });
            try {
                if (mDeleteConfig) {
                    ServerConnectionManager mgr = ServerConnectionManager.getInstance(ctx);
                    if (mgr != null)
                        mgr.disconnectAndRemoveAllConnections(true);
                    else
                        new File(ctx.getFilesDir(),
                                ServerConnectionManager.CONNECTED_SERVERS_FILE_PATH).delete();
                    ServerConfigManager.getInstance(ctx).deleteAllServers(true);
                    NotificationRuleManager.getUserRules(ctx).clear();
                    CommandAliasManager.getInstance(ctx).getUserAliases().clear();
                    SettingsHelper.getInstance(ctx).clear();
                    NotificationCountStorage.getInstance(ctx).close();

                    File files = ctx.getFilesDir();
                    List<File> filesList = io.mrarm.irc.util.FileUtils.listFiles(files);
                    for (File file : filesList) {
                        if (file.getName().equals("cache") || file.getName().equals("lib"))
                            continue;
                        io.mrarm.irc.util.FileUtils.deleteRecursive(file);
                    }

                    NotificationCountStorage.getInstance(ctx).open();
                }
                if (mDeleteServerLogs != null) {
                    deleteChatLogDir(mDeleteServerLogs);
                } else {
                    java.util.Set<UUID> uuidsToDelete = new java.util.HashSet<>();
                    File chatLogDir = ServerConfigManager.getInstance(mContext).getChatLogDir();
                    File[] logFiles = chatLogDir != null ? chatLogDir.listFiles() : null;
                    if (logFiles != null) {
                        for (File file : logFiles) {
                            try {
                                uuidsToDelete.add(UUID.fromString(file.getName()));
                            } catch (IllegalArgumentException ignored) {
                                deleteChatLogsInDirectory(file);
                            }
                        }
                    }
                    ServerConnectionManager connectionManager = ServerConnectionManager.getInstance(mContext);
                    if (connectionManager != null) {
                        for (ServerConnectionInfo conn : connectionManager.getConnections()) {
                            uuidsToDelete.add(conn.getUUID());
                        }
                    }
                    for (UUID uuid : uuidsToDelete) {
                        try {
                            deleteChatLogDir(uuid);
                        } catch (Exception e) {
                            Log.e("StorageSettingsAdapter", "Failed to delete chat log dir for uuid: " + uuid, e);
                        }
                    }
                }
            } catch (Exception e) {
                Log.e("StorageSettingsAdapter", "Failed in RemoveDataTask", e);
            }

            // Dismiss dialog and refresh on UI thread
            mMainHandler.post(() -> {
                try {
                    mAlertDialog.dismiss();
                } catch (Exception ignored) {
                }
                StorageSettingsAdapter adapter = mAdapterRef.get();
                if (adapter != null) {
                    adapter.mCurrentRemoveTask = null;
                    adapter.refreshServerLogs(mContext.getApplicationContext());
                }
            });
        }

        private void deleteChatLogDir(UUID uuid) {
            try {
                ServerConnectionManager connectionManager = ServerConnectionManager.getInstance(mContext);
                if (connectionManager != null)
                    connectionManager.killDisconnectingConnection(uuid);
                ServerConnectionInfo connection = connectionManager != null ? connectionManager.getConnection(uuid) : null;

                SQLiteMessageStorageApi oldStorageApi = null;
                if (connection != null) {
                    try {
                        synchronized (connection.getLock()) {
                            Object api = connection.getApiInstance();
                            if (api instanceof ServerConnectionApi) {
                                ServerConnectionData connectionData = ((ServerConnectionApi) api).getServerConnectionData();
                                if (connectionData.getMessageStorageApi() instanceof SQLiteMessageStorageApi) {
                                    oldStorageApi = (SQLiteMessageStorageApi) connectionData.getMessageStorageApi();
                                    connectionData.setMessageStorageApi(new StubMessageStorageApi());
                                }
                            }
                        }
                    } catch (Exception e) {
                        Log.e("StorageSettingsAdapter", "Error stubbing storage API", e);
                    }
                }

                if (oldStorageApi != null) {
                    try {
                        oldStorageApi.close();
                    } catch (Exception e) {
                        Log.e("StorageSettingsAdapter", "Error closing old storage API for server " + uuid, e);
                    }
                }

                File file = ServerConfigManager.getInstance(mContext).getServerChatLogDir(uuid);
                deleteChatLogsInDirectory(file);

                if (connection != null) {
                    try {
                        synchronized (connection.getLock()) {
                            Object api = connection.getApiInstance();
                            if (api instanceof ServerConnectionApi) {
                                file.mkdirs();
                                SQLiteMessageStorageApi newStorageApi = new SQLiteMessageStorageApi(file);
                                try {
                                    newStorageApi.open();
                                } catch (Exception e) {
                                    Log.e("StorageSettingsAdapter", "Failed to reopen storage API for server " + uuid, e);
                                }
                                ServerConnectionData connectionData = ((ServerConnectionApi) api).getServerConnectionData();
                                connectionData.setMessageStorageApi(newStorageApi);
                            }
                        }
                        IRCService.onStorageApiChanged(connection);
                    } catch (Exception e) {
                        Log.e("StorageSettingsAdapter", "Error restoring new storage API", e);
                    }
                }
                ChatLogStorageManager.getInstance(mContext).requestUpdate(uuid);
            } catch (Exception e) {
                Log.e("StorageSettingsAdapter", "Error in deleteChatLogDir for uuid " + uuid, e);
            }
        }

        private void deleteChatLogsInDirectory(File dir) {
            if (dir == null || !dir.exists())
                return;
            if (dir.isDirectory()) {
                List<File> files = io.mrarm.irc.util.FileUtils.listFiles(dir);
                for (File file : files) {
                    if (file.isDirectory()) {
                        deleteChatLogsInDirectory(file);
                    } else if (file.getName().startsWith("messages-")) {
                        if (file.getName().endsWith(".db")) {
                            SettingsHelper.deleteSQLiteDatabase(file);
                        } else if (file.exists()) {
                            file.delete();
                        }
                    }
                }
            } else if (dir.getName().startsWith("messages-")) {
                if (dir.getName().endsWith(".db")) {
                    SettingsHelper.deleteSQLiteDatabase(dir);
                } else if (dir.exists()) {
                    dir.delete();
                }
            }
        }
    }

    public static void clearAllAppData(Context context) {
        final ExecutorService executor = new PoolSerialExecutor();
        executor.execute(() -> {
            Context ctx = context;
            ServerConnectionManager mgr = ServerConnectionManager.getInstance(ctx);
            if (mgr != null)
                mgr.disconnectAndRemoveAllConnections(true);
            else
                new File(ctx.getFilesDir(),
                        ServerConnectionManager.CONNECTED_SERVERS_FILE_PATH).delete();
            ServerConfigManager.getInstance(ctx).deleteAllServers(true);
            NotificationRuleManager.getUserRules(ctx).clear();
            CommandAliasManager.getInstance(ctx).getUserAliases().clear();
            SettingsHelper.getInstance(ctx).clear();
            NotificationCountStorage.getInstance(ctx).close();

            File files = ctx.getFilesDir();
            List<File> filesList = io.mrarm.irc.util.FileUtils.listFiles(files);
            for (File file : filesList) {
                if (file.getName().equals("cache") || file.getName().equals("lib"))
                    continue;
                io.mrarm.irc.util.FileUtils.deleteRecursive(file);
            }

            NotificationCountStorage.getInstance(ctx).open();
        });
        executor.shutdown();
        try {
            if (!executor.awaitTermination(30, java.util.concurrent.TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private static String formatFileSize(long size) {
        if (size / 1024L >= 128)
            return String.format(java.util.Locale.getDefault(), "%.2f MB", size / 1024.0 / 1024.0);
        else
            return String.format(java.util.Locale.getDefault(), "%.2f KB", size / 1024.0);
    }
}