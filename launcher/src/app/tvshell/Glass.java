package app.tvshell;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.widget.FrameLayout;

/**
 * Liquid Glass: a panel that shows a blurred copy of the artwork behind it, a light tint,
 * a soft top sheen and a bright rim, like tvOS 26.
 */
class Glass extends FrameLayout {
    private final float radius;
    private final Path clip = new Path();
    private final RectF bounds = new RectF();
    private final RectF rimBounds = new RectF();
    private final Paint image = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final Paint tintPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sheen = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Matrix m = new Matrix();
    private Bitmap shaderBitmap;
    private final int[] loc = new int[2];
    private int tint = 0x26FFFFFF;
    // Smoky base under the tint so white text stays readable on bright artwork.
    private int base = 0x33000000;

    Glass(Context c, float radiusDp) {
        super(c);
        radius = Ui.dp(radiusDp);
        setWillNotDraw(false);
        rim.setStyle(Paint.Style.STROKE);
        rim.setStrokeWidth(Ui.dp(1.3f));
        Backdrop.register(this);
        setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override
            public void getOutline(android.view.View v, android.graphics.Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), Math.min(radius, v.getHeight() / 2f));
            }
        });
    }

    void setBase(int color) {
        base = color;
        invalidate();
    }

    private android.animation.ValueAnimator tintAnim;

    /** Fade to a new tint (used for focus), so highlights glide in and out. */
    void animateTint(int color) {
        if (tintAnim != null) tintAnim.cancel();
        if (Ui.reduceMotion) {
            setTint(color);
            return;
        }
        tintAnim = android.animation.ValueAnimator.ofArgb(tint, color);
        tintAnim.setDuration(180);
        tintAnim.addUpdateListener(a -> setTint((int) a.getAnimatedValue()));
        tintAnim.start();
    }

    void setTint(int color) {
        tint = color;
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        bounds.set(0, 0, w, h);
        float r = Math.min(radius, h / 2f);
        clip.reset();
        clip.addRoundRect(bounds, r, r, Path.Direction.CW);
        float inset = rim.getStrokeWidth() / 2;
        rimBounds.set(inset, inset, w - inset, h - inset);
        sheen.setShader(new LinearGradient(0, 0, 0, h * 0.55f, 0x22FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
        rim.setShader(new LinearGradient(0, 0, w * 0.6f, h, new int[] {0xC0FFFFFF, 0x26FFFFFF, 0x59FFFFFF},
                new float[] {0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        // Every layer is an anti-aliased rounded rect (no clipPath), so corners stay smooth
        // even while the panel is scaled up for focus.
        float r = Math.min(radius, getHeight() / 2f);
        Bitmap b = Backdrop.blurred;
        if (b != null && !b.isRecycled()) {
            if (b != shaderBitmap) {
                shaderBitmap = b;
                image.setShader(new BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
            }
            getLocationOnScreen(loc);
            int sw = getResources().getDisplayMetrics().widthPixels;
            int sh = getResources().getDisplayMetrics().heightPixels;
            m.setScale(sw / (float) b.getWidth(), sh / (float) b.getHeight());
            m.postTranslate(-loc[0], -loc[1]);
            // Undo this view's own scale so the backdrop stays still while the panel lifts.
            float sx = getScaleX(), sy = getScaleY();
            if (sx != 1f || sy != 1f) m.postScale(1f / sx, 1f / sy);
            image.getShader().setLocalMatrix(m);
            canvas.drawRoundRect(bounds, r, r, image);
        } else {
            tintPaint.setColor(0x59202024);
            canvas.drawRoundRect(bounds, r, r, tintPaint);
        }
        tintPaint.setColor(over(tint, base));
        canvas.drawRoundRect(bounds, r, r, tintPaint);
        canvas.drawRoundRect(bounds, r, r, sheen);
        canvas.drawRoundRect(rimBounds, r, r, rim);
        super.dispatchDraw(canvas);
    }

    /** Composite colour {@code top} over {@code bottom}, so the two tints cost one draw. */
    private static int over(int top, int bottom) {
        float ta = (top >>> 24) / 255f, ba = (bottom >>> 24) / 255f;
        float a = ta + ba * (1 - ta);
        if (a <= 0) return 0;
        int r = Math.round((((top >> 16) & 0xFF) * ta + ((bottom >> 16) & 0xFF) * ba * (1 - ta)) / a);
        int gr = Math.round((((top >> 8) & 0xFF) * ta + ((bottom >> 8) & 0xFF) * ba * (1 - ta)) / a);
        int bl = Math.round(((top & 0xFF) * ta + (bottom & 0xFF) * ba * (1 - ta)) / a);
        return (Math.round(a * 255) << 24) | (r << 16) | (gr << 8) | bl;
    }

    /** Just the bright rim and sheen, for app cards whose own artwork fills them. */
    static Drawable rim(float radiusDp) {
        return new Drawable() {
            private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
            private final Paint gloss = new Paint();
            private final RectF r = new RectF();

            @Override
            public void draw(Canvas c) {
                android.graphics.Rect b = getBounds();
                float rad = Ui.dp(radiusDp);
                float sw = Ui.dp(1.4f);
                stroke.setStyle(Paint.Style.STROKE);
                stroke.setStrokeWidth(sw);
                stroke.setShader(new LinearGradient(0, 0, b.width() * 0.6f, b.height(),
                        new int[] {0xB3FFFFFF, 0x1AFFFFFF, 0x4DFFFFFF}, new float[] {0f, 0.55f, 1f},
                        Shader.TileMode.CLAMP));
                gloss.setShader(new LinearGradient(0, 0, 0, b.height() * 0.5f, 0x2EFFFFFF, 0x00FFFFFF,
                        Shader.TileMode.CLAMP));
                r.set(0, 0, b.width(), b.height());
                c.drawRoundRect(r, rad, rad, gloss);
                r.inset(sw / 2, sw / 2);
                c.drawRoundRect(r, rad, rad, stroke);
            }

            @Override
            public void setAlpha(int a) {}

            @Override
            public void setColorFilter(ColorFilter f) {}

            @Override
            public int getOpacity() {
                return PixelFormat.TRANSLUCENT;
            }
        };
    }
}
