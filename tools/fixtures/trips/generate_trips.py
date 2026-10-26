#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Generatore dei VIAGGI SINTETICI (Fase 7, P12; docs/fase7-progetto.md par. 5.3 punto 1 e F87).

Legge la specifica delle 20 storie di P6 (spec-media.tsv e spec-album.tsv, scritta dal test TripFixtureExportTest di core-albums: la fonte e' il
codice Kotlin, mai questo file) e scrive, per ogni foto, un JPEG con l'EXIF e il GPS veri: data e ora locali dello scatto (DateTimeOriginal), il fuso
(OffsetTimeOriginal), la posizione (GPS con riferimenti N/S ed E/W) e la marca. Fusi orari, giorni senza GPS, screenshot, tablet e familiari ci sono
tutti, come nelle storie. Tutti i luoghi sono citta' pubbliche; nessun dato personale. Solo la libreria standard di Python (nessun Pillow).

Le immagini sono piccole (96x72, o 56x120 per gli screenshot) e fatte di blocchi 8x8 di tinta piatta: il colore dice il giorno, il disegno di ogni
foto e' diverso (sono tutti file con un'impronta diversa). Un video della storia e' disegnato come foto: la classificazione degli album non guarda il
tipo, e la JVM lo prova (TripFixtureExportTest). L'uscita e' deterministica: lo stesso input da' gli stessi byte, la data di modifica dei file e'
fissa (cosi' la data si legge solo dall'EXIF).

Uscita, in <out>/MCG-viaggi/storia-NN/ (NN = la storia):
  telefono/DCIM/mcg-prova/        le foto del telefono (da caricare con `adb push` in /sdcard/DCIM/mcg-prova/)
  telefono/Pictures/mcg-prova/    gli screenshot del telefono (Screenshot_*.jpg)
  nas-tablet/Backup/tablet-di-casa/   le foto del tablet di casa (area privata del NAS di prova: variante "completo")
  nas-famiglia/Famiglia/          le foto dei familiari (area comune del NAS di prova: variante "completo")
  MANIFEST.tsv                    per ogni file: storia, numero, giorno, minuto, fuso, GPS, dispositivo, sorgente, album, SHA-256, byte
  viaggi-sintetici.tsv            gli album della storia con i loro membri (per nome di file) e i numeri attesi dalla JVM (per la prova generale
                                  dello strumento "Valutazione degli album" sul telefono)
e in <out>/MCG-viaggi/LEGGIMI.txt i comandi per caricare tutto sul telefono di prova.

Uso:
  python generate_trips.py --spec <spec-media.tsv> [--albums <spec-album.tsv>] [--attesi <attesi.tsv>] [--out <cartella>]
                           [--storia N ...] [--solo-manifesto]
  python generate_trips.py --verifica        le prove del generatore sul solo Python (campione/, EXIF riletto, determinismo)
"""
import argparse
import colorsys
import datetime
import hashlib
import os
import struct
import sys
import tempfile

MAKE = "MCG Prova"
MASK64 = (1 << 64) - 1
FIXED_MTIME = 1767225600  # 2026-01-01T00:00:00Z: la data di un file non e' mai una fonte della data dello scatto

PHOTO_W, PHOTO_H = 96, 72
SHOT_W, SHOT_H = 56, 120


# --- Un generatore di numeri casuali piccolo e uguale ovunque (SplitMix64) ----------------------------------------------------------

class Rng:
    def __init__(self, seed):
        self.s = seed & MASK64

    def next(self):
        self.s = (self.s + 0x9E3779B97F4A7C15) & MASK64
        z = self.s
        z = ((z ^ (z >> 30)) * 0xBF58476D1CE4E5B9) & MASK64
        z = ((z ^ (z >> 27)) * 0x94D049BB133111EB) & MASK64
        return z ^ (z >> 31)

    def unit(self):
        return (self.next() >> 11) / float(1 << 53)


# --- JPEG: blocchi 8x8 di tinta piatta, solo il coefficiente DC ---------------------------------------------------------------------
# Quantizzazione tutta a 8: il DC quantizzato e' esattamente (valore - 128). Ogni blocco finisce subito con EOB (nessun coefficiente AC).

DC_BITS = [0, 1, 5, 1, 1, 1, 1, 1, 1, 0, 0, 0, 0, 0, 0, 0]  # tabella DC standard (Annex K)
DC_VALUES = list(range(12))
AC_BITS = [1] + [0] * 15  # un solo simbolo, EOB (0x00), con il codice "0"
AC_VALUES = [0x00]


def huffman_codes(bits, values):
    codes = {}
    code = 0
    k = 0
    for length in range(1, 17):
        for _ in range(bits[length - 1]):
            codes[values[k]] = (code, length)
            code += 1
            k += 1
        code <<= 1
    return codes


DC_CODES = huffman_codes(DC_BITS, DC_VALUES)


class BitWriter:
    def __init__(self):
        self.out = bytearray()
        self.acc = 0
        self.n = 0

    def put(self, value, length):
        self.acc = (self.acc << length) | (value & ((1 << length) - 1))
        self.n += length
        while self.n >= 8:
            self.n -= 8
            byte = (self.acc >> self.n) & 0xFF
            self.out.append(byte)
            if byte == 0xFF:
                self.out.append(0x00)
        self.acc &= (1 << self.n) - 1

    def finish(self):
        if self.n:
            self.put((1 << (8 - self.n)) - 1, 8 - self.n)
        return bytes(self.out)


def rgb_to_ycc(r, g, b):
    y = 0.299 * r + 0.587 * g + 0.114 * b
    cb = 128 - 0.168736 * r - 0.331264 * g + 0.5 * b
    cr = 128 + 0.5 * r - 0.418688 * g - 0.081312 * b
    return tuple(max(0, min(255, int(round(v)))) for v in (y, cb, cr))


def encode_scan(mcus):
    w = BitWriter()
    pred = [0, 0, 0]
    for mcu in mcus:
        for c in range(3):
            dc = mcu[c] - 128
            diff = dc - pred[c]
            pred[c] = dc
            size = abs(diff).bit_length()
            code, length = DC_CODES[size]
            w.put(code, length)
            if size:
                w.put(diff if diff > 0 else (diff - 1), size)
            w.put(0, 1)  # EOB
    return w.finish()


def segment(marker, payload):
    return bytes([0xFF, marker]) + struct.pack(">H", len(payload) + 2) + payload


def jpeg_bytes(width, height, mcus, exif_app1):
    dqt = segment(0xDB, bytes([0x00]) + bytes([8] * 64))
    sof = segment(0xC0, struct.pack(">BHHB", 8, height, width, 3) + b"".join(bytes([i, 0x11, 0]) for i in (1, 2, 3)))
    dht = segment(
        0xC4,
        bytes([0x00]) + bytes(DC_BITS) + bytes(DC_VALUES) + bytes([0x10]) + bytes(AC_BITS) + bytes(AC_VALUES),
    )
    sos = segment(0xDA, bytes([3]) + b"".join(bytes([i, 0x00]) for i in (1, 2, 3)) + bytes([0, 63, 0]))
    return b"\xff\xd8" + exif_app1 + dqt + sof + dht + sos + encode_scan(mcus) + b"\xff\xd9"


# --- EXIF (TIFF little-endian: IFD0, IFD Exif, IFD GPS) ------------------------------------------------------------------------------

ASCII, BYTE, SHORT, LONG, RATIONAL = 2, 1, 3, 4, 5


def e_ascii(tag, text):
    return (tag, ASCII, len(text) + 1, text.encode("ascii") + b"\0")


def e_short(tag, v):
    return (tag, SHORT, 1, struct.pack("<H", v))


def e_long(tag, v):
    return (tag, LONG, 1, struct.pack("<I", v))


def e_rationals(tag, pairs):
    return (tag, RATIONAL, len(pairs), b"".join(struct.pack("<II", n, d) for n, d in pairs))


def ifd(entries, start):
    """Un IFD che comincia a `start` (dall'inizio del blocco TIFF): le voci con piu' di 4 byte di valore stanno dopo la tabella."""
    entries = sorted(entries, key=lambda e: e[0])
    table = 2 + 12 * len(entries) + 4
    out = bytearray(struct.pack("<H", len(entries)))
    data = bytearray()
    for tag, typ, count, value in entries:
        if len(value) <= 4:
            out += struct.pack("<HHI", tag, typ, count) + value.ljust(4, b"\0")
        else:
            out += struct.pack("<HHII", tag, typ, count, start + table + len(data))
            data += value
            if len(data) % 2:
                data += b"\0"
    out += struct.pack("<I", 0)
    return bytes(out + data)


def dms(value_micro):
    """Gradi, primi e secondi come razionali, esatti, da un valore in milionesimi di grado (la specifica ha sei decimali)."""
    arcsec = value_micro * 36  # in decimillesimi di secondo d'arco
    deg, rest = divmod(arcsec, 3600 * 10000)
    minutes, sec = divmod(rest, 60 * 10000)
    return [(deg, 1), (minutes, 1), (sec, 10000)]


def offset_text(zone):
    sign = "+" if zone >= 0 else "-"
    return "%s%02d:%02d" % (sign, abs(zone) // 60, abs(zone) % 60)


def exif_app1(row, width, height):
    local = datetime.datetime.combine(row["day"], datetime.time(row["minute"] // 60, row["minute"] % 60))
    utc = local - datetime.timedelta(minutes=row["zone"])
    stamp = local.strftime("%Y:%m:%d %H:%M:%S")
    gps = row["lat"] is not None and row["lon"] is not None
    model = {"telefono": "Telefono di prova", "tablet": "Tablet di prova"}.get(row["device"], "Fotocamera di prova")
    ifd0 = [e_ascii(0x010F, MAKE), e_ascii(0x0110, model), e_short(0x0112, 1), e_ascii(0x0132, stamp), e_long(0x8769, 0)]
    if gps:
        ifd0.append(e_long(0x8825, 0))
    exif = [
        e_ascii(0x9003, stamp), e_ascii(0x9004, stamp), e_ascii(0x9011, offset_text(row["zone"])), e_ascii(0x9012, offset_text(row["zone"])),
        e_long(0xA002, width), e_long(0xA003, height),
    ]
    size0 = len(ifd(ifd0, 8))
    exif_at = 8 + size0
    sizex = len(ifd(exif, exif_at))
    gps_at = exif_at + sizex
    ifd0 = [e_long(0x8769, exif_at) if e[0] == 0x8769 else (e_long(0x8825, gps_at) if e[0] == 0x8825 else e) for e in ifd0]
    tiff = b"II*\0" + struct.pack("<I", 8) + ifd(ifd0, 8) + ifd(exif, exif_at)
    if gps:
        lat, lon = int(round(row["lat"] * 1_000_000)), int(round(row["lon"] * 1_000_000))
        entries = [
            (0x0000, BYTE, 4, bytes([2, 3, 0, 0])),
            e_ascii(0x0001, "N" if lat >= 0 else "S"), e_rationals(0x0002, dms(abs(lat))),
            e_ascii(0x0003, "E" if lon >= 0 else "W"), e_rationals(0x0004, dms(abs(lon))),
            e_rationals(0x0007, [(utc.hour, 1), (utc.minute, 1), (utc.second, 1)]),
            e_ascii(0x001D, utc.strftime("%Y:%m:%d")),
        ]
        tiff += ifd(entries, gps_at)
    return segment(0xE1, b"Exif\0\0" + tiff)


# --- Il disegno: la tinta dice il giorno, ogni foto e' diversa --------------------------------------------------------------------

def draw(row):
    """I blocchi (Y, Cb, Cr) in ordine di lettura, la larghezza e l'altezza."""
    rng = Rng(row["story"] * 1_000_003 + row["n"])
    if row["screen"]:
        wb, hb = SHOT_W // 8, SHOT_H // 8
    else:
        wb, hb = PHOTO_W // 8, PHOTO_H // 8
    hue = ((row["day"].toordinal() * 37 + row["story"] * 53) % 360) / 360.0
    night = row["minute"] < 6 * 60 or row["minute"] >= 21 * 60
    base = 0.38 if night else 0.88
    mcus = []
    for by in range(hb):
        for bx in range(wb):
            if row["screen"]:
                v = 0.16 + 0.08 * rng.unit() + (0.18 if by == 0 else 0.0) + (0.55 if rng.unit() < 0.08 else 0.0)
                r, g, b = colorsys.hsv_to_rgb((hue + 0.5) % 1.0, 0.25, min(1.0, v))
            else:
                sky = 1.12 if by < hb // 3 else 0.92
                v = min(1.0, base * sky * (0.80 + 0.20 * rng.unit()))
                r, g, b = colorsys.hsv_to_rgb((hue + (rng.unit() - 0.5) * 0.06) % 1.0, 0.50 + 0.20 * rng.unit(), v)
            mcus.append(rgb_to_ycc(r * 255, g * 255, b * 255))
    return mcus, wb * 8, hb * 8


def render(row):
    mcus, width, height = draw(row)
    return jpeg_bytes(width, height, mcus, exif_app1(row, width, height))


# --- Nomi dei file e cartelle ---------------------------------------------------------------------------------------------------

def file_name(row):
    stamp = "%s_%02d%02d00" % (row["day"].strftime("%Y%m%d"), row["minute"] // 60, row["minute"] % 60)
    tail = "s%02dn%05d" % (row["story"], row["n"])
    if row["screen"]:
        return "Screenshot_%s_%s.jpg" % (stamp.replace("_", "-"), tail)
    return "IMG_%s_%s.jpg" % (stamp, tail)


def folder(row):
    """La cartella (relativa alla storia) dove sta il file: il telefono, il tablet nell'area privata, i familiari nell'area comune."""
    if row["source"] == "family":
        return os.path.join("nas-famiglia", "Famiglia")
    if row["device"] == "tablet":
        return os.path.join("nas-tablet", "Backup", "tablet-di-casa")
    if row["screen"]:
        return os.path.join("telefono", "Pictures", "mcg-prova")
    return os.path.join("telefono", "DCIM", "mcg-prova")


# --- La specifica e i numeri attesi ---------------------------------------------------------------------------------------------

def read_spec(path):
    rows = []
    with open(path, encoding="utf-8", newline="") as f:
        for line in f.read().split("\n"):
            line = line.rstrip("\r")
            if not line or line.startswith("#"):
                continue
            c = line.split("\t")
            if len(c) != 13:
                raise SystemExit("Riga della specifica non valida (%d colonne invece di 13)." % len(c))
            rows.append({
                "story": int(c[0]), "n": int(c[1]), "day": datetime.date.fromisoformat(c[2]), "minute": int(c[3]), "zone": int(c[4]),
                "lat": None if c[5] == "-" else float(c[5]), "lon": None if c[6] == "-" else float(c[6]), "place": int(c[7]),
                "device": c[8], "source": c[9], "screen": c[10] == "1", "video": c[11] == "1",
                "albums": [] if c[12] == "-" else [int(x) for x in c[12].split(",")],
            })
    return rows


def read_albums(path):
    albums = {}
    if path and os.path.isfile(path):
        with open(path, encoding="utf-8", newline="") as f:
            for line in f.read().split("\n"):
                line = line.rstrip("\r")
                if line and not line.startswith("#"):
                    c = line.split("\t")
                    albums[(int(c[0]), int(c[1]))] = {"title": c[2], "kind": c[3], "trips": int(c[4]), "photos": int(c[5])}
    return albums


def read_expected(path):
    """(storia, album, variante) -> (foto_viaggio, sicuri, giusti, suggeriti, coperte); le righe con `*` sono i totali e non servono qui."""
    expected = {}
    if path and os.path.isfile(path):
        with open(path, encoding="utf-8", newline="") as f:
            for line in f.read().split("\n"):
                line = line.rstrip("\r")
                if line and not line.startswith("#"):
                    c = line.split("\t")
                    if c[0] != "*" and c[1] != "*":
                        expected[(int(c[0]), int(c[1]), c[4])] = tuple(c[5:10])
    return expected


# --- L'uscita -------------------------------------------------------------------------------------------------------------------

def write_story(root, story, rows, albums, expected, only_manifest):
    base = os.path.join(root, "storia-%02d" % story)
    manifest = ["#file\tstoria\tn\tgiorno\tminuto\tfuso\tlat\tlon\tdispositivo\tsorgente\tschermo\talbum\tsha256\tbyte"]
    names = {}
    for row in rows:
        name = file_name(row)
        rel = os.path.join(folder(row), name)
        data = render(row)
        names[row["n"]] = name
        if not only_manifest:
            path = os.path.join(base, rel)
            os.makedirs(os.path.dirname(path), exist_ok=True)
            with open(path, "wb") as f:
                f.write(data)
            os.utime(path, (FIXED_MTIME, FIXED_MTIME))
        manifest.append("\t".join([
            rel.replace(os.sep, "/"), str(story), str(row["n"]), row["day"].isoformat(), str(row["minute"]), str(row["zone"]),
            "-" if row["lat"] is None else "%.6f" % row["lat"], "-" if row["lon"] is None else "%.6f" % row["lon"],
            row["device"], row["source"], "1" if row["screen"] else "0", ",".join(map(str, row["albums"])) or "-",
            hashlib.sha256(data).hexdigest(), str(len(data)),
        ]))
    os.makedirs(base, exist_ok=True)
    with open(os.path.join(base, "MANIFEST.tsv"), "w", encoding="utf-8", newline="\n") as f:
        f.write("\n".join(manifest) + "\n")
    write_import(base, story, rows, albums, expected, names)
    return len(rows)


def write_import(base, story, rows, albums, expected, names):
    """Il file per la prova generale dello strumento sul telefono: gli album della storia, i loro membri per nome e i numeri attesi dalla JVM."""
    lines = ["#MCGSINT1\tstoria=%d" % story]
    indexes = sorted({a for row in rows for a in row["albums"]})
    for index in indexes:
        members = [r for r in rows if index in r["albums"]]
        title = albums.get((story, index), {}).get("title", "Album %d" % index)
        first_day = min(r["day"] for r in members)
        last_day = min(max(r["day"] for r in members), first_day + datetime.timedelta(days=365))
        numbers = []
        for variant in ("telefono", "completo"):
            numbers += list(expected.get((story, index, variant), ("-",) * 5))
        lines.append("\t".join(["A", str(index), title, first_day.isoformat(), last_day.isoformat()] + numbers))
        for r in members:
            lines.append("\t".join(["M", str(index), names[r["n"]]]))
    with open(os.path.join(base, "viaggi-sintetici.tsv"), "w", encoding="utf-8", newline="\n") as f:
        f.write("\n".join(lines) + "\n")


LEGGIMI = """VIAGGI SINTETICI (Fase 7, P12) - come caricarli sul telefono di prova
=====================================================================
Foto finte con EXIF e GPS veri (luoghi pubblici, nessun dato personale), generate da tools/fixtures/trips/generate_trips.py dalla specifica delle
20 storie di P6. NON COMMITTARE questa cartella: e' uscita del generatore (la regola "out/" del .gitignore la copre).

UNA STORIA PER VOLTA. Le 20 storie coprono gli stessi due anni: nella stessa Libreria si mescolerebbero (l'album "Oslo 2024" prenderebbe le foto
di Oslo di tutte le storie) e i numeri non sarebbero piu' quelli della JVM. Prima di caricare una storia si toglie la precedente.

Sul telefono di prova (build DEBUG, account di prova; solo le due cartelle mcg-prova, mai altre):

  # 1. si toglie quello che c'era (solo mcg-prova)
  adb -s <seriale> shell rm -rf /sdcard/DCIM/mcg-prova /sdcard/Pictures/mcg-prova
  # 2. si carica la storia NN (le foto del telefono e gli screenshot)
  adb -s <seriale> push storia-NN/telefono/DCIM/mcg-prova /sdcard/DCIM/
  adb -s <seriale> push storia-NN/telefono/Pictures/mcg-prova /sdcard/Pictures/
  # 3. si chiede la scansione del MediaStore (come tools\\chaos\\s25.ps1)
  adb -s <seriale> shell content call --uri content://media/ --method scan_volume --arg external_primary
  # 4. l'app: aspettare che la Libreria mostri le foto (la scansione e l'indice partono da soli)

Il file per la prova generale dello strumento "Valutazione degli album" (Impostazioni > Sviluppo) dentro la cartella dell'app di debug:

  adb -s <seriale> push storia-NN/viaggi-sintetici.tsv /data/local/tmp/viaggi-sintetici.tsv
  adb -s <seriale> shell chmod a+r /data/local/tmp/viaggi-sintetici.tsv
  adb -s <seriale> shell run-as dev.pampa.mycloudgallery.debug mkdir -p no_backup/eval/albums/import
  adb -s <seriale> shell run-as dev.pampa.mycloudgallery.debug cp /data/local/tmp/viaggi-sintetici.tsv no_backup/eval/albums/import/
  adb -s <seriale> shell rm /data/local/tmp/viaggi-sintetici.tsv

DUE VARIANTI DEI NUMERI ATTESI (attesi.tsv, dalla JVM, sullo stesso testo della specifica):
  telefono   solo cio' che sta in mcg-prova (foto del telefono e screenshot): la strada piu' corta.
  completo   anche il tablet (nas-tablet/Backup/tablet-di-casa/, area privata del NAS di prova) e i familiari (nas-famiglia/Famiglia/, area comune):
             come SmartQualityTest. Questa parte passa dal NAS di prova (dal PC, con gli account di prova) e NON e' stata provata qui.
I video delle storie sono disegnati come foto. I nomi dei luoghi li da' il dataset vero dell'app: i numeri sul telefono possono scostarsi di qualche
decimo di punto da quelli della JVM (che usa i nomi finti della storia); le soglie del progetto sono precisione 99% e copertura 95%.
"""


def write_leggimi(root):
    with open(os.path.join(root, "LEGGIMI.txt"), "w", encoding="utf-8", newline="\n") as f:
        f.write(LEGGIMI)


def generate(spec_path, albums_path, expected_path, out, stories, only_manifest):
    rows = read_spec(spec_path)
    albums = read_albums(albums_path)
    expected = read_expected(expected_path)
    root = os.path.join(out, "MCG-viaggi")
    os.makedirs(root, exist_ok=True)
    wanted = sorted({r["story"] for r in rows}) if not stories else sorted(stories)
    total = 0
    for story in wanted:
        mine = [r for r in rows if r["story"] == story]
        if not mine:
            raise SystemExit("La storia %d non c'e' nella specifica." % story)
        total += write_story(root, story, mine, albums, expected, only_manifest)
        print("storia %02d: %d file" % (story, len(mine)))
    write_leggimi(root)
    print("%d file in %s" % (total, root))
    return root


# --- Le prove del generatore, sul solo Python -----------------------------------------------------------------------------------

def read_exif(data):
    """Rilegge l'EXIF che scrive questo generatore (TIFF little-endian): data, fuso, marca, modello e GPS. Solo per le prove."""
    if data[:2] != b"\xff\xd8" or data[2:4] != b"\xff\xe1":
        raise ValueError("manca l'APP1 subito dopo l'SOI")
    length = struct.unpack(">H", data[4:6])[0]
    block = data[6:4 + length]
    if block[:6] != b"Exif\0\0":
        raise ValueError("manca l'intestazione Exif")
    tiff = block[6:]
    if tiff[:4] != b"II*\0":
        raise ValueError("TIFF non little-endian")

    def read_ifd(offset):
        count = struct.unpack_from("<H", tiff, offset)[0]
        out = {}
        for i in range(count):
            tag, typ, n, raw = struct.unpack_from("<HHI4s", tiff, offset + 2 + 12 * i)
            size = {ASCII: 1, BYTE: 1, SHORT: 2, LONG: 4, RATIONAL: 8}[typ] * n
            value = raw if size <= 4 else tiff[struct.unpack("<I", raw)[0]:struct.unpack("<I", raw)[0] + size]
            out[tag] = (typ, n, bytes(value[:size]))
        return out

    def text(entry):
        return entry[2].rstrip(b"\0").decode("ascii")

    def rationals(entry):
        return [struct.unpack_from("<II", entry[2], 8 * i) for i in range(entry[1])]

    ifd0 = read_ifd(struct.unpack_from("<I", tiff, 4)[0])
    exif = read_ifd(struct.unpack("<I", ifd0[0x8769][2])[0])
    info = {
        "make": text(ifd0[0x010F]), "model": text(ifd0[0x0110]), "local": text(exif[0x9003]), "offset": text(exif[0x9011]),
        "width": struct.unpack("<I", exif[0xA002][2])[0], "height": struct.unpack("<I", exif[0xA003][2])[0], "lat": None, "lon": None,
    }
    if 0x8825 in ifd0:
        gps = read_ifd(struct.unpack("<I", ifd0[0x8825][2])[0])

        def degrees(entry, ref):
            (d, _), (m, _), (s, sd) = rationals(entry)
            value = d + m / 60.0 + (s / sd) / 3600.0
            return -value if text(ref) in ("S", "W") else value

        info["lat"] = degrees(gps[0x0002], gps[0x0001])
        info["lon"] = degrees(gps[0x0004], gps[0x0003])
    return info


def check_structure(data):
    if data[:2] != b"\xff\xd8" or data[-2:] != b"\xff\xd9":
        raise ValueError("SOI o EOI mancante")
    markers = []
    i = 2
    while i < len(data) - 2:
        if data[i] != 0xFF:
            raise ValueError("marcatore atteso a %d" % i)
        marker = data[i + 1]
        markers.append(marker)
        if marker == 0xDA:
            break
        i += 2 + struct.unpack(">H", data[i + 2:i + 4])[0]
    if markers != [0xE1, 0xDB, 0xC0, 0xC4, 0xDA]:
        raise ValueError("segmenti inattesi: %s" % markers)


def verifica():
    here = os.path.dirname(os.path.abspath(__file__))
    sample = os.path.join(here, "campione")
    spec = os.path.join(sample, "spec-campione.tsv")
    rows = read_spec(spec)
    problems = []
    with tempfile.TemporaryDirectory() as first, tempfile.TemporaryDirectory() as second:
        generate(spec, None, None, first, None, False)
        generate(spec, None, None, second, None, False)
        a = os.path.join(first, "MCG-viaggi", "storia-99")
        b = os.path.join(second, "MCG-viaggi", "storia-99")
        committed = os.path.join(sample, "storia-99")
        listed = sorted(os.path.relpath(os.path.join(d, f), a) for d, _, fs in os.walk(a) for f in fs)
        if len(listed) != 2 + len(rows):
            problems.append("attesi %d file (le foto, MANIFEST.tsv e viaggi-sintetici.tsv), trovati %d" % (2 + len(rows), len(listed)))
        for rel in listed:
            with open(os.path.join(a, rel), "rb") as f1, open(os.path.join(b, rel), "rb") as f2:
                data = f1.read()
                if data != f2.read():
                    problems.append("non deterministico: " + rel)
            path = os.path.join(committed, rel)
            if not os.path.isfile(path):
                problems.append("manca nel campione: " + rel)
            else:
                with open(path, "rb") as committed_file:
                    expected_bytes = committed_file.read()
                if rel.endswith(".tsv"):  # i file di testo possono avere le righe a capo del sistema; i JPEG no, mai
                    expected_bytes = expected_bytes.replace(b"\r\n", b"\n")
                if expected_bytes != data:
                    problems.append("diverso dal campione: " + rel)
            if rel.endswith(".jpg"):
                check_structure(data)
        by_name = {file_name(r): r for r in rows}
        for rel in listed:
            name = os.path.basename(rel)
            if name not in by_name:
                continue
            r = by_name[name]
            info = read_exif(open(os.path.join(a, rel), "rb").read())
            local = datetime.datetime.combine(r["day"], datetime.time(r["minute"] // 60, r["minute"] % 60)).strftime("%Y:%m:%d %H:%M:%S")
            if info["local"] != local or info["offset"] != offset_text(r["zone"]) or info["make"] != MAKE:
                problems.append("data, fuso o marca sbagliati: " + name)
            if (r["lat"] is None) != (info["lat"] is None):
                problems.append("GPS presente o assente a torto: " + name)
            elif r["lat"] is not None and (abs(info["lat"] - r["lat"]) > 1e-9 or abs(info["lon"] - r["lon"]) > 1e-9):
                problems.append("GPS diverso: " + name)
    if problems:
        for p in problems:
            print("FALLITA: " + p)
        return 1
    print("verifica del generatore: %d file del campione, EXIF riletto, uscita deterministica e uguale a campione/: tutto a posto" % len(rows))
    return 0


def main(argv):
    here = os.path.dirname(os.path.abspath(__file__))
    parser = argparse.ArgumentParser(description="Genera i viaggi sintetici (JPEG con EXIF e GPS) dalla specifica delle storie di P6.")
    parser.add_argument("--spec", help="spec-media.tsv (scritta dal test TripFixtureExportTest)")
    parser.add_argument("--albums", help="spec-album.tsv (titoli e generi degli album)")
    parser.add_argument("--attesi", help="attesi.tsv (i numeri della JVM)")
    parser.add_argument("--out", default=os.path.normpath(os.path.join(here, "..", "out")), help="cartella di uscita (default tools/fixtures/out)")
    parser.add_argument("--storia", type=int, nargs="*", help="solo queste storie (0-19); di default tutte")
    parser.add_argument("--solo-manifesto", action="store_true", help="scrive i manifesti e i file per lo strumento, senza i JPEG")
    parser.add_argument("--verifica", action="store_true", help="le prove del generatore sul campione")
    args = parser.parse_args(argv)
    if args.verifica:
        return verifica()
    if not args.spec:
        parser.error("serve --spec (o --verifica)")
    generate(args.spec, args.albums, args.attesi, args.out, args.storia, args.solo_manifesto)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
