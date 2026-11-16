package io.github.externalvnc.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

/** A transparent HDMI-only mouse cursor; its redraw never uploads the framebuffer bitmap. */
final class RemoteCursorView extends View {
    private final Paint outline = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path arrow = new Path();
    private int remoteWidth, remoteHeight, cursorX, cursorY;
    private int scaleMode;
    private float userScale = 1f;

    RemoteCursorView(Context context) {
        super(context);
        setClickable(false);
        // macOS-style black arrow with a thin white contrasting border.
        outline.setColor(Color.WHITE);
        outline.setStyle(Paint.Style.STROKE);
        outline.setStrokeWidth(2.6f);
        outline.setStrokeJoin(Paint.Join.ROUND);
        fill.setColor(Color.BLACK);
        fill.setStyle(Paint.Style.FILL);
        arrow.moveTo(0, 0);
        arrow.lineTo(0, 30);
        arrow.lineTo(6.3f, 23.5f);
        arrow.lineTo(12.5f, 38);
        arrow.lineTo(17.1f, 35.6f);
        arrow.lineTo(11.2f, 21.8f);
        arrow.lineTo(21.7f, 21.5f);
        arrow.close();
    }

    void moveTo(int x, int y, int width, int height) {
        if (width <= 0 || height <= 0) return;
        cursorX = x;
        cursorY = y;
        remoteWidth = width;
        remoteHeight = height;
        invalidate();
    }

    void setScaleMode(int mode) {
        scaleMode = mode;
        invalidate();
    }

    void setCursorScale(float scale) {
        userScale = Math.max(.5f, Math.min(2f, scale));
        invalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (remoteWidth == 0 || remoteHeight == 0) return;
        float wRatio = getWidth() / (float) remoteWidth;
        float hRatio = getHeight() / (float) remoteHeight;
        float factor = scaleMode == 1 ? Math.max(wRatio, hRatio) : Math.min(wRatio, hRatio);
        float frameWidth = scaleMode == 2 ? getWidth() : remoteWidth * factor;
        float frameHeight = scaleMode == 2 ? getHeight() : remoteHeight * factor;
        float x = (getWidth() - frameWidth) / 2f + cursorX * frameWidth / remoteWidth;
        float y = (getHeight() - frameHeight) / 2f + cursorY * frameHeight / remoteHeight;
        canvas.save();
        canvas.translate(x, y);
        // 22 px tall on a 1080p screen; stays modest on high-DPI HDMI displays.
        float defaultHeight = 22f * (float) Math.sqrt(getHeight() / 1080f);
        defaultHeight = Math.max(17f, Math.min(34f, defaultHeight));
        float size = defaultHeight / 38f * userScale;
        canvas.scale(size, size);
        canvas.drawPath(arrow, outline);
        canvas.drawPath(arrow, fill);
        canvas.restore();
    }
}
