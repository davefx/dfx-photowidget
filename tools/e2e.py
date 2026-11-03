#!/usr/bin/env python3
"""
End-to-end test of the widget on a real device or emulator.

Usage: python3 tools/e2e.py -s emulator-5554 [--apk app/build/outputs/apk/release/app-release.apk]
       [--fast]  (shorter waits)  [--no-reboot]

Works with the app UI in English or Spanish (matches both).

What it checks (all on the release build, without access to internal data):
  1. Adding the widget from the app opens the configuration.
  2. Picking photos with the system picker; 30 s interval.
  3. Consumption: photos requested (= decodes) with the home screen visible, with another app
     in front and with the screen off. The last two must be 0.
  4. The slideshow resumes when returning home; tapping changes the photo.
  5. Reconfigure with a folder (SAF) and hourly changes: NON-wakeup alarm; an alarm with
     another app in front decodes nothing; back on the home screen the photo changes.
  6. Reboot: the alarm is rescheduled and the widget is drawn again.
  7. batterystats: no wakelocks or wakeup alarms from the app. No crashes.
"""
import argparse
import os
import re
import subprocess
import sys
import tempfile
import time

sys.path.insert(0, os.path.dirname(__file__))
from ui import PKG, Device  # noqa: E402

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
results = []

# App texts in English (default) and Spanish: the device language decides which one is shown.
PICK_PHOTOS = "^(Pick photos|Elegir fotos)$"
EVERY_30S = "^(Every 30 seconds|Cada 30 segundos)$"
EVERY_HOUR = "^(Every hour|Cada hora)$"


def check(name, ok, detail=""):
    results.append((name, bool(ok), detail))
    print(f"  [{'OK' if ok else 'FAIL'}] {name}" + (f"  ({detail})" if detail else ""), flush=True)
    return ok


def make_photos(folder):
    """Six 4000×3000 solid-color JPEGs (with a white square in the center)."""
    from PIL import Image, ImageDraw
    cols = [(220, 40, 40), (40, 180, 60), (40, 80, 220), (230, 200, 30), (160, 40, 200), (30, 200, 210)]
    paths = []
    for i, c in enumerate(cols):
        p = os.path.join(folder, f"test{i}.jpg")
        im = Image.new("RGB", (4000, 3000), c)
        ImageDraw.Draw(im).rectangle([1800, 1300, 2200, 1700], fill=(255, 255, 255))
        im.save(p, quality=85)
        paths.append(p)
    return paths


class Run:
    def __init__(self, d: Device, apk, fast, workdir):
        self.d, self.apk, self.wd = d, apk, workdir
        self.wait = 40 if fast else 75
        self.shot = 0

    # ---------- helpers ----------
    def screenshot(self, tag):
        self.shot += 1
        p = os.path.join(self.wd, f"{self.d.serial}_{self.shot:02d}_{tag}.png")
        self.d.screenshot(p)
        return p

    def widget_color(self):
        """Color of a point in the photo (a quarter into the widget, outside the white square)."""
        from PIL import Image
        b = self.d.widget_bounds()
        if not b:
            return None
        x1, y1, x2, y2 = b
        p = os.path.join(self.wd, "px.png")
        self.d.screenshot(p)
        r, g, bl = Image.open(p).convert("RGB").getpixel((x1 + (x2 - x1) // 4, y1 + (y2 - y1) // 4))
        return (r // 32, g // 32, bl // 32)  # tolerates antialiasing and the animation

    def colors_during(self, seconds, step=5):
        seen = []
        end = time.time() + seconds
        while time.time() < end:
            c = self.widget_color()
            if c and (not seen or seen[-1] != c):
                seen.append(c)
            time.sleep(step)
        return seen

    def go_home(self):
        """Home screen with the widget in view. Only pages leftwards: swiping right from the
        first page opens the Google panel, which covers the home screen."""
        d = self.d
        d.wake()
        d.home()
        time.sleep(3)
        w, h = d.screen_size()
        for _ in range(3):
            if d.widget_bounds() is not None:
                return True
            d.sh(f"input swipe {w * 9 // 10} {h // 2} {w // 10} {h // 2} 300")
            time.sleep(2)
        return d.widget_bounds() is not None

    def other_app(self):
        self.d.sh("am start -a android.settings.SETTINGS")
        time.sleep(3)

    def pick_photos(self, n=3):
        d = self.d
        d.click(PICK_PHOTOS)
        pkg, nodes = PKG, []
        for _ in range(20):
            time.sleep(1.5)
            nodes = d.dump()
            pkg = nodes[0].get("package") if nodes else PKG
            if pkg != PKG and (d.find_all(desc="^Photo|^Foto|^Image", nodes=nodes)
                               or [x for x in nodes if re.search(r":id/(item_root|thumbnail|icon_thumb)", x.get("resource-id", ""))]):
                break
        if "media" in pkg:  # system photo picker (Android 11+ with module, 13+)
            items = d.find_all(desc="^Photo|^Foto|^Image", nodes=nodes)
            for it in items[:n]:
                d.tap_node(it)
                time.sleep(0.7)
            d.click(rid="button_add")
        else:
            # Fallback: DocumentsUI (ACTION_OPEN_DOCUMENT). On the headless Android 11 emulator it
            # ignores touch, so it is driven by keyboard: one photo per opening.
            for k in range(n):
                if k:
                    d.click(PICK_PHOTOS)
                    time.sleep(4)
                self.docui_pick(index=k)
                time.sleep(3)
            return pkg
        time.sleep(3)
        return pkg

    def docui_focus_first_item(self):
        d = self.d
        for _ in range(12):
            if any(e.get("focused") == "true" and (e.get("resource-id", "").endswith(":id/item_root")
                                                   or re.search(r"\.(jpg|png), ", e.get("content-desc", "")))
                   for e in d.dump()):
                return True
            d.key("KEYCODE_TAB")
            time.sleep(0.4)
        return False

    def docui_pick(self, index):
        """Picks photo number `index` from DCIM/Camera in DocumentsUI using only the keyboard."""
        d = self.d
        for _ in range(10):  # wait for it to load
            if d.find_all(desc=r"\.jpg, ") or d.find("^Camera$", timeout=0):
                break
            time.sleep(2)
        if not d.find_all(desc=r"\.jpg, "):
            # We are in DCIM: open Camera.
            if not self.docui_focus_first_item():
                raise LookupError("DocumentsUI: could not get focus")
            d.key("KEYCODE_ENTER")
            time.sleep(4)
        # Arrow keys do not move focus here; Tab does (it also stops on the preview icon).
        seen = []
        for _ in range(8 + 3 * index):
            f = [e.get("content-desc", "") for e in d.dump() if e.get("focused") == "true"]
            if f and re.search(r"\.(jpg|png), ", f[0]) and f[0] not in seen:
                seen.append(f[0])
                if len(seen) == index + 1:
                    d.key("KEYCODE_ENTER")
                    return
            d.key("KEYCODE_TAB")
            time.sleep(0.4)
        raise LookupError(f"DocumentsUI: could not reach photo {index}")

    def pick_dcim_folder(self):
        d = self.d
        d.click("^(Add folder|Añadir carpeta)$")
        use = "^(Use this folder|USE THIS FOLDER|Usar esta carpeta|USAR ESTA CARPETA)$"
        allow = "^(Allow|ALLOW|Permitir|PERMITIR)$"
        d.click(use, timeout=20)
        if d.find(allow, timeout=6) is None:
            # DocumentsUI not responding to touch (headless emulator): use the keyboard.
            for _ in range(15):
                f = [e for e in d.dump() if e.get("focused") == "true"]
                if f and re.search(use, f[0].get("text", ""), re.I):
                    d.key("KEYCODE_ENTER")
                    break
                d.key("KEYCODE_TAB")
                time.sleep(0.4)
        n = d.find(allow, timeout=10)
        if n is None:
            raise LookupError("folder permission dialog did not appear")
        d.tap_node(n)
        if d.find(allow, timeout=3) is not None:
            d.key("KEYCODE_TAB"); d.key("KEYCODE_TAB"); d.key("KEYCODE_ENTER")
        time.sleep(3)

    def choose(self, current, wanted):
        """Opens a dropdown and picks an option, scrolling the list if it is not in view."""
        d = self.d
        d.tap_node(d.scroll_to(current))
        time.sleep(1)
        for dy in (0, 1, 1, 1, -1, -1, -1, -1, -1):
            nodes = d.dump()
            n = d.find_all(wanted, nodes=nodes)
            if n:
                d.tap_node(n[0])
                time.sleep(1)
                return
            # Scroll INSIDE the popup list: swiping outside it closes it.
            lists = [x for x in nodes if x.get("class") == "android.widget.ListView"]
            if not lists:
                break
            x1, y1, x2, y2 = d.bounds(lists[0])
            xm, top, bot = (x1 + x2) // 2, y1 + (y2 - y1) // 5, y2 - (y2 - y1) // 5
            a, b = (top, bot) if dy > 0 else (bot, top)
            d.sh(f"input swipe {xm} {a} {xm} {b} 400")
            time.sleep(1)
        raise LookupError(f"not found: {wanted}")

    def save(self):
        self.d.tap_node(self.d.scroll_to("^(Save|Guardar)$"))
        time.sleep(4)

    # ---------- tests ----------
    def setup(self, photos):
        d = self.d
        print(f"== {d.serial}: Android SDK {d.sdk}, launcher {self.launcher()}")
        d.adb("uninstall", PKG)
        d.adb("install", self.apk, check=True)
        d.sh("mkdir -p /sdcard/DCIM/Camera")
        for p in photos:
            d.adb("push", p, "/sdcard/DCIM/Camera/")
            d.sh(f"am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file:///sdcard/DCIM/Camera/{os.path.basename(p)}")
        d.sh("settings put system screen_off_timeout 1800000")
        d.sh("dumpsys battery unplug")
        d.sh("dumpsys batterystats --reset")
        d.adb("logcat", "-b", "all", "-c")
        time.sleep(2)

    def launcher(self):
        out = self.d.sh("cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME")
        return out.strip().splitlines()[-1] if out.strip() else "?"

    def t_add_and_configure(self):
        d = self.d
        self.go_home()
        d.sh(f"am start -n {PKG}/.ConfigActivity")
        time.sleep(2)
        d.click("^(Add a widget|Añadir un widget)")
        time.sleep(2)
        for label in ("Add automatically", "Add to home screen", "Añadir automáticamente", "Añadir a la pantalla de inicio", "Add", "Añadir"):
            btn = [n for n in d.find_all(f"^{label}$") if n.get("clickable") == "true" and n.get("package") != PKG]
            if btn:
                d.tap_node(btn[0])
                break
        else:
            raise LookupError("add-widget dialog button not found")
        time.sleep(4)
        self.wid = (d.widget_ids() or [None])[-1]
        check("widget placed from the app", self.wid is not None, f"id {self.wid}")
        check("configuration opens by itself", d.find(PICK_PHOTOS, timeout=5) is not None)
        picker = self.pick_photos(3)
        got = d.find(r"\d+ (photos? selected|fotos? elegidas?)", timeout=5)
        check("photos picked with the picker", got is not None and got.get("text", "").strip().split()[-3] == "3",
              f"{picker}: {got.get('text') if got is not None else 'nothing'}")
        self.choose(EVERY_HOUR, EVERY_30S)
        self.choose("^(No border|Sin marco)$", "^(Medium|Medio)$")
        d.clear_log()
        self.save()
        self.go_home()
        time.sleep(5)
        n = d.requests(self.wid)
        self.screenshot("configured")
        check("widget draws the photo", n >= 1 and d.widget_bounds() is not None, f"{n} photos requested")
        a = d.alarms()
        check("30 s slideshow: only a daily non-wakeup alarm", a == [(False, 86_400_000)], str(a))

    def t_consumption(self):
        d, w = self.d, self.wait
        d.clear_log()
        colors = self.colors_during(w)
        n_home = d.requests(self.wid)
        check(f"home screen visible {w} s: photo changes", len(colors) >= 2, f"{len(colors)} photos seen, {n_home} decoded")
        self.other_app()
        d.clear_log()
        time.sleep(w)
        check(f"another app in front {w} s: 0 photos prepared", d.requests(self.wid) == 0, f"{d.requests(self.wid)}")
        self.go_home()
        d.key("KEYCODE_SLEEP")
        time.sleep(2)
        d.clear_log()
        time.sleep(w)
        check(f"screen off {w} s: 0 photos prepared", d.requests(self.wid) == 0, f"{d.requests(self.wid)}")
        self.go_home()
        colors = self.colors_during(45)
        check("slideshow continues back on the home screen", len(colors) >= 2, f"{len(colors)} photos seen")

    def wait_change(self, before, timeout=12):
        """Waits for the photo to change and settle (the crossfade briefly shows the background)."""
        end = time.time() + timeout
        last = None
        while time.time() < end:
            c = self.widget_color()
            if c is not None and c != before and c == last:
                return c
            last = c
            time.sleep(0.5)
        return before

    def t_tap(self):
        self.go_home()
        changes = 0
        for _ in range(3):
            b = self.d.widget_bounds()
            before = self.widget_color()
            self.d.tap((b[0] + b[2]) // 2, (b[1] + b[3]) // 2)
            if self.wait_change(before) != before:
                changes += 1
            time.sleep(2)
        check("tapping goes to the next photo", changes >= 2, f"{changes}/3 taps changed the photo")

    def t_hourly_folder(self):
        d = self.d
        d.sh(f"am start -n {PKG}/.ConfigActivity")
        time.sleep(2)
        d.click("^Widget 1")
        time.sleep(2)
        d.click(desc="^(Remove|Quitar)$")
        time.sleep(1)
        check("remove source", d.find("photos? selected|fotos? elegidas?", timeout=2) is None)
        self.pick_dcim_folder()
        check("folder added with the folder picker", d.find("DCIM", timeout=5) is not None)
        self.choose(EVERY_30S, EVERY_HOUR)
        self.save()
        self.go_home()
        time.sleep(5)
        a = d.alarms()
        check("hourly: 1 h non-wakeup RTC alarm", a == [(False, 3_600_000)], str(a))
        before = self.widget_color()
        # With another app in front, the alarm only records the next photo: nothing is decoded.
        self.other_app()
        d.clear_log()
        d.sh(f"am broadcast -n {PKG}/.PhotoWidgetProvider -a {PKG}.ALARM --ei appWidgetId {self.wid}")
        time.sleep(5)
        check("alarm with another app in front: 0 photos prepared", d.requests(self.wid) == 0, f"{d.requests(self.wid)}")
        self.go_home()
        after = self.wait_change(before)
        n = d.requests(self.wid)
        check("new photo shown back on the home screen", before != after and n >= 1, f"{before} -> {after}, {n} decoded")

    def t_reboot(self):
        d = self.d
        d.adb("reboot")
        time.sleep(10)
        d.wait_boot()
        time.sleep(15)
        d.sh("settings put system screen_off_timeout 1800000")
        d.adb("logcat", "-c")
        a = []
        for _ in range(30):  # BOOT_COMPLETED reaches apps a few seconds after boot
            a = d.alarms()
            if a:
                break
            time.sleep(3)
        check("after reboot, the alarm is rescheduled", a == [(False, 3_600_000)], str(a))
        for _ in range(8):  # the launcher takes a while to finish loading after boot
            if self.go_home():
                break
            time.sleep(10)
        time.sleep(5)
        n = d.requests(self.wid)
        self.screenshot("after_reboot")
        check("after reboot, the widget draws the photo", d.widget_bounds() is not None and self.widget_color() is not None, f"{n} decoded")

    def t_battery(self):
        d = self.d
        uid = re.search(r"userId=(\d+)", d.sh(f"dumpsys package {PKG}"))
        stats = d.sh(f"dumpsys batterystats {PKG}")
        wl = [l.strip() for l in stats.splitlines() if re.search(r"Wake lock|Wakeup alarm", l) and "photowidget" in l.lower()]
        check("no wakelocks or wakeup alarms (batterystats)", not wl, "; ".join(wl[:3]))
        cpu = re.search(r"Total cpu time: u=(\S+) s=(\S+)", stats)
        if cpu:
            print(f"     App CPU over the whole test: user {cpu.group(1)}, system {cpu.group(2)}")
        crashes = d.crashes()
        check("no crashes", not crashes, "; ".join(crashes[:2]))
        d.sh("dumpsys battery reset")
        return uid


def main():
    ap = argparse.ArgumentParser(description="End-to-end test of the widget over adb.")
    ap.add_argument("-s", "--serial", required=True, help="adb device serial")
    ap.add_argument("--apk", default=os.path.join(ROOT, "app/build/outputs/apk/release/app-release.apk"))
    ap.add_argument("--fast", action="store_true", help="shorter waits")
    ap.add_argument("--no-reboot", action="store_true", help="skip the reboot test")
    ap.add_argument("--out", default=None, help="folder for screenshots")
    a = ap.parse_args()
    wd = a.out or tempfile.mkdtemp(prefix="dfx-e2e-")
    os.makedirs(wd, exist_ok=True)
    r = Run(Device(a.serial), a.apk, a.fast, wd)
    steps = [r.t_add_and_configure, r.t_consumption, r.t_tap, r.t_hourly_folder]
    if not a.no_reboot:
        steps.append(r.t_reboot)
    steps.append(r.t_battery)
    r.setup(make_photos(wd))
    for s in steps:
        try:
            s()
        except Exception as e:  # a broken test does not stop the following ones
            check(s.__name__, False, f"{type(e).__name__}: {e}")
            r.screenshot("error_" + s.__name__)
    failed = [x for x in results if not x[1]]
    print(f"\n{a.serial}: {len(results) - len(failed)}/{len(results)} passed. Screenshots in {wd}")
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
