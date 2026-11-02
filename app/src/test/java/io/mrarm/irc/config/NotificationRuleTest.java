package io.mrarm.irc.config;

import org.junit.Test;

import io.mrarm.chatlib.dto.MessageInfo;
import io.mrarm.chatlib.dto.MessageSenderInfo;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertFalse;

public class NotificationRuleTest {

    @Test
    public void testDirectMessageRuleAppliesToDirectNotices() {
        NotificationRule directMsgRule = new NotificationRule(
                "Direct Messages",
                NotificationRule.AppliesToEntry.directEvents(),
                null
        );

        MessageSenderInfo sender = new MessageSenderInfo("TestSender", "user", "host.com", null, null);
        MessageInfo normalPm = new MessageInfo(sender, null, "Hello PM", MessageInfo.MessageType.NORMAL);
        MessageInfo noticePm = new MessageInfo(sender, null, "Hello Notice PM", MessageInfo.MessageType.NOTICE);

        // When channel is null (direct message), directEvents() should match both PRIVMSG and NOTICE
        assertTrue(directMsgRule.appliesTo(null, null, normalPm));
        assertTrue(directMsgRule.appliesTo(null, null, noticePm));

        // When channel is specified (channel message), directEvents() should NOT match
        assertFalse(directMsgRule.appliesTo(null, "#channel", normalPm));
        assertFalse(directMsgRule.appliesTo(null, "#channel", noticePm));
    }
}
