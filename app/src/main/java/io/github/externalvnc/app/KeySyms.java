package io.github.externalvnc.app;

import android.view.KeyEvent;

/** Basic Android KeyEvent to X11 RFB keysym mapping for physical keyboards. */
public final class KeySyms {
    private KeySyms() { }

    public static int map(KeyEvent event) {
        int key = event.getKeyCode();
        switch (key) {
            case KeyEvent.KEYCODE_ENTER: return 0xff0d;
            case KeyEvent.KEYCODE_DEL: return 0xff08;
            case KeyEvent.KEYCODE_FORWARD_DEL: return 0xffff;
            case KeyEvent.KEYCODE_TAB: return 0xff09;
            case KeyEvent.KEYCODE_ESCAPE: return 0xff1b;
            case KeyEvent.KEYCODE_DPAD_LEFT: return 0xff51;
            case KeyEvent.KEYCODE_DPAD_UP: return 0xff52;
            case KeyEvent.KEYCODE_DPAD_RIGHT: return 0xff53;
            case KeyEvent.KEYCODE_DPAD_DOWN: return 0xff54;
            case KeyEvent.KEYCODE_MOVE_HOME: return 0xff50;
            case KeyEvent.KEYCODE_MOVE_END: return 0xff57;
            case KeyEvent.KEYCODE_PAGE_UP: return 0xff55;
            case KeyEvent.KEYCODE_PAGE_DOWN: return 0xff56;
            case KeyEvent.KEYCODE_INSERT: return 0xff63;
            case KeyEvent.KEYCODE_SHIFT_LEFT: return 0xffe1;
            case KeyEvent.KEYCODE_SHIFT_RIGHT: return 0xffe2;
            case KeyEvent.KEYCODE_CTRL_LEFT: return 0xffe3;
            case KeyEvent.KEYCODE_CTRL_RIGHT: return 0xffe4;
            case KeyEvent.KEYCODE_ALT_LEFT: return 0xffe9;
            case KeyEvent.KEYCODE_ALT_RIGHT: return 0xffea;
            case KeyEvent.KEYCODE_META_LEFT: return 0xffeb;
            case KeyEvent.KEYCODE_META_RIGHT: return 0xffec;
        }
        if (key >= KeyEvent.KEYCODE_A && key <= KeyEvent.KEYCODE_Z)
            return 'a' + key - KeyEvent.KEYCODE_A;
        if (key >= KeyEvent.KEYCODE_0 && key <= KeyEvent.KEYCODE_9)
            return '0' + key - KeyEvent.KEYCODE_0;
        int unicode = event.getUnicodeChar(0);
        if (unicode > 0 && unicode <= 0xff) return unicode;
        return unicode > 0 ? 0x01000000 | unicode : 0;
    }
}
