"""Conversione una tantum, sul PC, di YuNet e SFace da ONNX a `.tflite` (Fase 8, P1; docs/fase8-progetto.md D6, D7, §2.2 "La conversione").

    python -I convert.py --root "C:\\...\\mcg-models\\faces" [--only yunet-2023mar yunet-2026may sface]

Per ogni modello:
  1. controlla dimensione e SHA-256 del file ONNX d'ingresso (se non tornano si ferma);
  2. YuNet: fissa l'ingresso a 1x3x1024x1024 (le uscite si ricavano dal grafo) e salva un ONNX derivato in `out/onnx-1024/`: SOLO le forme
     dichiarate cambiano, nessun peso (il confronto di parita' usa questo stesso file);
  3. lancia `onnx2tf` (ingresso NHWC, uscite con i nomi dell'ONNX); SFace produce anche la variante a pesi int8 dinamici;
  4. scrive `out/tflite/<modello>/conversion.json` con versioni degli strumenti, comandi, dimensioni e SHA-256 di ogni file.
Gli ONNX, l'ambiente Python e gli intermedi restano fuori dal repository; nel repository entrano solo i `.tflite` scelti.
"""
from __future__ import annotations

import argparse
import importlib.metadata as md
import json
import shutil
import subprocess
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import common  # noqa: E402

LATO = 1024

# nome breve -> (file ONNX, famiglia)
MODELLI = {
    "yunet-2023mar": ("face_detection_yunet_2023mar.onnx", "yunet"),
    "yunet-2026may": ("face_detection_yunet_2026may.onnx", "yunet"),
    "sface": ("face_recognition_sface_2021dec.onnx", "sface"),
}
STRUMENTI = ["onnx2tf", "tensorflow-cpu", "tf-keras", "keras", "onnx", "onnxruntime", "ai-edge-litert", "numpy", "onnxsim", "onnxoptimizer",
             "onnx-graphsurgeon", "opencv-python", "protobuf", "h5py", "ml-dtypes", "flatbuffers"]


def versioni() -> dict[str, str]:
    trovate = {}
    for nome in STRUMENTI:
        try:
            trovate[nome] = md.version(nome)
        except md.PackageNotFoundError:
            trovate[nome] = "(assente)"
    trovate["python"] = sys.version.split()[0]
    return trovate


def fissa_ingresso_yunet(sorgente: Path, destinazione: Path) -> None:
    """Copia l'ONNX di YuNet con l'ingresso fisso 1x3x1024x1024 e le uscite ricavate dal grafo (i pesi non cambiano)."""
    import onnx

    modello = onnx.load(str(sorgente))
    ingresso = modello.graph.input[0]
    dim = ingresso.type.tensor_type.shape.dim
    for d, valore in zip(dim, (1, 3, LATO, LATO)):
        d.ClearField("dim_param")
        d.dim_value = valore
    for uscita in modello.graph.output:
        uscita.type.tensor_type.ClearField("shape")
    del modello.graph.value_info[:]  # le forme intermedie dichiarate sono quelle di 640 px: si ricavano di nuovo
    modello = onnx.shape_inference.infer_shapes(modello, check_type=True, strict_mode=True)
    attese = {8: (LATO // 8) ** 2, 16: (LATO // 16) ** 2, 32: (LATO // 32) ** 2}
    for uscita in modello.graph.output:
        forma = [d.dim_value for d in uscita.type.tensor_type.shape.dim]
        passo = int(uscita.name.rsplit("_", 1)[1])
        if len(forma) != 3 or forma[0] != 1 or forma[1] != attese[passo]:
            sys.exit(f"{uscita.name}: forma {forma} inattesa a {LATO} px (attesi {attese[passo]} punti)")
    onnx.checker.check_model(modello)
    destinazione.parent.mkdir(parents=True, exist_ok=True)
    onnx.save(modello, str(destinazione))


def lancia_onnx2tf(onnx_in: Path, cartella: Path, extra: list[str]) -> list[str]:
    """Lancia onnx2tf in `cartella`, su una COPIA dell'ONNX (lo strumento semplifica il file in ingresso sul posto).

    Due accorgimenti: (1) onnx2tf chiama il programma `onnxsim`, che sta accanto a python nell'ambiente: lo si mette nel PATH del processo;
    (2) per i suoi controlli scarica da GitHub e da un sito di terzi un file di numeri di prova (`calibration_image_sample_data_...npy`) se
    non lo trova nella cartella di lavoro: lo si scrive qui, con numeri nostri e fissi, cosi' non apre la rete.
    """
    import os

    import numpy as np

    if cartella.exists():
        shutil.rmtree(cartella)
    cartella.mkdir(parents=True)
    copia = cartella / "ingresso" / onnx_in.name
    copia.parent.mkdir()
    shutil.copy2(onnx_in, copia)
    prova = np.random.RandomState(20261027).rand(20, 128, 128, 3).astype(np.float32)
    np.save(cartella / "calibration_image_sample_data_20x128x128x3_float32.npy", prova)
    comando = [sys.executable, "-I", "-c", "import sys, onnx2tf; sys.argv[0] = 'onnx2tf'; onnx2tf.main()",
               "-i", str(copia), "-o", str(cartella / "uscita"), "-coion", "-osd"] + extra
    ambiente = dict(os.environ)
    ambiente["PATH"] = str(Path(sys.executable).parent) + os.pathsep + ambiente.get("PATH", "")
    ambiente["TF_CPP_MIN_LOG_LEVEL"] = "2"
    inizio = time.time()
    esito = subprocess.run(comando, cwd=str(cartella), env=ambiente, capture_output=True, text=True, encoding="utf-8", errors="replace")
    (cartella / "onnx2tf.log").write_text(esito.stdout + "\n--- stderr ---\n" + esito.stderr, encoding="utf-8")
    if esito.returncode != 0:
        sys.exit(f"onnx2tf e' fallito ({esito.returncode}); vedi {cartella / 'onnx2tf.log'}")
    print(f"  onnx2tf: {time.time() - inizio:.0f} s")
    return comando[3:]


USCITE_YUNET = [f"{k}_{s}" for k in ("cls", "obj", "bbox", "kps") for s in (8, 16, 32)]
DESCRIZIONI = {
    "yunet-2023mar": "YuNet 2023mar (OpenCV Zoo, MIT), ingresso 1x1024x1024x3 BGR 0-255, 12 uscite grezze cls/obj/bbox/kps a passo 8/16/32",
    "yunet-2026may": "YuNet 2026may (OpenCV Zoo, MIT), ingresso 1x1024x1024x3 BGR 0-255, 12 uscite grezze cls/obj/bbox/kps a passo 8/16/32",
    "sface": "SFace 2021dec (OpenCV Zoo, Apache-2.0), ingresso 1x112x112x3 RGB 0-255, uscita 128 float",
}


def canonicalizza(origine: Path, destinazione: Path, nome: str, famiglia: str) -> bool:
    """Riscrive il `.tflite` di onnx2tf con le uscite nell'ordine e coi nomi dell'ONNX (cls_8, cls_16, cls_32, obj_8, ..., kps_32).

    onnx2tf lascia le uscite in un ordine qualunque (`PartitionedCall:N`); chi le legge dal telefono per posizione (P2) ha bisogno di un
    ordine fisso. Le uscite e l'ingresso prendono il nome dell'ONNX; i pesi e i calcoli non cambiano (la parita' si misura sul file finale).
    Ritorna False se il modello non ha una firma (la variante a pesi int8 dinamici di onnx2tf non ce l'ha): resta com'e'.
    """
    import numpy as np
    from tensorflow.lite.tools import flatbuffer_utils

    modello = flatbuffer_utils.read_model(str(origine))
    if not modello.signatureDefs:
        return False
    sottografo = modello.subgraphs[0]
    firma = modello.signatureDefs[0]
    uscite = {t.name.decode(): t.tensorIndex for t in firma.outputs}
    ingressi = {t.name.decode(): t.tensorIndex for t in firma.inputs}
    ordine = USCITE_YUNET if famiglia == "yunet" else sorted(uscite)
    if sorted(ordine) != sorted(uscite):
        sys.exit(f"{nome}: uscite {sorted(uscite)} diverse da quelle attese {sorted(ordine)}")
    sottografo.outputs = np.array([uscite[n] for n in ordine], dtype=np.int32)
    for n in ordine:
        sottografo.tensors[uscite[n]].name = n.encode()
    for n, i in ingressi.items():
        sottografo.tensors[i].name = n.encode()
    modello.description = DESCRIZIONI[nome].encode()
    destinazione.parent.mkdir(parents=True, exist_ok=True)
    flatbuffer_utils.write_model(modello, str(destinazione))
    return True


def converti(nome: str, radice: Path) -> None:
    file_onnx, famiglia = MODELLI[nome]
    originale = common.verifica_onnx(radice / "onnx", file_onnx)
    cartella = radice / "out" / "tflite" / nome
    sorgente = originale
    if famiglia == "yunet":
        sorgente = radice / "out" / "onnx-1024" / file_onnx
        fissa_ingresso_yunet(originale, sorgente)
        print(f"  ONNX a {LATO} px: {sorgente.name} {common.sha256_file(sorgente)}")
    extra = ["-odrqt"] if famiglia == "sface" else []
    comando = lancia_onnx2tf(sorgente, cartella, extra)
    prodotti = {}
    for tflite in sorted((cartella / "uscita").glob("*.tflite")):
        canonico = cartella / "finale" / tflite.name
        if canonicalizza(tflite, canonico, nome, famiglia):
            tflite = canonico
        prodotti[tflite.name] = {"dimensione": tflite.stat().st_size, "sha256": common.sha256_file(tflite),
                                 "cartella": "finale" if tflite == canonico else "uscita"}
    if not prodotti:
        sys.exit(f"{nome}: onnx2tf non ha prodotto nessun .tflite")
    rapporto = {
        "modello": nome,
        "ingresso_onnx": {"file": file_onnx, **common.impronte_note()["file"][file_onnx]},
        "onnx_di_conversione": {"file": sorgente.name, "sha256": common.sha256_file(sorgente)},
        "comando_onnx2tf": comando,
        "strumenti": versioni(),
        "tflite": prodotti,
    }
    (cartella / "conversion.json").write_text(json.dumps(rapporto, indent=2, ensure_ascii=False), encoding="utf-8")
    for n, v in prodotti.items():
        print(f"  {n}: {v['dimensione']} byte, {v['sha256'][:16]}...")


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--root", help="cartella dei modelli dei volti, fuori dal repository (o MCG_FACES_ROOT)")
    ap.add_argument("--only", nargs="+", choices=sorted(MODELLI), help="solo questi modelli (predefinito: tutti)")
    args = ap.parse_args()
    radice = common.radice_modelli(args.root)
    for nome in args.only or sorted(MODELLI):
        print(nome)
        converti(nome, radice)


if __name__ == "__main__":
    main()
