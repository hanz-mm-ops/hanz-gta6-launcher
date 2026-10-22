package app.tvshell;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Background artwork for the Top Shelf: featured shows (TVmaze, CC BY-SA) or landscape and
 * city photos (Lorem Picsum / Unsplash). Everything is cached on disk at screen size.
 */
final class Featured {
    static final String SHOWS = "shows";
    static final String LANDSCAPES = "landscapes";
    static final String CITIES = "cities";
    static final String DARK = "dark";
    static final String[] MODES = {SHOWS, LANDSCAPES, CITIES, DARK};

    interface Listener {
        void onItem(Item item);
    }

    static final class Item {
        final String title;
        final String label;
        final String meta;
        final String summary;
        final String credit;
        final File image;

        Item(String title, String label, String meta, String summary, String credit, File image) {
            this.title = title;
            this.label = label;
            this.meta = meta;
            this.summary = summary;
            this.credit = credit;
            this.image = image;
        }
    }

    // Severance, The Last of Us, Stranger Things, The Bear, Squid Game, Ted Lasso, House of the
    // Dragon, The Boys, Wednesday, Slow Horses, The Mandalorian, Silo, Reacher, Shogun, Fallout, Andor.
    private static final int[] SHOW_IDS = {44933, 46562, 2993, 54198, 43687, 44458, 44778, 15299,
            53647, 45039, 38963, 38052, 43031, 37336, 49041, 52341};
    private static final int[] LANDSCAPE_IDS = {10, 11, 15, 16, 28, 29, 54, 110, 124, 128, 141, 177, 184, 191};
    private static final int[] CITY_IDS = {57, 74, 84, 122, 49, 164};
    private static final long MAX_AGE_MS = 7L * 24 * 3600 * 1000;
    static final int W = 1280;
    static final int H = 720;

    private Featured() {}

    static String mode(Context c) {
        return Prefs.get(c).getString(Prefs.BACKGROUND, SHOWS);
    }

    static String modeName(String mode) {
        switch (mode) {
            case LANDSCAPES: return "Aerial Landscapes";
            case CITIES: return "Cityscapes";
            case DARK: return "Dark";
            default: return "Featured Shows";
        }
    }

    // Services whose shows count as "what's on"; the name prefix matches TVmaze's webChannel.
    private static final String[] STREAMERS = {"Netflix", "Prime Video", "Apple TV", "Disney+", "HBO Max",
            "Max", "Hulu", "Paramount+", "Peacock"};
    private static final long TRENDING_MAX_AGE_MS = 24L * 3600 * 1000;
    private static final int SHOW_COUNT = 16;

    /** Show ids for the current mode: this week's streaming hits first, the curated list after. */
    private static int[] ids(Context c, String mode) {
        if (!SHOWS.equals(mode)) return ids(mode);
        java.util.LinkedHashSet<Integer> out = new java.util.LinkedHashSet<>();
        File f = new File(dir(c), "trending.json");
        try {
            if (f.exists()) {
                JSONArray a = new JSONArray(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
                for (int i = 0; i < a.length(); i++) out.add(a.getInt(i));
            }
        } catch (IOException | JSONException ignored) {
            // Fall back to the curated list.
        }
        for (int id : SHOW_IDS) {
            if (out.size() >= SHOW_COUNT) break;
            out.add(id);
        }
        int[] ids = new int[Math.min(out.size(), SHOW_COUNT)];
        int i = 0;
        for (int id : out) {
            if (i == ids.length) break;
            ids[i++] = id;
        }
        return ids;
    }

    /**
     * Once a day, look at the last week of streaming releases on TVmaze and keep the most
     * popular English-language shows from the big services.
     */
    private static void refreshTrending(Context c) {
        File f = new File(dir(c), "trending.json");
        if (f.exists() && System.currentTimeMillis() - f.lastModified() < TRENDING_MAX_AGE_MS) return;
        java.util.Map<Integer, Integer> weight = new java.util.HashMap<>();
        java.text.SimpleDateFormat day = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US);
        long now = System.currentTimeMillis();
        for (int back = 0; back < 7; back++) {
            try {
                JSONArray eps = new JSONArray(get("https://api.tvmaze.com/schedule/web?date="
                        + day.format(new java.util.Date(now - back * 24L * 3600 * 1000))));
                for (int i = 0; i < eps.length(); i++) {
                    JSONObject show = eps.getJSONObject(i).optJSONObject("_embedded");
                    show = show == null ? null : show.optJSONObject("show");
                    if (show == null || !"English".equals(show.optString("language"))) continue;
                    JSONObject ch = show.optJSONObject("webChannel");
                    String name = ch == null ? "" : ch.optString("name");
                    boolean major = false;
                    for (String s : STREAMERS) major |= name.startsWith(s);
                    int w = show.optInt("weight");
                    if (major && w >= 80) weight.put(show.getInt("id"), w);
                }
            } catch (IOException | JSONException e) {
                // A missing day just means fewer candidates.
            }
        }
        if (weight.isEmpty()) return;
        List<Integer> ranked = new ArrayList<>(weight.keySet());
        ranked.sort((a, b) -> weight.get(b) - weight.get(a));
        JSONArray a = new JSONArray();
        for (int i = 0; i < Math.min(12, ranked.size()); i++) a.put(ranked.get(i));
        try {
            Files.write(f.toPath(), a.toString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException ignored) {
            // Try again next time.
        }
    }

    private static int[] ids(String mode) {
        switch (mode) {
            case LANDSCAPES: return LANDSCAPE_IDS;
            case CITIES: return CITY_IDS;
            case SHOWS: return SHOW_IDS;
            default: return new int[0];
        }
    }

    /** Whatever is already on disk for the current mode; no network, so it is instant. */
    static List<Item> cached(Context c, String mode) {
        List<Item> out = new ArrayList<>();
        for (int id : ids(c, mode)) {
            Item it = fromDisk(c, mode, id, false);
            if (it != null) out.add(it);
        }
        return out;
    }

    /** Fetch anything missing or stale, reporting each item as soon as it is ready. Blocking. */
    static void load(Context c, String mode, Listener l, java.util.function.BooleanSupplier stillWanted) {
        if (SHOWS.equals(mode)) refreshTrending(c);
        for (int id : ids(c, mode)) {
            try {
                Item it = fromDisk(c, mode, id, true);
                if (it == null) it = mode.equals(SHOWS) ? fetchShow(c, id) : fetchPhoto(c, mode, id);
                if (it != null) l.onItem(it);
            } catch (IOException | JSONException | RuntimeException e) {
                // Skip this one; the rest still load.
            }
            if (!stillWanted.getAsBoolean()) return; // The viewer switched backgrounds meanwhile.
        }
    }

    static Bitmap decode(File f) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inPreferredConfig = Bitmap.Config.RGB_565;
        o.inMutable = true;
        return BitmapFactory.decodeFile(f.getPath(), o);
    }

    // ---------------------------------------------------------------- disk

    private static File dir(Context c) {
        File d = new File(c.getCacheDir(), "featured");
        d.mkdirs();
        return d;
    }

    private static Item fromDisk(Context c, String mode, int id, boolean requireFresh) {
        File json = new File(dir(c), mode + "_" + id + ".json");
        File img = new File(dir(c), mode + "_" + id + ".jpg");
        if (!json.exists() || !img.exists()) return null;
        if (requireFresh && System.currentTimeMillis() - json.lastModified() > MAX_AGE_MS) return null;
        try {
            JSONObject o = new JSONObject(new String(Files.readAllBytes(json.toPath()), StandardCharsets.UTF_8));
            return new Item(o.optString("title"), o.optString("label"), o.optString("meta"),
                    o.optString("summary"), o.optString("credit"), img);
        } catch (IOException | JSONException e) {
            return null;
        }
    }

    private static Item save(Context c, String mode, int id, Item partial, String imageUrl)
            throws IOException, JSONException {
        File img = new File(dir(c), mode + "_" + id + ".jpg");
        saveScaled(imageUrl, img);
        JSONObject o = new JSONObject();
        o.put("title", partial.title).put("label", partial.label).put("meta", partial.meta)
                .put("summary", partial.summary).put("credit", partial.credit);
        Files.write(new File(dir(c), mode + "_" + id + ".json").toPath(),
                o.toString().getBytes(StandardCharsets.UTF_8));
        return new Item(partial.title, partial.label, partial.meta, partial.summary, partial.credit, img);
    }

    // ---------------------------------------------------------------- sources

    private static Item fetchShow(Context c, int id) throws IOException, JSONException {
        JSONObject s = new JSONObject(get("https://api.tvmaze.com/shows/" + id + "?embed=images"));
        JSONObject channel = s.optJSONObject("webChannel");
        if (channel == null) channel = s.optJSONObject("network");
        List<String> meta = new ArrayList<>();
        JSONArray genres = s.optJSONArray("genres");
        for (int i = 0; genres != null && i < Math.min(2, genres.length()); i++) meta.add(genres.getString(i));
        String premiered = s.isNull("premiered") ? "" : s.optString("premiered");
        if (premiered.length() >= 4) meta.add(premiered.substring(0, 4));
        JSONObject rating = s.optJSONObject("rating");
        if (rating != null && !rating.isNull("average")) meta.add("★ " + rating.optDouble("average"));

        String url = pickBackground(s.getJSONObject("_embedded").getJSONArray("images"));
        if (url == null) return null;
        Item partial = new Item(s.getString("name"),
                channel != null ? channel.optString("name", "") : "",
                String.join("  ·  ", meta),
                s.isNull("summary") ? "" : clean(s.optString("summary")),
                "Artwork and info from TVmaze", null);
        return save(c, SHOWS, id, partial, url);
    }

    private static Item fetchPhoto(Context c, String mode, int id) throws IOException, JSONException {
        String author = "";
        try {
            author = new JSONObject(get("https://picsum.photos/id/" + id + "/info")).optString("author", "");
        } catch (IOException | JSONException ignored) {
            // The photo still works without a credit line.
        }
        Item partial = new Item("", "", "", "", author.isEmpty() ? "Unsplash" : "Photo: " + author + " · Unsplash", null);
        return save(c, mode, id, partial, "https://picsum.photos/id/" + id + "/" + W + "/" + H);
    }

    /** The smallest background that still covers 1920 px, or the largest one available. */
    private static String pickBackground(JSONArray images) throws JSONException {
        String best = null;
        int bestW = 0;
        for (int i = 0; i < images.length(); i++) {
            JSONObject img = images.getJSONObject(i);
            if (!"background".equals(img.optString("type"))) continue;
            JSONObject orig = img.getJSONObject("resolutions").getJSONObject("original");
            int w = orig.optInt("width");
            boolean better = best == null
                    || (w >= 1920 && (bestW < 1920 || w < bestW))
                    || (bestW < 1920 && w > bestW);
            if (better) {
                best = orig.getString("url");
                bestW = w;
            }
        }
        return best;
    }

    private static String clean(String html) {
        String t = html.replaceAll("<[^>]+>", "").replace("&amp;", "&").replace("&quot;", "\"")
                .replace("&#39;", "'").replace("&nbsp;", " ").trim();
        return t.length() > 200 ? t.substring(0, 197).trim() + "…" : t;
    }

    // ---------------------------------------------------------------- network

    private static HttpURLConnection open(String url) throws IOException {
        HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
        con.setConnectTimeout(10000);
        con.setReadTimeout(20000);
        con.setInstanceFollowRedirects(true);
        con.setRequestProperty("User-Agent", "TVHome/1.0");
        return con;
    }

    private static String get(String url) throws IOException {
        HttpURLConnection con = open(url);
        try (InputStream in = con.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
            return out.toString("UTF-8");
        } finally {
            con.disconnect();
        }
    }

    /** Download an image and store it center-cropped to exactly W×H as a JPEG. */
    private static void saveScaled(String url, File out) throws IOException {
        File tmp = new File(out.getPath() + ".part");
        HttpURLConnection con = open(url);
        try (InputStream in = con.getInputStream(); OutputStream os = new FileOutputStream(tmp)) {
            byte[] buf = new byte[32768];
            for (int n; (n = in.read(buf)) > 0; ) os.write(buf, 0, n);
        } finally {
            con.disconnect();
        }

        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(tmp.getPath(), bounds);
        int sample = 1;
        while (bounds.outWidth / (sample * 2) >= W && bounds.outHeight / (sample * 2) >= H) sample *= 2;
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inSampleSize = sample;
        Bitmap src = BitmapFactory.decodeFile(tmp.getPath(), o);
        tmp.delete();
        if (src == null) throw new IOException("undecodable image");

        float scale = Math.max(W / (float) src.getWidth(), H / (float) src.getHeight());
        int cw = Math.round(W / scale), ch = Math.round(H / scale);
        int cx = (src.getWidth() - cw) / 2, cy = (src.getHeight() - ch) / 2;
        Bitmap dst = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        new Canvas(dst).drawBitmap(src, new Rect(cx, cy, cx + cw, cy + ch), new Rect(0, 0, W, H),
                new Paint(Paint.FILTER_BITMAP_FLAG));
        src.recycle();
        try (OutputStream os = new FileOutputStream(out)) {
            dst.compress(Bitmap.CompressFormat.JPEG, 86, os);
        }
        dst.recycle();
    }
}
