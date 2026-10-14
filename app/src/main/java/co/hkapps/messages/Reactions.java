package co.hkapps.messages;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tapback reactions. Phones without iMessage receive them as plain texts like
 * Loved “See you at 7”, so that is what this app sends, and it folds such texts
 * back into an emoji under the message they point at.
 */
public final class Reactions {

    /** Emoji, iPhone verb, noun used in "Removed a … from". */
    static final String[][] KINDS = {
            {"❤️", "Loved", "heart"},
            {"👍", "Liked", "like"},
            {"👎", "Disliked", "dislike"},
            {"😂", "Laughed at", "laugh"},
            {"‼️", "Emphasized", "exclamation"},
            {"❓", "Questioned", "question mark"},
    };

    private static final String QUOTED = "[“\"](.+?)(?:…|\\.\\.\\.)?[”\"]";
    private static final Pattern ADD = Pattern.compile(
            "^(Loved|Liked|Disliked|Laughed at|Emphasized|Questioned) " + QUOTED + "$", Pattern.DOTALL);
    private static final Pattern REMOVE = Pattern.compile(
            "^Removed an? (heart|like|dislike|laugh|exclamation|question mark|exclamation mark) from "
                    + QUOTED + "$", Pattern.DOTALL);
    // Google Messages writes reactions as: Reacted 😂 to “…”
    private static final Pattern ADD_EMOJI = Pattern.compile("^Reacted (\\S+) to " + QUOTED + "$", Pattern.DOTALL);
    private static final Pattern REMOVE_EMOJI = Pattern.compile("^Removed (\\S+) from " + QUOTED + "$", Pattern.DOTALL);

    // Long messages get cut in the quote; match on the start.
    private static final int QUOTE_LIMIT = 80;

    public static final class Entry {
        public final String emoji;
        public final boolean mine;
        final String from;

        Entry(String emoji, boolean mine, String from) {
            this.emoji = emoji;
            this.mine = mine;
            this.from = from;
        }
    }

    private Reactions() {}

    /** The text to send for a reaction (or for taking one back). */
    static String compose(String emoji, String targetBody, boolean remove) {
        String quote = targetBody.length() > QUOTE_LIMIT
                ? targetBody.substring(0, QUOTE_LIMIT).trim() + "…" : targetBody;
        for (String[] k : KINDS) {
            if (k[0].equals(emoji)) {
                if (remove) {
                    String article = k[2].startsWith("e") ? "an " : "a ";
                    return "Removed " + article + k[2] + " from “" + quote + "”";
                }
                return k[1] + " “" + quote + "”";
            }
        }
        return (remove ? "Removed " + emoji + " from “" : "Reacted " + emoji + " to “") + quote + "”";
    }

    /**
     * Turns reaction texts into emoji on their target messages and returns the list without
     * the reaction texts. Messages must be oldest first.
     */
    static List<Message> apply(List<Message> messages) {
        List<Message> visible = new ArrayList<>();
        for (Message m : messages) {
            Parsed p = parse(m.body);
            Message target = p == null || !m.parts.isEmpty() ? null : findTarget(visible, p.quote);
            if (target == null) {
                visible.add(m);
                continue;
            }
            boolean mine = !m.incoming();
            String from = mine ? "" : m.address;
            // One reaction per person and message, like iMessage: a new one replaces the old.
            Iterator<Entry> it = target.reactions.iterator();
            while (it.hasNext()) {
                Entry e = it.next();
                boolean samePerson = e.mine == mine && (mine || Prefs.sameAddress(e.from, from));
                if (samePerson && (!p.remove || e.emoji.equals(p.emoji))) it.remove();
            }
            if (!p.remove) target.reactions.add(new Entry(p.emoji, mine, from));
        }
        return visible;
    }

    /** The emoji I've put on a message, or null. */
    static String mine(Message m) {
        for (Entry e : m.reactions) {
            if (e.mine) return e.emoji;
        }
        return null;
    }

    private static final class Parsed {
        String emoji;
        String quote;
        boolean remove;
    }

    static boolean isReaction(String body) {
        return parse(body) != null;
    }

    private static Parsed parse(String body) {
        if (body == null || body.length() < 6) return null;
        Parsed p = new Parsed();
        Matcher m = ADD.matcher(body);
        if (m.matches()) {
            p.emoji = emojiForVerb(m.group(1));
            p.quote = m.group(2);
            return p;
        }
        m = REMOVE.matcher(body);
        if (m.matches()) {
            p.emoji = emojiForNoun(m.group(1));
            p.quote = m.group(2);
            p.remove = true;
            return p;
        }
        m = ADD_EMOJI.matcher(body);
        if (m.matches()) {
            p.emoji = m.group(1);
            p.quote = m.group(2);
            return p;
        }
        m = REMOVE_EMOJI.matcher(body);
        if (m.matches()) {
            p.emoji = m.group(1);
            p.quote = m.group(2);
            p.remove = true;
            return p;
        }
        return null;
    }

    private static Message findTarget(List<Message> earlier, String quote) {
        String q = quote.trim();
        for (int i = earlier.size() - 1; i >= 0; i--) {
            String body = earlier.get(i).body.trim();
            if (body.isEmpty()) continue;
            if (body.equals(q) || (q.length() >= 10 && body.startsWith(q))) return earlier.get(i);
        }
        return null;
    }

    private static String emojiForVerb(String verb) {
        for (String[] k : KINDS) if (k[1].equals(verb)) return k[0];
        return "❤️";
    }

    private static String emojiForNoun(String noun) {
        if (noun.equals("exclamation mark")) noun = "exclamation";
        for (String[] k : KINDS) if (k[2].equals(noun)) return k[0];
        return "❤️";
    }

    /** "❤️ 2  👍" for the chip under a bubble. */
    static String summary(List<Entry> entries) {
        List<String> order = new ArrayList<>();
        List<Integer> counts = new ArrayList<>();
        for (Entry e : entries) {
            int i = order.indexOf(e.emoji);
            if (i < 0) {
                order.add(e.emoji);
                counts.add(1);
            } else {
                counts.set(i, counts.get(i) + 1);
            }
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < order.size(); i++) {
            if (sb.length() > 0) sb.append("  ");
            sb.append(order.get(i));
            if (counts.get(i) > 1) sb.append(' ').append(counts.get(i));
        }
        return sb.toString();
    }
}
