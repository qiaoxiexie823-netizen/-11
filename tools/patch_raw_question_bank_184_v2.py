from __future__ import annotations

from pathlib import Path
import base64
import hashlib
import json
import lzma

import patch_raw_question_bank_184 as base

EXPECTED_PARTS = 7
EXPECTED_RECORDS = 9691
EXPECTED_SHA256 = "33b8057b19c24680ac7ceb32c55637ec931c367d64660536fb0b653d80448d11"


def load_exact_raw_bank_v2() -> bytes:
    parts = [Path(f"tools/raw_9691_184_v2_part{index:02d}.b64")
             for index in range(EXPECTED_PARTS)]
    missing = [str(path) for path in parts if not path.exists()]
    if missing:
        raise RuntimeError("缺少校验题库分片：" + ", ".join(missing))

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
        raise RuntimeError("题库 B/C/D 答案字段校验失败")

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


if __name__ == "__main__":
    base.load_exact_raw_bank = load_exact_raw_bank_v2
    base.main()
