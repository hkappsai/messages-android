# Roadmap: Textra feature parity

Source of truth for the comparison: Textra 4.87 (versionCode 48761), pulled from a test phone and
decompiled with jadx/apktool. Textra's code is obfuscated, so references below point at what survives:
string resource names, preference keys (`com.mplus.lib.qp.p` per-chat prefs, `service/Mgrs` global
prefs) and the few unobfuscated class names (`com.mplus.lib.ui.*`).

Ground rules stay the same as the rest of the app: Android framework only, no AndroidX, no
third-party libraries. Two features need the network (`INTERNET` permission): web link previews and
GIF search. GIF search uses the Giphy web API through `HttpURLConnection` and only appears when a
`GIPHY_API_KEY` is set in `local.properties`.

Textra settings are layered: every notification and sending setting has a global value and an
optional per-chat override (`BasePerContactPreferenceActivity`). We do the same with `ChatPrefs`.

Status: ✅ built · ⬜ not started · ➖ won't do (reason given)

Checked on a Xiaomi M2101K6I (Android 12) without being the default SMS app:
chat list, chat screen, tap menu with on-device date detection, "+" panel (recent photos, camera
capture, emoji), Keep with a web link preview, per-chat Customize, Settings, Customize look,
Sending settings, New message tabs. Not yet checked on a phone: anything that needs the default
SMS role (sending, delivery reports, receiving, notifications, pop-up, Quick Reply, bubbles,
undo delete, lock, backup restore of messages), and GIF search (needs a Giphy key).

## Phase 1: core messaging

| Feature | Textra reference | Our implementation | Status |
| --- | --- | --- | --- |
| Delivery reports, SMS | `smsDeliveryReceipts`, `settings_default_receipts_*`, `message_list_delivery_indicator_*` | `deliveryIntent` → `DeliveryReceiver` writes `Sms.STATUS`; "Delivered" under bubble | ✅ |
| Delivery reports, MMS | `mmsDeliveryReceipts` | `X-Mms-Delivery-Report: yes` in send-req, keep `m_id` from send-conf, `m-delivery-ind` push updates `Mms.STATUS` | ✅ |
| Dual SIM: pick SIM per message / per chat | `knownSims`, `message_details_sim_slot` | `Sims` (SubscriptionManager), SIM button in compose bar, per-chat default | ✅ |
| Multi-select messages and chats | `message_list_cab_multi_select`, `convolist_cab_*` | `CHOICE_MODE_MULTIPLE_MODAL` action modes on both lists | ✅ |
| Mute a chat / notifications on-off | `convo_muted_toast`, `convolist_cab_notifications_on/off` | `ChatPrefs.muted` | ✅ |
| Mark as unread | `wearable_action_label_mark_as_unread`, `define_actions_button_summary_mark_as_unread` | set `read=0` on newest incoming message | ✅ |
| Drafts | `message_list_draft` | saved per chat in `AppDb`, shown as "Draft:" in the list | ✅ |
| Message details | `message_details_*` | dialog: type, SIM, sent / delivered / received times, size | ✅ |
| Share files into the app | `IntegrationActivity`, `shortcuts.xml` share targets | `SEND` / `SEND_MULTIPLE` for image, video, audio, vCard, calendar | ✅ |
| Undo delete (20 s) | `undo_delete_hint`, `service/undo/PurgeWorker` | `UndoBar` + pending deletes in `AppDb`, purged on timeout or next start | ✅ |
| Forward | `message_list_cab_forward` | pick people, prefill text and attachments | ✅ |
| Retry / send as new | `message_list_cab_retry_send2`, `message_list_cab_send_again` | message menu | ✅ |
| Lock messages | `message_list_cab_lock`, `bot_tip10_toUser` | provider `locked` column; deletes skip locked rows | ✅ |
| Your mobile number | `settings_your_phone_number_*` | setting added to `Mms.selfNumbers` | ✅ |
| Group send: Chat (MMS) or Bulk (SMS each) | `sendGroupMessageAsMms`, `settings_send_group_message_*` | `Sender` | ✅ |
| Convert long SMS to MMS | `convert_sms_to_mms_*` | `Sender`, threshold 1/2/3 parts | ✅ |
| Split long SMS | `splitLongSmsMessages` | send parts as separate texts | ✅ |
| Strip accents | `removeDiacritics` | `Normalizer`, never / always / only when fewer parts | ✅ |
| Send with Enter, close keyboard after sending | `sendUsingEnter`, `closeKeyboardAfterSending` | compose box | ✅ |
| Send delay 0–9 s | `sendDelay` | countdown bubble, tap to cancel | ✅ |
| Signatures | `enableSignatureInSendArea`, `signatureId`, `ChooseSignatureActivity` | `AppDb.signatures`, global and per chat | ✅ |
| Quick voice memos | `enableVoiceMemos` | mic-as-send when empty becomes a setting | ✅ |
| SMS character counter | `showSmsCharacterCounter` | setting | ✅ |
| Scheduling presets, schedule with attachments | `sendarea_scheduledetails_preset_*` | preset sheet + attachments copied to app files | ✅ |
| Mobile contacts only, Recents / Starred / Pinned | `addMobileOnly`, `new_message_contact_kinds_*` | tabs in New message | ✅ |
| Contact groups as recipients | `pickrecipients_tab_content_groups` | groups tab | ✅ |

## Phase 2: notifications

| Feature | Textra reference | Our implementation | Status |
| --- | --- | --- | --- |
| MessagingStyle notifications (Wear, Auto, conversations) | `wearable_action_*` | `Notification.MessagingStyle`, semantic reply / mark-read actions, `automotive_app_desc` | ✅ |
| Sound, vibrate pattern, LED per chat | `notificationstyle_prompt_ringtone`, `vibrateBehaviour`, `notificationstyle_prompt_ledblinkcolor_*` | one channel per chat setting combo (channels can't change after creation) | ✅ |
| Custom vibrate patterns | `MakeVibratePatternActivity`, `ChooseVibratePatternActivity` | tap-to-record screen, stored in `AppDb` | ✅ |
| Repeat notifications | `notificationRepeatCount`, `notificationstyle_combine` | `AlarmManager` re-alert every 2 min | ✅ |
| Privacy: show contact / show text | `showContact`, `showMessageText` | builds a redacted public version too | ✅ |
| Wake screen | `wakeUpLockScreen` | `PowerManager` wake lock, never / always / except silent-DND | ✅ |
| Heads-up style when unlocked / locked | `headsupStyle`, `headsupStyleWhenLocked` | Android / app pop-up / none | ✅ |
| Pop-up (floatification) with duration | `unlockModeFloatification`, `lockModeFloatification`, `floatification_*` | overlay window (`SYSTEM_ALERT_WINDOW`) unlocked; full-screen intent when locked | ✅ |
| Quick Reply | `QuickConvoActivity`, `tapNotification`, `autoCloseQuickReply` | `QuickReplyActivity` dialog | ✅ |
| Configurable buttons (3) | `androidButton1-3`, `textraButton2-3`, `DefineActionsActivity` | reply, quick reply, mark read / unread, call, delete last, block, open | ✅ |
| Clearing marks as read | `markAsRead` | `deleteIntent` | ✅ |
| In-chat sound / vibrate | `inConvoPlayRingtone`, `inConvoPlayVibrate` | play when a text lands in the open chat | ✅ |
| Unknown-sender notifications | `settings_unknown_contacts_*`, `notification_channel_incoming_messages_silent` | normal / silent / none | ✅ |
| Badge count | `badgeAppIcon` | `setNumber` + channel badge flag | ✅ |
| Quick Compose | `quickCompose`, `notification_channel_quick_compose` | ongoing notification with inline reply to a picked contact | ✅ |
| Conversation bubbles | `ui/bubble/BubbleActivity` | `BubbleMetadata` + dynamic shortcuts | ✅ |
| Copy one-time code | `wearable_action_copy_onetimecode` | `Otp` detection, action button | ✅ |
| Failed-send notification | `notify_cant_send_sms_*` | tap to resend | ✅ |
| Play sent sound | `playSentSound` | `SoundPool` / tone | ✅ |
| Pebble, Pushbullet | `notifyPebble`, `pushbullet.android.extension` | ➖ dead hardware / needs their library | ➖ |

## Phase 3: chat tools and attachments

| Feature | Textra reference | Our implementation | Status |
| --- | --- | --- | --- |
| Attach panel (plus panel) | `FragmentActivityWithPlusPanel`, `pluspanel_*`, `bot_tip6/7/9` | bottom panel: recent photos, camera, gallery, video, voice, contact, event, location, GIF, emoji, schedule | ✅ |
| Recent photos grid, long-press = latest photo | `pluspanel_gallery_choose_*` | `MediaStore` query | ✅ |
| Inline camera preview | `plus/camera/CameraPreview` | Camera2 `TextureView` tile, long-press opens camera app | ✅ |
| Emoji panel with recents and skin tone | `EmojisView`, `pluspanel_emoji_group_*`, `emojiST`, `recentlyUsedEmojis` | `EmojiPanel` with built-in emoji list | ✅ |
| Emoji styles (iOS, Twitter, Blob, JoyPixels) | `settings_emojistyle_*` | ➖ needs downloaded font packs from a server | ➖ |
| GIF search | `plus/giphy/*`, `giphyRecents` | `Giphy` over `HttpURLConnection`, trending / search / recents | ✅ |
| Share contacts as vCard or text | `pickcontacts_*` | `VCard` from `Contacts.CONTENT_VCARD_URI` | ✅ |
| Calendar events in and out | `mms_n_calendar_*`, `message_list_cab_add_event` | `Ics` build / parse, `CalendarContract` insert intent | ✅ |
| Send location | `message_list_cab_map` | last known location as a maps link | ✅ |
| Chat media gallery | `ConvoMediaActivity`, `gallery_*` | grid of every picture / video in the chat | ✅ |
| Viewer: swipe, save, share, details | `GalleryActivity`, `media_details_*` | `ImageViewerActivity` upgrade | ✅ |
| Auto-save received pictures | `savePicsToGallery` | `MediaStore` insert on download | ✅ |
| Save storage space | `saveStorageSpace` | keep compressed vs original copy | ✅ |
| Carrier size limit | `mmsSendSizeLimit`, `mms_network_settings_*` | setting overrides carrier value | ✅ |
| Wi-Fi fix | `mms_network_settings_apn_settings_deactivate_wifi2` | ➖ Android 10+ blocks apps from turning Wi-Fi off; MMS service already forces mobile data | ➖ |
| Video warning | `stagefright*`, `confirm_view_video` | confirm before playing received video | ✅ |
| Hidden timestamps by dragging | `bot_tip1_toUser` | drag the list left to reveal every bubble's time | ✅ |
| Tap a bubble for quick options | `bot_tip2_toUser` | tap = quick menu, long-press = select | ✅ |
| Web link previews | `settings_url_prerender_*`, `service/prerender` | `LinkPreview` (og tags), cache in `AppDb` | ✅ |
| Smart Reply | `smartReply` | `TextClassifier.suggestConversationActions` (on-device, API 29+) | ✅ |
| Report as spam | `message_list_cab_report_as_spam` | forward to 7726 after confirm | ✅ |
| Email, share chat as email | `message_list_cab_email`, `message_list_cab_share_as_email`, `include_media_dialog_*` | `ACTION_SEND` with transcript | ✅ |
| Map, Add event, Call, Message from text | `message_list_cab_map/add_event/call/message` | detected addresses / dates / numbers | ✅ |
| Add chat to home screen | `message_list_cab_add_to_home_screen` | pinned shortcut | ✅ |
| Keep (notes to self) chat | `chat_name_of_keep`, `keep_convo_hint` | local chat in `AppDb` | ✅ |
| Search: chats too, Locked / Scheduled tabs | `search_scope_*`, `search_hint` | `SearchActivity` | ✅ |
| Rename group | `groupDisplayName`, `groupname_edit_dialog_title` | `ChatPrefs.name` | ✅ |
| People in chat | `convo_settings_people_in_conversation_category` | chat settings screen | ✅ |
| Reaction details | `tapback_details_*` | who reacted, newest first | ✅ |

## Phase 4: look, storage, system

| Feature | Textra reference | Our implementation | Status |
| --- | --- | --- | --- |
| Settings layout like Textra | `settings_*_category` | General / Customize / Notifications / Messaging / More | ✅ |
| Per-chat Customize screen | `SettingsPerContactActivity` | `ChatSettingsActivity` | ✅ |
| Theme color per chat, auto-theme contacts | `settings_convo_theme_color`, `autoColorContacts` | `Look.forChat` | ✅ |
| Light & night schedule | `darkThemeTri`, `settings_dark_theme_light_and_auto_dark` | time window | ✅ |
| Bubble style and colors | `bubbleStyle`, `bubblestyle_*` | shapes + sent / received bubble and text colors | ✅ |
| Font and text size | `font`, `textSize` | system / serif / mono / condensed; 4 sizes | ✅ |
| App icon color | `settings_app_icon_color_*` | launcher `activity-alias` per color | ✅ |
| Emoji skin tone | `emojiST` | default tone | ✅ |
| Blocklist screen | `BlacklistedActivity`, `blacklist_*`, `block_dialog_title` | last message, view chat, add number, groups too | ✅ |
| Auto-block unknown email senders | `settings_auto_blocklist_unknown_email_contacts` | sender looks like an email and isn't a contact | ✅ |
| Messages to keep | `messageLimit`, `settings_message_limit_*`, `service/cleanup` | global + per chat, `JobScheduler` daily, Free space now | ✅ |
| Settings backup | `service/backup/TextraBackupAgent` | Android backup of settings + export / import file | ✅ |
| Direct share and launcher shortcut | `shortcuts.xml`, `app_shortcut_new_message_short` | dynamic + static shortcuts | ✅ |
| Debug log | `enableMsglog`, `settings_email_debug_log_*` | ring log in `AppDb`, share | ✅ |
| Mark all as read in settings | `settings_mark_all_as_read_*` | settings row | ✅ |
| About, What's new | `SettingsAboutActivity`, `ChangeLogActivity` | about screen | ✅ |
| Resync database | `settings_resync_database_*` | ➖ Textra keeps its own copy of messages; we read Android's store directly, so nothing to resync | ➖ |
| Ads, Pro, UserVoice support, bot | `settings_manage_ads_*`, `settings_support_*`, `bot_*` | ➖ business features, not messaging | ➖ |

## Build order

1. Foundation: `AppDb` (SQLite), `ChatPrefs`, settings row builder, permissions (`READ_PHONE_STATE`,
   `READ_PHONE_NUMBERS`, `INTERNET`, `VIBRATE`, `WAKE_LOCK`, `SYSTEM_ALERT_WINDOW`,
   `USE_FULL_SCREEN_INTENT`, `CALL_PHONE`, `READ_MEDIA_*`, `READ_CALENDAR`, `ACCESS_COARSE_LOCATION`).
2. Phase 1, then build + device test.
3. Phase 2, then build + device test.
4. Phase 3, then build + device test.
5. Phase 4, then build + device test, README update.
