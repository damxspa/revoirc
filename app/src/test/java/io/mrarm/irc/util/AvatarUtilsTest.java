package io.mrarm.irc.util;

import org.junit.Test;

import java.util.Locale;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class AvatarUtilsTest {

    @Test
    public void testIsChateagratisServer() {
        assertTrue(AvatarUtils.isChateagratisServer("irc.chatsfree.net"));
        assertTrue(AvatarUtils.isChateagratisServer("irc.chatzona.org"));
        assertTrue(AvatarUtils.isChateagratisServer("irc.chateagratis.net"));
        assertTrue(AvatarUtils.isChateagratisServer("5.39.71.48"));
        assertTrue(AvatarUtils.isChateagratisServer("51.255.67.106"));
        assertTrue(AvatarUtils.isChateagratisServer("54.36.122.39"));
        assertTrue(AvatarUtils.isChateagratisServer("irc.chateagratis.net:6667"));
        assertFalse(AvatarUtils.isChateagratisServer("irc.libera.chat"));
        assertFalse(AvatarUtils.isChateagratisServer("chat.zona"));
        assertFalse(AvatarUtils.isChateagratisServer(null));
    }

    @Test
    public void testEnsureWaitCommand() {
        assertNull(AvatarUtils.ensureWaitCommand(null));
        assertTrue(AvatarUtils.ensureWaitCommand(new java.util.ArrayList<>()).isEmpty());

        java.util.List<String> cmds = new java.util.ArrayList<>();
        cmds.add("/join #amistad");
        java.util.List<String> result = AvatarUtils.ensureWaitCommand(cmds);
        assertEquals(2, result.size());
        assertEquals("wait 6", result.get(0));
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
}