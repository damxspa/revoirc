package io.mrarm.irc.util;

public class IRCUtils {

    public static boolean isChannel(String name) {
        return name != null && (name.startsWith("#") || name.startsWith("&") || name.startsWith("!") || name.startsWith("+"));
    }

}
