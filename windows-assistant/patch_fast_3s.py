from pathlib import Path

SOURCE = Path("windows-assistant/main.py")

FAST_QUESTION_BANK = r'''class QuestionBank:
    def __init__(self, path: Path) -> None:
        self.entries: list[QuestionEntry] = []
        with path.open("r", encoding="utf-8") as handle:
            for line in handle:
                line = line.strip()
                if not line:
                    continue
                try:
                    item = json.loads(line)
                except json.JSONDecodeError:
                    continue
                question = str(item.get("q", "")).strip()
                answers = item.get("a") or []
                if not question or not answers:
                    continue
                answer = " / ".join(str(value).strip() for value in answers if str(value).strip())
                normalized = normalize_text(question)
                if answer and normalized:
                    self.entries.append(QuestionEntry(question, answer, normalized))

        self.gram_index: dict[str, list[int]] = {}
        for index, entry in enumerate(self.entries):
            for gram in self._grams(entry.normalized):
                self.gram_index.setdefault(gram, []).append(index)

    @staticmethod
    def _grams(value: str) -> set[str]:
        if len(value) < 2:
            return {value} if value else set()
        return {value[index : index + 2] for index in range(len(value) - 1)}

    def _candidate_ids(self, normalized: str, limit: int = 120) -> list[int]:
        counts: dict[int, int] = {}
        for gram in self._grams(normalized):
            for entry_id in self.gram_index.get(gram, ()):
                counts[entry_id] = counts.get(entry_id, 0) + 1
        if not counts:
            return []
        return [
            entry_id
            for entry_id, _count in sorted(
                counts.items(), key=lambda item: item[1], reverse=True
            )[:limit]
        ]

    @property
    def size(self) -> int:
        return len(self.entries)

    def find_best(
        self,
        lines: Iterable[str],
        full_text: str,
        minimum_score: float = 59.0,
    ) -> tuple[QuestionEntry, float] | None:
        source_lines = [line.strip() for line in lines if line and line.strip()][:18]
        segments: list[str] = []
        for index, line in enumerate(source_lines):
            if len(normalize_text(line)) >= 3:
                segments.append(line)
            if index + 1 < len(source_lines):
                segments.append(line + source_lines[index + 1])
            if index + 2 < len(source_lines) and len(segments) < 38:
                segments.append(line + source_lines[index + 1] + source_lines[index + 2])
        if full_text and len(full_text) <= 420:
            segments.append(full_text)

        normalized_full = normalize_text(full_text)
        if len(normalized_full) >= 4:
            for entry_id in self._candidate_ids(normalized_full, 180):
                entry = self.entries[entry_id]
                if len(entry.normalized) >= 4 and entry.normalized in normalized_full:
                    return entry, 99.9

        best_entry: QuestionEntry | None = None
        best_score = 0.0
        seen_segments: set[str] = set()
        for segment in segments[:40]:
            normalized = normalize_text(segment)
            if len(normalized) < 3 or normalized in seen_segments:
                continue
            seen_segments.add(normalized)
            candidate_ids = self._candidate_ids(normalized)
            for entry_id in candidate_ids:
                entry = self.entries[entry_id]
                if normalized in entry.normalized and len(normalized) >= 5:
                    coverage = len(normalized) / max(1, len(entry.normalized))
                    score = min(97.0, 78.0 + coverage * 19.0)
                else:
                    ratio = float(fuzz.WRatio(normalized, entry.normalized))
                    length_ratio = min(len(normalized), len(entry.normalized)) / max(
                        1, max(len(normalized), len(entry.normalized))
                    )
                    score = ratio * (0.84 + 0.16 * length_ratio)
                if score > best_score:
                    best_score = score
                    best_entry = entry

        if best_entry is None or best_score < minimum_score:
            return None
        return best_entry, best_score


'''

FAST_LOOP = r'''    def _recognition_loop(self) -> None:
        assert self.question_bank is not None
        assert self.ocr_engine is not None
        with mss.mss() as capture:
            while not self.stop_event.is_set():
                cycle_started = time.perf_counter()
                window = self.selected_window
                if window is None or not self._window_is_valid(window):
                    self.root.after(0, lambda: self.status_var.set("游戏窗口已关闭或最小化"))
                    time.sleep(0.5)
                    continue
                try:
                    # 1280×720 极速区域：优先只识别答题弹窗核心区域。
                    fast_region = {
                        "left": window.left + int(window.width * 0.12),
                        "top": window.top + int(window.height * 0.13),
                        "width": int(window.width * 0.73),
                        "height": int(window.height * 0.68),
                    }
                    shot = capture.grab(fast_region)
                    image = Image.frombytes("RGB", shot.size, shot.bgra, "raw", "BGRX")
                    if image.width > 920:
                        height = max(1, round(image.height * 920 / image.width))
                        image = image.resize((920, height), Image.Resampling.BILINEAR)
                    lines, full_text = self._run_ocr(np.asarray(image))
                    match = self.question_bank.find_best(lines, full_text, 59.0)

                    # 首次未匹配时立刻扩大一次识别范围，不等待下一轮。
                    if match is None:
                        wide_region = {
                            "left": window.left + int(window.width * 0.08),
                            "top": window.top + int(window.height * 0.08),
                            "width": int(window.width * 0.82),
                            "height": int(window.height * 0.82),
                        }
                        wide_shot = capture.grab(wide_region)
                        wide_image = Image.frombytes(
                            "RGB", wide_shot.size, wide_shot.bgra, "raw", "BGRX"
                        )
                        if wide_image.width > 1050:
                            height = max(1, round(wide_image.height * 1050 / wide_image.width))
                            wide_image = wide_image.resize(
                                (1050, height), Image.Resampling.BILINEAR
                            )
                        wide_lines, wide_text = self._run_ocr(np.asarray(wide_image))
                        lines.extend(wide_lines)
                        full_text = full_text + "\n" + wide_text
                        match = self.question_bank.find_best(lines, full_text, 57.0)

                    now = time.perf_counter()
                    if match is None:
                        self.miss_count += 1
                        if self.question_wait_started <= 0:
                            self.question_wait_started = now
                        # 3秒保护：接近时限时采用较低阈值给出“疑似答案”。
                        if now - self.question_wait_started >= 2.15:
                            match = self.question_bank.find_best(lines, full_text, 53.0)
                        if match is None and self.miss_count >= 2:
                            self.last_question = ""
                            self.root.after(
                                0,
                                lambda: self.overlay.update("正在快速识别下一道题…"),
                            )
                    if match is not None:
                        self.miss_count = 0
                        entry, score = match
                        elapsed = (
                            now - self.question_wait_started
                            if self.question_wait_started > 0
                            else now - cycle_started
                        )
                        self.question_wait_started = 0.0
                        if entry.question != self.last_question:
                            self.last_question = entry.question
                            prefix = "答案" if score >= 59 else "疑似答案"
                            display = (
                                f"{prefix}：{entry.answer}\n"
                                f"题目：{entry.question}\n"
                                f"匹配：{score:.0f}% · 用时：{elapsed:.1f}秒"
                            )
                            self.root.after(0, lambda text=display: self.overlay.update(text))
                            self.root.after(
                                0,
                                lambda value=elapsed: self.status_var.set(
                                    f"已识别到题目，用时 {value:.1f} 秒"
                                ),
                            )
                except Exception as error:
                    self.root.after(
                        0,
                        lambda text=str(error): self.status_var.set(
                            f"识别异常：{text[:70]}"
                        ),
                    )
                cycle_elapsed = time.perf_counter() - cycle_started
                self.stop_event.wait(max(0.03, self._interval_seconds() - cycle_elapsed))

'''


def main() -> None:
    text = SOURCE.read_text(encoding="utf-8")
    if "3秒保护" in text:
        print("Fast three-second patch already applied")
        return

    text = text.replace(
        'APP_NAME = "深情电脑答题助手"',
        'APP_NAME = "深情电脑答题助手 1.1 极速版"',
        1,
    )

    bank_start = text.index("class QuestionBank:")
    overlay_start = text.index("class OverlayWindow:")
    text = text[:bank_start] + FAST_QUESTION_BANK + text[overlay_start:]

    text = text.replace(
        '        self.miss_count = 0\n',
        '        self.miss_count = 0\n        self.question_wait_started = 0.0\n',
        1,
    )
    text = text.replace(
        'self.interval_var = tk.StringVar(value="0.7 秒")',
        'self.interval_var = tk.StringVar(value="0.25 秒")',
        1,
    )
    text = text.replace(
        'values=["0.5 秒", "0.7 秒", "1.0 秒"]',
        'values=["0.20 秒", "0.25 秒", "0.40 秒"]',
        1,
    )
    text = text.replace("            return 0.7\n", "            return 0.25\n", 1)

    warmup_old = '''        if self.ocr_engine is None:
            self.status_var.set("正在载入中文 OCR 模型…")
            self.root.update_idletasks()
            self.ocr_engine = RapidOCR()
'''
    warmup_new = '''        if self.ocr_engine is None:
            self.status_var.set("正在载入并预热中文 OCR 模型…")
            self.root.update_idletasks()
            self.ocr_engine = RapidOCR()
            try:
                # 启动前完成第一次模型推理，避免第一题额外等待。
                self.ocr_engine(np.full((96, 320, 3), 255, dtype=np.uint8))
            except Exception:
                pass
'''
    if warmup_old not in text:
        raise RuntimeError("Unable to locate OCR initialization block")
    text = text.replace(warmup_old, warmup_new, 1)

    loop_start = text.index("    def _recognition_loop(self) -> None:")
    ocr_start = text.index("    def _run_ocr(self, image: np.ndarray)")
    text = text[:loop_start] + FAST_LOOP + text[ocr_start:]

    SOURCE.write_text(text, encoding="utf-8")
    print("Applied Windows sub-three-second recognition patch")


if __name__ == "__main__":
    main()
