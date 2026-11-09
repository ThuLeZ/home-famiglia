package com.cosyhome.tv;

import android.content.Context;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.widget.ScrollView;

/**
 * A ScrollView that scrolls a focused tile fully into view with room left for its focus zoom. With
 * row snapping on, it leaves scrolling to its owner, which moves it a whole row at a time.
 */
public class RoomyScrollView extends ScrollView {

    private final int room;
    private boolean snapping;

    public RoomyScrollView(Context context, AttributeSet attrs) {
        super(context, attrs);
        room = Math.round(12 * getResources().getDisplayMetrics().density);
    }

    void setRowSnapping(boolean on) {
        snapping = on;
    }

    @Override
    protected int computeScrollDeltaToGetChildRectOnScreen(Rect rect) {
        if (snapping) return 0;
        Rect padded = new Rect(rect);
        padded.inset(0, -room);
        return super.computeScrollDeltaToGetChildRectOnScreen(padded);
    }
}
