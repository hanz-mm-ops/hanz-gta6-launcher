package app.tvshell;

import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.SystemClock;
import android.service.notification.NotificationListenerService;
import android.util.Log;
import java.io.File;
import java.io.FileOutputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Remembers the last thing played in each app, for Continue Watching. Android TV keeps its own
 * Watch Next list to itself, but apps publish what's playing (title, artwork, position, length) to
 * their media session; notification access (granted over ADB by install.sh) lets us follow those.
 * Nothing is sent anywhere: the record stays in this app's storage on the TV.
 */
public class MediaWatcher extends NotificationListenerService {
    static final String PREFS = "watching";
    private MediaSessionManager sessions;
    private final MediaSessionManager.OnActiveSessionsChangedListener changed = this::track;

    private final Map<MediaSession.Token, MediaController.Callback> callbacks = new HashMap<>();

    @Override
    public void onListenerConnected() {
        sessions = getSystemService(MediaSessionManager.class);
        ComponentName me = new ComponentName(this, MediaWatcher.class);
        try {
            sessions.addOnActiveSessionsChangedListener(changed, me);
            track(sessions.getActiveSessions(me));
        } catch (SecurityException e) {
            Log.i(ViceData.TAG, "media watcher: no notification access");
        }
    }

    @Override
    public void onListenerDisconnected() {
        if (sessions != null) sessions.removeOnActiveSessionsChangedListener(changed);
        callbacks.clear();
    }

    private void track(List<MediaController> list) {
        if (list == null) return;
        for (MediaController c : list) {
            if (getPackageName().equals(c.getPackageName()) || callbacks.containsKey(c.getSessionToken())) continue;
            MediaController.Callback cb = new MediaController.Callback() {
                @Override
                public void onMetadataChanged(MediaMetadata m) {
                    save(c);
                }

                @Override
                public void onPlaybackStateChanged(PlaybackState s) {
                    save(c);
                }

                @Override
                public void onSessionDestroyed() {
                    callbacks.remove(c.getSessionToken());
                }
            };
            c.registerCallback(cb);
            callbacks.put(c.getSessionToken(), cb);
            save(c);
        }
    }

    /** One record per app: the latest title with its artwork, position and length. */
    private void save(MediaController c) {
        MediaMetadata m = c.getMetadata();
        if (m == null) return;
        String title = first(m, MediaMetadata.METADATA_KEY_DISPLAY_TITLE, MediaMetadata.METADATA_KEY_TITLE);
        if (title == null || title.trim().isEmpty()) return;
        String sub = first(m, MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, MediaMetadata.METADATA_KEY_ARTIST,
                MediaMetadata.METADATA_KEY_ALBUM);
        long duration = m.getLong(MediaMetadata.METADATA_KEY_DURATION);
        long position = -1;
        PlaybackState s = c.getPlaybackState();
        if (s != null && s.getPosition() >= 0) {
            position = s.getPosition();
            if (s.getState() == PlaybackState.STATE_PLAYING && s.getLastPositionUpdateTime() > 0) {
                position += (long) ((SystemClock.elapsedRealtime() - s.getLastPositionUpdateTime()) * s.getPlaybackSpeed());
            }
        }
        String pkg = c.getPackageName();
        SharedPreferences p = getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        JSONObject old = read(p.getString(pkg, null));
        boolean sameItem = old != null && title.equals(old.optString("title"));
        try {
            JSONObject o = new JSONObject();
            o.put("title", title);
            o.put("sub", sub == null ? "" : sub);
            o.put("duration", duration);
            o.put("position", position >= 0 ? position : sameItem ? old.optLong("position", -1) : -1);
            o.put("live", duration <= 0 && s != null && s.getState() == PlaybackState.STATE_PLAYING);
            o.put("at", System.currentTimeMillis());
            p.edit().putString(pkg, o.toString()).apply();
        } catch (JSONException ignored) {
            return;
        }
        if (!sameItem) saveArt(pkg, m);
    }

    private void saveArt(String pkg, MediaMetadata m) {
        Bitmap art = m.getBitmap(MediaMetadata.METADATA_KEY_ART);
        if (art == null) art = m.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
        if (art == null) art = m.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
        File f = artFile(this, pkg);
        if (art == null) {
            f.delete();
            return;
        }
        f.getParentFile().mkdirs();
        int w = Math.min(640, art.getWidth());
        Bitmap small = w == art.getWidth() ? art : Bitmap.createScaledBitmap(art, w, Math.max(1, art.getHeight() * w / art.getWidth()), true);
        try (FileOutputStream out = new FileOutputStream(f)) {
            small.compress(Bitmap.CompressFormat.JPEG, 85, out);
        } catch (Exception e) {
            f.delete();
        }
    }

    static File artFile(Context c, String pkg) {
        return new File(new File(c.getFilesDir(), "watching"), pkg + ".jpg");
    }

    private static String first(MediaMetadata m, String... keys) {
        for (String k : keys) {
            CharSequence v = m.getText(k);
            if (v != null && v.length() > 0) return v.toString();
        }
        return null;
    }

    static JSONObject read(String json) {
        if (json == null) return null;
        try {
            return new JSONObject(json);
        } catch (JSONException e) {
            return null;
        }
    }
}
