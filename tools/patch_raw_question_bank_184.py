from __future__ import annotations

from pathlib import Path
import base64
import hashlib
import json
import lzma

ROOT = Path("app/src/main/java/com/ruisi/changanmatch")
ASSET = Path("app/src/main/assets/questions.jsonl")
QUESTION_BANK = ROOT / "QuestionBank.java"
VIEWER = ROOT / "QuestionBankViewerActivity.java"
DISPLAY_FILES = [
    ROOT / "MainActivity.java",
    ROOT / "QuizActivity.java",
    ROOT / "QuizScreenCaptureService.java",
]
EXPECTED_PARTS = 7
EXPECTED_RECORDS = 9691
EXPECTED_SHA256 = "33b8057b19c24680ac7ceb32c55637ec931c367d64660536fb0b653d80448d11"
PLACEHOLDER = "题库文件未提供答案"


def load_exact_raw_bank() -> bytes:
    parts = [Path(f"tools/raw_9691_184_v2_part{index:02d}.b64")
             for index in range(EXPECTED_PARTS)]
    missing = [str(path) for path in parts if not path.exists()]
    if missing:
        raise RuntimeError("缺少原始题库分片：" + ", ".join(missing))
    encoded = "".join(path.read_text(encoding="utf-8").strip() for path in parts)
    raw = lzma.decompress(base64.b64decode(encoded))
    digest = hashlib.sha256(raw).hexdigest()
    if digest != EXPECTED_SHA256:
        raise RuntimeError(f"题库 SHA256 不一致：{digest}")
    lines = [line for line in raw.decode("utf-8").splitlines() if line.strip()]
    if len(lines) != EXPECTED_RECORDS:
        raise RuntimeError(f"原始题库记录数不一致：{len(lines)}")
    distribution: dict[str, int] = {}
    selected_examples: dict[str, str] = {}
    empty_selected = 0
    for number, line in enumerate(lines, 1):
        item = json.loads(line)
        question = item.get("q")
        options = item.get("a")
        answer_code = str(item.get("ans", "")).strip().upper()
        if not isinstance(question, str) or not question.strip():
            raise RuntimeError(f"第 {number} 条缺少题目")
        if not isinstance(options, list) or not options:
            raise RuntimeError(f"第 {number} 条缺少选项")
        distribution[answer_code] = distribution.get(answer_code, 0) + 1
        index = {"A": 0, "B": 1, "C": 2, "D": 3}.get(answer_code, 0)
        selected = str(options[index]).strip() if index < len(options) else ""
        if not selected:
            empty_selected += 1
        elif answer_code in ("B", "C", "D") and answer_code not in selected_examples:
            selected_examples[answer_code] = selected
    if not all(code in selected_examples for code in ("B", "C", "D")):
        raise RuntimeError("题库多选答案字段校验失败")
    Path("generated-apk").mkdir(parents=True, exist_ok=True)
    Path("generated-apk/question-bank-count.txt").write_text(
        "\n".join([
            "source=uploaded_raw_question_bank",
            f"records={len(lines)}",
            "duplicates=preserved",
            "order=preserved",
            f"sha256={digest}",
            "answer_distribution=" + json.dumps(distribution, ensure_ascii=False, sort_keys=True),
            f"empty_selected_answers={empty_selected}",
            "selected_B_example=" + selected_examples["B"],
            "selected_C_example=" + selected_examples["C"],
            "selected_D_example=" + selected_examples["D"],
        ]) + "\n",
        encoding="utf-8",
    )
    return raw


def patch_question_bank_loader() -> None:
    text = QUESTION_BANK.read_text(encoding="utf-8").replace("\r\n", "\n")
    old = '''                    JSONArray answers = object.optJSONArray("a");
                    if (question.isEmpty() || answers == null || answers.length() == 0) continue;
                    List<String> answerList = new ArrayList<>();
                    for (int i = 0; i < answers.length(); i++) {
                        String answer = answers.optString(i, "").trim();
                        if (!answer.isEmpty()) answerList.add(answer);
                    }
                    if (!answerList.isEmpty()) {
                        entries.add(new Entry(question, String.join(" / ", answerList)));
                    }
'''
    new = f'''                    JSONArray answers = object.optJSONArray("a");
                    if (question.isEmpty() || answers == null || answers.length() == 0) continue;
                    String answerCode = object.optString("ans", "A").trim().toUpperCase(Locale.ROOT);
                    int answerIndex = answerCode.equals("B") ? 1 :
                            (answerCode.equals("C") ? 2 : (answerCode.equals("D") ? 3 : 0));
                    String selectedAnswer = answerIndex < answers.length()
                            ? answers.optString(answerIndex, "").trim() : "";
                    if (selectedAnswer.isEmpty()) selectedAnswer = "{PLACEHOLDER}";
                    // 完整保留原文件每一行和重复题目，正确答案由 ans 字段决定。
                    entries.add(new Entry(question, selectedAnswer));
'''
    if old not in text:
        if "正确答案由 ans 字段决定" not in text:
            raise RuntimeError("QuestionBank.java 加载代码定位失败")
    else:
        text = text.replace(old, new, 1)
    QUESTION_BANK.write_text(text, encoding="utf-8", newline="\n")


def patch_viewer_loader() -> None:
    text = VIEWER.read_text(encoding="utf-8").replace("\r\n", "\n")
    old = '''                        List<String> answers = new ArrayList<>();
                        for (int index = 0; index < answerArray.length(); index++) {
                            String answer = answerArray.optString(index, "").trim();
                            if (!answer.isEmpty()) answers.add(answer);
                        }
                        if (!answers.isEmpty()) {
                            loaded.add(new QuestionItem(question,
                                    android.text.TextUtils.join(" / ", answers)));
                        }
'''
    new = f'''                        String answerCode = object.optString("ans", "A").trim().toUpperCase(Locale.ROOT);
                        int answerIndex = answerCode.equals("B") ? 1 :
                                (answerCode.equals("C") ? 2 : (answerCode.equals("D") ? 3 : 0));
                        String selectedAnswer = answerIndex < answerArray.length()
                                ? answerArray.optString(answerIndex, "").trim() : "";
                        if (selectedAnswer.isEmpty()) selectedAnswer = "{PLACEHOLDER}";
                        // 保持原始9691条记录的顺序与重复项。
                        loaded.add(new QuestionItem(question, selectedAnswer));
'''
    if old not in text:
        if "保持原始9691条记录" not in text:
            raise RuntimeError("QuestionBankViewerActivity.java 加载代码定位失败")
    else:
        text = text.replace(old, new, 1)
    VIEWER.write_text(text, encoding="utf-8", newline="\n")


def patch_visible_counts() -> None:
    for path in DISPLAY_FILES:
        text = path.read_text(encoding="utf-8").replace("\r\n", "\n")
        text = text.replace("3603", "9691").replace("2020", "9691")
        path.write_text(text, encoding="utf-8", newline="\n")


def main() -> None:
    raw = load_exact_raw_bank()
    # 不解析、排序、合并或去重，直接写入用户上传文件的原始字节。
    ASSET.write_bytes(raw)
    patch_question_bank_loader()
    patch_viewer_loader()
    patch_visible_counts()
    print("Applied exact raw 9691 question bank for Android 1.8.4")


if __name__ == "__main__":
    main()
