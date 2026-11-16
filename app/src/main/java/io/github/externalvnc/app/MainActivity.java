package io.github.externalvnc.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Insets;
import android.hardware.display.DisplayManager;
import android.net.Uri;
import android.provider.Settings;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.InputType;
import android.view.Display;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.Gravity;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.AdapterView;

import java.io.IOException;
import java.io.OutputStream;
import java.security.GeneralSecurityException;
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
    private Spinner profileSelector;
    private Spinner authSelector;
    private EditText hostField, portField, usernameField, passwordField, profileNameField;
    private CheckBox savePasswordCheck;
    private ProfileRepository profileRepository;
    private CredentialVault credentialVault;
    private List<ConnectionProfile> profileOptions = new ArrayList<>();
    private String editingProfileId, activeSessionProfileId;
    private boolean loadingProfile;
    private boolean drawerOpen;
    private FrameLayout drawerOverlay;
    private ScrollView drawerPanel;
    private Button floatingMenuButton;
    private AppLog diagnostics;
    private TextView statusLabel;
    private Button connectButton, scaleButton, captureButton;
    private Button fpsButton;
    private Button accessibilityButton;
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
    private float cursorScale = 1f;
    private float mouseSpeed = 2f;
    private boolean immersiveMouse = true;
    private boolean fastColor = true;
    private volatile String performanceSummary = "";
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
        profileRepository = new ProfileRepository(this);
        credentialVault = new CredentialVault(this);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        displayManager = (DisplayManager) getSystemService(DISPLAY_SERVICE);
        displayManager.registerDisplayListener(this, ui);
        buildInterface();
        refreshDisplays();
        refreshProfiles(profileRepository.defaultId());
        int savedFps = getPreferences(MODE_PRIVATE).getInt("targetFps", 30);
        targetFps = savedFps == 60 || savedFps == 90 ? savedFps : 30;
        fpsButton.setText("目标帧率：" + targetFps);
        cursorScale = getPreferences(MODE_PRIVATE).getFloat("cursorScale", 1f);
        mouseSpeed = getPreferences(MODE_PRIVATE).getFloat("mouseSpeed", 2f);
        immersiveMouse = getPreferences(MODE_PRIVATE).getBoolean("immersiveMouse", true);
        fastColor = getPreferences(MODE_PRIVATE).getBoolean("fastColor", true);
        touchpad.setMouseSpeed(mouseSpeed);
        captureButton.setText(immersiveMouse ? "沉浸鼠标：待抓取" : "抓取鼠标");
        VncKeyboardAccessibilityService.attach(this);
        // A new launch has no active session: expose the connection controls first.
        ui.post(this::openDrawer);
    }

    private void buildInterface() {
        FrameLayout screen = new FrameLayout(this);
        screen.setBackgroundColor(0xff1f2b38);
        setContentView(screen);

        touchpad = new TouchpadView(this);
        screen.addView(touchpad, new FrameLayout.LayoutParams(-1, -1));

        floatingMenuButton = new Button(this);
        floatingMenuButton.setAllCaps(false);
        floatingMenuButton.setText("☰");
        floatingMenuButton.setTextSize(22);
        floatingMenuButton.setContentDescription("打开远程控制菜单");
        floatingMenuButton.setOnClickListener(v -> openDrawer());
        FrameLayout.LayoutParams fabParams = new FrameLayout.LayoutParams(dp(58), dp(58),
                Gravity.TOP | Gravity.END);
        fabParams.topMargin = dp(28);
        fabParams.rightMargin = dp(14);
        screen.addView(floatingMenuButton, fabParams);

        // The drawer is an in-app overlay, not a SYSTEM_ALERT_WINDOW system overlay.
        drawerOverlay = new FrameLayout(this);
        drawerOverlay.setBackgroundColor(0xb9000000);
        drawerOverlay.setClickable(true);
        drawerOverlay.setVisibility(View.GONE);
        drawerOverlay.setOnClickListener(v -> closeDrawer());
        screen.addView(drawerOverlay, new FrameLayout.LayoutParams(-1, -1));

        ScrollView drawerScroll = new ScrollView(this);
        drawerPanel = drawerScroll;
        drawerScroll.setBackgroundColor(0xff18202d);
        drawerScroll.setFillViewport(false);
        drawerScroll.setClickable(true);
        int drawerWidth = Math.min(dp(440),
                Math.round(getResources().getDisplayMetrics().widthPixels * .83f));
        FrameLayout.LayoutParams drawerParams = new FrameLayout.LayoutParams(drawerWidth,
                -1, Gravity.RIGHT);
        drawerOverlay.addView(drawerScroll, drawerParams);

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(12), dp(16), dp(12), dp(26));
        layout.setBackgroundColor(0xff18202d);
        drawerScroll.addView(layout, new ScrollView.LayoutParams(-1, -2));

        if (android.os.Build.VERSION.SDK_INT >= 30) {
            screen.setOnApplyWindowInsetsListener((view, insets) -> {
                Insets safe = insets.getInsets(WindowInsets.Type.displayCutout()
                        | WindowInsets.Type.systemBars());
                FrameLayout.LayoutParams position = (FrameLayout.LayoutParams)
                        floatingMenuButton.getLayoutParams();
                position.topMargin = Math.max(dp(14), safe.top + dp(10));
                position.rightMargin = Math.max(dp(14), safe.right + dp(10));
                floatingMenuButton.setLayoutParams(position);
                return insets;
            });
        }

        LinearLayout titleRow = row(layout);
        TextView heading = label("OPPO Pad Mini · 外屏 VNC", 19);
        heading.setTextColor(Color.WHITE);
        titleRow.addView(heading, new LinearLayout.LayoutParams(0, -2, 1));
        Button closeButton = button(titleRow, "关闭 ✕", .48f);
        closeButton.setOnClickListener(v -> closeDrawer());
        statusLabel = label("连接 HDMI 后，先测试是否可独立显示。", 12);
        statusLabel.setTextColor(0xff93ebd1);
        layout.addView(statusLabel);

        TextView connectionHeading = label("连接管理", 16);
        connectionHeading.setTextColor(Color.WHITE);
        layout.addView(connectionHeading);

        LinearLayout profilesRow = row(layout);
        profileSelector = new Spinner(this);
        profilesRow.addView(profileSelector, new LinearLayout.LayoutParams(0, dp(48), 2.2f));
        Button newProfileButton = button(profilesRow, "新增", .7f);
        newProfileButton.setOnClickListener(v -> newProfile());
        Button defaultProfileButton = button(profilesRow, "设默认", 1f);
        defaultProfileButton.setOnClickListener(v -> markDefaultProfile());
        profileNameField = textField(row(layout), "配置名称", "", InputType.TYPE_CLASS_TEXT, 1f);

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

        LinearLayout profileActionRow = row(layout);
        savePasswordCheck = new CheckBox(this);
        savePasswordCheck.setText("加密保存密码");
        savePasswordCheck.setTextColor(Color.WHITE);
        savePasswordCheck.setChecked(true);
        profileActionRow.addView(savePasswordCheck, new LinearLayout.LayoutParams(0, dp(48), 1.7f));
        Button saveProfileButton = button(profileActionRow, "保存配置", 1f);
        saveProfileButton.setOnClickListener(v -> saveProfile());
        Button deleteProfileButton = button(profileActionRow, "删除", .75f);
        deleteProfileButton.setOnClickListener(v -> confirmDeleteProfile());

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
        fpsStatus.setTextSize(11);
        performanceRow.addView(fpsStatus, new LinearLayout.LayoutParams(0, dp(48), 1.0f));

        LinearLayout inputRow = row(layout);
        inputTestButton = button(inputRow, "键鼠测试（不连接VNC）", 1.6f);
        inputTestButton.setOnClickListener(v -> {
            if (inputTestPresentation != null) closeInputTest();
            else openInputTest();
        });

        captureButton = button(inputRow, "抓取鼠标", 1f);
        captureButton.setOnClickListener(v -> toggleCapture());

        Button mouseSettingsButton = button(inputRow, "鼠标/画质", 1.1f);
        mouseSettingsButton.setOnClickListener(v -> showMouseSettings());
        accessibilityButton = button(inputRow, "无障碍键盘", 1.1f);
        accessibilityButton.setOnClickListener(v -> showAccessibilityInstructions());

        TextView hint = label("菜单收起后整个 Pad 都是触控板：单指移动/点击、双指轻点右键、双指滚动、长按拖拽。"
                + "默认尝试沉浸捕获实体鼠标，移动不受触控板边界限制；『鼠标/画质』中可调光标和速度。", 12);
        hint.setTextColor(0xffb9c6d4);
        layout.addView(hint);

        LinearLayout logRow = row(layout);
        Button viewLogButton = button(logRow, "查看日志", 1f);
        viewLogButton.setOnClickListener(v -> showLogs());
        Button copyLogButton = button(logRow, "复制日志", 1f);
        copyLogButton.setOnClickListener(v -> copyLogs());
        Button exportLogButton = button(logRow, "导出日志", 1f);
        exportLogButton.setOnClickListener(v -> requestExportLogs());

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
            captureButton.setText(hasCapture ? "退出沉浸/释放" : "进入沉浸/抓取");
            if (!hasCapture) {
                pressedButtons = 0;
                if (currentClient != null) currentClient.setButtons(0);
            }
            if (inputTestPresentation != null) {
                inputTestPresentation.captured(hasCapture);
                status(hasCapture ? "鼠标已捕获：移动鼠标，观察 HDMI 绿色虚拟光标。"
                        : "鼠标未捕获：在 Pad 触控板区域仍可以测试移动。");
            }
        });
        touchpad.setOnCapturedPointerListener(this::handleCapturedPointer);
        profileSelector.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view,
                                                  int position, long id) {
                if (loadingProfile || position < 0 || position >= profileOptions.size()) return;
                selectProfile(profileOptions.get(position));
            }
            @Override public void onNothingSelected(AdapterView<?> parent) { }
        });
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

    private void showMouseSettings() {
        final float originalSpeed = mouseSpeed, originalCursor = cursorScale;
        final boolean originalImmersive = immersiveMouse, originalColor = fastColor;
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(dp(20), dp(8), dp(20), dp(8));

        TextView speedLabel = label("鼠标速度：" + Math.round(mouseSpeed * 100) + "%", 15);
        column.addView(speedLabel);
        SeekBar speedBar = new SeekBar(this);
        speedBar.setMax(350); // 50%–400%
        speedBar.setProgress(Math.round(mouseSpeed * 100) - 50);
        speedBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean user) {
                mouseSpeed = (progress + 50) / 100f;
                speedLabel.setText("鼠标速度：" + (progress + 50) + "%");
                if (touchpad != null) touchpad.setMouseSpeed(mouseSpeed);
            }
            @Override public void onStartTrackingTouch(SeekBar bar) { }
            @Override public void onStopTrackingTouch(SeekBar bar) { }
        });
        column.addView(speedBar);

        TextView cursorLabel = label("Mac 光标大小：" + Math.round(cursorScale * 100) + "%", 15);
        column.addView(cursorLabel);
        SeekBar cursorBar = new SeekBar(this);
        cursorBar.setMax(150); // 50%–200%
        cursorBar.setProgress(Math.round(cursorScale * 100) - 50);
        cursorBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean user) {
                cursorScale = (progress + 50) / 100f;
                cursorLabel.setText("Mac 光标大小：" + (progress + 50) + "%");
                if (presentation != null) presentation.setCursorScale(cursorScale);
            }
            @Override public void onStartTrackingTouch(SeekBar bar) { }
            @Override public void onStopTrackingTouch(SeekBar bar) { }
        });
        column.addView(cursorBar);

        CheckBox immersiveToggle = new CheckBox(this);
        immersiveToggle.setText("默认沉浸式鼠标（自动捕获，无移动边界）");
        immersiveToggle.setChecked(immersiveMouse);
        immersiveToggle.setOnCheckedChangeListener((button, selected) -> immersiveMouse = selected);
        column.addView(immersiveToggle);

        CheckBox qualityToggle = new CheckBox(this);
        qualityToggle.setText("优先流畅：16位省流传输（关闭为32位原彩；重连生效）");
        qualityToggle.setChecked(fastColor);
        qualityToggle.setOnCheckedChangeListener((button, selected) -> fastColor = selected);
        column.addView(qualityToggle);

        new AlertDialog.Builder(this).setTitle("远程鼠标与传输性能")
                .setView(column).setPositiveButton("保存", (dialog, which) -> {
                    getPreferences(MODE_PRIVATE).edit()
                            .putFloat("mouseSpeed", mouseSpeed)
                            .putFloat("cursorScale", cursorScale)
                            .putBoolean("immersiveMouse", immersiveMouse)
                            .putBoolean("fastColor", fastColor).apply();
                    if (immersiveMouse) requestImmersiveCapture();
                    else if (touchpad != null && touchpad.hasPointerCapture())
                        touchpad.releasePointerCapture();
                    if (currentClient != null)
                        status("鼠标设置已保存；传输画质在下次连接 VNC 时生效。");
                }).setNegativeButton("取消", (dialog, which) -> {
                    mouseSpeed = originalSpeed;
                    cursorScale = originalCursor;
                    immersiveMouse = originalImmersive;
                    fastColor = originalColor;
                    if (touchpad != null) touchpad.setMouseSpeed(mouseSpeed);
                    if (presentation != null) presentation.setCursorScale(cursorScale);
                }).show();
    }

    private void showAccessibilityInstructions() {
        new AlertDialog.Builder(this)
                .setTitle("实体键盘快捷键映射")
                .setMessage("开启系统无障碍服务『External VNC Display · 键盘转发』后，"
                        + "当本应用位于前台、VNC 已连接且触控板获得焦点时，"
                        + "会优先将实体键盘按键与 Ctrl / Alt / Shift / Command 组合发送至 Mac。\n\n"
                        + "离开应用或在本机输入连接密码时不会转发，也不记录按键内容。"
                        + "ColorOS/Android 系统保留的部分组合键仍可能无法拦截；"
                        + "若其他无障碍服务已占用按键过滤，也可能无法生效。")
                .setPositiveButton("打开无障碍设置", (dialog, which) -> {
                    try { startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)); }
                    catch (RuntimeException error) { status("无法打开无障碍设置：" + error.getMessage()); }
                }).setNegativeButton("稍后", null).show();
    }

    private void requestImmersiveCapture() {
        if (!immersiveMouse || drawerOpen || currentClient == null
                || touchpad == null || !hasWindowFocus()) return;
        touchpad.requestFocus();
        touchpad.postDelayed(() -> {
            if (immersiveMouse && !drawerOpen && currentClient != null && hasWindowFocus()
                    && !touchpad.hasPointerCapture()) {
                touchpad.requestPointerCapture();
                diagnostics.add("INPUT", "Automatic immersive pointer capture requested");
            }
        }, 180);
    }

    boolean forwardAccessibilityKey(KeyEvent event) {
        if (drawerOpen || currentClient == null || !hasWindowFocus() || touchpad == null
                || getCurrentFocus() != touchpad) return false;
        int action = event.getAction();
        if (action != KeyEvent.ACTION_DOWN && action != KeyEvent.ACTION_UP) return false;
        int keysym = KeySyms.map(event);
        if (keysym == 0) return false;
        currentClient.sendKey(keysym, action == KeyEvent.ACTION_DOWN);
        return true;
    }

    @Override protected void onResume() {
        super.onResume();
        if (accessibilityButton != null)
            accessibilityButton.setText(VncKeyboardAccessibilityService.isServiceConnected()
                    ? "无障碍键盘：已开" : "无障碍键盘：未开");
        if (touchpad != null && currentClient != null) requestImmersiveCapture();
    }

    @Override protected void onPause() {
        if (touchpad != null && touchpad.hasPointerCapture()) touchpad.releasePointerCapture();
        if (currentClient != null) currentClient.releaseAllKeys();
        super.onPause();
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            applyPadImmersive();
            requestImmersiveCapture();
        }
    }

    private void applyPadImmersive() {
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            android.view.WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                controller.hide(WindowInsets.Type.systemBars());
                controller.setSystemBarsBehavior(
                        android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
        }
    }

    @Override public void onBackPressed() {
        if (drawerOpen) closeDrawer();
        else openDrawer();
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

    private void openDrawer() {
        if (drawerOpen) return;
        drawerOpen = true;
        if (touchpad != null && touchpad.hasPointerCapture()) touchpad.releasePointerCapture();
        if (currentClient != null) {
            currentClient.setButtons(0);
            currentClient.releaseAllKeys();
        }
        if (drawerOverlay != null) drawerOverlay.setVisibility(View.VISIBLE);
        if (drawerPanel != null) {
            drawerPanel.animate().cancel();
            drawerPanel.setTranslationX(drawerPanel.getLayoutParams().width);
            drawerPanel.animate().translationX(0f).setDuration(200).start();
        }
    }

    private void closeDrawer() {
        if (!drawerOpen) return;
        drawerOpen = false;
        if (drawerPanel != null) {
            drawerPanel.animate().cancel();
            drawerPanel.animate().translationX(drawerPanel.getLayoutParams().width)
                    .setDuration(180).withEndAction(() -> {
                        if (!drawerOpen && drawerOverlay != null)
                            drawerOverlay.setVisibility(View.GONE);
                    }).start();
        } else if (drawerOverlay != null) drawerOverlay.setVisibility(View.GONE);
        InputMethodManager keyboard = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (keyboard != null) keyboard.hideSoftInputFromWindow(touchpad.getWindowToken(), 0);
        if (touchpad != null) touchpad.requestFocus();
        requestImmersiveCapture();
    }

    private void refreshProfiles(String selectedId) {
        profileOptions = profileRepository.list();
        List<String> labels = new ArrayList<>();
        for (ConnectionProfile profile : profileOptions) {
            String prefix = profile.id.equals(profileRepository.defaultId()) ? "★ " : "";
            labels.add(prefix + profile.displayLabel());
        }
        if (labels.isEmpty()) labels.add("尚无连接配置，请点新增");
        loadingProfile = true;
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(this,
                android.R.layout.simple_spinner_item, labels) {
            @Override public View getView(int position, View convertView,
                                          android.view.ViewGroup parent) {
                TextView view = (TextView) super.getView(position, convertView, parent);
                view.setTextColor(Color.WHITE);
                return view;
            }
        };
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        profileSelector.setAdapter(adapter);
        int selected = 0;
        for (int i = 0; i < profileOptions.size(); i++) {
            if (profileOptions.get(i).id.equals(selectedId)) { selected = i; break; }
        }
        if (!profileOptions.isEmpty()) {
            profileSelector.setSelection(selected);
            loadProfile(profileOptions.get(selected));
        } else newProfile();
        loadingProfile = false;
    }

    private void selectProfile(ConnectionProfile selected) {
        if (selected.id.equals(editingProfileId)) return;
        if (currentClient != null && activeSessionProfileId != null
                && !activeSessionProfileId.equals(selected.id)) {
            String previousId = activeSessionProfileId;
            new AlertDialog.Builder(this).setTitle("切换远程账号")
                    .setMessage("切换账号需要先断开当前 VNC 会话，继续吗？")
                    .setPositiveButton("断开并切换", (dialog, which) -> {
                        disconnect();
                        loadProfile(selected);
                    })
                    .setNegativeButton("取消", (dialog, which) ->
                            refreshProfiles(previousId))
                    .show();
        } else loadProfile(selected);
    }

    private void loadProfile(ConnectionProfile profile) {
        editingProfileId = profile.id;
        profileNameField.setText(profile.name);
        hostField.setText(profile.host);
        portField.setText(Integer.toString(profile.port));
        usernameField.setText(profile.username);
        passwordField.setText("");
        boolean saved = credentialVault.hasSavedPassword(profile.id);
        passwordField.setHint(saved ? "已安全保存密码，留空沿用" : "密码（首次请输入）");
        savePasswordCheck.setChecked(true);
        authSelector.setSelection(profile.authMode.ordinal());
    }

    private void newProfile() {
        editingProfileId = null;
        profileNameField.setText("新连接");
        hostField.setText("");
        portField.setText("5900");
        usernameField.setText("");
        passwordField.setText("");
        passwordField.setHint("密码（可选择保存）");
        savePasswordCheck.setChecked(true);
        authSelector.setSelection(0);
        status("新建连接配置：填写账号并点击『保存配置』。 ");
    }

    private boolean saveProfile() {
        String host = hostField.getText().toString().trim();
        String name = profileNameField.getText().toString().trim();
        String username = usernameField.getText().toString().trim();
        String portString = portField.getText().toString().trim();
        if (host.isEmpty() || host.length() > 253 || username.length() > 256 || name.length() > 100) {
            status("保存失败：请填写合法主机地址、配置名称和用户名。");
            return false;
        }
        final int port;
        try {
            port = Integer.parseInt(portString);
            if (port < 1 || port > 65535) throw new NumberFormatException();
        } catch (NumberFormatException error) {
            status("保存失败：端口必须为 1–65535。");
            return false;
        }
        ConnectionProfile profile = editingProfileId == null ? null
                : profileRepository.find(editingProfileId);
        if (profile == null) profile = new ConnectionProfile();
        profile.name = name.isEmpty() ? "Mac 连接" : name;
        profile.host = host;
        profile.port = port;
        profile.username = username;
        profile.authMode = RfbClient.AuthMode.values()[authSelector.getSelectedItemPosition()];
        String enteredPassword = passwordField.getText().toString();
        try {
            profileRepository.upsert(profile);
            if (!savePasswordCheck.isChecked()) credentialVault.remove(profile.id);
            else if (!enteredPassword.isEmpty()) credentialVault.save(profile.id, enteredPassword);
            editingProfileId = profile.id;
            passwordField.setText("");
            refreshProfiles(profile.id);
            status("连接配置已保存" + (credentialVault.hasSavedPassword(profile.id)
                    ? "（密码使用设备 Keystore 加密）" : "（未保存密码）"));
            return true;
        } catch (RuntimeException | GeneralSecurityException error) {
            status("保存连接配置失败：" + error.getClass().getSimpleName());
            return false;
        }
    }

    private void markDefaultProfile() {
        if (editingProfileId == null) {
            status("请先保存连接配置，再设置默认账号。");
            return;
        }
        profileRepository.makeDefault(editingProfileId);
        refreshProfiles(editingProfileId);
        status("已设置为默认连接账号。");
    }

    private void confirmDeleteProfile() {
        if (editingProfileId == null) return;
        String removing = editingProfileId;
        new AlertDialog.Builder(this).setTitle("删除连接账号")
                .setMessage("将删除此连接配置和设备上加密保存的密码，无法撤销。")
                .setPositiveButton("删除", (dialog, which) -> {
                    if (removing.equals(activeSessionProfileId)) disconnect();
                    credentialVault.remove(removing);
                    profileRepository.remove(removing);
                    refreshProfiles(profileRepository.defaultId());
                    status("连接账号及关联密码已删除。");
                }).setNegativeButton("取消", null).show();
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
            next.setCursorScale(cursorScale);
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
        String typedPassword = passwordField.getText().toString();
        if (!saveProfile()) return;
        ConnectionProfile profile = profileRepository.find(editingProfileId);
        if (profile == null) { status("请选择有效连接账号。"); return; }
        String host = profile.host, username = profile.username;
        int port = profile.port;
        final String password;
        try {
            String stored = typedPassword.isEmpty() ? credentialVault.load(profile.id) : typedPassword;
            password = stored == null ? "" : stored;
        } catch (GeneralSecurityException error) {
            status("保存的密码无法解密，请重新输入并保存。" );
            return;
        }

        RfbClient.AuthMode mode = profile.authMode;
        if ((mode == RfbClient.AuthMode.MAC_ACCOUNT || mode == RfbClient.AuthMode.MAC_CBC_COMPAT)
                && username.isEmpty()) {
            status("Mac 账号认证请填写本地用户名（不是 Apple ID 邮箱）。");
            return;
        }
        if (password.isEmpty() && mode != RfbClient.AuthMode.AUTO) {
            status("请填写密码；Mac 账号认证使用 Mac 登录密码。");
            return;
        }
        // Legacy host/port metadata only. Password is kept in Keystore-backed storage.
        getPreferences(MODE_PRIVATE).edit().putString("host", host)
                .putString("port", Integer.toString(port)).apply();

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
                    ConnectionProfile savedProfile = profileRepository.find(profile.id);
                    if (savedProfile != null) {
                        savedProfile.lastConnectedAt = System.currentTimeMillis();
                        profileRepository.upsert(savedProfile);
                    }
                    status("已连接 " + desktop + " · " + width + "×" + height);
                    touchpad.requestFocus();
                    if (immersiveMouse) requestImmersiveCapture();
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
                            fpsStatus.setText(String.format(Locale.ROOT,
                                    "变化 %.1f FPS\n%s", actual, performanceSummary));
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

            private long lastTimingLog;
            @Override public void onFrameTiming(long serverWaitMs, long readDecodeMs, int updateRects) {
                long now = SystemClock.elapsedRealtime();
                performanceSummary = "等 " + serverWaitMs + "ms / 解 " + readDecodeMs + "ms";
                if (now - lastTimingLog > 3000) {
                    lastTimingLog = now;
                    diagnostics.add("PERF", "RFB frame wait=" + serverWaitMs + "ms"
                            + " readDecode=" + readDecodeMs + "ms rectangles=" + updateRects
                            + " framebuffer=" + holder[0].getFramebufferWidth() + "x"
                            + holder[0].getFramebufferHeight());
                }
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
                    activeSessionProfileId = null;
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
        activeSessionProfileId = profile.id;
        session.setTargetFps(targetFps);
        session.setFastColor(fastColor);
        touchpad.setClient(session);
        touchpad.setMouseSpeed(mouseSpeed);
        connectButton.setText("断开 VNC");
        status("正在连接 " + host + ":" + port + " ...");
        closeDrawer();
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
        activeSessionProfileId = null;
        if (session != null) session.close();
        touchpad.setClient(null);
        synchronized (renderLock) { renderUpdates.clear(); }
        fpsStatus.setText("实际：-- FPS");
        if (touchpad.hasPointerCapture()) touchpad.releasePointerCapture();
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
            immersiveMouse = false;
            getPreferences(MODE_PRIVATE).edit().putBoolean("immersiveMouse", false).apply();
            touchpad.releasePointerCapture();
            pressedButtons = 0;
            if (currentClient != null) currentClient.setButtons(0);
            return;
        }
        immersiveMouse = true;
        getPreferences(MODE_PRIVATE).edit().putBoolean("immersiveMouse", true).apply();
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
                if (session != null) {
                    float ratio = session.getFramebufferWidth()
                            / (float) Math.max(1, getResources().getDisplayMetrics().widthPixels);
                    float effective = Math.max(1f, ratio) * mouseSpeed;
                    session.sendRelativeMove(dx * effective, dy * effective);
                }
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
        if (!drawerOpen && session != null && getCurrentFocus() == touchpad) {
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
        VncKeyboardAccessibilityService.detach(this);
        displayManager.unregisterDisplayListener(this);
        disconnect();
        closeInputTest();
        closePresentation();
        worker.shutdownNow();
        super.onDestroy();
    }
}
