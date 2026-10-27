"""Le composizioni dei casi dei volti (Fase 8, P11; docs/fase8-progetto.md §5.2 "Dati senza persone vere", casi E-F3, E-F15, E-F13/E-F14).

    python -I compose_casi.py --root "C:\\...\\mcg-models\\faces" [--seme 20261029] [--verifica]

Riusa `tools/models/faces/compose.py` di P1 (volti nitidi dei ritratti di pubblico dominio, sfondi senza volti, incollatura con bordo sfumato) e
aggiunge i casi che quello non copre. Quattro serie, tutte di 1024x1024, in `<root>/out/p11/` (fuori dal repository, come i ritratti):
  tv       8   E-F3: un ritratto dentro una "televisione" (primo piano sullo schermo, con righe e riflesso); meta' con una persona vera accanto
  doppia   8   E-F15: la stessa persona due volte nella stessa foto (a: riflesso in uno specchio, b: la sua foto incorniciata appesa al muro)
  collage  6   E-F15: un collage di 4, 6 o 9 riquadri con piu' ritratti degli stessi soggetti (anche autoritratti a eta' diverse, E-F2)
  piccoli 12   E-F13/E-F14: gruppi da 3 a 8 volti piccoli (lato corto 24-60 px) su sfondi diversi
`verita.json` dice, per ogni immagine, i volti con il loro ruolo (persona, tv, specchio, foto-nella-foto, collage), il ritratto di origine, il
riquadro e i cinque punti. Con --verifica passa le immagini da YuNet (ONNX 1024 px di P1) e riporta quanti volti di verita' trova e quanti
riquadri in piu' (il volto sulla TV e quello nella cornice li trova, ed e' proprio il caso da provare).
Nessuna immagine entra nel repository, e nei nomi e nelle etichette ci sono solo `ritratto-NN` e `sNN`: mai il nome di chi e' ritratto.
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

import numpy as np

QUI = Path(__file__).resolve().parent
sys.path.insert(0, str(QUI.parent.parent / "models" / "faces"))
import common  # noqa: E402
import compose  # noqa: E402
import engines  # noqa: E402

LATO = compose.LATO
SERIE = {"tv": 8, "doppia": 8, "collage": 6, "piccoli": 12}


def finale(tela: np.ndarray, rng: np.random.RandomState) -> np.ndarray:
    """Una compressione JPEG come le anteprime vere (qualita' 80-94)."""
    import cv2

    ok, buf = cv2.imencode(".jpg", tela, [cv2.IMWRITE_JPEG_QUALITY, int(rng.randint(80, 95))])
    return cv2.imdecode(buf, cv2.IMREAD_COLOR)


def stanza(rng: np.random.RandomState) -> np.ndarray:
    """Una parete a sfumatura con il pavimento in basso e un po' di rumore a bassa frequenza: nessun volto, nessuna forma che lo somigli."""
    import cv2

    a = rng.randint(120, 230, 3)
    b = np.clip(a + rng.randint(-30, 30, 3), 0, 255)
    t = np.linspace(0, 1, LATO)[:, None, None]
    img = (a * (1 - t) + b * t) * np.ones((1, LATO, 1))
    pavimento = int(LATO * 0.8)
    img[pavimento:] = img[pavimento:] * 0.55 + rng.randint(10, 40, 3)
    rumore = cv2.resize(rng.randn(10, 10, 3).astype(np.float32), (LATO, LATO), interpolation=cv2.INTER_CUBIC) * 10
    return np.clip(img + rumore, 0, 255).astype(np.uint8)


def ritaglio_volto(sorgente: np.ndarray, volto: dict, altezza_volti: float, proporzione: float) -> tuple[np.ndarray, dict]:
    """Un ritaglio attorno al volto, alto `altezza_volti` volte il riquadro e largo `proporzione` volte l'altezza, dentro il ritratto.
    Ritorna il ritaglio e il volto (riquadro e punti) nelle coordinate del ritaglio."""
    bx, by, bw, bh = volto["riquadro"]
    h, w = sorgente.shape[:2]
    alto = min(bh * altezza_volti, h, w / proporzione)
    largo = alto * proporzione
    x0 = float(np.clip(bx + bw / 2 - largo / 2, 0, w - largo))
    y0 = float(np.clip(by + bh / 2 - alto * 0.45, 0, h - alto))
    x0i, y0i, wi, hi = int(round(x0)), int(round(y0)), int(round(largo)), int(round(alto))
    pezzo = sorgente[y0i:y0i + hi, x0i:x0i + wi]
    return pezzo, {"riquadro": np.array([bx - x0i, by - y0i, bw, bh], dtype=np.float64), "punti": np.asarray(volto["punti"], dtype=np.float64) - np.array([x0i, y0i])}


def metti(tela: np.ndarray, pezzo: np.ndarray, volto: dict, x0: int, y0: int, larghezza: int, altezza: int, ruolo: str, origine: dict) -> dict:
    """Ridimensiona `pezzo` a larghezza x altezza, lo mette in (x0, y0) e ritorna la verita' del suo volto."""
    import cv2

    ph, pw = pezzo.shape[:2]
    tela[y0:y0 + altezza, x0:x0 + larghezza] = cv2.resize(pezzo, (larghezza, altezza), interpolation=cv2.INTER_AREA if larghezza < pw else cv2.INTER_CUBIC)
    sx, sy = larghezza / pw, altezza / ph
    r = volto["riquadro"] * np.array([sx, sy, sx, sy]) + np.array([x0, y0, 0, 0])
    p = volto["punti"] * np.array([sx, sy]) + np.array([x0, y0])
    return {"ruolo": ruolo, "ritratto": origine["ritratto"], "soggetto": origine["soggetto"], "riquadro": [float(v) for v in r],
            "punti": [[float(a), float(b)] for a, b in p], "lato_corto": float(min(r[2], r[3]))}


def televisione(tela, rng, volto, sorgente, x0, y0, larg, alt) -> dict:
    """Una televisione con lo schermo larg x alt in (x0, y0) e, dentro, il primo piano di un volto (E-F3)."""
    import cv2

    bordo = 14
    cv2.rectangle(tela, (x0 - bordo, y0 - bordo), (x0 + larg + bordo, y0 + alt + bordo), (25, 25, 28), -1)
    cx = x0 + larg // 2
    cv2.rectangle(tela, (cx - 60, y0 + alt + bordo), (cx + 60, y0 + alt + bordo + 18), (35, 35, 38), -1)
    cv2.rectangle(tela, (cx - 130, y0 + alt + bordo + 18), (cx + 130, y0 + alt + bordo + 30), (30, 30, 33), -1)
    pezzo, v = ritaglio_volto(sorgente, volto, altezza_volti=float(rng.uniform(2.2, 3.0)), proporzione=larg / alt)
    verita = metti(tela, pezzo, v, x0, y0, larg, alt, "tv", volto)
    schermo = tela[y0:y0 + alt, x0:x0 + larg].astype(np.float32)
    schermo[::3] *= 0.93  # le righe
    lucido = np.tile(np.linspace(0.10, 0.0, larg, dtype=np.float32), (alt, 1))[:, :, None] * 255
    tela[y0:y0 + alt, x0:x0 + larg] = np.clip(schermo + lucido, 0, 255).astype(np.uint8)  # il riflesso
    return verita


def caso_tv(i, rng, volti, sorgenti, pezzi) -> tuple[np.ndarray, list[dict]]:
    tela = stanza(rng)
    volto = volti[rng.randint(len(volti))]
    larg, alt = int(rng.randint(520, 700)), int(rng.randint(300, 400))
    x0, y0 = int(rng.randint(40, LATO - larg - 40)), int(rng.randint(60, 260))
    trovati = [televisione(tela, rng, volto, sorgenti[volto["ritratto"]], x0, y0, larg, alt)]
    if i % 2 == 1:  # una persona vera davanti alla TV
        altro = next(v for v in (volti[rng.randint(len(volti))] for _ in range(50)) if v["soggetto"] != volto["soggetto"])
        lato = float(rng.uniform(110, 170))
        bw, bh = altro["riquadro"][2:]
        nw, nh = int(round(bw * 2 * lato / min(bw, bh))), int(round(bh * 2 * lato / min(bw, bh)))
        pos = compose.posizione_libera(rng, nw, nh, [(x0 - 20, y0 - 20, larg + 40, alt + 90)])
        if pos is not None:
            vero = compose.incolla(tela, sorgenti[altro["ritratto"]], altro, lato, pos[0], pos[1], rng)
            trovati.append({"ruolo": "persona", **{k: vero[k] for k in ("ritratto", "soggetto", "riquadro", "punti", "lato_corto")}})
    return finale(tela, rng), trovati


def caso_doppia(i, rng, volti, sorgenti, pezzi) -> tuple[np.ndarray, list[dict]]:
    """a (i pari): la persona e il suo riflesso in uno specchio. b (i dispari): la persona e la sua foto incorniciata sul muro."""
    import cv2

    tela = stanza(rng) if i % 2 else compose.sfondo(rng, pezzi)
    volto = volti[rng.randint(len(volti))]
    sorgente = sorgenti[volto["ritratto"]]
    lato = float(rng.uniform(110, 160))
    bw, bh = volto["riquadro"][2:]
    nw, nh = int(round(bw * 2 * lato / min(bw, bh))), int(round(bh * 2 * lato / min(bw, bh)))
    x0, y0 = int(rng.randint(40, LATO // 2 - nw)), int(rng.randint(300, LATO - nh - 40))
    vero = compose.incolla(tela, sorgente, volto, lato, x0, y0, rng)
    trovati = [{"ruolo": "persona", **{k: vero[k] for k in ("ritratto", "soggetto", "riquadro", "punti", "lato_corto")}}]
    if i % 2 == 0:
        # Lo specchio: la regione con il volto, rovesciata, piu' scura e un po' sfocata, sul lato opposto.
        x1 = LATO - x0 - nw
        regione = tela[y0:y0 + nh, x0:x0 + nw].copy()
        riflesso = cv2.GaussianBlur(np.clip(regione[:, ::-1].astype(np.float32) * 0.82, 0, 255).astype(np.uint8), (0, 0), 1.2)
        tela[y0:y0 + nh, x1:x1 + nw] = riflesso
        cv2.rectangle(tela, (x1 - 10, y0 - 10), (x1 + nw + 10, y0 + nh + 10), (60, 50, 40), 10)
        r = vero["riquadro"]
        punti = np.array(vero["punti"], dtype=np.float64)
        punti = np.stack([nw - (punti[:, 0] - x0) + x1, punti[:, 1]], axis=1)[[1, 0, 2, 4, 3]]
        trovati.append({"ruolo": "specchio", "ritratto": volto["ritratto"], "soggetto": volto["soggetto"],
                        "riquadro": [float(x1 + nw - (r[0] - x0) - r[2]), float(r[1]), float(r[2]), float(r[3])],
                        "punti": [[float(a), float(b)] for a, b in punti], "lato_corto": float(min(r[2], r[3]))})
    else:
        # La foto appesa: un ritaglio piccolo con la cornice, in alto a destra.
        pezzo, v = ritaglio_volto(sorgente, volto, altezza_volti=2.4, proporzione=0.8)
        fa = int(rng.randint(150, 210))
        fl = int(fa * 0.8)
        fx, fy = int(rng.randint(LATO // 2 + 60, LATO - fl - 60)), int(rng.randint(60, 220))
        cv2.rectangle(tela, (fx - 14, fy - 14), (fx + fl + 14, fy + fa + 14), (70, 45, 25), -1)
        trovati.append(metti(tela, pezzo, v, fx, fy, fl, fa, "foto-nella-foto", volto))
    return finale(tela, rng), trovati


def caso_collage(i, rng, volti, sorgenti, pezzi) -> tuple[np.ndarray, list[dict]]:
    """Righe x colonne di riquadri con il volto di piu' ritratti: i soggetti con piu' ritratti tornano (autoritratti a eta' diverse)."""
    righe, colonne = [(2, 2), (2, 3), (3, 3)][i % 3]
    gap = 16
    cella = (LATO - (colonne + 1) * gap) // colonne
    cella_alt = (LATO - (righe + 1) * gap) // righe
    tela = np.full((LATO, LATO, 3), 235, np.uint8)
    per_soggetto: dict[str, list[dict]] = {}
    for v in volti:
        per_soggetto.setdefault(v["soggetto"], []).append(v)
    ripetuti = [s for s, vs in per_soggetto.items() if len(vs) >= 2]
    scelti = [per_soggetto[ripetuti[rng.randint(len(ripetuti))]][k] for k in range(2)]  # due ritratti dello stesso soggetto
    scelti += [volti[rng.randint(len(volti))] for _ in range(righe * colonne - len(scelti))]
    rng.shuffle(scelti)
    trovati = []
    for k, volto in enumerate(scelti):
        r, c = divmod(k, colonne)
        x0, y0 = gap + c * (cella + gap), gap + r * (cella_alt + gap)
        pezzo, v = ritaglio_volto(sorgenti[volto["ritratto"]], volto, altezza_volti=float(rng.uniform(2.0, 2.6)), proporzione=cella / cella_alt)
        trovati.append(metti(tela, pezzo, v, x0, y0, cella, cella_alt, "collage", volto))
    return finale(tela, rng), trovati


def caso_piccoli(i, rng, volti, sorgenti, pezzi) -> tuple[np.ndarray, list[dict]]:
    tela = compose.sfondo(rng, pezzi)
    trovati, occupati = [], []
    for _ in range(3 + i % 6):  # da 3 a 8
        lato = float(rng.uniform(24, 60))
        volto = volti[rng.randint(len(volti))]
        bw, bh = volto["riquadro"][2:]
        nw, nh = int(round(bw * 2 * lato / min(bw, bh))), int(round(bh * 2 * lato / min(bw, bh)))
        pos = compose.posizione_libera(rng, nw, nh, occupati)
        if pos is None:
            continue
        occupati.append((pos[0], pos[1], nw, nh))
        vero = compose.incolla(tela, sorgenti[volto["ritratto"]], volto, lato, pos[0], pos[1], rng)
        trovati.append({"ruolo": "persona", **{k: vero[k] for k in ("ritratto", "soggetto", "riquadro", "punti", "lato_corto")}})
    return finale(tela, rng), trovati


CASI = {"tv": caso_tv, "doppia": caso_doppia, "collage": caso_collage, "piccoli": caso_piccoli}


def verifica(radice: Path, verita: dict) -> dict:
    """YuNet (ONNX a 1024 px di P1) sulle immagini: volti di verita' trovati (IoU >= 0,3) e riquadri senza un volto di verita'."""
    import cv2

    motore = engines.YuNetOnnx(radice / "out" / "onnx-1024" / "face_detection_yunet_2023mar.onnx")
    esito = {}
    for serie in SERIE:
        trovati = attesi = extra = 0
        for nome, volti in verita.items():
            if not nome.startswith(serie + "/"):
                continue
            img = cv2.imread(str(radice / "out" / "p11" / nome))
            quadro, _ = common.quadro_yunet(img)
            visti = engines.rilevamenti(motore, quadro)
            usati = set()
            for v in volti:
                attesi += 1
                for k, r in enumerate(visti):
                    if k not in usati and common.iou(r["riquadro"], v["riquadro"]) >= 0.3:
                        usati.add(k)
                        trovati += 1
                        break
            extra += len(visti) - len(usati)
        esito[serie] = {"volti_di_verita": attesi, "trovati": trovati, "riquadri_in_piu": extra}
    return esito


def main() -> None:
    import cv2

    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--root", help="cartella dei modelli dei volti, fuori dal repository (o MCG_FACES_ROOT)")
    ap.add_argument("--seme", type=int, default=20261029)
    ap.add_argument("--verifica", action="store_true", help="passa le immagini da YuNet e riporta quanti volti trova")
    args = ap.parse_args()
    radice = common.radice_modelli(args.root)
    # Solo i ritratti di UNA persona (sXX): le scene (mXX) hanno piu' persone sotto una stessa sigla.
    volti = [v for v in compose.volti_dei_ritratti(radice) if v["soggetto"].startswith("s")]
    sorgenti = {v["ritratto"]: cv2.imread(str(radice / "ritratti" / f"{v['ritratto']}.jpg")) for v in volti}
    pezzi = compose.regioni_senza_volti(radice, volti)
    print(f"{len(volti)} volti di {len({v['ritratto'] for v in volti})} ritratti, {len(pezzi)} pezzi di sfondo")
    rng = np.random.RandomState(args.seme)
    uscita = radice / "out" / "p11"
    verita: dict[str, list] = {}
    for serie, quante in SERIE.items():
        (uscita / serie).mkdir(parents=True, exist_ok=True)
        for i in range(quante):
            img, vol = CASI[serie](i, rng, volti, sorgenti, pezzi)
            nome = f"{serie}/img-{i:03d}.png"
            cv2.imwrite(str(uscita / nome), img)
            verita[nome] = vol
        print(f"serie {serie}: {quante} immagini, {sum(len(v) for n, v in verita.items() if n.startswith(serie + '/'))} volti")
    (uscita / "verita.json").write_text(json.dumps({"seme": args.seme, "lato": LATO, "immagini": verita}, ensure_ascii=False), encoding="utf-8")
    if args.verifica:
        esito = verifica(radice, verita)
        (uscita / "verifica.json").write_text(json.dumps(esito, indent=1), encoding="utf-8")
        for serie, e in esito.items():
            print(f"{serie}: YuNet trova {e['trovati']} volti su {e['volti_di_verita']} (riquadri in piu': {e['riquadri_in_piu']})")


if __name__ == "__main__":
    main()
