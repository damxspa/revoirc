package io.mrarm.irc.config;

public class NotificationSettings {

    public boolean enabled = true;

    public boolean mentionFormatting = true;

    public boolean noNotification = false;

    public int priority = 0;

    public boolean lightEnabled = true;
    public int light = 0;
    public int lightOnDuration = 500;
    public int lightOffDuration = 2000;

    public boolean vibrationEnabled = true;
    public int vibrationDuration = 500;

    public boolean soundEnabled = true;
    public String soundUri;

    public String notificationChannelId;

}
