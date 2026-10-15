package co.hkapps.messages;

import android.content.Context;
import android.text.Html;
import android.util.Patterns;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * "Web previews": the title, description and picture of a link in a message, read from the
 * page's Open Graph tags. Fetched once, then kept in AppDb.
 */
final class LinkPreview {

    static final class Info {
        final String url;
        final String title;
        final String description;
        /** Path of the downloaded picture, or null. */
        final String image;
        final String site;
        final long fetched;

        Info(String url, String title, String description, String image, String site, long fetched) {
            this.url = url;
            this.title = title;
            this.description = description;
            this.image = image;
            this.site = site;
            this.fetched = fetched;
        }

        boolean empty() {
            return (title == null || title.isEmpty()) && image == null;
        }
    }

    private static final int MAX_PAGE = 512 * 1024;
    private static final int MAX_IMAGE = 2 * 1024 * 1024;
    private static final Pattern META = Pattern.compile("<meta\\s[^>]*>", Pattern.CASE_INSENSITIVE);
    private static final Pattern ATTR = Pattern.compile("(\\w[\\w:-]*)\\s*=\\s*(\"([^\"]*)\"|'([^']*)')");
    private static final Pattern TITLE = Pattern.compile("<title[^>]*>(.*?)</title>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private LinkPreview() {}

    /** Should this message get a preview, given the "Web previews" setting? */
    static boolean wanted(Context c, Message m) {
        int setting = Prefs.integer(c, Prefs.LINK_PREVIEWS);
        if (setting == Prefs.PREVIEWS_OFF || m.scheduled()) return false;
        if (setting == Prefs.PREVIEWS_ALL) return true;
        return m.incoming() ? setting == Prefs.PREVIEWS_RECEIVED : setting == Prefs.PREVIEWS_SENT;
    }

    /** The first web link in a text, or null. */
    static String firstUrl(String text) {
        if (text == null) return null;
        Matcher m = Patterns.WEB_URL.matcher(text);
        while (m.find()) {
            String url = m.group();
            // Plain words with a dot ("e.g") also match; ask for a scheme or "www."
            String lower = url.toLowerCase(Locale.ROOT);
            if (lower.startsWith("http://") || lower.startsWith("https://")) return url;
            if (lower.startsWith("www.")) return "https://" + url;
        }
        return null;
    }

    /** Cached preview, else fetches it. Call off the main thread. Null when there is nothing to show. */
    static Info get(Context c, String url) {
        AppDb db = AppDb.get(c);
        Info cached = db.preview(url);
        if (cached != null) return cached.empty() ? null : cached;
        Info fresh = fetch(c, url);
        db.savePreview(fresh);
        return fresh.empty() ? null : fresh;
    }

    private static Info fetch(Context c, String url) {
        long now = System.currentTimeMillis();
        try {
            byte[] page = download(url, MAX_PAGE, "text/html");
            if (page == null) return new Info(url, null, null, null, null, now);
            String html = new String(page, StandardCharsets.UTF_8);
            String title = null, description = null, image = null, site = null;
            Matcher meta = META.matcher(html);
            while (meta.find()) {
                String tag = meta.group();
                String key = null, content = null;
                Matcher a = ATTR.matcher(tag);
                while (a.find()) {
                    String name = a.group(1).toLowerCase(Locale.ROOT);
                    String value = a.group(3) != null ? a.group(3) : a.group(4);
                    if (name.equals("property") || name.equals("name")) key = value.toLowerCase(Locale.ROOT);
                    else if (name.equals("content")) content = value;
                }
                if (key == null || content == null) continue;
                switch (key) {
                    case "og:title": case "twitter:title": if (title == null) title = content; break;
                    case "og:description": case "twitter:description": case "description":
                        if (description == null) description = content;
                        break;
                    case "og:image": case "og:image:url": case "twitter:image": if (image == null) image = content; break;
                    case "og:site_name": if (site == null) site = content; break;
                    default: break;
                }
            }
            if (title == null) {
                Matcher t = TITLE.matcher(html);
                if (t.find()) title = t.group(1).trim();
            }
            if (site == null) site = new URL(url).getHost().replaceFirst("^www\\.", "");
            String imagePath = null;
            if (image != null) {
                String imageUrl = new URL(new URL(url), Html.fromHtml(image, 0).toString()).toString();
                byte[] data = download(imageUrl, MAX_IMAGE, "image/");
                if (data != null) {
                    File dir = new File(c.getCacheDir(), "previews");
                    if (dir.exists() || dir.mkdirs()) {
                        File file = new File(dir, Integer.toHexString(url.hashCode()) + ".img");
                        try (OutputStream out = new FileOutputStream(file)) {
                            out.write(data);
                            imagePath = file.getPath();
                        }
                    }
                }
            }
            return new Info(url, clean(title), clean(description), imagePath, clean(site), now);
        } catch (IOException | RuntimeException e) {
            return new Info(url, null, null, null, null, now);
        }
    }

    private static String clean(String s) {
        if (s == null) return null;
        String text = Html.fromHtml(s, 0).toString().trim();
        return text.isEmpty() ? null : text;
    }

    private static byte[] download(String url, int max, String wantedType) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(8000);
        conn.setReadTimeout(8000);
        conn.setInstanceFollowRedirects(true);
        // Some sites only send Open Graph tags to link-preview bots.
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) facebookexternalhit/1.1");
        try {
            if (conn.getResponseCode() / 100 != 2) return null;
            String type = conn.getContentType();
            if (type != null && !type.toLowerCase(Locale.ROOT).contains(wantedType)) return null;
            try (InputStream in = conn.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[16 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    if (out.size() > max) break;
                }
                return out.toByteArray();
            }
        } finally {
            conn.disconnect();
        }
    }
}
