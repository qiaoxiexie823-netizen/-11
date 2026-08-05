from __future__ import annotations

from pathlib import Path
import base64
import hashlib
import json
import lzma

import patch_raw_question_bank_184 as base

EXPECTED_RECORDS = 9691
EXPECTED_SHA256 = "33b8057b19c24680ac7ceb32c55637ec931c367d64660536fb0b653d80448d11"


def load_exact_raw_bank_single() -> bytes:
    source = Path("tools/raw_9691_exact_single.b64")
    if not source.exists():
        raise RuntimeError("缺少原始9691题库压缩文件")

    raw = lzma.decompress(base64.b64decode(source.read_text(encoding="utf-8").strip()))
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
            "answer_distribution=" + json.dumps(distribution, ensure_ascii=False, sort_keys=True),
        ]) + "\n",
        encoding="utf-8",
    )
    return raw


if __name__ == "__main__":
    base.load_exact_raw_bank = load_exact_raw_bank_single
    base.main()
