"""Anteprima SVG della carta di base, chiara e scura, senza emulatore ne' MapLibre (Fase 9, P2): `docs/screenshots/9-P2/`.

    python -I tools/maps/style/preview.py [cartella di uscita]

Legge i GeoJSON di `feature-map/src/main/assets/map/base/` e la tavolozza di `palette.py` e li disegna come lo stile offline: sfondo mare, terre,
laghi, province e regioni a tratti, confini, coste, punti e nomi delle citta', nomi dei paesi e delle regioni. NON e' il disegno di MapLibre
(non c'e' l'ombreggiatura dei glifi SDF, la collisione dei nomi e' una versione semplice, il carattere e' quello del sistema): serve a vedere che
cosa contengono i dati e come stanno i colori. Il disegno vero e' di P4. Nessun luogo o coordinata della famiglia: tre inquadrature fisse.
Riproducibile: stessi file, stessi byte (nessun orologio).
"""
import math
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import mapscommon as mc  # noqa: E402
import palette  # noqa: E402

BASE = os.path.join(mc.ASSET_MAP, "base")
USCITA = os.path.join(mc.RADICE, "docs", "screenshots", "9-P2")
# (nome, lon, lat, zoom, larghezza, altezza)
VISTE = (
    ("mondo", 8.0, 24.0, 1.8, 1000, 640),
    ("europa", 14.0, 47.0, 3.5, 900, 680),
    ("italia", 12.5, 41.9, 5.6, 900, 1000),
)
SOGLIE_PAESI = ((2, 1.6), (4, 2.6), (5, 3.6), (7, 4.6))
SOGLIE_CITTA = {1: 2.6, 2: 3.6, 3: 4.6, 4: 5.6, 5: 6.6}


class Vista:
    def __init__(self, lon, lat, zoom, w, h):
        self.zoom, self.w, self.h = zoom, w, h
        self.scala = 512.0 * 2 ** zoom
        self.cx, self.cy = self._mondo(lon, lat)

    def _mondo(self, lon, lat):
        lat = max(-85.05, min(85.05, lat))
        x = (lon + 180.0) / 360.0 * self.scala
        y = (0.5 - math.log(math.tan(math.pi / 4 + math.radians(lat) / 2)) / (2 * math.pi)) * self.scala
        return x, y

    def px(self, lon, lat):
        x, y = self._mondo(lon, lat)
        return x - self.cx + self.w / 2.0, y - self.cy + self.h / 2.0

    def dentro(self, p, margine=0):
        return -margine <= p[0] <= self.w + margine and -margine <= p[1] <= self.h + margine


def _carica(nome):
    return mc.carica_json(os.path.join(BASE, nome))["features"]


def _tratto(punti):
    return "M" + "L".join("%s %s" % (mc.num(x, 1), mc.num(y, 1)) for x, y in punti)


def _proietta(v, punti, tol=0.6):
    pp = [v.px(x, y) for x, y in punti]
    xs = [p[0] for p in pp]
    ys = [p[1] for p in pp]
    if max(xs) < -50 or min(xs) > v.w + 50 or max(ys) < -50 or min(ys) > v.h + 50:
        return None
    semplice = mc.douglas_peucker(pp, tol)
    return semplice if len(semplice) >= 2 else None


def _percorsi(v, geom, chiuso):
    out = []
    if geom["type"] == "MultiPolygon":
        anelli = [r for poligono in geom["coordinates"] for r in poligono]
    elif geom["type"] == "MultiLineString":
        anelli = geom["coordinates"]
    else:
        return out
    for anello in anelli:
        p = _proietta(v, anello)
        if p:
            out.append(_tratto(p) + ("Z" if chiuso else ""))
    return out


def _testo(x, y, stringa, dim, colore, alone, peso="400", spazio=0, maiuscolo=False, ancora="middle"):
    stringa = stringa.upper() if maiuscolo else stringa
    s = stringa.replace("&", "&amp;").replace("<", "&lt;")
    return (
        '<text x="%s" y="%s" font-size="%s" font-weight="%s" letter-spacing="%s" text-anchor="%s" fill="%s" stroke="%s" stroke-width="2.4" '
        'stroke-linejoin="round" paint-order="stroke">%s</text>' % (mc.num(x, 1), mc.num(y, 1), mc.num(dim, 1), peso, mc.num(spazio, 1), ancora, colore, alone, s)
    )


def disegna(v, pal, nome_stile):
    o = ['<svg xmlns="http://www.w3.org/2000/svg" width="%d" height="%d" viewBox="0 0 %d %d" font-family="\'Noto Sans\',\'Segoe UI\',sans-serif">' % (v.w, v.h, v.w, v.h)]
    o.append('<rect width="%d" height="%d" fill="%s"/>' % (v.w, v.h, pal["water"]))
    terre = _carica("land.geojson")[0]["geometry"]
    o.append('<path fill="%s" fill-rule="evenodd" d="%s"/>' % (pal["land"], "".join(_percorsi(v, terre, True))))
    laghi = _carica("lakes.geojson")[0]["geometry"]
    o.append('<path fill="%s" fill-rule="evenodd" d="%s"/>' % (pal["water"], "".join(_percorsi(v, laghi, True))))
    if v.zoom >= 4.5:
        for f in _carica("regions.geojson"):
            lvl = f["properties"]["lvl"]
            if lvl == 2 and v.zoom < 7:
                continue
            d = "".join(_percorsi(v, f["geometry"], False))
            if d:
                o.append('<path fill="none" stroke="%s" stroke-width="%s" stroke-dasharray="%s" d="%s"/>' % (
                    pal["boundary_region" if lvl == 1 else "boundary_province"], "0.9" if lvl == 1 else "0.6", "4 3" if lvl == 1 else "2 3", d))
    for f in _carica("borders.geojson"):
        d = "".join(_percorsi(v, f["geometry"], False))
        if d:
            disputato = f["properties"]["d"] == 1
            o.append('<path fill="none" stroke="%s" stroke-width="%s"%s d="%s"/>' % (
                pal["boundary_state" if disputato else "boundary_country"], "0.8" if disputato else "1.1", ' stroke-dasharray="2 2"' if disputato else "", d))
    costa = _carica("coast.geojson")[0]["geometry"]
    o.append('<path fill="none" stroke="%s" stroke-width="0.8" d="%s"/>' % (pal["coast"], "".join(_percorsi(v, costa, False))))
    occupati = []

    def libero(x, y, testo, dim):
        w = len(testo) * dim * 0.58 + 4
        r = (x - w / 2, y - dim, x + w / 2, y + dim * 0.35)
        if r[0] < 2 or r[2] > v.w - 2 or r[1] < 2 or r[3] > v.h - 2:
            return False
        for a in occupati:
            if r[0] < a[2] and r[2] > a[0] and r[1] < a[3] and r[3] > a[1]:
                return False
        occupati.append(r)
        return True

    paesi = _carica("country-labels.geojson")

    def paesi_con_rank(selezione):
        for f in paesi:
            rank = f["properties"]["rank"]
            minimo = 5.6 if rank >= 6 else next(z for massimo, z in SOGLIE_PAESI if rank <= massimo)
            if not selezione(rank) or v.zoom < minimo:
                continue
            x, y = v.px(*f["geometry"]["coordinates"])
            dim = min(13.0, 8.0 + v.zoom * 1.0)
            if v.dentro((x, y)) and libero(x, y, f["properties"]["name"], dim):
                o.append(_testo(x, y, f["properties"]["name"], dim, pal["text_country"], pal["halo"], "700", 0.8, True))

    # Stesso ordine di priorita' dello stile: paesi grandi, citta', paesi piccoli, regioni.
    paesi_con_rank(lambda rank: rank <= 5)
    punti, etichette = [], []
    for f in _carica("cities.geojson"):
        rank = f["properties"].get("rank", 5)
        if v.zoom < SOGLIE_CITTA[rank]:
            continue
        x, y = v.px(*f["geometry"]["coordinates"])
        if not v.dentro((x, y), 4):
            continue
        punti.append('<circle cx="%s" cy="%s" r="%s" fill="%s" stroke="%s" stroke-width="0.8"/>' % (mc.num(x, 1), mc.num(y, 1), "3" if rank < 3 else "2.4", pal["dot"], pal["halo"]))
        dim = 11.0 + max(0, 3 - rank) * 1.2
        if libero(x, y + dim + 4, f["properties"]["name"], dim):
            etichette.append(_testo(x, y + dim + 3, f["properties"]["name"], dim, pal["text_primary"], pal["halo"]))
    o += punti + etichette
    paesi_con_rank(lambda rank: rank >= 6)
    if v.zoom >= 5.4:
        for f in _carica("region-labels.geojson"):
            x, y = v.px(*f["geometry"]["coordinates"])
            dim = 10.0 + (v.zoom - 5.0)
            if v.dentro((x, y)) and libero(x, y, f["properties"]["name"], dim):
                o.append(_testo(x, y, f["properties"]["name"], dim, pal["text_secondary"], pal["halo"], "400", 1.5, True))
    o.append(_testo(v.w - 8, v.h - 8, "Natural Earth · GeoNames", 10, pal["text_secondary"], pal["halo"], ancora="end"))
    o.append("</svg>")
    return ("\n".join(o) + "\n").encode("utf-8")


def main():
    uscita = sys.argv[1] if len(sys.argv) > 1 else USCITA
    for nome_pal, pal in palette.TAVOLOZZE.items():
        for nome, lon, lat, zoom, w, h in VISTE:
            svg = disegna(Vista(lon, lat, zoom, w, h), pal, nome_pal)
            mc.scrivi(os.path.join(uscita, "carta-base-%s-%s.svg" % (nome, nome_pal)), svg)
            print("carta-base-%s-%s.svg  %d B" % (nome, nome_pal, len(svg)))


if __name__ == "__main__":
    main()
