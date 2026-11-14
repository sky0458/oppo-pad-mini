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
    private InputTestPresentation inputTestPresentation;
    private Button inputTestButton;
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

        LinearLayout inputRow = row(layout);
        inputTestButton = button(inputRow, "键鼠测试（不连接VNC）", 1.6f);
        inputTestButton.setOnClickListener(v -> {
            if (inputTestPresentation != null) closeInputTest();
            else openInputTest();
        });

        captureButton = button(inputRow, "抓取鼠标", 1f);
        captureButton.setOnClickListener(v -> toggleCapture());

        TextView hint = label("键鼠测试：HDMI 显示虚拟光标和按键；鼠标可在下方触控板区域移动，"
                + "点击“抓取鼠标”可尝试捕获硬件鼠标；点击触控板使硬件键盘获得焦点。", 12);
        hint.setTextColor(0xffb9c6d4);
        layout.addView(hint);
        touchpad = new TouchpadView(this);
        touchpad.setInputListener(new TouchpadView.InputListener() {
            @Override public void onMove(float dx, float dy) {
                if (inputTestPresentation != null) inputTestPresentation.move(dx, dy);
            }
            @Override public void onClick(int button) {
                if (inputTestPresentation != null) inputTestPresentation.click(button);
            }
            @Override public void onScroll(boolean up) {
                if (inputTestPresentation != null) inputTestPresentation.scroll(up);
            }
        });
        touchpad.setCaptureListener(hasCapture -> {
            captured = hasCapture;
            captureButton.setText(hasCapture ? "释放鼠标" : "抓取鼠标");
            if (inputTestPresentation != null) {
                inputTestPresentation.captured(hasCapture);
                status(hasCapture ? "鼠标已捕获：移动鼠标，观察 HDMI 绿色虚拟光标。"
                        : "鼠标未捕获：把鼠标移入下方触控板区域，也可验证输入。");
            }
        });
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
        if (inputTestPresentation != null) closeInputTest();
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


    /** Independent input pipeline test; no VNC server, host, or password required. */
    private void openInputTest() {
        if (displays.isEmpty()) {
            status("没有检测到外接显示器，请先接上 HDMI。");
            return;
        }
        int selected = displaySelector.getSelectedItemPosition();
        if (selected < 0 || selected >= displays.size()) return;
        if (currentClient != null) disconnect();
        closePresentation();
        closeInputTest();
        Display target = displays.get(selected);
        try {
            InputTestPresentation next = new InputTestPresentation(this, target);
            next.show();
            inputTestPresentation = next;
            inputTestButton.setText("退出键鼠测试");
            status("HDMI 输入测试已开启：在触控板区域移动鼠标，或点击“抓取鼠标”。");
            // Keep keyboard focus in the primary Activity, not on the Presentation.
            touchpad.post(touchpad::requestFocus);
        } catch (RuntimeException error) {
            status("键鼠测试失败：" + error.getMessage());
        }
    }

    private void closeInputTest() {
        if (inputTestPresentation == null) return;
        if (touchpad.hasPointerCapture()) touchpad.releasePointerCapture();
        inputTestPresentation.dismiss();
        inputTestPresentation = null;
        inputTestButton.setText("键鼠测试（不连接VNC）");
        status("键鼠测试已结束；可以重新启动或连接 VNC。");
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
        if (touchpad.hasPointerCapture()) {
            touchpad.releasePointerCapture();
            pressedButtons = 0;
            if (currentClient != null) currentClient.setButtons(0);
            return;
        }
        touchpad.requestFocus();
        touchpad.post(() -> {
            touchpad.requestPointerCapture();
            touchpad.postDelayed(() -> {
                if (!touchpad.hasPointerCapture()) {
                    status("ColorOS 未授予鼠标捕获；仍可在下方触控板内移动鼠标测试。");
                }
            }, 400);
        });
    }

    private boolean handleCapturedPointer(View view, MotionEvent event) {
        RfbClient session = currentClient;
        InputTestPresentation test = inputTestPresentation;
        if (session == null && test == null) return false;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_MOVE:
            case MotionEvent.ACTION_HOVER_MOVE:
                float dx = event.getX(), dy = event.getY();
                if (session != null) session.sendRelativeMove(dx * 1.8f, dy * 1.8f);
                if (test != null) test.move(dx, dy);
                return true;
            case MotionEvent.ACTION_BUTTON_PRESS:
                int downButton = mouseButton(event.getActionButton());
                pressedButtons |= downButton;
                if (session != null) session.setButtons(pressedButtons);
                if (test != null) test.button(downButton, true);
                return true;
            case MotionEvent.ACTION_BUTTON_RELEASE:
                int upButton = mouseButton(event.getActionButton());
                pressedButtons &= ~upButton;
                if (session != null) session.setButtons(pressedButtons);
                if (test != null) test.button(upButton, false);
                return true;
            case MotionEvent.ACTION_SCROLL:
                float wheel = event.getAxisValue(MotionEvent.AXIS_VSCROLL);
                if (wheel != 0) {
                    if (session != null) session.scroll(wheel > 0);
                    if (test != null) test.scroll(wheel > 0);
                }
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
        if (inputTestPresentation != null && getCurrentFocus() == touchpad
                && (event.getAction() == KeyEvent.ACTION_DOWN
                || event.getAction() == KeyEvent.ACTION_UP)) {
            inputTestPresentation.key(event);
            return true;
        }
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
        if (inputTestPresentation != null
                && inputTestPresentation.getDisplay().getDisplayId() == displayId)
            closeInputTest();
        ui.post(this::refreshDisplays);
    }

    @Override protected void onDestroy() {
        displayManager.unregisterDisplayListener(this);
        disconnect();
        closeInputTest();
        closePresentation();
        worker.shutdownNow();
        super.onDestroy();
    }
}
