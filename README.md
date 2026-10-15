# Messages

A messaging app for Android with SMS, MMS, group chats, photos, GIFs, voice memos,
reactions, scheduled texts and Textra-style customization. It uses only the Android
framework: no AndroidX, no Kotlin, no third-party libraries. Even the MMS wire format
is read and written by the app's own code (`MmsPdu.java`). See
[docs/ROADMAP.md](docs/ROADMAP.md) for the feature-by-feature comparison with Textra.

## Features

**Conversations**
- Contact pictures (or colored letters), unread counts, drafts, muted and pinned icons
- Long-press to select chats: delete (with 20-second undo), pin, mark read or unread,
  mute, blocklist, customize
- Swipe right to call, swipe left to delete
- Keep: a chat for notes, links and pictures you want to remember

**Chat**
- Bubbles in four styles, day headers, drag left to see every message's time
- Tap a bubble for quick options: copy, copy code, react, forward, share, save to
  gallery, send as new, lock, info, report spam, and actions for dates, places and numbers
  found in the text; long-press to select many
- Delivery reports ("Sent", "Delivered"), SIM per message on dual-SIM phones
- Drafts, signatures, send delay (tap to cancel), send with Enter, Smart Reply suggestions
- Web link previews, contact cards and calendar events you can save
- Undo delete for 20 seconds; locked messages can't be deleted

**The + panel**
- Recent photos and videos, a live camera tile, emoji with recents and skin tones
- GIF search (needs a Giphy key, see Build), voice memos, contacts (as vCard or text),
  events, your location, any file, and send later

**MMS and group chats**
- Pictures shrink to fit your carrier's limit (or a limit you pick)
- Group messages as one MMS chat or as separate texts (bulk)
- Long texts can go as MMS; long texts can be split for picky carriers

**Scheduled texts**
- Hold send: quick choices like "Tomorrow morning", or any date and time
- With attachments; change time, edit, send now or delete

**Notifications**
- Per-chat sound, vibration pattern (or record your own), LED color, repeats
- Messages pop-up over any app and Quick Reply, also on the lock screen
- Three buttons you choose, privacy (hide name or text), wake screen, badge
- Conversation bubbles, Android Auto and Wear actions, copy one-time codes
- Unknown senders silent or hidden; Quick Compose in the notification shade

**Customize**
- Light, dark, black or light-by-day; theme, bubble and text colors; fonts and text size;
  app icon color
- Every chat can have its own name, color, notification and sending settings

**More**
- Search chats and messages; locked and scheduled tabs
- Blocklist with last messages; auto-block unknown email senders
- Messages to keep per chat, backup and restore to a file, debug log

## Default SMS app

Android lets only the default SMS app send texts, receive new ones, mark them read
or delete them. The app shows a banner until you set it as default. Reading old
messages works either way.

**Internet:** only web link previews and GIF search use it, and only when used.

**Group chats:** to leave your own number out of a group, the app reads it from the SIM.
Some carriers don't store it there; set it in Settings > Your mobile number.

## Build

1. Open this folder in Android Studio.
2. Let Gradle sync, then press Run.

GIF search is off unless you add a Giphy API key to `local.properties`:
`GIPHY_API_KEY=your_key`.

Or with the Android SDK installed: `./gradlew assembleDebug`. The APK lands in
`app/build/outputs/apk/debug/`.

Needs Android 8.0 (API 26) or newer.

## Test on a phone

With the phone plugged in over USB and USB debugging on:

```
scripts/test-on-device.sh
```

It builds, installs, makes Messages the default SMS app, grants permissions, opens the
app, prints a test checklist and shows any crashes live. With several devices connected,
pass the serial from `adb devices`.

**Xiaomi / Redmi / POCO (MIUI and HyperOS)** need a few extra switches:

- Developer options > **Install via USB**: on (asks for a Mi account and a SIM)
- Developer options > **USB debugging (Security settings)**: on, so adb can grant permissions
- App info > Messages > **Autostart**: on, or scheduled texts won't fire after a reboot
- App info > Messages > Battery saver > **No restrictions**, so scheduled texts go out on time
- App info > Messages > Other permissions: allow **Send MMS** and **Notification SMS** if listed

## Code

| File | Job |
| --- | --- |
| `MessageReader.java` | Reads SMS and MMS: conversations, threads, search, group members, names |
| `SmsWriter.java` | Sends texts and saves, marks read or deletes messages |
| `Sender.java` | Picks SMS or MMS for each outgoing message |
| `MmsPdu.java` | Reads and writes the binary MMS format (plain Java) |
| `Mms.java` | Sends and downloads MMS through Android's MMS service, saves them |
| `MmsReceiver.java`, `MmsResultReceiver.java` | "You have an MMS" notices; sent and downloaded results |
| `MmsFileProvider.java` | Shares MMS data with the phone service, and files with other apps |
| `MediaPrep.java` | Reads picked files, shrinks pictures to the size limit |
| `ImageLoader.java` | Loads pictures, GIFs and video frames into the chat |
| `VoiceRecorder.java` | Records voice memos |
| `Reactions.java` | Sends tapback texts and turns received ones into emoji |
| `Scheduler.java`, `ScheduleReceiver.java`, `BootReceiver.java` | Scheduled texts |
| `ImageViewerActivity.java` | Full-screen pictures |
| `SmsReceiver.java` | Gets incoming SMS, saves them, shows the notification |
| `SentReceiver.java` | Marks a sent text as sent or failed |
| `NotifyReceiver.java` | Notification buttons: reply, mark read, call, delete, block, copy code, repeats |
| `DeliveryReceiver.java` | SMS delivery reports |
| `Incoming.java` | What happens when a message arrives: blocking, open chat, unknown senders, pop-up |
| `Popup.java`, `QuickReplyActivity.java`, `Screen.java` | Pop-up, Quick Reply, waking the screen |
| `Shortcuts.java` | Chat shortcuts for direct share, bubbles and "Add to home screen" |
| `RespondService.java` | Quick replies when declining a call |
| `Notifier.java` | Builds notifications and their per-setting channels; Quick Compose |
| `Vibes.java`, `Sounds.java`, `VibratePatternActivity.java` | Vibration patterns and sounds |
| `MainActivity.java` | Conversation list |
| `ThreadActivity.java` | One conversation and the reply box |
| `NewMessageActivity.java` | Pick one or more contacts, or type numbers |
| `SearchActivity.java` | Message search |
| `SettingsActivity.java` and `*SettingsActivity.java` | Settings home, look, notifications, sending |
| `SettingsScreen.java`, `Rows.java`, `Sections.java` | Settings pages built from rows; sections shared with per-chat settings |
| `ChatSettingsActivity.java`, `ChatPrefs.java` | A chat's own settings, falling back to the app's |
| `AttachPanel.java`, `CameraTile.java`, `EmojiPanel.java`, `EmojiData.java`, `GifPanel.java`, `Giphy.java` | The "+" panel |
| `VCard.java`, `Ics.java`, `Locations.java`, `EventDialog.java` | Contact cards, events, location |
| `LinkPreview.java`, `SmartReplies.java`, `Otp.java` | Web previews, suggested replies, one-time codes |
| `Sims.java` | Dual SIM |
| `Deleter.java`, `UndoBar.java` | Deletes with undo |
| `Keep.java` | The Keep chat |
| `MediaActivity.java`, `Gallery.java` | A chat's media, saving to the gallery |
| `SignaturesActivity.java`, `BlocklistActivity.java`, `BackupActivity.java`, `Backup.java`, `Cleanup.java` | Signatures, blocklist, backup, messages to keep |
| `AppDb.java` | The app's own database: drafts, signatures, Keep, previews, undo, log |
| `AppIcon.java` | App icon colors |
| `SwipeHelper.java` | Swipe-to-call and swipe-to-delete on list rows |
| `BaseActivity.java`, `Look.java` | Applies the chosen theme to every screen |
| `Prefs.java` | Saved settings with defaults, pins and blocked numbers |
| `DefaultSms.java` | Checks and requests the default SMS role |

## License

Copyright 2026 HK Apps

Licensed under the Apache License, Version 2.0. See [LICENSE](LICENSE) for the full text.
