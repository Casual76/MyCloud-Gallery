# MyCloud Gallery

Galleria Android privata per una famiglia di soli adulti, complementare a Samsung Gallery: foto del
telefono e del NAS WD My Cloud EX2 Ultra in una sola libreria, backup automatico, ricerca sul
dispositivo, nessun cloud di terzi e nessuna telemetria. Telefono e tablet.

Questo repository e' lo **scheletro della Fase 1.1**: build, moduli vuoti, tema Ametista e controlli di
sicurezza. Nessuna funzionalita' di prodotto.

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
   esportato oltre la launcher, nessun codice solo-debug dentro.

`-SkipGradle` salta i passi 5-7 (controllo statico, pochi secondi). `-NoDaemon` non lascia Gradle residente.

## Moduli

Package base `dev.pampa.mycloudgallery`, namespace `dev.pampa.mycloudgallery.<modulo>` (es.
`dev.pampa.mycloudgallery.core.trust`). Tutti i moduli sono librerie Android (Groovy DSL) con una classe
segnaposto e almeno un test.

| Modulo | Cosa conterra' | Vede |
|---|---|---|
| `:app` | `MainActivity`, Hilt, navigazione, lavori in background; applicationId `dev.pampa.mycloudgallery` (debug: `.debug`) | tutti + `engine-ui` |
| `:core-common` | logger interno (in release non scrive nulla), `Redacted`, `DispatcherProvider` | nessuno |
| `:core-model` | tipi di dominio (media, posizioni, versioni, dispositivi) | nessuno |
| `:core-database` | Room + KSP: replica locale dell'indice | model, common |
| `:core-designsystem` | tema Ametista, componenti della galleria, catalogo componenti | `engine-ui` |
| `:core-trust` | formato e firme dei dati sul NAS | model, common |
| `:core-security` | credenziali (Keystore), blocco app, protezioni dello schermo | common |
| `:core-rules` | motore delle regole delle attivita' | model, common |

Nessun ciclo: `core-common` e `core-model` sono le foglie. Il Fluid Engine e' in `engine/` (submodule,
agganciato al tag **engine-2.10.1**); entrano nel build solo `engine-foundation` e `engine-ui`. Per
aggiungerne altri: `engine\tools\engine-install.ps1 -AppRoot . -Modules engine-ui,engine-storage` (e
rigenerare i checksum).

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
  chiaro, **qualunque componente esportato oltre la launcher** e **qualunque permesso non elencato** in
  `releaseManifestPolicy` (`gradle/verify-release-manifest.gradle`). Aggiungere una riga li' e' una
  decisione di sicurezza: prima si scrive in `docs/sicurezza.md` (§4/§4-bis).
- **Backup e trasferimento esclusi**: `allowBackup="false"` piu' `data_extraction_rules.xml` con tutti i
  domini esclusi in `<cloud-backup>` e in `<device-transfer>`.
- **Checksum delle dipendenze**: `gradle/verification-metadata.xml` (sha256). Una dipendenza nuova o
  aggiornata fa fallire il build finche' non si rigenerano; dopo averla dichiarata:

  ```powershell
  .\gradlew.bat --write-verification-metadata sha256 --rerun-tasks --no-build-cache --no-parallel `
    assembleDebug assembleRelease assembleDebugAndroidTest galleryTest galleryLint `
    :app:lintRelease :core-designsystem:recordRoborazziDebug
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
.\gradlew.bat :core-designsystem:recordRoborazziDebug # PNG del catalogo in core-designsystem\build\outputs\roborazzi
.\gradlew.bat :app:assembleRelease                    # release (non firmata se mancano le chiavi)
```

Nella build debug la schermata iniziale ha un ingresso **Catalogo componenti**
(`:core-designsystem`, `ComponentCatalogPlaceholder`): nella release non esiste.

Test con Robolectric: girano sull'SDK 35 (`gradle/test-resources/robolectric.properties`) perche'
l'SDK 36 richiede Java 21.

## Dove sta il resto

- Piano completo: `C:\Users\casua\.claude\plans\cozy-swinging-origami.md` (fuori dal repo).
- Decisioni della Fase 0: `docs/decisioni.md`; comportamenti Android/One UI: `docs/android.md`;
  threat model e checklist per fase: `docs/sicurezza.md`; inventario del NAS: `docs/inventario-nas.md`.
- Prove di Fase 0 (codice usa e getta): `spike/`.
- Regole del design system Fluid: skill `fluid-engine` (`references/regole.md`): nessun colore, testo o
  raggio scritto a mano; liste raggruppate; una pagina in arrivo non sfuma mai.
