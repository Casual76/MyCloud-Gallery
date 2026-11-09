# Prestazioni misurate (Fase 3, P9; guardia della Fase 4A, P10)

> Fonti: `docs/fase3-progetto.md` D21, §2.6 (tabella degli scenari e delle soglie), §4.1 (tappa M1), §4.3 (riga P9), §5.5 (F11, F12); `docs/fase4-progetto.md` §5.6 (libreria mista e guardia), §4.4 (riga P10).
> Qui sta **come si misura**. I **numeri** accettati stanno in `docs/bench/riferimento-<dispositivo>.json` (formato in `formato-riferimenti.md`)
> e le soglie in `benchmark/soglie.json`. La parte di M1 (la griglia vera su `SyntheticLibrary`) la scrive P7 in `docs/bench/M1.md`.

## 1. Che cosa c'è

| Cosa | Dove |
|---|---|
| Scenari Macrobenchmark e generatore del Baseline Profile | `benchmark/src/main/kotlin/dev/pampa/mycloudgallery/benchmark/` |
| Soglie assolute e regole di regressione | `benchmark/soglie.json` |
| Dataset della variante di misura (100 mila righe, dalla Fase 4A il 60% solo-NAS con la replica sintetica) | `app/src/benchmarkRelease/` (solo lì: mai in release) |
| Guardia della Fase 4A: riferimento del 13/10 | `docs/bench/riferimento-s25-fase3.json` (§2-ter) |
| Confronto con soglie e riferimento | `tools/bench/compare.ps1` (con `-SelfTest` si prova senza dispositivo) |
| Il passo `-Bench` e il controllo del profilo | `tools/check.ps1` (`-Bench`, `-BenchSerial`, `-BenchStrict`) |
| Profilo di base su `benchmarkRelease` | `tools/bench/generate-profile.ps1` |
| Media di prova per l'emulatore | `tools/bench/seed-media.ps1` |
| I budget JVM (complessità, contatori) | dentro `galleryTest`, non qui |
| Ricordi e mappa (Fase 9, P12): le due classi di prove, i 14 scenari, il dataset con coordinate, anniversari e scaffale, il pilota della generazione | §10 |

## 2. Gli scenari

Tutti con `CompilationMode.Partial(BaselineProfileMode.Require)` (si cambia con `benchCompilation`, vedi `BenchConfig`). Il nome della prova è la chiave di `soglie.json`.

| Prova (classe) | Cosa fa | Metrica e soglia | Regressione |
|---|---|---|---|
| `scroll_<livello>_<stile>` (`LibraryScrollBenchmark`), 8 prove: `years/months/days/all` × `square/justified` | App appena avviata, livello e stile impostati, fling avanti e indietro nel tempo su 100 mila elementi | `FrameJankMetric`: `jankPercent` medio ≤ 1% (fotogrammi davvero in ritardo, vedi §2-bis) | P95 `frameDurationCpuMs` +10% |
| `pinch` (`LibraryPinchBenchmark`) | 10 pinch continui (5 chiusure + 5 aperture) fra i livelli | `jankPercent` medio ≤ 1% | P95 `frameDurationCpuMs` +10% |
| `coldStart` (`StartupBenchmark`) | `StartupMode.COLD`, 15 ripetizioni: cache delle pipeline grafiche e del disco buttate, il primo avvio dopo un'installazione o un aggiornamento | **primo disegno utile** `libraryUsefulMs` p90 < 750 ms; caricamento completo `timeToFullDisplayMs` p90 < 1500 ms (§2-quater) | p90 +10% di tutti e due |
| `coldStartUsual` (`StartupBenchmark`), **nuova** | Avvio a freddo di tutti i giorni: processo nuovo, cache delle pipeline e del disco calde; 15 ripetizioni | `libraryUsefulMs` p90 < 400 ms; `timeToFullDisplayMs` p90 < 1500 ms | p90 +10% |
| `collectionsPage`, `searchPage`, `albumPage`, `peoplePage` (`PagesBenchmark`), **nuove** | Primo ingresso dopo un avvio a freddo nelle Raccolte, in Cerca a riposo, in un album, in Persone; 10 iterazioni | dal tocco al primo fotogramma con l'ultimo stato o lo scheletro (`raccoltePageMs`, `cercaPageMs`, `albumPageMs`, `personePageMs`) p90 < 150 ms | p90 +15% |
| `viewerOpen` (`ViewerBenchmark`) | Tocco su una miniatura, attesa, Indietro; 12 iterazioni | sezione di traccia `MCG:viewerOpen` p90 < 100 ms; `viewerOpenMaxFrameMs` (il fotogramma più lungo nei primi 500 ms dell'apertura) massimo ≤ 16,7 ms | p90 +15% |
| `zoom200Mp` (`Zoom200MpBenchmark`), solo tablet | Apre la foto più grande (la riga più recente), 5 cicli di zoom e pan sui quattro angoli | `processAlive` = 1 (nessun OOM); `pssPeakKb` sotto il tetto calibrato alla prima misura | PSS +15% |
| `coldStartNasFirst` (`NasLibraryBenchmark`), **nuova 4A** | Avvio a freddo con la prima pagina fatta solo di miniature del NAS (profilo «NAS prima», §5) | TTFD p90 < 1000 ms (proposta di P10: il §5.6 non fissa una soglia) | p90 +10% |
| `viewerOpenNas` (`NasLibraryBenchmark`), **nuova 4A** | Apertura di un media solo-NAS con l'HQ in replica (cella al centro del profilo «NAS prima»); 12 iterazioni | `MCG:viewerOpen` p90 < 100 ms (§5.6) | p90 +15% |
| `libraryMemory` (`LibraryMemoryBenchmark`), **nuova 4A** | 5 avvii a freddo, 2 s di quiete, `dumpsys meminfo` | `libraryPssKb`, `libraryJavaHeapKb`, `libraryNativeHeapKb`, `libraryGraphicsKb` (informative) | PSS +15% |

Dalla Fase 4A **tutte le prove della Fase 3 girano sulla libreria mista** (§5): la metrica `libraryNasShare` (quota di righe solo-NAS, 0-1) viaggia con ogni prova e `compare.ps1` rifiuta di confrontare con la guardia una prova sotto il 55% (`NON VALIDA`: sarebbe un dataset della Fase 3).

- **Se una cosa non c'è, la prova si salta con il motivo; non si inventa un numero.** Una prova senza Libreria raggiungibile, senza menu dello stile o senza la foto da 200 MP risulta `MANCA` in `compare.ps1`, con la riga del rapporto JUnit che dice perché.
- L'avvio a freddo gira anche senza Libreria (misura l'avvio verso l'onboarding), ma la metrica `libraryShown` lo marca **non valido** e `compare.ps1` non lo confronta con i 1000 ms.
- Il tetto di PSS dello zoom è `null` in `soglie.json` finché non c'è la prima misura sul Tab S9: `compare.ps1 -Calibrate` lo scrive (misura + 10%, a multipli di 1 MB).
- Gli id dell'interfaccia di cui le prove si fidano (descrizioni delle celle, nomi dei livelli, menu dello stile) sono in **un solo posto**: `BenchLabels` in `BenchUi.kt`. Confermati sulla Libreria vera il 13/10: celle `Foto, …`/`Video, …`, segmenti `Anni`/`Mesi`/`Giorni`/`Tutte`, menu `Vista` con `Quadrata`/`Proporzioni originali`.
- **Perché le otto prove di scorrimento fallivano** (S25, 13/10, «La griglia non c'e' per cambiare livello»): mentre il menu della vista è aperto *e mentre si richiude*, il `FluidGlassModalHost` nasconde la pagina all'accessibilità (`fluidGlassModalObscured`), e UiAutomator non vedeva la lista. Ora `BenchUi.awaitGrid` aspetta che la griglia torni, `selectStyle` aspetta che il menu sparisca e preme Indietro solo se il menu è davvero aperto (a menu chiuso Indietro chiudeva l'app), il segmento di un livello si ricava dagli altri se non si vede, e la griglia si ricerca a ogni fling (`StaleObjectException` al livello Tutte). Il fling «verso i più vecchi» usava `Direction.DOWN`, che in UiAutomator è la direzione del *contenuto*: dal fondo della Libreria non si muoveva niente; ora è `UP`.
- **Dispositivo condiviso.** Ogni gesto e ogni Indietro passano da `BenchUi.ensureForeground`: se in primo piano non c'è l'app sotto misura la prova si ferma invece di toccare un'altra app (il 13/10 un'altra sessione ha aperto la sua app sull'S25 nel mezzo di una prova).

## 2-bis. Il jank vero: `FrameJankMetric`

`frameOverrunMs` di `FrameTimingMetric` misura quanto un fotogramma finisce dopo la sua scadenza *teorica*. A 120 Hz, quando l'app produce un fotogramma dopo l'altro (scorrimento, pinch), BufferQueue tiene un buffer in coda e il fotogramma successivo parte già "in ritardo" rispetto al suo vsync anche se SurfaceFlinger lo presenta in orario: FrameTimeline lo chiama **Buffer Stuffing**. Non è uno scatto: nessun fotogramma resta sullo schermo più a lungo. Sull'S25 (13/10) erano circa 740 fotogrammi su 800 nel pinch, con `frameOverrunMs` P50 +3,1 ms e P99 +14,9 ms, mentre i fotogrammi davvero in ritardo erano lo 0,4-1,2%.

`FrameJankMetric` (in `BenchMetrics.kt`) legge FrameTimeline dalla traccia Perfetto, dall'inizio del blocco misurato (la sezione `measureBlock`) alla fine della traccia, e solo per il processo dell'app. Solo l'inizio: la traccia si ferma subito dopo il blocco, e la fine della sezione si perde quando il buffer di Perfetto perde pacchetti (sull'S25 una sezione di 0,97 s per un pinch di 7 s).

```sql
SELECT COUNT(*) AS total,
       SUM(CASE WHEN a.jank_type GLOB '*App Deadline Missed*' OR a.present_type = 'Dropped Frame' THEN 1 ELSE 0 END) AS late
FROM actual_frame_timeline_slice a JOIN process p USING(upid)
WHERE p.name = '<pacchetto>' AND a.ts >= <inizio di measureBlock>
```

| Misura | Che cos'è |
|---|---|
| `jankPercent` | `late / total × 100`: i fotogrammi che SurfaceFlinger attribuisce all'app come scadenza mancata (anche se insieme ad altri tipi, per esempio "App Deadline Missed, Buffer Stuffing") o che non arrivano allo schermo (`Dropped Frame`). Buffer Stuffing da solo, "Prediction Error", "SurfaceFlinger Scheduling" e "Display HAL" non contano: non sono colpa dell'app |
| `jankFrames`, `timelineFrames` | Il numeratore e il denominatore, per iterazione |
| `maxFrameCpuMs` | Il fotogramma più lungo, dall'inizio del `Choreographer#doFrame` alla fine del `DrawFrames` del RenderThread con lo stesso id di vsync (come `frameDurationCpuMs`) |

La soglia (`benchmark/soglie.json`) è `jankPercent` **medio** sulle iterazioni ≤ 1% per gli otto scorrimenti e il pinch. È una `TraceMetric` di benchmark-macro 1.4.1, quindi gira sul dispositivo con il trace processor della libreria: niente calcoli sull'host.

Per l'apertura del viewer `ViewerOpenMetric` usa le stesse query nella finestra di 500 ms che parte dalla sezione `MCG:viewerOpen` (l'espansione dura 300 ms): `viewerOpenMaxFrameMs` (il fotogramma più lungo) e `viewerOpenJankFrames`. La soglia è "nessun fotogramma oltre 16,7 ms" (due vsync) in nessuna delle 12 aperture.

Per guardare una traccia a mano: `trace_processor_shell.exe -q query.sql traccia.perfetto-trace` (Perfetto v58.2, in `%USERPROFILE%\.mycloudgallery\tools\perfetto\`), con la query qui sopra o con `select a.jank_type, a.present_type, count(*) ...` raggruppata per tipo.

## 2-ter. La guardia della Fase 4A (P10, `docs/fase4-progetto.md` §5.6)

La Fase 4 non deve peggiorare la fluidità. Le misure del 13/10 (`misure-s25-2026-10-13.md`, corse `final` e `final-startup`) sono il **riferimento** della guardia: `docs/bench/riferimento-s25-fase3.json`, estratto dai JSON grezzi con `compare.ps1 -ExtractReference` (non è un riferimento «accettato»: il 13/10 `-Accept` si era rifiutato per le soglie fallite). Se l'utente ne accetta uno nuovo (`riferimento-s25.json`, per esempio dopo il filone grafico) è quello che vale: **«la guardia confronta sempre con l'ultimo riferimento accettato dall'utente»**.

| Verifica | Limite | Dal 13/10 |
|---|---|---|
| TTFD `coldStart`, p90 | 749,1 → **≤ 750 ms** (regressione +10%, `arrotondaSu`) | 681 |
| `viewerOpenMs`, p90 | 24,15 → **≤ 25 ms** (+15%) | 21 |
| `jankPercent` di scorrimento (8 prove) e pinch, media | ≤ misura + 10% relativo **e** ≤ 1% dove già lo era (`mantieniSotto`), con un pavimento di 0,15 punti | 0,08-0,66% quadrata, 0,22-3,56% giustificata, 0,54% pinch |
| `viewerOpenMaxFrameMs`, massimo | ≤ misura + 10% | 97,3 ms (telefono caldo) |
| `frameDurationCpuMs` P95 | ≤ misura + 10% (regola esistente) | 9,5-23 ms scorrimento, 14,1 pinch |

- **Le soglie che già fallivano** (scorrimento giustificato a mesi/anni/giorni, fotogramma più lungo dell'apertura) risultano `NOTO`, non `FAIL`: vale «non devono peggiorare». `-Accept` si rifiuta se ce n'è una (serve `-Force` e un motivo).
- **Il pavimento di 0,15 punti** è una scelta di P10: un +10% relativo su 0,08% sono 0,008 punti, meno di un fotogramma su 700 e sotto il rumore di una corsa (il 13/10 `years_justified` andava da 0,6 a 3,9% fra le iterazioni per la temperatura). Si toglie da `soglie.json` (`pavimento`) se l'utente vuole la regola alla lettera.
- **Nuove misure** senza riferimento (`coldStartNasFirst`, `viewerOpenNas`, `libraryMemory`): `NUOVO` fino alla prima corsa buona; poi `compare.ps1 -Accept` le fissa.
- La guardia si applica da sola sui dispositivi veri quando esiste un riferimento (`check.ps1 -Bench` non cambia); `-NoGuard` la spegne. Sugli emulatori (smoke) non c'è nessuna soglia.
- Il Tab S9 non ha un riferimento del 13/10: la prima corsa buona con la libreria mista (`-Accept`) lo diventa.
- Le misure del 15/10 sulla G2 con la libreria mista (pinch e scorrimento, cause e correzioni): `misure-s25-2026-10-15.md`.

## 2-quater. Avvio e pagine istantanei (28/10)

Il principio dell'utente: si vede **subito** qualcosa di vero (l'ultima Libreria, l'ultimo stato o lo scheletro di una pagina), il resto si carica
dopo. Due numeri per l'avvio, e uno per ogni pagina:

- **Primo disegno utile** (`libraryUsefulMs`, `LibraryUsefulMetric` in `AvvioMetrics.kt`): dall'inizio del lancio (`launching:`, lo stesso
  punto di `timeToInitialDisplayMs`) alla fine del primo fotogramma del RenderThread dopo il segno `MCG:avvio:libreriaUtile`, che l'app scrive
  la prima volta che **ogni cella in vista** ha la sua risposta (la miniatura in memoria, o un tentativo finito senza: quella cella resta sul
  segnaposto e riprova da sola). E' il numero che conta. Senza il segno la misura scrive `libraryUsefulMissing` = 1, mai un numero inventato.
- **Caricamento completo** (`timeToFullDisplayMs`, `reportFullyDrawn` come prima): soglia larga, 1.500 ms.
- I segni intermedi, utili per leggere dove va il tempo, viaggiano nel JSON come `avvio*Ms` dal lancio: `avvioIstantaneaMs` (istantanea
  pubblicata), `avvioSbloccatoMs` (blocco app superato), `avvioDestinazioneMs` (destinazione nota), `avvioGrigliaMs` (prima griglia composta),
  `avvioRimandatiMs` (parte il lavoro rimandato a dopo il primo disegno), `libraryLiveMs` (indice riconciliato con il catalogo).
- **Pagine** (`PageOpenMetric`): la sezione asincrona `MCG:pagina:<nome>` che l'app apre al tocco e chiude al primo disegno della pagina, piu'
  il fotogramma che la segue. Album e Persone si saltano finche' la variante di misura non li monta (L287, nessun NAS).

**Due avvii a freddo, due casi.** `StartupMode.COLD` butta prima di ogni avvio la cache delle pipeline grafiche (un broadcast al
`ProfileInstallReceiver`) e quella delle pagine del kernel: sull'S25 i primi fotogrammi ricompilano circa 70 pipeline Vulkan, 250-350 ms di
RenderThread. Un utente ci passa solo dopo un'installazione o un aggiornamento: e' `coldStart`, il caso peggiore. Tutti gli altri giorni la cache
c'e': e' `coldStartUsual` (`startupMode = null`, il processo si uccide nel `setupBlock`, `StartupTimingMetric` lo legge come freddo). Si puo'
anche far girare `coldStart` senza buttare le pipeline con l'argomento `-Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.dropShaders.enable=false`.

**Lo stesso metodo della bisezione** per confrontare: nella stessa corsa prima `LibraryPinchBenchmark` (la Libreria aperta, il dataset pronto)
e poi le prove dell'avvio. Con l'app di misura appena installata `coldStart` misura la pagina iniziale (`libraryShown` = 0) e non vale; dopo 10
minuti di scorrimento il telefono e' caldo e l'avvio esce gonfiato (il 1.342 ms del 27/10).

```powershell
$env:ANDROID_SERIAL = "RFCY61FZDHW"
.\gradlew.bat --no-daemon --max-workers=2 :benchmark:connectedBenchmarkReleaseAndroidTest `
  "-Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.enabledRules=macrobenchmark" `
  "-Pandroid.testInstrumentationRunnerArguments.class=dev.pampa.mycloudgallery.benchmark.LibraryPinchBenchmark,dev.pampa.mycloudgallery.benchmark.StartupBenchmark,dev.pampa.mycloudgallery.benchmark.PagesBenchmark"
```

Le tracce Perfetto si possono leggere anche sul PC senza scaricare niente: per questo pacchetto e' bastato un lettore minimo del formato in
Python (sezioni di atrace, nomi dei thread, tempi di CPU dallo scheduler), rimasto fra gli script di lavoro fuori dal repository (vedi
`docs/decisioni.md`, "Avvio e pagine istantanei"). Se serve ancora, va portato in `tools/bench/`.

## 3. Come si usa

### Senza dispositivo (sempre)

```powershell
powershell -ExecutionPolicy Bypass -File tools\check.ps1                 # include: compila scenari e dataset, controllo del profilo, -Bench in SKIP
powershell -ExecutionPolicy Bypass -File tools\bench\compare.ps1 -SelfTest
```

### Smoke sull'emulatore (gli scenari girano; i numeri non valgono)

```powershell
# emulatore avviato (Medium_Phone o Pampa_Tablet); media di prova (non serve sul telefono vero)
powershell -ExecutionPolicy Bypass -File tools\bench\seed-media.ps1
powershell -ExecutionPolicy Bypass -File tools\check.ps1 -Bench
```

Su un emulatore `-Bench` imposta da solo `benchSmoke=true` (2 iterazioni, un ciclo) e `suppressErrors=EMULATOR,…`, e `compare.ps1` esce in modo smoke: nessuna soglia, solo "girata / saltata e perché".

### Misura vera su S25 e Tab S9 (un dispositivo per volta)

Prima:
- **Impostazioni → Display → Fluidità movimento: Adattiva** (a 120 Hz); schermo acceso e sbloccato; batteria carica; tablet in orizzontale come lo si usa. `check.ps1 -Bench` annota in sola lettura i modi del display e le impostazioni (`peak_refresh_rate`, `min_refresh_rate`, `refresh_rate_mode`) e `compare.ps1` avvisa se il massimo letto è sotto i 120 Hz.
- Le varianti `benchmarkRelease` e `nonMinifiedRelease` hanno **un id proprio**, `dev.pampa.mycloudgallery.bench` (P11, `applicationIdSuffix '.bench'` in `app/build.gradle`): si installano accanto alla release firmata e alla debug (`.debug`) senza toccarle. Prima avevano l'id della release e, firmate con la chiave di debug, l'avrebbero sostituita cancellandone i dati.
- Per lo zoom a 200 MP sul tablet: la foto generata da `tools/fixtures` (P3) copiata con `adb push` in `Pictures/MCG-prova/` (con il consenso dell'utente, §5.5 A.3).

```powershell
powershell -ExecutionPolicy Bypass -File tools\check.ps1 -Bench                 # confronta con soglie e riferimento
powershell -ExecutionPolicy Bypass -File tools\check.ps1 -Bench -BenchStrict    # come sopra, ma una prova mancante è un errore (chiusura della fase)
powershell -ExecutionPolicy Bypass -File tools\check.ps1 -Bench -BenchSerial R5CY12345   # se i dispositivi sono più d'uno
```

Dopo una **misura buona** (nessuna soglia fallita, riferimento assente o da aggiornare perché il codice è cambiato **di proposito**):

```powershell
powershell -ExecutionPolicy Bypass -File tools\bench\compare.ps1 -Accept -RefreshHz 120      # scrive docs\bench\riferimento-<dispositivo>.json
powershell -ExecutionPolicy Bypass -File tools\bench\compare.ps1 -Calibrate                  # solo tablet, la prima volta: tetto di PSS in soglie.json
```

Il riferimento si **committa** con il motivo nel messaggio; una regressione si corregge, non si "accetta".

`-Accept` scrive sempre `riferimento-<dispositivo>.json` (fino al 15/10, senza un riferimento accettato, riscriveva per
errore `riferimento-<dispositivo>-fase3.json`). Da una misura **parziale** (solo alcune prove, per esempio pinch e
scorrimento rimisurati dopo una correzione) le prove non misurate tengono i valori del riferimento in uso, cosi' la guardia
continua a valere per tutte; `-Origin "<testo>"` scrive da dove vengono i numeri (`misure-s25-2026-10-15.md`).

**Guardia della Fase 4A.** Con la libreria mista e i riferimenti del 13/10 (§2-ter) il comando è lo stesso: `check.ps1 -Bench` stampa anche le righe `guardia` e `NOTO`. Per rifare il riferimento del 13/10 dai JSON grezzi (fuori dal repository):

```powershell
$base = "$env:USERPROFILE\.mycloudgallery\bench"
.\tools\bench\compare.ps1 -ExtractReference @("$base\s25-2026-10-13-final-startup", "$base\s25-2026-10-13-final") `
  -RefreshHz 120 -ReferenceDate 2026-10-13 -Origin "Galaxy S25, misure del 13/10/2026 ..."
``` La chiusura della fase richiede `riferimento-s25.json` e `riferimento-tab-s9.json` (e la nota con la tabella di `compare.ps1` incollata in `docs/bench/misure-<dispositivo>-<data>.md`).

Una sola prova (esempio):

```powershell
.\gradlew :benchmark:connectedBenchmarkReleaseAndroidTest `
  "-Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.enabledRules=macrobenchmark" `
  "-Pandroid.testInstrumentationRunnerArguments.class=dev.pampa.mycloudgallery.benchmark.StartupBenchmark"
```

I risultati (`*-benchmarkData.json`, tracce Perfetto) li copia AGP in `benchmark\build\outputs\connected_android_test_additional_output\benchmarkRelease\connected\<dispositivo>\`.

## 4. Il Baseline Profile

- `baselineProfile { automaticGenerationDuringBuild = false; saveInSrc = true; filter { exclude("dev.pampa.mycloudgallery.bench.**") } }` in `app/build.gradle`: il profilo **non** si genera durante il build (serve un dispositivo); il plugin lo salva in `app/src/release/generated/baselineProfiles/` (`baseline-prof.txt` e `startup-prof.txt`, da committare), da dove AGP lo applica alla release e alle due varianti di misura. Il filtro tiene fuori le classi del dataset di misura, che nella release non esistono.
- **Generato il 13/10 sull'S25** con `gradlew :app:generateBaselineProfile` (`ANDROID_SERIAL=RFCY61FZDHW`, 31 minuti, profili stabili dalla nona iterazione): circa 38.300 regole, di cui 7.300 del codice della galleria (viewer 900, griglia 600, telephoto 1.040); dopo R8 restano circa 20.600 regole nell'APK.
- Il generatore è `BaselineProfileGenerator`: scorrimento avanti e indietro, i quattro livelli dai segmenti, lo stile giustificato, i pinch, l'apertura del viewer con il pager, lo zoom e il pan, le informazioni, un video. **Non produce un profilo povero**: se la Libreria non è raggiungibile si salta. Ogni Indietro è condizionato (si chiude il viewer solo se è aperto, le informazioni solo se si sono aperte): un Indietro di troppo usciva dall'app e il resto del giro finiva sulla schermata Home.
- Due strade, stessi file in uscita:
  1. `gradlew :app:generateBaselineProfile`: il percorso ufficiale del plugin, sulla variante `nonMinifiedRelease`, che dal P11 ha il dataset e il `ProfileInstallReceiver` (vedi §6). **È quella usata.**
  2. `tools\bench\generate-profile.ps1`: lo stesso generatore sulla variante `benchmarkRelease` (R8 attivo, senza offuscamento). Scrive in `app/src/main/generated/baselineProfiles/`: se lo si usa, si tiene **un solo** profilo nei sorgenti.
- `check.ps1` controlla che, **quando il profilo esiste nei sorgenti**, l'APK di release contenga `assets/dexopt/baseline.prof` (+ `.profm`) **e** che AGP abbia fuso le regole nel profilo della release (le librerie portano profili propri: la sola presenza del file non basta).

## 5. Il dataset della variante `benchmarkRelease`

Esiste **solo** in `app/src/benchmarkRelease/` (D21). `check.ps1` verifica sul dex di release che non ci sia nessuna classe `Bench*`, nessun package `benchmark` e nessun `ProfileInstallReceiver`.

1. `BenchSession.readiness(profilo)` (la sonda del modulo `:benchmark`, una volta per processo di test e per profilo) concede con `pm grant` i permessi di lettura dei media, scrive il marcatore `bench-library.marker` in `/sdcard/Android/data/<id>/files/` (più `bench-library.nasfirst` per il profilo «NAS prima», che si toglie e rimette a ogni prova) e avvia l'app.
2. `BenchDatasetProvider` (un `ContentProvider` non esportato, creato dal sistema prima di `Application.onCreate`) vede il marcatore **su un thread proprio** (sul thread principale non fa niente: non sposta l'avvio a freddo) e crea il database del profilo (`gallery-bench.db`, `gallery-bench-nasfirst.db`; mai `gallery.db`) con `BenchSeeder`, dopo aver creato (una volta) i modelli delle miniature.
   **Il file che l'app ha aperto non si tocca (C1, 09/11).** La preparazione gira mentre l'app è accesa sullo stesso database: il seminatore scrive un file suo (`<nome>.building`), lo chiude e lo rinomina in `<nome>.ready`; lo scambio con il database dell'app avviene **all'avvio successivo**, in `BenchDatasetProvider.onCreate` prima di `Application.onCreate` (`BenchDatabaseFiles`). Lo stato `pronto` vuol dire quindi «pronto dal prossimo avvio»: chi lo aspetta deve fermare e riavviare l'app, come fa `BenchSession`. Prima il seminatore cancellava e riscriveva il file aperto dall'app, e dopo un'installazione pulita l'app cadeva con `no such table: search_history` o `database is locked`.
   Nello smoke sull'emulatore la sonda scrive anche `bench-library.smoke`: con quel file accanto al marcatore il dataset è di **5.000 righe** (e 48 modelli di miniatura invece di 192) invece di 100.000. L'app guarda solo se i file *esistono*: il contenuto di un file creato da shell in `Android/data/<id>/files/` non lo può leggere (permesso negato, provato su Android 37).
3. Lo stato sta in `bench-dataset.json` (o `bench-dataset-nasfirst.json`) accanto al marcatore (`in_corso` → `pronto` / `errore`, righe, quote di presenza, replica, media veri, megapixel massimi, tempi, righe rilette dall'indice, riferimenti del telefono risolti). Un avvio successivo con lo stato `pronto` (stessa `SHAPE_VERSION`, 4 dalla Fase 9) non rifà niente: gli avvii misurati non lo pagano.

### 5.1 La forma delle righe

100.000 elementi, seme fisso, **la stessa sequenza di numeri casuali del 13/10**: date (15 anni, 240 giorni «a raffica» = 38% delle righe, giornate vuote), luoghi 60% (id veri di GeoNames), il 10% in fusi diversi, preferiti 2%, date stimate 3%, screenshot 10%, WhatsApp 20%, Quick Share 2%, panorami dove la proporzione lo è, tipo e proporzione presi dal media vero a cui la riga punta (rotazione `i % N`). Quindi la griglia (righe, intestazioni, layout) è quella su cui sono state fatte le misure del 13/10; **cambia solo la presenza**:

| Profilo | Le righe più recenti (per data) | Tutte le altre |
|---|---|---|
| `MIXED` (default, tutti gli scenari della Fase 3) | 120 solo-telefono (la prima è il soggetto dello zoom a 200 MP: la foto più grande) | 60% **solo-NAS**, 6% telefono **e** NAS (un terzo «al sicuro»), il resto solo telefono (per hash della riga) |
| `NAS_FIRST` (`coldStartNasFirst`, `viewerOpenNas`) | 300 solo-NAS, tutte con l'HQ in replica | come `MIXED` |

- **Perché la testa del `MIXED` è del telefono**: la libreria si apre in fondo (D1) sulle foto appena scattate, che sono sul telefono; così `coldStart` e `viewerOpen` (cella al centro) misurano la stessa cosa del 13/10 e la guardia confronta mele con mele, mentre scorrimento e pinch attraversano tutto il resto, dove il 60% è del NAS. La prima pagina fatta solo di NAS è il profilo `NAS_FIRST`: la prova non deve riconoscere le celle dal testo (che il filone grafico può cambiare), è il dataset a decidere.
- **Riga solo-NAS**: `media.presence = nas_only`, `cell_flags` con `ON_NAS` (mai `ON_PHONE`), una posizione `nas` (account `alessio`, share privata `alessio` all'85% o comune `NAS` al 15%, percorso `Foto/<aaaa>/<mm>/IMG_<n>.<est>`, `media_store_id` nullo), ThumbHash vero del modello di miniatura, nessuna posizione del telefono: `phoneRefs` non la risolve (è il lavoro di P7: `MediaRef.Nas`). Riga su entrambi: tutte e due le posizioni, `ON_PHONE | ON_NAS` (e `SAFE`).
- `quick_hash` = 64 cifre esadecimali uniche per riga, `size` = quella del media vero: la **chiave di contenuto** (`ContentKey.of(qh1, dimensione).short`, D4) è quella che il catalogo ricaverà e con cui la replica trova i blob. Se lo schema v3 di P2 la tiene in una colonna (`content_key`, `content_key_short`, `ck_short`) il seeder la scrive in `media`, `media_version` e `media_location`; altrimenti non fa niente (`BenchTable.optionalLong`).
- Il seeder scrive con SQL diretto le tabelle `media`, `media_version`, `media_location`, `version_metadata`, `media_store_cursor` e `catalog_meta` leggendo le colonne da `PRAGMA table_info`: una colonna `NOT NULL` aggiunta da una migrazione futura non lo rompe, una colonna che sparisce lo fa fallire subito (stato `errore`, con il nome della tabella). Alla fine rilegge il file con un'istanza nuova di `GalleryDatabase` e controlla con le query vere di P1 (`indexCursor()`: 100.000 righe e la quota di presenza dai flag; `phoneRefs`: tutte le prime 200 righe con il telefono risolte) e con la replica (sotto) che il dataset sia quello che l'indice della timeline si aspetta.

### 5.2 La replica sintetica (pacchi di miniature WebP vere)

`BenchReplica.kt`. In `noBackupFilesDir/bench-replica/`:

- `templates-<versione>-<per gruppo>-<con HQ>.pack`: i **modelli** (192 a regime = 8 proporzioni da 9:16 a 3:1 × 24 varianti, 48 nello smoke), ognuno una «foto» disegnata (sfumature, macchie morbide, grana) e codificata in **WebP lossy vero** con `Bitmap.compress`: S (lato corto 200 px, 7,6 KB di media), M (400 px, 24 KB) e, per 3 varianti per proporzione, l'HQ (lato lungo 2048 px, 348 KB): i pesi del §2.10 (S 7,7 KB, M 21 KB). La S non è la M rimpicciolita (perderebbe la grana): è disegnata alla sua taglia. Costo a regime: decine di secondi sul telefono, una volta.
- `mixed.idx` / `nasfirst.idx`: l'**indice** della replica, un blob per (chiave di contenuto ridotta, classe 2 = `thumbS`, 3 = `thumbM`, 5 = `hq`), 17 byte a voce, ordinato; 132.849 voci nel `MIXED` (2,2 MB). Tante chiavi puntano allo stesso modello: l'indice ha la dimensione di una replica vera, i pacchi no (14 MB a regime, 4 MB nello smoke, invece di centinaia).
- `BenchReplicaStore` li legge come li leggerà `ReplicaBlobs` (D15): indice in memoria, ricerca binaria, pacco mappato in sola lettura, mai copiato (`locate`, `read`).
- Il seeder controlla 64 chiavi campione (ci sono S e M, sono WebP, si decodificano) e scrive in `bench-dataset.json` i tempi di decodifica di S e M **su quel dispositivo** (`decodificaSMs`, `decodificaMMs`: dati utili a P7 per il budget).

## 6. Collegamento con l'app (per P11 e M1)

La variante di misura può mettere **classi** e **moduli Hilt** nell'app, ma `src/main` non può chiamare un `Bench*` che non c'è nelle altre varianti. Il collegamento è un'interfaccia che esiste in `src/main` (`di/MeasurementHooks.kt`) e un binding opzionale:

```kotlin
// src/main: una dichiarazione, nessuna implementazione
interface MeasurementHooks { val family: FamilySetupRepository; val phoneSync: PhoneLibrarySync; val databaseName: String }
@Module @InstallIn(SingletonComponent::class)
abstract class MeasurementHooksModule { @BindsOptionalOf abstract fun measurementHooks(): MeasurementHooks }

// src/benchmarkRelease: l'unico posto che la lega (compilato in benchmarkRelease e nonMinifiedRelease, in nessun'altra)
@Module @InstallIn(SingletonComponent::class)
abstract class BenchHooksModule { @Binds abstract fun measurementHooks(hooks: BenchMeasurementHooks): MeasurementHooks }
```

`BenchMeasurementHooks` consegna
- `family`: `BenchFamilySetupRepository` (`SetupState.Ready(admin = false)`: la famiglia "pronta", senza NAS e senza rete);
- `phoneSync`: `BenchPhoneLibrarySync` (ferma: la scansione vera riconcilierebbe le 100 mila righe sintetiche con i pochi media veri e le segnerebbe assenti);
- `databaseName`: `gallery-bench.db` (profilo `MIXED`) oppure `gallery-bench-nasfirst.db` (profilo `NAS_FIRST`, se accanto al marcatore c'è `bench-library.nasfirst`; `BenchMeasurementHooks` lo legge una volta, alla costruzione), così anche `nonMinifiedRelease` (`:app:generateBaselineProfile`) apre il dataset e non `gallery.db` vuoto (I3). Il profilo di `generateBaselineProfile` è il misto: il prossimo profilo generato avrà dentro anche i percorsi del NAS.

**Cosa fa `src/main`** (3 punti, tutti con un `Optional<MeasurementHooks>` vuoto fuori dalle varianti di misura, dove resta il comportamento vero): `di/GraphModule.kt` (famiglia, via `chooseFamilySetup`), `di/GalleryModule.kt` (sincronizzazione ferma e nome del database).

**Perché non più `ServiceLoader`** (revisione di sicurezza della Fase 3, R6). Un file `META-INF/services/...` è un canale dichiarativo e invisibile: qualunque dipendenza (o un file messo per sbaglio in `src/main/resources`) poteva far partire l'app vera con la famiglia "pronta", saltando onboarding e approvazione dell'admin, e nessun controllo lo vietava (`check.ps1` cercava solo le classi `Bench*`). Ora `src/main` non nomina `ServiceLoader` (lo prova `MeasurementHooksTest`), la release non ha nessun `META-INF/services/dev.pampa.*` e `tools/check.ps1` fallisce se lo trova (passo "nessun file di servizio dev.pampa.* in META-INF/services"); un test controlla anche l'ordine di scelta (le hooks vincono, poi la scorciatoia di debug, poi la famiglia vera).

**Controllato (13/10):** il modulo Hilt compare nel componente di `benchmarkRelease` e `nonMinifiedRelease` (`BenchMeasurementHooks`) e non in quello di release (`Optional.empty()`); nell'APK `benchmarkRelease` ci sono `BenchMeasurementHooks`, `BenchFamilySetupRepository` e `BenchPhoneLibrarySync` nel dex e nessun file in `META-INF/services/`.

Altre due cose che le prove si aspettano dall'app (senza, girano lo stesso e `compare.ps1` lo dice):

- **`reportFullyDrawn()`** alla prima pagina di miniature disegnata o dopo 300 ms (P7/P11, per esempio dalla `LibraryTab`): è il TTFD dell'avvio a freddo.
- **La sezione di traccia `MCG:viewerOpen`** dal gestore del tocco al primo fotogramma con l'immagine (P8). Va bene sincrona o asincrona; fra il tocco e il primo fotogramma passano più fotogrammi, quindi di solito è asincrona:
  ```kotlin
  android.os.Trace.beginAsyncSection("MCG:viewerOpen", cookie)   // al tocco
  android.os.Trace.endAsyncSection("MCG:viewerOpen", cookie)     // al primo fotogramma con la bitmap
  ```
  La metrica (`ViewerOpenMetric`) legge la tabella `slice` di Perfetto, dove compaiono tutte e due le forme.

Per `:app:generateBaselineProfile` ufficiale (non obbligatorio, vedi §4) `app/build.gradle` ora fa, dentro il `finalizeDsl` (P11):
```groovy
extension.sourceSets.findByName('nonMinifiedRelease')?.with {
  manifest.srcFile('src/benchmarkRelease/AndroidManifest.xml')
  kotlin.srcDir('src/benchmarkRelease/kotlin')
  resources.srcDir('src/benchmarkRelease/resources')
}
```

## 6-bis. La replica sintetica e l'app (per P7 e P11)

Le miniature dei media solo-NAS verranno da `ReplicaBlobs` (P4) tramite `NasThumbnailSource` (P7). La variante porta già i dati (§5.2) ma **l'app non li vede ancora**, per due motivi che non sono di P10 e che la regola «solo aggiunte» lascia a P11:

1. `app` non dipende da `core-index`, quindi `src/benchmarkRelease` non può nominare `ReplicaBlobs`;
2. `MeasurementHooks` (`app/src/main/di`) non ha un posto dove consegnarli a `GalleryModule`/`GalleryGraph`.

Con `implementation project(':core-index')` in `app/build.gradle` e tre aggiunte (nessuna firma esistente cambia) le miniature del NAS escono dal pacco sintetico:

```kotlin
// src/main/di/MeasurementHooks.kt, nell'interfaccia: la replica locale sintetica della variante di misura (P10); null altrove.
val replicaBlobs: ReplicaBlobs? get() = null

// src/benchmarkRelease/.../BenchMeasurementHooks.kt
override val replicaBlobs: ReplicaBlobs? get() = replica?.let { BenchReplicaBlobs(it) }

// src/benchmarkRelease/.../BenchReplicaBlobs.kt (nuovo): l'adattatore, una ventina di righe
internal class BenchReplicaBlobs(private val store: BenchReplicaStore) : ReplicaBlobs {
  override fun locate(contentKey: Long, cls: SegmentClass): BlobLocation? =
    store.locate(contentKey, cls.code)?.let { BlobLocation(it.packId, it.offset, it.length, cls) }

  override fun read(location: BlobLocation): ByteBuffer =
    store.read(BenchBlobLocation(location.packId, location.offset, location.length, location.cls.code))

  override suspend fun estimate(quality: QualityLevel): SpaceEstimate = SpaceEstimate(store.packBytes, store.blobCount)
}
```

`GalleryModule` passa poi `measurement?.replicaBlobs` a dove P7 costruisce `NasThumbnailSource` (come oggi passa `benchSync`). Con la replica vera (P4) il seeder scriverà il suo formato (`replica_blob` + pacchi in `noBackupFilesDir/replica/packs/`) al posto di `BenchReplicaStore`: stessi dati (chiave ridotta, classe, offset, lunghezza), stesso `BenchReplicaBuilder` per i modelli. **Allineare dopo P2/P7**: i nomi delle colonne della chiave di contenuto e delle posizioni NAS nello schema v3 (oggi il seeder usa le colonne di `media_location` che esistono già nella v2: `account_id`, `share_name`, `share_key`, `nas_path`) e, se P7 vuole una cella `MediaRef.Nas` con `locationId`, `location_id` è l'`INTEGER PRIMARY KEY` di `media_location`.

## 6-ter. I selettori e il filone grafico

`BenchLabels`/`BenchUi` si fidano dell'albero di accessibilità; il filone grafico (`docs/grafica-progetto.md`) lo cambia. Cosa è stato reso robusto (P10) e cosa serve ancora:

| Cosa | Oggi | Dopo il filone grafico |
|---|---|---|
| Celle | descrizione `^(Foto RAW\|Foto in movimento\|Foto\|Video\|Immagine animata),` | invariato se la descrizione comincia così (la presenza può venire dopo) |
| Livelli | segmenti `Anni`/`Mesi`/`Giorni`/`Tutte` | **senza segmenti** (A, B, C): `selectLevel` prova l'etichetta «Livello: …» (B, tag `g1-chrome-livello`), poi le voci del menu «Vista» (A e C, tag `g1-chrome-vista`), poi i pinch. Non verificato sul disegno scelto: il prototipo è solo debug |
| Stile | menu «Vista» → `Quadrata`/`Proporzioni originali` | invariato; il pulsante si trova prima per tag `g1-chrome-vista`, poi per descrizione/testo «Vista» |
| Griglia | l'elemento scorrevole più grande | invariato |
| Cella solo-NAS | **non serve più riconoscerla**: la sceglie il profilo del dataset (§5.1) | — |

Cosa serve all'app, se si vogliono selettori per tag invece che per testo: `Modifier.semantics { testTagsAsResourceId = true }` alla radice (oggi lo ha solo il prototipo G1) e un tag sul pulsante della vista e sul menu dei livelli del disegno scelto (i `g1-chrome-*` vanno già bene se restano). Un cambio di etichetta («Vista», «Anni», …) o dell'ordine dei menu si sistema in `BenchLabels` e `BenchUi.selectLevelFromMenu`. A disegno scelto: `check.ps1 -Bench -BenchStrict` su un emulatore (smoke) dice subito quali prove saltano e perché.

## 7. Che cosa è stato provato (e dove)

| Cosa | Esito |
|---|---|
| `compare.ps1 -SelfTest`; `-Accept` e `-Calibrate` su risultati d'esempio; lettura di un vero `*-benchmarkData.json` | tutto verde (`tools/bench/samples/`) |
| Scenari e generatore compilano (`:benchmark:compileBenchmarkReleaseKotlin`); i sorgenti di `app/src/benchmarkRelease` compilano contro i moduli veri; la variante li compila e la release no; il manifest fuso di `benchmarkRelease` ha provider, receiver e `profileable`, quello di release nessuno dei tre | verde |
| Dataset: 100.000 righe su Robolectric (indice letto per intero, 200 riferimenti su 200, nessun ordinamento temporaneo, sorgenti 68/10/20/2%) e 5.000 righe sull'emulatore Medium_Phone con 62 media veri, anche con R8 e senza offuscamento | verde |
| Scenari `coldStart`, `scroll_days_square`, `pinch`, `viewerOpen`, `zoom200Mp` su Medium_Phone (API 37.1), contro un **segnaposto della Libreria** (una lista di celle "Foto, …" con `reportFullyDrawn` e la sezione asincrona `MCG:viewerOpen`): gesti, metriche proprie (`viewerOpenMs`, `pssPeakKb`, `processAlive`, `libraryShown`), JSON e `compare.ps1 -Smoke` | girano; i numeri dell'emulatore non valgono |
| Scenari `coldStart`, `pinch`, `viewerOpen` con la **vera app** (P11, 13/10) su Medium_Phone (API 37.1), smoke (`benchSmoke=true`), senza media veri | girano tutti e tre: `libraryShown` = 1 (la Libreria vera si apre sul dataset), `timeToFullDisplayMs` misurato (`reportFullyDrawn` c'e'), `viewerOpenMs` misurato (la sezione `MCG:viewerOpen` c'e'), il pinch attraversa i livelli. I numeri dell'emulatore non valgono |
| `check.ps1 -Bench` con un dispositivo vero | **S25, 13/10**: tutte le prove girano (avvio, 8 scorrimenti, pinch, viewer); numeri, cause e soglie ancora fallite in `misure-s25-2026-10-13.md`. Tab S9 non provato |
| `:app:generateBaselineProfile` e `tools\bench\generate-profile.ps1` | `:app:generateBaselineProfile` **sull'S25, 13/10**: 38.341 regole in `app/src/release/generated/baselineProfiles/` (§4). Lo script non provato |
| Selettori delle prove e `FrameJankMetric` sull'emulatore (smoke) | le 8 prove di scorrimento, il pinch, l'avvio e il viewer girano |
| Hooks di misura (Hilt, niente `ServiceLoader`): nel componente delle due varianti di misura, `Optional.empty()` nella release, nessun `META-INF/services/dev.pampa.*` nell'APK di release | verde (R6, §6) |
| **P10 (14/10)** `compare.ps1 -SelfTest` con la guardia: limiti, pavimento, `mantieniSotto`, `NOTO`, arrotondamenti 750/25, libreria non mista, smoke | verde |
| **P10** guardia sulle misure grezze del 13/10 (`-Results …\s25-2026-10-13-final`, riferimento estratto) | 27 OK, 0 FAIL, 4 NOTO (i tre scorrimenti giustificati e il fotogramma più lungo, già fuori soglia), `coldStart` MANCA (sta in `final-startup`) |
| **P10** dataset e replica su JVM (Robolectric nativo, `tools/bench/samples/BenchSeederRobolectricTest.kt.txt`, non nel repository): 100.000 righe, 59,76% solo-NAS, 6% su entrambi, 132.849 blob (S 7,6 KB, M 24 KB, HQ 348 KB), pacco 14 MB, indice 2,2 MB; 43 su 43 chiavi campione lette e decodificate; testa del `MIXED` tutta del telefono, del `NAS_FIRST` tutta del NAS (con HQ); i due profili convivono | verde |
| **P10** smoke su Medium_Phone (API 37.1, headless, 4 GB), eseguito su un **albero di lavoro al commit di P0** (la copia di lavoro aveva i pacchetti paralleli a metà e non compilava tutta): `coldStart`, `coldStartNasFirst`, `viewerOpen`, `viewerOpenNas`, `libraryMemory`, `pinch`, `scroll_days_square`, `scroll_days_justified` con 5.000 righe (57% solo-NAS, 6.461 blob; «NAS prima» 62%, 7.151 blob) | **girano tutte e 8**: il profilo cambia da solo fra una classe e l'altra, `libraryNasShare`, `libraryNasFirst`, `libraryReplicaBlobs` e le metriche di memoria viaggiano nel JSON, il menu dello stile e il viewer funzionano con le righe solo-NAS ancora senza riferimento (segnaposto). Le altre sei prove di scorrimento non sono state selezionate; i numeri dell'emulatore non valgono. Un primo giro con tutte le 16 prove si è interrotto («System has crashed») durante la prima prova misurata: emulatore da 2 GB con l'host al 90% per le build degli altri agenti |
| **P10** compilazione di `:app:compileBenchmarkReleaseKotlin` e `:benchmark:compileBenchmarkReleaseKotlin` sulla copia di lavoro con i pacchetti paralleli | verde |
| **P12 (31/10)** `compare.ps1 -SelfTest` con i 14 scenari `ricordi-*` e `mappa-*` (soglie esatte, validita', guardie, `CAL`, `NUOVO`); `check.ps1`: passo «scenari dei ricordi e della mappa» (soglie, prove nel modulo, soglie JVM nei test, nessuna mappa online); `check.ps1 -Bench` su Medium_Phone (x86_64, 5.000 righe) | verde; **le 14 prove nuove girano** (tabella al §10). Su S25 e Tab S9 non provato (F111, F112, F123, F124) |

## 8. Limiti e cose da sapere

- **Più dispositivi collegati:** `check.ps1 -Bench` si ferma e chiede `-BenchSerial`; Gradle gira su tutti i dispositivi collegati a meno di `ANDROID_SERIAL`, e lo script lo imposta per quello scelto. Gli emulatori sono condivisi con gli altri pacchetti: la sonda tocca solo il pacchetto sotto misura e quello di test.
- **I numeri dell'emulatore non valgono.** Servono a vedere che gli scenari girano. Medium_Phone (API 37.1) usa un'immagine con pagine da 16 KB: può comparire un avviso di compatibilità all'avvio dell'app.
- **Ci vuole la Libreria raggiungibile**: dal P11 la variante di misura apre la Libreria vera sul dataset (famiglia "pronta" dalle `MeasurementHooks`). Senza il marcatore del dataset l'app va all'onboarding: tutte le prove tranne `coldStart` si saltano con il motivo e `coldStart` misura l'onboarding (marcato non valido).
- **Il profilo è fatto su un dataset realistico, non sul tuo.** Tipi, proporzioni e rotazione vengono dai media veri del dispositivo; le date e la distribuzione sono sintetiche.
- **Le fixture dei video**: il dataset ci mette i video veri del dispositivo (con la loro durata) ma il benchmark non li riproduce; la riproduzione si prova sul campo (F12).
- **La replica sintetica è ottimistica sulla lettura, non sulla decodifica.** I blob sono WebP veri con i pesi del §2.10 e si decodificano come quelli veri, ma il pacco è di 14 MB e resta caldo nella cache di pagine; la replica vera (centinaia di MB) leggerà da flash al primo tocco di ogni pagina. Per un avvio a freddo «da dispositivo appena acceso» la differenza si vede solo con una replica vera (M2, F6-F7).
- **Nessun numero finché non ci sono P7, P8 e P11.** Fino ad allora le celle solo-NAS non hanno riferimento (restano segnaposto), il viewer non apre il NAS e la replica non arriva all'app (§6-bis): il dataset e gli scenari girano e basta. La misura che chiude la 4A si fa su S25 e Tab S9 dopo P11.
- **Il dataset si rifà** quando cambia `SHAPE_VERSION` (4 dalla Fase 9, P12) o passano piu' di 2 giorni dalla creazione (i giorni «di un anno fa», §10): alla prima corsa dopo l'aggiornamento la sonda aspetta fino a 4 minuti la creazione (100.000 righe e 192 modelli di miniatura: decine di secondi sul telefono, minuti su un emulatore, per questo lo smoke usa 5.000 righe).
- **Il riferimento del 13/10 è dell'S25 con la libreria tutta sul telefono.** Vale per l'S25; il Tab S9 non ne ha. La guardia non sa se un'oscillazione viene dal codice o dalla temperatura del telefono (`years_justified` oscilla da 0,6 a 3,9% nella stessa corsa): ripetere una corsa che fallisce a telefono freddo prima di cercare la causa.
- **Niente rete**: né l'app (D24) né il collaudo la usano. `benchmark` dichiara `INTERNET` solo perché lo porta la libreria di test nel suo APK (mai in quello dell'app).

## 9. Album e persone (debito del 27/10)

Gli scenari `album` (Fase 7, §5.4 di `docs/fase7-progetto.md`) e `volti` (Fase 8, §5.4 di `docs/fase8-progetto.md`) erano promessi e non c'erano (`docs/fase9-progetto.md` §0, punto 12). Ci sono: **le prove sono scritte, compilano, hanno le soglie dei progetti in `benchmark/soglie.json` e `tools\check.ps1` lo verifica** (passo «scenari degli album e dei volti», che lancia anche `compare.ps1 -SelfTest`). **Non girano ancora su un telefono**: la variante di misura non ha la parte dell'app che serve (vedi sotto), quindi `check.ps1 -Bench` le dà `MANCA` con il motivo, e con `-BenchStrict` falliscono. Il compito che resta è dell'app, non del collaudo.

| Prova (classe) | Cosa fa | Metrica e soglia | Regressione |
|---|---|---|---|
| `albumOpen` (`AlbumBenchmark`), scenario `album` | Avvio a freddo, scheda Raccolte, tocco sull'album a mano da 5.000 elementi; 12 iterazioni | `albumOpenMs` (dal tocco alla prima cella della griglia) p90 ≤ 300 ms | p90 +15% |
| `albumScroll` (`AlbumBenchmark`), scenario `album-scroll` | Fling avanti e indietro nell'album da 5.000; 8 iterazioni | `jankPercent` medio ≤ 1% (la guardia della Libreria) | P95 `frameDurationCpuMs` +15% |
| `albumRecompute` (`AlbumBenchmark`), scenario `album-ricalcolo` | Ricalcolo di uno smart sulle 100 mila righe **con il lavoro in corso** (la lettura della libreria all'avvio), tempo scritto dall'app; 5 iterazioni | `albumRecomputeMs` massimo ≤ 2000 ms | mediana +15% |
| `collectionsOpen` (`AlbumBenchmark`), scenario `album-raccolte` | Le Raccolte con 50 album: dal tocco sulla scheda alla riga dell'album grande; 12 iterazioni | nessun tetto nel progetto | p90 +15% |
| `facesEngines` (`FacesBenchmark`), scenario `volti` | I motori dei volti (YuNet a 1024 px, SFace) misurati **dentro l'app** su immagini e volti disegnati; 5 avvii | nessun tetto: solo regressione (la misura di riferimento è F104) | p95 di YuNet, p95 di SFace, ms per foto (mediana) e PSS (massimo) +15% |
| `facesLoadedScroll` (`FacesBenchmark`), scenario `volti-scroll` | La guardia di scorrimento della Libreria (livello Tutte, stile quadrato) **con il calcolo dei volti in corso**; 8 iterazioni | `jankPercent` medio ≤ 1% | P95 `frameDurationCpuMs` +15% |

**Come funzionano.** Ogni prova ha la validità in `soglie.json` (`albumsSeeded`, `facesMsPerPhoto`, `facesComputing`): senza, `compare.ps1` la dice `NON VALIDA` o `MANCA`, mai un falso verde. `albumOpenMs` e `collectionsOpenMs` li misura UiAutomator (l'app non emette sezioni di traccia per gli album): comprendono il ritardo di rilevamento dell'albero di accessibilità, quindi sono prudenti per il tetto dei 300 ms e la regressione confronta sempre la stessa misura. Le etichette di Raccolte e album (`Raccolte`, `Tutti gli album`, il titolo `Bench 5000`) sono in `AlbumUi` e sono **provvisorie** fino al primo giro sul telefono. Le guardie esistenti (scorrimento, viewer, avvio) «con la sincronizzazione degli album condivisi in corso» (§5.4 della Fase 7) restano le loro prove: servono gli album condivisi nel dataset.

**Cosa serve dall'app** (nessuno dei due pezzi c'è oggi; le costanti sono in `AlbumBenchTarget` e `FacesBenchTarget`):
1. **Album sintetici.** Con il file `bench-library.albums` accanto al marcatore il dataset crea un album a mano di 5.000 elementi (titolo `Bench 5000`), almeno 50 album e uno smart che copre le 100 mila righe, e scrive in `bench-dataset.json` i campi `albumi`, `albumGrandeElementi` e `albumSmartRighe` (alzare `SHAPE_VERSION`: un dataset già «pronto» non si rifà). **Il blocco vero è un altro:** la variante di misura non monta gli album, perché `AlbumsWiring` aspetta `nas.organizer` (l'archivio dei dati personali) e la variante non ha il NAS (`GalleryModule`: `nas = ... takeIf { measurement == null }`). Serve un archivio personale locale nel dataset, o un montaggio degli album che non lo richieda. Con il file `bench-library.albums.recompute` l'app ricalcola quello smart a ogni avvio e scrive `bench-albums.json` (`{"stato":"pronto","ricalcoloSmartMs":...}`).
2. **Misura dei volti nell'app.** Con `bench-library.faces` l'app misura i motori su immagini disegnate (come `FaceMeasurementsDeviceTest` di P2) e scrive `bench-faces.json` (`yunetP50Ms`, `yunetP95Ms`, `sfaceP50Ms`, `sfaceP95Ms`, `msPerFoto`, `pssKb`); con `bench-library.faces.load` continua il calcolo a bassa priorità e scrive `"calcoloInCorso":true`. **Non è stata scritta qui di proposito:** C134 vuole i motori veri nominati in un posto solo (`FacesWiring`; `FacesFenceSourcesTest` lo controlla su `src/main`), e una misura dentro la variante di misura li nominerebbe un'altra volta, fuori dal recinto dell'attivazione. La strada pulita è un aggancio di prova in `FacesWiring` (`testing`), o far uscire da lì la sola misura.

Fino a allora i motori dei volti si misurano con i test strumentati di P2 (`:core-ai:connectedDebugAndroidTest`, F104) e le prove di scorrimento con gli scenari di sempre.

## 10. Ricordi e mappa (Fase 9, P12)

Gli scenari `ricordi` e `mappa` (`docs/fase9-progetto.md` §5.4; punto 12 del §0: «scenari promessi e mancanti») **esistono e girano**: `MemoriesBenchmark` (5 prove) e `MapBenchmark` (9 prove) in `benchmark/src/main/kotlin/.../benchmark/`, 14 scenari `ricordi-*` e `mappa-*` in `benchmark/soglie.json`, e `tools\check.ps1` li verifica (passo «scenari dei ricordi e della mappa»). Costruiti come `SearchBenchmark`, `AlbumBenchmark` e `FacesBenchmark`: `BenchSession`, `BenchConfig`, `BenchUi`, `BenchLabels`; una prova che non puo' girare si **salta con il motivo** (`Assume`), mai un falso verde.

| Prova (classe) | Scenario (`soglie.json`) | Cosa fa | Metrica e soglia (S25 e Tab S9, 120 Hz) | Regressione |
|---|---|---|---|---|
| `memoryOpen` (`MemoriesBenchmark`) | `ricordi-apertura` | Avvio a freddo, Raccolte, tocco su un ricordo dello scaffale seminato; 12 iterazioni | `memoryOpenMs` (dal tocco alla prima cella della griglia) p90 ≤ 300 ms | p90 +15% |
| `memoryPlayer` | `ricordi-presentazione` | «Riproduci» e 30 s di presentazione con il movimento; «Rivedi» se il ricordo finisce prima; 3 iterazioni | `jankPercent` medio ≤ 1%; `maxFrameCpuMs` (il fotogramma piu' lungo) max ≤ 16,7 ms | jank: riferimento +10%, pavimento 0,15 punti; fotogramma e `frameDurationCpuMs` P95 +15% |
| `memoriesGenerate` | `ricordi-generazione` | Su 100 mila righe, **da zero**: un giro con il budget notturno e uno leggero a ogni avvio (il pilota dell'app, sotto); 5 iterazioni | sezioni di traccia `MCG:memories.generate` (passo pieno) ≤ 2.000 ms e `MCG:memories.generate.light` (passo leggero) ≤ 300 ms, la peggiore delle iterazioni | mediana +15% |
| `collectionsScroll` | `ricordi-raccolte` | Fling avanti e indietro nelle Raccolte con le sezioni Ricordi e Luoghi; 8 iterazioni | `jankPercent` medio ≤ 1% (la guardia della Libreria) | `frameDurationCpuMs` P95 +10%; guardia come la Libreria |
| `memoriesLoadedScroll` | `ricordi-scroll-carico` | `scroll_all_square` (livello Tutte, stile quadrato) **con la generazione in corso**: i giri si ripetono di continuo a bassa priorita'; 8 iterazioni | `jankPercent` medio ≤ 1% | `frameDurationCpuMs` P95 +15% |
| `mapOpenCold`, `mapOpenWarm` (`MapBenchmark`) | `mappa-apertura-freddo`, `mappa-apertura-caldo` | Raccolte, sezione Luoghi, tocco su «Mappa»; a freddo il processo e' nuovo (MapLibre e indice da avviare), a caldo la mappa e' gia' stata aperta una volta; 12 iterazioni | `mapOpenMs` (dal tocco alla prima bolla) p90 ≤ 800 ms a freddo, ≤ 300 ms a caldo | p90 +15% |
| `mapGestures` | `mappa-gesti` | Spostamenti e pizzichi scriptati per 20 s; 5 iterazioni | `jankPercent` medio ≤ 1% | `frameDurationCpuMs` P95 +10%; guardia come la Libreria |
| `mapDraw` | `mappa-disegno` | Gli stessi gesti: il tempo di disegno di MapLibre per fotogramma | `mapDrawP95Ms` max, **`null` da calibrare** (provvisorio 12 ms) | mediana +15% |
| `mapClusters` | `mappa-bolle` | Dodici spostamenti con la camera ferma fra l'uno e l'altro; 8 iterazioni | sezione di traccia `MCG:map.clusters` (`BubbleEngine.compute`): `mapClustersP95Ms` max ≤ 8 ms | mediana +15% |
| `mapZoneOpen` | `mappa-zona` | Tocco sulla bolla piu' piccola (e, se serve, ci si avvicina) fino alla griglia di una zona | `zoneOpenMs` (alla prima cella) p90 ≤ 150 ms | p90 +15% |
| `mapZoneViewer` | `mappa-viewer` | Tocco su una cella della griglia della zona | `viewerOpenMs` (sezione `MCG:viewerOpen`, la guardia del viewer) p90 ≤ 100 ms | p90 +15% |
| `mapMemory` | `mappa-memoria` | PSS (e heap Java, nativo, grafica) a mappa aperta dopo i gesti | `mapPssKb` max: nessun tetto, la prima corsa buona e' il riferimento | +15% |
| `mapLoadedScroll` | `mappa-scroll-carico` | `scroll_all_square` **dopo aver aperto la mappa** | `jankPercent` medio ≤ 1% | `frameDurationCpuMs` P95 +15% |

**Il dataset** (`app/src/benchmarkRelease/`, `SHAPE_VERSION` 4: un dataset di una versione vecchia si rifa' da solo). Sta **solo** li' (mai in release: il controllo di `check.ps1` sui `Bench*` nel dex di release c'era gia'). La sequenza dei numeri casuali del 13/10 non cambia (date, luoghi, flag e presenza di ogni riga sono quelli di sempre, quindi le guardie della Libreria confrontano la stessa griglia): le novita' vengono da hash della riga.

- **Coordinate** (`BenchGeo`): le righe che hanno un luogo (il 60% della Libreria, id veri di GeoNames) hanno anche una posizione plausibile: il centro di una delle 19 citta' piu' uno scarto (82% entro 0,04 gradi, 15% entro 0,35, 3% entro 2,5). Dati pubblici, nessun luogo dell'utente. Campo `righePosizione` di `bench-dataset.json` (100 mila righe: circa 60 mila).
- **Giorni «di un anno fa»** (`BenchAnniversary`): un gruppo di righe, scelte da un passo fisso (80 righe per giorno a 100 mila righe), prende le date dei 7 giorni attorno a «oggi» di 1 e di 3 anni fa (1.120 righe). Le date sono relative al giorno della **creazione** (`giornoSeme`): il dataset vale per 2 giorni, poi si rifa' (decine di secondi sul telefono, la sonda aspetta fino a 4 minuti). Cosi' la generazione trova anniversari veri.
- **Scaffale dei ricordi seminato** (`BenchShelf`): cinque ricordi veri in `noBackupFilesDir/memories/shelf.bin` (il file di `FileShelfStore`): «Un anno fa», «3 anni fa», un viaggio e un evento di prova, «Il meglio di» del mese scorso; elementi = righe del dataset sul telefono (le loro miniature si disegnano davvero); titoli inventati, nessuna persona. Giorno dell'ultimo giro = oggi, quindi `refresh(OPEN)` all'apertura di Raccolte non rigenera niente e lo scaffale resta quello seminato. Campo `ricordiSeminati`.

**Cosa e' nell'app per le prove** (tutto in `src/benchmarkRelease`, tranne i punti di aggancio dichiarati sotto):
- **Ricordi montati senza NAS**: nella variante di misura non c'e' NAS e quindi nessun archivio dei dati personali, e i ricordi non si sarebbero mai montati («Aspetto la tua libreria»). `MeasurementHooks.memoriesPersonalStore` (default null) da' a `MemoriesWiring.build` uno store personale **in memoria** (`InMemoryPersonalStore`: mai su disco, mai sul NAS): i ricordi si montano come nell'app vera, dopo il primo disegno, con le scelte (visto, non mostrare) che durano quanto il processo.
- **Pilota della generazione** (`BenchMemoriesDriver`, un thread, solo con un file accanto al marcatore): con `bench-library.memories` fa a ogni avvio un giro pieno e uno leggero e scrive `bench-memories.json` (millisecondi, ricordi nuovi, esclusi per motivo: solo numeri); con `bench-library.memories.load` li ripete di continuo a bassa priorita' (`calcoloInCorso`). Usa **un motore suo** con le stesse porte vere ma uno scaffale in memoria vuoto: ogni giro e' una generazione completa da zero e lo scaffale seminato dell'app (quello di `memoryOpen`) non si tocca.
- **Sezioni di traccia** (`AvvioTrace`): `MCG:memories.generate` e `MCG:memories.generate.light` dentro `MemoryEngine.refresh` (budget notturno; `LIGHT` e `OPEN`), `MCG:map.clusters` dentro `BubbleEngine.compute`, e il contatore `MCG:map.drawUs`: il tempo di disegno di ogni fotogramma di MapLibre **come lo misura MapLibre** (`OnDidFinishRenderingFrameListener`, microsecondi; MapLibre li da' in secondi), scritto dalla superficie della mappa. Mai dati: nomi fissi e un numero.
- **Mappa dettagliata sempre spenta**: `MeasurementHooks.mapOnlineAllowed = false` nella variante di misura spegne il cancello di build della mappa (`MapOnline.Unavailable`): la carta e' **solo quella di base offline** (`Natural Earth · GeoNames`), non parte nessuna richiesta verso OpenFreeMap e le impostazioni del dispositivo non si toccano. Nessuna prova nomina la scelta online (`check.ps1` lo controlla sui sorgenti di `benchmark/`).

**Come girano e come si saltano.** Ogni prova ha la sua **validita'** in `soglie.json` (`memoriesSeeded`, `mapSeeded`, `memoriesSections`, `mapDrawFrames`, `mapClustersSections`, `memoriesComputing`, `mapWasOpened`): senza, `compare.ps1` dice `NON VALIDA` o `MANCA`. Si saltano con il motivo: dataset senza scaffale o senza posizioni, Raccolte senza un ricordo da aprire, riga «Mappa» assente, mappa senza bolle (MapLibre non parte o nessun punto), pilota che non scrive lo stato. `memoryOpenMs`, `mapOpenMs` e `zoneOpenMs` li misura UiAutomator: comprendono il ritardo di rilevamento dell'albero di accessibilita' (decine di ms), quindi sono prudenti per i tetti e la regressione confronta sempre la stessa misura. Le etichette di cui le prove si fidano sono in `MemoriesUi` e `MapUi` (titoli dei ricordi, «Riproduci», «Mappa», frasi delle bolle «N foto, luogo»): se cambiano si cambiano li'.

**Il tempo di disegno resta da calibrare.** `mappa-disegno` ha `"massimo": null` con la calibrazione (`margine` 0,10, `passo` 0,5): compare.ps1 lo dice `CAL`, mai `FAIL`. Il tetto provvisorio del progetto e' 12 ms; dopo la prima corsa buona sull'S25 `compare.ps1 -Calibrate` lo scrive (misura + 10% a multipli di 0,5 ms). Da questo pacchetto `-Calibrate` vale per ogni regola con `calibrazione` (prima solo il picco di PSS dello zoom a 200 MP).

**Le guardie esistenti con la mappa aperta e la generazione in corso.** `memoriesLoadedScroll` e `mapLoadedScroll` sono le prove «cariche» come `facesLoadedScroll`: la guardia di scorrimento della Libreria con la generazione in corso e dopo aver aperto la mappa. La mappa e' una pagina a tutto schermo dell'app, non una scheda: «aperta in un'altra scheda» e' qui «aperta e poi lasciata» (MapLibre, indice dei punti e bolle restano nel processo); mentre si scorre la Libreria la `MapView` e' gia' stata distrutta, quindi la guardia vede il costo dell'indice e della memoria, non quello di un disegno concorrente. Un disegno concorrente non e' possibile nell'app com'e'.

**Soglie JVM del §5.4** (a ogni `check.ps1`, dentro `galleryTest`; `check.ps1` controlla anche che i limiti nei test non siano stati allentati):

| Soglia | Dove sta |
|---|---|
| generazione su 100 mila < 1 s (a freddo, a caldo, con la scelta di P7) | `core-memories/.../plan/MemoryEnginePerformanceTest.kt` |
| scelta di un ricordo da 200 candidati < 50 ms | `core-memories/.../pick/PickPerformanceTest.kt` (`LIMIT_MS = 50.0`) |
| indice della mappa: costruzione < 800 ms, `clusters` < 4 ms p95 | `core-map/.../points/GeoPointIndexPerformanceTest.kt` |
| query dei punti su 100 mila righe < 400 ms, senza `TEMP B-TREE` | `core-database/.../MapPointsQueriesTest.kt` (`centoMilaRigheSottoI400MillisecondiSenzaOrdinareInMemoria`, `LIMIT_MS = 400L`) |

### Come girano

```powershell
# Smoke sull'emulatore Medium_Phone (x86_64): gli scenari girano, i numeri non valgono
powershell -ExecutionPolicy Bypass -File tools\bench\seed-media.ps1
powershell -ExecutionPolicy Bypass -File tools\check.ps1 -Bench
# Solo le due classi nuove
.\gradlew.bat --no-daemon --max-workers=2 :benchmark:connectedBenchmarkReleaseAndroidTest -PbenchKeepX86=true `
  "-Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.enabledRules=macrobenchmark" `
  "-Pandroid.testInstrumentationRunnerArguments.benchSmoke=true" `
  "-Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.suppressErrors=EMULATOR,LOW-BATTERY,NOT-PROFILEABLE" `
  "-Pandroid.testInstrumentationRunnerArguments.class=dev.pampa.mycloudgallery.benchmark.MemoriesBenchmark,dev.pampa.mycloudgallery.benchmark.MapBenchmark"
# Misura vera (S25, poi Tab S9: un dispositivo per volta), dopo Impostazioni -> Display -> Fluidita' movimento: Adattiva
powershell -ExecutionPolicy Bypass -File tools\check.ps1 -Bench -BenchSerial <seriale>
powershell -ExecutionPolicy Bypass -File tools\bench\compare.ps1 -Calibrate      # il tetto del tempo di disegno di MapLibre
powershell -ExecutionPolicy Bypass -File tools\bench\compare.ps1 -Accept -RefreshHz 120
```

**`benchmarkRelease` su un emulatore x86_64.** Dalla Fase 6 (P11) la `benchmarkRelease` porta solo `arm64-v8a`: sull'emulatore x86_64 non si installava piu' (`INSTALL_FAILED_NO_MATCHING_ABIS`) e lo smoke non girava. Con `-PbenchKeepX86=true` (lo mette `check.ps1 -Bench` da solo su un emulatore) `app/build.gradle` tiene `x86_64` **nella sola `benchmarkRelease`**; la release non ha mai `x86_64` (passo 7e di `check.ps1`), e sul telefono e sul tablet non cambia niente. L'APK dello smoke pesa circa 164 MB invece di 90: il dato dell'emulatore ha poco spazio (5,8 GB condivisi), e **si disinstalla prima di reinstallare** (`adb uninstall dev.pampa.mycloudgallery.bench`; Gradle lo fa da solo a fine prove).

### Esito dello smoke (31/10, Medium_Phone, API 37, x86_64, headless, 4 GB, dataset da 5.000 righe)

`tools\check.ps1 -Bench` (tutte le classi di `:benchmark`, `benchSmoke=true`: 2 iterazioni e un ciclo; `-PbenchKeepX86=true` messo da solo) sull'emulatore `Medium_Phone` (sdk_gphone16k_x86_64, API 37, headless, 4 GB), con i 60 media di `seed-media.ps1` e il dataset da 5.000 righe (57% solo-NAS, 2.992 righe con posizione, 336 righe con le date di anniversario, 5 ricordi seminati). Poi `compare.ps1 -Smoke` sui risultati. **I numeri dell'emulatore non valgono**: la colonna dice solo che la misura c'e'.

| Prova | Esito | Cosa si e' visto (emulatore, non vale) |
|---|---|---|
| `memoryOpen` | girata | `memoryOpenMs` ~0,64 s: Raccolte mostra i cinque ricordi seminati, il tocco apre la pagina con la griglia |
| `memoryPlayer` | girata | 6 s di presentazione (smoke), `jankPercent` e `maxFrameCpuMs` misurati; «Riproduci» e il player si trovano |
| `memoriesGenerate` | girata | sezioni `MCG:memories.generate` 312 ms (anche 313 ms dentro l'app) e `.light` 4,3 ms su 5.000 righe, 2 ricordi nuovi, nessun pacchetto perso |
| `collectionsScroll` | girata | fling nelle Raccolte con le sezioni Ricordi e Luoghi (la riga «Mappa» sta sotto: l'elenco scorre) |
| `memoriesLoadedScroll` | girata | `memoriesComputing` = 1: la generazione lavora a bassa priorita' durante lo scorrimento della Libreria |
| `mapOpenCold`, `mapOpenWarm` | girate | MapLibre parte sull'emulatore (carta di base offline `Natural Earth · GeoNames`, bolle con le miniature), `mapOpenMs` ~1,1 s |
| `mapGestures` | girata | spostamenti e pizzichi per 4 s (smoke), `jankPercent` misurato |
| `mapDraw` | girata | 173 fotogrammi con il contatore `MCG:map.drawUs`; p95 ~0,03 ms: il numero dichiarato da MapLibre e' minuscolo sull'emulatore (il disegno vero e' sulla GPU) |
| `mapClusters` | girata | 3 sezioni `MCG:map.clusters` per iterazione, p95 13 ms (emulatore, JIT), nessun pacchetto perso |
| `mapZoneOpen` | girata | dal tocco sulla bolla piu' piccola alla prima cella della griglia della zona |
| `mapZoneViewer` | girata | la sezione `MCG:viewerOpen` c'e' anche dalla griglia della zona: `viewerOpenMs` ~39 ms |
| `mapMemory` | girata | `mapPssKb` ~157 MB a mappa aperta (heap nativo 81 MB) |
| `mapLoadedScroll` | girata | `mapWasOpened` = 1: aperta la mappa, poi la Libreria con `scroll_all_square` |

Le altre classi con i ricordi montati anche nella variante di misura (le guardie che «non devono peggiorare»): scorrimenti, pinch, avvio a freddo e abituale, viewer, memoria e le due prove del NAS **girano come prima**. Il generatore del profilo e lo zoom a 200 MP (solo tablet) si saltano come sempre. Prima di questo pacchetto quattro prove delle fasi vecchie fallivano per il selettore della scheda, non per i ricordi ne' per la mappa: `collectionsPage` e `searchPage` (`PagesBenchmark`, avvio istantaneo) cercavano la descrizione della scheda e aspettavano 8 s a vuoto prima di ripiegare sul testo (la pillola ha il nome come testo): la misura cominciava con un'attesa senza fotogrammi e la traccia restava vuota («Observed no expect/actual slices»); `SearchBenchmark` (Fase 6, `searchWarm`, `searchColdContent`, `searchColdFilters`, `searchMemory`) non ripiegava mai («La scheda Cerca non si apre»). Corretti in `benchmark/` (prima il testo, poi la descrizione) e rigirate da sole: ora girano (`raccoltePageMs`, `cercaPageMs`); la ricerca gira e dice `searchSectionMissing` (l'app non emette ancora `search.firstResults`, come avverte `SearchBenchmark`). Restano `MANCA`, come prima e con il loro motivo, solo i sette che la variante non puo' fare: `albumOpen`, `albumScroll`, `albumRecompute`, `albumPage` (nessun album), `peoplePage` (nessuna Persone), `facesEngines` e `facesLoadedScroll` (nessuna misura nell'app).

### Cosa manca (sull'S25 e sul Tab S9)

- **F123** (prestazioni della mappa): `MapBenchmark` con le 100 mila righe e le soglie del §5.4; la prima corsa buona calibra `mappa-disegno` (`-Calibrate`) e, con `-Accept`, fissa il riferimento `docs/bench/riferimento-s25.json` (e `riferimento-tab-s9.json`). Finche' non c'e' un riferimento le regressioni risultano `NUOVO`.
- **F124** (prestazioni dei ricordi): `MemoriesBenchmark` e i tempi di generazione sulla Libreria da 100 mila righe.
- **F111, F112** (le guardie delle Fasi 3-8 con la mappa aperta e con la generazione in corso): `memoriesLoadedScroll` e `mapLoadedScroll`, da leggere insieme alle prove di scorrimento di sempre sullo stesso riferimento.
- Il confronto delle due sezioni nuove della traccia d'avvio (`MCG:app:ricordi`, `MCG:app:mappa`, P13) nella prossima corsa di `coldStart`: ora, con i ricordi montati anche nella variante di misura, lo scaffale si legge (`restore()`) dopo il primo disegno in ogni scenario, come nell'app vera.

## 11. Widget, pannelli e accessibilità (Fase 10, P9)

Gli scenari `widget`, `pannelli` e `accessibilita` (`docs/fase10-progetto.md` §5.4, riquadro P9 del §4.4) **esistono e girano**: `WidgetBenchmark` (3 prove), `PanesBenchmark` (5) e `AccessibilityScrollBenchmark` (3) in `benchmark/src/main/kotlin/.../benchmark/`, 11 scenari nuovi in `benchmark/soglie.json` (48 in tutto) e le soglie JVM del §5.4. Costruiti come `MemoriesBenchmark` e `MapBenchmark` (`BenchSession`, `BenchConfig`, `BenchUi`): una prova che non può girare si **salta con il motivo** (`Assume`), mai un falso verde; ogni scenario ha la sua **validità** (`widgetValid`, `panesDocked`, `panesWide`, `panesWindows`, `a11yValid`, `a11yFontScale`, `widgetRefreshing`) e `compare.ps1` dice `NON VALIDA` se la prova non ha misurato quello che doveva.

| Prova (classe) | Scenario (`soglie.json`) | Cosa fa | Metrica e soglia (S25 e Tab S9, 120 Hz) | Regressione |
|---|---|---|---|---|
| `widgetUpdate` (`WidgetBenchmark`, telefono) | `widget` | Avvio a freddo, widget grande legato dal collaudo; 1 giro a freddo, 3 a caldo e 2 «Nascondi» di prova; 8 iterazioni | sezione `MCG:widget.update`: `widgetUpdateMs` (il più lungo dei giri **dopo il primo**) p90 ≤ 400 ms; sezione `MCG:widget.blank`: `widgetBlankMs` p90 ≤ 100 ms | p90 +15% (anche `widgetUpdateColdMs`, il primo giro del processo) |
| `widgetUpdateTablet` | `widget-tablet` | La stessa prova su un tablet (≥ 600 dp di larghezza minima) | `widgetUpdateMs` p90 ≤ 600 ms; `widgetBlankMs` p90 ≤ 100 ms | p90 +15% |
| `widgetLoadedScroll` | `widget-scroll-carico` | `scroll_all_square` con l'aggiornamento del widget **in corso** (i giri di continuo, uno alla volta con una pausa); 8 iterazioni | `jankPercent` medio ≤ 1% | `frameDurationCpuMs` P95 +15%; guardia: riferimento +10%, pavimento 0,15 punti |
| `panesScroll` (`PanesBenchmark`) | `pannelli-scroll` | Finestra di 1280 dp, viewer affiancato, fling avanti e indietro nella griglia a sinistra; 8 iterazioni | `jankPercent` medio ≤ 1% | guardia +10% (pavimento 0,15); `frameDurationCpuMs` P95 +15% |
| `panesViewerSwap` | `pannelli-foto` | Tocco su una cella del pannello: dalla posizione del viewer prima a quella dopo (UiAutomator); 12 iterazioni | `viewerSwapMs` p90 ≤ 100 ms | p90 +15% |
| `panesZoneOpen` | `pannelli-zona` | La mappa nella finestra larga, tocco sulla bolla piccola: dalla bolla alla prima cella della griglia **nel pannello**; 12 iterazioni | `zoneOpenMs` p90 ≤ 150 ms | p90 +15% |
| `panesCrossing` | `pannelli-soglia` | Il viewer affiancato e la finestra ridimensionata attraverso 1280, 960, 720 dp e ritorno (`wm size`); 5 iterazioni | `maxFrameCpuMs` (il fotogramma più lungo) max ≤ 33 ms | max +15% |
| `panesWindowsMemory` | `pannelli-memoria` | Due finestre (la seconda con `am start` `NEW_TASK \| MULTIPLE_TASK \| LAUNCH_ADJACENT`), qualche scorrimento, PSS; 3 iterazioni | `windowsPssKb` max: nessun tetto, la prima corsa buona è il riferimento | +15% |
| `a11yScroll` (`AccessibilityScrollBenchmark`) | `accessibilita-scroll` | `scroll_all_square` con il servizio di prova acceso (strumento, legge l'albero a ogni scorrimento); 8 iterazioni | `jankPercent` medio ≤ 1% | guardia +10% (pavimento 0,15); `frameDurationCpuMs` P95 +15% |
| `a11yTextScroll` | `accessibilita-testo` | `scroll_all_square` con `font_scale` 2,0 | `jankPercent` medio ≤ 1% | `frameDurationCpuMs` P95 +15% |
| `a11yViewerOpen` | `accessibilita-viewer` | Apertura del viewer con il servizio acceso; 12 iterazioni | `viewerOpenMs` (sezione `MCG:viewerOpen`) p90 ≤ 100 ms | p90 +15% |

**Il widget: pilota sulle porte vere** (`app/src/benchmarkRelease/`; con P13 su `main` `WidgetGraph` lega le porte vere in `Application.onCreate` e il widget vero disegna: lo scenario misura quello, non un seme di prova).
- **Sezioni di traccia** (`AvvioTrace.WIDGET_UPDATE`, `WIDGET_BLANK`, dentro `WidgetEngine`): `MCG:widget.update` comincia quando il giro ha il suo turno e finisce alla consegna ad `AppWidgetManager`; `MCG:widget.blank` copre lo svuotamento (registro, disegno senza immagini, consegna) e **non** il giro che segue. Sono l'unica modifica al codice di produzione del pacchetto (sezioni asincrone, nomi fissi, mai dati: `AvvioTraceFase10Test`).
- **Le porte** sono quelle vere di `WidgetGraph` sulla Libreria del dataset di misura (pianificatore sopra la fonte dei nascosti del catalogo, registro su file, miniature locali, scaffale dei ricordi seminato `BenchShelf`): nessun seme di prova. Il pilota le aspetta con `WidgetHost.services` (a processo nuovo il fornitore le monta e le da' appena sono pronte). Prima della fusione con P13 c'era un seme (`BenchWidgetServices`: pianificatore vero, miniature WebP di prova); e' stato tolto.
- **Il pilota** (`BenchWidgetDriver`, `BenchWidgetReceiver`, esportato e protetto dal solo `DUMP` come il `ProfileInstallReceiver`): niente gira all'avvio; il collaudo manda `am broadcast -n <id>/dev.pampa.mycloudgallery.bench.BenchWidgetReceiver -a dev.pampa.mycloudgallery.bench.WIDGET_<COMANDO>` (`PREPARE`, `UPDATE`, `BLANK`, `LOAD_START`, `LOAD_STOP`, `CLEANUP`) e legge `bench-widget.json` accanto al marcatore (solo numeri). Il widget lo lega **il collaudo**, non l'app (legare un widget vuole `BIND_APPWIDGET`, che un'app non ha, e `cmd appwidget` non ha comandi su questa piattaforma): `WidgetDriver.bindWidget`, nel processo di prova, adotta l'identità della shell per quel solo permesso (`UiAutomation.adoptShellPermissionIdentity`), alloca l'id con un `AppWidgetHost` suo e lo lega al ricevitore dell'app con la misura grande (250 x 250 dp); a fine prova lo toglie. **Nessun launcher**: il disegno arriva ad `AppWidgetManager` (il lavoro del launcher che lo mostra è in un altro processo e non fa parte della misura).

**I pannelli e le due finestre.** Sull'emulatore la finestra larga si emula con `wm size 2560x1600` e `wm density 320` (1280 x 800 dp, come il Tab S9 in orizzontale) e si rimette a fine prova; sul tablet vero non si cambia lo schermo (serve la larghezza: orizzontale o DeX; `benchAllowScreen=true` lo permette, per `pannelli-soglia`); su un telefono vero la classe si salta. Il viewer si affianca dal suo menu («Altre azioni», «Affianca alla Libreria») e a fine prova si rimette a schermo intero (la scelta si ricorda per profilo). **Le due finestre sono Libreria e Libreria**: il §5.4 dice «Libreria e un album», ma gli album non sono montati nella variante di misura (L287).

**Il servizio di accessibilità di prova** (`BenchA11yService`, nel solo APK di collaudo `:benchmark`): un servizio vero, `isAccessibilityTool="true"` come TalkBack (la finestra dell'app gli dà l'albero, P8), che a ogni scorrimento o cambio di contenuto, al più ogni 100 ms, percorre tutto l'albero della finestra attiva (più severo di TalkBack, che legge il nodo a fuoco e i vicini). Si accende con `settings put secure enabled_accessibility_services` e si rimette com'era (`BenchSettings`: ogni impostazione cambiata è ricordata e rimessa, anche da una corsa morta a metà). **UiAutomation sospende gli altri servizi** quando si collega senza `FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES`: la prova imposta il flag e controlla che il servizio abbia letto davvero (`a11yWalks`, `a11yConnected`); se no, `NON VALIDA`. Il `warm` delle descrizioni parte con il descrittore legato da P13 (`A11yActivity` sopra `AccessibilityManager`): il servizio di prova e' uno strumento, quindi lo accende, e queste prove misurano il lettore dell'albero, gli eventi **e** il `warm`.

**Le guardie esistenti con il servizio acceso.** Una corsa di tutti gli scenari di sempre con il servizio acceso per tutta la durata (`BenchServiceListener`, argomento `benchWithService=true`): vedi i comandi per l'S25 sotto. **Mai `-Accept` su quella corsa**: il riferimento è quello senza.

**Soglie JVM del §5.4** (a ogni `check.ps1`, dentro `galleryTest`):

| Soglia | Dove sta |
|---|---|
| piano del widget < 50 ms (p95, Libreria da 100 mila) | `core-widget/.../plan/DefaultWidgetPlannerBigWorldTest.kt` (`isLessThan(50_000)` microsecondi) |
| verifica di un manifest firmato < 5 ms (mediana, manifest al limite) | `core-release/.../codec/SignedManifestVerifierPerformanceTest.kt` (nuova, P9) |
| descrizione < 1 ms a cache calda | `core-describe/.../rules/DefaultMediaDescriberTest.kt` (`isLessThan(1_000_000L)` ns) |
| `warm` di 60 celle < 20 ms (mediana) | `core-describe/.../rules/DefaultMediaDescriberWarmPerformanceTest.kt` (nuova, P9) |
| svuotamento del widget < 100 ms dopo `hiddenChanged` | `feature-widget/.../WidgetBlankTimingTest.kt` (P3: 19-31 ms) |
