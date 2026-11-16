package io.github.externalvnc.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.view.View;

import java.util.function.Consumer;
import java.util.List;
import io.github.externalvnc.rfb.RfbClient;

/** VNC framebuffer view owned by the external Presentation window. */
public final class RemoteFrameView extends View {
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private Bitmap bitmap;
    private String message = "Presentation API OK";
    private int scaleMode; // 0 FIT, 1 CENTER_CROP, 2 STRETCH
    private Consumer<String> diagnostic;
    private int framesApplied;
    private boolean firstViewDrawLogged;
    private boolean firstBitmapDrawLogged;
    private boolean diagnosticPattern;
    private Rect viewport;

    public RemoteFrameView(Context context) {
        super(context);
        setBackgroundColor(Color.BLACK);
    }

    public void setScaleMode(int mode) {
        scaleMode = mode;
        invalidate();
    }

    public void setViewport(int x, int y, int w, int h) {
        viewport = w > 0 && h > 0 ? new Rect(x, y, x + w, y + h) : null;
        invalidate();
    }

    public void setMessage(String text) {
        message = text;
        invalidate();
    }

    public void setDiagnosticListener(Consumer<String> callback) {
        diagnostic = callback;
    }

    /** Full-screen saturated pattern tests HDMI composition independently of VNC pixels. */
    public boolean toggleDiagnosticPattern() {
        diagnosticPattern = !diagnosticPattern;
        log("External display diagnostic pattern=" + diagnosticPattern);
        invalidate();
        return diagnosticPattern;
    }

    public void setFrame(int width, int height, int[] pixels) {
        if (bitmap == null || bitmap.getWidth() != width || bitmap.getHeight() != height) {
            if (bitmap != null) bitmap.recycle();
            bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        }
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height);
        framesApplied++;
        if (framesApplied <= 3) log("Bitmap updated #" + framesApplied + " " + width + "x" + height);
        message = null;
        invalidate();
    }

    /** Applies only the changed RFB rectangles, avoiding full-frame bitmap uploads. */
    public void setRegions(int width, int height, List<RfbClient.FrameRegion> regions) {
        if (bitmap == null || bitmap.getWidth() != width || bitmap.getHeight() != height) {
            if (bitmap != null) bitmap.recycle();
            bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            bitmap.eraseColor(Color.BLACK);
        }
        for (RfbClient.FrameRegion region : regions) {
            bitmap.setPixels(region.argb, 0, region.width,
                    region.x, region.y, region.width, region.height);
        }
        framesApplied++;
        if (framesApplied <= 3) log("Bitmap regions #" + framesApplied + " count="
                + regions.size() + " " + width + "x" + height);
        message = null;
        invalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (!firstViewDrawLogged) {
            firstViewDrawLogged = true;
            log("Presentation view onDraw size=" + getWidth() + "x" + getHeight()
                    + " hardwareAccelerated=" + canvas.isHardwareAccelerated());
        }
        canvas.drawColor(Color.BLACK);
        if (diagnosticPattern) {
            int[] colors = {0xffff3030, 0xff30ff30, 0xff3030ff, 0xffffffff};
            for (int i = 0; i < colors.length; i++) {
                paint.setColor(colors[i]);
                canvas.drawRect(i * getWidth() / 4f, 0, (i + 1) * getWidth() / 4f,
                        getHeight(), paint);
            }
            paint.setColor(Color.BLACK);
            paint.setTextSize(72);
            canvas.drawText("HDMI PATTERN TEST", 48, getHeight() / 2f, paint);
            return;
        }
        if (bitmap != null && !bitmap.isRecycled()) {
            Rect source = viewport == null ? new Rect(0, 0, bitmap.getWidth(), bitmap.getHeight())
                    : new Rect(Math.max(0, viewport.left), Math.max(0, viewport.top),
                    Math.min(bitmap.getWidth(), viewport.right),
                    Math.min(bitmap.getHeight(), viewport.bottom));
            if (source.isEmpty()) return;
            float widthRatio = getWidth() / (float) source.width();
            float heightRatio = getHeight() / (float) source.height();
            float scale = scaleMode == 1
                    ? Math.max(widthRatio, heightRatio)
                    : Math.min(widthRatio, heightRatio);
            float displayW = scaleMode == 2 ? getWidth() : source.width() * scale;
            float displayH = scaleMode == 2 ? getHeight() : source.height() * scale;
            float left = (getWidth() - displayW) / 2f;
            float top = (getHeight() - displayH) / 2f;
            paint.setColor(Color.WHITE);
            try {
                canvas.drawBitmap(bitmap, source, new RectF(left, top,
                        left + displayW, top + displayH), paint);
                if (!firstBitmapDrawLogged) {
                    firstBitmapDrawLogged = true;
                    log("First bitmap drawn; view=" + getWidth() + "x" + getHeight()
                            + ", bitmap=" + bitmap.getWidth() + "x" + bitmap.getHeight());
                }
            } catch (RuntimeException error) {
                log("Bitmap draw failed: " + error.getClass().getSimpleName() + " " + error.getMessage());
                paint.setColor(Color.RED);
                paint.setTextSize(36);
                canvas.drawText("VNC bitmap render error - export logs", 24, getHeight() / 2f, paint);
            }
        } else if (message != null) {
            paint.setColor(0xff80ebd0);
            paint.setTextSize(Math.max(24, getWidth() / 42f));
            canvas.drawText(message, Math.max(24, getWidth() * 0.05f),
                    getHeight() / 2f, paint);
        }
        // Deliberately visible during hardware testing, even when the remote pixels are black.
        if (framesApplied > 0 && bitmap != null && !bitmap.isRecycled()) {
            paint.setColor(0xb9000000);
            canvas.drawRect(0, 0, Math.min(getWidth(), 1050), 64, paint);
            paint.setTextSize(30);
            paint.setColor(0xff80ebd0);
            canvas.drawText("HDMI VNC | frame " + framesApplied + " | "
                            + bitmap.getWidth() + "x" + bitmap.getHeight(), 22, 44, paint);
        }
    }

    private void log(String text) {
        if (diagnostic != null) diagnostic.accept(text);
    }

    public void release() {
        if (bitmap != null) {
            bitmap.recycle();
            bitmap = null;
        }
    }
}
