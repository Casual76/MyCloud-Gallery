"""Genera le fixture numeriche dei motori dei volti (Fase 8, P2; docs/decisioni.md "Fase 8 P2"). Solo numeri: nessuna immagine, nessun volto.

    python -I genera_riferimenti.py --verita "<root>\\out\\composizioni\\verita.json" --dest <cartella delle fixture>

Cosa scrive (tutto in `--dest`, file di testo):
  - `tensori-sintetici.txt`: le dodici uscite grezze di YuNet **ricostruite** dai numeri di P1 (`verita.json`: riquadri e cinque punti dei volti
    incollati nelle composizioni, ricavati da P1 dal consenso dei due YuNet sul ritratto), con le celle vicine che il modello fa accendere
    per lo stesso volto, i doppi fra scale, le celle appena sotto e appena sopra la soglia e i riquadri a cavallo della soglia dell'NMS;
    in forma *sparsa* (una cella non scritta vale zero: punteggio zero, mai un candidato). L'**atteso** non lo calcola questo script ma il
    decodificatore di riferimento di P1 (`tools/models/faces/common.py::decodifica_yunet`, provato da P1 contro `FaceDetectorYN` di OpenCV);
  - `allineamento.txt`: insiemi di cinque punti (quelli di verita' di P1, con scarti realistici) e la matrice che P1 ha calcolato per
    portarli sul modello a 112 px di ArcFace (`common.similitudine_umeyama`).
P1 non ha lasciato i tensori grezzi veri dei modelli (solo le misure di parita' e la verita' di terra): questi sono quindi tensori costruiti,
non registrati. Le prove con tensori veri si ottengono con `core-ai/src/androidTest/faces/esporta_riferimenti.py --tensori` (stesso formato).
Il seme e' fisso: due esecuzioni danno gli stessi byte.
"""
from __future__ import annotations

import argparse
import json
import math
import sys
from pathlib import Path

import numpy as np

AQUI = Path(__file__).resolve().parent
REPO = AQUI.parents[4]
sys.path.insert(0, str(REPO / "tools" / "models" / "faces"))
import common  # noqa: E402  (il decodificatore di riferimento di P1)

PASSI = (8, 16, 32)
CELLE = {8: 128, 16: 64, 32: 32}


def esadecimale(valori) -> str:
    """Float32 come 8 cifre esadecimali (esatto, nessun arrotondamento di stampa)."""
    return " ".join(f"{int(x):08x}" for x in np.asarray(valori, dtype=np.float32).view(np.uint32))


class Uscite:
    def __init__(self):
        self.t = {}
        for p in PASSI:
            n = CELLE[p] ** 2
            self.t[f"cls_{p}"] = np.zeros((1, n, 1), np.float32)
            self.t[f"obj_{p}"] = np.zeros((1, n, 1), np.float32)
            self.t[f"bbox_{p}"] = np.zeros((1, n, 4), np.float32)
            self.t[f"kps_{p}"] = np.zeros((1, n, 10), np.float32)
        self.celle = {}  # (passo, indice) -> vettore a 16 valori, per scriverle sparse

    def metti(self, passo, riga, col, punteggio, riquadro, punti, rng, rumore=0.0):
        """Scrive una cella che prevede `riquadro` (x, y, w, h) e `punti` (5x2) del quadro; `punteggio` = radice(cls * obj) con cls = obj."""
        n = CELLE[passo]
        if not (0 <= riga < n and 0 <= col < n):
            return
        idx = riga * n + col
        x, y, w, h = riquadro
        cx, cy = x + w / 2, y + h / 2
        b = np.array([cx / passo - col, cy / passo - riga, math.log(w / passo), math.log(h / passo)], np.float32)
        k = np.empty(10, np.float32)
        for i in range(5):
            k[2 * i] = punti[i][0] / passo - col
            k[2 * i + 1] = punti[i][1] / passo - riga
        if rumore:
            b += rng.normal(0, rumore, 4).astype(np.float32)
            k += rng.normal(0, rumore, 10).astype(np.float32)
        s = np.float32(punteggio)
        self.t[f"cls_{passo}"][0, idx, 0] = s
        self.t[f"obj_{passo}"][0, idx, 0] = s
        self.t[f"bbox_{passo}"][0, idx] = b
        self.t[f"kps_{passo}"][0, idx] = k
        self.celle[(passo, idx)] = None

    def righe(self):
        for (passo, idx) in sorted(self.celle):
            n = CELLE[passo]
            v = np.concatenate([self.t[f"cls_{passo}"][0, idx], self.t[f"obj_{passo}"][0, idx], self.t[f"bbox_{passo}"][0, idx],
                                self.t[f"kps_{passo}"][0, idx]])
            yield f"cella {passo} {idx // n} {idx % n} {esadecimale(v)}"


def passo_per(lato):
    return 8 if lato < 36 else (16 if lato < 90 else 32)


def accendi_volto(u, volto, rng, vicine=True, doppio_di_scala=True):
    """Le celle che il modello accende per un volto: la centrale, le otto vicine piu' deboli e (a volte) la scala accanto."""
    riquadro = tuple(volto["riquadro"])
    punti = [tuple(p) for p in volto["punti"]]
    lato = min(riquadro[2:])
    passo = passo_per(lato)
    cx, cy = riquadro[0] + riquadro[2] / 2, riquadro[1] + riquadro[3] / 2
    col, riga = int(cx // passo), int(cy // passo)
    centrale = float(rng.uniform(0.80, 0.97))
    u.metti(passo, riga, col, centrale, riquadro, punti, rng, rumore=0.004)
    if vicine:
        for dr in (-1, 0, 1):
            for dc in (-1, 0, 1):
                if dr == 0 and dc == 0:
                    continue
                u.metti(passo, riga + dr, col + dc, max(0.05, centrale - float(rng.uniform(0.05, 0.40))), riquadro, punti, rng, rumore=0.02)
    if doppio_di_scala and passo != 32 and rng.uniform() < 0.6:
        altro = passo * 2
        u.metti(altro, int(cy // altro), int(cx // altro), max(0.05, centrale - float(rng.uniform(0.10, 0.45))), riquadro, punti, rng, rumore=0.03)


def distrattori(u, rng, quanti, sotto=(0.30, 0.59)):
    """Celle isolate con un punteggio sotto la soglia di 0,6 e riquadri a caso: non devono mai uscire."""
    for _ in range(quanti):
        passo = int(rng.choice(PASSI))
        n = CELLE[passo]
        riga, col = int(rng.integers(0, n)), int(rng.integers(0, n))
        w, h = float(rng.uniform(20, 200)), float(rng.uniform(20, 200))
        x, y = float(rng.uniform(0, 900)), float(rng.uniform(0, 900))
        punti = [(x + w * fx, y + h * fy) for fx, fy in ((0.3, 0.4), (0.7, 0.4), (0.5, 0.6), (0.35, 0.8), (0.65, 0.8))]
        u.metti(passo, riga, col, float(rng.uniform(*sotto)), (x, y, w, h), punti, rng)


def scrivi_atteso(u):
    atteso = common.decodifica_yunet(u.t)
    atteso.sort(key=lambda r: -r["punteggio"])
    for r in atteso:
        x, y, w, h = r["riquadro"]
        punti = " ".join(f"{v:.6f}" for v in np.asarray(r["punti"]).reshape(-1))
        yield f"atteso {r['punteggio']:.7f} {x:.6f} {y:.6f} {w:.6f} {h:.6f} {punti}"


def scena_volti(nome, volti, rng, extra_distrattori=12):
    u = Uscite()
    for v in volti:
        accendi_volto(u, v, rng)
    distrattori(u, rng, extra_distrattori)
    return nome, u


def scena_soglia_e_nms(rng):
    """Le due soglie: punteggio appena sotto/sopra 0,6 e riquadri con IoU appena sopra/sotto 0,3 (A tenuto, B soppresso, C tenuto)."""
    u = Uscite()
    punti = lambda x, y, w, h: [(x + w * fx, y + h * fy) for fx, fy in ((0.3, 0.4), (0.7, 0.4), (0.5, 0.6), (0.35, 0.8), (0.65, 0.8))]
    # Soglia: 0,598 non esce, 0,603 si'.
    for i, s in enumerate((0.598, 0.603)):
        box = (40.0 + 220 * i, 40.0, 64.0, 64.0)
        u.metti(16, int((box[1] + 32) // 16), int((box[0] + 32) // 16), s, box, punti(*box), rng)
    # NMS: A 100x100 in (400, 400); B spostato in modo che l'IoU con A valga circa 0,34 (soppresso); C con IoU circa 0,26 con A (tenuto).
    # IoU di due quadrati di lato L spostati di d in x: (L - d) / (L + d). d = 0,49 L -> 0,342 ; d = 0,59 L -> 0,258.
    L = 100.0
    caselle = [((400.0, 400.0, L, L), 0.95), ((400.0 + 0.49 * L, 400.0, L, L), 0.90), ((400.0 - 0.59 * L, 400.0, L, L), 0.85)]
    for box, s in caselle:
        u.metti(32, int((box[1] + box[3] / 2) // 32), int((box[0] + box[2] / 2) // 32), s, box, punti(*box), rng)
    # Tre cellule vicine con lo stesso punteggio: l'ordine di arrivo decide chi resta.
    box = (700.0, 100.0, 60.0, 60.0)
    for dc in (0, 1, 2):
        u.metti(8, int((box[1] + 30) // 8), int((box[0] + 30) // 8) + dc, 0.80, box, punti(*box), rng)
    return "soglia-e-nms", u


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--verita", required=True, help="verita.json di P1 (composizioni)")
    ap.add_argument("--dest", required=True)
    args = ap.parse_args()
    verita = json.loads(Path(args.verita).read_text(encoding="utf-8"))["immagini"]
    dest = Path(args.dest)
    rng = np.random.default_rng(20261028)

    scene = []
    for nome in ("A/img-000.png", "A/img-001.png", "A/img-004.png", "B/img-000.png", "B/img-002.png"):
        volti = [{"riquadro": v["riquadro"], "punti": v["punti"]} for v in verita[nome]]
        scene.append(scena_volti("composizione-" + nome.replace("/", "-").replace(".png", "").lower(), volti, rng))
    scene.append(scena_volti("senza-volti", [], rng, extra_distrattori=40))
    scene.append(scena_soglia_e_nms(rng))

    righe = ["MCGF-TENSORI 1",
             "# Uscite grezze di YuNet in forma sparsa (celle non scritte = zero): `cella <passo> <riga> <colonna> <16 float32 in esadecimale>`",
             "# con i valori nell'ordine cls, obj, bbox0..3, kps0..9; `atteso <punteggio> <x> <y> <w> <h> <x0> <y0> ... <x4> <y4>` dal decodificatore",
             "# di riferimento di P1 (common.decodifica_yunet), in ordine di punteggio. Costruite da genera_riferimenti.py dai numeri di P1."]
    for nome, u in scene:
        righe.append(f"immagine {nome}")
        righe.extend(u.righe())
        righe.extend(scrivi_atteso(u))
        righe.append("fine")
    (dest / "tensori-sintetici.txt").write_text("\n".join(righe) + "\n", encoding="utf-8", newline="\n")

    # Allineamento: i cinque punti di verita' di P1, anche ruotati e scalati, e la matrice di P1.
    righe = ["MCGF-ALLINEAMENTO 1",
             "# `punti <10 float32 esadecimali>` e `matrice <6 numeri>` (a, b, tx, ty in forma [[a, -b, tx], [b, a, ty]]) di P1 (common.similitudine_umeyama)"]
    casi = []
    for nome in ("A/img-000.png", "A/img-002.png", "B/img-001.png"):
        for v in verita[nome][:4]:
            casi.append(np.array(v["punti"], dtype=np.float64))
    for i in range(12):
        # Un insieme di punti ruotato, scalato e spostato a caso, con uno scarto di 0,5 px su ogni punto.
        base = common.MODELLO_112.copy()
        ang = rng.uniform(-0.7, 0.7)
        s = rng.uniform(0.3, 4.0)
        r = np.array([[math.cos(ang), -math.sin(ang)], [math.sin(ang), math.cos(ang)]])
        casi.append((base @ r.T) * s + rng.uniform(50, 900, 2) + rng.normal(0, 0.5, (5, 2)))
    for punti in casi:
        punti32 = punti.astype(np.float32)
        m = common.similitudine_umeyama(punti32.astype(np.float64), common.MODELLO_112)
        righe.append(f"punti {esadecimale(punti32.reshape(-1))}")
        righe.append("matrice " + " ".join(f"{v:.9f}" for v in m.reshape(-1)))
    (dest / "allineamento.txt").write_text("\n".join(righe) + "\n", encoding="utf-8", newline="\n")
    print(f"scritte {len(scene)} scene e {len(casi)} casi di allineamento in {dest}")


if __name__ == "__main__":
    main()
