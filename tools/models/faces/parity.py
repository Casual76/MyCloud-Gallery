"""Parita' fra l'ONNX e il `.tflite` di YuNet e di SFace sul PC (Fase 8, P1; docs/fase8-progetto.md §2.2 punto 4, regola 3 di docs/decisioni.md).

    python -I parity.py --root "C:\\...\\mcg-models\\faces" [--scrivi <file.json>]

YuNet (ingresso fisso 1024x1024, decodifica nostra uguale per i due): sulle immagini di prova (i ritratti e le composizioni A, B, C), ogni
riquadro dell'ONNX con punteggio >= 0,605 deve avere un riquadro del `.tflite` con IoU >= 0,95 e i cinque punti entro 1 px, e viceversa;
fra 0,595 e 0,605 il riquadro puo' cadere dall'una o dall'altra parte della soglia e si conta a parte.
SFace: su tutti i volti incollati delle composizioni A e B (allineati coi punti di verita') e su quelli dei ritratti (allineati coi punti
rilevati), il coseno fra il vettore dell'ONNX fp32 e quello del `.tflite` float16 deve essere >= 0,999 per OGNI volto.
Sono riportate anche le varianti che non si consegnano (float32, int8 dinamico, YuNet float16, ONNX int8 di OpenCV Zoo) come contesto.
Esce con codice 1 se un controllo di consegna non passa.
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))
import common  # noqa: E402
import dati  # noqa: E402
import engines  # noqa: E402

SOGLIA_IOU = 0.95
SOGLIA_PUNTI_PX = 1.0
PUNTEGGIO_SICURO = 0.605
PUNTEGGIO_DUBBIO = 0.595
SOGLIA_COSENO = 0.999


def spostamento_punti(a: np.ndarray, b: np.ndarray) -> float:
    return float(np.max(np.linalg.norm(a - b, axis=1)))


def confronta_rilevamenti(da: list[dict], verso: list[dict]) -> dict:
    """Ogni riquadro di `da` con punteggio >= 0,605 cerca il suo gemello in `verso`; riporta fallimenti, minimi e massimi."""
    esito = {"controllati": 0, "senza_gemello": 0, "iou_minimo": 1.0, "punti_max_px": 0.0, "punteggio_max_diff": 0.0, "dubbi_senza_gemello": 0}
    for r in da:
        migliore = max(verso, key=lambda x: common.iou(r["riquadro"], x["riquadro"]), default=None)
        i = common.iou(r["riquadro"], migliore["riquadro"]) if migliore else 0.0
        if r["punteggio"] >= PUNTEGGIO_SICURO:
            esito["controllati"] += 1
            if migliore is None or i < SOGLIA_IOU or spostamento_punti(r["punti"], migliore["punti"]) > SOGLIA_PUNTI_PX:
                esito["senza_gemello"] += 1
            if migliore is not None:
                esito["iou_minimo"] = min(esito["iou_minimo"], i)
                esito["punti_max_px"] = max(esito["punti_max_px"], spostamento_punti(r["punti"], migliore["punti"]))
                esito["punteggio_max_diff"] = max(esito["punteggio_max_diff"], abs(r["punteggio"] - migliore["punteggio"]))
        elif r["punteggio"] >= PUNTEGGIO_DUBBIO and (migliore is None or i < SOGLIA_IOU):
            esito["dubbi_senza_gemello"] += 1
    return esito


def immagini_yunet(radice: Path) -> list[tuple[str, np.ndarray]]:
    elenco = [(r["etichetta"], common.quadro_yunet(r["immagine"])[0]) for r in dati.ritratti(radice)]
    for serie in ("A", "B", "C"):
        elenco += [(c["nome"], c["immagine"]) for c in dati.composizioni(radice, serie)]
    return elenco


def parita_yunet(onnx: Path, tflite: Path, immagini) -> dict:
    ref, prova = engines.YuNetOnnx(onnx), engines.YuNetTflite(tflite)
    somma = {"immagini": len(immagini), "onnx_su_tflite": None, "tflite_su_onnx": None, "scarto_massimo_uscite": {}, "rilevamenti_onnx": 0,
             "immagini_con_numero_diverso": 0}
    a_b, b_a = [], []
    for nome, quadro in immagini:
        uo, ut = ref.uscite(quadro), prova.uscite(quadro)
        for n in engines.USCITE_YUNET:
            d = float(np.max(np.abs(uo[n] - ut[n])))
            somma["scarto_massimo_uscite"][n] = max(somma["scarto_massimo_uscite"].get(n, 0.0), d)
        do, dt = common.decodifica_yunet(uo), common.decodifica_yunet(ut)
        somma["rilevamenti_onnx"] += len(do)
        if len(do) != len(dt):
            somma["immagini_con_numero_diverso"] += 1
        a_b.append(confronta_rilevamenti(do, dt))
        b_a.append(confronta_rilevamenti(dt, do))

    def unisci(parti: list[dict]) -> dict:
        return {"controllati": sum(p["controllati"] for p in parti), "senza_gemello": sum(p["senza_gemello"] for p in parti),
                "dubbi_senza_gemello": sum(p["dubbi_senza_gemello"] for p in parti), "iou_minimo": min(p["iou_minimo"] for p in parti),
                "punti_max_px": max(p["punti_max_px"] for p in parti), "punteggio_max_diff": max(p["punteggio_max_diff"] for p in parti)}

    somma["onnx_su_tflite"], somma["tflite_su_onnx"] = unisci(a_b), unisci(b_a)
    somma["scarto_massimo_uscite"] = {k: float(v) for k, v in somma["scarto_massimo_uscite"].items()}
    somma["passa"] = somma["onnx_su_tflite"]["senza_gemello"] == 0 and somma["tflite_su_onnx"]["senza_gemello"] == 0
    return somma


def volti_allineati(radice: Path, yunet_onnx: Path) -> list[dict]:
    """I volti 112x112 per SFace: i volti incollati (punti di verita') e quelli dei ritratti (punti rilevati dall'ONNX di YuNet)."""
    volti = []
    for serie in ("A", "B"):
        for c in dati.composizioni(radice, serie):
            for k, v in enumerate(c["volti"]):
                volti.append({"origine": f"{c['nome']}#{k}", "volto": common.volto_allineato(c["immagine"], v["punti"])})
    det = engines.YuNetOnnx(yunet_onnx)
    for r in dati.ritratti(radice):
        quadro, scala = common.quadro_yunet(r["immagine"])
        for k, d in enumerate(engines.rilevamenti(det, quadro)):
            volti.append({"origine": f"{r['etichetta']}#{k}", "volto": common.volto_allineato(r["immagine"], d["punti"] / scala)})
    return volti


def parita_sface(riferimento: Path, varianti: dict[str, Path], volti: list[dict]) -> dict:
    ref = engines.SFaceOnnx(riferimento)
    vettori_ref = [ref.vettore(v["volto"]) for v in volti]
    esito = {"volti": len(volti)}
    for nome, percorso in varianti.items():
        motore = engines.SFaceOnnx(percorso) if percorso.suffix == ".onnx" else engines.SFaceTflite(percorso)
        cos = np.array([common.coseno(r, motore.vettore(v["volto"])) for r, v in zip(vettori_ref, volti)])
        norme = np.array([np.linalg.norm(motore.vettore(v["volto"])) for v in volti[:50]])
        esito[nome] = {"coseno_minimo": float(cos.min()), "coseno_medio": float(cos.mean()), "coseno_p1": float(np.percentile(cos, 1)),
                       "sotto_0_999": int((cos < 0.999).sum()), "sotto_0_995": int((cos < 0.995).sum()), "norma_media_prime_50": float(norme.mean())}
    return esito


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--root", help="cartella dei modelli dei volti, fuori dal repository (o MCG_FACES_ROOT)")
    ap.add_argument("--scrivi", help="dove salvare il rapporto JSON (predefinito: <root>/out/misure/parita.json)")
    args = ap.parse_args()
    radice = common.radice_modelli(args.root)
    out = radice / "out"
    immagini = immagini_yunet(radice)
    rapporto: dict = {"yunet": {}, "sface": {}}
    consegna_ok = True
    for versione in ("2023mar", "2026may"):
        onnx = out / "onnx-1024" / f"face_detection_yunet_{versione}.onnx"
        for precisione in ("float32", "float16"):
            tflite = out / "tflite" / f"yunet-{versione}" / "finale" / f"face_detection_yunet_{versione}_{precisione}.tflite"
            r = parita_yunet(onnx, tflite, immagini)
            rapporto["yunet"][f"{versione}-{precisione}"] = r
            print(f"YuNet {versione} {precisione}: {r['immagini']} immagini, {r['rilevamenti_onnx']} riquadri, "
                  f"ONNX->tflite senza gemello {r['onnx_su_tflite']['senza_gemello']}/{r['onnx_su_tflite']['controllati']}, "
                  f"tflite->ONNX {r['tflite_su_onnx']['senza_gemello']}/{r['tflite_su_onnx']['controllati']}, IoU min {r['onnx_su_tflite']['iou_minimo']:.5f}, "
                  f"punti max {r['onnx_su_tflite']['punti_max_px']:.4f} px, uscite max {max(r['scarto_massimo_uscite'].values()):.2e}, "
                  f"dubbi {r['onnx_su_tflite']['dubbi_senza_gemello']}+{r['tflite_su_onnx']['dubbi_senza_gemello']}, "
                  f"{'PASSA' if r['passa'] else 'NON PASSA'}")
            if precisione == "float32" and not r["passa"]:
                consegna_ok = False
    volti = volti_allineati(radice, out / "onnx-1024" / "face_detection_yunet_2023mar.onnx")
    cartella = out / "tflite" / "sface" / "finale"
    varianti = {
        "tflite-float16": cartella / "face_recognition_sface_2021dec_float16.tflite",
        "tflite-float32": cartella / "face_recognition_sface_2021dec_float32.tflite",
        "tflite-int8-dinamico": out / "tflite" / "sface" / "uscita" / "face_recognition_sface_2021dec_dynamic_range_quant.tflite",
        "onnx-int8-opencv-zoo": radice / "onnx" / "face_recognition_sface_2021dec_int8.onnx",
    }
    rapporto["sface"] = parita_sface(radice / "onnx" / "face_recognition_sface_2021dec.onnx", varianti, volti)
    for nome, v in rapporto["sface"].items():
        if isinstance(v, dict):
            print(f"SFace {nome}: coseno min {v['coseno_minimo']:.6f}, medio {v['coseno_medio']:.6f}, p1 {v['coseno_p1']:.6f}, "
                  f"sotto 0,999: {v['sotto_0_999']}, sotto 0,995: {v['sotto_0_995']} (su {rapporto['sface']['volti']} volti)")
    if rapporto["sface"]["tflite-float16"]["coseno_minimo"] < SOGLIA_COSENO:
        consegna_ok = False
    destinazione = Path(args.scrivi) if args.scrivi else out / "misure" / "parita.json"
    destinazione.parent.mkdir(parents=True, exist_ok=True)
    destinazione.write_text(json.dumps(rapporto, indent=2), encoding="utf-8")
    print("consegna:", "PARITA' OK" if consegna_ok else "PARITA' NON PASSATA")
    sys.exit(0 if consegna_ok else 1)


if __name__ == "__main__":
    main()
