package co.hkapps.messages;

import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * Textra-style Quick Reply: a small window over whatever is on screen (even the lock screen)
 * with the new messages, smart replies and a reply box.
 */
public class QuickReplyActivity extends BaseActivity {

    static final String EXTRA_KEYBOARD = "keyboard";

    private long threadId;
    private List<String> recipients = new ArrayList<>();
    private LinearLayout messagesBox;
    private LinearLayout suggestions;
    private EditText input;
    private TextView title;

    static Intent intent(Context c, long threadId, boolean keyboard) {
        return new Intent(c, QuickReplyActivity.class)
                .setData(Uri.parse("quickreply:" + threadId))
                .putExtra(ThreadActivity.EXTRA_THREAD_ID, threadId)
                .putExtra(EXTRA_KEYBOARD, keyboard)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
    }

    @Override
    protected int themeRes() {
        return R.style.QuickReply;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        }
        threadId = getIntent().getLongExtra(ThreadActivity.EXTRA_THREAD_ID, -1);
        boolean keyboard = getIntent().getBooleanExtra(EXTRA_KEYBOARD, true);
        getWindow().setSoftInputMode(keyboard ? WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE
                | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                : WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);

        int pad = (int) Format.dp(this, 16);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(Look.rounded(look.dark ? (look.black ? 0xFF121212 : 0xFF2A2B2E) : 0xFFFFFFFF, Format.dp(this, 24)));
        root.setPadding(pad, pad, pad, pad / 2);

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        title = new TextView(this);
        title.setTextSize(18);
        title.setTypeface(null, Typeface.BOLD);
        title.setTextColor(look.dark ? 0xFFE8EAED : 0xFF202124);
        header.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        header.addView(iconButton(R.drawable.ic_call, "Call", v -> call()));
        header.addView(iconButton(R.drawable.ic_delete, "Delete last message", v -> deleteLast()));
        header.addView(iconButton(R.drawable.ic_message, "Open app", v -> openApp()));
        header.addView(iconButton(R.drawable.ic_close, "Close", v -> finish()));
        root.addView(header);

        ScrollView scroll = new ScrollView(this);
        messagesBox = new LinearLayout(this);
        messagesBox.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(messagesBox);
        LinearLayout.LayoutParams scrollLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1);
        root.addView(scroll, scrollLp);

        HorizontalScrollView suggestScroll = new HorizontalScrollView(this);
        suggestScroll.setHorizontalScrollBarEnabled(false);
        suggestions = new LinearLayout(this);
        suggestScroll.addView(suggestions);
        root.addView(suggestScroll);

        LinearLayout compose = new LinearLayout(this);
        compose.setGravity(Gravity.CENTER_VERTICAL);
        input = new EditText(this);
        input.setHint(R.string.type_message);
        input.setMaxLines(4);
        input.setInputType(EditorInfo.TYPE_CLASS_TEXT | EditorInfo.TYPE_TEXT_FLAG_CAP_SENTENCES
                | EditorInfo.TYPE_TEXT_FLAG_MULTI_LINE | EditorInfo.TYPE_TEXT_VARIATION_SHORT_MESSAGE);
        compose.addView(input, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        ImageButton send = new ImageButton(this);
        send.setImageResource(R.drawable.ic_send);
        send.setImageTintList(android.content.res.ColorStateList.valueOf(look.onAccent));
        send.setBackground(Look.circle(look.accent));
        send.setContentDescription(getString(R.string.send));
        send.setOnClickListener(v -> send());
        int s = (int) Format.dp(this, 44);
        LinearLayout.LayoutParams sendLp = new LinearLayout.LayoutParams(s, s);
        sendLp.setMarginStart(pad / 2);
        compose.addView(send, sendLp);
        root.addView(compose);

        LinearLayout outer = new LinearLayout(this);
        outer.setGravity(Gravity.CENTER);
        outer.setPadding(pad, pad * 3, pad, pad * 3);
        outer.setOnClickListener(v -> finish());
        root.setClickable(true);
        outer.addView(root, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(outer);
        load();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        threadId = intent.getLongExtra(ThreadActivity.EXTRA_THREAD_ID, threadId);
        load();
    }

    private ImageButton iconButton(int icon, String label, View.OnClickListener click) {
        ImageButton b = new ImageButton(this);
        b.setImageResource(icon);
        b.setContentDescription(label);
        b.setImageTintList(android.content.res.ColorStateList.valueOf(look.dark ? 0xFFBDC1C6 : 0xFF5F6368));
        android.util.TypedValue tv = new android.util.TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, tv, true);
        b.setBackgroundResource(tv.resourceId);
        b.setOnClickListener(click);
        int size = (int) Format.dp(this, 40);
        b.setLayoutParams(new LinearLayout.LayoutParams(size, size));
        return b;
    }

    private void load() {
        long id = threadId;
        executor.execute(() -> {
            MessageReader reader = new MessageReader(this);
            List<String> people = reader.recipientsOf(id);
            String name = ChatPrefs.name(this, id);
            if (name == null) name = reader.names(people);
            List<Message> all = Reactions.apply(reader.loadThread(id));
            // The unread messages, or at least the last three.
            List<Message> shown = new ArrayList<>();
            int start = all.size();
            while (start > 0 && all.get(start - 1).incoming() && !all.get(start - 1).read) start--;
            start = Math.min(start, Math.max(0, all.size() - 3));
            for (int i = Math.max(0, start); i < all.size(); i++) shown.add(all.get(i));
            List<String> smart = SmartReplies.suggest(this, id, all);
            String chatName = name;
            onUi(() -> {
                recipients = people;
                title.setText(chatName);
                showMessages(shown);
                showSuggestions(smart);
            });
        });
    }

    private void showMessages(List<Message> shown) {
        messagesBox.removeAllViews();
        int pad = (int) Format.dp(this, 10);
        for (Message m : shown) {
            TextView t = new TextView(this);
            String who = m.incoming() ? (m.senderName != null ? MessageReader.firstName(m.senderName) + ": " : "") : "";
            t.setText(who + m.preview());
            t.setTextSize(look.messageTextSize());
            t.setTypeface(look.typeface());
            t.setTextColor(m.incoming() ? look.bubbleInText : look.bubbleOutText);
            t.setBackground(look.bubble(this, m.incoming() ? look.bubbleIn : look.bubbleOut, m.incoming(), true));
            t.setPadding(pad + pad / 2, pad, pad + pad / 2, pad);
            t.setAutoLinkMask(android.text.util.Linkify.ALL);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.gravity = m.incoming() ? Gravity.START : Gravity.END;
            lp.topMargin = pad / 2;
            messagesBox.addView(t, lp);
        }
    }

    private void showSuggestions(List<String> smart) {
        suggestions.removeAllViews();
        for (String s : smart) {
            Button chip = new Button(this, null, android.R.attr.borderlessButtonStyle);
            chip.setAllCaps(false);
            chip.setText(s);
            chip.setTextColor(look.accent);
            chip.setOnClickListener(v -> {
                input.setText(s);
                send();
            });
            suggestions.addView(chip);
        }
    }

    private void send() {
        String text = input.getText().toString().trim();
        if (TextUtils.isEmpty(text) || recipients.isEmpty() || !requireDefault()) return;
        List<String> to = recipients;
        long id = threadId;
        Context app = getApplicationContext();
        input.setText("");
        Sounds.playSent(this);
        SmsWriter.run(() -> {
            Sender.send(app, to, text);
            SmsWriter.markRead(app, id);
            Notifier.cancel(app, id);
        });
        if (Prefs.bool(this, Prefs.AUTO_CLOSE_QR)) finish();
        else load();
    }

    private void call() {
        if (recipients.size() != 1) return;
        Notifier.cancel(this, threadId);
        startActivity(new Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", recipients.get(0), null)));
        finish();
    }

    private void deleteLast() {
        if (!requireDefault()) return;
        long id = threadId;
        Context app = getApplicationContext();
        SmsWriter.run(() -> {
            SmsWriter.deleteLatestIncoming(app, id);
            Notifier.cancel(app, id);
        });
        finish();
    }

    private void openApp() {
        startActivity(new Intent(this, ThreadActivity.class)
                .putExtra(ThreadActivity.EXTRA_THREAD_ID, threadId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        finish();
    }
}
