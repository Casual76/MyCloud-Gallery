"""
Apre il NAS sintetico in "libreria mista" (G2) sull'emulatore: Libreria -> Vista -> Impostazioni -> Sviluppo -> "NAS
sintetico, libreria mista", poi aspetta che la scansione finta abbia riempito la griglia (celle "solo sul NAS" o "sul
telefono e sul NAS" nell'albero di accessibilita'). SOLO emulatore: e' la build di debug con i suoi strumenti.

Uso: python -I docs/screenshots/G2/strumenti/apri-mista.py <seriale>
"""

import os
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

ADB = os.path.join(os.environ["LOCALAPPDATA"], "Android", "Sdk", "platform-tools", "adb.exe")
SERIAL = sys.argv[1]
PKG = "dev.pampa.mycloudgallery.debug"


def shell(cmd):
    return subprocess.run([ADB, "-s", SERIAL, "shell", cmd], stdout=subprocess.PIPE, stderr=subprocess.DEVNULL).stdout.decode("utf-8", "replace")


def nodes():
    shell("uiautomator dump /sdcard/ui.xml")
    xml = shell("cat /sdcard/ui.xml")
    try:
        root = ET.fromstring(xml[xml.index("<?xml"):])
    except (ET.ParseError, ValueError):
        return []
    out = []
    for n in root.iter("node"):
        try:
            l, t, r, b = [int(v) for v in n.get("bounds").replace("][", ",").strip("[]").split(",")]
        except (ValueError, AttributeError):
            continue
        out.append((n.get("text", ""), n.get("content-desc", ""), (l + r) // 2, (t + b) // 2))
    return out


def tap(label, attempts=8, scroll=False):
    for _ in range(attempts):
        for text, desc, x, y in nodes():
            if text == label or desc == label:
                shell(f"input tap {x} {y}")
                time.sleep(2.0)
                return True
        if scroll:
            shell("input swipe 650 1500 650 700 400")
        time.sleep(1.0)
    return False


if not SERIAL.startswith("emulator-") and shell("getprop ro.kernel.qemu").strip() != "1":
    sys.exit("Non e' un emulatore.")
shell(f"am force-stop {PKG}")
shell(f"monkey -p {PKG} -c android.intent.category.LAUNCHER 1")
time.sleep(7)
# Le Impostazioni dalla scheda Raccolte (il tasto in alto): una strada che non dipende dalla lunghezza del menu "Vista".
assert tap("Raccolte"), "scheda Raccolte non trovata"
assert tap("Impostazioni"), "Impostazioni non trovate"
assert tap("NAS sintetico, libreria mista", attempts=12, scroll=True), "voce della libreria mista non trovata"
tap("Libreria")
start = time.time()
while time.time() - start < 600:
    descs = [d for _, d, _, _ in nodes()]
    if any(d.endswith(("solo sul NAS", "sul telefono e sul NAS", "solo sul telefono")) for d in descs):
        print(f"libreria mista pronta in {time.time() - start:.0f} s")
        sys.exit(0)
    time.sleep(5)
sys.exit("la libreria mista non si e' riempita in 10 minuti")
