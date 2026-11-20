package io.mrarm.irc.util;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
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
        assertFalse(AvatarUtils.isChateagratisServer("irc.freenode.net"));
        assertFalse(AvatarUtils.isChateagratisServer(null));
    }
}
