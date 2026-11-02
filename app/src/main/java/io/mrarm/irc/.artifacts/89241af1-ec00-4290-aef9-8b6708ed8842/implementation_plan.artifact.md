# Implementation Plan - Disable Notifications for Private Message Disconnects

Prevent notifications from being shown when a user in a private conversation disconnects from the network (QUIT message).

## User Review Required

> [!NOTE]
> A new setting "Notify on PM disconnects" will be added under Interface settings, defaulting to disabled to satisfy the request.

## Proposed Changes

### Configuration

#### [MODIFY] [settings.yml](file:///home/damian/IRC/revolution-irc/app/settings.yml)
- Add `notifyPrivateQuit` boolean setting to `AppSettings` with a default value of `false`.

#### [MODIFY] [strings.xml](file:///home/damian/IRC/revolution-irc/app/src/main/res/values/strings.xml)
- Add `pref_title_notify_private_quit` and `pref_summary_notify_private_quit` string resources.

### Logic

#### [MODIFY] [NotificationManager.java](file:///home/damian/IRC/revolution-irc/app/src/main/java/io/mrarm/irc/NotificationManager.java)
- Update `processMessage` to check if the message is a `QUIT` in a private chat and respect the `notifyPrivateQuit` setting.

### UI

#### [MODIFY] [InterfaceSettingsFragment.java](file:///home/damian/IRC/revolution-irc/app/src/main/java/io/mrarm/irc/setting/fragment/InterfaceSettingsFragment.java)
- Add a `CheckBoxSetting` for the new `notifyPrivateQuit` setting.

## Verification Plan

### Manual Verification
1. Open a private chat with a user.
2. Have that user disconnect (QUIT) from the network.
3. Verify that no notification is shown when the "Notify on PM disconnects" setting is disabled.
4. Enable the setting and verify that the notification is shown again.
