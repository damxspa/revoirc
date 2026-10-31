package io.mrarm.irc.config;

import android.content.Context;
import android.util.Log;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import io.mrarm.irc.ServerConnectionManager;

public class ServerConfigManager {

    private static ServerConfigManager mInstance;

    private static final String TAG = "ServerConfigManager";

    // Use centralized constants from BackupPaths
    private static final String SERVERS_PATH = BackupPaths.SERVERS_PATH;
    private static final String SERVER_FILE_PREFIX = BackupPaths.SERVER_FILE_PREFIX;
    private static final String SERVER_FILE_SUFFIX = BackupPaths.SERVER_FILE_SUFFIX;
    private static final String SERVER_CERTS_FILE_PREFIX = BackupPaths.SERVER_CERTS_FILE_PREFIX;
    private static final String SERVER_CERTS_FILE_SUFFIX = BackupPaths.SERVER_CERTS_FILE_SUFFIX;
    private static final String SERVER_LOGS_PATH = BackupPaths.SERVER_LOGS_PATH;
    private static final String SERVER_MISC_DATA_FILENAME = BackupPaths.SERVER_MISC_DATA_FILENAME;

    public static ServerConfigManager getInstance(Context context) {
        if (mInstance == null)
            mInstance = new ServerConfigManager(context.getApplicationContext());
        return mInstance;
    }

    private final File mServersPath;
    private final File mServerLogsPath;
    private final File mFallbackServerLogsPath;

    private final Context mContext;
    private final List<ServerConfigData> mServers = new ArrayList<>();
    private final Map<UUID, ServerConfigData> mServersMap = new HashMap<>();
    private final List<ConnectionsListener> mListeners = new ArrayList<>();
    private final Object mIOLock = new Object();

    public ServerConfigManager(Context context) {
        mContext = context;
        mServersPath = new File(context.getFilesDir(), SERVERS_PATH);
        File externalFilesDir = context.getExternalFilesDir(null);
        mFallbackServerLogsPath = new File(context.getFilesDir(), SERVER_LOGS_PATH);
        mServerLogsPath = externalFilesDir != null ? new File(externalFilesDir, SERVER_LOGS_PATH)
                : mFallbackServerLogsPath;
        if (!mServerLogsPath.exists() && mServerLogsPath.mkdirs()) {
            Log.d(TAG, "Created server logs path: " + mServerLogsPath.getAbsolutePath());
        }

        if (externalFilesDir != null && mFallbackServerLogsPath.exists()) {
            try {
                migrateServerLogs(mFallbackServerLogsPath, mServerLogsPath);
                if (!mFallbackServerLogsPath.delete()) {
                    Log.w(TAG, "Failed to delete fallback server logs path: " + mFallbackServerLogsPath.getAbsolutePath());
                }
            } catch (IOException e) {
                Log.e(TAG, "Failed to migrate server logs", e);
            }
        }

        loadServers();
    }

    /**
     * Loads all server configurations from disk.
     * Returns a result object containing loaded servers and any errors encountered.
     */
    public static class LoadResult {
        public final List<ServerConfigData> servers = new ArrayList<>();
        public final List<LoadError> errors = new ArrayList<>();

        public boolean hasErrors() {
            return !errors.isEmpty();
        }

        public boolean isEmpty() {
            return servers.isEmpty() && errors.isEmpty();
        }
    }

    public static class LoadError {
        public final String fileName;
        public final String message;
        public final Exception exception;

        public LoadError(String fileName, String message, Exception exception) {
            this.fileName = fileName;
            this.message = message;
            this.exception = exception;
        }
    }

    /**
     * Loads servers and returns a result with both loaded servers and any errors.
     * Does not modify internal state - use loadServers() for that.
     */
    public static LoadResult loadServersFromDisk(File serversPath) {
        LoadResult result = new LoadResult();
        File[] files = serversPath.listFiles();
        if (files == null) {
            result.errors.add(new LoadError("directory", "Failed to list server directory (not a directory or I/O error)", null));
            return result;
        }
        for (File f : files) {
            if (!f.isFile() || !f.getName().startsWith(SERVER_FILE_PREFIX) || !f.getName().endsWith(SERVER_FILE_SUFFIX))
                continue;
            try (BufferedReader reader = new BufferedReader(new FileReader(f))) {
                ServerConfigData data = SettingsHelper.getGson().fromJson(reader, ServerConfigData.class);
                if (data == null) {
                    result.errors.add(new LoadError(f.getName(), "Server config parsed as null", null));
                } else if (data.uuid == null) {
                    result.errors.add(new LoadError(f.getName(), "Server config missing UUID", null));
                } else {
                    data.migrateLegacyProperties();
                    result.servers.add(data);
                }
            } catch (IOException e) {
                result.errors.add(new LoadError(f.getName(), "I/O error reading server config", e));
            } catch (Exception e) {
                result.errors.add(new LoadError(f.getName(), "Failed to parse server config: " + e.getMessage(), e));
            }
        }
        return result;
    }

    // NOTE: This is not synchronized; don't call it outside of the constructor
    private void loadServers() {
        LoadResult result = loadServersFromDisk(mServersPath);
        for (ServerConfigData data : result.servers) {
            mServers.add(data);
            mServersMap.put(data.uuid, data);
        }
        for (LoadError error : result.errors) {
            Log.e(TAG, "Failed to load server: " + error.fileName + " - " + error.message, error.exception);
        }
        if (result.hasErrors()) {
            Log.w(TAG, "Loaded " + result.servers.size() + " servers with " + result.errors.size() + " errors");
        }
    }

    /**
     * Atomically migrates server logs from one directory to another.
     * Uses a temporary directory to ensure atomicity - if migration fails,
     * the original files are left untouched.
     * 
     * @param from Source directory
     * @param to Destination directory
     * @throws IOException if migration fails
     */
    private void migrateServerLogs(File from, File to) throws IOException {
        File[] files = from.listFiles();
        if (files == null) {
            Log.w(TAG, "Failed to list source directory for migration: " + from.getAbsolutePath());
            return;
        }
        
        // Create a temporary migration directory
        File tempMigrationDir = new File(to.getParentFile(), to.getName() + "_migrating_" + System.currentTimeMillis());
        if (!tempMigrationDir.mkdirs()) {
            throw new IOException("Failed to create temporary migration directory: " + tempMigrationDir.getAbsolutePath());
        }
        
        boolean migrationFailed = false;
        IOException migrationException = null;
        
        try {
            // Migrate all files to temp directory
            for (File file : files) {
                if (file.isDirectory()) {
                    migrateDirectoryRecursive(file, new File(tempMigrationDir, file.getName()));
                } else {
                    copyFileToDirectory(file, tempMigrationDir);
                }
            }
            
            // If we get here, all files copied successfully
            // Now move temp directory to target (atomic rename)
            if (!to.exists() && !to.mkdirs()) {
                throw new IOException("Failed to create target directory: " + to.getAbsolutePath());
            }
            
            // Move files from temp to target
            File[] tempFiles = tempMigrationDir.listFiles();
            if (tempFiles != null) {
                for (File tempFile : tempFiles) {
                    File destFile = new File(to, tempFile.getName());
                    if (destFile.exists() && !destFile.delete()) {
                        throw new IOException("Failed to delete existing file in target: " + destFile.getAbsolutePath());
                    }
                    if (!tempFile.renameTo(destFile)) {
                        throw new IOException("Failed to move migrated file: " + tempFile.getAbsolutePath() + " -> " + destFile.getAbsolutePath());
                    }
                }
            }
            
        } catch (IOException e) {
            migrationFailed = true;
            migrationException = e;
            Log.e(TAG, "Server log migration failed, rolling back", e);
        } finally {
            // Cleanup temp directory
            if (tempMigrationDir.exists()) {
                deleteRecursive(tempMigrationDir);
            }
        }
        
        if (migrationFailed) {
            throw migrationException;
        }
        
        // Migration successful - delete source files
        for (File file : files) {
            if (!deleteRecursive(file)) {
                Log.w(TAG, "Failed to delete source file after successful migration: " + file.getAbsolutePath());
            }
        }
        
        // Try to delete source directory
        if (from.exists() && !from.delete()) {
            Log.w(TAG, "Failed to delete source directory after migration: " + from.getAbsolutePath());
        }
        
        Log.d(TAG, "Server log migration completed successfully: " + from.getAbsolutePath() + " -> " + to.getAbsolutePath());
    }
    
    /**
     * Recursively migrates a directory to a target directory.
     */
    private void migrateDirectoryRecursive(File sourceDir, File targetDir) throws IOException {
        if (!targetDir.mkdirs()) {
            throw new IOException("Failed to create target directory: " + targetDir.getAbsolutePath());
        }
        
        File[] files = sourceDir.listFiles();
        if (files == null) {
            throw new IOException("Failed to list source directory: " + sourceDir.getAbsolutePath());
        }
        
        for (File file : files) {
            if (file.isDirectory()) {
                migrateDirectoryRecursive(file, new File(targetDir, file.getName()));
            } else {
                copyFileToDirectory(file, targetDir);
            }
        }
    }
    
    /**
     * Copies a file to a target directory.
     */
    private void copyFileToDirectory(File sourceFile, File targetDir) throws IOException {
        File targetFile = new File(targetDir, sourceFile.getName());
        File tempFile = new File(targetDir, sourceFile.getName() + ".tmp");
        
        try (FileInputStream fis = new FileInputStream(sourceFile);
             FileOutputStream fos = new FileOutputStream(tempFile)) {
            byte[] buf = new byte[16 * 1024];
            int n;
            while ((n = fis.read(buf)) > 0) {
                fos.write(buf, 0, n);
            }
        }
        
        if (!tempFile.renameTo(targetFile)) {
            throw new IOException("Failed to rename temp file: " + tempFile.getAbsolutePath() + " -> " + targetFile.getAbsolutePath());
        }
    }
    
    /**
     * Recursively deletes a file or directory.
     * @return true if deletion succeeded, false otherwise
     */
    private boolean deleteRecursive(File file) {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursive(child);
                }
            }
        }
        return file.delete();
    }

    public List<ServerConfigData> getServers() {
        synchronized (this) {
            return new ArrayList<>(mServers);
        }
    }

    public ServerConfigData findServer(UUID uuid) {
        synchronized (this) {
            return mServersMap.get(uuid);
        }
    }

    public void saveServer(ServerConfigData data) throws IOException {
        boolean existed = false;
        synchronized (this) {
            if (mServersMap.containsKey(data.uuid)) {
                existed = true;
                mServers.remove(mServersMap.get(data.uuid));
            }
            mServers.add(data);
            mServersMap.put(data.uuid, data);
        }
        synchronized (mIOLock) {
            if (!mServersPath.exists() && !mServersPath.mkdirs()) {
                Log.w(TAG, "Failed to create servers path: " + mServersPath.getAbsolutePath());
            }
            File serverFile = new File(mServersPath, SERVER_FILE_PREFIX + data.uuid.toString() + SERVER_FILE_SUFFIX);
            try (BufferedWriter writer = new BufferedWriter(new FileWriter(serverFile))) {
                SettingsHelper.getGson().toJson(data, writer);
            }
        }
        synchronized (mListeners) {
            if (existed) {
                for (ConnectionsListener listener : mListeners)
                    listener.onConnectionUpdated(data);
            } else {
                for (ConnectionsListener listener : mListeners)
                    listener.onConnectionAdded(data);
            }
        }
    }

    public void deleteServer(ServerConfigData data, boolean deleteLogs) {
        ServerConnectionManager.getInstance(mContext).killDisconnectingConnection(data.uuid);
        synchronized (this) {
            mServers.remove(data);
            mServersMap.remove(data.uuid);
        }
        synchronized (mIOLock) {
            File file = new File(mServersPath, SERVER_FILE_PREFIX + data.uuid.toString() + SERVER_FILE_SUFFIX);
            if (!file.delete()) {
                Log.w(TAG, "Failed to delete server file: " + file.getAbsolutePath());
            }
            file = getServerSSLCertsFile(data.uuid);
            if (file.exists() && !file.delete()) {
                Log.w(TAG, "Failed to delete server certs file: " + file.getAbsolutePath());
            }
            if (deleteLogs) {
                file = getServerChatLogDir(data.uuid);
                if (file.exists()) {
                    File[] files = file.listFiles();
                    if (files != null) {
                        for (File f : files) {
                            if (!f.delete()) {
                                Log.w(TAG, "Failed to delete log file: " + f.getAbsolutePath());
                            }
                        }
                    }
                    if (!file.delete()) {
                        Log.w(TAG, "Failed to delete server logs directory: " + file.getAbsolutePath());
                    }
                }
            }
        }
        synchronized (mListeners) {
            for (ConnectionsListener listener : mListeners)
                listener.onConnectionRemoved(data);
        }
        NotificationCountStorage.getInstance(mContext).requestRemoveServerCounters(data.uuid);
    }

    public void deleteServer(ServerConfigData data) {
        deleteServer(data, true);
    }

    public void deleteAllServers(boolean deleteLogs) {
        synchronized (this) {
            while (!mServers.isEmpty())
                deleteServer(mServers.get(mServers.size() - 1), deleteLogs);
        }
    }

    public File getServerSSLCertsFile(UUID uuid) {
        return new File(mServersPath, SERVER_CERTS_FILE_PREFIX + uuid.toString() + SERVER_CERTS_FILE_SUFFIX);
    }

    public File getChatLogDir() {
        return mServerLogsPath;
    }

    public File getServerChatLogDir(UUID uuid) {
        return new File(mServerLogsPath, uuid.toString());
    }

    public File getServerMiscDataFile(UUID uuid) {
        return new File(getServerChatLogDir(uuid), SERVER_MISC_DATA_FILENAME);
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

    public interface ConnectionsListener {

        void onConnectionAdded(ServerConfigData data);

        void onConnectionRemoved(ServerConfigData data);

        void onConnectionUpdated(ServerConfigData data);

    }

}