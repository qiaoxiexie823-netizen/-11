from __future__ import annotations

from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MAIN = ROOT / "windows-assistant" / "main.py"


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if old not in text:
        if new in text:
            return text
        raise RuntimeError(f"无法定位补丁位置：{label}")
    return text.replace(old, new, 1)


def patch() -> None:
    text = MAIN.read_text(encoding="utf-8").replace("\r\n", "\n")

    text = replace_once(
        text,
        'APP_VERSION = "2.1.2"',
        'APP_VERSION = "2.1.3"',
        "version",
    )

    text = replace_once(
        text,
        '''MODE_INTERVAL = {
    MODE_DISPLAY: 0.45,
    MODE_STABLE: 0.32,
    MODE_FAST: 0.18,
}
MODE_OPTION_THRESHOLD = {
    MODE_DISPLAY: 0.0,
    MODE_STABLE: 70.0,
    MODE_FAST: 58.0,
}

# 1280×720 客户区中四个横条选项的中心比例：左上、右上、左下、右下。
OPTION_CENTERS = (
    (0.395, 0.636),
    (0.716, 0.636),
    (0.395, 0.736),
    (0.716, 0.736),
)
''',
        '''MODE_INTERVAL = {
    MODE_DISPLAY: 0.16,
    MODE_STABLE: 0.11,
    MODE_FAST: 0.055,
}
MODE_OPTION_THRESHOLD = {
    MODE_DISPLAY: 0.0,
    MODE_STABLE: 74.0,
    MODE_FAST: 61.0,
}

# 依据用户提供的1280×720电脑版科举截图重新标定。
# 坐标均为游戏客户区比例，因此窗口移动不会影响定位。
EXPECTED_CLIENT_WIDTH = 1280
EXPECTED_CLIENT_HEIGHT = 720
OPTION_CENTERS = (
    (0.397, 0.644),
    (0.716, 0.644),
    (0.397, 0.744),
    (0.716, 0.744),
)
QUESTION_ROI = (0.250, 0.220, 0.875, 0.475)
OPTION_ROI = (0.235, 0.585, 0.875, 0.795)
QUESTION_CHANGE_THRESHOLD = 2.15
''',
        "timing and coordinates",
    )

    text = replace_once(
        text,
        '''user32 = ctypes.windll.user32


class RECT(ctypes.Structure):
''',
        '''user32 = ctypes.windll.user32

# 使用真实物理像素坐标，避免Windows 125%/150%缩放造成点击偏移。
try:
    ctypes.windll.shcore.SetProcessDpiAwareness(2)
except Exception:
    try:
        user32.SetProcessDPIAware()
    except Exception:
        pass


class RECT(ctypes.Structure):
''',
        "dpi awareness",
    )

    text = replace_once(
        text,
        '''def click_screen(hwnd: int, x: int, y: int) -> bool:
    """使用真实鼠标事件点击，游戏客户端兼容性高；点击后恢复原鼠标位置。"""
    if not user32.IsWindow(hwnd) or user32.IsIconic(hwnd):
        return False
    old = POINT()
    user32.GetCursorPos(ctypes.byref(old))
    try:
        user32.SetForegroundWindow(hwnd)
        time.sleep(0.045)
        user32.SetCursorPos(int(x), int(y))
        time.sleep(0.025)
        user32.mouse_event(0x0002, 0, 0, 0, 0)  # LEFTDOWN
        time.sleep(0.035)
        user32.mouse_event(0x0004, 0, 0, 0, 0)  # LEFTUP
        time.sleep(0.045)
        return True
    finally:
        user32.SetCursorPos(old.x, old.y)
''',
        '''def click_screen(hwnd: int, x: int, y: int) -> bool:
    """使用真实鼠标事件快速点击；按客户区定位，点击后恢复鼠标位置。"""
    if not user32.IsWindow(hwnd) or user32.IsIconic(hwnd):
        return False
    old = POINT()
    user32.GetCursorPos(ctypes.byref(old))
    try:
        if user32.GetForegroundWindow() != hwnd:
            user32.SetForegroundWindow(hwnd)
            time.sleep(0.012)
        user32.SetCursorPos(int(x), int(y))
        time.sleep(0.006)
        user32.mouse_event(0x0002, 0, 0, 0, 0)  # LEFTDOWN
        time.sleep(0.012)
        user32.mouse_event(0x0004, 0, 0, 0, 0)  # LEFTUP
        time.sleep(0.006)
        return True
    finally:
        user32.SetCursorPos(old.x, old.y)
''',
        "fast click",
    )

    text = replace_once(
        text,
        '''        self.last_question = ""
        self.last_clicked_question = ""
        self.same_question_count = 0
        self.miss_count = 0
        self.last_click_at = 0.0
''',
        '''        self.last_question = ""
        self.last_clicked_question = ""
        self.same_question_count = 0
        self.miss_count = 0
        self.last_click_at = 0.0
        self.last_question_probe: np.ndarray | None = None
        self.last_match_entry: QuestionEntry | None = None
        self.last_match_score = 0.0
''',
        "runtime state",
    )

    text = text.replace(
        'text="请保持《长安幻想》桌面版为 1280×720；窗口可以移动，但不要缩放。",',
        'text="游戏内分辨率保持1280×720；窗口可任意移动，程序按客户区实时跟随定位。",',
        1,
    )
    text = text.replace(
        'text="仅处理当前题目：不会点击“开始答题”，也不会点击“下一题/下一关”。正常识别和显示/点击目标控制在5秒内。",',
        'text="只识别当前科举题目并点击正确答案；不会点击开始答题、下一题或下一关。下一题画面变化后立即重新识别。",',
        1,
    )

    text = replace_once(
        text,
        '''        self.last_question = ""
        self.last_clicked_question = ""
        self.same_question_count = 0
        self.miss_count = 0
        self.worker = threading.Thread(target=self._recognition_loop, daemon=True)
''',
        '''        self.last_question = ""
        self.last_clicked_question = ""
        self.same_question_count = 0
        self.miss_count = 0
        self.last_question_probe = None
        self.last_match_entry = None
        self.last_match_score = 0.0
        self.worker = threading.Thread(target=self._recognition_loop, daemon=True)
''',
        "start reset",
    )

    start = text.index("    def _recognition_loop(self) -> None:\n")
    end = text.index("    def _run_ocr(self, image: np.ndarray) -> tuple[list[OCRLine], str]:\n", start)
    new_loop = '''    @staticmethod
    def _roi_rect(window: WindowInfo, roi: tuple[float, float, float, float]) -> tuple[int, int, int, int]:
        left_ratio, top_ratio, right_ratio, bottom_ratio = roi
        left = window.left + int(window.width * left_ratio)
        top = window.top + int(window.height * top_ratio)
        width = max(8, int(window.width * (right_ratio - left_ratio)))
        height = max(8, int(window.height * (bottom_ratio - top_ratio)))
        return left, top, width, height

    @staticmethod
    def _grab_array(capture: mss.mss, rect: tuple[int, int, int, int]) -> np.ndarray:
        left, top, width, height = rect
        shot = capture.grab({"left": left, "top": top, "width": width, "height": height})
        # MSS原生BGRA直接转NumPy，避免PIL整图转换带来的额外开销。
        return np.asarray(shot, dtype=np.uint8)[:, :, :3].copy()

    @staticmethod
    def _make_question_probe(image: np.ndarray) -> np.ndarray:
        # 只取稀疏像素判断题干画面是否变化；没有变化时完全跳过OCR。
        return image[::10, ::10, :].astype(np.int16, copy=False)

    def _question_has_changed(self, probe: np.ndarray) -> bool:
        previous = self.last_question_probe
        if previous is None or previous.shape != probe.shape:
            return True
        return float(np.mean(np.abs(probe - previous))) >= QUESTION_CHANGE_THRESHOLD

    def _try_click_visible_option(
        self,
        capture: mss.mss,
        window: WindowInfo,
        entry: QuestionEntry,
        question_score: float,
    ) -> bool:
        mode = self.mode_var.get()
        if mode == MODE_DISPLAY or entry.question == self.last_clicked_question:
            return False

        option_left, option_top, option_width, option_height = self._roi_rect(window, OPTION_ROI)
        option_image = self._grab_array(
            capture,
            (option_left, option_top, option_width, option_height),
        )
        option_lines, _option_text = self._run_ocr(option_image)
        option = self._find_answer_option(
            option_lines,
            entry.answer,
            window,
            option_left,
            option_top,
        )
        if option is None:
            return False

        option_index, option_score = option
        threshold = MODE_OPTION_THRESHOLD.get(mode, 74.0)
        if option_score < threshold:
            return False
        if time.monotonic() - self.last_click_at < 0.18:
            return False

        self._click_option(window, option_index, entry, question_score, option_score)
        return entry.question == self.last_clicked_question

    def _recognition_loop(self) -> None:
        assert self.question_bank is not None
        assert self.ocr_engine is not None
        with mss.mss() as capture:
            while not self.stop_event.is_set():
                selected = self.selected_window
                if selected is None:
                    self.stop_event.wait(0.20)
                    continue

                window = read_window_info(selected.hwnd, selected.title)
                if window is None:
                    self.root.after(0, lambda: self.status_var.set("游戏窗口已关闭或最小化"))
                    self.stop_event.wait(0.35)
                    continue
                self.selected_window = window

                if abs(window.width - EXPECTED_CLIENT_WIDTH) > 3 or abs(window.height - EXPECTED_CLIENT_HEIGHT) > 3:
                    self.last_question_probe = None
                    self.root.after(
                        0,
                        lambda size=f"{window.width}×{window.height}": self.status_var.set(
                            f"当前客户区{size}，请将游戏内分辨率设为1280×720"
                        ),
                    )
                    self.stop_event.wait(0.25)
                    continue

                cycle_started = time.perf_counter()
                try:
                    question_rect = self._roi_rect(window, QUESTION_ROI)
                    question_image = self._grab_array(capture, question_rect)
                    probe = self._make_question_probe(question_image)
                    changed = self._question_has_changed(probe)

                    # 题干没变时，不重复跑题目OCR。若正确选项上次没识别到，只重试很小的选项区域。
                    if not changed and self.last_match_entry is not None:
                        if (
                            self.mode_var.get() != MODE_DISPLAY
                            and self.last_match_entry.question != self.last_clicked_question
                        ):
                            self._try_click_visible_option(
                                capture,
                                window,
                                self.last_match_entry,
                                self.last_match_score,
                            )
                        spent = time.perf_counter() - cycle_started
                        self.stop_event.wait(max(0.015, self._mode_interval() - spent))
                        continue

                    ocr_lines, full_text = self._run_ocr(question_image)
                    match = self.question_bank.find_best(
                        [line.text for line in ocr_lines],
                        full_text,
                    )

                    if match is None:
                        self.miss_count += 1
                        # 连续两次未匹配后记住当前非题目画面，直到画面变化再启动OCR。
                        if self.miss_count >= 2:
                            self.last_question_probe = probe.copy()
                            self.last_question = ""
                            self.last_clicked_question = ""
                            self.last_match_entry = None
                            self.same_question_count = 0
                            self.root.after(
                                0,
                                lambda: self.overlay.update(f"{self.mode_var.get()}\\n等待当前题目…"),
                            )
                    else:
                        self.miss_count = 0
                        entry, score = match
                        is_new_question = entry.question != self.last_question
                        self.last_question_probe = probe.copy()
                        self.last_match_entry = entry
                        self.last_match_score = score

                        if is_new_question:
                            self.last_question = entry.question
                            self.last_clicked_question = ""
                            self.same_question_count = 1
                        else:
                            self.same_question_count += 1

                        elapsed_ms = (time.perf_counter() - cycle_started) * 1000.0
                        display = f"答案：{entry.answer}\\n匹配：{score:.0f}% · {elapsed_ms:.0f}ms"
                        self.root.after(0, lambda value=display: self.overlay.update(value))
                        self.root.after(0, lambda: self.status_var.set("已识别到当前题目"))

                        if self.mode_var.get() != MODE_DISPLAY:
                            # 新题识别完成后只对四个答案横条做一次小区域OCR并立即点击。
                            self._try_click_visible_option(capture, window, entry, score)
                except Exception as error:
                    self.root.after(
                        0,
                        lambda value=str(error): self.status_var.set(f"识别异常：{value[:70]}"),
                    )

                spent = time.perf_counter() - cycle_started
                interval = self._mode_interval()
                self.stop_event.wait(max(0.015, interval - min(spent, interval)))

'''
    text = text[:start] + new_loop + text[end:]

    text = text.replace(
        "if option_score >= threshold and time.monotonic() - self.last_click_at >= 0.55:",
        "if option_score >= threshold and time.monotonic() - self.last_click_at >= 0.18:",
    )

    MAIN.write_text(text, encoding="utf-8", newline="\n")

    verify = MAIN.read_text(encoding="utf-8")
    required = [
        'APP_VERSION = "2.1.3"',
        "EXPECTED_CLIENT_WIDTH = 1280",
        "QUESTION_ROI =",
        "OPTION_ROI =",
        "QUESTION_CHANGE_THRESHOLD",
        "_question_has_changed",
        "_try_click_visible_option",
        "SetProcessDpiAwareness",
        "elapsed_ms",
        "9691道本地题库",
        'item.get("ans", "A")',
    ]
    missing = [token for token in required if token not in verify]
    if missing:
        raise RuntimeError("2.1.3补丁校验失败：" + ", ".join(missing))


if __name__ == "__main__":
    patch()
    print("Applied Windows 2.1.3 fast 1280x720 quiz recognition patch")
