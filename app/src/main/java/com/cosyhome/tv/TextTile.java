package com.cosyhome.tv;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Outline;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.function.Consumer;

/**
 * A tile with no app behind it, a channel or a device: frosted glass at rest, white when focused.
 * A channel shows its number and name side by side; a device its icon and name.
 */
@SuppressLint("ViewConstructor")
final class TextTile extends FrameLayout {

    private static final int GLASS = 0x4D0E1620, GLASS_EDGE = 0x33FFFFFF;
    private static final int FOCUSED = 0xF7FFFFFF, INK = 0xFF15212B;

    final String label;
    private Consumer<TextTile> onFocus;
    private final GradientDrawable bg = new GradientDrawable();
    private final TextView main;
    private final TextView lead;
    private final ImageView icon;

    /** A channel: its number, dimmer, before its name. */
    TextTile(Context ctx, String number, String name, float restAlpha, Runnable action) {
        this(ctx, number, name, 0, restAlpha, action);
    }

    /** A device: its icon before its name. */
    TextTile(Context ctx, String name, int iconRes, float restAlpha, Runnable action) {
        this(ctx, null, name, iconRes, restAlpha, action);
    }

    private TextTile(Context ctx, String number, String name, int iconRes, float restAlpha, Runnable action) {
        super(ctx);
        label = number == null ? name : number + " · " + name;
        float d = getResources().getDisplayMetrics().density;
        final float lift = 8 * d, radius = 12 * d;
        setFocusable(true);
        setClickable(true);
        bg.setCornerRadius(radius);
        bg.setColor(GLASS);
        bg.setStroke(Math.max(1, Math.round(d)), GLASS_EDGE);
        setBackground(bg);
        setAlpha(restAlpha);
        setContentDescription(label);
        setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
            }
        });

        LinearLayout line = new LinearLayout(ctx);
        line.setOrientation(LinearLayout.HORIZONTAL);
        line.setGravity(Gravity.CENTER);
        int pad = Math.round(10 * d);
        line.setPadding(pad, 0, pad, 0);
        if (iconRes != 0) {
            icon = new ImageView(ctx);
            icon.setImageResource(iconRes);
            icon.setImageTintList(ColorStateList.valueOf(0xFFFFFFFF));
            int size = Math.round(20 * d);
            LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(size, size);
            ilp.setMarginEnd(Math.round(8 * d));
            line.addView(icon, ilp);
            lead = null;
        } else {
            icon = null;
            lead = text(number, 17f, 300);
            lead.setAlpha(0.7f);
            LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            llp.setMarginEnd(Math.round(8 * d));
            line.addView(lead, llp);
        }
        main = text(name, 13f, 500);
        line.addView(main, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        addView(line, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT, Gravity.CENTER));

        setOnClickListener(v -> action.run());
        setOnFocusChangeListener((v, focused) -> {
            setInk(focused);
            animate().cancel();
            animate().scaleX(focused ? Tile.FOCUS_SCALE : 1f).scaleY(focused ? Tile.FOCUS_SCALE : 1f)
                    .alpha(focused ? 1f : restAlpha)
                    .translationZ(focused ? lift : 0f)
                    .setDuration(focused ? 200 : 160)
                    .setInterpolator(new DecelerateInterpolator(2f))
                    .start();
            if (focused && onFocus != null) onFocus.accept(this);
        });
    }

    /** White glass and dark type when focused, the other way round at rest. */
    private void setInk(boolean focused) {
        bg.setColor(focused ? FOCUSED : GLASS);
        int ink = focused ? INK : 0xFFFFFFFF;
        main.setTextColor(ink);
        if (lead != null) lead.setTextColor(ink);
        if (icon != null) icon.setImageTintList(ColorStateList.valueOf(ink));
    }

    private TextView text(String s, float sp, int weight) {
        TextView t = new TextView(getContext());
        t.setText(s);
        t.setTextColor(0xFFFFFFFF);
        t.setTextSize(sp);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        t.setIncludeFontPadding(false);
        t.setTypeface(getResources().getFont(R.font.outfit));
        t.setFontVariationSettings("'wght' " + weight);
        return t;
    }

    void setOnTileFocused(Consumer<TextTile> listener) {
        onFocus = listener;
    }
}
