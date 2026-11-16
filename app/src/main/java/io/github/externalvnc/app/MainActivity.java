package io.github.externalvnc.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.graphics.Color;
import android.hardware.display.DisplayManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
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
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import io.github.externalvnc.rfb.RfbClient;

/**
 * OPPO Pad Mini primary screen: controls and touchpad.
 * External HDMI display: Presentation-owned independent VNC framebuffer.
 */
public final class MainActivity extends Activity implements DisplayManager.DisplayListener {
    private static final int REQUEST_EXPORT_LOG = 1001;
    private static final AtomicBoolean crashHandlerInstalled = new AtomicBoolean();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final AtomicBoolean framePending = new AtomicBoolean();
    private final AtomicBoolean firstFrameLogged = new AtomicBoolean();
    private final AtomicInteger receivedFrames = new AtomicInteger();
    private final Object renderLock = new Object();
    private final ArrayDeque<RenderUpdate> renderUpdates = new ArrayDeque<>();
    private final List<Display> displays = new ArrayList<>();

    private static final class RenderUpdate {
        final int width, height;
        final List<RfbClient.FrameRegion> regions;
        final int[] fullPixels;

        RenderUpdate(int width, int height, List<RfbClient.FrameRegion> regions, int[] fullPixels) {
            this.width = width;
            this.height = height;
            this.regions = regions;
            this.fullPixels = fullPixels;
        }
    }

    private DisplayManager displayManager;
    private Spinner displaySelector;
    private Spinner authSelector;
    private EditText hostField, portField, usernameField, passwordField;
    private AppLog diagnostics;
    private TextView statusLabel;
    private Button connectButton, scaleButton, captureButton;
    private Button fpsButton;
    private TextView fpsStatus;
    private TouchpadView touchpad;
    private ExternalPresentation presentation;
    private InputTestPresentation inputTestPresentation;
    private Button inputTestButton;
    private RfbClient currentClient;
    private boolean captured;
    private int scaleMode;
    private int pressedButtons;
    private int targetFps = 30;
    private long fpsPeriodStart;
    private int fpsPeriodFrames;

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        diagnostics = new AppLog(this);
        if (crashHandlerInstalled.compareAndSet(false, true)) {
            Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
            AppLog crashLog = diagnostics;
            Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
                crashLog.add("CRASH", "Uncaught on " + thread.getName() + ": "
                        + android.util.Log.getStackTraceString(error));
                if (previous != null) previous.uncaughtException(thread, error);
            });
        }
        diagnostics.add("APP", "Application started; logs are saved locally and credentials are never logged");
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        displayManager = (DisplayManager) getSystemService(DISPLAY_SERVICE);
        displayManager.registerDisplayListener(this, ui);
        buildInterface();
        refreshDisplays();
        hostField.setText(getPreferences(MODE_PRIVATE).getString("host", ""));
        portField.setText(getPreferences(MODE_PRIVATE).getString("port", "5900"));
        int savedFps = getPreferences(MODE_PRIVATE).getInt("targetFps", 30);
        targetFps = savedFps == 60 || savedFps == 90 ? savedFps : 30;
        fpsButton.setText("目标帧率：" + targetFps);
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
        passwordField = textField(credentials, "密码", "",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD, 1.5f);

        LinearLayout authRow = row(layout);
        usernameField = textField(authRow, "Mac 本地用户名（账号认证必填）", "", InputType.TYPE_CLASS_TEXT, 1.5f);
        authSelector = new Spinner(this);
        ArrayAdapter<String> authenticationOptions = new ArrayAdapter<String>(this,
                android.R.layout.simple_spinner_item,
                new String[]{"认证：自动", "Mac 账号密码", "Mac 账号（CBC兼容）", "VNC 专用密码"}) {
            @Override public View getView(int position, View convertView, android.view.ViewGroup parent) {
                TextView view = (TextView) super.getView(position, convertView, parent);
                view.setTextColor(Color.WHITE);
                return view;
            }
        };
        authenticationOptions.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        authSelector.setAdapter(authenticationOptions);
        authRow.addView(authSelector, new LinearLayout.LayoutParams(0, dp(48), 1.4f));

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

        Button redrawButton = button(actionRow, "远端重绘", .8f);
        redrawButton.setOnClickListener(v -> {
            RfbClient client = currentClient;
            if (client == null) status("请先连接 VNC 再请求重绘。");
            else status(client.requestFullRefresh() ? "已向 Mac 请求完整画面更新。"
                    : "VNC 尚未完成初始化，或连接已经断开。");
        });

        Button patternButton = button(actionRow, "HDMI 彩条", .8f);
        patternButton.setOnClickListener(v -> {
            if (!openPresentation()) return;
            boolean showing = presentation.toggleDiagnosticPattern();
            patternButton.setText(showing ? "退出彩条" : "HDMI 彩条");
            status(showing ? "已显示 HDMI 彩条：红、绿、蓝、白，与 VNC 像素无关。"
                    : "已恢复 VNC 画面显示。");
        });

        LinearLayout performanceRow = row(layout);
        Button resolutionButton = button(performanceRow, "远端分辨率", 1.2f);
        resolutionButton.setOnClickListener(v -> showResolutionOptions());
        fpsButton = button(performanceRow, "目标帧率：30", 1.1f);
        fpsButton.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle("VNC 请求帧率上限")
                .setSingleChoiceItems(new String[]{"30 FPS（默认）", "60 FPS", "90 FPS"},
                        targetFps == 30 ? 0 : targetFps == 60 ? 1 : 2, (dialog, index) -> {
                            targetFps = new int[]{30, 60, 90}[index];
                            fpsButton.setText("目标帧率：" + targetFps);
                            getPreferences(MODE_PRIVATE).edit().putInt("targetFps", targetFps).apply();
                            if (currentClient != null) currentClient.setTargetFps(targetFps);
                            diagnostics.add("FPS", "Requested rate cap=" + targetFps);
                            dialog.dismiss();
                        })
                .setNegativeButton("取消", null).show());
        fpsStatus = label("实际：-- FPS", 12);
        fpsStatus.setTextColor(0xff93ebd1);
        performanceRow.addView(fpsStatus, new LinearLayout.LayoutParams(0, dp(48), 1.0f));

        LinearLayout inputRow = row(layout);
        inputTestButton = button(inputRow, "键鼠测试（不连接VNC）", 1.6f);
        inputTestButton.setOnClickListener(v -> {
            if (inputTestPresentation != null) closeInputTest();
            else openInputTest();
        });

        captureButton = button(inputRow, "抓取鼠标", 1f);
        captureButton.setOnClickListener(v -> toggleCapture());

        TextView hint = label("HDMI 现可显示独立白色鼠标光标；在下方触控板操作，或点『抓取鼠标』捕获硬件鼠标。"
                + "目标 FPS 是请求上限，实际数值见上方；键盘先点击触控板取得焦点。", 12);
        hint.setTextColor(0xffb9c6d4);
        layout.addView(hint);

        LinearLayout logRow = row(layout);
        Button viewLogButton = button(logRow, "查看日志", 1f);
        viewLogButton.setOnClickListener(v -> showLogs());
        Button copyLogButton = button(logRow, "复制日志", 1f);
        copyLogButton.setOnClickListener(v -> copyLogs());
        Button exportLogButton = button(logRow, "导出日志", 1f);
        exportLogButton.setOnClickListener(v -> requestExportLogs());

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
            diagnostics.add("INPUT", "Pointer capture=" + hasCapture);
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

    private void showResolutionOptions() {
        RfbClient client = currentClient;
        if (client == null) { status("请先连接 VNC 再选择远端分辨率。"); return; }
        String[] options = {"1280 × 720", "1600 × 900", "1920 × 1080",
                "2560 × 1440", "2880 × 1800", "3840 × 2160", "自定义…"};
        new AlertDialog.Builder(this)
                .setTitle("远端桌面分辨率")
                .setItems(options, (dialog, index) -> {
                    if (index == options.length - 1) { showCustomResolution(); return; }
                    int[][] modes = {{1280, 720}, {1600, 900}, {1920, 1080},
                            {2560, 1440}, {2880, 1800}, {3840, 2160}};
                    requestRemoteResolution(modes[index][0], modes[index][1]);
                })
                .setNeutralButton("能力说明", (dialog, which) -> showResolutionHelp())
                .setNegativeButton("取消", null).show();
    }

    private void showCustomResolution() {
        LinearLayout inputs = new LinearLayout(this);
        inputs.setPadding(dp(12), dp(4), dp(12), dp(4));
        EditText widthField = new EditText(this);
        EditText heightField = new EditText(this);
        widthField.setHint("宽度，例如 1920");
        heightField.setHint("高度，例如 1080");
        widthField.setInputType(InputType.TYPE_CLASS_NUMBER);
        heightField.setInputType(InputType.TYPE_CLASS_NUMBER);
        inputs.setOrientation(LinearLayout.VERTICAL);
        inputs.addView(widthField);
        inputs.addView(heightField);
        new AlertDialog.Builder(this).setTitle("自定义远端分辨率")
                .setView(inputs).setPositiveButton("申请调整", (dialog, which) -> {
                    try {
                        int w = Integer.parseInt(widthField.getText().toString());
                        int h = Integer.parseInt(heightField.getText().toString());
                        if (w < 320 || h < 240 || (long) w * h > 16_777_216L)
                            throw new NumberFormatException();
                        requestRemoteResolution(w, h);
                    } catch (NumberFormatException ex) {
                        status("无效分辨率：宽度至少 320、高度至少 240、总像素最多 1677 万。");
                    }
                }).setNegativeButton("取消", null).show();
    }

    private void requestRemoteResolution(int width, int height) {
        RfbClient client = currentClient;
        if (client == null || !client.supportsRemoteResize()) {
            showResolutionHelp();
            return;
        }
        if (client.requestDesktopResize(width, height))
            status("已向远端请求 " + width + "×" + height + "；等待服务端确认。 ");
        else status("请求失败：连接未就绪或分辨率超出范围。");
    }

    private void showResolutionHelp() {
        new AlertDialog.Builder(this)
                .setTitle("macOS VNC 分辨率限制")
                .setMessage("标准 VNC 只能在服务器声明 ExtendedDesktopSize 能力时，"
                        + "修改真实远端桌面分辨率。\n\n"
                        + "当前连接" + (currentClient != null && currentClient.supportsRemoteResize()
                            ? "已声明支持，可选择预设或自定义尺寸。"
                            : "未声明支持；macOS 内置屏幕共享通常不支持该功能。"
                            + "\n请在 Mac『系统设置 → 显示器』调整分辨率后，返回本应用点击『远端重绘』；"
                            + "也可调节『画面：适应/裁切/拉伸』，但后者不会改变远端像素数。"))
                .setPositiveButton("知道了", null).show();
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
        diagnostics.add("UI", message);
    }

    private void showLogs() {
        try {
            TextView contents = label(diagnostics.recent(65000), 12);
            contents.setTextIsSelectable(true);
            contents.setTextColor(0xff243448);
            ScrollView scroll = new ScrollView(this);
            scroll.addView(contents);
            new AlertDialog.Builder(this).setTitle("诊断日志（最近 65K 字符）")
                    .setView(scroll).setPositiveButton("关闭", null)
                    .setNeutralButton("复制", (dialog, which) -> copyLogs()).show();
        } catch (IOException error) {
            status("查看日志失败：" + error.getMessage());
        }
    }

    private void copyLogs() {
        try {
            String content = diagnostics.recent(128000);
            ClipboardManager manager = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            manager.setPrimaryClip(ClipData.newPlainText("External VNC diagnostics", content));
            Toast.makeText(this, "已复制日志（最多最近 128K 字符）", Toast.LENGTH_SHORT).show();
            diagnostics.add("LOG", "Diagnostic log copied to clipboard");
        } catch (IOException error) {
            status("复制日志失败：" + error.getMessage());
        }
    }

    private void requestExportLogs() {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("text/plain");
        String name = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(new Date());
        intent.putExtra(Intent.EXTRA_TITLE, "oppo-pad-mini-vnc-" + name + ".log");
        diagnostics.add("LOG", "Export requested via Android document picker");
        startActivityForResult(intent, REQUEST_EXPORT_LOG);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_EXPORT_LOG || resultCode != RESULT_OK || data == null) return;
        Uri uri = data.getData();
        if (uri == null) return;
        try (OutputStream destination = getContentResolver().openOutputStream(uri)) {
            if (destination == null) throw new IOException("Unable to open destination");
            diagnostics.exportTo(destination);
            status("诊断日志已导出至选定位置（包含最近三个轮转日志）");
        } catch (IOException error) {
            status("日志导出失败：" + error.getMessage());
        }
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
        diagnostics.add("DISPLAY", "Opening Presentation display #" + target.getDisplayId()
                + ", mode=" + target.getMode().getPhysicalWidth() + "x" + target.getMode().getPhysicalHeight());
        if (inputTestPresentation != null) closeInputTest();
        if (presentation != null
                && presentation.getDisplay().getDisplayId() == target.getDisplayId()) return true;
        closePresentation();
        try {
            ExternalPresentation next = new ExternalPresentation(this, target);
            next.show();
            next.setDiagnosticListener(message -> diagnostics.add("RENDER", message));
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
        String username = usernameField.getText().toString().trim();
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

        RfbClient.AuthMode mode = new RfbClient.AuthMode[]{RfbClient.AuthMode.AUTO,
                RfbClient.AuthMode.MAC_ACCOUNT, RfbClient.AuthMode.MAC_CBC_COMPAT,
                RfbClient.AuthMode.VNC_PASSWORD}[authSelector.getSelectedItemPosition()];
        if ((mode == RfbClient.AuthMode.MAC_ACCOUNT || mode == RfbClient.AuthMode.MAC_CBC_COMPAT)
                && username.isEmpty()) {
            status("Mac 账号认证请填写本地用户名（不是 Apple ID 邮箱）。");
            return;
        }
        if (password.isEmpty() && mode != RfbClient.AuthMode.AUTO) {
            status("请填写密码；Mac 账号认证使用 Mac 登录密码。");
            return;
        }
        // Password intentionally never persisted.
        getPreferences(MODE_PRIVATE).edit()
                .putString("host", host)
                .putString("port", portString)
                .apply();

        diagnostics.add("CONNECT", "Starting VNC connection port=" + port
                + " auth=" + mode + " usernameProvided=" + !username.isEmpty()
                + " passwordProvided=" + !password.isEmpty());

        final RfbClient[] holder = new RfbClient[1];
        firstFrameLogged.set(false);
        receivedFrames.set(0);
        fpsPeriodStart = SystemClock.elapsedRealtime();
        fpsPeriodFrames = 0;
        fpsStatus.setText("实际：-- FPS");
        synchronized (renderLock) {
            renderUpdates.clear();
            framePending.set(false);
        }
        RfbClient session = new RfbClient(host, port, username, password, mode, new RfbClient.Listener() {
            @Override public void onConnected(String desktop, int width, int height) {
                ui.post(() -> {
                    if (currentClient != holder[0]) return;
                    status("已连接 " + desktop + " · " + width + "×" + height);
                    touchpad.requestFocus();
                });
            }

            @Override public void onFrame(int width, int height, int[] pixels) {
                // Compatibility fallback. MainActivity normally uses dirty regions instead.
                recordFrame(width, height, pixels);
                queueFrame(holder[0], new RenderUpdate(width, height, null,
                        Arrays.copyOf(pixels, pixels.length)), pixels);
            }

            @Override public boolean usesFrameRegions() { return true; }

            @Override public void onFrameRegions(int width, int height,
                                                  List<RfbClient.FrameRegion> regions,
                                                  int[] currentPixels) {
                recordFrame(width, height, currentPixels);
                queueFrame(holder[0], new RenderUpdate(width, height, regions, null), currentPixels);
            }

            private void recordFrame(int width, int height, int[] pixels) {
                int number = receivedFrames.incrementAndGet();
                fpsPeriodFrames++;
                long now = SystemClock.elapsedRealtime();
                long elapsed = now - fpsPeriodStart;
                if (elapsed >= 2000) {
                    float actual = fpsPeriodFrames * 1000f / elapsed;
                    fpsPeriodStart = now;
                    fpsPeriodFrames = 0;
                    ui.post(() -> {
                        if (currentClient == holder[0])
                            fpsStatus.setText(String.format(Locale.ROOT, "实际：%.1f FPS", actual));
                    });
                }
                if (number <= 3 || number % 60 == 0) {
                    int samples = 0, nonBlack = 0, hasAlpha = 0;
                    int step = Math.max(1, pixels.length / 1024);
                    for (int i = 0; i < pixels.length; i += step) {
                        samples++;
                        if ((pixels[i] & 0x00ffffff) != 0) nonBlack++;
                        if ((pixels[i] >>> 24) != 0) hasAlpha++;
                    }
                    diagnostics.add("FRAME", "Received #" + number + " " + width + "x" + height
                            + ", nonBlack=" + nonBlack + "/" + samples
                            + ", opaque=" + hasAlpha + "/" + samples
                            + ", center=0x" + Integer.toHexString(pixels[pixels.length / 2]));
                }
                if (firstFrameLogged.compareAndSet(false, true))
                    diagnostics.add("FRAME", "First framebuffer update " + width + "x" + height);
            }

            @Override public void onPointerMoved(int x, int y, int width, int height) {
                Runnable update = () -> {
                    if (currentClient == holder[0] && presentation != null)
                        presentation.moveCursor(x, y, width, height);
                };
                if (Looper.myLooper() == Looper.getMainLooper()) update.run();
                else ui.post(update);
            }

            @Override public void onResizeCapabilityChanged(boolean supported) {
                diagnostics.add("RFB", "Remote SetDesktopSize capability=" + supported);
            }

            @Override public void onError(String error) {
                ui.post(() -> {
                    if (currentClient == holder[0]) status("VNC 失败：" + error);
                });
            }

            @Override public void onDisconnected() {
                diagnostics.add("CONNECT", "RFB session ended");
                ui.post(() -> {
                    if (currentClient != holder[0]) return;
                    synchronized (renderLock) { renderUpdates.clear(); }
                    currentClient = null;
                    touchpad.setClient(null);
                    fpsStatus.setText("实际：-- FPS");
                    connectButton.setText("连接 VNC");
                });
            }

            @Override public void onDiagnostic(String message) {
                diagnostics.add("RFB", message);
            }
        });

        holder[0] = session;
        currentClient = session;
        session.setTargetFps(targetFps);
        touchpad.setClient(session);
        connectButton.setText("断开 VNC");
        status("正在连接 " + host + ":" + port + " ...");
        worker.submit(session::runBlocking);
    }

    /** Keep a small render queue; on overload replace pending deltas with an exact full snapshot. */
    private void queueFrame(RfbClient source, RenderUpdate update, int[] currentPixels) {
        synchronized (renderLock) {
            if (source != currentClient) return;
            if (renderUpdates.size() >= 4) {
                renderUpdates.clear();
                update = new RenderUpdate(update.width, update.height, null,
                        Arrays.copyOf(currentPixels, currentPixels.length));
                diagnostics.add("RENDER", "UI behind reader; collapsed pending regions to one full frame");
            }
            renderUpdates.addLast(update);
            if (framePending.compareAndSet(false, true)) ui.post(() -> drainFrames(source));
        }
    }

    private void drainFrames(RfbClient source) {
        if (currentClient != source) return;
        int processed = 0;
        while (processed++ < 2) {
            RenderUpdate update;
            synchronized (renderLock) {
                update = renderUpdates.pollFirst();
                if (update == null) {
                    framePending.set(false);
                    return;
                }
            }
            if (currentClient != source || presentation == null) continue;
            try {
                if (update.fullPixels != null)
                    presentation.showFrame(update.width, update.height, update.fullPixels);
                else presentation.showRegions(update.width, update.height, update.regions);
            } catch (RuntimeException exception) {
                diagnostics.add("RENDER", "Error applying VNC frame: " + exception);
                status("VNC 绘制失败，请导出日志：" + exception.getMessage());
            }
        }
        ui.post(() -> drainFrames(source));
    }

    private void disconnect() {
        diagnostics.add("CONNECT", "Disconnect requested by app");
        RfbClient session = currentClient;
        currentClient = null;
        if (session != null) session.close();
        touchpad.setClient(null);
        synchronized (renderLock) { renderUpdates.clear(); }
        fpsStatus.setText("实际：-- FPS");
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
                // Captured pointer motion is relative, not an absolute position in the view.
                float dx = event.getAxisValue(MotionEvent.AXIS_RELATIVE_X);
                float dy = event.getAxisValue(MotionEvent.AXIS_RELATIVE_Y);
                if (dx == 0f && dy == 0f) {
                    dx = event.getX();
                    dy = event.getY();
                }
                if (session != null) session.sendRelativeMove(dx * 1.25f, dy * 1.25f);
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
