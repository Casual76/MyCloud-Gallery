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
| `:app` | `MainActivity`, blocco app, navigazione, il grafo della Fase 2 (`graph/AppGraph`), quello della galleria (`gallery/GalleryGraph`) e, dalla 4A, quello del NAS nella Libreria (`nas/NasGraph`) esposti a Hilt, `TrustSyncWorker`, `PhoneSyncWorker` e `LibraryMaintenanceWorker` (`work/GalleryWork.kt`), i lavori del NAS (`work/NasWork.kt`), notifiche e riepilogo giornaliero, ciclo di vita del processo; dalla Fase 6 (P11) `ai/AiGraph` (modelli, motori, lettura del testo dietro il cancello, calcolo AI, adattatori Room dei dati `ai`), `search/SearchGraph` (indice locale e motore di ricerca, vive anche senza NAS), `nas/NasAi` (teste `ai` nel giro dell'indice), `work/AiWork.kt` (`ModelFetchWorker`, `AiComputeWorker`), la scheda Cerca e le pagine della ricerca (`navigation/SearchNavigation.kt`); la release porta solo `arm64-v8a`; applicationId `dev.pampa.mycloudgallery` (debug: `.debug`; variante di misura: `.bench`) | tutti + `engine-ui` |
| `:core-common` | logger interno (in release non scrive nulla), `Redacted`, `DispatcherProvider` | nessuno |
| `:core-model` | tipi di dominio (media, posizioni, versioni, dispositivi) | nessuno |
| `:core-database` | Room + KSP: replica locale dell'indice | model, common |
| `:core-designsystem` | tema Ametista, componenti della galleria, catalogo componenti | `engine-ui` |
| `:core-trust` | formato e firme dei dati sul NAS | model, common |
| `:core-security` | credenziali (Keystore), blocco app, protezioni dello schermo | common, model (dalla Fase 8: `FaceGeneration` per la chiave dei volti `mcg-faces-<G>`) |
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
| `:core-models` | Fase 6: catalogo dei modelli fissato nell'app (id, dimensione, SHA-256), sorgenti (NAS, HTTPS, cartella dal PC), scaricamento con ripresa, verifica a ogni caricamento, spazio e rimozione, copia sul NAS. **L'unico modulo dell'app che apre connessioni HTTPS** (pacchetto `download/https/`, solo host ammessi) | common, model, trust (`SharePath`, `NasLayout`, `NasFileSystem`), nas (lettura; pubblicazione esclusiva nella propria cartella), rules. **Non vede** index, ai, search, database |
| `:core-ai` | Fase 6: motori on-device (vettori di testo e immagini con LiteRT-LM; OCR con ML Kit solo con la lettura del testo attiva; ritaglio del soggetto con MagicTouch su LiteRT) e calcolo AI (unita', prenotazioni, buffer degli indicizzatori, ripresa, pubblicazione). **L'unico modulo che vede `litertlm`, `litert` e ML Kit** | common, model, trust, index (contratti), prepare (solo per i media del telefono), models (solo `ModelFiles`), rules. **Non vede** nas (i byte del NAS arrivano dalle porte di core-index), nasops, backup, search |
| `:core-search` | Fase 6: indice locale (vettori e testo), analizzatore delle query italiane, fusione e soglie di convinzione, "Trova simili", copertura, cronologia. **Nessuna rete, nessun NAS** | common, model, database (porte), library, places, ai (solo contratti). **Non vede** nas, index, models, prepare, nasops, backup |
| `:feature-search` | Fase 6: schermata Cerca e pagina Ricerca e modelli. Stati e contratti, composable con i soli componenti G2. **Cieca a `core-nas`, `core-index`, `core-nasops`, `core-backup`** | search, library, imaging, designsystem; ai e models solo nei contratti di stato |
| `:core-albums` | Fase 7: album personali (manuali e smart, nei dati personali), regole degli album smart, viaggi ed eventi con la "casa" solo locale, replica, fusione e permessi degli album condivisi, orchestratore della condivisione e del "Riporta" (diario proprio). **Non sposta, non carica, non cancella da se'**: porte sue (`AlbumMover`, `AlbumUploader`, `SafeCopyRequester`, `AlbumPublisher`) legate da `:app`, solo con una `ShareConfirmation` emessa dal foglio | common, model, library (viste e visibilita'), trust (`NasFileSystem` solo in `shared/`), database, personal, places, search (solo contratti). **Non vede** nasops, backup, nas, nasmedia, index, ai, models, rete |
| `:feature-albums` | Fase 7: elenco e pagina degli album, editor delle regole, "Suggerite" e "Da condividere", fogli di condivisione, permessi e chiusura (con la scelta di Q5), proposte di viaggio. Stati e contratti, composable con i soli componenti G2. **Cieca a `core-nas`, `core-nasops`, `core-backup`, `core-index`, `core-trust`** | albums (contratti), library, imaging, designsystem |
| `:core-faces` | Fase 8: Persone (da attivare per account). Attivazione, avviso ed esclusione, calcolo dei volti, raggruppamento incrementale con un custode, nomi e correzioni, sincronizzazione dei dati dei volti nell'area privata, "Disattiva e cancella", archivio cifrato. **Non cancella, non cerca e non tocca gli album da se'**: porte sue (`FacesEraseOps`, `PeopleIndex`, `SmartAlbumsPeople`) legate da `:app` | common, model, library (viste e visibilita'), trust (`NasFileSystem` solo in `sync/`), index (contratti), personal (contratti), ai e models (solo contratti), database (solo il database dei volti, da `store/`), security (solo la chiave dei volti). **Non vede** nasops, backup, nas, nasmedia, search, albums, rete |
| `:feature-people` | Fase 8: sezione Persone e "Altri volti", pagina persona, revisione dei volti, fogli di unione, attivazione e avviso, Impostazioni → Persone, "Disattiva e cancella". Stati e contratti, composable con i soli componenti G2. **Cieca a `core-nas`, `core-nasops`, `core-trust`, `core-ai`, `core-models`, `core-search`, `core-albums`, `core-backup`, `core-index`** | faces (contratti), library, imaging, designsystem |
| `:core-memories` | Fase 9: Ricordi. Pianificatore (giorno nel passato, viaggi, eventi, persone, mesi), scelta delle foto, scaffale locale, notifiche discrete, musica. **JVM puro**, non vede rete, NAS, volti, ricerca, album ne' coordinate: viaggi, persone, vettori e pixel arrivano da porte sue (`MemoryTrips`, `MemoryPeople`, `MemoryVectors`, `MemoryPixels`) legate da `:app` | common, model, library (contratti e viste), personal (porta `MemoriesPersonalSettings`) |
| `:feature-memories` | Fase 9: sezione Ricordi, pagina del ricordo, presentazione, fogli "Mostra meno", Impostazioni → Ricordi e notifiche. Stati e contratti, composable con i soli componenti G2 | memories (contratti), library, imaging, designsystem |
| `:core-map` | Fase 9: Mappa, la parte pura. Punti con coordinate effettive, indice a celle (z-order), cancello delle richieste di rete (`MapRequestGate`), regioni offline, impostazioni della mappa. **JVM puro**, senza MapLibre ne' OkHttp | common, model |
| `:feature-map` | Fase 9: la mappa (MapLibre), sezione Luoghi, regioni offline, mini-mappa del viewer. L'unico HTTP della mappa sta in `feature-map/.../net/` (OkHttp dietro il cancello) | map (contratti), library, imaging, places, designsystem |
| `:core-widget` | Fase 10: il widget, la parte pura. Che cosa mostra ogni widget (il ricordo di oggi, altrimenti foto a caso: Q1 (a)), il registro di cio' che ha mostrato, le ore di quiete (23-7) e il tocco con due numeri. **JVM puro**, nessuna rete ne' NAS, mai una persona nelle firme | common, model, library (contratti), memories (contratti) |
| `:core-release` | Fase 10: il manifest di rilascio firmato (P-256, due chiavi fissate), letto solo dal NAS (Q8 (a)); stato dei blocchi solo piu' severo, anti-rigioco, indirizzi aggiuntivi dei modelli. **JVM puro**, vede solo le fondamenta | common, model |
| `:core-describe` | Fase 10: le descrizioni dei media per superficie (dentro l'app, Nascosti, widget, notifiche), mai salvate ne' esportate. **JVM puro** | common, model, library (contratti), places (contratti) |
| `:feature-widget` | Fase 10: il widget "MyCloud Gallery" (Glance sopra `engine-widget`, P3), ricevitore non esportato, ridisegno; in P0 lo stato sigillato `WidgetUiState` | widget (contratti); da P3 imaging, designsystem, engine-widget |

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

**Fase 6: chi vede chi** (`docs/fase6-progetto.md` §1.1). Nascono `core-models`, `core-ai`, `core-search` e `feature-search` (P0 ne
scrive i contratti, i null-object e i fake). Le regole:
- `core-models` e' l'**unico** modulo con codice HTTPS (pacchetto `download/https/`, classi `internal`): ogni altro modulo e ogni altro
  pacchetto ha `NoNetworkSourcesTest`, e un test d'architettura prova che nessun altro nomina un client HTTP o TLS (`javax.net.ssl`,
  `java.net.URL`, le `URLConnection`, i socket SSL, le librerie di rete; i socket verso il NAS di `core-network` e `core-nas` restano).
  Il resto dell'app riceve solo `ModelManager` e `ModelFiles`;
- `litertlm`, `litert` e ML Kit compaiono **solo** nel `build.gradle` di `core-ai`, e `com.google.mlkit` (con `MlKit.initialize`) solo
  in `core-ai/.../ocr/`, dietro il cancello della lettura del testo (spenta di default);
- `core-search` e `feature-search` non vedono ne' rete ne' NAS: `core-search` non vede `core-models`, `core-index` ne' `core-nas`, e
  `feature-search` non vede `core-nas`, `core-index`, `core-nasops` ne' `core-backup`;
- `feature-viewer` non vede `core-ai` ne' `core-search`: il testo dal vivo e il ritaglio passano da porte sue, che `:app` lega;
- chi legge il NAS o mostra la Libreria (`core-index`, `core-nasmedia`, `core-prepare`, `core-library`, `core-imaging`) **non vede**
  `core-ai`, `core-models` ne' `core-search`; `core-prepare` riceve una porta `AnalysisSink` definita da lui;
- restano le regole delle Fasi 4 e 5: `core-ai` e `core-models` non vedono `NasFileOps`, `DestructiveNasAccess`, `NasBackupOps`,
  `core-nasops` ne' `core-backup`.

`Phase6ArchitectureTest` (`core-common`, P0) tiene queste regole, con il grafo senza cicli e tutti i moduli in `galleryModules`; ogni
violazione reintrodotta lo fa fallire. Le aggiunte ai moduli esistenti (classi `vec` e `ocr` in `core-trust`, testa `ai` in
`core-index`, schema v5 in `core-database`, ruoli modificabili in `core-family`, impostazioni sincronizzate in `core-personal`,
testo dal vivo, ritaglio e foto in movimento in `feature-viewer`) sono nel §1.3 del progetto.

**Fase 7: chi vede chi** (`docs/fase7-progetto.md` §1.1). Nascono `core-albums` e `feature-albums` (P0 ne scrive i contratti, i
null-object, i fake e gli esecutori di riferimento `InMemoryAlbums`, `InMemoryShareRunner`, `InMemoryTrips`, con le suite di contratto
che le implementazioni vere dei pacchetti dopo devono passare). Le regole:
- `core-albums` **non vede** `core-nasops`, `core-backup`, `core-nas`, `core-nasmedia`, `core-index`, `core-ai`, `core-models` ne' la
  rete, nemmeno per nome nel codice di produzione: sposta, carica, chiede il guardiano e pubblica solo da porte sue, che `:app` lega
  a `NasOperations` (con `ConfirmationIssuer` sullo stesso piano), a `IncomingUploads` e al guardiano;
- una `ShareConfirmation` (costruttore `internal`) la emette solo il foglio (`ShareConfirmationSheet`/`ShareConfirmationIssuer` in
  `feature-albums` e `:app`); `ShareRunner.start`, `AlbumMover.move` e `AlbumUploader.upload` la pretendono nella firma;
- `NasFileSystem` solo in `core-albums/.../shared/` (i file `albums/`, con la sola `publishExclusive`); `home_estimate` solo in
  `core-database` e `core-albums/.../trips/`; nessun `PersonalKind` nuovo (i campi degli album stanno dentro `ALBUM`, `SETTING`,
  `MEDIA_FLAG`);
- `feature-albums` vede di `core-albums` solo contratti, null-object e fake, e non vede `core-nas`, `core-nasops`, `core-backup`,
  `core-index` ne' `core-trust`; `core-search` non vede `core-albums`.

`Phase7ArchitectureTest` (`core-common`, P0) tiene queste regole; ogni violazione reintrodotta lo fa fallire. La recinzione D30
(`FeatureFlags.sharedAlbumsTestSharesOnly`, album condivisi solo sulle share di prova finche' l'utente non dice di si', Q7) e' accesa in
release e in debug e `tools/check.ps1` la cerca nel dex di release. Le aggiunte ai moduli esistenti (codici degli album in `core-model`,
campi D4 in `core-personal`, `LibraryFilter.EDITED`, `VisibleMedia`, `HiddenMask` e `WidgetFeed` in `core-library`, `MoveCompletionSink`
in `core-nasops`, `IncomingDestination.AlbumFolder` e `SafeCopyRequests` in `core-backup`, `DiagSection.ALBUMS`) sono nel §1.3 del
progetto e in `docs/decisioni.md` ("Fase 7 P0").

**Fase 8: chi vede chi** (`docs/fase8-progetto.md` §1.1). Nascono `core-faces` e `feature-people` (P0 ne scrive i contratti, i
null-object, i fake e gli esecutori di riferimento `ReferenceGrouping`, `InMemoryPeople`, `InMemoryFacesRemote`, `InMemoryFacesArchive`, con
le suite di contratto che le implementazioni vere dei pacchetti dopo devono passare; in `core-ai` i motori finti `FakeFaceEngines`, in
`core-security` `FakeFacesKeys`, in `core-nasops` `InMemoryFacesNasEraser`). Le regole:
- `core-faces` **non vede** `core-nasops`, `core-backup`, `core-nas`, `core-nasmedia`, `core-search`, `core-albums` ne' la rete, nemmeno
  per nome nel codice di produzione: cancella sul NAS, alimenta la ricerca e gli album solo da porte sue, che `:app` lega;
- `NasFileSystem` solo in `core-faces/.../sync/` (i file `faces/<G>/<io>/` dell'area privata, con la sola `publishExclusive`); il database
  dei volti solo da `store/`, la chiave dei volti solo da `store/` ed `erase/`; di `core-ai` e `core-models` solo i contratti; LiteRT solo in
  `core-ai` (fra le classi dei volti solo `faces/FaceLiteRtSessions.kt`), anche nel bytecode della release (`tools/check.ps1`);
- `FacesActivationConsent` la costruisce solo la pagina di attivazione e `FacesEraseConfirmation` solo il foglio "Disattiva e cancella",
  entrambi in `feature-people`; `PersonalKind.PERSON` non compare mai nel codice di produzione (i nomi stanno nella classe `people` sotto
  `faces/`);
- `feature-people` vede di `core-faces` solo contratti, null-object e fake; nessun altro modulo, tranne `:app`, arriva a `core-faces`
  (`core-search` e `core-albums` ne usano i soli contratti loro: `PeopleLookup`, `PeopleMembership`).

`Phase8ArchitectureTest` (`core-common`, P0) tiene queste regole; ogni violazione reintrodotta lo fa fallire. La recinzione D34
(`FeatureFlags.facesSyncTestSharesOnly`, dati dei volti solo sulle share di prova finche' l'utente non dice di si' a fine fase, Q5) e' accesa
in release e in debug e `tools/check.ps1` la cerca nel dex di release. Le aggiunte ai moduli esistenti (codici e tipi dei volti in
`core-model`, testa di tipo 9 e classi 18-20 in `core-trust`, `contract/Faces.kt` e `BufferKeepers` in `core-ai`, ruoli dei modelli dei
volti in `core-models`, voce `faces` e gancio `PrePublishHook` in `core-personal`, `FacesKeys` in `core-security`, `FacesNasEraser` in
`core-nasops`, `SearchChip.Person` e `PeopleLookup` in `core-search`, `PeopleRule` e `PeopleMembership` in `core-albums`, `ViewerPeople` in
`feature-viewer`, `DiagSection.PEOPLE`) sono nel §1.3 del progetto e in `docs/decisioni.md` ("Fase 8 P0").

**Fase 9: chi vede chi** (`docs/fase9-progetto.md` §1.1). Nascono `core-memories`, `feature-memories`, `core-map` e `feature-map` (P0 ne
scrive i contratti, i null-object, i fake e gli esecutori di riferimento `ReferencePlanner`, `ReferencePicker`, `InMemoryShelfStore`,
`InMemoryMemories`, `ReferenceNotifyPolicy`, `ReferenceTilePolicy`, `ReferenceClusterIndex`, `InMemoryMapRegions`, `InMemoryMapSettings`, con
le suite di contratto che le implementazioni vere dei pacchetti dopo devono passare). Le regole:
- `core-memories` e `core-map` sono **JVM puri** (niente Android, librerie, Compose, Hilt); `core-memories` non vede volti, ricerca, album,
  modelli, NAS, rete, indice, trust ne' la mappa e non maneggia coordinate: tutto arriva da porte sue che `:app` lega; scrive i dati
  personali solo dalla porta `MemoriesPersonalSettings` di `core-personal`;
- MapLibre solo in `feature-map`; OkHttp solo in `feature-map/.../net/`, con dichiarazioni `internal` (la regola 5 della Fase 6 ammette ora
  due pacchetti di rete), anche nel bytecode della release (`tools/check.ps1`);
- `MapOnlineChoice` la costruisce solo `feature-map/.../privacy/` e `RegionDownloadConfirmation` solo il foglio delle regioni; la porta dei
  viaggi per i ricordi (`TripMemories`) non porta coordinate ne' casa, e la casa non compare nei moduli della Fase 9;
- `feature-memories` e `feature-map` vedono dei loro core solo contratti, null-object e fake; `feature-viewer` mostra la mini-mappa da una
  porta sua (`ViewerPlaceMap`), senza vedere la mappa.

`Phase9ArchitectureTest` (`core-common`, P0) tiene queste regole; ogni violazione reintrodotta lo fa fallire. La mappa online e' accesa di
base per tutti (risposta Q1 (c) del 27/10, `FeatureFlags.mapOnline`), con l'interruttore per dispositivo in Impostazioni; il cancello di rete
resta intero. Le aggiunte ai moduli esistenti (campi dei ricordi in `core-personal`, `TripMemories` in `core-albums`, `ViewerPlaceMap` in
`feature-viewer`, sezione `places` delle Raccolte, `DiagSection.MEMORIES` e `MAP`) sono in `docs/decisioni.md` ("Fase 9 P0").

**Fase 10: chi vede chi** (`docs/fase10-progetto.md` §1.1). Nascono `core-widget`, `core-release`, `core-describe` e `feature-widget` (P0 ne
scrive i contratti, i null-object, i fake e gli esecutori di riferimento `ReferenceWidgetPlanner`, `InMemoryWidgetLedger`,
`ReferenceWidgetSchedule`, `ReferenceWidgetTaps`, `InMemoryReleaseState`, `TableManifestVerifier`, `ReferenceDescriber`, con le suite di
contratto). Le regole:
- `core-widget`, `core-release` e `core-describe` sono **JVM puri**; `core-widget` non nomina mai una persona nelle firme e vede di
  `core-memories` solo i contratti; `core-release` vede solo le fondamenta e i campi del manifest, le chiavi e il verificatore non escono da
  lui (fuori si legge solo `ReleaseStatus`, e gli indirizzi aggiuntivi passano da `ExtraModelSources` di `core-models`); il manifest si legge
  **solo dal NAS** (Q8 (a)): nessuna classe di rete nuova;
- `core-describe` non ha una superficie per log, Diagnostica o esportazione;
- Glance ed `engine-widget` solo in `feature-widget` (sorgenti, `build.gradle` e bytecode della release), il `PendingIntent` del widget solo in
  `feature-widget/.../tap/`;
- nessun `sharedUserId` e nessun permesso definito dall'app in nessun manifest: la firma resta `pampa.jks`, condivisa con le altre app Pampa
  (decisione dell'utente del 31/10, limite L559 di `docs/sicurezza.md`).

`Phase10ArchitectureTest` (`core-common`, P0) tiene queste regole; ogni violazione reintrodotta lo fa fallire. `tools/check.ps1` ha due passi
nuovi: nessuna chiave privata nel contenuto dei file del repository (3b) e Glance al suo posto nel dex di release, senza la chiave di prova
del manifest (7i). Le aggiunte ai moduli esistenti (`contract/Drag.kt` e `DragPort.kt` in `core-library`, `ExtraModelSources` in
`core-models`, `adaptive/` in `core-designsystem`, `DiagSection.WIDGET` e `RELEASE`) sono in `docs/decisioni.md` ("Fase 10 P0").

**In arrivo con i pacchetti della Fase 10**: `CHANGELOG.md` alla radice (le novita' di ogni versione in italiano semplice, la fonte del testo
del Pampa Store e della pagina "Novita' di questa versione", P12); `tools/release/` (lo strumento delle chiavi e della firma del manifest
`ManifestTool`, P1; `release.ps1` e `recinzioni.json`, P10 e P12); `tools/security/` (i generatori ostili della revisione finale 10.4, che
scrivono solo nelle share di prova dentro `mcg-it/<giro>/`, P11).

**Fase 9 in `:app` (P13).** `MemoriesModule` monta i ricordi (`MemoriesWiring.build` -> `MemoriesGraph`): nascono senza I/O e si montano dopo il
primo disegno utile con la famiglia pronta, leggendo prima l'ultimo scaffale da file; le porte vere sono i viaggi di `CatalogTrips`, le persone
montate (solo con un nome, celle mostrabili, il nome mai salvato), i vettori della ricerca, le misure dei pixel dalle miniature del telefono, i
ritagli; la notifica (`MemoryNotifier`, il lavoro unico `memories-notify` con il suo giorno in `MemoryNotifySlot`) e la musica (selettore di sistema,
`MediaPlayer` con il focus audio). `MapModule` monta la mappa (`MapGraph`), pigra in ogni pezzo: impostazioni su DataStore, punti dalla query di P3
alla prima apertura, rete e traffico da core-network e dal registro, MapLibre solo alla prima mappa o mini-mappa con il client delle tessere
installato prima; le regioni offline (P5) sono ancora il null-object. Le sezioni Ricordi e Luoghi delle Raccolte, le rotte dei due feature,
"Mostra sulla mappa" nel viewer, le voci di Impostazioni, la generazione notturna nella manutenzione, la Diagnostica e l'azzeramento (`memories/`,
`map/`, lavoro e notifica) sono in `docs/decisioni.md` ("Fase 9 P13: integrazione").

**Fase 6 in `:app` (P11).** `AiModule` monta tutto una volta per processo (`AiWiring.build`): `LocalModels` (cartella
`noBackupFilesDir/models`, sorgenti di `ModelFetchers`, cancello di rete per l'HTTPS; la copia sul NAS resta spenta finche' `core-nas` non
ha la scrittura a flusso), `SearchGraph` (`noBackupFilesDir/search`: indice locale, righe del testo letto, cronologia nel database),
`AiGraph` (buffer `noBackupFilesDir/ai-buffer`, calcolo, lettura del testo con `GatedTextReader.create`). La parte `ai` dell'indice
condiviso nasce con il motore del NAS (`NasAiRuntime`: replica `AiShareSync` agganciata a `IndexSyncEngine`, pubblicatore, unita' con
`WorkPass.AI`). All'avvio `TextReadingStartup` gira **prima di Hilt** (D41). L'azzeramento ferma AI e ricerca per prime. La prova della
catena intera e' `SearchChainIntegrationTest`.

**Il completamento della Fase 6 in `:app` (P11b).** Tre pezzi che P11 aveva lasciato fuori:
- **Il ritaglio del soggetto (P13)** e' collegato. `CutoutRuntime` tiene `SubjectCutting` con MagicTouch (`MagicTouchCutter.create` sui file
  verificati; il modello e' nell'APK e la prima volta si copia fra i modelli, senza rete), costruito al primo ritaglio e rilasciato dopo 3 minuti,
  a memoria bassa (`AiStack.onTrimMemory`) e all'azzeramento (`AiGraph.stop`). `ai/CutoutWiring.kt` ha gli adattatori delle porte del viewer:
  `AppViewerSubjectCutter` (sorgente = la migliore immagine che il dispositivo ha gia': la riga del telefono, l'originale portato dal viewer o
  l'HQ della replica; mai uno scaricamento), `AppCutoutPng`, `CutoutFiles` (Copia e Condividi nella cartella `cacheDir/share/` del
  `FileProvider` che c'e' gia'), `AppCutoutNewPhoto` ("Salva come nuova foto" in `Pictures/MyCloud Gallery/Ritagli/`, in debug con un conto di prova
  `Pictures/mcg-prova/Ritagli/`) e `DiagCutoutSaver` (`CUTOUT_FAILED`, `CUTOUT_SAVE_FAILED`). La sorgente "Ritagli" del backup arriva dal passo 6
  dell'onboarding (`BackupSourceCodec.fromOnboarding`) e, una volta sola, alle installazioni di prima (`DataStoreBackupSettingsStorage.offerCutoutsOnce`).
  La prova e' `CutoutIntegrationTest`.
- **I media del solo telefono si analizzano** (§2.2, D10, D14). `AppAiPhone` elenca i media senza copia sul NAS (`MediaCatalog.phoneOnlyWork`),
  apre i loro byte (`MediaStorePhoneBytes`, con la recinzione di D13) e il `Preparer` della preparazione del NAS ne fa le anteprime, che vanno nel
  buffer; il calcolo gira nella stessa tappa e con le stesse regole, e i risultati restano solo locali. La prova e' `PhoneOnlyAiTest`.
- **La lettura al volo del viewer** legge l'immagine mostrata (anteprima nel buffer, byte del telefono, HQ della replica) solo con la lettura del
  testo attiva qui, entro 2 secondi; il testo si salva nell'indice locale e si mette nell'outbox `ai` delle share che contengono il file (`AiGraph.keepReadText`).
  La prova e' `ViewerTextOnTheFlyTest`.

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

- **Schermate**: le quattro pagine di `feature-nas` sono rotte (`nasGraph`) e, in Raccolte, la sezione "Cartelle NAS" (prima "Sul NAS"; voci come
  dati, `CollectionEntry`): sul telefono una voce apre la rotta, sul tablet la pagina sta nel pannello di dettaglio. Un file toccato in
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
- **Navigazione**: "Libera spazio" in Raccolte → Utility e nella selezione della Libreria; "Da decidere" nella pagina Backup.
- **D12/R2/D13** valgono anche qui: le versioni con un account di prova stanno sotto `mcg-campo/Versioni/`, i file in arrivo passano dal
  cancello della share di **destinazione**; dalla 5B non si scrive mai nell'area comune (correzioni e versioni si fermano con un avviso).
- **Lavori**: i file in arrivo con `IncomingUploadWorker` (o, per una condivisione grande, `BackupJobService` con l'id `1_000_104`).
- **Prove**: `FiveBIntegrationTest` (sei percorsi interi sul grafo vero, finti solo NAS e telefono), `FiveBButtonsTest`, `AppEntryGateTest`.

**Gli album nell'app (Fase 7, P13).** `di/AlbumsModule.kt` monta un `albums/AlbumsGraph` per processo (`AlbumsWiring.build`): nasce con il
processo e monta gli album (`AlbumsLive`) appena la famiglia e' pronta (galleria partita e dati personali agganciati); l'azzeramento lo ferma
**per primo** (`AppGraph.albumsStopper`), poi galleria e dati (`albums/` in `noBackupFilesDir`, il database):

```
condivisi (P2)   -> replica e pubblicatore su Room (RoomAlbumReplicaStore, RoomAlbumPublishStore: righe di shared_album_entry), NasSharedAlbumSync
condivisione (P4)-> ShareOrchestrator + RoomShareDiary; OpsAlbumMover sulle NasOperations della 4B (ConfirmationIssuer sullo stesso piano),
                    FolderAlbumUploader sui caricamenti INCOMING del backup (cartella "Album condivisi" dell'area comune), guardiano
                    (GuardianSafeCopyRequests), una sola porta di completamento sul catalogo (CatalogMoveCompletionSink -> attachMoved)
personali (P3)   -> CatalogAlbums sui dati personali; copie degli album chiusi con PersonalAlbumCopies.keep
smart (P6)       -> RulesSmartEngine, SmartRecompute (a fine scansione, accorpato), segni "Nuove" su file
viaggi (P7)      -> CatalogTrips, rilevazione una volta per notte (LibraryMaintenanceWorker)
```

- **Lavori**: il giro degli album nel lavoro dell'indice (`IndexSyncWorker`: replica, pubblicazione, copie, ripresa), la condivisione nel suo lavoro
  (`work/AlbumWork.kt`, `ShareWorker`), la notte (`LibraryMaintenanceWorker`: viaggi, ricalcolo degli smart, hash completo dei video e firme dei
  Doppioni simili). Recinzione D30 (`FeatureFlags.sharedAlbumsMayUse`) e cancelli dell'indice (`AppAlbumWriteGate`), mai `LOCAL_ONLY`.
- **Schermate**: `albumsGraph` (elenco, pagina, Suggerite, editor, viaggi) e il selettore dei media (`MediaPickerDestination`), `utilityGraph`
  (Utility e Doppioni), Impostazioni → Privacy → Nascosti. "Aggiungi ad album" (selezione della Libreria, menu ⋮ del viewer, Nascosti,
  condivisione in arrivo) e' un foglio sopra la schermata (`AlbumRequestsHost`), mai una rotta; il viewer aperto da un album ha anche "Copertina"
  e "Rimuovi" secondo la tabella dei permessi.
- **Raccolte** (`di/CollectionsModule.kt`): un fornitore per sezione (Album, Album smart, Viaggi, Cartelle NAS, Utility con "Libera spazio",
  "Da tenere al sicuro" vuota perche' gli Eliminati di recente sono nell'Utility); ordine e visibilita' nei dati personali
  (`SyncedCollectionsSettings`), validi subito anche prima del registro.
- **Utility** (`di/UtilityModule.kt`): Doppioni identici e simili sul catalogo, cestino del NAS e pulizia sopra `NasOperations` (un tocco per il
  cestino, due per "Elimina subito"), "Controlla di notte i video del NAS".
- **Diagnostica**: sezione Album con i codici del §2.8 (`albums/AlbumsDiagnostics.kt`, solo numeri).
- **Prove**: `AlbumsIntegrationTest` (due famiglie di dispositivi sul grafo vero e sul simulatore SMB, album ostile, azzeramento),
  `AlbumsShareBenchAppTest` (il banco della condivisione sui legami di `:app`, con guasti SMB e morti del processo), i test delle porte e del registro
  dei Nascosti.

**Le persone nell'app (Fase 8, P13).** `di/FacesModule.kt` monta un `faces/FacesGraph` per processo (`FacesWiring.build`): nasce con il processo e
monta le persone (`FacesLive`) appena la famiglia e' pronta; l'azzeramento lo ferma **per primo** (`AppGraph.facesStopper`), poi distrugge **tutte** le
chiavi dei volti (`mcg-faces-<G>`, anche nella fase delle credenziali di `DeviceSelfWipe`) e toglie i file (`FacesWiring.FILES`: `faces/` con i database
cifrati, le chiavi avvolte, `faces-local`, `faces-erase`, `personal-heads`, battito e segno della tappa). Il NAS non si tocca all'azzeramento di un dispositivo.

```
interruttore (P7) -> SyncedFacesSettings (voce `faces` dei dati personali) + FileFacesLocalStore + DefaultFacesSwitch; avviso una tantum all'apertura dell'app
recinto (P8)      -> FacesEraseFence per PRIMO: ogni apertura (archivi, replica, pubblicatore, calcolo) passa da Fenced*; un solo FacesStores per processo
calcolo (P4)      -> FacesComputeEngine sopra il catalogo (CatalogFacesCatalog), il buffer di analisi, l'indice attestato e i byte del telefono (AppFaceInputs);
                     motori YuNet/SFace costruiti solo quando servono (FacesEngineHolder) e rilasciati a fine tappa, a memoria scarsa e allo stop
sync (P3)         -> NasFacesRemote/Sync/Publisher/Leases sull'area privata, FacesAttestation sul catalogo; custode (P5) con CustodianGrouping
cancella (P8)     -> DefaultFacesEraser + FacesNasWiring (core-nasops) + FacesNasJanitor; gancio PrePublishHook nei dati personali (FacesPrePublish dalla nascita del processo)
persone (P9)      -> ArchivePeople dietro le facciate (FacesPeopleFacade, ritagli solo in memoria), sezione delle Raccolte, rotte (peopleGraph), riga nel viewer
ricerca/album(P10)-> PeopleBridge (ricerca per persona, regola Persone degli smart, PeopleChoices, peopleChanged, cronologia ripulita dai nomi)
```

- **Lavori** (nomi distinti da quelli degli altri lavori): il calcolo `faces-compute` (tappe da 8 minuti, `AI_COMPUTE`, periodico e "Analizza ora"), la
  cancellazione `faces-erase` (riprende dal diario), la replica e la pubblicazione nel giro dell'indice **dopo** i dati personali (`IndexSyncWorker`: la
  generazione e' fresca), il raggruppamento notturno nella manutenzione della Libreria, la pulizia dei propri file nella manutenzione dell'indice.
- **Cancelli**: `AppFacesWriteGate` = recinzione D34 (`FeatureFlags.facesSyncMayUse`, accesa **anche in release** fino al consenso) + `ShareFence` (solo
  debug) + cancelli dell'indice; il passo «D34 nella release» di `tools/check.ps1` lo legge nel bytecode.
- **Modelli**: `ModelCatalog.needed(..., FACES)` solo dove Persone e' attiva qui; i due modelli stanno nell'APK e si estraggono alla prima apertura; l'esclusione
  e la cancellazione li tolgono.
- **Diagnostica**: sezione Persone (`faces/FacesDiagnostics.kt`): i codici del §2.10 (`FacesDiag.ALL`), solo numeri; `FACES_KILLED_MEMORY` per la morte per memoria; `FACES_HISTORY_KEPT` quando le ricerche recenti potrebbero tenere ancora un nome (correzioni D).
- **Debug**: lo strumento di valutazione delle persone (`debugtools/eval/faces/`), in sola lettura; non c'e' nella release.
- **Prove**: `FacesIntegrationTest` (due account e tre dispositivi sul grafo vero e sul simulatore SMB: attivazione, calcolo e istantanea, nomi che viaggiano,
  l'altro account non vede niente, «Disattiva e cancella» con un dispositivo spento che torna, azzeramento completo), `FacesFenceSourcesTest`,
  `AppFacesWriteGateTest`, `Phase8DiagCatalogTest`, `FacesWorkSchedulingTest`, `FacesStageWatchTest` e i test del registro dei Nascosti sul codice legato.

Il Fluid Engine e' in `engine/` (submodule, agganciato al tag **engine-2.13.1**); entrano nel build solo
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

## Rilascio: versioni, changelog, `release.ps1`

La catena di rilascio della Fase 10 (P12; `docs/fase10-progetto.md` §2.9, D32-D34; `docs/decisioni.md`, "Fase 10 P12"). Tutto dal PC, niente
rete: la pubblicazione sul Pampa Store la fa poi l'orchestratore con la skill del Pampa Store, e solo con il sì dell'utente.

- **`release/versions.txt`**: il registro delle versioni, una riga per APK pubblicato e in fondo quella che si prepara:
  `<versione> <versionCode> <stato> <canale> <data> <sha256>`, stato `pubblicata` o `prevista` (una sola, l'ultima), canale `beta` o `stable`.
  `app/build.gradle` prende `versionName` e `versionCode` dalle prime due colonne dell'**ultima** riga (e fallisce se non e' valida). Il
  `versionCode` cresce sempre e non si riusa: lo stesso numero puo' stare su due righe solo per la stessa versione e lo **stesso APK** (il passaggio
  da beta a stable, con la stessa impronta).
- **`CHANGELOG.md`**: le novita' in italiano semplice, una sezione `## <versione>` con quattro voci in quest'ordine (Novita', Correzioni,
  Sicurezza, Da sapere). La copia dentro l'app e' `feature-settings/src/main/res/raw/changelog.md` (Impostazioni -> Informazioni e licenze ->
  "Novita' di questa versione"): `ReleaseNotesTest` le tiene uguali, quindi si cambiano insieme.
- **`tools\release\release.ps1`**: i passi della release, con tutti i controlli (codice di uscita 0 solo se tutto e' a posto):

  | Passo | Cosa controlla |
  |---|---|
  | 0 chiavi del manifest | `ReleaseKeys.PROVISIONAL` falso: K1 e K2 vere (senza, nessun blocco a distanza, L560) |
  | 1 albero | `git status` pulito, HEAD sul tag `v<versione>` di `main` (o un commit dopo che cambia solo `versions.txt`), engine pulito |
  | 2 recinzioni | `tools\release\recinzioni.json` c'e' ed e' leggibile (il confronto con il bytecode e' di `check.ps1`) |
  | 3 prove | `tools\check.ps1 -Bench -BenchStrict` verde con l'S25 collegato, riferimento S25 di al piu' 7 giorni |
  | 4 build | la riga di `versions.txt` e `assembleRelease` firmata con `pampa.jks` (le voci `pampa.*` le legge Gradle, lo script mai) |
  | 5 APK | firma v2 e v3 con l'impronta di `docs/sicurezza.md` §5.6, non debuggable, manifest e permessi dell'elenco chiuso, nessun resto di debug o di KT, librerie a 16 KB |
  | 6 changelog | la sezione della versione in `CHANGELOG.md` e dentro l'APK |
  | 7 resoconto | `build\release\<versione>\`: APK, SHA-256, rapporto, dipendenze, mapping di R8 (resta sul PC), testo per lo store, checklist |
  | 8 prova a secco | non la fa lo script: stampa cosa dare all'orchestratore (`--dry-run` del publisher) |

  Le opzioni:

  | Opzione | Cosa fa |
  |---|---|
  | `-Version <x.y.z[-beta.N]> -Channel beta\|stable` | la release di quella versione (passi 0-8) |
  | `-Prepara` (con `-Version`, `-Channel`) | aggiunge la riga `prevista` a `versions.txt` (versionCode = massimo + 1; lo stesso da beta a stable) |
  | `-SegnaPubblicata` (con `-Version`, `-Channel`) | dopo la pubblicazione: la riga diventa `pubblicata` con la data e l'impronta dell'APK |
  | `-Impronta [-Scrivi]` | l'impronta SHA-256 del certificato di `pampa.jks` (la password passa da una variabile d'ambiente, mai stampata); `-Scrivi` la mette in §5.6 |
  | `-Checklist` | stampa la checklist di rilascio |
  | `-NoDaemon`, `-BenchSerial <seriale>`, `-BenchMaxAgeDays <n>`, `-GradleArgs` | Gradle senza demone, l'S25 fra piu' dispositivi, l'eta' massima del riferimento, argomenti per Gradle |
  | `-TestKeystore <file sotto build\>` (`-TestStorePassword`, `-TestKeyAlias`) | **solo prove**: firma con un keystore di prova; solo cosi' valgono `-TestOverlay`, `-ApkPath`, `-SkipTree`, `-SkipChecks`, e il resoconto va in `build\release-test\` con scritto "PROVA" |

  ```powershell
  powershell -ExecutionPolicy Bypass -File tools\release\release.ps1 -Impronta -Scrivi                 # una volta, l'utente
  powershell -ExecutionPolicy Bypass -File tools\release\release.ps1 -Prepara -Version 1.0.0 -Channel beta
  powershell -ExecutionPolicy Bypass -File tools\release\release.ps1 -Version 1.0.0 -Channel beta -NoDaemon
  powershell -ExecutionPolicy Bypass -File tools\release\release.ps1 -SegnaPubblicata -Version 1.0.0 -Channel beta
  ```

  La prova dello script e' `tools\release\test-release.ps1` (APK piccoli costruiti con gli strumenti dell'SDK e un keystore di prova, nessuna rete).
- **Il manifest firmato** (D29-D31): `release/manifest.json` (leggibile, senza segreti) si firma con `tools\release\manifest-tool.ps1 sign` e si copia
  sul NAS in `<area comune>\.mycloudgallery\release\manifest.mcgr` (`docs/formato-nas.md` §15). L'app lo legge nel giro della sincronizzazione al
  piu' una volta al giorno: puo' solo mettere questa versione in sola lettura (avviso "Aggiorna") o fermarla, mai cancellare o aprire qualcosa.

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

## Licenza

MyCloud Gallery e' software libero: si distribuisce con la **GNU General Public License, versione 3**
(file `LICENSE`), e chi riceve l'APK ha diritto ai sorgenti completi, con la stessa licenza. Viene dal
Fluid Engine 3.0, che contiene codice GPL-3.0 di LibrePods (`engine/LICENSES/LibrePods.md`); le licenze
delle librerie e delle opere dentro l'engine sono nella pagina Impostazioni -> Informazioni e licenze.
