# Formato dei riferimenti e delle soglie (Fase 3, P9; guardia della Fase 4A, P10)

I file di questa cartella sono letti da `tools/bench/compare.ps1`. Tre cose diverse, da non confondere:

| File | Chi lo scrive | Che cos'è |
|---|---|---|
| `benchmark/soglie.json` | a mano (P9, poi chi cambia le soglie del §2.6) | le regole: quali prove, quali metriche, quali limiti |
| `docs/bench/riferimento-<dispositivo>.json` | `compare.ps1 -Accept`, dopo una misura buona | i **numeri accettati** di un dispositivo, contro cui si calcola la regressione |
| `docs/bench/riferimento-<dispositivo>-fase3.json` | `compare.ps1 -ExtractReference`, dai JSON grezzi del 13/10 (P10) | le misure della Fase 3, **non accettate** (le soglie fallivano), con cui la guardia della Fase 4A confronta finché non c'è un riferimento accettato. Stesso formato, in più `origine` |
| `…/connected_android_test_additional_output/…/*-benchmarkData.json` | Macrobenchmark (AGP li copia dal dispositivo) | i risultati dell'ultima corsa: ingresso di `compare.ps1` |

## 1. `docs/bench/riferimento-<dispositivo>.json`

`<dispositivo>` è una chiave di `soglie.json`: `s25`, `tab-s9` (gli unici con riferimenti veri). Per gli emulatori **non esiste** riferimento: non si accetta niente da un emulatore.

```json
{
  "formato": 1,
  "dispositivo": "s25",
  "modello": "SM-S931B",
  "sdk": 36,
  "frequenzaHz": 120,
  "data": "2026-11-02",
  "variante": "benchmarkRelease",
  "prove": {
    "scroll_days_square": { "jankPercent.mean": 0.3, "frameDurationCpuMs.P95": 5.2 },
    "pinch":              { "jankPercent.mean": 0.6, "frameDurationCpuMs.P95": 6.0 },
    "coldStart":          { "timeToFullDisplayMs.p90": 860 },
    "viewerOpen":         { "viewerOpenMs.p90": 80, "viewerOpenMaxFrameMs.max": 12.4 }
  }
}
```

- `prove`: per ogni prova (il nome del metodo di test) le coppie `metrica.statistica` che le regole di `soglie.json` usano. Si scrivono **tutte** quelle presenti, anche le assolute: un riferimento serve a leggere dove stavamo, non solo a confrontare.
- Le statistiche sono quelle di `soglie.json`: `P50`/`P90`/`P95`/`P99` per le metriche campionate (un valore per fotogramma: `FrameTimingMetric`), `min`/`max`/`median`/`mean`/`p<N>` calcolati sulle iterazioni per le altre (percentile "nearest rank"). `jankPercent` (`FrameJankMetric`, un valore per iterazione) si confronta come `mean`: vedi `README.md` §2-bis.
- `frequenzaHz` è il massimo letto in sola lettura da `check.ps1 -Bench`; `data`, `modello` e `sdk` servono a capire a che macchina si riferisce il numero.
- **Un esempio completo** (anche di risultati e di regressione) è in `tools/bench/samples/`, ed è quello che prova `compare.ps1 -SelfTest`.

### Quale riferimento vale

`riferimento-<dispositivo>.json` se esiste (l'ultimo accettato dall'utente), altrimenti `riferimento-<dispositivo>-fase3.json`; con `-NoGuard` solo il primo. Il riferimento della Fase 3 si può rifare dai JSON grezzi (`README.md` §3).

### Quando si aggiorna

- **Mai per far passare un test.** Una regressione si corregge. Si accetta un riferimento nuovo quando il codice è cambiato **di proposito** (per esempio un livello della griglia con più celle) e la misura è buona, con il motivo nel messaggio del commit.
- Prima misura di un dispositivo: `compare.ps1 -Accept` dopo una corsa senza soglie fallite. Fino ad allora le regressioni risultano `NUOVO` (non falliscono).
- Il riferimento si rifà **per dispositivo e per versione di Android**: un aggiornamento di One UI sposta i numeri, si rimisura e si accetta di nuovo.

## 2. `benchmark/soglie.json`

```json
{
  "formato": 1,
  "dispositivi": { "s25": { "nome": "...", "classe": "telefono", "modelli": ["SM-S931"], "frequenzaMinimaHz": 120 } },
  "scenari": [
    {
      "id": "scroll",
      "prove": "^scroll_(years|months|days|all)_(square|justified)$",
      "proveAttese": ["scroll_years_square", "..."],
      "classiDispositivo": ["telefono", "tablet"],
      "validita":   { "metrica": "libraryShown", "statistica": "min", "minimo": 1 },
      "assolute":   [ { "metrica": "frameOverrunMs", "statistica": "P99", "massimo": 0.0 } ],
      "regressione":[ { "metrica": "frameDurationCpuMs", "statistica": "P95", "aumentoMassimo": 0.10 } ]
    }
  ]
}
```

| Campo | Significato |
|---|---|
| `dispositivi.<chiave>.modelli` | prefissi di `ro.product.model` (`SM-S931` vale per `SM-S931B`, `SM-S931U`…); gli emulatori si riconoscono dal modello (`sdk_gphone…`) e hanno `"emulatore": true` |
| `dispositivi.<chiave>.classe` | `telefono` o `tablet`: decide quali scenari si applicano (lo zoom a 200 MP solo al tablet) |
| `scenari[].prove` | espressione regolare sul nome della prova; `proveAttese` elenca i nomi che devono esserci (quelli che mancano risultano `MANCA`, con il motivo del rapporto JUnit se la prova è stata saltata) |
| `validita` | la prova ha misurato quello che doveva: per l'avvio a freddo, la Libreria era visibile. Se non lo è, la prova risulta non valida e **non si confronta** con la soglia |
| `assolute[]` | `massimo` (≤), `massimoEsclusivo` (<) o `minimo` (≥) sul valore di `metrica`/`statistica`. `massimo: null` con `calibrazione` = tetto da calibrare con la prima misura (`compare.ps1 -Calibrate`) |
| `regressione[]` | `aumentoMassimo`: frazione massima di aumento sul riferimento accettato (`0.10` = +10%). Un riferimento non positivo non si confronta (`N/A`). `arrotondaSu` (P10): passo in unità della metrica; il riferimento si porta al passo più vicino e il limite si arrotonda per eccesso (TTFD 680,7 → 681 × 1,10 = 749,1 → **750**; `viewerOpenMs` 21,1 → 21 × 1,15 = 24,15 → **25**, come scrive il §5.6) |
| `guardia[]` (P10) | come `regressione[]`, ma si valuta solo con la guardia attiva (un riferimento, niente `-Smoke`, niente `-NoGuard`) e per metriche che le soglie assolute non coprono o che già fallivano: `aumentoMassimo`; `pavimento` (scarto assoluto sotto cui non si distingue dal rumore: il limite è il maggiore fra `ref × (1 + aumento)` e `ref + pavimento`); `mantieniSotto` (se il riferimento era sotto questo valore, il limite non lo supera) |
| `mista` (P10) | `true` = la prova deve girare sulla libreria mista: la metrica `libraryNasShare` (min) deve valere almeno `mistaMinimo` (0,55); sotto, la prova è `NON VALIDA`. Se la metrica manca (risultati vecchi) non si controlla |

Esiti di `compare.ps1`: `OK`, `FAIL`, `MANCA` (prova attesa senza risultato), `CAL` (tetto da calibrare), `NUOVO` (nessun riferimento), `NOTO` (P10: soglia assoluta già violata dal riferimento: non fa fallire, vale la `guardia`; `-Accept` si rifiuta), `N/A`. Codice di uscita: `0` senza `FAIL` (e, con `-RequireAll`, senza `MANCA` né `CAL`); `1` altrimenti; `2` per errori d'uso o file illeggibili.

## 3. I risultati di Macrobenchmark (ingresso)

`*-benchmarkData.json`, un file per corsa, con `context` (modello, SDK, fingerprint…) e `benchmarks[]`:

- `name`, `className`, `repeatIterations`;
- `metrics.<nome>`: `minimum`, `maximum`, `median` e `runs[]` (un valore per iterazione): `timeToInitialDisplayMs`, `timeToFullDisplayMs`, `viewerOpenMs`, `pssPeakKb`, `processAlive`, `libraryShown`, `frameCount`; dalla Fase 4A anche `libraryNasShare` (0-1), `libraryRows`, `libraryReplicaBlobs`, `libraryNasFirst` (ogni prova), `libraryPssKb`, `libraryJavaHeapKb`, `libraryNativeHeapKb`, `libraryGraphicsKb` (`libraryMemory`);
- `sampledMetrics.<nome>`: `P50`, `P90`, `P95`, `P99` su tutti i fotogrammi di tutte le iterazioni: `frameDurationCpuMs`, `frameOverrunMs`.

Le metriche proprie (`viewerOpenMs`, `pssPeakKb`, `processAlive`, `libraryShown`, `libraryNasShare`, `libraryPssKb`, …) sono in `BenchMetrics.kt`.
