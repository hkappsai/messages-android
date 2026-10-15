package co.hkapps.messages;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Finds one-time codes ("Your code is 482913") so they can be copied with one tap. */
final class Otp {

    private static final Pattern HINT = Pattern.compile(
            "(?i)(code|otp|pin|passcode|password|verification|verify|token|one[- ]time|login|sign[- ]in|2fa|kod|codigo|código)");
    // 4-8 digits, optionally split in the middle like "482 913" or "482-913". G- prefixes (Google) too.
    private static final Pattern CODE = Pattern.compile("(?<![\\d.,/:])(?:G-)?(\\d{3,4}[ -]?\\d{3,4}|\\d{4,8})(?![\\d.,/:%])");

    private Otp() {}

    /** The code in a message that looks like it carries one, digits only, or null. */
    static String find(String text) {
        if (text == null || text.isEmpty() || !HINT.matcher(text).find()) return null;
        Matcher m = CODE.matcher(text);
        while (m.find()) {
            String digits = m.group(1).replaceAll("[ -]", "");
            if (digits.length() < 4 || digits.length() > 8) continue;
            // Skip things that look like years in a date sentence.
            if (digits.length() == 4 && digits.startsWith("20") && text.matches("(?s).*\\b(19|20)\\d{2}\\b.*")
                    && !text.toLowerCase().contains("code")) continue;
            return digits;
        }
        return null;
    }
}
