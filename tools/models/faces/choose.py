"""Applica le regole di scelta di D7 scritte PRIMA di misurare (docs/decisioni.md, «Fase 8 P1», regole 1 e 2) e scrive i numeri.

    python -I choose.py --root "C:\\...\\mcg-models\\faces" [--scrivi <file.json>]

Regola 1 (versione di YuNet): `2026may` solo se trova almeno il 5% in piu' dei volti con lato corto fra 24 e 47 px (serie B + ritratti ridotti
a 28, 36 e 44 px), con non piu' falsi positivi (serie A, B, C) e al massimo il 30% di latenza in piu'; altrimenti `2023mar`.
Regola 2 (precisione di SFace): float16, a meno che int8 dinamico abbia coseno >= 0,995 con l'ONNX fp32 su OGNI volto di prova **e** una
separazione delle coppie dei ritratti non peggiore (AUC non piu' bassa di oltre 0,005, coppie sbagliate alla soglia 0,363 non di piu').
"""
from __future__ import annotations

import argparse
import json
import statistics
import sys
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))
import common  # noqa: E402
import dati  # noqa: E402
import engines  # noqa: E402
import parity  # noqa: E402

IOU_TROVATO = 0.3
LATO_BASSO, LATO_ALTO = 24.0, 48.0
LATI_RIDOTTI = (28.0, 36.0, 44.0)
FATTORE_TROVATI = 1.05
FATTORE_LATENZA = 1.30
SOGLIA_COSENO_INT8 = 0.995
TOLLERANZA_AUC = 0.005
SOGLIA_VERIFICA = 0.363


def casi_piccoli(radice: Path) -> list[dict]:
    """Immagini con volti di verita' (serie B e ritratti ridotti) per la regola 1: [{nome, quadro, volti}]."""
    casi = [{"nome": c["nome"], "quadro": c["immagine"], "volti": c["volti"]} for c in dati.composizioni(radice, "B")]
    a = engines.YuNetOnnx(radice / "out" / "onnx-1024" / "face_detection_yunet_2023mar.onnx")
    b = engines.YuNetOnnx(radice / "out" / "onnx-1024" / "face_detection_yunet_2026may.onnx")
    for r in dati.ritratti(radice):
        quadro, scala = common.quadro_yunet(r["immagine"])
        cons = dati.consenso_ritratto(engines.rilevamenti(a, quadro), engines.rilevamenti(b, quadro), scala)
        if len(cons) != 1:
            continue
        volto = {"riquadro": cons[0]["riquadro"], "punti": cons[0]["punti"]}
        for lato in LATI_RIDOTTI:
            piccolo, verita = dati.ritratto_ridotto(r["immagine"], volto, lato)
            casi.append({"nome": f"{r['etichetta']}@{int(lato)}", "quadro": piccolo, "volti": [verita]})
    return casi


def misura_yunet(tflite: Path, casi: list[dict], serie_fp: list[dict]) -> dict:
    motore = engines.YuNetTflite(tflite)
    per_fascia = {"24-31": [0, 0], "32-39": [0, 0], "40-47": [0, 0]}
    trovati = totale = 0
    for c in casi:
        det = engines.rilevamenti(motore, c["quadro"])
        coppie, _, _ = dati.abbina(det, c["volti"], IOU_TROVATO)
        presi = {j for _, j, _ in coppie}
        for j, v in enumerate(c["volti"]):
            lato = min(v["riquadro"][2:])
            if not (LATO_BASSO <= lato < LATO_ALTO):
                continue
            fascia = "24-31" if lato < 32 else "32-39" if lato < 40 else "40-47"
            totale += 1
            per_fascia[fascia][1] += 1
            if j in presi:
                trovati += 1
                per_fascia[fascia][0] += 1
    falsi = 0
    for c in serie_fp:
        det = engines.rilevamenti(motore, c["immagine"])
        _, in_piu, _ = dati.abbina(det, c["volti"], IOU_TROVATO)
        falsi += len(in_piu)
    return {"volti_24_47": totale, "trovati": trovati, "richiamo": trovati / totale, "per_fascia": {k: v for k, v in per_fascia.items()},
            "falsi_positivi": falsi, "immagini_falsi_positivi": len(serie_fp)}


def latenze(percorsi: dict[str, Path], immagini: list[np.ndarray]) -> dict[str, float]:
    """Mediana (ms) di 30 inferenze a 1024 dopo 5 di riscaldamento, a turno fra i candidati sulle stesse immagini."""
    motori = {n: engines.YuNetTflite(p) for n, p in percorsi.items()}
    tempi: dict[str, list[float]] = {n: [] for n in percorsi}
    for giro in range(35):
        quadro = immagini[giro % len(immagini)]
        for n, m in motori.items():
            t = m.tempo_ms(quadro)
            if giro >= 5:
                tempi[n].append(t)
    return {n: statistics.median(t) for n, t in tempi.items()}


def regola_1(radice: Path) -> dict:
    cartella = radice / "out" / "tflite"
    percorsi = {v: cartella / f"yunet-{v}" / "finale" / f"face_detection_yunet_{v}_float32.tflite" for v in ("2023mar", "2026may")}
    casi = casi_piccoli(radice)
    serie_fp = [c for s in ("A", "B", "C") for c in dati.composizioni(radice, s)]
    misure = {v: misura_yunet(p, casi, serie_fp) for v, p in percorsi.items()}
    tempi = latenze(percorsi, [c["quadro"] for c in casi[:20]])
    a, b = misure["2023mar"], misure["2026may"]
    cond_a = b["trovati"] >= FATTORE_TROVATI * a["trovati"]
    cond_b = b["falsi_positivi"] <= a["falsi_positivi"]
    cond_c = tempi["2026may"] <= FATTORE_LATENZA * tempi["2023mar"]
    scelta = "2026may" if (cond_a and cond_b and cond_c) else "2023mar"
    return {"misure": misure, "latenza_mediana_ms": tempi, "condizioni": {"a_trovati": cond_a, "b_falsi_positivi": cond_b, "c_latenza": cond_c},
            "scelta": scelta}


def auc_e_errori(vettori: list[np.ndarray], soggetti: list[str]) -> dict:
    n = len(vettori)
    pos, neg = [], []
    for i in range(n):
        for j in range(i + 1, n):
            (pos if soggetti[i] == soggetti[j] else neg).append(common.coseno(vettori[i], vettori[j]))
    pos, neg = np.array(pos), np.array(neg)
    # AUC = probabilita' che una coppia vera abbia coseno maggiore di una falsa (pari = 1/2)
    confronti = (pos[:, None] > neg[None, :]).mean() + 0.5 * (pos[:, None] == neg[None, :]).mean()
    return {"coppie_vere": int(pos.size), "coppie_false": int(neg.size), "auc": float(confronti),
            "errori_alla_soglia": int((pos < SOGLIA_VERIFICA).sum() + (neg >= SOGLIA_VERIFICA).sum()),
            "vere_sotto_soglia": int((pos < SOGLIA_VERIFICA).sum()), "false_sopra_soglia": int((neg >= SOGLIA_VERIFICA).sum())}


def regola_2(radice: Path) -> dict:
    out = radice / "out"
    volti = parity.volti_allineati(radice, out / "onnx-1024" / "face_detection_yunet_2023mar.onnx")
    ref = engines.SFaceOnnx(radice / "onnx" / "face_recognition_sface_2021dec.onnx")
    f16 = engines.SFaceTflite(out / "tflite" / "sface" / "finale" / "face_recognition_sface_2021dec_float16.tflite")
    i8 = engines.SFaceTflite(out / "tflite" / "sface" / "uscita" / "face_recognition_sface_2021dec_dynamic_range_quant.tflite")
    coseni = np.array([common.coseno(ref.vettore(v["volto"]), i8.vettore(v["volto"])) for v in volti])
    # coppie dei ritratti: un solo volto per ritratto, solo ritratti con un volto di consenso (stesso insieme della regola 1)
    a = engines.YuNetOnnx(out / "onnx-1024" / "face_detection_yunet_2023mar.onnx")
    b = engines.YuNetOnnx(out / "onnx-1024" / "face_detection_yunet_2026may.onnx")
    soggetti, facce = [], []
    for r in dati.ritratti(radice):
        quadro, scala = common.quadro_yunet(r["immagine"])
        cons = dati.consenso_ritratto(engines.rilevamenti(a, quadro), engines.rilevamenti(b, quadro), scala)
        if len(cons) == 1:
            soggetti.append(r["soggetto"])
            facce.append(common.volto_allineato(r["immagine"], cons[0]["punti"]))
    metriche = {nome: auc_e_errori([m.vettore(f) for f in facce], soggetti) for nome, m in (("float16", f16), ("int8-dinamico", i8))}
    cond_a = bool(coseni.min() >= SOGLIA_COSENO_INT8)
    cond_b = bool(metriche["int8-dinamico"]["auc"] >= metriche["float16"]["auc"] - TOLLERANZA_AUC
                  and metriche["int8-dinamico"]["errori_alla_soglia"] <= metriche["float16"]["errori_alla_soglia"])
    return {"volti_di_prova": len(volti), "coseno_int8_su_fp32": {"minimo": float(coseni.min()), "medio": float(coseni.mean()),
                                                                  "p1": float(np.percentile(coseni, 1)), "sotto_0_995": int((coseni < SOGLIA_COSENO_INT8).sum())},
            "ritratti_per_le_coppie": len(facce), "coppie": metriche, "condizioni": {"a_coseno": cond_a, "b_separazione": cond_b},
            "scelta": "int8-dinamico" if (cond_a and cond_b) else "float16"}


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--root", help="cartella dei modelli dei volti, fuori dal repository (o MCG_FACES_ROOT)")
    ap.add_argument("--scrivi", help="rapporto JSON (predefinito: <root>/out/misure/scelte.json)")
    args = ap.parse_args()
    radice = common.radice_modelli(args.root)
    r1, r2 = regola_1(radice), regola_2(radice)
    for v, m in r1["misure"].items():
        print(f"YuNet {v}: volti 24-47 px {m['volti_24_47']}, trovati {m['trovati']} ({m['richiamo']:.4f}) per fascia {m['per_fascia']}, "
              f"falsi positivi {m['falsi_positivi']} su {m['immagini_falsi_positivi']} immagini")
    print("latenza mediana a 1024 (ms):", {k: round(v, 2) for k, v in r1["latenza_mediana_ms"].items()}, "condizioni:", r1["condizioni"],
          "=> scelta YuNet", r1["scelta"])
    print("SFace int8 su fp32:", r2["coseno_int8_su_fp32"], "su", r2["volti_di_prova"], "volti")
    print("coppie dei ritratti:", r2["coppie"], "condizioni:", r2["condizioni"], "=> scelta SFace", r2["scelta"])
    destinazione = Path(args.scrivi) if args.scrivi else radice / "out" / "misure" / "scelte.json"
    destinazione.parent.mkdir(parents=True, exist_ok=True)
    destinazione.write_text(json.dumps({"regola_1": r1, "regola_2": r2}, indent=2), encoding="utf-8")


if __name__ == "__main__":
    main()
