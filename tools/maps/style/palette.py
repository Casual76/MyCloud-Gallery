"""La tavolozza della mappa (Fase 9, P2): i colori dei due stili, tutti esadecimali a sei cifre.

L'Ametista dell'app (#9966CC in chiaro, #B88CE8 in scuro, `GalleryAccent.Amethyst`) e' il colore dell'acqua e dei confini sottili: nello stile
chiaro l'acqua e' un'ametista pallidissima e i confini sono l'ametista piena; nello scuro l'acqua e' un viola profondo e i confini
l'ametista chiara. La terra e' un grigio appena violaceo. I colori del testo si controllano contro OGNI sfondo su cui puo' cadere
(terra, acqua, boschi, parchi, ghiacci, strade) e devono dare almeno 4,5:1 (WCAG AA, testo normale): lo fa `check_style.py`, e lo rifa'
per conto suo il test `MapStyleAssetsTest` di feature-map sui file scritti.
"""

CONTRASTO_MINIMO = 4.5

CHIARO = {
    # sfondi
    "land": "#F3F0F7",
    "water": "#D8CCEC",
    "waterway": "#C3B0E2",
    "park": "#EAE6EF",
    "wood": "#E4DFEA",
    "residential": "#EDE9F1",
    "ice": "#FFFFFF",
    "aeroway": "#FFFFFF",
    "road_minor": "#E2DCEA",
    "road_casing": "#D2C9DE",
    "road_inner": "#FFFFFF",
    "road_subtle": "#DAD2E4",
    # linee (non sono testo: niente soglia)
    "coast": "#B79BDB",
    "boundary_country": "#9966CC",
    "boundary_state": "#B792DD",
    "boundary_region": "#B08ADB",
    "boundary_province": "#CDB9E8",
    "dot": "#2E2540",
    # testo
    "text_primary": "#2E2540",
    "text_secondary": "#5A4B73",
    "text_country": "#3B2D52",
    "text_water": "#4B2E7F",
    "text_road": "#5A4B73",
    "halo": "#F3F0F7",
}

SCURO = {
    "land": "#221D2B",
    "water": "#33264A",
    "waterway": "#42326A",
    "park": "#272130",
    "wood": "#2A2433",
    "residential": "#252030",
    "ice": "#3A3347",
    "aeroway": "#2F2839",
    "road_minor": "#352E40",
    "road_casing": "#2B2535",
    "road_inner": "#3F3750",
    "road_subtle": "#3A3346",
    "coast": "#6E4F9A",
    "boundary_country": "#B88CE8",
    "boundary_state": "#8F6BB8",
    "boundary_region": "#9A74C8",
    "boundary_province": "#5E4880",
    "dot": "#EDE7F5",
    "text_primary": "#EDE7F5",
    "text_secondary": "#C9BEDA",
    "text_country": "#F4EFFA",
    "text_water": "#D2BDF2",
    "text_road": "#C9BEDA",
    "halo": "#221D2B",
}

# Colori che fanno da sfondo al testo: tutti i riempimenti e le strade.
SFONDI = ("land", "water", "park", "wood", "residential", "ice", "aeroway", "road_minor", "road_inner", "road_subtle")
TESTI = ("text_primary", "text_secondary", "text_country", "text_water", "text_road")

TAVOLOZZE = {"chiaro": CHIARO, "scuro": SCURO}
