package co.hkapps.messages;

import android.content.Context;
import android.media.MediaRecorder;
import android.os.Build;

import java.io.File;
import java.io.IOException;

/** Records voice memos as AMR, the audio format every phone's MMS player knows. */
final class VoiceRecorder {

    static final String CONTENT_TYPE = "audio/amr";
    /** About 3 KB per second, so a few minutes fit any carrier's limit. */
    static final int MAX_SECONDS = 180;
    private static final long MIN_MILLIS = 1000;

    private MediaRecorder recorder;
    private File file;
    private long startedAt;

    @SuppressWarnings("deprecation")
    void start(Context context) throws IOException {
        file = new File(MmsFileProvider.dir(context, MmsFileProvider.DIR_SHARE),
                "voice-" + System.currentTimeMillis() + ".amr");
        recorder = Build.VERSION.SDK_INT >= 31 ? new MediaRecorder(context) : new MediaRecorder();
        recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
        recorder.setOutputFormat(MediaRecorder.OutputFormat.AMR_NB);
        recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB);
        recorder.setMaxDuration(MAX_SECONDS * 1000);
        recorder.setOutputFile(file.getPath());
        try {
            recorder.prepare();
            recorder.start();
        } catch (IOException | RuntimeException e) {
            release();
            throw e instanceof IOException ? (IOException) e : new IOException(e);
        }
        startedAt = System.currentTimeMillis();
    }

    boolean isRecording() {
        return recorder != null;
    }

    long elapsedMillis() {
        return recorder == null ? 0 : System.currentTimeMillis() - startedAt;
    }

    /** Stops and returns the recording, or null if it was too short to keep. */
    File stop() {
        if (recorder == null) return null;
        boolean ok = elapsedMillis() >= MIN_MILLIS;
        try {
            recorder.stop();
        } catch (RuntimeException e) {
            ok = false;  // stop() throws when nothing was recorded.
        }
        release();
        if (!ok) file.delete();
        return ok ? file : null;
    }

    void cancel() {
        if (recorder == null) return;
        try {
            recorder.stop();
        } catch (RuntimeException ignored) {
            // Nothing recorded.
        }
        release();
        if (file != null) file.delete();
    }

    private void release() {
        if (recorder != null) {
            recorder.release();
            recorder = null;
        }
    }
}
