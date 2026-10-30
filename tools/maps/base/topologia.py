"""Confini delle regioni e delle province italiane da Natural Earth 1:10m (admin-1), e il punto dove scrivere il nome di ogni regione.

Natural Earth ha le 110 province di Italia come poligoni (con il codice della regione `region_cod`). Qui si cercano i lati che due
poligoni dividono (stessi due vertici): se le due province sono di regioni diverse il lato e' un confine di regione, altrimenti di
provincia; i lati di un poligono solo sono la costa o il confine di Stato, che la carta di base prende da altri file. I lati si
concatenano in spezzate (da un incrocio all'altro) e si semplificano con Douglas-Peucker: cosi' un confine in comune si disegna una volta
sola e combacia con quello vicino.
"""
import math

import mapscommon as mc

# I nomi italiani delle 20 regioni, per codice ISO 3166-2 (`region_cod` di Natural Earth): in Natural Earth sono in parte in inglese
# ("Apulia", "Sicily"). Un codice che non e' qui FERMA lo strumento: un dato nuovo non entra senza che qualcuno lo guardi.
REGIONI = {
    "IT-21": "Piemonte",
    "IT-23": "Valle d’Aosta",
    "IT-25": "Lombardia",
    "IT-32": "Trentino-Alto Adige",
    "IT-34": "Veneto",
    "IT-36": "Friuli-Venezia Giulia",
    "IT-42": "Liguria",
    "IT-45": "Emilia-Romagna",
    "IT-52": "Toscana",
    "IT-55": "Umbria",
    "IT-57": "Marche",
    "IT-62": "Lazio",
    "IT-65": "Abruzzo",
    "IT-67": "Molise",
    "IT-72": "Campania",
    "IT-75": "Puglia",
    "IT-77": "Basilicata",
    "IT-78": "Calabria",
    "IT-82": "Sicilia",
    "IT-88": "Sardegna",
}


def province_italiane(admin1):
    """Dalle feature di admin-1: [(codice regione, [anelli])] delle sole province italiane, in ordine di codice ISO della provincia."""
    out = []
    for f in admin1["features"]:
        p = f["properties"]
        if p.get("adm0_a3") != "ITA":
            continue
        codice = p.get("region_cod")
        if codice not in REGIONI:
            mc.esci("regione di Natural Earth non prevista: %r" % (codice,))
        g = f["geometry"]
        poligoni = [g["coordinates"]] if g["type"] == "Polygon" else g["coordinates"]
        anelli = [[(x, y) for x, y in anello] for poligono in poligoni for anello in poligono]
        out.append((p.get("iso_3166_2") or "", codice, anelli))
    out.sort(key=lambda t: t[0])
    if len({r for _, r, _ in out}) != len(REGIONI):
        mc.esci("non ci sono tutte le 20 regioni nei dati")
    return [(r, a) for _, r, a in out]


def _lati(province):
    """{lato (due vertici in ordine): insieme degli indici delle province che lo hanno}."""
    lati = {}
    for i, (_, anelli) in enumerate(province):
        for anello in anelli:
            for a, b in zip(anello, anello[1:]):
                if a == b:
                    continue
                chiave = (a, b) if a < b else (b, a)
                lati.setdefault(chiave, set()).add(i)
    return lati


def _chiave(a, b):
    return (a, b) if a < b else (b, a)


def _concatena(lati):
    """Spezzate dai lati (lista di coppie di vertici): da un vertice di grado diverso da 2 a un altro; poi gli anelli chiusi."""
    vicini = {}
    for a, b in lati:
        vicini.setdefault(a, []).append(b)
        vicini.setdefault(b, []).append(a)
    for v in vicini.values():
        v.sort()
    usati = set()

    def percorri(inizio, secondo):
        linea = [inizio, secondo]
        usati.add(_chiave(inizio, secondo))
        corrente = secondo
        while len(vicini[corrente]) == 2:
            successivi = [n for n in vicini[corrente] if _chiave(corrente, n) not in usati]
            if not successivi:
                break
            prossimo = successivi[0]
            usati.add(_chiave(corrente, prossimo))
            linea.append(prossimo)
            corrente = prossimo
        return linea

    spezzate = []
    for v in sorted(vicini):
        if len(vicini[v]) != 2:
            for n in vicini[v]:
                if _chiave(v, n) not in usati:
                    spezzate.append(percorri(v, n))
    for v in sorted(vicini):
        for n in vicini[v]:
            if _chiave(v, n) not in usati:
                spezzate.append(percorri(v, n))
    return spezzate


def confini(province, tol_regione, tol_provincia):
    """([spezzate dei confini di regione], [spezzate dei confini di provincia]), gia' semplificate e a 5 decimali."""
    lati = _lati(province)
    regione, provincia = [], []
    for lato in sorted(lati):
        proprietari = lati[lato]
        if len(proprietari) < 2:
            continue
        regioni = {province[i][0] for i in proprietari}
        (regione if len(regioni) > 1 else provincia).append(lato)
    uscita = []
    for lista, tol in ((regione, tol_regione), (provincia, tol_provincia)):
        linee = []
        for spezzata in _concatena(lista):
            semplice = mc.arrotonda(mc.douglas_peucker(spezzata, tol))
            if len(semplice) >= 2:
                linee.append(semplice)
        linee.sort(key=lambda s: (s[0], s[-1], len(s)))
        uscita.append(linee)
    return uscita[0], uscita[1]


def _bordi_di_regione(province, lati):
    """{codice regione: [lati del suo bordo]}: i lati di un poligono solo, e quelli fra regioni diverse."""
    bordo = {}
    for lato in sorted(lati):
        proprietari = lati[lato]
        regioni = sorted({province[i][0] for i in proprietari})
        if len(proprietari) >= 2 and len(regioni) == 1:
            continue
        for r in regioni:
            bordo.setdefault(r, []).append(lato)
    return bordo


def _dentro(px, py, segmenti):
    """Pari-dispari: il punto e' dentro il bordo se un raggio verso est incrocia un numero dispari di lati."""
    dentro = False
    for (ax, ay), (bx, by) in segmenti:
        if (ay > py) != (by > py):
            x = ax + (py - ay) * (bx - ax) / (by - ay)
            if x > px:
                dentro = not dentro
    return dentro


def _distanza(px, py, segmenti, k):
    migliore = float("inf")
    for (ax, ay), (bx, by) in segmenti:
        d = mc._distanza2_da_segmento((px * k, py), (ax * k, ay), (bx * k, by))
        if d < migliore:
            migliore = d
    return math.sqrt(migliore)


def punti_nome(province):
    """{codice regione: (lon, lat)}: il punto interno piu' lontano dal bordo (polo di inaccessibilita', a due passate di griglia)."""
    lati = _lati(province)
    punti = {}
    for r, lista in sorted(_bordi_di_regione(province, lati).items()):
        xs = [v[0] for a, b in lista for v in (a, b)]
        ys = [v[1] for a, b in lista for v in (a, b)]
        k = math.cos(math.radians((min(ys) + max(ys)) / 2))
        migliore = (-1.0, 0.0, 0.0)
        x0, x1, y0, y1 = min(xs), max(xs), min(ys), max(ys)
        for passo in (0.08, 0.01):
            if passo == 0.01:
                x0, x1, y0, y1 = migliore[1] - 0.08, migliore[1] + 0.08, migliore[2] - 0.08, migliore[2] + 0.08
            for i in range(int((x1 - x0) / passo) + 2):
                for j in range(int((y1 - y0) / passo) + 2):
                    px, py = x0 + i * passo, y0 + j * passo
                    if not _dentro(px, py, lista):
                        continue
                    d = _distanza(px, py, lista, k)
                    if d > migliore[0] + 1e-12:
                        migliore = (d, px, py)
        punti[r] = (round(migliore[1], 5), round(migliore[2], 5))
    return punti
