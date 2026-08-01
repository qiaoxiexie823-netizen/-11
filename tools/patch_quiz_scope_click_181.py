from pathlib import Path

ROOT = Path("app/src/main/java/com/ruisi/changanmatch")
SERVICE = ROOT / "QuizScreenCaptureService.java"
MAIN = ROOT / "MainActivity.java"


def replace_method(text: str, start_marker: str, end_marker: str, replacement: str) -> str:
    start = text.index(start_marker)
    end = text.index(end_marker, start)
    return text[:start] + replacement + text[end:]


def patch_service() -> None:
    text = SERVICE.read_text(encoding="utf-8").replace("\r\n", "\n")
    if "QUIZ_SCOPE_CLICK_V181" in text:
        return

    marker = '    private static final String MANUAL_WRONG_LEARNING_V180 = "manual-wrong-learning";\n'
    if marker not in text:
        raise RuntimeError("1.8.0 learning patch must run before 1.8.1 scope patch")
    text = text.replace(
        marker,
        marker + '    private static final String QUIZ_SCOPE_CLICK_V181 = "quiz-page-only-portrait-click";\n',
        1,
    )

    handle_start = "    private void handleOcrResult(Text result, Bitmap frame) {"
    handle_end = "    private boolean tryAutoPageAction(Text result) {"
    handle_replacement = r'''    private void handleOcrResult(Text result, Bitmap frame) {
        // 只在科举闯关的正式题目页工作。类型选择、主城、结算和下一关页面全部忽略。
        if (!isQuizQuestionPage(result)) {
            clearQuizPageState();
            updateOverlay(autoClick
                    ? "等待进入科举答题页…\n自动点击已开启"
                    : "等待进入科举答题页…\n长按悬浮窗停止");
            return;
        }

        // 手动模式下，仅在仍处于当前科举题目页时检查红绿反馈并学习正确答案。
        if (!autoClick && !lastQuestion.isEmpty()) {
            tryLearnManualCorrection(result, frame);
        }

        List<String> lines = new ArrayList<>();
        for (Text.TextBlock block : result.getTextBlocks()) {
            for (Text.Line line : block.getLines()) {
                String value = line.getText().trim();
                if (!value.isEmpty()) lines.add(value);
            }
        }

        QuestionBank.Match match = questionBank.findBest(lines, result.getText());
        if (match == null) {
            consecutiveMisses++;
            if (consecutiveMisses >= 3) {
                updateOverlay(autoClick
                        ? "科举题目识别中…\n自动点击已开启"
                        : "科举题目识别中…\n长按悬浮窗停止");
            }
            return;
        }

        consecutiveMisses = 0;
        if (!match.question.equals(lastQuestion)) {
            lastQuestion = match.question;
            lastShownAnswer = match.answer;
            lastClickedQuestion = "";
            pendingClickQuestion = "";
            for (int i = 0; i < lastOptionTexts.length; i++) lastOptionTexts[i] = "";
            updateOverlay("答案：" + match.answer +
                    "\n匹配 " + Math.round(match.score * 100) + "%" +
                    (autoClick ? " · 等待点击" : ""));
        }

        captureOptionTexts(result);
        if (autoClick) tryAutoClickAnswer(result, match);
    }

    private boolean isQuizQuestionPage(Text result) {
        if (result == null) return false;
        String raw = result.getText() == null ? "" : result.getText();
        String compact = raw.replace(" ", "")
                .replace("\n", "")
                .replace("／", "/");
        boolean hasTitle = compact.contains("科举闯关");
        boolean hasQuestionNumber = compact.contains("第1/2题") ||
                compact.contains("第2/2题") ||
                (compact.contains("第1") && compact.contains("2题")) ||
                (compact.contains("第2") && compact.contains("2题"));
        boolean hasScoreHeader = compact.contains("得分") && compact.contains("已答对");
        return hasTitle && hasQuestionNumber && hasScoreHeader && countVisibleOptionRows(result) >= 3;
    }

    private int countVisibleOptionRows(Text result) {
        boolean[] rows = new boolean[]{false, false, false, false};
        float firstRowY = screenHeight * 0.550f;
        float rowGap = screenHeight * 0.0528f;
        for (Text.TextBlock block : result.getTextBlocks()) {
            for (Text.Line line : block.getLines()) {
                Rect box = line.getBoundingBox();
                if (box == null) continue;
                float screenX = box.centerX() / Math.max(0.01f, ocrScale);
                float screenY = box.centerY() / Math.max(0.01f, ocrScale) + ocrCropTop;
                if (screenX < screenWidth * 0.16f || screenX > screenWidth * 0.97f) continue;
                int row = Math.round((screenY - firstRowY) / rowGap);
                if (row < 0 || row > 3) continue;
                if (Math.abs(screenY - (firstRowY + rowGap * row)) <= screenHeight * 0.031f) {
                    String value = QuestionBank.normalize(line.getText());
                    if (!value.isEmpty()) rows[row] = true;
                }
            }
        }
        int count = 0;
        for (boolean visible : rows) if (visible) count++;
        return count;
    }

    private void clearQuizPageState() {
        consecutiveMisses = 0;
        lastQuestion = "";
        lastShownAnswer = "";
        lastClickedQuestion = "";
        pendingClickQuestion = "";
        pendingPageAction = "";
        for (int i = 0; i < lastOptionTexts.length; i++) lastOptionTexts[i] = "";
    }

'''
    text = replace_method(text, handle_start, handle_end, handle_replacement)

    page_start = "    private boolean tryAutoPageAction(Text result) {"
    page_end = "    private void tryAutoClickAnswer(Text result, QuestionBank.Match match) {"
    page_replacement = r'''    private boolean tryAutoPageAction(Text result) {
        // 1.8.1：不点击开始答题、下一题或下一关。
        return false;
    }

'''
    text = replace_method(text, page_start, page_end, page_replacement)

    click_start = "    private void tryAutoClickAnswer(Text result, QuestionBank.Match match) {"
    click_end = "    private AnswerTarget findAnswerTarget(Text result, String answerText) {"
    click_replacement = r'''    private void tryAutoClickAnswer(Text result, QuestionBank.Match match) {
        if (!isQuizQuestionPage(result)) return;
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

        captureOptionTexts(result);
        int optionIndex = findAnswerOptionIndex(match.answer);
        if (optionIndex < 0) {
            updateOverlay("答案：" + match.answer + "\n正在定位正确选项…");
            return;
        }

        // 用户视频为 576×1280 竖屏单列四选项；使用比例坐标兼容同纵横比设备。
        float screenX = screenWidth * 0.585f;
        float firstRowY = screenHeight * 0.550f;
        float rowGap = screenHeight * 0.0528f;
        float screenY = firstRowY + rowGap * optionIndex;
        final String clickQuestion = match.question;
        final String clickAnswer = match.answer;
        final int selectedOption = optionIndex + 1;

        pendingClickQuestion = clickQuestion;
        lastClickAt = now;
        updateOverlay("答案：" + clickAnswer +
                "\n正在点击第" + selectedOption + "项…");

        boolean queued = AutomationAccessibilityService.performTap(
                screenX, screenY, success -> mainHandler.post(() -> {
                    if (clickQuestion.equals(pendingClickQuestion)) pendingClickQuestion = "";
                    if (!clickQuestion.equals(lastQuestion)) return;
                    if (success) {
                        lastClickedQuestion = clickQuestion;
                        updateOverlay("答案：" + clickAnswer +
                                "\n已点击第" + selectedOption + "项");
                    } else {
                        lastClickAt = 0L;
                        lastClickedQuestion = "";
                        updateOverlay("答案：" + clickAnswer +
                                "\n点击失败，正在重试…");
                    }
                }));
        if (!queued) {
            pendingClickQuestion = "";
            lastClickAt = 0L;
            updateOverlay("答案：" + clickAnswer + "\n自动点击权限未连接");
        }
    }

    private int findAnswerOptionIndex(String answerText) {
        String[] answers = answerText.split("\\s*/\\s*");
        int bestIndex = -1;
        double bestScore = 0.0;
        for (int row = 0; row < lastOptionTexts.length; row++) {
            String option = QuestionBank.normalize(lastOptionTexts[row]);
            if (option.isEmpty()) continue;
            for (String answerValue : answers) {
                String answer = QuestionBank.normalize(answerValue);
                if (answer.isEmpty()) continue;
                double score = answerLineScore(option, answer);
                if (score > bestScore) {
                    bestScore = score;
                    bestIndex = row;
                }
            }
        }
        return bestScore >= 0.78 ? bestIndex : -1;
    }

'''
    text = replace_method(text, click_start, click_end, click_replacement)

    # Ensure manual learning can never run outside a verified question page.
    learn_marker = "    private void tryLearnManualCorrection(Text result, Bitmap frame) {\n"
    if learn_marker not in text:
        raise RuntimeError("Manual learning method not found")
    text = text.replace(
        learn_marker,
        learn_marker + "        if (!isQuizQuestionPage(result)) return;\n",
        1,
    )

    # Align option OCR and color sampling with the portrait layout from the supplied video.
    text = text.replace("screenHeight * 0.551f", "screenHeight * 0.550f")
    text = text.replace("screenHeight * 0.0536f", "screenHeight * 0.0528f")

    SERVICE.write_text(text, encoding="utf-8", newline="\n")


def patch_main_ui() -> None:
    text = MAIN.read_text(encoding="utf-8").replace("\r\n", "\n")
    note = '''        TextView note = text(
                "当前版本只保留答题器和卡密，已取消消消乐入口。错题记录仅保存在本机，不上传服务器。",
                13, TEXT_MUTED);
        note.setGravity(Gravity.CENTER);
        note.setPadding(dp(5), dp(10), dp(5), 0);
        card.addView(note, fullWidth());
'''
    text = text.replace(note, "")
    MAIN.write_text(text, encoding="utf-8", newline="\n")


def main() -> None:
    patch_service()
    patch_main_ui()
    print("Applied quiz-page-only recognition and portrait auto-click 1.8.1")


if __name__ == "__main__":
    main()
