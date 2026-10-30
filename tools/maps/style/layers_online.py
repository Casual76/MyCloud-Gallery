"""L'adattamento degli stili di OpenFreeMap (Positron per il chiaro, Dark per lo scuro) allo stile dell'app (Fase 9, P2; D22).

Si parte dai livelli dello stile scaricato (commit fissato) e se ne tiene solo una parte, per id:
 * via tutto cio' che e' 3D o commerciale o non serve a una galleria: edifici, punti di interesse (nessuno dei due stili li ha, ma il controllo
   lo verifica), ferrovie, sentieri, piste d'aeroporto, tunnel e ponti, scudetti delle strade;
 * ogni colore e' preso dalla tavolozza (acqua e confini in Ametista), ogni `text-font` e' uno dei nostri due (NotoSans-Regular e
   NotoSans-Bold, senza spazi nel nome: l'URL dei glifi non ha codifiche percentuali), ogni nome di luogo e' "italiano, poi inglese, poi latino";
 * le tessere sono quelle dell'URL stabile (la sorgente la scrive `build_style.py`): nessuno stile, carattere o icona si chiede a un server;
 * le etichette dei luoghi e i confini entrano da z7,5/z8, dove la carta di base sfuma (`layers_base.py`).
"""
import copy

NOME = ["coalesce", ["get", "name:it"], ["get", "name_en"], ["get", "name:latin"]]

# id del livello -> "tipo:ruolo" (il ruolo e' una chiave della tavolozza). Gli id che non sono qui non entrano nello stile.
RUOLI_CHIARO = {
    "park": "fill:park", "water": "fill:water", "landcover_ice_shelf": "fill:ice", "landcover_glacier": "fill:ice",
    "landuse_residential": "fill:residential", "landcover_wood": "fill:wood", "waterway": "line:waterway", "aeroway-area": "fill:aeroway",
    "highway_minor": "line:road_minor", "highway_major_casing": "line:road_casing", "highway_major_inner": "line:road_inner",
    "highway_major_subtle": "line:road_subtle", "highway_motorway_casing": "line:road_casing", "highway_motorway_inner": "line:road_inner",
    "highway_motorway_subtle": "line:road_subtle",
    "boundary_3": "line:boundary_state", "boundary_2": "line:boundary_country", "boundary_disputed": "line:boundary_state",
    "waterway_line_label": "text:text_water", "water_name_point_label": "text:text_water", "water_name_line_label": "text:text_water",
    "highway-name-minor": "text:text_road", "highway-name-major": "text:text_road", "airport": "text:text_secondary",
    "label_other": "text:text_secondary", "label_village": "text:text_secondary", "label_town": "text:text_primary",
    "label_state": "text:text_secondary", "label_city": "text:text_primary", "label_city_capital": "text:text_primary",
    "label_country_3": "text:text_country", "label_country_2": "text:text_country", "label_country_1": "text:text_country",
}
RUOLI_SCURO = {
    "water": "fill:water", "landcover_ice_shelf": "fill:ice", "landcover_glacier": "fill:ice", "landuse_residential": "fill:residential",
    "landcover_wood": "fill:wood", "landuse_park": "fill:park", "waterway": "line:waterway", "aeroway-area": "fill:aeroway",
    "highway_minor": "line:road_minor", "highway_major_casing": "line:road_casing", "highway_major_inner": "line:road_inner",
    "highway_major_subtle": "line:road_subtle", "highway_motorway_casing": "line:road_casing", "highway_motorway_inner": "line:road_inner",
    "highway_motorway_subtle": "line:road_subtle",
    "water_name": "text:text_water", "highway_name_other": "text:text_road", "highway_name_motorway": "text:text_road",
    "boundary_state": "line:boundary_state", "boundary_country_z5-": "line:boundary_country",
    "place_other": "text:text_secondary", "place_suburb": "text:text_secondary", "place_village": "text:text_secondary",
    "place_town": "text:text_primary", "place_city": "text:text_primary", "place_city_large": "text:text_primary",
    "place_state": "text:text_secondary", "place_country_other": "text:text_country", "place_country_minor": "text:text_country",
    "place_country_major": "text:text_country",
}
RUOLI = {"chiaro": RUOLI_CHIARO, "scuro": RUOLI_SCURO}

# Dove le tessere prendono il posto della carta di base (la sfumatura e' di layers_base.py).
Z_LUOGHI = 7.5
Z_CONFINI = 7.0
Z_CONFINI_STATO = 8.0


def _sfuma_dentro(z0, z1):
    return ["interpolate", ["linear"], ["zoom"], z0, 0, z1, 1]


def _carattere(nome):
    return "NotoSans-Bold" if nome.endswith("Bold") else "NotoSans-Regular"


def _testo(layer, ruolo, pal, icona_aeroporto):
    layout = layer.setdefault("layout", {})
    paint = layer.setdefault("paint", {})
    layout["text-field"] = copy.deepcopy(NOME)
    font = layout.get("text-font")
    if not (isinstance(font, list) and all(isinstance(f, str) for f in font)):
        raise ValueError("text-font non e' una lista di nomi in " + layer["id"])
    layout["text-font"] = [_carattere(font[0])]
    for chiave in ("icon-image", "icon-size", "icon-allow-overlap", "icon-optional", "icon-padding"):
        layout.pop(chiave, None)
    for chiave in [k for k in paint if k.startswith("icon-")]:
        del paint[chiave]
    paint["text-color"] = pal[ruolo]
    paint["text-halo-color"] = pal["halo"]
    paint["text-halo-width"] = 1.2
    paint["text-halo-blur"] = 0.5
    if layer["id"] == "airport":
        layout["icon-image"] = icona_aeroporto
        layout["icon-size"] = 1


def _colore(layer, tipo, ruolo, pal):
    paint = layer.setdefault("paint", {})
    paint.pop("fill-pattern", None)
    paint["%s-color" % tipo] = pal[ruolo]


def _zoom(layer):
    """Il passaggio dalla carta di base alle tessere: nomi dei luoghi da z7,5 con dissolvenza, confini da z7/z8."""
    sl = layer.get("source-layer")
    paint = layer.setdefault("paint", {})
    if layer["type"] == "symbol" and sl == "place":
        layer["minzoom"] = Z_LUOGHI
        paint["text-opacity"] = _sfuma_dentro(Z_LUOGHI, Z_LUOGHI + 1)
        massimo = 12 if "country" in layer["id"] or "state" in layer["id"] else None
        if massimo is None:
            layer.pop("maxzoom", None)
        else:
            layer["maxzoom"] = massimo
    elif layer["type"] == "line" and sl == "boundary":
        statale = layer["id"] in ("boundary_3", "boundary_state")
        z = Z_CONFINI_STATO if statale else Z_CONFINI
        layer["minzoom"] = max(float(layer.get("minzoom", 0)), z)
        layer.pop("maxzoom", None)
        paint["line-opacity"] = _sfuma_dentro(z, z + 1)


def adatta(sorgente, ruoli, pal, icona_aeroporto, solo=None):
    """I livelli online adattati (lista di dizionari, nell'ordine dello stile scaricato). [ruoli] dice quali tenere e come colorarli; [solo]
    ne limita l'elenco a certi id (serve a prendere il livello dell'aeroporto di Positron anche per lo stile scuro)."""
    id_scelti = set(ruoli) if solo is None else set(solo)
    mancanti = sorted(id_scelti - {l["id"] for l in sorgente["layers"]})
    if mancanti:
        raise ValueError("livelli attesi che lo stile scaricato non ha piu': %s" % mancanti)
    out = []
    for originale in sorgente["layers"]:
        if originale["id"] not in id_scelti:
            continue
        layer = copy.deepcopy(originale)
        tipo, ruolo = ruoli[layer["id"]].split(":")
        if tipo in ("fill", "line"):
            _colore(layer, tipo, ruolo, pal)
        else:
            _testo(layer, ruolo, pal, icona_aeroporto)
        _zoom(layer)
        layer["source"] = "openmaptiles"
        out.append(layer)
    return out
