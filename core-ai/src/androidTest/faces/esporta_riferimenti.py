"""Esporta i riferimenti numerici per la parita' sul dispositivo (Fase 8, P2; docs/decisioni.md "Fase 8 P2", F104).

    python -I esporta_riferimenti.py --root "C:\\VibeCoded Projects\\mcg-models\\faces" --dest "C:\\VibeCoded Projects\\mcg-models\\faces\\out\\dispositivo" [--serie A B] [--max 30] [--tensori N]

Cosa fa (sul PC, con l'ambiente di P1: `<root>\\venv\\conv\\Scripts\\python.exe`; l'ONNX e' quello di P1, nessuna rete):
  - per ogni composizione delle serie scelte (`<root>\\out\\composizioni\\A|B|C`, ritratti di pubblico dominio incollati su sfondi senza volti, lato 1024):
    rileva con l'ONNX di YuNet (decodifica nostra di P1: `common.decodifica_yunet`) e, per ogni rilevamento, calcola il vettore dell'ONNX di SFace sul volto
    allineato con i punti dell'ONNX (`common.volto_allineato`), normalizzato; scrive anche i riquadri di verita' di terra (per il richiamo);
  - scrive `<dest>/refs.txt` (solo numeri) e copia le immagini in `<dest>/img/<serie>-img-NNN.png`;
  - con `--tensori N`: scrive anche `<dest>/tensori-reali.txt`, le uscite grezze VERE di YuNet (in forma sparsa) per le prime N immagini, nel formato delle
    fixture della JVM: copiandolo in `core-ai/src/test/resources/faces/` la prova di decodifica e NMS le prende da sola (sono numeri, non immagini; P1 non le
    aveva lasciate).
Poi: `adb push <dest>\\. /data/local/tmp/mcg-faces/` (le immagini restano FUORI dal repository: la regola dei brief e' nessun volto, nessun ritratto).
Non scarica niente e non apre la rete. Questo script non e' stato eseguito dal pacchetto P2 (niente ambiente Python di P1 nella sua cartella di lavoro): e' scritto
sulle stesse funzioni di `parity.py`; se qualcosa non torna, il punto di partenza e' `tools/models/faces/parity.py`.
"""
from __future__ import annotations

import argparse
import shutil
import sys
from pathlib import Path

import numpy as np

AQUI = Path(__file__).resolve().parent
REPO = AQUI.parents[3]
sys.path.insert(0, str(REPO / "tools" / "models" / "faces"))
import common  # noqa: E402
import dati  # noqa: E402
import engines  # noqa: E402


def esadecimale(valori) -> str:
    """Float32 come 8 cifre esadecimali (esatto)."""
    return " ".join(f"{int(x):08x}" for x in np.asarray(valori, dtype=np.float32).view(np.uint32))


def nome_su_dispositivo(nome: str) -> str:
    """`A/img-000.png` -> `img/A-img-000.png`."""
    serie, resto = nome.split("/", 1)
    return f"img/{serie}-{resto}"


def righe_tensori(nome: str, uscite: dict, minimo: float = 0.30):
    """Le uscite grezze sparse: le sole celle con punteggio >= `minimo`, piu' l'atteso del decodificatore di riferimento."""
    yield f"immagine {nome}"
    for passo in common.PASSI:
        n = (1024 // passo) ** 2
        cls = np.clip(uscite[f"cls_{passo}"].reshape(-1), 0.0, 1.0)
        obj = np.clip(uscite[f"obj_{passo}"].reshape(-1), 0.0, 1.0)
        bbox = uscite[f"bbox_{passo}"].reshape(-1, 4)
        kps = uscite[f"kps_{passo}"].reshape(-1, 10)
        celle = 1024 // passo
        for idx in np.nonzero(np.sqrt(cls * obj) >= minimo)[0]:
            v = np.concatenate([[uscite[f"cls_{passo}"].reshape(-1)[idx]], [uscite[f"obj_{passo}"].reshape(-1)[idx]], bbox[idx], kps[idx]])
            yield f"cella {passo} {idx // celle} {idx % celle} {esadecimale(v)}"
        assert cls.shape[0] == n
    atteso = sorted(common.decodifica_yunet(uscite), key=lambda r: -r["punteggio"])
    for r in atteso:
        x, y, w, h = r["riquadro"]
        punti = " ".join(f"{v:.6f}" for v in np.asarray(r["punti"]).reshape(-1))
        yield f"atteso {r['punteggio']:.7f} {x:.6f} {y:.6f} {w:.6f} {h:.6f} {punti}"
    yield "fine"


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--root", help="cartella dei modelli dei volti, fuori dal repository (o MCG_FACES_ROOT)")
    ap.add_argument("--dest", required=True, help="dove scrivere refs.txt e img/ (fuori dal repository)")
    ap.add_argument("--serie", nargs="+", default=["A", "B"], help="serie delle composizioni (A, B, C)")
    ap.add_argument("--max", type=int, default=30, help="al piu' tante immagini per serie")
    ap.add_argument("--tensori", type=int, default=0, help="scrive anche tensori-reali.txt con le uscite grezze vere delle prime N immagini")
    args = ap.parse_args()

    radice = common.radice_modelli(args.root)
    dest = Path(args.dest).resolve()
    if REPO == dest or REPO in dest.parents:
        sys.exit("La destinazione non puo' stare dentro il repository: ci sono immagini di volti (ritratti di pubblico dominio).")
    (dest / "img").mkdir(parents=True, exist_ok=True)

    yunet = engines.YuNetOnnx(radice / "out" / "onnx-1024" / "face_detection_yunet_2023mar.onnx")
    sface = engines.SFaceOnnx(common.verifica_onnx(radice / "onnx", "face_recognition_sface_2021dec.onnx"))

    righe = ["MCGF-RIFERIMENTI 1", "# Riferimenti dell'ONNX di P1 per la parita' sul dispositivo: solo numeri (esporta_riferimenti.py)."]
    tensori = ["MCGF-TENSORI 1",
               "# Uscite grezze VERE di YuNet in forma sparsa (celle con punteggio >= 0,30), con l'atteso del decodificatore di riferimento di P1.",
               "# Registrate da esporta_riferimenti.py --tensori: numeri, nessuna immagine."]
    totale = rilevamenti_totali = 0
    for serie in args.serie:
        for c in dati.composizioni(radice, serie)[: args.max]:
            quadro, scala = common.quadro_yunet(c["immagine"])
            if abs(scala - 1.0) > 1e-9 or c["immagine"].shape[:2] != (1024, 1024):
                continue  # le composizioni hanno il lato di 1024: il quadro del modello e' l'immagine stessa
            uscite = yunet.uscite(quadro)
            trovati = sorted(common.decodifica_yunet(uscite), key=lambda r: -r["punteggio"])
            nome = nome_su_dispositivo(c["nome"])
            shutil.copyfile(radice / "out" / "composizioni" / c["nome"], dest / nome)
            righe.append(f"immagine {nome}")
            for v in c["volti"]:
                x, y, w, h = v["riquadro"]
                righe.append(f"verita {x:.6f} {y:.6f} {w:.6f} {h:.6f}")
            for r in trovati:
                volto = common.volto_allineato(quadro, r["punti"])
                vettore = common.normalizza(sface.vettore(volto)).astype(np.float32)
                x, y, w, h = r["riquadro"]
                punti = " ".join(f"{v:.6f}" for v in np.asarray(r["punti"]).reshape(-1))
                righe.append(f"rilevamento {r['punteggio']:.7f} {x:.6f} {y:.6f} {w:.6f} {h:.6f} {punti} {esadecimale(vettore)}")
                rilevamenti_totali += 1
            righe.append("fine")
            if totale < args.tensori:
                tensori.extend(righe_tensori("reale-" + c["nome"].replace("/", "-").replace(".png", "").lower(), uscite))
            totale += 1
    (dest / "refs.txt").write_text("\n".join(righe) + "\n", encoding="utf-8", newline="\n")
    print(f"scritti {totale} riferimenti ({rilevamenti_totali} rilevamenti) e le immagini in {dest}")
    if args.tensori:
        (dest / "tensori-reali.txt").write_text("\n".join(tensori) + "\n", encoding="utf-8", newline="\n")
        print(f"scritto tensori-reali.txt (prime {min(args.tensori, totale)} immagini): da copiare in core-ai/src/test/resources/faces/")


if __name__ == "__main__":
    main()
