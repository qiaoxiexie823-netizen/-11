from pathlib import Path

SOURCE = Path("windows-assistant/main.py")


def main() -> None:
    text = SOURCE.read_text(encoding="utf-8")
    text = text.replace('APP_VERSION = "2.1.0"', 'APP_VERSION = "2.1.1"', 1)

    start_marker = "    def _run_ocr(self, image: np.ndarray) -> tuple[list[OCRLine], str]:"
    end_marker = "    @staticmethod\n    def _ocr_line(text: str, box: Any, score: float) -> OCRLine:"
    start = text.index(start_marker)
    end = text.index(end_marker, start)

    replacement = '''    @staticmethod
    def _sequence(value: Any) -> list[Any]:
        """Safely convert RapidOCR list/tuple/NumPy outputs without truth-value checks."""
        if value is None:
            return []
        if isinstance(value, np.ndarray):
            return value.tolist()
        if isinstance(value, list):
            return value
        if isinstance(value, tuple):
            return list(value)
        try:
            return list(value)
        except (TypeError, ValueError):
            return []

    def _run_ocr(self, image: np.ndarray) -> tuple[list[OCRLine], str]:
        assert self.ocr_engine is not None
        output = self.ocr_engine(image)
        lines: list[OCRLine] = []

        if output is None:
            return lines, ""

        if hasattr(output, "txts"):
            texts = self._sequence(getattr(output, "txts", None))
            boxes = self._sequence(getattr(output, "boxes", None))
            scores = self._sequence(getattr(output, "scores", None))
            for index, raw_text in enumerate(texts):
                text = str(raw_text).strip()
                if not text:
                    continue
                box = boxes[index] if index < len(boxes) else None
                score_value = scores[index] if index < len(scores) else 1.0
                try:
                    score = float(score_value)
                except (TypeError, ValueError):
                    score = 1.0
                lines.append(self._ocr_line(text, box, score))
        else:
            payload: Any = output
            if isinstance(output, tuple):
                payload = output[0] if len(output) > 0 else None
            items = self._sequence(payload)
            for item in items:
                item_values = self._sequence(item)
                if len(item_values) < 2:
                    continue
                box = item_values[0]
                text = str(item_values[1]).strip()
                try:
                    score = float(item_values[2]) if len(item_values) >= 3 else 1.0
                except (TypeError, ValueError):
                    score = 1.0
                if text:
                    lines.append(self._ocr_line(text, box, score))

        return lines, "\\n".join(line.text for line in lines)

'''
    text = text[:start] + replacement + text[end:]
    SOURCE.write_text(text, encoding="utf-8")
    print("Applied NumPy-safe RapidOCR parser for Windows 2.1.1")


if __name__ == "__main__":
    main()
