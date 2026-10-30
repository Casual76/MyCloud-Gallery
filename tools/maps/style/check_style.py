"""I controlli sugli stili scritti (Fase 9, P2): quelli del "Fatto quando" di docs/fase9-progetto.md §4.4, riga P2.

    python -I tools/maps/style/check_style.py [cartella]   (di base feature-map/src/main/assets/map)

 * nessun URL fuori da `asset://` e dall'URL stabile delle tessere; nessuna sorgente con `url` (il TileJSON lo chiede il cancello);
 * ogni `text-font` ha i suoi glifi (i quattro intervalli veri e tutti gli altri come file vuoti), ogni `icon-image` il suo sprite (1x e 2x);
 * niente edifici 3D, niente punti di interesse, niente livelli "Casa";
 * lo stile offline non ha sorgenti di rete; l'online ha la sola sorgente `openmaptiles`;
 * contrasto del testo >= 4,5:1 contro ogni sfondo su cui puo' cadere, calcolato sui colori dello stile; acqua e confini in Ametista.
Gli stessi controlli, in Kotlin, sono in `MapStyleAssetsTest` (feature-map): il test li rifa' per conto suo sui file dell'app.
"""
import colorsys
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import mapscommon as mc  # noqa: E402
import palette  # noqa: E402

URL_TESSERE = "https://tiles.openfreemap.org/planet/v/{z}/{x}/{y}.pbf"
FONT_AMMESSI = ("NotoSans-Regular", "NotoSans-Bold")
INTERVALLI_VERI = ("0-255", "256-511", "768-1023", "8192-8447")
LIVELLI_ORIGINE = {"water", "landcover", "landuse", "park", "waterway", "aeroway", "transportation", "transportation_name", "boundary", "place",
                   "water_name", "aerodrome_label"}
AMETISTA = {"chiaro": "#9966CC", "scuro": "#B88CE8"}
# Le proprieta' che la specifica di stile conosce per i tipi di livello usati qui: una chiave sbagliata farebbe scartare il livello a MapLibre.
PAINT = {
    "background": {"background-color", "background-opacity", "background-pattern"},
    "fill": {"fill-antialias", "fill-color", "fill-opacity", "fill-outline-color", "fill-pattern", "fill-translate", "fill-translate-anchor"},
    "line": {"line-blur", "line-color", "line-dasharray", "line-gap-width", "line-gradient", "line-offset", "line-opacity", "line-pattern",
             "line-translate", "line-translate-anchor", "line-width"},
    "circle": {"circle-blur", "circle-color", "circle-opacity", "circle-pitch-alignment", "circle-pitch-scale", "circle-radius",
               "circle-stroke-color", "circle-stroke-opacity", "circle-stroke-width", "circle-translate", "circle-translate-anchor"},
    "symbol": {"icon-color", "icon-halo-blur", "icon-halo-color", "icon-halo-width", "icon-opacity", "icon-translate", "icon-translate-anchor",
               "text-color", "text-halo-blur", "text-halo-color", "text-halo-width", "text-opacity", "text-translate", "text-translate-anchor"},
}
LAYOUT = {
    "background": {"visibility"},
    "fill": {"fill-sort-key", "visibility"},
    "line": {"line-cap", "line-join", "line-miter-limit", "line-round-limit", "line-sort-key", "visibility"},
    "circle": {"circle-sort-key", "visibility"},
    "symbol": {"icon-allow-overlap", "icon-anchor", "icon-ignore-placement", "icon-image", "icon-keep-upright", "icon-offset", "icon-optional",
               "icon-padding", "icon-pitch-alignment", "icon-rotate", "icon-rotation-alignment", "icon-size", "icon-text-fit",
               "icon-text-fit-padding", "symbol-avoid-edges", "symbol-placement", "symbol-sort-key", "symbol-spacing", "symbol-z-order",
               "text-allow-overlap", "text-anchor", "text-field", "text-font", "text-ignore-placement", "text-justify", "text-keep-upright",
               "text-letter-spacing", "text-line-height", "text-max-angle", "text-max-width", "text-offset", "text-optional", "text-padding",
               "text-pitch-alignment", "text-radial-offset", "text-rotate", "text-rotation-alignment", "text-size", "text-transform",
               "text-variable-anchor", "text-writing-mode", "visibility"},
}
CHIAVI_LIVELLO = {"id", "type", "source", "source-layer", "minzoom", "maxzoom", "filter", "layout", "paint", "metadata"}
STILI = ("style-chiaro.json", "style-scuro.json", "style-chiaro-offline.json", "style-scuro-offline.json")


def _stringhe(valore):
    if isinstance(valore, str):
        yield valore
    elif isinstance(valore, list):
        for v in valore:
            yield from _stringhe(v)
    elif isinstance(valore, dict):
        for k, v in valore.items():
            yield k
            yield from _stringhe(v)


def _colori(valore):
    """I colori esadecimali '#rrggbb' dentro un valore di proprieta' (anche in un'espressione)."""
    return [s for s in _stringhe(valore) if len(s) == 7 and s.startswith("#")]


def controlla_stile(nome_file, stile, cartella, sprite_nomi):
    problemi = []
    nome = "scuro" if "scuro" in nome_file else "chiaro"
    online = not nome_file.endswith("-offline.json")
    pal = palette.TAVOLOZZE[nome]

    for s in _stringhe(stile):
        if "://" in s and not (s.startswith("asset://") or s == URL_TESSERE):
            problemi.append("%s: URL fuori dall'elenco: %s" % (nome_file, s))
    for nome_sorgente, sorgente in stile["sources"].items():
        if "url" in sorgente and sorgente["type"] in ("vector", "raster", "raster-dem"):
            problemi.append("%s: la sorgente %s ha `url` (il TileJSON lo chiede il cancello)" % (nome_file, nome_sorgente))
        if sorgente["type"] in ("raster", "raster-dem"):
            problemi.append("%s: sorgente raster %s" % (nome_file, nome_sorgente))
        if sorgente["type"] == "vector" and (not online or nome_sorgente != "openmaptiles" or sorgente.get("tiles") != [URL_TESSERE]):
            problemi.append("%s: sorgente vettoriale non ammessa: %s" % (nome_file, nome_sorgente))
    if online and "openmaptiles" not in stile["sources"]:
        problemi.append("%s: manca la sorgente openmaptiles" % nome_file)
    if not stile["glyphs"].startswith("asset://map/glyphs/"):
        problemi.append("%s: glifi non da asset://" % nome_file)
    if online != ("sprite" in stile):
        problemi.append("%s: lo sprite c'e' solo nello stile online" % nome_file)

    ids = [l["id"] for l in stile["layers"]]
    if len(ids) != len(set(ids)):
        problemi.append("%s: id dei livelli ripetuti" % nome_file)
    fondi = []
    testi = set()
    for l in stile["layers"]:
        tipo = l["type"]
        if tipo in ("fill-extrusion", "raster", "hillshade", "heatmap"):
            problemi.append("%s: livello %s di tipo %s" % (nome_file, l["id"], tipo))
        sl = l.get("source-layer")
        if sl is not None and sl not in LIVELLI_ORIGINE:
            problemi.append("%s: source-layer non ammesso %s in %s (niente edifici, niente punti di interesse)" % (nome_file, sl, l["id"]))
        if "source" in l and l["source"] not in stile["sources"]:
            problemi.append("%s: livello %s con sorgente sconosciuta" % (nome_file, l["id"]))
        if set(re.split(r"[^a-z0-9]+", l["id"].lower())) & {"casa", "home", "3d", "poi", "building", "extrusion"}:
            problemi.append("%s: id sospetto %s" % (nome_file, l["id"]))
        paint = l.get("paint", {})
        layout = l.get("layout", {})
        for chiave in l:
            if chiave not in CHIAVI_LIVELLO:
                problemi.append("%s: chiave sconosciuta %s in %s" % (nome_file, chiave, l["id"]))
        for chiave in paint:
            if chiave not in PAINT.get(tipo, set()):
                problemi.append("%s: proprieta' paint sconosciuta %s in %s" % (nome_file, chiave, l["id"]))
        for chiave in layout:
            if chiave not in LAYOUT.get(tipo, set()):
                problemi.append("%s: proprieta' layout sconosciuta %s in %s" % (nome_file, chiave, l["id"]))
        if "minzoom" in l and "maxzoom" in l and not l["minzoom"] < l["maxzoom"]:
            problemi.append("%s: minzoom >= maxzoom in %s" % (nome_file, l["id"]))
        if tipo != "background" and "source" not in l:
            problemi.append("%s: livello %s senza sorgente" % (nome_file, l["id"]))
        for chiave in ("text-font",):
            for f in layout.get(chiave, []):
                if f not in FONT_AMMESSI:
                    problemi.append("%s: carattere non ammesso %s in %s" % (nome_file, f, l["id"]))
                for iv in INTERVALLI_VERI:
                    if not os.path.exists(os.path.join(cartella, "glyphs", f, iv + ".pbf")) or os.path.getsize(os.path.join(cartella, "glyphs", f, iv + ".pbf")) < 100:
                        problemi.append("%s: mancano i glifi %s/%s" % (nome_file, f, iv))
        icona = layout.get("icon-image")
        if icona is not None:
            if not isinstance(icona, str) or icona not in sprite_nomi:
                problemi.append("%s: icona %r di %s non e' nello sprite" % (nome_file, icona, l["id"]))
        if tipo == "background":
            fondi += _colori(paint.get("background-color"))
        elif tipo == "fill":
            fondi += _colori(paint.get("fill-color"))
        elif tipo == "line" and sl in ("transportation", "aeroway"):
            fondi += _colori(paint.get("line-color"))
        elif tipo == "symbol" and "text-color" in paint:
            testi.update(_colori(paint["text-color"]))
        if tipo == "symbol":
            for campo in _stringhe(layout.get("text-field", [])):
                if campo.strip().lower() in ("casa", "home"):
                    problemi.append("%s: etichetta Casa in %s" % (nome_file, l["id"]))
    if not testi or not fondi:
        problemi.append("%s: nessun colore di testo o di sfondo da confrontare" % nome_file)
    peggiore = min((mc.contrasto(t, f) for t in testi for f in set(fondi)), default=0)
    if peggiore < palette.CONTRASTO_MINIMO:
        problemi.append("%s: contrasto del testo %.2f:1, minimo %.1f:1" % (nome_file, peggiore, palette.CONTRASTO_MINIMO))
    colore_acqua = pal["water"]
    r, g, b = (c / 255.0 for c in mc.hex_a_rgb(colore_acqua))
    tonalita = colorsys.rgb_to_hls(r, g, b)[0] * 360
    if not 255 <= tonalita <= 285:
        problemi.append("%s: l'acqua non e' in Ametista (tonalita' %.0f)" % (nome_file, tonalita))
    if pal["boundary_country"] != AMETISTA[nome]:
        problemi.append("%s: il confine non e' l'Ametista dell'app" % nome_file)
    if nome_file.endswith(".json") and os.path.getsize(os.path.join(cartella, nome_file)) > 100 * 1024:
        problemi.append("%s: piu' di 100 KB" % nome_file)
    return problemi, peggiore


def controlla(cartella):
    problemi = []
    sprite = {}
    for scala, suff in ((1, ""), (2, "@2x")):
        with open(os.path.join(cartella, "sprites", "mcg%s.json" % suff), "rb") as f:
            sprite[scala] = set(mc.carica_json(os.path.join(cartella, "sprites", "mcg%s.json" % suff)))
    if sprite[1] != sprite[2]:
        problemi.append("sprite 1x e 2x con icone diverse")
    for f in ("NOTICE.txt", "LICENSE-NaturalEarth.txt", "LICENSE-Positron.txt", "LICENSE-Dark.txt", "LICENSE-NotoSans.txt", "LICENSE-OpenFreeMapStyles.txt"):
        if not os.path.exists(os.path.join(cartella, f)):
            problemi.append("manca " + f)
    for font in FONT_AMMESSI:
        presenti = {n[:-4] for n in os.listdir(os.path.join(cartella, "glyphs", font)) if n.endswith(".pbf")}
        attesi = {"%d-%d" % (i, i + 255) for i in range(0, 65536, 256)}
        if presenti != attesi:
            problemi.append("%s: intervalli di glifi mancanti o in piu'" % font)
    contrasti = {}
    for nome_file in STILI:
        stile = mc.carica_json(os.path.join(cartella, nome_file))
        p, peggiore = controlla_stile(nome_file, stile, cartella, sprite[1])
        problemi += p
        contrasti[nome_file] = round(peggiore, 2)
    return problemi, contrasti


def main():
    cartella = sys.argv[1] if len(sys.argv) > 1 else mc.ASSET_MAP
    problemi, contrasti = controlla(cartella)
    for p in problemi:
        print("PROBLEMA  " + p)
    print("contrasto peggiore del testo (testo contro ogni sfondo): %s" % contrasti)
    print("controlli degli stili: %s" % ("OK" if not problemi else "%d problemi" % len(problemi)))
    sys.exit(1 if problemi else 0)


if __name__ == "__main__":
    main()
