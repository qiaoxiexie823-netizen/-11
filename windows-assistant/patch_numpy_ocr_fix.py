from pathlib import Path

SOURCE = Path("windows-assistant/main.py")


def main() -> None:
    text = SOURCE.read_text(encoding="utf-8")
    text = text.replace('APP_VERSION = "2.1.0"', 'APP_VERSION = "2.1.1"', 1)

    replacements = {
        '            texts = list(getattr(output, "txts") or [])\n':
            '            raw_texts = getattr(output, "txts", None)\n'
            '            texts = [] if raw_texts is None else list(raw_texts)\n',
        '            boxes = list(getattr(output, "boxes") or [])\n':
            '            raw_boxes = getattr(output, "boxes", None)\n'
            '            boxes = [] if raw_boxes is None else list(raw_boxes)\n',
        '            scores = list(getattr(output, "scores") or [])\n':
            '            raw_scores = getattr(output, "scores", None)\n'
            '            scores = [] if raw_scores is None else list(raw_scores)\n',
    }

    for old, new in replacements.items():
        if old not in text:
            raise RuntimeError(f"Expected OCR source line not found: {old.strip()}")
        text = text.replace(old, new, 1)

    SOURCE.write_text(text, encoding="utf-8")
    print("Applied minimal NumPy-safe RapidOCR parser for Windows 2.1.1")


if __name__ == "__main__":
    main()
