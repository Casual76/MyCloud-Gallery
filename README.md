# MyCloud Gallery

Galleria Android privata per una famiglia di soli adulti, complementare a Samsung Gallery: foto del
telefono e del NAS WD My Cloud EX2 Ultra in una sola libreria, backup automatico, ricerca sul
dispositivo, nessun cloud di terzi e nessuna telemetria. Telefono e tablet.

Stato: **Fase 2 integrata (P9)**. L'app collega le implementazioni vere di rete e casa, sessione SMB verso il NAS,
famiglia (onboarding, ingresso con invito, registro, approvazione, smarrimento e azzeramento, recupero dell'admin), QR,
Impostazioni e Diagnostica. La galleria vera e propria (Libreria, backup, indice, ricerca) arriva dalla Fase 3: le schede
sono ancora segnaposto.

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
| `:app` | `MainActivity`, blocco app, navigazione, il grafo della Fase 2 (`graph/AppGraph`) esposto a Hilt, `TrustSyncWorker` e notifiche, ciclo di vita del processo; applicationId `dev.pampa.mycloudgallery` (debug: `.debug`) | tutti + `engine-ui` |
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

Nessun ciclo: `core-common` e `core-model` sono le foglie. I `feature-*` non si conoscono fra loro: ogni
passaggio da una funzione all'altra e' una callback scritta da `:app` (`navigation/AppNavHost.kt`).

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

Il Fluid Engine e' in `engine/` (submodule, agganciato al tag **engine-2.11.0**); entrano nel build solo
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

Test con Robolectric: girano sull'SDK 35 (`gradle/test-resources/robolectric.properties`) perche'
l'SDK 36 richiede Java 21.

## Dove sta il resto

- Piano completo: `C:\Users\casua\.claude\plans\cozy-swinging-origami.md` (fuori dal repo).
- Decisioni della Fase 0: `docs/decisioni.md`; comportamenti Android/One UI: `docs/android.md`;
  threat model e checklist per fase: `docs/sicurezza.md`; inventario del NAS: `docs/inventario-nas.md`.
- Prove di Fase 0 (codice usa e getta): `spike/`.
- Regole del design system Fluid: skill `fluid-engine` (`references/regole.md`): nessun colore, testo o
  raggio scritto a mano; liste raggruppate; una pagina in arrivo non sfuma mai.
