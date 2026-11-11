"""L'icona di MyCloud Gallery: una nuvola di vetro colorato, calcolata (non disegnata a cerchi).

La nuvola e' un solo pezzo di vetro: la sagoma e' l'unione morbida dei petali e della base, lo spessore ha un bordo arrotondato e
cresce un poco dove i petali si intrecciano. Da quello spessore si ricavano le normali, e dalle normali la luce: il riflesso sul lato
da cui arriva la luce, la luce che attraversa il vetro e si raccoglie sul lato opposto, la dispersione dei colori sul bordo e la
luce colorata che il vetro butta sullo sfondo. Nessuna linea disegnata: i soli bordi sono quelli della nuvola.

Uso: python -I tools/icona/vetro.py <cartella>          scrive primo-piano-432.png e anteprima.png (per guardarla)
     python -I tools/icona/vetro.py --res app/src/main/res   scrive mipmap-*/ic_launcher_foreground.png (le cinque densita')
Servono Pillow e numpy. I numeri del disegno (petali, colori, luce) sono qui sotto: si cambia qui e si rigenera.
"""
import os
import sys

import numpy as np
from PIL import Image, ImageDraw

V = 108.0                      # il lato dell'icona adattiva, in dp
LATO = 432                     # xxxhdpi
SS = 3                         # campioni per pixel, per lato
ALZA = 2.5                     # la nuvola sta un po' piu' su, per far posto alla luce che butta sotto

# I petali: (cx, cy, r, colore RGB lineare-ish 0..1).
def rgb(h):
    return np.array([int(h[i:i + 2], 16) / 255.0 for i in (1, 3, 5)])

PETALI = [
    (36.0, 60.0, 11.0, rgb("#FF4A2E")),   # rosso arancio
    (46.0, 50.0, 12.0, rgb("#FFB000")),   # giallo
    (59.0, 45.0, 14.5, rgb("#2FC454")),   # verde
    (71.0, 53.0, 11.5, rgb("#12AEEA")),   # azzurro
    (74.0, 61.5, 9.5, rgb("#3F6BFF")),    # blu
]
# I colori della base (solo pigmento, niente forma): rosa, viola.
PIGMENTI_BASE = [
    (47.0, 67.0, 9.0, rgb("#FF3F86")),
    (60.0, 67.5, 9.5, rgb("#A04DF5")),
]
BASE = (36.0, 56.0, 74.0, 71.0)  # x0, y0, x1, y1

# Quanto rilievo ha il vetro. "mezzo" e' la via di mezzo scelta dall'utente (12/11/2026): una lastra quasi piana con il bordo
# smussato, i petali come vetri colorati che si fondono, **senza il riflesso della luce in alto a sinistra** (lampo, velo e lama a zero:
# l'ha chiesto lui); "pieno" e' la versione a bolle, "piano" quasi senza rilievo.
PROFILI = {
    "pieno": dict(bordo=6.0, cupole=0.26, piano=0.50, rilievo=9.0, caustica=0.85, ombra=0.30, lampo=0.95, velo=0.16, lama=0.16, taglio=0.22, alone=0.62, alone_dy=5.2, luce_alta=0.0),
    "mezzo": dict(bordo=3.4, cupole=0.07, piano=0.86, rilievo=4.6, caustica=0.50, ombra=0.04, lampo=0.0, velo=0.0, lama=0.0, taglio=0.07, alone=0.50, alone_dy=4.4, luce_alta=0.10),
    "piano": dict(bordo=2.2, cupole=0.03, piano=0.94, rilievo=3.0, caustica=0.30, ombra=0.08, lampo=0.55, velo=0.03, lama=0.05, taglio=0.08, alone=0.42, alone_dy=3.8, luce_alta=0.12),
}
P = dict(PROFILI["mezzo"])

LUCE = np.array([-0.52, -0.66, 0.54])
LUCE = LUCE / np.linalg.norm(LUCE)


def smin(a, b, k):
    h = np.clip(0.5 + 0.5 * (b - a) / k, 0.0, 1.0)
    return b + (a - b) * h - k * h * (1.0 - h)


def _scatola(a, raggio, asse):
    """Media mobile lungo un asse (finestra 2*raggio+1), con i bordi ripetuti."""
    if raggio < 1:
        return a
    imb = [(0, 0), (0, 0)]
    imb[asse] = (raggio + 1, raggio)
    c = np.cumsum(np.pad(a, imb, mode="edge"), axis=asse, dtype=np.float64)
    n = a.shape[asse]
    alto = np.take(c, np.arange(2 * raggio + 1, 2 * raggio + 1 + n), axis=asse)
    basso = np.take(c, np.arange(0, n), axis=asse)
    return ((alto - basso) / (2 * raggio + 1)).astype(np.float32)


def sfoca(a, sigma_px):
    """Sfocatura quasi gaussiana di una matrice float 2D: tre medie mobili per asse."""
    raggio = int(round(np.sqrt(sigma_px * sigma_px * 12 / 3 + 1) / 2 - 0.5))
    out = a.astype(np.float32)
    for _ in range(3):
        out = _scatola(_scatola(out, raggio, 0), raggio, 1)
    return out


def calcola(n):
    px = V / n
    ys, xs = np.mgrid[0:n, 0:n].astype(np.float32)
    x = (xs + 0.5) * px
    y = (ys + 0.5) * px + ALZA

    # Sagoma: distanza con segno dell'unione morbida.
    sd = None
    for cx, cy, r, _ in PETALI:
        d = np.hypot(x - cx, y - cy) - r
        sd = d if sd is None else smin(sd, d, 2.6)
    bx = np.maximum(np.maximum(BASE[0] - x, x - BASE[2]), 0.0)
    by = np.maximum(np.maximum(BASE[1] - y, y - BASE[3]), 0.0)
    dentro = np.minimum(np.maximum(np.maximum(BASE[0] - x, x - BASE[2]), np.maximum(BASE[1] - y, y - BASE[3])), 0.0)
    sd = smin(sd, np.hypot(bx, by) + dentro, 1.6)
    # La base resta piatta: sotto y = 71 niente.
    sd = np.maximum(sd, y - BASE[3])
    D = -sd
    copertura = np.clip(D / px + 0.5, 0.0, 1.0)

    # Spessore: bordo arrotondato, e un poco di piu' dove i petali si intrecciano.
    e = P["bordo"]
    t = np.clip(D / e, 0.0, 1.0)
    h0 = np.sqrt(np.clip(1.0 - (1.0 - t) ** 2, 0.0, 1.0))
    somma = np.zeros_like(x)
    pesi = []
    for cx, cy, r, _ in PETALI:
        q = np.clip(1.0 - ((x - cx) ** 2 + (y - cy) ** 2) / (r * r), 0.0, 1.0)
        g = np.sqrt(q)
        somma += g
        pesi.append(q ** 0.85)
    for cx, cy, r, _ in PIGMENTI_BASE:
        q = np.clip(1.0 - ((x - cx) ** 2 + (y - cy) ** 2) / (r * r), 0.0, 1.0)
        pesi.append(0.9 * q ** 1.1)
    cupole = np.zeros_like(x)
    for cx, cy, r, _ in PETALI:
        q = np.clip(1.0 - ((x - cx) ** 2 + (y - cy) ** 2) / ((r * 1.04) ** 2), 0.0, 1.0)
        cupole += q ** 0.75
    H = h0 * (P["piano"] + P["cupole"] * cupole)
    H = sfoca(H, 0.75 / px)  # niente spigoli dentro: solo onde

    # Il pigmento: media pesata dei colori, un po' piu' profonda dove si sovrappongono.
    colori = [c for *_, c in PETALI] + [c for *_, c in PIGMENTI_BASE]
    W = np.stack(pesi, axis=-1) + 1e-4
    C = (W[..., None] * np.stack(colori, axis=0)[None, None, :, :]).sum(axis=2) / W.sum(axis=2, keepdims=True)
    logC = (W[..., None] * np.log(np.stack(colori, axis=0)[None, None, :, :] + 0.04)).sum(axis=2) / W.sum(axis=2, keepdims=True)
    Cgeo = np.exp(logC) - 0.04
    C = 0.55 * C + 0.45 * Cgeo
    for k in range(3):
        C[..., k] = sfoca(C[..., k], 0.9 / px)
    # Saturazione viva.
    grigio = C.mean(axis=2, keepdims=True)
    C = np.clip(grigio + (C - grigio) * 1.22, 0.0, 1.0)

    # Normali dallo spessore.
    zs = P["rilievo"]
    gy, gx = np.gradient(H, px)
    def normale(scala):
        nx, ny = -gx * scala, -gy * scala
        nz = np.ones_like(nx)
        m = np.sqrt(nx * nx + ny * ny + nz * nz)
        return nx / m, ny / m, nz / m

    nx, ny, nz = normale(zs)
    lxy = LUCE[:2] / np.linalg.norm(LUCE[:2])

    # Luce che attraversa: si raccoglie sul lato opposto alla luce (caustica), e il lato della luce resta un po' piu' fondo.
    via = -(nx * lxy[0] + ny * lxy[1])
    caustica = np.clip(via, 0.0, 1.0) ** 1.35
    ombra = np.clip(-via, 0.0, 1.0) ** 1.2
    cielo = 0.5 + 0.5 * (-ny)  # l'ambiente: chiaro sopra, scuro sotto

    corpo = C * (0.90 + 0.16 * cielo)[..., None]
    corpo = corpo * (1.0 - P["ombra"] * ombra)[..., None]
    chiaro = C + (1.0 - C) * 0.42
    corpo = corpo + chiaro * (P["caustica"] * caustica)[..., None]
    # Una lastra di vetro prende piu' luce in alto: un velo chiaro che scende dall'alto, senza forma.
    alto = np.clip((BASE[3] - y) / 42.0, 0.0, 1.0)
    corpo = corpo + (1.0 - corpo) * (P["luce_alta"] * alto ** 1.5)[..., None]

    # Il riflesso, con un filo di dispersione: ogni colore vede una normale appena diversa.
    mezzo = LUCE + np.array([0.0, 0.0, 1.0])
    mezzo = mezzo / np.linalg.norm(mezzo)
    riflesso = np.zeros_like(C)
    for k, s in enumerate((0.90, 1.0, 1.12)):
        ax, ay, az = normale(zs * s)
        ndh = np.clip(ax * mezzo[0] + ay * mezzo[1] + az * mezzo[2], 0.0, 1.0)
        riflesso[..., k] = P["lampo"] * ndh ** 70 + P["velo"] * ndh ** 10
    # Una lama di luce larga sulla parte alta, come una finestra riflessa.
    lama = np.exp(-(((x - 51.0) * 0.55 + (y - 41.0)) ** 2) / (2 * 3.2 ** 2)) * np.clip(H, 0, 1) ** 2 * P["lama"]
    corpo = corpo + riflesso + lama[..., None]

    # Bordo: il vetro visto di taglio e' piu' denso (Fresnel).
    taglio = (1.0 - nz) ** 1.6
    corpo = corpo * (1.0 - P["taglio"] * taglio)[..., None] + (0.9 * P["taglio"] * taglio * cielo)[..., None]
    corpo = np.clip(corpo, 0.0, 1.0)

    alfa = copertura * 0.96

    # La luce colorata che il vetro butta sullo sfondo, piu' un'ombra di contatto.
    dx, dy = 1.4 / px, P["alone_dy"] / px
    def sposta(a):
        return np.roll(np.roll(a, int(round(dy)), axis=0), int(round(dx)), axis=1)
    alone_a = sposta(sfoca(copertura, 3.6 / px)) * P["alone"]
    alone_c = np.stack([sposta(sfoca(C[..., k] * copertura, 3.6 / px)) for k in range(3)], axis=-1)
    alone_c = alone_c / np.maximum(sposta(sfoca(copertura, 3.6 / px)), 1e-3)[..., None]
    grigio = alone_c.mean(axis=2, keepdims=True)
    alone_c = np.clip(grigio + (alone_c - grigio) * 1.25, 0.0, 1.0)
    contatto = np.roll(sfoca(copertura, 1.5 / px), int(round(2.2 / px)), axis=0) * 0.20

    # Composizione (premoltiplicata): ombra di contatto, alone colorato, vetro.
    rgb_out = np.zeros_like(C)
    a_out = np.zeros_like(x)
    def sopra(col, a):
        nonlocal rgb_out, a_out
        rgb_out = col * a[..., None] + rgb_out * (1.0 - a)[..., None]
        a_out = a + a_out * (1.0 - a)
    sopra(np.zeros_like(C), contatto * (1.0 - copertura))
    sopra(alone_c, alone_a * (1.0 - copertura))
    sopra(corpo, alfa)
    out = np.dstack([np.where(a_out[..., None] > 1e-4, rgb_out / np.maximum(a_out, 1e-4)[..., None], 0.0), a_out])
    return (np.clip(out, 0.0, 1.0) * 255.0 + 0.5).astype(np.uint8)


def primo_piano(lato=LATO):
    grande = Image.fromarray(calcola(lato * SS), mode="RGBA")
    return grande.resize((lato, lato), Image.LANCZOS)


def come_sul_telefono(fg, fondo, lato):
    """L'icona adattiva vista dal launcher: i 72dp centrali dei 108, ritagliati a squircle."""
    n = fg.size[0]
    base = Image.new("RGBA", fg.size, fondo)
    base.alpha_composite(fg)
    m = int(n * 18 / 108)
    vista = base.crop((m, m, n - m, n - m)).resize((lato * 4, lato * 4), Image.LANCZOS)
    maschera = Image.new("L", vista.size, 0)
    ImageDraw.Draw(maschera).rounded_rectangle((0, 0, vista.size[0] - 1, vista.size[1] - 1), radius=int(vista.size[0] * 0.30), fill=255)
    vista.putalpha(maschera)
    return vista.resize((lato, lato), Image.LANCZOS)


DENSITA = {"mdpi": 108, "hdpi": 162, "xhdpi": 216, "xxhdpi": 324, "xxxhdpi": 432}

if __name__ == "__main__":
    if len(sys.argv) > 2 and sys.argv[1] == "--res":
        for nome, lato in DENSITA.items():
            cartella = os.path.join(sys.argv[2], "mipmap-" + nome)
            os.makedirs(cartella, exist_ok=True)
            primo_piano(lato).save(os.path.join(cartella, "ic_launcher_foreground.png"), optimize=True)
            print(nome, lato)
        sys.exit(0)
    uscita = sys.argv[1] if len(sys.argv) > 1 else os.path.dirname(os.path.abspath(__file__))
    if len(sys.argv) > 2 and sys.argv[2] == "--confronto":
        tela = Image.new("RGBA", (1100, 640), (26, 25, 31, 255))
        for k, nome in enumerate(("piano", "mezzo", "pieno")):
            P.clear(); P.update(PROFILI[nome])
            fg = primo_piano()
            tela.alpha_composite(come_sul_telefono(fg, (255, 255, 255, 255), 250), (40 + k * 280, 30))
            tela.alpha_composite(come_sul_telefono(fg, (21, 20, 26, 255), 250), (40 + k * 280, 310))
            tela.alpha_composite(come_sul_telefono(fg, (255, 255, 255, 255), 60), (900 + (k % 3) * 0, 40 + k * 90))
            tela.alpha_composite(come_sul_telefono(fg, (21, 20, 26, 255), 60), (990, 40 + k * 90))
        tela.convert("RGB").save(os.path.join(uscita, "confronto.png"))
        print("confronto")
        sys.exit(0)
    fg = primo_piano()
    fg.save(os.path.join(uscita, "primo-piano-432.png"))
    tela = Image.new("RGBA", (1120, 420), (26, 25, 31, 255))
    tela.alpha_composite(come_sul_telefono(fg, (255, 255, 255, 255), 320), (30, 50))
    tela.alpha_composite(come_sul_telefono(fg, (21, 20, 26, 255), 320), (390, 50))
    chiaro = Image.new("RGBA", (150, 320), (150, 172, 208, 255))
    scuro = Image.new("RGBA", (150, 320), (16, 17, 22, 255))
    for k, (sfondo, xx) in enumerate(((chiaro, 760), (scuro, 930))):
        sfondo.alpha_composite(come_sul_telefono(fg, (255, 255, 255, 255), 64), (43, 40))
        sfondo.alpha_composite(come_sul_telefono(fg, (21, 20, 26, 255), 64), (43, 140))
        sfondo.alpha_composite(come_sul_telefono(fg, (255, 255, 255, 255), 44), (53, 240))
        tela.alpha_composite(sfondo, (xx, 50))
    tela.convert("RGB").save(os.path.join(uscita, "anteprima.png"))
    print("fatto")
