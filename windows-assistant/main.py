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
PUBLIC_KEY_BASE64 = (
    "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE5BkWbuphUm0iSd3E54z+8QBy3HO3/"
    "SP11xAP4qKnF6YF/oQmxqUMdDpyjqM9w0sM0Iz9ZNV4IpLZveuz7qyOUA=="
)
WINDOW_KEYWORD = "长安幻想"

PURPLE = "#5B3DAA"
PURPLE_DARK = "#3D2970"
PAGE_BG = "#F7F5FC"
TEXT_DARK = "#373640"
TEXT_MUTED = "#706C7C"
GREEN = "#187D4C"
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

        rect = RECT()
        if not user32.GetClientRect(hwnd, ctypes.byref(rect)):
            return True
        point = POINT(0, 0)
        if not user32.ClientToScreen(hwnd, ctypes.byref(point)):
            return True
        width = rect.right - rect.left
        height = rect.bottom - rect.top
        if width > 300 and height > 200:
            windows.append(WindowInfo(int(hwnd), title, point.x, point.y, width, height))
        return True

    user32.EnumWindows(callback_type(callback), 0)
    return windows


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
        for index, line in enumerate(source_lines[:28]):
            if len(normalize_text(line)) >= 3:
                segments.append(line)
            if index + 1 < len(source_lines):
                segments.append(line + source_lines[index + 1])
            if index + 2 < len(source_lines):
                segments.append(line + source_lines[index + 1] + source_lines[index + 2])
        if full_text and len(full_text) < 600:
            segments.append(full_text)

        normalized_full = normalize_text(full_text)
        best_entry: QuestionEntry | None = None
        best_score = 0.0

        for entry in self.entries:
            if len(entry.normalized) >= 4 and entry.normalized in normalized_full:
                return entry, 99.9

        for segment in segments[:70]:
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
            length_ratio = min(len(normalized), len(self.entries[index].normalized)) / max(
                1, max(len(normalized), len(self.entries[index].normalized))
            )
            adjusted = float(score) * (0.82 + 0.18 * length_ratio)
            if adjusted > best_score:
                best_score = adjusted
                best_entry = self.entries[index]

        if best_entry is None or best_score < 60:
            return None
        return best_entry, best_score


class OverlayWindow:
    def __init__(self, root: tk.Tk) -> None:
        self.window = tk.Toplevel(root)
        self.window.withdraw()
        self.window.overrideredirect(True)
        self.window.attributes("-topmost", True)
        self.window.attributes("-alpha", 0.93)
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
            y = max(8, game.top + 55)
            self.window.geometry(f"430x128+{x}+{y}")
        else:
            self.window.geometry("430x128+30+80")
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
        self.root.title(APP_NAME)
        self.root.geometry("620x590")
        self.root.minsize(620, 590)
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
        self.stop_event = threading.Event()
        self.worker: threading.Thread | None = None
        self.overlay = OverlayWindow(self.root)
        self.last_question = ""
        self.miss_count = 0

        self._configure_style()
        self.container = tk.Frame(self.root, bg=PAGE_BG)
        self.container.pack(fill="both", expand=True, padx=22, pady=18)
        self.countdown_var = tk.StringVar(value="卡密未激活")
        self.status_var = tk.StringVar(value="等待启动")
        self.window_var = tk.StringVar()
        self.interval_var = tk.StringVar(value="0.7 秒")
        self.manual_var = tk.StringVar()

        if self.active_license.valid:
            self._build_main_page()
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
        style.configure("Danger.TLabel", background=PAGE_BG, foreground=RED, font=("Microsoft YaHei UI", 10, "bold"))

    def _clear(self) -> None:
        for child in self.container.winfo_children():
            child.destroy()

    def _header(self) -> None:
        ttk.Label(self.container, text=APP_NAME, style="Title.TLabel").pack(anchor="center")
        ttk.Label(
            self.container,
            text="《长安幻想》电脑端 · 本地题库 · 本地 OCR",
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
            text="复制本机号到原来的“深情卡密生成器”中生成卡密。支持本机绑定卡密和通用卡密。",
            style="Card.TLabel",
            wraplength=520,
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

        ttk.Label(
            self.container,
            text="卡密格式与手机端完全一致，由原卡密生成器签发。",
            style="Subtitle.TLabel",
        ).pack(anchor="center", pady=10)

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
            text="请保持《长安幻想》桌面版为 1280×720 窗口模式。",
            style="Card.TLabel",
        ).pack(anchor="w", pady=(4, 0))

        control_card = self._card()
        ttk.Label(control_card, text="识题控制", style="CardTitle.TLabel").pack(anchor="w")
        interval_row = ttk.Frame(control_card, style="Card.TFrame")
        interval_row.pack(fill="x", pady=(10, 6))
        ttk.Label(interval_row, text="识别间隔", style="Card.TLabel").pack(side="left")
        interval_combo = ttk.Combobox(
            interval_row,
            textvariable=self.interval_var,
            values=["0.5 秒", "0.7 秒", "1.0 秒"],
            width=10,
            state="readonly",
        )
        interval_combo.pack(side="right")

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
        self.manual_result = ttk.Label(manual_card, text="题库正在等待加载", style="Card.TLabel", wraplength=520, justify="left")
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

    def _ensure_engine(self) -> None:
        if self.question_bank is None:
            self.status_var.set("正在载入 3603 道题库…")
            self.root.update_idletasks()
            self.question_bank = QuestionBank(resource_path("questions.jsonl"))
            self.manual_result.configure(text=f"题库已载入 {self.question_bank.size} 道题")
        if self.ocr_engine is None:
            self.status_var.set("正在载入中文 OCR 模型…")
            self.root.update_idletasks()
            self.ocr_engine = RapidOCR()

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
            self._ensure_engine()
        except Exception as error:
            messagebox.showerror(APP_NAME, f"OCR 或题库加载失败：\n{error}")
            return
        self.stop_event.clear()
        self.overlay.show(self.selected_window)
        self.overlay.update("题库已载入，等待识别题目…")
        self.status_var.set("正在识别游戏窗口")
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

    def _interval_seconds(self) -> float:
        try:
            return float(self.interval_var.get().split()[0])
        except Exception:
            return 0.7

    def _window_is_valid(self, window: WindowInfo) -> bool:
        return bool(user32.IsWindow(window.hwnd) and not user32.IsIconic(window.hwnd))

    def _recognition_loop(self) -> None:
        assert self.question_bank is not None
        assert self.ocr_engine is not None
        with mss.mss() as capture:
            while not self.stop_event.is_set():
                window = self.selected_window
                if window is None or not self._window_is_valid(window):
                    self.root.after(0, lambda: self.status_var.set("游戏窗口已关闭或最小化"))
                    time.sleep(1.0)
                    continue
                try:
                    # Crop the question dialog and options while excluding most background UI.
                    crop_left = window.left + int(window.width * 0.11)
                    crop_top = window.top + int(window.height * 0.12)
                    crop_width = int(window.width * 0.76)
                    crop_height = int(window.height * 0.75)
                    shot = capture.grab(
                        {
                            "left": crop_left,
                            "top": crop_top,
                            "width": crop_width,
                            "height": crop_height,
                        }
                    )
                    image = Image.frombytes("RGB", shot.size, shot.bgra, "raw", "BGRX")
                    lines, full_text = self._run_ocr(np.asarray(image))
                    match = self.question_bank.find_best(lines, full_text)
                    if match is None:
                        self.miss_count += 1
                        if self.miss_count >= 3:
                            self.last_question = ""
                            self.root.after(0, lambda: self.overlay.update("正在等待下一道题…"))
                    else:
                        self.miss_count = 0
                        entry, score = match
                        if entry.question != self.last_question:
                            self.last_question = entry.question
                            display = f"答案：{entry.answer}\n题目：{entry.question}\n匹配：{score:.0f}%"
                            self.root.after(0, lambda text=display: self.overlay.update(text))
                            self.root.after(0, lambda: self.status_var.set("已识别到题目"))
                except Exception as error:
                    self.root.after(0, lambda text=str(error): self.status_var.set(f"识别异常：{text[:70]}"))
                self.stop_event.wait(self._interval_seconds())

    def _run_ocr(self, image: np.ndarray) -> tuple[list[str], str]:
        assert self.ocr_engine is not None
        output = self.ocr_engine(image)
        texts: list[str] = []

        if hasattr(output, "txts"):
            raw = getattr(output, "txts")
            if raw is not None:
                texts = [str(value).strip() for value in raw if str(value).strip()]
        elif isinstance(output, tuple):
            payload = output[0] if output else None
            if payload:
                for item in payload:
                    if isinstance(item, (list, tuple)) and len(item) >= 2:
                        value = str(item[1]).strip()
                        if value:
                            texts.append(value)
        elif isinstance(output, list):
            for item in output:
                if isinstance(item, (list, tuple)) and len(item) >= 2:
                    value = str(item[1]).strip()
                    if value:
                        texts.append(value)

        return texts, "\n".join(texts)

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
