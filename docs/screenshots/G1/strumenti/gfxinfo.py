"""
Costo di disegno relativo delle direzioni G1 sull'EMULATORE (non e' una misura di prestazioni: l'emulatore disegna con
la GPU del PC; serve solo a confrontare le direzioni fra loro nelle stesse condizioni).

Per ogni direzione: riavvio, `dumpsys gfxinfo reset`, scorrimenti uguali (lenti e veloci), poi i percentili di
fotogramma e di GPU che `gfxinfo` riporta. Tre ripetizioni, la mediana.

Uso: python -I docs/screenshots/G1/strumenti/gfxinfo.py [direzioni] [ripetizioni]
"""

import os
import re
import statistics
import subprocess
import sys
import time

ADB = os.path.join(os.environ["LOCALAPPDATA"], "Android", "Sdk", "platform-tools", "adb.exe")
SERIAL = "emulator-5554"
PKG = "dev.pampa.mycloudgallery.debug"
DESIGNS = (sys.argv[1] if len(sys.argv) > 1 else "attuale,A,B,C").split(",")
REPS = int(sys.argv[2]) if len(sys.argv) > 2 else 3


def shell(cmd):
    return subprocess.run([ADB, "-s", SERIAL, "shell", cmd], stdout=subprocess.PIPE, stderr=subprocess.DEVNULL).stdout.decode("utf-8", "replace")


KEYS = {
    "fotogrammi": r"Total frames rendered: (\d+)",
    "in ritardo %": r"Janky frames: \d+ \(([\d.]+)%\)",
    "p50 ms": r"50th percentile: (\d+)ms",
    "p90 ms": r"90th percentile: (\d+)ms",
    "p99 ms": r"99th percentile: (\d+)ms",
    "gpu p50 ms": r"50th gpu percentile: (\d+)ms",
    "gpu p90 ms": r"90th gpu percentile: (\d+)ms",
    "gpu p99 ms": r"99th gpu percentile: (\d+)ms",
}

shell("cmd uimode night no")
table = {}
for design in DESIGNS:
    runs = []
    for rep in range(REPS):
        shell(f"echo {design} > /sdcard/Android/data/{PKG}/files/g1-design")
        shell(f"am force-stop {PKG}")
        shell(f"monkey -p {PKG} -c android.intent.category.LAUNCHER 1")
        time.sleep(7)
        shell(f"dumpsys gfxinfo {PKG} reset")
        for _ in range(4):
            shell("input swipe 540 800 540 1500 300")
            time.sleep(0.8)
        for _ in range(4):
            shell("input swipe 540 1500 540 800 300")
            time.sleep(0.8)
        for _ in range(3):
            shell("input swipe 540 900 540 1230 900")
            time.sleep(0.5)
        out = shell(f"dumpsys gfxinfo {PKG}")
        row = {}
        for name, pattern in KEYS.items():
            m = re.search(pattern, out)
            row[name] = float(m.group(1)) if m else float("nan")
        runs.append(row)
    table[design] = {k: statistics.median(r[k] for r in runs) for k in KEYS}
    print(design, table[design], flush=True)

print()
print("| direzione | " + " | ".join(KEYS) + " |")
print("|---|" + "---|" * len(KEYS))
for design, row in table.items():
    print(f"| {design} | " + " | ".join(f"{row[k]:g}" for k in KEYS) + " |")
