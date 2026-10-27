"""Scarica (o ricontrolla) i file ONNX di YuNet e SFace da OpenCV Zoo, fuori dal repository (Fase 8, P1; docs/fase8-progetto.md §8).

Solo i file di `fingerprints.json`, solo dai tre host di GitHub elencati li', al commit fissato; ogni file e' accettato solo se dimensione e
SHA-256 sono quelli noti. Un file che c'e' gia' e torna non si riscarica.

    python -I fetch_onnx.py --root "C:\\...\\mcg-models\\faces"

I file scaricati sono dati non fidati: qui si leggono solo come byte, mai si eseguono.
"""
from __future__ import annotations

import argparse
import hashlib
import sys
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

# Con `python -I` la cartella dello script non e' nel percorso di import: la aggiungiamo noi (e' quella degli strumenti, nel repository).
sys.path.insert(0, str(Path(__file__).resolve().parent))
import common  # noqa: E402

MAX_REDIREZIONI = 5


class _SoloHostAmmessi(urllib.request.HTTPRedirectHandler):
    """Segue le redirezioni a mano: solo https e solo verso gli host ammessi (GitHub e i suoi file grezzi)."""

    def __init__(self, ammessi: set[str]):
        self.ammessi = ammessi
        self.salti = 0

    def redirect_request(self, req, fp, code, msg, headers, newurl):
        self.salti += 1
        if self.salti > MAX_REDIREZIONI:
            raise urllib.error.URLError("troppe redirezioni")
        p = urllib.parse.urlparse(newurl)
        if p.scheme != "https" or (p.hostname or "").lower() not in self.ammessi or p.username or p.port not in (None, 443):
            raise urllib.error.URLError("redirezione verso un host non ammesso: " + (p.hostname or "?"))
        return super().redirect_request(req, fp, code, msg, headers, newurl)


def scarica(url: str, ammessi: set[str], massimo: int) -> bytes:
    p = urllib.parse.urlparse(url)
    if p.scheme != "https" or (p.hostname or "").lower() not in ammessi:
        sys.exit("Indirizzo non ammesso: " + url)
    opener = urllib.request.build_opener(_SoloHostAmmessi(ammessi))
    richiesta = urllib.request.Request(url, headers={"User-Agent": "MyCloudGallery-modelli-volti/1.0", "Accept-Encoding": "identity"})
    with opener.open(richiesta, timeout=120) as risposta:
        dati = risposta.read(massimo + 1)
    if len(dati) > massimo:
        sys.exit("Piu' byte del previsto da " + url)
    return dati


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--root", help="cartella dei modelli dei volti, fuori dal repository (o MCG_FACES_ROOT)")
    args = ap.parse_args()
    radice = common.radice_modelli(args.root)
    destinazione = radice / "onnx"
    destinazione.mkdir(parents=True, exist_ok=True)
    note = common.impronte_note()
    sorg = note["sorgente"]
    ammessi = {h.lower() for h in sorg["host_ammessi"]}
    base = f"https://github.com/{sorg['repository']}/raw/{sorg['commit']}/models"
    voci = [(nome, v["cartella"], nome, v["dimensione"], v["sha256"]) for nome, v in note["file"].items()]
    voci += [(chiave, v["cartella"], v["file"], v["dimensione"], v["sha256"]) for chiave, v in note["licenze"].items()]
    for locale, cartella, nome_remoto, dimensione, sha in voci:
        percorso = destinazione / locale
        if percorso.is_file() and percorso.stat().st_size == dimensione and common.sha256_file(percorso) == sha:
            print(f"gia' presente e verificato: {locale}")
            continue
        dati = scarica(f"{base}/{cartella}/{nome_remoto}", ammessi, dimensione)
        if len(dati) != dimensione or hashlib.sha256(dati).hexdigest() != sha:
            sys.exit(f"{locale}: dimensione o SHA-256 diversi da quelli noti. Mi fermo (il file non viene salvato).")
        parziale = percorso.with_suffix(percorso.suffix + ".parte")
        parziale.write_bytes(dati)
        parziale.replace(percorso)
        print(f"scaricato e verificato: {locale} ({dimensione} byte)")


if __name__ == "__main__":
    main()
