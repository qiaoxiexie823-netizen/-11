from __future__ import annotations

from collections import OrderedDict
from pathlib import Path
import base64
import json
import re
import unicodedata
import zlib

ASSET_PATH = Path("app/src/main/assets/questions.jsonl")
PART_PATTERN = "latest_bank_183_part*.b64"
EXPECTED_PARTS = 7
EXPECTED_LATEST_UNIQUE = 1953


def normalize(value: str) -> str:
    text = unicodedata.normalize("NFKC", str(value or "")).lower()
    text = text.replace("o", "0").replace("l", "1")
    text = re.sub(r"第\d+题|请选择|正确答案|答案", "", text)
    return re.sub(r"[\W_]+", "", text, flags=re.UNICODE)


def parse_jsonl(text: str, source: str) -> list[tuple[str, list[str]]]:
    records: list[tuple[str, list[str]]] = []
    for line_number, raw_line in enumerate(text.splitlines(), 1):
        line = raw_line.strip()
        if not line:
            continue
        try:
            item = json.loads(line)
        except Exception as exc:
            raise RuntimeError(f"{source} 第 {line_number} 行不是有效 JSON: {exc}") from exc
        question = str(item.get("q", "")).strip()
        raw_answers = item.get("a", [])
        if isinstance(raw_answers, str):
            raw_answers = [raw_answers]
        answers: list[str] = []
        if isinstance(raw_answers, list):
            for value in raw_answers:
                answer = str(value or "").strip()
                if answer and answer not in answers:
                    answers.append(answer)
        if question and answers and normalize(question):
            records.append((question, answers))
    return records


def load_latest() -> list[tuple[str, list[str]]]:
    parts = sorted(Path("tools").glob(PART_PATTERN))
    if len(parts) != EXPECTED_PARTS:
        raise RuntimeError(
            f"最新题库数据分片数量错误：期望 {EXPECTED_PARTS}，实际 {len(parts)}"
        )
    encoded = "".join(part.read_text(encoding="utf-8").strip() for part in parts)
    try:
        decoded = zlib.decompress(base64.b64decode(encoded)).decode("utf-8")
    except Exception as exc:
        raise RuntimeError(f"最新题库数据解压失败：{exc}") from exc
    latest = parse_jsonl(decoded, "最新题库")
    if len(latest) != EXPECTED_LATEST_UNIQUE:
        raise RuntimeError(
            f"最新题库条数校验失败：期望 {EXPECTED_LATEST_UNIQUE}，实际 {len(latest)}"
        )
    return latest


def main() -> None:
    current_text = ASSET_PATH.read_text(encoding="utf-8")
    current = parse_jsonl(current_text, "原题库")
    latest = load_latest()

    merged: OrderedDict[str, tuple[str, list[str]]] = OrderedDict()
    for question, answers in current:
        key = normalize(question)
        if key and key not in merged:
            merged[key] = (question, answers)

    original_unique = len(merged)
    overwritten = 0
    added = 0
    for question, answers in latest:
        key = normalize(question)
        if not key:
            continue
        if key in merged:
            overwritten += 1
        else:
            added += 1
        # 用户提供的最新题库优先覆盖同题旧答案。
        merged[key] = (question, answers)

    output_lines = [
        json.dumps({"q": question, "a": answers}, ensure_ascii=False, separators=(",", ":"))
        for question, answers in merged.values()
    ]
    if len(output_lines) < max(original_unique, EXPECTED_LATEST_UNIQUE):
        raise RuntimeError("合并后题库条数异常，已停止构建")

    ASSET_PATH.write_text("\n".join(output_lines) + "\n", encoding="utf-8", newline="\n")
    diagnostics = Path("generated-apk")
    diagnostics.mkdir(parents=True, exist_ok=True)
    (diagnostics / "question-bank-count.txt").write_text(
        "\n".join(
            [
                "source_records=9691",
                f"latest_unique={len(latest)}",
                f"original_unique={original_unique}",
                f"overwritten={overwritten}",
                f"added={added}",
                f"merged_total={len(output_lines)}",
            ]
        )
        + "\n",
        encoding="utf-8",
    )
    print(
        "Latest question bank merged:",
        f"original={original_unique}",
        f"latest={len(latest)}",
        f"overwritten={overwritten}",
        f"added={added}",
        f"total={len(output_lines)}",
    )


if __name__ == "__main__":
    main()
