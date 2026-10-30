"""Stili, glifi, sprite e licenze della mappa (Fase 9, P2; docs/fase9-progetto.md §2.7, D22).

    python -I tools/maps/style/build_style.py             scrive feature-map/src/main/assets/map/ (stili, glyphs/, sprites/, LICENSE-*, NOTICE) e impronte.json
    python -I tools/maps/style/build_style.py --verifica  ricostruisce in memoria e confronta con impronte.json e con i file scritti

Non scarica niente: lavora sui file di `build/download/` (li mette `fetch.py --scarica`, a mano). Riproducibile: stessi ingressi, stessi byte.
Dopo la scrittura lancia i controlli di `check_style.py`.
"""
import copy
import os
import sys
import zipfile

AQUI = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.dirname(AQUI))
sys.path.insert(0, AQUI)
import mapscommon as mc  # noqa: E402
import layers_base  # noqa: E402
import layers_online  # noqa: E402
import notice  # noqa: E402
import palette  # noqa: E402
import png  # noqa: E402

IMPRONTE = os.path.join(AQUI, "impronte.json")
URL_TESSERE = "https://tiles.openfreemap.org/planet/v/{z}/{x}/{y}.pbf"  # MapHost.TILES + STABLE_TILE_PREFIX (core-map): lo prova MapStyleAssetsTest
GLIFI = "asset://map/glyphs/{fontstack}/{range}.pbf"
SPRITE = "asset://map/sprites/mcg"
FONTS = {"NotoSans-Regular": "Noto Sans Regular", "NotoSans-Bold": "Noto Sans Bold"}
INTERVALLI_VERI = ("0-255", "256-511", "768-1023", "8192-8447")
SORGENTE_STILE = {"chiaro": "positron", "scuro": "dark"}
LICENZE = {
    "LICENSE-NaturalEarth.txt": ("ne-5.1.2-LICENSE", "LICENSE.md", "natural"),
    "LICENSE-OpenFreeMapStyles.txt": ("ofm-styles-006ea57-repo-license", "LICENSE.md", "stile"),
    "LICENSE-Positron.txt": ("ofm-styles-006ea57-positron-license", "LICENSE.md", "stile"),
    "LICENSE-Dark.txt": ("ofm-styles-006ea57-dark-license", "LICENSE.md", "stile"),
    "LICENSE-NotoSans.txt": ("openmaptiles-fonts-2.0-noto-sans-license", "LICENSE", "stile"),
}


def _ingressi():
    stile = mc.carica_json(os.path.join(AQUI, "fingerprints.json"))
    base = mc.carica_json(os.path.join(os.path.dirname(AQUI), "base", "fingerprints.json"))
    mc.prepara_ingressi(stile["scaricati"], scarica=False, parla=False)
    mc.prepara_ingressi(base["scaricati"], scarica=False, parla=False)
    return stile, base


def _percorso(doc, id_):
    e = next(e for e in doc["scaricati"] if e["id"] == id_)
    return os.path.join(mc.DOWNLOAD, e["id"], e["file"])


def sorgente_online():
    return {"type": "vector", "tiles": [URL_TESSERE], "minzoom": 0, "maxzoom": 14, "attribution": notice.ONLINE}


def _frecce(pal_nome):
    """I due livelli dei sensi unici (dallo stile Dark): la freccia, nera nel chiaro e bianca nello scuro."""
    icona = "oneway-black" if pal_nome == "chiaro" else "oneway-white"
    comune = {
        "type": "symbol", "source": "openmaptiles", "source-layer": "transportation", "minzoom": 15,
        "paint": {"icon-opacity": 0.5},
    }
    def uno(id_, filtro, rotazione):
        l = copy.deepcopy(comune)
        l.update({"id": id_, "filter": filtro, "layout": {
            "icon-image": icona, "icon-padding": 2, "icon-rotate": rotazione, "icon-rotation-alignment": "map",
            "icon-size": ["interpolate", ["linear"], ["zoom"], 15, 0.5, 19, 1], "symbol-placement": "line", "symbol-spacing": 200}})
        return l
    return [uno("road_oneway", ["==", ["get", "oneway"], 1], 90), uno("road_oneway_opposite", ["==", ["get", "oneway"], -1], -90)]


def costruisci_stile(stile_doc, nome, en_linea):
    pal = palette.TAVOLOZZE[nome]
    stile = {
        "version": 8,
        "name": "MyCloud Gallery %s%s" % (nome, "" if en_linea else " (senza tessere online)"),
        "metadata": {"mcg:stile": nome, "mcg:online": en_linea},
        "sources": {},
        "glyphs": GLIFI,
    }
    base = layers_base.sorgenti()
    if en_linea:
        sorgente = mc.carica_json(_percorso(stile_doc, "ofm-styles-006ea57-%s-style" % SORGENTE_STILE[nome]))
        icona = "airport-black" if nome == "chiaro" else "airport-white"
        online = layers_online.adatta(sorgente, layers_online.RUOLI[nome], pal, icona)
        if nome == "scuro":
            # Lo stile Dark non ha l'aeroporto: lo prende da Positron, ricolorato.
            positron = mc.carica_json(_percorso(stile_doc, "ofm-styles-006ea57-positron-style"))
            aeroporto = layers_online.adatta(positron, layers_online.RUOLI["chiaro"], pal, icona, solo=["airport"])
            primo_luogo = next(i for i, l in enumerate(online) if l.get("source-layer") == "place")
            online[primo_luogo:primo_luogo] = aeroporto
        online += _frecce(nome)
        primo_testo = next(i for i, l in enumerate(online) if l["type"] == "symbol" and l.get("source-layer") != "transportation")
        layers = (
            layers_base.sfondi(pal, True) + layers_base.riempimenti(pal, True) + online[:primo_testo]
            + layers_base.linee(pal, True) + online[primo_testo:] + layers_base.etichette(pal, True)
        )
        stile["sprite"] = SPRITE
        stile["sources"]["openmaptiles"] = sorgente_online()
    else:
        layers = (
            layers_base.sfondi(pal, False) + layers_base.riempimenti(pal, False) + layers_base.linee(pal, False)
            + layers_base.etichette(pal, False)
        )
    stile["sources"].update(base)
    stile["layers"] = layers
    return stile


# --- Sprite ----------------------------------------------------------------------------------------------------

ICONE = (("airport-black", "airport_11", False), ("airport-white", "airport_11", True), ("oneway-black", "oneway", False), ("oneway-white", "oneway", True))


def costruisci_sprite(stile_doc, scala):
    suffisso = "" if scala == 1 else "@2x"
    atlante = mc.carica_json(_percorso(stile_doc, "ofm-styles-006ea57-sprite-%dx-json" % scala))
    with open(_percorso(stile_doc, "ofm-styles-006ea57-sprite-%dx-png" % scala), "rb") as f:
        larghezza, _altezza, righe = png.leggi(f.read())
    pezzi = []
    for nome, origine, bianco in ICONE:
        e = atlante[origine]
        pezzo = png.ritaglia(larghezza, righe, e["x"], e["y"], e["width"], e["height"])
        pezzi.append((nome, e["width"], e["height"], png.bianco(pezzo) if bianco else pezzo))
    x = 0
    indice = {}
    for nome, w, h, _ in pezzi:
        indice[nome] = {"height": h, "pixelRatio": scala, "width": w, "x": x, "y": 0}
        x += w + 2
    alto = max(h for _, _, h, _ in pezzi)
    tela = [bytearray(x * 4) for _ in range(alto)]
    for nome, w, h, pezzo in pezzi:
        for j in range(h):
            inizio = indice[nome]["x"] * 4
            tela[j][inizio:inizio + w * 4] = pezzo[j]
    return {
        "sprites/mcg%s.json" % suffisso: mc.json_compatto(indice),
        "sprites/mcg%s.png" % suffisso: png.scrivi(x, alto, tela),
    }


# --- Glifi -----------------------------------------------------------------------------------------------------

def _vuoto(nome, intervallo):
    """Un file di glifi valido e senza glifi: MapLibre lo legge, non trova il carattere e va avanti (un file che manca resterebbe un errore)."""
    def campo(numero, testo):
        t = testo.encode("utf-8")
        return bytes([numero << 3 | 2, len(t)]) + t
    interno = campo(1, nome) + campo(2, intervallo)
    return bytes([1 << 3 | 2, len(interno)]) + interno


def costruisci_glifi(stile_doc):
    file = {}
    with zipfile.ZipFile(_percorso(stile_doc, "openmaptiles-fonts-2.0-noto-sans-zip")) as z:
        for nome, cartella_zip in sorted(FONTS.items()):
            for inizio in range(0, 65536, 256):
                intervallo = "%d-%d" % (inizio, inizio + 255)
                if intervallo in INTERVALLI_VERI:
                    file["glyphs/%s/%s.pbf" % (nome, intervallo)] = z.read("%s/%s.pbf" % (cartella_zip, intervallo))
                else:
                    file["glyphs/%s/%s.pbf" % (nome, intervallo)] = _vuoto(nome, intervallo)
    return file


def costruisci():
    stile_doc, base_doc = _ingressi()
    file = {}
    for nome in ("chiaro", "scuro"):
        file["style-%s.json" % nome] = mc.json_compatto(costruisci_stile(stile_doc, nome, True))
        file["style-%s-offline.json" % nome] = mc.json_compatto(costruisci_stile(stile_doc, nome, False))
    file.update(costruisci_sprite(stile_doc, 1))
    file.update(costruisci_sprite(stile_doc, 2))
    file.update(costruisci_glifi(stile_doc))
    for destinazione, (id_, nome_file, quale) in sorted(LICENZE.items()):
        doc = stile_doc if quale == "stile" else base_doc
        with open(_percorso(doc, id_), "rb") as f:
            file[destinazione] = f.read()
    uscite = {n: (len(b), mc.sha256_bytes(b)) for n, b in file.items()}
    base_registro = mc.carica_json(os.path.join(os.path.dirname(AQUI), "base", "impronte.json"))
    uscite.update({"base/" + n: (v["byte"], v["sha256"]) for n, v in base_registro["file"].items()})
    file["NOTICE.txt"] = notice.testo(stile_doc, base_doc, uscite)
    return file


def registro(file):
    veri = {n: {"sha256": mc.sha256_bytes(b), "byte": len(b)} for n, b in sorted(file.items()) if not _e_vuoto(n, b)}
    vuoti = sorted(n for n, b in file.items() if _e_vuoto(n, b))
    return {
        "descrizione": "Impronte di stile, sprite, glifi e licenze (feature-map/src/main/assets/map/). Le scrive `build_style.py`; `MapStyleAssetsTest` le confronta con i file.",
        "glifi_vuoti": {"quanti": len(vuoti), "sha256_dell_elenco_e_dei_byte": mc.sha256_bytes(b"".join(n.encode() + b"\n" + file[n] for n in vuoti))},
        "file": veri,
    }


def _e_vuoto(nome, byte):
    return nome.startswith("glyphs/") and nome.split("/")[-1].removesuffix(".pbf") not in INTERVALLI_VERI


def main():
    verifica = "--verifica" in sys.argv[1:]
    file = costruisci()
    reg = mc.json_leggibile(registro(file))
    if verifica:
        ok = True
        for n, b in sorted(file.items()):
            path = os.path.join(mc.ASSET_MAP, n)
            if not os.path.exists(path) or mc.sha256_file(path) != mc.sha256_bytes(b):
                print("DIVERSO   " + n)
                ok = False
        if not os.path.exists(IMPRONTE) or open(IMPRONTE, "rb").read() != reg:
            print("DIVERSO   impronte.json")
            ok = False
        print("stile, sprite, glifi e licenze %s" % ("riprodotti, stesse impronte" if ok else "NON riprodotti"))
        sys.exit(0 if ok else 1)
    for n, b in sorted(file.items()):
        mc.scrivi(os.path.join(mc.ASSET_MAP, n), b)
    mc.scrivi(IMPRONTE, reg)
    tot = sum(len(b) for b in file.values())
    for n, b in sorted(file.items()):
        if not n.startswith("glyphs/") or not _e_vuoto(n, b):
            print("%-44s %9d B  %s" % (n, len(b), mc.sha256_bytes(b)[:16]))
    print("scritti %d file, %d B (glifi vuoti: %d)" % (len(file), tot, sum(1 for n, b in file.items() if _e_vuoto(n, b))))


if __name__ == "__main__":
    main()
