package io.mrarm.irc.config;

/**
 * Centralized constants for backup and server file paths.
 * This class consolidates all path-related constants to avoid duplication
 * and ensure consistency across the codebase.
 */
public final class BackupPaths {

    // Prevent instantiation
    private BackupPaths() {}

    // Backup file structure constants
    public static final String BACKUP_PREFERENCES_PATH = "preferences.json";
    public static final String BACKUP_PREF_VALUES_PREFIX = "pref_values/";
    public static final String BACKUP_SERVER_PREFIX = "servers/server-";
    public static final String BACKUP_SERVER_SUFFIX = ".json";
    public static final String BACKUP_SERVER_CERTS_PREFIX = "servers/server-certs-";
    public static final String BACKUP_SERVER_CERTS_SUFFIX = ".jks";
    public static final String BACKUP_NOTIFICATION_RULES_PATH = "notification_rules.json";
    public static final String BACKUP_COMMAND_ALIASES_PATH = "command_aliases.json";
    public static final String BACKUP_TEMPLATES_PATH = "templates.json";
    public static final String NOTIFICATION_COUNT_DB_PATH = "notification-count.db";
    public static final String BACKUP_THEME_PREFIX = "themes/theme-";
    public static final String BACKUP_THEME_SUFFIX = ".json";

    // ServerConfigManager file structure constants (for local storage)
    public static final String SERVERS_PATH = "servers";
    public static final String SERVER_FILE_PREFIX = "server-";
    public static final String SERVER_FILE_SUFFIX = ".json";
    public static final String SERVER_CERTS_FILE_PREFIX = "server-certs-";
    public static final String SERVER_CERTS_FILE_SUFFIX = ".jks";
    public static final String SERVER_LOGS_PATH = "chat-logs";
    public static final String SERVER_MISC_DATA_FILENAME = "misc-data.db";

    // Connected servers file
    public static final String CONNECTED_SERVERS_FILE_PATH = "connected_servers.json";
}