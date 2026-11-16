package io.github.externalvnc.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import io.github.externalvnc.rfb.RfbClient;

/** Lightweight 87-key ANSI tenkeyless keyboard; sends RFB keysyms without an Android IME. */
final class OnScreenKeyboardView extends View {
    private static final int SHIFT_L = 0xffe1, SHIFT_R = 0xffe2;
    private static final int CTRL_L = 0xffe3, CTRL_R = 0xffe4;
    private static final int ALT_L = 0xffe9, ALT_R = 0xffea;
    private static final int CMD_L = 0xffeb, CMD_R = 0xffec;
    private static final int CAPS = 0xffe5;

    private static final class Key {
        final String caption;
        final int sym;
        final float weight;
        final boolean modifier;

        Key(String caption, int sym, float weight, boolean modifier) {
            this.caption = caption;
            this.sym = sym;
            this.weight = weight;
            this.modifier = modifier;
        }
    }

    private static Key k(String label, int sym) { return new Key(label, sym, 1f, false); }
    private static Key w(String label, int sym, float weight) {
        return new Key(label, sym, weight, false);
    }
    private static Key m(String label, int sym, float weight) {
        return new Key(label, sym, weight, true);
    }
    private static Key[] letterRow(String letters) {
        Key[] keys = new Key[letters.length()];
        for (int i = 0; i < letters.length(); i++)
            keys[i] = k(String.valueOf(letters.charAt(i)),
                    Character.toLowerCase(letters.charAt(i)));
        return keys;
    }

    private static final Key[][] MAIN = {
            { k("Esc", 0xff1b), k("F1", 0xffbe), k("F2", 0xffbf), k("F3", 0xffc0),
              k("F4", 0xffc1), k("F5", 0xffc2), k("F6", 0xffc3), k("F7", 0xffc4),
              k("F8", 0xffc5), k("F9", 0xffc6), k("F10", 0xffc7), k("F11", 0xffc8),
              k("F12", 0xffc9) },
            { k("` ~", '`'), k("1 !", '1'), k("2 @", '2'), k("3 #", '3'),
              k("4 $", '4'), k("5 %", '5'), k("6 ^", '6'), k("7 &", '7'),
              k("8 *", '8'), k("9 (", '9'), k("0 )", '0'), k("- _", '-'),
              k("= +", '='), w("⌫", 0xff08, 1.8f) },
            { w("Tab", 0xff09, 1.5f), k("Q", 'q'), k("W", 'w'), k("E", 'e'),
              k("R", 'r'), k("T", 't'), k("Y", 'y'), k("U", 'u'), k("I", 'i'),
              k("O", 'o'), k("P", 'p'), k("[ {", '['), k("] }", ']'),
              w("\\ |", '\\', 1.5f) },
            { w("Caps", CAPS, 1.8f), k("A", 'a'), k("S", 's'), k("D", 'd'),
              k("F", 'f'), k("G", 'g'), k("H", 'h'), k("J", 'j'), k("K", 'k'),
              k("L", 'l'), k("; :", ';'), k("' \"", '\''), w("↵ Enter", 0xff0d, 2.2f) },
            { m("⇧ Shift", SHIFT_L, 2.2f), k("Z", 'z'), k("X", 'x'),
              k("C", 'c'), k("V", 'v'), k("B", 'b'), k("N", 'n'), k("M", 'm'),
              k(", <", ','), k(". >", '.'), k("/ ?", '/'), m("⇧ Shift", SHIFT_R, 2.5f) },
            { m("Ctrl", CTRL_L, 1.3f), m("⌘", CMD_L, 1.2f),
              m("⌥ Opt", ALT_L, 1.3f), w("Space", ' ', 5.2f),
              m("⌥ Opt", ALT_R, 1.3f), m("⌘", CMD_R, 1.2f),
              k("Menu", 0xff67), m("Ctrl", CTRL_R, 1.3f) },
    };
    private static final Key[][] NAV = {
            { k("Prt", 0xff61), k("ScrLk", 0xff14), k("Pause", 0xff13) },
            { k("Ins", 0xff63), k("Home", 0xff50), k("PgUp", 0xff55) },
            { k("Del", 0xffff), k("End", 0xff57), k("PgDn", 0xff56) },
            {},
            { k("↑", 0xff52) },
            { k("←", 0xff51), k("↓", 0xff54), k("→", 0xff53) },
    };

    static int keyCount() {
        int count = 0;
        for (Key[] row : MAIN) count += row.length;
        for (Key[] row : NAV) count += row.length;
        return count;
    }

    private static final class Hit {
        final Key key;
        final RectF area;
        Hit(Key key, RectF area) { this.key = key; this.area = area; }
    }

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final List<Hit> hits = new ArrayList<>(87);
    private final Set<Integer> heldModifiers = new LinkedHashSet<>();
    private RfbClient client;
    private Hit pressed;
    private boolean caps;

    OnScreenKeyboardView(Context context) {
        super(context);
        setBackgroundColor(0xeb15202e);
        setClickable(true);
        setFocusable(false);
        setContentDescription("87 键远程键盘，点击功能键或组合键可输入到 Mac");
    }

    void setClient(RfbClient next) {
        releaseModifiers();
        client = next;
        invalidate();
    }

    void releaseModifiers() {
        if (client != null)
            for (int sym : heldModifiers) client.sendKey(sym, false);
        heldModifiers.clear();
        pressed = null;
        invalidate();
    }

    private void tapKey(Key key) {
        if (client == null) return;
        if (key.modifier) {
            if (heldModifiers.remove(key.sym)) client.sendKey(key.sym, false);
            else {
                heldModifiers.add(key.sym);
                client.sendKey(key.sym, true);
            }
        } else {
            client.sendKey(key.sym, true);
            client.sendKey(key.sym, false);
            if (key.sym == CAPS) caps = !caps;
            releaseModifiers(); // sticky modifiers apply to the next key only
        }
        invalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        hits.clear();
        final float margin = dp(5), gap = Math.max(dp(2), getWidth() / 450f);
        final float leftWidth = (getWidth() - margin * 3) * .805f;
        final float navLeft = margin * 2 + leftWidth;
        final float navWidth = getWidth() - navLeft - margin;
        final float rowHeight = (getHeight() - 2 * margin) / 6f;
        for (int row = 0; row < 6; row++) {
            float top = margin + row * rowHeight + gap / 2f;
            float h = rowHeight - gap;
            drawKeys(canvas, MAIN[row], margin, leftWidth, top, h, gap);
            Key[] nav = NAV[row];
            if (nav.length == 1)
                drawKeys(canvas, nav, navLeft + navWidth / 3f,
                        navWidth / 3f, top, h, gap);
            else if (nav.length > 0)
                drawKeys(canvas, nav, navLeft, navWidth, top, h, gap);
        }
    }

    private void drawKeys(Canvas canvas, Key[] row, float start, float total,
                          float top, float height, float gap) {
        float weightSum = 0;
        for (Key key : row) weightSum += key.weight;
        float scale = (total - gap * Math.max(0, row.length - 1)) / weightSum;
        float x = start;
        for (Key key : row) {
            float width = scale * key.weight;
            RectF area = new RectF(x, top, x + width, top + height);
            hits.add(new Hit(key, area));
            boolean selected = heldModifiers.contains(key.sym) || (key.sym == CAPS && caps);
            boolean touching = pressed != null && pressed.key == key;
            paint.setColor(touching ? 0xff79c4d2 : selected ? 0xffdca54c
                    : key.sym == 0xff1b || key.sym == 0xff0d ? 0xff934950 : 0xff394759);
            canvas.drawRoundRect(area, dp(4), dp(4), paint);
            paint.setColor(touching ? Color.BLACK : Color.WHITE);
            float size = Math.min(dp(13), Math.min(height * .43f, width * .3f));
            paint.setTextSize(Math.max(dp(7.5f), size));
            paint.setTextAlign(Paint.Align.CENTER);
            Paint.FontMetrics metrics = paint.getFontMetrics();
            float baseline = area.centerY() - (metrics.ascent + metrics.descent) / 2f;
            canvas.drawText(key.caption, area.centerX(), baseline, paint);
            x += width + gap;
        }
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                pressed = hitAt(event.getX(), event.getY());
                invalidate();
                return true;
            case MotionEvent.ACTION_UP:
                Hit released = hitAt(event.getX(), event.getY());
                if (pressed != null && released != null && pressed.key == released.key)
                    tapKey(pressed.key);
                pressed = null;
                invalidate();
                performClick();
                return true;
            case MotionEvent.ACTION_CANCEL:
                pressed = null;
                invalidate();
                return true;
            default: return true;
        }
    }

    @Override public boolean performClick() {
        super.performClick();
        return true;
    }

    private Hit hitAt(float x, float y) {
        for (Hit hit : hits) if (hit.area.contains(x, y)) return hit;
        return null;
    }

    private float dp(float px) { return px * getResources().getDisplayMetrics().density; }
}
