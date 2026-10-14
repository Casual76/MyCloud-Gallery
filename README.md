# MyCloud Gallery

Galleria Android privata per una famiglia di soli adulti, complementare a Samsung Gallery: foto del
telefono e del NAS WD My Cloud EX2 Ultra in una sola libreria, backup automatico, ricerca sul
dispositivo, nessun cloud di terzi e nessuna telemetria. Telefono e tablet.

Stato: **Fase 4A integrata (P11, M1)**. Oltre alla Fase 2 (rete e casa, sessione SMB verso il NAS, famiglia, QR, Impostazioni e
Diagnostica) l'app mostra la **galleria del telefono**: Libreria (griglia della timeline su anni, mesi, giorni e tutte, con
selezione, preferiti, nascosti ed eliminazione dal telefono), viewer (zoom, video, foto in movimento, informazioni) e Raccolte
(Preferiti, Video, Screenshot, WhatsApp, Nascosti ed Eliminati di recente dietro lo sblocco). Tutto sul telefono, senza rete:
il NAS nella Libreria e' la Fase 4 (`docs/fase4-progetto.md`). Con il **P11 della 4A** (milestone M1) i pacchetti P0–P10
sono collegati nel grafo vero dell'app (`nas/NasGraph`): la scansione del NAS porta i media nella Libreria (data, presenza
"solo NAS", miniature EXIF provvisorie), la preparazione produce le anteprime e le pubblica nell'indice della share, gli altri
dispositivi sincronizzano l'indice e vedono le miniature senza preparare niente, il viewer apre gli originali dal NAS e
"Scarica" li copia sul telefono. Nessuna operazione sui media del NAS (D1): le scritture sono solo nell'area dell'app
(`.mycloudgallery/`), e nella 4A solo nelle share di prova (D31). Le operazioni sui file del NAS arrivano con la 4B.

## Verifica in un comando

```powershell
powershell -ExecutionPolicy Bypass -File tools\check.ps1
```

Esegue, in ordine, e finisce con un riepilogo OK/FAIL (codice di uscita 0 solo se e' tutto OK):

1. `engine\tools\engine-doctor.ps1`: l'engine e' quello che `engine.properties` dichiara;
2. nessuna modifica locale dentro `engine/`;
3. nessuna chiave di firma o `local.properties` tracciati da git;
4. verifica dei checksum attiva (`gradle/verification-metadata.xml`) e wrapper fissato;
5. Gradle: `assembleDebug assembleRelease galleryTest galleryLint`, dove `galleryTest` e `galleryLint`
   sono `testDebugUnitTest` e `lintDebug` dei soli moduli della galleria (l'engine ha la sua CI);
   `assembleRelease` esegue anche `verifyReleaseManifest` (vedi sotto);
6. ogni modulo ha eseguito almeno un test e nessuno e' fallito;
7. ispezione dell'**APK di release** con aapt2: non debuggable, backup escluso, nessun componente
   esportato oltre quelli ammessi (la launcher e il JobService di WorkManager, solo con
   `BIND_JOB_SERVICE`), nessun codice solo-debug dentro;
8. nessuna classe di un package `testing` (i fake dei contratti) nel dex dell'APK di release.

`-SkipGradle` salta i passi 5-8 (controllo statico, pochi secondi). `-NoDaemon` non lascia Gradle residente.

## Moduli

Package base `dev.pampa.mycloudgallery`, namespace `dev.pampa.mycloudgallery.<modulo>` (es.
`dev.pampa.mycloudgallery.core.trust`). Tutti i moduli sono librerie Android (Groovy DSL) con una classe
segnaposto e almeno un test.

| Modulo | Cosa contiene | Vede |
|---|---|---|
| `:app` | `MainActivity`, blocco app, navigazione, il grafo della Fase 2 (`graph/AppGraph`), quello della galleria (`gallery/GalleryGraph`) e, dalla 4A, quello del NAS nella Libreria (`nas/NasGraph`) esposti a Hilt, `TrustSyncWorker`, `PhoneSyncWorker` e `LibraryMaintenanceWorker` (`work/GalleryWork.kt`), i lavori del NAS (`work/NasWork.kt`), notifiche e riepilogo giornaliero, ciclo di vita del processo; applicationId `dev.pampa.mycloudgallery` (debug: `.debug`; variante di misura: `.bench`) | tutti + `engine-ui` |
| `:core-common` | logger interno (in release non scrive nulla), `Redacted`, `DispatcherProvider` | nessuno |
| `:core-model` | tipi di dominio (media, posizioni, versioni, dispositivi) | nessuno |
| `:core-database` | Room + KSP: replica locale dell'indice | model, common |
| `:core-designsystem` | tema Ametista, componenti della galleria, catalogo componenti | `engine-ui` |
| `:core-trust` | formato e firme dei dati sul NAS | model, common |
| `:core-security` | credenziali (Keystore), blocco app, protezioni dello schermo | common |
| `:core-rules` | motore delle regole delle attivita'; contratto della salute del background (`platform/health`) | model, common |
| `:core-network` | stato della rete, casa (impronta ULA), VPN di casa, cancello del percorso verso il NAS, WoL | common, rules |
| `:core-nas` | sessione SMB (smbj, unico modulo che la vede), errori, interruttore di sicurezza, test di connessione | common, model, trust, security, network, rules |
| `:core-family` | fiducia operativa e flussi di famiglia (onboarding, inviti, registro, azzeramento), solo logica | common, model, trust, security, network, nas, rules |
| `:core-qr` | QR: generazione e scansione (ZXing core + CameraX, fotocamera dentro l'app) | common, security, designsystem |
| `:feature-onboarding` | schermate della configurazione iniziale | family, network, nas, qr, security, rules, designsystem |
| `:feature-settings` | Impostazioni, famiglia e dispositivi | family, network, nas, qr, security, rules, designsystem |
| `:feature-diagnostics` | Diagnostica locale ed esportazione senza dati personali | common, rules, network, nas, family, designsystem |
| `:core-places` | luoghi offline da GeoNames (asset `places.mcgp1`), fusi orari, nomi in italiano | common, model |
| `:core-metadata` | lettura dei metadati (EXIF, contenitori video, foto in movimento), ThumbHash | common, model, places |
| `:core-mediastore` | lettura del MediaStore e sincronizzazione della Libreria del telefono (scansione, META, impronte) | common, model, database, metadata, places |
| `:core-imaging` | miniature, segnaposto, decodifica per il viewer con i tetti di memoria | common, model; dalla Fase 4 index e nas (`implementation`) |
| `:core-library` | indice della timeline, istantanea su disco, layout della griglia, raccolte, date (`text/GalleryDates`), stato del NAS nel piede (`LibraryNasStatus`, Fase 4) | common, model, database, imaging; dalla Fase 4 index (`implementation`) |
| `:feature-library` | Libreria, Raccolte, Nascosti ed Eliminati di recente | common, model, designsystem, imaging, library, mediastore, places, security |
| `:feature-viewer` | viewer a schermo intero | common, model, database, designsystem, imaging, library, mediastore, metadata, places, security |
| `:benchmark` | scenari Macrobenchmark e Baseline Profile (`docs/bench/README.md`); mai nell'APK | `:app` (variante `benchmarkRelease`) |
| `:core-index` | Fase 4: indice condiviso nelle share (pubblicazione con outbox, unità di lavoro con prenotazioni, replica locale a pacchi, sincronizzazione delle teste, qualità delle anteprime) | common, model, trust; database, nas, rules in `implementation` |
| `:core-nasmedia` | Fase 4: scansione del NAS (elenco a pagine, rilevamento con impronta rapida, esclusioni, stabilità con l'ora del NAS, congelamento per cambiamenti di massa); NAS sintetico per i test | common, model, trust, database, nas, metadata, index |
| `:core-prepare` | Fase 4: preparazione dei media (miniature S e M, anteprime di analisi e HQ, fotogrammi dei video senza scaricarli, hash completo); dalla Fase 5 anche per il backup. **Non conosce il NAS** | common, model, metadata, index; imaging in `implementation` |

Nessun ciclo: `core-common` e `core-model` sono le foglie. I `feature-*` non si conoscono fra loro: ogni
passaggio da una funzione all'altra e' una callback scritta da `:app` (`navigation/AppNavHost.kt`).

**Fase 4: chi vede chi** (`docs/fase4-progetto.md` §1.1). Nella 4A (P0): `core-index`, `core-nasmedia` e `core-prepare` come
nella tabella; `core-prepare` non vede `core-nas` nemmeno di passaggio (riceve byte); `core-imaging` e `core-library` vedono
l'indice solo in `implementation`. Nella 4B (P0-B) arriveranno `core-nasops` (l'**unico** modulo con operazioni distruttive sui
file del NAS, l'unico che vede `NasFileOps`), `core-personal` (dati personali) e `feature-nas` (Cartelle NAS, Spazio NAS,
Eliminati di recente del NAS, Operazioni in corso); di `core-nasops` dipendono solo `feature-nas`, il menu del viewer e `:app`.
Lo verifica `Phase4ArchitectureTest` (`core-common`): grafo senza cicli, dipendenze dei moduli nuovi, `core-nasops` invisibile a
chi legge il NAS o mostra la Libreria, smbj solo in `core-nas`, `NasFileOps` solo in `core-nas` e `core-nasops`, regole dei
moduli nuovi. Ogni modulo nuovo della Fase 4 ha anche `NoNetworkSourcesTest` (niente rete, niente smbj) e
`NoWallClockSourcesTest` (niente orologio del telefono nelle decisioni: ora del NAS o orologio monotono).

Dalla Fase 2 ogni modulo nuovo ha tre package fissi (docs/fase2-progetto.md, D6):
- `contract`: le interfacce e i tipi che gli altri moduli usano (con KDoc che spiega il perche');
- `noop`: i null-object che falliscono chiusi (nessuna rete, nessuna autenticazione, dispositivo mai configurato).
  `:app` li ha legati fino al P9; ora lega le implementazioni vere e R8 toglie i null-object non usati dalla release;
- `testing`: i fake, in `src/main` perche' i moduli a valle li riusano nei loro test senza cicli.
  **Mai nella release**: `tools\check.ps1` fallisce se una classe di un package `testing` compare nel dex.

I legami Hilt sono in `app/src/main/.../di/`: `AppModule` (dispatcher, blocco app, anello della Diagnostica, funzioni
sperimentali) e `GraphModule`, che espone contratto per contratto **un solo** `AppGraph` per processo
(`app/src/main/.../graph/`): archivi locali, rete e casa (P2), sessione SMB (P1), fiducia e ciclo di vita (P3), onboarding
e ingresso (P4), regole e salute (P8). Debug e release usano lo stesso grafo; cambia solo `AppPlatform` (Keystore, rete
di Android, smbj, WireGuard), che i test d'integrazione sostituiscono con il simulatore SMB di `core-nas`, una rete finta e
chiavi in software. Intorno al grafo, in `:app`:
- `process/ProcessWatcher`: primo piano e profilo attivo (`AppContextSignal`), `VpnAutomation` agganciata al processo,
  sessione NAS chiusa in background, controllo del registro all'apertura e all'arrivo a casa, controllo quotidiano;
- `work/`: `TrustSyncWorker` (WorkManager: una volta al giorno, ogni 15 minuti nella finestra d'invito, all'apertura e
  all'arrivo a casa; rispetta il motore delle regole) e la pianificazione;
- `notify/`: canali "Approvazioni" e "Problemi del NAS"; le notifiche aprono solo l'app (`PendingIntent` immutabile);
- `graph/AppLocalDataEraser`: la fase dei dati locali dell'azzeramento (mai il diario dell'azzeramento);
- `root/RootViewModel` e `status/GlobalStatus`: la prima schermata dallo stato vero del dispositivo e l'indicatore globale
  (§3.5) da rete, sessione, sonda TCP e interruttore.

**Il NAS nella Libreria (Fase 4A, P11).** `GalleryGraph` costruisce un `nas/NasGraph` quando la funzione e' accesa
(`FeatureFlags.nasLibrary`, accesa anche in release; D31 `indexPublishTestSharesOnly` resta acceso) e il dispositivo e'
configurato (`AppGraph.nasInputs`: account, share privata e comune, esclusioni firmate, chiave del dispositivo, registro,
fiducia, regole, ora del NAS). Il grafo, per ogni area:

```
sessione SMB (P1, mediaReader)
  -> scansione DefaultNasScanner (P5) -> catalogo MediaCatalog v3 (P2, MediaCatalogScanStore)
  -> unita' di lavoro con prenotazioni (P3, CatalogWorkSource) -> preparazione DefaultPreparer (P6, NasPrepInputs)
  -> outbox e pubblicazione dell'indice OutboxIndexPublisher (P3, RoomIndexPublishStore, solo share di prova: D31)
  -> sincronizzazione IndexSyncEngine e replica LocalReplica (P4, RoomReplicaStore, pacchi in noBackupFilesDir/replica)
  -> Libreria (P7: CatalogTimelineSource con nasRefs, NasThumbnailSource, LibraryTabState.nas)
  -> viewer e "Scarica" (P8: NasImageOrigin, video a intervalli, NasDownloader con CatalogCopyLinker)
```

I lavori (`work/NasWork.kt`: scansione, sincronizzazione dell'indice, preparazione, anteprime HQ) girano a tappe
(`StagedRun`, D22) dentro le regole delle attivita' (P9); il congelamento (D10) ferma le scritture automatiche
(`MutableNasFreezeSignal` -> regole) e finisce nel **riepilogo giornaliero** (`notify/NasDailySummary`, risposta Q6).
L'emergenza "Ferma le scritture sul NAS" e "Ho controllato, continua" sono azioni della Diagnostica; la qualita' delle
anteprime e "Mostra le copertine degli album" stanno in Impostazioni (`feature-settings/.../quality`). L'azzeramento ferma il
NAS per primo e cancella outbox, pacchi, tentativi e interruttori. La recinzione delle share di prova (D33, `ShareFence`) e'
legata **solo in debug** (`DeveloperTools.shareGuard()`); `tools\check.ps1` verifica che non sia nel dex di release. In debug,
Impostazioni → Sviluppo → "NAS sintetico" apre una Libreria di prova con 20 mila file su un NAS finto in memoria, passati
dalla scansione vera, in un database separato (M1 sull'emulatore).

Il Fluid Engine e' in `engine/` (submodule, agganciato al tag **engine-2.13.0**); entrano nel build solo
`engine-foundation` e `engine-ui`. Per aggiungerne altri: `engine\tools\engine-install.ps1 -AppRoot . -Modules
engine-ui,engine-storage` (e rigenerare i checksum).

## SDK e toolchain

- `compileSdk` 36 (dall'engine), `targetSdk` 36 (decisione di prodotto, `versions-app.gradle`), `minSdk` 29
  (`engine.minSdk` in `gradle.properties`), Java/Kotlin 17.
- AGP, Kotlin, Compose BOM, Navigation, Lifecycle, DataStore: quelli dell'engine
  (`engine/versions.gradle`); `build.gradle` fa fallire il build con un messaggio chiaro se i plugin
  dichiarati non combaciano con l'engine.
- Le dipendenze **proprie** dell'app (Hilt, Room, WorkManager, coroutines, serialization, test) sono
  tutte in `versions-app.gradle`, con versioni stabili e fissate, per tutta la Fase 1: nei moduli si
  scrive `implementation appDeps.xxx`. Niente analytics, crash reporting, Qualcomm/NPU.
- `gradle/android-conventions.gradle` e' il blocco `android { ... }` comune a tutti i moduli
  (SDK, Java 17, test, lint). Un modulo nuovo copia l'intestazione di uno esistente.

## Sicurezza della build

- **R8 attivo in release** (taglio, ottimizzazione, risorse); l'offuscamento dei nomi e' spento
  (come FluidWeather) per avere tracce leggibili nella diagnostica in app: `app/proguard-rules.pro`.
  Le chiamate a `android.util.Log` v/d/i/w delle librerie spariscono dal bytecode di release.
- **`debuggable false`** in release, verificato due volte: `verifyReleaseManifest` (Gradle, sul manifest
  fuso; `assembleRelease` non produce l'APK se fallisce) e `tools\check.ps1` (sull'APK vero).
  `verifyReleaseManifest` fallisce anche per backup attivo, `dataExtractionRules` mancanti, traffico in
  chiaro, **qualunque componente esportato non ammesso** (oltre alla launcher, solo il JobService di
  WorkManager, protetto da `BIND_JOB_SERVICE`) e **qualunque permesso non elencato** in
  `releaseManifestPolicy` (`gradle/verify-release-manifest.gradle`). Aggiungere una riga li' e' una
  decisione di sicurezza: prima si scrive in `docs/sicurezza.md` (§4/§4-bis).
- **Backup e trasferimento esclusi**: `allowBackup="false"` piu' `data_extraction_rules.xml` con tutti i
  domini esclusi in `<cloud-backup>` e in `<device-transfer>`.
- **Checksum delle dipendenze**: `gradle/verification-metadata.xml` (sha256). Una dipendenza nuova o
  aggiornata fa fallire il build finche' non si rigenerano; dopo averla dichiarata:

  ```powershell
  .\gradlew.bat --write-verification-metadata sha256 --rerun-tasks --no-build-cache --no-parallel `
    assembleDebug assembleRelease assembleDebugAndroidTest galleryTest galleryLint `
    :app:lintRelease :core-designsystem:recordRoborazziDebug :app:recordRoborazziDebug
  ```

  e si **legge il diff** del file: devono comparire solo gli artefatti della dipendenza aggiunta. I
  checksum sono quelli degli artefatti risolti su Windows (alcuni, come aapt2, sono per sistema
  operativo). Limite noto: Robolectric scarica da solo i suoi `android-all` da Maven Central al primo
  test, fuori dalla verifica di Gradle.
- **Firma di release**: le quattro chiavi `pampa.storeFile`, `pampa.keyAlias`, `pampa.storePassword`,
  `pampa.keyPassword` si scrivono in `local.properties` (fuori da git, mai da copiare da altri progetti).
  Se mancano la release si compila lo stesso, **non firmata** (`app-release-unsigned.apk`), senza errori.
- **Log**: `GalleryLog` non scrive nulla in release perche' l'unico sink su logcat esiste solo in
  `app/src/debug`. Per dati sensibili da mostrare comunque in un messaggio: `Redacted`.

## Comandi utili

```powershell
.\gradlew.bat :app:installDebug                       # installa la debug (id dev.pampa.mycloudgallery.debug)
.\gradlew.bat galleryTest                             # unit test di tutti i moduli della galleria
.\gradlew.bat :core-designsystem:recordRoborazziDebug # PNG del catalogo in docs\screenshots\1.2
.\gradlew.bat :app:recordRoborazziDebug --tests "*Scaffold*"  # PNG dello scaffold della Fase 2 in docs\screenshots\2-P0
.\gradlew.bat :app:recordRoborazziDebug --tests "*P9Flows*"   # PNG dei flussi integrati in docs\screenshots\2-P9 (locale)
.\gradlew.bat :app:testDebugUnitTest --tests "*integration*"  # flussi dell'app sul grafo vero e sul NAS simulato
.\gradlew.bat :core-nas:testDebugUnitTest -PnasIntegration    # NAS vero dal PC (NEGOTIATE; il resto solo con le credenziali)
.\gradlew.bat :app:assembleRelease                    # release (non firmata se mancano le chiavi)
```

Nella build debug, Impostazioni → **Sviluppo** ha l'ingresso al **Catalogo componenti** (`:core-designsystem`,
`ComponentCatalogPlaceholder`); nella release non esiste (`app/src/debug/.../debugtools/DeveloperTools.kt`). Dal P9 la
debug non ha piu' uno stato finto del dispositivo: e' l'app vera, quella della verifica sul campo.

Solo nella debug (P11):
- **Libreria sintetica**: Libreria → Vista → "Sintetica: 2.000 / 100.000 elementi" (torna con "Libreria del telefono"). Usa un
  database a parte (`gallery-synthetic.db`), mai quello vero; la prima volta lo riempie in qualche decina di secondi. Da `adb`
  si sceglie scrivendo il numero in `/sdcard/Android/data/dev.pampa.mycloudgallery.debug/files/m1-synthetic-count`.
- **Famiglia "pronta" sull'emulatore** (senza NAS): `adb shell touch
  /sdcard/Android/data/dev.pampa.mycloudgallery.debug/files/debug-family-ready.marker`, poi riavviare l'app. Lo si toglie
  cancellando il file. In release non esiste.

Test con Robolectric: girano sull'SDK 35 (`gradle/test-resources/robolectric.properties`) perche'
l'SDK 36 richiede Java 21.

## Dove sta il resto

- Piano completo: `C:\Users\casua\.claude\plans\cozy-swinging-origami.md` (fuori dal repo).
- Decisioni della Fase 0: `docs/decisioni.md`; comportamenti Android/One UI: `docs/android.md`;
  threat model e checklist per fase: `docs/sicurezza.md`; inventario del NAS: `docs/inventario-nas.md`.
- Prove di Fase 0 (codice usa e getta): `spike/`.
- Regole del design system Fluid: skill `fluid-engine` (`references/regole.md`): nessun colore, testo o
  raggio scritto a mano; liste raggruppate; una pagina in arrivo non sfuma mai.
