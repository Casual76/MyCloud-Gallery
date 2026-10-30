"""Funzioni comuni degli strumenti della mappa (Fase 9, P2): solo libreria standard di Python.

Si lancia sempre con `python -I` (modalita' isolata: ne' la cartella dello script ne' quella corrente entrano in sys.path, nessuna
variabile d'ambiente conta); gli script di `base/` e `style/` aggiungono a mano la cartella di QUESTO file, che e' nostra, mai quella dei file
scaricati. I file scaricati sono dati non fidati: si leggono come testo, JSON o ZIP, non si eseguono mai.
"""
import hashlib
import json
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
RADICE = os.path.dirname(os.path.dirname(HERE))
DOWNLOAD = os.path.join(RADICE, "build", "download")
ASSET_MAP = os.path.join(RADICE, "feature-map", "src", "main", "assets", "map")

# I soli host da cui uno strumento puo' scaricare (l'elenco approvato al §8 di docs/fase9-progetto.md: GitHub e i suoi spazi di rilascio).
HOST_AMMESSI = (
    "raw.githubusercontent.com",
    "github.com",
    "objects.githubusercontent.com",
    "release-assets.githubusercontent.com",
)


def sha256_bytes(data):
    return hashlib.sha256(data).hexdigest()


def sha256_file(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for blocco in iter(lambda: f.read(1 << 20), b""):
            h.update(blocco)
    return h.hexdigest()


def carica_json(path):
    with open(path, "rb") as f:
        return json.loads(f.read().decode("utf-8"))


def json_compatto(obj):
    """Il JSON come lo scrive lo strumento: compatto, UTF-8, ordine delle chiavi quello di inserimento, a capo finale."""
    return (json.dumps(obj, ensure_ascii=False, separators=(",", ":"), allow_nan=False) + "\n").encode("utf-8")


def json_leggibile(obj):
    """Per le impronte e i riepiloghi: rientro di due spazi, chiavi ordinate."""
    return (json.dumps(obj, ensure_ascii=False, indent=2, sort_keys=True, allow_nan=False) + "\n").encode("utf-8")


def scrivi(path, data):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "wb") as f:
        f.write(data)


def num(v, decimali=5):
    """Un numero con al piu' [decimali] cifre decimali, senza zeri inutili e senza `-0`: cosi' i byte sono gli stessi su ogni macchina."""
    testo = "%.*f" % (decimali, v)
    if "." in testo:
        testo = testo.rstrip("0").rstrip(".")
    return "0" if testo in ("-0", "") else testo


# --- Douglas-Peucker, a tolleranza fissata ---------------------------------------------------------------------------

def _distanza2_da_segmento(p, a, b):
    ax, ay = a
    bx, by = b
    dx = bx - ax
    dy = by - ay
    if dx == 0.0 and dy == 0.0:
        ex = p[0] - ax
        ey = p[1] - ay
        return ex * ex + ey * ey
    t = ((p[0] - ax) * dx + (p[1] - ay) * dy) / (dx * dx + dy * dy)
    t = 0.0 if t < 0.0 else 1.0 if t > 1.0 else t
    ex = p[0] - (ax + t * dx)
    ey = p[1] - (ay + t * dy)
    return ex * ex + ey * ey


def douglas_peucker(punti, tolleranza):
    """Semplifica una spezzata (lista di (lon, lat)) tenendo il primo e l'ultimo punto. La tolleranza e' in gradi, distanza piana.

    Iterativa (niente ricorsione: le coste hanno migliaia di punti); a parita' di distanza vince il primo punto, cosi' il risultato
    non dipende da altro che dai dati.
    """
    n = len(punti)
    if n <= 2:
        return list(punti)
    tol2 = tolleranza * tolleranza
    tieni = [False] * n
    tieni[0] = tieni[n - 1] = True
    pila = [(0, n - 1)]
    while pila:
        i, j = pila.pop()
        migliore = -1.0
        indice = -1
        a = punti[i]
        b = punti[j]
        for k in range(i + 1, j):
            d = _distanza2_da_segmento(punti[k], a, b)
            if d > migliore:
                migliore = d
                indice = k
        if migliore > tol2:
            tieni[indice] = True
            pila.append((i, indice))
            pila.append((indice, j))
    return [p for p, t in zip(punti, tieni) if t]


def arrotonda(punti):
    """Arrotonda a 5 decimali e toglie i punti consecutivi uguali."""
    out = []
    for x, y in punti:
        q = (round(x, 5) + 0.0, round(y, 5) + 0.0)
        if not out or out[-1] != q:
            out.append(q)
    return out


def area_anello(anello):
    """Area con segno (formula del laccio) in gradi quadrati."""
    s = 0.0
    for (x0, y0), (x1, y1) in zip(anello, anello[1:]):
        s += x0 * y1 - x1 * y0
    return s / 2.0


# --- Scrittura compatta dei GeoJSON, con le coordinate a 5 decimali --------------------------------------------------

def _coord(c):
    return "[" + num(c[0]) + "," + num(c[1]) + "]"


def _geom(g):
    t = g["type"]
    c = g["coordinates"]
    if t == "Point":
        corpo = _coord(c)
    elif t == "LineString":
        corpo = "[" + ",".join(_coord(p) for p in c) + "]"
    elif t == "Polygon":
        corpo = "[" + ",".join("[" + ",".join(_coord(p) for p in r) + "]" for r in c) + "]"
    elif t == "MultiLineString":
        corpo = "[" + ",".join("[" + ",".join(_coord(p) for p in linea) + "]" for linea in c) + "]"
    elif t == "MultiPolygon":
        corpo = "[" + ",".join(
            "[" + ",".join("[" + ",".join(_coord(p) for p in r) + "]" for r in poligono) + "]" for poligono in c
        ) + "]"
    else:
        raise ValueError("geometria non prevista: " + t)
    return '{"type":"' + t + '","coordinates":' + corpo + "}"


def geojson_bytes(features):
    """Una FeatureCollection. Ogni feature su una riga, in questo ordine di chiavi: type, properties, geometry; le proprieta'
    nell'ordine in cui sono nel dizionario; niente spazi. Le coordinate arrivano gia' arrotondate, qui si formattano soltanto."""
    righe = []
    for f in features:
        props = json.dumps(f["properties"], ensure_ascii=False, separators=(",", ":"), allow_nan=False)
        righe.append('{"type":"Feature","properties":' + props + ',"geometry":' + _geom(f["geometry"]) + "}")
    corpo = '{"type":"FeatureCollection","features":[\n' + ",\n".join(righe) + "\n]}\n"
    return corpo.encode("utf-8")


# --- Colori e contrasto (WCAG 2.x) ------------------------------------------------------------------------------------

def hex_a_rgb(testo):
    m = re.fullmatch(r"#([0-9a-fA-F]{6})", testo)
    if not m:
        raise ValueError("colore non esadecimale a sei cifre: " + testo)
    v = int(m.group(1), 16)
    return (v >> 16) & 255, (v >> 8) & 255, v & 255


def luminanza(rgb):
    def canale(c):
        c = c / 255.0
        return c / 12.92 if c <= 0.03928 else ((c + 0.055) / 1.055) ** 2.4

    r, g, b = (canale(c) for c in rgb)
    return 0.2126 * r + 0.7152 * g + 0.0722 * b


def contrasto(a, b):
    """Il rapporto di contrasto WCAG fra due colori '#rrggbb'."""
    la = luminanza(hex_a_rgb(a))
    lb = luminanza(hex_a_rgb(b))
    chiaro, scuro = (la, lb) if la >= lb else (lb, la)
    return (chiaro + 0.05) / (scuro + 0.05)


def esci(messaggio, codice=1):
    sys.stderr.write(messaggio + "\n")
    sys.exit(codice)


# --- Scaricamento (solo a mano, solo da host approvati, sempre con impronta) ---------------------------------------------

def _apri(url):
    import urllib.parse
    import urllib.request

    class _Solo(urllib.request.HTTPRedirectHandler):
        def redirect_request(self, req, fp, code, msg, headers, newurl):
            _controlla_url(newurl)
            return super().redirect_request(req, fp, code, msg, headers, newurl)

    _controlla_url(url)
    opener = urllib.request.build_opener(_Solo)
    richiesta = urllib.request.Request(url, headers={"User-Agent": "MyCloudGallery-tools-maps/1"})
    return opener.open(richiesta, timeout=120)


def _controlla_url(url):
    import urllib.parse

    parti = urllib.parse.urlsplit(url)
    if parti.scheme != "https" or parti.hostname not in HOST_AMMESSI or parti.username or parti.port not in (None, 443):
        esci("URL fuori dall'elenco approvato (§8): " + url)


def prepara_ingressi(elenco, scarica, parla=True):
    """Controlla (e, con [scarica], scarica) gli ingressi `{id, file, url, sha256, size}` sotto build/download/<id>/<file>.

    Ritorna la lista dei percorsi in ordine. Un file gia' presente con l'impronta giusta si lascia; uno con l'impronta sbagliata
    FERMA tutto (non si sovrascrive mai); uno mancante si scarica solo con [scarica], in una cartella nuova e vuota.
    """
    percorsi = []
    for e in elenco:
        cartella = os.path.join(DOWNLOAD, e["id"])
        path = os.path.join(cartella, e["file"])
        if os.path.exists(path):
            if sha256_file(path) != e["sha256"]:
                esci("impronta SHA-256 diversa da quella fissata: " + path)
            if parla:
                print("ok        %s" % e["id"])
        elif not scarica:
            esci("manca %s (si scarica a mano con --scarica, da %s)" % (path, e["url"]))
        else:
            if os.path.exists(cartella) and os.listdir(cartella):
                esci("la cartella non e' vuota, non scarico: " + cartella)
            os.makedirs(cartella, exist_ok=True)
            tmp = path + ".part"
            h = hashlib.sha256()
            n = 0
            with _apri(e["url"]) as r, open(tmp, "wb") as f:
                for blocco in iter(lambda: r.read(1 << 20), b""):
                    h.update(blocco)
                    n += len(blocco)
                    f.write(blocco)
            if h.hexdigest() != e["sha256"] or n != e["size"]:
                os.remove(tmp)
                esci("scaricato %s: impronta o dimensione diverse da quelle fissate (%d B)" % (e["id"], n))
            os.replace(tmp, path)
            print("scaricato %s (%d B)" % (e["id"], n))
        percorsi.append(path)
    return percorsi
