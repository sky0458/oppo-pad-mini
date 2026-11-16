package io.github.externalvnc.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import io.github.externalvnc.rfb.RfbClient;

/** A touchpad on the Android primary display, sending RFB input to the remote computer. */
public final class TouchpadView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    public interface InputListener {
        void onMove(float dx, float dy);
        void onClick(int button);
        void onScroll(boolean up);
    }
    public interface CaptureListener {
        void onChanged(boolean hasCapture);
    }

    private RfbClient client;
    private float mouseSpeed = 2f;
    private InputListener inputListener;
    private CaptureListener captureListener;
    private boolean hoverKnown;
    private float hoverX, hoverY;
    private float lastX, lastY, startX, startY, wheel;
    private long downTime;
    private boolean moved;
    private boolean twoFingerTap, twoFingerMoved, dragging;
    private final int touchSlop;
    private final Runnable beginDrag = () -> {
        if (!moved && !twoFingerTap && client != null && !dragging) {
            dragging = true;
            client.setButtons(1);
            performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
        }
    };

    public TouchpadView(Context context) {
        super(context);
        setFocusable(true);
        setFocusableInTouchMode(true);
        setBackgroundColor(Color.rgb(31, 43, 56));
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
    }

    public void setClient(RfbClient client) {
        stopDrag();
        this.client = client;
    }
    public void setMouseSpeed(float mouseSpeed) { this.mouseSpeed = mouseSpeed; }
    public void setInputListener(InputListener listener) { inputListener = listener; }
    public void setCaptureListener(CaptureListener listener) { captureListener = listener; }

    @Override public void onPointerCaptureChange(boolean hasCapture) {
        super.onPointerCaptureChange(hasCapture);
        if (captureListener != null) captureListener.onChanged(hasCapture);
    }

    private void emitMove(float dx, float dy) {
        if (client != null) {
            // The full touchpad width/height corresponds to the full remote screen.
            // A fixed 1.8x multiplier never preserved that proportional mapping.
            float scaleX = client.getFramebufferWidth() / (float) Math.max(1, getWidth());
            float scaleY = client.getFramebufferHeight() / (float) Math.max(1, getHeight());
            client.sendRelativeMove(dx * scaleX * mouseSpeed,
                    dy * scaleY * mouseSpeed);
        }
        if (inputListener != null) inputListener.onMove(dx, dy);
    }

    private void emitClick(int button) {
        if (client != null) client.click(button);
        if (inputListener != null) inputListener.onClick(button);
    }

    private void emitScroll(boolean up) {
        if (client != null) client.scroll(up);
        if (inputListener != null) inputListener.onScroll(up);
    }

    private void stopDrag() {
        removeCallbacks(beginDrag);
        if (dragging && client != null) client.setButtons(0);
        dragging = false;
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        paint.setColor(0xffc0d8e4);
        paint.setTextSize(18 * getResources().getDisplayMetrics().scaledDensity);
        canvas.drawText("触控板：移动/点击 · 双指右键/滚动 · 长按拖拽",
                24, getHeight() / 2f, paint);
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        switch (action) {
            case MotionEvent.ACTION_DOWN:
                requestFocus();
                getParent().requestDisallowInterceptTouchEvent(true);
                lastX = startX = event.getX();
                lastY = startY = event.getY();
                downTime = event.getEventTime();
                wheel = 0;
                moved = false;
                twoFingerTap = false;
                twoFingerMoved = false;
                stopDrag();
                postDelayed(beginDrag, ViewConfiguration.getLongPressTimeout());
                return true;
            case MotionEvent.ACTION_POINTER_DOWN:
                removeCallbacks(beginDrag);
                stopDrag();
                if (event.getPointerCount() == 2) {
                    twoFingerTap = true;
                    twoFingerMoved = false;
                    lastX = (event.getX(0) + event.getX(1)) / 2f;
                    lastY = (event.getY(0) + event.getY(1)) / 2f;
                } else twoFingerTap = false;
                moved = true;
                return true;
            case MotionEvent.ACTION_POINTER_UP:
                moved = true;
                int released = event.getActionIndex();
                int remaining = released == 0 ? 1 : 0;
                lastX = event.getX(remaining); lastY = event.getY(remaining);
                return true;
            case MotionEvent.ACTION_MOVE:
                boolean multiple = event.getPointerCount() >= 2;
                float newX = multiple ? (event.getX(0) + event.getX(1)) / 2f : event.getX();
                float newY = multiple ? (event.getY(0) + event.getY(1)) / 2f : event.getY();
                float dx = newX - lastX, dy = newY - lastY;
                if (!multiple && Math.hypot(newX - startX, newY - startY) > touchSlop) {
                    moved = true;
                    removeCallbacks(beginDrag);
                }
                if (client != null || inputListener != null) {
                    if (multiple) {
                        if (Math.hypot(dx, dy) > touchSlop / 2f) twoFingerMoved = true;
                        wheel += dy;
                        while (Math.abs(wheel) >= 30) {
                            boolean up = wheel < 0;
                            emitScroll(up);
                            twoFingerMoved = true;
                            wheel += up ? 30 : -30;
                        }
                    } else {
                        emitMove(dx, dy);
                    }
                }
                lastX = newX; lastY = newY;
                return true;
            case MotionEvent.ACTION_UP:
                removeCallbacks(beginDrag);
                if (dragging) stopDrag();
                else if ((client != null || inputListener != null)
                        && event.getEventTime() - downTime < 300) {
                    if (twoFingerTap && !twoFingerMoved) emitClick(4);
                    else if (!moved) emitClick(1);
                }
                getParent().requestDisallowInterceptTouchEvent(false);
                return true;
            case MotionEvent.ACTION_CANCEL:
                stopDrag();
                getParent().requestDisallowInterceptTouchEvent(false);
                return true;
            default: return true;
        }
    }

    @Override public boolean onGenericMotionEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_SCROLL) {
            float dy = event.getAxisValue(MotionEvent.AXIS_VSCROLL);
            if (dy != 0) { emitScroll(dy > 0); return true; }
        }
        if (event.getActionMasked() == MotionEvent.ACTION_HOVER_ENTER) {
            hoverKnown = false;
            return true;
        }
        if (event.getActionMasked() == MotionEvent.ACTION_HOVER_MOVE) {
            float x = event.getX(), y = event.getY();
            if (hoverKnown) emitMove(x - hoverX, y - hoverY);
            hoverX = x; hoverY = y; hoverKnown = true;
            return true;
        }
        if (event.getActionMasked() == MotionEvent.ACTION_HOVER_EXIT) {
            hoverKnown = false;
            return true;
        }
        return super.onGenericMotionEvent(event);
    }
}
