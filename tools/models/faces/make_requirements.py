"""Scrive `requirements.txt` con le versioni fissate E le impronte SHA-256 di ogni file di ogni pacchetto (Fase 8, P1; docs/fase8-progetto.md §2.2).

    python -I make_requirements.py --python "<root>\\venv\\conv\\Scripts\\python.exe"

Parte dai pacchetti che l'ambiente di conversione ha installato (`pip list`), chiede a PyPI (`pypi.org`, solo la sua API JSON) l'impronta di
ogni file di quella versione e scrive il file nel formato di `pip install --require-hashes --no-deps -r requirements.txt`. Serve solo a chi
aggiorna l'ambiente: chi lo ricrea usa `setup-env.ps1`, che non interroga PyPI per le impronte.
"""
from __future__ import annotations

import argparse
import json
import subprocess
import sys
import time
import urllib.request
from pathlib import Path

QUI = Path(__file__).resolve().parent
INTESTAZIONE = """# Ambiente di conversione dei modelli dei volti (Fase 8, P1). Python 3.11, Windows x64 (le impronte sono quelle delle ruote adatte a questo).
# Si installa con `setup-env.ps1` (cioe' `pip install --no-deps --require-hashes -r requirements.txt`): ogni file scaricato deve avere una
# di queste impronte SHA-256, altrimenti pip si ferma. `--no-deps` perche' il grafo e' scritto per intero qui sotto: onnx2tf 1.29.24 dichiara
# `tensorflow==2.19.0`, `ai-edge-litert==2.1.0` e `simple-onnx-processing-tools`, che non servono o non esistono per Windows e Python 3.11
# (si usano `tensorflow-cpu` 2.19.1, `ai-edge-litert` 2.1.2 e i soli `sne4onnx` e `sng4onnx`, che sono quelli che onnx2tf importa); `pip check`
# segnala questa differenza, ed e' voluta. Generato da `make_requirements.py`.
"""


def installati(python: str) -> list[tuple[str, str]]:
    uscita = subprocess.run([python, "-I", "-m", "pip", "list", "--format=json"], capture_output=True, text=True, check=True).stdout
    return sorted((p["name"], p["version"]) for p in json.loads(uscita) if p["name"].lower() not in ("pip",))


def impronte(nome: str, versione: str) -> list[str]:
    """Le impronte dei file di questa versione che l'interprete in uso saprebbe installare (le ruote adatte; i sorgenti solo se non ce n'e')."""
    from packaging.tags import sys_tags
    from packaging.utils import parse_wheel_filename

    richiesta = urllib.request.Request(f"https://pypi.org/pypi/{nome}/{versione}/json", headers={"User-Agent": "MyCloudGallery-modelli-volti/1.0"})
    with urllib.request.urlopen(richiesta, timeout=60) as r:
        dati = json.load(r)
    supportati = set(sys_tags())
    ruote, sorgenti = set(), set()
    for f in dati["urls"]:
        if f["filename"].endswith(".whl"):
            if supportati & set(parse_wheel_filename(f["filename"])[3]):
                ruote.add(f["digests"]["sha256"])
        else:
            sorgenti.add(f["digests"]["sha256"])
    return sorted(ruote or sorgenti)


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--python", required=True, help="python dell'ambiente di conversione")
    args = ap.parse_args()
    righe = [INTESTAZIONE]
    for nome, versione in installati(args.python):
        hashes = impronte(nome, versione)
        if not hashes:
            sys.exit(f"{nome} {versione}: nessun file su PyPI")
        righe.append(f"{nome.lower()}=={versione} \\\n" + " \\\n".join(f"    --hash=sha256:{h}" for h in hashes) + "\n")
        time.sleep(0.2)
    (QUI / "requirements.txt").write_text("\n".join(righe), encoding="utf-8", newline="\n")
    print(f"scritto requirements.txt ({len(righe) - 1} pacchetti)")


if __name__ == "__main__":
    main()
