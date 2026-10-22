package app.tvshell;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.graphics.SurfaceTexture;
import android.media.MediaMetadataRetriever;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Surface;
import android.view.TextureView;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import java.util.ArrayList;
import java.util.List;

/**
 * The background you pick for the home screen (the screen saver shows the same): the skyline video
 * from rockstargames.com/VI, the bundled artworks, or all the artworks in turn.
 */
final class Backgrounds {
    static final String HOME = "vice_bg_home";
    static final String VIDEO = "video";
    static final String ROTATE = "rotate";
    private static final long ROTATE_MS = 15000;

    static final int[] ART = {R.drawable.vice_background, R.drawable.vice_art_1, R.drawable.vice_art_2,
            R.drawable.vice_art_3, R.drawable.vice_art_4, R.drawable.vice_art_5, R.drawable.vice_art_6,
            R.drawable.vice_art_7, R.drawable.vice_art_8, R.drawable.vice_art_9, R.drawable.vice_art_10};
    private static final String[] NAMES = {"Key Art", "Boatyard", "Ocean View", "Jack of Hearts", "Mural",
            "The Club", "On the Boat", "The Pier", "Sunset", "Muscle Car", "Gas Station"};

    private Backgrounds() {}

    /** Every choice, in picker order: ALL, the video, then the artworks. */
    static List<String> keys() {
        List<String> k = new ArrayList<>();
        k.add(ROTATE);
        k.add(VIDEO);
        for (int i = 0; i < ART.length; i++) k.add("art:" + i);
        return k;
    }

    static String get(Context c, String target) {
        return Prefs.get(c).getString(target, VIDEO);
    }

    static void set(Context c, String target, String key) {
        Prefs.get(c).edit().putString(target, key).apply();
    }

    /** In a chosen list, the skyline video; artworks are 0…ART.length-1. */
    static final int CLIP = -1;

    /**
     * What a saved value picks, in order: "set:v,1,4" (v is the video). Older "video",
     * "rotate" and "art:1,4" still read. Never empty: nothing picked means the video.
     */
    static List<Integer> chosen(String value) {
        List<Integer> out = new ArrayList<>();
        if (ROTATE.equals(value)) {
            for (int i = 0; i < ART.length; i++) out.add(i);
        } else if (value != null && (value.startsWith("art:") || value.startsWith("set:"))) {
            for (String p : value.substring(4).split(",")) {
                p = p.trim();
                try {
                    int i = "v".equals(p) ? CLIP : Integer.parseInt(p);
                    if (i >= CLIP && i < ART.length && !out.contains(i)) out.add(i);
                } catch (NumberFormatException ignored) {
                    // skip a bad entry
                }
            }
        }
        if (out.isEmpty()) out.add(CLIP);
        return out;
    }

    /** Everything: the video and every artwork. */
    static List<Integer> everything() {
        List<Integer> all = new ArrayList<>();
        all.add(CLIP);
        for (int i = 0; i < ART.length; i++) all.add(i);
        return all;
    }

    /** The value to save for these picks; none means the video. */
    static String of(List<Integer> picks) {
        if (picks.isEmpty()) return VIDEO;
        StringBuilder b = new StringBuilder("set:");
        for (int i = 0; i < picks.size(); i++) b.append(i == 0 ? "" : ",").append(picks.get(i) == CLIP ? "v" : String.valueOf(picks.get(i)));
        return b.toString();
    }

    static String label(String value) {
        List<Integer> picks = chosen(value);
        if (picks.size() == ART.length + 1) return "Everything";
        if (picks.size() == 1) return picks.get(0) == CLIP ? "Skyline" : NAMES[picks.get(0)];
        return picks.size() + " Backgrounds";
    }

    static String name(int pick) {
        return pick == CLIP ? "Skyline" : NAMES[pick];
    }

    static String kind(String key) {
        if (VIDEO.equals(key)) return "Video";
        if (ROTATE.equals(key)) return "The video and every artwork, 15 s each";
        return "Artwork";
    }

    /** A bundled image no wider than needed for {@code width} px. */
    static Bitmap decode(Context c, int res, int width) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inScaled = false;
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeResource(c.getResources(), res, o);
        o.inSampleSize = 1;
        while (o.outWidth / (o.inSampleSize * 2) >= width) o.inSampleSize *= 2;
        o.inJustDecodeBounds = false;
        return BitmapFactory.decodeResource(c.getResources(), res, o);
    }

    /** A small picture of a choice for the picker; blocks, so call it off the main thread. */
    static Bitmap thumbnail(Context c, String key, int width) {
        if (VIDEO.equals(key)) {
            MediaMetadataRetriever r = new MediaMetadataRetriever();
            try (AssetFileDescriptor fd = c.getResources().openRawResourceFd(R.raw.vice_skyline)) {
                r.setDataSource(fd.getFileDescriptor(), fd.getStartOffset(), fd.getLength());
                Bitmap frame = r.getFrameAtTime(2_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
                if (frame != null) return Bitmap.createScaledBitmap(frame, width, width * frame.getHeight() / frame.getWidth(), true);
            } catch (Exception e) {
                Log.i(ViceData.TAG, "video thumbnail: " + e);
            } finally {
                try {
                    r.release();
                } catch (Exception ignored) {
                    // nothing to free
                }
            }
            return decode(c, ART[0], width);
        }
        List<Integer> picks = chosen(key);
        return decode(c, ART[ROTATE.equals(key) ? 1 : Math.max(0, picks.get(0))], width);
    }

    /** Full-screen background that shows whichever choice it's given; cross-fades between pictures. */
    static final class View extends FrameLayout {
        private final Handler main = new Handler(Looper.getMainLooper());
        private ImageView front, back;
        private final Video video;
        private String key;
        private boolean playing;
        private int slide;
        private int generation;
        private List<Integer> order = new ArrayList<>();

        private final Runnable rotate = new Runnable() {
            @Override
            public void run() {
                slide = (slide + 1) % order.size();
                showPick(order.get(slide), true);
                main.postDelayed(this, ROTATE_MS);
            }
        };

        /** One pick: the video fades in over whatever picture is showing; a picture cross-fades in. */
        private void showPick(int pick, boolean fade) {
            if (pick == CLIP) {
                if (front.getDrawable() == null) show(ART[0], false);
                video.enable(playing);
            } else {
                show(ART[pick], fade);
                video.enable(false);
            }
        }

        View(Context c) {
            super(c);
            front = picture(c);
            back = picture(c);
            back.setAlpha(0f);
            addView(front, full());
            addView(back, full());
            video = new Video(c);
            addView(video, full());
        }

        private static ImageView picture(Context c) {
            ImageView v = new ImageView(c);
            v.setScaleType(ImageView.ScaleType.CENTER_CROP);
            return v;
        }

        private static LayoutParams full() {
            return new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        }

        void apply(String k) {
            if (k.equals(key)) return;
            key = k;
            main.removeCallbacks(rotate);
            order = chosen(k);
            slide = 0;
            showPick(order.get(0), front.getDrawable() != null);
            if (playing && order.size() > 1) main.postDelayed(rotate, ROTATE_MS);
        }

        /** Video and slideshow only run while this is on screen. */
        void play(boolean on) {
            if (on == playing) return;
            playing = on;
            if (!order.isEmpty() && order.get(slide) == CLIP) video.enable(on);
            main.removeCallbacks(rotate);
            if (on && order.size() > 1) main.postDelayed(rotate, ROTATE_MS);
        }

        private void show(int res, boolean fade) {
            final int gen = ++generation;
            int width = getResources().getDisplayMetrics().widthPixels;
            new Thread(() -> {
                Bitmap b = decode(getContext(), res, Math.max(width, 1280));
                main.post(() -> {
                    if (gen != generation) return;
                    if (!fade || front.getDrawable() == null) {
                        front.setImageBitmap(b);
                        return;
                    }
                    back.setImageBitmap(b);
                    back.setAlpha(0f);
                    back.animate().alpha(1f).setDuration(900).withEndAction(() -> {
                        ImageView t = front;
                        front = back;
                        back = t;
                        back.setAlpha(0f);
                        back.setImageDrawable(null);
                        bringChildToFront(video);
                    }).start();
                    bringChildToFront(back);
                    bringChildToFront(video);
                });
            }, "background").start();
        }
    }

    /**
     * The skyline clip (res/raw/vice_skyline.mp4, H.264), muted and looping. It fades in over the
     * still once its first frame is drawn; if the TV can't play it, the still simply stays.
     */
    private static final class Video extends TextureView implements TextureView.SurfaceTextureListener {
        private MediaPlayer player;
        private Surface surface;
        private boolean enabled;

        Video(Context c) {
            super(c);
            setOpaque(false);
            setAlpha(0f);
            setSurfaceTextureListener(this);
        }

        void enable(boolean on) {
            enabled = on;
            if (on) start();
            else stop();
        }

        private void start() {
            if (player != null || surface == null) return;
            MediaPlayer p = new MediaPlayer();
            try {
                p.setDataSource(getContext(), Uri.parse("android.resource://" + getContext().getPackageName() + "/" + R.raw.vice_skyline));
            } catch (java.io.IOException e) {
                Log.i(ViceData.TAG, "background video: " + e);
                p.release();
                return;
            }
            p.setSurface(surface);
            p.setLooping(true);
            p.setVolume(0f, 0f);
            p.setOnVideoSizeChangedListener((mp, vw, vh) -> cover(vw, vh));
            p.setOnInfoListener((mp, what, extra) -> {
                if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) animate().alpha(1f).setDuration(600).start();
                return false;
            });
            p.setOnErrorListener((mp, what, extra) -> {
                Log.i(ViceData.TAG, "background video: can't play (" + what + "/" + extra + "), keeping the still");
                stop();
                return true;
            });
            p.setOnPreparedListener(mp -> {
                cover(mp.getVideoWidth(), mp.getVideoHeight());
                if (enabled && mp == player) mp.start();
            });
            player = p;
            try {
                p.prepareAsync();
            } catch (IllegalStateException e) {
                Log.i(ViceData.TAG, "background video: couldn't start, keeping the still");
                player = null;
                p.release();
            }
        }

        /** Fade out, then let go of the decoder. */
        private void stop() {
            MediaPlayer p = player;
            player = null;
            if (p == null) {
                setAlpha(0f);
                return;
            }
            // Let go of the surface now, so a new player can take it straight away; the last frame
            // stays on screen while it fades.
            try {
                p.setSurface(null);
                p.pause();
            } catch (IllegalStateException ignored) {
                // not prepared yet; releasing is enough
            }
            animate().cancel();
            animate().alpha(0f).setDuration(getAlpha() > 0 ? 900 : 0).withEndAction(p::release).start();
        }

        /** object-fit: cover; TextureView stretches the frame to the view, so undo that and crop. */
        private void cover(int vw, int vh) {
            float w = getWidth(), h = getHeight();
            if (vw == 0 || vh == 0 || w == 0 || h == 0) return;
            float s = Math.max(w / vw, h / vh);
            Matrix m = new Matrix();
            m.setScale(vw * s / w, vh * s / h, w / 2f, h / 2f);
            setTransform(m);
        }

        @Override
        public void onSurfaceTextureAvailable(SurfaceTexture st, int w, int h) {
            surface = new Surface(st);
            if (enabled) start();
        }

        @Override
        public void onSurfaceTextureSizeChanged(SurfaceTexture st, int w, int h) {
            if (player != null) cover(player.getVideoWidth(), player.getVideoHeight());
        }

        @Override
        public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
            stop();
            if (surface != null) surface.release();
            surface = null;
            return true;
        }

        @Override
        public void onSurfaceTextureUpdated(SurfaceTexture st) {}
    }
}
