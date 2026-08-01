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
import android.os.Build;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends Activity {
    private static final int REQ_NOTIFICATION = 1002;
    private static final int TAB_QUIZ = 0;
    private static final int TAB_LICENSE = 1;

    private static final String PREF_ACTIVE_KEY = "offline_active_license_key";
    private static final String PREF_LICENSE_EXPIRES_AT = "offline_license_expires_at";
    private static final String PREF_LICENSE_PERMANENT = "offline_license_permanent";
    private static final String PREF_LICENSE_TYPE = "offline_license_type";

    private static final int PURPLE = Color.rgb(91, 61, 170);
    private static final int PURPLE_DARK = Color.rgb(61, 41, 112);
    private static final int PAGE_BG = Color.rgb(247, 245, 252);
    private static final int TEXT_DARK = Color.rgb(55, 54, 64);
    private static final int TEXT_MUTED = Color.rgb(112, 108, 124);
    private static final int GREEN = Color.rgb(24, 125, 76);
    private static final int RED = Color.rgb(190, 45, 45);

    private SharedPreferences preferences;
    private TextView countdownView;
    private CountDownTimer licenseTimer;
    private int currentTab = TAB_LICENSE;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        preferences = getSharedPreferences("match3_settings", MODE_PRIVATE);
        showDisclaimer();
    }

    private void showDisclaimer() {
        String message =
                "本软件为非官方答题辅助工具，仅供学习、交流和个人测试使用。\n\n" +
                "屏幕识别、悬浮窗及无障碍自动点击功能，可能不符合游戏运营方的用户协议或风控规则，可能导致警告、功能限制或账号封禁。使用前请自行阅读并遵守相关协议。\n\n" +
                "本软件不修改游戏数据、不注入游戏进程、不处理游戏封包，也不包含绕过检测或破解功能。继续使用即表示你已理解风险并自愿承担。";

        new AlertDialog.Builder(this)
                .setTitle("使用免责声明")
                .setMessage(message)
                .setCancelable(false)
                .setPositiveButton("我已阅读并同意", (dialog, which) -> {
                    int firstTab = isOfflineLicenseValid() ? TAB_QUIZ : TAB_LICENSE;
                    buildShell(firstTab);
                    if (isOfflineLicenseValid()) requestNotificationPermissionIfNeeded();
                })
                .setNegativeButton("不同意并退出", (dialog, which) -> finishAndRemoveTask())
                .show();
    }

    private void buildShell(int selectedTab) {
        stopLicenseTimer();
        currentTab = selectedTab;

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

        if (selectedTab == TAB_QUIZ) renderQuizTab(content);
        else renderLicenseTab(content);

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
        button.setBackground(selected ? roundRect(PURPLE, 13) : roundRect(Color.TRANSPARENT, 13));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) button.setStateListAnimator(null);
        button.setOnClickListener(v -> switchTab(tab));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(43), 1f);
        params.setMargins(dp(2), 0, dp(2), 0);
        parent.addView(button, params);
    }

    private void switchTab(int tab) {
        if (tab == TAB_QUIZ && !isOfflineLicenseValid()) {
            Toast.makeText(this, "请先在卡密栏完成激活", Toast.LENGTH_SHORT).show();
            buildShell(TAB_LICENSE);
            return;
        }
        buildShell(tab);
    }

    private void renderQuizTab(LinearLayout root) {
        LinearLayout card = verticalCard(Color.WHITE, 20);
        card.setPadding(dp(18), dp(18), dp(18), dp(18));
        root.addView(card, cardParams());

        TextView title = text("长安题库答题器", 22, PURPLE_DARK);
        title.setGravity(Gravity.CENTER);
        card.addView(title, fullWidth());

        TextView description = text(
                "内置3603道本地题库，支持悬浮显示答案和自动点击。手动点击模式下，答错后会识别正确选项并保存到本地学习题库。",
                14, TEXT_MUTED);
        description.setGravity(Gravity.CENTER);
        description.setLineSpacing(0, 1.2f);
        description.setPadding(0, dp(10), 0, dp(15));
        card.addView(description, fullWidth());

        Button openButton = primaryButton("进入答题器");
        openButton.setOnClickListener(v -> startActivity(new Intent(this, QuizActivity.class)));
        card.addView(openButton, buttonParams());

        LearnedQuestionStore learned = new LearnedQuestionStore(this);
        TextView learnedState = text("本地错题学习记录：" + learned.size() + " 道", 14, GREEN);
        learnedState.setGravity(Gravity.CENTER);
        learnedState.setPadding(dp(8), dp(12), dp(8), dp(4));
        card.addView(learnedState, fullWidth());

        TextView note = text(
                "当前版本只保留答题器和卡密，已取消消消乐入口。错题记录仅保存在本机，不上传服务器。",
                13, TEXT_MUTED);
        note.setGravity(Gravity.CENTER);
        note.setPadding(dp(5), dp(10), dp(5), 0);
        card.addView(note, fullWidth());
    }

    private void renderLicenseTab(LinearLayout root) {
        LinearLayout card = verticalCard(Color.WHITE, 20);
        card.setPadding(dp(18), dp(18), dp(18), dp(18));
        root.addView(card, cardParams());

        OfflineLicense.Result active = readActiveLicense();
        TextView cardTitle = text(active.valid ? "卡密信息" : "离线卡密激活", 21, PURPLE_DARK);
        cardTitle.setGravity(Gravity.CENTER);
        card.addView(cardTitle, fullWidth());

        TextView description = text(
                "卡密只在本机验证，不连接服务器。复制本机号到原来的卡密生成器，再输入生成的卡密完成激活。",
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

        if (active.valid) {
            String expiryText = active.permanent ? "永久有效" :
                    new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA)
                            .format(new Date(active.expiresAtMillis));
            TextView activeState = text("已激活：" + active.typeLabel + "\n有效期：" + expiryText,
                    14, GREEN);
            activeState.setGravity(Gravity.CENTER);
            activeState.setPadding(0, dp(14), 0, dp(7));
            card.addView(activeState, fullWidth());

            Button enterButton = primaryButton("进入答题器");
            enterButton.setOnClickListener(v -> switchTab(TAB_QUIZ));
            card.addView(enterButton, buttonParams());
            return;
        }

        EditText licenseInput = new EditText(this);
        licenseInput.setSingleLine(true);
        licenseInput.setTextSize(15);
        licenseInput.setHint("请粘贴生成的离线卡密");
        licenseInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        licenseInput.setPadding(dp(16), 0, dp(16), 0);
        licenseInput.setBackground(roundStroke(Color.rgb(252, 251, 255), Color.rgb(188, 177, 220), 14));
        card.addView(licenseInput, licenseInputParams());

        TextView status = text("无需联网即可验证", 13, TEXT_MUTED);
        status.setGravity(Gravity.CENTER);
        status.setPadding(0, dp(7), 0, dp(8));
        card.addView(status, fullWidth());

        Button verifyButton = primaryButton("激活并进入");
        verifyButton.setOnClickListener(v -> activateOfflineLicense(licenseInput, status));
        card.addView(verifyButton, buttonParams());
    }

    private void activateOfflineLicense(EditText input, TextView status) {
        String key = input.getText().toString().trim();
        OfflineLicense.Result result = OfflineLicense.verify(key, getMachineId(), System.currentTimeMillis());
        if (!result.valid) {
            input.setError(result.message);
            status.setText(result.message);
            status.setTextColor(RED);
            return;
        }
        preferences.edit()
                .putString(PREF_ACTIVE_KEY, key)
                .putLong(PREF_LICENSE_EXPIRES_AT, result.expiresAtMillis)
                .putBoolean(PREF_LICENSE_PERMANENT, result.permanent)
                .putString(PREF_LICENSE_TYPE, result.typeLabel)
                .apply();
        Toast.makeText(this, "激活成功", Toast.LENGTH_SHORT).show();
        requestNotificationPermissionIfNeeded();
        buildShell(TAB_QUIZ);
    }

    private OfflineLicense.Result readActiveLicense() {
        return OfflineLicense.verify(preferences.getString(PREF_ACTIVE_KEY, ""),
                getMachineId(), System.currentTimeMillis());
    }

    private boolean isOfflineLicenseValid() {
        return readActiveLicense().valid;
    }

    private String getMachineId() {
        String androidId = Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);
        if (androidId == null || androidId.trim().isEmpty()) androidId = "unknown-device";
        String source = androidId + "|" + getPackageName();
        String raw;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(source.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder();
            for (byte value : bytes) builder.append(String.format(Locale.US, "%02X", value & 0xff));
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
        OfflineLicense.Result result = readActiveLicense();
        if (!result.valid) {
            countdownView.setText("卡密未激活");
            countdownView.setTextColor(RED);
            countdownView.setBackground(roundRect(Color.rgb(255, 236, 236), 15));
            return;
        }
        if (result.permanent) {
            countdownView.setText("卡密：永久有效");
            countdownView.setTextColor(GREEN);
            countdownView.setBackground(roundRect(Color.rgb(237, 249, 242), 15));
            return;
        }
        startLicenseCountdown(result.expiresAtMillis);
    }

    private void startLicenseCountdown(long expiresAtMillis) {
        stopLicenseTimer();
        long remaining = expiresAtMillis - System.currentTimeMillis();
        if (remaining <= 0L) {
            countdownView.setText("卡密已到期");
            return;
        }
        licenseTimer = new CountDownTimer(remaining, 1000L) {
            @Override public void onTick(long millisUntilFinished) { updateCountdownText(millisUntilFinished); }
            @Override public void onFinish() {
                if (countdownView != null) countdownView.setText("卡密已到期");
                if (currentTab == TAB_QUIZ) buildShell(TAB_LICENSE);
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
        countdownView.setTextColor(remainingMs <= 86400000L ? Color.rgb(190, 92, 28) : GREEN);
        countdownView.setBackground(roundRect(remainingMs <= 86400000L
                ? Color.rgb(255, 244, 229) : Color.rgb(237, 249, 242), 15));
    }

    private void stopLicenseTimer() {
        if (licenseTimer != null) {
            licenseTimer.cancel();
            licenseTimer = null;
        }
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFICATION);
        }
    }

    @Override protected void onDestroy() {
        stopLicenseTimer();
        super.onDestroy();
    }

    private LinearLayout verticalCard(int color, int radiusDp) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(roundRect(color, radiusDp));
        card.setElevation(dp(2));
        return card;
    }

    private TextView text(String value, int size, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        return view;
    }

    private Button primaryButton(String value) {
        Button button = new Button(this);
        button.setText(value);
        button.setTextSize(16);
        button.setTextColor(Color.WHITE);
        button.setAllCaps(false);
        button.setBackground(roundRect(PURPLE, 14));
        return button;
    }

    private Button secondaryButton(String value) {
        Button button = new Button(this);
        button.setText(value);
        button.setTextSize(15);
        button.setTextColor(PURPLE);
        button.setAllCaps(false);
        button.setBackground(roundStroke(Color.WHITE, Color.rgb(174, 159, 212), 14));
        return button;
    }

    private LinearLayout.LayoutParams fullWidth() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams cardParams() {
        LinearLayout.LayoutParams params = fullWidth();
        params.setMargins(0, dp(5), 0, dp(6));
        return params;
    }

    private LinearLayout.LayoutParams buttonParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(52));
        params.setMargins(0, dp(5), 0, dp(5));
        return params;
    }

    private LinearLayout.LayoutParams compactButtonParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(46));
        params.setMargins(0, dp(3), 0, dp(3));
        return params;
    }

    private LinearLayout.LayoutParams licenseInputParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(54));
        params.setMargins(0, dp(14), 0, dp(4));
        return params;
    }

    private LinearLayout.LayoutParams withMargins(LinearLayout.LayoutParams source, int margin) {
        source.setMargins(margin, margin, margin, margin);
        return source;
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
