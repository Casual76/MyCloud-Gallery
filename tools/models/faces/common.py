"""Funzioni comuni degli strumenti dei modelli dei volti (Fase 8, P1; docs/fase8-progetto.md §2.2).

Si lancia sempre con `python -I` (modalita' isolata: niente PYTHONPATH, niente cartella utente, niente import dalla cartella corrente).
Qui ci sono solo cose pure: impronte, geometria dei riquadri, decodifica di YuNet (la stessa per ONNX e `.tflite`), allineamento di SFace.
Nessuna rete e nessun modello: i file veri li passano gli script che importano questo modulo.
"""
from __future__ import annotations

import hashlib
import json
import os
import sys
from pathlib import Path

import numpy as np

QUI = Path(__file__).resolve().parent

# --- Cartelle fuori dal repository -------------------------------------------------------------------------------------------------


def radice_modelli(argomento: str | None = None) -> Path:
    """La cartella dei modelli dei volti fuori dal repository: `--root`, oppure la variabile MCG_FACES_ROOT."""
    valore = argomento or os.environ.get("MCG_FACES_ROOT")
    if not valore:
        sys.exit("Serve --root (o la variabile MCG_FACES_ROOT): la cartella dei modelli dei volti, FUORI dal repository.")
    radice = Path(valore).resolve()
    repo = QUI.parents[2]
    if radice == repo or repo in radice.parents:
        sys.exit("La cartella dei modelli non puo' stare dentro il repository (mai ONNX, ambienti Python o ritratti nel repository).")
    return radice


# --- Impronte -------------------------------------------------------------------------------------------------------------------------


def sha256_file(percorso: Path) -> str:
    h = hashlib.sha256()
    with open(percorso, "rb") as f:
        for blocco in iter(lambda: f.read(1 << 20), b""):
            h.update(blocco)
    return h.hexdigest()


def impronte_note() -> dict:
    with open(QUI / "fingerprints.json", encoding="utf-8") as f:
        return json.load(f)


def verifica_onnx(cartella_onnx: Path, nome: str) -> Path:
    """Il file ONNX `nome` con dimensione e SHA-256 uguali a quelli noti; altrimenti si ferma (i file scaricati sono dati non fidati)."""
    attesa = impronte_note()["file"][nome]
    percorso = cartella_onnx / nome
    if not percorso.is_file():
        sys.exit(f"Manca {percorso}: lanciare prima fetch_onnx.py.")
    if percorso.stat().st_size != attesa["dimensione"]:
        sys.exit(f"{nome}: dimensione {percorso.stat().st_size} invece di {attesa['dimensione']}. Mi fermo.")
    trovata = sha256_file(percorso)
    if trovata != attesa["sha256"]:
        sys.exit(f"{nome}: SHA-256 {trovata} invece di {attesa['sha256']}. Mi fermo.")
    return percorso


# --- Riquadri ------------------------------------------------------------------------------------------------------------------------


def iou(a, b) -> float:
    """IoU di due riquadri (x, y, larghezza, altezza)."""
    ax2, ay2, bx2, by2 = a[0] + a[2], a[1] + a[3], b[0] + b[2], b[1] + b[3]
    iw = max(0.0, min(ax2, bx2) - max(a[0], b[0]))
    ih = max(0.0, min(ay2, by2) - max(a[1], b[1]))
    inter = iw * ih
    unione = a[2] * a[3] + b[2] * b[3] - inter
    return float(inter / unione) if unione > 0 else 0.0


def nms(rilevamenti: list[dict], soglia_iou: float, massimo: int = 5000) -> list[dict]:
    """Soppressione dei doppi: ordina per punteggio, tiene un riquadro se non si sovrappone (IoU > soglia) a uno gia' tenuto."""
    ordinati = sorted(rilevamenti, key=lambda r: -r["punteggio"])[:massimo]
    tenuti: list[dict] = []
    for r in ordinati:
        if all(iou(r["riquadro"], t["riquadro"]) <= soglia_iou for t in tenuti):
            tenuti.append(r)
    return tenuti


# --- YuNet: decodifica delle dodici uscite grezze (le fa il nostro codice, non il modello; §2.2 punto 2) -----------------------------

PASSI = (8, 16, 32)
SOGLIA_RILEVAMENTO = 0.6
SOGLIA_NMS = 0.3


def decodifica_yunet(uscite: dict[str, np.ndarray], lato: int = 1024, soglia: float = SOGLIA_RILEVAMENTO, soglia_nms: float = SOGLIA_NMS,
                     senza_nms: bool = False) -> list[dict]:
    """Da cls/obj/bbox/kps delle tre scale ai rilevamenti `{riquadro: (x, y, w, h), punti: 5x2, punteggio}` nelle coordinate del quadro.

    Come `FaceDetectorYN` di OpenCV: punteggio = radice di clamp(cls) * clamp(obj); centro = (colonna + dx) * passo; lato = exp(dw) * passo;
    cinque punti = (kps + colonna) * passo. `uscite` ha le chiavi cls_8 ... kps_32 con forma [1, N, C] (N = (lato/passo)^2).
    """
    trovati: list[dict] = []
    for passo in PASSI:
        celle = lato // passo
        cls = np.clip(uscite[f"cls_{passo}"].reshape(-1), 0.0, 1.0)
        obj = np.clip(uscite[f"obj_{passo}"].reshape(-1), 0.0, 1.0)
        bbox = uscite[f"bbox_{passo}"].reshape(-1, 4)
        kps = uscite[f"kps_{passo}"].reshape(-1, 10)
        if cls.shape[0] != celle * celle:
            raise ValueError(f"cls_{passo}: {cls.shape[0]} celle invece di {celle * celle}")
        punteggio = np.sqrt(cls * obj)
        for idx in np.nonzero(punteggio >= soglia)[0]:
            riga, colonna = divmod(int(idx), celle)
            cx = (colonna + bbox[idx, 0]) * passo
            cy = (riga + bbox[idx, 1]) * passo
            w = float(np.exp(bbox[idx, 2])) * passo
            h = float(np.exp(bbox[idx, 3])) * passo
            punti = np.stack([(kps[idx, 0::2] + colonna) * passo, (kps[idx, 1::2] + riga) * passo], axis=1)
            trovati.append({"riquadro": (float(cx - w / 2), float(cy - h / 2), w, h), "punti": punti.astype(np.float64),
                            "punteggio": float(punteggio[idx])})
    return trovati if senza_nms else nms(trovati, soglia_nms)


def quadro_yunet(immagine_bgr: np.ndarray, lato: int = 1024) -> tuple[np.ndarray, float]:
    """Ridimensiona a lato lungo `lato` (anche in su) con i bordi a 0 in basso e a destra. Ritorna il quadro BGR uint8 e la scala usata."""
    import cv2

    h, w = immagine_bgr.shape[:2]
    scala = lato / max(h, w)
    nw, nh = max(1, round(w * scala)), max(1, round(h * scala))
    ridotta = cv2.resize(immagine_bgr, (nw, nh), interpolation=cv2.INTER_AREA if scala < 1 else cv2.INTER_LINEAR)
    quadro = np.zeros((lato, lato, 3), dtype=np.uint8)
    quadro[:nh, :nw] = ridotta
    return quadro, scala


# --- SFace: allineamento ai cinque punti del modello a 112 px di ArcFace (docs/fase8-progetto.md §2.2 "Nel telefono") ----------------

MODELLO_112 = np.array([[38.2946, 51.6963], [73.5318, 51.5014], [56.0252, 71.7366], [41.5493, 92.3655], [70.7299, 92.2041]], dtype=np.float64)


def similitudine_umeyama(origine: np.ndarray, destinazione: np.ndarray) -> np.ndarray:
    """Matrice 2x3 della similitudine (rotazione, scala uniforme, traslazione) ai minimi quadrati da `origine` a `destinazione` (Nx2)."""
    n = origine.shape[0]
    mo, md = origine.mean(axis=0), destinazione.mean(axis=0)
    o, d = origine - mo, destinazione - md
    cov = d.T @ o / n
    u, s, vt = np.linalg.svd(cov)
    segno = np.ones(2)
    if np.linalg.det(cov) < 0:
        segno[-1] = -1
    r = u @ np.diag(segno) @ vt
    varianza = (o ** 2).sum() / n
    scala = (s * segno).sum() / varianza
    t = md - scala * r @ mo
    return np.hstack([scala * r, t[:, None]])


def volto_allineato(immagine_bgr: np.ndarray, punti: np.ndarray) -> np.ndarray:
    """Il volto 112x112 allineato (BGR uint8) dai cinque punti nell'immagine; interpolazione bilineare, bordi a 0."""
    import cv2

    m = similitudine_umeyama(np.asarray(punti, dtype=np.float64), MODELLO_112)
    return cv2.warpAffine(immagine_bgr, m, (112, 112), flags=cv2.INTER_LINEAR, borderMode=cv2.BORDER_CONSTANT, borderValue=(0, 0, 0))


def normalizza(v: np.ndarray) -> np.ndarray:
    v = np.asarray(v, dtype=np.float64).reshape(-1)
    return v / np.linalg.norm(v)


def coseno(a: np.ndarray, b: np.ndarray) -> float:
    return float(np.dot(normalizza(a), normalizza(b)))
