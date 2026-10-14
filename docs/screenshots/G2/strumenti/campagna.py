"""
Campagna di screenshot della tappa grafica G2 (docs/grafica-progetto.md §12), SOLO su un emulatore.

Due scenari, per ogni tema (chiaro, scuro):
  - `libreria`: la Libreria vera dell'emulatore con il contenuto peggiore di G1 (`../G1/strumenti/genera-media.py`) e i
    media di `tools/bench/seed-media.ps1`: a riposo (il piede), ventiquattro passi di scorrimento lento verso i piu' vecchi
    (prima i media del benchmark di oggi, poi a turno le giornate bianche, nere, di testo, sature, a righe), la Libreria scorsa a meta', la
    pillola riaperta, lo scrubber, la selezione (con due preferiti, per le Raccolte), il menu della vista, il livello Mesi,
    poi le Raccolte: l'elenco, i Preferiti, la selezione nei Preferiti;
  - `mista`: il NAS sintetico in "libreria mista" (Impostazioni -> Sviluppo, aperto prima a mano o da `apri-mista`): i
    segni di presenza su Giorni e Mesi, la selezione, e il piede del NAS in ogni stato (scritto in `files/g2-nas-footer`).
Ogni scatto ha accanto il dump dell'albero di accessibilita' (uiautomator): `misura-contrasto.py` ci trova la chrome per
tag (`chrome-*`), le capsule del piede e i dischi di presenza delle celle.

Uso: python -I docs/screenshots/G2/strumenti/campagna.py <cartella> <seriale> <scenario> [temi] [passi]
"""

import os
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

ADB = os.path.join(os.environ["LOCALAPPDATA"], "Android", "Sdk", "platform-tools", "adb.exe")
OUT = sys.argv[1]
SERIAL = sys.argv[2]
SCENARIO = sys.argv[3]
THEMES = (sys.argv[4] if len(sys.argv) > 4 else "chiaro,scuro").split(",")
STEPS = int(sys.argv[5]) if len(sys.argv) > 5 else 24
PKG = "dev.pampa.mycloudgallery.debug"
FILES = f"/sdcard/Android/data/{PKG}/files"


def adb(*args, out=None):
    return subprocess.run([ADB, "-s", SERIAL, *args], stdout=out or subprocess.PIPE, stderr=subprocess.DEVNULL, check=False)


def shell(cmd):
    return adb("shell", cmd).stdout.decode("utf-8", "replace")


def ensure_emulator():
    qemu = shell("getprop ro.kernel.qemu").strip()
    if not SERIAL.startswith("emulator-") and qemu != "1":
        sys.exit(f"{SERIAL} non e' un emulatore: niente scatti su un dispositivo vero.")


def size():
    w, h = shell("wm size").split(":")[-1].strip().split("x")
    return int(w), int(h)


def dump():
    shell("uiautomator dump /sdcard/ui.xml")
    return shell("cat /sdcard/ui.xml")


def grab(folder, name):
    with open(os.path.join(folder, name + ".png"), "wb") as f:
        adb("exec-out", "screencap", "-p", out=f)
    xml = dump()
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


def tap(node):
    x, y = center(node)
    shell(f"input tap {x} {y}")


def find(xml, text=None, desc=None, rid=None, starts=None):
    for n in nodes(xml):
        if text is not None and n["text"] == text:
            return n
        if desc is not None and n["desc"] == desc:
            return n
        if rid is not None and n["id"] == rid:
            return n
        if starts is not None and (n["desc"].startswith(starts) or n["text"].startswith(starts)):
            return n
    return None


def tap_first(text=None, desc=None, rid=None, starts=None, wait=1.5):
    n = find(dump(), text=text, desc=desc, rid=rid, starts=starts)
    if n:
        tap(n)
        time.sleep(wait)
    return n is not None


CELL_KINDS = ("Foto,", "Video,", "Foto in movimento,", "Foto RAW,", "Immagine animata,")


def cells(xml, top, bottom):
    found = [n for n in nodes(xml) if n["desc"].startswith(CELL_KINDS)]
    return [c for c in found if c["b"][1] > top and c["b"][3] < bottom and c["b"][2] - c["b"][0] > 100]


def launch(theme):
    shell(f"cmd uimode night {'yes' if theme == 'scuro' else 'no'}")
    shell(f"am force-stop {PKG}")
    shell(f"monkey -p {PKG} -c android.intent.category.LAUNCHER 1")
    time.sleep(7)


def scrubber(folder, w, h):
    # Due scorrimenti veloci di piu' di tre quarti di schermo, e tre scatti subito dopo l'ultimo: la maniglia compare
    # scorrendo e sparisce 1,4 s dopo la quiete.
    shell(f"input swipe {w // 2} {h * 3 // 10} {w // 2} {h * 8 // 10} 120")
    time.sleep(1.0)
    shell(f"input swipe {w // 2} {h * 3 // 10} {w // 2} {h * 85 // 100} 100")
    for k in range(1, 4):
        with open(os.path.join(folder, f"scrubber-{k}.png"), "wb") as f:
            adb("exec-out", "screencap", "-p", out=f)
        time.sleep(0.15)
    time.sleep(2.5)


def select_two(folder, h, name, favorite=False):
    xml = grab(folder, f"prima-di-{name}")
    found = cells(xml, h // 5, h * 7 // 10)
    if len(found) < 2:
        return
    x, y = center(found[0])
    shell(f"input swipe {x} {y} {x} {y} 900")
    time.sleep(1.0)
    tap(found[-1])
    time.sleep(1.5)
    grab(folder, name)
    if favorite:
        tap_first(desc="Aggiungi ai preferiti", wait=2.0)
        grab(folder, f"{name}-preferiti")
    tap_first(text="Fine")
    time.sleep(1.0)


def menu(folder, name="menu"):
    if tap_first(desc="Vista", wait=2.0):
        grab(folder, name)
        shell("input keyevent 4")
        time.sleep(1.5)


def level(folder, label, name):
    if tap_first(desc="Vista", wait=2.0):
        # Il cambio di livello si anima e poi si ancora: si fotografa a griglia ferma (lo scatto e il dump devono dire la
        # stessa cosa, o i dischi di presenza si cercherebbero dove le celle non sono piu').
        if tap_first(text=label, wait=4.5):
            grab(folder, name)
            return True
        shell("input keyevent 4")
        time.sleep(1.2)
    return False


def run_libreria(theme, w, h):
    folder = os.path.join(OUT, f"libreria-{theme}")
    os.makedirs(folder, exist_ok=True)
    launch(theme)
    grab(folder, "p00")
    for i in range(1, STEPS + 1):
        shell(f"input swipe {w // 2} {h * 3 // 8} {w // 2} {h * 51 // 100} 1200")
        time.sleep(1.4)
        grab(folder, f"p{i:02d}")
    # La pillola si ripiega scorrendo verso i piu' vecchi e si riapre tornando verso i recenti.
    shell(f"input swipe {w // 2} {h * 51 // 100} {w // 2} {h * 3 // 8} 1200")
    time.sleep(1.4)
    grab(folder, "pillola-aperta")
    # Scorsa a meta': qualche lancio verso i piu' vecchi.
    for _ in range(3):
        shell(f"input swipe {w // 2} {h * 3 // 10} {w // 2} {h * 7 // 10} 250")
        time.sleep(0.8)
    time.sleep(1.5)
    grab(folder, "meta")
    scrubber(folder, w, h)
    select_two(folder, h, "selezione", favorite=(theme == THEMES[0]))
    menu(folder)
    if level(folder, "Mesi", "mesi"):
        level(folder, "Giorni", "giorni")
    # Le Raccolte: l'elenco, i Preferiti (due, messi nella selezione), la selezione nei Preferiti.
    if tap_first(text="Raccolte", wait=2.5):
        grab(folder, "raccolte")
        if tap_first(text="Preferiti", wait=3.0):
            grab(folder, "preferiti")
            if tap_first(desc="Seleziona", wait=1.5):
                xml = dump()
                found = cells(xml, h // 6, h * 9 // 10)
                if found:
                    tap(found[0])
                    time.sleep(1.5)
                    grab(folder, "preferiti-selezione")
                tap_first(text="Fine")
            shell("input keyevent 4")
            time.sleep(1.5)
        tap_first(text="Libreria", wait=2.0)


def footer(state):
    if state:
        shell(f"sh -c 'echo {state} > {FILES}/g2-nas-footer'")
    else:
        shell(f"rm -f {FILES}/g2-nas-footer")
    time.sleep(2.5)


def run_mista(theme, w, h):
    folder = os.path.join(OUT, f"mista-{theme}")
    os.makedirs(folder, exist_ok=True)
    shell(f"cmd uimode night {'yes' if theme == 'scuro' else 'no'}")
    time.sleep(3.0)
    # Ai piu' recenti (D1), dove c'e' il piede: "Libreria" toccata di nuovo, e qualche lancio verso i recenti per sicurezza
    # (dopo il cambio di tema l'activity si ricrea e il primo tocco puo' andare perso).
    tap_first(text="Libreria", wait=2.5)
    for _ in range(6):
        shell(f"input swipe {w // 2} {h * 7 // 10} {w // 2} {h * 2 // 10} 120")
        time.sleep(0.6)
    time.sleep(2.0)
    footer(None)
    grab(folder, "riposo")
    for state in ("lettura", "allineo", "aggiornato", "fermo", "irraggiungibile", "comune", "prova"):
        footer(state)
        grab(folder, f"piede-{state}")
    footer(None)
    for i in range(1, 4):
        shell(f"input swipe {w // 2} {h * 3 // 8} {w // 2} {h * 51 // 100} 1200")
        time.sleep(1.4)
        grab(folder, f"p{i:02d}")
    select_two(folder, h, "selezione")
    if level(folder, "Mesi", "mesi"):
        level(folder, "Giorni", "giorni")


ensure_emulator()
W, H = size()
for theme in THEMES:
    t0 = time.time()
    if SCENARIO == "libreria":
        run_libreria(theme, W, H)
    elif SCENARIO == "mista":
        run_mista(theme, W, H)
    else:
        sys.exit(f"Scenario sconosciuto: {SCENARIO}")
    print(f"{SCENARIO} {theme}: {time.time() - t0:.0f} s", flush=True)
