"""
Contenuto di prova "caso peggiore" per la revisione grafica G1 della Libreria (solo per l'EMULATORE).

Genera JPEG con data di scatto (EXIF DateTimeOriginal) e, per alcuni, coordinate GPS, raggruppati in giorni a tema:
bianco, nero, testo nero su bianco (screenshot), testo bianco su nero, colori saturi, righe sottili, misto. Cosi',
scorrendo, sotto la chrome in alto e in basso passano file intere dello stesso tipo: e' li' che il vetro e il testo si
mettono alla prova.

Uso (dalla radice del progetto):
    python -I docs/screenshots/G1/strumenti/genera-media.py <cartella-di-uscita>
poi `adb -s emulator-5554 push <cartella>/. /sdcard/Pictures/G1-prova` e la scansione del MediaStore
(`adb shell content call --uri content://media/ --method scan_volume --arg external_primary`).
Mai su un telefono vero: sono immagini finte, ma il loro posto e' un emulatore.
"""

import random
import sys
from datetime import datetime, timedelta
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont, TiffImagePlugin

OUT = Path(sys.argv[1] if len(sys.argv) > 1 else "g1-media")
OUT.mkdir(parents=True, exist_ok=True)
rng = random.Random(1310)

PLACES = {
    "Milano": (45.4642, 9.1900),
    "Firenze": (43.7696, 11.2558),
    "Oslo": (59.9139, 10.7522),
    "Napoli": (40.8518, 14.2681),
    "Torino": (45.0703, 7.6869),
}

WORDS = (
    "la libreria mostra le foto del telefono e del nas in una sola griglia ordinata per giorno mese e anno "
    "ogni intestazione dice la data e il luogo e la barra in alto deve restare leggibile sopra qualsiasi "
    "contenuto anche quando sotto passa una pagina piena di testo nero su bianco o di testo bianco su nero "
    "il vetro sfoca rifrange e tinge ma il contrasto lo decide la pellicola e il colore delle scritte"
).split()


def font(size):
    try:
        return ImageFont.truetype("arial.ttf", size)
    except OSError:
        return ImageFont.load_default(size=size)


def rational(value):
    return TiffImagePlugin.IFDRational(value)


def dms(value):
    value = abs(value)
    d = int(value)
    m = int((value - d) * 60)
    s = round(((value - d) * 60 - m) * 60, 2)
    return (rational(d), rational(m), rational(s))


def save(img, name, when, place=None):
    exif = Image.Exif()
    stamp = when.strftime("%Y:%m:%d %H:%M:%S")
    exif[0x0132] = stamp
    exif[0x010F] = "G1"
    exif[0x0110] = "Prova grafica"
    ifd = exif.get_ifd(0x8769)
    ifd[0x9003] = stamp
    ifd[0x9004] = stamp
    if place is not None:
        lat, lon = PLACES[place]
        gps = exif.get_ifd(0x8825)
        gps[0] = b"\x02\x02\x00\x00"
        gps[1] = "N" if lat >= 0 else "S"
        gps[2] = dms(lat)
        gps[3] = "E" if lon >= 0 else "W"
        gps[4] = dms(lon)
    img.convert("RGB").save(OUT / name, "JPEG", quality=88, exif=exif.tobytes())


def flat(color, size=(1200, 1200)):
    return Image.new("RGB", size, color)


def soft(color_a, color_b, size=(1200, 1200)):
    w, h = size
    img = Image.new("RGB", size)
    px = img.load()
    for y in range(h):
        t = y / (h - 1)
        c = tuple(int(color_a[i] + (color_b[i] - color_a[i]) * t) for i in range(3))
        for x in range(w):
            px[x, y] = c
    return img


def text_page(dark, size=(1080, 2400)):
    bg, ink = ((0, 0, 0), (255, 255, 255)) if dark else ((255, 255, 255), (0, 0, 0))
    img = Image.new("RGB", size, bg)
    draw = ImageDraw.Draw(img)
    f = font(46)
    y = 40
    while y < size[1] - 60:
        line = " ".join(rng.choice(WORDS) for _ in range(rng.randint(4, 7)))
        draw.text((48, y), line, fill=ink, font=f)
        y += 64
    return img


def stripes(size=(1200, 1200), period=6):
    img = Image.new("RGB", size, (255, 255, 255))
    draw = ImageDraw.Draw(img)
    for y in range(0, size[1], period):
        draw.rectangle([0, y, size[0], y + period // 2 - 1], fill=(0, 0, 0))
    return img


def snow():
    img = soft((255, 255, 255), (238, 241, 245))
    draw = ImageDraw.Draw(img)
    for _ in range(40):
        x, y = rng.randint(0, 1200), rng.randint(0, 1200)
        draw.ellipse([x, y, x + 30, y + 30], fill=(250, 250, 252))
    return img


def night():
    img = flat((6, 6, 10))
    draw = ImageDraw.Draw(img)
    for _ in range(25):
        x, y = rng.randint(0, 1200), rng.randint(600, 1200)
        draw.ellipse([x, y, x + 8, y + 8], fill=(255, 220, 140))
    return img


SATURATED = [(255, 0, 0), (0, 0, 255), (0, 200, 0), (255, 0, 255), (255, 230, 0), (0, 230, 255), (255, 120, 0), (120, 0, 255)]


def make(theme, i):
    if theme == "bianco":
        return [flat((255, 255, 255)), snow(), flat((250, 250, 250)), soft((255, 255, 255), (232, 236, 240))][i % 4]
    if theme == "nero":
        return [flat((0, 0, 0)), night(), flat((10, 10, 12)), soft((0, 0, 0), (24, 24, 30))][i % 4]
    if theme == "testo":
        return text_page(dark=False)
    if theme == "testo-scuro":
        return text_page(dark=True)
    if theme == "saturi":
        return flat(SATURATED[i % len(SATURATED)])
    if theme == "righe":
        return stripes(period=[4, 6, 10][i % 3])
    # misto: un po' di tutto
    pick = i % 6
    return [flat((255, 255, 255)), flat((0, 0, 0)), flat(SATURATED[(i * 3) % len(SATURATED)]), text_page(dark=i % 2 == 0), stripes(), soft((40, 120, 200), (250, 200, 80))][pick]


# Giorni (dal piu' recente): un tema per giorno, con quanti elementi e il luogo.
DAYS = [
    ("misto", 7, "Milano"),
    ("bianco", 8, "Oslo"),
    ("nero", 8, "Oslo"),
    ("testo", 8, None),
    ("saturi", 8, "Napoli"),
    ("righe", 8, "Torino"),
    ("testo-scuro", 8, None),
    ("bianco", 12, "Firenze"),
    ("nero", 12, "Firenze"),
    ("saturi", 12, "Milano"),
    ("misto", 9, "Milano"),
    ("testo", 12, None),
    ("bianco", 8, "Milano"),
    ("saturi", 8, "Torino"),
    ("nero", 8, "Napoli"),
    ("misto", 10, "Firenze"),
    ("righe", 8, "Oslo"),
    ("bianco", 8, "Napoli"),
    ("testo-scuro", 8, None),
    ("saturi", 8, "Firenze"),
]

start = datetime(2026, 10, 13, 18, 0, 0)
count = 0
for d, (theme, n, place) in enumerate(DAYS):
    # Tre giorni di stacco ogni tanto: mesi diversi per i livelli Mesi e Anni.
    day = start - timedelta(days=d * 4 + (60 if d >= 14 else 0))
    for i in range(n):
        when = day - timedelta(minutes=i * 17)
        img = make(theme, i)
        name = f"G1_{when.strftime('%Y%m%d_%H%M%S')}_{theme}_{i:02d}.jpg"
        save(img, name, when, place)
        count += 1
# Un anno prima, per il livello Anni.
for i in range(8):
    when = datetime(2025, 7, 20, 11, 0, 0) - timedelta(minutes=i * 9)
    save(make(["bianco", "nero", "saturi", "testo"][i % 4], i), f"G1_{when.strftime('%Y%m%d_%H%M%S')}_anno_{i:02d}.jpg", when, "Firenze")
    count += 1
print(f"{count} immagini in {OUT}")
