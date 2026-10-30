"""Lettura minima del dataset dei luoghi della Fase 3 (`core-places/.../places.mcgp1`, formato MCGP1, docs/luoghi.md §3).

Serve a una cosa sola: il NOME ITALIANO di ogni luogo, per id GeoNames. Il file GeoNames `cities1000` ha i nomi inglesi ("Rome",
"Naples", "Florence"), mentre il dataset dei luoghi, costruito dai nomi alternativi `it`, ha "Roma", "Napoli", "Firenze": la mappa di
un'app in italiano deve scrivere questi. Lettura da sola, senza controllare il CRC (lo controlla l'impronta SHA-256 fissata nello strumento).
"""
import struct

MAGIC = b"MCGP1\x00\x00\x00"
SIZE_PLACE = 17
FLAG_SECONDARY = 4


def leggi_nomi(dati):
    """Ritorna ({id GeoNames: nome}, {id dei distretti}) dal contenuto del file."""
    if dati[:8] != MAGIC:
        raise ValueError("non e' un file MCGP1")
    luoghi, celle = struct.unpack_from("<ii", dati, 16)
    off_cell_first, off_cell_name, off_places, off_names = struct.unpack_from("<iiii", dati, 52)
    nomi = {}
    distretti = set()
    for c in range(celle):
        primo, successivo = struct.unpack_from("<ii", dati, off_cell_first + 4 * c)
        (inizio_nome,) = struct.unpack_from("<i", dati, off_cell_name + 4 * c)
        cursore = off_names + inizio_nome
        for j in range(primo, successivo):
            rec = off_places + j * SIZE_PLACE
            (id_geonames,) = struct.unpack_from("<i", dati, rec)
            classe = dati[rec + 14]
            lunghezza = dati[rec + 16]
            nomi[id_geonames] = dati[cursore:cursore + lunghezza].decode("utf-8")
            cursore += lunghezza
            if classe & FLAG_SECONDARY:
                distretti.add(id_geonames)
    if len(nomi) != luoghi:
        raise ValueError("il numero dei luoghi letti non torna con l'intestazione")
    return nomi, distretti
