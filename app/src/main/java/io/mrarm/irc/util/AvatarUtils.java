package io.mrarm.irc.util;

import java.util.Locale;

public class AvatarUtils {

    public static boolean isChateagratisServer(String host) {
        if (host == null) {
            return false;
        }
        String h = host.toLowerCase(Locale.ROOT).trim();
        return h.equalsIgnoreCase("irc.chatsfree.net") ||
                h.equalsIgnoreCase("irc.chatzona.org") ||
                h.equalsIgnoreCase("irc.chateagratis.net") ||
                h.equals("5.39.71.48") ||
                h.equals("51.255.67.106") ||
                h.equals("54.36.122.39");
    }

    public static void shutdown() {
    }
}
