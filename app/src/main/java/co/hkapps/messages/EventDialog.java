package co.hkapps.messages;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.text.format.DateFormat;
import android.text.format.DateUtils;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.ArrayAdapter;

import java.util.Calendar;

/** A small form to make a calendar event to send (title, date, time, length, place). */
final class EventDialog {

    interface OnEvent {
        void made(Ics.Event event);
    }

    private static final String[] LENGTHS = {"30 minutes", "1 hour", "2 hours", "3 hours", "All day"};
    private static final int[] MINUTES = {30, 60, 120, 180, 0};

    private EventDialog() {}

    static void show(Activity a, OnEvent onEvent) {
        int pad = (int) Format.dp(a, 20);
        LinearLayout form = new LinearLayout(a);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(pad, pad / 2, pad, 0);

        EditText title = new EditText(a);
        title.setHint("What");
        title.setSingleLine(true);
        form.addView(title);

        Calendar when = Calendar.getInstance();
        when.add(Calendar.HOUR_OF_DAY, 1);
        when.set(Calendar.MINUTE, 0);
        when.set(Calendar.SECOND, 0);
        when.set(Calendar.MILLISECOND, 0);

        Button date = new Button(a, null, android.R.attr.borderlessButtonStyle);
        Button time = new Button(a, null, android.R.attr.borderlessButtonStyle);
        Runnable refresh = () -> {
            date.setText(DateUtils.formatDateTime(a, when.getTimeInMillis(),
                    DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_SHOW_WEEKDAY | DateUtils.FORMAT_ABBREV_ALL));
            time.setText(DateUtils.formatDateTime(a, when.getTimeInMillis(), DateUtils.FORMAT_SHOW_TIME));
        };
        refresh.run();
        date.setOnClickListener(v -> new DatePickerDialog(a, (dp, y, m, d) -> {
            when.set(y, m, d);
            refresh.run();
        }, when.get(Calendar.YEAR), when.get(Calendar.MONTH), when.get(Calendar.DAY_OF_MONTH)).show());
        time.setOnClickListener(v -> new TimePickerDialog(a, (tp, h, min) -> {
            when.set(Calendar.HOUR_OF_DAY, h);
            when.set(Calendar.MINUTE, min);
            refresh.run();
        }, when.get(Calendar.HOUR_OF_DAY), when.get(Calendar.MINUTE), DateFormat.is24HourFormat(a)).show());
        LinearLayout row = new LinearLayout(a);
        row.addView(date, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        row.addView(time, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        form.addView(row);

        Spinner length = new Spinner(a);
        length.setAdapter(new ArrayAdapter<>(a, android.R.layout.simple_spinner_dropdown_item, LENGTHS));
        length.setSelection(1);
        form.addView(length);

        EditText place = new EditText(a);
        place.setHint("Where (optional)");
        place.setSingleLine(true);
        form.addView(place);

        new AlertDialog.Builder(a)
                .setTitle("Send an event")
                .setView(form)
                .setPositiveButton("Attach", (d, w) -> {
                    Ics.Event e = new Ics.Event();
                    e.title = title.getText().toString().trim();
                    if (e.title.isEmpty()) e.title = "Event";
                    int minutes = MINUTES[length.getSelectedItemPosition()];
                    e.allDay = minutes == 0;
                    if (e.allDay) {
                        when.set(Calendar.HOUR_OF_DAY, 0);
                        when.set(Calendar.MINUTE, 0);
                        e.start = when.getTimeInMillis();
                        e.end = e.start + DateUtils.DAY_IN_MILLIS;
                    } else {
                        e.start = when.getTimeInMillis();
                        e.end = e.start + minutes * 60_000L;
                    }
                    e.location = place.getText().toString().trim();
                    onEvent.made(e);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }
}
