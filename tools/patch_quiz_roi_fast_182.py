from pathlib import Path

ROOT = Path("app/src/main/java/com/ruisi/changanmatch")
SERVICE = ROOT / "QuizScreenCaptureService.java"
ACTIVITY = ROOT / "QuizActivity.java"
MANIFEST = Path("app/src/main/AndroidManifest.xml")
REGION_SERVICE = ROOT / "QuizRegionOverlayService.java"


def replace_between(text: str, start_marker: str, end_marker: str, replacement: str) -> str:
    start = text.index(start_marker)
    end = text.index(end_marker, start)
    return text[:start] + replacement + text[end:]


def patch_capture_service() -> None:
    text = SERVICE.read_text(encoding="utf-8").replace("\r\n", "\n")
    if "QUIZ_ROI_FAST_V182" in text:
        return

    field_marker = '    private static final String QUIZ_SCOPE_CLICK_V181 = "quiz-page-only-portrait-click";\n'
    if field_marker not in text:
        raise RuntimeError("1.8.1 patch must run before 1.8.2")
    text = text.replace(
        field_marker,
        field_marker
        + '    private static final String QUIZ_ROI_FAST_V182 = "adjustable-roi-fast-recognition";\n'
        + '    private static final long QUIZ_SESSION_GRACE_MS = 2600L;\n'
        + '    private float roiLeftRatio = 0.04f;\n'
        + '    private float roiTopRatio = 0.06f;\n'
        + '    private float roiRightRatio = 0.98f;\n'
        + '    private float roiBottomRatio = 0.78f;\n'
        + '    private int ocrCropLeft;\n'
        + '    private int ocrCropWidth;\n'
        + '    private boolean quizSessionActive;\n'
        + '    private long lastQuizEvidenceAt;\n'
        + '    private final float[] lastOptionCenterY = new float[]{0f, 0f, 0f, 0f};\n',
        1,
    )

    text = text.replace("private static final long OCR_INTERVAL_MS = 420L;",
                        "private static final long OCR_INTERVAL_MS = 280L;")
    text = text.replace("private static final int OCR_MAX_WIDTH = 1080;",
                        "private static final int OCR_MAX_WIDTH = 960;")

    start_marker = "        centerOnly = intent.getBooleanExtra(EXTRA_CENTER_ONLY, true);\n"
    if start_marker not in text:
        raise RuntimeError("onStart settings marker not found")
    text = text.replace(
        start_marker,
        "        centerOnly = true;\n"
        "        loadRecognitionRegion();\n",
        1,
    )

    prepare_start = "    private Bitmap prepareBitmap(Bitmap source) {"
    prepare_end = "    private void handleOcrResult(Text result, Bitmap frame) {"
    prepare_replacement = r'''    private void loadRecognitionRegion() {
        android.content.SharedPreferences prefs =
                getSharedPreferences("quiz_settings", MODE_PRIVATE);
        roiLeftRatio = clamp(prefs.getFloat("roiLeft", 0.04f), 0f, 0.80f);
        roiTopRatio = clamp(prefs.getFloat("roiTop", 0.06f), 0f, 0.80f);
        roiRightRatio = clamp(prefs.getFloat("roiRight", 0.98f),
                roiLeftRatio + 0.20f, 1f);
        roiBottomRatio = clamp(prefs.getFloat("roiBottom", 0.78f),
                roiTopRatio + 0.20f, 1f);
    }

    private Bitmap prepareBitmap(Bitmap source) {
        int sourceWidth = source.getWidth();
        int sourceHeight = source.getHeight();
        int left = Math.round(sourceWidth * roiLeftRatio);
        int top = Math.round(sourceHeight * roiTopRatio);
        int right = Math.round(sourceWidth * roiRightRatio);
        int bottom = Math.round(sourceHeight * roiBottomRatio);

        left = Math.max(0, Math.min(left, sourceWidth - 2));
        top = Math.max(0, Math.min(top, sourceHeight - 2));
        right = Math.max(left + 2, Math.min(right, sourceWidth));
        bottom = Math.max(top + 2, Math.min(bottom, sourceHeight));

        ocrCropLeft = left;
        ocrCropTop = top;
        ocrCropWidth = right - left;
        Bitmap cropped = Bitmap.createBitmap(source, left, top,
                Math.max(1, right - left), Math.max(1, bottom - top));
        if (cropped.getWidth() <= OCR_MAX_WIDTH) {
            ocrScale = 1f;
            ocrBitmapHeight = cropped.getHeight();
            return cropped;
        }
        ocrScale = OCR_MAX_WIDTH / (float) cropped.getWidth();
        int targetHeight = Math.max(1, Math.round(cropped.getHeight() * ocrScale));
        Bitmap scaled = Bitmap.createScaledBitmap(
                cropped, OCR_MAX_WIDTH, targetHeight, true);
        ocrBitmapHeight = scaled.getHeight();
        if (scaled != cropped) cropped.recycle();
        return scaled;
    }

'''
    text = replace_between(text, prepare_start, prepare_end, prepare_replacement)

    handle_start = "    private void handleOcrResult(Text result, Bitmap frame) {"
    handle_end = "    private boolean tryAutoPageAction(Text result) {"
    handle_replacement = r'''    private void handleOcrResult(Text result, Bitmap frame) {
        List<String> lines = new ArrayList<>();
        for (Text.TextBlock block : result.getTextBlocks()) {
            for (Text.Line line : block.getLines()) {
                String value = line.getText().trim();
                if (!value.isEmpty()) lines.add(value);
            }
        }

        // 恢复上一版的整块 OCR 文本 + 多行片段题库匹配，不再要求每帧都完整识别页头。
        QuestionBank.Match match = questionBank.findBest(lines, result.getText());
        boolean quizPage = updateQuizSession(result, match);
        if (!quizPage) {
            if (!quizSessionActive) clearQuizQuestionState();
            updateOverlay(autoClick
                    ? "等待进入科举答题页…\n自动点击已开启"
                    : "等待进入科举答题页…\n长按悬浮窗停止");
            return;
        }

        // 错题学习也被同一科举页面会话限制，离开页面不会收录任何内容。
        if (!autoClick && !lastQuestion.isEmpty()) {
            tryLearnManualCorrection(result, frame);
        }

        if (match == null) {
            consecutiveMisses++;
            if (consecutiveMisses >= 2) {
                updateOverlay(autoClick
                        ? "科举题目识别中…\n自动点击已开启"
                        : "科举题目识别中…\n长按悬浮窗停止");
            }
            return;
        }

        consecutiveMisses = 0;
        captureOptionTexts(result);
        if (!match.question.equals(lastQuestion)) {
            lastQuestion = match.question;
            lastShownAnswer = match.answer;
            lastClickedQuestion = "";
            pendingClickQuestion = "";
            updateOverlay("答案：" + match.answer +
                    "\n匹配 " + Math.round(match.score * 100) + "%" +
                    (autoClick ? " · 等待点击" : ""));
        }

        if (autoClick) tryAutoClickAnswer(result, match);
    }

    private boolean updateQuizSession(Text result, QuestionBank.Match match) {
        long now = System.currentTimeMillis();
        String raw = result == null || result.getText() == null ? "" : result.getText();
        String compact = QuestionBank.normalize(raw.replace("／", "/"));
        boolean strongMarker = compact.contains("科举闯关") ||
                compact.contains("第1/2题") || compact.contains("第2/2题") ||
                compact.contains("第12题") || compact.contains("第22题");
        int evidence = 0;
        if (compact.contains("得分")) evidence++;
        if (compact.contains("已答对")) evidence++;
        if (compact.contains("答题者")) evidence++;
        if (compact.contains("文学知识") || compact.contains("历史知识") ||
                compact.contains("地理知识") || compact.contains("游戏知识")) evidence++;
        int optionCount = countLikelyOptionLines(result);

        boolean entryEvidence = strongMarker ||
                (match != null && evidence >= 2 && optionCount >= 2);
        boolean continuationEvidence = quizSessionActive && match != null && optionCount >= 2;
        if (entryEvidence || continuationEvidence) {
            quizSessionActive = true;
            lastQuizEvidenceAt = now;
            return true;
        }
        if (quizSessionActive && now - lastQuizEvidenceAt <= QUIZ_SESSION_GRACE_MS) {
            return true;
        }
        quizSessionActive = false;
        return false;
    }

    private boolean isQuizQuestionPage(Text result) {
        return updateQuizSession(result, null);
    }

    private int countLikelyOptionLines(Text result) {
        if (result == null) return 0;
        List<Float> centers = new ArrayList<>();
        for (Text.TextBlock block : result.getTextBlocks()) {
            for (Text.Line line : block.getLines()) {
                Rect box = line.getBoundingBox();
                if (box == null || box.centerY() < ocrBitmapHeight * 0.43f) continue;
                String value = QuestionBank.normalize(line.getText());
                if (value.isEmpty() || value.length() > 32 || isMetaLine(value)) continue;
                float y = box.centerY();
                boolean duplicate = false;
                for (Float existing : centers) {
                    if (Math.abs(existing - y) < Math.max(8f, ocrBitmapHeight * 0.025f)) {
                        duplicate = true;
                        break;
                    }
                }
                if (!duplicate) centers.add(y);
            }
        }
        return Math.min(4, centers.size());
    }

    private boolean isMetaLine(String value) {
        return value.contains("科举闯关") || value.contains("答题者") ||
                value.contains("已答对") || value.contains("得分") ||
                value.contains("第1/2题") || value.contains("第2/2题") ||
                value.contains("倒计时") || value.contains("下一关") ||
                value.contains("开始答题") || value.contains("排名前");
    }

    private void clearQuizQuestionState() {
        consecutiveMisses = 0;
        lastQuestion = "";
        lastShownAnswer = "";
        lastClickedQuestion = "";
        pendingClickQuestion = "";
        pendingPageAction = "";
        for (int i = 0; i < lastOptionTexts.length; i++) {
            lastOptionTexts[i] = "";
            lastOptionCenterY[i] = 0f;
        }
    }

'''
    text = replace_between(text, handle_start, handle_end, handle_replacement)

    capture_start = "    private void captureOptionTexts(Text result) {"
    capture_end = "    private void tryLearnManualCorrection(Text result, Bitmap frame) {"
    capture_replacement = r'''    private void captureOptionTexts(Text result) {
        class OptionLine {
            final String text;
            final float screenY;
            OptionLine(String text, float screenY) {
                this.text = text;
                this.screenY = screenY;
            }
        }
        List<OptionLine> candidates = new ArrayList<>();
        for (Text.TextBlock block : result.getTextBlocks()) {
            for (Text.Line line : block.getLines()) {
                Rect box = line.getBoundingBox();
                if (box == null || box.centerY() < ocrBitmapHeight * 0.43f) continue;
                String value = LearnedQuestionStore.cleanAnswer(line.getText());
                String normalized = QuestionBank.normalize(value);
                if (normalized.isEmpty() || normalized.length() > 32 || isMetaLine(normalized)) continue;
                float screenY = box.centerY() / Math.max(0.01f, ocrScale) + ocrCropTop;
                candidates.add(new OptionLine(value, screenY));
            }
        }
        candidates.sort((a, b) -> Float.compare(a.screenY, b.screenY));
        for (int i = 0; i < lastOptionTexts.length; i++) {
            lastOptionTexts[i] = "";
            lastOptionCenterY[i] = 0f;
        }
        int row = 0;
        for (OptionLine candidate : candidates) {
            if (row >= 4) break;
            if (row > 0 && Math.abs(candidate.screenY - lastOptionCenterY[row - 1]) <
                    screenHeight * 0.025f) {
                if (candidate.text.length() > lastOptionTexts[row - 1].length()) {
                    lastOptionTexts[row - 1] = candidate.text;
                }
                continue;
            }
            lastOptionTexts[row] = candidate.text;
            lastOptionCenterY[row] = candidate.screenY;
            row++;
        }
    }

'''
    text = replace_between(text, capture_start, capture_end, capture_replacement)

    click_start = "    private void tryAutoClickAnswer(Text result, QuestionBank.Match match) {"
    click_end = "    private int findAnswerOptionIndex(String answerText) {"
    click_replacement = r'''    private void tryAutoClickAnswer(Text result, QuestionBank.Match match) {
        if (!quizSessionActive) return;
        if (!AutomationAccessibilityService.isReady()) {
            updateOverlay("答案：" + match.answer + "\n自动点击权限未开启");
            return;
        }
        if (match.score < 0.68) return;

        long now = System.currentTimeMillis();
        if (match.question.equals(lastClickedQuestion) ||
                match.question.equals(pendingClickQuestion) ||
                now - lastClickAt < CLICK_COOLDOWN_MS) {
            return;
        }

        // 使用上一版的答案文字定位，再点击整条选项中央；不依赖固定的四行坐标。
        AnswerTarget target = findAnswerTarget(result, match.answer);
        if (target == null) {
            updateOverlay("答案：" + match.answer + "\n正在定位正确选项…");
            return;
        }
        float detectedY = target.centerY / Math.max(0.01f, ocrScale) + ocrCropTop;
        float screenX = ocrCropLeft + ocrCropWidth * 0.55f;
        float screenY = clamp(detectedY, 1f, Math.max(1f, screenHeight - 1f));
        final String clickQuestion = match.question;
        final String clickAnswer = match.answer;

        pendingClickQuestion = clickQuestion;
        lastClickAt = now;
        updateOverlay("答案：" + clickAnswer + "\n正在点击正确选项…");
        boolean queued = AutomationAccessibilityService.performTap(
                screenX, screenY, success -> mainHandler.post(() -> {
                    if (clickQuestion.equals(pendingClickQuestion)) pendingClickQuestion = "";
                    if (!clickQuestion.equals(lastQuestion)) return;
                    if (success) {
                        lastClickedQuestion = clickQuestion;
                        updateOverlay("答案：" + clickAnswer + "\n已自动点击");
                    } else {
                        lastClickAt = 0L;
                        lastClickedQuestion = "";
                        updateOverlay("答案：" + clickAnswer + "\n点击失败，正在重试…");
                    }
                }));
        if (!queued) {
            pendingClickQuestion = "";
            lastClickAt = 0L;
            updateOverlay("答案：" + clickAnswer + "\n自动点击权限未连接");
        }
    }

'''
    text = replace_between(text, click_start, click_end, click_replacement)

    target_start = "    private AnswerTarget findAnswerTarget(Text result, String answerText) {"
    target_end = "    private double answerLineScore(String line, String answer) {"
    target_replacement = r'''    private AnswerTarget findAnswerTarget(Text result, String answerText) {
        String[] answers = answerText.split("\\s*/\\s*");
        AnswerTarget best = null;
        double bestScore = 0.0;
        for (Text.TextBlock block : result.getTextBlocks()) {
            for (Text.Line line : block.getLines()) {
                Rect box = line.getBoundingBox();
                if (box == null || box.centerY() < ocrBitmapHeight * 0.43f) continue;
                String lineValue = QuestionBank.normalize(line.getText());
                if (lineValue.isEmpty() || lineValue.length() > 32 || isMetaLine(lineValue)) continue;
                for (String answer : answers) {
                    String normalizedAnswer = QuestionBank.normalize(answer);
                    if (normalizedAnswer.isEmpty()) continue;
                    double score = answerLineScore(lineValue, normalizedAnswer);
                    if (score > bestScore) {
                        bestScore = score;
                        best = new AnswerTarget(line.getText(), box.centerX(), box.centerY());
                    }
                }
            }
        }
        return bestScore >= 0.78 ? best : null;
    }

'''
    text = replace_between(text, target_start, target_end, target_replacement)

    # Dynamic color sampling for wrong-answer learning.
    color_start = "    private double optionColorRatio(Bitmap frame, int row, boolean green) {"
    color_end = "    private void tryAutoClickAnswer(Text result, QuestionBank.Match match) {"
    color_method = r'''    private double optionColorRatio(Bitmap frame, int row, boolean green) {
        if (frame == null || frame.isRecycled() || row < 0 || row >= 4 ||
                lastOptionCenterY[row] <= 0f) return 0.0;
        int centerX = Math.round((ocrCropLeft + ocrCropWidth * 0.55f - ocrCropLeft) * ocrScale);
        int centerY = Math.round((lastOptionCenterY[row] - ocrCropTop) * ocrScale);
        int halfWidth = Math.max(8, Math.round(ocrCropWidth * 0.36f * ocrScale));
        int halfHeight = Math.max(5, Math.round(screenHeight * 0.016f * ocrScale));
        int left = Math.max(0, centerX - halfWidth);
        int right = Math.min(frame.getWidth() - 1, centerX + halfWidth);
        int top = Math.max(0, centerY - halfHeight);
        int bottom = Math.min(frame.getHeight() - 1, centerY + halfHeight);
        int matched = 0;
        int total = 0;
        for (int y = top; y <= bottom; y += 3) {
            for (int x = left; x <= right; x += 3) {
                int color = frame.getPixel(x, y);
                int r = Color.red(color);
                int g = Color.green(color);
                int b = Color.blue(color);
                boolean hit = green
                        ? (g >= 90 && g > r * 1.16 && g > b * 1.06)
                        : (r >= 105 && r > g * 1.18 && r > b * 1.08);
                if (hit) matched++;
                total++;
            }
        }
        return total == 0 ? 0.0 : matched / (double) total;
    }

'''
    text = replace_between(text, color_start, color_end, color_method)

    SERVICE.write_text(text, encoding="utf-8", newline="\n")


def patch_quiz_activity() -> None:
    text = ACTIVITY.read_text(encoding="utf-8").replace("\r\n", "\n")
    if "调整题目识别区域" in text:
        return

    text = text.replace(
        "    private TextView manualResult;\n",
        "    private TextView manualResult;\n    private TextView regionState;\n",
        1,
    )
    text = text.replace(
        "仅识别屏幕中间区域（推荐，速度更快）",
        "只识别自定义框内区域（固定开启）",
        1,
    )
    text = text.replace(
        "        centerOnly.setChecked(getSharedPreferences(\"quiz_settings\", MODE_PRIVATE)\n"
        "                .getBoolean(\"centerOnly\", true));\n"
        "        centerOnly.setOnCheckedChangeListener((button, checked) ->\n"
        "                getSharedPreferences(\"quiz_settings\", MODE_PRIVATE)\n"
        "                        .edit().putBoolean(\"centerOnly\", checked).apply());\n",
        "        centerOnly.setChecked(true);\n"
        "        centerOnly.setEnabled(false);\n",
        1,
    )

    insert_marker = "        captureCard.addView(centerOnly, fullWidth());\n\n"
    insert = r'''        captureCard.addView(centerOnly, fullWidth());

        regionState = text("", 13, TEXT_MUTED);
        regionState.setPadding(dp(4), dp(2), dp(4), dp(4));
        captureCard.addView(regionState, fullWidth());

        Button regionButton = secondaryButton("调整题目识别区域");
        regionButton.setOnClickListener(v -> openRegionAdjuster());
        captureCard.addView(regionButton, buttonParams());

'''
    if insert_marker not in text:
        raise RuntimeError("QuizActivity region insert marker not found")
    text = text.replace(insert_marker, insert, 1)

    text = text.replace(
        "        updatePermissionState();\n    }\n\n    private void updatePermissionState()",
        "        updatePermissionState();\n        updateRegionState();\n    }\n\n"
        "    private void updateRegionState() {\n"
        "        if (regionState == null) return;\n"
        "        android.content.SharedPreferences prefs =\n"
        "                getSharedPreferences(\"quiz_settings\", MODE_PRIVATE);\n"
        "        int width = Math.round((prefs.getFloat(\"roiRight\", 0.98f) -\n"
        "                prefs.getFloat(\"roiLeft\", 0.04f)) * 100);\n"
        "        int height = Math.round((prefs.getFloat(\"roiBottom\", 0.78f) -\n"
        "                prefs.getFloat(\"roiTop\", 0.06f)) * 100);\n"
        "        regionState.setText(\"当前识别框：屏幕宽度 \" + width +\n"
        "                \"%，高度 \" + height + \"%。请覆盖题目和四个选项。\");\n"
        "    }\n\n"
        "    private void openRegionAdjuster() {\n"
        "        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&\n"
        "                !Settings.canDrawOverlays(this)) {\n"
        "            new AlertDialog.Builder(this)\n"
        "                    .setTitle(\"需要悬浮窗权限\")\n"
        "                    .setMessage(\"请先授权悬浮窗，才能在游戏画面上拖动和缩放识别框。\")\n"
        "                    .setPositiveButton(\"去授权\", (dialog, which) -> openOverlaySettings())\n"
        "                    .setNegativeButton(\"取消\", null)\n"
        "                    .show();\n"
        "            return;\n"
        "        }\n"
        "        startService(new Intent(this, QuizRegionOverlayService.class));\n"
        "        Toast.makeText(this, \"请在游戏画面上调整识别框\", Toast.LENGTH_LONG).show();\n"
        "        moveTaskToBack(true);\n"
        "    }\n\n"
        "    private void updatePermissionState()",
        1,
    )

    text = text.replace(
        "service.putExtra(QuizScreenCaptureService.EXTRA_CENTER_ONLY, centerOnly.isChecked());",
        "service.putExtra(QuizScreenCaptureService.EXTRA_CENTER_ONLY, true);",
        1,
    )
    ACTIVITY.write_text(text, encoding="utf-8", newline="\n")


def create_region_service() -> None:
    content = r'''package com.ruisi.changanmatch;

import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.os.Build;
import android.os.IBinder;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

public class QuizRegionOverlayService extends Service {
    private WindowManager windowManager;
    private FrameLayout root;
    private RegionView regionView;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        showEditor();
        return START_NOT_STICKY;
    }

    private void showEditor() {
        if (root != null) return;
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        root = new FrameLayout(this);
        regionView = new RegionView();
        root.addView(regionView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        LinearLayout toolbar = new LinearLayout(this);
        toolbar.setOrientation(LinearLayout.VERTICAL);
        toolbar.setPadding(dp(12), dp(8), dp(12), dp(8));
        toolbar.setBackgroundColor(Color.argb(225, 61, 41, 112));

        TextView tip = new TextView(this);
        tip.setText("拖动框移动｜拖动右下角圆点缩放｜识别框需覆盖题目和四个选项");
        tip.setTextColor(Color.WHITE);
        tip.setTextSize(13);
        tip.setGravity(Gravity.CENTER);
        toolbar.addView(tip, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(38)));

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER);
        toolbar.addView(actions, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(46)));
        Button save = button("保存识别框");
        Button reset = button("恢复默认");
        Button cancel = button("取消");
        actions.addView(save, actionParams());
        actions.addView(reset, actionParams());
        actions.addView(cancel, actionParams());

        FrameLayout.LayoutParams toolbarParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, dp(92));
        toolbarParams.gravity = Gravity.BOTTOM;
        root.addView(toolbar, toolbarParams);

        save.setOnClickListener(v -> {
            regionView.save();
            Toast.makeText(this, "识别区域已保存", Toast.LENGTH_SHORT).show();
            stopSelf();
        });
        reset.setOnClickListener(v -> regionView.resetDefault());
        cancel.setOnClickListener(v -> stopSelf());

        int type = Build.VERSION.SDK_INT >= 26
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                type,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN |
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        windowManager.addView(root, params);
    }

    private Button button(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextSize(13);
        button.setAllCaps(false);
        return button;
    }

    private LinearLayout.LayoutParams actionParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(42), 1f);
        params.setMargins(dp(3), 0, dp(3), 0);
        return params;
    }

    @Override
    public void onDestroy() {
        if (root != null && windowManager != null) {
            try { windowManager.removeView(root); } catch (Exception ignored) {}
        }
        root = null;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private final class RegionView extends View {
        private final Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint shade = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint handle = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private float downX;
        private float downY;
        private float startLeft;
        private float startTop;
        private float startRight;
        private float startBottom;
        private boolean resizing;

        RegionView() {
            super(QuizRegionOverlayService.this);
            setLayerType(View.LAYER_TYPE_SOFTWARE, null);
            border.setStyle(Paint.Style.STROKE);
            border.setStrokeWidth(dp(4));
            border.setColor(Color.rgb(118, 76, 220));
            shade.setColor(Color.argb(105, 0, 0, 0));
            handle.setColor(Color.rgb(118, 76, 220));
        }

        @Override
        protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            SharedPreferences prefs = getSharedPreferences("quiz_settings", MODE_PRIVATE);
            rect.set(
                    prefs.getFloat("roiLeft", 0.04f) * w,
                    prefs.getFloat("roiTop", 0.06f) * h,
                    prefs.getFloat("roiRight", 0.98f) * w,
                    prefs.getFloat("roiBottom", 0.78f) * h);
            clampRect(w, h);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            canvas.drawRect(0, 0, getWidth(), rect.top, shade);
            canvas.drawRect(0, rect.top, rect.left, rect.bottom, shade);
            canvas.drawRect(rect.right, rect.top, getWidth(), rect.bottom, shade);
            canvas.drawRect(0, rect.bottom, getWidth(), getHeight(), shade);
            canvas.drawRoundRect(rect, dp(12), dp(12), border);
            canvas.drawCircle(rect.right, rect.bottom, dp(13), handle);
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            float x = event.getX();
            float y = event.getY();
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = x;
                    downY = y;
                    startLeft = rect.left;
                    startTop = rect.top;
                    startRight = rect.right;
                    startBottom = rect.bottom;
                    resizing = Math.hypot(x - rect.right, y - rect.bottom) <= dp(48);
                    return resizing || rect.contains(x, y);
                case MotionEvent.ACTION_MOVE:
                    float dx = x - downX;
                    float dy = y - downY;
                    if (resizing) {
                        rect.right = startRight + dx;
                        rect.bottom = startBottom + dy;
                    } else {
                        rect.set(startLeft + dx, startTop + dy,
                                startRight + dx, startBottom + dy);
                    }
                    clampRect(getWidth(), getHeight());
                    invalidate();
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    return true;
                default:
                    return false;
            }
        }

        void resetDefault() {
            rect.set(getWidth() * 0.04f, getHeight() * 0.06f,
                    getWidth() * 0.98f, getHeight() * 0.78f);
            clampRect(getWidth(), getHeight());
            invalidate();
        }

        void save() {
            getSharedPreferences("quiz_settings", MODE_PRIVATE).edit()
                    .putFloat("roiLeft", rect.left / Math.max(1f, getWidth()))
                    .putFloat("roiTop", rect.top / Math.max(1f, getHeight()))
                    .putFloat("roiRight", rect.right / Math.max(1f, getWidth()))
                    .putFloat("roiBottom", rect.bottom / Math.max(1f, getHeight()))
                    .apply();
        }

        private void clampRect(int width, int height) {
            float minWidth = Math.max(dp(220), width * 0.45f);
            float minHeight = Math.max(dp(260), height * 0.30f);
            float toolbarTop = height - dp(100);
            if (rect.width() < minWidth) rect.right = rect.left + minWidth;
            if (rect.height() < minHeight) rect.bottom = rect.top + minHeight;
            if (rect.left < 0) rect.offset(-rect.left, 0);
            if (rect.top < 0) rect.offset(0, -rect.top);
            if (rect.right > width) rect.offset(width - rect.right, 0);
            if (rect.bottom > toolbarTop) rect.offset(0, toolbarTop - rect.bottom);
            rect.left = Math.max(0, rect.left);
            rect.top = Math.max(0, rect.top);
            rect.right = Math.min(width, Math.max(rect.left + minWidth, rect.right));
            rect.bottom = Math.min(toolbarTop, Math.max(rect.top + minHeight, rect.bottom));
        }
    }
}
'''
    REGION_SERVICE.write_text(content, encoding="utf-8", newline="\n")


def patch_manifest() -> None:
    text = MANIFEST.read_text(encoding="utf-8").replace("\r\n", "\n")
    if 'android:name=".QuizRegionOverlayService"' not in text:
        marker = '''        <service
            android:name=".QuizScreenCaptureService"
            android:exported="false"
            android:foregroundServiceType="mediaProjection" />
'''
        addition = marker + '''
        <service
            android:name=".QuizRegionOverlayService"
            android:exported="false" />
'''
        if marker not in text:
            raise RuntimeError("Quiz service manifest marker not found")
        text = text.replace(marker, addition, 1)
    MANIFEST.write_text(text, encoding="utf-8", newline="\n")


def main() -> None:
    patch_capture_service()
    patch_quiz_activity()
    create_region_service()
    patch_manifest()
    print("Applied adjustable ROI and fast recognition patch 1.8.2")


if __name__ == "__main__":
    main()
