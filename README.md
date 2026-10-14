# Messages

A small Android app that shows the SMS messages on your phone. It uses only the
Android framework: no AndroidX, no Kotlin, no third-party libraries.

## What it does

- Lists every SMS conversation, newest first, with contact name, last message and date
- Bolds conversations with unread messages
- Opens a conversation as chat bubbles (received on the left, sent on the right)
- Asks for SMS permission (and contacts, for names) on first launch

## Build

1. Open this folder in Android Studio.
2. Let Gradle sync, then press Run.

Or from the command line, with the Android SDK installed:

```
./gradlew assembleDebug
```

The APK lands in `app/build/outputs/apk/debug/`.

## Code

| File | Job |
| --- | --- |
| `SmsReader.java` | Queries the system SMS provider and looks up contact names |
| `MainActivity.java` | Conversation list and permission handling |
| `ThreadActivity.java` | Messages of one conversation |
| `Sms.java` | Plain data class for one message |
| `Format.java` | Date and avatar letter helpers |

Read-only: the app does not send, delete or receive SMS. Google Play only lets
default SMS apps use `READ_SMS`, so this build fits personal or sideloaded use.
