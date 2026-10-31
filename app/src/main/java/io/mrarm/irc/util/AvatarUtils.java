package io.mrarm.irc.util;

import java.util.ArrayList;
import java.util.List;
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

    public static List<String> ensureWaitCommand(List<String> cmds) {
        return ensureWaitCommand(cmds, null);
    }

    public static List<String> ensureWaitCommand(List<String> cmds, String host) {
        if (cmds == null || cmds.isEmpty())
            return cmds;
        String first = cmds.get(0).trim().replaceAll("^/", "");
        if (first.toLowerCase(Locale.ROOT).startsWith("wait")) {
            return cmds;
        }
        List<String> modified = new ArrayList<>();
        int waitTime = (host != null && isChateagratisServer(host)) ? 10 : 3;
        modified.add("wait " + waitTime);
        modified.addAll(cmds);
        return modified;
    }
}
