package com.cosyhome.tv;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Outline;
import android.view.Gravity;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.util.function.Consumer;

/** A tile with no app behind it, a channel or a device: a large line and a small one. */
@SuppressLint("ViewConstructor")
final class TextTile extends FrameLayout {

    /** Set while four rows share the screen: the tiles are smaller, so the type is too. */
    static boolean compact;
    final String label;
    private Consumer<TextTile> onFocus;

    TextTile(Context ctx, String big, String small, int color, float restAlpha, Runnable action) {
        this(ctx, big, small, 0, color, restAlpha, action);
    }

    TextTile(Context ctx, String big, String small, int icon, int color, float restAlpha, Runnable action) {
        super(ctx);
        label = small.isEmpty() ? big : big + " · " + small;
        float d = getResources().getDisplayMetrics().density;
        final float lift = 10 * d, radius = 9 * d;
        setFocusable(true);
        setClickable(true);
        setBackgroundColor(color);
        setAlpha(restAlpha);
        setForeground(ctx.getDrawable(R.drawable.tile_ring));
        setContentDescription(label);
        setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
            }
        });
        setClipToOutline(true);

        if (icon != 0) {
            // An icon beside the name, for a device.
            android.widget.LinearLayout line = new android.widget.LinearLayout(ctx);
            line.setOrientation(android.widget.LinearLayout.HORIZONTAL);
            line.setGravity(Gravity.CENTER);
            android.widget.ImageView image = new android.widget.ImageView(ctx);
            image.setImageResource(icon);
            int size = (int) (22 * d);
            line.addView(image, new android.widget.LinearLayout.LayoutParams(size, size));
            TextView name = text(big, 12f);
            name.setSingleLine(true);
            android.widget.LinearLayout.LayoutParams nlp = new android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
            line.addView(name, nlp);
            addView(line, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT, Gravity.CENTER));
            finish(action, restAlpha, lift);
            return;
        }

        TextView top = text(big, small.isEmpty() ? (compact ? 12f : 15f) : (compact ? 15f : 22f));
        LayoutParams tlp = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT,
                small.isEmpty() ? Gravity.CENTER : Gravity.TOP);
        tlp.topMargin = small.isEmpty() ? 0 : (int) ((compact ? 5 : 9) * d);
        addView(top, tlp);
        if (!small.isEmpty()) {
            TextView bottom = text(small, compact ? 9f : 11f);
            LayoutParams blp = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
            blp.bottomMargin = (int) ((compact ? 4 : 9) * d);
            addView(bottom, blp);
        }

        finish(action, restAlpha, lift);
    }

    private void finish(Runnable action, float restAlpha, float lift) {
        setOnClickListener(v -> action.run());
        setOnFocusChangeListener((v, focused) -> {
            animate().cancel();
            animate().scaleX(focused ? Tile.FOCUS_SCALE : 1f).scaleY(focused ? Tile.FOCUS_SCALE : 1f)
                    .alpha(focused ? 1f : restAlpha)
                    .translationZ(focused ? lift : 0f)
                    .setDuration(focused ? 240 : 160)
                    .setInterpolator(new OvershootInterpolator(focused ? 1.4f : 0f))
                    .start();
            if (focused && onFocus != null) onFocus.accept(this);
        });
    }

    private TextView text(String s, float sp) {
        TextView t = new TextView(getContext());
        t.setText(s);
        t.setTextColor(0xFFFFFFFF);
        t.setTextSize(sp);
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        t.setGravity(Gravity.CENTER);
        int pad = (int) (6 * getResources().getDisplayMetrics().density);
        t.setPadding(pad, 0, pad, 0);
        t.setTypeface(getResources().getFont(R.font.outfit));
        return t;
    }

    void setOnTileFocused(Consumer<TextTile> listener) {
        onFocus = listener;
    }
}
