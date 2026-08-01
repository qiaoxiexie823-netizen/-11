from pathlib import Path

ROOT = Path("app/src/main/java/com/ruisi/changanmatch")


def patch_question_bank() -> None:
    path = ROOT / "QuestionBank.java"
    text = path.read_text(encoding="utf-8").replace("\r\n", "\n")
    if "learnedStore.getAnswer" in text:
        return

    text = text.replace(
        "    private final Map<String, List<Integer>> gramIndex = new HashMap<>();\n",
        "    private final Map<String, List<Integer>> gramIndex = new HashMap<>();\n"
        "    private final LearnedQuestionStore learnedStore;\n",
        1,
    )
    text = text.replace(
        "    public QuestionBank(Context context) {\n        load(context);\n",
        "    public QuestionBank(Context context) {\n"
        "        learnedStore = new LearnedQuestionStore(context);\n"
        "        load(context);\n",
        1,
    )
    old = '''        if (best == null) return null;
        int questionLength = normalize(best.question).length();
        double threshold = questionLength <= 5 ? 0.76 : (questionLength <= 9 ? 0.64 : 0.54);
        return best.score >= threshold ? best : null;
'''
    new = '''        if (best == null) return null;
        int questionLength = normalize(best.question).length();
        double threshold = questionLength <= 5 ? 0.76 : (questionLength <= 9 ? 0.64 : 0.54);
        if (best.score < threshold) return null;
        String learnedAnswer = learnedStore.getAnswer(best.question);
        if (!learnedAnswer.isEmpty()) {
            return new Match(best.question, learnedAnswer, best.score);
        }
        return best;
'''
    if old not in text:
        raise RuntimeError("QuestionBank result block not found")
    path.write_text(text.replace(old, new, 1), encoding="utf-8", newline="\n")


def patch_quiz_activity() -> None:
    path = ROOT / "QuizActivity.java"
    text = path.read_text(encoding="utf-8").replace("\r\n", "\n")
    if "查看错题学习记录" in text:
        return

    text = text.replace(
        "启动后切回游戏，助手会自动识别题目并显示答案。开启自动点击且授权无障碍后，会定位包含正确答案的选项文字并点击；关闭时只显示答案。",
        "启动后切回游戏，助手会自动识别题目并显示答案。开启自动点击时会点击正确选项；关闭自动点击后为手动模式，答错时会识别游戏公布的正确选项并保存到本机学习题库。",
        1,
    )
    text = text.replace(
        "请在无障碍设置中开启“宴会消消乐自动滑动”。该权限同时用于答题器点击正确选项。",
        "请在无障碍设置中开启“深情答题自动点击”。该权限只用于点击答题选项。",
        1,
    )

    marker = "        captureCard.addView(safetyNote, fullWidth());\n\n"
    insert = '''        captureCard.addView(safetyNote, fullWidth());

        LinearLayout learningCard = card();
        learningCard.setPadding(dp(16), dp(16), dp(16), dp(16));
        root.addView(learningCard, cardParams());
        learningCard.addView(text("手动模式错题学习", 19, PURPLE_DARK), fullWidth());
        LearnedQuestionStore learnedStore = new LearnedQuestionStore(this);
        TextView learningState = text(
                "已记录 " + learnedStore.size() + " 道错题。关闭自动点击后，答错页面出现正确答案文字，或选项呈现红/绿反馈时，助手会自动保存正确答案。",
                14, TEXT_MUTED);
        learningState.setLineSpacing(0, 1.18f);
        learningState.setPadding(0, dp(8), 0, dp(8));
        learningCard.addView(learningState, fullWidth());

        Button viewLearning = secondaryButton("查看错题学习记录");
        viewLearning.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle("错题学习记录（" + learnedStore.size() + "道）")
                .setMessage(learnedStore.recentText(30))
                .setPositiveButton("关闭", null)
                .show());
        learningCard.addView(viewLearning, buttonParams());

        Button clearLearning = secondaryButton("清空错题记录");
        clearLearning.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle("清空错题记录")
                .setMessage("清空后将恢复使用内置题库答案，确定继续吗？")
                .setNegativeButton("取消", null)
                .setPositiveButton("确定清空", (dialog, which) -> {
                    learnedStore.clear();
                    learningState.setText("已记录 0 道错题。新的答错题目仍会继续自动学习。");
                    Toast.makeText(this, "错题记录已清空", Toast.LENGTH_SHORT).show();
                })
                .show());
        learningCard.addView(clearLearning, buttonParams());

'''
    if marker not in text:
        raise RuntimeError("QuizActivity safety note marker not found")
    path.write_text(text.replace(marker, insert, 1), encoding="utf-8", newline="\n")


def patch_quiz_service() -> None:
    path = ROOT / "QuizScreenCaptureService.java"
    text = path.read_text(encoding="utf-8").replace("\r\n", "\n")
    if "MANUAL_WRONG_LEARNING_V180" in text:
        return

    field_marker = '    private static final String QUIZ_LAYOUT_V164 = "fixed-four-row-layout";\n'
    if field_marker not in text:
        raise RuntimeError("Full-auto quiz patch must run before learning patch")
    text = text.replace(
        field_marker,
        field_marker
        + '    private static final String MANUAL_WRONG_LEARNING_V180 = "manual-wrong-learning";\n'
        + '    private LearnedQuestionStore learnedStore;\n'
        + '    private String lastShownAnswer = "";\n'
        + '    private final String[] lastOptionTexts = new String[]{"", "", "", ""};\n'
        + '    private long lastCorrectionAt;\n',
        1,
    )

    text = text.replace(
        "        questionBank = new QuestionBank(this);\n",
        "        learnedStore = new LearnedQuestionStore(this);\n"
        "        questionBank = new QuestionBank(this);\n",
        1,
    )

    old_callback = "        task.addOnSuccessListener(this::handleOcrResult)\n"
    new_callback = "        task.addOnSuccessListener(result -> handleOcrResult(result, prepared))\n"
    if old_callback not in text:
        raise RuntimeError("OCR success callback not found")
    text = text.replace(old_callback, new_callback, 1)

    signature = "    private void handleOcrResult(Text result) {\n"
    if signature not in text:
        raise RuntimeError("OCR handler signature not found")
    text = text.replace(
        signature,
        "    private void handleOcrResult(Text result, Bitmap frame) {\n"
        "        if (!autoClick && !lastQuestion.isEmpty()) {\n"
        "            tryLearnManualCorrection(result, frame);\n"
        "        }\n",
        1,
    )

    new_question_old = '''        if (!match.question.equals(lastQuestion)) {
            lastQuestion = match.question;
            lastClickedQuestion = "";
            pendingClickQuestion = "";
'''
    new_question_new = '''        if (!match.question.equals(lastQuestion)) {
            lastQuestion = match.question;
            lastShownAnswer = match.answer;
            lastClickedQuestion = "";
            pendingClickQuestion = "";
            for (int i = 0; i < lastOptionTexts.length; i++) lastOptionTexts[i] = "";
'''
    if new_question_old not in text:
        raise RuntimeError("New question block not found")
    text = text.replace(new_question_old, new_question_new, 1)

    auto_marker = "        if (autoClick) tryAutoClickAnswer(result, match);\n    }\n\n"
    if auto_marker not in text:
        raise RuntimeError("Auto click marker not found")
    text = text.replace(
        auto_marker,
        "        captureOptionTexts(result);\n"
        "        if (autoClick) tryAutoClickAnswer(result, match);\n"
        "    }\n\n",
        1,
    )

    method_marker = "    private void tryAutoClickAnswer(Text result, QuestionBank.Match match) {\n"
    methods = r'''    private void captureOptionTexts(Text result) {
        float firstRowY = screenHeight * 0.551f;
        float rowGap = screenHeight * 0.0536f;
        for (Text.TextBlock block : result.getTextBlocks()) {
            for (Text.Line line : block.getLines()) {
                Rect box = line.getBoundingBox();
                if (box == null) continue;
                float screenY = box.centerY() / Math.max(0.01f, ocrScale) + ocrCropTop;
                int row = Math.round((screenY - firstRowY) / rowGap);
                if (row < 0 || row > 3) continue;
                if (Math.abs(screenY - (firstRowY + rowGap * row)) > screenHeight * 0.037f) continue;
                String value = LearnedQuestionStore.cleanAnswer(line.getText());
                String normalized = QuestionBank.normalize(value);
                if (normalized.length() < 1 || normalized.length() > 32) continue;
                if (normalized.contains("倒计时") || normalized.contains("答题者")) continue;
                if (lastOptionTexts[row].isEmpty() || value.length() > lastOptionTexts[row].length()) {
                    lastOptionTexts[row] = value;
                }
            }
        }
    }

    private void tryLearnManualCorrection(Text result, Bitmap frame) {
        long now = System.currentTimeMillis();
        if (learnedStore == null || now - lastCorrectionAt < 1600L) return;

        captureOptionTexts(result);
        String explicit = extractExplicitCorrectAnswer(result);
        int greenRow = detectCorrectRowFromColors(frame);
        String correctAnswer = explicit;
        if (correctAnswer.isEmpty() && greenRow >= 0) {
            correctAnswer = lastOptionTexts[greenRow];
        }
        correctAnswer = LearnedQuestionStore.cleanAnswer(correctAnswer);
        if (correctAnswer.isEmpty()) return;

        String rawText = result.getText() == null ? "" : result.getText();
        String normalizedRaw = QuestionBank.normalize(rawText);
        boolean hasErrorText = rawText.contains("回答错误") || rawText.contains("答错") ||
                rawText.contains("回答不正确") || rawText.contains("正确答案") ||
                normalizedRaw.contains("回答错误");
        boolean hasColorFeedback = greenRow >= 0 && detectWrongRedRow(frame) >= 0;
        if (!hasErrorText && !hasColorFeedback) return;

        String existing = learnedStore.getAnswer(lastQuestion);
        if (QuestionBank.normalize(existing).equals(QuestionBank.normalize(correctAnswer))) return;
        if (learnedStore.saveCorrection(lastQuestion, correctAnswer, lastShownAnswer)) {
            lastCorrectionAt = now;
            lastShownAnswer = correctAnswer;
            updateOverlay("已自动记录错题\n正确答案：" + correctAnswer +
                    "\n本地学习题库：" + learnedStore.size() + "道");
        }
    }

    private String extractExplicitCorrectAnswer(Text result) {
        for (Text.TextBlock block : result.getTextBlocks()) {
            for (Text.Line line : block.getLines()) {
                String value = line.getText() == null ? "" : line.getText().trim();
                if (!value.contains("正确答案")) continue;
                String answer = value.replaceFirst(".*正确答案[是为：:\\s]*", "").trim();
                answer = LearnedQuestionStore.cleanAnswer(answer);
                if (!QuestionBank.normalize(answer).isEmpty()) return answer;
            }
        }
        return "";
    }

    private int detectCorrectRowFromColors(Bitmap frame) {
        int bestRow = -1;
        double bestGreen = 0.0;
        for (int row = 0; row < 4; row++) {
            double ratio = optionColorRatio(frame, row, true);
            if (ratio > bestGreen) {
                bestGreen = ratio;
                bestRow = row;
            }
        }
        return bestGreen >= 0.075 ? bestRow : -1;
    }

    private int detectWrongRedRow(Bitmap frame) {
        int bestRow = -1;
        double bestRed = 0.0;
        for (int row = 0; row < 4; row++) {
            double ratio = optionColorRatio(frame, row, false);
            if (ratio > bestRed) {
                bestRed = ratio;
                bestRow = row;
            }
        }
        return bestRed >= 0.055 ? bestRow : -1;
    }

    private double optionColorRatio(Bitmap frame, int row, boolean green) {
        if (frame == null || frame.isRecycled()) return 0.0;
        float firstRowY = screenHeight * 0.551f;
        float rowGap = screenHeight * 0.0536f;
        int centerX = Math.round(screenWidth * 0.585f * ocrScale);
        int centerY = Math.round((firstRowY + rowGap * row - ocrCropTop) * ocrScale);
        int halfWidth = Math.max(8, Math.round(screenWidth * 0.19f * ocrScale));
        int halfHeight = Math.max(5, Math.round(screenHeight * 0.018f * ocrScale));
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
    if method_marker not in text:
        raise RuntimeError("Auto-click method marker not found")
    text = text.replace(method_marker, methods + method_marker, 1)
    path.write_text(text, encoding="utf-8", newline="\n")


def patch_manifest_and_strings() -> None:
    manifest = Path("app/src/main/AndroidManifest.xml")
    text = manifest.read_text(encoding="utf-8").replace("\r\n", "\n")
    text = text.replace('''        <service
            android:name=".ScreenCaptureService"
            android:exported="false"
            android:foregroundServiceType="mediaProjection" />

''', "")
    manifest.write_text(text, encoding="utf-8", newline="\n")

    strings = Path("app/src/main/res/values/strings.xml")
    value = strings.read_text(encoding="utf-8").replace("\r\n", "\n")
    value = value.replace("宴会消消乐自动滑动", "深情答题自动点击")
    value = value.replace(
        "根据本地屏幕分析结果执行相邻方块滑动。仅在用户主动开启自动模式时运行。",
        "根据本地题库识别结果点击答题选项。仅在用户主动开启自动点击时运行。",
    )
    strings.write_text(value, encoding="utf-8", newline="\n")


def main() -> None:
    patch_question_bank()
    patch_quiz_activity()
    patch_quiz_service()
    patch_manifest_and_strings()
    print("Applied quiz-only UI and manual wrong-answer learning 1.8.0")


if __name__ == "__main__":
    main()
