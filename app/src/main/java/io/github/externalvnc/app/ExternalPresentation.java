package io.github.externalvnc.app;

import android.app.Presentation;
import android.content.Context;
import android.os.Bundle;
import android.view.Display;
import android.view.WindowManager;
import android.widget.FrameLayout;
import java.util.List;
import io.github.externalvnc.rfb.RfbClient;

import java.util.function.Consumer;

/** An independent Android window on the selected external Display. */
public final class ExternalPresentation extends Presentation {
    private RemoteFrameView frameView;
    private RemoteCursorView cursorView;

    public ExternalPresentation(Context context, Display target) {
        super(context, target);
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);
        // Keep physical keyboard and pointer focus on the tablet controls.
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
        frameView = new RemoteFrameView(getContext());
        cursorView = new RemoteCursorView(getContext());
        FrameLayout layered = new FrameLayout(getContext());
        layered.addView(frameView, new FrameLayout.LayoutParams(-1, -1));
        layered.addView(cursorView, new FrameLayout.LayoutParams(-1, -1));
        setContentView(layered);
    }

    public void showFrame(int w, int h, int[] pixels) {
        if (frameView != null) frameView.setFrame(w, h, pixels);
    }

    public void showRegions(int w, int h, List<RfbClient.FrameRegion> regions) {
        if (frameView != null) frameView.setRegions(w, h, regions);
    }

    public void moveCursor(int x, int y, int width, int height) {
        if (cursorView != null) cursorView.moveTo(x, y, width, height);
    }

    public void setCursorScale(float scale) {
        if (cursorView != null) cursorView.setCursorScale(scale);
    }

    public void setDiagnosticListener(Consumer<String> callback) {
        if (frameView != null) frameView.setDiagnosticListener(callback);
    }

    public boolean toggleDiagnosticPattern() {
        return frameView != null && frameView.toggleDiagnosticPattern();
    }

    public void showMessage(String message) {
        if (frameView != null) frameView.setMessage(message);
    }

    public void setScaleMode(int mode) {
        if (frameView != null) frameView.setScaleMode(mode);
        if (cursorView != null) cursorView.setScaleMode(mode);
    }

    public void setRemoteViewport(int x, int y, int w, int h) {
        if (frameView != null) frameView.setViewport(x, y, w, h);
        if (cursorView != null) cursorView.setViewport(x, y, w, h);
    }

    @Override public void dismiss() {
        if (frameView != null) frameView.release();
        super.dismiss();
    }
}
