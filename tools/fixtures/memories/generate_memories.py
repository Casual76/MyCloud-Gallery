#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Generatore della LIBRERIA DI PROVA DEI RICORDI (Fase 9, P11; docs/fase9-progetto.md par. 5.3 e riga P11 del par. 4.4).

Legge la specifica della Libreria sintetica "di una famiglia" (spec-media.tsv, scritta dal test MemoriesFixtureExportTest di core-memories: la fonte e' il
codice Kotlin, mai questo file) e scrive, per ogni foto, un JPEG con l'EXIF: data e ora locali dello scatto (DateTimeOriginal), marca e modello di prova. **Nessun
GPS, nessun dato personale**: i luoghi sono id finti nella specifica e non entrano nei file.

LE DATE SONO RELATIVE AL GIORNO DELLA PROVA. Ogni riga della specifica dice `anni_fa` e `giorni_fa`; il giorno dello scatto e' `oggi - giorni_fa giorni - anni_fa anni`
(prima i giorni, poi gli anni, un 29 febbraio che non esiste diventa 28), con `oggi` = --oggi (di default il giorno in cui si lancia). Cosi' gli anniversari
(una festa "un anno fa", un viaggio di 40 giorni fa di 4 anni fa) cadono sempre nei giorni giusti, qualunque sia il giorno della prova: Raccolte -> Ricordi sul
telefono di prova ha sempre qualcosa da mostrare. Nessuna data e' scritta nel repository.

Le immagini riusano il disegno e lo scrittore EXIF di tools/fixtures/trips/generate_trips.py (piccole, a blocchi di tinta piatta: il colore dice il giorno). Solo la
libreria standard di Python. L'uscita e' deterministica: la stessa specifica e lo stesso --oggi danno gli stessi byte.

Uscita, in <out>/MCG-ricordi/:
  telefono/DCIM/mcg-prova/        le foto del telefono (da caricare con `adb push` in /sdcard/DCIM/mcg-prova/)
  telefono/Pictures/mcg-prova/    gli screenshot del telefono (Screenshot_*.jpg)
  MANIFEST.tsv                    per ogni file: numero, giorno, secondo, anni_fa, giorni_fa, fonte, tipo, concetto, persone, nascosta, SHA-256, byte
  concetti.tsv                    per ogni file i concetti dei vettori finti (`MemoryVectors` di prova): file, concetto, persone, luogo, mossa, buia
  nascoste.txt                    i file da nascondere a mano nell'app per provare che i Nascosti non escono mai
  LEGGIMI.txt                     i comandi per caricare tutto sul telefono di prova

Uso:
  python generate_memories.py --spec <spec-media.tsv> [--oggi aaaa-mm-gg] [--out <cartella>] [--solo-manifesto]
  python generate_memories.py --verifica        le prove del generatore sul solo Python (campione/, EXIF riletto, determinismo, date relative)
"""
import argparse
import datetime
import hashlib
import importlib.util
import os
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
STORY = 98  # la "storia" del disegno: il seme delle immagini (non confondere con le storie dei viaggi, 0-19 e 99)
COLUMNS = ["n", "anni_fa", "giorni_fa", "secondo", "fonte", "tipo", "documento", "concetto", "persone", "luogo", "mossa", "buia", "nascosta"]


def load_trips():
    """Il generatore dei viaggi sintetici, per il suo disegno e il suo scrittore EXIF (non si copia niente)."""
    path = os.path.normpath(os.path.join(HERE, "..", "trips", "generate_trips.py"))
    spec = importlib.util.spec_from_file_location("generate_trips", path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


TRIPS = load_trips()


def minus_years(day, years):
    """Come `LocalDate.minusYears`: un 29 febbraio in un anno non bisestile diventa 28 febbraio."""
    try:
        return day.replace(year=day.year - years)
    except ValueError:
        return day.replace(year=day.year - years, day=28)


def day_of(today, years, days):
    return minus_years(today - datetime.timedelta(days=days), years)


def read_spec(path):
    rows = []
    with open(path, encoding="utf-8", newline="") as f:
        for number, line in enumerate(f, 1):
            line = line.rstrip("\r\n")
            if not line or line.startswith("#"):
                continue
            c = line.split("\t")
            if len(c) != len(COLUMNS):
                raise SystemExit("riga %d della specifica: attese %d colonne, trovate %d" % (number, len(COLUMNS), len(c)))
            r = dict(zip(COLUMNS, c))
            for key in ("n", "anni_fa", "giorni_fa", "secondo", "documento", "luogo", "mossa", "buia", "nascosta"):
                r[key] = int(r[key])
            if r["fonte"] not in ("camera", "screenshot", "whatsapp") or r["tipo"] not in ("foto", "video", "animata"):
                raise SystemExit("riga %d della specifica: fonte o tipo sconosciuti" % number)
            if not (0 <= r["secondo"] < 86400 and 0 <= r["giorni_fa"] <= 365 and 0 <= r["anni_fa"] <= 8):
                raise SystemExit("riga %d della specifica: giorno o secondo fuori intervallo" % number)
            rows.append(r)
    return rows


def trips_row(r, today):
    """La riga nella forma che il disegno e lo scrittore EXIF dei viaggi vogliono: nessun GPS, nessuna posizione, il fuso e' sempre UTC."""
    return {
        "story": STORY, "n": r["n"], "day": day_of(today, r["anni_fa"], r["giorni_fa"]), "minute": r["secondo"] // 60, "zone": 0,
        "lat": None, "lon": None, "device": "telefono", "source": "my_phone", "screen": 1 if r["fonte"] == "screenshot" else 0,
        "second": r["secondo"],
    }


def write_files(rows, today, root, only_manifest):
    manifest = ["#file\tn\tgiorno\tsecondo\tanni_fa\tgiorni_fa\tfonte\ttipo\tconcetto\tpersone\tnascosta\tsha256\tbyte"]
    concepts = ["#file\tconcetto\tpersone\tluogo\tmossa\tbuia"]
    hidden = []
    for r in rows:
        t = trips_row(r, today)
        name = TRIPS.file_name(t)
        rel = os.path.join(TRIPS.folder(t), name)
        data = TRIPS.render(t)
        if not only_manifest:
            path = os.path.join(root, rel)
            os.makedirs(os.path.dirname(path), exist_ok=True)
            with open(path, "wb") as f:
                f.write(data)
            os.utime(path, (TRIPS.FIXED_MTIME, TRIPS.FIXED_MTIME))
        rel_text = rel.replace(os.sep, "/")
        manifest.append("\t".join(str(x) for x in (
            rel_text, r["n"], t["day"].isoformat(), r["secondo"], r["anni_fa"], r["giorni_fa"], r["fonte"], r["tipo"], r["concetto"], r["persone"], r["nascosta"],
            hashlib.sha256(data).hexdigest(), len(data))))
        concepts.append("\t".join(str(x) for x in (rel_text, r["concetto"], r["persone"], r["luogo"], r["mossa"], r["buia"])))
        if r["nascosta"]:
            hidden.append(name)
    for fname, lines in (("MANIFEST.tsv", manifest), ("concetti.tsv", concepts), ("nascoste.txt", hidden)):
        with open(os.path.join(root, fname), "w", encoding="utf-8", newline="\n") as f:
            f.write("\n".join(lines) + "\n")
    return len(rows)


LEGGIMI = """LIBRERIA DI PROVA DEI RICORDI (generata da tools/fixtures/memories/generate_memories.py; solo per il telefono di prova, mai per quello di tutti i giorni)

Le foto sono immagini piccole inventate, con la data di scatto nell'EXIF calcolata dal giorno della prova: gli anniversari cadono nei giorni giusti.
Non c'e' nessun dato personale e nessun GPS.

Per caricarle sul telefono di prova (account `test`, cartelle recintate mcg-prova):
  adb push telefono/DCIM/mcg-prova/. /sdcard/DCIM/mcg-prova/
  adb push telefono/Pictures/mcg-prova/. /sdcard/Pictures/mcg-prova/
Poi aspettare la scansione e Raccolte -> Ricordi. Per provare i Nascosti: nascondere nell'app i file elencati in nascoste.txt (sono foto "perfette": se uscissero in un
ricordo sarebbe la prova che i Nascosti non sono rispettati).
"""


def generate(spec_path, today, out, only_manifest):
    rows = read_spec(spec_path)
    root = os.path.join(out, "MCG-ricordi")
    os.makedirs(root, exist_ok=True)
    total = write_files(rows, today, root, only_manifest)
    with open(os.path.join(root, "LEGGIMI.txt"), "w", encoding="utf-8", newline="\n") as f:
        f.write(LEGGIMI)
    print("%d file in %s" % (total, root))
    return root


# --- Le prove del generatore, sul solo Python -----------------------------------------------------------------------------------

def verifica():
    sample = os.path.join(HERE, "campione", "spec-campione.tsv")
    rows = read_spec(sample)
    problems = []
    if len(rows) < 20:
        problems.append("il campione ha troppe poche righe: %d" % len(rows))
    first_day = datetime.date(2026, 10, 30)
    second_day = datetime.date(2028, 2, 29)  # un 29 febbraio: gli anniversari devono restare tali
    with tempfile.TemporaryDirectory() as a1, tempfile.TemporaryDirectory() as a2, tempfile.TemporaryDirectory() as b1:
        generate(sample, first_day, a1, False)
        generate(sample, first_day, a2, False)
        generate(sample, second_day, b1, False)
        ra, rb, rc = (os.path.join(d, "MCG-ricordi") for d in (a1, a2, b1))
        listed = sorted(os.path.relpath(os.path.join(d, f), ra) for d, _, fs in os.walk(ra) for f in fs)
        if len(listed) != 4 + len(rows):
            problems.append("attesi %d file (le foto, MANIFEST.tsv, concetti.tsv, nascoste.txt e LEGGIMI.txt), trovati %d" % (4 + len(rows), len(listed)))
        for rel in listed:
            with open(os.path.join(ra, rel), "rb") as f1, open(os.path.join(rb, rel), "rb") as f2:
                data = f1.read()
                if data != f2.read():
                    problems.append("non deterministico: " + rel)
            if rel.endswith(".jpg"):
                TRIPS.check_structure(data)
        for r in rows:
            for today, base in ((first_day, ra), (second_day, rc)):
                t = trips_row(r, today)
                path = os.path.join(base, TRIPS.folder(t), TRIPS.file_name(t))
                if not os.path.isfile(path):
                    problems.append("manca il file di una riga del campione (oggi %s)" % today)
                    continue
                with open(path, "rb") as f:
                    info = TRIPS.read_exif(f.read())
                local = datetime.datetime.combine(t["day"], datetime.time(t["minute"] // 60, t["minute"] % 60)).strftime("%Y:%m:%d %H:%M:%S")
                if info["local"] != local or info["make"] != TRIPS.MAKE or info["lat"] is not None or info["lon"] is not None:
                    problems.append("data, marca o GPS sbagliati (oggi %s)" % today)
                # La data e' relativa: oggi - giorni - anni, e mai nel futuro.
                if t["day"] >= today or t["day"] != day_of(today, r["anni_fa"], r["giorni_fa"]):
                    problems.append("data non relativa a oggi (oggi %s)" % today)
        # Gli anniversari restano tali: il giorno e il mese di una riga con giorni_fa e anni_fa sono quelli di oggi spostati, per ogni oggi.
        for r in rows:
            if r["anni_fa"] == 0:
                continue
            d1 = day_of(first_day, r["anni_fa"], r["giorni_fa"])
            d2 = day_of(second_day, r["anni_fa"], r["giorni_fa"])
            if (first_day - datetime.timedelta(days=r["giorni_fa"])).strftime("%m-%d") != d1.strftime("%m-%d") and d1.strftime("%m-%d") != "02-28":
                problems.append("anniversario spostato (oggi %s)" % first_day)
            if (second_day - datetime.timedelta(days=r["giorni_fa"])).strftime("%m-%d") != d2.strftime("%m-%d") and d2.strftime("%m-%d") != "02-28":
                problems.append("anniversario spostato (oggi %s)" % second_day)
    if problems:
        for p in sorted(set(problems)):
            print("FALLITA: " + p)
        return 1
    print("verifica del generatore: %d righe del campione, EXIF riletto, nessun GPS, uscita deterministica, date relative a due giorni di prova diversi: tutto a posto" % len(rows))
    return 0


def main(argv):
    parser = argparse.ArgumentParser(description="Genera la Libreria di prova dei ricordi (JPEG con EXIF), con date relative al giorno della prova.")
    parser.add_argument("--spec", help="spec-media.tsv (scritta dal test MemoriesFixtureExportTest)")
    parser.add_argument("--oggi", help="il giorno della prova (aaaa-mm-gg); di default oggi")
    parser.add_argument("--out", default=os.path.normpath(os.path.join(HERE, "out")), help="cartella di uscita (default tools/fixtures/memories/out)")
    parser.add_argument("--solo-manifesto", action="store_true", help="scrive i manifesti, senza i JPEG")
    parser.add_argument("--verifica", action="store_true", help="le prove del generatore sul campione")
    args = parser.parse_args(argv)
    if args.verifica:
        return verifica()
    if not args.spec:
        parser.error("serve --spec (o --verifica)")
    today = datetime.date.fromisoformat(args.oggi) if args.oggi else datetime.date.today()
    generate(args.spec, today, args.out, args.solo_manifesto)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
