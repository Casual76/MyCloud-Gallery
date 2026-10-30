"""PNG minimale (RGBA a 8 bit, senza interlacciamento) in sola libreria standard: legge l'atlante degli sprite di OpenFreeMap, ne ritaglia le
icone che servono e scrive il nostro atlante.

La scrittura e' DETERMINISTICA per costruzione: righe senza filtro e flusso zlib fatto a mano con soli blocchi "stored" (nessuna
compressione), cosi' i byte non dipendono dalla versione di zlib di chi lancia lo strumento. L'atlante pesa pochi KB: non serve comprimerlo.
"""
import struct
import zlib

FIRMA = b"\x89PNG\r\n\x1a\n"


def leggi(dati):
    """Ritorna (larghezza, altezza, righe) con ogni riga come bytearray RGBA."""
    if dati[:8] != FIRMA:
        raise ValueError("non e' un PNG")
    pos = 8
    larghezza = altezza = None
    idat = bytearray()
    while pos < len(dati):
        (lung,) = struct.unpack_from(">I", dati, pos)
        tipo = dati[pos + 4:pos + 8]
        corpo = dati[pos + 8:pos + 8 + lung]
        pos += 12 + lung
        if tipo == b"IHDR":
            larghezza, altezza, profondita, colore, _comp, _filtro, interlaccio = struct.unpack(">IIBBBBB", corpo)
            if (profondita, colore, interlaccio) != (8, 6, 0):
                raise ValueError("serve un PNG RGBA a 8 bit senza interlacciamento")
        elif tipo == b"IDAT":
            idat += corpo
        elif tipo == b"IEND":
            break
    grezzo = zlib.decompress(bytes(idat))
    passo = larghezza * 4
    righe = []
    prec = bytearray(passo)
    p = 0
    for _ in range(altezza):
        filtro = grezzo[p]
        riga = bytearray(grezzo[p + 1:p + 1 + passo])
        p += 1 + passo
        if filtro == 1:
            for i in range(4, passo):
                riga[i] = (riga[i] + riga[i - 4]) & 255
        elif filtro == 2:
            for i in range(passo):
                riga[i] = (riga[i] + prec[i]) & 255
        elif filtro == 3:
            for i in range(passo):
                a = riga[i - 4] if i >= 4 else 0
                riga[i] = (riga[i] + ((a + prec[i]) >> 1)) & 255
        elif filtro == 4:
            for i in range(passo):
                a = riga[i - 4] if i >= 4 else 0
                b = prec[i]
                c = prec[i - 4] if i >= 4 else 0
                pa = abs(b - c)
                pb = abs(a - c)
                pc = abs(a + b - 2 * c)
                pred = a if (pa <= pb and pa <= pc) else (b if pb <= pc else c)
                riga[i] = (riga[i] + pred) & 255
        elif filtro != 0:
            raise ValueError("filtro PNG sconosciuto")
        righe.append(riga)
        prec = riga
    return larghezza, altezza, righe


def _adler32(dati):
    return zlib.adler32(dati) & 0xFFFFFFFF


def _zlib_stored(dati):
    out = bytearray(b"\x78\x01")
    n = len(dati)
    pos = 0
    while True:
        blocco = dati[pos:pos + 65535]
        pos += len(blocco)
        ultimo = 1 if pos >= n else 0
        out += struct.pack("<BHH", ultimo, len(blocco), len(blocco) ^ 0xFFFF)
        out += blocco
        if ultimo:
            break
    out += struct.pack(">I", _adler32(dati))
    return bytes(out)


def _chunk(tipo, corpo):
    crc = zlib.crc32(tipo + corpo) & 0xFFFFFFFF
    return struct.pack(">I", len(corpo)) + tipo + corpo + struct.pack(">I", crc)


def scrivi(larghezza, altezza, righe):
    grezzo = bytearray()
    for r in righe:
        grezzo.append(0)
        grezzo += r
    return (
        FIRMA
        + _chunk(b"IHDR", struct.pack(">IIBBBBB", larghezza, altezza, 8, 6, 0, 0, 0))
        + _chunk(b"IDAT", _zlib_stored(bytes(grezzo)))
        + _chunk(b"IEND", b"")
    )


def ritaglia(larghezza, righe, x, y, w, h):
    return [bytearray(righe[y + j][x * 4:(x + w) * 4]) for j in range(h)]


def bianco(pezzo):
    """La stessa icona con il colore tutto bianco e l'opacita' di prima (per lo stile scuro)."""
    out = []
    for riga in pezzo:
        nuova = bytearray(riga)
        for i in range(0, len(nuova), 4):
            nuova[i] = nuova[i + 1] = nuova[i + 2] = 255
        out.append(nuova)
    return out
