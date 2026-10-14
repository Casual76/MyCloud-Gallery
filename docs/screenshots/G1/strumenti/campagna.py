"""
Campagna di screenshot della revisione grafica G1, SOLO sull'emulatore (emulator-5554).

Per ogni tema (chiaro, scuro) e ogni direzione (attuale, A, B, C): la Libreria a riposo, poi passi di scorrimento lento
verso i piu' vecchi (cosi' sotto la chrome passano a turno le giornate bianche, nere, di testo, sature, a righe), uno
scorrimento veloce (lo scrubber), la selezione con due elementi, il menu della vista aperto. Ogni scatto ha accanto il
dump dell'albero di accessibilita' (uiautomator), che `misura-contrasto.py` usa per sapere dove sta il testo.

Uso: python -I docs/screenshots/G1/strumenti/campagna.py <cartella> [temi] [direzioni] [passi]
"""

import os
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

ADB = os.path.join(os.environ["LOCALAPPDATA"], "Android", "Sdk", "platform-tools", "adb.exe")
SERIAL = "emulator-5554"
PKG = "dev.pampa.mycloudgallery.debug"
OUT = sys.argv[1]
THEMES = (sys.argv[2] if len(sys.argv) > 2 else "chiaro,scuro").split(",")
DESIGNS = (sys.argv[3] if len(sys.argv) > 3 else "attuale,A,B,C").split(",")
STEPS = int(sys.argv[4]) if len(sys.argv) > 4 else 12


def adb(*args, out=None):
    return subprocess.run([ADB, "-s", SERIAL, *args], stdout=out or subprocess.PIPE, stderr=subprocess.DEVNULL, check=False)


def shell(cmd):
    return adb("shell", cmd).stdout.decode("utf-8", "replace")


def grab(folder, name):
    with open(os.path.join(folder, name + ".png"), "wb") as f:
        adb("exec-out", "screencap", "-p", out=f)
    shell("uiautomator dump /sdcard/ui.xml")
    xml = shell("cat /sdcard/ui.xml")
    with open(os.path.join(folder, name + ".xml"), "w", encoding="utf-8") as f:
        f.write(xml)
    return xml


def nodes(xml):
    try:
        root = ET.fromstring(xml[xml.index("<?xml"):] if "<?xml" in xml else xml)
    except (ET.ParseError, ValueError):
        return []
    found = []
    for n in root.iter("node"):
        b = n.get("bounds", "")
        try:
            l, t, r, bt = [int(v) for v in b.replace("][", ",").strip("[]").split(",")]
        except ValueError:
            continue
        found.append({"text": n.get("text", ""), "desc": n.get("content-desc", ""), "id": n.get("resource-id", ""), "b": (l, t, r, bt)})
    return found


def center(node):
    l, t, r, b = node["b"]
    return (l + r) // 2, (t + b) // 2


def tap_desc(xml, desc):
    for n in nodes(xml):
        if n["desc"] == desc:
            x, y = center(n)
            shell(f"input tap {x} {y}")
            return True
    return False


def photo_cells(xml):
    cells = [n for n in nodes(xml) if n["desc"].startswith("Foto,")]
    # Celle intere, lontane dalla chrome: fra 500 e 1700 pixel.
    return [c for c in cells if c["b"][1] > 500 and c["b"][3] < 1700 and c["b"][2] - c["b"][0] > 100]


def run(theme, design):
    folder = os.path.join(OUT, f"{design}-{theme}")
    os.makedirs(folder, exist_ok=True)
    shell(f"cmd uimode night {'yes' if theme == 'scuro' else 'no'}")
    shell(f"echo {design} > /sdcard/Android/data/{PKG}/files/g1-design")
    shell(f"am force-stop {PKG}")
    shell(f"monkey -p {PKG} -c android.intent.category.LAUNCHER 1")
    time.sleep(7)
    grab(folder, "p00")
    for i in range(1, STEPS + 1):
        shell("input swipe 540 900 540 1230 1200")
        time.sleep(1.4)
        grab(folder, f"p{i:02d}")
    # La pillola aperta sopra il contenuto: in A e C si ripiega scorrendo verso i piu' vecchi e si riapre tornando verso
    # i recenti, quindi uno scorrimento all'indietro la riapre sopra le foto che passano.
    shell("input swipe 540 1230 540 900 1200")
    time.sleep(1.4)
    grab(folder, "pillola-aperta")
    # Lo scrubber: due scorrimenti veloci di piu' di tre quarti di schermo (il primo dopo l'avvio a volte scorre poco), e
    # tre scatti subito dopo l'ultimo: la maniglia compare scorrendo e sparisce 1,4 s dopo la quiete.
    shell("input swipe 540 700 540 1900 120")
    time.sleep(1.0)
    shell("input swipe 540 700 540 2000 100")
    for k in range(1, 4):
        with open(os.path.join(folder, f"scrubber-{k}.png"), "wb") as f:
            adb("exec-out", "screencap", "-p", out=f)
        time.sleep(0.15)
    time.sleep(2.5)
    xml = grab(folder, "prima-della-selezione")
    cells = photo_cells(xml)
    if len(cells) >= 2:
        x, y = center(cells[0])
        shell(f"input swipe {x} {y} {x} {y} 900")
        time.sleep(1.0)
        x, y = center(cells[-1])
        shell(f"input tap {x} {y}")
        time.sleep(1.5)
        xml = grab(folder, "selezione")
        if not (tap_desc(xml, "Chiudi") or tap_desc(xml, "Annulla la selezione")):
            for n in nodes(xml):
                if n["text"] == "Fine":
                    x, y = center(n)
                    shell(f"input tap {x} {y}")
                    break
        time.sleep(1.5)
    xml = grab(folder, "prima-del-menu")
    if tap_desc(xml, "Vista"):
        time.sleep(2.0)
        grab(folder, "menu")
        shell("input keyevent 4")
        time.sleep(1.2)
    if design == "B":
        xml = grab(folder, "prima-del-livello")
        for n in nodes(xml):
            if n["desc"].startswith("Livello:"):
                x, y = center(n)
                shell(f"input tap {x} {y}")
                time.sleep(2.0)
                grab(folder, "menu-livello")
                shell("input keyevent 4")
                time.sleep(1.2)
                break


for theme in THEMES:
    for design in DESIGNS:
        t0 = time.time()
        run(theme, design)
        print(f"{design} {theme}: {time.time() - t0:.0f} s", flush=True)
