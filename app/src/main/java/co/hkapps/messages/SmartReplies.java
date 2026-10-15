package co.hkapps.messages;

import android.app.Person;
import android.content.Context;
import android.os.Build;
import android.view.textclassifier.ConversationAction;
import android.view.textclassifier.ConversationActions;
import android.view.textclassifier.TextClassificationManager;
import android.view.textclassifier.TextClassifier;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Suggested quick replies ("Sounds good", "On my way") from Android's on-device text
 * classifier. Nothing leaves the phone. Needs Android 10.
 */
final class SmartReplies {

    private static final Map<Long, CharSequence[]> CACHE = new ConcurrentHashMap<>();

    private SmartReplies() {}

    static boolean available() {
        return Build.VERSION.SDK_INT >= 29;
    }

    /** Last suggestions worked out for a chat, for notification reply choices. Never null. */
    static CharSequence[] cached(long threadId) {
        CharSequence[] c = CACHE.get(threadId);
        return c == null ? new CharSequence[0] : c;
    }

    /** Call off the main thread. messages: the chat, oldest first. */
    static List<String> suggest(Context context, long threadId, List<Message> messages) {
        if (!available() || !Prefs.bool(context, Prefs.SMART_REPLY) || messages.isEmpty()) return Collections.emptyList();
        Message last = messages.get(messages.size() - 1);
        if (!last.incoming() || last.body.isEmpty()) {
            CACHE.remove(threadId);
            return Collections.emptyList();
        }
        try {
            TextClassificationManager tcm = context.getSystemService(TextClassificationManager.class);
            TextClassifier classifier = tcm == null ? null : tcm.getTextClassifier();
            if (classifier == null) return Collections.emptyList();
            Person me = ConversationActions.Message.PERSON_USER_SELF;
            List<ConversationActions.Message> history = new ArrayList<>();
            int from = Math.max(0, messages.size() - 6);
            for (int i = from; i < messages.size(); i++) {
                Message m = messages.get(i);
                if (m.body.isEmpty() || m.scheduled()) continue;
                Person author = m.incoming() ? new Person.Builder().setName(m.address).setKey(m.address).build() : me;
                history.add(new ConversationActions.Message.Builder(author)
                        .setText(m.body)
                        .setReferenceTime(ZonedDateTime.ofInstant(Instant.ofEpochMilli(m.date), ZoneId.systemDefault()))
                        .build());
            }
            if (history.isEmpty()) return Collections.emptyList();
            ConversationActions.Request request = new ConversationActions.Request.Builder(history)
                    .setMaxSuggestions(3)
                    .setTypeConfig(new TextClassifier.EntityConfig.Builder()
                            .includeTypesFromTextClassifier(false)
                            .setIncludedTypes(Collections.singletonList(ConversationAction.TYPE_TEXT_REPLY))
                            .build())
                    .build();
            List<String> replies = new ArrayList<>();
            for (ConversationAction action : classifier.suggestConversationActions(request).getConversationActions()) {
                if (action.getTextReply() != null && action.getTextReply().length() > 0) {
                    replies.add(action.getTextReply().toString());
                }
            }
            CACHE.put(threadId, replies.toArray(new CharSequence[0]));
            return replies;
        } catch (RuntimeException e) {
            return Collections.emptyList();
        }
    }
}
