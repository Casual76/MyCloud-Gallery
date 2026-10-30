"""Prove degli strumenti della mappa (Fase 9, P2), senza rete ne' scaricamenti.

    python -I tools/maps/selftest.py

Le prove sui pezzi piccoli (Douglas-Peucker, numeri, PNG, contrasto, glifi vuoti) girano sempre; quelle che ricostruiscono tutto
(riproducibilita': due corse, stessi byte e stesse impronte di quelle committate) girano se i file scaricati sono in `build/download/`.
"""
import os
import struct
import sys
import unittest

AQUI = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, AQUI)
sys.path.insert(0, os.path.join(AQUI, "base"))
sys.path.insert(0, os.path.join(AQUI, "style"))
import mapscommon as mc  # noqa: E402


class Piccoli(unittest.TestCase):
    def test_douglas_peucker_tiene_gli_estremi_e_toglie_i_punti_allineati(self):
        linea = [(0.0, 0.0), (1.0, 0.0001), (2.0, 0.0), (3.0, 0.0)]
        self.assertEqual(mc.douglas_peucker(linea, 0.01), [(0.0, 0.0), (3.0, 0.0)])

    def test_douglas_peucker_tiene_cio_che_supera_la_tolleranza(self):
        linea = [(0.0, 0.0), (1.0, 0.5), (2.0, 0.0)]
        self.assertEqual(mc.douglas_peucker(linea, 0.1), linea)
        self.assertEqual(mc.douglas_peucker(linea, 0.6), [(0.0, 0.0), (2.0, 0.0)])

    def test_douglas_peucker_e_deterministico_e_non_ricorsivo(self):
        linea = [(i * 0.001, (i % 7) * 0.0005) for i in range(20000)]
        a = mc.douglas_peucker(linea, 0.0007)
        self.assertEqual(a, mc.douglas_peucker(list(linea), 0.0007))
        self.assertGreater(len(a), 2)

    def test_numeri_a_cinque_decimali_senza_zeri_inutili_e_senza_meno_zero(self):
        self.assertEqual(mc.num(12.5), "12.5")
        self.assertEqual(mc.num(-0.000001), "0")
        self.assertEqual(mc.num(1.123456789), "1.12346")
        self.assertEqual(mc.num(3.0), "3")

    def test_arrotonda_toglie_i_punti_uguali(self):
        self.assertEqual(mc.arrotonda([(1.000001, 2.0), (1.000002, 2.0), (3.0, 4.0)]), [(1.0, 2.0), (3.0, 4.0)])

    def test_contrasto_wcag(self):
        self.assertAlmostEqual(mc.contrasto("#000000", "#FFFFFF"), 21.0, places=1)
        self.assertAlmostEqual(mc.contrasto("#777777", "#FFFFFF"), 4.48, places=1)
        self.assertEqual(mc.contrasto("#123456", "#123456"), 1.0)

    def test_png_scrittura_e_lettura(self):
        import png
        righe = [bytearray(bytes((x * 3 + y) & 255 for x in range(12))) for y in range(5)]
        dati = png.scrivi(3, 5, righe)
        self.assertEqual(png.scrivi(3, 5, righe), dati)
        larghezza, altezza, letto = png.leggi(dati)
        self.assertEqual((larghezza, altezza), (3, 5))
        self.assertEqual([bytes(r) for r in letto], [bytes(r) for r in righe])
        self.assertEqual(png.bianco([bytearray([10, 20, 30, 77])])[0], bytearray([255, 255, 255, 77]))

    def test_il_file_di_glifi_vuoto_e_un_messaggio_protobuf_valido(self):
        import build_style
        dati = build_style._vuoto("NotoSans-Regular", "512-767")
        self.assertEqual(dati[0], 0x0A)
        interno = dati[2:2 + dati[1]]
        self.assertEqual(len(dati), 2 + len(interno))
        self.assertEqual(interno[0], 0x0A)
        nome = interno[2:2 + interno[1]]
        resto = interno[2 + interno[1]:]
        self.assertEqual(nome, b"NotoSans-Regular")
        self.assertEqual(resto[0], 0x12)
        self.assertEqual(resto[2:2 + resto[1]], b"512-767")

    def test_la_tavolozza_rispetta_il_contrasto_minimo(self):
        import palette
        for nome, pal in palette.TAVOLOZZE.items():
            for t in palette.TESTI:
                for s in palette.SFONDI:
                    self.assertGreaterEqual(mc.contrasto(pal[t], pal[s]), palette.CONTRASTO_MINIMO, "%s %s su %s" % (nome, t, s))

    def test_i_nomi_delle_regioni_sono_venti_e_senza_casa(self):
        import topologia
        self.assertEqual(len(topologia.REGIONI), 20)
        self.assertFalse({n.lower() for n in topologia.REGIONI.values()} & {"casa", "home"})


def _ci_sono_i_download():
    return os.path.exists(os.path.join(mc.DOWNLOAD, "ne-5.1.2-ne_50m_land")) and os.path.exists(
        os.path.join(mc.DOWNLOAD, "openmaptiles-fonts-2.0-noto-sans-zip"))


@unittest.skipUnless(_ci_sono_i_download(), "mancano i file di build/download (si scaricano a mano: fetch.py --scarica)")
class Riproducibilita(unittest.TestCase):
    def test_la_carta_di_base_riscritta_ha_gli_stessi_byte_e_le_impronte_committate(self):
        import build_base
        a, riepilogo = build_base.costruisci()
        b, _ = build_base.costruisci()
        self.assertEqual({n: mc.sha256_bytes(x) for n, x in a.items()}, {n: mc.sha256_bytes(x) for n, x in b.items()})
        scritto = mc.carica_json(build_base.IMPRONTE)
        self.assertEqual({n: v["sha256"] for n, v in scritto["file"].items()}, {n: mc.sha256_bytes(x) for n, x in a.items()})
        self.assertLessEqual(sum(len(x) for x in a.values()), 4_000_000)
        for nome, x in a.items():
            self.assertNotIn(b'"Casa"', x, nome)
        self.assertEqual(riepilogo["citta"], scritto["conteggi"]["citta"])

    def test_stili_sprite_glifi_e_licenze_riscritti_hanno_gli_stessi_byte(self):
        import build_style
        a = build_style.costruisci()
        b = build_style.costruisci()
        self.assertEqual({n: mc.sha256_bytes(x) for n, x in a.items()}, {n: mc.sha256_bytes(x) for n, x in b.items()})
        reg = build_style.registro(a)
        with open(build_style.IMPRONTE, "rb") as f:
            self.assertEqual(mc.json_leggibile(reg), f.read())

    def test_i_nomi_italiani_dei_luoghi_ci_sono_e_i_distretti_non_hanno_il_punto(self):
        import mcgp1
        with open(os.path.join(mc.RADICE, "core-places", "src", "main", "assets", "places", "places.mcgp1"), "rb") as f:
            nomi, distretti = mcgp1.leggi_nomi(f.read())
        self.assertEqual(len(nomi), 161650)
        self.assertEqual(len(distretti), 206)
        self.assertEqual([nomi[i] for i in (3169070, 3173435, 3176959)], ["Roma", "Milano", "Firenze"])


if __name__ == "__main__":
    unittest.main(verbosity=2)
