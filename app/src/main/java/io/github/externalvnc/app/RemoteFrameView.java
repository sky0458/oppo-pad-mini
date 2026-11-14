package io.github.externalvnc.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/** VNC framebuffer view owned by the external Presentation window. */
public final class RemoteFrameView extends View {
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private Bitmap bitmap;
    private String message = "Presentation API OK";
    private int scaleMode; // 0 FIT, 1 CENTER_CROP, 2 STRETCH

    public RemoteFrameView(Context context) {
        super(context);
        setBackgroundColor(Color.BLACK);
    }

    public void setScaleMode(int mode) {
        scaleMode = mode;
        invalidate();
    }

    public void setMessage(String text) {
        message = text;
        invalidate();
    }

    public void setFrame(int width, int height, int[] pixels) {
        if (bitmap == null || bitmap.getWidth() != width || bitmap.getHeight() != height) {
            if (bitmap != null) bitmap.recycle();
            bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        }
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height);
        message = null;
        invalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.drawColor(Color.BLACK);
        if (bitmap != null && !bitmap.isRecycled()) {
            float widthRatio = getWidth() / (float) bitmap.getWidth();
            float heightRatio = getHeight() / (float) bitmap.getHeight();
            float scale = scaleMode == 1
                    ? Math.max(widthRatio, heightRatio)
                    : Math.min(widthRatio, heightRatio);
            float displayW = scaleMode == 2 ? getWidth() : bitmap.getWidth() * scale;
            float displayH = scaleMode == 2 ? getHeight() : bitmap.getHeight() * scale;
            float left = (getWidth() - displayW) / 2f;
            float top = (getHeight() - displayH) / 2f;
            paint.setColor(Color.WHITE);
            canvas.drawBitmap(bitmap, null, new RectF(left, top,
                    left + displayW, top + displayH), paint);
        } else if (message != null) {
            paint.setColor(0xff80ebd0);
            paint.setTextSize(Math.max(24, getWidth() / 42f));
            canvas.drawText(message, Math.max(24, getWidth() * 0.05f),
                    getHeight() / 2f, paint);
        }
    }

    public void release() {
        if (bitmap != null) {
            bitmap.recycle();
            bitmap = null;
        }
    }
}
