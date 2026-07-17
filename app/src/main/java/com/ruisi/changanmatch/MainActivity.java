package com.ruisi.changanmatch;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
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
import android.widget.LinearLayout;
import android.widget.NumberPicker;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {
    private static final int REQ_CAPTURE = 1001;
    private static final int REQ_NOTIFICATION = 1002;

    private MediaProjectionManager projectionManager;
    private SharedPreferences preferences;
    private TextView permissionState;
    private NumberPicker rowsPicker;
    private NumberPicker columnsPicker;
    private NumberPicker kindsPicker;
    private CheckBox autoMode;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        projectionManager = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        preferences = getSharedPreferences("match3_settings", MODE_PRIVATE);
        buildUi();
        requestNotificationPermissionIfNeeded();
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(24), dp(18), dp(24), dp(28));
        scroll.addView(root);

        TextView title = text("长安幻想·宴会消消乐助手", 27, Color.rgb(66, 40, 116));
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, 0, 0, dp(8));
        root.addView(title, fullWidth());

        TextView intro = text(
                "在手机本地识别宴会消消乐棋盘，计算最佳相邻交换。默认只提示；开启自动模式后，通过安卓无障碍服务执行滑动。\n\n首次使用：授权悬浮窗 → 开启无障碍 → 设置棋盘行列 → 开始识别 → 切回游戏后点击浮窗“标定”。",
                16, Color.DKGRAY);
        intro.setLineSpacing(0, 1.18f);
        intro.setPadding(dp(18), dp(14), dp(18), dp(14));
        intro.setBackground(roundRect(Color.rgb(248, 246, 255), 16));
        root.addView(intro, withMargins(fullWidth(), dp(6)));

        permissionState = text("", 15, Color.DKGRAY);
        permissionState.setPadding(0, dp(10), 0, dp(8));
        root.addView(permissionState, fullWidth());

        Button overlayButton = primaryButton("① 授权悬浮窗");
        overlayButton.setOnClickListener(v -> openOverlaySettings());
        root.addView(overlayButton, buttonParams());

        Button accessibilityButton = secondaryButton("② 开启自动滑动权限");
        accessibilityButton.setOnClickListener(v ->
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        root.addView(accessibilityButton, buttonParams());

        TextView settingsTitle = text("棋盘参数", 20, Color.rgb(66, 40, 116));
        settingsTitle.setPadding(0, dp(17), 0, dp(5));
        root.addView(settingsTitle, fullWidth());

        LinearLayout pickers = new LinearLayout(this);
        pickers.setOrientation(LinearLayout.HORIZONTAL);
        pickers.setGravity(Gravity.CENTER);
        rowsPicker = addPicker(pickers, "行数", 4, 10, preferences.getInt("rows", 8));
        columnsPicker = addPicker(pickers, "列数", 4, 10, preferences.getInt("columns", 7));
        kindsPicker = addPicker(pickers, "图标种类", 4, 9, preferences.getInt("kinds", 5));
        root.addView(pickers, fullWidth());

        autoMode = new CheckBox(this);
        autoMode.setText("开启自动滑动（关闭时只显示建议步骤）");
        autoMode.setTextSize(16);
        autoMode.setChecked(preferences.getBoolean("auto_mode", false));
        autoMode.setPadding(0, dp(8), 0, dp(6));
        root.addView(autoMode, fullWidth());

        Button startButton = primaryButton("③ 开始识别棋盘");
        startButton.setOnClickListener(v -> startCaptureRequest());
        root.addView(startButton, buttonParams());

        Button stopButton = secondaryButton("停止运行");
        stopButton.setOnClickListener(v -> {
            Intent stop = new Intent(this, ScreenCaptureService.class);
            stop.setAction(ScreenCaptureService.ACTION_STOP);
            startService(stop);
            Toast.makeText(this, "已发送停止指令", Toast.LENGTH_SHORT).show();
        });
        root.addView(stopButton, buttonParams());

        Button resetButton = secondaryButton("重置棋盘标定");
        resetButton.setOnClickListener(v -> {
            preferences.edit()
                    .remove("board_left").remove("board_top")
                    .remove("board_right").remove("board_bottom").apply();
            Toast.makeText(this, "已重置，开始后请重新标定", Toast.LENGTH_SHORT).show();
        });
        root.addView(resetButton, buttonParams());

        TextView note = text(
                "已按宴会消消乐真实画面预设为 8 行×7 列、5 类图标。建议先关闭自动模式测试；粉红切片、数字和闪光属于消除动画，助手会等待画面稳定后再计算。所有识别均在本机完成。",
                14, Color.rgb(90, 90, 90));
        note.setPadding(dp(14), dp(12), dp(14), dp(12));
        note.setBackground(roundRect(Color.rgb(245, 245, 245), 13));
        root.addView(note, withMargins(fullWidth(), dp(12)));

        setContentView(scroll);
    }

    private NumberPicker addPicker(LinearLayout parent, String label, int min, int max, int value) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        TextView caption = text(label, 14, Color.DKGRAY);
        caption.setGravity(Gravity.CENTER);
        box.addView(caption, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        NumberPicker picker = new NumberPicker(this);
        picker.setMinValue(min);
        picker.setMaxValue(max);
        picker.setValue(Math.max(min, Math.min(max, value)));
        box.addView(picker, new LinearLayout.LayoutParams(dp(108), dp(104)));
        parent.addView(box, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        return picker;
    }

    @Override
    protected void onResume() {
        super.onResume();
        updatePermissionState();
    }

    private void updatePermissionState() {
        boolean overlay = Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this);
        boolean accessibility = AutomationAccessibilityService.isReady();
        permissionState.setText("悬浮窗：" + (overlay ? "已授权" : "未授权") +
                "    自动滑动：" + (accessibility ? "已开启" : "未开启"));
        permissionState.setTextColor(overlay && accessibility
                ? Color.rgb(20, 125, 72) : Color.rgb(190, 80, 35));
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
        service.putExtra(ScreenCaptureService.EXTRA_AUTO_MODE, autoMode.isChecked());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(service);
        else startService(service);
        Toast.makeText(this, "已启动，请切回游戏并点击浮窗“标定”", Toast.LENGTH_LONG).show();
        moveTaskToBack(true);
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFICATION);
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
        button.setTextSize(17);
        button.setTextColor(Color.WHITE);
        button.setAllCaps(false);
        button.setBackground(roundRect(Color.rgb(93, 60, 180), 14));
        return button;
    }

    private Button secondaryButton(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextSize(16);
        button.setTextColor(Color.rgb(93, 60, 180));
        button.setAllCaps(false);
        button.setBackground(roundStroke(Color.WHITE, Color.rgb(93, 60, 180), 14));
        return button;
    }

    private LinearLayout.LayoutParams fullWidth() {
        return new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams buttonParams() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(54));
        p.setMargins(0, dp(6), 0, dp(6));
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
