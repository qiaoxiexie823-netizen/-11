package com.ruisi.changanmatch;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public class QuizActivity extends Activity {
    private static final int REQ_CAPTURE = 2101;
    private static final int REQ_NOTIFICATION = 2102;

    private static final int PURPLE = Color.rgb(91, 61, 170);
    private static final int PURPLE_DARK = Color.rgb(61, 41, 112);
    private static final int PAGE_BG = Color.rgb(247, 245, 252);
    private static final int TEXT_DARK = Color.rgb(55, 54, 64);
    private static final int TEXT_MUTED = Color.rgb(112, 108, 124);
    private static final int GREEN = Color.rgb(24, 125, 76);

    private MediaProjectionManager projectionManager;
    private QuestionBank questionBank;
    private CheckBox centerOnly;
    private CheckBox autoClick;
    private TextView permissionState;
    private TextView manualResult;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        projectionManager = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        try {
            questionBank = new QuestionBank(this);
        } catch (Exception error) {
            new AlertDialog.Builder(this)
                    .setTitle("题库加载失败")
                    .setMessage("内置题库文件无法读取，请重新安装最新版深情助手。")
                    .setPositiveButton("返回", (dialog, which) -> finish())
                    .setCancelable(false)
                    .show();
            return;
        }
        buildUi();
        requestNotificationPermissionIfNeeded();
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(PAGE_BG);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(16), dp(16), dp(22));
        scroll.addView(root);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        root.addView(header, fullWidth());

        Button back = secondaryButton("返回");
        back.setOnClickListener(v -> finish());
        header.addView(back, new LinearLayout.LayoutParams(dp(78), dp(44)));

        TextView title = text("长安题库答题器", 23, PURPLE_DARK);
        title.setGravity(Gravity.CENTER);
        header.addView(title, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView count = text("题库已载入 " + questionBank.size() + " 道题", 13, GREEN);
        count.setGravity(Gravity.CENTER);
        count.setPadding(dp(10), dp(6), dp(10), dp(6));
        count.setBackground(roundRect(Color.rgb(237, 249, 242), 15));
        LinearLayout.LayoutParams countParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        countParams.gravity = Gravity.CENTER_HORIZONTAL;
        countParams.setMargins(0, dp(12), 0, dp(8));
        root.addView(count, countParams);

        LinearLayout captureCard = card();
        captureCard.setPadding(dp(16), dp(16), dp(16), dp(16));
        root.addView(captureCard, cardParams());

        captureCard.addView(text("屏幕识题与自动点击", 19, PURPLE_DARK), fullWidth());
        TextView intro = text(
                "启动后切回游戏，助手会自动识别题目并显示答案。开启自动点击且授权无障碍后，会定位包含正确答案的选项文字并点击；关闭时只显示答案。",
                14, TEXT_MUTED);
        intro.setLineSpacing(0, 1.18f);
        intro.setPadding(0, dp(8), 0, dp(10));
        captureCard.addView(intro, fullWidth());

        permissionState = text("", 14, TEXT_MUTED);
        permissionState.setPadding(0, dp(2), 0, dp(7));
        captureCard.addView(permissionState, fullWidth());

        Button overlayButton = primaryButton("① 授权悬浮窗");
        overlayButton.setOnClickListener(v -> openOverlaySettings());
        captureCard.addView(overlayButton, buttonParams());

        Button accessibilityButton = secondaryButton("② 开启自动点击权限");
        accessibilityButton.setOnClickListener(v ->
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        captureCard.addView(accessibilityButton, buttonParams());

        centerOnly = new CheckBox(this);
        centerOnly.setText("仅识别屏幕中间区域（推荐，速度更快）");
        centerOnly.setTextSize(15);
        centerOnly.setTextColor(TEXT_DARK);
        centerOnly.setChecked(getSharedPreferences("quiz_settings", MODE_PRIVATE)
                .getBoolean("centerOnly", true));
        centerOnly.setOnCheckedChangeListener((button, checked) ->
                getSharedPreferences("quiz_settings", MODE_PRIVATE)
                        .edit().putBoolean("centerOnly", checked).apply());
        centerOnly.setPadding(0, dp(6), 0, dp(3));
        captureCard.addView(centerOnly, fullWidth());

        autoClick = new CheckBox(this);
        autoClick.setText("识别正确答案后自动点击选项");
        autoClick.setTextSize(15);
        autoClick.setTextColor(TEXT_DARK);
        autoClick.setChecked(getSharedPreferences("quiz_settings", MODE_PRIVATE)
                .getBoolean("autoClick", false));
        autoClick.setOnCheckedChangeListener((button, checked) -> {
            getSharedPreferences("quiz_settings", MODE_PRIVATE)
                    .edit().putBoolean("autoClick", checked).apply();
            updatePermissionState();
        });
        autoClick.setPadding(0, dp(3), 0, dp(4));
        captureCard.addView(autoClick, fullWidth());

        Button startButton = primaryButton("③ 开始屏幕识题");
        startButton.setOnClickListener(v -> startCaptureRequest());
        captureCard.addView(startButton, buttonParams());

        Button stopButton = secondaryButton("停止识题与自动点击");
        stopButton.setOnClickListener(v -> {
            Intent stop = new Intent(this, QuizScreenCaptureService.class);
            stop.setAction(QuizScreenCaptureService.ACTION_STOP);
            startService(stop);
            Toast.makeText(this, "已停止识题", Toast.LENGTH_SHORT).show();
        });
        captureCard.addView(stopButton, buttonParams());

        TextView safetyNote = text(
                "自动点击只会在题库匹配成功且屏幕中识别到答案文字时执行；匹配不确定或找不到答案选项时不会点击。",
                12, Color.rgb(135, 96, 35));
        safetyNote.setLineSpacing(0, 1.15f);
        safetyNote.setPadding(dp(12), dp(10), dp(12), dp(10));
        safetyNote.setBackground(roundRect(Color.rgb(255, 247, 232), 13));
        captureCard.addView(safetyNote, fullWidth());

        LinearLayout manualCard = card();
        manualCard.setPadding(dp(16), dp(16), dp(16), dp(16));
        root.addView(manualCard, cardParams());
        manualCard.addView(text("手动查题测试", 19, PURPLE_DARK), fullWidth());

        EditText manualInput = new EditText(this);
        manualInput.setHint("输入题目或关键词，例如：浪里白条");
        manualInput.setTextSize(16);
        manualInput.setMinLines(2);
        manualInput.setPadding(dp(14), dp(10), dp(14), dp(10));
        manualInput.setBackground(roundStroke(Color.rgb(252, 251, 255),
                Color.rgb(188, 177, 220), 14));
        manualCard.addView(manualInput, inputParams());

        Button queryButton = secondaryButton("在题库中查答案");
        queryButton.setOnClickListener(v -> {
            String input = manualInput.getText().toString().trim();
            if (input.isEmpty()) {
                Toast.makeText(this, "请先输入题目", Toast.LENGTH_SHORT).show();
                return;
            }
            QuestionBank.Match match = questionBank.findBest(input);
            if (match == null) {
                manualResult.setText("未找到足够相近的题目");
                manualResult.setTextColor(Color.rgb(190, 45, 45));
            } else {
                manualResult.setText("答案：" + match.answer + "\n\n题目：" +
                        match.question + "\n匹配度：" + Math.round(match.score * 100) + "%");
                manualResult.setTextColor(TEXT_DARK);
            }
        });
        manualCard.addView(queryButton, buttonParams());

        manualResult = text("可先输入“浪里白条”测试题库是否正常", 15, TEXT_MUTED);
        manualResult.setLineSpacing(0, 1.16f);
        manualResult.setTextIsSelectable(true);
        manualResult.setPadding(dp(14), dp(12), dp(14), dp(12));
        manualResult.setBackground(roundRect(Color.rgb(246, 243, 252), 14));
        manualCard.addView(manualResult, fullWidth());

        TextView maker = text("深情制作", 14, PURPLE);
        maker.setGravity(Gravity.CENTER);
        maker.setPadding(0, dp(12), 0, 0);
        root.addView(maker, fullWidth());
        setContentView(scroll);
    }

    @Override
    protected void onResume() {
        super.onResume();
        updatePermissionState();
    }

    private void updatePermissionState() {
        if (permissionState == null) return;
        boolean overlay = Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
                Settings.canDrawOverlays(this);
        boolean accessibility = AutomationAccessibilityService.isReady();
        boolean wantsAutoClick = autoClick != null && autoClick.isChecked();
        permissionState.setText("悬浮窗：" + (overlay ? "已授权" : "未授权") +
                "    自动点击：" + (accessibility ? "已开启" : "未开启"));
        permissionState.setTextColor(overlay && (!wantsAutoClick || accessibility)
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            new AlertDialog.Builder(this)
                    .setTitle("需要悬浮窗权限")
                    .setMessage("请先允许深情助手显示在其他应用上层，才能在游戏界面显示答案。")
                    .setPositiveButton("去授权", (dialog, which) -> openOverlaySettings())
                    .setNegativeButton("取消", null)
                    .show();
            return;
        }
        if (autoClick.isChecked() && !AutomationAccessibilityService.isReady()) {
            new AlertDialog.Builder(this)
                    .setTitle("需要自动点击权限")
                    .setMessage("请在无障碍设置中开启“宴会消消乐自动滑动”。该权限同时用于答题器点击正确选项。")
                    .setPositiveButton("去开启", (dialog, which) ->
                            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)))
                    .setNegativeButton("取消", null)
                    .show();
            return;
        }
        startActivityForResult(projectionManager.createScreenCaptureIntent(), REQ_CAPTURE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_CAPTURE) return;
        if (resultCode != RESULT_OK || data == null) {
            Toast.makeText(this, "未获得屏幕录制权限", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent service = new Intent(this, QuizScreenCaptureService.class);
        service.setAction(QuizScreenCaptureService.ACTION_START);
        service.putExtra(QuizScreenCaptureService.EXTRA_RESULT_CODE, resultCode);
        service.putExtra(QuizScreenCaptureService.EXTRA_RESULT_DATA, data);
        service.putExtra(QuizScreenCaptureService.EXTRA_CENTER_ONLY, centerOnly.isChecked());
        service.putExtra(QuizScreenCaptureService.EXTRA_AUTO_CLICK, autoClick.isChecked());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(service);
        } else {
            startService(service);
        }
        Toast.makeText(this,
                autoClick.isChecked() ? "识题与自动点击已启动，请切回游戏" : "识题已启动，请切回游戏",
                Toast.LENGTH_LONG).show();
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

    private LinearLayout card() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(roundRect(Color.WHITE, 18));
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
        button.setBackground(roundStroke(Color.WHITE,
                Color.rgb(174, 159, 212), 14));
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

    private LinearLayout.LayoutParams inputParams() {
        LinearLayout.LayoutParams params = fullWidth();
        params.setMargins(0, dp(9), 0, dp(5));
        return params;
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
