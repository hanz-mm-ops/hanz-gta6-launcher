package app.tvshell;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;

import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Toast;
import app.tvshell.Vice.Fill;
import app.tvshell.Vice.Text;
import app.tvshell.Vice.Tile;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static app.tvshell.Vice.f;
import static app.tvshell.Vice.px;
import static app.tvshell.Vice.white;

/**
 * The Vice home screen: a full-screen hero with a release countdown, then Apps, Continue
 * Watching, Smart Home and Weather, scrolled a section at a time with the remote. Every number
 * is a CSS px from reference.html (see {@link Vice}).
 */
public class HomeActivity extends Activity {

    /** One app card: an installed app or a built-in shortcut (mirroring, inputs, settings). */
    static final class Entry {
        String key, label, subtitle;
        Intent launch;
        Drawable banner, icon;
        int category = -1;
        boolean builtIn;
        long lastUsed, useTime;
    }

    private static final int APP_SLOTS = 10;
    private static final String YOUTUBE = "com.google.android.youtube.tv";
    private static final String[] VIDEO_APPS = {YOUTUBE, "com.netflix.ninja", "com.amazon.amazonvideo.livingroom",
            "com.disney.disneyplus", "com.apple.atve.androidtv.appletv", "com.wbd.stream", "net.mbc.shahid",
            "com.google.android.videos", "com.plexapp.android", "org.xbmc.kodi", "com.osn.go", "com.starzplay.tv",
            "com.stremio.one", "tv.hanz.player", "com.google.android.youtube.tvmusic", "com.wbd.stream", "com.watchit.tv"};

    private static final int[][] WATCH_COLORS = {
            {0xFFE8558F, 0xFF4B1A4A}, {0xFFF28A4C, 0xFF5A1F3A}, {0xFF7A3FA0, 0xFF24103A}, {0xFFD9466F, 0xFF3A1236}};

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<String, Entry> entries = new LinkedHashMap<>();

    private FrameLayout root;
    private Page page;
    private LinearLayout column;
    private FrameLayout hero;
    private LinearLayout quickSection, appsSection, watchSection, weatherSection;
    private View footer;
    private Vice.Grid appGrid, watchGrid;
    private LinearLayout weatherRows, inputRow;
    private final Text[] countdown = new Text[4];
    private int countdownCount;
    private Text clock, ampm, today, todayDate, temp, cond, weatherTitle;
    private ImageView weatherIcon;
    private View openApps, backToTop;
    private ValueAnimator bob;
    private Backgrounds.View background;
    private FrameLayout picker;
    private View pickerReturn;
    private FrameLayout appsPage;
    private String movingKey;
    private boolean editMode;
    private FrameLayout actions;
    private View actionsReturn;
    private final List<ObjectAnimator> wiggles = new ArrayList<>();
    private View appsReturn;
    private Saver preview;
    private ObjectAnimator scrolling;
    private Sheet sheet;
    private int screenW, screenH;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            updateClock();
            long now = System.currentTimeMillis();
            main.postDelayed(this, 1000 - now % 1000 + 5);
        }
    };

    private final Runnable weatherTick = new Runnable() {
        @Override
        public void run() {
            loadWeather();
            main.postDelayed(this, 15 * 60 * 1000);
        }
    };

    private final BroadcastReceiver packagesChanged = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            reloadApps();
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        Ui.init(this);
        Vice.init(this);
        DisplayMetrics dm = getResources().getDisplayMetrics();
        screenW = Math.max(dm.widthPixels, dm.heightPixels);
        screenH = Math.min(dm.widthPixels, dm.heightPixels);
        Log.i(ViceData.TAG, "screen " + screenW + "x" + screenH + " density " + dm.density + " scale " + Vice.S);

        buildLayout();
        setContentView(root);
        getWindow().getDecorView().getViewTreeObserver().addOnGlobalFocusChangeListener((was, now) -> follow(now));

        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_PACKAGE_ADDED);
        f.addAction(Intent.ACTION_PACKAGE_REMOVED);
        f.addAction(Intent.ACTION_PACKAGE_CHANGED);
        f.addDataScheme("package");
        registerReceiver(packagesChanged, f);
        applyLayoutExtras(getIntent());
        reloadApps();
        // Start on OPEN APPS, not whatever the framework finds first.
        root.post(() -> {
            focus(openApps);
            Intent i = getIntent();
            if (i != null && i.hasExtra("app.tvshell.PICK_BACKGROUND")) pickBackground(i.getStringExtra("app.tvshell.PICK_BACKGROUND"));
        });
    }

    /**
     * Lets install scripts set the favourites: am start ... --es app.tvshell.DOCK "pkg1,pkg2".
     * --ei app.tvshell.SECTION 0–5 jumps to a section without moving focus (for screenshots).
     * --es app.tvshell.BACKGROUND "set:v,1,4" picks the background (v = the video).
     */
    private boolean applyLayoutExtras(Intent i) {
        if (i == null) return false;
        int section = i.getIntExtra("app.tvshell.SECTION", -1);
        if (section >= 0) {
            root.post(() -> {
                View s = column.getChildAt(Math.min(section, column.getChildCount() - 1));
                int max = Math.max(0, column.getHeight() - page.getHeight());
                page.scrollTo(0, Math.min(max, s.getTop()));
            });
        }
        String bg = i.getStringExtra("app.tvshell.BACKGROUND");
        if (bg != null) {
            Backgrounds.set(this, Backgrounds.HOME, bg);
            if (background != null) background.apply(bg);
        }
        String dock = i.getStringExtra("app.tvshell.DOCK");
        if (dock != null) Prefs.setTopRow(this, new ArrayList<>(java.util.Arrays.asList(dock.split(","))));
        return dock != null;
    }

    @Override
    protected void onDestroy() {
        unregisterReceiver(packagesChanged);
        super.onDestroy();
    }

    @Override
    protected void onResume() {
        super.onResume();
        main.removeCallbacks(tick);
        main.post(tick);
        main.removeCallbacks(weatherTick);
        main.post(weatherTick);
        if (bob != null) bob.start();
        background.apply(Backgrounds.get(this, Backgrounds.HOME));
        background.play(page.getScrollY() < hero.getHeight() || hero.getHeight() == 0);
        loadWatch();
    }

    @Override
    protected void onPause() {
        main.removeCallbacks(tick);
        main.removeCallbacks(weatherTick);
        if (bob != null) bob.cancel();
        background.play(false);
        super.onPause();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (sheet != null && sheet.isShowing()) sheet.dismiss();
        String moveFor = intent.getStringExtra("app.tvshell.MOVE");
        if (moveFor != null && entries.containsKey(moveFor)) {
            startMove(moveFor);
            return;
        }
        String menuFor = intent.getStringExtra("app.tvshell.APP_MENU");
        if (menuFor != null && entries.containsKey(menuFor)) {
            appMenu(entries.get(menuFor));
            return;
        }
        if (intent.getBooleanExtra("app.tvshell.ALL_APPS", false)) {
            allApps();
            return;
        }
        if (intent.getBooleanExtra("app.tvshell.PREVIEW_SAVER", false)) {
            previewSaver();
            return;
        }
        if (intent.hasExtra("app.tvshell.PICK_BACKGROUND")) {
            pickBackground(intent.getStringExtra("app.tvshell.PICK_BACKGROUND"));
            return;
        }
        if (intent.hasExtra("app.tvshell.SECTION") || intent.hasExtra("app.tvshell.BACKGROUND")) {
            applyLayoutExtras(intent);
            return;
        }
        if (applyLayoutExtras(intent)) populateApps();
        goToTop();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus && (getCurrentFocus() == null || !Vice.focusVisible)) focus(openApps);
    }

    @Override
    public void onBackPressed() {
        if (actions != null) closeActions();
        else if (picker != null) closePicker();
        else if (appsPage != null) closeAllApps();
        else if (sheet != null && sheet.isShowing()) sheet.dismiss();
        else goToTop();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (editMode && actions == null) {
            int code = e.getKeyCode();
            if (code == KeyEvent.KEYCODE_BACK) {
                if (e.getAction() == KeyEvent.ACTION_UP) exitMove();
                return true;
            }
            if (movingKey != null) {
                if (e.getAction() == KeyEvent.ACTION_DOWN) {
                    switch (code) {
                        case KeyEvent.KEYCODE_DPAD_LEFT: moveBy(-1); break;
                        case KeyEvent.KEYCODE_DPAD_RIGHT: moveBy(1); break;
                        case KeyEvent.KEYCODE_DPAD_UP: moveBy(-5); break;
                        case KeyEvent.KEYCODE_DPAD_DOWN: moveBy(5); break;
                        case KeyEvent.KEYCODE_DPAD_CENTER: case KeyEvent.KEYCODE_ENTER: dropMove(); break;
                        default: return super.dispatchKeyEvent(e);
                    }
                }
                return true;
            }
        }
        // Any key ends the screen saver preview, like the real one.
        if (preview != null) {
            if (e.getAction() == KeyEvent.ACTION_UP) {
                preview.stop();
                root.removeView(preview);
                preview = null;
                background.play(page.getScrollY() < hero.getHeight());
            }
            return true;
        }
        if (!Vice.focusVisible && e.getAction() == KeyEvent.ACTION_DOWN && isNavKey(e.getKeyCode())) {
            Vice.focusVisible = true;
            if (Tile.current != null) Tile.current.apply();
        }
        return super.dispatchKeyEvent(e);
    }

    private static boolean isNavKey(int k) {
        return k == KeyEvent.KEYCODE_DPAD_UP || k == KeyEvent.KEYCODE_DPAD_DOWN || k == KeyEvent.KEYCODE_DPAD_LEFT
                || k == KeyEvent.KEYCODE_DPAD_RIGHT || k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER
                || k == KeyEvent.KEYCODE_TAB;
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_MENU || keyCode == KeyEvent.KEYCODE_SETTINGS) {
            startActivity(new Intent(this, SettingsActivity.class));
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    // ---------------------------------------------------------------- scrolling

    /** Moves focus with the framework's D-pad search and leaves all scrolling to {@link #follow}. */
    static final class Page extends ScrollView {
        Page(Context c) {
            super(c);
        }

        @Override
        protected int computeScrollDeltaToGetChildRectOnScreen(Rect r) {
            return 0;
        }

        @Override
        public boolean executeKeyEvent(KeyEvent e) {
            return false;
        }
    }

    /** Scroll so the section holding the focused view starts at the top of the screen. */
    private void follow(View now) {
        if (now == null || page == null) return;
        int target;
        View v = now;
        while (v != null && v.getParent() != column && v.getParent() instanceof View) v = (View) v.getParent();
        if (v == null || v.getParent() != column) return;
        if (v == hero) target = 0;
        else if (v == footer || v == weatherSection) target = Integer.MAX_VALUE;
        else target = v.getTop();
        boolean fromHero = page.getScrollY() < hero.getHeight() / 2;
        scrollTo(target);
        if (v == appsSection && fromHero) revealApps();
    }

    private boolean heroShown = true;

    /**
     * Leaving the first screen fades its countdown and buttons out in one go (and back in on return),
     * drawn through a hardware layer so the TV's GPU only composites; the artwork stays put.
     */
    private void heroContent(boolean show) {
        if (hero == null || show == heroShown) return;
        heroShown = show;
        for (int i = 2; i < hero.getChildCount(); i++) {   // 0 = artwork, 1 = shading
            View c = hero.getChildAt(i);
            c.animate().cancel();
            if (Ui.reduceMotion) {
                c.setAlpha(show ? 1f : 0f);
                c.setTranslationY(0);
                continue;
            }
            c.animate().alpha(show ? 1f : 0f).translationY(show ? 0 : -px(30))
                    .setStartDelay(show ? 120 : 0).setDuration(show ? 420 : 260)
                    .setInterpolator(SMOOTH).withLayer().start();
        }
    }

    /** Arriving at Your Apps from the top: the cards rise into place in turn, as the scroll settles. */
    private void revealApps() {
        if (Ui.reduceMotion) return;
        for (int i = 0; i < appGrid.getChildCount(); i++) {
            View c = appGrid.getChildAt(i);
            int row = i / 5, col = i % 5;
            c.animate().cancel();
            c.setAlpha(0f);
            c.setTranslationY(px(48));
            c.animate().alpha(1f).translationY(0)
                    .setStartDelay(160 + row * 90 + col * 40).setDuration(460)
                    .setInterpolator(SMOOTH).withLayer().start();
        }
    }

    /** Fast start, long gentle settle: reads as smooth on a remote. */
    private static final android.view.animation.Interpolator SMOOTH =
            new android.view.animation.PathInterpolator(0.22f, 1f, 0.36f, 1f);

    private void scrollTo(int y) {
        int max = Math.max(0, column.getHeight() - page.getHeight());
        y = Math.max(0, Math.min(max, y));
        if (scrolling != null) scrolling.cancel();
        if (y == page.getScrollY()) return;
        scrolling = ObjectAnimator.ofInt(page, "scrollY", y);
        scrolling.setDuration(Ui.reduceMotion ? 0 : 560);
        scrolling.setInterpolator(SMOOTH);
        heroContent(y < hero.getHeight() / 2);
        scrolling.start();
    }

    private void goToTop() {
        scrollTo(0);
        focus(openApps);
    }

    private void focus(View v) {
        if (v == null) return;
        if (v.isInTouchMode()) v.requestFocusFromTouch(); else v.requestFocus();
    }

    // ---------------------------------------------------------------- layout

    private void buildLayout() {
        root = new FrameLayout(this);
        root.setBackgroundColor(Vice.PAGE);
        page = new Page(this);
        page.setVerticalScrollBarEnabled(false);
        page.setOverScrollMode(View.OVER_SCROLL_NEVER);
        page.setClipChildren(false);
        page.setFocusable(false);
        page.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        // The hero video only plays while the hero is on screen.
        page.setOnScrollChangeListener((v, x, y, ox, oy) -> {
            if (background != null) background.play(y < hero.getHeight());
        });

        column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setClipChildren(false);
        column.setClipToPadding(false);
        page.addView(column, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        column.addView(buildHero(), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(1080)));
        column.addView(buildApps(), fullWidth());
        column.addView(buildWatch(), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(518.2f)));
        column.addView(buildQuick(), fullWidth());
        column.addView(buildWeather(), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(846.5f)));
        column.addView(buildFooter(), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(140.5f)));
        root.addView(page, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private static LinearLayout.LayoutParams fullWidth() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout section(float top, float bottom) {
        LinearLayout s = new LinearLayout(this);
        s.setOrientation(LinearLayout.VERTICAL);
        s.setClipChildren(false);
        s.setClipToPadding(false);
        s.setPadding(px(80), px(top), px(80), px(bottom));
        return s;
    }

    private LinearLayout hbox() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        l.setClipChildren(false);
        l.setClipToPadding(false);
        return l;
    }

    private static LinearLayout.LayoutParams wrap(float leftMargin) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = px(leftMargin);
        return lp;
    }

    private static LinearLayout.LayoutParams size(float w, float h, float leftMargin) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(px(w), px(h));
        lp.leftMargin = px(leftMargin);
        return lp;
    }

    private static LinearLayout.LayoutParams below(float topMargin) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = px(topMargin);
        return lp;
    }

    // ---------------------------------------------------------------- hero

    private View buildHero() {
        hero = new FrameLayout(this);
        hero.setClipChildren(false);
        hero.setClipToPadding(false);

        // The picked background: the skyline video, an artwork, or all artworks in turn.
        background = new Backgrounds.View(this);
        hero.addView(background, match());

        View shade = new View(this);
        shade.setBackground(new Fill(0, 0,
                Vice.ellipse(0.5f, 0.52f, 0.42f, 0.40f,
                        new int[] {Vice.argb(0.55f, 34, 8, 40), Vice.argb(0f, 34, 8, 40)}, new float[] {0f, 1f}),
                Vice.linear(180, new int[] {Vice.argb(0.55f, 22, 10, 27), Vice.argb(0f, 22, 10, 27),
                                Vice.argb(0f, 22, 10, 27), Vice.argb(0.55f, 22, 10, 27), Vice.PAGE},
                        new float[] {0f, 0.18f, 0.46f, 0.74f, 1f})));
        hero.addView(shade, match());

        // The logo sits in the top-left corner where the section links used to be.
        // OK on the logo starts the screen saver.
        Tile logo = Vice.button(this, 12, null, v -> previewSaver()).lift(4).noRing();
        logo.face.setClipToOutline(false);
        logo.face.addView(new Logo(this, 96));
        hero.addView(logo, lp(Gravity.TOP | Gravity.START, 80, 28, 0, 0));
        FrameLayout.LayoutParams status = lp(Gravity.TOP | Gravity.END, 0, 0, 80, 0);
        status.height = px(140);
        hero.addView(buildStatus(), status);
        hero.addView(buildCountdown(), new FrameLayout.LayoutParams(px(1600), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        hero.addView(buildInputs(), lp(Gravity.BOTTOM | Gravity.END, 0, 0, 80, 120));
        hero.addView(buildScrollHint(), lp(Gravity.BOTTOM | Gravity.START, 960, 0, 0, 34));
        return hero;
    }

    private static FrameLayout.LayoutParams match() {
        return new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }

    private static FrameLayout.LayoutParams lp(int gravity, float l, float t, float r, float b) {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, gravity);
        lp.leftMargin = px(l);
        lp.topMargin = px(t);
        lp.rightMargin = px(r);
        lp.bottomMargin = px(b);
        return lp;
    }

    /** Decode a bundled image no larger than needed for {@code width} px. */
    private Bitmap decodeResource(int res, int width) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeResource(getResources(), res, o);
        o.inSampleSize = 1;
        while (o.outWidth / (o.inSampleSize * 2) >= width) o.inSampleSize *= 2;
        o.inJustDecodeBounds = false;
        o.inScaled = false;
        return BitmapFactory.decodeResource(getResources(), res, o);
    }

    /** A link-style control: text that brightens to white, with the focus ring. */
    private Tile link(Text label, int to, View.OnClickListener click) {
        Tile t = Vice.button(this, 0, null, click);
        int from = label.color();
        t.onProgress(p -> label.color(Vice.mix(from, to, p)));
        t.face.addView(label);
        return t;
    }

    /** Apps, Watch or the end of the page: scroll there and focus the first thing in it. */
    private void jump(int which) {
        View target;
        if (which == 0) target = appGrid.getChildCount() > 0 ? appGrid.getChildAt(0) : null;
        else if (which == 1) target = watchGrid.getChildCount() > 0 ? watchGrid.getChildAt(0) : null;
        else target = backToTop;
        if (target != null) focus(target);
    }

    /** Weather and time in one pill, then the Settings button. */
    private View buildStatus() {
        LinearLayout right = hbox();

        // Straight on the artwork, no pill: bigger, brighter, with a soft shadow to stay readable.
        LinearLayout pill = hbox();
        int shade = Vice.argb(0.6f, 20, 4, 24);

        // The condition word ("SUNNY") isn't shown; the icon says it.
        cond = new Text(this, "", Vice.INTER_700, 24, 0.12f, 24, white(0.95f));


        // Date on two lines (TUESDAY / 22 OCT 26), a hairline, then the big time with PM on its
        // baseline; all Oswald, like the mock-up. The two date lines span exactly the digits'
        // height: the day starts at the top of the digits, the date sits on their baseline.
        clock = new Text(this, "", Vice.OSWALD, 80, 0.01f, 80, 0xFFFFFFFF).tabular().shadow(3, 16, shade);
        ampm = new Text(this, "", Vice.OSWALD, 30, 0.04f, 30, 0xFFFFFFFF).shadow(3, 16, shade);
        float digits = clock.capHeight() / Vice.S;      // CSS px
        float dateSize = digits / 2.45f / 0.81f;         // two caps plus a gap of ~0.45 cap
        Text probe = new Text(this, "", Vice.OSWALD, dateSize, 0.02f, dateSize, 0xFFFFFFFF);
        float pitch = (digits - probe.capHeight() / Vice.S) + 0f;   // baseline-to-baseline
        today = new Text(this, "", Vice.OSWALD, dateSize, 0.02f, pitch, 0xFFFFFFFF).shadow(3, 16, shade);
        todayDate = new Text(this, "", Vice.OSWALD, dateSize, 0.02f, pitch, 0xFFFFFFFF).shadow(3, 16, shade);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setBaselineAligned(true);

        // Weather first, at the digits' height: icon as tall as them, temperature the same size.
        int cap = Math.round(clock.capHeight());
        int capTop = Math.round(clock.baseline() - clock.capHeight());
        weatherIcon = Vice.icon(this, R.drawable.vice_sun, 0xFFFFFFFF);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(cap, cap);
        ilp.topMargin = capTop;
        row.addView(weatherIcon, ilp);
        temp = new Text(this, "", Vice.OSWALD, 80, 0.01f, 80, 0xFFFFFFFF).shadow(3, 16, shade);
        row.addView(temp, wrap(16));
        View divider = new View(this);
        divider.setBackgroundColor(white(0.7f));
        LinearLayout.LayoutParams wdl = new LinearLayout.LayoutParams(px(2), cap);
        wdl.leftMargin = px(28);
        wdl.topMargin = capTop;
        row.addView(divider, wdl);
        LinearLayout date = new LinearLayout(this);
        date.setOrientation(LinearLayout.VERTICAL);
        date.addView(today, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        date.addView(todayDate, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        // The date line, not the day, shares the time's baseline.
        date.setBaselineAlignedChildIndex(1);
        row.addView(date, wrap(28));

        // The hairline is as tall as the digits and lines up with them.
        View divider2 = new View(this);
        divider2.setBackgroundColor(white(0.7f));
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(px(2), cap);
        dlp.leftMargin = px(28);
        dlp.topMargin = capTop;
        row.addView(divider2, dlp);

        row.addView(clock, wrap(28));
        row.addView(ampm, wrap(12));
        pill.addView(row, wrap(0));

        right.addView(pill, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, px(96)));
        // Settings lives in the Shortcuts band (and the remote's Menu key), not up here.
        return right;
    }

    /** Release date and the ticking countdown, large and centred on the hero. */
    private View buildCountdown() {
        LinearLayout block = new LinearLayout(this);
        block.setOrientation(LinearLayout.VERTICAL);
        block.setGravity(Gravity.CENTER_HORIZONTAL);
        block.setClipChildren(false);
        block.setClipToPadding(false);

        int shadow = Vice.argb(0.55f, 20, 4, 24);
        Text date = new Text(this, "19 NOVEMBER 2026", Vice.INTER_700, 30, 0.28f, 36, 0xFFFFFFFF).shadow(4, 30, shadow);
        block.addView(date, wrap(0));
        // DD : HH : MM : SS, each number with its unit under it. The digits are solid white at
        // the top, fading out to nothing at the bottom, with no shadow to grey them.
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.TOP);
        String[] units = {"DAYS", "HOURS", "MINUTES", "SECONDS"};
        for (int i = 0; i < 4; i++) {
            if (i > 0) row.addView(countdownColumn(":", ""));
            row.addView(countdownColumn("00", units[i]));
        }
        LinearLayout.LayoutParams clp = wrap(0);
        clp.topMargin = px(8);
        block.addView(row, clp);
        return block;
    }

    /** One number of the countdown (or a colon) with its unit label centred under it. */
    private View countdownColumn(String digits, String unit) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        Text number = new Text(this, digits, Vice.OSWALD, 250, 0.01f, 250, 0xFFFFFFFF).tabular().fade();
        if (!unit.isEmpty()) number.steadyDigits();
        col.addView(number, wrap(0));
        Text label = new Text(this, unit, Vice.INTER_700, 18, 0.28f, 22, white(0.45f))
                .shadow(4, 30, Vice.argb(0.55f, 20, 4, 24));
        LinearLayout.LayoutParams llp = wrap(0);
        llp.topMargin = px(2);
        col.addView(label, llp);
        if (!unit.isEmpty()) countdown[countdownCount++] = number;
        return col;
    }

    /** logo.png at the given height with its drop shadow baked in. */
    private final class Logo extends View {
        private final Bitmap art;
        private final int pad, w, h;

        Logo(Context c, float heightCss) {
            super(c);
            h = px(heightCss);
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inScaled = false;
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeResource(getResources(), R.drawable.vice_logo, o);
            w = Math.round(o.outWidth * h / (float) o.outHeight);
            o.inSampleSize = 1;
            while (o.outHeight / (o.inSampleSize * 2) >= h * 2) o.inSampleSize *= 2;
            o.inJustDecodeBounds = false;
            Bitmap sampled = BitmapFactory.decodeResource(getResources(), R.drawable.vice_logo, o);
            Bitmap scaled = Bitmap.createScaledBitmap(sampled, w, h, true);
            int[] p = new int[1];
            art = Vice.dropShadow(scaled, 10, 30, Vice.argb(0.45f, 20, 4, 24), p);
            pad = p[0];
        }

        @Override
        protected void onMeasure(int ws, int hs) {
            setMeasuredDimension(w, h);
        }

        @Override
        protected void onDraw(Canvas c) {
            c.drawBitmap(art, -pad, -pad, null);
        }
    }

    /**
     * A first-screen button that is just a word (with an optional icon): no frame and no ring. When
     * the cursor reaches it, it fills with the accent gradient.
     */
    private Tile heroWord(String label, int icon, float textSize, float height, View.OnClickListener click) {
        Tile t = Vice.button(this, height / 2, null, click).noRing();
        // Takes the word's size, never asks for more (a bare View would fill all the space offered).
        View glow = new View(this) {
            @Override
            protected void onMeasure(int ws, int hs) {
                setMeasuredDimension(MeasureSpec.getMode(ws) == MeasureSpec.EXACTLY ? MeasureSpec.getSize(ws) : 0,
                        MeasureSpec.getMode(hs) == MeasureSpec.EXACTLY ? MeasureSpec.getSize(hs) : 0);
            }
        };
        glow.setBackground(new Fill(0, height / 2, Vice.linear(90, new int[] {Vice.ACCENT_A, Vice.ACCENT_B}, null)));
        glow.setAlpha(0f);
        t.face.addView(glow, match());
        LinearLayout in = hbox();
        in.setPadding(px(height * 0.45f), 0, px(height * 0.45f), 0);
        if (icon != 0) in.addView(Vice.icon(this, icon, 0xFFFFFFFF), size(textSize, textSize, 0));
        Text text = new Text(this, label, Vice.INTER_700, textSize, 0.06f, textSize * 1.22f, white(0.88f))
                .shadow(2, 12, Vice.argb(0.6f, 20, 4, 24));
        in.addView(text, wrap(icon != 0 ? 10 : 0));
        t.face.addView(in, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
        t.onProgress(p -> {
            glow.setAlpha(p);
            text.color(Vice.mix(white(0.88f), 0xFFFFFFFF, p));
        });
        return t;
    }

    /** Game Mode (the console's HDMI port), AirPlay, Artwork and Settings, as words. */
    private View buildInputs() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.END);
        col.setClipChildren(false);
        col.setClipToPadding(false);
        inputRow = hbox();
        col.addView(inputRow, wrap(0));
        populateInputs();
        return col;
    }

    private void populateInputs() {
        inputRow.removeAllViews();
        List<String[]> found = new ArrayList<>();
        TvInputManager tim = getSystemService(TvInputManager.class);
        if (tim != null) {
            for (TvInputInfo info : tim.getTvInputList()) {
                if (!info.isPassthroughInput() || info.isHidden(this) || info.getType() != TvInputInfo.TYPE_HDMI) continue;
                CharSequence custom = info.loadCustomLabel(this);
                String label = inputName(String.valueOf(custom != null ? custom : info.loadLabel(this)));
                found.add(new String[] {info.getId(), label});
            }
        }
        // The hero keeps just Game Mode (the PS5 port) and AirPlay; every input is in the band below.
        found.removeIf(in -> !"PS5".equals(in[1]));
        if (found.isEmpty()) found.add(new String[] {"", inputName("HDMI 1")});
        found.add(new String[] {Actions.AIRPLAY_KEY, "AirPlay"});
        for (int i = 0; i < found.size(); i++) {
            String[] in = found.get(i);
            boolean game = "PS5".equals(in[1]);
            Tile word = heroWord(game ? "GAME MODE" : in[1].toUpperCase(Locale.ROOT), 0, 17, 48,
                    game ? v -> gameMode() : v -> pickInput(in[0], in[1]));
            LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, px(48));
            plp.leftMargin = i == 0 ? 0 : px(4);
            inputRow.addView(word, plp);
        }
        // After AirPlay: Artwork opens the background picker.
        Tile artwork = heroWord("ARTWORK", 0, 17, 48, v -> pickBackground(Backgrounds.HOME));
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, px(48));
        alp.leftMargin = px(4);
        inputRow.addView(artwork, alp);
        Tile settings = heroWord("SETTINGS", 0, 17, 48, v -> startActivity(new Intent(this, SettingsActivity.class)));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, px(48));
        slp.leftMargin = px(4);
        inputRow.addView(settings, slp);
        // The first screen opens on Game Mode.
        if (inputRow.getChildCount() > 0) openApps = inputRow.getChildAt(0);
    }

    /** Game Mode: the HDMI port a console is plugged into and switched on, else the PS5's port. */
    private void gameMode() {
        String[] port = Actions.consoleInput(this, PORT_NAMES);
        if (port == null) {
            toast(this, "No console found on HDMI");
            return;
        }
        if (!port[2].equals("connected")) toast(this, "Turn your console on: opening " + port[1]);
        pickInput(port[0], port[1]);
    }

    /** What's plugged into each port, by port number; HDMI 1 is the PS5. */
    private static final String[] PORT_NAMES = {null, "PS5"};

    /** "HDMI 1", "HDMI1" or "HDMI 1 (ARC)" → the device's name, if we know it. */
    static String inputName(String label) {
        String n = label.replaceAll("\\s", "").toUpperCase(Locale.ROOT);
        if (!n.startsWith("HDMI") || n.length() < 5 || !Character.isDigit(n.charAt(4))) return label;
        if (n.length() > 5 && Character.isDigit(n.charAt(5))) return label;
        int port = n.charAt(4) - '0';
        return port < PORT_NAMES.length && PORT_NAMES[port] != null ? PORT_NAMES[port] : label;
    }

    private void pickInput(String id, String label) {
        if (id.isEmpty()) {
            toast(this, label + " isn't available on this TV");
            return;
        }
        if (Actions.AIRPLAY_KEY.equals(id) && !Actions.airplay(this)) {
            // TCL and many TVs run AirPlay in the background: there's no screen to open.
            List<Sheet.Item> how = new ArrayList<>();
            how.add(new Sheet.Item("On your iPhone or iPad", "Control Center → Screen Mirroring → choose this TV", getDrawable(R.drawable.vice_app_airplay)));
            how.add(new Sheet.Item("On your Mac", "Control Center → Screen Mirroring → choose this TV", getDrawable(R.drawable.ic_mirror)));
            sheet = Sheet.show(root, "AirPlay", "Ready on this TV", null, how, i -> {});
            return;
        }
        boolean ok = Actions.AIRPLAY_KEY.equals(id) ? true
                : Actions.LIVE_TV_KEY.equals(id) ? Actions.liveTv(this) : Actions.switchInput(this, id);
        if (!ok) {
            toast(this, "Couldn't switch to " + label);
            return;
        }
        Prefs.get(this).edit().putString("vice_input", id).apply();
        main.postDelayed(() -> {
            populateInputs();
            populateQuick();
        }, 500);
    }

    // ---------------------------------------------------------------- inputs and shortcuts

    /** One tile in the Inputs / Shortcuts band. */
    private static final class Quick {
        final String id, label;
        final int icon;
        final boolean dim;
        final Runnable run;

        Quick(String id, String label, int icon, boolean dim, Runnable run) {
            this.id = id;
            this.label = label;
            this.icon = icon;
            this.dim = dim;
            this.run = run;
        }
    }

    private View buildQuick() {
        quickSection = section(60, 40);
        populateQuick();
        return quickSection;
    }

    /**
     * Every TV input (named as the TV names it, e.g. "PS5"; dimmed when nothing is plugged in)
     * and a few shortcuts, as one row of tiles in two groups.
     */
    private void populateQuick() {
        View focused = getCurrentFocus();
        Object focusedKey = focused != null ? focused.getTag() : null;
        quickSection.removeAllViews();

        List<Quick> inputs = new ArrayList<>();
        inputs.add(new Quick(Actions.LIVE_TV_KEY, "Live TV", R.drawable.vice_tv, false,
                () -> pickInput(Actions.LIVE_TV_KEY, "Live TV")));
        TvInputManager tim = getSystemService(TvInputManager.class);
        List<TvInputInfo> hdmi = new ArrayList<>();
        if (tim != null) {
            for (TvInputInfo info : tim.getTvInputList()) {
                if (info.isPassthroughInput() && !info.isHidden(this) && info.getType() == TvInputInfo.TYPE_HDMI) hdmi.add(info);
            }
        }
        hdmi.sort((a, b) -> String.valueOf(a.loadLabel(this)).compareToIgnoreCase(String.valueOf(b.loadLabel(this))));
        for (TvInputInfo info : hdmi) {
            CharSequence custom = info.loadCustomLabel(this);
            String label = inputName(String.valueOf(custom != null ? custom : info.loadLabel(this)));
            boolean unplugged = tim.getInputState(info.getId()) == TvInputManager.INPUT_STATE_DISCONNECTED;
            String up = label.toUpperCase(Locale.ROOT);
            boolean console = up.contains("PS") || up.contains("XBOX") || up.contains("SWITCH") || up.contains("GAME");
            String id = info.getId();
            inputs.add(new Quick(id, label, console ? R.drawable.vice_gamepad : R.drawable.vice_hdmi, unplugged,
                    () -> pickInput(id, label)));
        }
        if (hdmi.isEmpty()) {
            for (int i = 1; i <= 3; i++) {
                String label = inputName("HDMI " + i);
                int icon = label.startsWith("HDMI") ? R.drawable.vice_hdmi : R.drawable.vice_gamepad;
                inputs.add(new Quick("", label, icon, true, () -> pickInput("", label)));
            }
        }

        List<Quick> shortcuts = new ArrayList<>();
        shortcuts.add(new Quick("@allapps", "All Apps", R.drawable.vice_grid, false, this::allApps));
        shortcuts.add(new Quick("@background", "Background", R.drawable.ic_photo, false,
                () -> pickBackground(Backgrounds.HOME)));
        shortcuts.add(new Quick("@sound", "Home Theater", R.drawable.vice_speaker, false,
                () -> Actions.openSystem(this, Settings.ACTION_SOUND_SETTINGS)));
        shortcuts.add(new Quick("@home", "Home Settings", R.drawable.vice_gear, false,
                () -> startActivity(new Intent(this, SettingsActivity.class))));
        shortcuts.add(new Quick("@tv", "TV Settings", R.drawable.vice_sliders, false,
                () -> Actions.openSystem(this, Settings.ACTION_SETTINGS)));

        // Equal tiles across both groups: 16px between tiles, 48px between the groups.
        int n = inputs.size() + shortcuts.size();
        float tile = (1760f - 48 - (n - 2) * 16) / n;
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setClipChildren(false);
        row.setClipToPadding(false);
        float left = inputs.size() * tile + (inputs.size() - 1) * 16;
        float right = shortcuts.size() * tile + (shortcuts.size() - 1) * 16;
        row.addView(quickGroup("INPUTS", inputs), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, left));
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, right);
        rlp.leftMargin = px(48);
        row.addView(quickGroup("SHORTCUTS", shortcuts), rlp);
        quickSection.addView(row, fullWidth());

        if (focusedKey != null) {
            View again = quickSection.findViewWithTag(focusedKey);
            if (again != null) focus(again);
        }
    }

    private View quickGroup(String title, List<Quick> items) {
        LinearLayout g = new LinearLayout(this);
        g.setOrientation(LinearLayout.VERTICAL);
        g.setClipChildren(false);
        g.setClipToPadding(false);
        g.addView(new Text(this, title, Vice.INTER_700, 14, 0.26f, 17, white(0.7f)), wrap(0));
        Vice.Grid grid = new Vice.Grid(this, items.size(), 16, 0);
        for (Quick q : items) grid.addView(quickTile(q));
        g.addView(grid, below(14));
        return g;
    }

    /** Smart Home's card style: dark and outlined, white when it's the input in use. */
    private View quickTile(Quick q) {
        String chosen = Prefs.get(this).getString("vice_input", "");
        boolean on = !q.id.isEmpty() && q.id.equals(chosen);
        int ink = on ? Vice.INK : q.dim ? white(0.35f) : 0xFFFFFFFF;
        Fill fill = on ? new Fill(0xFFFFFFFF, 20)
                : q.dim ? new Fill(0, 20).border(1.5f, white(0.12f))
                : new Fill(white(0.05f), 20).border(1.5f, white(0.2f));
        Tile t = Vice.button(this, 20, fill, v -> q.run.run()).lift(6);
        t.setTag("quick:" + q.id + q.label);

        LinearLayout inner = new LinearLayout(this);
        inner.setOrientation(LinearLayout.VERTICAL);
        inner.setGravity(Gravity.CENTER);
        inner.setPadding(px(10), 0, px(10), 0);
        inner.addView(Vice.icon(this, q.icon, ink), size(30, 30, 0));
        inner.addView(new Text(this, q.label.toUpperCase(Locale.ROOT), Vice.INTER_700, 15, 0.12f, 18, ink)
                .align(Gravity.CENTER_HORIZONTAL), below(16));
        t.face.addView(inner, match());
        t.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(120)));
        return t;
    }

    /** "SCROLL" and a chevron, bobbing on a 2 s loop. */
    private View buildScrollHint() {
        LinearLayout hint = new LinearLayout(this);
        hint.setOrientation(LinearLayout.VERTICAL);
        hint.setGravity(Gravity.CENTER_HORIZONTAL);
        hint.addView(new Text(this, "SCROLL", Vice.INTER_700, 12, 0.3f, 14.5f, 0xFFFFFFFF), wrap(0));
        ImageView chev = Vice.icon(this, R.drawable.vice_chevron, 0xFFFFFFFF);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(px(26), px(26));
        clp.topMargin = px(6);
        hint.addView(chev, clp);
        hint.setAlpha(0.45f);
        if (!Ui.reduceMotion) {
            bob = ValueAnimator.ofFloat(0f, 1f);
            bob.setDuration(2000);
            bob.setRepeatCount(ValueAnimator.INFINITE);
            bob.setInterpolator(null);
            bob.addUpdateListener(a -> {
                float t = (float) a.getAnimatedValue();
                float e = Vice.EASE_IN_OUT.getInterpolation(t < 0.5f ? t * 2 : (1 - t) * 2);
                hint.setAlpha(0.45f + 0.55f * e);
                hint.setTranslationY(f(8) * e);
            });
        }
        return hint;
    }

    private void updateClock() {
        Calendar now = Calendar.getInstance();
        int h = now.get(Calendar.HOUR);
        clock.set((h == 0 ? 12 : h) + String.format(Locale.US, ":%02d", now.get(Calendar.MINUTE)));
        ampm.set(now.get(Calendar.AM_PM) == Calendar.AM ? "AM" : "PM");
        today.set(new java.text.SimpleDateFormat("EEEE", Locale.US).format(now.getTime()).toUpperCase(Locale.ROOT));
        todayDate.set(new java.text.SimpleDateFormat("d MMM yy", Locale.US).format(now.getTime()).toUpperCase(Locale.ROOT));

        Calendar launch = Calendar.getInstance();
        launch.clear();
        launch.set(2026, Calendar.NOVEMBER, 19, 0, 0, 0);
        long s = Math.max(0, (launch.getTimeInMillis() - now.getTimeInMillis()) / 1000);
        long[] parts = {s / 86400, s / 3600 % 24, s / 60 % 60, s % 60};
        for (int i = 0; i < 4; i++) countdown[i].set(String.format(Locale.US, "%02d", parts[i]));
    }

    // ---------------------------------------------------------------- headings

    /** An Anton 72px heading with an optional right-hand item sharing its baseline. */
    private View heading(String title, View right, Text rightText) {
        FrameLayout row = new FrameLayout(this);
        row.setClipChildren(false);
        row.setClipToPadding(false);
        Text h2 = new Text(this, title, Vice.ANTON, 72, 0.02f, 72, 0xFFFFFFFF);
        row.addView(h2, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        if (right != null) {
            FrameLayout.LayoutParams rlp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.END);
            rlp.topMargin = Math.round(h2.baseline() - rightText.baseline());
            row.addView(right, rlp);
        }
        if (title.endsWith("WEATHER")) weatherTitle = h2;
        return row;
    }

    // ---------------------------------------------------------------- apps

    private View buildApps() {
        appsSection = section(90, 40);
        Text seeAllText = new Text(this, "SEE ALL", Vice.INTER_700, 18, 0.2f, 22, Vice.LINK);
        Tile seeAll = link(seeAllText, 0xFFFFFFFF, v -> allApps());
        appsSection.addView(heading("YOUR APPS", seeAll, seeAllText));
        appGrid = new Vice.Grid(this, 5, 24, 36);
        appsSection.addView(appGrid, below(44));
        return appsSection;
    }

    private void reloadApps() {
        new Thread(() -> {
            Map<String, Entry> found = loadEntries();
            main.post(() -> {
                entries.clear();
                entries.putAll(found);
                populateApps();
                loadWatch();
            });
        }, "load-apps").start();
    }

    private Map<String, Entry> loadEntries() {
        PackageManager pm = getPackageManager();
        Map<String, Entry> out = new LinkedHashMap<>();
        addLaunchables(pm, out, Intent.CATEGORY_LEANBACK_LAUNCHER);
        addLaunchables(pm, out, Intent.CATEGORY_LAUNCHER);
        if (Actions.hasMirroring(this)) out.put(Prefs.MIRROR, builtIn(Prefs.MIRROR, "AirPlay", "Cast to TV", R.drawable.vice_app_airplay));
        out.put(Prefs.INPUTS, builtIn(Prefs.INPUTS, "Live TV", "Inputs", R.drawable.vice_app_tv));
        out.put(Prefs.SETTINGS, builtIn(Prefs.SETTINGS, "Settings", "System", R.drawable.vice_app_settings));
        Map<String, long[]> use = ViceData.usage(this);
        for (Entry e : out.values()) {
            long[] u = use.get(e.key);
            if (u != null) {
                e.lastUsed = u[0];
                e.useTime = u[1];
            }
        }
        return out;
    }

    private void addLaunchables(PackageManager pm, Map<String, Entry> out, String category) {
        Intent q = new Intent(Intent.ACTION_MAIN).addCategory(category);
        for (ResolveInfo ri : pm.queryIntentActivities(q, 0)) {
            String pkg = ri.activityInfo.packageName;
            if (out.containsKey(pkg)) continue;
            Entry e = new Entry();
            e.key = pkg;
            e.label = String.valueOf(ri.loadLabel(pm));
            e.launch = new Intent(Intent.ACTION_MAIN).addCategory(category)
                    .setClassName(pkg, ri.activityInfo.name)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            ApplicationInfo ai = ri.activityInfo.applicationInfo;
            int bannerRes = ri.activityInfo.banner != 0 ? ri.activityInfo.banner : ai.banner;
            e.banner = sharp(pm, ai, bannerRes);
            if (e.banner == null && bannerRes != 0) e.banner = ri.activityInfo.loadBanner(pm);
            e.icon = sharp(pm, ai, ri.getIconResource());
            if (e.icon == null) e.icon = ri.loadIcon(pm);
            e.category = ai.category;
            if (e.category == ApplicationInfo.CATEGORY_UNDEFINED && java.util.Arrays.asList(VIDEO_APPS).contains(pkg)) {
                e.category = ApplicationInfo.CATEGORY_VIDEO;
            }
            e.subtitle = subtitle(e.category);
            out.put(pkg, e);
        }
    }

    private static String subtitle(int category) {
        switch (category) {
            case ApplicationInfo.CATEGORY_VIDEO: return "Video";
            case ApplicationInfo.CATEGORY_AUDIO: return "Music";
            case ApplicationInfo.CATEGORY_GAME: return "Games";
            case ApplicationInfo.CATEGORY_IMAGE: return "Photos";
            case ApplicationInfo.CATEGORY_NEWS: return "News";
            case ApplicationInfo.CATEGORY_SOCIAL: return "Social";
            case ApplicationInfo.CATEGORY_MAPS: return "Maps";
            case ApplicationInfo.CATEGORY_PRODUCTIVITY: return "Tools";
            default: return "App";
        }
    }

    /** Load an app's artwork at the highest density it ships, so it stays crisp when scaled up. */
    private static Drawable sharp(PackageManager pm, ApplicationInfo ai, int resId) {
        if (resId == 0) return null;
        try {
            Resources r = pm.getResourcesForApplication(ai);
            Drawable d = r.getDrawableForDensity(resId, DisplayMetrics.DENSITY_XXXHIGH, null);
            if (d instanceof BitmapDrawable) ((BitmapDrawable) d).setFilterBitmap(true);
            return d;
        } catch (PackageManager.NameNotFoundException | Resources.NotFoundException e) {
            return null;
        }
    }

    private Entry builtIn(String key, String label, String subtitle, int iconRes) {
        Entry e = new Entry();
        e.key = key;
        e.label = label;
        e.subtitle = subtitle;
        e.icon = getDrawable(iconRes);
        e.builtIn = true;
        return e;
    }

    /** Favourites first (the dock setting), then the most-used apps, then A–Z. */
    private List<Entry> orderedApps() {
        Set<String> hidden = Prefs.hidden(this);
        List<Entry> out = new ArrayList<>();
        for (String k : Prefs.topRow(this)) {
            Entry e = entries.get(k);
            if (e != null && !out.contains(e)) out.add(e);
        }
        List<Entry> rest = new ArrayList<>();
        for (Entry e : entries.values()) {
            if (out.contains(e) || (hidden.contains(e.key) && !e.builtIn)) continue;
            rest.add(e);
        }
        Collator collator = Collator.getInstance();
        rest.sort((a, b) -> {
            if (a.useTime != b.useTime) return Long.compare(b.useTime, a.useTime);
            if (a.builtIn != b.builtIn) return a.builtIn ? 1 : -1;
            return collator.compare(a.label, b.label);
        });
        out.addAll(rest);
        while (out.size() > APP_SLOTS && !out.get(out.size() - 1).builtIn) out.remove(out.size() - 1);
        while (out.size() > APP_SLOTS) out.remove(out.size() - 1);
        // Settings closes the grid, as in the reference, unless the viewer moved it.
        Entry settings = entries.get(Prefs.SETTINGS);
        if (settings != null && !Prefs.topRow(this).contains(Prefs.SETTINGS)) {
            out.remove(settings);
            while (out.size() > APP_SLOTS - 1) out.remove(out.size() - 1);
            out.add(settings);
        }
        return out;
    }

    private void populateApps() {
        View focused = getCurrentFocus();
        Object focusedKey = focused != null ? focused.getTag() : null;
        for (ObjectAnimator w : wiggles) w.cancel();
        wiggles.clear();
        appGrid.removeAllViews();
        List<Entry> apps = orderedApps();
        int sunset = 0;
        for (Entry e : apps) {
            int color = Prefs.SETTINGS.equals(e.key) ? Vice.SYSTEM : Vice.SUNSET[sunset++ % Vice.SUNSET.length];
            appGrid.addView(appCard(e, color));
        }
        int rows = Math.max(1, (apps.size() + 4) / 5);
        float height = 90 + 72 + 44 + rows * 251.2f + (rows - 1) * 36 + 40;
        appsSection.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(height)));
        if (focusedKey != null) {
            View again = appGrid.findViewWithTag(focusedKey);
            if (again != null) root.post(() -> focus(again));
        }
    }

    /** A 16:9 card (the app's TV banner, or a sunset gradient with its icon) and its name. */
    private View appCard(Entry e, int color) {
        LinearLayout item = new LinearLayout(this);
        item.setOrientation(LinearLayout.VERTICAL);
        item.setClipChildren(false);
        item.setClipToPadding(false);
        item.setFocusable(true);
        item.setClickable(true);
        item.setLongClickable(true);
        item.setTag(e.key);

        Fill bg;
        if (e.banner != null) {
            bg = new Fill(Vice.INK, 20);
        } else {
            bg = new Fill(0, 20,
                    Vice.circle(0.25f, 0.15f, new int[] {white(0.22f), white(0f)}, new float[] {0f, 0.45f}),
                    Vice.linear(155, new int[] {color, Vice.alpha(color, 0xAA / 255f), Vice.INK}, new float[] {0f, 0.45f, 1f}))
                    .border(1, white(0.14f));
        }
        Tile art = new Tile(this, 20, bg).lift(8).shadow(18, 40, Vice.argb(0.3f, 0, 0, 0), 30, 60, Vice.argb(0.45f, 0, 0, 0));
        if (e.banner != null) {
            ImageView banner = new ImageView(this);
            banner.setImageDrawable(e.banner);
            banner.setScaleType(ImageView.ScaleType.CENTER_CROP);
            art.face.addView(banner, match());
            View rim = new View(this);
            rim.setBackground(new Fill(0, 20).border(1, white(0.14f)));
            art.face.addView(rim, match());
        } else {
            ImageView icon = new ImageView(this);
            icon.setImageDrawable(e.icon);
            icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
            if (e.builtIn) icon.setColorFilter(0xFFFFFFFF);
            art.face.addView(icon, Vice.at(px(60), px(60), Gravity.CENTER));
        }
        art.focusOn(item);
        item.addView(art, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(187.2f)));

        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.setPadding(px(4), 0, px(4), 0);
        boolean lifted = e.key.equals(movingKey);
        labels.addView(new Text(this, e.label.toUpperCase(Locale.ROOT), Vice.INTER_700, 20, 0.1f, 24.5f,
                lifted ? Vice.ACCENT_B : 0xFFFFFFFF), below(0));
        labels.addView(new Text(this, lifted ? "◀ ▶ ▲ ▼ to move · OK to drop"
                : editMode && appsPage == null ? "OK to pick up · BACK when done" : e.subtitle, Vice.INTER_500, 16, 0, 19.5f,
                lifted ? Vice.LINK : white(0.55f)), below(4));
        if (lifted) {
            View ring = new View(this);
            ring.setBackground(new Fill(0, 20).border(4, Vice.ACCENT_B));
            art.face.addView(ring, match());
        }
        item.addView(labels, below(16));

        item.setOnClickListener(v -> {
            if (editMode && appsPage == null) {
                movingKey = e.key;
                populateApps();
                focusMoving();
            } else {
                open(e);
            }
        });
        item.setOnLongClickListener(v -> {
            if (!editMode) appMenu(e);
            return true;
        });
        // In move mode every card wiggles a little; the lifted one stays still and grows.
        if (editMode && appsPage == null) {
            if (e.key.equals(movingKey)) {
                art.setScaleX(1.06f);
                art.setScaleY(1.06f);
            } else {
                ObjectAnimator w = ObjectAnimator.ofFloat(art, "rotation", -0.8f, 0.8f);
                w.setDuration(260 + (Math.abs(e.key.hashCode()) % 80));
                w.setRepeatCount(ObjectAnimator.INFINITE);
                w.setRepeatMode(ObjectAnimator.REVERSE);
                if (!Ui.reduceMotion) w.start();
                wiggles.add(w);
                FrameLayout badge = new FrameLayout(this);
                badge.setBackground(new Fill(0xFF1F1024, 10).border(1.5f, white(0.5f)));
                badge.addView(Vice.icon(this, R.drawable.vice_move, 0xFFFFFFFF), Vice.at(px(20), px(20), Gravity.CENTER));
                FrameLayout.LayoutParams blp = Vice.at(px(36), px(36), Gravity.TOP | Gravity.END);
                blp.topMargin = px(10);
                blp.rightMargin = px(10);
                art.face.addView(badge, blp);
            }
        }
        return item;
    }

    private void open(Entry e) {
        switch (e.key) {
            case Prefs.MIRROR:
                sheet = Actions.mirroring(root);
                break;
            case Prefs.INPUTS:
                sheet = Actions.inputs(root);
                break;
            case Prefs.SETTINGS:
                startActivity(new Intent(this, SettingsActivity.class));
                break;
            case "@store":
                Intent store = getPackageManager().getLeanbackLaunchIntentForPackage("com.android.vending");
                if (store == null) store = getPackageManager().getLaunchIntentForPackage("com.android.vending");
                if (store == null) store = new Intent(Intent.ACTION_VIEW, Uri.parse("market://search?q=&c=apps"));
                Actions.startOrSay(this, store, "The Play Store isn't on this TV");
                break;
            default:
                Actions.startOrSay(this, e.launch, "Couldn't open " + e.label);
        }
        if (appsPage != null) main.postDelayed(this::closeAllApps, 400);
    }

    // ---------------------------------------------------------------- moving apps

    /**
     * Move mode: every app wiggles; OK picks one up, the arrows move it, OK drops it. The mode
     * stays on for the next app until BACK.
     */
    private void startMove(String key) {
        // Freeze what's on screen as the viewer's own order, so moving one app moves only that one.
        List<String> order = new ArrayList<>();
        for (Entry x : orderedApps()) order.add(x.key);
        Prefs.setTopRow(this, order);
        editMode = true;
        movingKey = key;
        Vice.focusVisible = true;
        populateApps();
        focusMoving();
        toast(this, "Move mode · OK picks up and drops · BACK when done");
    }

    private void exitMove() {
        String key = movingKey;
        editMode = false;
        movingKey = null;
        populateApps();
        View v = key != null ? appGrid.findViewWithTag(key) : null;
        if (v == null) v = getCurrentFocus();
        if (v != null) focus(v);
    }

    private void moveBy(int delta) {
        List<String> order = new ArrayList<>();
        for (Entry x : orderedApps()) order.add(x.key);
        int from = order.indexOf(movingKey);
        if (from < 0) return;
        int to = Math.max(0, Math.min(order.size() - 1, from + delta));
        if (to == from) return;
        order.remove(from);
        order.add(to, movingKey);
        Prefs.setTopRow(this, order);
        populateApps();
        focusMoving();
    }

    /** Drop the lifted app where it is; move mode stays on for the next one. */
    private void dropMove() {
        String key = movingKey;
        movingKey = null;
        populateApps();
        View v = appGrid.findViewWithTag(key);
        if (v != null) focus(v);
    }

    private void focusMoving() {
        View v = appGrid.findViewWithTag(movingKey);
        if (v != null) root.post(() -> focus(v));
    }

    /** Every app, A–Z, full screen in the Your Apps card style (SEE ALL and the All Apps tile). */
    private void allApps() {
        if (sheet != null && sheet.isShowing()) sheet.dismiss();
        if (appsPage != null) return;
        appsReturn = getCurrentFocus();
        Set<String> hidden = Prefs.hidden(this);
        List<Entry> all = new ArrayList<>();
        for (Entry e : entries.values()) if (!hidden.contains(e.key) || e.builtIn) all.add(e);
        Collator collator = Collator.getInstance();
        all.sort((a, b) -> collator.compare(a.label, b.label));

        appsPage = new FrameLayout(this);
        appsPage.setBackgroundColor(Vice.alpha(Vice.PAGE, 0.97f));
        appsPage.setClickable(true);
        ScrollView scroller = overlayScroller();
        LinearLayout body = section(80, 120);
        body.addView(new Text(this, "EVERYTHING ON THIS TV", Vice.INTER_700, 14, 0.26f, 17, white(0.7f)), wrap(0));
        Text count = new Text(this, all.size() + " APPS", Vice.INTER_700, 18, 0.2f, 22, white(0.6f));
        LinearLayout.LayoutParams hlp = below(14);
        body.addView(heading("ALL APPS", count, count), hlp);
        Vice.Grid grid = new Vice.Grid(this, 5, 24, 36);
        for (int i = 0; i < all.size(); i++) {
            Entry e = all.get(i);
            int color = Prefs.SETTINGS.equals(e.key) ? Vice.SYSTEM : Vice.SUNSET[i % Vice.SUNSET.length];
            grid.addView(appCard(e, color));
        }
        // Last card: get more apps from the Play Store.
        Entry store = new Entry();
        store.key = "@store";
        store.label = "Get More Apps";
        store.subtitle = "Play Store";
        store.icon = getDrawable(R.drawable.ic_plus);
        store.builtIn = true;
        grid.addView(appCard(store, Vice.SUNSET[all.size() % Vice.SUNSET.length]));
        body.addView(grid, below(44));
        scroller.addView(body, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        appsPage.addView(scroller, match());
        root.addView(appsPage, match());
        Vice.focusVisible = true;
        if (grid.getChildCount() > 0) root.post(() -> focus(grid.getChildAt(0)));
    }

    private void closeAllApps() {
        if (appsPage == null) return;
        root.removeView(appsPage);
        appsPage = null;
        View back = appsReturn != null && appsReturn.isAttachedToWindow() ? appsReturn : openApps;
        appsReturn = null;
        focus(back);
    }

    /**
     * A plain scroller for full-screen pages: it follows focus (unlike {@link Page}) and keeps
     * a margin so a lifted card's ring never touches the edge.
     */
    private ScrollView overlayScroller() {
        ScrollView sc = new ScrollView(this);
        sc.setVerticalScrollBarEnabled(false);
        sc.setOverScrollMode(View.OVER_SCROLL_NEVER);
        sc.setVerticalFadingEdgeEnabled(true);
        sc.setFadingEdgeLength(px(90));
        sc.setSmoothScrollingEnabled(true);
        return sc;
    }

    /** Hold OK on an app: square Move / Remove / Info buttons float over the app's own card. */
    private void appMenu(Entry e) {
        View card = getCurrentFocus();
        if (card == null || !e.key.equals(card.getTag())) card = (appsPage != null ? appsPage : appGrid).findViewWithTag(e.key);
        if (card == null) return;
        closeActions();
        List<String> favourites = Prefs.topRow(this);
        boolean isFavourite = favourites.contains(e.key);
        boolean onHome = appsPage == null && orderedApps().contains(e);

        List<Object[]> acts = new ArrayList<>();
        if (onHome) acts.add(new Object[] {R.drawable.vice_move, "MOVE", (Runnable) () -> startMove(e.key)});
        acts.add(new Object[] {isFavourite ? R.drawable.vice_minus : R.drawable.vice_plus_circle,
                isFavourite ? "REMOVE" : "ADD", (Runnable) () -> {
                    favourites.remove(e.key);
                    if (!isFavourite) favourites.add(0, e.key);
                    Prefs.setTopRow(this, favourites);
                    populateApps();
                    toast(this, isFavourite ? e.label + " removed from Your Apps" : e.label + " added to Your Apps");
                }});
        if (!e.builtIn) acts.add(new Object[] {R.drawable.vice_info, "INFO", (Runnable) () ->
                Actions.startOrSay(this, new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", e.key, null)), "App info isn't available")});

        actions = new FrameLayout(this);
        actions.setClickable(true);
        actions.setBackgroundColor(Vice.argb(0.35f, 22, 10, 27));
        actionsReturn = card;
        // A tray the size of the card, in the buttons' colour at 70 %; equal columns so the three sit evenly.
        LinearLayout row = hbox();
        row.setGravity(Gravity.CENTER);
        row.setBackground(new Fill(Vice.alpha(0xFF1F1024, 0.7f), 20));
        row.setClipChildren(false);
        row.setClipToPadding(false);
        List<View> buttons = new ArrayList<>();
        for (int i = 0; i < acts.size(); i++) {
            Object[] a0 = acts.get(i);
            Runnable run = (Runnable) a0[2];
            LinearLayout col = new LinearLayout(this);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setGravity(Gravity.CENTER_HORIZONTAL);
            col.setClipChildren(false);
            col.setClipToPadding(false);
            // Rounded squares, like the icon boxes on the Smart Home cards.
            Tile b = Vice.button(this, 18, new Fill(0xFF1F1024, 18).border(1.5f, white(0.3f)), v -> {
                closeActions();
                run.run();
            }).lift(4).noRing().shadow(14, 30, Vice.argb(0.5f, 0, 0, 0), 0, 0, 0);
            // Focus fills the button with the accent gradient instead of drawing a ring.
            View glow = new View(this);
            glow.setBackground(new Fill(0, 18, Vice.linear(135, new int[] {Vice.ACCENT_A, Vice.ACCENT_B}, null)));
            glow.setAlpha(0f);
            b.face.addView(glow, match());
            b.onProgress(glow::setAlpha);
            b.face.addView(Vice.icon(this, (Integer) a0[0], 0xFFFFFFFF), Vice.at(px(28), px(28), Gravity.CENTER));
            col.addView(b, new LinearLayout.LayoutParams(px(64), px(64)));
            Text label = new Text(this, (String) a0[1], Vice.INTER_700, 12, 0.16f, 15, white(0.85f)).align(Gravity.CENTER_HORIZONTAL);
            LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            tl.topMargin = px(10);
            col.addView(label, tl);
            row.addView(col, new LinearLayout.LayoutParams(px(88), ViewGroup.LayoutParams.WRAP_CONTENT));
            buttons.add(b);
        }
        // Keep the remote on these buttons until one is chosen or BACK.
        for (int i = 0; i < buttons.size(); i++) {
            final boolean first = i == 0, last = i == buttons.size() - 1;
            buttons.get(i).setOnKeyListener((v, code, ev) -> {
                if (ev.getAction() != KeyEvent.ACTION_DOWN) return false;
                return code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN
                        || (first && code == KeyEvent.KEYCODE_DPAD_LEFT) || (last && code == KeyEvent.KEYCODE_DPAD_RIGHT);
            });
        }
        // Exactly over the card's picture.
        int[] at = new int[2], base = new int[2];
        View art = ((ViewGroup) card).getChildAt(0);
        art.getLocationInWindow(at);
        root.getLocationInWindow(base);
        FrameLayout.LayoutParams lp = Vice.at(art.getWidth(), art.getHeight(), Gravity.TOP | Gravity.START);
        // Where the card rests: it is lifted while focused and drops back once the buttons take focus.
        lp.leftMargin = at[0] - base[0] - Math.round(art.getTranslationX());
        lp.topMargin = at[1] - base[1] - Math.round(art.getTranslationY());
        actions.addView(row, lp);
        root.addView(actions, match());
        row.setAlpha(0f);
        row.setScaleX(0.9f);
        row.setScaleY(0.9f);
        row.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(Ui.reduceMotion ? 0 : 180).setInterpolator(Vice.EASE).start();
        Vice.focusVisible = true;
        root.post(() -> focus(buttons.get(0)));
    }

    private void closeActions() {
        if (actions == null) return;
        root.removeView(actions);
        actions = null;
        if (actionsReturn != null && actionsReturn.isAttachedToWindow() && movingKey == null && !editMode) focus(actionsReturn);
        actionsReturn = null;
    }

    // ---------------------------------------------------------------- continue watching

    private View buildWatch() {
        watchSection = section(60, 40);
        watchSection.addView(heading("CONTINUE WATCHING", null, null));
        watchGrid = new Vice.Grid(this, 4, 28, 28);
        watchSection.addView(watchGrid, below(44));
        return watchSection;
    }

    /**
     * The last thing watched in each app: Android TV's Watch Next where it's shared with us, and
     * otherwise what MediaWatcher saw playing. One card per app; with nothing to show, the
     * section is hidden.
     */
    private void loadWatch() {
        if (entries.isEmpty() || watchGrid == null) return;
        new Thread(() -> {
            List<ViceData.Watch> rows = new ArrayList<>();
            List<String> seen = new ArrayList<>();
            List<ViceData.Watch> next = ViceData.watchNext(this, 12);
            List<ViceData.Watch> mine = ViceData.lastWatched(this, 12);
            List<ViceData.Watch> all = new ArrayList<>();
            if (next != null) all.addAll(next);
            all.addAll(mine);
            all.sort((x, y) -> Long.compare(y.engaged, x.engaged));
            for (ViceData.Watch w : all) {
                if (w.pkg == null || seen.contains(w.pkg) || !entries.containsKey(w.pkg)) continue;
                seen.add(w.pkg);
                rows.add(w);
                if (rows.size() == 4) break;
            }
            for (ViceData.Watch w : rows) {
                Entry app = entries.get(w.pkg);
                if (w.tag == null) w.tag = app != null ? app.label.toUpperCase(Locale.ROOT) : "";
                if (w.launch == null && app != null) w.launch = app.launch;
            }
            Bitmap[] posters = new Bitmap[rows.size()];
            for (int i = 0; i < rows.size(); i++) posters[i] = ViceData.poster(this, rows.get(i).poster, px(419), px(236));
            Log.i(ViceData.TAG, "continue watching: " + rows.size() + " (watch next " + (next == null ? "closed" : next.size()) + ", watched " + mine.size() + ")");
            main.post(() -> populateWatch(rows, posters));
        }, "watch").start();
    }

    private void populateWatch(List<ViceData.Watch> rows, Bitmap[] posters) {
        watchGrid.removeAllViews();
        for (int i = 0; i < rows.size(); i++) watchGrid.addView(watchCard(rows.get(i), posters[i], i));
        watchSection.setVisibility(rows.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private View watchCard(ViceData.Watch w, Bitmap poster, int index) {
        LinearLayout item = new LinearLayout(this);
        item.setOrientation(LinearLayout.VERTICAL);
        item.setClipChildren(false);
        item.setClipToPadding(false);
        item.setFocusable(true);
        item.setClickable(true);

        int[] c = WATCH_COLORS[index % WATCH_COLORS.length];
        Fill bg = new Fill(0, 20,
                Vice.circle(0.75f, 0.2f, new int[] {white(0.25f), white(0f)}, new float[] {0f, 0.45f}),
                Vice.linear(150, new int[] {c[0], c[1]}, null));
        Tile art = new Tile(this, 20, bg).lift(8).shadow(18, 40, Vice.argb(0.3f, 0, 0, 0), 30, 60, Vice.argb(0.45f, 0, 0, 0));
        if (poster != null) {
            ImageView img = new ImageView(this);
            img.setImageDrawable(new BitmapDrawable(getResources(), poster));
            img.setScaleType(ImageView.ScaleType.CENTER_CROP);
            art.face.addView(img, match());
        }
        View rim = new View(this);
        rim.setBackground(new Fill(0, 20).border(1, white(0.14f)));
        art.face.addView(rim, match());

        FrameLayout play = new FrameLayout(this);
        play.setBackground(new Fill(white(0.92f), 38));
        play.addView(Vice.icon(this, R.drawable.vice_play, Vice.INK), Vice.at(px(30), px(30), Gravity.CENTER));
        art.face.addView(play, Vice.at(px(76), px(76), Gravity.CENTER));

        if (w.tag != null && !w.tag.isEmpty()) {
            FrameLayout tag = new FrameLayout(this);
            tag.setBackground(new Fill(Vice.argb(0.55f, 22, 10, 27), 6));
            tag.setPadding(px(12), px(6), px(12), px(6));
            tag.addView(new Text(this, w.tag, Vice.INTER_700, 13, 0.16f, 15.5f, 0xFFFFFFFF));
            FrameLayout.LayoutParams tlp = Vice.at(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.START);
            tlp.leftMargin = px(15);
            tlp.topMargin = px(15);
            art.face.addView(tag, tlp);
        }

        if (w.progress >= 0) {
            FrameLayout track = new FrameLayout(this);
            track.setBackgroundColor(white(0.22f));
            View fill = new View(this);
            fill.setBackground(new Fill(0, 0, Vice.linear(90, new int[] {Vice.ACCENT_A, Vice.ACCENT_B}, null)));
            track.addView(fill, Vice.at(Math.round(f(417) * w.progress), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.START));
            FrameLayout.LayoutParams blp = Vice.at(ViewGroup.LayoutParams.MATCH_PARENT, px(6), Gravity.BOTTOM);
            blp.leftMargin = px(1);
            blp.rightMargin = px(1);
            blp.bottomMargin = px(1);
            art.face.addView(track, blp);
        }
        art.focusOn(item);
        item.addView(art, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(235.7f)));

        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.setPadding(px(4), 0, px(4), 0);
        labels.addView(new Text(this, w.title == null ? "" : w.title, Vice.INTER_700, 22, 0, 27, 0xFFFFFFFF), below(0));
        labels.addView(new Text(this, w.meta == null ? "" : w.meta, Vice.INTER_500, 16, 0, 19.5f, white(0.55f)), below(4));
        item.addView(labels, below(16));

        item.setOnClickListener(v -> {
            Log.i(ViceData.TAG, "continue watching: open " + w.pkg);
            if (w.launch != null) Actions.startOrSay(this, w.launch, "Couldn't open " + w.title);
            else toast(this, "Couldn't open " + w.title);
        });
        return item;
    }

    // ---------------------------------------------------------------- weather

    private View buildWeather() {
        weatherSection = section(60, 80);
        weatherSection.addView(heading("WEATHER", null, null));
        weatherRows = new LinearLayout(this);
        weatherRows.setOrientation(LinearLayout.VERTICAL);
        weatherSection.addView(weatherRows, below(36));
        showWeather(ViceData.cachedWeather(this));
        return weatherSection;
    }

    private void loadWeather() {
        ViceData.Weather cached = ViceData.cachedWeather(this);
        if (cached != null) showWeather(cached);
        if (cached != null && !ViceData.weatherStale(this)) return;
        new Thread(() -> {
            try {
                ViceData.Weather w = ViceData.fetchWeather(this);
                main.post(() -> showWeather(w));
            } catch (Exception e) {
                Log.i(ViceData.TAG, "weather: " + e);
            }
        }, "weather").start();
    }

    private void showWeather(ViceData.Weather w) {
        if (w == null) return;
        temp.set(w.temp + "°");
        cond.set(w.cond);
        boolean clear = w.code <= 1;
        weatherIcon.setImageResource(!clear ? R.drawable.vice_cloud : w.day ? R.drawable.vice_sun : R.drawable.vice_moon);
        weatherTitle.set(w.city.isEmpty() ? "WEATHER" : w.city.toUpperCase(Locale.ROOT) + " WEATHER");

        weatherRows.removeAllViews();
        weatherRows.addView(rule(0.18f), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(1)));
        for (ViceData.Row r : w.rows) {
            LinearLayout row = hbox();
            row.setPadding(0, px(26), 0, px(26));
            row.addView(new Text(this, r.label, Vice.INTER_700, 17, 0.2f, 20.5f, white(0.6f)),
                    new LinearLayout.LayoutParams(px(220), ViewGroup.LayoutParams.WRAP_CONTENT));
            row.addView(new Text(this, r.cond, Vice.ANTON, 36, 0.02f, 54.5f, 0xFFFFFFFF),
                    new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            row.addView(new Text(this, r.temp + "°", Vice.ANTON, 44, 0, 66.5f, Vice.TEMP).align(Gravity.END),
                    new LinearLayout.LayoutParams(px(160), ViewGroup.LayoutParams.WRAP_CONTENT));
            weatherRows.addView(row, fullWidth());
            weatherRows.addView(rule(0.18f), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(1)));
        }
    }

    private View rule(float alpha) {
        View v = new View(this);
        v.setBackgroundColor(white(alpha));
        return v;
    }

    // ---------------------------------------------------------------- footer

    private View buildFooter() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setClipChildren(false);
        box.setClipToPadding(false);
        box.addView(rule(0.12f), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(1)));
        FrameLayout inner = new FrameLayout(this);
        inner.setClipChildren(false);
        inner.setClipToPadding(false);
        inner.setPadding(px(80), px(50), px(80), px(70));
        Text label = new Text(this, "BACK TO TOP ↑", Vice.INTER_700, 16, 0.24f, 19.5f, Vice.LINK);
        Tile top = link(label, 0xFFFFFFFF, v -> goToTop());
        inner.addView(top, Vice.at(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.END));
        backToTop = top;
        box.addView(inner, fullWidth());
        footer = box;
        return box;
    }

    /** The screen saver, full screen over the home screen, until a key is pressed (the logo opens it). */
    private void previewSaver() {
        if (preview != null) return;
        background.play(false);
        preview = new Saver(this);
        root.addView(preview, match());
        preview.start();
    }

    // ---------------------------------------------------------------- background picker

    /** Full-screen grid of backgrounds; the screen saver uses the same one as the home screen. */
    private void pickBackground(String target) {
        target = Backgrounds.HOME;
        if (sheet != null && sheet.isShowing()) sheet.dismiss();
        if (picker != null) root.removeView(picker);
        // Remember what opened the picker, so closing it goes back there (and the page with it).
        else pickerReturn = getCurrentFocus();
        Vice.focusVisible = true;
        final String which = target;
        String chosen = Backgrounds.get(this, which);

        picker = new FrameLayout(this);
        picker.setBackgroundColor(Vice.alpha(Vice.PAGE, 0.96f));
        picker.setClickable(true);
        ScrollView scroller = overlayScroller();
        LinearLayout body = section(80, 120);
        body.addView(new Text(this, "HOME SCREEN AND SCREEN SAVER", Vice.INTER_700, 14, 0.26f, 17, white(0.7f)), wrap(0));
        body.addView(new Text(this, "BACKGROUND", Vice.ANTON, 72, 0.02f, 72, 0xFFFFFFFF), below(14));
        body.addView(new Text(this, "OK checks a background. Check several, video included, to show them in turn, 15 s each. BACK when done.",
                Vice.INTER_500, 18, 0, 22, white(0.6f)), below(18));

        // Checked artworks; empty means the Skyline video. Every change is saved and shown at once.
        List<Integer> sel = Backgrounds.chosen(Backgrounds.get(this, which));
        Vice.Grid grid = new Vice.Grid(this, 4, 24, 36);
        View current = null;
        List<String> keys = Backgrounds.keys();
        List<ImageView> thumbs = new ArrayList<>();
        List<Runnable> refresh = new ArrayList<>();
        for (String key : keys) {
            // The pick this tile stands for: the video (CLIP), an artwork, or null for ALL.
            Integer pick = Backgrounds.VIDEO.equals(key) ? Integer.valueOf(Backgrounds.CLIP)
                    : key.startsWith("art:") ? Integer.valueOf(key.substring(4)) : null;
            LinearLayout item = new LinearLayout(this);
            item.setOrientation(LinearLayout.VERTICAL);
            item.setClipChildren(false);
            item.setClipToPadding(false);
            item.setFocusable(true);
            item.setClickable(true);
            item.setTag("bg:" + key);
            Tile art = new Tile(this, 20, new Fill(Vice.INK, 20)).lift(8)
                    .shadow(18, 40, Vice.argb(0.3f, 0, 0, 0), 30, 60, Vice.argb(0.45f, 0, 0, 0));
            ImageView img = new ImageView(this);
            img.setScaleType(ImageView.ScaleType.CENTER_CROP);
            art.face.addView(img, match());
            thumbs.add(img);
            if (Backgrounds.VIDEO.equals(key)) {
                FrameLayout play = new FrameLayout(this);
                play.setBackground(new Fill(white(0.92f), 28));
                play.addView(Vice.icon(this, R.drawable.vice_play, Vice.INK), Vice.at(px(22), px(22), Gravity.CENTER));
                art.face.addView(play, Vice.at(px(56), px(56), Gravity.CENTER));
            }
            View rim = new View(this);
            art.face.addView(rim, match());
            // The check badge, top right, in the accent gradient.
            FrameLayout badge = new FrameLayout(this);
            badge.setBackground(new Fill(0, 22, Vice.linear(135, new int[] {Vice.ACCENT_A, Vice.ACCENT_B}, null)).border(2, 0xFFFFFFFF));
            badge.addView(Vice.icon(this, R.drawable.vice_check, 0xFFFFFFFF), Vice.at(px(24), px(24), Gravity.CENTER));
            FrameLayout.LayoutParams blp = Vice.at(px(44), px(44), Gravity.TOP | Gravity.END);
            blp.topMargin = px(14);
            blp.rightMargin = px(14);
            art.face.addView(badge, blp);
            art.focusOn(item);
            item.addView(art, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(225)));
            String name = pick == null ? "All" : Backgrounds.name(pick);
            item.addView(new Text(this, name.toUpperCase(Locale.ROOT), Vice.INTER_700, 20, 0.1f, 24.5f, 0xFFFFFFFF), below(16));
            Text sub = new Text(this, "", Vice.INTER_500, 16, 0, 19.5f, white(0.55f));
            item.addView(sub, below(4));

            refresh.add(() -> {
                boolean on = pick != null ? sel.contains(pick) : sel.size() == Backgrounds.ART.length + 1;
                rim.setBackground(new Fill(0, 20).border(on ? 4 : 1, on ? Vice.ACCENT_B : white(0.14f)));
                badge.setVisibility(on ? View.VISIBLE : View.GONE);
                sub.set(on ? (pick != null && sel.size() > 1 ? "Selected · " + (sel.indexOf(pick) + 1) + " of " + sel.size()
                        : "In use") : Backgrounds.kind(key));
                sub.color(on ? Vice.LINK : white(0.55f));
            });
            item.setOnClickListener(v -> {
                if (pick == null) {
                    boolean all = sel.size() == Backgrounds.ART.length + 1;
                    sel.clear();
                    if (!all) sel.addAll(Backgrounds.everything());
                } else if (sel.contains(pick)) {
                    sel.remove(pick);
                } else {
                    sel.add(pick);
                }
                // Nothing checked falls back to the video, so the screen is never empty.
                if (sel.isEmpty()) sel.add(Backgrounds.CLIP);
                String value = Backgrounds.of(sel);
                Backgrounds.set(this, which, value);
                background.apply(value);
                for (Runnable r : refresh) r.run();
            });
            grid.addView(item);
            if (current == null && pick != null && sel.contains(pick)) current = item;
        }
        for (Runnable r : refresh) r.run();
        body.addView(grid, below(44));
        scroller.addView(body, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        picker.addView(scroller, match());
        root.addView(picker, match());
        View first = current != null ? current : grid.getChildAt(0);
        root.post(() -> focus(first));

        // Thumbnails load in the background.
        int w = px(420);
        new Thread(() -> {
            for (int i = 0; i < keys.size(); i++) {
                Bitmap b = Backgrounds.thumbnail(this, keys.get(i), w);
                ImageView img = thumbs.get(i);
                main.post(() -> img.setImageBitmap(b));
            }
        }, "thumbs").start();
    }

    private void closePicker() {
        if (picker == null) return;
        root.removeView(picker);
        picker = null;
        View back = pickerReturn != null && pickerReturn.isAttachedToWindow() ? pickerReturn : openApps;
        pickerReturn = null;
        focus(back);
    }

    static void toast(Context c, String s) {
        Toast.makeText(c, s, Toast.LENGTH_SHORT).show();
    }
}
