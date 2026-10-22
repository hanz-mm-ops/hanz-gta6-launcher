package app.tvshell;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Home layout: which apps sit in the Top Shelf row and which are hidden. */
final class Prefs {
    static final String REDUCE_MOTION = "reduce_motion";
    static final String BACKGROUND = "background";
    static final String WIDGETS = "widgets";
    private static final String TOP_ROW = "top_row";
    private static final String HIDDEN = "hidden";
    private static final String GRID = "grid";
    static final String ADD = "@add";

    static final String MIRROR = "@mirror";
    static final String SETTINGS = "@settings";
    static final String INPUTS = "@inputs";
    static final int TOP_ROW_MAX = 6;

    static final String APPLE_TV = "com.apple.atve.androidtv.appletv";
    private static final List<String> DEFAULT_TOP_ROW = Arrays.asList(
            APPLE_TV, "com.google.android.youtube.tv", "com.amazon.amazonvideo.livingroom",
            "com.spotify.tv.android", MIRROR, SETTINGS);

    // Covered by the built-in tiles, or plumbing that should never show on the home screen.
    private static final Set<String> DEFAULT_HIDDEN = new HashSet<>(Arrays.asList(
            "app.tvshell", "com.android.tv.settings", "com.zeasn.launcher", "com.spocky.projengmenu",
            "com.hisilicon.miracast", "com.newlink.cast", "com.hisilicon.tvsetting",
            "com.hisilicon.tv.menu", "de.szalkowski.activitylauncher", "com.android.traceur",
            "com.zvt.link.youtube", "com.zvt.link.spotify"));

    private Prefs() {}

    static SharedPreferences get(Context c) {
        return c.getSharedPreferences("home", Context.MODE_PRIVATE);
    }

    static List<String> topRow(Context c) {
        // One-time: put Apple TV first in a dock the viewer already customised.
        if (!get(c).getBoolean("dock_apple_tv", false)) {
            String saved = get(c).getString(TOP_ROW, null);
            if (saved != null && !saved.contains(APPLE_TV)) {
                List<String> row = new ArrayList<>(Arrays.asList(saved.split(",")));
                row.add(0, APPLE_TV);
                while (row.size() > TOP_ROW_MAX) row.remove(row.size() - 1);
                setTopRow(c, row);
            }
            get(c).edit().putBoolean("dock_apple_tv", true).apply();
        }
        String s = get(c).getString(TOP_ROW, null);
        if (s == null) return new ArrayList<>(DEFAULT_TOP_ROW);
        List<String> out = new ArrayList<>();
        for (String p : s.split(",")) if (!p.isEmpty()) out.add(p);
        return out;
    }

    static void setTopRow(Context c, List<String> row) {
        get(c).edit().putString(TOP_ROW, String.join(",", row)).apply();
    }

    /** Apps the viewer chose to show below the dock, in order. Empty until they add some. */
    static List<String> grid(Context c) {
        String s = get(c).getString(GRID, "");
        List<String> out = new ArrayList<>();
        for (String k : s.split(",")) if (!k.isEmpty()) out.add(k);
        return out;
    }

    static void setGrid(Context c, List<String> keys) {
        get(c).edit().putString(GRID, String.join(",", keys)).apply();
    }

    static Set<String> hidden(Context c) {
        Set<String> s = get(c).getStringSet(HIDDEN, null);
        return s == null ? new HashSet<>(DEFAULT_HIDDEN) : new HashSet<>(s);
    }

    static void setHidden(Context c, Set<String> hidden) {
        get(c).edit().putStringSet(HIDDEN, hidden).apply();
    }

    static void reset(Context c) {
        SharedPreferences p = get(c);
        boolean motion = p.getBoolean(REDUCE_MOTION, false);
        String background = p.getString(BACKGROUND, Featured.SHOWS);
        boolean widgets = p.getBoolean(WIDGETS, true);
        p.edit().remove(TOP_ROW).remove(HIDDEN).remove(GRID).putBoolean(REDUCE_MOTION, motion)
                .putString(BACKGROUND, background).putBoolean(WIDGETS, widgets).apply();
    }
}
