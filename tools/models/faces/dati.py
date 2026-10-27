"""Dati di prova e abbinamenti per `parity.py` e `choose.py` (Fase 8, P1). Nessun dato personale: ritratti di pubblico dominio e composizioni
sintetiche generate da `compose.py`, tutti fuori dal repository (`<root>/ritratti`, `<root>/out/composizioni`)."""
from __future__ import annotations

import json
from pathlib import Path

import numpy as np

import common


def ritratti(radice: Path) -> list[dict]:
    import cv2

    elenco = json.loads((radice / "ritratti" / "ELENCO.json").read_text(encoding="utf-8"))
    return [{"etichetta": e["etichetta"], "soggetto": e["soggetto"], "immagine": cv2.imread(str(radice / "ritratti" / f"{e['etichetta']}.jpg"))}
            for e in elenco]


def composizioni(radice: Path, serie: str) -> list[dict]:
    """Le immagini di una serie (A, B o C) con la verita' per costruzione: [{nome, immagine, volti: [{riquadro, punti, lato_corto, ...}]}]."""
    import cv2

    base = radice / "out" / "composizioni"
    verita = json.loads((base / "verita.json").read_text(encoding="utf-8"))["immagini"]
    uscita = []
    for nome in sorted(n for n in verita if n.startswith(serie + "/")):
        volti = [{**v, "punti": np.array(v["punti"], dtype=np.float64)} for v in verita[nome]]
        uscita.append({"nome": nome, "immagine": cv2.imread(str(base / nome)), "volti": volti})
    return uscita


def abbina(rilevamenti: list[dict], verita: list[dict], iou_minimo: float) -> tuple[list[tuple[int, int, float]], list[int], list[int]]:
    """Abbinamento goloso per IoU decrescente fra riquadri trovati e riquadri di verita'.

    Ritorna (coppie (indice trovato, indice verita', IoU), trovati senza volto di verita', volti di verita' senza riquadro)."""
    candidati = []
    for i, r in enumerate(rilevamenti):
        for j, v in enumerate(verita):
            valore = common.iou(r["riquadro"], v["riquadro"])
            if valore >= iou_minimo:
                candidati.append((valore, i, j))
    candidati.sort(reverse=True)
    usati_r, usati_v, coppie = set(), set(), []
    for valore, i, j in candidati:
        if i in usati_r or j in usati_v:
            continue
        usati_r.add(i)
        usati_v.add(j)
        coppie.append((i, j, valore))
    return (coppie, [i for i in range(len(rilevamenti)) if i not in usati_r], [j for j in range(len(verita)) if j not in usati_v])


def consenso_ritratto(rilevamenti_a: list[dict], rilevamenti_b: list[dict], scala: float) -> list[dict]:
    """I volti di un ritratto che entrambi i modelli trovano (IoU >= 0,8, punteggio >= 0,8 per tutti e due) e che a piena risoluzione hanno
    il lato corto >= 60 px. I rilevamenti sono quelli del quadro 1024x1024 (`common.quadro_yunet`, che ha usato `scala`); il risultato porta
    riquadro e punti nelle coordinate del ritratto a piena risoluzione."""
    uscita = []
    for r in rilevamenti_a:
        altro = max(rilevamenti_b, key=lambda x: common.iou(r["riquadro"], x["riquadro"]), default=None)
        if altro is None or common.iou(r["riquadro"], altro["riquadro"]) < 0.8 or min(r["punteggio"], altro["punteggio"]) < 0.8:
            continue
        riquadro = tuple(v / scala for v in r["riquadro"])
        if min(riquadro[2:]) >= 60:
            uscita.append({"riquadro": riquadro, "punti": r["punti"] / scala, "punteggio": min(r["punteggio"], altro["punteggio"])})
    return uscita


def ritratto_ridotto(immagine: np.ndarray, volto: dict, lato_volto: float) -> tuple[np.ndarray, dict]:
    """Il ritratto ridotto in modo che il riquadro `volto` misuri `lato_volto` px di lato corto, al centro di un quadro 1024x1024 del colore
    medio del ritratto. Ritorna il quadro e il volto di verita' nelle coordinate del quadro. `volto`: riquadro e punti nelle coordinate
    del ritratto a piena risoluzione."""
    import cv2

    f = lato_volto / min(volto["riquadro"][2:])
    h, w = immagine.shape[:2]
    nw, nh = max(8, round(w * f)), max(8, round(h * f))
    piccola = cv2.resize(immagine, (nw, nh), interpolation=cv2.INTER_AREA)
    quadro = np.empty((1024, 1024, 3), np.uint8)
    quadro[:] = immagine.reshape(-1, 3).mean(axis=0).astype(np.uint8)
    x0, y0 = (1024 - nw) // 2, (1024 - nh) // 2
    quadro[y0:y0 + nh, x0:x0 + nw] = piccola
    bx, by, bw, bh = volto["riquadro"]
    return quadro, {"riquadro": (bx * f + x0, by * f + y0, bw * f, bh * f), "punti": volto["punti"] * f + np.array([x0, y0]),
                    "lato_corto": min(bw, bh) * f}
