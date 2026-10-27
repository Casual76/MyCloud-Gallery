"""Prove delle funzioni pure di `common.py` (Fase 8, P1): nessun modello, nessun dato. `python -I selftest.py` esce con 0 se tutto torna.

Vale come rete di sicurezza per chi cambia la decodifica di YuNet o l'allineamento: i modelli veri si provano con `parity.py` e
`verify_opencv.py`, queste prove dicono subito se si e' rotto qualcosa di piu' semplice.
"""
from __future__ import annotations

import sys
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))
import common  # noqa: E402


def prova_iou_e_nms() -> None:
    assert abs(common.iou((0, 0, 10, 10), (0, 0, 10, 10)) - 1.0) < 1e-12
    assert common.iou((0, 0, 10, 10), (20, 20, 5, 5)) == 0.0
    assert abs(common.iou((0, 0, 10, 10), (5, 0, 10, 10)) - 50 / 150) < 1e-12
    r = [{"riquadro": (0, 0, 10, 10), "punteggio": 0.9}, {"riquadro": (1, 0, 10, 10), "punteggio": 0.8}, {"riquadro": (50, 50, 10, 10), "punteggio": 0.7}]
    assert [x["punteggio"] for x in common.nms(r, 0.3)] == [0.9, 0.7]


def prova_similitudine() -> None:
    rng = np.random.RandomState(7)
    for _ in range(50):
        angolo, scala, t = rng.uniform(-1, 1), rng.uniform(0.3, 3), rng.uniform(-50, 50, 2)
        r = np.array([[np.cos(angolo), -np.sin(angolo)], [np.sin(angolo), np.cos(angolo)]])
        origine = rng.uniform(0, 200, (5, 2))
        destinazione = scala * origine @ r.T + t
        m = common.similitudine_umeyama(origine, destinazione)
        ricostruita = origine @ m[:, :2].T + m[:, 2]
        assert np.max(np.abs(ricostruita - destinazione)) < 1e-8
    # Specchio: la similitudine non deve ribaltare (determinante positivo).
    m = common.similitudine_umeyama(common.MODELLO_112 * np.array([-1, 1]) + [112, 0], common.MODELLO_112)
    assert np.linalg.det(m[:, :2]) > 0


def prova_decodifica() -> None:
    lato = 64
    uscite = {}
    for passo in common.PASSI:
        n = (lato // passo) ** 2
        uscite[f"cls_{passo}"] = np.zeros((1, n, 1), np.float32)
        uscite[f"obj_{passo}"] = np.zeros((1, n, 1), np.float32)
        uscite[f"bbox_{passo}"] = np.zeros((1, n, 4), np.float32)
        uscite[f"kps_{passo}"] = np.zeros((1, n, 10), np.float32)
    # Un volto a passo 8: cella (riga 2, colonna 3), centro (3 + 0,5) * 8 = 28 e (2 + 0,25) * 8 = 18, lato 16 * 1 = 8 * exp(ln 2)
    celle = lato // 8
    i = 2 * celle + 3
    uscite["cls_8"][0, i, 0], uscite["obj_8"][0, i, 0] = 0.81, 1.0
    uscite["bbox_8"][0, i] = [0.5, 0.25, np.log(2.0), np.log(2.0)]
    uscite["kps_8"][0, i] = [-1, -0.5, 1, -0.5, 0, 0, -1, 1, 1, 1]
    trovati = common.decodifica_yunet(uscite, lato=lato)
    assert len(trovati) == 1
    t = trovati[0]
    assert abs(t["punteggio"] - 0.9) < 1e-6
    assert np.allclose(t["riquadro"], (28 - 8, 18 - 8, 16, 16))
    assert np.allclose(t["punti"][0], ((3 - 1) * 8, (2 - 0.5) * 8)) and np.allclose(t["punti"][4], ((3 + 1) * 8, (2 + 1) * 8))
    # Sotto soglia non esce niente.
    uscite["cls_8"][0, i, 0] = 0.1
    assert common.decodifica_yunet(uscite, lato=lato) == []


def prova_quadro() -> None:
    img = np.full((300, 600, 3), 200, np.uint8)
    quadro, scala = common.quadro_yunet(img, 1024)
    assert quadro.shape == (1024, 1024, 3) and abs(scala - 1024 / 600) < 1e-12
    assert quadro[:512].min() > 0 and quadro[513:].max() == 0  # il contenuto sta in alto, i bordi a 0 in basso


if __name__ == "__main__":
    for prova in (prova_iou_e_nms, prova_similitudine, prova_decodifica, prova_quadro):
        prova()
        print("ok", prova.__name__)
