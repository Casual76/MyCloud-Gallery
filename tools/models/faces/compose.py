"""Genera le composizioni sintetiche di prova (Fase 8, P1; docs/fase8-progetto.md §5.2) dai ritratti di pubblico dominio.

    python -I compose.py --root "C:\\...\\mcg-models\\faces" [--seme 20261027]

Cosa fa: trova i volti nitidi dei ritratti (consenso dei due YuNet: tutti e due >= 0,8 e IoU fra loro >= 0,8, lato >= 60 px), li ritaglia con un
margine e un bordo sfumato e li incolla, a scale diverse, specchiati e ritoccati, su sfondi SENZA volti (sfumature, forme, pezzi di ritratto
lontani dai volti). Tre serie, tutte di 1024x1024, in `<root>/out/composizioni/`:
  A  «gruppo»      60 immagini, 3-8 volti ciascuna, lato corto del riquadro 24-160 px (distribuzione logaritmica)
  B  «piccoli»     45 immagini, 8 volti ciascuna, lato corto 24-47 px (>= 300 volti)
  C  «senza volti» 40 immagini, solo sfondo (per i falsi positivi)
`verita.json` dice, per ogni immagine, riquadro, cinque punti e lato corto di ogni volto incollato e da quale ritratto viene. La verita' e'
per costruzione: i riquadri e i punti sono quelli del consenso sul ritratto, portati alla scala e alla posizione dell'incollatura.
Nessuna immagine di composizione entra nel repository.
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))
import common  # noqa: E402
import dati  # noqa: E402
import engines  # noqa: E402

LATO = 1024
MARGINE = 0.5  # il ritaglio e' il riquadro piu' mezzo riquadro da ogni parte
SERIE = {"A": 60, "B": 45, "C": 40}


def volti_dei_ritratti(radice: Path) -> list[dict]:
    """I volti nitidi dei ritratti singoli, con il consenso dei due YuNet (ONNX a 1024 px) sul ritratto a piena risoluzione."""
    import cv2

    a = engines.YuNetOnnx(radice / "out" / "onnx-1024" / "face_detection_yunet_2023mar.onnx")
    b = engines.YuNetOnnx(radice / "out" / "onnx-1024" / "face_detection_yunet_2026may.onnx")
    elenco = json.loads((radice / "ritratti" / "ELENCO.json").read_text(encoding="utf-8"))
    volti = []
    for voce in elenco:
        img = cv2.imread(str(radice / "ritratti" / f"{voce['etichetta']}.jpg"))
        quadro, scala = common.quadro_yunet(img)
        da, db = engines.rilevamenti(a, quadro), engines.rilevamenti(b, quadro)
        for v in dati.consenso_ritratto(da, db, scala):
            volti.append({"ritratto": voce["etichetta"], "soggetto": voce["soggetto"], "riquadro": v["riquadro"], "punti": v["punti"]})
    return volti


def regioni_senza_volti(radice: Path, volti: list[dict]) -> list[np.ndarray]:
    """Pezzi di ritratto da usare come sfondo: i ritratti singoli (non le scene affollate) senza i volti e un largo bordo intorno."""
    import cv2

    elenco = json.loads((radice / "ritratti" / "ELENCO.json").read_text(encoding="utf-8"))
    pezzi = []
    for voce in elenco:
        if voce["soggetto"].startswith("m"):
            continue
        img = cv2.imread(str(radice / "ritratti" / f"{voce['etichetta']}.jpg"))
        h, w = img.shape[:2]
        proibito = np.zeros((h, w), np.uint8)
        for v in (x for x in volti if x["ritratto"] == voce["etichetta"]):
            x0, y0, bw, bh = v["riquadro"]
            cv2.rectangle(proibito, (int(x0 - bw), int(y0 - bh)), (int(x0 + 2 * bw), int(y0 + 2 * bh)), 1, -1)
        # tre pezzi di 192 px (ridotti dal ritratto) per ritratto, sul bordo basso e laterale, se non toccano il vietato
        for (px, py) in ((w // 8, int(h * 0.72)), (int(w * 0.55), int(h * 0.72)), (w // 10, h // 3)):
            lato = min(w // 3, 360)
            if py + lato > h or px + lato > w or proibito[py:py + lato, px:px + lato].any():
                continue
            pezzi.append(cv2.resize(img[py:py + lato, px:px + lato], (192, 192), interpolation=cv2.INTER_AREA))
    return pezzi


def sfondo(rng: np.random.RandomState, pezzi: list[np.ndarray]) -> np.ndarray:
    import cv2

    tipo = rng.randint(3)
    if tipo == 0:  # sfumatura verticale con rumore a bassa frequenza
        a, b = rng.randint(30, 230, 3), rng.randint(30, 230, 3)
        t = np.linspace(0, 1, LATO)[:, None, None]
        img = (a * (1 - t) + b * t) * np.ones((1, LATO, 1))
        rumore = cv2.resize(rng.randn(12, 12, 3).astype(np.float32), (LATO, LATO), interpolation=cv2.INTER_CUBIC) * 18
        return np.clip(img + rumore, 0, 255).astype(np.uint8)
    if tipo == 1 and pezzi:  # mosaico di pezzi di ritratto (consistenza di una pittura)
        img = np.zeros((LATO, LATO, 3), np.uint8)
        for y in range(0, LATO, 192):
            for x in range(0, LATO, 192):
                p = pezzi[rng.randint(len(pezzi))]
                if rng.rand() < 0.5:
                    p = p[:, ::-1]
                img[y:y + 192, x:x + 192] = p[: LATO - y, : LATO - x]
        return cv2.GaussianBlur(img, (0, 0), 6 + 4 * rng.rand())  # il mosaico si sfoca: niente bordi duri che somiglino a volti
    img = np.full((LATO, LATO, 3), rng.randint(40, 200, 3), np.uint8)  # forme a caso su fondo piano
    for _ in range(rng.randint(8, 24)):
        colore = tuple(int(c) for c in rng.randint(0, 255, 3))
        x, y = rng.randint(0, LATO, 2)
        if rng.rand() < 0.5:
            cv2.rectangle(img, (x, y), (min(LATO - 1, x + rng.randint(40, 300)), min(LATO - 1, y + rng.randint(40, 300))), colore, -1)
        else:
            cv2.ellipse(img, (int(x), int(y)), (int(rng.randint(30, 200)), int(rng.randint(30, 200))), int(rng.randint(180)), 0, 360, colore, -1)
    return cv2.GaussianBlur(img, (0, 0), 3)


def incolla(tela: np.ndarray, sorgente: np.ndarray, volto: dict, lato_corto: float, x0: int, y0: int, rng: np.random.RandomState) -> dict:
    """Incolla il volto `volto` del ritratto `sorgente` con il riquadro di lato corto `lato_corto` e angolo del ritaglio in (x0, y0)."""
    import cv2

    bx, by, bw, bh = volto["riquadro"]
    cx0, cy0 = int(round(bx - MARGINE * bw)), int(round(by - MARGINE * bh))
    cw, ch = int(round(bw * (1 + 2 * MARGINE))), int(round(bh * (1 + 2 * MARGINE)))
    h, w = sorgente.shape[:2]
    ritaglio = np.zeros((ch, cw, 3), np.uint8)
    sx0, sy0, sx1, sy1 = max(cx0, 0), max(cy0, 0), min(cx0 + cw, w), min(cy0 + ch, h)
    ritaglio[sy0 - cy0:sy1 - cy0, sx0 - cx0:sx1 - cx0] = sorgente[sy0:sy1, sx0:sx1]
    punti = volto["punti"] - np.array([cx0, cy0])
    scala = lato_corto / min(bw, bh)
    nw, nh = max(4, int(round(cw * scala))), max(4, int(round(ch * scala)))
    ritaglio = cv2.resize(ritaglio, (nw, nh), interpolation=cv2.INTER_AREA if scala < 1 else cv2.INTER_CUBIC)
    punti = punti * np.array([nw / cw, nh / ch])
    riq = np.array([(bx - cx0) * nw / cw, (by - cy0) * nh / ch, bw * nw / cw, bh * nh / ch])
    specchiato = bool(rng.rand() < 0.5)
    if specchiato:
        ritaglio = np.ascontiguousarray(ritaglio[:, ::-1])
        punti = np.stack([nw - punti[:, 0], punti[:, 1]], axis=1)[[1, 0, 2, 4, 3]]
        riq[0] = nw - riq[0] - riq[2]
    contrasto, luce = 0.8 + 0.4 * rng.rand(), rng.randint(-25, 26)
    ritaglio = np.clip(ritaglio.astype(np.float32) * contrasto + luce, 0, 255).astype(np.uint8)
    sfocatura = 0.8 * rng.rand() if lato_corto < 60 else 0.4 * rng.rand()
    if sfocatura > 0.15:
        ritaglio = cv2.GaussianBlur(ritaglio, (0, 0), sfocatura)
    maschera = np.zeros((nh, nw), np.float32)
    cv2.ellipse(maschera, (nw // 2, nh // 2), (int(nw * 0.47), int(nh * 0.47)), 0, 0, 360, 1.0, -1)
    sigma = max(0.8, 0.08 * min(nw, nh))
    maschera = cv2.GaussianBlur(maschera, (0, 0), sigma)[:, :, None]
    ax0, ay0 = max(0, x0), max(0, y0)
    ax1, ay1 = min(LATO, x0 + nw), min(LATO, y0 + nh)
    parte = tela[ay0:ay1, ax0:ax1].astype(np.float32)
    m = maschera[ay0 - y0:ay1 - y0, ax0 - x0:ax1 - x0]
    tela[ay0:ay1, ax0:ax1] = (parte * (1 - m) + ritaglio[ay0 - y0:ay1 - y0, ax0 - x0:ax1 - x0].astype(np.float32) * m).astype(np.uint8)
    punti = punti + np.array([x0, y0])
    riq[0] += x0
    riq[1] += y0
    return {"ritratto": volto["ritratto"], "soggetto": volto["soggetto"], "specchiato": specchiato, "riquadro": [float(v) for v in riq],
            "punti": [[float(a), float(b)] for a, b in punti], "lato_corto": float(min(riq[2], riq[3]))}


def posizione_libera(rng, nw: int, nh: int, occupati: list[tuple[int, int, int, int]], prova: int = 60):
    for _ in range(prova):
        x, y = rng.randint(0, LATO - nw), rng.randint(0, LATO - nh)
        if all(x + nw + 6 <= o[0] or o[0] + o[2] + 6 <= x or y + nh + 6 <= o[1] or o[1] + o[3] + 6 <= y for o in occupati):
            return x, y
    return None


def componi(serie: str, indice: int, volti, sorgenti: dict[str, np.ndarray], pezzi, rng: np.random.RandomState) -> tuple[np.ndarray, list[dict]]:
    import cv2

    tela = sfondo(rng, pezzi)
    trovati: list[dict] = []
    if serie != "C":
        n = rng.randint(3, 9) if serie == "A" else 8
        occupati: list[tuple[int, int, int, int]] = []
        for _ in range(n):
            lato = float(np.exp(rng.uniform(np.log(24), np.log(160)))) if serie == "A" else float(rng.uniform(24, 47.99))
            volto = volti[rng.randint(len(volti))]
            bw, bh = volto["riquadro"][2:]
            nw, nh = int(round(bw * (1 + 2 * MARGINE) * lato / min(bw, bh))), int(round(bh * (1 + 2 * MARGINE) * lato / min(bw, bh)))
            pos = posizione_libera(rng, nw, nh, occupati)
            if pos is None:
                continue
            occupati.append((pos[0], pos[1], nw, nh))
            trovati.append(incolla(tela, sorgenti[volto["ritratto"]], volto, lato, pos[0], pos[1], rng))
    # il quadro e' un'anteprima: passa da una compressione JPEG come le anteprime vere
    ok, buf = cv2.imencode(".jpg", tela, [cv2.IMWRITE_JPEG_QUALITY, int(rng.randint(80, 95))])
    return cv2.imdecode(buf, cv2.IMREAD_COLOR), trovati


def main() -> None:
    import cv2

    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--root", help="cartella dei modelli dei volti, fuori dal repository (o MCG_FACES_ROOT)")
    ap.add_argument("--seme", type=int, default=20261027)
    args = ap.parse_args()
    radice = common.radice_modelli(args.root)
    volti = volti_dei_ritratti(radice)
    print(f"{len(volti)} volti nitidi da {len({v['ritratto'] for v in volti})} ritratti")
    sorgenti = {v["ritratto"]: cv2.imread(str(radice / "ritratti" / f"{v['ritratto']}.jpg")) for v in volti}
    pezzi = regioni_senza_volti(radice, volti)
    print(f"{len(pezzi)} pezzi di sfondo")
    rng = np.random.RandomState(args.seme)
    uscita = radice / "out" / "composizioni"
    verita: dict[str, list] = {}
    for serie, quante in SERIE.items():
        (uscita / serie).mkdir(parents=True, exist_ok=True)
        totale = 0
        for i in range(quante):
            img, vol = componi(serie, i, volti, sorgenti, pezzi, rng)
            nome = f"{serie}/img-{i:03d}.png"
            cv2.imwrite(str(uscita / nome), img)
            verita[nome] = vol
            totale += len(vol)
        print(f"serie {serie}: {quante} immagini, {totale} volti")
    (uscita / "verita.json").write_text(json.dumps({"seme": args.seme, "lato": LATO, "immagini": verita}, ensure_ascii=False), encoding="utf-8")


if __name__ == "__main__":
    main()
