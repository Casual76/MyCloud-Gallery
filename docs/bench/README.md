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
| `scroll_<livello>_<stile>` (`LibraryScrollBenchmark`), 8 prove: `years/months/days/all` × `square/justified` | App appena avviata, livello e stile impostati, fling avanti e indietro nel tempo su 100 mila elementi | `FrameTimingMetric`: `frameOverrunMs` P99 ≤ 0 (meno dell'1% oltre gli 8,3 ms) | P95 `frameDurationCpuMs` +10% |
| `pinch` (`LibraryPinchBenchmark`) | 10 pinch continui (5 chiusure + 5 aperture) fra i livelli | `frameOverrunMs` P99 ≤ 0 | P95 `frameDurationCpuMs` +10% |
| `coldStart` (`StartupBenchmark`) | `StartupMode.COLD`, 15 ripetizioni | `timeToFullDisplayMs` p90 < 1000 ms (serve `reportFullyDrawn`) | p90 +10% |
| `viewerOpen` (`ViewerBenchmark`) | Tocco su una miniatura, attesa, Indietro; 12 iterazioni | sezione di traccia `MCG:viewerOpen` p90 < 100 ms | p90 +15% |
| `zoom200Mp` (`Zoom200MpBenchmark`), solo tablet | Apre la foto più grande (la riga più recente), 5 cicli di zoom e pan sui quattro angoli | `processAlive` = 1 (nessun OOM); `pssPeakKb` sotto il tetto calibrato alla prima misura | PSS +15% |

- **Se una cosa non c'è, la prova si salta con il motivo; non si inventa un numero.** Una prova senza Libreria raggiungibile, senza menu dello stile o senza la foto da 200 MP risulta `MANCA` in `compare.ps1`, con la riga del rapporto JUnit che dice perché.
- L'avvio a freddo gira anche senza Libreria (misura l'avvio verso l'onboarding), ma la metrica `libraryShown` lo marca **non valido** e `compare.ps1` non lo confronta con i 1000 ms.
- Il tetto di PSS dello zoom è `null` in `soglie.json` finché non c'è la prima misura sul Tab S9: `compare.ps1 -Calibrate` lo scrive (misura + 10%, a multipli di 1 MB).
- Gli id dell'interfaccia di cui le prove si fidano (descrizioni delle celle, nomi dei livelli, menu dello stile) sono in **un solo posto**: `BenchLabels` in `BenchUi.kt`. P7 le conferma o le cambia a M1.

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
- La variante `benchmarkRelease` ha **lo stesso `applicationId` della release** e la firma di debug: installarla **sostituisce** la release firmata (e Android rifiuta l'aggiornamento: `INSTALL_FAILED_UPDATE_INCOMPATIBLE`, quindi va disinstallata prima, e i dati locali della release si perdono). La build debug (`.debug`) non è toccata. Sull'S25 di prova si misura quando la release firmata non serve (F18 si fa dopo, reinstallandola).
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

- `baselineProfile { automaticGenerationDuringBuild = false; saveInSrc = true }` in `app/build.gradle`: il profilo **non** si genera durante il build (serve un dispositivo) e si salva in `app/src/main/generated/baselineProfiles/` (da committare).
- Il generatore è `BaselineProfileGenerator` (scorrimento, pinch, apertura del viewer con pager, info, zoom, un video). **Non produce un profilo povero**: se la Libreria non è raggiungibile si salta.
- Due strade, stessi file in uscita:
  1. `gradlew :app:generateBaselineProfile`: il percorso ufficiale del plugin, sulla variante `nonMinifiedRelease`. Quella variante **non ha il dataset** (vive solo in `app/src/benchmarkRelease/`) né il `ProfileInstallReceiver`. Per usarla serve che `app/build.gradle` (P0/P11) aggiunga a `nonMinifiedRelease` lo stesso `src/benchmarkRelease` (sorgenti e manifest) nel blocco `finalizeDsl` che già c'è per `benchmarkRelease`: due righe, vedi §6.
  2. `tools\bench\generate-profile.ps1`: lo stesso generatore sulla variante `benchmarkRelease` (R8 attivo, senza offuscamento: i nomi restano, R8 scarta da solo le regole che non trovano più il metodo). È la strada che funziona oggi.
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

La variante di misura può mettere **file** e **servizi** nell'app, ma `src/main` non può chiamare un `Bench*` che non c'è nelle altre varianti. Per questo il collegamento è uno `ServiceLoader` su contratti che esistono già:

`app/src/benchmarkRelease/resources/META-INF/services/` registra
- `dev.pampa.mycloudgallery.core.family.contract.FamilySetupRepository` → `BenchFamilySetupRepository` (`SetupState.Ready(admin = false)`: la famiglia "pronta", senza NAS e senza rete);
- `dev.pampa.mycloudgallery.core.mediastore.contract.PhoneLibrarySync` → `BenchPhoneLibrarySync` (ferma: la scansione vera riconcilierebbe le 100 mila righe sintetiche con i pochi media veri e le segnerebbe assenti).

**Cosa deve fare P11** nel grafo (in `src/main`, 3 punti; nelle altre varianti `ServiceLoader` non trova niente e resta il comportamento vero):

```kotlin
// GraphModule: la famiglia
@Provides fun familySetupRepository(graph: AppGraph): FamilySetupRepository =
  ServiceLoader.load(FamilySetupRepository::class.java).firstOrNull() ?: graph.setup

// dove si costruisce la sincronizzazione del telefono
val phoneSync: PhoneLibrarySync =
  ServiceLoader.load(PhoneLibrarySync::class.java).firstOrNull() ?: RealPhoneLibrarySync(/* ... */)

// dove si apre il database della Libreria: il dataset è in un file a parte
val database = GalleryDatabase.build(
  context,
  if (BuildConfig.BUILD_TYPE == "benchmarkRelease") "gallery-bench.db" else GalleryDatabase.NAME,
)
```

**Da controllare dopo il collegamento:** R8 elimina i servizi che nessuno carica (provato su un APK minimo: senza una chiamata a `ServiceLoader.load` la classe e il file in `META-INF/services/` spariscono). Quindi, quando P11 ha messo le righe qui sopra, aprire l'APK `benchmarkRelease` e controllare che `META-INF/services/dev.pampa.mycloudgallery.core.family.contract.FamilySetupRepository` ci sia ancora e che nel dex ci siano `BenchFamilySetupRepository` e `BenchPhoneLibrarySync`; se mancano, la Libreria dello scenario resta sull'onboarding e la sonda lo dice ("In attesa di M1").

Altre due cose che le prove si aspettano dall'app (senza, girano lo stesso e `compare.ps1` lo dice):

- **`reportFullyDrawn()`** alla prima pagina di miniature disegnata o dopo 300 ms (P7/P11, per esempio dalla `LibraryTab`): è il TTFD dell'avvio a freddo.
- **La sezione di traccia `MCG:viewerOpen`** dal gestore del tocco al primo fotogramma con l'immagine (P8). Va bene sincrona o asincrona; fra il tocco e il primo fotogramma passano più fotogrammi, quindi di solito è asincrona:
  ```kotlin
  android.os.Trace.beginAsyncSection("MCG:viewerOpen", cookie)   // al tocco
  android.os.Trace.endAsyncSection("MCG:viewerOpen", cookie)     // al primo fotogramma con la bitmap
  ```
  La metrica (`ViewerOpenMetric`) legge la tabella `slice` di Perfetto, dove compaiono tutte e due le forme.

Per `:app:generateBaselineProfile` ufficiale (non obbligatorio, vedi §4) in `app/build.gradle`, dentro il `finalizeDsl` che c'è già:
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
| `check.ps1 -Bench` con un dispositivo vero e con la **vera app** | **non provato**: l'app non si assembla finché P11 non lega i `@HiltViewModel` di P7/P10 (`MissingBinding`: `ThumbnailLoader`, `GridLayoutEngine`, `PhoneMediaIntents`…). Vale anche per `assembleDebug`/`assembleRelease` |
| `:app:generateBaselineProfile` e `toolsench\generate-profile.ps1` | **non provati** (stessa ragione, e la Libreria deve essere raggiungibile) |
| Servizi `ServiceLoader` dopo R8 nell'app vera (`META-INF/services` nell'APK) | **da controllare** dopo il collegamento (§6) |

## 8. Limiti e cose da sapere

- **Più dispositivi collegati:** `check.ps1 -Bench` si ferma e chiede `-BenchSerial`; Gradle gira su tutti i dispositivi collegati a meno di `ANDROID_SERIAL`, e lo script lo imposta per quello scelto. Gli emulatori sono condivisi con gli altri pacchetti: la sonda tocca solo il pacchetto sotto misura e quello di test.
- **I numeri dell'emulatore non valgono.** Servono a vedere che gli scenari girano. Medium_Phone (API 37.1) usa un'immagine con pagine da 16 KB: può comparire un avviso di compatibilità all'avvio dell'app.
- **Ci vuole la Libreria raggiungibile**: finché P11 non lega i tre punti del §6 e P7 non consegna la griglia (M1), tutte le prove tranne `coldStart` si saltano con "In attesa di M1" e `coldStart` misura l'onboarding (marcato non valido).
- **Il profilo è fatto su un dataset realistico, non sul tuo.** Tipi, proporzioni e rotazione vengono dai media veri del dispositivo; le date e la distribuzione sono sintetiche.
- **Le fixture dei video**: il dataset ci mette i video veri del dispositivo (con la loro durata) ma il benchmark non li riproduce; la riproduzione si prova sul campo (F12).
- **Niente rete**: né l'app (D24) né il collaudo la usano. `benchmark` dichiara `INTERNET` solo perché lo porta la libreria di test nel suo APK (mai in quello dell'app).
