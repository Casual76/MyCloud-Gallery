"""
Misura il contrasto VERO della chrome della G2 sugli screenshot dell'emulatore (dove il vetro sfoca davvero). E' il
metodo di G1 (`../G1/strumenti/misura-contrasto.py`, docs/grafica-progetto.md §1.5) sui pezzi della G2:

  - la chrome di vetro adattivo, trovata per tag (`chrome-titolo`, `chrome-comandi` e i tasti dentro, `chrome-indietro`,
    `chrome-azioni`), la pillola dell'engine (Libreria, Raccolte, Cerca) e le voci del menu della vista aperto;
  - il piede: le capsule della rete e del NAS (testo sul contenitore tonale) e il dettaglio sotto (testo sulla pagina);
  - i dischi di presenza delle celle (in alto a destra; dove stanno lo si ricava dal riquadro della cella e dalle misure
    di `GalleryGridCell`: 6 dp dal bordo, 22 dp il disco intero, 18 il piccolo), con il glifo bianco sullo scrim.

Per ogni pezzo: l'inchiostro (i pixel piu' estremi nel verso atteso), il fondo (l'anello fra 3 e 14 pixel dai glifi) e il
contrasto WCAG contro il fondo PEGGIORE dell'anello (il 5 per cento piu' vicino all'inchiostro). Il caso peggiore di una
categoria e' il minimo su tutti gli scatti.

Uso: python -I docs/screenshots/G2/strumenti/misura-contrasto.py <cartella-della-campagna> [uscita.json]
"""

import json
import os
import sys
import xml.etree.ElementTree as ET

import numpy as np
from PIL import Image, ImageFilter

ROOT = sys.argv[1]
OUT = sys.argv[2] if len(sys.argv) > 2 else os.path.join(ROOT, "contrasti.json")

PILL = {"Libreria", "Raccolte", "Cerca"}
CELL_KINDS = ("Foto,", "Video,", "Foto in movimento,", "Foto RAW,", "Immagine animata,")
PROMINENT = ("solo sul telefono", "solo sul NAS", "copia sul NAS da verificare")
QUIET = ("sul telefono e sul NAS", "al sicuro sul NAS")
FOOTER_PILLS = {
    "Rete non riconosciuta", "NAS non raggiungibile", "Sto leggendo il NAS…", "Allineo l’indice del NAS…", "NAS aggiornato",
    "Lavori fermi: cambiamenti anomali", "Anteprime salvate", "Area comune non raggiungibile", "Area privata non raggiungibile",
    "Indice condiviso in prova", "A casa · NAS collegato", "Fuori casa, senza rete",
}


def luminance(rgb):
    c = rgb / 255.0
    lin = np.where(c <= 0.04045, c / 12.92, ((c + 0.055) / 1.055) ** 2.4)
    return 0.2126 * lin[..., 0] + 0.7152 * lin[..., 1] + 0.0722 * lin[..., 2]


def dilate(mask, r):
    out = mask.copy()
    for dy in range(-r, r + 1):
        for dx in range(-r, r + 1):
            if dx * dx + dy * dy > r * r:
                continue
            out |= np.roll(np.roll(mask, dy, axis=0), dx, axis=1)
    return out


def ratio(a, b):
    hi, lo = max(a, b), min(a, b)
    return (hi + 0.05) / (lo + 0.05)


def local_background(crop, size=31):
    enc = np.clip(crop, 0, 1) ** (1 / 2.2)
    img = Image.fromarray((enc * 255).astype(np.uint8)).filter(ImageFilter.MedianFilter(size))
    return (np.asarray(img).astype(np.float64) / 255.0) ** 2.2


def glyphs(crop, background, dark):
    delta = (background + 0.05) / (crop + 0.05) if dark else (crop + 0.05) / (background + 0.05)
    return delta > 1.35, delta


def measure(L, box, dark_ink, icon=False, circle=False, status_bar=66):
    l, t, r, b = box
    if icon and not circle:
        # Il glifo di un tasto (22 dp) sta al centro del bersaglio (48 dp): all'orlo la lente rifrange il contenuto di
        # fuori, che non e' inchiostro. Si guarda il 64% centrale.
        dx, dy = int((r - l) * 0.18), int((b - t) * 0.18)
        l, t, r, b = l + dx, t + dy, r - dx, b - dy
    h, w = L.shape
    l, t, r, b = max(0, int(l)), max(status_bar, int(t)), min(w, int(r)), min(h, int(b))
    if r - l < 6 or b - t < 6:
        return None
    pad = 16
    T, B, Lf, R = max(0, t - pad), min(h, b + pad), max(0, l - pad), min(w, r + pad)
    big = L[T:B, Lf:R]
    # Il fondo locale: una finestra piu' grande del glifo (un'icona piena, il cuore del preferito, e' larga 58 px e con
    # una finestra di 31 il suo interno sarebbe "fondo"); per il disco piccolo una finestra stretta, dentro il cerchio.
    background = local_background(big, 15 if circle else (61 if icon else 31))
    inside = np.zeros_like(big, dtype=bool)
    if circle:
        # Il disco di presenza: solo i pixel dentro il cerchio (meno l'orlo di 2 px con il filo): fuori c'e' la foto.
        cy, cx, rad = (t + b) / 2 - T, (l + r) / 2 - Lf, (r - l) / 2 - 2
        yy, xx = np.mgrid[0:big.shape[0], 0:big.shape[1]]
        inside = (yy - cy) ** 2 + (xx - cx) ** 2 <= rad * rad
    else:
        inside[t - T:b - T, l - Lf:r - Lf] = True
    if circle:
        return measure_disc(big, inside)
    candidates = [dark_ink, not dark_ink] if dark_ink is not None else [True, False]
    best = None
    for dark in candidates:
        mask, _ = glyphs(big, background, dark)
        mask &= inside
        if mask.sum() >= 12:
            if dark_ink is not None:
                best = (dark, mask)
                break
            if best is None or mask.sum() > best[1].sum():
                best = (dark, mask)
    if best is None:
        return {"ink": float(np.median(big[inside])), "worst": 1.0, "median": 1.0, "dark_ink": bool(dark_ink)}
    dark, mask = best
    order = np.sort(big[mask])
    ink = float(order[:40].mean() if dark else order[-40:].mean())
    near = dilate(mask, 3 if not circle else 2)
    ring = dilate(mask, 14 if not circle else 6) & ~near & inside
    bg = big[ring]
    if bg.size < 20:
        return None
    worst_bg = float(np.percentile(bg, 5 if dark else 95))
    return {"ink": ink, "worst": round(ratio(ink, worst_bg), 2), "median": round(ratio(ink, float(np.median(bg))), 2), "dark_ink": bool(dark)}


def measure_disc(big, inside):
    """
    Il disco di presenza: dentro il cerchio ci sono solo lo scrim (scuro, con la foto sotto) e il glifo bianco, quindi i
    glifi si separano con una soglia a meta' fra i due (in luminanza codificata), non con il fondo locale (un glifo pieno
    largo 37 px sarebbe "fondo" per una finestra stretta). Il fondo peggiore e' il 95 per cento piu' chiaro dello scrim
    lontano 2 px dal glifo.
    """
    enc = np.clip(big, 0, 1) ** (1 / 2.2)
    vals = enc[inside]
    if vals.size < 40:
        return None
    lo, hi = np.percentile(vals, 5), np.percentile(vals, 99.5)
    if hi - lo < 0.15:
        return {"ink": float(np.median(big[inside])), "worst": 1.0, "median": 1.0, "dark_ink": False}
    glyph = inside & (enc > (lo + hi) / 2)
    ring = inside & ~dilate(glyph, 2)
    if glyph.sum() < 12 or ring.sum() < 20:
        return None
    ink = float(np.sort(big[glyph])[-40:].mean())
    bg = big[ring]
    return {"ink": ink, "worst": round(ratio(ink, float(np.percentile(bg, 95))), 2), "median": round(ratio(ink, float(np.median(bg))), 2), "dark_ink": False}


def parse(path):
    s = open(path, encoding="utf-8", errors="replace").read()
    if "<?xml" not in s:
        return None
    try:
        return ET.fromstring(s[s.index("<?xml"):])
    except ET.ParseError:
        return None


def bounds(n):
    try:
        return tuple(int(v) for v in n.get("bounds").replace("][", ",").strip("[]").split(","))
    except (ValueError, AttributeError):
        return None


def targets(root, density, height):
    """(categoria, etichetta, riquadro, tipo) dei pezzi da misurare nello scatto."""
    found = []

    def walk(n, owner):
        rid = n.get("resource-id", "")
        if rid.startswith("chrome-"):
            owner = rid[len("chrome-"):]
        text, desc, box = n.get("text", ""), n.get("content-desc", ""), bounds(n)
        if box:
            yield n, owner, text, desc, box
        for ch in n:
            yield from walk(ch, owner)

    inset, disc, quiet, min_side = 6 * density, 22 * density, 18 * density, 64 * density
    # Le celle dell'ultima colonna arrivano dal dump tagliate a destra dove corre lo scrubber (l'albero di accessibilita'
    # ritaglia il nodo): nella griglia quadrata le celle di uno scatto sono larghe uguali, e la larghezza vera e' quella
    # delle altre.
    widths = sorted(b[2] - b[0] for _, _, _, d, b in walk(root, None) if d.startswith(CELL_KINDS))
    typical = widths[len(widths) // 2] if widths else 0
    for n, owner, text, desc, box in walk(root, None):
        label = text or desc
        if not label or label == "Scorri la timeline":
            continue
        l, top, r, b = box
        if desc.startswith(CELL_KINDS):
            if typical and r - l < typical - 4 and b - top >= typical - 4:
                r = l + typical
            # Il disco di presenza della cella, se la cella e' intera, abbastanza grande e lontana dalla chrome.
            phrase = next((p for p in PROMINENT + QUIET if desc.endswith(", " + p)), None)
            if phrase and min(r - l, b - top) >= min_side and top > height * 0.12 and b < height * 0.78:
                side = disc if phrase in PROMINENT else quiet
                found.append(("presenza", phrase, (r - inset - side, top + inset, r - inset, top + inset + side), "disco"))
            continue
        if label in PILL and top > height * 0.75 and owner is None:
            found.append(("pillola", label, box, "testo"))
        elif owner == "azioni" and desc:
            found.append(("azioni", label, box, "icona"))
        elif owner == "titolo" and text:
            found.append(("titolo", label, box, "testo"))
        elif owner in ("comandi", "oggi", "seleziona", "vista", "fine", "indietro") and (text or desc):
            found.append(("comandi", label, box, "testo" if text else "icona"))
        elif text in FOOTER_PILLS and b < height * 0.88:
            # Il piede si misura dove si legge davvero: sopra la sfumatura di bordo in basso e la pillola.
            found.append(("piede", label, box, "testo"))
        elif text and owner is None and b < height * 0.88 and (" di " in text and any(ch.isdigit() for ch in text) or text.startswith(("Nessuna connessione", "Oggi alle", "Il NAS non", "Sul NAS"))):
            found.append(("piede-dettaglio", label, box, "testo"))
    unique = {}
    for item in found:
        unique.setdefault(item[2], item)
    return list(unique.values())


def menu_targets(root):
    found = []
    for n in root.iter("node"):
        text, box = n.get("text", ""), bounds(n)
        if text and box and box[1] >= 66:
            found.append(("menu", text, box, "testo"))
    # L'ultima voce di un menu che scorre e' tagliata dal bordo del pannello (si legge scorrendo): non si misura.
    if found:
        lowest = max(found, key=lambda f: f[2][3])
        found.remove(lowest)
    return found


def polarity(theme, category):
    if category in ("menu", "pillola", "piede", "piede-dettaglio"):
        return theme == "chiaro"
    if category == "presenza":
        return False
    return None  # il vetro adattivo sceglie da se': si prova il verso con piu' glifi


density = float(os.environ.get("G2_DENSITY", "2.625"))
results = []
for folder in sorted(os.listdir(ROOT)):
    path = os.path.join(ROOT, folder)
    if not os.path.isdir(path) or "-" not in folder:
        continue
    scenario, theme = folder.rsplit("-", 1)
    for name in sorted(os.listdir(path)):
        if not name.endswith(".xml"):
            continue
        shot = name[:-4]
        png = os.path.join(path, shot + ".png")
        root = parse(os.path.join(path, name))
        if root is None or not os.path.exists(png) or shot.startswith("prima-di"):
            continue
        img = np.asarray(Image.open(png).convert("RGB")).astype(np.float64)
        L = luminance(img)
        found = menu_targets(root) if shot.startswith("menu") else targets(root, density, L.shape[0])
        for category, label, box, kind in found:
            m = measure(L, box, polarity(theme, category), icon=(kind in ("icona", "disco")), circle=(kind == "disco"))
            if m is None:
                continue
            results.append({"scenario": scenario, "tema": theme, "scatto": shot, "categoria": category, "etichetta": label,
                            "tipo": kind, "riquadro": list(box), **m})

with open(OUT, "w", encoding="utf-8") as f:
    json.dump(results, f, ensure_ascii=False, indent=1)

summary = {}
for r in results:
    key = (r["tema"], r["categoria"])
    if key not in summary or r["worst"] < summary[key]["worst"]:
        summary[key] = r
print(f"{'tema':6} {'categoria':16} {'peggiore':>8} {'mediano':>8} {'soglia':>6}     scenario/scatto / etichetta  (n)")
for key in sorted(summary):
    r = summary[key]
    need = 4.5 if r["tipo"] == "testo" else 3.0
    n = sum(1 for x in results if (x["tema"], x["categoria"]) == key)
    flag = "ok" if r["worst"] >= need else "NO"
    print(f"{r['tema']:6} {r['categoria']:16} {r['worst']:8.2f} {r['median']:8.2f} {need:6.1f} {flag:2}  {r['scenario']}/{r['scatto']} / {r['etichetta']}  ({n})")
