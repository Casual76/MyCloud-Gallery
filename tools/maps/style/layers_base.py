"""Le sorgenti e i livelli della carta di base dentro lo stile (Fase 9, P2): terre, laghi, coste, confini, regioni e nomi, da `asset://map/base/`.

Due usi:
 * `en_linea=True`: lo stile ONLINE. Sotto le tessere di OpenFreeMap e, quando le tessere non ci sono (mappa online spenta, nessuna rete),
   unico disegno. Da z7 le tessere prendono il posto: i livelli della carta sfumano fra z7 e z8 (z9 per le regioni) e le tessere entrano con
   la stessa sfumatura, cosi' una linea non e' mai doppia.
 * `en_linea=False`: lo stile OFFLINE (mini-mappa, mappa con l'online spento e nessuna regione): niente sorgente online, niente sfumature,
   nessun limite di zoom oltre quello dei dati.
"""
BASE = "asset://map/base/"

FONT_REGOLARE = "NotoSans-Regular"
FONT_GRASSETTO = "NotoSans-Bold"

ATTRIBUZIONE_BASE = "Natural Earth"
ATTRIBUZIONE_CITTA = "GeoNames"


def sorgenti():
    def geo(nome, massimo, attribuzione=None):
        s = {"type": "geojson", "data": BASE + nome, "maxzoom": massimo}
        if attribuzione:
            s["attribution"] = attribuzione
        return s

    return {
        "base-land": geo("land.geojson", 7, ATTRIBUZIONE_BASE),
        "base-coast": geo("coast.geojson", 7),
        "base-lakes": geo("lakes.geojson", 7),
        "base-borders": geo("borders.geojson", 7),
        "base-regions": geo("regions.geojson", 9),
        "base-country-labels": {"type": "geojson", "data": BASE + "country-labels.geojson"},
        "base-region-labels": {"type": "geojson", "data": BASE + "region-labels.geojson"},
        "base-cities": {"type": "geojson", "data": BASE + "cities.geojson", "attribution": ATTRIBUZIONE_CITTA},
    }


def _sfuma(z_pieno, z_zero, massimo=1):
    """Un'opacita' che da [massimo] a z_pieno scende a 0 a z_zero (e resta 0)."""
    return ["interpolate", ["linear"], ["zoom"], z_pieno, massimo, z_zero, 0]


def _opacita(en_linea, z_pieno, z_zero):
    return _sfuma(z_pieno, z_zero) if en_linea else 1


def _opzionali(layer, en_linea, massimo):
    """Online: il livello non si disegna oltre [massimo] (la sfumatura e' gia' a zero)."""
    if en_linea:
        layer["maxzoom"] = massimo
    return layer


def sfondi(pal, en_linea):
    if not en_linea:
        return [{"id": "bg-ocean", "type": "background", "paint": {"background-color": pal["water"]}}]
    return [
        {"id": "bg-land", "type": "background", "paint": {"background-color": pal["land"]}},
        # Il mare e' lo sfondo finche' le tessere non lo disegnano: da z7 a z8 lascia il posto alla terra (l'acqua la mettono le tessere).
        {"id": "bg-ocean", "type": "background", "paint": {"background-color": pal["water"], "background-opacity": _sfuma(7, 8)}},
    ]


def riempimenti(pal, en_linea):
    land = {"id": "base-land", "type": "fill", "source": "base-land", "paint": {"fill-color": pal["land"], "fill-antialias": True}}
    laghi = {
        "id": "base-lakes", "type": "fill", "source": "base-lakes",
        "paint": {"fill-color": pal["water"], "fill-opacity": _opacita(en_linea, 6.5, 7.5)},
    }
    return [land, _opzionali(laghi, en_linea, 8)]


def linee(pal, en_linea):
    regioni = {
        "id": "base-region-lines", "type": "line", "source": "base-regions", "minzoom": 4.5,
        "filter": ["==", ["get", "lvl"], 1],
        "layout": {"line-join": "round"},
        "paint": {
            "line-color": pal["boundary_region"], "line-dasharray": [4, 3],
            "line-width": ["interpolate", ["linear"], ["zoom"], 5, 0.5, 9, 1],
            "line-opacity": _opacita(en_linea, 8, 9),
        },
    }
    province = {
        "id": "base-province-lines", "type": "line", "source": "base-regions", "minzoom": 7,
        "filter": ["==", ["get", "lvl"], 2],
        "paint": {
            "line-color": pal["boundary_province"], "line-dasharray": [2, 3], "line-width": 0.5,
            "line-opacity": _opacita(en_linea, 8, 9),
        },
    }
    confini = {
        "id": "base-borders", "type": "line", "source": "base-borders",
        "filter": ["==", ["get", "d"], 0],
        "layout": {"line-join": "round", "line-cap": "round"},
        "paint": {
            "line-color": pal["boundary_country"],
            "line-width": ["interpolate", ["linear"], ["zoom"], 1, 0.5, 5, 0.9, 8, 1.4],
            "line-opacity": _opacita(en_linea, 6.5, 7.5),
        },
    }
    contesi = {
        "id": "base-borders-disputed", "type": "line", "source": "base-borders",
        "filter": ["==", ["get", "d"], 1],
        "paint": {
            "line-color": pal["boundary_state"], "line-dasharray": [2, 2],
            "line-width": ["interpolate", ["linear"], ["zoom"], 1, 0.4, 5, 0.8, 8, 1.2],
            "line-opacity": _opacita(en_linea, 6.5, 7.5),
        },
    }
    costa = {
        "id": "base-coast", "type": "line", "source": "base-coast",
        "layout": {"line-join": "round", "line-cap": "round"},
        "paint": {
            "line-color": pal["coast"],
            "line-width": ["interpolate", ["linear"], ["zoom"], 0, 0.4, 5, 0.7, 8, 1.1],
            "line-opacity": _opacita(en_linea, 6.5, 7.5),
        },
    }
    return [
        _opzionali(regioni, en_linea, 9.5), _opzionali(province, en_linea, 9.5),
        _opzionali(confini, en_linea, 8), _opzionali(contesi, en_linea, 8), _opzionali(costa, en_linea, 8),
    ]


def _testo(font, dimensione, pal, colore, extra=None):
    layout = {
        "text-field": ["get", "name"], "text-font": [font], "text-size": dimensione, "text-max-width": 7,
        "text-padding": 2, "text-optional": True,
    }
    if extra:
        layout.update(extra)
    paint = {"text-color": pal[colore], "text-halo-color": pal["halo"], "text-halo-width": 1.2, "text-halo-blur": 0.5}
    return layout, paint


def etichette(pal, en_linea):
    """Nomi dei paesi, delle regioni italiane e delle citta' (punto e nome), a livelli di importanza.

    L'ORDINE dei livelli e' la priorita' nella collisione dei nomi: paesi grandi, citta', paesi piccoli (rank >= 6: San Marino, Vaticano, Monaco...
    cederebbero il posto a Roma o a Rimini), regioni (il nome di una regione cede a quello di una citta')."""
    fuori = (lambda z: _sfuma(z - 1, z)) if en_linea else (lambda z: 1)

    def paese(nome, filtro, minimo, dim):
        layout, paint = _testo(FONT_GRASSETTO, dim, pal, "text_country", {"text-transform": "uppercase", "text-letter-spacing": 0.08})
        paint["text-opacity"] = fuori(7.5)
        layer = {"id": "base-country-" + nome, "type": "symbol", "source": "base-country-labels", "minzoom": minimo, "filter": filtro,
                 "layout": layout, "paint": paint}
        return _opzionali(layer, en_linea, 8)

    # Paesi: il livello di Natural Earth (LABELRANK) decide da quando si vedono.
    grandi = [
        paese("a", ["<=", ["get", "rank"], 2], 1.6, ["interpolate", ["linear"], ["zoom"], 1, 9, 5, 14]),
        paese("b", ["all", [">=", ["get", "rank"], 3], ["<=", ["get", "rank"], 4]], 2.6, ["interpolate", ["linear"], ["zoom"], 2, 9, 6, 13]),
        paese("c", ["==", ["get", "rank"], 5], 3.6, ["interpolate", ["linear"], ["zoom"], 3, 9, 6, 12]),
    ]
    piccoli = [paese("d", [">=", ["get", "rank"], 6], 5.6, ["interpolate", ["linear"], ["zoom"], 4, 9, 7, 11])]
    layout, paint = _testo(
        FONT_REGOLARE, ["interpolate", ["linear"], ["zoom"], 5, 10, 8, 13], pal, "text_secondary",
        {"text-transform": "uppercase", "text-letter-spacing": 0.15},
    )
    paint["text-opacity"] = _sfuma(7.5, 8.5) if en_linea else 1
    regione = [_opzionali(
        {"id": "base-region-labels", "type": "symbol", "source": "base-region-labels", "minzoom": 5.4, "layout": layout, "paint": paint},
        en_linea, 9,
    )]
    # Citta': un punto e un nome, da z2,6 le piu' grandi a z6,6 quelle da 50.000 abitanti. Il livello 5 non ha la proprieta' `rank` (e' il valore di base).
    citta = []
    for livello, minimo, dim in ((1, 2.6, 14), (2, 3.6, 13), (3, 4.6, 12), (4, 5.6, 11.5), (5, 6.6, 11)):
        filtro = ["==", ["get", "rank"], livello] if livello < 5 else ["!", ["has", "rank"]]
        raggio = ["interpolate", ["linear"], ["zoom"], 3, 1.6, 8, 3] if livello < 3 else ["interpolate", ["linear"], ["zoom"], 5, 1.4, 8, 2.4]
        punto = {
            "id": "base-city-dot-%d" % livello, "type": "circle", "source": "base-cities", "minzoom": minimo, "filter": filtro,
            "paint": {
                "circle-color": pal["dot"], "circle-stroke-color": pal["halo"], "circle-stroke-width": 0.8, "circle-radius": raggio,
                "circle-opacity": fuori(7.5), "circle-stroke-opacity": fuori(7.5),
            },
        }
        layout, paint = _testo(
            FONT_REGOLARE, ["interpolate", ["linear"], ["zoom"], minimo, dim - 2, 9, dim], pal, "text_primary",
            {"text-anchor": "top", "text-offset": [0, 0.5], "text-max-width": 8, "symbol-sort-key": livello},
        )
        paint["text-opacity"] = fuori(7.5)
        nome = {"id": "base-city-%d" % livello, "type": "symbol", "source": "base-cities", "minzoom": minimo, "filter": filtro,
                "layout": layout, "paint": paint}
        citta += [_opzionali(punto, en_linea, 8.5), _opzionali(nome, en_linea, 8.5)]
    return grandi + citta + piccoli + regione
