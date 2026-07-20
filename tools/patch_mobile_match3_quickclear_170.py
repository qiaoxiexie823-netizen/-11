from pathlib import Path

MAIN = Path("app/src/main/java/com/ruisi/changanmatch/MainActivity.java")
CAPTURE = Path("app/src/main/java/com/ruisi/changanmatch/ScreenCaptureService.java")
ACCESS = Path("app/src/main/java/com/ruisi/changanmatch/AutomationAccessibilityService.java")


def patch_main() -> None:
    text = MAIN.read_text(encoding="utf-8").replace("\r\n", "\n")
    if "一键应用视频快消预设" in text:
        return

    old_options = '''        String[] speedOptions = {
                "稳定（1.5～2.0秒）",
                "快速（0.8～1.2秒）",
                "极速（0.5～0.8秒）"
        };'''
    new_options = '''        String[] speedOptions = {
                "稳定（1.5～2.0秒）",
                "快速（0.8～1.2秒）",
                "极速（0.5～0.8秒）",
                "快消（约0.3～0.5秒）"
        };'''
    if old_options not in text:
        raise RuntimeError("speedOptions block not found")
    text = text.replace(old_options, new_options, 1)

    old_selection = '''        speedSpinner.setSelection(Math.max(0, Math.min(2,
                preferences.getInt("speed_mode", 0))));'''
    new_selection = '''        speedSpinner.setSelection(Math.max(0, Math.min(3,
                preferences.getInt("speed_mode", 0))));'''
    if old_selection not in text:
        raise RuntimeError("speed selection block not found")
    text = text.replace(old_selection, new_selection, 1)

    marker = '''        settingsCard.addView(autoMode, fullWidth());
'''
    insertion = '''        settingsCard.addView(autoMode, fullWidth());

        Button quickPresetButton = secondaryButton("一键应用视频快消预设");
        quickPresetButton.setOnClickListener(v -> {
            rowsPicker.setValue(8);
            columnsPicker.setValue(7);
            kindsPicker.setValue(5);
            speedSpinner.setSelection(3);
            autoMode.setChecked(true);
            preferences.edit()
                    .putInt("rows", 8)
                    .putInt("columns", 7)
                    .putInt("kinds", 5)
                    .putInt("speed_mode", 3)
                    .putBoolean("auto_mode", true)
                    .putFloat("board_left", 0.010f)
                    .putFloat("board_top", 0.315f)
                    .putFloat("board_right", 0.990f)
                    .putFloat("board_bottom", 0.815f)
                    .apply();
            Toast.makeText(this,
                    "已应用：8×7、5类图标、视频棋盘范围和快消速度",
                    Toast.LENGTH_LONG).show();
        });
        settingsCard.addView(quickPresetButton, buttonParams());
'''
    if marker not in text:
        raise RuntimeError("auto mode insertion marker not found")
    text = text.replace(marker, insertion, 1)

    old_note = '''                "默认参数为 8 行×7 列、5 类图标。建议第一次关闭自动模式测试；粉红切片、数字和闪光属于消除动画，助手会等待画面稳定后再计算。",'''
    new_note = '''                "视频同款棋盘为 8 行×7 列、5 类图标。点击“一键应用视频快消预设”后可直接启动；粉红切片、数字、连击文字和闪光属于动画，助手会等待画面稳定后再滑动。",'''
    if old_note not in text:
        raise RuntimeError("note text not found")
    text = text.replace(old_note, new_note, 1)
    MAIN.write_text(text, encoding="utf-8", newline="\n")


def patch_capture() -> None:
    text = CAPTURE.read_text(encoding="utf-8").replace("\r\n", "\n")
    if "speedLabel = \"快消\"" in text:
        return

    text = text.replace(
        "    private static final double STABLE_THRESHOLD = 0.055;\n",
        "    private static final double DEFAULT_STABLE_THRESHOLD = 0.055;\n",
        1,
    )
    text = text.replace(
        "    private int requiredStableFrames = 3;\n",
        "    private int requiredStableFrames = 3;\n"
        "    private double stableThreshold = DEFAULT_STABLE_THRESHOLD;\n",
        1,
    )
    text = text.replace(
        "        speedMode = clamp(intent.getIntExtra(EXTRA_SPEED_MODE, 0), 0, 2);",
        "        speedMode = clamp(intent.getIntExtra(EXTRA_SPEED_MODE, 0), 0, 3);",
        1,
    )
    text = text.replace(
        "        if (change > STABLE_THRESHOLD) {",
        "        if (change > stableThreshold) {",
        1,
    )
    text = text.replace(
        "return new RectF(0.01f, 0.255f, 0.99f, 0.775f);",
        "return new RectF(0.01f, 0.315f, 0.99f, 0.815f);",
        1,
    )
    text = text.replace(
        'preferences.getFloat("board_top", 0.255f)',
        'preferences.getFloat("board_top", 0.315f)',
        1,
    )
    text = text.replace(
        'preferences.getFloat("board_bottom", 0.775f)',
        'preferences.getFloat("board_bottom", 0.815f)',
        1,
    )

    start = text.index("    private void applySpeedMode(int mode) {")
    end = text.index("    private void showControlOverlay() {")
    replacement = '''    private void applySpeedMode(int mode) {
        if (mode == 3) {
            frameIntervalMs = 120;
            actionCooldownMs = 360;
            sameBoardCooldownMs = 1100;
            requiredStableFrames = 2;
            stableThreshold = 0.068;
            speedLabel = "快消";
        } else if (mode == 2) {
            frameIntervalMs = 180;
            actionCooldownMs = 520;
            sameBoardCooldownMs = 2200;
            requiredStableFrames = 2;
            stableThreshold = 0.060;
            speedLabel = "极速";
        } else if (mode == 1) {
            frameIntervalMs = 280;
            actionCooldownMs = 850;
            sameBoardCooldownMs = 3200;
            requiredStableFrames = 2;
            stableThreshold = 0.057;
            speedLabel = "快速";
        } else {
            frameIntervalMs = 450;
            actionCooldownMs = 1400;
            sameBoardCooldownMs = 4500;
            requiredStableFrames = 3;
            stableThreshold = DEFAULT_STABLE_THRESHOLD;
            speedLabel = "稳定";
        }
    }

'''
    text = text[:start] + replacement + text[end:]

    text = text.replace(
        '            autoButton = smallButton(autoMode ? "自动：开" : "自动：关");',
        '            autoButton = smallButton(autoButtonLabel());',
        1,
    )
    text = text.replace(
        '            if (autoButton != null) autoButton.setText(autoMode ? "自动：开" : "自动：关");',
        '            if (autoButton != null) autoButton.setText(autoButtonLabel());',
        1,
    )
    update_marker = '''    private void updateAutoButton() {
'''
    label_method = '''    private String autoButtonLabel() {
        if (speedMode == 3) return autoMode ? "快消：开" : "快消：关";
        return autoMode ? "自动：开" : "自动：关";
    }

'''
    if update_marker not in text:
        raise RuntimeError("updateAutoButton marker not found")
    text = text.replace(update_marker, label_method + update_marker, 1)

    old_swipe = '''        if (AutomationAccessibilityService.performSwipe(startX, startY, endX, endY)) {'''
    new_swipe = '''        long swipeDuration = speedMode == 3 ? 115L : 190L;
        if (AutomationAccessibilityService.performSwipe(
                startX, startY, endX, endY, swipeDuration)) {'''
    if old_swipe not in text:
        raise RuntimeError("performSwipe call not found")
    text = text.replace(old_swipe, new_swipe, 1)

    CAPTURE.write_text(text, encoding="utf-8", newline="\n")


def patch_accessibility() -> None:
    text = ACCESS.read_text(encoding="utf-8").replace("\r\n", "\n")
    if "long durationMs" in text:
        return

    old = '''    public static boolean performSwipe(float startX, float startY, float endX, float endY) {
        AutomationAccessibilityService service = instance;
        if (service == null) return false;
        service.mainHandler.post(() -> {
            Path path = new Path();
            path.moveTo(startX, startY);
            path.lineTo(endX, endY);
            GestureDescription.StrokeDescription stroke =
                    new GestureDescription.StrokeDescription(path, 0, 190);
            GestureDescription gesture = new GestureDescription.Builder()
                    .addStroke(stroke)
                    .build();
            service.dispatchGesture(gesture, null, null);
        });
        return true;
    }
'''
    new = '''    public static boolean performSwipe(float startX, float startY, float endX, float endY) {
        return performSwipe(startX, startY, endX, endY, 190L);
    }

    public static boolean performSwipe(float startX, float startY,
                                       float endX, float endY, long durationMs) {
        AutomationAccessibilityService service = instance;
        if (service == null) return false;
        long safeDuration = Math.max(90L, Math.min(350L, durationMs));
        service.mainHandler.post(() -> {
            Path path = new Path();
            path.moveTo(startX, startY);
            path.lineTo(endX, endY);
            GestureDescription.StrokeDescription stroke =
                    new GestureDescription.StrokeDescription(path, 0, safeDuration);
            GestureDescription gesture = new GestureDescription.Builder()
                    .addStroke(stroke)
                    .build();
            service.dispatchGesture(gesture, null, null);
        });
        return true;
    }
'''
    if old not in text:
        raise RuntimeError("performSwipe method not found")
    ACCESS.write_text(text.replace(old, new, 1), encoding="utf-8", newline="\n")


def main() -> None:
    patch_main()
    patch_capture()
    patch_accessibility()
    print("Applied mobile match3 quick-clear patch 1.7.0")


if __name__ == "__main__":
    main()
