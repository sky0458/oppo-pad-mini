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

    RemoteCursorView(Context context) {
        super(context);
        setClickable(false);
        outline.setColor(Color.BLACK);
        outline.setStyle(Paint.Style.STROKE);
        outline.setStrokeWidth(3.5f);
        outline.setStrokeJoin(Paint.Join.ROUND);
        fill.setColor(Color.WHITE);
        fill.setStyle(Paint.Style.FILL);
        arrow.moveTo(0, 0);
        arrow.lineTo(0, 30);
        arrow.lineTo(7, 23);
        arrow.lineTo(12, 37);
        arrow.lineTo(18, 34);
        arrow.lineTo(12, 20);
        arrow.lineTo(22, 20);
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
        float size = Math.max(1.6f, Math.min(getWidth(), getHeight()) / 900f);
        canvas.scale(size, size);
        canvas.drawPath(arrow, outline);
        canvas.drawPath(arrow, fill);
        canvas.restore();
    }
}
