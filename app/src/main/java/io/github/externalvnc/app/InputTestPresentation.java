package io.github.externalvnc.app;

import android.app.Presentation;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Bundle;
import android.view.Display;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * No VNC required: checks whether physical mouse/keyboard events collected on the
 * Pad can be represented as independent content on an HDMI Presentation.
 *
 * The bright crosshair is an application-rendered virtual cursor, NOT the
 * Android system mouse pointer and NOT a cursor moved to the secondary Display.
 */
public final class InputTestPresentation extends Presentation {
    private TestCanvas testCanvas;

    public InputTestPresentation(Context context, Display display) {
        super(context, display);
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);
        // Physical inputs stay focused on the control Activity on the tablet.
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
        testCanvas = new TestCanvas(getContext());
        setContentView(testCanvas);
    }

    public void move(float dx, float dy) {
        if (testCanvas != null) testCanvas.move(dx, dy);
    }

    public void button(int mask, boolean down) {
        if (testCanvas != null) testCanvas.button(mask, down);
    }

    public void click(int mask) {
        if (testCanvas != null) {
            testCanvas.button(mask, true);
            testCanvas.button(mask, false);
        }
    }

    public void scroll(boolean up) {
        if (testCanvas != null) testCanvas.scroll(up);
    }

    public void key(KeyEvent event) {
        if (testCanvas != null) testCanvas.key(event);
    }

    public void captured(boolean hasCapture) {
        if (testCanvas != null) testCanvas.captured(hasCapture);
    }

    private static final class TestCanvas extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Deque<String> recent = new ArrayDeque<>();
        private float pointerX = 0.5f; // normalized to secondary screen
        private float pointerY = 0.5f;
        private String lastKey = "(no key received)";
        private String lastButton = "(no click received)";
        private int moves, clicks, scrolls, keys;
        private int heldButtons;
        private boolean pointerCaptured;

        TestCanvas(Context context) { super(context); }

        void captured(boolean value) {
            pointerCaptured = value;
            note(value ? "Pointer capture enabled" : "Pointer capture released");
        }

        void move(float dx, float dy) {
            if (dx == 0 && dy == 0) return;
            if (getWidth() > 0 && getHeight() > 0) {
                pointerX = clamp(pointerX + dx * 2.5f / getWidth());
                pointerY = clamp(pointerY + dy * 2.5f / getHeight());
            }
            moves++;
            invalidate();
        }

        void button(int mask, boolean down) {
            if (mask == 0) return;
            if (down) {
                heldButtons |= mask;
                clicks++;
            } else {
                heldButtons &= ~mask;
            }
            lastButton = buttonName(mask) + (down ? " DOWN" : " UP");
            note(lastButton);
        }

        void scroll(boolean up) {
            scrolls++;
            note("Scroll " + (up ? "UP" : "DOWN"));
        }

        void key(KeyEvent event) {
            if (event.getAction() != KeyEvent.ACTION_DOWN
                    && event.getAction() != KeyEvent.ACTION_UP) return;
            String action = event.getAction() == KeyEvent.ACTION_DOWN ? "DOWN" : "UP";
            int unicode = event.getUnicodeChar();
            String printable = Character.isValidCodePoint(unicode) && unicode >= 32
                    ? new String(Character.toChars(unicode)) : "";
            lastKey = KeyEvent.keyCodeToString(event.getKeyCode()) + " " + action
                    + (printable.isEmpty() ? "" : " [" + printable + "]");
            if (event.getAction() == KeyEvent.ACTION_DOWN) keys++;
            note("Key: " + lastKey);
        }

        private void note(String message) {
            recent.addFirst(message);
            while (recent.size() > 7) recent.removeLast();
            invalidate();
        }

        private static float clamp(float value) {
            return Math.max(0.015f, Math.min(0.985f, value));
        }

        private static String buttonName(int mask) {
            switch (mask) {
                case 1: return "LEFT";
                case 2: return "MIDDLE";
                case 4: return "RIGHT";
                default: return "BUTTON " + mask;
            }
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            int w = getWidth(), h = getHeight();
            if (w == 0 || h == 0) return;

            canvas.drawColor(0xff0b1220);
            float unit = Math.max(1f, Math.min(w / 1920f, h / 1080f));
            float margin = 46 * unit;
            float titleSize = 42 * unit;
            float medium = 27 * unit;
            float small = 23 * unit;
            paint.setStyle(Paint.Style.FILL);

            paint.setColor(0xff72ead2);
            paint.setTextSize(titleSize);
            paint.setFakeBoldText(true);
            canvas.drawText("HDMI INPUT TEST", margin, 82 * unit, paint);
            paint.setFakeBoldText(false);
            paint.setTextSize(medium);
            paint.setColor(0xffd7e4f5);
            canvas.drawText("Presentation + virtual cursor (no VNC needed)", margin,
                    126 * unit, paint);
            paint.setTextSize(small);
            paint.setColor(0xff91a7bd);
            canvas.drawText("The system pointer can remain on the Pad. This green crosshair is drawn by the app.",
                    margin, 165 * unit, paint);

            paint.setColor(pointerCaptured ? 0xff185940 : 0xff604923);
            canvas.drawRoundRect(new RectF(margin, 195 * unit, 600 * unit,
                    252 * unit), 12 * unit, 12 * unit, paint);
            paint.setColor(Color.WHITE);
            paint.setTextSize(medium);
            canvas.drawText(pointerCaptured ? "Pointer capture: ACTIVE" :
                    "Pointer capture: OFF / mouse over touchpad", margin + 16 * unit,
                    234 * unit, paint);

            paint.setColor(0xffd7e4f5);
            canvas.drawText("Motion events: " + moves + "    Clicks: " + clicks
                    + "    Scroll: " + scrolls + "    Keys: " + keys,
                    margin, 305 * unit, paint);
            canvas.drawText("Last button: " + lastButton, margin, 349 * unit, paint);
            canvas.drawText("Last key: " + lastKey, margin, 393 * unit, paint);
            canvas.drawText("Held buttons: " + heldButtons, margin, 437 * unit, paint);

            float panelX = margin;
            float panelY = 485 * unit;
            paint.setColor(0xff202e41);
            canvas.drawRoundRect(new RectF(panelX, panelY,
                    Math.min(w - margin, 1070 * unit), Math.min(h - margin, 930 * unit)),
                    16 * unit, 16 * unit, paint);
            paint.setColor(0xffcad6e5);
            paint.setTextSize(medium);
            canvas.drawText("Recent input events:", panelX + 24 * unit,
                    panelY + 46 * unit, paint);
            float y = panelY + 90 * unit;
            paint.setTextSize(small);
            for (String entry : recent) {
                canvas.drawText("> " + entry, panelX + 24 * unit, y, paint);
                y += 43 * unit;
            }

            // The crosshair is drawn in the HDMI Presentation window, independent of
            // the default Android pointer visible on the Pad.
            float x = pointerX * w, cy = pointerY * h;
            paint.setColor(0x7772ead2);
            paint.setStrokeWidth(3 * unit);
            canvas.drawLine(x - 38 * unit, cy, x + 38 * unit, cy, paint);
            canvas.drawLine(x, cy - 38 * unit, x, cy + 38 * unit, paint);
            paint.setColor(0xff46f7c6);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(5 * unit);
            canvas.drawCircle(x, cy, 18 * unit, paint);
            paint.setStyle(Paint.Style.FILL);
            canvas.drawCircle(x, cy, 7 * unit, paint);
            paint.setTextSize(small);
            canvas.drawText("APP CURSOR", Math.min(w - 180 * unit, x + 28 * unit),
                    Math.max(40 * unit, cy - 25 * unit), paint);
        }
    }
}
