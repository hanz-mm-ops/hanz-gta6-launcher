package app.tvshell;

import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.tv.TvContract;
import android.net.Uri;
import android.util.Log;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** What the Vice home shows: weather, Watch Next and app usage. All calls block; use a thread. */
final class ViceData {
    static final String TAG = "Vice";
    private static final long WEATHER_MAX_AGE_MS = 30L * 60 * 1000;

    private ViceData() {}

    // ---------------------------------------------------------------- weather

    static final class Row {
        String label, cond;
        int temp;
    }

    static final class Weather {
        String city = "";
        int temp;
        int code;
        boolean day = true;
        String cond = "";
        final List<Row> rows = new ArrayList<>();
    }

    /** Last forecast we fetched, re-read for the current hour; null before the first fetch. */
    static Weather cachedWeather(Context c) {
        SharedPreferences p = Prefs.get(c);
        String json = p.getString("vice_forecast", null);
        if (json == null) return null;
        try {
            return parse(p.getString("vice_city", ""), new JSONObject(json));
        } catch (JSONException e) {
            return null;
        }
    }

    static boolean weatherStale(Context c) {
        return System.currentTimeMillis() - Prefs.get(c).getLong("vice_forecast_at", 0) > WEATHER_MAX_AGE_MS;
    }

    /** Approximate location from the connection's IP, then the forecast from Open-Meteo. */
    static Weather fetchWeather(Context c) throws IOException, JSONException {
        JSONObject where = new JSONObject(get("https://ipwho.is/?fields=city,latitude,longitude,success"));
        if (!where.optBoolean("success", true)) throw new IOException("no location");
        String url = "https://api.open-meteo.com/v1/forecast?latitude=" + where.getDouble("latitude")
                + "&longitude=" + where.getDouble("longitude")
                + "&current=temperature_2m,weather_code,is_day"
                + "&hourly=temperature_2m,weather_code,is_day&timezone=auto&forecast_days=2";
        JSONObject forecast = new JSONObject(get(url));
        String city = where.optString("city", "");
        Prefs.get(c).edit().putString("vice_forecast", forecast.toString()).putString("vice_city", city)
                .putLong("vice_forecast_at", System.currentTimeMillis()).apply();
        return parse(city, forecast);
    }

    private static Weather parse(String city, JSONObject f) throws JSONException {
        Weather w = new Weather();
        w.city = city;
        JSONObject now = f.getJSONObject("current");
        w.temp = (int) Math.round(now.getDouble("temperature_2m"));
        w.code = now.getInt("weather_code");
        w.day = now.optInt("is_day", 1) == 1;
        w.cond = describe(w.code, w.day);

        JSONObject h = f.getJSONObject("hourly");
        JSONArray time = h.getJSONArray("time");
        JSONArray temp = h.getJSONArray("temperature_2m");
        JSONArray code = h.getJSONArray("weather_code");
        JSONArray day = h.getJSONArray("is_day");
        // Hourly times are in the TV's time zone (timezone=auto). Find this hour from the clock,
        // not the cached "current" block, so an older forecast still lines up.
        String hourNow = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:00", java.util.Locale.US)
                .format(new java.util.Date());
        int idx = -1, tonight = -1;
        String today = hourNow.substring(0, 10);
        for (int i = 0; i < time.length(); i++) {
            String t = time.getString(i);
            if (t.equals(hourNow)) idx = i;
            if (t.equals(today + "T22:00")) tonight = i;
        }
        Row first = new Row();
        first.label = "NOW";
        first.temp = w.temp;
        first.cond = w.cond;
        w.rows.add(first);
        if (idx < 0) return w;
        String[] labels = {"+1 HR", "+2 HRS", "+3 HRS"};
        for (int k = 1; k <= 3; k++) w.rows.add(hourRow(labels[k - 1], idx + k, temp, code, day));
        if (tonight <= idx + 3) tonight = idx + 4;
        w.rows.add(hourRow("TONIGHT", tonight, temp, code, day));
        return w;
    }

    private static Row hourRow(String label, int i, JSONArray temp, JSONArray code, JSONArray day) throws JSONException {
        Row r = new Row();
        r.label = label;
        i = Math.min(i, temp.length() - 1);
        r.temp = (int) Math.round(temp.getDouble(i));
        r.cond = describe(code.getInt(i), day.optInt(i, 1) == 1);
        return r;
    }

    static String describe(int code, boolean day) {
        if (code == 0) return day ? "SUNNY" : "CLEAR";
        if (code == 1) return day ? "MOSTLY SUNNY" : "MOSTLY CLEAR";
        if (code == 2) return "PARTLY CLOUDY";
        if (code == 3) return "CLOUDY";
        if (code <= 48) return "FOGGY";
        if (code <= 57) return "DRIZZLE";
        if (code <= 67) return "RAIN";
        if (code <= 77) return "SNOW";
        if (code <= 82) return "SHOWERS";
        if (code <= 86) return "SNOW SHOWERS";
        return "STORMS";
    }

    private static String get(String url) throws IOException {
        HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
        con.setConnectTimeout(8000);
        con.setReadTimeout(8000);
        try (InputStream in = con.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
            return out.toString("UTF-8");
        } finally {
            con.disconnect();
        }
    }

    // ---------------------------------------------------------------- watch next

    static final class Watch {
        String pkg, title, meta, tag;
        float progress = -1f;
        String poster;
        Intent launch;
        long engaged;
    }

    /**
     * Android TV's Watch Next row, newest first. Returns null when the TV won't let a
     * launcher read it (most firmwares only show an app its own rows).
     */
    static List<Watch> watchNext(Context c, int max) {
        String[] cols = {
                TvContract.WatchNextPrograms.COLUMN_PACKAGE_NAME,
                TvContract.WatchNextPrograms.COLUMN_TITLE,
                TvContract.WatchNextPrograms.COLUMN_SEASON_DISPLAY_NUMBER,
                TvContract.WatchNextPrograms.COLUMN_EPISODE_DISPLAY_NUMBER,
                TvContract.WatchNextPrograms.COLUMN_POSTER_ART_URI,
                TvContract.WatchNextPrograms.COLUMN_THUMBNAIL_URI,
                TvContract.WatchNextPrograms.COLUMN_LAST_PLAYBACK_POSITION_MILLIS,
                TvContract.WatchNextPrograms.COLUMN_DURATION_MILLIS,
                TvContract.WatchNextPrograms.COLUMN_INTENT_URI,
                TvContract.WatchNextPrograms.COLUMN_LIVE,
                TvContract.WatchNextPrograms.COLUMN_BROWSABLE,
                TvContract.WatchNextPrograms.COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS,
        };
        List<Watch> out = new ArrayList<>();
        try (Cursor cur = c.getContentResolver().query(TvContract.WatchNextPrograms.CONTENT_URI, cols, null, null, null)) {
            if (cur == null) {
                Log.i(TAG, "watch next: provider missing");
                return null;
            }
            while (cur.moveToNext()) {
                if (cur.getInt(10) == 0) continue;
                Watch w = new Watch();
                w.pkg = cur.getString(0);
                w.title = cur.getString(1);
                String season = cur.getString(2), episode = cur.getString(3);
                w.poster = cur.getString(4) != null ? cur.getString(4) : cur.getString(5);
                long pos = cur.getLong(6), dur = cur.getLong(7);
                boolean live = cur.getInt(9) == 1;
                w.engaged = cur.getLong(11);
                if (live) {
                    w.meta = "Live now";
                    w.progress = 1f;
                } else {
                    String left = dur > 0 ? Math.max(1, Math.round((dur - pos) / 60000f)) + " min left" : "";
                    String ep = season != null && episode != null ? "S" + season + " · E" + episode : null;
                    w.meta = ep != null && !left.isEmpty() ? ep + " · " + left : ep != null ? ep : left;
                    if (dur > 0) w.progress = Math.max(0f, Math.min(1f, pos / (float) dur));
                }
                try {
                    String uri = cur.getString(8);
                    if (uri != null) w.launch = Intent.parseUri(uri, Intent.URI_INTENT_SCHEME);
                } catch (java.net.URISyntaxException ignored) {
                    // falls back to the app's own launch intent
                }
                out.add(w);
            }
        } catch (SecurityException | IllegalArgumentException e) {
            Log.i(TAG, "watch next: not permitted (" + e.getMessage() + ")");
            return null;
        }
        out.sort((a, b) -> Long.compare(b.engaged, a.engaged));
        while (out.size() > max) out.remove(out.size() - 1);
        Log.i(TAG, "watch next: " + out.size() + " rows");
        return out;
    }

    /** Poster art from a content:, android.resource:, file: or https: address, about w×h px. */
    static Bitmap poster(Context c, String uri, int w, int h) {
        if (uri == null) return null;
        try {
            byte[] data;
            Uri u = Uri.parse(uri);
            String scheme = u.getScheme() == null ? "" : u.getScheme();
            try (InputStream in = scheme.startsWith("http") ? new URL(uri).openStream()
                    : c.getContentResolver().openInputStream(u)) {
                if (in == null) return null;
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[16384];
                for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
                data = out.toByteArray();
            }
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, o);
            o.inSampleSize = 1;
            while (o.outWidth / (o.inSampleSize * 2) >= w && o.outHeight / (o.inSampleSize * 2) >= h) o.inSampleSize *= 2;
            o.inJustDecodeBounds = false;
            return BitmapFactory.decodeByteArray(data, 0, data.length, o);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    // ---------------------------------------------------------------- usage

    /** Package → last time used and total foreground time over 30 days; empty if not allowed. */
    static Map<String, long[]> usage(Context c) {
        Map<String, long[]> out = new HashMap<>();
        UsageStatsManager um = c.getSystemService(UsageStatsManager.class);
        if (um == null) return out;
        long now = System.currentTimeMillis();
        try {
            Map<String, UsageStats> all = um.queryAndAggregateUsageStats(now - 30L * 24 * 3600 * 1000, now);
            for (Map.Entry<String, UsageStats> e : all.entrySet()) {
                UsageStats s = e.getValue();
                if (s.getTotalTimeInForeground() <= 0) continue;
                out.put(e.getKey(), new long[] {s.getLastTimeUsed(), s.getTotalTimeInForeground()});
            }
        } catch (SecurityException e) {
            Log.i(TAG, "usage stats: not permitted");
        }
        return out;
    }

    static String ago(long when) {
        long m = (System.currentTimeMillis() - when) / 60000;
        if (m < 2) return "Opened just now";
        if (m < 60) return "Opened " + m + " min ago";
        long h = m / 60;
        if (h < 24) return "Opened " + h + (h == 1 ? " hour ago" : " hours ago");
        long d = h / 24;
        return d == 1 ? "Opened yesterday" : "Opened " + d + " days ago";
    }

    // ---------------------------------------------------------------- last watched per app

    /** What MediaWatcher saw last in each app, newest first, one per app; music apps left out. */
    static List<Watch> lastWatched(Context c, int max) {
        List<Watch> out = new ArrayList<>();
        android.content.pm.PackageManager pm = c.getPackageManager();
        for (Map.Entry<String, ?> e : c.getSharedPreferences(MediaWatcher.PREFS, Context.MODE_PRIVATE).getAll().entrySet()) {
            JSONObject o = MediaWatcher.read(String.valueOf(e.getValue()));
            if (o == null) continue;
            String pkg = e.getKey();
            try {
                if (pm.getApplicationInfo(pkg, 0).category == android.content.pm.ApplicationInfo.CATEGORY_AUDIO) continue;
            } catch (android.content.pm.PackageManager.NameNotFoundException gone) {
                continue;
            }
            Watch w = new Watch();
            w.pkg = pkg;
            w.title = o.optString("title");
            w.engaged = o.optLong("at");
            long dur = o.optLong("duration"), pos = o.optLong("position", -1);
            String sub = o.optString("sub");
            if (o.optBoolean("live")) {
                w.meta = sub.isEmpty() ? "Live now" : sub + " · Live";
                w.progress = 1f;
            } else if (dur > 0 && pos >= 0) {
                long left = Math.max(1, Math.round((dur - pos) / 60000f));
                w.meta = (sub.isEmpty() ? "" : sub + " · ") + left + " min left";
                w.progress = Math.max(0f, Math.min(1f, pos / (float) dur));
            } else {
                w.meta = sub.isEmpty() ? ago(w.engaged).replace("Opened", "Watched") : sub;
            }
            java.io.File art = MediaWatcher.artFile(c, pkg);
            if (art.exists()) w.poster = Uri.fromFile(art).toString();
            out.add(w);
        }
        out.sort((a, b) -> Long.compare(b.engaged, a.engaged));
        while (out.size() > max) out.remove(out.size() - 1);
        return out;
    }
}
