package co.hkapps.messages;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.database.Cursor;
import android.provider.Telephony;

import java.util.ArrayList;
import java.util.List;

/**
 * "Messages to keep": deletes the oldest texts and picture messages over the limit in each chat,
 * once a day and on "Free space now". Locked messages are never touched.
 */
final class Cleanup {

    private static final int JOB_ID = 4242;

    private Cleanup() {}

    /** Runs the clean-up daily while any limit is set; cancels it otherwise. */
    static void schedule(Context c) {
        JobScheduler js = c.getSystemService(JobScheduler.class);
        if (js == null) return;
        if (!anyLimit(c)) {
            js.cancel(JOB_ID);
            return;
        }
        if (js.getPendingJob(JOB_ID) != null) return;
        js.schedule(new JobInfo.Builder(JOB_ID, new ComponentName(c, Job.class))
                .setPeriodic(24 * 60 * 60 * 1000L)
                .setRequiresDeviceIdle(true)
                .setPersisted(true)
                .build());
    }

    private static boolean anyLimit(Context c) {
        if (Prefs.integer(c, Prefs.KEEP_TEXT) > 0 || Prefs.integer(c, Prefs.KEEP_MEDIA) > 0) return true;
        for (String key : ChatPrefs.all(c).keySet()) {
            if (key.endsWith("." + Prefs.KEEP_TEXT) || key.endsWith("." + Prefs.KEEP_MEDIA)) return true;
        }
        return false;
    }

    /** One chat, or every chat when threadId is not positive. Returns how many were deleted. */
    static int run(Context c, long threadId) {
        if (!DefaultSms.isDefault(c)) return 0;
        List<Long> threads = new ArrayList<>();
        if (threadId > 0) {
            threads.add(threadId);
        } else {
            try (Cursor cur = c.getContentResolver().query(
                    Telephony.Threads.CONTENT_URI.buildUpon().appendQueryParameter("simple", "true").build(),
                    new String[]{Telephony.Threads._ID}, null, null, null)) {
                while (cur != null && cur.moveToNext()) threads.add(cur.getLong(0));
            } catch (RuntimeException e) {
                return 0;
            }
        }
        int deleted = 0;
        for (long id : threads) {
            int text = ChatPrefs.integer(c, id, Prefs.KEEP_TEXT);
            int media = ChatPrefs.integer(c, id, Prefs.KEEP_MEDIA);
            if (text > 0) deleted += trim(c, Telephony.Sms.CONTENT_URI, id, text, false);
            if (media > 0) deleted += trim(c, Telephony.Mms.CONTENT_URI, id, media, true);
        }
        DebugLog.log(c, "clean-up deleted " + deleted);
        return deleted;
    }

    private static int trim(Context c, android.net.Uri table, long threadId, int keep, boolean mms) {
        List<Long> old = new ArrayList<>();
        String where = "thread_id = ? AND locked = 0" + (mms ? " AND " + Telephony.Mms.MESSAGE_BOX + " != "
                + Telephony.Mms.MESSAGE_BOX_DRAFTS : " AND type != " + Telephony.Sms.MESSAGE_TYPE_DRAFT);
        try (Cursor cur = c.getContentResolver().query(table, new String[]{"_id"}, where,
                new String[]{String.valueOf(threadId)}, "date DESC")) {
            int n = 0;
            while (cur != null && cur.moveToNext()) {
                if (++n > keep) old.add(cur.getLong(0));
            }
        } catch (RuntimeException e) {
            return 0;
        }
        for (long id : old) SmsWriter.deleteMessage(c, mms, id);
        return old.size();
    }

    /** Daily job. */
    public static class Job extends JobService {
        @Override
        public boolean onStartJob(JobParameters params) {
            Context app = getApplicationContext();
            SmsWriter.run(() -> {
                run(app, -1);
                jobFinished(params, false);
            });
            return true;
        }

        @Override
        public boolean onStopJob(JobParameters params) {
            return true;
        }
    }
}
