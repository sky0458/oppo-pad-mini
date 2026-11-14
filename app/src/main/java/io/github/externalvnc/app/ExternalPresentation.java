package io.github.externalvnc.app;

import android.app.Presentation;
import android.content.Context;
import android.os.Bundle;
import android.view.Display;
import android.view.WindowManager;

/** An independent Android window on the selected external Display. */
public final class ExternalPresentation extends Presentation {
    private RemoteFrameView frameView;

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
        setContentView(frameView);
    }

    public void showFrame(int w, int h, int[] pixels) {
        if (frameView != null) frameView.setFrame(w, h, pixels);
    }

    public void showMessage(String message) {
        if (frameView != null) frameView.setMessage(message);
    }

    public void setScaleMode(int mode) {
        if (frameView != null) frameView.setScaleMode(mode);
    }

    @Override public void dismiss() {
        if (frameView != null) frameView.release();
        super.dismiss();
    }
}
