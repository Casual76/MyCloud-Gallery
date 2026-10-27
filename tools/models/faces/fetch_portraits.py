"""Scarica i ritratti dipinti di pubblico dominio di Wikimedia Commons per le prove dei modelli dei volti (Fase 8, P1; docs/fase8-progetto.md §5.2).

    python -I fetch_portraits.py --root "C:\\...\\mcg-models\\faces"

Legge `<root>/ritratti/selezione.json` (elenco di `{"titolo": "File:...", "soggetto": "s01", "larghezza": 1280}`; `soggetto` e' un'etichetta
neutra: stesso soggetto = stessa persona ritratta) e per ogni voce:
  - chiede a Commons licenza e dimensioni (API di `commons.wikimedia.org`);
  - accetta il file SOLO se la licenza e' «Public domain» (o CC0), non risulta protetto da copyright ed e' un JPEG;
  - scarica la versione ridotta (miniatura standard) da `upload.wikimedia.org`, controlla che sia un JPEG che si decodifica;
  - la salva come `<root>/ritratti/ritratto-NN.jpg` (nessun nome di persona nel nome del file) e annota fonte, licenza e SHA-256 in
    `<root>/ritratti/ELENCO.json`.
Solo i due host di Wikimedia sopra; nel repository non entra mai un ritratto (restano fuori, in <root>/ritratti).
"""
from __future__ import annotations

import argparse
import html
import json
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import common  # noqa: E402

API = "https://commons.wikimedia.org/w/api.php"
HOST_API = "commons.wikimedia.org"
HOST_FILE = "upload.wikimedia.org"
USER_AGENT = "MyCloudGallery-modelli-volti/1.0 (https://github.com/Casual76/MyCloud-Gallery; prove dei modelli dei volti)"
MAX_BYTES = 25 * 1024 * 1024
PAUSA_S = 1.5


class _SoloQuestoHost(urllib.request.HTTPRedirectHandler):
    """Nessuna redirezione verso un host diverso da quello ammesso per la richiesta."""

    def __init__(self, host: str):
        self.host = host

    def redirect_request(self, req, fp, code, msg, headers, newurl):
        p = urllib.parse.urlparse(newurl)
        if p.scheme != "https" or (p.hostname or "").lower() != self.host:
            raise urllib.error.URLError("redirezione verso un host non ammesso: " + (p.hostname or "?"))
        return super().redirect_request(req, fp, code, msg, headers, newurl)


def richiesta(url: str, host: str, massimo: int) -> bytes:
    p = urllib.parse.urlparse(url)
    if p.scheme != "https" or (p.hostname or "").lower() != host:
        sys.exit("Indirizzo non ammesso: " + url)
    ultimo = None
    for tentativo in range(4):
        try:
            req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT, "Accept-Encoding": "identity"})
            with urllib.request.build_opener(_SoloQuestoHost(host)).open(req, timeout=90) as r:
                dati = r.read(massimo + 1)
            if len(dati) > massimo:
                sys.exit("Piu' byte del previsto da " + url)
            return dati
        except urllib.error.HTTPError as e:
            ultimo = e
            if e.code in (429, 503):
                time.sleep(10 * (tentativo + 1))
                continue
            raise
    raise ultimo  # type: ignore[misc]


def info_file(titolo: str, larghezza: int) -> dict:
    params = {"action": "query", "format": "json", "formatversion": "2", "titles": titolo, "prop": "imageinfo",
              "iiprop": "url|size|mime|extmetadata", "iiurlwidth": str(larghezza),
              "iiextmetadatafilter": "LicenseShortName|UsageTerms|Copyrighted|Artist|AttributionRequired|LicenseUrl|DateTimeOriginal"}
    dati = json.loads(richiesta(API + "?" + urllib.parse.urlencode(params), HOST_API, 1 << 20))
    pagine = dati.get("query", {}).get("pages", [])
    if not pagine or "imageinfo" not in pagine[0]:
        sys.exit(f"{titolo}: non trovato su Commons")
    return pagine[0]["imageinfo"][0]


def testo(valore: str | None) -> str:
    if not valore:
        return ""
    return re.sub(r"\s+", " ", html.unescape(re.sub(r"<[^>]+>", " ", valore))).strip()


def licenza_ammessa(meta: dict) -> tuple[bool, str]:
    breve = testo(meta.get("LicenseShortName", {}).get("value"))
    copyright_ = testo(meta.get("Copyrighted", {}).get("value"))
    ok = breve in ("Public domain", "CC0") and copyright_ == "False"
    return ok, breve


def main() -> None:
    import cv2
    import numpy as np

    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--root", help="cartella dei modelli dei volti, fuori dal repository (o MCG_FACES_ROOT)")
    args = ap.parse_args()
    radice = common.radice_modelli(args.root)
    cartella = radice / "ritratti"
    selezione = json.loads((cartella / "selezione.json").read_text(encoding="utf-8"))
    elenco = []
    for n, voce in enumerate(selezione, start=1):
        etichetta = f"ritratto-{n:02d}"
        info = info_file(voce["titolo"], int(voce.get("larghezza", 1280)))
        meta = info.get("extmetadata", {})
        ok, breve = licenza_ammessa(meta)
        if not ok or info.get("mime") != "image/jpeg":
            print(f"SCARTATO {etichetta}: licenza «{breve}», mime {info.get('mime')}")
            continue
        url = info.get("thumburl") or info["url"]
        # L'API indica la miniatura su `thumb.wikimedia.org`, che non e' fra gli host autorizzati: la stessa miniatura sta su
        # `upload.wikimedia.org` allo stesso percorso (senza i parametri di tracciamento in coda).
        pu = urllib.parse.urlparse(url)
        if pu.hostname == "thumb.wikimedia.org":
            url = urllib.parse.urlunparse(("https", HOST_FILE, pu.path, "", "", ""))
        file_locale = cartella / f"{etichetta}.jpg"
        if not file_locale.is_file():
            time.sleep(PAUSA_S)
            dati = richiesta(url, HOST_FILE, MAX_BYTES)
            if not dati.startswith(b"\xff\xd8\xff"):
                sys.exit(f"{etichetta}: non e' un JPEG")
            immagine = cv2.imdecode(np.frombuffer(dati, dtype=np.uint8), cv2.IMREAD_COLOR)
            if immagine is None:
                sys.exit(f"{etichetta}: il JPEG non si decodifica")
            file_locale.write_bytes(dati)
        immagine = cv2.imread(str(file_locale))
        elenco.append({
            "etichetta": etichetta,
            "soggetto": voce["soggetto"],
            "titolo_commons": voce["titolo"],
            "pagina": "https://commons.wikimedia.org/wiki/" + urllib.parse.quote(voce["titolo"].replace(" ", "_")),
            "indirizzo_file": url,
            "licenza": breve,
            "condizioni": testo(meta.get("UsageTerms", {}).get("value")),
            "autore": testo(meta.get("Artist", {}).get("value"))[:200],
            "originale": {"larghezza": info.get("width"), "altezza": info.get("height")},
            "scaricato": {"larghezza": int(immagine.shape[1]), "altezza": int(immagine.shape[0]), "byte": file_locale.stat().st_size,
                          "sha256": common.sha256_file(file_locale)},
        })
        print(f"{etichetta}: {breve}, {immagine.shape[1]}x{immagine.shape[0]}, {file_locale.stat().st_size} byte")
        time.sleep(PAUSA_S)
    (cartella / "ELENCO.json").write_text(json.dumps(elenco, indent=2, ensure_ascii=False), encoding="utf-8")
    print(f"{len(elenco)} ritratti, {sum(e['scaricato']['byte'] for e in elenco)} byte")


if __name__ == "__main__":
    main()
