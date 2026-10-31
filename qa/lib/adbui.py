#!/usr/bin/env python3
"""Tiny adb/UIAutomator driver for PartFlasher QA E2E tests (stdlib only).

The app UI is Compose (no XML ids guaranteed), so most interactions locate nodes
by their visible text / content-desc and tap their bounds centre.

Examples:
    qa/lib/adbui.py launch
    qa/lib/adbui.py wait "已获取 Root 权限" --timeout 30
    qa/lib/adbui.py tap "重新申请 Root 权限"
    qa/lib/adbui.py tap "test_a" --contains
    qa/lib/adbui.py screenshot 01-launch
    qa/lib/adbui.py texts
    qa/lib/adbui.py dump --file qa/artifacts/ui.xml
"""
from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
ADB = os.environ.get("ADB", str(ROOT / ".android-sdk/platform-tools/adb"))
SERIAL = os.environ.get("ANDROID_SERIAL", "emulator-5554")
PKG = os.environ.get("APP_PACKAGE", "com.partflasher.app.debug")
ACTIVITY = os.environ.get(
    "APP_ACTIVITY", "com.partflasher.app.MainActivity"
)
ART = ROOT / "qa/artifacts"
SCREENSHOT_ALLOW_MISSING = os.environ.get("QA_SCREENSHOT_OPTIONAL", "0") == "1"


def adb(*args: str, timeout: float | None = 60.0) -> str:
    cmd = [ADB, "-s", SERIAL, *args]
    try:
        proc = subprocess.run(cmd, capture_output=True, timeout=timeout)
    except subprocess.TimeoutExpired:
        raise SystemExit(f"adb timed out: {' '.join(cmd)}")
    return proc.stdout.decode("utf-8", "replace")


def adb_bytes(*args: str, timeout: float | None = 120.0) -> bytes:
    cmd = [ADB, "-s", SERIAL, *args]
    proc = subprocess.run(cmd, capture_output=True, timeout=timeout)
    return proc.stdout


def shell(*args: str, timeout: float | None = 60.0) -> str:
    return adb("shell", *args, timeout=timeout)


def dump_xml() -> ET.Element:
    shell("uiautomator", "dump", "/sdcard/window_dump.xml", timeout=60)
    raw = adb("exec-out", "cat", "/sdcard/window_dump.xml", timeout=60)
    start = raw.find("<?xml")
    if start < 0:
        start = raw.find("<hierarchy")
    if start < 0:
        raise SystemExit(f"uiautomator dump returned no XML:\n{raw[:500]}")
    raw = raw[start:]
    end = raw.rfind("</hierarchy>")
    if end >= 0:
        raw = raw[: end + len("</hierarchy>")]
    return ET.fromstring(raw)


def nodes(root: ET.Element) -> list[dict]:
    result = []
    for el in root.iter("node"):
        bounds = el.get("bounds", "")
        m = re.match(r"\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]", bounds)
        cx = cy = 0
        if m:
            x1, y1, x2, y2 = map(int, m.groups())
            cx, cy = (x1 + x2) // 2, (y1 + y2) // 2
        result.append(
            {
                "text": el.get("text", ""),
                "desc": el.get("content-desc", ""),
                "id": el.get("resource-id", ""),
                "cls": el.get("class", ""),
                "clickable": el.get("clickable") == "true",
                "enabled": el.get("enabled") == "true",
                # uiautomator dump omits the attribute for visible nodes; only
                # an explicit "false" means hidden.
                "displayed": el.get("displayed", "true") != "false",
                "bounds": bounds,
                "cx": cx,
                "cy": cy,
            }
        )
    return result


def find(
    all_nodes: list[dict],
    text: str | None = None,
    contains: bool = False,
    desc: str | None = None,
    rid: str | None = None,
    clickable_only: bool = False,
    timeout: float = 5.0,
    interval: float = 0.5,
) -> list[dict]:
    deadline = time.time() + timeout
    while True:
        matched = []
        for n in all_nodes:
            if not n["displayed"]:
                continue
            if text is not None:
                hay = n["text"]
                if contains:
                    if text not in hay:
                        continue
                elif hay != text:
                    continue
            if desc is not None and n["desc"] != desc:
                continue
            if rid is not None and n["id"] != rid:
                continue
            if clickable_only and not (n["clickable"] or n["desc"] or n["text"]):
                continue
            matched.append(n)
        if matched and all_nodes:
            # Deduplicate identical bounds (Compose may emit nested nodes).
            seen = set()
            unique = []
            for n in matched:
                key = n["bounds"]
                if key in seen:
                    continue
                seen.add(key)
                unique.append(n)
            return unique
        if time.time() >= deadline:
            return []
        time.sleep(interval)
        all_nodes[:] = nodes(dump_xml())  # mutate caller's snapshot


def tap(x: int, y: int) -> None:
    shell("input", "tap", str(x), str(y))


def tap_node(node: dict) -> None:
    tap(node["cx"], node["cy"])


def visible_texts(all_nodes: list[dict]) -> list[str]:
    out = []
    for n in all_nodes:
        for key in ("text", "desc"):
            value = n[key].strip()
            if value:
                out.append(value)
    # Unique, preserving order
    return list(dict.fromkeys(out))


def screenshot(name: str) -> Path:
    ART.mkdir(parents=True, exist_ok=True)
    path = name if Path(name).is_absolute() else ART / name
    if not path.suffix:
        path = path.with_suffix(".png")
    data = adb_bytes("exec-out", "screencap", "-p")
    path.write_bytes(data)
    print(f"screenshot: {path} ({len(data)} bytes)")
    return path


def launch(wait_s: float = 2.0) -> None:
    adb("shell", "am", "start", "-W", "-n", f"{PKG}/{ACTIVITY}")
    time.sleep(wait_s)


def stop() -> None:
    adb("shell", "am", "force-stop", PKG)


def main(argv: list[str]) -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    sub = ap.add_subparsers(dest="cmd", required=True)

    p = sub.add_parser("dump", help="print UI hierarchy")
    p.add_argument("--file")
    p.add_argument("--texts", action="store_true", help="only print visible texts")
    p.add_argument("--json", action="store_true")

    p = sub.add_parser("tap", help="tap by text/desc/id")
    p.add_argument("value", nargs="?")
    p.add_argument("--contains", action="store_true")
    p.add_argument("--desc", action="store_true", help="match content-desc instead of text")
    p.add_argument("--id", action="store_true", help="match resource-id")
    p.add_argument("--index", type=int, default=0)
    p.add_argument("--xy", nargs=2, type=int, metavar=("X", "Y"))
    p.add_argument("--timeout", type=float, default=5.0)

    p = sub.add_parser("wait", help="wait for text/desc to appear")
    p.add_argument("value")
    p.add_argument("--contains", action="store_true")
    p.add_argument("--desc", action="store_true")
    p.add_argument("--timeout", type=float, default=30.0)
    p.add_argument("--interval", type=float, default=0.5)
    p.add_argument("--missing", action="store_true", help="wait until it disappears")

    p = sub.add_parser("screenshot", help="save a PNG screenshot")
    p.add_argument("name")
    p.add_argument("--optional", action="store_true")

    p = sub.add_parser("launch")
    p.add_argument("--wait", type=float, default=2.0)

    sub.add_parser("stop")
    sub.add_parser("texts", help="print visible texts (shortcut for dump --texts)")

    p = sub.add_parser("keyevent")
    p.add_argument("key")

    p = sub.add_parser("rotate")
    p.add_argument("value", choices=["0", "1", "2", "3", "auto"])

    p = sub.add_parser("night")
    p.add_argument("value", choices=["yes", "no", "auto"])

    p = sub.add_parser("screen-on")

    args = ap.parse_args(argv)

    if args.cmd == "dump":
        root = dump_xml()
        nl = nodes(root)
        if args.texts:
            for t in visible_texts(nl):
                print(t)
            return 0
        if args.json:
            print(json.dumps(nl, ensure_ascii=False, indent=1))
            return 0
        text = ET.tostring(root, encoding="unicode")
        if args.file:
            Path(args.file).write_text(text, encoding="utf-8")
            print(f"wrote {args.file}")
        else:
            print(text)
        return 0

    if args.cmd == "texts":
        for t in visible_texts(nodes(dump_xml())):
            print(t)
        return 0

    if args.cmd == "tap":
        if args.xy:
            tap(*args.xy)
            print(f"tapped {args.xy}")
            return 0
        if not args.value:
            raise SystemExit("tap requires a value or --xy")
        snapshot = nodes(dump_xml())
        matched = find(
            snapshot,
            text=None if (args.desc or args.id) else args.value,
            contains=args.contains,
            desc=args.value if args.desc else None,
            rid=args.value if args.id else None,
            timeout=args.timeout,
        )
        if not matched:
            print(f"NOT FOUND: {args.value}", file=sys.stderr)
            return 2
        idx = min(args.index, len(matched) - 1)
        node = matched[idx]
        tap_node(node)
        print(f"tapped '{args.value}' at {node['bounds']} (index {idx}/{len(matched) - 1})")
        return 0

    if args.cmd == "wait":
        deadline = time.time() + args.timeout
        while True:
            snapshot = nodes(dump_xml())
            matched = find(
                snapshot,
                text=None if args.desc else args.value,
                contains=args.contains,
                desc=args.value if args.desc else None,
                timeout=0.1,
            )
            found = bool(matched)
            if found != args.missing:
                print(f"{'FOUND' if found else 'GONE'}: {args.value}")
                return 0
            if time.time() >= deadline:
                print(f"TIMEOUT waiting for {args.value} (missing={args.missing})", file=sys.stderr)
                for t in visible_texts(snapshot)[:60]:
                    print(f"  visible: {t}", file=sys.stderr)
                return 1
            time.sleep(args.interval)

    if args.cmd == "screenshot":
        path = screenshot(args.name)
        if not path.exists() and not args.optional:
            return 1
        return 0

    if args.cmd == "launch":
        launch(args.wait)
        print(f"launched {PKG}/{ACTIVITY}")
        return 0

    if args.cmd == "stop":
        stop()
        print(f"stopped {PKG}")
        return 0

    if args.cmd == "keyevent":
        shell("input", "keyevent", args.key)
        return 0

    if args.cmd == "rotate":
        if args.value == "auto":
            shell("settings", "put", "system", "accelerometer_rotation", "1")
        else:
            shell("settings", "put", "system", "accelerometer_rotation", "0")
            shell("settings", "put", "system", "user_rotation", args.value)
        print(f"rotation set to {args.value}")
        return 0

    if args.cmd == "night":
        shell("cmd", "uimode", "night", args.value)
        print(f"night mode set to {args.value}")
        return 0

    if args.cmd == "screen-on":
        shell("input", "keyevent", "224")
        shell("input", "keyevent", "82")
        shell("wm", "dismiss-keyguard")
        return 0

    return 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
