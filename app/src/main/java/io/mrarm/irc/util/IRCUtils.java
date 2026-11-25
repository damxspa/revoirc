package io.mrarm.irc.util;

import java.util.Locale;

public class IRCUtils {

    public static boolean isChannel(String name) {
        return name != null && (name.startsWith("#") || name.startsWith("&") || name.startsWith("!") || name.startsWith("+"));
    }

    public static boolean isAntibotServer(String address) {
        if (address == null)
            return false;
        String addr = address.trim().toLowerCase(Locale.ROOT);
        return addr.equals("irc.chatsfree.net") || addr.equals("chatsfree.net") || addr.endsWith(".chatsfree.net")
                || addr.equals("irc.chatzona.org") || addr.equals("chatzona.org") || addr.endsWith(".chatzona.org")
                || addr.equals("irc.chateagratis.net") || addr.equals("chateagratis.net") || addr.endsWith(".chateagratis.net")
                || addr.equals("5.39.71.48")
                || addr.equals("51.255.67.106")
                || addr.equals("54.36.122.39");
    }

}
