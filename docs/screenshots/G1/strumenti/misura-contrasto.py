"""
Misura il contrasto VERO della chrome sugli screenshot dell'emulatore (dove il vetro sfoca davvero).

Per ogni scatto della campagna (`campagna.py`) legge dal dump di uiautomator dove sta il testo della chrome (titolo, data
agganciata, comandi, segmenti, pillola, barra delle azioni) e, nei pixel:
  - l'inchiostro: i pixel piu' estremi nel verso atteso (scuro sul chiaro o chiaro sullo scuro) dentro il riquadro;
  - il fondo: l'anello di pixel attorno ai glifi (fra 3 e 14 pixel dall'inchiostro), cioe' il vetro o la sfumatura
    cosi' come si vede intorno alle lettere, con sotto la foto di quel momento;
  - il contrasto WCAG fra l'inchiostro e il fondo PEGGIORE dell'anello (il 5 per cento piu' vicino all'inchiostro), e
    quello mediano.
Il caso peggiore di una direzione e' il minimo su tutti gli scatti (le giornate bianche, nere, di testo, sature e a
righe passano a turno sotto la chrome).

Uso: python -I docs/screenshots/G1/strumenti/misura-contrasto.py <cartella-della-campagna> [uscita.json]
"""

import json
import os
import sys
import xml.etree.ElementTree as ET

import numpy as np
from PIL import Image, ImageFilter

ROOT = sys.argv[1]
OUT = sys.argv[2] if len(sys.argv) > 2 else os.path.join(ROOT, "contrasti.json")
STATUS_BAR = 66  # px: sopra c'e' la barra di stato del sistema, non la chrome

PILL = {"Libreria", "Raccolte", "Cerca"}
SEGMENTS = {"Anni", "Mesi", "Giorni", "Tutte"}
ACTIONS = {"Condividi", "Aggiungi ai preferiti", "Togli dai preferiti", "Nascondi", "Elimina dal telefono"}


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
    """Il fondo locale di ogni pixel: la mediana in una finestra di `size` pixel (i glifi sono sottili, il fondo no)."""
    enc = np.clip(crop, 0, 1) ** (1 / 2.2)
    img = Image.fromarray((enc * 255).astype(np.uint8)).filter(ImageFilter.MedianFilter(size))
    return (np.asarray(img).astype(np.float64) / 255.0) ** 2.2


def glyphs(crop, background, dark):
    """I pixel dei glifi nel verso [dark]: abbastanza piu' scuri (o chiari) del loro fondo locale."""
    if dark:
        delta = (background + 0.05) / (crop + 0.05)
    else:
        delta = (crop + 0.05) / (background + 0.05)
    return delta > 1.35, delta


def measure(L, box, dark_ink, icon=False):
    l, t, r, b = box
    if icon:
        # Il glifo di un tasto (22 dp) sta al centro del bersaglio (48 dp): ai bordi c'e' l'orlo della lente, che rifrange
        # il contenuto di fuori (una cella nera sotto una capsula bianca) e non e' inchiostro. Si guarda il 64% centrale.
        dx, dy = int((r - l) * 0.18), int((b - t) * 0.18)
        l, t, r, b = l + dx, t + dy, r - dx, b - dy
    h, w = L.shape
    l, t, r, b = max(0, l), max(STATUS_BAR, t), min(w, r), min(h, b)
    if r - l < 6 or b - t < 6:
        return None
    pad = 16
    T, B, Lf, R = max(0, t - pad), min(h, b + pad), max(0, l - pad), min(w, r + pad)
    big = L[T:B, Lf:R]
    background = local_background(big)
    inside = np.zeros_like(big, dtype=bool)
    inside[t - T:b - T, l - Lf:r - Lf] = True
    # Il verso atteso (dal tema e dalla direzione); se nel riquadro i glifi non ci sono in quel verso (un'icona viola
    # sopra una foto nera e' piu' chiara del suo fondo), si prova l'altro. Senza verso noto, vince quello con piu' glifi.
    candidates = [dark_ink, not dark_ink] if dark_ink is not None else [True, False]
    best = None
    for dark in candidates:
        mask, delta = glyphs(big, background, dark)
        mask &= inside
        if mask.sum() >= 12:
            if dark_ink is not None:
                best = (dark, mask)
                break
            if best is None or mask.sum() > best[1].sum():
                best = (dark, mask)
    if best is None:
        # Nessun glifo distinguibile dal fondo: il contrasto e' quello di un testo invisibile.
        return {"ink": float(np.median(big[inside])), "worst": 1.0, "median": 1.0, "dark_ink": bool(dark_ink)}
    dark, mask = best
    values = big[mask]
    order = np.sort(values)
    ink = float(order[:40].mean() if dark else order[-40:].mean())
    near = dilate(mask, 3)
    ring = dilate(mask, 14) & ~near & inside
    bg = big[ring]
    if bg.size < 20:
        return None
    worst_bg = float(np.percentile(bg, 5 if dark else 95))
    return {
        "ink": ink,
        "worst": round(ratio(ink, worst_bg), 2),
        "median": round(ratio(ink, float(np.median(bg))), 2),
        "dark_ink": bool(dark),
    }


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
        l, t, r, b = [int(v) for v in n.get("bounds").replace("][", ",").strip("[]").split(",")]
        return l, t, r, b
    except (ValueError, AttributeError):
        return None


def targets(root, design):
    """(categoria, etichetta, riquadro, tipo) dei testi e delle icone di chrome nello scatto."""
    found = []
    chrome_ids = {}

    def walk(n, owner):
        rid = n.get("resource-id", "")
        if rid.startswith("g1-chrome-"):
            owner = rid[len("g1-chrome-"):]
        text, desc, box = n.get("text", ""), n.get("content-desc", ""), bounds(n)
        if box:
            yield n, owner, text, desc, box
        for ch in n:
            yield from walk(ch, owner)

    for n, owner, text, desc, box in walk(root, None):
        label = text or desc
        if not label or desc.startswith(("Foto,", "Video,")) or label == "Scorri la timeline":
            continue
        top = box[1]
        if label in PILL and top > 1800:
            found.append(("pillola", label, box, "testo"))
        elif label in ACTIONS and top > 1800:
            found.append(("azioni", label, box, "icona"))
        elif design == "attuale":
            if label in SEGMENTS and top > 1800:
                found.append(("segmenti", label, box, "testo"))
            elif desc == "Torna ai più recenti" or text == "Oggi" and top > 1500:
                found.append(("oggi", label, box, "testo"))
            elif top < 175 and top >= STATUS_BAR - 10:
                found.append(("barra" if text else "comandi", label, box, "testo" if text else "icona"))
            elif 175 <= top < 330 and text:
                found.append(("agganciata", label, box, "testo"))
        elif owner is not None:
            if owner in ("titolo", "barra") and text:
                found.append(("titolo" if owner == "titolo" or top < 210 else "agganciata", label, box, "testo"))
            elif owner in ("comandi", "seleziona", "vista", "oggi", "livello", "chiudi"):
                found.append(("comandi", label, box, "testo" if text else "icona"))
    # Un riquadro per volta (lo stesso testo puo' comparire due volte: la lista sotto e l'intestazione agganciata).
    unique = {}
    for item in found:
        unique.setdefault(item[2], item)
    return list(unique.values())


def menu_targets(root):
    """Le voci del menu della vista aperto (il pop-up di vetro dell'engine): il resto della pagina e' nascosto."""
    found = []
    for n in root.iter("node"):
        text, box = n.get("text", ""), bounds(n)
        if text and box and box[1] >= STATUS_BAR:
            found.append(("menu", text, box, "testo"))
    return found


def polarity(design, theme, category):
    if category in ("menu", "pillola"):
        return theme == "chiaro"
    if design == "C":
        return None
    if design == "A" and category in ("titolo", "comandi", "azioni"):
        return False
    return theme == "chiaro"


results = []
for folder in sorted(os.listdir(ROOT)):
    path = os.path.join(ROOT, folder)
    if not os.path.isdir(path) or "-" not in folder:
        continue
    design, theme = folder.split("-", 1)
    for name in sorted(os.listdir(path)):
        if not name.endswith(".xml"):
            continue
        shot = name[:-4]
        png = os.path.join(path, shot + ".png")
        root = parse(os.path.join(path, name))
        if root is None or not os.path.exists(png) or shot.startswith("prima-del"):
            continue
        L = luminance(np.asarray(Image.open(png).convert("RGB")).astype(np.float64))
        found = menu_targets(root) if shot.startswith("menu") else targets(root, design)
        for category, label, box, kind in found:
            m = measure(L, box, polarity(design, theme, category), icon=(kind == "icona"))
            if m is None:
                continue
            results.append({"direzione": design, "tema": theme, "scatto": shot, "categoria": category, "etichetta": label,
                            "tipo": kind, "riquadro": box, **m})

with open(OUT, "w", encoding="utf-8") as f:
    json.dump(results, f, ensure_ascii=False, indent=1)

# Il riassunto: per direzione, tema e categoria il caso peggiore (minimo dei "worst") e dove.
summary = {}
for r in results:
    key = (r["direzione"], r["tema"], r["categoria"])
    if key not in summary or r["worst"] < summary[key]["worst"]:
        summary[key] = r
print(f"{'direzione':9} {'tema':6} {'categoria':11} {'peggiore':>8} {'mediano':>8} {'soglia':>6}  scatto / etichetta")
for key in sorted(summary):
    r = summary[key]
    need = 4.5 if r["tipo"] == "testo" else 3.0
    flag = "ok" if r["worst"] >= need else "NO"
    print(f"{r['direzione']:9} {r['tema']:6} {r['categoria']:11} {r['worst']:8.2f} {r['median']:8.2f} {need:6.1f} {flag:2} {r['scatto']} / {r['etichetta']}")
