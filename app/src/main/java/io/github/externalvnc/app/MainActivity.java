package io.github.externalvnc.app;

import android.app.Activity;
import android.graphics.Color;
import android.hardware.display.DisplayManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Display;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.externalvnc.rfb.RfbClient;

/**
 * OPPO Pad Mini primary screen: controls and touchpad.
 * External HDMI display: Presentation-owned independent VNC framebuffer.
 */
public final class MainActivity extends Activity implements DisplayManager.DisplayListener {
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final AtomicBoolean framePending = new AtomicBoolean();
    private final List<Display> displays = new ArrayList<>();

    private DisplayManager displayManager;
    private Spinner displaySelector;
    private EditText hostField, portField, passwordField;
    private TextView statusLabel;
    private Button connectButton, scaleButton, captureButton;
    private TouchpadView touchpad;
    private ExternalPresentation presentation;
    private RfbClient currentClient;
    private boolean captured;
    private int scaleMode;
    private int pressedButtons;

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        displayManager = (DisplayManager) getSystemService(DISPLAY_SERVICE);
        displayManager.registerDisplayListener(this, ui);
        buildInterface();
        refreshDisplays();
        hostField.setText(getPreferences(MODE_PRIVATE).getString("host", ""));
        portField.setText(getPreferences(MODE_PRIVATE).getString("port", "5900"));
    }

    private void buildInterface() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(10), dp(8), dp(10), dp(8));
        layout.setBackgroundColor(0xff18202d);
        setContentView(layout);

        TextView heading = label("OPPO Pad Mini · 外屏 VNC", 19);
        heading.setTextColor(Color.WHITE);
        layout.addView(heading);
        statusLabel = label("连接 HDMI 后，先测试是否可独立显示。", 12);
        statusLabel.setTextColor(0xff93ebd1);
        layout.addView(statusLabel);

        LinearLayout credentials = row(layout);
        hostField = textField(credentials, "Mac IP / ZeroTier IP", "", InputType.TYPE_CLASS_TEXT, 2.5f);
        portField = textField(credentials, "端口", "5900", InputType.TYPE_CLASS_NUMBER, .7f);
        passwordField = textField(credentials, "VNC 专用密码", "",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD, 1.5f);

        LinearLayout monitorRow = row(layout);
        displaySelector = new Spinner(this);
        monitorRow.addView(displaySelector, new LinearLayout.LayoutParams(0, dp(48), 2.8f));
        Button refreshButton = button(monitorRow, "刷新", .7f);
        refreshButton.setOnClickListener(v -> refreshDisplays());
        Button testButton = button(monitorRow, "测试外屏", 1.1f);
        testButton.setOnClickListener(v -> openPresentation());

        LinearLayout actionRow = row(layout);
        connectButton = button(actionRow, "连接 VNC", 1.2f);
        connectButton.setOnClickListener(v -> {
            if (currentClient == null) connect();
            else disconnect();
        });

        scaleButton = button(actionRow, "画面：适应", 1f);
        scaleButton.setOnClickListener(v -> {
            scaleMode = (scaleMode + 1) % 3;
            scaleButton.setText(new String[]{"画面：适应", "画面：裁切", "画面：拉伸"}[scaleMode]);
            if (presentation != null) presentation.setScaleMode(scaleMode);
        });

        captureButton = button(actionRow, "抓取鼠标", 1f);
        captureButton.setOnClickListener(v -> toggleCapture());

        TextView hint = label("单指移动/点击，双指滚动；外接键盘在触控板获得焦点时转发。"
                + "点击抓取鼠标以使用相对移动。", 12);
        hint.setTextColor(0xffb9c6d4);
        layout.addView(hint);
        touchpad = new TouchpadView(this);
        touchpad.setOnCapturedPointerListener(this::handleCapturedPointer);
        LinearLayout.LayoutParams touchParams = new LinearLayout.LayoutParams(-1, 0, 1);
        touchParams.topMargin = dp(6);
        layout.addView(touchpad, touchParams);
    }

    private TextView label(String text, int sp) {
        TextView label = new TextView(this);
        label.setText(text);
        label.setTextSize(sp);
        label.setPadding(dp(4), dp(3), dp(4), dp(3));
        return label;
    }

    private LinearLayout row(LinearLayout parent) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        parent.addView(row, new LinearLayout.LayoutParams(-1, -2));
        return row;
    }

    private EditText textField(LinearLayout row, String hint, String value, int type, float weight) {
        EditText field = new EditText(this);
        field.setSingleLine();
        field.setTextSize(13);
        field.setTextColor(Color.WHITE);
        field.setHintTextColor(0xffadb8c9);
        field.setHint(hint);
        field.setInputType(type);
        field.setText(value);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(48), weight);
        params.rightMargin = dp(3);
        row.addView(field, params);
        return field;
    }

    private Button button(LinearLayout row, String title, float weight) {
        Button button = new Button(this);
        button.setText(title);
        button.setAllCaps(false);
        button.setTextSize(12);
        row.addView(button, new LinearLayout.LayoutParams(0, dp(48), weight));
        return button;
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void status(String message) {
        statusLabel.setText(message);
    }

    /** We want an external physical display, not a secondary Activity on the primary one. */
    private void refreshDisplays() {
        displays.clear();
        List<String> labels = new ArrayList<>();
        for (Display display : displayManager.getDisplays()) {
            if (display.getDisplayId() == Display.DEFAULT_DISPLAY) continue;
            displays.add(display);
            Display.Mode mode = display.getMode();
            labels.add("#" + display.getDisplayId() + " · " + display.getName()
                    + " · " + mode.getPhysicalWidth() + "×" + mode.getPhysicalHeight());
        }
        if (labels.isEmpty()) labels.add("未找到独立的外部 Display");
        displaySelector.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, labels));
        if (displays.isEmpty()) status("系统未向应用暴露外屏 Display。请检查 HDMI/ColorOS。");
        else status("发现 " + displays.size() + " 个外屏。请点击“测试外屏”。");
    }

    /** Must succeed before attempting VNC: otherwise ColorOS is blocking Presentation. */
    private boolean openPresentation() {
        if (displays.isEmpty()) {
            Toast.makeText(this, "没有可选的外接显示器", Toast.LENGTH_SHORT).show();
            return false;
        }
        int selected = displaySelector.getSelectedItemPosition();
        if (selected < 0 || selected >= displays.size()) return false;
        Display target = displays.get(selected);
        if (presentation != null
                && presentation.getDisplay().getDisplayId() == target.getDisplayId()) return true;
        closePresentation();
        try {
            ExternalPresentation next = new ExternalPresentation(this, target);
            next.show();
            next.setScaleMode(scaleMode);
            next.showMessage("Presentation OK · Display #" + target.getDisplayId());
            presentation = next;
            status("已创建外屏窗口；请检查 HDMI 显示器是否出现 Presentation OK。");
            return true;
        } catch (RuntimeException error) {
            status("独立外屏失败：" + error.getMessage());
            return false;
        }
    }

    private void connect() {
        if (!openPresentation()) return;
        String host = hostField.getText().toString().trim();
        String portString = portField.getText().toString().trim();
        String password = passwordField.getText().toString();
        if (host.isEmpty()) { status("请填写 Mac 的局域网 IP。"); return; }
        final int port;
        try {
            port = Integer.parseInt(portString);
            if (port < 1 || port > 65535) throw new NumberFormatException();
        } catch (NumberFormatException error) {
            status("端口应在 1–65535 之间，通常为 5900。");
            return;
        }
        // Password intentionally never persisted.
        getPreferences(MODE_PRIVATE).edit()
                .putString("host", host)
                .putString("port", portString)
                .apply();

        final RfbClient[] holder = new RfbClient[1];
        RfbClient session = new RfbClient(host, port, password, new RfbClient.Listener() {
            @Override public void onConnected(String desktop, int width, int height) {
                ui.post(() -> {
                    if (currentClient != holder[0]) return;
                    status("已连接 " + desktop + " · " + width + "×" + height);
                    touchpad.requestFocus();
                });
            }

            @Override public void onFrame(int width, int height, int[] pixels) {
                if (!framePending.compareAndSet(false, true)) return;
                int[] copy = Arrays.copyOf(pixels, pixels.length);
                ui.post(() -> {
                    try {
                        if (currentClient == holder[0] && presentation != null)
                            presentation.showFrame(width, height, copy);
                    } finally {
                        framePending.set(false);
                    }
                });
            }

            @Override public void onError(String error) {
                ui.post(() -> {
                    if (currentClient == holder[0]) status("VNC 失败：" + error);
                });
            }

            @Override public void onDisconnected() {
                ui.post(() -> {
                    if (currentClient != holder[0]) return;
                    currentClient = null;
                    touchpad.setClient(null);
                    connectButton.setText("连接 VNC");
                });
            }
        });

        holder[0] = session;
        currentClient = session;
        touchpad.setClient(session);
        connectButton.setText("断开 VNC");
        status("正在连接 " + host + ":" + port + " ...");
        worker.submit(session::runBlocking);
    }

    private void disconnect() {
        RfbClient session = currentClient;
        currentClient = null;
        if (session != null) session.close();
        touchpad.setClient(null);
        if (captured) toggleCapture();
        connectButton.setText("连接 VNC");
        status("连接已断开；外屏测试窗口仍保持开启。");
    }

    private void closePresentation() {
        if (presentation != null) {
            presentation.dismiss();
            presentation = null;
        }
    }

    private void toggleCapture() {
        captured = !captured;
        if (captured) {
            touchpad.requestFocus();
            touchpad.requestPointerCapture();
            captureButton.setText("释放鼠标");
        } else {
            touchpad.releasePointerCapture();
            pressedButtons = 0;
            if (currentClient != null) currentClient.setButtons(0);
            captureButton.setText("抓取鼠标");
        }
    }

    private boolean handleCapturedPointer(View view, MotionEvent event) {
        RfbClient session = currentClient;
        if (session == null) return false;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_MOVE:
            case MotionEvent.ACTION_HOVER_MOVE:
                session.sendRelativeMove(event.getX() * 1.8f, event.getY() * 1.8f);
                return true;
            case MotionEvent.ACTION_BUTTON_PRESS:
                pressedButtons |= mouseButton(event.getActionButton());
                session.setButtons(pressedButtons);
                return true;
            case MotionEvent.ACTION_BUTTON_RELEASE:
                pressedButtons &= ~mouseButton(event.getActionButton());
                session.setButtons(pressedButtons);
                return true;
            case MotionEvent.ACTION_SCROLL:
                float wheel = event.getAxisValue(MotionEvent.AXIS_VSCROLL);
                if (wheel != 0) session.scroll(wheel > 0);
                return true;
            default: return false;
        }
    }

    private static int mouseButton(int button) {
        if (button == MotionEvent.BUTTON_PRIMARY) return 1;
        if (button == MotionEvent.BUTTON_TERTIARY) return 2;
        if (button == MotionEvent.BUTTON_SECONDARY) return 4;
        return 0;
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        RfbClient session = currentClient;
        if (session != null && getCurrentFocus() == touchpad) {
            int keysym = KeySyms.map(event);
            if (keysym != 0 && (event.getAction() == KeyEvent.ACTION_DOWN
                    || event.getAction() == KeyEvent.ACTION_UP)) {
                session.sendKey(keysym, event.getAction() == KeyEvent.ACTION_DOWN);
                return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    @Override public void onDisplayAdded(int displayId) {
        ui.post(this::refreshDisplays);
    }

    @Override public void onDisplayChanged(int displayId) { }

    @Override public void onDisplayRemoved(int displayId) {
        if (presentation != null && presentation.getDisplay().getDisplayId() == displayId)
            closePresentation();
        ui.post(this::refreshDisplays);
    }

    @Override protected void onDestroy() {
        displayManager.unregisterDisplayListener(this);
        disconnect();
        closePresentation();
        worker.shutdownNow();
        super.onDestroy();
    }
}
