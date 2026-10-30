"""La carta di base della mappa (Fase 9, P2; docs/fase9-progetto.md §2.7, D25): da Natural Earth 5.1.2 e da GeoNames a GeoJSON piccoli.

    python -I tools/maps/base/build_base.py             scrive feature-map/src/main/assets/map/base/*.geojson e impronte.json
    python -I tools/maps/base/build_base.py --verifica  ricostruisce in memoria e confronta con impronte.json e con i file scritti

Non scarica niente: lavora sui file in `build/download/` (li mette `fetch.py --scarica`, a mano) e su `build/input/geonames/`. Riproducibile: stessi
ingressi, stessi byte (Douglas-Peucker a tolleranza fissata, coordinate a 5 decimali, ordine stabile, niente orologio ne' ordine di dizionari
che non sia quello di inserimento). La carta non ha mai un livello, un'etichetta o un punto "Casa": i nomi vengono tutti da Natural Earth e
da GeoNames, e lo strumento si ferma se ne incontra uno che si chiama cosi'.
"""
import io
import os
import sys
import zipfile

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import mapscommon as mc  # noqa: E402
import mcgp1  # noqa: E402
import topologia  # noqa: E402

QUI = os.path.dirname(os.path.abspath(__file__))
USCITA = os.path.join(mc.ASSET_MAP, "base")
IMPRONTE = os.path.join(QUI, "impronte.json")

# Tolleranze di Douglas-Peucker, in gradi (distanza piana): 0,005 gradi sono circa 550 m, meno di un pixel a z7, il livello oltre il quale
# prendono il posto le tessere online. Le regioni (1:10m) sono piu' fini.
TOL_TERRE = 0.01
TOL_LAGHI = 0.01
TOL_CONFINI = 0.01
TOL_REGIONI = 0.002
TOL_PROVINCE = 0.003
# Un anello piu' piccolo di cosi' (gradi quadrati, circa 2,5 km2) sparisce: a questa scala sarebbe un puntino.
AREA_MINIMA = 0.0002
POPOLAZIONE_MINIMA = 50000
# Le soglie dei livelli delle citta' (abitanti): 1 sopra 3 milioni, ... 5 il resto (senza proprieta' `rank`). Una capitale di Stato e' almeno di livello 3.
SOGLIE_CITTA = ((3000000, 1), (1000000, 2), (400000, 3), (150000, 4))
NOMI_VIETATI = ("casa", "home")

# I caratteri che i glifi dentro l'app coprono (docs/fase9-progetto.md §2.7): 0-255, 256-511, 768-1023, 8192-8447.
INTERVALLI_GLIFI = ((0, 511), (768, 1023), (8192, 8447))


def _ingresso(doc, id_):
    for e in doc["scaricati"]:
        if e["id"] == id_:
            return os.path.join(mc.DOWNLOAD, e["id"], e["file"])
    raise KeyError(id_)


def _carica_ne(doc, nome):
    path = _ingresso(doc, "ne-5.1.2-" + nome)
    return mc.carica_json(path)


def _geometrie_anelli(geom):
    """Gli anelli di ogni poligono di una geometria Polygon/MultiPolygon: [[anello esterno, buchi...], ...]."""
    if geom["type"] == "Polygon":
        return [geom["coordinates"]]
    if geom["type"] == "MultiPolygon":
        return geom["coordinates"]
    raise ValueError("geometria non prevista: " + geom["type"])


def _semplifica_anello(anello, tol):
    punti = [(x, y) for x, y in anello]
    semplice = mc.arrotonda(mc.douglas_peucker(punti, tol))
    if len(semplice) < 4 or semplice[0] != semplice[-1]:
        return None
    if abs(mc.area_anello(semplice)) < AREA_MINIMA:
        return None
    return semplice


def poligoni(fc, tol):
    """Tutti i poligoni delle feature, con gli anelli semplificati (i buchi troppo piccoli e i poligoni senza anello esterno spariscono)."""
    out = []
    for f in fc["features"]:
        for poligono in _geometrie_anelli(f["geometry"]):
            esterno = _semplifica_anello(poligono[0], tol)
            if esterno is None:
                continue
            buchi = [b for b in (_semplifica_anello(a, tol) for a in poligono[1:]) if b is not None]
            out.append([esterno] + buchi)
    out.sort(key=lambda p: (p[0][0], len(p[0])))
    return out


def linee(fc, tol, filtro=None):
    """Tutte le spezzate delle feature (LineString/MultiLineString), semplificate; [filtro] sceglie le feature."""
    out = []
    for f in fc["features"]:
        if filtro is not None and not filtro(f["properties"]):
            continue
        g = f["geometry"]
        pezzi = [g["coordinates"]] if g["type"] == "LineString" else g["coordinates"]
        for pezzo in pezzi:
            semplice = mc.arrotonda(mc.douglas_peucker([(x, y) for x, y in pezzo], tol))
            if len(semplice) >= 2:
                out.append(semplice)
    out.sort(key=lambda s: (s[0], s[-1], len(s)))
    return out


def _feature(props, tipo, coordinate):
    return {"properties": props, "geometry": {"type": tipo, "coordinates": coordinate}}


def _controlla_nome(nome):
    if nome.strip().lower() in NOMI_VIETATI:
        mc.esci("un nome della carta di base e' \"%s\": la carta non ha mai una Casa" % nome)
    return nome


def _coperto(nome):
    return all(any(a <= ord(c) <= b for a, b in INTERVALLI_GLIFI) for c in nome)


def etichette_paesi(paesi):
    out = []
    for f in paesi["features"]:
        p = f["properties"]
        nome = (p.get("NAME_IT") or p.get("NAME") or "").strip()
        if not nome or p.get("LABEL_X") is None:
            continue
        if not _coperto(nome):
            mc.esci("nome di paese fuori dai glifi dell'app: %r" % nome)
        out.append((int(p.get("LABELRANK") or 7), nome, round(p["LABEL_X"], 5), round(p["LABEL_Y"], 5)))
    out.sort()
    return [
        _feature({"name": _controlla_nome(n), "rank": r}, "Point", [x, y])
        for r, n, x, y in out
    ]


def livello_citta(popolazione, capitale):
    livello = 5
    for soglia, l in SOGLIE_CITTA:
        if popolazione >= soglia:
            livello = l
            break
    return min(livello, 3) if capitale else livello


def citta(zip_path, nomi_it, distretti):
    """Le citta' di GeoNames cities1000 con almeno 50.000 abitanti (e le capitali di Stato, di qualunque dimensione), col nome italiano."""
    righe = []
    senza_nome_it = 0
    with zipfile.ZipFile(zip_path) as z:
        with z.open("cities1000.txt") as f:
            for riga in io.TextIOWrapper(f, encoding="utf-8", newline=""):
                c = riga.rstrip("\r\n").split("\t")
                if len(c) < 19 or c[6] != "P":
                    continue
                codice = c[7]
                if codice == "PPLX":
                    continue  # un quartiere: la citta' ha il suo punto
                popolazione = int(c[14] or 0)
                capitale = codice == "PPLC"
                if popolazione < POPOLAZIONE_MINIMA and not capitale:
                    continue
                id_ = int(c[0])
                if id_ in distretti:
                    continue
                nome = nomi_it.get(id_)
                if nome is None:
                    senza_nome_it += 1
                    nome = c[1]
                if not _coperto(nome):
                    nome = c[2]  # il nome ASCII di GeoNames
                nome = nome.strip()
                if not nome or not _coperto(nome):
                    continue
                righe.append((livello_citta(popolazione, capitale), -popolazione, nome, id_, round(float(c[5]), 5), round(float(c[4]), 5), capitale))
    righe.sort()
    out = []
    for livello, _, nome, _id, lon, lat, capitale in righe:
        props = {"name": _controlla_nome(nome)}
        if livello < 5:
            props["rank"] = livello  # il livello 5 e' il valore di base: non si scrive (il file pesa meno)
        if capitale:
            props["cap"] = 1
        out.append(_feature(props, "Point", [lon, lat]))
    return out, senza_nome_it


def costruisci():
    """Tutti i file della carta di base: {nome: byte}, e un riepilogo di conteggi (per il registro, mai nomi)."""
    doc = mc.carica_json(os.path.join(QUI, "fingerprints.json"))
    mc.prepara_ingressi(doc["scaricati"], scarica=False, parla=False)
    for e in doc["locali"]:
        path = os.path.join(mc.RADICE, e["percorso"])
        if not os.path.exists(path) or mc.sha256_file(path) != e["sha256"]:
            mc.esci("ingresso locale mancante o con impronta diversa: " + e["percorso"])
    file = {}
    riepilogo = {}

    terre = poligoni(_carica_ne(doc, "ne_50m_land"), TOL_TERRE)
    file["land.geojson"] = mc.geojson_bytes([_feature({}, "MultiPolygon", terre)])
    riepilogo["terre_poligoni"] = len(terre)

    coste = linee(_carica_ne(doc, "ne_50m_coastline"), TOL_TERRE)
    file["coast.geojson"] = mc.geojson_bytes([_feature({}, "MultiLineString", coste)])
    riepilogo["coste_linee"] = len(coste)

    laghi = poligoni(_carica_ne(doc, "ne_50m_lakes"), TOL_LAGHI)
    file["lakes.geojson"] = mc.geojson_bytes([_feature({}, "MultiPolygon", laghi)])
    riepilogo["laghi_poligoni"] = len(laghi)

    confini_stato = _carica_ne(doc, "ne_50m_admin_0_boundary_lines_land")
    internazionali = linee(confini_stato, TOL_CONFINI, lambda p: (p.get("FEATURECLA") or "").startswith("International boundary"))
    contesi = linee(confini_stato, TOL_CONFINI, lambda p: not (p.get("FEATURECLA") or "").startswith("International boundary"))
    file["borders.geojson"] = mc.geojson_bytes(
        [_feature({"d": 0}, "MultiLineString", internazionali), _feature({"d": 1}, "MultiLineString", contesi)]
    )
    riepilogo["confini_linee"] = [len(internazionali), len(contesi)]

    province = topologia.province_italiane(_carica_ne(doc, "ne_10m_admin_1_states_provinces"))
    regioni, provinciali = topologia.confini(province, TOL_REGIONI, TOL_PROVINCE)
    file["regions.geojson"] = mc.geojson_bytes(
        [_feature({"lvl": 1}, "MultiLineString", regioni), _feature({"lvl": 2}, "MultiLineString", provinciali)]
    )
    riepilogo["regioni_linee"] = [len(regioni), len(provinciali)]
    punti = topologia.punti_nome(province)
    nomi_regioni = [
        _feature({"name": _controlla_nome(topologia.REGIONI[r])}, "Point", [punti[r][0], punti[r][1]]) for r in sorted(punti)
    ]
    file["region-labels.geojson"] = mc.geojson_bytes(nomi_regioni)

    file["country-labels.geojson"] = mc.geojson_bytes(etichette_paesi(_carica_ne(doc, "ne_50m_admin_0_countries")))

    with open(os.path.join(mc.RADICE, "core-places", "src", "main", "assets", "places", "places.mcgp1"), "rb") as f:
        nomi_it, distretti = mcgp1.leggi_nomi(f.read())
    elenco, senza_it = citta(os.path.join(mc.RADICE, "build", "input", "geonames", "cities1000.zip"), nomi_it, distretti)
    file["cities.geojson"] = mc.geojson_bytes(elenco)
    riepilogo["citta"] = len(elenco)
    riepilogo["citta_con_nome_inglese"] = senza_it
    return file, riepilogo


def registro(file, riepilogo):
    return {
        "descrizione": "Impronte della carta di base (feature-map/src/main/assets/map/base/). Le scrive `build_base.py`; `CartaDiBaseTest` le confronta con i file.",
        "natural_earth": "5.1.2",
        "tolleranze_gradi": {
            "terre_coste_laghi_confini": TOL_TERRE, "regioni": TOL_REGIONI, "province": TOL_PROVINCE, "area_minima_anello": AREA_MINIMA,
        },
        "decimali": 5,
        "popolazione_minima": POPOLAZIONE_MINIMA,
        "conteggi": riepilogo,
        "file": {n: {"sha256": mc.sha256_bytes(b), "byte": len(b)} for n, b in sorted(file.items())},
    }


def main():
    verifica = "--verifica" in sys.argv[1:]
    file, riepilogo = costruisci()
    reg = mc.json_leggibile(registro(file, riepilogo))
    if verifica:
        scritto = mc.carica_json(IMPRONTE)
        ok = True
        for n, b in sorted(file.items()):
            path = os.path.join(USCITA, n)
            sul_disco = mc.sha256_file(path) if os.path.exists(path) else None
            if sul_disco != mc.sha256_bytes(b) or scritto["file"].get(n, {}).get("sha256") != mc.sha256_bytes(b):
                print("DIVERSO   " + n)
                ok = False
        if open(IMPRONTE, "rb").read() != reg:
            print("DIVERSO   impronte.json")
            ok = False
        print("carta di base %s" % ("riprodotta, stesse impronte" if ok else "NON riprodotta"))
        sys.exit(0 if ok else 1)
    for n, b in sorted(file.items()):
        mc.scrivi(os.path.join(USCITA, n), b)
    mc.scrivi(IMPRONTE, reg)
    totale = sum(len(b) for b in file.values())
    for n, b in sorted(file.items()):
        print("%-24s %9d B  %s" % (n, len(b), mc.sha256_bytes(b)))
    print("totale %d B (%.2f MiB); conteggi %s" % (totale, totale / 1048576.0, riepilogo))


if __name__ == "__main__":
    main()
