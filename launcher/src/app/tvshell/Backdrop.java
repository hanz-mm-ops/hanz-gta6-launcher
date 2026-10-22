package app.tvshell;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Random;

/**
 * Full-screen artwork behind the home screen: cross-fades between images with a slow zoom,
 * and keeps a small blurred copy that Liquid Glass panels sample from.
 */
final class Backdrop extends FrameLayout {
    /** Tiny blurred copy of the current artwork, stretched behind every Glass panel. */
    static Bitmap blurred;
    private static final List<WeakReference<View>> glass = new ArrayList<>();

    private final ImageView[] layers = new ImageView[2];
    private final boolean scrims;
    private final ImageView blurLayer;
    private final View dim;
    private final Random random = new Random();
    private int front = 0;

    Backdrop(Context c, boolean withScrims) {
        super(c);
        scrims = withScrims;
        setBackgroundColor(0xFF000000);
        for (int i = 0; i < 2; i++) {
            layers[i] = new ImageView(c);
            layers[i].setScaleType(ImageView.ScaleType.CENTER_CROP);
            layers[i].setAlpha(0f);
            addView(layers[i], new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
        blurLayer = new ImageView(c);
        blurLayer.setScaleType(ImageView.ScaleType.FIT_XY);
        blurLayer.setAlpha(0f);
        blurLayer.setVisibility(GONE);
        addView(blurLayer, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        dim = new View(c);
        dim.setBackgroundColor(0x8C000000);
        dim.setAlpha(0f);
        dim.setVisibility(GONE);
        addView(dim, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    static void register(View v) {
        glass.add(new WeakReference<>(v));
    }

    private static void refreshGlass() {
        for (Iterator<WeakReference<View>> it = glass.iterator(); it.hasNext(); ) {
            View v = it.next().get();
            if (v == null) it.remove(); else v.invalidate();
        }
    }

    /** Bitmaps should come from {@link #prepare}, which bakes in the darkening gradients. */
    void show(Bitmap bmp) {
        int back = 1 - front;
        ImageView in = layers[back];
        ImageView out = layers[front];
        in.animate().cancel();
        in.setImageBitmap(bmp);
        in.setScaleX(1f);
        in.setScaleY(1f);
        in.setTranslationX(0);
        in.setAlpha(0f);
        in.setVisibility(VISIBLE);
        int fade = Ui.reduceMotion ? 0 : 900;
        in.animate().alpha(1f).setDuration(fade).withEndAction(() -> {
            out.setVisibility(INVISIBLE);
            if (!Ui.reduceMotion) {
                // Slow zoom on a single textured layer: cheap for the GPU.
                float drift = (random.nextBoolean() ? 1 : -1) * getWidth() * 0.02f;
                in.animate().scaleX(1.06f).scaleY(1.06f).translationX(drift)
                        .setDuration(12000).setInterpolator(new LinearInterpolator()).start();
            }
        }).start();
        front = back;

        blurred = blur(bmp);
        blurLayer.setImageBitmap(blurred);
        refreshGlass();
    }

    /** Decode-time work off the main thread: darken the lower and left edges into the image. */
    static Bitmap prepare(Bitmap src, boolean withScrims) {
        if (!withScrims || src == null) return src;
        Bitmap out = src.isMutable() ? src : src.copy(Bitmap.Config.RGB_565, true);
        android.graphics.Canvas c = new android.graphics.Canvas(out);
        int w = out.getWidth(), h = out.getHeight();
        android.graphics.Paint p = new android.graphics.Paint();
        p.setShader(new android.graphics.LinearGradient(0, h * 0.22f, 0, h,
                new int[] {0x00000000, 0x8C000000, 0xF0000000}, null, android.graphics.Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, p);
        p.setShader(new android.graphics.LinearGradient(0, 0, w * 0.62f, 0,
                new int[] {0xA6000000, 0x40000000, 0x00000000}, null, android.graphics.Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w * 0.62f, h, p);
        return out;
    }

    void clear() {
        for (ImageView l : layers) l.animate().alpha(0f).setDuration(300).start();
        blurred = null;
        blurLayer.setImageDrawable(null);
        refreshGlass();
    }

    /** Blur and dim the artwork, as tvOS does when you scroll down into the app grid. */
    void setDimmed(boolean dimmed) {
        int d = Ui.reduceMotion ? 0 : 300;
        for (View v : new View[] {blurLayer, dim}) {
            v.animate().cancel();
            if (dimmed) {
                v.setVisibility(VISIBLE);
                v.animate().alpha(1f).setDuration(d).start();
            } else {
                v.animate().alpha(0f).setDuration(d).withEndAction(() -> v.setVisibility(GONE)).start();
            }
        }
    }

    /** Downscale then box-blur: cheap enough for a 1 GB TV, smooth once stretched back up. */
    static Bitmap blur(Bitmap src) {
        Bitmap small = Bitmap.createScaledBitmap(src, 96, 54, true).copy(Bitmap.Config.ARGB_8888, true);
        int w = small.getWidth(), h = small.getHeight();
        int[] px = new int[w * h];
        small.getPixels(px, 0, w, 0, 0, w, h);
        for (int pass = 0; pass < 3; pass++) {
            boxPass(px, w, h, 3, true);
            boxPass(px, w, h, 3, false);
        }
        small.setPixels(px, 0, w, 0, 0, w, h);
        return small;
    }

    private static void boxPass(int[] px, int w, int h, int r, boolean horizontal) {
        int outer = horizontal ? h : w, inner = horizontal ? w : h;
        int[] line = new int[inner];
        for (int o = 0; o < outer; o++) {
            for (int i = 0; i < inner; i++) line[i] = px[horizontal ? o * w + i : i * w + o];
            for (int i = 0; i < inner; i++) {
                int rs = 0, gs = 0, bs = 0, n = 0;
                for (int k = Math.max(0, i - r); k <= Math.min(inner - 1, i + r); k++) {
                    int p = line[k];
                    rs += (p >> 16) & 0xFF;
                    gs += (p >> 8) & 0xFF;
                    bs += p & 0xFF;
                    n++;
                }
                px[horizontal ? o * w + i : i * w + o] = 0xFF000000 | ((rs / n) << 16) | ((gs / n) << 8) | (bs / n);
            }
        }
    }
}
