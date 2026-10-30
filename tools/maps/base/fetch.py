"""Controlla (e, solo con --scarica, scarica) gli ingressi della carta di base: Natural Earth 5.1.2.

    python -I tools/maps/base/fetch.py             controlla che i file ci siano, con l'impronta fissata (non scarica)
    python -I tools/maps/base/fetch.py --scarica   scarica i mancanti da GitHub (nvkelso/natural-earth-vector, commit fissato)

Lo scaricamento e' un'azione a mano: `build_base.py` e i test non lo chiamano mai.
"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
import mapscommon as mc  # noqa: E402


def main():
    scarica = "--scarica" in sys.argv[1:]
    doc = mc.carica_json(os.path.join(os.path.dirname(os.path.abspath(__file__)), "fingerprints.json"))
    mc.prepara_ingressi(doc["scaricati"], scarica)
    for e in doc["locali"]:
        path = os.path.join(mc.RADICE, e["percorso"])
        if not os.path.exists(path) or mc.sha256_file(path) != e["sha256"]:
            mc.esci("ingresso locale mancante o con impronta diversa: " + e["percorso"])
        print("ok        %s (locale)" % e["id"])


if __name__ == "__main__":
    main()
