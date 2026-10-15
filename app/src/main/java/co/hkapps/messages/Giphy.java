package co.hkapps.messages;

import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** GIF search through the Giphy web API. Only on when a GIPHY_API_KEY was built in. */
final class Giphy {

    /** One result: a small preview and the sizes we could send. */
    static final class Gif {
        final String id;
        final String preview;
        /** {url, size in bytes} from smallest to largest. */
        final List<Object[]> renditions = new ArrayList<>();

        Gif(String id, String preview) {
            this.id = id;
            this.preview = preview;
        }

        /** The largest GIF that fits in maxBytes, or the smallest one. */
        String bestUrl(int maxBytes) {
            String best = null;
            for (Object[] r : renditions) {
                if (best == null || (Long) r[1] <= maxBytes) best = (String) r[0];
            }
            return best;
        }
    }

    private static final String BASE = "https://api.giphy.com/v1/gifs/";
    // Smallest first, so bestUrl walks up while things still fit.
    private static final String[] SIZES = {"preview_gif", "fixed_width_small", "fixed_height_small",
            "fixed_width_downsampled", "fixed_width", "fixed_height", "downsized"};

    private Giphy() {}

    static boolean enabled() {
        return !BuildConfig.GIPHY_API_KEY.isEmpty();
    }

    /** Trending GIFs when query is empty. Call off the main thread. */
    static List<Gif> search(String query, int offset) throws IOException {
        String url = query == null || query.trim().isEmpty()
                ? BASE + "trending?api_key=" + BuildConfig.GIPHY_API_KEY + "&limit=40&rating=pg-13&offset=" + offset
                : BASE + "search?api_key=" + BuildConfig.GIPHY_API_KEY + "&limit=40&rating=pg-13&offset=" + offset
                        + "&q=" + Uri.encode(query.trim());
        String json = new String(get(url, 4 * 1024 * 1024), StandardCharsets.UTF_8);
        List<Gif> result = new ArrayList<>();
        try {
            JSONArray data = new JSONObject(json).getJSONArray("data");
            for (int i = 0; i < data.length(); i++) {
                JSONObject item = data.getJSONObject(i);
                JSONObject images = item.getJSONObject("images");
                JSONObject small = images.optJSONObject("fixed_width_small");
                if (small == null) small = images.optJSONObject("preview_gif");
                if (small == null) continue;
                Gif gif = new Gif(item.optString("id"), small.optString("url"));
                for (String size : SIZES) {
                    JSONObject r = images.optJSONObject(size);
                    if (r == null || r.optString("url").isEmpty()) continue;
                    gif.renditions.add(new Object[]{r.optString("url"), r.optLong("size", Long.MAX_VALUE)});
                }
                result.add(gif);
            }
        } catch (JSONException e) {
            throw new IOException("Bad reply from Giphy", e);
        }
        return result;
    }

    static byte[] get(String url, int max) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(15000);
        try {
            if (conn.getResponseCode() / 100 != 2) throw new IOException("HTTP " + conn.getResponseCode());
            try (InputStream in = conn.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[16 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    if (out.size() > max) throw new IOException("Too large");
                }
                return out.toByteArray();
            }
        } finally {
            conn.disconnect();
        }
    }
}
