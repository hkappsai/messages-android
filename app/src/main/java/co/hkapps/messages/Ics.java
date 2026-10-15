package co.hkapps.messages;

import android.content.Intent;
import android.provider.CalendarContract;

import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/** Calendar events in messages (.ics): make one to send, and add received ones to the calendar. */
final class Ics {

    static final class Event {
        String title;
        long start;
        long end;
        boolean allDay;
        String location;
        String description;
    }

    private Ics() {}

    static byte[] build(Event e) {
        SimpleDateFormat utc = new SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.US);
        utc.setTimeZone(TimeZone.getTimeZone("UTC"));
        SimpleDateFormat day = new SimpleDateFormat("yyyyMMdd", Locale.US);
        StringBuilder sb = new StringBuilder();
        sb.append("BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//HK Apps//Messages//EN\r\nBEGIN:VEVENT\r\n");
        sb.append("UID:").append(System.currentTimeMillis()).append("@messages.hkapps.co\r\n");
        sb.append("DTSTAMP:").append(utc.format(new Date())).append("\r\n");
        if (e.allDay) {
            sb.append("DTSTART;VALUE=DATE:").append(day.format(new Date(e.start))).append("\r\n");
            sb.append("DTEND;VALUE=DATE:").append(day.format(new Date(e.end))).append("\r\n");
        } else {
            sb.append("DTSTART:").append(utc.format(new Date(e.start))).append("\r\n");
            sb.append("DTEND:").append(utc.format(new Date(e.end))).append("\r\n");
        }
        sb.append("SUMMARY:").append(escape(e.title)).append("\r\n");
        if (e.location != null && !e.location.isEmpty()) sb.append("LOCATION:").append(escape(e.location)).append("\r\n");
        if (e.description != null && !e.description.isEmpty()) {
            sb.append("DESCRIPTION:").append(escape(e.description)).append("\r\n");
        }
        sb.append("END:VEVENT\r\nEND:VCALENDAR\r\n");
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    static Event parse(byte[] data) {
        Event e = new Event();
        String text = new String(data, StandardCharsets.UTF_8).replace("\r\n ", "").replace("\n ", "");
        boolean inEvent = false;
        for (String line : text.split("\r?\n")) {
            if (line.startsWith("BEGIN:VEVENT")) inEvent = true;
            if (!inEvent) continue;
            int colon = line.indexOf(':');
            if (colon < 0) continue;
            String key = line.substring(0, colon).toUpperCase(Locale.ROOT);
            String value = unescape(line.substring(colon + 1).trim());
            String name = key.contains(";") ? key.substring(0, key.indexOf(';')) : key;
            switch (name) {
                case "SUMMARY": e.title = value; break;
                case "LOCATION": e.location = value; break;
                case "DESCRIPTION": e.description = value; break;
                case "DTSTART":
                    e.allDay = value.length() == 8;
                    e.start = time(value, key);
                    break;
                case "DTEND": e.end = time(value, key); break;
                default: break;
            }
            if (line.startsWith("END:VEVENT")) break;
        }
        if (e.end == 0 && e.start != 0) e.end = e.start + (e.allDay ? 24 * 3600_000L : 3600_000L);
        return e;
    }

    static Intent insertIntent(Event e) {
        Intent i = new Intent(Intent.ACTION_INSERT).setData(CalendarContract.Events.CONTENT_URI);
        if (e.title != null) i.putExtra(CalendarContract.Events.TITLE, e.title);
        if (e.location != null) i.putExtra(CalendarContract.Events.EVENT_LOCATION, e.location);
        if (e.description != null) i.putExtra(CalendarContract.Events.DESCRIPTION, e.description);
        if (e.start != 0) i.putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, e.start);
        if (e.end != 0) i.putExtra(CalendarContract.EXTRA_EVENT_END_TIME, e.end);
        i.putExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, e.allDay);
        return i;
    }

    private static long time(String value, String key) {
        try {
            if (value.length() == 8) {
                SimpleDateFormat f = new SimpleDateFormat("yyyyMMdd", Locale.US);
                return f.parse(value).getTime();
            }
            SimpleDateFormat f = new SimpleDateFormat("yyyyMMdd'T'HHmmss", Locale.US);
            if (value.endsWith("Z")) {
                f.setTimeZone(TimeZone.getTimeZone("UTC"));
                value = value.substring(0, value.length() - 1);
            } else if (key.contains("TZID=")) {
                String tz = key.substring(key.indexOf("TZID=") + 5);
                if (tz.contains(";")) tz = tz.substring(0, tz.indexOf(';'));
                f.setTimeZone(TimeZone.getTimeZone(tz));
            }
            Date d = f.parse(value);
            return d == null ? 0 : d.getTime();
        } catch (ParseException | RuntimeException ex) {
            return 0;
        }
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace(";", "\\;").replace(",", "\\,").replace("\n", "\\n");
    }

    private static String unescape(String s) {
        return s.replace("\\n", "\n").replace("\\N", "\n").replace("\\,", ",").replace("\\;", ";").replace("\\\\", "\\");
    }
}
