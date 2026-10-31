package io.mrarm.irc.util;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
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

            // Test null or empty list on Chateagratis host - should inject wait 10
            java.util.List<String> nullResult = AvatarUtils.ensureWaitCommand(null, host);
            assertNotNull(nullResult);
            assertEquals(1, nullResult.size());
            assertEquals("wait 10", nullResult.get(0));

            java.util.List<String> emptyResult = AvatarUtils.ensureWaitCommand(new java.util.ArrayList<>(), host);
            assertNotNull(emptyResult);
            assertEquals(1, emptyResult.size());
            assertEquals("wait 10", emptyResult.get(0));
        }
        
        // Test with non-Chateagratis host - should use default wait 3 or return empty/null
        java.util.List<String> resultDefault = AvatarUtils.ensureWaitCommand(cmds, "irc.libera.chat");
        assertEquals(2, resultDefault.size());
        assertEquals("wait 3", resultDefault.get(0));
        assertEquals("/join #amistad", resultDefault.get(1));

        assertNull(AvatarUtils.ensureWaitCommand(null, "irc.libera.chat"));
        assertTrue(AvatarUtils.ensureWaitCommand(new java.util.ArrayList<>(), "irc.libera.chat").isEmpty());

        // Test with null host - should use default wait 3 or return empty/null
        java.util.List<String> resultNull = AvatarUtils.ensureWaitCommand(cmds, null);
        assertEquals(2, resultNull.size());
        assertEquals("wait 3", resultNull.get(0));
        assertEquals("/join #amistad", resultNull.get(1));

        assertNull(AvatarUtils.ensureWaitCommand(null, null));
        assertTrue(AvatarUtils.ensureWaitCommand(new java.util.ArrayList<>(), null).isEmpty());

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
