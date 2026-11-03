#!/usr/bin/env python3
"""
Prueba de extremo a extremo del widget en un dispositivo o emulador real.

Uso:  python3 tools/e2e.py -s emulator-5554 [--apk app/build/outputs/apk/release/app-release.apk]
      [--fast]  (esperas más cortas)  [--no-reboot]

Qué comprueba (todo con la release, sin acceso a datos internos):
  1. Añadir el widget desde la app y que se abra la configuración.
  2. Elegir fotos con el selector del sistema; intervalo de 30 s.
  3. Consumo: fotos pedidas (= decodificaciones) con el escritorio visible, con otra app delante
     y con la pantalla apagada. Las dos últimas deben ser 0.
  4. El pase se reanuda al volver al escritorio; tocar cambia la foto.
  5. Reconfigurar con una carpeta (SAF) y cambio cada hora: alarma NO despertadora; una alarma
     con otra app delante no decodifica nada; al volver al escritorio la foto cambia.
  6. Reinicio: la alarma se reprograma y el widget vuelve a pintarse.
  7. batterystats: ningún wakelock ni alarma despertadora de la app. Ningún cierre inesperado.
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


def check(name, ok, detail=""):
    results.append((name, bool(ok), detail))
    print(f"  [{'OK' if ok else 'FALLO'}] {name}" + (f"  ({detail})" if detail else ""), flush=True)
    return ok


def make_photos(folder):
    """Seis JPEG de 4000×3000 de colores lisos (con un cuadrado blanco central)."""
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

    # ---------- utilidades ----------
    def screenshot(self, tag):
        self.shot += 1
        p = os.path.join(self.wd, f"{self.d.serial}_{self.shot:02d}_{tag}.png")
        self.d.screenshot(p)
        return p

    def widget_color(self):
        """Color de un punto de la foto (a un cuarto del widget, fuera del cuadrado blanco)."""
        from PIL import Image
        b = self.d.widget_bounds()
        if not b:
            return None
        x1, y1, x2, y2 = b
        p = os.path.join(self.wd, "px.png")
        self.d.screenshot(p)
        r, g, bl = Image.open(p).convert("RGB").getpixel((x1 + (x2 - x1) // 4, y1 + (y2 - y1) // 4))
        return (r // 32, g // 32, bl // 32)  # tolera el antialias y la animación

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
        """Escritorio con el widget a la vista. Solo pasa páginas hacia la izquierda: hacia la
        derecha, desde la primera página, se abre el panel de Google y tapa el escritorio."""
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
        d.click("^Elegir fotos$")
        pkg, nodes = PKG, []
        for _ in range(20):
            time.sleep(1.5)
            nodes = d.dump()
            pkg = nodes[0].get("package") if nodes else PKG
            if pkg != PKG and (d.find_all(desc="^Photo|^Foto|^Image", nodes=nodes)
                               or [x for x in nodes if re.search(r":id/(item_root|thumbnail|icon_thumb)", x.get("resource-id", ""))]):
                break
        if "media" in pkg:  # selector de fotos del sistema (Android 11+ con módulo, 13+)
            items = d.find_all(desc="^Photo|^Foto|^Image", nodes=nodes)
            for it in items[:n]:
                d.tap_node(it)
                time.sleep(0.7)
            d.click(rid="button_add")
        else:
            # Respaldo: DocumentsUI (ACTION_OPEN_DOCUMENT). En el emulador sin ventana de Android 11
            # no responde al tacto, así que se maneja con teclado: una foto por apertura.
            for k in range(n):
                if k:
                    d.click("^Elegir fotos$")
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
        """Elige la foto número index de DCIM/Camera en DocumentsUI usando solo el teclado."""
        d = self.d
        for _ in range(10):  # esperar a que cargue
            if d.find_all(desc=r"\.jpg, ") or d.find("^Camera$", timeout=0):
                break
            time.sleep(2)
        if not d.find_all(desc=r"\.jpg, "):
            # Estamos en DCIM: entrar en Camera.
            if not self.docui_focus_first_item():
                raise LookupError("DocumentsUI: no consigo el foco")
            d.key("KEYCODE_ENTER")
            time.sleep(4)
        # Las flechas no mueven el foco aquí; Tab sí (pasa también por el icono de vista previa).
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
        raise LookupError(f"DocumentsUI: no llego a la foto {index}")

    def pick_dcim_folder(self):
        d = self.d
        d.click("^Añadir carpeta$")
        use = "^(Use this folder|USE THIS FOLDER|Usar esta carpeta|USAR ESTA CARPETA)$"
        allow = "^(Allow|ALLOW|Permitir|PERMITIR)$"
        d.click(use, timeout=20)
        if d.find(allow, timeout=6) is None:
            # DocumentsUI sin respuesta al tacto (emulador sin ventana): con teclado.
            for _ in range(15):
                f = [e for e in d.dump() if e.get("focused") == "true"]
                if f and re.search(use, f[0].get("text", ""), re.I):
                    d.key("KEYCODE_ENTER")
                    break
                d.key("KEYCODE_TAB")
                time.sleep(0.4)
        n = d.find(allow, timeout=10)
        if n is None:
            raise LookupError("no aparece el diálogo de permiso de carpeta")
        d.tap_node(n)
        if d.find(allow, timeout=3) is not None:
            d.key("KEYCODE_TAB"); d.key("KEYCODE_TAB"); d.key("KEYCODE_ENTER")
        time.sleep(3)

    def choose(self, current, wanted):
        """Abre un desplegable y elige una opción, desplazando la lista si no está a la vista."""
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
            # Desplazar DENTRO de la lista emergente: fuera de ella, la cierra.
            lists = [x for x in nodes if x.get("class") == "android.widget.ListView"]
            if not lists:
                break
            x1, y1, x2, y2 = d.bounds(lists[0])
            xm, top, bot = (x1 + x2) // 2, y1 + (y2 - y1) // 5, y2 - (y2 - y1) // 5
            a, b = (top, bot) if dy > 0 else (bot, top)
            d.sh(f"input swipe {xm} {a} {xm} {b} 400")
            time.sleep(1)
        raise LookupError(f"no encuentro {wanted}")

    def save(self):
        self.d.tap_node(self.d.scroll_to("^Guardar$"))
        time.sleep(4)

    # ---------- pruebas ----------
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
        d.click("^Añadir un widget")
        time.sleep(2)
        for label in ("Add automatically", "Add to home screen", "Añadir automáticamente", "Añadir a la pantalla de inicio", "Add", "Añadir"):
            btn = [n for n in d.find_all(f"^{label}$") if n.get("clickable") == "true" and n.get("package") != PKG]
            if btn:
                d.tap_node(btn[0])
                break
        else:
            raise LookupError("no encuentro el botón del diálogo de añadir widget")
        time.sleep(4)
        self.wid = (d.widget_ids() or [None])[-1]
        check("widget colocado desde la app", self.wid is not None, f"id {self.wid}")
        check("la configuración se abre sola", d.find("^Elegir fotos$", timeout=5) is not None)
        picker = self.pick_photos(3)
        got = d.find(r"\d+ fotos? elegidas?", timeout=5)
        check("fotos elegidas con el selector", got is not None and got.get("text", "").strip().split()[-3] == "3",
              f"{picker}: {got.get('text') if got is not None else 'nada'}")
        self.choose("^Cada hora$", "^Cada 30 segundos$")
        self.choose("^Sin marco$", "^Medio$")
        d.clear_log()
        self.save()
        self.go_home()
        time.sleep(5)
        n = d.requests(self.wid)
        self.screenshot("configurado")
        check("el widget pinta la foto", n >= 1 and d.widget_bounds() is not None, f"{n} fotos pedidas")
        a = d.alarms()
        check("pase de 30 s: solo alarma diaria no despertadora", a == [(False, 86_400_000)], str(a))

    def t_consumption(self):
        d, w = self.d, self.wait
        d.clear_log()
        colors = self.colors_during(w)
        n_home = d.requests(self.wid)
        check(f"escritorio visible {w} s: cambia de foto", len(colors) >= 2, f"{len(colors)} fotos vistas, {n_home} decodificadas")
        self.other_app()
        d.clear_log()
        time.sleep(w)
        check(f"otra app delante {w} s: 0 fotos preparadas", d.requests(self.wid) == 0, f"{d.requests(self.wid)}")
        self.go_home()
        d.key("KEYCODE_SLEEP")
        time.sleep(2)
        d.clear_log()
        time.sleep(w)
        check(f"pantalla apagada {w} s: 0 fotos preparadas", d.requests(self.wid) == 0, f"{d.requests(self.wid)}")
        self.go_home()
        colors = self.colors_during(45)
        check("al volver al escritorio el pase sigue", len(colors) >= 2, f"{len(colors)} fotos vistas")

    def wait_change(self, before, timeout=12):
        """Espera a que la foto cambie y se estabilice (el fundido deja ver el fondo un instante)."""
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
        check("tocar pasa a la siguiente foto", changes >= 2, f"{changes}/3 toques cambiaron la foto")

    def t_hourly_folder(self):
        d = self.d
        d.sh(f"am start -n {PKG}/.ConfigActivity")
        time.sleep(2)
        d.click("^Widget 1")
        time.sleep(2)
        d.click(desc="^Quitar$")
        time.sleep(1)
        check("quitar fuente", d.find("fotos elegidas", timeout=2) is None)
        self.pick_dcim_folder()
        check("carpeta añadida con el selector de carpetas", d.find("DCIM", timeout=5) is not None)
        self.choose("^Cada 30 segundos$", "^Cada hora$")
        self.save()
        self.go_home()
        time.sleep(5)
        a = d.alarms()
        check("cada hora: alarma RTC no despertadora de 1 h", a == [(False, 3_600_000)], str(a))
        before = self.widget_color()
        # Con otra app delante, la alarma solo apunta la siguiente foto: no decodifica nada.
        self.other_app()
        d.clear_log()
        d.sh(f"am broadcast -n {PKG}/.PhotoWidgetProvider -a {PKG}.ALARM --ei appWidgetId {self.wid}")
        time.sleep(5)
        check("alarma con otra app delante: 0 fotos preparadas", d.requests(self.wid) == 0, f"{d.requests(self.wid)}")
        self.go_home()
        after = self.wait_change(before)
        n = d.requests(self.wid)
        check("al volver al escritorio se ve la nueva foto", before != after and n >= 1, f"{before} -> {after}, {n} decodificadas")

    def t_reboot(self):
        d = self.d
        d.adb("reboot")
        time.sleep(10)
        d.wait_boot()
        time.sleep(15)
        d.sh("settings put system screen_off_timeout 1800000")
        d.adb("logcat", "-c")
        a = []
        for _ in range(30):  # BOOT_COMPLETED llega a las apps unos segundos después del arranque
            a = d.alarms()
            if a:
                break
            time.sleep(3)
        check("tras reiniciar, la alarma se reprograma", a == [(False, 3_600_000)], str(a))
        for _ in range(8):  # el launcher tarda en terminar de cargar tras el arranque
            if self.go_home():
                break
            time.sleep(10)
        time.sleep(5)
        n = d.requests(self.wid)
        self.screenshot("tras_reinicio")
        check("tras reiniciar, el widget pinta la foto", d.widget_bounds() is not None and self.widget_color() is not None, f"{n} decodificadas")

    def t_battery(self):
        d = self.d
        uid = re.search(r"userId=(\d+)", d.sh(f"dumpsys package {PKG}"))
        stats = d.sh(f"dumpsys batterystats {PKG}")
        wl = [l.strip() for l in stats.splitlines() if re.search(r"Wake lock|Wakeup alarm", l) and "photowidget" in l.lower()]
        check("sin wakelocks ni alarmas despertadoras (batterystats)", not wl, "; ".join(wl[:3]))
        cpu = re.search(r"Total cpu time: u=(\S+) s=(\S+)", stats)
        if cpu:
            print(f"     CPU de la app durante toda la prueba: usuario {cpu.group(1)}, sistema {cpu.group(2)}")
        crashes = d.crashes()
        check("ningún cierre inesperado", not crashes, "; ".join(crashes[:2]))
        d.sh("dumpsys battery reset")
        return uid


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("-s", "--serial", required=True)
    ap.add_argument("--apk", default=os.path.join(ROOT, "app/build/outputs/apk/release/app-release.apk"))
    ap.add_argument("--fast", action="store_true")
    ap.add_argument("--no-reboot", action="store_true")
    ap.add_argument("--out", default=None, help="carpeta para capturas")
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
        except Exception as e:  # una prueba rota no impide las siguientes
            check(s.__name__, False, f"{type(e).__name__}: {e}")
            r.screenshot("error_" + s.__name__)
    failed = [x for x in results if not x[1]]
    print(f"\n{a.serial}: {len(results) - len(failed)}/{len(results)} correctas. Capturas en {wd}")
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
