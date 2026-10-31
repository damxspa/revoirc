package io.mrarm.irc.util;

import org.junit.Test;

import java.util.Base64;
import java.util.Locale;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class AvatarUtilsTest {

    private String getDynamicNick() {
        return "user_" + Math.abs(new java.util.Random().nextInt(100000));
    }

    @Test
    public void testIsChateagratisServer() {
        assertTrue(AvatarUtils.isChateagratisServer("irc.chatsfree.net"));
        assertTrue(AvatarUtils.isChateagratisServer("irc.chatzona.org"));
        assertTrue(AvatarUtils.isChateagratisServer("irc.chateagratis.net"));
        assertTrue(AvatarUtils.isChateagratisServer("5.39.71.48"));
        assertTrue(AvatarUtils.isChateagratisServer("51.255.67.106"));
        assertTrue(AvatarUtils.isChateagratisServer("54.36.122.39"));
        assertFalse(AvatarUtils.isChateagratisServer(null));
    }

    @Test
    public void testGetAvatarUrlForNick_Dynamic() {
        String nick = getDynamicNick();
        String url = AvatarUtils.getAvatarUrlForNick(nick);
        assertEquals("https://chat-avatars.chateagratis.net/avatar/Chat/" + nick.toLowerCase(Locale.ROOT), url);

        String urlUpper = AvatarUtils.getAvatarUrlForNick(nick.toUpperCase(Locale.ROOT));
        assertEquals("https://chat-avatars.chateagratis.net/avatar/Chat/" + nick.toLowerCase(Locale.ROOT), urlUpper);
    }

    @Test
    public void testBuildGecos_WithoutExtension() {
        String nick = getDynamicNick();
        String url = "https://chat-avatars.chateagratis.net/avatar/Chat/" + nick;
        String gecos = AvatarUtils.buildGecos(url);
        assertNotNull(gecos);

        byte[] bytes = Base64.getDecoder().decode(gecos.trim());
        String decoded = new String(bytes);
        String[] parts = decoded.split("\\|", -1);
        assertEquals(4, parts.length);
        assertEquals("#", parts[0]);
        assertEquals("#", parts[1]);
        assertEquals("#", parts[2]);
        assertTrue(parts[3].startsWith("/avatar/Chat/" + nick + "?v="));
    }

    @Test
    public void testBuildGecos_StripsPngExtensionIfPresent() {
        String nick = getDynamicNick();
        String url = "https://chat-avatars.chateagratis.net/avatar/Chat/" + nick + ".png";
        String gecos = AvatarUtils.buildGecos(url);
        assertNotNull(gecos);

        byte[] bytes = Base64.getDecoder().decode(gecos.trim());
        String decoded = new String(bytes);
        String[] parts = decoded.split("\\|", -1);
        assertEquals(4, parts.length);
        assertTrue(parts[3].startsWith("/avatar/Chat/" + nick + "?v="));
    }

    @Test
    public void testGetAvatarUrlFromGecos_NoExtension() {
        String nick = getDynamicNick();
        String unencoded = "#|#|#|/avatar/Chat/" + nick + "?v=123456";
        String base64Gecos = Base64.getEncoder().encodeToString(unencoded.getBytes());

        String avatarUrl = AvatarUtils.getAvatarUrlFromGecos(base64Gecos);
        assertEquals("https://chat-avatars.chateagratis.net/avatar/Chat/" + nick + "?v=123456", avatarUrl);
    }

    @Test
    public void testStripExtensionFromChateagratisUrl() {
        String nick = getDynamicNick();
        assertEquals("https://chat-avatars.chateagratis.net/avatar/Chat/" + nick,
                AvatarUtils.stripExtensionFromChateagratisUrl("https://chat-avatars.chateagratis.net/avatar/Chat/" + nick + ".png"));
        assertEquals("https://chat-avatars.chateagratis.net/avatar/Chat/" + nick + "?v=100",
                AvatarUtils.stripExtensionFromChateagratisUrl("https://chat-avatars.chateagratis.net/avatar/Chat/" + nick + ".png?v=100"));
    }

    @Test
    public void testGetAvatarUrlFromGecos_GarbageGecos() {
        String garbage = Base64.getEncoder().encodeToString("Garbage".getBytes());
        assertNull(AvatarUtils.getAvatarUrlFromGecos(garbage));
        assertNull(AvatarUtils.getAvatarUrlFromGecos("Garbage"));
    }

    @Test
    public void testEnsureTimestamp() {
        String nick = getDynamicNick();
        String url = "https://chat-avatars.chateagratis.net/avatar/Chat/" + nick;
        String timestamped = AvatarUtils.ensureTimestamp(url);
        assertNotNull(timestamped);
        assertTrue(timestamped.startsWith("https://chat-avatars.chateagratis.net/avatar/Chat/" + nick + "?v="));
        assertFalse(timestamped.endsWith("?v="));

        String withEmptyV = "https://chat-avatars.chateagratis.net/avatar/Chat/" + nick + "?v=";
        String fixed = AvatarUtils.ensureTimestamp(withEmptyV);
        assertTrue(fixed.startsWith("https://chat-avatars.chateagratis.net/avatar/Chat/" + nick + "?v="));
        assertFalse(fixed.endsWith("?v="));
    }

    @Test
    public void testGetAvatarUrl_FallbackToNick() {
        String nick = getDynamicNick();
        String garbageGecos = Base64.getEncoder().encodeToString("Garbage".getBytes());
        String url = AvatarUtils.getAvatarUrl(null, garbageGecos, nick, null);
        assertEquals("https://chat-avatars.chateagratis.net/avatar/Chat/" + nick, url);
    }

    @Test
    public void testEnsureWaitCommand() {
        assertNull(AvatarUtils.ensureWaitCommand(null));
        assertTrue(AvatarUtils.ensureWaitCommand(new java.util.ArrayList<>()).isEmpty());

        java.util.List<String> cmds = new java.util.ArrayList<>();
        cmds.add("/join #amistad");
        java.util.List<String> result = AvatarUtils.ensureWaitCommand(cmds);
        assertEquals(2, result.size());
        assertEquals("wait 3", result.get(0));
        assertEquals("/join #amistad", result.get(1));

        java.util.List<String> cmdsWithWait = new java.util.ArrayList<>();
        cmdsWithWait.add("/wait 3");
        cmdsWithWait.add("/join #amistad");
        java.util.List<String> resultWithWait = AvatarUtils.ensureWaitCommand(cmdsWithWait);
        assertEquals(2, resultWithWait.size());
        assertEquals("/wait 3", resultWithWait.get(0));
        assertEquals("/join #amistad", resultWithWait.get(1));

        java.util.List<String> cmdsWithWait5 = new java.util.ArrayList<>();
        cmdsWithWait5.add("wait 5");
        cmdsWithWait5.add("/join #amistad");
        java.util.List<String> resultWithWait5 = AvatarUtils.ensureWaitCommand(cmdsWithWait5);
        assertEquals(2, resultWithWait5.size());
        assertEquals("wait 5", resultWithWait5.get(0));
        assertEquals("/join #amistad", resultWithWait5.get(1));
    }

    @Test
    public void testEnsureWaitCommand_ChateagratisServers() {
        // Test with Chateagratis server hosts - should use wait 10
        java.util.List<String> cmds = new java.util.ArrayList<>();
        cmds.add("/join #amistad");
        
        // Test all Chateagratis hosts
        String[] chateagratisHosts = {
            "irc.chatsfree.net",
            "irc.chatzona.org",
            "irc.chateagratis.net",
            "5.39.71.48",
            "51.255.67.106",
            "54.36.122.39"
        };
        
        for (String host : chateagratisHosts) {
            java.util.List<String> result = AvatarUtils.ensureWaitCommand(cmds, host);
            assertEquals(2, result.size());
            assertEquals("wait 10", result.get(0));
            assertEquals("/join #amistad", result.get(1));
        }
        
        // Test with non-Chateagratis host - should use default wait 3
        java.util.List<String> resultDefault = AvatarUtils.ensureWaitCommand(cmds, "irc.libera.chat");
        assertEquals(2, resultDefault.size());
        assertEquals("wait 3", resultDefault.get(0));
        assertEquals("/join #amistad", resultDefault.get(1));
        
        // Test with null host - should use default wait 3
        java.util.List<String> resultNull = AvatarUtils.ensureWaitCommand(cmds, null);
        assertEquals(2, resultNull.size());
        assertEquals("wait 3", resultNull.get(0));
        assertEquals("/join #amistad", resultNull.get(1));
        
        // Test with existing wait command - should not override
        java.util.List<String> cmdsWithWait = new java.util.ArrayList<>();
        cmdsWithWait.add("wait 5");
        cmdsWithWait.add("/join #test");
        java.util.List<String> resultExistingWait = AvatarUtils.ensureWaitCommand(cmdsWithWait, "irc.chatsfree.net");
        assertEquals(2, resultExistingWait.size());
        assertEquals("wait 5", resultExistingWait.get(0));
        assertEquals("/join #test", resultExistingWait.get(1));
    }
}
