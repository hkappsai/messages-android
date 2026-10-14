package co.hkapps.messages;

import android.content.Context;
import android.text.format.DateUtils;

final class Format {
    private Format() {}

    /** "3:45 PM" for today, "Oct 12" for this year, "10/12/2024" for older. */
    static String shortDate(Context context, long millis) {
        if (DateUtils.isToday(millis)) {
            return DateUtils.formatDateTime(context, millis, DateUtils.FORMAT_SHOW_TIME);
        }
        long yearAgo = System.currentTimeMillis() - DateUtils.YEAR_IN_MILLIS;
        if (millis > yearAgo) {
            return DateUtils.formatDateTime(context, millis,
                    DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_ABBREV_MONTH | DateUtils.FORMAT_NO_YEAR);
        }
        return DateUtils.formatDateTime(context, millis,
                DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_NUMERIC_DATE | DateUtils.FORMAT_SHOW_YEAR);
    }

    /** Date plus time, used under each message bubble. */
    static String fullDate(Context context, long millis) {
        return DateUtils.formatDateTime(context, millis,
                DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_SHOW_TIME | DateUtils.FORMAT_ABBREV_MONTH);
    }

    /** First letter or digit of a name, for the round avatar. */
    static String initial(String name) {
        for (int i = 0; i < name.length(); i++) {
            char ch = name.charAt(i);
            if (Character.isLetterOrDigit(ch)) return String.valueOf(Character.toUpperCase(ch));
        }
        return "#";
    }
}
