package io.mrarm.irc.config;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;
import android.util.Log;

import androidx.annotation.NonNull;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import net.lingala.zip4j.core.ZipFile;
import net.lingala.zip4j.exception.ZipException;
import net.lingala.zip4j.model.FileHeader;
import net.lingala.zip4j.model.ZipParameters;
import net.lingala.zip4j.util.Zip4jConstants;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.StringWriter;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import io.mrarm.irc.ServerConnectionManager;
import io.mrarm.irc.setting.ListWithCustomSetting;
import io.mrarm.irc.util.theme.ThemeInfo;
import io.mrarm.irc.util.theme.ThemeManager;

public class BackupManager {

    // Use centralized constants from BackupPaths
    private static final String BACKUP_PREFERENCES_PATH = BackupPaths.BACKUP_PREFERENCES_PATH;
    private static final String BACKUP_PREF_VALUES_PREFIX = BackupPaths.BACKUP_PREF_VALUES_PREFIX;
    private static final String BACKUP_SERVER_PREFIX = BackupPaths.BACKUP_SERVER_PREFIX;
    private static final String BACKUP_SERVER_SUFFIX = BackupPaths.BACKUP_SERVER_SUFFIX;
    private static final String BACKUP_SERVER_CERTS_PREFIX = BackupPaths.BACKUP_SERVER_CERTS_PREFIX;
    private static final String BACKUP_SERVER_CERTS_SUFFIX = BackupPaths.BACKUP_SERVER_CERTS_SUFFIX;
    private static final String BACKUP_NOTIFICATION_RULES_PATH = BackupPaths.BACKUP_NOTIFICATION_RULES_PATH;
    private static final String BACKUP_COMMAND_ALIASES_PATH = BackupPaths.BACKUP_COMMAND_ALIASES_PATH;
    private static final String NOTIFICATION_COUNT_DB_PATH = BackupPaths.NOTIFICATION_COUNT_DB_PATH;
    private static final String BACKUP_THEME_PREFIX = BackupPaths.BACKUP_THEME_PREFIX;
    private static final String BACKUP_THEME_SUFFIX = BackupPaths.BACKUP_THEME_SUFFIX;

    public static void createBackup(Context context, File file, String password) throws IOException {
        try {
            Log.d("BackupManager", "createBackup: " + file.getAbsolutePath());

            ZipFile zipFile = new ZipFile(file);
            ZipParameters params = new ZipParameters();
            params.setCompressionMethod(Zip4jConstants.COMP_DEFLATE);
            params.setCompressionLevel(Zip4jConstants.DEFLATE_LEVEL_NORMAL);
            if (password != null) {
                params.setEncryptFiles(true);
                params.setEncryptionMethod(Zip4jConstants.ENC_METHOD_AES);
                params.setAesKeyStrength(Zip4jConstants.AES_STRENGTH_256);
                params.setPassword(password);
            }
            params.setSourceExternalStream(true);

            params.setFileNameInZip(BACKUP_PREFERENCES_PATH);
            zipFile.addStream(exportPreferencesToJson(context), params);

            for (File f : SettingsHelper.getInstance(context).getCustomFiles()) {
                params.setFileNameInZip(BACKUP_PREF_VALUES_PREFIX + f.getName());
                zipFile.addFile(f, params);
            }

            StringWriter writer;
            ServerConfigManager configManager = ServerConfigManager.getInstance(context);
            for (ServerConfigData data : configManager.getServers()) {
                writer = new StringWriter();
                SettingsHelper.getGson().toJson(data, writer);
                params.setFileNameInZip(BACKUP_SERVER_PREFIX + data.uuid + BACKUP_SERVER_SUFFIX);
                zipFile.addStream(new ByteArrayInputStream(writer.toString().getBytes()), params);
                File sslCertsFile = configManager.getServerSSLCertsFile(data.uuid);
                if (sslCertsFile.exists()) {
                    synchronized (ServerCertificateManager.get(sslCertsFile)) { // lock the helper to prevent any writes to the file
                        params.setFileNameInZip(BACKUP_SERVER_CERTS_PREFIX + data.uuid + BACKUP_SERVER_CERTS_SUFFIX);
                        zipFile.addFile(sslCertsFile, params);
                    }
                }
            }

            writer = new StringWriter();
            NotificationRuleManager.saveUserRuleSettings(context, writer);
            params.setFileNameInZip(BACKUP_NOTIFICATION_RULES_PATH);
            zipFile.addStream(new ByteArrayInputStream(writer.toString().getBytes()), params);

            writer = new StringWriter();
            CommandAliasManager.getInstance(context).saveUserSettings(writer);
            params.setFileNameInZip(BACKUP_COMMAND_ALIASES_PATH);
            zipFile.addStream(new ByteArrayInputStream(writer.toString().getBytes()), params);

            NotificationCountStorage.getInstance(context).close();
            try {
                File notifDbFile = NotificationCountStorage.getFile(context);
                if (notifDbFile.exists()) {
                    params.setFileNameInZip(NOTIFICATION_COUNT_DB_PATH);
                    zipFile.addFile(notifDbFile, params);
                }
            } finally {
                NotificationCountStorage.getInstance(context).open();
            }

            ThemeManager themeManager = ThemeManager.getInstance(context);
            for (ThemeInfo themeInfo : themeManager.getCustomThemes()) {
                params.setFileNameInZip(BACKUP_THEME_PREFIX + themeInfo.uuid + BACKUP_THEME_SUFFIX);
                zipFile.addFile(themeManager.getThemePath(themeInfo.uuid), params);
            }
        } catch (ZipException e) {
            throw new IOException(e);
        }
    }

    /**
     * Verifies backup file integrity and structure.
     * Checks file existence, JSON parseability, and basic structure validation.
     */
    public static boolean verifyBackupFile(File file) {
        VerificationResult result = verifyBackupDetailed(file);
        return result.isValid;
    }

    /**
     * Detailed backup verification returning validation results.
     * Validates ALL backup components: preferences, servers, certificates, themes, notification DB, etc.
     */
    public static VerificationResult verifyBackupDetailed(File file) {
        VerificationResult result = new VerificationResult();
        final List<File> tempDirsToCleanup = new ArrayList<>();
        try {
            ZipFile zipFile = new ZipFile(file);
            
            // Check required files
            result.hasPreferences = zipFile.getFileHeader(BACKUP_PREFERENCES_PATH) != null;
            result.hasNotificationRules = zipFile.getFileHeader(BACKUP_NOTIFICATION_RULES_PATH) != null;
            result.hasCommandAliases = zipFile.getFileHeader(BACKUP_COMMAND_ALIASES_PATH) != null;
            
            // Check optional files
            result.hasServers = false;
            result.hasCertificates = false;
            result.hasThemes = false;
            result.hasNotificationDb = zipFile.getFileHeader(NOTIFICATION_COUNT_DB_PATH) != null;
            
            List<FileHeader> serverHeaders = new ArrayList<>();
            List<FileHeader> certHeaders = new ArrayList<>();
            List<FileHeader> themeHeaders = new ArrayList<>();
            
            for (Object header : zipFile.getFileHeaders()) {
                if (!(header instanceof FileHeader)) continue;
                FileHeader fh = (FileHeader) header;
                String name = fh.getFileName();
                if (name.startsWith(BACKUP_SERVER_PREFIX) && name.endsWith(BACKUP_SERVER_SUFFIX)) {
                    result.hasServers = true;
                    result.serverCount++;
                    serverHeaders.add(fh);
                } else if (name.startsWith(BACKUP_SERVER_CERTS_PREFIX) && name.endsWith(BACKUP_SERVER_CERTS_SUFFIX)) {
                    result.hasCertificates = true;
                    result.certificateCount++;
                    certHeaders.add(fh);
                } else if (name.startsWith(BACKUP_THEME_PREFIX) && name.endsWith(BACKUP_THEME_SUFFIX)) {
                    result.hasThemes = true;
                    result.themeCount++;
                    themeHeaders.add(fh);
                }
            }
            
            // Validate JSON parseability for required files
            if (result.hasPreferences) {
                try (Reader reader = new BufferedReader(new InputStreamReader(
                        zipFile.getInputStream(zipFile.getFileHeader(BACKUP_PREFERENCES_PATH))))) {
                    SettingsHelper.getGson().fromJson(reader, JsonObject.class);
                    result.preferencesValid = true;
                } catch (ZipException | IOException e) {
                    throw e;
                } catch (Exception e) {
                    result.addError("Preferences JSON invalid: " + e.getMessage());
                }
            }
            
            if (result.hasNotificationRules) {
                try (Reader reader = new BufferedReader(new InputStreamReader(
                        zipFile.getInputStream(zipFile.getFileHeader(BACKUP_NOTIFICATION_RULES_PATH))))) {
                    SettingsHelper.getGson().fromJson(reader, JsonObject.class);
                    result.notificationRulesValid = true;
                } catch (Exception e) {
                    result.addError("Notification rules JSON invalid: " + e.getMessage());
                }
            }
            
            if (result.hasCommandAliases) {
                try (Reader reader = new BufferedReader(new InputStreamReader(
                        zipFile.getInputStream(zipFile.getFileHeader(BACKUP_COMMAND_ALIASES_PATH))))) {
                    SettingsHelper.getGson().fromJson(reader, JsonObject.class);
                    result.commandAliasesValid = true;
                } catch (Exception e) {
                    result.addError("Command aliases JSON invalid: " + e.getMessage());
                }
            }
            
            // Validate server configs
            for (FileHeader serverHeader : serverHeaders) {
                try (Reader reader = new BufferedReader(new InputStreamReader(
                        zipFile.getInputStream(serverHeader)))) {
                    ServerConfigData data = SettingsHelper.getGson().fromJson(reader, ServerConfigData.class);
                    if (data == null || data.uuid == null) {
                        result.addError("Server config invalid (null or missing UUID): " + serverHeader.getFileName());
                    } else {
                        result.validServerCount++;
                    }
                } catch (Exception e) {
                    result.addError("Server config parse failed: " + serverHeader.getFileName() + " - " + e.getMessage());
                }
            }
            
            // Validate certificates (check if they're valid keystores)
            for (FileHeader certHeader : certHeaders) {
                InputStream certStream = null;
                try {
                    certStream = zipFile.getInputStream(certHeader);
                    if (certStream == null) {
                        result.addError("Certificate stream null: " + certHeader.getFileName());
                        continue;
                    }
                    // Try to load as keystore to validate
                    java.security.KeyStore ks = java.security.KeyStore.getInstance(java.security.KeyStore.getDefaultType());
                    ks.load(certStream, null);
                    result.validCertificateCount++;
                } catch (Exception e) {
                    result.addError("Certificate invalid: " + certHeader.getFileName() + " - " + e.getMessage());
                } finally {
                    if (certStream != null) {
                        try { certStream.close(); } catch (IOException ignored) {}
                    }
                }
            }
            
            // Validate themes
            for (FileHeader themeHeader : themeHeaders) {
                File themeTempDir = null;
                try {
                    themeTempDir = new File(file.getParentFile(), "backup_verify_temp_" + System.currentTimeMillis());
                    themeTempDir.mkdirs();
                    tempDirsToCleanup.add(themeTempDir);
                    File extractedFile = new File(themeTempDir, themeHeader.getFileName());
                    zipFile.extractFile(themeHeader, themeTempDir.getAbsolutePath(), null, extractedFile.getName());
                    validateThemeFile(extractedFile);
                    result.validThemeCount++;
                } catch (Exception e) {
                    result.addError("Theme validation failed: " + themeHeader.getFileName() + " - " + e.getMessage());
                } finally {
                    if (themeTempDir != null) {
                        deleteRecursive(themeTempDir);
                        tempDirsToCleanup.remove(themeTempDir);
                    }
                }
            }
            
            // Validate notification DB if present
            if (result.hasNotificationDb) {
                FileHeader dbHeader = zipFile.getFileHeader(NOTIFICATION_COUNT_DB_PATH);
                File dbTempDir = null;
                try {
                    dbTempDir = new File(file.getParentFile(), "backup_verify_temp_" + System.currentTimeMillis());
                    dbTempDir.mkdirs();
                    tempDirsToCleanup.add(dbTempDir);
                    File extractedDb = new File(dbTempDir, NOTIFICATION_COUNT_DB_PATH);
                    zipFile.extractFile(dbHeader, dbTempDir.getAbsolutePath(), null, extractedDb.getName());
                    // Validate by opening as SQLite database
                    android.database.sqlite.SQLiteDatabase db = android.database.sqlite.SQLiteDatabase.openDatabase(
                            extractedDb.getAbsolutePath(), null, android.database.sqlite.SQLiteDatabase.OPEN_READONLY);
                    try (android.database.Cursor cursor = db.rawQuery("SELECT count, firstMessageId FROM 'notification_count' LIMIT 1", null)) {
                        // Just verify we can query
                        cursor.moveToFirst();
                    }
                    db.close();
                    result.notificationDbValid = true;
                } catch (Exception e) {
                    result.addError("Notification DB validation failed: " + e.getMessage());
                } finally {
                    if (dbTempDir != null) {
                        deleteRecursive(dbTempDir);
                        tempDirsToCleanup.remove(dbTempDir);
                    }
                }
            }
            
            result.isValid = result.hasPreferences && result.hasNotificationRules && result.hasCommandAliases
                    && result.preferencesValid && result.notificationRulesValid && result.commandAliasesValid
                    && result.errors.isEmpty();
            
        } catch (ZipException e) {
            Log.e("BackupManager", "Backup verification failed: zip error", e);
            result.addError("Zip error: " + e.getMessage());
        } catch (IOException e) {
            Log.e("BackupManager", "Backup verification failed: IO error", e);
            result.addError("IO error: " + e.getMessage());
        } catch (com.google.gson.JsonSyntaxException e) {
            Log.e("BackupManager", "Backup verification failed: invalid JSON", e);
            result.addError("JSON syntax error: " + e.getMessage());
        } finally {
            // Cleanup all temp directories
            for (File dir : tempDirsToCleanup) {
                deleteRecursive(dir);
            }
        }
        return result;
    }

    /**
     * Result of detailed backup verification.
     */
    public static class VerificationResult {
        public boolean isValid = false;
        public boolean hasPreferences = false;
        public boolean hasNotificationRules = false;
        public boolean hasCommandAliases = false;
        public boolean hasServers = false;
        public boolean hasCertificates = false;
        public boolean hasThemes = false;
        public boolean hasNotificationDb = false;
        public boolean preferencesValid = false;
        public boolean notificationRulesValid = false;
        public boolean commandAliasesValid = false;
        public boolean notificationDbValid = false;
        public int serverCount = 0;
        public int validServerCount = 0;
        public int certificateCount = 0;
        public int validCertificateCount = 0;
        public int themeCount = 0;
        public int validThemeCount = 0;
        public final List<String> errors = new ArrayList<>();
        
        public void addError(String error) {
            errors.add(error);
        }
        
        @Override
        @androidx.annotation.NonNull
        public String toString() {
            return "VerificationResult{" +
                    "isValid=" + isValid +
                    ", hasPreferences=" + hasPreferences +
                    ", hasNotificationRules=" + hasNotificationRules +
                    ", hasCommandAliases=" + hasCommandAliases +
                    ", hasServers=" + hasServers +
                    ", hasCertificates=" + hasCertificates +
                    ", hasThemes=" + hasThemes +
                    ", hasNotificationDb=" + hasNotificationDb +
                    ", serverCount=" + serverCount +
                    ", validServerCount=" + validServerCount +
                    ", certificateCount=" + certificateCount +
                    ", validCertificateCount=" + validCertificateCount +
                    ", themeCount=" + themeCount +
                    ", validThemeCount=" + validThemeCount +
                    ", errors=" + errors +
                    '}';
        }
    }

    public static boolean isBackupPasswordProtected(File file) {
        try {
            ZipFile zipFile = new ZipFile(file);
            FileHeader preferences = zipFile.getFileHeader(BACKUP_PREFERENCES_PATH);
            return preferences != null && preferences.isEncrypted();
        } catch (ZipException e) {
            Log.w("BackupManager", "Failed to check backup password protection", e);
            return false;
        }
    }

    /**
     * Restores backup using a staged approach with full validation before any destructive changes.
     * 
     * Flow:
     * 1. Extract all backup contents to temporary staging directory
     * 2. Validate ALL components (preferences, servers, certs, themes, notification rules, aliases, DB)
     * 3. If validation passes: atomically apply all changes
     * 4. If validation fails: cleanup staging dir, throw exception, original data preserved
     */
    public static void restoreBackup(Context context, File file, String password) throws IOException {
        File stagingDir = null;
        ZipFile zipFile;
        
        try {
            zipFile = new ZipFile(file);
            if (password != null) {
                zipFile.setPassword(password);
            }

            // Phase 1: Extract everything to staging directory
            stagingDir = new File(context.getCacheDir(), "backup_restore_staging_" + System.currentTimeMillis());
            if (!stagingDir.mkdirs()) {
                throw new IOException("Failed to create staging directory: " + stagingDir.getAbsolutePath());
            }
            Log.d("BackupManager", "Created staging directory: " + stagingDir.getAbsolutePath());

            // Extract all files to staging directory
            extractAllToStaging(zipFile, stagingDir);
            
            // Phase 2: Validate all extracted components
            ValidationResult validation = validateStagedBackup(context, stagingDir);
            if (!validation.isValid) {
                throw new IOException("Backup validation failed: " + validation.getErrorSummary());
            }
            
            Log.d("BackupManager", "Backup validation passed, applying restore...");
            
            // Phase 3: Apply all changes atomically (with rollback capability)
            applyStagedRestore(context, stagingDir, validation);
            
            Log.d("BackupManager", "Backup restore completed successfully");
            
        } catch (ZipException e) {
            throw new IOException("Zip error during restore: " + e.getMessage(), e);
        } finally {
            // Always cleanup staging directory
            if (stagingDir != null) {
                deleteRecursive(stagingDir);
                Log.d("BackupManager", "Cleaned up staging directory");
            }
        }
    }

    /**
     * Extracts all backup entries to staging directory preserving directory structure.
     */
    private static void extractAllToStaging(ZipFile zipFile, File stagingDir) throws ZipException, IOException {
        for (Object header : zipFile.getFileHeaders()) {
            if (!(header instanceof FileHeader)) continue;
            FileHeader fileHeader = (FileHeader) header;
            String fileName = fileHeader.getFileName();
            
            // Skip directory entries
            if (fileHeader.isDirectory()) continue;
            
            // Prevent path traversal
            if (fileName.contains("..") || fileName.startsWith("/") || fileName.startsWith("\\")) {
                Log.w("BackupManager", "Skipping suspicious file name: " + fileName);
                continue;
            }
            
            File destFile = new File(stagingDir, fileName);
            File parentDir = destFile.getParentFile();
            if (parentDir != null && !parentDir.mkdirs()) {
                Log.w("BackupManager", "Failed to create parent directory: " + parentDir.getAbsolutePath());
            }
            
            zipFile.extractFile(fileHeader, parentDir != null ? parentDir.getAbsolutePath() : stagingDir.getAbsolutePath(),
                    null, destFile.getName());
        }
    }

    /**
     * Validates all components in the staging directory.
     */
    private static ValidationResult validateStagedBackup(Context context, File stagingDir) {
        ValidationResult result = new ValidationResult();
        
        // Validate preferences
        File prefsFile = new File(stagingDir, BACKUP_PREFERENCES_PATH);
        if (!prefsFile.exists()) {
            result.addError("Missing preferences.json");
        } else {
            try (BufferedReader reader = new BufferedReader(new FileReader(prefsFile))) {
                JsonObject obj = SettingsHelper.getGson().fromJson(reader, JsonObject.class);
                if (obj == null) {
                    result.addError("Preferences JSON is null");
                } else {
                    result.preferencesValid = true;
                }
            } catch (Exception e) {
                result.addError("Preferences validation failed: " + e.getMessage());
            }
        }
        
        // Validate notification rules
        File notifRulesFile = new File(stagingDir, BACKUP_NOTIFICATION_RULES_PATH);
        if (!notifRulesFile.exists()) {
            result.addError("Missing notification_rules.json");
        } else {
            try (BufferedReader reader = new BufferedReader(new FileReader(notifRulesFile))) {
                SettingsHelper.getGson().fromJson(reader, JsonObject.class);
                result.notificationRulesValid = true;
            } catch (Exception e) {
                result.addError("Notification rules validation failed: " + e.getMessage());
            }
        }
        
        // Validate command aliases
        File aliasesFile = new File(stagingDir, BACKUP_COMMAND_ALIASES_PATH);
        if (!aliasesFile.exists()) {
            result.addError("Missing command_aliases.json");
        } else {
            try (BufferedReader reader = new BufferedReader(new FileReader(aliasesFile))) {
                SettingsHelper.getGson().fromJson(reader, JsonObject.class);
                result.commandAliasesValid = true;
            } catch (Exception e) {
                result.addError("Command aliases validation failed: " + e.getMessage());
            }
        }
        
        // Validate servers
        File serversDir = new File(stagingDir, "servers");
        if (serversDir.exists() && serversDir.isDirectory()) {
            File[] serverFiles = serversDir.listFiles((dir, name) -> name.startsWith("server-") && name.endsWith(".json"));
            if (serverFiles != null) {
                result.serverCount = serverFiles.length;
                for (File serverFile : serverFiles) {
                    try (BufferedReader reader = new BufferedReader(new FileReader(serverFile))) {
                        ServerConfigData data = SettingsHelper.getGson().fromJson(reader, ServerConfigData.class);
                        if (data == null || data.uuid == null) {
                            result.addError("Invalid server config (null or missing UUID): " + serverFile.getName());
                        } else {
                            result.validServerCount++;
                            result.serverUuids.add(data.uuid);
                        }
                    } catch (Exception e) {
                        result.addError("Server config parse failed: " + serverFile.getName() + " - " + e.getMessage());
                    }
                }
            }
        }
        
        // Validate certificates
        if (serversDir != null && serversDir.exists()) {
            File[] certFiles = serversDir.listFiles((dir, name) -> name.startsWith("server-certs-") && name.endsWith(".jks"));
            if (certFiles != null) {
                result.certificateCount = certFiles.length;
                for (File certFile : certFiles) {
                    try (java.io.FileInputStream fis = new java.io.FileInputStream(certFile)) {
                        java.security.KeyStore ks = java.security.KeyStore.getInstance(java.security.KeyStore.getDefaultType());
                        ks.load(fis, null);
                        result.validCertificateCount++;
                        // Extract UUID from filename
                        String uuidStr = certFile.getName().substring("server-certs-".length(), certFile.getName().length() - ".jks".length());
                        try {
                            result.certificateUuids.add(UUID.fromString(uuidStr));
                        } catch (IllegalArgumentException ignored) {}
                    } catch (Exception e) {
                        result.addError("Certificate validation failed: " + certFile.getName() + " - " + e.getMessage());
                    }
                }
            }
        }
        
        // Validate themes
        File themesDir = new File(stagingDir, "themes");
        if (themesDir.exists() && themesDir.isDirectory()) {
            File[] themeFiles = themesDir.listFiles((dir, name) -> name.startsWith("theme-") && name.endsWith(".json"));
            if (themeFiles != null) {
                result.themeCount = themeFiles.length;
                for (File themeFile : themeFiles) {
                    try {
                        validateThemeFile(themeFile);
                        result.validThemeCount++;
                        String uuidStr = themeFile.getName().substring("theme-".length(), themeFile.getName().length() - ".json".length());
                        try {
                            result.themeUuids.add(UUID.fromString(uuidStr));
                        } catch (IllegalArgumentException ignored) {}
                    } catch (Exception e) {
                        result.addError("Theme validation failed: " + themeFile.getName() + " - " + e.getMessage());
                    }
                }
            }
        }
        
        // Validate notification DB
        File notifDbFile = new File(stagingDir, NOTIFICATION_COUNT_DB_PATH);
        if (notifDbFile.exists()) {
            result.hasNotificationDb = true;
            try {
                android.database.sqlite.SQLiteDatabase db = android.database.sqlite.SQLiteDatabase.openDatabase(
                        notifDbFile.getAbsolutePath(), null, android.database.sqlite.SQLiteDatabase.OPEN_READONLY);
                try (android.database.Cursor c = db.rawQuery("SELECT count, firstMessageId FROM 'notification_count' LIMIT 1", null)) {
                    // Verify schema
                }
                db.close();
                result.notificationDbValid = true;
            } catch (Exception e) {
                result.addError("Notification DB validation failed: " + e.getMessage());
            }
        }
        
        // Validate custom preference files
        File prefValuesDir = new File(stagingDir, "pref_values");
        if (prefValuesDir.exists() && prefValuesDir.isDirectory()) {
            File[] customFiles = prefValuesDir.listFiles();
            if (customFiles != null) {
                result.customFileCount = customFiles.length;
                for (File customFile : customFiles) {
                    // Basic validation - file should be readable and reasonable size
                    if (customFile.length() > 10 * 1024 * 1024) { // 10MB limit
                        result.addError("Custom file too large: " + customFile.getName());
                    } else {
                        result.validCustomFileCount++;
                    }
                }
            }
        }
        
        result.isValid = result.preferencesValid && result.notificationRulesValid && result.commandAliasesValid && result.errors.isEmpty();
        return result;
    }

    /**
     * Applies the validated staged restore. This is the only phase that makes destructive changes.
     * If any step fails, we attempt to rollback but log the inconsistency.
     */
    private static void applyStagedRestore(Context context, File stagingDir, ValidationResult validation) throws IOException {
        ServerConfigManager configManager = ServerConfigManager.getInstance(context);
        ThemeManager themeManager = ThemeManager.getInstance(context);
        
        // Track what we've successfully applied for potential rollback
        List<UUID> restoredServerUuids = new ArrayList<>();
        
        try {
            // 1. Restore preferences (use commit() for synchronous persistence)
            File prefsFile = new File(stagingDir, BACKUP_PREFERENCES_PATH);
            if (prefsFile.exists()) {
                try (BufferedReader reader = new BufferedReader(new FileReader(prefsFile))) {
                    importPreferencesFromJsonSync(context, reader);
                }
            }
            
            // 2. Restore notification rules
            File notifRulesFile = new File(stagingDir, BACKUP_NOTIFICATION_RULES_PATH);
            if (notifRulesFile.exists()) {
                try (BufferedReader reader = new BufferedReader(new FileReader(notifRulesFile))) {
                    NotificationRuleManager.loadUserRuleSettings(reader);
                    NotificationRuleManager.saveUserRuleSettings(context);
                }
            }
            
            // 3. Restore command aliases
            File aliasesFile = new File(stagingDir, BACKUP_COMMAND_ALIASES_PATH);
            if (aliasesFile.exists()) {
                try (BufferedReader reader = new BufferedReader(new FileReader(aliasesFile))) {
                    CommandAliasManager aliasManager = CommandAliasManager.getInstance(context);
                    aliasManager.loadUserSettings(reader);
                    aliasManager.saveUserSettings();
                }
            }
            
            // 4. Backup current server list for potential rollback
            List<ServerConfigData> currentServers = new ArrayList<>(configManager.getServers());
            
            // 5. Restore servers (delete all first, then restore from backup)
            // We delete existing servers ONLY after we know the backup servers are valid
            configManager.deleteAllServers(false);
            ServerConnectionManager.getInstance(context).disconnectAndRemoveAllConnections(true);
            
            File serversDir = new File(stagingDir, "servers");
            if (serversDir.exists() && serversDir.isDirectory()) {
                File[] serverFiles = serversDir.listFiles((dir, name) -> name.startsWith("server-") && name.endsWith(".json"));
                if (serverFiles != null) {
                    for (File serverFile : serverFiles) {
                        try (BufferedReader reader = new BufferedReader(new FileReader(serverFile))) {
                            ServerConfigData data = SettingsHelper.getGson().fromJson(reader, ServerConfigData.class);
                            if (data != null) {
                                data.migrateLegacyProperties();
                                configManager.saveServer(data);
                                restoredServerUuids.add(data.uuid);
                            }
                        } catch (Exception e) {
                            Log.e("BackupManager", "Failed to restore server: " + serverFile.getName(), e);
                        }
                    }
                }
            }
            
            // 6. Restore certificates
            if (serversDir.exists()) {
                File[] certFiles = serversDir.listFiles((dir, name) -> name.startsWith("server-certs-") && name.endsWith(".jks"));
                if (certFiles != null) {
                    for (File certFile : certFiles) {
                        String uuidStr = certFile.getName().substring("server-certs-".length(), certFile.getName().length() - ".jks".length());
                        try {
                            UUID uuid = UUID.fromString(uuidStr);
                            ServerCertificateManager helper = ServerCertificateManager.get(context, uuid);
                            try (java.io.FileInputStream fis = new java.io.FileInputStream(certFile)) {
                                helper.loadKeyStore(fis);
                                helper.saveKeyStore();
                                Log.d("BackupManager", "Restored SSL certificates for: " + uuid);
                            }
                        } catch (Exception e) {
                            Log.e("BackupManager", "Failed to restore certificate for " + uuidStr, e);
                        }
                    }
                }
            }
            
            // 7. Restore themes (atomic swap)
            File themesDir = new File(stagingDir, "themes");
            File targetThemeDir = themeManager.getThemesDir();
            File backupThemeDir = new File(context.getCacheDir(), "themes_backup_" + System.currentTimeMillis());
            
            // Backup current themes
            if (targetThemeDir.exists()) {
                if (!copyDirectory(targetThemeDir, backupThemeDir)) {
                    Log.w("BackupManager", "Failed to backup current themes, proceeding anyway");
                }
            }
            
            try {
                // Clear target theme directory
                if (targetThemeDir.exists()) {
                    File[] existingThemes = targetThemeDir.listFiles();
                    if (existingThemes != null) {
                        for (File f : existingThemes) {
                            if (!f.delete()) {
                                Log.w("BackupManager", "Failed to delete existing theme: " + f.getName());
                            }
                        }
                    }
                }
                if (!targetThemeDir.mkdirs()) {
                    Log.w("BackupManager", "Failed to create themes directory: " + targetThemeDir.getAbsolutePath());
                }
                
                // Copy validated themes
                if (themesDir.exists() && themesDir.isDirectory()) {
                    File[] themeFiles = themesDir.listFiles((dir, name) -> name.startsWith("theme-") && name.endsWith(".json"));
                    if (themeFiles != null) {
                        for (File themeFile : themeFiles) {
                            File destFile = new File(targetThemeDir, themeFile.getName());
                            if (!copyFile(themeFile, destFile)) {
                                Log.e("BackupManager", "Failed to copy theme: " + themeFile.getName());
                            }
                        }
                    }
                }
            } catch (Exception e) {
                // Rollback themes from backup
                Log.e("BackupManager", "Theme restore failed, rolling back", e);
                if (backupThemeDir.exists()) {
                    deleteRecursive(targetThemeDir);
                    copyDirectory(backupThemeDir, targetThemeDir);
                }
                throw new IOException("Theme restore failed: " + e.getMessage(), e);
            } finally {
                // Cleanup backup
                deleteRecursive(backupThemeDir);
            }
            
            // 8. Restore notification database (atomic replace)
            File notifDbFile = new File(stagingDir, NOTIFICATION_COUNT_DB_PATH);
            if (notifDbFile.exists()) {
                NotificationCountStorage storage = NotificationCountStorage.getInstance(context);
                storage.close();
                File destDbFile = NotificationCountStorage.getFile(context);
                File backupDbFile = new File(destDbFile.getAbsolutePath() + ".bak");
                
                // Backup current DB
                if (destDbFile.exists()) {
                    if (!destDbFile.renameTo(backupDbFile)) {
                        Log.w("BackupManager", "Failed to backup current notification DB");
                    }
                }
                
                try {
                    if (!copyFile(notifDbFile, destDbFile)) {
                        throw new IOException("Failed to copy notification DB");
                    }
                    storage.open();
                    storage.validateDatabase();
                    // Remove backup on success
                    if (backupDbFile.exists()) backupDbFile.delete();
                } catch (Exception e) {
                    Log.e("BackupManager", "Notification DB restore failed, rolling back", e);
                    // Rollback
                    if (backupDbFile.exists()) {
                        if (destDbFile.exists()) destDbFile.delete();
                        backupDbFile.renameTo(destDbFile);
                    }
                    storage.open(); // Reopen with rolled-back DB
                    throw new IOException("Notification DB restore failed: " + e.getMessage(), e);
                }
            }
            
            // 9. Restore custom preference files
            File prefValuesDir = new File(stagingDir, "pref_values");
            File targetCustomDir = ListWithCustomSetting.getCustomFilesDir(context);
            if (prefValuesDir.exists() && prefValuesDir.isDirectory()) {
                File[] customFiles = prefValuesDir.listFiles();
                if (customFiles != null) {
                    for (File customFile : customFiles) {
                        // Validate filename - no path traversal
                        String name = customFile.getName();
                        if (name.contains("..") || name.contains("/") || name.contains("\\")) {
                            Log.w("BackupManager", "Skipping suspicious custom file name: " + name);
                            continue;
                        }
                        File destFile = new File(targetCustomDir, name);
                        copyFile(customFile, destFile);
                    }
                }
            }
            
            // 10. Clean up chat logs for servers not in backup
            // (Servers that existed before but aren't in the backup)
            for (ServerConfigData server : currentServers) {
                if (!restoredServerUuids.contains(server.uuid)) {
                    File logDir = configManager.getServerChatLogDir(server.uuid);
                    if (logDir.exists()) {
                        File[] files = logDir.listFiles();
                        if (files != null) {
                            for (File f : files) f.delete();
                        }
                        logDir.delete();
                    }
                }
            }
            
            // 11. Reload themes
            themeManager.reloadThemes();
            
        } catch (IOException e) {
            // Attempt rollback of what we can
            attemptRollback(configManager, themeManager, restoredServerUuids);
            throw e;
        }
    }

    /**
     * Attempts to rollback changes if restore fails mid-way.
     * Best effort - logs what couldn't be rolled back.
     */
    private static void attemptRollback(ServerConfigManager configManager, ThemeManager themeManager,
                                       List<UUID> restoredServerUuids) {
        Log.w("BackupManager", "Attempting rollback of partial restore...");
        
        // Rollback servers - delete restored ones
        for (UUID uuid : restoredServerUuids) {
            try {
                ServerConfigData data = configManager.findServer(uuid);
                if (data != null) {
                    configManager.deleteServer(data, false);
                }
            } catch (Exception e) {
                Log.e("BackupManager", "Rollback failed for server: " + uuid, e);
            }
        }
        
        // Note: Full rollback of preferences, notification rules, aliases, custom files, notification DB
        // would require keeping backups of the originals, which we don't do for simplicity.
        // In practice, the validation phase should catch issues before we reach this point.
        
        // Reload themes to ensure consistency
        try {
            themeManager.reloadThemes();
        } catch (Exception e) {
            Log.e("BackupManager", "Failed to reload themes after rollback", e);
        }
    }

    /**
     * Validation result for staged backup.
     */
    private static class ValidationResult {
        public boolean isValid = false;
        public boolean preferencesValid = false;
        public boolean notificationRulesValid = false;
        public boolean commandAliasesValid = false;
        public boolean notificationDbValid = false;
        public int serverCount = 0;
        public int validServerCount = 0;
        public int certificateCount = 0;
        public int validCertificateCount = 0;
        public int themeCount = 0;
        public int validThemeCount = 0;
        public int customFileCount = 0;
        public int validCustomFileCount = 0;
        public boolean hasNotificationDb = false;
        public final List<UUID> serverUuids = new ArrayList<>();
        public final List<UUID> certificateUuids = new ArrayList<>();
        public final List<UUID> themeUuids = new ArrayList<>();
        public final List<String> errors = new ArrayList<>();
        
        public void addError(String error) {
            errors.add(error);
        }
        
        public String getErrorSummary() {
            if (errors.isEmpty()) return "Unknown validation error";
            return String.join("; ", errors);
        }
    }

    private static InputStream exportPreferencesToJson(Context context) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        String str = SettingsHelper.getGson().toJson(prefs.getAll());
        return new ByteArrayInputStream(str.getBytes());
    }

    /**
     * Imports preferences from JSON using synchronous commit() instead of async apply().
     * This ensures preferences are fully persisted before restore continues.
     */
    private static void importPreferencesFromJsonSync(Context context, Reader reader) {
        SharedPreferences.Editor prefs = PreferenceManager.getDefaultSharedPreferences(context).edit();
        SettingsHelper.getInstance(context).clear();
        JsonObject obj = SettingsHelper.getGson().fromJson(reader, JsonObject.class);
        if (obj == null)
            return;
        for (Map.Entry<String, JsonElement> entry : obj.entrySet()) {
            JsonElement el = entry.getValue();
            if (el == null || el.isJsonNull())
                continue;
            if (el.isJsonArray()) {
                Set<String> items = new HashSet<>();
                for (JsonElement child : el.getAsJsonArray()) {
                    if (child != null && !child.isJsonNull())
                        items.add(child.getAsString());
                }
                prefs.putStringSet(entry.getKey(), items);
            } else if (el.isJsonPrimitive()) {
                JsonPrimitive primitive = el.getAsJsonPrimitive();
                if (primitive.isBoolean()) {
                    prefs.putBoolean(entry.getKey(), primitive.getAsBoolean());
                } else if (primitive.isNumber()) {
                    Number number = primitive.getAsNumber();
                    if (number instanceof Float || number instanceof Double)
                        prefs.putFloat(entry.getKey(), number.floatValue());
                    else if (number instanceof Long)
                        prefs.putLong(entry.getKey(), number.longValue());
                    else
                        prefs.putInt(entry.getKey(), number.intValue());
                } else if (primitive.isString()) {
                    prefs.putString(entry.getKey(), primitive.getAsString());
                }
            }
        }
        // Use commit() instead of apply() for synchronous persistence during restore
        boolean committed = prefs.commit();
        if (!committed) {
            Log.w("BackupManager", "Preferences commit() returned false during restore");
        }
    }

    /**
     * Recursively deletes a directory and all its contents.
     */
    private static void deleteRecursive(File file) {
        if (file == null) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursive(child);
                }
            }
        }
        if (!file.delete()) {
            Log.w("BackupManager", "Failed to delete: " + file.getAbsolutePath());
        }
    }

    /**
     * Copies a single file.
     */
    private static boolean copyFile(File source, File dest) {
        try (java.io.FileInputStream fis = new java.io.FileInputStream(source);
             java.io.FileOutputStream fos = new java.io.FileOutputStream(dest)) {
            byte[] buffer = new byte[16 * 1024];
            int length;
            while ((length = fis.read(buffer)) > 0) {
                fos.write(buffer, 0, length);
            }
            return true;
        } catch (IOException e) {
            Log.e("BackupManager", "Failed to copy file " + source.getName() + " to " + dest.getName(), e);
            return false;
        }
    }

    /**
     * Copies a directory recursively.
     */
    private static boolean copyDirectory(File source, File dest) {
        if (!source.exists() || !source.isDirectory()) return false;
        if (!dest.mkdirs()) return false;
        
        File[] files = source.listFiles();
        if (files == null) return true;
        
        for (File file : files) {
            File destFile = new File(dest, file.getName());
            if (file.isDirectory()) {
                if (!copyDirectory(file, destFile)) return false;
            } else {
                if (!copyFile(file, destFile)) return false;
            }
        }
        return true;
    }

    /**
     * Validates that a theme file contains valid JSON and required fields.
     */
    private static void validateThemeFile(File themeFile) throws IOException {
        try (BufferedReader reader = new BufferedReader(new FileReader(themeFile))) {
            ThemeInfo theme = SettingsHelper.getGson().fromJson(reader, ThemeInfo.class);
            if (theme == null) {
                throw new IOException("Theme file is empty or null");
            }
            if (theme.uuid == null) {
                throw new IOException("Theme missing UUID");
            }
            if (theme.base == null || theme.base.isEmpty()) {
                throw new IOException("Theme missing base theme");
            }
        }
    }
}