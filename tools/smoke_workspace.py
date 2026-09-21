#!/usr/bin/env python3
"""Destructive ONLY to a disposable emulator's AIS data; never targets physical devices.
Run: python3 tools/smoke_workspace.py path/to/app-debug.apk --reset-test-data
"""
from __future__ import annotations
import argparse
import re
import subprocess
import time
import xml.etree.ElementTree as ET
from pathlib import Path

PACKAGE = "ai.aios.app"
COMPONENT = f"{PACKAGE}/.workspace.WorkspaceActivity"
OUTPUT = Path("smoke-workspace-results")


def adb(*args: str, timeout: int = 40) -> bytes:
    return subprocess.run(["adb", "-e", *args], check=True, stdout=subprocess.PIPE,
                          stderr=subprocess.PIPE, timeout=timeout).stdout


def tree() -> ET.Element:
    adb("shell", "uiautomator", "dump", "/sdcard/ais-workspace.xml")
    raw = adb("exec-out", "cat", "/sdcard/ais-workspace.xml")
    OUTPUT.mkdir(exist_ok=True)
    (OUTPUT / "window.xml").write_bytes(raw)
    return ET.fromstring(raw)


def find(text: str) -> ET.Element:
    deadline = time.monotonic() + 40
    while time.monotonic() < deadline:
        root = tree()
        for node in root.iter("node"):
            if node.get("text") == text and node.get("enabled") == "true":
                return node
        time.sleep(0.25)
    raise AssertionError(f"UI text not found: {text}")


def tap(text: str) -> None:
    node = find(text)
    bounds = list(map(int, re.findall(r"\d+", node.attrib["bounds"])))
    if len(bounds) != 4:
        raise AssertionError(f"Invalid bounds: {bounds}")
    left, top, right, bottom = bounds
    adb("shell", "input", "tap", str((left + right) // 2), str((top + bottom) // 2))


def snapshot() -> bytes:
    return adb("exec-out", "run-as", PACKAGE, "cat", "files/workspace-v1.bin")


def wait_status(*statuses: str) -> str:
    deadline = time.monotonic() + 15
    while time.monotonic() < deadline:
        data = snapshot()
        for status in statuses:
            if status.encode("ascii") in data:
                return status
        time.sleep(0.2)
    raise AssertionError(f"Missing persisted status: {statuses}")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path)
    parser.add_argument("--reset-test-data", action="store_true", required=True)
    args = parser.parse_args()
    if not args.apk.is_file():
        parser.error("APK not found")
    if adb("shell", "getprop", "ro.kernel.qemu").strip() != b"1":
        raise RuntimeError("Refusing to run: a disposable Android emulator is required")
    OUTPUT.mkdir(exist_ok=True)
    adb("install", "-r", str(args.apk), timeout=120)
    adb("shell", "pm", "clear", PACKAGE)
    # Clean test app state only. Do not grant Accessibility, notifications or calendar access.
    adb("shell", "am", "start", "-W", "-n", COMPONENT)
    tap("Выполнить")
    find("Связаться с Анной")
    assert snapshot().startswith(b"AIS1"), "Workspace was not persisted"
    tap("Готово")
    find("Выполнено локально")
    adb("shell", "am", "force-stop", PACKAGE)
    adb("shell", "am", "start", "-W", "-n", COMPONENT)
    find("Выполнено локально")
    print("PASS launch without key/permission setup, create, complete, force-stop/reload")
    tap("В календарь")
    result = wait_status("WAITING_APPROVAL", "BLOCKED")
    if result == "WAITING_APPROVAL":
        tap("Отклонить")
        wait_status("REJECTED")
        tap("В календарь")
        find("Требуется подтверждение")
        tap("Стоп")
        wait_status("CANCELLED")
        assert b"HANDED_OFF" not in snapshot(), "An unapproved handoff occurred"
        print("PASS native-capable proposal: Reject and Stop cause no handoff")
    else:
        print("PASS missing calendar is BLOCKED; calendar UI smoke not available on this image")
    tap("Приложения")
    find("Ручной режим всегда доступен")
    (OUTPUT / "screen.png").write_bytes(adb("exec-out", "screencap", "-p"))
    print("PASS manual applications recovery remains accessible")


if __name__ == "__main__":
    try:
        main()
    except Exception:
        OUTPUT.mkdir(exist_ok=True)
        try:
            (OUTPUT / "failure.png").write_bytes(adb("exec-out", "screencap", "-p"))
            (OUTPUT / "logcat.txt").write_bytes(adb("logcat", "-d", "-t", "1500"))
        except Exception:
            pass
        raise
