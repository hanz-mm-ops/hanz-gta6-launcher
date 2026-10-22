package app.tvshell;

import android.os.Handler;
import android.os.Looper;
import android.service.dreams.DreamService;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import app.tvshell.Vice.Fill;
import app.tvshell.Vice.Text;
import java.util.Calendar;
import java.util.Locale;

import static app.tvshell.Vice.px;
import static app.tvshell.Vice.white;

/** Screen saver: shows {@link Saver}, the home screen's background with just the time and countdown. */
public class AerialDream extends DreamService {
    private Saver saver;

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(false);
        setFullscreen(true);
        setScreenBright(true);
        saver = new Saver(this);
        setContentView(saver);
        saver.start();
    }

    @Override
    public void onDetachedFromWindow() {
        if (saver != null) saver.stop();
        super.onDetachedFromWindow();
    }
}

/**
 * The home screen's background (video or artwork) with no buttons or logo: the release countdown
 * bottom left and the time bottom right, the same size. Used by the screen saver, and by the home
 * screen's logo button.
 */
final class Saver extends FrameLayout {
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Text[] countdown = new Text[4];
    private Text clock, ampm;
    private LinearLayout centre, corner;
    private Backgrounds.View background;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            update();
            main.postDelayed(this, 1000 - System.currentTimeMillis() % 1000 + 5);
        }
    };

    Saver(android.content.Context c) {
        super(c);
        Ui.init(c);
        Vice.init(c);
        FrameLayout root = this;
        root.setBackgroundColor(Vice.PAGE);
        background = new Backgrounds.View(getContext());
        background.apply(Backgrounds.get(getContext(), Backgrounds.HOME));
        root.addView(background, full());

        // The hero's shading, so the text reads the same as on the home screen.
        View shade = new View(getContext());
        shade.setBackground(new Fill(0, 0,
                Vice.ellipse(0.5f, 0.52f, 0.42f, 0.40f,
                        new int[] {Vice.argb(0.55f, 34, 8, 40), Vice.argb(0f, 34, 8, 40)}, new float[] {0f, 1f}),
                Vice.linear(180, new int[] {Vice.argb(0.55f, 22, 10, 27), Vice.argb(0f, 22, 10, 27),
                                Vice.argb(0f, 22, 10, 27), Vice.argb(0.55f, 22, 10, 27), Vice.argb(0.85f, 22, 10, 27)},
                        new float[] {0f, 0.18f, 0.46f, 0.74f, 1f})));
        root.addView(shade, full());

        int glow = Vice.argb(0.6f, 20, 4, 24);
        int labelGlow = Vice.argb(0.55f, 20, 4, 24);

        // Bottom left: the date over the countdown.
        centre = new LinearLayout(getContext());
        centre.setOrientation(LinearLayout.VERTICAL);
        centre.setGravity(Gravity.START);
        centre.addView(new Text(getContext(), "19 NOVEMBER 2026", Vice.INTER_700, 20, 0.28f, 24, 0xFFFFFFFF).shadow(4, 30, glow),
                new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        String[] units = {"DAYS", "HOURS", "MINUTES", "SECONDS"};
        for (int i = 0; i < 4; i++) {
            if (i > 0) row.addView(column(":", "", -1));
            row.addView(column("00", units[i], i));
        }
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.topMargin = px(6);
        centre.addView(row, rlp);
        FrameLayout.LayoutParams llp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.START);
        llp.leftMargin = px(80);
        llp.bottomMargin = px(80);
        root.addView(centre, llp);

        // Bottom right: the time, as big as the countdown, with MORNING / NOON / AFTERNOON / EVENING / NIGHT / MIDNIGHT under it like a unit.
        corner = new LinearLayout(getContext());
        corner.setOrientation(LinearLayout.VERTICAL);
        corner.setGravity(Gravity.CENTER_HORIZONTAL);
        clock = new Text(getContext(), "", Vice.OSWALD, 150, 0.01f, 150, 0xFFFFFFFF).tabular().fade();
        corner.addView(clock, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        ampm = new Text(getContext(), "", Vice.INTER_700, 14, 0.28f, 17, white(0.45f)).shadow(4, 30, labelGlow);
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        alp.topMargin = px(2);
        corner.addView(ampm, alp);
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.END);
        clp.rightMargin = px(80);
        clp.bottomMargin = px(80);
        root.addView(corner, clp);
    }

    void start() {
        background.play(true);
        main.removeCallbacks(tick);
        main.post(tick);
    }

    void stop() {
        main.removeCallbacks(tick);
        background.play(false);
    }

    /** One countdown number (or a colon) with its unit under it, like the home screen's but smaller. */
    private View column(String digits, String unit, int index) {
        LinearLayout col = new LinearLayout(getContext());
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        Text number = new Text(getContext(), digits, Vice.OSWALD, 150, 0.01f, 150, 0xFFFFFFFF).tabular().fade();
        if (index >= 0) {
            number.steadyDigits();
            countdown[index] = number;
        }
        // Wrap, not match: a vertical LinearLayout would squeeze the digits to the label's width.
        col.addView(number, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        llp.topMargin = px(2);
        col.addView(new Text(getContext(), unit, Vice.INTER_700, 14, 0.28f, 17, white(0.45f))
                .shadow(4, 30, Vice.argb(0.55f, 20, 4, 24)), llp);
        return col;
    }

    private void update() {
        Calendar now = Calendar.getInstance();
        int h = now.get(Calendar.HOUR);
        clock.set((h == 0 ? 12 : h) + String.format(Locale.US, ":%02d", now.get(Calendar.MINUTE)));
        // Under the time, the part of the day instead of AM/PM.
        int hour = now.get(Calendar.HOUR_OF_DAY);
        ampm.set(hour < 5 ? "MIDNIGHT" : hour < 12 ? "MORNING" : hour < 13 ? "NOON" : hour < 18 ? "AFTERNOON"
                : hour < 21 ? "EVENING" : "NIGHT");

        Calendar launch = Calendar.getInstance();
        launch.clear();
        launch.set(2026, Calendar.NOVEMBER, 19, 0, 0, 0);
        long s = Math.max(0, (launch.getTimeInMillis() - now.getTimeInMillis()) / 1000);
        long[] parts = {s / 86400, s / 3600 % 24, s / 60 % 60, s % 60};
        for (int i = 0; i < 4; i++) countdown[i].set(String.format(Locale.US, "%02d", parts[i]));
    }

    private static FrameLayout.LayoutParams full() {
        return new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }

}
