package app.tvshell;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.StatFs;
import android.provider.Settings;
import android.text.format.Formatter;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.net.Inet4Address;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.Supplier;

/** tvOS-style Settings: a big icon on the left, a list on the right, pages that stack. */
public class SettingsActivity extends Activity {

    private static final class Row {
        final String label;
        final Supplier<String> value;
        final Runnable action;
        final boolean opensPage;
        java.util.function.IntConsumer adjust;

        Row(String label, Supplier<String> value, Runnable action, boolean opensPage) {
            this.label = label;
            this.value = value;
            this.action = action;
            this.opensPage = opensPage;
        }

        Row adjust(java.util.function.IntConsumer a) {
            adjust = a;
            return this;
        }
    }

    private static final class Page {
        final String title;
        final int icon;
        final Supplier<List<Row>> rows;
        int focused;

        Page(String title, int icon, Supplier<List<Row>> rows) {
            this.title = title;
            this.icon = icon;
            this.rows = rows;
        }
    }

    private static final String DREAM = "app.tvshell/.AerialDream";
    private static final int[] SAVER_MINUTES = {5, 10, 15, 30, 60, 0};
    private static final ComponentName DEVELOPER = new ComponentName("com.android.tv.settings",
            "com.android.tv.settings.system.development.DevelopmentActivity");

    private final Deque<Page> stack = new ArrayDeque<>();
    private FrameLayout root;
    private Vice.Text title;
    private ImageView bigIcon;
    private LinearLayout list;
    private ScrollView listScroll;
    private Sheet sheet;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        Ui.init(this);
        Vice.init(this);
        Vice.focusVisible = true;
        buildLayout();
        setContentView(root);
        push(mainPage());
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Values may have changed in a system screen we sent the viewer to.
        if (!stack.isEmpty()) render(false);
    }

    @Override
    public void onBackPressed() {
        if (sheet != null && sheet.isShowing()) {
            sheet.dismiss();
            return;
        }
        if (stack.size() > 1) {
            stack.pop();
            transition(-1);
        } else {
            finish();
        }
    }

    // ---------------------------------------------------------------- layout

    private void buildLayout() {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        int w = Math.max(dm.widthPixels, dm.heightPixels);

        // The hero artwork, faded into the page colour so the list stays readable.
        root = new FrameLayout(this);
        root.setBackgroundColor(Vice.PAGE);
        ImageView art = new ImageView(this);
        art.setScaleType(ImageView.ScaleType.CENTER_CROP);
        android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
        o.inScaled = false;
        o.inSampleSize = w <= 1280 ? 4 : 2;
        art.setImageBitmap(android.graphics.BitmapFactory.decodeResource(getResources(), R.drawable.vice_background, o));
        root.addView(art, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        View shade = new View(this);
        shade.setBackground(new Vice.Fill(0, 0,
                Vice.linear(90, new int[] {Vice.argb(0.55f, 22, 10, 27), Vice.argb(0.94f, 22, 10, 27), Vice.PAGE},
                        new float[] {0f, 0.45f, 1f}),
                Vice.linear(180, new int[] {Vice.argb(0.35f, 22, 10, 27), Vice.PAGE}, new float[] {0f, 1f})));
        root.addView(shade, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        FrameLayout content = new FrameLayout(this);
        content.setClipChildren(false);
        root.addView(content, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        left.addView(new Vice.Text(this, "SETTINGS", Vice.INTER_700, 14, 0.26f, 17, Vice.white(0.7f)));
        title = new Vice.Text(this, "", Vice.ANTON, 72, 0.02f, 72, 0xFFFFFFFF);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(Vice.px(640), ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.topMargin = Vice.px(14);
        left.addView(title, tlp);
        FrameLayout box = new FrameLayout(this);
        box.setBackground(new Vice.Fill(Vice.white(0.06f), 32).border(1.5f, Vice.white(0.16f)));
        bigIcon = new ImageView(this);
        bigIcon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        box.addView(bigIcon, Vice.at(Vice.px(72), Vice.px(72), Gravity.CENTER));
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(Vice.px(140), Vice.px(140));
        blp.topMargin = Vice.px(36);
        left.addView(box, blp);
        FrameLayout.LayoutParams llp0 = Vice.at(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.START);
        llp0.leftMargin = Vice.px(80);
        llp0.topMargin = Vice.px(90);
        content.addView(left, llp0);

        listScroll = new ScrollView(this);
        listScroll.setVerticalScrollBarEnabled(false);
        listScroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        listScroll.setClipChildren(false);
        listScroll.setClipToPadding(false);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setClipChildren(false);
        // Room for the focus ring and lift at the list's edges.
        list.setClipToPadding(false);
        list.setPadding(Vice.px(12), Vice.px(90), Vice.px(12), Vice.px(200));
        listScroll.addView(list);
        FrameLayout.LayoutParams llp = new FrameLayout.LayoutParams(Vice.px(924),
                ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END);
        llp.rightMargin = Vice.px(68);
        content.addView(listScroll, llp);
    }

    private void push(Page p) {
        if (!stack.isEmpty()) stack.peek().focused = focusedIndex();
        stack.push(p);
        if (stack.size() == 1) render(true); else transition(1);
    }

    /** Slide the old list out and the new one in: forward goes left, back goes right. */
    private void transition(int dir) {
        if (Ui.reduceMotion) {
            render(true);
            return;
        }
        list.animate().cancel();
        list.animate().alpha(0f).translationX(-dir * Ui.dp(40)).setDuration(110).setInterpolator(Ui.EASE)
                .withEndAction(() -> {
                    render(false, stack.peek().focused);
                    list.setTranslationX(dir * Ui.dp(40));
                    list.animate().alpha(1f).translationX(0).setDuration(200).setInterpolator(Ui.EASE).start();
                    bigIcon.setAlpha(0f);
                    bigIcon.animate().alpha(1f).setDuration(200).start();
                    title.setAlpha(0f);
                    title.animate().alpha(1f).setDuration(200).start();
                }).start();
    }

    private int focusedIndex() {
        View f = getCurrentFocus();
        return f == null ? 0 : Math.max(0, list.indexOfChild(f));
    }

    private void render(boolean animate) {
        render(animate, animate ? stack.peek().focused : focusedIndex());
    }

    private void render(boolean animate, int keep) {
        Page p = stack.peek();
        title.set(p.title.toUpperCase(java.util.Locale.ROOT));
        bigIcon.setImageResource(p.icon);
        // Line icons are drawn white-on-clear; the app icon has its own colours.
        if (p.icon == R.drawable.app_icon) bigIcon.clearColorFilter(); else bigIcon.setColorFilter(0xFFFFFFFF);
        list.removeAllViews();
        List<Row> rows = p.rows.get();
        for (Row r : rows) list.addView(rowView(r));
        if (list.getChildCount() > 0) {
            list.getChildAt(Math.min(keep, list.getChildCount() - 1)).requestFocusFromTouch();
        }
        if (animate && !Ui.reduceMotion) {
            list.setAlpha(0f);
            list.setTranslationX(Ui.dp(30));
            list.animate().alpha(1f).translationX(0).setDuration(220).setInterpolator(Ui.EASE).start();
        }
    }

    /** One setting: Smart Home's card style, white focus ring, value on the right. */
    private View rowView(Row r) {
        Vice.Tile tile = new Vice.Tile(this, 20, new Vice.Fill(Vice.white(0.05f), 20).border(1.5f, Vice.white(0.18f)));
        tile.setFocusable(true);
        tile.setClickable(r.action != null);
        tile.focusOn(tile);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(Vice.px(30), 0, Vice.px(26), 0);
        tile.face.addView(row, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        Vice.Text label = new Vice.Text(this, r.label.toUpperCase(java.util.Locale.ROOT), Vice.INTER_700, 19, 0.08f, 23, 0xFFFFFFFF);
        row.addView(label, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        String v = r.value != null ? r.value.get() : null;
        Vice.Text value = new Vice.Text(this, v == null ? "" : v, Vice.INTER_500, 17, 0, 21, Vice.white(0.55f));
        row.addView(value, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        if (r.opensPage) {
            ImageView chevron = Vice.icon(this, R.drawable.vice_chevron, Vice.white(0.55f));
            chevron.setRotation(-90);
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(Vice.px(22), Vice.px(22));
            clp.leftMargin = Vice.px(14);
            row.addView(chevron, clp);
        }
        if (r.adjust != null) {
            value.set("◀  " + value.get() + "  ▶");
            tile.setOnKeyListener((v2, code, e) -> {
                if (e.getAction() != android.view.KeyEvent.ACTION_DOWN) return false;
                int d = code == android.view.KeyEvent.KEYCODE_DPAD_LEFT ? -1
                        : code == android.view.KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : 0;
                if (d == 0) return false;
                r.adjust.accept(d);
                value.set("◀  " + (r.value != null ? r.value.get() : "") + "  ▶");
                return true;
            });
        }
        if (r.action != null) tile.setOnClickListener(x -> r.action.run());

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Vice.px(84));
        lp.bottomMargin = Vice.px(14);
        tile.setLayoutParams(lp);
        return tile;
    }

    private Row page(String label, Supplier<String> value, Supplier<Page> page) {
        return new Row(label, value, () -> push(page.get()), true);
    }

    private Row system(String label, Supplier<String> value, String action) {
        return new Row(label, value, () -> Actions.openSystem(this, action), true);
    }

    private Row action(String label, Supplier<String> value, Runnable run) {
        return new Row(label, value, run, false);
    }

    // ---------------------------------------------------------------- pages

    private Page mainPage() {
        return new Page("Settings", R.drawable.ic_gear, () -> {
            List<Row> r = new ArrayList<>();
            r.add(page("General", null, this::generalPage));
            r.add(page("Video and Audio", null, this::videoPage));
            r.add(page("Screen Saver", this::saverSummary, this::saverPage));
            r.add(page("Network", this::wifiName, this::networkPage));
            r.add(page("Remotes and Devices", null, this::devicesPage));
            r.add(action("Screen Mirroring", () -> "AirPlay, Miracast", () -> sheet = Actions.mirroring(root)));
            r.add(action("Inputs", () -> "HDMI, TV", () -> sheet = Actions.inputs(root)));
            r.add(page("Apps", null, this::appsPage));
            r.add(page("Home Screen", null, this::homePage));
            r.add(page("Power and Startup", this::standbySummary, this::powerPage));
            r.add(page("System", null, this::systemPage));
            return r;
        });
    }

    private Page generalPage() {
        return new Page("General", R.drawable.ic_gear, () -> {
            List<Row> r = new ArrayList<>();
            r.add(page("About", null, this::aboutPage));
            r.add(action("Reduce Motion", () -> Ui.reduceMotion ? "On" : "Off", () -> {
                Ui.reduceMotion = !Ui.reduceMotion;
                Prefs.get(this).edit().putBoolean(Prefs.REDUCE_MOTION, Ui.reduceMotion).apply();
                render(false);
            }));
            r.add(system("Language", null, Settings.ACTION_LOCALE_SETTINGS));
            r.add(system("Keyboard", null, Settings.ACTION_INPUT_METHOD_SETTINGS));
            r.add(system("Date and Time", null, Settings.ACTION_DATE_SETTINGS));
            r.add(system("Accessibility", null, Settings.ACTION_ACCESSIBILITY_SETTINGS));
            return r;
        });
    }

    private Page aboutPage() {
        return new Page("About", R.drawable.app_icon, () -> {
            ActivityManager.MemoryInfo mem = new ActivityManager.MemoryInfo();
            getSystemService(ActivityManager.class).getMemoryInfo(mem);
            StatFs fs = new StatFs(Environment.getDataDirectory().getPath());
            String name = Settings.Global.getString(getContentResolver(), Settings.Global.DEVICE_NAME);
            List<Row> r = new ArrayList<>();
            r.add(action("Name", () -> name != null ? name : "Smart TV", null));
            r.add(action("Model", () -> Build.MODEL, null));
            r.add(action("Chip", () -> Build.HARDWARE + " · " + Build.SOC_MODEL, null));
            r.add(action("Android Engine", () -> Build.VERSION.RELEASE, null));
            r.add(action("Memory", () -> Formatter.formatShortFileSize(this, mem.totalMem)
                    + " (" + Formatter.formatShortFileSize(this, mem.availMem) + " free)", null));
            r.add(action("Storage", () -> Formatter.formatShortFileSize(this, fs.getAvailableBytes())
                    + " free of " + Formatter.formatShortFileSize(this, fs.getTotalBytes()), null));
            r.add(action("IP Address", this::ipAddress, null));
            r.add(action("Home Version", () -> "3.0", null));
            r.add(action("Artwork", () -> "TVmaze (CC BY-SA), Unsplash", null));
            return r;
        });
    }

    private Page videoPage() {
        return new Page("Video and Audio", R.drawable.ic_input, () -> {
            List<Row> r = new ArrayList<>();
            r.add(new Row("Picture", null, this::openPicture, true));
            r.add(page("Sound", this::volumeSummary, this::soundPage));
            r.add(system("Display", null, Settings.ACTION_DISPLAY_SETTINGS));
            return r;
        });
    }

    private void openPicture() {
        Intent tv = getPackageManager().getLaunchIntentForPackage("com.hisilicon.tvsetting");
        if (tv != null && Actions.start(this, tv)) return;
        Actions.openSystem(this, Settings.ACTION_DISPLAY_SETTINGS);
    }

    private Page saverPage() {
        return new Page("Screen Saver", R.drawable.ic_screensaver, () -> {
            List<Row> r = new ArrayList<>();
            r.add(action("Start After", this::saverSummary, this::cycleSaverDelay));
            r.add(action("Type", () -> "Aerial", this::useAerial));
            r.add(action("Start Now", null, () -> {
                useAerial();
                Actions.screensaverNow(this);
            }));
            return r;
        });
    }

    private Page homePage() {
        return new Page("Home Screen", R.drawable.ic_widgets, () -> {
            List<Row> r = new ArrayList<>();
            // The picker lives on the home screen, with the background right behind it.
            r.add(new Row("Background", () -> Backgrounds.label(Backgrounds.get(this, Backgrounds.HOME)), () ->
                    startActivity(new Intent(this, HomeActivity.class).putExtra("app.tvshell.PICK_BACKGROUND", Backgrounds.HOME)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP)), true));
            r.add(action("Clear Apps Below the Dock", null, () -> {
                Prefs.setGrid(this, new ArrayList<>());
                HomeActivity.toast(this, "Only your dock apps are on the home screen now");
            }));
            r.add(action("Reset Home Screen Layout", null, () -> {
                Prefs.reset(this);
                HomeActivity.toast(this, "Home screen reset");
            }));
            return r;
        });
    }

    private Page systemPage() {
        return new Page("System", R.drawable.ic_gear, () -> {
            List<Row> r = new ArrayList<>();
            r.add(system("Storage", null, Settings.ACTION_INTERNAL_STORAGE_SETTINGS));
            r.add(new Row("Developer Options", null, () -> Actions.startOrSay(this,
                    new Intent().setComponent(DEVELOPER), "Developer options aren't available"), true));
            r.add(system("All Android Settings", null, Settings.ACTION_SETTINGS));
            return r;
        });
    }

    private Page networkPage() {
        return new Page("Network", R.drawable.ic_wifi, () -> {
            List<Row> r = new ArrayList<>();
            r.add(action("Wi-Fi", this::wifiName, null));
            r.add(action("Signal", this::wifiSignal, null));
            r.add(action("IP Address", this::ipAddress, null));
            r.add(action("Router", this::gateway, null));
            r.add(system("Choose a Network", null, Settings.ACTION_WIFI_SETTINGS));
            return r;
        });
    }

    private Page soundPage() {
        android.media.AudioManager am = getSystemService(android.media.AudioManager.class);
        int stream = android.media.AudioManager.STREAM_MUSIC;
        return new Page("Sound", R.drawable.ic_input, () -> {
            List<Row> r = new ArrayList<>();
            r.add(action("Volume", this::volumeSummary, null).adjust(d -> am.adjustStreamVolume(stream,
                    d > 0 ? android.media.AudioManager.ADJUST_RAISE : android.media.AudioManager.ADJUST_LOWER, 0)));
            r.add(action("Mute", () -> am.isStreamMute(stream) ? "On" : "Off", () -> {
                am.adjustStreamVolume(stream, android.media.AudioManager.ADJUST_TOGGLE_MUTE, 0);
                render(false);
            }));
            r.add(system("More Sound Settings", null, Settings.ACTION_SOUND_SETTINGS));
            return r;
        });
    }

    private Page devicesPage() {
        return new Page("Remotes and Devices", R.drawable.ic_sliders, () -> {
            List<Row> r = new ArrayList<>();
            try {
                android.bluetooth.BluetoothAdapter bt =
                        getSystemService(android.bluetooth.BluetoothManager.class).getAdapter();
                if (bt != null) {
                    for (android.bluetooth.BluetoothDevice d : bt.getBondedDevices()) {
                        String name = d.getName() == null ? d.getAddress() : d.getName();
                        r.add(action(name, () -> "Paired", null));
                    }
                }
            } catch (SecurityException e) {
                r.add(action("Paired devices", () -> "Run install.sh to show", null));
            }
            if (r.isEmpty()) r.add(action("No paired devices", null, null));
            r.add(system("Pair a Remote or Accessory", null, Settings.ACTION_BLUETOOTH_SETTINGS));
            return r;
        });
    }

    private Page appsPage() {
        return new Page("Apps", R.drawable.ic_widgets, () -> {
            android.content.pm.PackageManager pm = getPackageManager();
            java.util.Map<String, android.content.pm.ResolveInfo> apps = new java.util.TreeMap<>();
            for (String cat : new String[] {Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER}) {
                for (android.content.pm.ResolveInfo ri : pm.queryIntentActivities(
                        new Intent(Intent.ACTION_MAIN).addCategory(cat), 0)) {
                    String pkg = ri.activityInfo.packageName;
                    if (pkg.equals(getPackageName())) continue;
                    String label = String.valueOf(ri.loadLabel(pm));
                    if (!apps.containsValue(ri) && apps.values().stream()
                            .noneMatch(x -> x.activityInfo.packageName.equals(pkg))) apps.put(label + pkg, ri);
                }
            }
            List<Row> r = new ArrayList<>();
            for (android.content.pm.ResolveInfo ri : apps.values()) {
                String pkg = ri.activityInfo.packageName;
                String label = String.valueOf(ri.loadLabel(pm));
                r.add(new Row(label, null, () -> appMenu(label, pkg, ri), true));
            }
            return r;
        });
    }

    private void appMenu(String label, String pkg, android.content.pm.ResolveInfo ri) {
        List<Sheet.Item> items = new ArrayList<>();
        items.add(new Sheet.Item("Open", null, getDrawable(R.drawable.vice_open)));
        items.add(new Sheet.Item("App Info", "Storage, permissions, force stop", getDrawable(R.drawable.vice_info)));
        items.add(new Sheet.Item("Uninstall", "You'll be asked to confirm", getDrawable(R.drawable.vice_trash)));
        sheet = Sheet.show(root, label, "App", ri != null ? ri.loadIcon(getPackageManager()) : null, items, i -> {
            if (i == 0) {
                Intent open = getPackageManager().getLeanbackLaunchIntentForPackage(pkg);
                if (open == null) open = getPackageManager().getLaunchIntentForPackage(pkg);
                if (open != null) Actions.startOrSay(this, open, "Couldn't open " + label);
            } else if (i == 1) {
                Actions.startOrSay(this, new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        android.net.Uri.fromParts("package", pkg, null)), "App info isn't available");
            } else {
                Actions.startOrSay(this, new Intent(Intent.ACTION_DELETE,
                        android.net.Uri.fromParts("package", pkg, null)), "This app can't be uninstalled");
            }
        });
    }

    private static final ComponentName TV_SETTINGS =
            new ComponentName("com.hisilicon.tvsetting", "com.hisilicon.tvsetting.main.SettingActivity");

    private Page powerPage() {
        return new Page("Power and Startup", R.drawable.ic_screensaver, () -> {
            List<Row> r = new ArrayList<>();
            r.add(new Row("Standby Mode", this::standbySummary, () -> Actions.startOrSay(this,
                    new Intent().setComponent(TV_SETTINGS), "TV settings aren't available"), true));
            r.add(new Row("Auto Sleep", this::autoSleepSummary, () -> Actions.startOrSay(this,
                    new Intent().setComponent(TV_SETTINGS), "TV settings aren't available"), true));
            r.add(action("Tip", () -> "Standby: STR · Auto Sleep: Off", null));
            r.add(page("Screen Saver", this::saverSummary, this::saverPage));
            return r;
        });
    }

    // ---------------------------------------------------------------- values

    private String standbySummary() {
        String m = Settings.System.getString(getContentResolver(), "setting_standby_mode");
        if (m == null) return "Unknown";
        switch (m) {
            case "str": return "Instant On (STR)";
            case "lightsleep": return "Light Sleep";
            case "lowpower": return "Low Power";
            case "shutdown": return "Full Shutdown";
            default: return m;
        }
    }

    private String autoSleepSummary() {
        String v = Settings.System.getString(getContentResolver(), "setting_auto_sleep_time");
        if (v == null || v.equals("0") || v.equals("-1")) return "Off";
        return v + " min";
    }

    private String volumeSummary() {
        android.media.AudioManager am = getSystemService(android.media.AudioManager.class);
        int s = android.media.AudioManager.STREAM_MUSIC;
        return am.isStreamMute(s) ? "Muted" : am.getStreamVolume(s) + " / " + am.getStreamMaxVolume(s);
    }

    @SuppressWarnings("deprecation")
    private String wifiName() {
        try {
            android.net.wifi.WifiInfo wi = getSystemService(android.net.wifi.WifiManager.class).getConnectionInfo();
            String ssid = wi == null ? null : wi.getSSID();
            if (ssid == null || ssid.contains("unknown")) return ipAddress().equals("Not connected") ? "Not connected" : "Connected";
            return ssid.replace("\"", "");
        } catch (SecurityException e) {
            return "Connected";
        }
    }

    @SuppressWarnings("deprecation")
    private String wifiSignal() {
        android.net.wifi.WifiInfo wi = getSystemService(android.net.wifi.WifiManager.class).getConnectionInfo();
        if (wi == null) return "—";
        int level = android.net.wifi.WifiManager.calculateSignalLevel(wi.getRssi(), 5);
        return new String[] {"Very Weak", "Weak", "Fair", "Good", "Excellent"}[Math.max(0, Math.min(4, level))];
    }

    private String gateway() {
        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        Network n = cm.getActiveNetwork();
        LinkProperties lp = n == null ? null : cm.getLinkProperties(n);
        if (lp == null) return "—";
        for (android.net.RouteInfo ri : lp.getRoutes()) {
            if (ri.isDefaultRoute() && ri.getGateway() instanceof Inet4Address) return ri.getGateway().getHostAddress();
        }
        return "—";
    }


    private String ipAddress() {
        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        Network n = cm.getActiveNetwork();
        LinkProperties lp = n == null ? null : cm.getLinkProperties(n);
        if (lp == null) return "Not connected";
        for (LinkAddress a : lp.getLinkAddresses()) {
            if (a.getAddress() instanceof Inet4Address) return a.getAddress().getHostAddress();
        }
        return "Connected";
    }

    private String saverSummary() {
        int ms = Settings.System.getInt(getContentResolver(), Settings.System.SCREEN_OFF_TIMEOUT, 0);
        if (ms <= 0 || ms >= Integer.MAX_VALUE / 2) return "Never";
        int min = Math.round(ms / 60000f);
        return min >= 60 ? (min / 60) + " hour" : min + " minutes";
    }

    private void cycleSaverDelay() {
        if (!Settings.System.canWrite(this)) {
            HomeActivity.toast(this, "Run install.sh again to allow this");
            return;
        }
        int current = Math.round(Settings.System.getInt(getContentResolver(),
                Settings.System.SCREEN_OFF_TIMEOUT, 0) / 60000f);
        int next = SAVER_MINUTES[0];
        for (int i = 0; i < SAVER_MINUTES.length; i++) {
            if (SAVER_MINUTES[i] == current) {
                next = SAVER_MINUTES[(i + 1) % SAVER_MINUTES.length];
                break;
            }
        }
        Settings.System.putInt(getContentResolver(), Settings.System.SCREEN_OFF_TIMEOUT,
                next == 0 ? Integer.MAX_VALUE : next * 60000);
        useAerial();
        render(false);
    }

    private void useAerial() {
        try {
            Settings.Secure.putString(getContentResolver(), "screensaver_components", DREAM);
            Settings.Secure.putInt(getContentResolver(), "screensaver_enabled", 1);
        } catch (SecurityException e) {
            // Not granted yet; install.sh sets the same values over ADB.
        }
    }
}
