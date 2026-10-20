# MyCloud Gallery

Galleria Android privata per una famiglia di soli adulti, complementare a Samsung Gallery: foto del
telefono e del NAS WD My Cloud EX2 Ultra in una sola libreria, backup automatico, ricerca sul
dispositivo, nessun cloud di terzi e nessuna telemetria. Telefono e tablet.

Stato: **Fase 4A integrata (P11, M1); Fase 4B integrata (P16)**, in attesa delle prove sul campo e della revisione di sicurezza. Oltre alla Fase 2 (rete e casa, sessione SMB verso il NAS, famiglia, QR, Impostazioni e
Diagnostica) l'app mostra la **galleria del telefono**: Libreria (griglia della timeline su anni, mesi, giorni e tutte, con
selezione, preferiti, nascosti ed eliminazione dal telefono), viewer (zoom, video, foto in movimento, informazioni) e Raccolte
(Preferiti, Video, Screenshot, WhatsApp, Nascosti ed Eliminati di recente dietro lo sblocco). Tutto sul telefono, senza rete:
il NAS nella Libreria e' la Fase 4 (`docs/fase4-progetto.md`). Con il **P11 della 4A** (milestone M1) i pacchetti P0–P10
sono collegati nel grafo vero dell'app (`nas/NasGraph`): la scansione del NAS porta i media nella Libreria (data, presenza
"solo NAS", miniature EXIF provvisorie), la preparazione produce le anteprime e le pubblica nell'indice della share, gli altri
dispositivi sincronizzano l'indice e vedono le miniature senza preparare niente, il viewer apre gli originali dal NAS e
"Scarica" li copia sul telefono. Nessuna operazione sui media del NAS (D1): le scritture sono solo nell'area dell'app
(`.mycloudgallery/`), e nella 4A solo nelle share di prova (D31). Con la **4B** (P0-B, P12–P16) il NAS si **organizza**: da
Raccolte → "Sul NAS" si sfogliano le Cartelle NAS, si crea, rinomina, sposta (anche fra area privata e comune) ed elimina nel
cestino del NAS per 30 giorni, si ripristina da "Eliminati di recente del NAS", si guardano Spazio NAS e Operazioni in corso con
"Ferma"; dal viewer "Sposta", "Rinomina" ed "Elimina…" per un media del NAS. Le operazioni lunghe continuano anche uscendo
dall'app (lavoro avviato dall'utente, UIDT, con notifica di avanzamento e "Ferma"); preferiti, nascosti e impostazioni personali
viaggiano fra i dispositivi dello stesso account, nella sua area privata.

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
   `BIND_JOB_SERVICE`; ogni `*JobService`, anche quello delle operazioni lunghe sui file del NAS della 4B, deve avere
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
| `:core-metadata` | lettura dei metadati (EXIF, contenitori video, foto in movimento), ThumbHash; 5B: contratti per scrivere data, luogo e rotazione (`MetadataWriter`) e togliere la posizione (`LocationStripper`) | common, model, places |
| `:core-mediastore` | lettura del MediaStore e sincronizzazione della Libreria del telefono (scansione, META, impronte); 5B: richieste di sistema (cestino, cancellazione, scrittura) e riscrittura di un file del telefono con copia di sicurezza (`PhoneMediaRewriter`) | common, model, database, metadata, places |
| `:core-imaging` | miniature, segnaposto, decodifica per il viewer con i tetti di memoria | common, model; dalla Fase 4 index e nas (`implementation`) |
| `:core-library` | indice della timeline, istantanea su disco, layout della griglia, raccolte, date (`text/GalleryDates`), stato del NAS nel piede (`LibraryNasStatus`, Fase 4) | common, model, database, imaging; dalla Fase 4 index (`implementation`) |
| `:feature-library` | Libreria, Raccolte, Nascosti ed Eliminati di recente | common, model, designsystem, imaging, library, mediastore, places, security |
| `:feature-viewer` | viewer a schermo intero | common, model, database, designsystem, imaging, library, mediastore, metadata, places, security |
| `:benchmark` | scenari Macrobenchmark e Baseline Profile (`docs/bench/README.md`); mai nell'APK | `:app` (variante `benchmarkRelease`) |
| `:core-index` | Fase 4: indice condiviso nelle share (pubblicazione con outbox, unità di lavoro con prenotazioni, replica locale a pacchi, sincronizzazione delle teste, qualità delle anteprime) | common, model, trust; database, nas, rules in `implementation` |
| `:core-nasmedia` | Fase 4: scansione del NAS (elenco a pagine, rilevamento con impronta rapida, esclusioni, stabilità con l'ora del NAS, congelamento per cambiamenti di massa); NAS sintetico per i test | common, model, trust, database, nas, metadata, index |
| `:core-prepare` | Fase 4: preparazione dei media (miniature S e M, anteprime di analisi e HQ, fotogrammi dei video senza scaricarli, hash completo); dalla Fase 5 anche per il backup. **Non conosce il NAS** | common, model, metadata, index; imaging in `implementation` |
| `:core-nasops` | Fase 4B: operazioni sui file del NAS (crea cartella, rinomina, sposta, elimina nel cestino del NAS, ripristina, "elimina subito"), cestino, Spazio NAS, Cartelle NAS, pulizia dei propri file; diario con macchina a stati (Room); `UserConfirmation`. **L'unico che vede le primitive di scrittura** (`core-nas/ops`). P0-B contratti e fake, P12 esecutore, P16 collegato in `:app` | common, model, trust; nas in `implementation` |
| `:core-personal` | Fase 4B: dati personali sincronizzati fra i miei dispositivi (preferiti, nascosti, album, ricordi visti, impostazioni personali); fusione per ora del NAS. P0-B contratti e fake, P13 sincronizzazione, P16 collegato in `:app` | common, model, trust |
| `:feature-nas` | Fase 4B: Cartelle NAS, Spazio NAS, Eliminati di recente del NAS, Operazioni in corso, fogli di conferma (linguaggio grafico G2, chrome in alto condivisa con le griglie: `GalleryTopChrome` di core-designsystem). P14 schermate, P16 rotte e Raccolte | nasops, designsystem, library, imaging, common, model |
| `:core-backup` | Fase 5A (5B: versioni, correzioni, "Libera spazio", attivita' in sospeso, caricamenti in arrivo): backup dei media del telefono nell'area privata (piano e destinazioni, caricamento con diario e macchina a stati, rilettura completa prima del nome definitivo, deduplica byte per byte, guardiano delle copie private, "al sicuro", stato). Vede il NAS **solo** con `NasBackupOps`, una vista in sola aggiunta di `core-nas` (`@AppendOnlyNasAccess`): non puo' togliere ne' sostituire un file | model, common, trust, nas (lettura e `NasBackupOps`), database (porte), index (publisher), prepare, metadata, rules. **Non vede** `core-nasops`, `NasFileOps`, `DestructiveNasAccess` ne' `core-mediastore` |
| `:feature-backup` | Fase 5A e 5B: impostazioni e pagina Backup, primo backup, stato nel piede e nel viewer; nella 5B Versioni, "Libera spazio", "Da decidere". Stati e contratti, composable con i soli componenti G2. **Cieca a `core-nas` e `core-nasops`** | backup (contratti), library, imaging, designsystem |
| `:feature-integrations` | Fase 5B: selettore per le altre app (`PickerActivity`), provider di documenti (`MediaDocumentsProvider`), ricezione delle condivisioni ("Salva sul NAS"), sessioni di "Modifica con…", condivisione senza posizione. **Cieca a `core-nas` e `core-nasops`** | backup, library, imaging, mediastore (contratti), designsystem |

Nessun ciclo: `core-common` e `core-model` sono le foglie. I `feature-*` non si conoscono fra loro: ogni
passaggio da una funzione all'altra e' una callback scritta da `:app` (`navigation/AppNavHost.kt`).

**Fase 4: chi vede chi** (`docs/fase4-progetto.md` §1.1). Nella 4A (P0): `core-index`, `core-nasmedia` e `core-prepare` come
nella tabella; `core-prepare` non vede `core-nas` nemmeno di passaggio (riceve byte); `core-imaging` e `core-library` vedono
l'indice solo in `implementation`. Nella 4B (P0-B) ci sono `core-nasops` (l'**unico** modulo con operazioni distruttive sui
file del NAS, l'unico che vede `NasFileOps`), `core-personal` (dati personali) e `feature-nas` (Cartelle NAS, Spazio NAS,
Eliminati di recente del NAS, Operazioni in corso); di `core-nasops` dipendono solo `feature-nas`, il menu del viewer e `:app`.
`feature-nas` non vede `core-nas`. Le primitive di scrittura richiedono un'adesione del compilatore (`@RequiresOptIn`, ERRORE) che
passano solo `core-nas` e `core-nasops`; un'operazione distruttiva parte **solo** da una `UserConfirmation`, il cui costruttore e'
`internal` e che costruisce solo `ConfirmationIssuer`, usato solo dalla UI dopo un tocco (`feature-nas`, menu del viewer, `:app`), o dal
diario locale (riprese dopo un'interruzione). Lo verifica `Phase4ArchitectureTest` (`core-common`): grafo senza cicli, dipendenze dei
moduli nuovi, `core-nasops` invisibile a chi legge il NAS o mostra la Libreria, smbj solo in `core-nas`, primitive di scrittura e
marcatore solo in `core-nas` e `core-nasops`, chi puo' costruire una conferma, regole dei moduli nuovi, tutti i moduli in `galleryModules`. Ogni modulo nuovo della Fase 4 ha anche `NoNetworkSourcesTest` (niente rete, niente smbj) e
`NoWallClockSourcesTest` (niente orologio del telefono nelle decisioni: ora del NAS o orologio monotono).

**Fase 5: chi vede chi** (`docs/fase5-progetto.md` §1.1). Nella 5A nascono `core-backup` e `feature-backup`, nella 5B
`feature-integrations`. `core-backup` e' il secondo modulo che scrive i media sul NAS, ma **in sola aggiunta**: vede il NAS solo
con `NasBackupOps` (in `core-nas/ops/backup/`, con un marcatore suo, `@AppendOnlyNasAccess`, `@RequiresOptIn` di livello errore,
che passano solo `core-nas` e `core-backup`): crea e pubblica senza sostituire, non toglie ne' sostituisce niente. Non nomina
`NasFileOps`, `DestructiveNasAccess`, `ConfirmationIssuer` ne' tipi di `core-nasops`: cio' che toglie un file dal NAS resta di
`core-nasops`, dietro una `UserConfirmation`. `feature-backup` e `feature-integrations` non vedono `core-nas` ne' `core-nasops`.
Chi legge il NAS o mostra la Libreria (`core-index`, `core-nasmedia`, `core-prepare`, `core-personal`, `core-library`,
`core-imaging`) **non vede** `core-backup`. `core-nasops` riceve solo la porta `CitedTemps` (la pulizia dei propri file rispetta i
temporanei del backup). `Phase4ArchitectureTest` e' esteso a queste regole.

**Fase 5B (P0-B).** `feature-integrations` porta in release i quattro componenti approvati come scheletri inerti (selettore `PickerActivity`, ricezione
`ShareReceiverActivity`, `MediaDocumentsProvider` protetto da `MANAGE_DOCUMENTS`, `FileProvider` non esportato); `NasBackupOps` ha solo due rinomine in piu'
(`archiveVersion` e `restoreVersion`, con il timbro obbligatorio, una estremita' sotto `Versioni/`); una `PhoneConfirmation` nasce solo da un tocco su un
foglio della UI. `Phase5BArchitectureTest` tiene il perimetro. Le prove guidate T-09, T-14, T-15 e T-16 sono una schermata della sola build debug
(Impostazioni → Sviluppo), assente dalla release; il loro resoconto, di soli numeri, va in `docs/android.md`.

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

**Organizzare il NAS (Fase 4B, P16).** `NasGraph` porta con se' un `nas/NasOrganizer`, che parte con il motore del NAS e lega:

```
NasOpsWiring (core-nasops)  -> operazioni (NasOpsEngine, diario RoomOpsStore), cestino, Spazio NAS, Cartelle NAS,
                               manutenzione dell'indice (Compactor acceso + pulizia dei propri file)
cancello OpsGate            -> emergenza, congelamento (D10), salto dell'ora (D32)
NasPersonalSync (P13)       -> registro locale in noBackupFilesDir/personal, proiezione sul catalogo, solo area privata (M7)
```

- **Schermate**: le quattro pagine di `feature-nas` sono rotte (`nasGraph`) e, in Raccolte, il gruppo "Sul NAS" (voci come dati,
  `CollectionEntry`): sul telefono una voce apre la rotta, sul tablet la pagina sta nel pannello di dettaglio. Un file toccato in
  Cartelle NAS si apre nel viewer della Libreria (solo se la Libreria lo mostra).
- **Viewer**: `ViewerServices.nasActions` (`NasOrganizer.mediaActions`) accende "Sposta", "Rinomina", "Elimina…" (P14).
- **Avvio e ripresa**: `start` esegue nello scope della galleria (la schermata puo' chiudersi) e risponde appena l'operazione e' nel
  diario; un'operazione lunga (fra share, o da 10 elementi in su) chiede il lavoro avviato dall'utente (`work/NasOpsJobService`,
  UIDT su Android 14+, notifica con "Ferma"); all'avvio, dopo una morte del processo o per un file aperto dal PC il lavoro riprende
  **dal diario** (mai una conferma nuova).
- **Lavori** (`work/NasWork.kt`, `INDEX_SYNC`): pulizia del cestino scaduto (solo dal diario locale), manutenzione dell'indice,
  sincronizzazione dei dati personali (anche 30 s dopo un preferito o un nascosto, con l'app aperta).
- **Diagnostica**: `nas/NasOpsDiagnostics` (operazioni, file aperti dal PC, elementi fermi, cestino, pulizia, manutenzione, dati
  personali; solo numeri).
- **Azzeramento**: la galleria ferma il NAS e le operazioni (e toglie i lavori in coda); il diario sta nel database, il registro dei
  dati personali in `noBackupFilesDir/personal/`: si cancellano con il resto.

**Il backup (Fase 5A, P7).** `GalleryGraph` costruisce un `backup/BackupGraph` dopo il NAS, con la stessa vita (l'azzeramento lo ferma
**per primo**, poi `AppLocalDataEraser`). `:app` non nomina mai le primitive del backup (`BackupEngineFactory`, `BackupSafetyFactory`;
`Phase5ArchitectureTest`) e non vede il NAS se non attraverso `NasBackupOps`:

```
catalogo (CatalogBackupCatalog) + telefono (MediaStorePhoneBytes) + NAS (NasBackupOps)
  -> motore del caricamento (P2, diario RoomBackupStore) -> copia verificata in Backup/<dispositivo>/AAAA/MM/ dell'area privata
  -> verificatore e guardiano (P3): "al sicuro" solo da una rilettura fatta qui
cancello AppBackupGate      -> famiglia pronta, area privata, share di prova (D12), emergenza, congelamento, area in sola lettura
```

- **D12**: sulle share che non sono di prova il cancello dice `TestOnly` e **non si scrive niente**, anche con la release (la pagina e il
  piede dicono "Backup in prova"); `tools\check.ps1` lo verifica nel bytecode della release (`dexdump`): `AppBackupGate.stateOf` chiama
  `FeatureFlags.backupMayWriteTo` e risponde `TEST_ONLY`. **D13**: solo in debug, con gli account di prova, il backup legge `DCIM/mcg-prova/`
  e `Pictures/mcg-prova/` e scrive sotto `mcg-campo/Backup/` (`DeveloperTools.backupFence()`, assente dal dex di release); **per questo, in
  release, un account di prova non scrive in nessuna share** (R2 della revisione 5A: `FeatureFlags.backupMayWriteAs`, `TEST_ONLY` per ogni
  share, provato anch'esso nel bytecode da `tools\check.ps1`).
- **Lavori** (`work/BackupWork.kt`): foto, video (solo Wi-Fi di casa), "Proteggi subito le foto nuove" (D34) e verifiche, a tappe dentro le
  regole delle attivita'; si chiedono a fine lettura del telefono, mai a intervalli brevi. Il primo backup e "Fai ora" sono lavori avviati
  dall'utente (`BackupJobService`, UIDT, notifica con soli numeri e "Ferma").
- **Schermate**: la pagina Backup in Impostazioni, la terza voce del piede della Libreria, la riga del viewer; le copie simili (D36) si
  vedono in una cella sola, con "Mostra come principale" e "Separa" nel pannello del viewer.
- **Diagnostica e riepilogo giornaliero**: codici del backup (`BACKUP_*`), soli numeri; il NAS continua ad avere una sola notifica al giorno.

**La 5B nell'app (P14).** Dentro `BackupGraph` nasce `backup/FiveBGraph` (versioni e correzioni, "Libera spazio", file in arrivo), con gli
stessi cancelli; le integrazioni hanno il loro `integrations/IntegrationsGraph`, installato da `MyCloudGalleryApp.onCreate` prima di
qualunque richiesta di un'altra app:

```
lettura del telefono -> versioni del telefono pianificate PRIMA del giro della 5A, nella stessa tappa (VersionedBackupEngine)
                     -> "Libera spazio": esiti in dubbio risolti (settle); un ritorno a una versione nota -> scambio sul NAS
"Salva sul NAS"      -> incoming/ -> elemento INCOMING del diario -> caricamento sicuro, rilettura, collegamento -> copia locale tolta
Versioni / Correggi / "Elimina solo dal telefono" -> fogli alla radice della galleria (main/FiveBHostEffect), conferme solo dal tocco
selettore, provider, ricezione -> EntryGate: famiglia pronta e blocco dell'app valutato ADESSO (isUnlockedNow)
```

- **Porte**: `di/FiveBModule.kt` lega `FreeSpace`, `VersionKeeper`, `Corrections`, `PendingTasks`, `BackupClock`, i dialoghi di sistema
  (con la recinzione D13 riga per riga), "Elimina solo dal telefono", "Da decidere", "Elimina le intermedie…" e le voci del backup nel viewer
  (`backup/FiveBUi.kt`); le facciate vanno ai servizi veri appena il backup parte (`FiveBButtonsTest`: nessuna e' un null-object).
- **Navigazione**: "Libera spazio" in Raccolte → Utilita' e nella selezione della Libreria; "Da decidere" nella pagina Backup.
- **D12/R2/D13** valgono anche qui: le versioni con un account di prova stanno sotto `mcg-campo/Versioni/`, i file in arrivo passano dal
  cancello della share di **destinazione**; dalla 5B non si scrive mai nell'area comune (correzioni e versioni si fermano con un avviso).
- **Lavori**: i file in arrivo con `IncomingUploadWorker` (o, per una condivisione grande, `BackupJobService` con l'id `1_000_104`).
- **Prove**: `FiveBIntegrationTest` (sei percorsi interi sul grafo vero, finti solo NAS e telefono), `FiveBButtonsTest`, `AppEntryGateTest`.

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
