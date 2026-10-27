"""Le somiglianze vere di SFace sul set pubblico (Fase 8, P11; docs/fase8-progetto.md §2.5 "I parametri", §5.3 punto 1).

    python -I misure_pubbliche.py --root "C:\\...\\mcg-models\\faces" [--scrivi <osservazioni.tsv>]

Prende i volti dei ritratti di pubblico dominio (uno per ritratto, a piena risoluzione) e quelli incollati nelle composizioni A e B di P1 (lato corto
24-160 px: i piccoli sono i WEAK), li allinea con i cinque punti, li passa da SFace (ONNX fp32 di P1: lo stesso vettore del `.tflite` float16, coseno
>= 0,999995) e ne ricava le **osservazioni** che la regola delle soglie del §2.5 vuole (`FacesEvalThresholds`): per ogni volto e per ogni persona (un
soggetto = un gruppo) la somiglianza del volto al gruppo come la calcola il raggruppamento (media dei 3 coseni piu' alti con gli esemplari, al piu'
16, scelti per essere diversi), quella alla seconda persona, e se il volto e' davvero di quella persona. Per la persona di un volto gli esemplari non
includono il ritratto da cui il volto viene (una copia piu' piccola dello stesso ritratto non vale come "stessa persona"): contano solo gli altri
ritratti della stessa persona (autoritratti a eta' diverse).
Scrive solo numeri: sigle di gruppo (g01...), livello, punteggi e un si'/no. Nessuna immagine, nessun vettore, nessun nome. Il file va in
`core-faces/src/test/resources/.../eval/osservazioni-pubbliche.tsv`, che lo legge. Stampa anche le distribuzioni delle coppie (stessa persona,
persone diverse) per `docs/decisioni.md`.
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

import numpy as np

QUI = Path(__file__).resolve().parent
sys.path.insert(0, str(QUI.parent.parent / "models" / "faces"))
import common  # noqa: E402
import compose  # noqa: E402
import engines  # noqa: E402

LIVELLO_CORE = 48.0  # lato corto minimo di un volto CORE (D19)
MASSIMO_ESEMPLARI = 16
PIU_ALTI = 3
SOTTO_NON_SI_SCRIVE = 0.30  # un punteggio sotto questo non cambia nessuna soglia sopra 0,45: non si scrive (ma ogni gruppo ha almeno una riga)


def carica_volti(radice: Path, sface) -> list[dict]:
    """I volti con il vettore normalizzato: {id, soggetto, ritratto, core, v}. Solo i soggetti di una persona (sNN)."""
    import cv2

    volti = []
    ritratti = {v["ritratto"]: v for v in compose.volti_dei_ritratti(radice) if v["soggetto"].startswith("s")}
    for etichetta, volto in sorted(ritratti.items()):
        img = cv2.imread(str(radice / "ritratti" / f"{etichetta}.jpg"))
        v = common.normalizza(sface.vettore(common.volto_allineato(img, volto["punti"])))
        volti.append({"id": f"{etichetta}:piena", "soggetto": volto["soggetto"], "ritratto": etichetta, "core": True, "v": v})
    base = radice / "out" / "composizioni"
    verita = json.loads((base / "verita.json").read_text(encoding="utf-8"))["immagini"]
    for nome in sorted(n for n in verita if n.startswith(("A/", "B/"))):
        img = cv2.imread(str(base / nome))
        for k, f in enumerate(verita[nome]):
            if not f["soggetto"].startswith("s"):
                continue
            v = common.normalizza(sface.vettore(common.volto_allineato(img, np.array(f["punti"]))))
            volti.append({"id": f"{nome}:{k}", "soggetto": f["soggetto"], "ritratto": f["ritratto"], "core": f["lato_corto"] >= LIVELLO_CORE, "v": v})
    return volti


def esemplari(indici: list[int], vettori: np.ndarray, core: np.ndarray) -> list[int]:
    """Gli esemplari di §2.5: al piu' 16 volti CORE (se non ce ne sono, qualunque), il piu' vicino al centroide e poi via via il piu' lontano dai gia' scelti."""
    pool = [i for i in indici if core[i]] or list(indici)
    if not pool:
        return []
    c = vettori[pool].mean(axis=0)
    c /= np.linalg.norm(c)
    scelti = [max(pool, key=lambda i: (float(vettori[i] @ c), -i))]
    while len(scelti) < min(MASSIMO_ESEMPLARI, len(pool)):
        resto = [i for i in pool if i not in scelti]
        scelti.append(min(resto, key=lambda i: (max(float(vettori[i] @ vettori[s]) for s in scelti), i)))
    return scelti


def somiglianza(i: int, ex: list[int], vettori: np.ndarray) -> float | None:
    valori = sorted((float(vettori[i] @ vettori[e]) for e in ex if e != i), reverse=True)[:PIU_ALTI]
    return sum(valori) / len(valori) if valori else None


def osservazioni(volti: list[dict]) -> tuple[list[tuple], dict]:
    vettori = np.stack([v["v"] for v in volti])
    core = np.array([v["core"] for v in volti])
    soggetti = sorted({v["soggetto"] for v in volti})
    di = {s: [i for i, v in enumerate(volti) if v["soggetto"] == s] for s in soggetti}
    fissi = {s: esemplari(di[s], vettori, core) for s in soggetti}
    senza_ritratto: dict[tuple, list[int]] = {}
    righe = []
    for i, f in enumerate(volti):
        punti: dict[str, float] = {}
        for s in soggetti:
            if s == f["soggetto"]:
                chiave = (s, f["ritratto"])
                if chiave not in senza_ritratto:
                    senza_ritratto[chiave] = esemplari([j for j in di[s] if volti[j]["ritratto"] != f["ritratto"]], vettori, core)
                ex = senza_ritratto[chiave]
            else:
                ex = fissi[s]
            valore = somiglianza(i, ex, vettori)
            if valore is not None:
                punti[s] = valore
        for s, valore in punti.items():
            altre = [x for q, x in punti.items() if q != s]
            righe.append((soggetti.index(s) + 1, "core" if f["core"] else "weak", valore, max(altre) if altre else None, s == f["soggetto"]))
    # Un punteggio basso non cambia nessuna soglia utile: si scrive solo sopra SOTTO_NON_SI_SCRIVE, piu' una riga per gruppo che altrimenti sparirebbe.
    visti = {g for g, _, p, _, _ in righe if p >= SOTTO_NON_SI_SCRIVE}
    scelte = [r for r in righe if r[2] >= SOTTO_NON_SI_SCRIVE]
    for g in sorted({r[0] for r in righe} - visti):
        scelte.append(max((r for r in righe if r[0] == g), key=lambda r: r[2]))
    # Le coppie volto-volto (non i punteggi ai gruppi) per le distribuzioni: stessa persona (ritratti diversi) e persone diverse.
    sim = vettori @ vettori.T
    coppie = {"stessa_persona": [], "persone_diverse": []}
    for tier, nome in ((True, "core"), (False, "weak")):
        for i in range(len(volti)):
            for j in range(i + 1, len(volti)):
                if core[i] != tier or core[j] != tier or volti[i]["ritratto"] == volti[j]["ritratto"]:
                    continue
                coppie["stessa_persona" if volti[i]["soggetto"] == volti[j]["soggetto"] else "persone_diverse"].append((nome, float(sim[i, j])))
    return scelte, coppie


def distribuzione(valori: list[float]) -> dict:
    if not valori:
        return {"n": 0}
    a = np.array(valori)
    return {"n": len(a), "media": round(float(a.mean()), 4), "min": round(float(a.min()), 4), "p5": round(float(np.percentile(a, 5)), 4),
            "p50": round(float(np.percentile(a, 50)), 4), "p95": round(float(np.percentile(a, 95)), 4), "p99": round(float(np.percentile(a, 99)), 4),
            "max": round(float(a.max()), 4)}


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--root", help="cartella dei modelli dei volti, fuori dal repository (o MCG_FACES_ROOT)")
    ap.add_argument("--scrivi", help="dove scrivere le osservazioni (di norma core-faces/src/test/resources/.../eval/osservazioni-pubbliche.tsv)")
    args = ap.parse_args()
    radice = common.radice_modelli(args.root)
    sface = engines.SFaceOnnx(common.verifica_onnx(radice / "onnx", "face_recognition_sface_2021dec.onnx"))
    volti = carica_volti(radice, sface)
    righe, coppie = osservazioni(volti)
    soggetti = {v["soggetto"] for v in volti}
    sommario = {"volti": len(volti), "core": sum(v["core"] for v in volti), "weak": sum(not v["core"] for v in volti), "persone": len(soggetti),
                "righe": len(righe), "giuste": sum(r[4] for r in righe), "sbagliate": sum(not r[4] for r in righe)}
    for nome in ("stessa_persona", "persone_diverse"):
        for tier in ("core", "weak"):
            sommario[f"{nome}_{tier}"] = distribuzione([c for t, c in coppie[nome] if t == tier])
    sommario["persone_diverse_sopra_0363"] = sum(c >= 0.363 for _, c in coppie["persone_diverse"])
    sommario["stessa_persona_sotto_0363"] = sum(c < 0.363 for _, c in coppie["stessa_persona"])
    print(json.dumps(sommario, indent=1))
    if args.scrivi:
        out = ["# Osservazioni per la regola delle soglie (FacesEvalThresholds) sul set pubblico: ritratti di pubblico dominio e composizioni di P1.",
               "# Generato da tools/fixtures/faces/misure_pubbliche.py con SFace (ONNX fp32 di P1). Solo numeri: gruppo (una persona = un gruppo), livello,",
               "# somiglianza al gruppo, somiglianza alla seconda persona (vuota se non c'e'), se il volto e' davvero di quella persona.",
               "gruppo\tlivello\tpunteggio\tseconda\tgiusto"]
        for g, livello, p, s, giusto in righe:
            out.append(f"g{g:02d}\t{livello}\t{p:.4f}\t{'' if s is None else f'{s:.4f}'}\t{int(giusto)}")
        Path(args.scrivi).write_text("\n".join(out) + "\n", encoding="utf-8", newline="\n")
        print(f"scritte {len(righe)} righe in {args.scrivi}")


if __name__ == "__main__":
    main()
