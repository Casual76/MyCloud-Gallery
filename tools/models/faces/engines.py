"""Esecutori di riferimento dei modelli dei volti per le prove sul PC (Fase 8, P1): ONNX Runtime per i file originali, l'interprete di LiteRT
per i `.tflite`. Sono le stesse due strade di `parity.py` e `choose.py`; qui sta solo il passaggio dall'immagine al tensore e viceversa.

Preprocessing (docs/fase8-progetto.md §2.2 punto 4, controllato con OpenCV in `verify_opencv.py`):
  - YuNet: quadro 1024x1024 BGR uint8 -> float32 0..255, senza media ne' scala (NCHW per l'ONNX, NHWC per il `.tflite`);
  - SFace: volto allineato 112x112 BGR uint8 -> RGB float32 0..255 (la media e la scala sono dentro il modello).
"""
from __future__ import annotations

import time
from pathlib import Path

import numpy as np

import common

USCITE_YUNET = [f"{k}_{s}" for k in ("cls", "obj", "bbox", "kps") for s in (8, 16, 32)]


def _sessione_ort(percorso: Path):
    import onnxruntime as ort

    opzioni = ort.SessionOptions()
    opzioni.intra_op_num_threads = 4
    opzioni.log_severity_level = 3
    return ort.InferenceSession(str(percorso), opzioni, providers=["CPUExecutionProvider"])


def _interprete(percorso: Path, thread: int = 4):
    from ai_edge_litert.interpreter import Interpreter

    it = Interpreter(model_path=str(percorso), num_threads=thread)
    it.allocate_tensors()
    return it


class YuNetOnnx:
    nome = "onnx"

    def __init__(self, percorso: Path):
        self.sessione = _sessione_ort(percorso)

    def uscite(self, quadro_bgr: np.ndarray) -> dict[str, np.ndarray]:
        x = quadro_bgr.astype(np.float32).transpose(2, 0, 1)[None]
        valori = self.sessione.run(USCITE_YUNET, {self.sessione.get_inputs()[0].name: x})
        return dict(zip(USCITE_YUNET, valori))


class YuNetTflite:
    nome = "tflite"

    def __init__(self, percorso: Path, thread: int = 4):
        self.it = _interprete(percorso, thread)
        self.ingresso = self.it.get_input_details()[0]["index"]
        dettagli = {d["name"]: d["index"] for d in self.it.get_output_details()}
        mancanti = [n for n in USCITE_YUNET if n not in dettagli]
        if mancanti:
            raise ValueError(f"{percorso.name}: uscite senza il nome atteso: {mancanti}")
        self.indici = [dettagli[n] for n in USCITE_YUNET]
        # Controllo di contratto: nell'ordine del file le uscite sono gia' quelle canoniche (posizione = nome).
        ordine_file = [d["name"] for d in self.it.get_output_details()]
        if ordine_file != USCITE_YUNET:
            raise ValueError(f"{percorso.name}: l'ordine delle uscite nel file non e' quello canonico: {ordine_file}")

    def uscite(self, quadro_bgr: np.ndarray) -> dict[str, np.ndarray]:
        self.it.set_tensor(self.ingresso, quadro_bgr.astype(np.float32)[None])
        self.it.invoke()
        return {n: self.it.get_tensor(i).copy() for n, i in zip(USCITE_YUNET, self.indici)}

    def tempo_ms(self, quadro_bgr: np.ndarray) -> float:
        self.it.set_tensor(self.ingresso, quadro_bgr.astype(np.float32)[None])
        t0 = time.perf_counter()
        self.it.invoke()
        return (time.perf_counter() - t0) * 1000.0


class SFaceOnnx:
    nome = "onnx"

    def __init__(self, percorso: Path):
        self.sessione = _sessione_ort(percorso)
        self.ingresso = self.sessione.get_inputs()[0].name

    def vettore(self, volto_bgr_112: np.ndarray) -> np.ndarray:
        x = volto_bgr_112[:, :, ::-1].astype(np.float32).transpose(2, 0, 1)[None]
        return self.sessione.run(None, {self.ingresso: np.ascontiguousarray(x)})[0].reshape(-1).astype(np.float64)


class SFaceTflite:
    nome = "tflite"

    def __init__(self, percorso: Path, thread: int = 4):
        self.it = _interprete(percorso, thread)
        self.ingresso = self.it.get_input_details()[0]["index"]
        self.uscita = self.it.get_output_details()[0]["index"]

    def vettore(self, volto_bgr_112: np.ndarray) -> np.ndarray:
        x = np.ascontiguousarray(volto_bgr_112[:, :, ::-1].astype(np.float32)[None])
        self.it.set_tensor(self.ingresso, x)
        self.it.invoke()
        return self.it.get_tensor(self.uscita).reshape(-1).astype(np.float64)


def rilevamenti(motore, quadro_bgr: np.ndarray, **kw) -> list[dict]:
    """I rilevamenti decodificati (dal nostro decodificatore) di un quadro 1024x1024."""
    return common.decodifica_yunet(motore.uscite(quadro_bgr), **kw)
