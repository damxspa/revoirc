package io.mrarm.irc.chat;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.text.Spannable;

import java.util.ArrayList;

import io.mrarm.chatlib.irc.IRCConnection;
import io.mrarm.irc.R;
import io.mrarm.irc.ServerConnectionInfo;
import io.mrarm.irc.config.AppSettings;
import io.mrarm.irc.config.CommandAliasManager;
import io.mrarm.irc.util.IRCColorUtils;
import io.mrarm.irc.util.IRCUtils;
import io.mrarm.irc.util.SimpleTextVariableList;

public class SendMessageHelper {

    private static final Handler sHandler = new Handler(Looper.getMainLooper());

    private static int getDelay(String target) {
        if (IRCUtils.isChannel(target))
            return 0;
        try {
            return Integer.parseInt(AppSettings.getPrivateMessageDelay());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    public static void sendMessage(Context context, ServerConnectionInfo connection,
                                   String channel, Spannable message, Callback cb) {
        String text = IRCColorUtils.convertSpannableToIRCString(context, message);
        if (text.isEmpty() || (!connection.isConnected() && !connection.isConnecting()))
            return;
        if (text.contains("\n")) {
            int delay = getDelay(channel);
            if (delay > 0) {
                cb.onMessageSent();
                sHandler.postDelayed(() -> {
                    try {
                        IRCConnection conn = (IRCConnection) connection.getApiInstance();
                        ChannelUIData uiData = connection.getChatUIData().getOrCreateChannelData(channel);
                        for (String s : text.split("\n")) {
                            conn.sendMessage(channel, s, null, null);
                            uiData.addHistoryMessage(s);
                        }
                    } catch (Exception ignored) {
                    }
                }, delay * 1000L);
                return;
            }
            try {
                IRCConnection conn = (IRCConnection) connection.getApiInstance();
                ChannelUIData uiData = connection.getChatUIData().getOrCreateChannelData(channel);
                for (String s : text.split("\n")) {
                    conn.sendMessage(channel, s, null, null);
                    uiData.addHistoryMessage(s);
                }
            } catch (Exception ignored) {
            }
            cb.onMessageSent();
            return;
        }
        if (text.charAt(0) == '/') {
            if (text.equalsIgnoreCase("/clear")) {
                cb.onClearHistoryRequested();
                return;
            }
            SimpleTextVariableList vars = new SimpleTextVariableList();
            vars.set(CommandAliasManager.VAR_CHANNEL, channel);
            vars.set(CommandAliasManager.VAR_MYNICK, connection.getUserNick());
            try {
                IRCConnection conn = (IRCConnection) connection.getApiInstance();
                CommandAliasManager.ProcessCommandResult result = CommandAliasManager
                        .getInstance(context).processCommand(conn.getServerConnectionData(),
                                text.substring(1), vars);
                if (result != null) {
                    if (result.mode == CommandAliasManager.CommandAlias.MODE_RAW) {
                        cb.onRawCommandExecuted(text, result.text);
                        conn.sendCommandRaw(result.text, null, null);
                    } else if (result.mode == CommandAliasManager.CommandAlias.MODE_MESSAGE) {
                        if (result.channel == null)
                            throw new RuntimeException();
                        int delay = getDelay(result.channel);
                        if (delay > 0) {
                            cb.onMessageSent();
                            sHandler.postDelayed(() -> {
                                if (!connection.hasChannel(result.channel)) {
                                    ArrayList<String> list = new ArrayList<>();
                                    list.add(result.channel);
                                    connection.getApiInstance().joinChannels(list, (Void v) -> {
                                        conn.sendMessage(result.channel, result.text, null, null);
                                    }, null);
                                } else {
                                    conn.sendMessage(result.channel, result.text, null, null);
                                }
                                connection.getChatUIData().getOrCreateChannelData(result.channel)
                                        .addHistoryMessage(message);
                            }, delay * 1000L);
                            return;
                        }
                        if (!connection.hasChannel(result.channel)) {
                            ArrayList<String> list = new ArrayList<>();
                            list.add(result.channel);
                            connection.getApiInstance().joinChannels(list, (Void v) -> {
                                conn.sendMessage(result.channel, result.text, null, null);
                            }, null);
                        } else {
                            conn.sendMessage(result.channel, result.text, null, null);
                        }
                    } else {
                        throw new RuntimeException();
                    }
                    connection.getChatUIData().getOrCreateChannelData(result.channel)
                            .addHistoryMessage(message);
                    cb.onMessageSent();
                    return;
                }
            } catch (RuntimeException e) {
                cb.onClientCommandError(context.getString(R.string.command_error_internal));
                return;
            }
            cb.onNoCommandHandlerFound(text);
            return;
        }
        if (channel == null)
            return;
        int delay = getDelay(channel);
        if (delay > 0) {
            cb.onMessageSent();
            sHandler.postDelayed(() -> {
                if (connection.isConnected() || connection.isConnecting()) {
                    connection.getChatUIData().getOrCreateChannelData(channel).addHistoryMessage(message);
                    connection.getApiInstance().sendMessage(channel, text, null, null);
                }
            }, delay * 1000L);
            return;
        }
        connection.getChatUIData().getOrCreateChannelData(channel).addHistoryMessage(message);
        cb.onMessageSent();
        connection.getApiInstance().sendMessage(channel, text, null, null);
    }

    public interface Callback {

        void onMessageSent();

        void onClearHistoryRequested();

        void onRawCommandExecuted(String clientCommand, String sentCommand);

        void onNoCommandHandlerFound(String message);

        void onClientCommandError(CharSequence error);

    }

}
