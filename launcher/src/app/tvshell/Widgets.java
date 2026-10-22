package app.tvshell;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import org.json.JSONException;
import org.json.JSONObject;

/** Lightweight home screen widgets on Liquid Glass cards: weather, clock, date, shows. */
final class Widgets {
    private static final long WEATHER_MAX_AGE_MS = 30L * 60 * 1000;

    private Widgets() {}

    /** A glass card that brightens and lifts when focused. */
    static Glass card(Context c, boolean focusable) {
        Glass g = new Glass(c, 26);
        g.setTint(0x1FFFFFFF);
        g.setClipToOutline(true);
        if (focusable) {
            g.setFocusable(true);
            g.setClickable(true);
            g.setOnFocusChangeListener((v, has) -> {
                g.animateTint(has ? 0x4DFFFFFF : 0x1FFFFFFF);
                Ui.lift(v, has, 1.05f);
            });
        }
        return g;
    }

    // ---------------------------------------------------------------- weather

    static View weather(Context c) {
        Glass g = card(c, false);
        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(Ui.dp(20), Ui.dp(16), Ui.dp(20), Ui.dp(16));
        TextView city = Ui.text(c, "Weather", 17, 0xE6FFFFFF, Ui.BOLD);
        TextView temp = Ui.text(c, "--°", 44, Ui.TEXT, Ui.BOLD);
        TextView cond = Ui.text(c, "", 15, 0xCCFFFFFF, Ui.MEDIUM);
        temp.setPadding(0, Ui.dp(2), 0, 0);
        col.addView(city);
        col.addView(temp);
        col.addView(cond);
        g.addView(col);

        SharedPreferences p = Prefs.get(c);
        applyWeather(p, city, temp, cond);
        if (System.currentTimeMillis() - p.getLong("w2_time", 0) > WEATHER_MAX_AGE_MS) {
            Handler main = new Handler(Looper.getMainLooper());
            new Thread(() -> {
                try {
                    fetchWeather(p);
                    main.post(() -> applyWeather(p, city, temp, cond));
                } catch (IOException | JSONException | RuntimeException e) {
                    main.post(() -> {
                        if (p.getLong("w2_time", 0) == 0) cond.setText("Unavailable");
                    });
                }
            }, "weather").start();
        }
        return g;
    }

    private static void applyWeather(SharedPreferences p, TextView city, TextView temp, TextView cond) {
        if (p.getLong("w2_time", 0) == 0) return;
        city.setText(p.getString("w2_city", "Weather"));
        temp.setText(p.getString("w2_temp", "--°"));
        cond.setText(p.getString("w2_cond", ""));
    }

    /** Approximate location from the connection's IP, then current conditions from Open-Meteo. */
    private static void fetchWeather(SharedPreferences p) throws IOException, JSONException {
        JSONObject where = new JSONObject(get("https://ipwho.is/?fields=city,country_code,latitude,longitude,success"));
        if (!where.optBoolean("success", false)) throw new IOException("no location");
        // Fahrenheit only where it's the local unit, judged by where the TV is, not its language.
        String country = where.optString("country_code", "");
        boolean fahrenheit = country.equals("US") || country.equals("LR") || country.equals("MM");
        String url = "https://api.open-meteo.com/v1/forecast?latitude=" + where.getDouble("latitude")
                + "&longitude=" + where.getDouble("longitude") + "&current=temperature_2m,weather_code"
                + (fahrenheit ? "&temperature_unit=fahrenheit" : "");
        JSONObject now = new JSONObject(get(url)).getJSONObject("current");
        p.edit()
                .putString("w2_city", where.optString("city", "Weather"))
                .putString("w2_temp", Math.round(now.getDouble("temperature_2m")) + (fahrenheit ? "°F" : "°C"))
                .putString("w2_cond", describe(now.getInt("weather_code")))
                .putLong("w2_time", System.currentTimeMillis())
                .apply();
    }

    private static String describe(int code) {
        if (code == 0) return "Clear";
        if (code <= 2) return "Partly Cloudy";
        if (code == 3) return "Cloudy";
        if (code <= 48) return "Foggy";
        if (code <= 57) return "Drizzle";
        if (code <= 67) return "Rain";
        if (code <= 77) return "Snow";
        if (code <= 82) return "Showers";
        return "Thunderstorms";
    }

    private static String get(String url) throws IOException {
        HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
        con.setConnectTimeout(8000);
        con.setReadTimeout(8000);
        con.setRequestProperty("User-Agent", "TVHome/1.0");
        try (InputStream in = con.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
            return out.toString("UTF-8");
        } finally {
            con.disconnect();
        }
    }

    // ---------------------------------------------------------------- clock and date

    static View clock(Context c) {
        Glass g = card(c, false);
        g.addView(new ClockFace(c), new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return g;
    }

    /** Analog clock with a scalloped face, like the tvOS widget, redrawn once a minute. */
    private static final class ClockFace extends View {
        private final Paint face = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint hand = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.Path scallop = new android.graphics.Path();

        ClockFace(Context c) {
            super(c);
            face.setColor(0xF2FFFFFF);
            hand.setStrokeCap(Paint.Cap.ROUND);
            label.setColor(0xFF1C1C1E);
            label.setTypeface(Ui.BOLD);
            label.setTextAlign(Paint.Align.CENTER);
        }

        @Override
        protected void onDraw(Canvas c) {
            float cx = getWidth() / 2f, cy = getHeight() / 2f;
            float r = Math.min(getWidth(), getHeight()) * 0.36f;
            scallop.reset();
            int bumps = 12;
            for (int i = 0; i <= 360; i += 3) {
                double a = Math.toRadians(i);
                float rr = r * (1f + 0.07f * (float) Math.cos(a * bumps));
                float x = cx + rr * (float) Math.cos(a), y = cy + rr * (float) Math.sin(a);
                if (i == 0) scallop.moveTo(x, y); else scallop.lineTo(x, y);
            }
            scallop.close();
            c.drawPath(scallop, face);

            Calendar now = Calendar.getInstance();
            float min = now.get(Calendar.MINUTE), hr = now.get(Calendar.HOUR) + min / 60f;
            label.setTextSize(r * 0.28f);
            c.drawText(new SimpleDateFormat("EEE d", Locale.getDefault()).format(now.getTime()),
                    cx, cy - r * 0.38f, label);
            drawHand(c, cx, cy, hr / 12f, r * 0.5f, r * 0.13f, 0xFF1C1C1E);
            drawHand(c, cx, cy, min / 60f, r * 0.78f, r * 0.08f, 0xFF3A3A3C);
            hand.setColor(0xFFFF9F0A);
            c.drawCircle(cx, cy, r * 0.07f, hand);
            postInvalidateDelayed(60000 - (System.currentTimeMillis() % 60000));
        }

        private void drawHand(Canvas c, float cx, float cy, float turn, float len, float width, int color) {
            double a = turn * 2 * Math.PI - Math.PI / 2;
            hand.setColor(color);
            hand.setStrokeWidth(width);
            c.drawLine(cx, cy, cx + len * (float) Math.cos(a), cy + len * (float) Math.sin(a), hand);
        }
    }

    static View date(Context c) {
        Glass g = card(c, false);
        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER);
        Date now = new Date();
        TextView day = Ui.text(c, new SimpleDateFormat("EEEE", Locale.getDefault()).format(now).toUpperCase(Locale.getDefault()),
                14, 0xFFFF453A, Ui.BOLD);
        day.setLetterSpacing(0.08f);
        TextView num = Ui.text(c, new SimpleDateFormat("d", Locale.getDefault()).format(now), 54, Ui.TEXT, Ui.BOLD);
        TextView month = Ui.text(c, new SimpleDateFormat("MMMM", Locale.getDefault()).format(now), 15, 0xCCFFFFFF, Ui.MEDIUM);
        for (TextView t : new TextView[] {day, num, month}) {
            t.setGravity(Gravity.CENTER_HORIZONTAL);
            col.addView(t, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        g.addView(col, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return g;
    }

    /** A focusable glass shortcut: icon and label. */
    static Glass shortcut(Context c, int iconRes, String label, View.OnClickListener click) {
        Glass g = card(c, true);
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        ImageView icon = new ImageView(c);
        icon.setImageResource(iconRes);
        icon.setColorFilter(Ui.TEXT);
        row.addView(icon, new LinearLayout.LayoutParams(Ui.dp(26), Ui.dp(26)));
        TextView t = Ui.text(c, label, 17, Ui.TEXT, Ui.BOLD);
        t.setPadding(Ui.dp(12), 0, 0, 0);
        row.addView(t);
        g.addView(row, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        g.setOnClickListener(click);
        return g;
    }

    // ---------------------------------------------------------------- show cards

    /** A tall card showing a featured show's artwork with its name; OK plays the trailer. */
    static final class ShowCard {
        final Glass view;
        private final ImageView art;
        private final TextView label;
        private final TextView title;

        ShowCard(Context c, String heading) {
            view = card(c, true);
            art = new ImageView(c);
            art.setScaleType(ImageView.ScaleType.CENTER_CROP);
            view.addView(art, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            View shade = new View(c);
            shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                    new int[] {0xD9000000, 0x00000000, 0x59000000}));
            view.addView(shade, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            label = Ui.text(c, heading, 13, 0xE6FFFFFF, Ui.BOLD);
            FrameLayout.LayoutParams llp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.START);
            llp.setMargins(Ui.dp(16), Ui.dp(14), Ui.dp(16), 0);
            view.addView(label, llp);
            title = Ui.text(c, "", 19, Ui.TEXT, Ui.BOLD);
            title.setSingleLine(false);
            title.setMaxLines(2);
            FrameLayout.LayoutParams tlp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
            tlp.setMargins(Ui.dp(16), 0, Ui.dp(16), Ui.dp(14));
            view.addView(title, tlp);
        }

        void set(Bitmap bmp, String heading, String name) {
            art.setImageBitmap(bmp);
            label.setText(heading);
            title.setText(name);
        }
    }
}
