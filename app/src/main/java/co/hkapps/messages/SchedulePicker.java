package co.hkapps.messages;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.text.format.DateFormat;
import android.text.format.DateUtils;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

/** "Send later": quick choices like "Tomorrow morning", or any date and time. */
final class SchedulePicker {

    interface OnPick {
        void picked(long time);
    }

    private SchedulePicker() {}

    static void show(Activity activity, OnPick pick) {
        List<String> labels = new ArrayList<>();
        List<Long> times = new ArrayList<>();
        Calendar now = Calendar.getInstance();

        add(labels, times, activity, "In 30 minutes", now.getTimeInMillis() + 30 * 60_000L);
        add(labels, times, activity, "In 1 hour", now.getTimeInMillis() + 60 * 60_000L);
        add(labels, times, activity, "In 3 hours", now.getTimeInMillis() + 3 * 60 * 60_000L);
        if (now.get(Calendar.HOUR_OF_DAY) < 19) add(labels, times, activity, "Later tonight", at(0, 20, 0));
        add(labels, times, activity, "Tomorrow morning", at(1, 8, 0));
        add(labels, times, activity, "Tomorrow afternoon", at(1, 14, 0));
        add(labels, times, activity, "Tomorrow evening", at(1, 19, 0));
        add(labels, times, activity, "Saturday morning", next(Calendar.SATURDAY, 9));
        add(labels, times, activity, "Sunday evening", next(Calendar.SUNDAY, 19));
        add(labels, times, activity, "Next Monday", next(Calendar.MONDAY, 8));
        labels.add("Pick a date & time…");

        new AlertDialog.Builder(activity)
                .setTitle("Send later")
                .setItems(labels.toArray(new String[0]), (d, which) -> {
                    if (which < times.size()) pick.picked(times.get(which));
                    else custom(activity, pick);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private static void add(List<String> labels, List<Long> times, Activity a, String label, long time) {
        labels.add(label + "  ·  " + DateUtils.formatDateTime(a, time,
                DateUtils.FORMAT_SHOW_TIME | DateUtils.FORMAT_SHOW_WEEKDAY | DateUtils.FORMAT_ABBREV_WEEKDAY));
        times.add(time);
    }

    private static long at(int daysAhead, int hour, int minute) {
        Calendar c = Calendar.getInstance();
        c.add(Calendar.DAY_OF_YEAR, daysAhead);
        c.set(Calendar.HOUR_OF_DAY, hour);
        c.set(Calendar.MINUTE, minute);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    /** The next given weekday (never today) at the given hour. */
    private static long next(int weekday, int hour) {
        Calendar c = Calendar.getInstance();
        do {
            c.add(Calendar.DAY_OF_YEAR, 1);
        } while (c.get(Calendar.DAY_OF_WEEK) != weekday);
        c.set(Calendar.HOUR_OF_DAY, hour);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    private static void custom(Activity activity, OnPick pick) {
        Calendar now = Calendar.getInstance();
        DatePickerDialog date = new DatePickerDialog(activity, (view, year, month, day) -> {
            new TimePickerDialog(activity, (tv, hour, minute) -> {
                Calendar at = Calendar.getInstance();
                at.set(year, month, day, hour, minute, 0);
                at.set(Calendar.MILLISECOND, 0);
                if (at.getTimeInMillis() < System.currentTimeMillis() + 30_000) {
                    Toast.makeText(activity, "That time has already passed", Toast.LENGTH_SHORT).show();
                    return;
                }
                pick.picked(at.getTimeInMillis());
            }, now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE) + 1,
                    DateFormat.is24HourFormat(activity)).show();
        }, now.get(Calendar.YEAR), now.get(Calendar.MONTH), now.get(Calendar.DAY_OF_MONTH));
        date.getDatePicker().setMinDate(now.getTimeInMillis() - 1000);
        date.show();
    }
}
