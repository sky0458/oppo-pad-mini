package io.github.externalvnc.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.MotionEvent;
import android.view.View;
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

    public TouchpadView(Context context) {
        super(context);
        setFocusable(true);
        setFocusableInTouchMode(true);
        setBackgroundColor(Color.rgb(31, 43, 56));
    }

    public void setClient(RfbClient client) { this.client = client; }
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

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        paint.setColor(0xffc0d8e4);
        paint.setTextSize(18 * getResources().getDisplayMetrics().scaledDensity);
        canvas.drawText("触控板：单指移动/点击 · 双指滚动", 24, getHeight() / 2f, paint);
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
                return true;
            case MotionEvent.ACTION_POINTER_DOWN:
            case MotionEvent.ACTION_POINTER_UP:
                moved = true;
                lastX = event.getX(); lastY = event.getY();
                return true;
            case MotionEvent.ACTION_MOVE:
                float newX = event.getX(), newY = event.getY();
                float dx = newX - lastX, dy = newY - lastY;
                if (Math.hypot(newX - startX, newY - startY) > 12) moved = true;
                if (client != null || inputListener != null) {
                    if (event.getPointerCount() >= 2) {
                        wheel += dy;
                        while (Math.abs(wheel) >= 30) {
                            boolean up = wheel < 0;
                            emitScroll(up);
                            wheel += up ? 30 : -30;
                        }
                    } else {
                        emitMove(dx, dy);
                    }
                }
                lastX = newX; lastY = newY;
                return true;
            case MotionEvent.ACTION_UP:
                if ((client != null || inputListener != null) && !moved
                        && event.getEventTime() - downTime < 300)
                    emitClick(1);
                getParent().requestDisallowInterceptTouchEvent(false);
                return true;
            case MotionEvent.ACTION_CANCEL:
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
