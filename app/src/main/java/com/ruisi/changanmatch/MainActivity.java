package com.ruisi.changanmatch;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.NumberPicker;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;

public class MainActivity extends Activity {
    private static final int REQ_CAPTURE = 1001;
    private static final int REQ_NOTIFICATION = 1002;

    private static final int TAB_MATCH = 0;
    private static final int TAB_QUIZ = 1;
    private static final int TAB_LICENSE = 2;

    private static final String OFFLINE_TEST_KEY = "SQCS-2026-TEST-0001";
    private static final String PREF_ACTIVE_KEY = "offline_active_license_key";
    private static final String PREF_TEST_EXPIRES_AT = "offline_test_expires_at";
    private static final long OFFLINE_DURATION_MS = 7L * 24L * 60L * 60L * 1000L;

    private static final int PURPLE = Color.rgb(91, 61, 170);
    private static final int PURPLE_DARK = Color.rgb(61, 41, 112);
    private static final int PAGE_BG = Color.rgb(247, 245, 252);
    private static final int TEXT_DARK = Color.rgb(55, 54, 64);
    private static final int TEXT_MUTED = Color.rgb(112, 108, 124);
    private static final int GREEN = Color.rgb(24, 125, 76);

    private MediaProjectionManager projectionManager;
    private SharedPreferences preferences;
    private TextView permissionState;
    private TextView countdownView;
    private Button startButton;
    private NumberPicker rowsPicker;
    private NumberPicker columnsPicker;
    private NumberPicker kindsPicker;
    private Spinner speedSpinner;
    private CheckBox autoMode;
    private CountDownTimer licenseTimer;
    private boolean mainUiReady;
    private int currentTab = TAB_LICENSE;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        projectionManager = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        preferences = getSharedPreferences("match3_settings", MODE_PRIVATE);
        showDisclaimer();
    }

    private void showDisclaimer() {
        String message =
                "本软件为非官方辅助工具，仅供学习、交流和个人测试使用。\n\n" +
                "自动识别、悬浮窗及无障碍自动滑动等功能，可能不符合游戏运营方的用户协议或风控规则，可能导致警告、功能限制、数据异常或账号封禁。使用前请自行阅读并遵守相关协议，建议不要在重要账号上使用。\n\n" +
                "因使用或无法使用本软件产生的账号封禁、虚拟物品或数据损失、设备异常、经济损失以及其他直接或间接后果，由使用者自行承担。开发者不提供账号安全保证，并在法律允许范围内不承担相关责任。\n\n" +
                "本软件不包含修改游戏数据、绕过检测或破解功能。继续使用即表示你已理解上述风险并自愿承担。";

        new AlertDialog.Builder(this)
                .setTitle("使用免责声明")
                .setMessage(message)
                .setCancelable(false)
                .setPositiveButton("我已阅读并同意", (dialog, which) -> {
                    int firstTab = isOfflineLicenseValid() ? TAB_MATCH : TAB_LICENSE;
                    buildShell(firstTab);
                    if (isOfflineLicenseValid()) requestNotificationPermissionIfNeeded();
                })
                .setNegativeButton("不同意并退出", (dialog, which) -> finishAndRemoveTask())
                .show();
    }

    private void buildShell(int selectedTab) {
        stopLicenseTimer();
        currentTab = selectedTab;
        mainUiReady = selectedTab == TAB_MATCH && isOfflineLicenseValid();
        permissionState = null;
        startButton = null;

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(PAGE_BG);
        page.setPadding(dp(14), dp(12), dp(14), dp(10));

        TextView title = text("深情助手", 25, PURPLE_DARK);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, dp(2), 0, dp(8));
        page.addView(title, fullWidth());

        LinearLayout tabs = new LinearLayout(this);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        tabs.setGravity(Gravity.CENTER);
        tabs.setPadding(dp(3), dp(3), dp(3), dp(3));
        tabs.setBackground(roundRect(Color.rgb(235, 231, 246), 16));
        page.addView(tabs, fullWidth());

        addTabButton(tabs, "消消乐", TAB_MATCH, selectedTab == TAB_MATCH);
        addTabButton(tabs, "答题器", TAB_QUIZ, selectedTab == TAB_QUIZ);
        addTabButton(tabs, "卡密", TAB_LICENSE, selectedTab == TAB_LICENSE);

        countdownView = text("", 11, GREEN);
        countdownView.setGravity(Gravity.CENTER);
        countdownView.setPadding(dp(10), dp(5), dp(10), dp(5));
        LinearLayout.LayoutParams countdownParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        countdownParams.gravity = Gravity.END;
        countdownParams.setMargins(0, dp(7), dp(3), dp(2));
        page.addView(countdownView, countdownParams);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(2), dp(4), dp(2), dp(10));
        scroll.addView(content);
        page.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        if (selectedTab == TAB_MATCH) {
            renderMatchTab(content);
        } else if (selectedTab == TAB_QUIZ) {
            renderQuizTab(content);
        } else {
            renderLicenseTab(content);
        }

        TextView maker = text("深情制作", 14, PURPLE);
        maker.setGravity(Gravity.CENTER);
        maker.setPadding(0, dp(5), 0, dp(1));
        page.addView(maker, fullWidth());

        setContentView(page);
        updateTopCountdown();
    }

    private void addTabButton(LinearLayout parent, String label, int tab, boolean selected) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextSize(15);
        button.setAllCaps(false);
        button.setTextColor(selected ? Color.WHITE : PURPLE);
        button.setPadding(0, 0, 0, 0);
        button.setMinHeight(0);
        button.setMinimumHeight(0);
        button.setBackground(selected
                ? roundRect(PURPLE, 13)
                : roundRect(Color.TRANSPARENT, 13));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            button.setStateListAnimator(null);
        }
        button.setOnClickListener(v -> switchTab(tab));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(43), 1f);
        params.setMargins(dp(2), 0, dp(2), 0);
        parent.addView(button, params);
    }

    private void switchTab(int tab) {
        if (tab != TAB_LICENSE && !isOfflineLicenseValid()) {
            Toast.makeText(this, "请先在卡密栏完成激活", Toast.LENGTH_SHORT).show();
            buildShell(TAB_LICENSE);
            return;
        }
        buildShell(tab);
    }

    private void renderLicenseTab(LinearLayout root) {
        LinearLayout card = verticalCard(Color.WHITE, 20);
        card.setPadding(dp(18), dp(18), dp(18), dp(18));
        root.addView(card, cardParams());

        TextView cardTitle = text(isOfflineLicenseValid() ? "卡密信息" : "离线卡密激活",
                21, PURPLE_DARK);
        cardTitle.setGravity(Gravity.CENTER);
        card.addView(cardTitle, fullWidth());

        TextView description = text(
                "卡密只在本机验证，不连接服务器。请复制本机号交给卡密制作方，再输入生成的卡密完成激活。",
                14, TEXT_MUTED);
        description.setGravity(Gravity.CENTER);
        description.setLineSpacing(0, 1.18f);
        description.setPadding(dp(2), dp(9), dp(2), dp(14));
        card.addView(description, fullWidth());

        LinearLayout deviceBox = verticalCard(Color.rgb(246, 243, 252), 15);
        deviceBox.setPadding(dp(14), dp(12), dp(14), dp(12));
        card.addView(deviceBox, withMargins(fullWidth(), dp(4)));

        TextView deviceLabel = text("本机号", 13, TEXT_MUTED);
        deviceLabel.setGravity(Gravity.CENTER);
        deviceBox.addView(deviceLabel, fullWidth());

        String machineId = getMachineId();
        TextView deviceValue = text(machineId, 17, PURPLE_DARK);
        deviceValue.setGravity(Gravity.CENTER);
        deviceValue.setTextIsSelectable(true);
        deviceValue.setPadding(0, dp(4), 0, dp(7));
        deviceBox.addView(deviceValue, fullWidth());

        Button copyButton = secondaryButton("复制本机号");
        copyButton.setOnClickListener(v -> copyMachineId(machineId));
        deviceBox.addView(copyButton, compactButtonParams());

        if (isOfflineLicenseValid()) {
            TextView activeState = text("卡密已激活，可在上方查看剩余时间", 14, GREEN);
            activeState.setGravity(Gravity.CENTER);
            activeState.setPadding(0, dp(14), 0, dp(7));
            card.addView(activeState, fullWidth());

            Button enterButton = primaryButton("进入消消乐");
            enterButton.setOnClickListener(v -> switchTab(TAB_MATCH));
            card.addView(enterButton, buttonParams());
            return;
        }

        EditText licenseInput = new EditText(this);
        licenseInput.setSingleLine(true);
        licenseInput.setTextSize(16);
        licenseInput.setHint("请输入离线卡密");
        licenseInput.setInputType(InputType.TYPE_CLASS_TEXT |
                InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        licenseInput.setPadding(dp(16), 0, dp(16), 0);
        licenseInput.setBackground(roundStroke(Color.rgb(252, 251, 255),
                Color.rgb(188, 177, 220), 14));
        card.addView(licenseInput, licenseInputParams());

        TextView status = text("无需联网即可验证", 13, TEXT_MUTED);
        status.setGravity(Gravity.CENTER);
        status.setPadding(0, dp(7), 0, dp(8));
        card.addView(status, fullWidth());

        Button verifyButton = primaryButton("激活并进入");
        verifyButton.setOnClickListener(v -> activateOfflineLicense(licenseInput, status));
        card.addView(verifyButton, buttonParams());
    }

    private void renderMatchTab(LinearLayout root) {
        LinearLayout introCard = verticalCard(Color.WHITE, 18);
        introCard.setPadding(dp(16), dp(15), dp(16), dp(15));
        root.addView(introCard, compactCardParams());

        TextView introTitle = text("宴会消消乐", 19, PURPLE_DARK);
        introCard.addView(introTitle, fullWidth());
        TextView intro = text(
                "授权悬浮窗 → 开启自动滑动权限 → 确认棋盘参数 → 开始识别 → 回到游戏点击浮窗“标定”。",
                14, TEXT_MUTED);
        intro.setLineSpacing(0, 1.2f);
        intro.setPadding(0, dp(7), 0, 0);
        introCard.addView(intro, fullWidth());

        LinearLayout permissionCard = verticalCard(Color.WHITE, 18);
        permissionCard.setPadding(dp(16), dp(15), dp(16), dp(15));
        root.addView(permissionCard, compactCardParams());
        permissionCard.addView(text("权限设置", 18, PURPLE_DARK), fullWidth());

        permissionState = text("", 14, TEXT_MUTED);
        permissionState.setPadding(0, dp(8), 0, dp(8));
        permissionCard.addView(permissionState, fullWidth());

        Button overlayButton = primaryButton("① 授权悬浮窗");
        overlayButton.setOnClickListener(v -> openOverlaySettings());
        permissionCard.addView(overlayButton, buttonParams());

        Button accessibilityButton = secondaryButton("② 开启自动滑动权限");
        accessibilityButton.setOnClickListener(v ->
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        permissionCard.addView(accessibilityButton, buttonParams());

        LinearLayout settingsCard = verticalCard(Color.WHITE, 18);
        settingsCard.setPadding(dp(16), dp(15), dp(16), dp(15));
        root.addView(settingsCard, compactCardParams());
        settingsCard.addView(text("棋盘与速度", 18, PURPLE_DARK), fullWidth());

        LinearLayout pickers = new LinearLayout(this);
        pickers.setOrientation(LinearLayout.HORIZONTAL);
        pickers.setGravity(Gravity.CENTER);
        pickers.setPadding(0, dp(7), 0, dp(2));
        rowsPicker = addPicker(pickers, "行数", 4, 10, preferences.getInt("rows", 8));
        columnsPicker = addPicker(pickers, "列数", 4, 10,
                preferences.getInt("columns", 7));
        kindsPicker = addPicker(pickers, "图标种类", 4, 9,
                preferences.getInt("kinds", 5));
        settingsCard.addView(pickers, fullWidth());

        LinearLayout speedRow = new LinearLayout(this);
        speedRow.setOrientation(LinearLayout.HORIZONTAL);
        speedRow.setGravity(Gravity.CENTER_VERTICAL);
        speedRow.setPadding(0, dp(5), 0, dp(3));
        TextView speedLabel = text("滑动速度", 15, TEXT_DARK);
        speedRow.addView(speedLabel, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 0.30f));

        speedSpinner = new Spinner(this);
        String[] speedOptions = {
                "稳定（1.5～2.0秒）",
                "快速（0.8～1.2秒）",
                "极速（0.5～0.8秒）"
        };
        ArrayAdapter<String> speedAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, speedOptions);
        speedAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        speedSpinner.setAdapter(speedAdapter);
        speedSpinner.setSelection(Math.max(0, Math.min(2,
                preferences.getInt("speed_mode", 0))));
        speedRow.addView(speedSpinner, new LinearLayout.LayoutParams(0, dp(50), 0.70f));
        settingsCard.addView(speedRow, fullWidth());

        autoMode = new CheckBox(this);
        autoMode.setText("开启自动滑动（关闭时只显示提示）");
        autoMode.setTextSize(15);
        autoMode.setTextColor(TEXT_DARK);
        autoMode.setChecked(preferences.getBoolean("auto_mode", false));
        autoMode.setPadding(0, dp(7), 0, dp(4));
        settingsCard.addView(autoMode, fullWidth());

        LinearLayout actionCard = verticalCard(Color.WHITE, 18);
        actionCard.setPadding(dp(16), dp(15), dp(16), dp(15));
        root.addView(actionCard, compactCardParams());
        actionCard.addView(text("运行控制", 18, PURPLE_DARK), fullWidth());

        startButton = primaryButton("③ 开始识别棋盘");
        startButton.setOnClickListener(v -> startCaptureRequest());
        actionCard.addView(startButton, buttonParams());

        Button stopButton = secondaryButton("停止运行");
        stopButton.setOnClickListener(v -> {
            Intent stop = new Intent(this, ScreenCaptureService.class);
            stop.setAction(ScreenCaptureService.ACTION_STOP);
            startService(stop);
            Toast.makeText(this, "已发送停止指令", Toast.LENGTH_SHORT).show();
        });
        actionCard.addView(stopButton, buttonParams());

        Button resetButton = secondaryButton("重置棋盘标定");
        resetButton.setOnClickListener(v -> {
            preferences.edit()
                    .remove("board_left").remove("board_top")
                    .remove("board_right").remove("board_bottom").apply();
            Toast.makeText(this, "已重置，开始后请重新标定", Toast.LENGTH_SHORT).show();
        });
        actionCard.addView(resetButton, buttonParams());

        TextView note = text(
                "默认参数为 8 行×7 列、5 类图标。建议第一次关闭自动模式测试；粉红切片、数字和闪光属于消除动画，助手会等待画面稳定后再计算。",
                13, TEXT_MUTED);
        note.setLineSpacing(0, 1.18f);
        note.setPadding(dp(14), dp(12), dp(14), dp(12));
        note.setBackground(roundRect(Color.rgb(241, 238, 248), 15));
        root.addView(note, compactCardParams());

        updatePermissionState();
    }

    private void renderQuizTab(LinearLayout root) {
        LinearLayout card = verticalCard(Color.WHITE, 20);
        card.setPadding(dp(18), dp(18), dp(18), dp(18));
        root.addView(card, cardParams());

        TextView title = text("长安题库答题器", 21, PURPLE_DARK);
        title.setGravity(Gravity.CENTER);
        card.addView(title, fullWidth());

        TextView description = text(
                "顶部切换入口已经按原方案保留。答题器使用本地题库和屏幕识别，不上传题目内容。",
                14, TEXT_MUTED);
        description.setGravity(Gravity.CENTER);
        description.setLineSpacing(0, 1.2f);
        description.setPadding(0, dp(10), 0, dp(15));
        card.addView(description, fullWidth());

        Button openButton = primaryButton("启动题库答题器");
        openButton.setOnClickListener(v -> openQuizAssistant());
        card.addView(openButton, buttonParams());

        TextView note = text(
                "答题时会打开本机题库识别组件；返回本应用后仍可在顶部继续切换消消乐和卡密页面。",
                13, TEXT_MUTED);
        note.setGravity(Gravity.CENTER);
        note.setPadding(dp(5), dp(10), dp(5), 0);
        card.addView(note, fullWidth());
    }

    private void openQuizAssistant() {
        Intent launch = getPackageManager().getLaunchIntentForPackage("com.ruisi.changanquiz");
        if (launch == null) {
            Toast.makeText(this, "未检测到题库答题器组件，请先安装题库模块", Toast.LENGTH_LONG).show();
            return;
        }
        startActivity(launch);
    }

    private void activateOfflineLicense(EditText input, TextView status) {
        String key = input.getText().toString().trim().toUpperCase(Locale.US);
        if (!OFFLINE_TEST_KEY.equals(key)) {
            input.setError("卡密不正确");
            status.setText("离线卡密验证失败");
            status.setTextColor(Color.rgb(190, 45, 45));
            return;
        }

        long now = System.currentTimeMillis();
        long expiresAt = preferences.getLong(PREF_TEST_EXPIRES_AT, 0L);
        if (expiresAt <= 0L) {
            expiresAt = now + OFFLINE_DURATION_MS;
            preferences.edit().putLong(PREF_TEST_EXPIRES_AT, expiresAt).apply();
        }
        if (now >= expiresAt) {
            status.setText("该离线卡密已到期");
            status.setTextColor(Color.rgb(190, 45, 45));
            return;
        }

        preferences.edit().putString(PREF_ACTIVE_KEY, key).apply();
        Toast.makeText(this, "激活成功", Toast.LENGTH_SHORT).show();
        requestNotificationPermissionIfNeeded();
        buildShell(TAB_MATCH);
    }

    private String getMachineId() {
        String androidId = Settings.Secure.getString(
                getContentResolver(), Settings.Secure.ANDROID_ID);
        if (androidId == null || androidId.trim().isEmpty()) {
            androidId = "unknown-device";
        }
        String source = androidId + "|" + getPackageName();
        String raw;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(source.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder();
            for (byte value : bytes) {
                builder.append(String.format(Locale.US, "%02X", value & 0xff));
            }
            raw = builder.substring(0, 16);
        } catch (Exception ignored) {
            raw = Integer.toHexString(source.hashCode()).toUpperCase(Locale.US);
            while (raw.length() < 16) raw += "0";
            raw = raw.substring(0, 16);
        }
        return raw.substring(0, 4) + "-" + raw.substring(4, 8) + "-" +
                raw.substring(8, 12) + "-" + raw.substring(12, 16);
    }

    private void copyMachineId(String machineId) {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText("深情助手本机号", machineId));
            Toast.makeText(this, "本机号已复制", Toast.LENGTH_SHORT).show();
        }
    }

    private void updateTopCountdown() {
        if (countdownView == null) return;
        if (!isOfflineLicenseValid()) {
            countdownView.setText("卡密未激活");
            countdownView.setTextColor(Color.rgb(190, 45, 45));
            countdownView.setBackground(roundRect(Color.rgb(255, 236, 236), 15));
            return;
        }
        startLicenseCountdown();
    }

    private void startLicenseCountdown() {
        stopLicenseTimer();
        long expiresAt = preferences.getLong(PREF_TEST_EXPIRES_AT, 0L);
        long remaining = expiresAt - System.currentTimeMillis();
        if (remaining <= 0L) {
            showExpiredState();
            return;
        }

        licenseTimer = new CountDownTimer(remaining, 1000L) {
            @Override
            public void onTick(long millisUntilFinished) {
                updateCountdownText(millisUntilFinished);
            }

            @Override
            public void onFinish() {
                showExpiredState();
            }
        };
        licenseTimer.start();
        updateCountdownText(remaining);
    }

    private void updateCountdownText(long remainingMs) {
        if (countdownView == null) return;
        long totalSeconds = Math.max(0L, remainingMs / 1000L);
        long days = totalSeconds / 86400L;
        long hours = (totalSeconds % 86400L) / 3600L;
        long minutes = (totalSeconds % 3600L) / 60L;
        long seconds = totalSeconds % 60L;
        countdownView.setText(String.format(Locale.CHINA,
                "剩余 %d天 %02d:%02d:%02d", days, hours, minutes, seconds));

        if (remainingMs <= 24L * 60L * 60L * 1000L) {
            countdownView.setTextColor(Color.rgb(190, 92, 28));
            countdownView.setBackground(roundRect(Color.rgb(255, 244, 229), 15));
        } else {
            countdownView.setTextColor(GREEN);
            countdownView.setBackground(roundRect(Color.rgb(237, 249, 242), 15));
        }
    }

    private void showExpiredState() {
        if (countdownView != null) {
            countdownView.setText("卡密已到期");
            countdownView.setTextColor(Color.rgb(190, 45, 45));
            countdownView.setBackground(roundRect(Color.rgb(255, 236, 236), 15));
        }
        if (startButton != null) {
            startButton.setEnabled(false);
            startButton.setText("卡密已到期，无法启动");
        }
    }

    private void stopLicenseTimer() {
        if (licenseTimer != null) {
            licenseTimer.cancel();
            licenseTimer = null;
        }
    }

    private boolean isOfflineLicenseValid() {
        String activeKey = preferences.getString(PREF_ACTIVE_KEY, "");
        long expiresAt = preferences.getLong(PREF_TEST_EXPIRES_AT, 0L);
        return OFFLINE_TEST_KEY.equals(activeKey) && expiresAt > System.currentTimeMillis();
    }

    private NumberPicker addPicker(LinearLayout parent, String label,
                                   int min, int max, int value) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        TextView caption = text(label, 13, TEXT_MUTED);
        caption.setGravity(Gravity.CENTER);
        box.addView(caption, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        NumberPicker picker = new NumberPicker(this);
        picker.setMinValue(min);
        picker.setMaxValue(max);
        picker.setValue(Math.max(min, Math.min(max, value)));
        box.addView(picker, new LinearLayout.LayoutParams(dp(92), dp(100)));
        parent.addView(box, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        return picker;
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (mainUiReady && permissionState != null) {
            updatePermissionState();
        }
    }

    @Override
    protected void onDestroy() {
        stopLicenseTimer();
        super.onDestroy();
    }

    private void updatePermissionState() {
        if (permissionState == null) return;
        boolean overlay = Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
                Settings.canDrawOverlays(this);
        boolean accessibility = AutomationAccessibilityService.isReady();
        permissionState.setText("悬浮窗：" + (overlay ? "已授权" : "未授权") +
                "    自动滑动：" + (accessibility ? "已开启" : "未开启"));
        permissionState.setTextColor(overlay && accessibility
                ? GREEN : Color.rgb(190, 80, 35));
    }

    private void openOverlaySettings() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return;
        try {
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName())));
        } catch (Exception ignored) {
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION));
        }
    }

    private void startCaptureRequest() {
        if (!isOfflineLicenseValid()) {
            Toast.makeText(this, "离线卡密已到期", Toast.LENGTH_LONG).show();
            showExpiredState();
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            new AlertDialog.Builder(this)
                    .setTitle("需要悬浮窗权限")
                    .setMessage("请先允许本应用显示在其他应用上层，才能标定棋盘并显示运行状态。")
                    .setPositiveButton("去授权", (d, w) -> openOverlaySettings())
                    .setNegativeButton("取消", null)
                    .show();
            return;
        }
        if (autoMode.isChecked() && !AutomationAccessibilityService.isReady()) {
            new AlertDialog.Builder(this)
                    .setTitle("自动滑动权限未开启")
                    .setMessage("开启自动模式需要在无障碍设置中启用“宴会消消乐自动滑动”。也可以先关闭自动模式，仅查看提示。")
                    .setPositiveButton("去开启", (d, w) ->
                            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)))
                    .setNegativeButton("取消", null)
                    .show();
            return;
        }
        saveSettings();
        startActivityForResult(projectionManager.createScreenCaptureIntent(), REQ_CAPTURE);
    }

    private void saveSettings() {
        preferences.edit()
                .putInt("rows", rowsPicker.getValue())
                .putInt("columns", columnsPicker.getValue())
                .putInt("kinds", kindsPicker.getValue())
                .putInt("speed_mode", speedSpinner.getSelectedItemPosition())
                .putBoolean("auto_mode", autoMode.isChecked())
                .apply();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_CAPTURE) return;
        if (resultCode != RESULT_OK || data == null) {
            Toast.makeText(this, "未获得屏幕录制权限", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent service = new Intent(this, ScreenCaptureService.class);
        service.setAction(ScreenCaptureService.ACTION_START);
        service.putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, resultCode);
        service.putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, data);
        service.putExtra(ScreenCaptureService.EXTRA_ROWS, rowsPicker.getValue());
        service.putExtra(ScreenCaptureService.EXTRA_COLUMNS, columnsPicker.getValue());
        service.putExtra(ScreenCaptureService.EXTRA_KINDS, kindsPicker.getValue());
        service.putExtra(ScreenCaptureService.EXTRA_SPEED_MODE,
                speedSpinner.getSelectedItemPosition());
        service.putExtra(ScreenCaptureService.EXTRA_AUTO_MODE, autoMode.isChecked());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(service);
        else startService(service);
        Toast.makeText(this, "已启动，请切回游戏并点击浮窗“标定”", Toast.LENGTH_LONG).show();
        moveTaskToBack(true);
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                        PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},
                    REQ_NOTIFICATION);
        }
    }

    private TextView text(String value, int size, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        return view;
    }

    private Button primaryButton(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextSize(16);
        button.setTextColor(Color.WHITE);
        button.setAllCaps(false);
        button.setBackground(roundRect(PURPLE, 14));
        return button;
    }

    private Button secondaryButton(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextSize(15);
        button.setTextColor(PURPLE);
        button.setAllCaps(false);
        button.setBackground(roundStroke(Color.WHITE, Color.rgb(174, 159, 212), 14));
        return button;
    }

    private LinearLayout verticalCard(int color, int radiusDp) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(roundRect(color, radiusDp));
        card.setElevation(dp(2));
        return card;
    }

    private LinearLayout.LayoutParams fullWidth() {
        return new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams cardParams() {
        LinearLayout.LayoutParams p = fullWidth();
        p.setMargins(0, dp(6), 0, dp(8));
        return p;
    }

    private LinearLayout.LayoutParams compactCardParams() {
        LinearLayout.LayoutParams p = fullWidth();
        p.setMargins(0, dp(5), 0, dp(5));
        return p;
    }

    private LinearLayout.LayoutParams buttonParams() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(52));
        p.setMargins(0, dp(5), 0, dp(5));
        return p;
    }

    private LinearLayout.LayoutParams compactButtonParams() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(46));
        p.setMargins(0, dp(2), 0, 0);
        return p;
    }

    private LinearLayout.LayoutParams licenseInputParams() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(56));
        p.setMargins(0, dp(13), 0, dp(2));
        return p;
    }

    private LinearLayout.LayoutParams withMargins(LinearLayout.LayoutParams p, int vertical) {
        p.setMargins(0, vertical, 0, vertical);
        return p;
    }

    private GradientDrawable roundRect(int color, int radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radiusDp));
        return drawable;
    }

    private GradientDrawable roundStroke(int fill, int stroke, int radiusDp) {
        GradientDrawable drawable = roundRect(fill, radiusDp);
        drawable.setStroke(dp(1), stroke);
        return drawable;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
