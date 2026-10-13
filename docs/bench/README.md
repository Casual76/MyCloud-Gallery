# Prestazioni misurate (Fase 3, P9)

> Fonti: `docs/fase3-progetto.md` D21, §2.6 (tabella degli scenari e delle soglie), §4.1 (tappa M1), §4.3 (riga P9), §5.5 (F11, F12).
> Qui sta **come si misura**. I **numeri** accettati stanno in `docs/bench/riferimento-<dispositivo>.json` (formato in `formato-riferimenti.md`)
> e le soglie in `benchmark/soglie.json`. La parte di M1 (la griglia vera su `SyntheticLibrary`) la scrive P7 in `docs/bench/M1.md`.

## 1. Che cosa c'è

| Cosa | Dove |
|---|---|
| Scenari Macrobenchmark e generatore del Baseline Profile | `benchmark/src/main/kotlin/dev/pampa/mycloudgallery/benchmark/` |
| Soglie assolute e regole di regressione | `benchmark/soglie.json` |
| Dataset della variante di misura (100 mila righe) | `app/src/benchmarkRelease/` (solo lì: mai in release) |
| Confronto con soglie e riferimento | `tools/bench/compare.ps1` (con `-SelfTest` si prova senza dispositivo) |
| Il passo `-Bench` e il controllo del profilo | `tools/check.ps1` (`-Bench`, `-BenchSerial`, `-BenchStrict`) |
| Profilo di base su `benchmarkRelease` | `tools/bench/generate-profile.ps1` |
| Media di prova per l'emulatore | `tools/bench/seed-media.ps1` |
| I budget JVM (complessità, contatori) | dentro `galleryTest`, non qui |

## 2. Gli scenari

Tutti con `CompilationMode.Partial(BaselineProfileMode.Require)` (si cambia con `benchCompilation`, vedi `BenchConfig`). Il nome della prova è la chiave di `soglie.json`.

| Prova (classe) | Cosa fa | Metrica e soglia | Regressione |
|---|---|---|---|
| `scroll_<livello>_<stile>` (`LibraryScrollBenchmark`), 8 prove: `years/months/days/all` × `square/justified` | App appena avviata, livello e stile impostati, fling avanti e indietro nel tempo su 100 mila elementi | `FrameJankMetric`: `jankPercent` medio ≤ 1% (fotogrammi davvero in ritardo, vedi §2-bis) | P95 `frameDurationCpuMs` +10% |
| `pinch` (`LibraryPinchBenchmark`) | 10 pinch continui (5 chiusure + 5 aperture) fra i livelli | `jankPercent` medio ≤ 1% | P95 `frameDurationCpuMs` +10% |
| `coldStart` (`StartupBenchmark`) | `StartupMode.COLD`, 15 ripetizioni | `timeToFullDisplayMs` p90 < 1000 ms (serve `reportFullyDrawn`) | p90 +10% |
| `viewerOpen` (`ViewerBenchmark`) | Tocco su una miniatura, attesa, Indietro; 12 iterazioni | sezione di traccia `MCG:viewerOpen` p90 < 100 ms; `viewerOpenMaxFrameMs` (il fotogramma più lungo nei primi 500 ms dell'apertura) massimo ≤ 16,7 ms | p90 +15% |
| `zoom200Mp` (`Zoom200MpBenchmark`), solo tablet | Apre la foto più grande (la riga più recente), 5 cicli di zoom e pan sui quattro angoli | `processAlive` = 1 (nessun OOM); `pssPeakKb` sotto il tetto calibrato alla prima misura | PSS +15% |

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

Il riferimento si **committa** con il motivo nel messaggio; una regressione si corregge, non si "accetta". La chiusura della fase richiede `riferimento-s25.json` e `riferimento-tab-s9.json` (e la nota con la tabella di `compare.ps1` incollata in `docs/bench/misure-<dispositivo>-<data>.md`).

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

1. `BenchSession.prepareDevice()` (la sonda del modulo `:benchmark`, una volta per processo di test) concede con `pm grant` i permessi di lettura dei media, scrive il marcatore `bench-library.marker` in `/sdcard/Android/data/<id>/files/` e avvia l'app.
2. `BenchDatasetProvider` (un `ContentProvider` non esportato, creato dal sistema prima di `Application.onCreate`) vede il marcatore **su un thread proprio** (sul thread principale non fa niente: non sposta l'avvio a freddo) e crea `gallery-bench.db` (mai `gallery.db`) con `BenchSeeder`.
   Nello smoke sull'emulatore la sonda scrive anche `bench-library.smoke`: con quel file accanto al marcatore il dataset è di **5.000 righe** invece di 100.000 (in software un emulatore, con il resto della macchina occupata, ci metterebbe decine di minuti). L'app guarda solo se i file *esistono*: il contenuto di un file creato da shell in `Android/data/<id>/files/` non lo può leggere (permesso negato, provato su Android 37).
3. Lo stato sta in `bench-dataset.json` accanto al marcatore (`in_corso` → `pronto` / `errore`, righe, media veri, megapixel massimi, tempo di creazione, righe rilette dall'indice, riferimenti del telefono risolti). Un avvio successivo con lo stato `pronto` non rifà niente: gli avvii misurati non lo pagano.

Forma delle righe (§5.2): 100.000 elementi, seme fisso, 15 anni con 240 giorni "a raffica" (38% delle righe) e giornate vuote; tipo e proporzione presi dal media vero a cui la riga punta (rotazione `i % N`), screenshot 10%, WhatsApp 20%, Quick Share 2%, preferiti 2%, date stimate 3%, luoghi 60% (id veri di GeoNames), il 10% in fusi diversi, panorami dove la proporzione lo è. La riga **più recente** punta all'immagine più grande (se il dispositivo ne ha una): la Libreria si apre in fondo (D1), quindi lo zoom a 200 MP la trova nell'ultima cella. Niente ThumbHash (`thumb_hash` nullo): le celle partono vuote invece che sfocate.

Il seeder scrive con SQL diretto le tabelle `media`, `media_version`, `media_location`, `version_metadata`, `media_store_cursor` e `catalog_meta` leggendo le colonne da `PRAGMA table_info`: una colonna `NOT NULL` aggiunta da una migrazione futura non lo rompe, una colonna che sparisce lo fa fallire subito (stato `errore`, con il nome della tabella). Alla fine rilegge il file con un'istanza nuova di `GalleryDatabase` e controlla con le query vere di P1 (`indexCursor()`: 100.000 righe; `phoneRefs`: tutte le prime 200 risolte) che il dataset sia quello che l'indice della timeline si aspetta.

Senza media veri (emulatore appena creato) il dataset si crea lo stesso ma lo stato dice `mediaReali: 0` e la sonda lo scrive nel motivo: le celle restano vuote e le misure non sono rappresentative. `tools\bench\seed-media.ps1` mette una sessantina di immagini sull'emulatore.

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
- `databaseName`: `gallery-bench.db` (`BenchDataset.DATABASE_NAME`), così anche `nonMinifiedRelease` (`:app:generateBaselineProfile`) apre il dataset e non `gallery.db` vuoto (I3).

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

## 8. Limiti e cose da sapere

- **Più dispositivi collegati:** `check.ps1 -Bench` si ferma e chiede `-BenchSerial`; Gradle gira su tutti i dispositivi collegati a meno di `ANDROID_SERIAL`, e lo script lo imposta per quello scelto. Gli emulatori sono condivisi con gli altri pacchetti: la sonda tocca solo il pacchetto sotto misura e quello di test.
- **I numeri dell'emulatore non valgono.** Servono a vedere che gli scenari girano. Medium_Phone (API 37.1) usa un'immagine con pagine da 16 KB: può comparire un avviso di compatibilità all'avvio dell'app.
- **Ci vuole la Libreria raggiungibile**: dal P11 la variante di misura apre la Libreria vera sul dataset (famiglia "pronta" dalle `MeasurementHooks`). Senza il marcatore del dataset l'app va all'onboarding: tutte le prove tranne `coldStart` si saltano con il motivo e `coldStart` misura l'onboarding (marcato non valido).
- **Il profilo è fatto su un dataset realistico, non sul tuo.** Tipi, proporzioni e rotazione vengono dai media veri del dispositivo; le date e la distribuzione sono sintetiche.
- **Le fixture dei video**: il dataset ci mette i video veri del dispositivo (con la loro durata) ma il benchmark non li riproduce; la riproduzione si prova sul campo (F12).
- **Niente rete**: né l'app (D24) né il collaudo la usano. `benchmark` dichiara `INTERNET` solo perché lo porta la libreria di test nel suo APK (mai in quello dell'app).
