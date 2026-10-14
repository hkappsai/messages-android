# Messages

A small SMS app for Android with features similar to Textra. It uses only the
Android framework: no AndroidX, no Kotlin, no third-party libraries.

## Features

**Conversations**
- Contact names with colored avatars, unread counts, and bold unread rows
- Pin conversations to the top (long-press a row)
- Swipe right to call, swipe left to delete
- Long-press menu: pin, mark as read, call, block, delete

**Chat**
- Sent and received bubbles, grouped with time stamps
- Reply box with a counter showing characters left and SMS parts
- "Sending…" and "Not sent" states; tap a failed message to send it again
- Long-press a message to copy, select part of the text, share or delete it
- Links, phone numbers and emails are tappable

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

**Not supported yet:** MMS (pictures and group chats), scheduled texts. While Messages
is the default app, incoming MMS only shows a notice.

## Build

1. Open this folder in Android Studio.
2. Let Gradle sync, then press Run.

Or with the Android SDK installed: `./gradlew assembleDebug`. The APK lands in
`app/build/outputs/apk/debug/`.

Needs Android 8.0 (API 26) or newer.

## Code

| File | Job |
| --- | --- |
| `SmsReader.java` | Reads conversations, threads and search results; looks up contact names |
| `SmsWriter.java` | Sends texts and saves, marks read or deletes them |
| `SmsReceiver.java` | Gets incoming texts, saves them, shows the notification |
| `SentReceiver.java` | Marks a sent text as sent or failed |
| `ReplyReceiver.java` | Notification Reply and Mark as read buttons |
| `RespondService.java` | Quick replies when declining a call |
| `MmsReceiver.java` | MMS notice (needed for the default SMS role) |
| `Notifier.java` | Builds notifications |
| `MainActivity.java` | Conversation list |
| `ThreadActivity.java` | One conversation and the reply box |
| `NewMessageActivity.java` | Pick a contact or type a number |
| `SearchActivity.java` | Message search |
| `SettingsActivity.java` | Default app, theme, colors, notifications, blocked numbers |
| `SwipeHelper.java` | Swipe-to-call and swipe-to-delete on list rows |
| `BaseActivity.java`, `Look.java` | Applies the chosen theme to every screen |
| `Prefs.java` | Saved settings, pins and blocked numbers |
| `DefaultSms.java` | Checks and requests the default SMS role |
