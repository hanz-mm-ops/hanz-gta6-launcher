package app.tvshell;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import app.tvshell.Vice.Fill;
import app.tvshell.Vice.Text;
import app.tvshell.Vice.Tile;
import java.util.List;
import java.util.Locale;

import static app.tvshell.Vice.px;
import static app.tvshell.Vice.white;

/** A panel of choices that slides over the current screen, in the Vice look (CSS px, see Vice). */
final class Sheet {
    interface OnPick {
        void pick(int index);
    }

    static final class Item {
        final String label;
        final String detail;
        final Drawable icon;
        final boolean glyph;

        Item(String label, String detail, Drawable icon) {
            this(label, detail, icon, true);
        }

        /** glyph=false keeps an app's own colours instead of tinting the icon white. */
        Item(String label, String detail, Drawable icon, boolean glyph) {
            this.label = label;
            this.detail = detail;
            this.icon = icon;
            this.glyph = glyph;
        }
    }

    private final ViewGroup host;
    private final FrameLayout scrim;
    private final View restoreFocus;

    private Sheet(ViewGroup host, FrameLayout scrim, View restoreFocus) {
        this.host = host;
        this.scrim = scrim;
        this.restoreFocus = restoreFocus;
    }

    static Sheet show(ViewGroup host, String title, List<Item> items, OnPick onPick) {
        return show(host, title, items, Gravity.CENTER, onPick);
    }

    static Sheet show(ViewGroup host, String title, List<Item> items, int gravity, OnPick onPick) {
        return show(host, title, null, null, items, gravity, onPick);
    }

    /** With a header: the thing's own icon and a line under the title (e.g. an app and its kind). */
    static Sheet show(ViewGroup host, String title, String subtitle, Drawable headIcon, List<Item> items, OnPick onPick) {
        return show(host, title, subtitle, headIcon, items, Gravity.CENTER, onPick);
    }

    static Sheet show(ViewGroup host, String title, String subtitle, Drawable headIcon, List<Item> items,
            int gravity, OnPick onPick) {
        Context c = host.getContext();
        Vice.init(c);
        Vice.focusVisible = true;
        View before = host.findFocus();

        FrameLayout scrim = new FrameLayout(c);
        scrim.setBackgroundColor(Vice.argb(0.72f, 22, 10, 27));
        scrim.setClickable(true);
        scrim.setAlpha(0f);
        scrim.setClipChildren(false);

        // The page colour, a hairline rim and the cards' soft shadow.
        Tile panel = new Tile(c, 28, new Fill(0xFF1F1024, 28).border(1.5f, white(0.14f)))
                .shadow(30, 60, Vice.argb(0.45f, 0, 0, 0), 0, 0, 0);
        LinearLayout body = new LinearLayout(c);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(px(44), px(40), px(44), px(32));
        // Clip to the panel (so a long list can't spill over the title) but not to its padding,
        // which leaves room for the focus ring at the sides.
        body.setClipChildren(true);
        body.setClipToPadding(false);
        panel.face.setClipToOutline(false);
        panel.face.setClipChildren(false);
        panel.face.addView(body, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout head = new LinearLayout(c);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        if (headIcon != null) {
            FrameLayout box = new FrameLayout(c);
            box.setBackground(new Fill(white(0.08f), 20).border(1, white(0.14f)));
            ImageView icon = new ImageView(c);
            icon.setImageDrawable(headIcon);
            icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
            box.addView(icon, Vice.at(px(52), px(52), Gravity.CENTER));
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(px(80), px(80));
            blp.rightMargin = px(22);
            head.addView(box, blp);
        }
        LinearLayout titles = new LinearLayout(c);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.addView(new Text(c, title.toUpperCase(Locale.ROOT), Vice.ANTON, 48, 0.02f, 52, 0xFFFFFFFF),
                new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        if (subtitle != null) {
            LinearLayout.LayoutParams slp0 = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            slp0.topMargin = px(6);
            titles.addView(new Text(c, subtitle.toUpperCase(Locale.ROOT), Vice.INTER_700, 14, 0.26f, 17, white(0.6f)), slp0);
        }
        head.addView(titles, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        body.addView(head, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        View rule = new View(c);
        rule.setBackgroundColor(white(0.12f));
        LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(1));
        rl.topMargin = px(26);
        body.addView(rule, rl);

        Sheet sheet = new Sheet(host, scrim, before);
        // Long lists (like All Apps) scroll inside the panel; padding leaves room for the focus ring.
        ScrollView scroller = new ScrollView(c);
        scroller.setVerticalScrollBarEnabled(false);
        scroller.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroller.setClipChildren(false);
        scroller.setClipToPadding(false);
        LinearLayout list = new LinearLayout(c);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setClipChildren(false);
        list.setClipToPadding(false);
        list.setPadding(px(12), px(12), px(12), px(12));
        scroller.addView(list);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                items.size() > 6 ? px(640) : ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.topMargin = px(12);
        slp.leftMargin = -px(12);
        slp.rightMargin = -px(12);
        body.addView(scroller, slp);

        View first = null;
        for (int i = 0; i < items.size(); i++) {
            final int index = i;
            View row = row(c, items.get(i), v -> {
                sheet.dismiss();
                onPick.pick(index);
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(88));
            if (i > 0) lp.topMargin = px(14);
            list.addView(row, lp);
            if (first == null) first = row;
        }

        boolean side = (gravity & Gravity.HORIZONTAL_GRAVITY_MASK) == Gravity.END;
        FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(px(side ? 620 : 720),
                ViewGroup.LayoutParams.WRAP_CONTENT, gravity);
        if (side) plp.setMargins(px(80), px(104), px(80), px(80));
        scrim.addView(panel, plp);
        host.addView(scrim, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        if (side) panel.setTranslationX(px(60)); else panel.setTranslationY(px(40));
        scrim.animate().alpha(1f).setDuration(200).start();
        panel.animate().translationX(0).translationY(0).setInterpolator(Vice.EASE)
                .setDuration(Ui.reduceMotion ? 0 : 250).start();
        if (first != null) first.requestFocusFromTouch();
        return sheet;
    }

    /** One choice: Smart Home's card style, white ring when focused. */
    private static View row(Context c, Item item, View.OnClickListener click) {
        Tile row = Vice.button(c, 18, new Fill(white(0.05f), 18).border(1.5f, white(0.16f)), click);
        LinearLayout inner = new LinearLayout(c);
        inner.setOrientation(LinearLayout.HORIZONTAL);
        inner.setGravity(Gravity.CENTER_VERTICAL);
        inner.setPadding(px(item.icon != null ? 16 : 26), 0, px(26), 0);

        if (item.icon != null) {
            FrameLayout box = new FrameLayout(c);
            box.setBackground(new Fill(white(0.08f), 14));
            ImageView icon = new ImageView(c);
            icon.setImageDrawable(item.icon);
            icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
            if (item.glyph) icon.setColorFilter(0xFFFFFFFF);
            int s = item.glyph ? 28 : 40;
            box.addView(icon, Vice.at(px(s), px(s), Gravity.CENTER));
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(px(52), px(52));
            blp.rightMargin = px(18);
            inner.addView(box, blp);
        }
        LinearLayout texts = new LinearLayout(c);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.addView(new Text(c, item.label.toUpperCase(Locale.ROOT), Vice.INTER_700, 19, 0.08f, 23, 0xFFFFFFFF),
                new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        if (item.detail != null) {
            LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            dlp.topMargin = px(4);
            texts.addView(new Text(c, item.detail, Vice.INTER_500, 15, 0, 18.5f, white(0.55f)), dlp);
        }
        inner.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        ImageView chevron = Vice.icon(c, R.drawable.vice_chevron, white(0.45f));
        chevron.setRotation(-90);
        inner.addView(chevron, new LinearLayout.LayoutParams(px(22), px(22)));
        row.face.addView(inner, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return row;
    }

    private boolean closing;

    /** Fade and slide away, then hand focus back to whatever had it before. */
    void dismiss() {
        if (scrim.getParent() == null || closing) return;
        closing = true;
        if (restoreFocus != null && restoreFocus.isAttachedToWindow()) restoreFocus.requestFocus();
        View panel = scrim.getChildAt(0);
        boolean side = ((FrameLayout.LayoutParams) panel.getLayoutParams()).gravity == (Gravity.END | Gravity.TOP);
        int d = Ui.reduceMotion ? 0 : 160;
        panel.animate().translationX(side ? px(50) : 0).translationY(side ? 0 : px(24))
                .setDuration(d).setInterpolator(Vice.EASE).start();
        scrim.animate().alpha(0f).setDuration(d).withEndAction(() -> host.removeView(scrim)).start();
    }

    boolean isShowing() {
        return scrim.getParent() != null && !closing;
    }
}
