"""Controlla (e, solo con --scarica, scarica) gli ingressi di stile, sprite e glifi.

    python -I tools/maps/style/fetch.py             controlla che i file ci siano, con l'impronta fissata (non scarica)
    python -I tools/maps/style/fetch.py --scarica   scarica i mancanti da GitHub (hyperknot/openfreemap-styles al commit fissato;
                                                    release v2.0 di openmaptiles/fonts), circa 62 MB per i glifi

Lo scaricamento e' un'azione a mano: `build_style.py` e i test non lo chiamano mai.
"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
import mapscommon as mc  # noqa: E402


def main():
    scarica = "--scarica" in sys.argv[1:]
    doc = mc.carica_json(os.path.join(os.path.dirname(os.path.abspath(__file__)), "fingerprints.json"))
    mc.prepara_ingressi(doc["scaricati"], scarica)


if __name__ == "__main__":
    main()
