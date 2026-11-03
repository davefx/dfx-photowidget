"""Minimal helpers to drive an Android device over adb (no external dependencies)."""
import re
import subprocess
import time
import xml.etree.ElementTree as ET

PKG = "com.davefx.photowidget"


class Device:
    def __init__(self, serial):
        self.serial = serial

    # ---------- adb ----------
    def adb(self, *args, check=False, timeout=120):
        r = subprocess.run(["adb", "-s", self.serial, *args], capture_output=True, text=True, timeout=timeout)
        if check and r.returncode != 0:
            raise RuntimeError(f"adb {' '.join(args)}: {r.stderr or r.stdout}")
        return r.stdout

    def sh(self, cmd, timeout=120):
        return self.adb("shell", cmd, timeout=timeout)

    @property
    def sdk(self):
        return int(self.sh("getprop ro.build.version.sdk").strip())

    def wait_boot(self, timeout=300):
        self.adb("wait-for-device", timeout=timeout)
        end = time.time() + timeout
        while time.time() < end:
            if self.sh("getprop sys.boot_completed").strip() == "1":
                return
            time.sleep(2)
        raise TimeoutError("device did not boot")

    def key(self, k):
        self.sh(f"input keyevent {k}")

    def home(self):
        self.key("KEYCODE_HOME")

    def wake(self):
        self.key("KEYCODE_WAKEUP")
        self.sh("wm dismiss-keyguard")

    def screenshot(self, path):
        with open(path, "wb") as f:
            f.write(subprocess.run(["adb", "-s", self.serial, "exec-out", "screencap", "-p"], capture_output=True).stdout)

    # ---------- UI ----------
    def dump(self):
        for _ in range(3):
            out = self.sh("uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; cat /sdcard/ui.xml")
            i = out.find("<?xml")
            if i >= 0:
                try:
                    return list(ET.fromstring(out[i:].strip()).iter("node"))
                except ET.ParseError:
                    pass
            time.sleep(1)
        return []

    @staticmethod
    def center(node):
        x1, y1, x2, y2 = map(int, re.findall(r"\d+", node.get("bounds")))
        return (x1 + x2) // 2, (y1 + y2) // 2

    @staticmethod
    def bounds(node):
        return tuple(map(int, re.findall(r"\d+", node.get("bounds"))))

    def find_all(self, pattern=None, desc=None, rid=None, pkg=None, nodes=None):
        nodes = nodes if nodes is not None else self.dump()
        res = []
        for n in nodes:
            if pattern and not re.search(pattern, n.get("text", ""), re.I):
                continue
            if desc and not re.search(desc, n.get("content-desc", ""), re.I):
                continue
            if rid and not re.search(rid, n.get("resource-id", "")):
                continue
            if pkg and not re.search(pkg, n.get("package", "")):
                continue
            res.append(n)
        return res

    def find(self, pattern=None, desc=None, rid=None, pkg=None, timeout=10):
        end = time.time() + timeout
        while True:
            r = self.find_all(pattern, desc, rid, pkg)
            if r:
                return r[0]
            if time.time() > end:
                return None
            time.sleep(1)

    def tap(self, x, y):
        self.sh(f"input tap {x} {y}")

    def tap_node(self, node):
        self.tap(*self.center(node))

    def click(self, pattern=None, desc=None, rid=None, timeout=10):
        n = self.find(pattern, desc, rid, timeout=timeout)
        if n is None:
            raise LookupError(f"not found: {pattern or desc or rid}")
        self.tap_node(n)
        return n

    def scroll_to(self, pattern, max_swipes=8):
        for _ in range(max_swipes):
            n = self.find(pattern, timeout=0)
            if n is not None:
                return n
            w, h = self.screen_size()
            self.sh(f"input swipe {w // 2} {h * 3 // 4} {w // 2} {h // 3} 400")
            time.sleep(1)
        raise LookupError(f"not found while scrolling: {pattern}")

    def screen_size(self):
        m = re.search(r"(\d+)x(\d+)", self.sh("wm size"))
        return int(m.group(1)), int(m.group(2))

    def foreground_pkg(self):
        nodes = self.dump()
        return nodes[0].get("package") if nodes else ""

    # ---------- app ----------
    def widget_ids(self):
        out = self.sh("dumpsys appwidget")
        ids = set()
        for m in re.finditer(r"id=(\d+)[^\n]*\n(?:[^\n]*\n){0,3}?[^\n]*provider=ComponentInfo\{" + PKG, out):
            ids.add(int(m.group(1)))
        if not ids:  # alternative format
            blocks = out.split("[")
            for b in blocks:
                if PKG + "/" in b and "PhotoWidgetProvider" in b:
                    m = re.search(r"id=(\d+)", b)
                    if m:
                        ids.add(int(m.group(1)))
        return sorted(ids)

    def requests(self, wid=None):
        """Photos requested by the launcher (each one is a decode) since the last logcat -c."""
        log = self.adb("logcat", "-d", "-s", "Slideshow:D")
        pat = f"getViewAt widget={wid} " if wid is not None else "getViewAt widget="
        return log.count(pat)

    def clear_log(self):
        self.adb("logcat", "-c")

    def crashes(self):
        log = self.adb("logcat", "-d", "-b", "crash")
        return [l for l in log.splitlines() if PKG in l]

    def prefs(self):
        return self.sh(f"run-as {PKG} cat shared_prefs/widgets.xml")

    def pref(self, key):
        m = re.search(rf'name="{re.escape(key)}"[^>]*value="([^"]*)"', self.prefs())
        return m.group(1) if m else None

    def alarms(self):
        """App alarms: list of (is_wakeup, interval_ms)."""
        lines = self.sh("dumpsys alarm").splitlines()
        res = []
        for i, l in enumerate(lines):
            if "Alarm{" in l and PKG in l:
                body = []
                for j in range(i + 1, min(i + 8, len(lines))):
                    if "Alarm{" in lines[j]:
                        break
                    body.append(lines[j])
                body = "\n".join(body)
                if "ALARM" not in body and "ALARM" not in l:
                    continue
                wake = "WAKEUP" in l or bool(re.search(r"type[ =](0|2)\b", l + body))
                m = re.search(r"repeatInterval=(\d+)", body)
                res.append((wake, int(m.group(1)) if m else None))
        return res

    def widget_bounds(self):
        """Widget rectangle on the home screen (view with an app id)."""
        nodes = self.dump()
        for rid in (PKG + ":id/root", PKG + ":id/flipper", PKG + ":id/item_photo"):
            for n in nodes:
                if n.get("resource-id") == rid:
                    return self.bounds(n)
        return None
