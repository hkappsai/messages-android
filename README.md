# Messages

A messaging app for Android with features similar to Textra: SMS, MMS, group chats,
photos, GIFs, voice memos, reactions and scheduled texts. It uses only the Android
framework: no AndroidX, no Kotlin, no third-party libraries. Even the MMS wire format
is read and written by the app's own code (`MmsPdu.java`).

## Features

**Conversations**
- Contact names with colored avatars, unread counts, and bold unread rows
- Pin conversations to the top (long-press a row)
- Swipe right to call, swipe left to delete
- Long-press menu: pin, mark as read, call, block, delete

**Chat**
- Sent and received bubbles, grouped with time stamps
- Pictures and animated GIFs in the chat; tap for full screen
- Voice memos: tap the mic, talk, tap send; play them back in the chat
- Videos and other files open in the app of your choice
- Reply box with a counter showing characters left and SMS parts
- "Sending…" and "Not sent" states; tap a failed message to send it again
- Long-press a message to react, copy, select part of the text, share or delete it
- Links, phone numbers and emails are tappable

**MMS and group chats**
- Attach photos, GIFs, camera shots or videos (several at once)
- Pictures shrink on their own to fit your carrier's MMS size limit
- Group chats with names over each sender's bubbles; hold a contact on the
  New message screen to add more people
- Picture messages that fail to download show a "Try again" notification

**Reactions**
- Long-press a message and pick ❤️ 👍 👎 😂 ‼️ ❓
- Sent as text like iPhones do (Loved “See you at 7”), so every phone can read them
- Reactions from iPhones and Google Messages show as emoji under the message
  instead of separate texts

**Scheduled texts**
- Type a message, hold the send button, pick a date and time
- Scheduled texts show faded in the chat; tap one to send now, edit or delete it
- They survive a reboot

**Notifications**
- New-message notifications with inline Reply and Mark as read
- No notification for the chat already open on screen
- Quick text replies when declining a call

**Search and blocking**
- Search the text of every message; matches are highlighted
- Block a number: its chat is hidden and it can't notify you. Messages are kept
  and come back when you unblock in Settings.

**Themes**
- Follow system, Light, Dark or Black (for OLED screens)
- 12 accent colors, plus a separate color for sent bubbles, with a live preview

## Default SMS app

Android lets only the default SMS app send texts, receive new ones, mark them read
or delete them. The app shows a banner until you set it as default. Reading old
messages works either way.

**Not supported:** GIF search (it needs an outside service such as Giphy, which goes
against the no-third-party rule; pick GIFs from your gallery instead), and
scheduling texts with attachments.

**Group chats:** to leave your own number out of a group, the app reads it from the SIM.
Some carriers don't store it there; then your number may show up in the group list.

## Build

1. Open this folder in Android Studio.
2. Let Gradle sync, then press Run.

Or with the Android SDK installed: `./gradlew assembleDebug`. The APK lands in
`app/build/outputs/apk/debug/`.

Needs Android 8.0 (API 26) or newer.

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
| `ReplyReceiver.java` | Notification Reply and Mark as read buttons |
| `RespondService.java` | Quick replies when declining a call |
| `Notifier.java` | Builds notifications |
| `MainActivity.java` | Conversation list |
| `ThreadActivity.java` | One conversation and the reply box |
| `NewMessageActivity.java` | Pick one or more contacts, or type numbers |
| `SearchActivity.java` | Message search |
| `SettingsActivity.java` | Default app, theme, colors, notifications, blocked numbers |
| `SwipeHelper.java` | Swipe-to-call and swipe-to-delete on list rows |
| `BaseActivity.java`, `Look.java` | Applies the chosen theme to every screen |
| `Prefs.java` | Saved settings, pins and blocked numbers |
| `DefaultSms.java` | Checks and requests the default SMS role |
