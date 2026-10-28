package io.mrarm.irc.util;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import net.lingala.zip4j.core.ZipFile;
import net.lingala.zip4j.exception.ZipException;
import net.lingala.zip4j.model.ZipParameters;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import io.mrarm.chatlib.android.storage.SQLiteMessageStorageApi;
import io.mrarm.chatlib.dto.KickMessageInfo;
import io.mrarm.chatlib.dto.MessageInfo;
import io.mrarm.chatlib.dto.MessageList;
import io.mrarm.chatlib.dto.MessageListAfterIdentifier;
import io.mrarm.chatlib.dto.NickChangeMessageInfo;
import io.mrarm.chatlib.message.MessageStorageApi;
import io.mrarm.irc.R;
import io.mrarm.irc.config.ServerConfigData;
import io.mrarm.irc.config.ServerConfigManager;

public class ChatExportManager {

    private static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
    private static final SimpleDateFormat TIME_FORMAT = new SimpleDateFormat("HH:mm:ss", Locale.US);
    private static final SimpleDateFormat FILE_DATE_FORMAT = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US);
    private static final SimpleDateFormat TITLE_DATE_FORMAT = new SimpleDateFormat("EEE dd MMM yyyy HH:mm:ss", Locale.US);

    private static final String[] NICK_COLORS = {
            "#A82F2F", "#2E7D32", "#1565C0", "#6A1B9A", "#EF6C00",
            "#00838F", "#AD1457", "#558B2F", "#283593", "#D84315",
            "#4E342E", "#37474F", "#0277BD", "#F9A825", "#5D4037", "#00695C"
    };

    public enum ExportFormat {
        TXT, HTML
    }

    public interface ExportCallback {
        void onExportCompleted(File zipFile);
        void onExportFailed(Exception e);
        void onProgressUpdate(int progress, int total);
    }

    public static void exportChatLogs(Context context, ExportCallback callback) {
        exportChatLogs(context, null, ExportFormat.TXT, callback);
    }

    public static void exportChatLogs(Context context, UUID serverUUID, ExportFormat format, ExportCallback callback) {
        exportChatLogs(context, serverUUID, format, false, callback);
    }

    public static void exportChatLogs(Context context, UUID serverUUID, ExportFormat format, boolean channelsOnly, ExportCallback callback) {
        Handler mainHandler = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            if (Looper.myLooper() == null) {
                Looper.prepare();
            }
            try {
                File exportDir = new File(context.getCacheDir(), "chat_export_" + FILE_DATE_FORMAT.format(new Date()));
                if (exportDir.exists()) {
                    deleteRecursive(exportDir);
                }
                if (!exportDir.mkdirs()) {
                    Log.w("ChatExportManager", "Failed to create export directory: " + exportDir.getAbsolutePath());
                }

                ServerConfigManager serverConfigManager = ServerConfigManager.getInstance(context);
                List<ServerConfigData> servers;
                if (serverUUID != null) {
                    ServerConfigData server = serverConfigManager.findServer(serverUUID);
                    servers = server != null ? Collections.singletonList(server) : Collections.emptyList();
                } else {
                    servers = serverConfigManager.getServers();
                }

                int totalSteps = servers.size();
                int currentStep = 0;
                int exportedCount = 0;

                for (ServerConfigData server : servers) {
                    File logDir = serverConfigManager.getServerChatLogDir(server.uuid);
                    if (!logDir.exists()) {
                        currentStep++;
                        continue;
                    }

                    File serverDir = exportDir;
                    if (!channelsOnly) {
                        serverDir = new File(exportDir, server.name.replaceAll("[^a-zA-Z0-9.-]", "_"));
                        if (!serverDir.exists() && !serverDir.mkdirs()) {
                            Log.w("ChatExportManager", "Failed to create server directory: " + serverDir.getAbsolutePath());
                        }
                    }

                    SQLiteMessageStorageApi storageApi = new SQLiteMessageStorageApi(logDir);
                    storageApi.open();

                    List<String> channels = discoverChannels(logDir);

                    for (String channel : channels) {
                        if (channelsOnly && !IRCUtils.isChannel(channel))
                            continue;
                        String fileName;
                        if (channel == null || channel.isEmpty()) {
                            fileName = "server";
                        } else {
                            fileName = channel.replaceAll("[^a-zA-Z0-9.-]", "_");
                            if (fileName.isEmpty())
                                fileName = "server";
                        }
                        if (channelsOnly) {
                            fileName = fileName + "_" + server.name.replaceAll("[^a-zA-Z0-9.-]", "_");
                        }
                        File channelFile = new File(serverDir, fileName + (format == ExportFormat.HTML ? ".html" : ".txt"));
                        exportChannel(context, storageApi, channel, channelFile, format, server.name);
                        if (channelFile.exists())
                            exportedCount++;
                    }

                    storageApi.close();
                    currentStep++;
                    if (callback != null) {
                        int finalCurrentStep = currentStep;
                        mainHandler.post(() -> callback.onProgressUpdate(finalCurrentStep, totalSteps));
                    }
                }

                if (exportedCount == 0) {
                    throw new Exception("No logs found to export");
                }

                String zipFileName = "IRC_Logs_" + FILE_DATE_FORMAT.format(new Date()) + ".zip";
                if (channelsOnly) {
                    zipFileName = "IRC_Channel_Logs_" + FILE_DATE_FORMAT.format(new Date()) + ".zip";
                }
                if (serverUUID != null && !servers.isEmpty()) {
                    zipFileName = "IRC_Logs_" + servers.get(0).name.replaceAll("[^a-zA-Z0-9.-]", "_") + "_" + FILE_DATE_FORMAT.format(new Date()) + ".zip";
                }
                File zipFile = new File(context.getCacheDir(), zipFileName);
                if (zipFile.exists() && !zipFile.delete()) {
                    Log.w("ChatExportManager", "Failed to delete existing zip file: " + zipFile.getAbsolutePath());
                }
                zipDirectory(exportDir, zipFile);

                deleteRecursive(exportDir);

                if (callback != null) {
                    mainHandler.post(() -> callback.onExportCompleted(zipFile));
                }
            } catch (Exception e) {
                if (callback != null) {
                    mainHandler.post(() -> callback.onExportFailed(e));
                }
            }
        }).start();
    }

    private static List<String> discoverChannels(File logDir) {
        Set<String> channels = new HashSet<>();
        File[] files = logDir.listFiles();
        if (files == null) return new ArrayList<>();
        for (File file : files) {
            if (file.getName().endsWith(".db") && file.getName().startsWith("messages-")) {
                try (SQLiteDatabase db = SQLiteDatabase.openDatabase(file.getAbsolutePath(), null, SQLiteDatabase.OPEN_READONLY)) {
                    try (Cursor cursor = db.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND (name LIKE 'messages_%' OR name = 'messages')", null)) {
                        while (cursor.moveToNext()) {
                            String tableName = cursor.getString(0);
                            if (Objects.equals(tableName, "messages")) {
                                channels.add(null);
                            } else {
                                channels.add(tableName.substring(9)); // strip "messages_" prefix
                            }
                        }
                    }
                } catch (Exception ignored) {
                }
            }
        }
        return new ArrayList<>(channels);
    }

    private static void exportChannel(Context context, MessageStorageApi storageApi, String channel,
                                       File outputFile, ExportFormat format, String serverName)
            throws IOException, InterruptedException, ExecutionException {
        List<MessageInfo> allMessages = new ArrayList<>();
        MessageListAfterIdentifier after = null;
        while (true) {
            Future<MessageList> future = storageApi.getMessages(channel, 100, null, after, null, null);
            MessageList list;
            try {
                list = future.get(30, TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                Log.e("ChatExportManager", "Timeout waiting for messages", e);
                break;
            }
            if (list == null || list.getMessages() == null || list.getMessages().isEmpty()) {
                break;
            }
            allMessages.addAll(list.getMessages());
            after = list.getOlder();
            if (after == null) {
                break;
            }
        }

        if (allMessages.isEmpty()) {
            return;
        }

        Collections.reverse(allMessages);

        try (BufferedWriter writer = new BufferedWriter(new FileWriter(outputFile))) {
            if (format == ExportFormat.HTML) {
                String displayChannel = (channel != null && !channel.isEmpty()) ? channel : "Server";
                String convTitle = "Conversation with " + displayChannel + " at "
                        + TITLE_DATE_FORMAT.format(new Date()) + " on " + serverName + " (IRC)";
                writer.write("<!DOCTYPE HTML PUBLIC \"-//W3C//DTD HTML 4.01//EN\" \"http://www.w3.org/TR/html4/strict.dtd\">\n");
                writer.write("<html><head><meta http-equiv=\"Content-Type\" content=\"text/html; charset=utf-8\">\n");
                writer.write("<title>" + escapeHtml(convTitle) + "</title>\n");
                writer.write("</head><body>\n");
                writer.write("<h1>" + escapeHtml(convTitle) + "</h1>\n");
                writer.write("<p>\n");
            }
            for (MessageInfo msg : allMessages) {
                String time = DATE_FORMAT.format(msg.getDate());
                String shortTime = TIME_FORMAT.format(msg.getDate());
                String nick = msg.getSender() != null ? msg.getSender().getNick() : "System";
                String message = msg.getMessage();

                if (msg.getType() == MessageInfo.MessageType.NORMAL
                        || msg.getType() == MessageInfo.MessageType.NOTICE
                        || msg.getType() == MessageInfo.MessageType.ME) {
                    String prefix = (msg.getType() == MessageInfo.MessageType.ME ? "* "
                            : msg.getType() == MessageInfo.MessageType.NOTICE ? "-" : "<");
                    String suffix = (msg.getType() == MessageInfo.MessageType.ME ? " "
                            : msg.getType() == MessageInfo.MessageType.NOTICE ? "- " : "> ");
                    if (format == ExportFormat.HTML) {
                        writer.write("<span style=\"color: " + getNickColor(nick) + "\">");
                        writer.write("<span style=\"font-size: smaller\">(" + shortTime + ")</span> ");
                        writer.write("<b>" + escapeHtml(prefix + nick + suffix) + "</b>");
                        writer.write("</span>");
                        writer.write(escapeHtml(message != null ? message : ""));
                        writer.write("<br>\n");
                    } else {
                        writer.write(String.format("[%s] %s%s%s%s\n", time, prefix, nick, suffix,
                                message != null ? message : ""));
                    }
                } else {
                    if (format == ExportFormat.HTML) {
                        String eventText = getEventText(context, msg, nick, message);
                        writer.write("<span style=\"font-size: smaller\">(" + shortTime + ")</span>");
                        writer.write("<b> " + escapeHtml(eventText) + "</b><br>\n");
                    } else {
                        String description = getEventDescription(context, msg, nick, message);
                        writer.write(String.format("[%s] (%s) %s\n", time, nick, description));
                    }
                }
            }
            if (format == ExportFormat.HTML) {
                writer.write("</p>\n</body></html>");
            }
        }
    }

    private static String getEventDescription(Context context, MessageInfo msg, String nick, String message) {
        switch (msg.getType()) {
            case JOIN:
                return context.getString(R.string.message_join, nick).trim();
            case PART:
                return context.getString(message == null
                        ? R.string.message_part_no_message : R.string.message_part, nick, message).trim();
            case QUIT:
                return context.getString(R.string.message_quit, nick, message != null ? message : "").trim();
            case KICK: {
                String kicked = (msg instanceof KickMessageInfo)
                        ? ((KickMessageInfo) msg).getKickedNick() : "unknown";
                return context.getString(R.string.message_kick, nick, kicked, message).trim();
            }
            case NICK_CHANGE: {
                String newNick = (msg instanceof NickChangeMessageInfo)
                        ? ((NickChangeMessageInfo) msg).getNewNick() : "unknown";
                return context.getString(R.string.message_nick_change, nick, newNick).trim();
            }
            case MODE:
                return context.getString(R.string.message_mode, nick, message != null ? message : "").trim();
            case TOPIC:
                if (message == null)
                    return context.getString(R.string.message_topic_none);
                if (msg.getSender() == null)
                    return context.getString(R.string.message_topic, message);
                return context.getString(R.string.message_topic_by, message, nick).trim();
            case DISCONNECT_WARNING:
                return context.getString(R.string.message_disconnected);
            default:
                return message != null ? message : "";
        }
    }

    private static String getEventText(Context context, MessageInfo msg, String nick, String message) {
        switch (msg.getType()) {
            case JOIN:
                return context.getString(R.string.message_join, nick);
            case PART:
                return context.getString(message == null
                        ? R.string.message_part_no_message : R.string.message_part, nick, message);
            case QUIT:
                return context.getString(R.string.message_quit, nick, message != null ? message : "");
            case KICK: {
                String kicked = (msg instanceof KickMessageInfo)
                        ? ((KickMessageInfo) msg).getKickedNick() : "unknown";
                return context.getString(R.string.message_kick, nick, kicked, message);
            }
            case NICK_CHANGE: {
                String newNick = (msg instanceof NickChangeMessageInfo)
                        ? ((NickChangeMessageInfo) msg).getNewNick() : "unknown";
                return context.getString(R.string.message_nick_change, nick, newNick);
            }
            case MODE:
                return context.getString(R.string.message_mode, nick, message != null ? message : "");
            case TOPIC:
                if (message == null)
                    return context.getString(R.string.message_topic_none);
                if (msg.getSender() == null)
                    return context.getString(R.string.message_topic, message);
                return context.getString(R.string.message_topic_by, message, nick);
            case DISCONNECT_WARNING:
                return context.getString(R.string.message_disconnected);
            default:
                return message != null ? message : "";
        }
    }

    private static String getNickColor(String nick) {
        if (nick == null)
            return "#000000";
        int idx = Math.abs(nick.hashCode()) % NICK_COLORS.length;
        return NICK_COLORS[idx];
    }

    private static String escapeHtml(String str) {
        if (str == null) return "";
        return str.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    private static void zipDirectory(File inputDir, File outputZip) throws ZipException {
        ZipFile zipFile = new ZipFile(outputZip);
        ZipParameters parameters = new ZipParameters();
        parameters.setCompressionMethod(8); // DEFLATE
        parameters.setCompressionLevel(5);
        File[] files = inputDir.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isDirectory()) {
                    zipFile.addFolder(file, parameters);
                } else {
                    zipFile.addFile(file, parameters);
                }
            }
        }
    }

    public static void exportSingleChannel(Context context, UUID serverUUID, String channelName,
                                            ExportFormat format, ExportCallback callback) {
        Handler mainHandler = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            if (Looper.myLooper() == null) {
                Looper.prepare();
            }
            try {
                ServerConfigManager serverConfigManager = ServerConfigManager.getInstance(context);
                ServerConfigData server = serverConfigManager.findServer(serverUUID);
                if (server == null) {
                    if (callback != null)
                        mainHandler.post(() -> callback.onExportFailed(new Exception("Server not found")));
                    return;
                }

                File logDir = serverConfigManager.getServerChatLogDir(server.uuid);
                if (!logDir.exists()) {
                    if (callback != null)
                        mainHandler.post(() -> callback.onExportFailed(new Exception("No logs found")));
                    return;
                }

                SQLiteMessageStorageApi storageApi = new SQLiteMessageStorageApi(logDir);
                storageApi.open();

                String fileName = (channelName == null || channelName.isEmpty()) ? "server"
                        : channelName.replaceAll("[^a-zA-Z0-9.-]", "_");
                if (fileName.isEmpty())
                    fileName = "server";
                String suffix = format == ExportFormat.HTML ? ".html" : ".txt";
                File outputFile = new File(context.getCacheDir(),
                        fileName + "_" + FILE_DATE_FORMAT.format(new Date()) + suffix);

                exportChannel(context, storageApi, channelName, outputFile, format, server.name);

                storageApi.close();

                if (callback != null)
                    mainHandler.post(() -> callback.onExportCompleted(outputFile));
            } catch (Exception e) {
                if (callback != null)
                    mainHandler.post(() -> callback.onExportFailed(e));
            }
        }).start();
    }

    private static void deleteRecursive(File file) {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursive(child);
                }
            }
        }
        if (!file.delete()) {
            Log.w("ChatExportManager", "Failed to delete file: " + file.getAbsolutePath());
        }
    }
}
