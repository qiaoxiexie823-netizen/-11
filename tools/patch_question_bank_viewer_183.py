from pathlib import Path

ACTIVITY = Path("app/src/main/java/com/ruisi/changanmatch/QuizActivity.java")
MAIN_ACTIVITY = Path("app/src/main/java/com/ruisi/changanmatch/MainActivity.java")
CAPTURE_SERVICE = Path("app/src/main/java/com/ruisi/changanmatch/QuizScreenCaptureService.java")
MANIFEST = Path("app/src/main/AndroidManifest.xml")
CURRENT_BANK_COUNT = "2020"


def patch_activity() -> None:
    text = ACTIVITY.read_text(encoding="utf-8").replace("\r\n", "\n")
    if 'secondaryButton("点击查看题库")' not in text:
        marker = "        root.addView(count, countParams);\n\n"
        addition = '''        root.addView(count, countParams);

        Button viewQuestionBank = secondaryButton("点击查看题库");
        viewQuestionBank.setOnClickListener(v ->
                startActivity(new Intent(this, QuestionBankViewerActivity.class)));
        root.addView(viewQuestionBank, buttonParams());

'''
        if marker not in text:
            raise RuntimeError("QuizActivity 题库数量区域定位失败")
        text = text.replace(marker, addition, 1)
    text = text.replace("3603", CURRENT_BANK_COUNT)
    ACTIVITY.write_text(text, encoding="utf-8", newline="\n")


def patch_display_counts() -> None:
    for path in (MAIN_ACTIVITY, CAPTURE_SERVICE):
        text = path.read_text(encoding="utf-8").replace("\r\n", "\n")
        text = text.replace("3603", CURRENT_BANK_COUNT)
        path.write_text(text, encoding="utf-8", newline="\n")


def patch_manifest() -> None:
    text = MANIFEST.read_text(encoding="utf-8").replace("\r\n", "\n")
    if 'android:name=".QuestionBankViewerActivity"' not in text:
        marker = '''        <activity
            android:name=".QuizActivity"
            android:exported="false"
            android:screenOrientation="sensorPortrait" />
'''
        addition = marker + '''
        <activity
            android:name=".QuestionBankViewerActivity"
            android:exported="false"
            android:screenOrientation="sensorPortrait" />
'''
        if marker not in text:
            raise RuntimeError("AndroidManifest QuizActivity 注册位置定位失败")
        text = text.replace(marker, addition, 1)
    MANIFEST.write_text(text, encoding="utf-8", newline="\n")


def main() -> None:
    patch_activity()
    patch_display_counts()
    patch_manifest()
    print("Added searchable question bank viewer and current bank count for 1.8.3")


if __name__ == "__main__":
    main()
