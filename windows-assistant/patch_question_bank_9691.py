from __future__ import annotations

from pathlib import Path
import base64
import hashlib
import json
import lzma

ROOT = Path(__file__).resolve().parents[1]
MAIN = ROOT / "windows-assistant" / "main.py"
BANK = ROOT / "app" / "src" / "main" / "assets" / "questions.jsonl"
EXPECTED_PARTS = 8
EXPECTED_RECORDS = 9691
EXPECTED_SHA256 = "33b8057b19c24680ac7ceb32c55637ec931c367d64660536fb0b653d80448d11"


def rebuild_bank() -> None:
    parts = [ROOT / "tools" / f"raw_9691_exact_part{index:02d}.b64" for index in range(EXPECTED_PARTS)]
    missing = [str(path) for path in parts if not path.exists()]
    if missing:
        raise RuntimeError("缺少9691题库分片：" + ", ".join(missing))

    encoded = "".join(path.read_text(encoding="utf-8").strip() for path in parts)
    raw = lzma.decompress(base64.b64decode(encoded))
    digest = hashlib.sha256(raw).hexdigest()
    if digest != EXPECTED_SHA256:
        raise RuntimeError(f"题库SHA256不一致：{digest}")

    lines = [line for line in raw.decode("utf-8").splitlines() if line.strip()]
    if len(lines) != EXPECTED_RECORDS:
        raise RuntimeError(f"题库记录数不一致：{len(lines)}")

    distribution: dict[str, int] = {}
    for number, line in enumerate(lines, 1):
        item = json.loads(line)
        question = str(item.get("q", "")).strip()
        options = item.get("a")
        answer_code = str(item.get("ans", "")).strip().upper()
        if not question:
            raise RuntimeError(f"第{number}条缺少题目")
        if not isinstance(options, list) or not options:
            raise RuntimeError(f"第{number}条缺少选项")
        distribution[answer_code] = distribution.get(answer_code, 0) + 1

    expected_distribution = {"A": 9616, "B": 30, "C": 23, "D": 21, "": 1}
    if distribution != expected_distribution:
        raise RuntimeError(f"答案分布不一致：{distribution}")

    BANK.write_bytes(raw)
    print(f"Windows题库已写入：{len(lines)}条，SHA256={digest}")


def patch_windows_loader() -> None:
    text = MAIN.read_text(encoding="utf-8").replace("\r\n", "\n")

    text = text.replace('APP_VERSION = "2.1.1"', 'APP_VERSION = "2.1.2"')
    text = text.replace('APP_VERSION = "2.1.0"', 'APP_VERSION = "2.1.2"')
    text = text.replace("3603道本地题库", "9691道本地题库")

    old = '''                question = str(item.get("q", "")).strip()
                answers = item.get("a") or []
                if not question or not answers:
                    continue
                answer = " / ".join(str(value).strip() for value in answers if str(value).strip())
                normalized = normalize_text(question)
                if answer and normalized:
                    self.entries.append(QuestionEntry(question, answer, normalized))
'''
    new = '''                question = str(item.get("q", "")).strip()
                answers = item.get("a") or []
                if not question or not answers:
                    continue
                answer_code = str(item.get("ans", "A")).strip().upper()
                answer_index = {"A": 0, "B": 1, "C": 2, "D": 3}.get(answer_code, 0)
                answer = str(answers[answer_index]).strip() if answer_index < len(answers) else ""
                if not answer:
                    answer = str(answers[0]).strip() if answers else ""
                normalized = normalize_text(question)
                if answer and normalized:
                    self.entries.append(QuestionEntry(question, answer, normalized))
'''

    if old in text:
        text = text.replace(old, new, 1)
    elif 'answer_index = {"A": 0, "B": 1, "C": 2, "D": 3}.get(answer_code, 0)' not in text:
        raise RuntimeError("无法定位Windows题库读取逻辑")

    MAIN.write_text(text, encoding="utf-8", newline="\n")

    verify = MAIN.read_text(encoding="utf-8")
    if 'APP_VERSION = "2.1.2"' not in verify:
        raise RuntimeError("Windows版本号更新失败")
    if "9691道本地题库" not in verify:
        raise RuntimeError("Windows题库数量显示更新失败")
    if 'item.get("ans", "A")' not in verify:
        raise RuntimeError("Windows正确答案字段读取逻辑更新失败")


if __name__ == "__main__":
    rebuild_bank()
    patch_windows_loader()
    print("Applied Windows 2.1.2 exact 9691 question-bank update")
