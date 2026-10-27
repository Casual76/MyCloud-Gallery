# Modelli dei volti: conversione e parità (Fase 8, P1)

Strumenti per ricreare, sul PC, i due `.tflite` che stanno in `core-ai/src/main/assets/faces/` a partire dai file ONNX di OpenCV Zoo
(YuNet, MIT; SFace, Apache-2.0). Il progetto è in `docs/fase8-progetto.md` (§2.2, D6–D9, D39); le regole di scelta, i numeri e le versioni sono
in `docs/decisioni.md`, sezione «Fase 8 P1». Tutto ciò che si scarica o si genera sta **fuori dal repository**, in una cartella `<root>` a
scelta (qui `C:\VibeCoded Projects\mcg-models\faces`): `onnx\`, `venv\`, `ritratti\`, `out\`, `cache\`, `tmp\`. Mai ONNX, ambienti
Python o ritratti nel repository (lo controlla `NoFaceModelSourcesInGitTest` e il `.gitignore`).

Gli script si lanciano con `python -I` (modalità isolata) e trattano come dati non fidati tutto ciò che sta sotto `<root>`.

| Passo | Comando | Cosa fa |
|---|---|---|
| 1 | `powershell -NoProfile -File setup-env.ps1 -Root <root>` | ambiente Python 3.11 isolato, pacchetti solo da `requirements.txt` con impronte (`--require-hashes`) |
| 2 | `python -I fetch_onnx.py --root <root>` | scarica gli ONNX dal commit fissato di `opencv/opencv_zoo` e controlla dimensione e SHA-256 (`fingerprints.json`) |
| 3 | `python -I convert.py --root <root>` | ONNX → `.tflite` (YuNet a 1×1024×1024×3 float32; SFace float16, float32 e int8 dinamico) con le uscite nell'ordine dell'ONNX |
| 4 | `python -I fetch_portraits.py --root <root>` | ritratti di pubblico dominio da Wikimedia Commons (`ritratti\selezione.json` → `ritratto-NN.jpg`, `ELENCO.json`) |
| 5 | `python -I compose.py --root <root>` | composizioni sintetiche A, B, C con la verità di terra |
| 6 | `python -I parity.py --root <root>` | parità ONNX contro `.tflite` (IoU ≥ 0,95, cinque punti entro 1 px, coseno ≥ 0,999) |
| 7 | `python -I choose.py --root <root>` | applica le regole di scelta di D7 (scritte prima di misurare) |
| 8 | `python -I verify_opencv.py --root <root>` | controlla preprocessing e decodifica contro `FaceDetectorYN` e `FaceRecognizerSF` di OpenCV |
| — | `python -I selftest.py` | prove delle funzioni pure (riquadri, decodifica, allineamento), senza modelli |

`python` è quello dell'ambiente: `<root>\venv\conv\Scripts\python.exe`. Gli script del repository: `common.py` (impronte, decodifica di
YuNet, allineamento), `engines.py` (ONNX Runtime e interprete di LiteRT), `dati.py` (dati di prova e abbinamenti), `make_requirements.py`
(solo per aggiornare `requirements.txt`).

**Contratto dei file consegnati** (per `core-ai/.../faces/`, P2)
- YuNet: ingresso `input` `[1, 1024, 1024, 3]` float32, BGR 0–255 senza normalizzare (bordi a 0 in basso e a destra); dodici uscite float32
  **in quest'ordine**: `cls_8, cls_16, cls_32, obj_8, obj_16, obj_32, bbox_8, bbox_16, bbox_32, kps_8, kps_16, kps_32`, forma `[1, N, C]`
  con N = 16384, 4096, 1024 (passi 8, 16, 32) e C = 1, 1, 4, 10. La decodifica è `common.decodifica_yunet`, da portare in Kotlin.
- SFace: ingresso `data` `[1, 112, 112, 3]` float32, volto allineato RGB 0–255 (la media e la scala sono nel modello); uscita `fc1` `[1, 128]`.
