from __future__ import annotations

from pathlib import Path
import base64
import hashlib
import json
import lzma
import re

import patch_raw_question_bank_184 as base

EXPECTED_PARTS = 8
EXPECTED_RECORDS = 9691
EXPECTED_SHA256 = "33b8057b19c24680ac7ceb32c55637ec931c367d64660536fb0b653d80448d11"


def load_exact_raw_bank_single() -> bytes:
    parts = [
        Path(f"tools/raw_9691_exact_part{index:02d}.b64")
        for index in range(EXPECTED_PARTS)
    ]
    missing = [str(path) for path in parts if not path.exists()]
    if missing:
        raise RuntimeError("缺少原始9691题库分片：" + ", ".join(missing))

    encoded = "".join(
        path.read_text(encoding="utf-8").strip()
        for path in parts
    )
    raw = lzma.decompress(base64.b64decode(encoded))
    digest = hashlib.sha256(raw).hexdigest()
    if digest != EXPECTED_SHA256:
        raise RuntimeError(f"题库SHA256不一致：{digest}")

    lines = [line for line in raw.decode("utf-8").splitlines() if line.strip()]
    if len(lines) != EXPECTED_RECORDS:
        raise RuntimeError(f"原始题库记录数不一致：{len(lines)}")

    distribution: dict[str, int] = {}
    for number, line in enumerate(lines, 1):
        item = json.loads(line)
        question = item.get("q")
        options = item.get("a")
        answer_code = str(item.get("ans", "")).strip().upper()
        if not isinstance(question, str) or not question.strip():
            raise RuntimeError(f"第{number}条缺少题目")
        if not isinstance(options, list) or not options:
            raise RuntimeError(f"第{number}条缺少选项")
        distribution[answer_code] = distribution.get(answer_code, 0) + 1

    expected_distribution = {"A": 9616, "B": 30, "C": 23, "D": 21, "": 1}
    if distribution != expected_distribution:
        raise RuntimeError(f"答案分布不一致：{distribution}")

    Path("generated-apk").mkdir(parents=True, exist_ok=True)
    Path("generated-apk/question-bank-count.txt").write_text(
        "\n".join([
            "source=uploaded_raw_question_bank",
            f"records={len(lines)}",
            "duplicates=preserved",
            "order=preserved",
            f"sha256={digest}",
            "answer_distribution=" + json.dumps(
                distribution,
                ensure_ascii=False,
                sort_keys=True,
            ),
        ]) + "\n",
        encoding="utf-8",
    )
    return raw


def remove_homepage_change_note() -> None:
    path = Path("app/src/main/java/com/ruisi/changanmatch/MainActivity.java")
    text = path.read_text(encoding="utf-8").replace("\r\n", "\n")
    pattern = re.compile(
        r'\n\s*TextView note = text\(\n'
        r'\s*"当前版本只保留答题器和卡密，已取消消消乐入口。错题记录仅保存在本机，不上传服务器。",\n'
        r'\s*13, TEXT_MUTED\);\n'
        r'\s*note\.setGravity\(Gravity\.CENTER\);\n'
        r'\s*note\.setPadding\(dp\(5\), dp\(10\), dp\(5\), 0\);\n'
        r'\s*card\.addView\(note, fullWidth\(\)\);\n',
        re.MULTILINE,
    )
    text, changes = pattern.subn("\n", text, count=1)
    if changes == 0 and "当前版本只保留答题器和卡密" in text:
        raise RuntimeError("首页修改说明删除失败")
    path.write_text(text, encoding="utf-8", newline="\n")


if __name__ == "__main__":
    base.load_exact_raw_bank = load_exact_raw_bank_single
    base.main()
    remove_homepage_change_note()
