"""Controlla l'elenco dei ritratti di pubblico dominio contro le immagini scaricate (Fase 8, P11; docs/fase8-progetto.md §5.2).

    python -I controlla_ritratti.py [--root "C:\\...\\mcg-models\\faces"]

Senza `--root` controlla solo l'elenco (`ritratti.tsv`): colonne, etichette `ritratto-NN` consecutive, soggetti `sNN` o `mNN`, licenza, impronte SHA-256 ben
formate. Con `--root` controlla anche ogni `<root>/ritratti/ritratto-NN.jpg`: dimensione e SHA-256 devono essere quelli dell'elenco (le immagini scaricate sono
dati non fidati: solo lette come byte). Nessuna rete, nessuno scaricamento: se manca un ritratto lo dice e basta (si scarica con `tools/models/faces/fetch_portraits.py`).
Esce con codice 1 se qualcosa non torna.
"""
from __future__ import annotations

import argparse
import hashlib
import re
import sys
from pathlib import Path

QUI = Path(__file__).resolve().parent
COLONNE = ["etichetta", "soggetto", "nomi", "titolo_commons", "pagina", "licenza", "byte", "sha256"]


def leggi_elenco() -> list[dict]:
    righe = [r for r in (QUI / "ritratti.tsv").read_text(encoding="utf-8").splitlines() if r.strip() and not r.startswith("#")]
    if righe[0].split("\t") != COLONNE:
        sys.exit(f"Intestazione dell'elenco non valida: {righe[0]!r}")
    voci = []
    for riga in righe[1:]:
        campi = riga.split("\t")
        if len(campi) != len(COLONNE):
            sys.exit(f"Riga con {len(campi)} campi invece di {len(COLONNE)}.")
        voci.append(dict(zip(COLONNE, campi)))
    return voci


def controlla_elenco(voci: list[dict]) -> list[str]:
    problemi = []
    for i, v in enumerate(voci, start=1):
        if v["etichetta"] != f"ritratto-{i:02d}":
            problemi.append(f"riga {i}: etichetta {v['etichetta']!r} invece di ritratto-{i:02d}")
        if not re.fullmatch(r"[sm][0-9]{2}", v["soggetto"]):
            problemi.append(f"{v['etichetta']}: soggetto {v['soggetto']!r} non e' sNN o mNN")
        if not v["licenza"].startswith("Public domain"):
            problemi.append(f"{v['etichetta']}: licenza {v['licenza']!r} non e' di pubblico dominio")
        if not v["pagina"].startswith("https://commons.wikimedia.org/wiki/File"):
            problemi.append(f"{v['etichetta']}: la fonte non e' una pagina di Wikimedia Commons")
        if not re.fullmatch(r"[0-9a-f]{64}", v["sha256"]) or not v["byte"].isdigit():
            problemi.append(f"{v['etichetta']}: impronta o dimensione non valide")
        if not v["nomi"].strip():
            problemi.append(f"{v['etichetta']}: manca la colonna dei nomi (serve alla guardia sul repository)")
    if len({v["etichetta"] for v in voci}) != len(voci):
        problemi.append("etichette doppie")
    return problemi


def controlla_immagini(voci: list[dict], radice: Path) -> list[str]:
    problemi = []
    for v in voci:
        percorso = radice / "ritratti" / f"{v['etichetta']}.jpg"
        if not percorso.is_file():
            problemi.append(f"{v['etichetta']}: manca {percorso.name}")
            continue
        dati = percorso.read_bytes()
        if len(dati) != int(v["byte"]):
            problemi.append(f"{v['etichetta']}: {len(dati)} byte invece di {v['byte']}")
        elif hashlib.sha256(dati).hexdigest() != v["sha256"]:
            problemi.append(f"{v['etichetta']}: SHA-256 diverso da quello dell'elenco")
    return problemi


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--root", help="cartella dei modelli dei volti, fuori dal repository: se c'e', controlla anche le immagini")
    args = ap.parse_args()
    voci = leggi_elenco()
    problemi = controlla_elenco(voci)
    if args.root:
        problemi += controlla_immagini(voci, Path(args.root).resolve())
    for p in problemi:
        print("PROBLEMA:", p)
    soggetti = {v["soggetto"] for v in voci}
    print(f"{len(voci)} ritratti, {len(soggetti)} soggetti" + (", immagini controllate" if args.root else ", solo l'elenco") + (f": {len(problemi)} problemi" if problemi else ": tutto a posto"))
    sys.exit(1 if problemi else 0)


if __name__ == "__main__":
    main()
