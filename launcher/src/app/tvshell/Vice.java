package app.tvshell;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.text.TextPaint;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.animation.Interpolator;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import java.util.HashMap;
import java.util.Map;

/**
 * The Vice look, measured from reference.html. Every size here is a CSS px on the 1920×1080
 * reference; {@link #S} turns it into device pixels, so the layout is the same on 720p, 1080p and
 * 4K panels whatever density the TV reports.
 */
final class Vice {
    static final int PAGE = 0xFF160A1B;
    static final int INK = 0xFF2A0F2C;
    static final int LINK = 0xFFFFD1E6;
    static final int TEMP = 0xFFFFC2DD;
    static final int ACCENT_A = 0xFFFF1F8E;
    static final int ACCENT_B = 0xFFFFA62B;
    /** Fallback card colours for apps without a TV banner, in the spec's order. */
    static final int[] SUNSET = {0xFFE8558F, 0xFFF28A4C, 0xFFB8337A, 0xFF7A3FA0, 0xFFF4A261,
            0xFF4B46A8, 0xFFD9466F, 0xFF8E4AA8, 0xFFC2416A};
    static final int SYSTEM = 0xFF5A4A6E;
    /** CSS `ease`, the timing of every transition in the reference. */
    static final Interpolator EASE = new PathInterpolator(0.25f, 0.1f, 0.25f, 1f);
    static final Interpolator EASE_IN_OUT = new PathInterpolator(0.42f, 0f, 0.58f, 1f);

    static float S = 1f;
    static Typeface ANTON, OSWALD, INTER_500, INTER_600, INTER_700;
    /** Like CSS :focus-visible: no focus ring until the remote is used. */
    static boolean focusVisible;

    private Vice() {}

    static void init(Context c) {
        DisplayMetrics dm = c.getResources().getDisplayMetrics();
        S = Math.max(dm.widthPixels, dm.heightPixels) / 1920f;
        Resources r = c.getResources();
        ANTON = r.getFont(R.font.anton);
        OSWALD = r.getFont(R.font.oswald);
        INTER_500 = r.getFont(R.font.inter_500);
        INTER_600 = r.getFont(R.font.inter_600);
        INTER_700 = r.getFont(R.font.inter_700);
    }

    static int px(float css) {
        return Math.round(css * S);
    }

    static float f(float css) {
        return css * S;
    }

    static int white(float a) {
        return argb(a, 255, 255, 255);
    }

    static int argb(float a, int r, int g, int b) {
        return Color.argb(Math.round(a * 255), r, g, b);
    }

    static int alpha(int color, float a) {
        return (color & 0x00FFFFFF) | (Math.round(a * 255) << 24);
    }

    /** Skia blur radius for a CSS blur length (CSS blur = 2σ; Skia σ = 0.57735·r + 0.5). */
    static float blur(float css) {
        return Math.max(0.1f, (css * S / 2f - 0.5f) / 0.57735f);
    }

    static int mix(int a, int b, float t) {
        return Color.argb(
                Math.round(Color.alpha(a) + (Color.alpha(b) - Color.alpha(a)) * t),
                Math.round(Color.red(a) + (Color.red(b) - Color.red(a)) * t),
                Math.round(Color.green(a) + (Color.green(b) - Color.green(a)) * t),
                Math.round(Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t));
    }

    static FrameLayout.LayoutParams at(int w, int h, int gravity) {
        return new FrameLayout.LayoutParams(w, h, gravity);
    }

    static ImageView icon(Context c, int res, int color) {
        ImageView v = new ImageView(c);
        v.setImageResource(res);
        v.setColorFilter(color);
        v.setScaleType(ImageView.ScaleType.FIT_CENTER);
        return v;
    }

    static ViewOutlineProvider rounded(float radiusCss) {
        return new ViewOutlineProvider() {
            @Override
            public void getOutline(View v, Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(),
                        Math.min(f(radiusCss), Math.min(v.getWidth(), v.getHeight()) / 2f));
            }
        };
    }

    // ---------------------------------------------------------------- text

    /**
     * One line of text laid out the way Chrome does it: the box is exactly the CSS line-height,
     * the glyphs sit in it by half-leading, and letter-spacing trails each character.
     */
    static final class Text extends View {
        final TextPaint paint = new TextPaint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
        private final float lineHeight;
        private String text;
        private int align = Gravity.START;
        private boolean fade;
        private TextPaint over;
        private float fixedWidth = -1;

        Text(Context c, String s, Typeface face, float size, float trackingEm, float lineHeight, int color) {
            super(c);
            text = s == null ? "" : s;
            paint.setTypeface(face);
            paint.setTextSize(f(size));
            paint.setLetterSpacing(trackingEm);
            paint.setColor(color);
            this.lineHeight = f(lineHeight);
        }

        Text tabular() {
            paint.setFontFeatureSettings("'tnum'");
            return this;
        }

        /** Keep the box as wide as the widest two-digit number, so a ticking count never shifts. */
        Text steadyDigits() {
            float max = 0;
            for (char d = '0'; d <= '9'; d++) max = Math.max(max, width("" + d + d));
            fixedWidth = max;
            align = Gravity.CENTER_HORIZONTAL;
            return this;
        }

        Text align(int gravity) {
            align = gravity;
            return this;
        }

        /** CSS text-shadow: 0 dy blur color. */
        Text shadow(float dy, float blurCss, int color) {
            paint.setShadowLayer(blur(blurCss), 0, f(dy), color);
            return this;
        }

        /**
         * Chrome paints text-shadow on top of a background-clip:text fill, which dims the
         * countdown's digits; draw the shadow as a second pass over the gradient to match.
         */
        private void shadowOnTop() {
            over = new TextPaint(paint);
            over.setShader(null);
            over.setColor(0x00000000);
            paint.clearShadowLayer();
        }

        /** The countdown's fill: solid white down to 20 %, then fading out completely at the bottom. */
        Text fade() {
            fade = true;
            return this;
        }

        String get() {
            return text;
        }

        void set(String s) {
            if (s == null) s = "";
            if (s.equals(text)) return;
            boolean resize = fixedWidth <= 0 && width(s) != width(text);
            text = s;
            if (resize) requestLayout();
            invalidate();
        }

        void color(int c) {
            if (paint.getColor() == c) return;
            paint.setColor(c);
            invalidate();
        }

        int color() {
            return paint.getColor();
        }

        /** Exact advance; measureText rounds up to whole pixels, which adds up across a row. */
        float width(String s) {
            return s.isEmpty() ? 0 : paint.getRunAdvance(s, 0, s.length(), 0, s.length(), false, s.length());
        }

        float baseline() {
            Paint.FontMetrics m = paint.getFontMetrics();
            return (lineHeight - (m.descent - m.ascent)) / 2f - m.ascent;
        }

        @Override
        public int getBaseline() {
            return Math.round(baseline());
        }

        /** Height of a capital or digit above the baseline, in px. */
        float capHeight() {
            android.graphics.Rect r = new android.graphics.Rect();
            paint.getTextBounds("H", 0, 1, r);
            return -r.top;
        }

        @Override
        protected void onMeasure(int ws, int hs) {
            int w = Math.round(fixedWidth > 0 ? Math.max(fixedWidth, width(text)) : width(text));
            setMeasuredDimension(resolveSize(w, ws), resolveSize(Math.round(lineHeight), hs));
        }

        @Override
        protected void onSizeChanged(int w, int h, int ow, int oh) {
            if (fade) {
                if (over == null && paint.getShadowLayerRadius() > 0) shadowOnTop();
                paint.setShader(new LinearGradient(0, 0, 0, h, new int[] {0xFFFFFFFF, 0xFFFFFFFF, white(0f)},
                        new float[] {0f, 0.2f, 1f}, Shader.TileMode.CLAMP));
            }
        }

        @Override
        protected void onDraw(Canvas c) {
            String s = text;
            float w = width(s);
            if (w > getWidth() + 0.5f) {
                s = TextUtils.ellipsize(s, paint, getWidth(), TextUtils.TruncateAt.END).toString();
                w = width(s);
            }
            int h = Gravity.getAbsoluteGravity(align, getLayoutDirection()) & Gravity.HORIZONTAL_GRAVITY_MASK;
            float x = h == Gravity.RIGHT ? getWidth() - w : h == Gravity.CENTER_HORIZONTAL ? (getWidth() - w) / 2f : 0;
            // Android spaces letters half before and half after; CSS puts it all after.
            x -= paint.getLetterSpacing() * paint.getTextSize() / 2f;
            // The view is whole pixels tall; keep the CSS line box centred in it.
            float y = baseline() + (getHeight() - lineHeight) / 2f;
            c.drawText(s, x, y, paint);
            if (over != null) c.drawText(s, x, y, over);
        }
    }

    // ---------------------------------------------------------------- backgrounds

    /** One CSS background layer, built for the box's size. */
    interface Layer {
        Shader make(float w, float h);
    }

    /** CSS linear-gradient(&lt;deg&gt;, …): the line runs through the centre, long enough to reach the corners. */
    static Layer linear(float deg, int[] colors, float[] stops) {
        return (w, h) -> {
            double a = Math.toRadians(deg);
            float dx = (float) Math.sin(a), dy = (float) -Math.cos(a);
            float len = Math.abs(w * dx) + Math.abs(h * dy);
            float cx = w / 2f, cy = h / 2f;
            return new LinearGradient(cx - dx * len / 2, cy - dy * len / 2, cx + dx * len / 2, cy + dy * len / 2,
                    colors, stops, Shader.TileMode.CLAMP);
        };
    }

    /** CSS radial-gradient(circle at x% y%, …): the radius reaches the farthest corner. */
    static Layer circle(float fx, float fy, int[] colors, float[] stops) {
        return (w, h) -> {
            float cx = fx * w, cy = fy * h;
            float r = (float) Math.hypot(Math.max(cx, w - cx), Math.max(cy, h - cy));
            return new RadialGradient(cx, cy, r, colors, stops, Shader.TileMode.CLAMP);
        };
    }

    /** CSS radial-gradient(ellipse rx% ry% at x% y%, …). */
    static Layer ellipse(float fx, float fy, float frx, float fry, int[] colors, float[] stops) {
        return (w, h) -> {
            float rx = frx * w, ry = fry * h;
            RadialGradient g = new RadialGradient(0, 0, rx, colors, stops, Shader.TileMode.CLAMP);
            Matrix m = new Matrix();
            m.setScale(1f, ry / rx);
            m.postTranslate(fx * w, fy * h);
            g.setLocalMatrix(m);
            return g;
        };
    }

    /** A CSS box: background colour, gradient layers (first is on top), border and radius. */
    static final class Fill extends Drawable {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        private final int color;
        private final float radius;
        private final Layer[] layers;
        private Shader[] shaders;
        private float border;
        private int borderColor;

        Fill(int color, float radiusCss, Layer... layers) {
            this.color = color;
            this.radius = radiusCss;
            this.layers = layers;
        }

        Fill border(float widthCss, int c) {
            border = widthCss;
            borderColor = c;
            return this;
        }

        @Override
        protected void onBoundsChange(Rect b) {
            shaders = null;
        }

        @Override
        public void draw(Canvas c) {
            Rect b = getBounds();
            float w = b.width(), h = b.height();
            if (w <= 0 || h <= 0) return;
            c.save();
            c.translate(b.left, b.top);
            float rad = Math.min(f(radius), Math.min(w, h) / 2f);
            r.set(0, 0, w, h);
            p.setStyle(Paint.Style.FILL);
            p.setShader(null);
            if (Color.alpha(color) > 0) {
                p.setColor(color);
                c.drawRoundRect(r, rad, rad, p);
            }
            if (layers.length > 0) {
                if (shaders == null) {
                    shaders = new Shader[layers.length];
                    for (int i = 0; i < layers.length; i++) shaders[i] = layers[i].make(w, h);
                }
                p.setColor(0xFFFFFFFF);
                for (int i = layers.length - 1; i >= 0; i--) {
                    p.setShader(shaders[i]);
                    c.drawRoundRect(r, rad, rad, p);
                }
                p.setShader(null);
            }
            if (border > 0) {
                float bw = f(border);
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(bw);
                p.setColor(borderColor);
                r.inset(bw / 2f, bw / 2f);
                float br = Math.max(0f, rad - bw / 2f);
                c.drawRoundRect(r, br, br, p);
            }
            c.restore();
        }

        @Override
        public void setAlpha(int a) {}

        @Override
        public void setColorFilter(ColorFilter cf) {}

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }

    // ---------------------------------------------------------------- shadows

    /** A box-shadow baked once into a bitmap, so scrolling never re-blurs anything. */
    static final class Shadow {
        final Bitmap bitmap;
        final int pad;
        final float dy;

        private Shadow(Bitmap bitmap, int pad, float dy) {
            this.bitmap = bitmap;
            this.pad = pad;
            this.dy = dy;
        }

        void draw(Canvas c, Paint p) {
            c.drawBitmap(bitmap, -pad, -pad + dy, p);
        }
    }

    private static final Map<String, Shadow> SHADOWS = new HashMap<>();

    /** CSS box-shadow: 0 dy blur color, for a w×h px box with the given corner radius. */
    static Shadow boxShadow(int w, int h, float radiusCss, float dyCss, float blurCss, int color) {
        String key = w + "x" + h + "/" + radiusCss + "/" + dyCss + "/" + blurCss + "/" + color;
        Shadow s = SHADOWS.get(key);
        if (s != null) return s;
        int pad = (int) Math.ceil(f(blurCss) * 1.5f) + 2;
        Bitmap b = Bitmap.createBitmap(w + 2 * pad, h + 2 * pad, Bitmap.Config.ARGB_8888);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(color);
        p.setMaskFilter(new BlurMaskFilter(blur(blurCss), BlurMaskFilter.Blur.NORMAL));
        float rad = Math.min(f(radiusCss), Math.min(w, h) / 2f);
        new Canvas(b).drawRoundRect(new RectF(pad, pad, pad + w, pad + h), rad, rad, p);
        s = new Shadow(b, pad, f(dyCss));
        SHADOWS.put(key, s);
        return s;
    }

    /** CSS filter: drop-shadow(0 dy blur color) baked under an image. Returns the padded result. */
    static Bitmap dropShadow(Bitmap src, float dyCss, float blurCss, int color, int[] padOut) {
        int pad = (int) Math.ceil(f(blurCss) * 1.5f + f(dyCss)) + 2;
        Bitmap out = Bitmap.createBitmap(src.getWidth() + 2 * pad, src.getHeight() + 2 * pad, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        Bitmap mask = src.extractAlpha();
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(color);
        p.setMaskFilter(new BlurMaskFilter(blur(blurCss), BlurMaskFilter.Blur.NORMAL));
        c.drawBitmap(mask, pad, pad + f(dyCss), p);
        mask.recycle();
        c.drawBitmap(src, pad, pad, new Paint(Paint.FILTER_BITMAP_FLAG));
        padOut[0] = pad;
        return out;
    }

    // ---------------------------------------------------------------- focus

    interface Progress {
        void at(float t);
    }

    /**
     * A box that draws the reference's focus treatment around itself: 3px white outline 6px out
     * (following the corner radius), an optional lift and an optional shadow that deepens.
     * Content goes in {@link #face}, which is clipped to the rounded shape.
     */
    static final class Tile extends FrameLayout {
        static Tile current;

        final FrameLayout face;
        private final float radius;
        private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint shadowPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
        private final RectF ringRect = new RectF();
        private float lift;
        private float[] rest, lifted;
        private int restColor, liftedColor;
        private Shadow restShadow, liftedShadow;
        private float t;
        private boolean wanted;
        private ValueAnimator anim;
        private Progress progress;
        private boolean showRing = true;

        Tile(Context c, float radiusCss, Drawable background) {
            super(c);
            radius = radiusCss;
            setClipChildren(false);
            setClipToPadding(false);
            setWillNotDraw(false);
            face = new FrameLayout(c);
            face.setBackground(background);
            face.setOutlineProvider(rounded(radiusCss));
            face.setClipToOutline(true);
            addView(face, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            ring.setStyle(Paint.Style.STROKE);
            ring.setStrokeWidth(f(3));
            ring.setColor(0xFFFFFFFF);
        }

        Tile lift(float css) {
            lift = css;
            return this;
        }

        /** CSS box-shadow at rest, and (optionally) while focused. */
        Tile shadow(float dy, float blur, int color, float liftDy, float liftBlur, int liftColor) {
            rest = new float[] {dy, blur};
            restColor = color;
            if (liftBlur > 0) {
                lifted = new float[] {liftDy, liftBlur};
                liftedColor = liftColor;
            }
            return this;
        }

        /** Show focus some other way (via {@link #onProgress}) instead of the white outline. */
        Tile noRing() {
            showRing = false;
            return this;
        }

        Tile onProgress(Progress p) {
            progress = p;
            return this;
        }

        /** Make this tile react to focus on {@code v} (itself, or the button it belongs to). */
        Tile focusOn(View v) {
            View.OnFocusChangeListener before = v.getOnFocusChangeListener();
            v.setOnFocusChangeListener((view, has) -> {
                if (before != null) before.onFocusChange(view, has);
                setWanted(has);
            });
            return this;
        }

        void setWanted(boolean has) {
            wanted = has;
            if (has) current = this;
            apply();
        }

        void apply() {
            float target = wanted && focusVisible ? 1f : 0f;
            if (anim != null) anim.cancel();
            if (t == target) return;
            anim = ValueAnimator.ofFloat(t, target);
            anim.setDuration(250);
            anim.setInterpolator(EASE);
            anim.addUpdateListener(a -> {
                t = (float) a.getAnimatedValue();
                setTranslationY(-f(lift) * t);
                if (progress != null) progress.at(t);
                invalidate();
            });
            anim.start();
        }

        @Override
        protected void onSizeChanged(int w, int h, int ow, int oh) {
            if (rest != null) restShadow = boxShadow(w, h, radius, rest[0], rest[1], restColor);
            if (lifted != null) liftedShadow = boxShadow(w, h, radius, lifted[0], lifted[1], liftedColor);
        }

        @Override
        public void draw(Canvas c) {
            if (restShadow != null) {
                shadowPaint.setAlpha(Math.round(255 * (liftedShadow != null ? 1f - t : 1f)));
                restShadow.draw(c, shadowPaint);
            }
            if (liftedShadow != null && t > 0) {
                shadowPaint.setAlpha(Math.round(255 * t));
                liftedShadow.draw(c, shadowPaint);
            }
            super.draw(c);
            if (t > 0 && showRing) {
                float o = f(6) + f(3) / 2f;
                ringRect.set(-o, -o, getWidth() + o, getHeight() + o);
                float r = radius > 0 ? Math.min(f(radius), Math.min(getWidth(), getHeight()) / 2f) + o : 0;
                ring.setAlpha(Math.round(255 * t));
                c.drawRoundRect(ringRect, r, r, ring);
            }
        }
    }

    /** Clickable, focusable Tile that is its own button. */
    static Tile button(Context c, float radiusCss, Drawable background, View.OnClickListener click) {
        Tile t = new Tile(c, radiusCss, background);
        t.setFocusable(true);
        t.setClickable(true);
        t.setOnClickListener(click);
        t.focusOn(t);
        return t;
    }

    /** Lays children out in rows of n equal columns, CSS-grid style (fractional widths kept). */
    static final class Grid extends ViewGroup {
        private final int cols;
        private final float colGap, rowGap;

        Grid(Context c, int cols, float colGapCss, float rowGapCss) {
            super(c);
            this.cols = cols;
            colGap = f(colGapCss);
            rowGap = f(rowGapCss);
            setClipChildren(false);
            setClipToPadding(false);
        }

        private float colW(int width) {
            return (width - colGap * (cols - 1)) / cols;
        }

        @Override
        protected void onMeasure(int ws, int hs) {
            int width = MeasureSpec.getSize(ws);
            float cw = colW(width);
            float y = 0;
            int n = getChildCount();
            for (int row = 0; row * cols < n; row++) {
                int rowH = 0;
                for (int i = row * cols; i < Math.min(n, row * cols + cols); i++) {
                    View v = getChildAt(i);
                    int col = i - row * cols;
                    int l = Math.round(col * (cw + colGap)), r = Math.round(col * (cw + colGap) + cw);
                    int ch = v.getLayoutParams() != null ? v.getLayoutParams().height : -2;
                    v.measure(MeasureSpec.makeMeasureSpec(r - l, MeasureSpec.EXACTLY), ch >= 0
                            ? MeasureSpec.makeMeasureSpec(ch, MeasureSpec.EXACTLY)
                            : MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
                    rowH = Math.max(rowH, v.getMeasuredHeight());
                }
                y += rowH + (row > 0 ? rowGap : 0);
            }
            setMeasuredDimension(width, Math.round(y));
        }

        @Override
        protected void onLayout(boolean changed, int l0, int t0, int r0, int b0) {
            float cw = colW(r0 - l0);
            float y = 0;
            int n = getChildCount();
            for (int row = 0; row * cols < n; row++) {
                int rowH = 0;
                for (int i = row * cols; i < Math.min(n, row * cols + cols); i++) {
                    View v = getChildAt(i);
                    int col = i - row * cols;
                    int l = Math.round(col * (cw + colGap));
                    int top = Math.round(y);
                    v.layout(l, top, l + v.getMeasuredWidth(), top + v.getMeasuredHeight());
                    rowH = Math.max(rowH, v.getMeasuredHeight());
                }
                y += rowH + rowGap;
            }
        }
    }
}
