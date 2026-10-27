"""Controlla preprocessing, decodifica e allineamento contro OpenCV (`FaceDetectorYN`, `FaceRecognizerSF`), Fase 8, P1.

    python -I verify_opencv.py --root "C:\\...\\mcg-models\\faces"

La parita' ONNX contro `.tflite` non dice se il preprocessing e' quello giusto (l'errore sarebbe uguale nei due): qui lo si confronta con la
libreria di riferimento, installata nell'ambiente come dipendenza di onnx2tf (nessun sorgente di OpenCV Zoo e' stato scaricato per leggerlo).
  - YuNet: i rilevamenti di `FaceDetectorYN` (stesso ONNX a 1024 px, soglie 0,6 e 0,3) contro la nostra decodifica sulle uscite di ONNX Runtime:
    riquadri, cinque punti (nello stesso ordine) e punteggi sulle stesse immagini;
  - SFace: l'allineamento di `FaceRecognizerSF.alignCrop` contro il nostro (differenza dei pixel e coseno dei vettori) e il vettore di
    `FaceRecognizerSF.feature` contro quello del nostro esecutore ONNX sullo stesso volto allineato.
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


def verifica_yunet(radice: Path, versione: str, immagini: list[tuple[str, np.ndarray]]) -> dict:
    import cv2

    percorso = radice / "out" / "onnx-1024" / f"face_detection_yunet_{versione}.onnx"
    ocv = cv2.FaceDetectorYN.create(str(percorso), "", (1024, 1024), common.SOGLIA_RILEVAMENTO, common.SOGLIA_NMS, 5000)
    nostro = engines.YuNetOnnx(percorso)
    esito = {"immagini": len(immagini), "riquadri_opencv": 0, "riquadri_nostri": 0, "senza_gemello": 0, "iou_minimo": 1.0, "punti_max_px": 0.0,
             "punteggio_max_diff": 0.0, "immagini_con_numero_diverso": 0}
    for nome, quadro in immagini:
        _, facce = ocv.detect(quadro)
        facce = [] if facce is None else facce
        mie = engines.rilevamenti(nostro, quadro)
        esito["riquadri_opencv"] += len(facce)
        esito["riquadri_nostri"] += len(mie)
        if len(facce) != len(mie):
            esito["immagini_con_numero_diverso"] += 1
        for f in facce:
            box = (float(f[0]), float(f[1]), float(f[2]), float(f[3]))
            migliore = max(mie, key=lambda r: common.iou(box, r["riquadro"]), default=None)
            if migliore is None or common.iou(box, migliore["riquadro"]) < 0.9:
                esito["senza_gemello"] += 1
                continue
            esito["iou_minimo"] = min(esito["iou_minimo"], common.iou(box, migliore["riquadro"]))
            punti = np.array(f[4:14], dtype=np.float64).reshape(5, 2)
            esito["punti_max_px"] = max(esito["punti_max_px"], float(np.max(np.linalg.norm(punti - migliore["punti"], axis=1))))
            esito["punteggio_max_diff"] = max(esito["punteggio_max_diff"], abs(float(f[14]) - migliore["punteggio"]))
    return esito


def verifica_sface(radice: Path, volti: list[dict]) -> dict:
    """`volti`: [{immagine, punti, rilevamento_opencv}] con i punti nelle coordinate dell'immagine."""
    import cv2

    riconoscitore = cv2.FaceRecognizerSF.create(str(radice / "onnx" / "face_recognition_sface_2021dec.onnx"), "")
    nostro = engines.SFaceOnnx(radice / "onnx" / "face_recognition_sface_2021dec.onnx")
    differenze_pixel, cos_allineamento, cos_vettore = [], [], []
    for v in volti:
        mio = common.volto_allineato(v["immagine"], v["punti"])
        di_ocv = riconoscitore.alignCrop(v["immagine"], v["riga_opencv"])
        differenze_pixel.append(float(np.mean(np.abs(mio.astype(np.float32) - di_ocv.astype(np.float32)))))
        f_ocv = riconoscitore.feature(di_ocv).reshape(-1)
        cos_allineamento.append(common.coseno(f_ocv, nostro.vettore(mio)))
        cos_vettore.append(common.coseno(riconoscitore.feature(mio).reshape(-1), nostro.vettore(mio)))
    return {"volti": len(volti), "differenza_media_pixel": {"media": float(np.mean(differenze_pixel)), "massima": float(np.max(differenze_pixel))},
            "coseno_nostro_allineamento_contro_opencv": {"minimo": float(np.min(cos_allineamento)), "medio": float(np.mean(cos_allineamento))},
            "coseno_stesso_volto_opencv_contro_nostro_onnx": {"minimo": float(np.min(cos_vettore)), "medio": float(np.mean(cos_vettore))}}


def main() -> None:
    import cv2

    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--root", help="cartella dei modelli dei volti, fuori dal repository (o MCG_FACES_ROOT)")
    ap.add_argument("--scrivi", help="rapporto JSON (predefinito: <root>/out/misure/opencv.json)")
    args = ap.parse_args()
    radice = common.radice_modelli(args.root)
    immagini = [(r["etichetta"], common.quadro_yunet(r["immagine"])[0]) for r in dati.ritratti(radice)]
    for serie in ("A", "B", "C"):
        immagini += [(c["nome"], c["immagine"]) for c in dati.composizioni(radice, serie)]
    rapporto: dict = {"opencv": cv2.__version__, "yunet": {}}
    for versione in ("2023mar", "2026may"):
        rapporto["yunet"][versione] = verifica_yunet(radice, versione, immagini)
        print(f"YuNet {versione} contro FaceDetectorYN:", rapporto["yunet"][versione])
    # SFace: volti dei ritratti rilevati da OpenCV stesso (cosi' la riga dei punti e' quella che alignCrop si aspetta)
    ocv = cv2.FaceDetectorYN.create(str(radice / "out" / "onnx-1024" / "face_detection_yunet_2023mar.onnx"), "", (1024, 1024), 0.6, 0.3, 5000)
    volti = []
    for r in dati.ritratti(radice):
        quadro, scala = common.quadro_yunet(r["immagine"])
        _, facce = ocv.detect(quadro)
        for f in ([] if facce is None else facce):
            riga = np.array(f, dtype=np.float32).copy()
            riga[:14] = riga[:14] / scala
            volti.append({"immagine": r["immagine"], "punti": riga[4:14].reshape(5, 2).astype(np.float64), "riga_opencv": riga.reshape(1, -1)})
    rapporto["sface"] = verifica_sface(radice, volti)
    print("SFace contro FaceRecognizerSF:", rapporto["sface"])
    destinazione = Path(args.scrivi) if args.scrivi else radice / "out" / "misure" / "opencv.json"
    destinazione.parent.mkdir(parents=True, exist_ok=True)
    destinazione.write_text(json.dumps(rapporto, indent=2), encoding="utf-8")


if __name__ == "__main__":
    main()
