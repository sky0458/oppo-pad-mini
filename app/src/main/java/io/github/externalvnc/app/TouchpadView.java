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
    private RfbClient client;
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
                if (client != null) {
                    if (event.getPointerCount() >= 2) {
                        wheel += dy;
                        while (Math.abs(wheel) >= 30) {
                            boolean up = wheel < 0;
                            client.scroll(up);
                            wheel += up ? 30 : -30;
                        }
                    } else {
                        client.sendRelativeMove(dx * 1.8f, dy * 1.8f);
                    }
                }
                lastX = newX; lastY = newY;
                return true;
            case MotionEvent.ACTION_UP:
                if (client != null && !moved && event.getEventTime() - downTime < 300)
                    client.click(1);
                getParent().requestDisallowInterceptTouchEvent(false);
                return true;
            case MotionEvent.ACTION_CANCEL:
                getParent().requestDisallowInterceptTouchEvent(false);
                return true;
            default: return true;
        }
    }

    @Override public boolean onGenericMotionEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_SCROLL && client != null) {
            float dy = event.getAxisValue(MotionEvent.AXIS_VSCROLL);
            if (dy != 0) { client.scroll(dy > 0); return true; }
        }
        return super.onGenericMotionEvent(event);
    }
}
