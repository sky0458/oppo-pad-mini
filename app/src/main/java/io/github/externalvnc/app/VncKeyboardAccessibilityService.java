package io.github.externalvnc.app;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;
import java.lang.ref.WeakReference;

/** Optional hardware-key filter for VNC shortcuts (including Command/Meta combinations).
 *  No text, keys or passwords are logged; the app must be foreground and the touchpad focused.
 */
public final class VncKeyboardAccessibilityService extends AccessibilityService {
    private static volatile boolean connected;
    private static WeakReference<MainActivity> controller = new WeakReference<>(null);

    static void attach(MainActivity activity) { controller = new WeakReference<>(activity); }
    static void detach(MainActivity activity) {
        if (controller.get() == activity) controller = new WeakReference<>(null);
    }
    static boolean isServiceConnected() { return connected; }

    @Override protected void onServiceConnected() {
        super.onServiceConnected();
        AccessibilityServiceInfo info = getServiceInfo();
        if (info != null) {
            info.flags |= AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS;
            setServiceInfo(info);
        }
        connected = true;
    }

    @Override protected boolean onKeyEvent(KeyEvent event) {
        MainActivity activity = controller.get();
        return activity != null && activity.forwardAccessibilityKey(event);
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) { }
    @Override public void onInterrupt() { }
    @Override public void onDestroy() {
        connected = false;
        super.onDestroy();
    }
}
