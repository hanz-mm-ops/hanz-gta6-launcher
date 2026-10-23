package app.tvshell;

import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.provider.Settings;
import android.view.ViewGroup;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.List;

/** Things both the home screen and Settings can do: casting, inputs, screen saver. */
final class Actions {
    private static final ComponentName AIRPLAY =
            new ComponentName("com.newlink.cast", "com.newlink.cast.activity.MainActivity");
    private static final ComponentName MIRACAST =
            new ComponentName("com.hisilicon.miracast", "com.hisilicon.miracast.activity.WelcomeActivity");
    private static final ComponentName TCL_MIRACAST = new ComponentName("com.tcl.miracast", "com.tcl.miracast.MainActivity");
    private static final ComponentName SCREENSAVER_NOW =
            new ComponentName("com.android.systemui", "com.android.systemui.Somnambulator");
    static final String TV_PLAYER = "com.hisilicon.tvui";

    /** Key for the AirPlay pill on the home screen's input row. */
    static final String AIRPLAY_KEY = "@airplay";

    private Actions() {}

    static boolean hasMirroring(Context c) {
        return exists(c, AIRPLAY) || exists(c, MIRACAST) || exists(c, TCL_MIRACAST);
    }

    private static boolean exists(Context c, ComponentName cn) {
        try {
            c.getPackageManager().getActivityInfo(cn, 0);
            return true;
        } catch (android.content.pm.PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    /** The name the device on this input gives over HDMI-CEC (e.g. "PlayStation 5"), if the TV shares it. */
    private static String cecName(TvInputInfo info) {
        try {
            Object d = TvInputInfo.class.getMethod("getHdmiDeviceInfo").invoke(info);
            if (d == null) return "";
            Object n = d.getClass().getMethod("getDisplayName").invoke(d);
            return n == null ? "" : n.toString();
        } catch (ReflectiveOperationException | RuntimeException e) {
            return "";
        }
    }

    /** AirPlay: the TV's own AirPlay input (TCL/MediaTek), else a stand-alone AirPlay app. */
    static boolean airplay(Context c) {
        TvInputManager tim = c.getSystemService(TvInputManager.class);
        if (tim != null) {
            for (TvInputInfo info : tim.getTvInputList()) {
                if (info.getId().toLowerCase(java.util.Locale.ROOT).contains("airplay") && switchInput(c, info.getId())) return true;
            }
        }
        return start(c, new Intent().setComponent(AIRPLAY));
    }

    /**
     * The HDMI input a console is on: one that's connected and named like a console (by the TV or
     * over HDMI-CEC), else any connected one, else the port the viewer named. Returns
     * {id, label, "connected"|"idle"} or null when the TV has no HDMI inputs.
     */
    static String[] consoleInput(Context c, String[] portNames) {
        TvInputManager tim = c.getSystemService(TvInputManager.class);
        if (tim == null) return null;
        String[] best = null, anyOn = null, named = null, first = null;
        for (TvInputInfo info : tim.getTvInputList()) {
            if (!info.isPassthroughInput() || info.getType() != TvInputInfo.TYPE_HDMI) continue;
            CharSequence custom = info.loadCustomLabel(c);
            String label = String.valueOf(custom != null ? custom : info.loadLabel(c));
            String device = cecName(info);
            boolean on = tim.getInputState(info.getId()) == TvInputManager.INPUT_STATE_CONNECTED;
            String text = (label + " " + device).toUpperCase(java.util.Locale.ROOT);
            boolean console = text.contains("PLAYSTATION") || text.contains("PS5") || text.contains("PS4")
                    || text.contains("XBOX") || text.contains("SWITCH") || text.contains("NINTENDO") || text.contains("CONSOLE");
            String shown = !device.isEmpty() ? device : HomeActivity.inputName(label);
            String[] r = {info.getId(), shown, on ? "connected" : "idle"};
            if (first == null) first = r;
            if (on && console && best == null) best = r;
            if (on && anyOn == null) anyOn = r;
            if (!HomeActivity.inputName(label).equals(label) && named == null) named = r;
        }
        return best != null ? best : anyOn != null ? anyOn : named != null ? named : first;
    }

    /** Key for the Live TV tile on the home screen. */
    static final String LIVE_TV_KEY = "@livetv";

    /** Antenna/cable TV: the vendor's TV player, Google's Live Channels, or any channel viewer. */
    static boolean liveTv(Context c) {
        for (String pkg : new String[] {TV_PLAYER, "com.google.android.tv"}) {
            Intent i = c.getPackageManager().getLeanbackLaunchIntentForPackage(pkg);
            if (i == null) i = c.getPackageManager().getLaunchIntentForPackage(pkg);
            if (i != null && start(c, i)) return true;
        }
        return start(c, new Intent(Intent.ACTION_VIEW, TvContract.Channels.CONTENT_URI));
    }

    /** Show an HDMI input full screen, the way the TV's own input menu does. */
    static boolean switchInput(Context c, String inputId) {
        return start(c, new Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(inputId)));
    }

    static boolean start(Context c, Intent i) {
        try {
            c.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return true;
        } catch (ActivityNotFoundException | SecurityException e) {
            return false;
        }
    }

    static void startOrSay(Context c, Intent i, String failure) {
        if (!start(c, i)) Toast.makeText(c, failure, Toast.LENGTH_SHORT).show();
    }

    static void openSystem(Context c, String action) {
        startOrSay(c, new Intent(action), "That setting isn't available on this TV");
    }

    static Sheet mirroring(ViewGroup host) {
        Context c = host.getContext();
        List<Sheet.Item> items = new ArrayList<>();
        items.add(new Sheet.Item("iPhone, iPad or Mac", "AirPlay screen mirroring",
                c.getDrawable(R.drawable.ic_mirror)));
        items.add(new Sheet.Item("Android phone or Windows PC", "Miracast / Smart View / Cast screen",
                c.getDrawable(R.drawable.ic_mirror)));
        return Sheet.show(host, "Screen Mirroring", items, i -> startOrSay(c,
                new Intent().setComponent(i == 0 ? AIRPLAY : exists(c, MIRACAST) ? MIRACAST : TCL_MIRACAST),
                "Screen mirroring isn't available"));
    }

    static Sheet inputs(ViewGroup host) {
        Context c = host.getContext();
        List<Sheet.Item> items = new ArrayList<>();
        List<String> ids = new ArrayList<>();
        TvInputManager tim = c.getSystemService(TvInputManager.class);
        if (tim != null) {
            for (TvInputInfo info : tim.getTvInputList()) {
                if (!info.isPassthroughInput() || info.isHidden(c)) continue;
                CharSequence custom = info.loadCustomLabel(c);
                String label = String.valueOf(custom != null ? custom : info.loadLabel(c));
                items.add(new Sheet.Item(label, null, c.getDrawable(R.drawable.ic_input)));
                ids.add(info.getId());
            }
        }
        items.add(new Sheet.Item("TV Player", "Antenna, cable and all inputs",
                c.getDrawable(R.drawable.ic_input)));
        return Sheet.show(host, "Inputs", items, i -> {
            if (i < ids.size()) {
                Intent view = new Intent(Intent.ACTION_VIEW,
                        TvContract.buildChannelUriForPassthroughInput(ids.get(i)));
                if (start(c, view)) return;
            }
            Intent player = c.getPackageManager().getLaunchIntentForPackage(TV_PLAYER);
            if (player == null) player = c.getPackageManager().getLeanbackLaunchIntentForPackage(TV_PLAYER);
            if (player != null) startOrSay(c, player, "Couldn't open the TV player");
        });
    }

    static void screensaverNow(Context c) {
        if (!start(c, new Intent().setComponent(SCREENSAVER_NOW))) {
            openSystem(c, Settings.ACTION_DREAM_SETTINGS);
        }
    }
}
