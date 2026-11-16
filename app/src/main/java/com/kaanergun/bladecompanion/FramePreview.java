package com.kaanergun.bladecompanion;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;
import com.kaanergun.bladecompanion.core.FrameCoordinates;

/** Fit-center preview with frame-relative arrow gestures only while explicitly frozen. */
public final class FramePreview extends View {
    public interface Listener { void arrow(FrameCoordinates.Point from, FrameCoordinates.Point to); }
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private Bitmap image;
    private boolean editable;
    private FrameCoordinates.Point from, to;
    private final Listener listener;

    public FramePreview(Context context, Listener listener) { super(context); this.listener = listener; setFocusable(true); }
    public boolean hasImage() { return image != null; }
    public void showArrow(FrameCoordinates.Point start, FrameCoordinates.Point end) { from = start; to = end; invalidate(); }
    public void display(Bitmap next, boolean allowArrow) {
        if (next != image || editable != allowArrow) { from = null; to = null; }
        image = next; editable = allowArrow; invalidate();
    }
    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas); canvas.drawColor(Color.rgb(18, 23, 29));
        if (image == null || getWidth() == 0 || getHeight() == 0) { return; }
        FrameCoordinates.Viewport viewport = viewport();
        canvas.drawBitmap(image, null, new RectF((float) viewport.left, (float) viewport.top,
                (float) (viewport.left + viewport.width), (float) (viewport.top + viewport.height)), paint);
        if (from != null && to != null) {
            float x0 = (float) viewport.viewX(from), y0 = (float) viewport.viewY(from);
            float x1 = (float) viewport.viewX(to), y1 = (float) viewport.viewY(to);
            paint.setColor(Color.YELLOW); paint.setStrokeWidth(5 * getResources().getDisplayMetrics().density);
            canvas.drawLine(x0, y0, x1, y1, paint);
            double angle = Math.atan2(y1 - y0, x1 - x0);
            float length = 16 * getResources().getDisplayMetrics().density;
            for (double delta : new double[] { -0.5, 0.5 }) {
                canvas.drawLine(x1, y1, x1 - (float) Math.cos(angle + delta) * length,
                        y1 - (float) Math.sin(angle + delta) * length, paint);
            }
        }
    }
    @Override public boolean onTouchEvent(MotionEvent event) {
        if (!editable || image == null || getWidth() == 0 || getHeight() == 0) { return false; }
        FrameCoordinates.Point point = viewport().fromView(event.getX(), event.getY());
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (point == null) { return false; }
                from = point; to = point; getParent().requestDisallowInterceptTouchEvent(true); invalidate(); return true;
            case MotionEvent.ACTION_MOVE:
                if (from == null) { return false; }
                if (point != null) { to = point; invalidate(); }
                return true;
            case MotionEvent.ACTION_UP:
                getParent().requestDisallowInterceptTouchEvent(false);
                if (from != null && point != null && (from.x != point.x || from.y != point.y)) {
                    to = point; listener.arrow(from, to); invalidate();
                } else { from = null; to = null; invalidate(); }
                performClick(); return true;
            case MotionEvent.ACTION_CANCEL:
                from = null; to = null; getParent().requestDisallowInterceptTouchEvent(false); invalidate(); return true;
            default: return false;
        }
    }
    @Override public boolean performClick() { super.performClick(); return true; }
    private FrameCoordinates.Viewport viewport() {
        return new FrameCoordinates.Viewport(image.getWidth(), image.getHeight(), getWidth(), getHeight());
    }
}
