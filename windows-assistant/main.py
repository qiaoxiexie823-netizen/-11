from __future__ import annotations

import base64
import ctypes
import hashlib
import json
import os
import re
import sys
import threading
import time
import unicodedata
import uuid
import winreg
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Iterable

import mss
import numpy as np
from cryptography.exceptions import InvalidSignature
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec
from PIL import Image
from rapidfuzz import fuzz, process
from rapidocr import RapidOCR
import tkinter as tk
from tkinter import messagebox, ttk

APP_NAME = "深情电脑答题助手"
APP_ID = "com.ruisi.changanpc"
APP_VERSION = "2.1.0"
PUBLIC_KEY_BASE64 = (
    "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE5BkWbuphUm0iSd3E54z+8QBy3HO3/"
    "SP11xAP4qKnF6YF/oQmxqUMdDpyjqM9w0sM0Iz9ZNV4IpLZveuz7qyOUA=="
)
WINDOW_KEYWORD = "长安幻想"

MODE_DISPLAY = "仅显示答案"
MODE_STABLE = "稳定自动点击"
MODE_FAST = "极速自动点击"
MODES = (MODE_DISPLAY, MODE_STABLE, MODE_FAST)
MODE_INTERVAL = {
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

PURPLE = "#5B3DAA"
PURPLE_DARK = "#3D2970"
PAGE_BG = "#F7F5FC"
TEXT_DARK = "#373640"
TEXT_MUTED = "#706C7C"
GREEN = "#187D4C"
ORANGE = "#BE681C"
RED = "#BE2D2D"

user32 = ctypes.windll.user32


class RECT(ctypes.Structure):
    _fields_ = [
        ("left", ctypes.c_long),
        ("top", ctypes.c_long),
        ("right", ctypes.c_long),
        ("bottom", ctypes.c_long),
    ]


class POINT(ctypes.Structure):
    _fields_ = [("x", ctypes.c_long), ("y", ctypes.c_long)]


@dataclass(frozen=True)
class WindowInfo:
    hwnd: int
    title: str
    left: int
    top: int
    width: int
    height: int


@dataclass(frozen=True)
class LicenseResult:
    valid: bool
    message: str
    expires_at_ms: int = 0
    permanent: bool = False
    type_label: str = ""


@dataclass(frozen=True)
class QuestionEntry:
    question: str
    answer: str
    normalized: str


@dataclass(frozen=True)
class OCRLine:
    text: str
    left: float
    top: float
    right: float
    bottom: float
    score: float = 1.0

    @property
    def center_x(self) -> float:
        return (self.left + self.right) / 2.0

    @property
    def center_y(self) -> float:
        return (self.top + self.bottom) / 2.0


def resource_path(name: str) -> Path:
    base = Path(getattr(sys, "_MEIPASS", Path(__file__).resolve().parent))
    return base / name


def app_data_dir() -> Path:
    root = Path(os.getenv("APPDATA", str(Path.home()))) / "ShenqingPCQuizAssistant"
    root.mkdir(parents=True, exist_ok=True)
    return root


def normalize_text(value: str) -> str:
    value = unicodedata.normalize("NFKC", value or "").lower()
    value = value.replace("o", "0").replace("l", "1")
    return re.sub(r"[^0-9a-z\u4e00-\u9fff]", "", value)


def normalize_machine_id(value: str) -> str:
    return re.sub(r"[^A-Z0-9]", "", (value or "").upper())


def machine_id() -> str:
    raw = ""
    try:
        with winreg.OpenKey(
            winreg.HKEY_LOCAL_MACHINE,
            r"SOFTWARE\Microsoft\Cryptography",
            0,
            winreg.KEY_READ | winreg.KEY_WOW64_64KEY,
        ) as key:
            raw = str(winreg.QueryValueEx(key, "MachineGuid")[0])
    except OSError:
        raw = f"{uuid.getnode()}|{os.getenv('COMPUTERNAME', '')}"

    digest = hashlib.sha256(f"{raw}|{APP_ID}".encode("utf-8")).hexdigest().upper()[:16]
    return "-".join(digest[index : index + 4] for index in range(0, 16, 4))


def verify_license(license_key: str, current_machine_id: str) -> LicenseResult:
    key = (license_key or "").strip()
    if not key:
        return LicenseResult(False, "请输入卡密")

    try:
        parts = key.split(".")
        if len(parts) != 5 or parts[0] != "SQ2":
            return LicenseResult(False, "卡密格式不正确")

        card_type = parts[1].upper()
        if card_type not in {"D", "U"}:
            return LicenseResult(False, "卡密类型不正确")

        expiry_token = parts[2].upper()
        nonce = parts[3].upper()
        if not re.fullmatch(r"[A-Z0-9]{6,16}", nonce):
            return LicenseResult(False, "卡密校验信息不正确")

        permanent = expiry_token == "P"
        expires_at_ms = (2**63 - 1) if permanent else int(expiry_token, 36) * 1000
        now_ms = int(time.time() * 1000)
        if not permanent and expires_at_ms <= now_ms:
            return LicenseResult(False, "卡密已到期")

        normalized_id = normalize_machine_id(current_machine_id)
        if len(normalized_id) != 16:
            return LicenseResult(False, "本机号读取失败")

        target = normalized_id if card_type == "D" else "*"
        payload = f"SQ2|{card_type}|{expiry_token}|{nonce}|{target}".encode("utf-8")
        public_key = serialization.load_der_public_key(base64.b64decode(PUBLIC_KEY_BASE64))
        signature_text = parts[4] + "=" * ((4 - len(parts[4]) % 4) % 4)
        signature = base64.urlsafe_b64decode(signature_text.encode("ascii"))
        public_key.verify(signature, payload, ec.ECDSA(hashes.SHA256()))

        return LicenseResult(
            True,
            "卡密验证成功",
            expires_at_ms=expires_at_ms,
            permanent=permanent,
            type_label="本机绑定卡密" if card_type == "D" else "通用卡密",
        )
    except InvalidSignature:
        return LicenseResult(False, "卡密与本机号不匹配或已损坏")
    except (ValueError, TypeError, OverflowError):
        return LicenseResult(False, "卡密验证失败")
    except Exception:
        return LicenseResult(False, "卡密验证失败")


def read_window_info(hwnd: int, title: str = "") -> WindowInfo | None:
    if not user32.IsWindow(hwnd) or user32.IsIconic(hwnd):
        return None
    rect = RECT()
    if not user32.GetClientRect(hwnd, ctypes.byref(rect)):
        return None
    point = POINT(0, 0)
    if not user32.ClientToScreen(hwnd, ctypes.byref(point)):
        return None
    width = rect.right - rect.left
    height = rect.bottom - rect.top
    if width <= 300 or height <= 200:
        return None
    return WindowInfo(int(hwnd), title, point.x, point.y, width, height)


def enum_game_windows() -> list[WindowInfo]:
    windows: list[WindowInfo] = []
    callback_type = ctypes.WINFUNCTYPE(ctypes.c_bool, ctypes.c_void_p, ctypes.c_void_p)

    def callback(hwnd: int, _lparam: int) -> bool:
        if not user32.IsWindowVisible(hwnd):
            return True
        length = user32.GetWindowTextLengthW(hwnd)
        if length <= 0:
            return True
        buffer = ctypes.create_unicode_buffer(length + 1)
        user32.GetWindowTextW(hwnd, buffer, length + 1)
        title = buffer.value.strip()
        if WINDOW_KEYWORD not in title:
            return True
        info = read_window_info(int(hwnd), title)
        if info is not None:
            windows.append(info)
        return True

    user32.EnumWindows(callback_type(callback), 0)
    return windows


def click_screen(hwnd: int, x: int, y: int) -> bool:
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


class QuestionBank:
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
        self.normalized_questions = [entry.normalized for entry in self.entries]

    @property
    def size(self) -> int:
        return len(self.entries)

    def find_best(self, lines: Iterable[str], full_text: str) -> tuple[QuestionEntry, float] | None:
        source_lines = [line.strip() for line in lines if line and line.strip()]
        segments: list[str] = []
        for index, line in enumerate(source_lines[:20]):
            if len(normalize_text(line)) >= 3:
                segments.append(line)
            if index + 1 < len(source_lines):
                segments.append(line + source_lines[index + 1])
            if index + 2 < len(source_lines):
                segments.append(line + source_lines[index + 1] + source_lines[index + 2])
        if full_text and len(full_text) < 420:
            segments.append(full_text)

        normalized_full = normalize_text(full_text)
        for entry in self.entries:
            if len(entry.normalized) >= 4 and entry.normalized in normalized_full:
                return entry, 99.9

        best_entry: QuestionEntry | None = None
        best_score = 0.0
        for segment in segments[:48]:
            normalized = normalize_text(segment)
            if len(normalized) < 3:
                continue
            result = process.extractOne(
                normalized,
                self.normalized_questions,
                scorer=fuzz.WRatio,
                score_cutoff=53,
            )
            if result is None:
                continue
            _choice, score, index = result
            entry = self.entries[index]
            length_ratio = min(len(normalized), len(entry.normalized)) / max(
                1, max(len(normalized), len(entry.normalized))
            )
            adjusted = float(score) * (0.82 + 0.18 * length_ratio)
            if adjusted > best_score:
                best_score = adjusted
                best_entry = entry

        if best_entry is None or best_score < 59:
            return None
        return best_entry, best_score


class OverlayWindow:
    def __init__(self, root: tk.Tk) -> None:
        self.window = tk.Toplevel(root)
        self.window.withdraw()
        self.window.overrideredirect(True)
        self.window.attributes("-topmost", True)
        self.window.attributes("-alpha", 0.94)
        self.window.configure(bg=PURPLE_DARK)
        self.label = tk.Label(
            self.window,
            text="等待识别题目…",
            bg=PURPLE_DARK,
            fg="white",
            font=("Microsoft YaHei UI", 14, "bold"),
            justify="left",
            anchor="w",
            padx=16,
            pady=12,
            wraplength=410,
        )
        self.label.pack(fill="both", expand=True)
        self._drag_start: tuple[int, int] | None = None
        self.label.bind("<ButtonPress-1>", self._begin_drag)
        self.label.bind("<B1-Motion>", self._drag)

    def _begin_drag(self, event: tk.Event) -> None:
        self._drag_start = (event.x_root - self.window.winfo_x(), event.y_root - self.window.winfo_y())

    def _drag(self, event: tk.Event) -> None:
        if self._drag_start is None:
            return
        self.window.geometry(f"+{event.x_root - self._drag_start[0]}+{event.y_root - self._drag_start[1]}")

    def show(self, game: WindowInfo | None = None) -> None:
        if game is not None:
            x = max(8, game.left + game.width - 455)
            y = max(8, game.top + 44)
            self.window.geometry(f"430x116+{x}+{y}")
        else:
            self.window.geometry("430x116+30+80")
        self.window.deiconify()
        self.window.lift()

    def hide(self) -> None:
        self.window.withdraw()

    def update(self, text: str, warning: bool = False) -> None:
        self.label.configure(text=text, bg="#8A3B30" if warning else PURPLE_DARK)
        self.window.configure(bg="#8A3B30" if warning else PURPLE_DARK)


class ShenqingPCApp:
    def __init__(self) -> None:
        self.root = tk.Tk()
        self.root.title(f"{APP_NAME} {APP_VERSION}")
        self.root.geometry("650x650")
        self.root.minsize(650, 650)
        self.root.configure(bg=PAGE_BG)
        self.root.protocol("WM_DELETE_WINDOW", self.close)

        self.machine_id = machine_id()
        self.config_path = app_data_dir() / "config.json"
        self.saved_config = self._load_config()
        self.active_license = verify_license(self.saved_config.get("license", ""), self.machine_id)

        self.windows: dict[str, WindowInfo] = {}
        self.selected_window: WindowInfo | None = None
        self.ocr_engine: RapidOCR | None = None
        self.question_bank: QuestionBank | None = None
        self.engine_lock = threading.Lock()
        self.engine_ready = threading.Event()
        self.engine_error: str | None = None
        self.stop_event = threading.Event()
        self.worker: threading.Thread | None = None
        self.overlay = OverlayWindow(self.root)
        self.last_question = ""
        self.last_clicked_question = ""
        self.same_question_count = 0
        self.miss_count = 0
        self.last_click_at = 0.0

        self._configure_style()
        self.container = tk.Frame(self.root, bg=PAGE_BG)
        self.container.pack(fill="both", expand=True, padx=22, pady=18)
        self.countdown_var = tk.StringVar(value="卡密未激活")
        self.status_var = tk.StringVar(value="等待启动")
        self.window_var = tk.StringVar()
        saved_mode = self.saved_config.get("mode", MODE_STABLE)
        self.mode_var = tk.StringVar(value=saved_mode if saved_mode in MODES else MODE_STABLE)
        self.manual_var = tk.StringVar()

        if self.active_license.valid:
            self._build_main_page()
            self._warm_engine_async()
        else:
            self._build_activation_page()
        self._tick_license()

    def _configure_style(self) -> None:
        style = ttk.Style()
        style.theme_use("clam")
        style.configure("TFrame", background=PAGE_BG)
        style.configure("TLabel", background=PAGE_BG, foreground=TEXT_DARK, font=("Microsoft YaHei UI", 11))
        style.configure("Title.TLabel", font=("Microsoft YaHei UI", 24, "bold"), foreground=PURPLE_DARK)
        style.configure("Subtitle.TLabel", font=("Microsoft YaHei UI", 11), foreground=TEXT_MUTED)
        style.configure("Card.TFrame", background="white", relief="flat")
        style.configure("Card.TLabel", background="white", foreground=TEXT_DARK, font=("Microsoft YaHei UI", 11))
        style.configure("CardTitle.TLabel", background="white", foreground=PURPLE_DARK, font=("Microsoft YaHei UI", 16, "bold"))
        style.configure("Primary.TButton", background=PURPLE, foreground="white", font=("Microsoft YaHei UI", 11, "bold"), padding=10)
        style.map("Primary.TButton", background=[("active", "#6D4CC0")])
        style.configure("Secondary.TButton", background="white", foreground=PURPLE, font=("Microsoft YaHei UI", 10), padding=8)
        style.configure("Green.TLabel", background=PAGE_BG, foreground=GREEN, font=("Microsoft YaHei UI", 10, "bold"))

    def _clear(self) -> None:
        for child in self.container.winfo_children():
            child.destroy()

    def _header(self) -> None:
        ttk.Label(self.container, text=APP_NAME, style="Title.TLabel").pack(anchor="center")
        ttk.Label(
            self.container,
            text=f"Windows {APP_VERSION} · 3603道本地题库 · 当前题目自动点击",
            style="Subtitle.TLabel",
        ).pack(anchor="center", pady=(2, 14))

    def _card(self) -> ttk.Frame:
        card = ttk.Frame(self.container, style="Card.TFrame", padding=18)
        card.pack(fill="x", pady=7)
        return card

    def _build_activation_page(self) -> None:
        self._clear()
        self._header()
        card = self._card()
        ttk.Label(card, text="离线卡密激活", style="CardTitle.TLabel").pack(anchor="center")
        ttk.Label(
            card,
            text="复制本机号到原来的“深情卡密生成器”中生成卡密。支持本机绑定和通用卡密。",
            style="Card.TLabel",
            wraplength=540,
            justify="center",
        ).pack(pady=(8, 15))

        ttk.Label(card, text="本机号", style="Card.TLabel").pack(anchor="center")
        machine_entry = ttk.Entry(card, justify="center", font=("Consolas", 14))
        machine_entry.insert(0, self.machine_id)
        machine_entry.configure(state="readonly")
        machine_entry.pack(fill="x", pady=(4, 8))
        ttk.Button(card, text="复制本机号", style="Secondary.TButton", command=self.copy_machine_id).pack(fill="x")

        ttk.Label(card, text="卡密", style="Card.TLabel").pack(anchor="w", pady=(15, 3))
        self.license_entry = ttk.Entry(card, font=("Consolas", 11))
        self.license_entry.pack(fill="x", ipady=8)
        self.activation_status = ttk.Label(card, text="无需联网验证", style="Card.TLabel")
        self.activation_status.pack(anchor="center", pady=9)
        ttk.Button(card, text="激活并进入", style="Primary.TButton", command=self.activate).pack(fill="x")

    def _build_main_page(self) -> None:
        self._clear()
        self._header()
        top = ttk.Frame(self.container)
        top.pack(fill="x")
        ttk.Label(top, textvariable=self.countdown_var, style="Green.TLabel").pack(side="right")

        window_card = self._card()
        ttk.Label(window_card, text="游戏窗口", style="CardTitle.TLabel").pack(anchor="w")
        row = ttk.Frame(window_card, style="Card.TFrame")
        row.pack(fill="x", pady=(10, 4))
        self.window_combo = ttk.Combobox(row, textvariable=self.window_var, state="readonly")
        self.window_combo.pack(side="left", fill="x", expand=True, padx=(0, 8), ipady=4)
        self.window_combo.bind("<<ComboboxSelected>>", self._on_window_selected)
        ttk.Button(row, text="刷新窗口", style="Secondary.TButton", command=self.refresh_windows).pack(side="right")
        ttk.Label(
            window_card,
            text="请保持《长安幻想》桌面版为 1280×720；窗口可以移动，但不要缩放。",
            style="Card.TLabel",
        ).pack(anchor="w", pady=(4, 0))

        control_card = self._card()
        ttk.Label(control_card, text="识题模式", style="CardTitle.TLabel").pack(anchor="w")
        mode_row = ttk.Frame(control_card, style="Card.TFrame")
        mode_row.pack(fill="x", pady=(10, 6))
        ttk.Label(mode_row, text="运行模式", style="Card.TLabel").pack(side="left")
        mode_combo = ttk.Combobox(
            mode_row,
            textvariable=self.mode_var,
            values=list(MODES),
            width=18,
            state="readonly",
        )
        mode_combo.pack(side="right")
        mode_combo.bind("<<ComboboxSelected>>", self._on_mode_selected)

        ttk.Label(
            control_card,
            text="仅处理当前题目：不会点击“开始答题”，也不会点击“下一题/下一关”。正常识别和显示/点击目标控制在5秒内。",
            style="Card.TLabel",
            wraplength=550,
            justify="left",
        ).pack(anchor="w", pady=(4, 8))

        buttons = ttk.Frame(control_card, style="Card.TFrame")
        buttons.pack(fill="x", pady=(5, 8))
        ttk.Button(buttons, text="开始识题", style="Primary.TButton", command=self.start_recognition).pack(side="left", fill="x", expand=True, padx=(0, 5))
        ttk.Button(buttons, text="停止", style="Secondary.TButton", command=self.stop_recognition).pack(side="left", fill="x", expand=True, padx=(5, 0))
        ttk.Label(control_card, textvariable=self.status_var, style="Card.TLabel").pack(anchor="center")

        manual_card = self._card()
        ttk.Label(manual_card, text="手动查题", style="CardTitle.TLabel").pack(anchor="w")
        self.manual_entry = ttk.Entry(manual_card, textvariable=self.manual_var, font=("Microsoft YaHei UI", 11))
        self.manual_entry.pack(fill="x", ipady=7, pady=(9, 6))
        ttk.Button(manual_card, text="查询答案", style="Secondary.TButton", command=self.manual_lookup).pack(fill="x")
        self.manual_result = ttk.Label(manual_card, text="题库与OCR正在后台预热", style="Card.TLabel", wraplength=540, justify="left")
        self.manual_result.pack(fill="x", pady=(9, 0))

        footer = ttk.Frame(self.container)
        footer.pack(fill="x", pady=(5, 0))
        ttk.Button(footer, text="隐藏/显示悬浮答案", style="Secondary.TButton", command=self.toggle_overlay).pack(side="left")
        ttk.Label(footer, text="深情制作", style="Subtitle.TLabel").pack(side="right")
        self.refresh_windows()

    def _load_config(self) -> dict[str, Any]:
        try:
            return json.loads(self.config_path.read_text(encoding="utf-8"))
        except Exception:
            return {}

    def _save_config(self, values: dict[str, Any]) -> None:
        self.saved_config.update(values)
        self.config_path.write_text(json.dumps(self.saved_config, ensure_ascii=False, indent=2), encoding="utf-8")

    def copy_machine_id(self) -> None:
        self.root.clipboard_clear()
        self.root.clipboard_append(self.machine_id)
        self.root.update()
        messagebox.showinfo(APP_NAME, "本机号已复制")

    def activate(self) -> None:
        key = self.license_entry.get().strip()
        result = verify_license(key, self.machine_id)
        if not result.valid:
            self.activation_status.configure(text=result.message, foreground=RED)
            return
        self._save_config({"license": key})
        self.active_license = result
        messagebox.showinfo(APP_NAME, "卡密激活成功")
        self._build_main_page()
        self._warm_engine_async()

    def _tick_license(self) -> None:
        if self.active_license.valid:
            refreshed = verify_license(self.saved_config.get("license", ""), self.machine_id)
            self.active_license = refreshed
            if refreshed.valid:
                if refreshed.permanent:
                    self.countdown_var.set("卡密：永久有效")
                else:
                    remaining = max(0, refreshed.expires_at_ms - int(time.time() * 1000)) // 1000
                    days, remainder = divmod(remaining, 86400)
                    hours, remainder = divmod(remainder, 3600)
                    minutes, seconds = divmod(remainder, 60)
                    self.countdown_var.set(f"卡密剩余：{days}天 {hours:02d}:{minutes:02d}:{seconds:02d}")
            else:
                self.stop_recognition()
                self._build_activation_page()
        self.root.after(1000, self._tick_license)

    def refresh_windows(self) -> None:
        found = enum_game_windows()
        self.windows.clear()
        display_names: list[str] = []
        for item in found:
            name = f"{item.title}  [{item.width}×{item.height}]"
            if name in self.windows:
                name += f" #{item.hwnd}"
            self.windows[name] = item
            display_names.append(name)
        self.window_combo.configure(values=display_names)
        if display_names:
            self.window_var.set(display_names[0])
            self.selected_window = self.windows[display_names[0]]
            self.status_var.set("已找到游戏窗口")
        else:
            self.window_var.set("")
            self.selected_window = None
            self.status_var.set("未找到《长安幻想》窗口")

    def _on_window_selected(self, _event: tk.Event | None = None) -> None:
        self.selected_window = self.windows.get(self.window_var.get())

    def _on_mode_selected(self, _event: tk.Event | None = None) -> None:
        mode = self.mode_var.get()
        if mode not in MODES:
            mode = MODE_STABLE
            self.mode_var.set(mode)
        self._save_config({"mode": mode})
        self.last_clicked_question = ""
        self.same_question_count = 0
        self.status_var.set(f"已切换：{mode}")

    def _warm_engine_async(self) -> None:
        if self.engine_ready.is_set() or (self.worker and self.worker.is_alive()):
            return

        def warm() -> None:
            try:
                self._ensure_engine()
                self.root.after(0, lambda: self.status_var.set("题库与OCR已就绪"))
                if hasattr(self, "manual_result"):
                    self.root.after(0, lambda: self.manual_result.configure(text=f"题库已载入 {self.question_bank.size if self.question_bank else 0} 道题"))
            except Exception as error:
                self.engine_error = str(error)
                self.root.after(0, lambda: self.status_var.set("OCR加载失败，请重新启动"))

        threading.Thread(target=warm, daemon=True).start()

    def _ensure_engine(self) -> None:
        if self.engine_ready.is_set():
            return
        with self.engine_lock:
            if self.engine_ready.is_set():
                return
            if self.question_bank is None:
                self.question_bank = QuestionBank(resource_path("questions.jsonl"))
            if self.ocr_engine is None:
                self.ocr_engine = RapidOCR()
                # 小图预热一次，后续第一道题无需再初始化推理会话。
                warm_image = np.full((96, 320, 3), 255, dtype=np.uint8)
                try:
                    self.ocr_engine(warm_image)
                except Exception:
                    pass
            self.engine_ready.set()

    def start_recognition(self) -> None:
        if not self.active_license.valid:
            messagebox.showerror(APP_NAME, "卡密无效或已到期")
            return
        self._on_window_selected()
        if self.selected_window is None:
            self.refresh_windows()
        if self.selected_window is None:
            messagebox.showwarning(APP_NAME, "请先打开《长安幻想》桌面版")
            return
        if self.worker and self.worker.is_alive():
            self.status_var.set("识题已经在运行")
            return
        try:
            self.status_var.set("正在确认OCR模型…")
            self.root.update_idletasks()
            self._ensure_engine()
        except Exception as error:
            messagebox.showerror(APP_NAME, f"OCR 或题库加载失败：\n{error}")
            return

        self.stop_event.clear()
        current = read_window_info(self.selected_window.hwnd, self.selected_window.title)
        if current is not None:
            self.selected_window = current
        self.overlay.show(self.selected_window)
        self.overlay.update(f"{self.mode_var.get()}\n等待当前题目…")
        self.status_var.set("正在识别游戏窗口")
        self.last_question = ""
        self.last_clicked_question = ""
        self.same_question_count = 0
        self.miss_count = 0
        self.worker = threading.Thread(target=self._recognition_loop, daemon=True)
        self.worker.start()

    def stop_recognition(self) -> None:
        self.stop_event.set()
        self.status_var.set("已停止")
        self.overlay.update("识题已停止")

    def toggle_overlay(self) -> None:
        if self.overlay.window.state() == "withdrawn":
            self.overlay.show(self.selected_window)
        else:
            self.overlay.hide()

    def _mode_interval(self) -> float:
        return MODE_INTERVAL.get(self.mode_var.get(), 0.32)

    def _recognition_loop(self) -> None:
        assert self.question_bank is not None
        assert self.ocr_engine is not None
        with mss.mss() as capture:
            while not self.stop_event.is_set():
                selected = self.selected_window
                if selected is None:
                    time.sleep(0.5)
                    continue
                window = read_window_info(selected.hwnd, selected.title)
                if window is None:
                    self.root.after(0, lambda: self.status_var.set("游戏窗口已关闭或最小化"))
                    time.sleep(0.8)
                    continue
                self.selected_window = window

                cycle_started = time.perf_counter()
                try:
                    # 只截取答题白框和四个选项，不扫描开始答题或结算按钮。
                    crop_left = window.left + int(window.width * 0.225)
                    crop_top = window.top + int(window.height * 0.205)
                    crop_width = int(window.width * 0.665)
                    crop_height = int(window.height * 0.610)
                    shot = capture.grab(
                        {
                            "left": crop_left,
                            "top": crop_top,
                            "width": crop_width,
                            "height": crop_height,
                        }
                    )
                    image = Image.frombytes("RGB", shot.size, shot.bgra, "raw", "BGRX")
                    ocr_lines, full_text = self._run_ocr(np.asarray(image))
                    match = self.question_bank.find_best([line.text for line in ocr_lines], full_text)

                    if match is None:
                        self.miss_count += 1
                        if self.miss_count >= 2:
                            self.last_question = ""
                            self.last_clicked_question = ""
                            self.same_question_count = 0
                            self.root.after(0, lambda: self.overlay.update(f"{self.mode_var.get()}\n等待当前题目…"))
                    else:
                        self.miss_count = 0
                        entry, score = match
                        if entry.question == self.last_question:
                            self.same_question_count += 1
                        else:
                            self.last_question = entry.question
                            self.last_clicked_question = ""
                            self.same_question_count = 1

                        elapsed = time.perf_counter() - cycle_started
                        display = f"答案：{entry.answer}\n匹配：{score:.0f}% · {elapsed:.1f}秒"
                        self.root.after(0, lambda text=display: self.overlay.update(text))
                        self.root.after(0, lambda: self.status_var.set("已识别到当前题目"))

                        mode = self.mode_var.get()
                        if mode != MODE_DISPLAY and entry.question != self.last_clicked_question:
                            required_frames = 2 if mode == MODE_STABLE else 1
                            if self.same_question_count >= required_frames:
                                option = self._find_answer_option(
                                    ocr_lines,
                                    entry.answer,
                                    window,
                                    crop_left,
                                    crop_top,
                                )
                                if option is not None:
                                    option_index, option_score = option
                                    threshold = MODE_OPTION_THRESHOLD.get(mode, 70.0)
                                    if option_score >= threshold and time.monotonic() - self.last_click_at >= 0.55:
                                        self._click_option(window, option_index, entry, score, option_score)
                except Exception as error:
                    self.root.after(0, lambda text=str(error): self.status_var.set(f"识别异常：{text[:70]}"))

                spent = time.perf_counter() - cycle_started
                self.stop_event.wait(max(0.02, self._mode_interval() - min(spent, self._mode_interval())))

    def _run_ocr(self, image: np.ndarray) -> tuple[list[OCRLine], str]:
        assert self.ocr_engine is not None
        output = self.ocr_engine(image)
        lines: list[OCRLine] = []

        if hasattr(output, "txts"):
            texts = list(getattr(output, "txts") or [])
            boxes = list(getattr(output, "boxes") or [])
            scores = list(getattr(output, "scores") or [])
            for index, raw_text in enumerate(texts):
                text = str(raw_text).strip()
                if not text:
                    continue
                box = boxes[index] if index < len(boxes) else None
                score = float(scores[index]) if index < len(scores) else 1.0
                lines.append(self._ocr_line(text, box, score))
        else:
            payload = output[0] if isinstance(output, tuple) and output else output
            if isinstance(payload, list):
                for item in payload:
                    if not isinstance(item, (list, tuple)) or len(item) < 2:
                        continue
                    box = item[0]
                    text = str(item[1]).strip()
                    score = float(item[2]) if len(item) >= 3 else 1.0
                    if text:
                        lines.append(self._ocr_line(text, box, score))

        return lines, "\n".join(line.text for line in lines)

    @staticmethod
    def _ocr_line(text: str, box: Any, score: float) -> OCRLine:
        try:
            points = np.asarray(box, dtype=float).reshape(-1, 2)
            left = float(points[:, 0].min())
            right = float(points[:, 0].max())
            top = float(points[:, 1].min())
            bottom = float(points[:, 1].max())
            return OCRLine(text, left, top, right, bottom, score)
        except Exception:
            return OCRLine(text, 0.0, 0.0, 0.0, 0.0, score)

    def _find_answer_option(
        self,
        lines: list[OCRLine],
        answer_text: str,
        window: WindowInfo,
        crop_left: int,
        crop_top: int,
    ) -> tuple[int, float] | None:
        option_texts: list[list[str]] = [[], [], [], []]

        for line in lines:
            if line.right <= line.left or line.bottom <= line.top:
                continue
            global_x = crop_left + line.center_x
            global_y = crop_top + line.center_y
            x_ratio = (global_x - window.left) / max(1, window.width)
            y_ratio = (global_y - window.top) / max(1, window.height)

            # 只接受四个答案横条区域，排除题目、答题者和倒计时文字。
            if not (0.23 <= x_ratio <= 0.88 and 0.585 <= y_ratio <= 0.795):
                continue

            distances = [
                ((x_ratio - center_x) / 0.22) ** 2 + ((y_ratio - center_y) / 0.075) ** 2
                for center_x, center_y in OPTION_CENTERS
            ]
            index = int(np.argmin(distances))
            if distances[index] <= 2.2:
                option_texts[index].append(line.text)

        answer_candidates = [normalize_text(part) for part in answer_text.split("/") if normalize_text(part)]
        if not answer_candidates:
            answer_candidates = [normalize_text(answer_text)]

        best_index = -1
        best_score = 0.0
        for index, pieces in enumerate(option_texts):
            option_value = normalize_text("".join(pieces))
            if not option_value:
                continue
            for answer in answer_candidates:
                if answer == option_value or answer in option_value or option_value in answer:
                    score = 100.0 if len(answer) == len(option_value) else 94.0
                else:
                    score = float(fuzz.WRatio(answer, option_value))
                if score > best_score:
                    best_score = score
                    best_index = index

        return None if best_index < 0 else (best_index, best_score)

    def _click_option(
        self,
        window: WindowInfo,
        option_index: int,
        entry: QuestionEntry,
        question_score: float,
        option_score: float,
    ) -> None:
        center_x, center_y = OPTION_CENTERS[option_index]
        screen_x = window.left + int(window.width * center_x)
        screen_y = window.top + int(window.height * center_y)
        self.last_click_at = time.monotonic()

        success = click_screen(window.hwnd, screen_x, screen_y)
        if success:
            self.last_clicked_question = entry.question
            text = (
                f"答案：{entry.answer}\n"
                f"已点击第{option_index + 1}项 · 题目{question_score:.0f}%/选项{option_score:.0f}%"
            )
            self.root.after(0, lambda value=text: self.overlay.update(value))
            self.root.after(0, lambda: self.status_var.set("正确答案已点击"))
        else:
            self.last_click_at = 0.0
            self.root.after(0, lambda: self.status_var.set("点击未执行，正在重新识别"))

    def manual_lookup(self) -> None:
        query = self.manual_var.get().strip()
        if not query:
            messagebox.showinfo(APP_NAME, "请先输入题目或关键词")
            return
        try:
            self._ensure_engine()
        except Exception as error:
            messagebox.showerror(APP_NAME, f"题库加载失败：{error}")
            return
        assert self.question_bank is not None
        match = self.question_bank.find_best([query], query)
        if match is None:
            self.manual_result.configure(text="没有找到足够相近的题目")
            return
        entry, score = match
        self.manual_result.configure(text=f"答案：{entry.answer}\n题目：{entry.question}\n匹配：{score:.0f}%")

    def close(self) -> None:
        self.stop_event.set()
        try:
            self.overlay.window.destroy()
        except Exception:
            pass
        self.root.destroy()

    def run(self) -> None:
        self.root.mainloop()


if __name__ == "__main__":
    ShenqingPCApp().run()
