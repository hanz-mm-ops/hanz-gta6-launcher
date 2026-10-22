package app.tvshell;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.TextView;

/** Shared look: sizes, colors, typefaces and the tvOS-style focus motion. */
final class Ui {
    static final int BG = 0xFF000000;
    static final int CARD = 0xFF2C2C2E;
    static final int GLASS = 0xCC1C1C1E;
    static final int TEXT = 0xFFFFFFFF;
    static final int TEXT_DIM = 0x99FFFFFF;
    static final Typeface REGULAR = Typeface.create("sans-serif", Typeface.NORMAL);
    static final Typeface MEDIUM = Typeface.create("sans-serif-medium", Typeface.NORMAL);
    static final Typeface BOLD = Typeface.create("sans-serif", Typeface.BOLD);

    static float density = 1f;
    static boolean reduceMotion = false;

    private Ui() {}

    static void init(Context c) {
        density = c.getResources().getDisplayMetrics().density;
        reduceMotion = Prefs.get(c).getBoolean(Prefs.REDUCE_MOTION, false);
    }

    static int dp(float v) {
        return Math.round(v * density);
    }

    static GradientDrawable round(int color, float radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        return g;
    }

    static TextView text(Context c, CharSequence s, float sp, int color, Typeface face) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setTypeface(face);
        t.setIncludeFontPadding(false);
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        return t;
    }

    /** Lift a focused view toward the viewer, the way tvOS does. */
    static void lift(View v, boolean focused, float scale) {
        float s = focused ? scale : 1f;
        v.animate().cancel();
        // One smooth ease-out both ways: no bounce, feels like tvOS gliding into focus.
        v.animate()
                .scaleX(s).scaleY(s)
                .setDuration(reduceMotion ? 80 : (focused ? 220 : 180))
                .setInterpolator(EASE)
                .start();
    }

    static final android.view.animation.Interpolator EASE = new DecelerateInterpolator(2.2f);

    /** Fade a text colour instead of snapping it. */
    static void fade(TextView t, int to) {
        if (t == null) return;
        int from = t.getCurrentTextColor();
        if (from == to) return;
        android.animation.ValueAnimator a = android.animation.ValueAnimator.ofArgb(from, to);
        a.setDuration(reduceMotion ? 0 : 180);
        a.addUpdateListener(x -> t.setTextColor((int) x.getAnimatedValue()));
        a.start();
    }

    /** Fade an icon's tint. */
    static void fade(android.widget.ImageView v, int from, int to) {
        android.animation.ValueAnimator a = android.animation.ValueAnimator.ofArgb(from, to);
        a.setDuration(reduceMotion ? 0 : 180);
        a.addUpdateListener(x -> v.setColorFilter((int) x.getAnimatedValue()));
        a.start();
    }

    /** Average color of a drawable, darkened so white text stays readable on it. */
    static int accent(Drawable d) {
        if (d == null) return 0xFF1C1C1E;
        try {
            Bitmap b = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888);
            Canvas cv = new Canvas(b);
            d.setBounds(0, 0, 8, 8);
            d.draw(cv);
            long r = 0, g = 0, bl = 0, n = 0;
            for (int y = 0; y < 8; y++) {
                for (int x = 0; x < 8; x++) {
                    int p = b.getPixel(x, y);
                    if (Color.alpha(p) < 128) continue;
                    r += Color.red(p);
                    g += Color.green(p);
                    bl += Color.blue(p);
                    n++;
                }
            }
            b.recycle();
            if (n == 0) return 0xFF1C1C1E;
            float[] hsv = new float[3];
            Color.RGBToHSV((int) (r / n), (int) (g / n), (int) (bl / n), hsv);
            hsv[1] = Math.min(hsv[1] * 1.1f, 0.85f);
            hsv[2] = Math.min(hsv[2], 0.55f);
            return Color.HSVToColor(hsv);
        } catch (RuntimeException e) {
            return 0xFF1C1C1E;
        }
    }
}
