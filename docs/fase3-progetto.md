# Fase 3 di MyCloud Gallery: progetto tecnico (la galleria del telefono)

> Base per i brief dei sub-agent. Fonti:
> - piano: §2, §4, FASE 3 (3.1–3.5), §6 (E-P, E-M, E-V, E-H), §7 e legami con 4.4 e 4.7;
> - `docs/modello-dati.md` e il codice di `core-model` e `core-database` (v1);
> - `docs/android.md` (temi 1–4, Android 17, T-09…T-13, T-23), `docs/decisioni.md`, `docs/inventario-nas.md`, `docs/sicurezza.md` (§4-bis, §8, checklist della Fase 3);
> - `core-designsystem` (`GalleryMediaCell`, `GalleryBadge`, `GalleryTimelineHeader`, `GalleryMotion`, barre, menu e fogli), Fluid Engine 2.11 (skill, `regole.md`, `design-system.md`);
> - `docs/fase2-progetto.md` per il formato e le regole dei pacchetti, `docs/research/*` solo per non precludere le fasi 6 e 8.
>
> Contesto:
> - **La Fase 2 è integrata.** La Libreria si raggiunge con `SetupState.Ready`, cioè con la famiglia fondata o il dispositivo approvato.
> - **La Fase 3 non usa né il NAS né la rete.** Lavora sul MediaStore del telefono e sulla replica locale (`gallery.db`).
> - **Dispositivi.** S25 (Android 17) e Tab S9 (Android 16) non sono sempre collegati. Ci sono già due emulatori configurati: `Medium_Phone` (API 37.1, 1080×2400) e `Pampa_Tablet` (API 35, 2560×1600, 1280 dp).

## 0. Sintesi

**Moduli nuovi**
- `core-places`: luoghi offline;
- `core-metadata`: date, tipi, EXIF, XMP e motion photo;
- `core-mediastore`: lettura e sincronizzazione del telefono;
- `core-imaging`: miniature e decodifica;
- `core-library`: indice della timeline, layout, filtri, selezione e il contratto con il viewer;
- `feature-library`: Libreria e Raccolte minime;
- `feature-viewer`;
- `benchmark`: modulo `com.android.test` con Macrobenchmark e Baseline Profile.

**Aggiunte ai moduli esistenti**
- `core-model` e `core-database`: schema v2 con migrazione;
- `core-designsystem`: package `grid/` e `viewer/`;
- `core-common`: una sezione della Diagnostica;
- `feature-diagnostics`: i codici della Fase 3;
- `feature-settings`: Informazioni e licenze;
- `:app`.

**La Libreria in breve**
- Un **indice in memoria a colonne**: 100 mila elementi occupano circa 8 MB.
- Una **istantanea su file** dell'indice per l'avvio a freddo sotto il secondo.
- Un **layout precalcolato** per livello e stile, mostrato come `LazyColumn` di righe. Niente Paging 3 e niente `LazyVerticalGrid`.
- Il viewer è una **sovrapposizione nella stessa schermata**: la miniatura diventa il viewer.

**Dodici pacchetti (P0–P11)**
- P0 (fondamenta) e P1 (modello e database v2) partono per primi, in parallelo, su file disgiunti.
- Poi P2–P10 in parallelo.
- Per ultimo P11, l'integrazione.
- Una tappa intermedia **M1** (griglia sintetica di P7, misurata da P9) verifica presto il rischio più grande, la fluidità a 120 Hz.

**Test senza dispositivo**
- `FakeMediaStore` e una libreria sintetica di 100 mila elementi.
- Robolectric con un provider `media` finto.
- Roborazzi per gli screenshot.
- Budget JVM su complessità e ricomposizioni.
- Gli emulatori per i test strumentati e gli smoke dei benchmark.
- I numeri veri arrivano dai passi F1–F19 sul campo.

### Decisioni di progetto (valgono se l'utente non dice altro)
| # | Decisione | Perché |
|---|---|---|
| D1 | **Ordine come Apple Foto.** I più recenti stanno in fondo e la Libreria si apre in fondo; toccare di nuovo "Libreria" (`GalleryTabBar.onReselect`) torna a oggi. Un solo parametro (`newestAtBottom`) nel layout | È l'interazione scelta nel piano §2; cambiare idea costa una riga |
| D2 | **Indice in memoria a colonne** (`TimelineSnapshot`): id (due `long`), istante, giorno locale, tipo, sorgente, flag, proporzione, durata, luogo, thumbhash. Nasce da una query a chiave su `media`, si aggiorna a pezzi dal flusso dei cambiamenti del catalogo e si salva in `noBackupFilesDir/replica/timeline.idx` per l'avvio a freddo | Scrubber, livelli, intestazioni con luoghi, pinch ancorato e ritorno dal viewer vogliono accesso casuale a tutta la timeline: Paging 3 imporrebbe salti, invalidazioni e segnaposto |
| D3 | **Griglia = `LazyColumn` di righe precalcolate** dal `LayoutEngine`: ogni riga è un'intestazione o una fila di celle. L'intestazione agganciata si disegna sopra la lista leggendo il modello, senza `stickyHeader` | Un elemento pigro per riga (7 celle al livello Anni); posizioni aritmetiche, quindi scrubber e ancoraggio in O(log n); la stessa struttura regge la griglia giustificata |
| D4 | **"Proporzioni originali" = righe giustificate**: nella riga stessa altezza, larghezza data dalla proporzione, entro 1:2–2:1 come la cella approvata nel catalogo 1.2. Di default la griglia è quadrata. *Alternativa*: l'immagine intera dentro il quadrato, come fa Apple | È ciò che mostra il catalogo approvato (`docs/screenshots/1.2/celle-*`) |
| D5 | **Miniature con una pipeline propria e sottile** (`core-imaging`). Per il telefono `ContentResolver.loadThumbnail`, annullabile, con la cache del MediaProvider. Poi: cache in memoria a taglie fisse; pianificatore guidato dalla finestra visibile, con priorità e annullamento; `prepareToDraw` fuori dal thread principale; segnaposto sfocato da **ThumbHash** salvato nell'indice. **Niente Coil né Glide** | A 120 Hz il percorso caldo va controllato: priorità, annullamento e la stessa bitmap per l'espansione nel viewer. In Fase 4 le miniature del NAS arrivano dalla replica locale e un caricatore generico non aggiunge niente. Meno dipendenze (§7.9) |
| D6 | **Zoom con telephoto** (`zoomable-image` + `sub-sampling-image`, Apache-2.0). La sorgente è nostra (nessun adattatore Coil o Glide) ed è avvolta in un componente nostro, `ZoomableMedia`. *Piano B*: tasselli propri con `BitmapRegionDecoder` | Tasselli, campionamento a 200 MP e convivenza con il pager sono già risolti; avvolto, si può sostituire |
| D7 | **Video con Media3 ExoPlayer**. Sorgenti solo locali (`ContentDataSource`, anche come intervallo di byte), mai `DefaultDataSource`. Controlli propri in stile Fluid, un solo player riusato, superficie `SurfaceView` (HDR) | Scorrimento preciso, HDR10/HLG, parte video delle motion photo come intervallo di byte, nessuna rete |
| D8 | **Luoghi offline da GeoNames** (CC BY 4.0). Uno strumento nel repo (`core-places`, task `buildPlacesDataset`) genera un file binario compatto: nome italiano, regione, paese (con `Locale.ITALIAN`), **fuso orario** di ogni luogo. Il file si estrae alla prima apertura in `noBackupFilesDir/places/` e si mappa in memoria. L'attribuzione sta in Impostazioni → Informazioni. **Scelta del luogo** (corretta dopo P4): non vince il più vicino, ma distanza meno un raggio d'influenza che cresce con la popolazione; i micro-stati (Vaticano, San Marino…) si riconoscono con poligoni semplificati, altrimenti il centro di Roma diventerebbe "Città del Vaticano" | Nessun servizio online (3.2; checklist "nessun traffico"). Il fuso del luogo risolve E-M14 per i video |
| D9 | **Impronte delle foto solo con lettura fedele** (`ACCESS_MEDIA_LOCATION` + `setRequireOriginal`). Senza il permesso le foto nuove restano "in attesa", contate e spiegate: **mai un hash rapido su byte oscurati**. I video non sono toccati. Nuovo edge case **E-P21** | Con byte oscurati, all'arrivo del permesso l'hash cambierebbe e il catalogo vedrebbe una modifica, cioè una versione falsa (android.md, scoperta 9). `qh1` è un contratto fra dispositivi |
| D10 | **La lettura del telefono resta fuori dal motore delle regole.** In primo piano gira sempre. In background girano solo: un lavoro WorkManager con trigger di contenuto (batteria non scarica, tappe di 8 minuti al massimo) e una manutenzione quotidiana in carica. Nessuna rete | È lavoro locale e leggero; la 1.6 governa NAS e calcolo |
| D11 | **META del telefono = metadati + luogo + thumbhash**, più il tipo definitivo e i gruppi. La fase `THUMB` resta alla Fase 4/5 (miniatura dell'indice condiviso): per i media del telefono la griglia usa le miniature di sistema | Nessun cambio di significato agli stati di lavoro; nessun file di miniature proprio per il telefono |
| D12 | **Il viewer è una sovrapposizione, non una rotta.** La miniatura diventa il viewer (`GalleryViewerTransitionState` + `FluidPhysicsState.driveExternally`), sopra la pillola. `:app` lo ospita; la griglia fornisce la geometria e il "porta in vista" (`ViewerAnchor`). Si chiude anche con il gesto Indietro predittivo | Regola Fluid: nessuna pagina in arrivo sfuma. Il piano vuole l'elemento condiviso dentro la schermata |
| D13 | **Menu ⋮ a capacità.** In Fase 3 compaiono solo le azioni che funzionano: preferito, nascondi, condividi, copia, elimina dal telefono, informazioni. Versioni, correzioni, scarica, modifica con, sposta e rinomina arrivano con le loro fasi, senza cambiare la struttura | Nessuna voce morta |
| D14 | **In Fase 3 si elimina solo dal telefono, nel cestino di sistema** (`createTrashRequest`: dialogo di sistema, 30 giorni), con "Eliminati di recente" per il ripristino. Il foglio avverte che **non c'è ancora una copia sul NAS**. Su Android 10 l'azione non c'è. *Alternativa*: rimandare tutto alla 5.5 | Il piano 3.4 la chiede; "ogni rimozione passa da un cestino" resta vero; l'app non cancella mai da sola |
| D15 | **Badge della Fase 3**: durata dei video, preferito, modificata, non leggibile. Solo telefono, solo NAS, al sicuro e in caricamento restano spenti fino alla Fase 4 (`LibraryFeatures.presenceBadges = false`) | Senza NAS tutto sarebbe "solo telefono": sarebbe solo rumore |
| D16 | **Filtro rapido**, ricordato: Tutto · Senza screenshot e WhatsApp · Screenshot · WhatsApp · Video · Preferiti. "Documenti" arriva con la Fase 6, perché serve capire il contenuto | §2: screenshot e WhatsApp restano visibili, con un filtro |
| D17 | **Raccolte minime**: Preferiti, Video, Screenshot, WhatsApp, Nascosti (credenziale di sistema, `FLAG_SECURE`), Eliminati di recente. Sul tablet a due pannelli (`FluidListDetailScaffold`): è la "barra laterale delle raccolte" del piano | La checklist di sicurezza della Fase 3 vuole i Nascosti protetti; la Fase 7 completa le Raccolte |
| D18 | **Mini-mappa rimandata alla 9.3**, perché le tessere richiedono la rete. Il pannello info mostra luogo, regione, paese, coordinate e "Apri in Mappe" (`geo:`, azione esplicita con avviso). *Alternativa*: una carta schematica offline (Natural Earth, pubblico dominio, circa 1 MB) | La Fase 3 non deve generare traffico di rete |
| D19 | **Video e motion photo.** Il video parte da solo quando la pagina si ferma, con l'audio e il focus audio, come in Foto. La motion photo si anima tenendo premuto | Comportamento di Apple; **da confermare** |
| D20 | **Raffiche e coppie RAW+JPEG come un elemento solo** (la copertina), con i `MediaGroup` esistenti. Nel dubbio non si raggruppa | Modello 1.3 (E-M6, E-M13) |
| D21 | **Prestazioni** con il modulo `:benchmark` (Macrobenchmark + generatore del Baseline Profile), soglie in `benchmark/soglie.json` e `tools\check.ps1 -Bench` a dispositivo collegato. Senza dispositivo il controllo è SKIP, ma la chiusura della fase richiede i risultati di S25 e tablet in `docs/bench/`. I budget JVM girano sempre dentro `galleryTest` | §4 e 3.5, con dispositivi non sempre collegati |
| D22 | `READ_EXTERNAL_STORAGE` con `maxSdkVersion="32"` (Android 10–12, coerente con minSdk 29). **Proposta** | `sicurezza.md` §4-bis lo rimandava alla Fase 3 |
| D23 | Valgono le regole della Fase 2: `contract`, `noop` e `testing` in ogni modulo nuovo; `core-*` senza Hilt; `feature-*` con `@HiltViewModel`; dipendenze, permessi e file di build condivisi li tocca **solo P0** (poi P11) | D6–D8 della Fase 2 |
| D24 | **Nessuna rete nei moduli della Fase 3**: un test vieta `java.net`, HTTP, `DefaultDataSource` e `DefaultHttpDataSource` nei loro sorgenti | Checklist della Fase 3: nessun traffico |

**Nuovi edge case proposti** (da aggiungere al §6 del piano):
- **E-P21**: `ACCESS_MEDIA_LOCATION` negato o ritirato. Le foto nuove restano in attesa e non nasce mai un'impronta oscurata. Quando il permesso torna si elaborano senza creare versioni.
- **E-P22**: `MediaStore.getVersion` cambiato (aggiornamento di One UI o del modulo Mainline). La scansione completa è riprendibile, usa la scorciatoia del timbro, non crea identità nuove e non segna assenze prima della fine.
- **E-M15**: orientamento EXIF specchiato (2, 4, 5, 7). La foto si vede sempre dritta e la proporzione è giusta.
- **E-M16**: motion photo in un formato non riconosciuto (per esempio HEIC di Samsung). Si mostra la foto ferma, con "parte video non riproducibile" nelle info.

## 1. Moduli

### 1.1 Grafo delle dipendenze (nessun ciclo)
```
core-common   core-model                                     (foglie)
core-database   → model, common
core-places     → model, common                              (+ asset GeoNames)
core-metadata   → model, common, places                      (+ exifinterface)
core-mediastore → model, common, database, metadata, places
core-imaging    → model, common                              (+ compose ui-graphics: ImageBitmap)
core-library    → model, common, database                    (+ datastore)
core-designsystem → engine-ui                                (+ grid/, viewer/)
feature-library → library, imaging, mediastore, places, security, designsystem
feature-viewer  → library, imaging, mediastore, metadata, places, database, security, designsystem (+ media3, telephoto)
benchmark       → com.android.test sul target :app (mai nell'APK)
app → tutti
```
- `core-library` non conosce i nomi dei luoghi: tiene i `PlaceRef` e i nomi li risolve la UI.
- I `feature-*` non si conoscono fra loro. La griglia apre il viewer con una callback fornita da `:app`.

### 1.2 Responsabilità e API (firme indicative; P0 scrive contratti, null-object e fake)

**`core-model` (P1): aggiunte**
| Elemento | Firma | Note |
|---|---|---|
| Tratti | `enum class MediaTrait(code, bit) : StableCode { PANORAMA, SPHERE, ULTRA_HDR, HDR_VIDEO, SCREEN_RECORDING }` | codici e bit stabili |
| Metadati | `MediaMetadata` + `traits: Set<MediaTrait> = emptySet()`, `place: PlaceRef? = null`, `thumbHash: ThumbHash? = null` | per versione, come oggi |
| Luogo | `@Sensitive class PlaceRef(val geonameId: Int, val dataset: Int)` | deriva dal GPS: è sensibile come il GPS, niente `toString` |
| Segnaposto | `@JvmInline value class ThumbHash(val base64: String)` | al massimo 48 caratteri, validato |
| Accesso ai byte | `sealed interface MediaRef { Phone(volume, mediaStoreId: Long, mime: String?); Synthetic(seed: Long, width, height) }` | dalla Fase 4 anche `Nas`. L'`_ID` vale solo con la stessa `getVersion`: lo garantisce la sincronizzazione |
| Flag della cella | `object CellFlags { EDITED, DATE_ESTIMATED, UNREADABLE, HAS_PLACE, MOTION, PANORAMA, ULTRA_HDR, FAVORITE }` | bit stabili, scritti nei campi derivati |
| Timbro | `FileStamp.looksUnchangedFrom(previous, ignoreGeneration: Boolean = false)` | dopo un cambio di `getVersion` la generazione non ha più senso (E-P22) |

**`core-database` (P1): schema v2 e catalogo**
| Elemento | Contenuto | Note |
|---|---|---|
| `media` (derivati) | + `local_day INTEGER NOT NULL` (aaaammgg nell'ora del posto), `aspect_milli INTEGER NOT NULL DEFAULT 1000` (già orientata), `duration_ms INTEGER`, `place_id INTEGER`, `cell_flags INTEGER NOT NULL DEFAULT 0`, `thumb_hash BLOB` | riscritti da `refreshDerived` come `sort_time`. Servono a leggere l'indice da **una** tabella |
| `version_metadata` | + `traits INTEGER`, `place_id INTEGER`, `place_dataset INTEGER`, `thumb_hash BLOB` | |
| `media_personal` | + `favorite_changed_at`, `hidden_changed_at` | prepara la sincronizzazione per campo della 4.7 senza un'altra migrazione |
| `media_store_cursor` (nuova) | `volume` (PK), `ms_version`, `generation`, `deleted_generation`, `scan_epoch`, `scan_resume_after_id`, `last_full_scan_at`, `last_reconcile_at` | atomico con le scritture del catalogo |
| `catalog_meta` (nuova) | `key` (PK), `value` | `change_seq`, `places_dataset` |
| `TimelineIndexDao` | `indexCursor(): Cursor` (visibili, solo copertine dei gruppi, ordinati per `sort_time DESC, media_id DESC`), `indexRows(ids): List<IndexRow>`, `hiddenRows(): List<IndexRow>`, `changeSeq(): Long` | `Cursor` grezzo: niente oggetti per riga |
| `MediaCatalog` | `recordAll(obs: List<Observation>, whenUndecided): List<RecordOutcome>` (una transazione); `confirmPresent(phone: Location.Phone, stamp, seenAt, ignoreGeneration): Boolean`; `markVanishedNotSeenSince(volume, epoch, cap: Int): Int`; `updateMetadata(versionId, metadata, kind: MediaKind? = null)`; `group(kind, members, cover)` / `ungroup(id)`; `val changes: SharedFlow<CatalogChange>` con `CatalogChange(seq, ids: Set<MediaId>, structural: Boolean)` emesso **dopo** il commit | ogni scrittura alza `change_seq`; anche `setPersonalFlags` emette |
| Migrazione | `Migration(1, 2)` + `seedV1` in `SchemaSeeds` | mai distruttiva |

**`core-places` (P4)**
| Elemento | Firma | Note |
|---|---|---|
| Luogo | `data class Place(id: PlaceId, name: String, admin1: String?, countryCode: String, countryName: String, zone: String?)` | `name` in italiano quando esiste (Londra, Monaco di Baviera); `countryName` da `Locale.ITALIAN` |
| Geocoder | `interface ReverseGeocoder { val dataset: Int; fun nearest(p: GeoPoint): PlaceMatch?; fun place(id: PlaceId): Place? }` | `PlaceMatch(place, distanceM, precision: CITY / NEAR / REGION_ONLY / COUNTRY_ONLY)` |
| Fuso | `interface TimeZoneLookup { fun zoneAt(p: GeoPoint): ZoneId? }` | dal luogo più vicino |
| Nomi per la UI | `interface PlaceNames { fun label(ref: PlaceRef): String?; fun summary(top: IntArray, distinct: Int): String }` | "Firenze", "Firenze e Fiesole", "Firenze e altri 2 luoghi" |
| Dati | `object PlacesDataset { fun open(context): ReverseGeocoder }` (estrae, verifica lo SHA-256 fissato nel codice, mappa); `fun openForTest(bytes)` | |
| Attribuzione | `object PlacesAttribution { val text; val licenseUrl }` | CC BY 4.0, con la nota "dati trasformati" |

**`core-metadata` (P3)**
| Elemento | Firma | Note |
|---|---|---|
| Byte | `interface MediaBytes { val size: Long; suspend fun read(range: LongRange): ByteArray; fun dataSource(): MediaDataSource }` | letture a intervalli: in Fase 4 si riusa via SMB |
| Estrazione | `interface MetadataExtractor { suspend fun extract(bytes: MediaBytes, hints: ExtractHints): Extraction }` | `Extraction { Read(metadata, kind, motion: ByteRange?, groupHint: GroupHint?); NotMedia(code); Corrupt(code); Unsupported(code) }` |
| Indizi | `ExtractHints(mime, displayName, relativePath, msDateTakenMs, msWidth, msHeight, msOrientation, msDurationMs, scanZone: ZoneId)` | |
| Date | `object CaptureTimeResolver { fun resolve(c: CaptureTimeInputs, zones: TimeZoneLookup, neighbors: NeighborOffsets?): CaptureTime }` | tabella del §2.2 |
| Nomi dei file | `object FileNameDates { fun parse(name: String): FileNameDate? }` | |
| Sorgente | `object SourceClassifier { fun classify(volume, relativePath, displayName, ownerPackage, mime): Classified(source, traits) }` | |
| Gruppi | `object GroupingRules { fun propose(c: List<GroupCandidate>): List<ProposedGroup> }` | raffiche e RAW+JPEG |
| Segnaposto | `object ThumbHashCodec { fun encode(w, h, rgba): ThumbHash; fun decode(h: ThumbHash, maxSide: Int): IntArray }` | implementazione propria dall'algoritmo pubblico |
| Motion photo | `object MotionPhotoLocator { fun locate(xmp: String?, tail: ByteArray, size: Long): MotionPart? }` | Google v1, MicroVideo, trailer SEF di Samsung |
| Dettagli EXIF | `suspend fun exifDetails(bytes): ExifDetails` (marca, modello, obiettivo, f, tempo, ISO, focale, flash) | **su richiesta**, per il pannello info: non si salvano |
| Video | `interface VideoProbe { fun probe(bytes): VideoFacts(durationMs, rotation, utcMs?, iso6709?, hdr, codecMime) }` | adattatore `MediaMetadataRetriever`, fake su JVM |

**`core-mediastore` (P2)**
| Elemento | Firma | Note |
|---|---|---|
| MediaStore | `interface MediaStoreSource { volumes(); version(v); generation(v): Long?; query(v, sinceGen: Long?, afterId: Long?, limit): List<MediaStoreRow>; deletedSince(v, gen): List<DeletedRow>?; allIds(v): LongArray; open(row): MediaBytes; changes(): Flow<Unit> }` | `AndroidMediaStoreSource` sottile; `FakeMediaStore` in `testing` (generazioni, versioni, rinumerazioni, cestino, accesso parziale, volumi) |
| Accesso | `data class PhoneAccess(media: FULL / PARTIAL / NONE, mediaLocation: Boolean, volumes)`; `interface PhoneAccessReader { val state: StateFlow<PhoneAccess>; fun refresh() }` | mai memorizzato: si rilegge a ogni `onResume` e a ogni giro |
| Sincronizzazione | `interface PhoneLibrarySync { val status: StateFlow<SyncStatus>; suspend fun syncNow(r: SyncReason): SyncReport; fun attach(scope); fun detach() }` | `SyncStatus { Idle(at); Scanning(done, total, full); Paused(reason) }`, motivi: `NO_PERMISSION`, `MEDIA_LOCATION_DENIED` (solo le foto), `STORAGE_LOCKED`, `DISK_FULL` |
| Lavori | `class PhoneSyncRunner { suspend fun run(budget: Duration): RunResult }`; `class MetadataPass { suspend fun run(budget, priority: MetaPriority): RunResult }` | i corpi dei worker di `:app` |
| Priorità | `interface MetaPriority { val visible: StateFlow<List<MediaId>> }` | `:app` la collega alla finestra visibile della griglia |
| Intent | `object PhoneMediaIntents { uriOf(ref); share(refs): Intent; trashRequest(refs, trash: Boolean): IntentSender?; canTrash: Boolean }` | `FLAG_GRANT_READ_URI_PERMISSION` + `ClipData` (Android 17/18) |
| Cestino di sistema | `interface RecentlyDeletedSource { suspend fun list(): List<TrashedItem>; }` | `QUERY_ARG_MATCH_TRASHED`, solo API 30+ |
| Diagnostica | `PhoneLibraryDiagnostics : DiagnosticsContributor` | solo numeri ed enum |

**`core-imaging` (P6)**
| Elemento | Firma | Note |
|---|---|---|
| Chiave | `data class ThumbKey(mediaId: MediaId, versionKey: Long, bucket: ThumbBucket)`; `enum class ThumbBucket(px) { S(160), M(320), L(400) }` | `versionKey` cambia con una modifica, così la cella si aggiorna |
| Caricatore | `interface ThumbnailLoader { fun image(key, ref): State<ImageBitmap?>; fun peek(key): ImageBitmap?; fun window(visible: List<ThumbRequest>, ahead: List<ThumbRequest>); fun trim(level: Int); val stats: StateFlow<ThumbStats> }` | **le celle osservano soltanto**: le richieste le fa la griglia una volta per cambio di finestra. Annulla ciò che esce dalla finestra |
| Sorgenti | `interface ThumbnailSource { suspend fun load(ref: MediaRef, px: Int, cancel: CancellationSignal): Bitmap? }` | `PhoneThumbnailSource` (`loadThumbnail`), `SyntheticThumbnailSource` (gradienti deterministici) |
| Segnaposto | `interface PlaceholderCache { fun image(h: ThumbHash): ImageBitmap }` | decodifica 32×32, cache piccola |
| Viewer | `interface ViewerImageSource { fun state(ref, screen: IntSize): Flow<ViewerImageState>; fun zoomSpec(ref): ZoomSpec?; fun animated(ref): AnimatedSpec? }` | `ViewerImageState { Preview(bitmap); Sharp(bitmap, hdr, colorSpace); Loading(progress?); Unavailable(reason); Failed(code) }` (E-V1, Fase 4 pronta) |

**`core-library` (P5)**
| Elemento | Firma | Note |
|---|---|---|
| Indice | `class TimelineSnapshot` (colonne immutabili: `size`, `seq`, `idAt(i)`, `sortTimeAt`, `localDayAt`, `kindAt`, `sourceAt`, `flagsAt`, `aspectAt`, `durationAt`, `placeAt`, `thumbHashAt`, `groupSizeAt`, `indexOf(id)`) | circa 80 B per elemento |
| Repository | `interface TimelineRepository { val snapshot: StateFlow<TimelineSnapshot?>; suspend fun start(); fun view(f: LibraryFilter): StateFlow<TimelineView> }` | parte dall'istantanea su file, poi riconcilia con `change_seq` |
| Istantanea | `class SnapshotStore(dir: File) { suspend fun read(): TimelineSnapshot?; suspend fun write(s) }` | formato versionato `MCGT1`, scrittura atomica, accorpata a 5 s |
| Preferenze | `interface LibraryPreferences { filter; level; style; suspend fun set...() }` | DataStore in `noBackupFilesDir` |
| Layout | `enum GridLevel { YEARS, MONTHS, DAYS, ALL }`, `enum GridStyle { SQUARE, JUSTIFIED }`, `data class GridSpec(level, style, widthPx, gapPx, densityDp)`; `object LayoutEngine { fun layout(v: TimelineView, s: GridSpec): GridLayout }` | `GridLayout`: `rowCount`, `rowKind(r)`, `rowTop(r)` (somme prefisse), `itemsOf(r)`, `rowOfItem(i)`, `sectionOfRow(r)`, `itemAt(r, x)` |
| Sezioni | `data class SectionInfo(level, firstDay, lastDay, count, topPlaces: IntArray, distinctPlaces: Int)` | |
| Ancoraggio | `object GridAnchors { fun anchorAt(l, row, x, yInRow): Anchor; fun place(l2, a: Anchor): ScrollTarget }` | il pinch e il ritorno dal viewer |
| Scrubber | `object ScrubberModel { fun rowAt(l, fraction); fun fractionOf(l, row); fun label(l, row): YearMonth }` | |
| Selezione | `class SelectionModel { val selected: StateFlow<SelectionSet>; fun toggle(id); fun beginDrag(i, l); fun dragTo(i); fun endDrag(); fun clear() }` | la prima cella del trascinamento decide se seleziona o deseleziona |
| Azioni locali | `interface LocalMediaActions { suspend fun setFavorite(ids, on); suspend fun setHidden(ids, on) }` | sopra `MediaCatalog.setPersonalFlags` |
| Viewer | `data class ViewerRequest(items: ViewerItems, start: MediaId, anchor: ViewerAnchor, secure: Boolean)`; `interface ViewerItems { val view: StateFlow<TimelineView>; fun ref(id): MediaRef }`; `interface ViewerAnchor { fun boundsOf(id): PixelRect?; suspend fun reveal(id); fun thumbKey(id): ThumbKey }` | niente tipi Compose: `PixelRect` è nostro |
| Dati di prova | `testing/SyntheticLibrary(seed, count)` | §5.2 |

**`feature-library` (P7 per la Libreria, P10 per Raccolte, Nascosti ed Eliminati di recente)**
- `@Composable fun LibraryTab(contentPadding, backdropOut: (GlassBackdropState) -> Unit, onOpenViewer: (ViewerRequest) -> Unit, onOpenSettings, statusFooter: @Composable () -> Unit)`;
- `@Composable fun MediaGrid(state: MediaGridState, ...)`: la griglia riusata da tutte le liste. P0 ne scrive la firma e un'implementazione segnaposto;
- `fun NavGraphBuilder.collectionsGraph(nav, onOpenViewer)`;
- `@Composable fun CollectionsTab(...)`.

**`feature-viewer` (P8)**
- `@Composable fun ViewerOverlay(request: ViewerRequest?, onClosed: () -> Unit)`;
- `ViewerViewModel`;
- `MediaDetails` dal grafo del catalogo, più EXIF e luogo su richiesta.

**`core-designsystem` (P7 su `grid/` e `media/`; P8 su `viewer/`; `motion/GalleryMotion.kt` congelato, solo file nuovi accanto)**
- `grid/GalleryGridCell`: cella leggera. Disegno in `drawWithCache`; nessuna animazione fuori dalla selezione e dalla pressione; nessun `fluidPressable` per cella, perché i tocchi li gestisce la griglia; semantica per cella;
- `grid/GalleryScrubber`;
- `grid/GalleryFilterMenu`;
- `grid/GalleryGridLevels.forWidth(widthDp)`;
- `viewer/GalleryViewerChrome`: barre di vetro sulla foto con `galleryTintOnPhoto`;
- `viewer/GalleryVideoBar`;
- `viewer/GalleryInfoSection`.

### 1.3 Aggiunte a moduli esistenti
| Modulo | Aggiunta | Chi |
|---|---|---|
| `core-common` | `DiagSection.PHONE_LIBRARY` | P0 |
| `core-designsystem` | file vuoti `CatalogGridSections.kt` e `CatalogViewerSections.kt` già registrati in `ComponentCatalog.kt` (poi li riempiono P7 e P8) | P0 |
| `feature-diagnostics` | codici, voci e `ExportKeys` della Fase 3 | P11 |
| `feature-settings` | "Informazioni e licenze" (GeoNames CC BY 4.0, telephoto, Media3) | P11 |
| `core-security` | nulla: `SystemCredentialAuthenticator`, `SecureScreen` e `RecentsPrivacy` bastano | — |

### 1.4 Cosa resta in `:app` (P0 lo predispone, P11 lo completa)

**`MainScaffold`**
- La scheda Libreria è **a tutto schermo**: la griglia è la sua lista. La pillola resta sopra, e sopra la pillola c'è l'host di `ViewerOverlay`.
- Raccolte usa `FluidScreen` (o le due colonne sul tablet).
- Le rotte delle griglie filtrate avvolgono la griglia con lo stesso host.

**Il grafo**
- `GalleryDatabase` e `MediaCatalog`, con l'account dal `NasAccountStore` e l'orologio di riferimento: quello del NAS se noto, altrimenti quello del dispositivo.
- La sincronizzazione legata al primo piano: `attach` in `ProcessWatcher`.
- `MetaPriority` collegata alla finestra visibile.

**I lavori WorkManager**
- `PhoneSyncWorker`: trigger di contenuto, si riaccoda dopo ogni giro.
- `LibraryMaintenanceWorker`: una volta al giorno in carica. Fa META residua, hash completi rimandati, riconciliazione degli `_ID`, pulizia delle assenze con tetto e ricalcolo dei luoghi a un dataset nuovo.

**Il resto**
- La richiesta dei permessi dalla Libreria, solo da un tocco dell'utente.
- `onTrimMemory` verso `ThumbnailLoader`.
- Solo in debug, la "Libreria sintetica" con un database **separato** (`gallery-synthetic.db`): non tocca mai quello vero.

## 2. Flussi

### 2.1 Lettura della libreria del telefono (piano 3.1, P2)
| Momento | Cosa | Dettagli |
|---|---|---|
| Avvio del processo (una volta) | Accesso e volumi | `PhoneAccessReader.refresh()`, `getExternalVolumeNames`, `getVersion` per volume. Versione diversa da quella del cursore: **scansione completa** del volume (E-P12, E-P22) |
| Scansione completa | per volume, dai più recenti (`DATE_TAKEN DESC`) a lotti di 500 | Si salva `scan_epoch` e l'ultimo `_ID` fatto: dopo un riavvio si riprende da lì (E-P4, E-P8). Per ogni riga c'è la **scorciatoia del timbro**: stesso posto, stessa dimensione e stessa `DATE_MODIFIED` → `confirmPresent` senza leggere byte (la generazione si ignora se la versione è cambiata). Altrimenti impronta e `recordAll`. A fine volume, **solo con accesso completo**: `markVanishedNotSeenSince(volume, epoch, cap)` |
| Incrementale | righe con `GENERATION_MODIFIED > g` | `g` si legge **prima** della query e si salva dopo il commit: il giro è idempotente. Su API 29 la generazione non c'è e si usa il confronto completo |
| Cancellazioni | `content://media/<volume>/deleted_files` (URI letterale; estensione S ≥ 23) | È solo un'**accelerazione**: ogni riga diventa `markVanished`. La riconciliazione quotidiana confronta gli `_ID` presenti con la cache |
| Volumi | volume sparito → `markVolumeUnavailable`; tornato → righe di nuovo `PRESENT` | E-P6 |
| Osservatori | `ContentObserver` su immagini e video, con i discendenti, accorpamento 1,5 s finché il processo vive; trigger di contenuto WorkManager (ritardo 5 s, massimo 60 s) quando non vive | T-13; obiettivo: modifiche esterne visibili entro 5 s |
| Righe ignorate | `IS_PENDING=1` (fuori dalla query), cestinate, dimensione zero (contate, E-M12), byte iniziali non da media (contati, E-M12) | |
| Impronta | testa e coda da 64 KiB con `setRequireOriginal`; la stessa testa serve all'EXIF. Hash completo solo per `NeedsFullHash` con file sotto i 64 MiB; oltre si usa `KEEP_SEPARATE` e lo completa la manutenzione | Nessuna lettura a file interi durante lo scorrimento |
| Dopo il commit | META con priorità: prima i visibili (`MetaPriority`), poi i più recenti | D11 |

| Edge case | Comportamento in Fase 3 |
|---|---|
| E-P1 | Con accesso parziale: modalità limitata spiegata (banner con "Consenti tutto", richiesta solo da un tocco). La Libreria mostra solo gli elementi accessibili adesso (insieme in memoria dall'ultima query) e **non segna mai assenze**; si rilegge a ogni `onResume` |
| E-P2, E-P10, E-P13 | Il lavoro in background può non partire (sospensione, risparmio energetico, telefono non ancora sbloccato dopo il riavvio): all'apertura l'incrementale per generazione recupera. Prima dello sblocco vale `Blocked(STORAGE_LOCKED)` della Fase 2 |
| E-P3 | Disco pieno: `SQLiteFullException` porta a `Paused(DISK_FULL)`, l'istantanea non si riscrive, la cache in memoria si riduce, nessun crash; avviso in Diagnostica |
| E-P4, E-P8 | Ripresa da `scan_resume_after_id` con lo stesso `epoch`; le scritture del catalogo sono idempotenti |
| E-P5 | `local_day` e l'ora mostrata usano il fuso **dello scatto**. Per le date senza fuso si salva il fuso del telefono **al momento della lettura**: cambiare fuso dopo non sposta nulla |
| E-P6 | Volume `UNAVAILABLE`, mai assente; al rientro le righe si ricollegano allo stesso media (stessa chiave). Su una scheda riformattata (id di volume nuovo) il ricollegamento passa dal contenuto |
| E-P7, E-P17 | La Cartella sicura, lo Spazio privato e le app clonate sono altri utenti: non si vedono. Lo dichiarano Info e Diagnostica |
| E-P9 | Le tolleranze usano l'orologio di riferimento. `purgeExpiredAbsences` gira al massimo una volta al giorno e ha un tetto per giro, così un salto dell'orologio non svuota nulla |
| E-P11 | Cestino di Samsung: la posizione diventa assente, con 60 giorni di tolleranza. Al ripristino `SameFile(reappeared)` restituisce lo stesso media con preferito e nascosto (test). Il messaggio "Recuperala dal cestino di Galleria" arriva con lo stato del backup (5.6, E-B4) |
| E-P12, E-P22 | ID rinumerati: ricollegamento per posto e impronta; nessuna identità e nessuna versione nuova (test con rinumerazione a ID scambiati) |
| E-P14, E-P16, E-P19, E-P20 | Non riguardano la 3.1: niente lavori lunghi, NAS o VPN. Il lavoro di un profilo non attivo si recupera all'apertura |
| E-P15 | Permessi ritirati: `Paused(NO_PERMISSION)`; **mai** interpretato come cancellazione |
| E-P18 | Un solo processo e una sola activity (`singleTask`); il ridimensionamento è gestito in 2.4 |
| E-P21 | Foto nuove "in attesa" (conteggio + spiegazione + richiesta). I video e le foto già note proseguono (scorciatoia del timbro) |
| E-M12 | Zero byte o falso media: ignorati e contati in Diagnostica, mai nella Libreria |

### 2.2 Metadati, date e tipi (3.1, P3)
| Fonte della data (in ordine) | Esito | Note |
|---|---|---|
| EXIF `DateTimeOriginal` + `OffsetTimeOriginal` | `EXACT`, `EXIF_WITH_OFFSET` | `SubSecTimeOriginal` per l'ordine delle raffiche |
| EXIF senza fuso + `GPSDateStamp`/`GPSTimeStamp` | `EXACT`: fuso = ora locale − UTC del GPS, arrotondato a 15 minuti ed entro ±14 h | |
| EXIF senza fuso, con GPS | `OFFSET_ASSUMED`, fuso del luogo all'istante (`TimeZoneLookup`) | |
| EXIF senza fuso e senza GPS | `OFFSET_ASSUMED`, fuso dei vicini (stessa cartella e stesso modello, entro 6 h, con fuso esatto), altrimenti quello del telefono alla lettura | |
| Video: data UTC del contenitore | `VIDEO_UTC`, `OFFSET_ASSUMED`; fuso dal GPS del video (ISO 6709), poi dai vicini, poi dal telefono | E-M14 |
| Nome del file | `FILE_NAME`: `ESTIMATED` se c'è solo il giorno, `OFFSET_ASSUMED` se c'è l'ora | tabella sotto |
| `DATE_TAKEN` del MediaStore | `MEDIASTORE`, `OFFSET_ASSUMED` | EXIF illeggibile |
| Data del file | `FILE_MODIFIED`, `ESTIMATED` | E-M8 |
| **Date assurde** (E-M9) | Vuote o a zero, prima del 1900, a ±1 giorno dall'epoch, oltre l'ora attuale + 48 h; per la data del file anche prima del 1995. La fonte si scarta e si passa alla successiva | inventario: date di file dal 1979 |

| Nome del file | Origine | Data |
|---|---|---|
| `IMG-20240312-WA0001.jpg`, `VID-…-WA…` | WhatsApp | solo il giorno (è la ricezione): `ESTIMATED` |
| `Screenshot_20240312_101530_App.jpg`, `Screenshot_20240312-101530.png` | screenshot | ora locale |
| `Screen_Recording_20240312-101530_App.mp4` | registrazione dello schermo | ora locale, tratto `SCREEN_RECORDING` |
| `20240312_101530.jpg`, `…_001.jpg` | fotocamera Samsung | ora locale |
| `IMG_20240312_101530.jpg`, `VID_…` | AOSP e altri | ora locale |
| `PXL_20240312_101530123.jpg` | Pixel | **UTC** |
| `signal-2024-03-12-101530.jpg`, `photo_2024-03-12_10-15-30.jpg` | Signal, Telegram | ora locale |

| Classificazione | Regola |
|---|---|
| `SCREENSHOT` | `DCIM/Screenshots/`, `Pictures/Screenshots/`, nome `Screenshot_*`; `DCIM/Screen recordings/` con il tratto `SCREEN_RECORDING` |
| `WHATSAPP` | `Android/media/com.whatsapp/…/WhatsApp Images|Video|Animated Gifs/` (anche `Sent/`), WhatsApp Business (`com.whatsapp.w4b`), `OWNER_PACKAGE_NAME`; gli adesivi esclusi |
| `QUICK_SHARE` | `Download/Quick Share/`, `Download/Nearby Share/` |
| `CAMERA` / `OTHER` | `DCIM/Camera/` / il resto |
| Tipo | `RAW_PHOTO` (DNG); `ANIMATED_IMAGE` (GIF, WebP con il blocco ANIM); `MOTION_PHOTO` (XMP `GCamera:MotionPhoto` o `MicroVideo`, trailer SEF `MotionPhoto_Data`); `VIDEO`; altrimenti `PHOTO`. Il tipo definitivo lo scrive `updateMetadata(…, kind)` |
| Tratti | `PANORAMA` (XMP GPano o rapporto ≥ 2,5:1), `SPHERE` (`equirectangular`), `ULTRA_HDR` (`hdrgm:Version`), `HDR_VIDEO` (trasferimento ST2084 o HLG) |
| Gruppi | RAW+JPEG: stessa cartella, stesso nome base, `.dng` + `.jpg`/`.heic`, scatti a meno di 2 s di distanza → copertina il JPEG. Raffica: almeno 2 foto con suffissi `_BURSTnnn`, `_nnn` o `(n)` dello stesso nome base, scatti consecutivi a meno di 1 s, stesso modello → copertina `_COVER` o la prima. **Nel dubbio non si raggruppa** |
| Orientamento | Gli 8 valori EXIF: la decodifica raddrizza sempre, la proporzione scambia i lati per 90°, 270°, 5 e 7 (E-V3, E-M15) |
| File corrotti | `Corrupt(DECODE_FAILED)` porta lo stato di lavoro a `GAVE_UP` dopo 5 tentativi: la cella è "non leggibile", mai bloccante (E-M10) |

### 2.3 Luoghi offline (3.2, P4)
| Aspetto | Scelta |
|---|---|
| Fonti (scaricate a mano, §6) | GeoNames `cities1000.zip` (mondo); `IT.zip` (tutti i `PPL*`/`PPLA*` italiani, compresi i comuni sotto i 1000 abitanti); `admin1CodesASCII.txt`; `alternateNamesV2.zip` (solo le righe `it` dei luoghi tenuti, per i nomi italiani di città e regioni) |
| Strumento | `:core-places:buildPlacesDataset` (JavaExec). Legge da `%USERPROFILE%\.mycloudgallery\geonames\`; lo stesso input dà sempre lo stesso output (SHA-256 nel codice e in `docs/luoghi.md`, con la data del dump) |
| Formato `MCGP1` | Intestazione; luoghi ordinati per cella di griglia di 0,25°, ognuno con lat/lon int32 (1e-5°), offset del nome (int32), regione (int16), paese (int16), fuso (int16), classe e popolazione (int8). Indice delle celle; tabella dei nomi in UTF-8; tabelle di regioni e fusi. Circa 170 mila luoghi, circa 4–5 MB, **circa 2,5 MB compressi nell'APK** |
| Ricerca | Celle vicine ad anello; vince il luogo più vicino fra quelli con almeno 1000 abitanti o capoluogo (`PPLA*`). Precisione `CITY` entro 15 km, `NEAR` entro 50 km, poi solo regione o paese. Antimeridiano e poli gestiti |
| Uso | In META: `place` e fuso del luogo nei metadati; `place_id` derivato in `media`; nuovo dataset = ricalcolo nella manutenzione (100 mila ricerche in meno di 1 s sul dispositivo) |
| Privacy | `PlaceRef` è sensibile come il GPS; nessun log; il luogo non lascia il telefono |
| Verifica | 200 coordinate scritte a mano in due file: `taratura-100.tsv` per tarare e `verifica-100.tsv` cieco, mai usato per tarare. Dentro: centri e periferie (Milano/Sesto San Giovanni), piccoli comuni, isole, coste, rifugi, confini (Como/Chiasso, Gorizia/Nova Gorica, Ventimiglia/Menton, San Marino, Vaticano) ed estero con esonimi (Londra, Parigi, Monaco di Baviera, Lisbona, Oslo). Ogni riga elenca i nomi accettati. **Fatto quando: almeno 95% corretti su 200 e su `verifica-100`** |

### 2.4 Libreria (3.3, P5 + P7 + P10)
| Aspetto | Comportamento |
|---|---|
| Livelli | Anni, Mesi, Giorni, Tutte. Celle quadrate di circa 56, 80, 100 e 130 dp: sul telefono (412 dp) 7, 5, 4 e 3 colonne; sul tablet (1280 dp) 16, 12, 10 e 8 al massimo (`GalleryGridLevels.forWidth`). Con lo stile giustificato l'altezza della riga è la cella del livello |
| Intestazioni | Anni: solo l'anno. Mesi: "Marzo 2024" + luoghi. Giorni: "Oggi", "Ieri", "Martedì 12 marzo" + luoghi. Tutte: nessuna, la data sta nella barra. L'intestazione agganciata è sopra la lista, in vetro (`GalleryTimelineHeader(stuck = true)`), e scivola via quando arriva la successiva |
| Pinch | `GalleryGridPinchState` dà la scala continua, con l'origine nel punto del gesto. Al cambio di livello si prende l'elemento sotto il dito (`GridAnchors`), si calcola la riga nel nuovo layout e si usa `requestScrollToItem` **nello stesso fotogramma**: l'elemento resta sotto il dito. Al rilascio una molla assesta. In alternativa, i segmenti dei livelli in basso (`jumpTo`). I layout dei quattro livelli si precalcolano fuori dal thread principale a ogni nuova istantanea |
| Scrubber | Compare con lo scorrimento veloce o toccando il bordo destro. Mappa frazione ↔ riga con le somme prefisse; mostra una bolla con mese e anno; aptica a ogni cambio di mese; per TalkBack è un cursore regolabile ("Marzo 2024") |
| Torna a oggi | Toccando di nuovo "Libreria", o col pulsante che compare lontano da oggi |
| Ritorno dal viewer | Prima della chiusura `reveal(id)` porta in vista l'elemento mostrato (al centro se era fuori schermo), poi la chiusura va ai suoi bordi |
| Stile | Quadrata o in proporzione (D4), scelta dal menu, ricordata; il cambio di stile resta ancorato |
| Filtro | D16: menu nella barra, ricordato; un filtro senza risultati dà lo stato vuoto con "Mostra tutto" |
| Preferiti e nascosti | Locali (`media_personal`), legati all'identità: sopravvivono a ID, cestino, modifiche e unioni (in un'unione il nascosto vince). Dalla 4.7 si sincronizzano. **Nascosti mai nell'indice né nell'istantanea su file** (E-H1) |
| Selezione | "Seleziona", oppure pressione lunga e trascinamento. In modalità selezione un trascinamento che parte orizzontale (inclinazione sotto i 30°) seleziona, uno verticale scorre. Scorrimento automatico vicino ai bordi (64 dp), velocità proporzionale alla profondità. Barra delle azioni in vetro al posto della pillola: Condividi, Preferito, Nascondi, Elimina (D14) |
| Tocchi | Un solo `pointerInput` sulla griglia (tocco, pressione lunga, trascinamento) con hit test dal layout. La cella premuta cede leggendo lo stato di pressione: si ricompone solo lei |
| Stati | Nessun permesso: `GalleryStateView` + "Consenti accesso". Accesso parziale: banner (E-P1). Foto in attesa: banner (E-P21). Prima lettura: avanzamento in fondo. Libreria vuota: `LibraryEmpty`. In fondo, sotto i più recenti, il riepilogo "N foto, M video" e l'indicatore globale della Fase 2 |
| Raccolte (P10) | Elenco raggruppato di Fluid: Preferiti, Video, Screenshot, WhatsApp, Nascosti, Eliminati di recente. Ogni voce apre una griglia filtrata, con lo stesso `MediaGrid` e lo stesso viewer |
| Nascosti (P10) | Credenziale di sistema a ogni ingresso, con un minuto di tolleranza (E-H3). `SecureScreen` (screenshot bloccati, E-S5) e recenti oscurati. Il viewer aperto da lì ha `secure = true`. Gli elementi si leggono dal database solo dopo lo sblocco |
| Eliminati di recente (P10) | Il cestino di sistema (`MATCH_TRASHED`) con i giorni rimasti e "Ripristina" (dialogo di sistema). Una frase chiarisce che è diverso dal cestino di Samsung Gallery |
| Tablet | Colonne per larghezza; Raccolte a due pannelli; ridimensionamenti, DeX e finestre ricalcolano il layout restando ancorati (E-P18). Lo stato si salva come elemento ancorato + scostamento, mai come indice |
| Accessibilità | Ogni cella ha una descrizione costruita solo quando serve ("Foto, 12 marzo 2024, 10:15, Oslo, preferita") e le azioni "Apri" e "Seleziona". Ci sono segmenti dei livelli, scrubber e testo grande nelle intestazioni |

### 2.5 Viewer (3.4, P8)
| Aspetto | Comportamento |
|---|---|
| Apertura e chiusura | Si parte dalla stessa bitmap della miniatura (`peek`), opaca dal primo fotogramma; la sagoma passa dalla cella allo schermo con `fluidPhysicsClip`. La chiusura può partire da un tocco, dallo swipe giù o dall'Indietro predittivo (`PredictiveBackHandler` guida `progress`) |
| Pager | `HorizontalPager` sulla vista della Libreria (lo stesso filtro). Precarica ±1 alla taglia dello schermo e annulla il resto (E-V5) |
| Immagine | Sequenza `Preview` (miniatura) → `Sharp` (`ImageDecoder` alla taglia dello schermo, orientamento, spazio colore) → zoom a tasselli (telephoto) allo zoom, **senza salti** (E-V1). Il doppio tocco ingrandisce; da ingrandita il pan arriva al bordo e passa al pager |
| Grandi formati | 200 MP: tasselli, mai il file intero. DNG: decodifica intera campionata, lato lungo ≤ 4096 px e tetto da `memoryClass`. HEIC: tasselli dove `BitmapRegionDecoder` li regge, altrimenti decodifica campionata (E-M1–M4) |
| HDR e colore | Ultra HDR: finestra in `COLOR_MODE_HDR` mentre la foto è visibile (API 34+), con la gain map nell'immagine intera; i tasselli possono essere SDR (limite dichiarato). Display P3 conservato (E-V4) |
| Video | Un solo `ExoPlayer`, preparato quando la pagina si ferma. Fino al primo fotogramma si vede la miniatura. Controlli in vetro: play, tempo, barra di scorrimento (`CLOSEST_SYNC` mentre si trascina, esatto al rilascio), audio. HDR su `SurfaceView`. Codec non supportato (8K, APV): "Questo dispositivo non riesce a riprodurre il video" (E-M7, E-M13) |
| Motion photo | Pressione lunga: riproduce l'intervallo trovato da `MotionPhotoLocator` (byte range su `ContentDataSource`). Formato sconosciuto: foto ferma e nota nelle info (E-M5, E-M16) |
| Animati | GIF e WebP animati con `AnimatedImageDrawable` (E-M11); nella griglia restano fermi |
| Chrome | Un tocco mostra o nasconde le barre e passa allo sfondo scuro immersivo (barre di sistema nascoste). Le barre usano `galleryTintOnPhoto` |
| Info | Swipe su (foglio a metà) sul telefono; pannello laterale da 840 dp in su. Contiene: data e ora con il fuso dello scatto ("ora di Oslo, UTC+1") e "stimata" quando serve; luogo, regione, paese, coordinate, "Apri in Mappe" (D18); fotocamera ed EXIF (su richiesta); dimensioni in pixel e MP, peso, formato; posizione sul telefono (cartella). Persone, album e versioni compaiono solo quando esistono (fasi 5, 7 e 8) |
| Menu ⋮ (D13) | Preferito (anche nella barra), Nascondi o Mostra, Condividi (URI del MediaStore con `FLAG_GRANT_READ_URI_PERMISSION` e `ClipData`; la posizione la regola il sistema dell'app che riceve; "rimuovi posizione" arriva con la 5.7), Copia (URI negli appunti, marcato sensibile se nascosto), Elimina dal telefono (D14), Informazioni |
| Stati da Fase 4 | `Unavailable(NAS_UNREACHABLE)`, `Loading(progress)`, avviso dati mobili: costruiti e provati **con sorgenti finte** (Fatto quando della 3.4) |
| Sicurezza | Con `request.secure` (Nascosti) vale `SecureScreen` per tutto il viewer |

| Edge case | Prova |
|---|---|
| E-V1 | Sorgente finta lenta: anteprima subito, sostituzione senza spostamento (stessa geometria) |
| E-V2 | Stato "dati mobili" con la dimensione, sorgente finta (il NAS arriva in Fase 4) |
| E-V3, E-M15 | Otto fixture di orientamento sempre dritte (screenshot) |
| E-V4 | Ultra HDR e video HDR: campo (F12) |
| E-V5 | Fling su 50 elementi lenti: al massimo 3 decodifiche in volo, le altre annullate (contatori) |
| E-V6 | L'elemento sparisce dalla vista (cancellato da Galleria o da un altro dispositivo): si passa al vicino con un breve avviso; con la lista vuota il viewer si chiude |
| E-V7 | Media solo sul NAS: Fase 5 (fuori dalla 3) |
| E-V8 | Ricreazione dell'activity, rotazione, split screen: id corrente e zoom restaurati (`ActivityScenario.recreate`) |

### 2.6 Prestazioni (3.5, P9)
| Misura | Scenario (Macrobenchmark, `CompilationMode.Partial(BaselineProfileMode.Require)`) | Soglia assoluta | Regressione |
|---|---|---|---|
| Fotogrammi in ritardo | Fling e scorrimento su 100 mila elementi, ai 4 livelli e nei 2 stili (`FrameTimingMetric`) | `frameOverrunMs` P99 ≤ 0, cioè meno dell'1% dei fotogrammi oltre gli 8,3 ms | P95 `frameDurationCpuMs` +10% |
| Pinch fra i livelli | 10 pinch continui con UiAutomator | P99 ≤ 0 | +10% |
| Avvio a freddo | `StartupMode.COLD`, 15 ripetizioni; `reportFullyDrawn` alla prima pagina di miniature disegnata o dopo 300 ms | TTFD p90 < 1000 ms | +10% |
| Apertura del viewer | Dal tocco al primo fotogramma con l'immagine (`TraceSectionMetric "MCG:viewerOpen"`) | p90 < 100 ms | +15% |
| Zoom 200 MP sul tablet | Zoom 1:1 e pan sui quattro angoli, 5 cicli | nessun OOM; PSS di picco sotto il tetto calibrato alla prima misura | +15% |

**Strumenti**
- Le soglie stanno in `benchmark/soglie.json`.
- I risultati si confrontano con `tools/bench/compare.ps1`, sia con le soglie sia con il riferimento accettato `docs/bench/riferimento-<dispositivo>.json`.
- `tools\check.ps1 -Bench` esegue le misure a dispositivo collegato. Annota la frequenza dello schermo letta in sola lettura, perché a 120 Hz serve "Fluidità movimento: Adattiva".

**Dati del benchmark**
- La variante `benchmarkRelease` usa un inizializzatore presente **solo nel suo source set**. Se trova un marcatore in `getExternalFilesDir`, riempie un database di 100 mila righe sintetiche che puntano a rotazione ai media reali del dispositivo (decodifica realistica, tutto locale) e lega dei fake di famiglia "pronta", senza rete.
- `ProfileInstallReceiver` torna solo in quella variante.
- `check.ps1` verifica che la release non contenga né quel receiver né le classi `Bench*`.

**Baseline Profile**
- `:app:generateBaselineProfile` gira su S25 (o, in mancanza, sull'emulatore `Medium_Phone`) e copre scorrimento, pinch, apertura del viewer e info.
- Il file si committa. `check.ps1` verifica che l'APK di release contenga `assets/dexopt/baseline.prof` quando il profilo esiste nei sorgenti.

**Percorso d'avvio** (verificato dalla traccia di P9)
- Il grafo della Fase 2 non sta sul percorso critico: parti pigre e nessuna sessione NAS.
- Il database si apre fuori dal thread principale.
- L'istantanea si legge o si mappa in meno di 20 ms.
- Il primo fotogramma usa segnaposto ThumbHash; la riconciliazione con il database avviene dopo il primo fotogramma.

**Budget JVM** (sempre dentro `galleryTest`; contatori e complessità, con tempi solo larghi, 5× l'atteso)
- costruzione e patch dell'indice di 100 mila elementi;
- layout di 4 livelli × 2 stili;
- ancoraggio in O(log n);
- andata e ritorno dell'istantanea;
- 100 mila ricerche di luoghi;
- ricomposizioni: una miniatura che arriva ricompone **una** cella, scorrere di una riga non ricompone le righe ferme;
- `EXPLAIN QUERY PLAN` della query dell'indice senza ordinamento temporaneo.

## 3. Scelte tecniche e dipendenze

### 3.1 Alternative valutate
| Tema | Opzioni | Scelta e motivo |
|---|---|---|
| Caricamento immagini | Coil 3 (Apache-2.0, maturo, Compose), Glide (BSD/Apache, pesante, Compose ancora in beta), pipeline propria (`loadThumbnail`/`ImageDecoder`) | **Pipeline propria** (D5). Coil costa per cella (richiesta, painter, flussi) e non sa nulla della finestra visibile; il suo vantaggio, la rete, qui non serve. Rischio: errori di cache e concorrenza → tempo virtuale nei test, contatori, budget di memoria |
| Zoom e tasselli | telephoto (Apache-2.0), porting di SubsamplingScaleImageView (View, Apache-2.0), tasselli propri | **telephoto avvolto** (D6): Compose nativo, pager, EXIF. Il porting di SSIV vuol dire interop con le View; i tasselli propri restano il piano B |
| Video | Media3 ExoPlayer (Apache-2.0), `MediaPlayer` di piattaforma | **Media3** (D7): scorrimento preciso, HDR, intervalli di byte, errori di codec chiari |
| Geocoding | GeoNames (CC BY 4.0), Natural Earth (pubblico dominio, solo confini), servizi online | **GeoNames compatto** (D8): unica fonte con città, nomi italiani e fusi; i servizi online sono vietati |
| Lista lunga | Paging 3, `LazyVerticalGrid` con span, righe precalcolate in `LazyColumn`, `LazyLayout` proprio | **Righe precalcolate** (D2, D3). Il `LazyLayout` proprio (API sperimentale) è il piano B se la misura M1 non regge |
| Prestazioni | Macrobenchmark + Baseline Profile, microbenchmark su dispositivo, solo test JVM | **Macrobenchmark + profilo + budget JVM** (D21) |
| Screenshot | Roborazzi (già in uso), Paparazzi | **Roborazzi**: stessa catena della Fase 2 |

### 3.2 Dipendenze nuove (le aggiunge solo P0, con checksum rigenerati e diff letto)
| Dipendenza | Modulo | Licenza | Peso nell'APK (stima, dopo R8) | Motivo |
|---|---|---|---|---|
| `androidx.exifinterface` 1.4.x | core-metadata | Apache-2.0 | circa 60 KB, già nel grafo tramite CameraX | EXIF, XMP, HEIF, DNG |
| `androidx.media3:media3-exoplayer` (porta common, container, datasource, decoder, extractor) | feature-viewer | Apache-2.0 | circa 1–1,5 MB | video, motion photo |
| `androidx.media3:media3-ui-compose`, **solo se stabile**; altrimenti `AndroidView(SurfaceView)` | feature-viewer | Apache-2.0 | circa 50 KB | superficie in Compose |
| `me.saket.telephoto:zoomable-image` + `sub-sampling-image` (+ `zoomable`, okio già presente) | feature-viewer | Apache-2.0 | circa 150 KB | zoom e tasselli |
| `androidx.compose.ui:ui-graphics` (versione dal BOM) | core-imaging | Apache-2.0 | già presente | `ImageBitmap` nelle API |
| `androidx.benchmark:benchmark-macro-junit4` 1.4.x, `androidx.test.uiautomator` 2.3.x | `:benchmark` | Apache-2.0 | **mai nell'APK** | misure |
| Plugin `androidx.baselineprofile` 1.4.x | build | Apache-2.0 | — | genera e consuma il profilo |
| Dati GeoNames (non è una dipendenza Gradle) | core-places (asset) | **CC BY 4.0**, con attribuzione | circa 2,5 MB | luoghi offline |
| ThumbHash | core-metadata | implementazione propria dall'algoritmo pubblico | trascurabile | segnaposto sfocati |

Versioni:
- La stabile più recente che compila con **compileSdk 36, AGP 8.13.2 e Kotlin 2.2.20**. Va annotata in `versions-app.gradle` con il perché, come si è fatto per CameraX 1.5.3 e WorkManager 2.11.2: una versione che chiede compileSdk 37 si scarta.
- Niente alpha, beta o rc.

Esclusi di proposito: Coil, Glide, Paging, Accompanist, `media3-datasource-okhttp`, qualunque SDK che parli con Internet.

## 4. Pacchetti di lavoro

### 4.1 Ordine
```
P0 fondamenta e contratti ─┐
P1 modello e database v2 ──┴─┬── P2 lettura del telefono ──────────┐
   (P4 può partire subito)   ├── P3 metadati e date ───────────────┤
                             ├── P4 luoghi ────────────────────────┤
                             ├── P5 indice, layout, selezione ─────┤
                             ├── P6 immagini ──────────────────────┤
                             ├── P7 UI Libreria ── M1 ──┐          ┤
                             ├── P8 viewer ─────────────┼──────────┤
                             ├── P9 prestazioni ◄───────┘ (dopo M1)┤
                             └── P10 Raccolte, Nascosti, cestino ──┴── P11 integrazione ── check.ps1 (-Bench) + security-review
poi: verifica sul campo (§5.5), revisione degli screenshot, chiusura della fase
```
**M1**: P7 consegna la griglia vera (layout, cella leggera, pinch, scrubber) su `SyntheticLibrary` con `SyntheticThumbnailSource`. P9 la misura su S25, oppure sull'emulatore solo per verificare che funzioni.
- Se la soglia dei fotogrammi non regge, si decide **subito** fra il `LazyLayout` proprio e la riga disegnata in un solo nodo.
- Se nessun dispositivo è collegato, si prosegue e la misura passa a P11.

### 4.2 Regole comuni dei brief (oltre a quelle della Fase 2, §4.2)
- **Mai** toccare file fuori dal proprio perimetro. Dipendenze, permessi e file di build condivisi si chiedono all'orchestratore (P0-bis).
- Stringhe in italiano, in un file di risorse per pacchetto (`values/strings_<area>.xml`), per non condividere `strings.xml` fra pacchetti paralleli dello stesso modulo.
- Regole Fluid: nessun valore scritto a mano (`NoHandWrittenStyleTest`), `FluidMotion`, angoli continui, nessuna pagina che sfuma; il vetro sul contenitore. In `core-designsystem` solo il proprio sotto-package.
- Log con `GalleryLog` e `Redacted`: mai nomi di file, percorsi, coordinate, luoghi o id di media. `GeoPoint` e `PlaceRef` sono `@Sensitive`.
- Niente decodifica, I/O o database sul thread principale; niente `runBlocking`. Nessun import di rete (D24).
- Test JVM o Robolectric per ogni modulo. Screenshot in `docs/screenshots/3-<pacchetto>/`: chiaro e scuro, telefono (412 dp) e tablet (1280 dp), testo grande.
- Report: cosa è stato fatto, test, edge case per ID, budget misurati, punti aperti, cosa provare sul campo.

### 4.3 I pacchetti
| ID | Obiettivo | Perimetro esclusivo | Dipende da | Fatto quando | Modello | Telefono |
|---|---|---|---|---|---|---|
| **P0 Fondamenta e contratti** | Scheletro dei moduli, dipendenze, policy, contratti, host in `:app` | `settings.gradle`, `build.gradle` (`galleryModules`), `versions-app.gradle`, `gradle/verification-metadata.xml`, `gradle/verify-release-manifest.gradle`, `tools/check.ps1` (`:benchmark` escluso dal controllo "test eseguiti"; niente `ProfileInstallReceiver` né `Bench*` in release), `app/**` (host e build type `benchmark`), creazione dei 7 moduli e di `:benchmark` (build, manifest, `contract/`, `noop/`, `testing/`, 1 test), stub `MediaGrid`/`ViewerOverlay`/`LibraryTab`, `core-common` (`DiagSection`), stub del catalogo in `core-designsystem`, `docs/sicurezza.md` §4-bis/§8 | — | `check.ps1` verde. Le dipendenze del §3.2 hanno checksum e il diff è stato letto. `READ_EXTERNAL_STORAGE` (max 32) è in policy e nel §4-bis. Test "nessuna rete" (D24). `MainScaffold` con la Libreria a tutto schermo e l'host del viewer sopra la pillola, girando sui fake (griglia sintetica, viewer finto), con screenshot | opus | no |
| **P1 Modello e database v2** | §1.2 `core-model` e `core-database` | `core-model/**`, `core-database/**` (schemi compresi), `docs/modello-dati.md` | — (in parallelo con P0) | `2.json` esportato; `ExportedSchemaTest` 1→2 con `seedV1`; `MergeReferencesTest` aggiornato. Campi derivati provati (giorno locale con fusi diversi, proporzione con 90/270 e orientamenti 5/7, flag). `recordAll` di 500 osservazioni in una transazione. `confirmPresent` e `markVanishedNotSeenSince` con tetto. `changes` emesso dopo il commit con `change_seq` monotono. `EXPLAIN` della query dell'indice senza ordinamento. 100 mila righe lette con `Cursor` (Robolectric) | opus | no |
| **P2 Lettura del telefono** | §2.1 | `core-mediastore/**` | P0, P1 (contratti di P3 e P4 tramite fake) | Con `FakeMediaStore`: prima scansione dai più recenti e ripresa a metà; incrementale; `deleted_files`; versione cambiata con ID scambiati senza identità né versioni nuove (E-P12, E-P22); cestino Samsung con ripristino che conserva preferito e nascosto (E-P11); volumi (E-P6); accesso parziale e permessi ritirati senza assenze (E-P1, E-P15); `ACCESS_MEDIA_LOCATION` negato (E-P21); E-M12; `IS_PENDING`. Adattatore su Robolectric con provider `media` finto (query con `Bundle`, `call` per versione e generazione, `openFile`, `openTypedAssetFile`). Strumentati sull'emulatore se disponibile. `PhoneMediaIntents`. Contributo alla Diagnostica | opus | sì (T-09…T-13) |
| **P3 Metadati e date** | §2.2 | `core-metadata/**`, `tools/fixtures/**`, `docs/fase3-campo.md` | P0 (fake di `TimeZoneLookup`) | Tabella delle date completa su fixture EXIF scritte dai test (E-M8, E-M9, E-M14, E-P5); nomi dei file; sorgenti, tipi e tratti; motion photo (Google v1, MicroVideo, SEF) su file sintetici; raffiche e RAW+JPEG; ThumbHash andata e ritorno; file troncati → `Corrupt` senza eccezioni; HEIC, DNG, PNG, WebP piccoli. Generatore del set di prova (200 MP, date assurde, senza EXIF, corrotti, zero byte, 8 orientamenti, nomi tipo) con i valori attesi in `docs/fase3-campo.md` | sonnet | sì (campioni veri) |
| **P4 Luoghi** | §2.3 | `core-places/**`, `docs/luoghi.md` | P0 | Strumento riproducibile (stesso input, stesso SHA-256); asset sotto i 3 MB compresso; almeno 95% su 200 e su `verifica-100`; 100 mila ricerche in meno di 2 s su JVM; nomi italiani; fusi; confini; attribuzione | sonnet | no |
| **P5 Indice, layout, selezione** | §1.2 `core-library` | `core-library/**` | P0, P1 | `SyntheticLibrary` deterministica da 100 mila. Indice: costruzione, patch dal flusso dei cambiamenti, istantanea andata e ritorno, versione del formato. Layout di 4 livelli × 2 stili entro i budget. Ancoraggio: su 1.000 casi casuali l'elemento sotto il dito resta sotto il dito. Scrubber; filtri; selezione a trascinamento; nascosti mai nell'indice né nel file; copertine dei gruppi; `LocalMediaActions`; `ViewerItems` che segue l'istantanea | opus | no |
| **P6 Immagini** | §1.2 `core-imaging` | `core-imaging/**` | P0 | Pianificatore (priorità, finestra, anticipo nella direzione dello scorrimento, annullamento, tetto di concorrenza) provato con il tempo virtuale; cache a budget con taglio su `trim`; `PhoneThumbnailSource` su Robolectric; decodifica per lo schermo con 8 orientamenti, spazio colore e gain map; animati; `ViewerImageState` con sorgenti lente e irraggiungibili finte (E-V1, E-V5); corrotti (E-M10); budget di memoria scritto nel report | opus | sì |
| **P7 UI Libreria** | §2.4 tranne Raccolte, Nascosti e cestino | `feature-library/.../library/**`, `.../grid/**`, `core-designsystem/.../grid/**`, `.../media/**`, `CatalogGridSections.kt` | P0 (fake di P5 e P6) | M1 consegnato. Griglia, pinch (gesto simulato ancorato), segmenti, intestazioni e intestazione agganciata, scrubber, torna a oggi, filtro e stile ricordati, selezione con scorrimento automatico, barra delle azioni, stati (permessi, parziale, in attesa, prima lettura, vuota), budget di ricomposizione, tablet e finestre (E-P18), `ViewerAnchor`; TalkBack, testo grande; screenshot `3-P7` | opus | sì (fluidità) |
| **P8 Viewer** | §2.5 | `feature-viewer/**`, `core-designsystem/.../viewer/**`, `CatalogViewerSections.kt` | P0 (fake di P5 e P6) | Espansione e chiusura (anche Indietro predittivo) senza sfumare; swipe giù; pager con precaricamento e annullamento; zoom; info (foglio e pannello); video con scorrimento; motion photo; animati; stati con fake; menu a capacità e azioni; E-V1…E-V8 nei test; `SecureScreen` con `secure`; screenshot `3-P8` | opus | sì |
| **P9 Prestazioni** | §2.6 | `benchmark/**`, `tools/bench/**`, `docs/bench/**`, `app/src/benchmarkRelease/**`, `tools/check.ps1` (solo il passo `-Bench` e il controllo del profilo, **dopo** P0) | P0; M1 di P7 | Scenari del §2.6; dataset della variante benchmark; generatore del profilo; `compare.ps1`; `check.ps1 -Bench`; smoke su `Medium_Phone`; prima misura su S25 se collegato | sonnet | sì (obbligatorio) |
| **P10 Raccolte, Nascosti, cestino** | §2.4 (righe P10) | `feature-library/.../collections/**`, `.../hidden/**`, `.../trash/**` | P0 (stub `MediaGrid`), P2 (`RecentlyDeletedSource` tramite fake) | Raccolte (anche a due pannelli); griglie filtrate; Nascosti con credenziale, `SecureScreen` e recenti oscurati; **test che scorre ogni superficie della Fase 3** e non trova mai un nascosto (E-H1); Eliminati di recente con ripristino; screenshot `3-P10` | sonnet | sì |
| **P11 Integrazione app** | §1.4 | `app/**` (P9 conserva `src/benchmarkRelease`), `gradle/verify-release-manifest.gradle` (riceve da P0), `feature-diagnostics/.../catalog/**` ed `export/ExportKeys.kt`, `feature-settings/.../about/**`, `README.md`, `docs/sicurezza.md` (checklist), `docs/decisioni.md` | P0–P10 | Grafo vero; worker; permessi dalla Libreria; `MetaPriority`; Diagnostica con test di catalogo per gli edge case della Fase 3; licenze; test d'integrazione sul MediaStore finto (Libreria popolata, modifica esterna visibile, viewer, nascosto); Libreria sintetica solo in debug con database separato; `check.ps1` verde; APK debug e release | opus | sì |

**Chiusura della Fase 3**
- `check.ps1` verde, e `check.ps1 -Bench` entro le soglie su S25 e su Tab S9;
- F1–F19 superati;
- screenshot approvati dall'utente;
- `security-review` e checklist della Fase 3 in `sicurezza.md` spuntata;
- `docs/android.md` aggiornato con gli esiti dei T-test.

## 5. Test senza dispositivo e piano sul campo

### 5.1 Livelli
| Livello | Dove | Cosa |
|---|---|---|
| JVM puro | core-model, metadata, places, library, imaging (pianificatore) | regole, date, nomi, gruppi, ThumbHash, geocoder, indice, layout, ancoraggio, selezione, budget |
| `FakeMediaStore` | `core-mediastore/testing` | generazioni, versioni, rinumerazioni, `deleted_files`, cestino, accesso parziale, permesso della posizione, volumi, `IS_PENDING`, latenze |
| Robolectric (SDK 35) | Room v2 su 100 mila righe; adattatore MediaStore con un `ContentProvider` finto registrato come `media`; Compose (gesti di pinch, trascinamento, scrubber); ricreazione dell'activity | |
| Roborazzi | feature-library, feature-viewer, catalogo grid e viewer | ogni stato, chiaro e scuro, telefono e tablet, testo grande. Si usa una libreria sintetica di 2.000 elementi per gli screenshot |
| Strumentati su emulatore | `Medium_Phone` (API 37.1) e `Pampa_Tablet` (API 35) | adattatore vero sul MediaProvider: fixture copiate **sull'emulatore** (nessun dato dell'utente), cestino, permessi con `pm grant` e `pm revoke`, smoke del benchmark. I numeri di fluidità valgono solo sui dispositivi veri |
| Dispositivi | S25, Tab S9 | §5.5 |

### 5.2 Libreria sintetica (`core-library/testing/SyntheticLibrary`)
| Parametro | Valore |
|---|---|
| Elementi | 100.000 (seme fisso), 15 anni; giorni "a raffica" (feste, viaggi), giornate vuote |
| Composizione | video 8%, screenshot 10%, WhatsApp 20%, Quick Share 2%; raffiche e RAW+JPEG 1%; preferiti 2%; nascosti 0,5%; date stimate 3% |
| Luoghi e fusi | 60% con luogo campionato da un mini-dataset di prova; viaggi con fuso diverso (UTC−5…+9) e video UTC nello stesso viaggio (E-M14) |
| Proporzioni | 4:3, 3:4, 16:9, 9:16, 1:1, panorami 3:1 |
| Usi | test JVM e budget; screenshot (2.000); database della Libreria sintetica in debug; variante benchmark (righe che puntano a media reali a rotazione) |

### 5.3 Fixture di formato
`tools/fixtures` (P3) genera sul PC:
- un JPEG da 200 MP (Java ImageIO, circa 60 MB);
- date assurde, file senza EXIF, file corrotti, file a zero byte;
- gli 8 orientamenti;
- i nomi di WhatsApp, Screenshot, Pixel, Telegram e Signal;
- motion photo sintetiche (Google v1 e SEF);
- un'immagine WebP animata.

Le prove con i campioni veri (motion photo Samsung, raffiche, panorami, Ultra HDR, DNG da Expert RAW, video 4K HDR10+, video oltre i 2 GB) le scatta l'utente.

### 5.4 Cosa non si prova senza dispositivo
- Latenze di `loadThumbnail` e decodifica HEIC: T-26, nuovo.
- Formato reale delle motion photo Samsung: T-27, nuovo.
- `IS_FAVORITE` scritto da Samsung Gallery (T-28, nuovo): se sì, P11 propone "Importa i preferiti di Galleria", facoltativo.
- Raffiche sull'S25: T-29, nuovo.
- Gain map in Compose e telephoto: T-30, nuovo.
- Cestino e ripristino (T-09), stabilità degli ID (T-10), oscuramento (T-11), accesso parziale (T-12), trigger di contenuto (T-13), limitatore di memoria di Android 17 (T-23).

### 5.5 Verifica sul campo (passi per l'utente)

**A. Preparazione (utente, circa 20 minuti)**
1. Debug USB su S25 e tablet. Su entrambi la build debug ha già finito l'onboarding della Fase 2 con l'account di prova: la Libreria richiede `Ready`.
2. Scattare i campioni veri: 3 motion photo, una raffica, un panorama, una coppia RAW+JPEG (Expert RAW), un video 4K HDR, un video lungo (oltre i 2 GB); ricevere una foto su WhatsApp e una con Quick Share.
3. **Consenso** a copiare sul telefono, con `adb push` + scansione, la cartella `Pictures/MCG-prova/` delle fixture; a fine prove si cancella.

**B. Dal PC (agente)**
- `gradlew :app:installDebug`;
- `check.ps1 -Bench` con un dispositivo per volta;
- `adb shell dumpsys meminfo` e `dumpsys gfxinfo` in sola lettura durante F12.

**C. Prove**
| # | Prova | Atteso |
|---|---|---|
| F1 | Prima lettura sull'S25 (circa 6.700 media) | La Libreria si riempie dai più recenti senza blocchi; tempo totale annotato; la Diagnostica conta ignorati e in attesa |
| F2 | Set `MCG-prova`, contro i valori di `docs/fase3-campo.md` | Data, ora, fuso, "stimata", luogo, tipo e sorgente corretti per ogni file |
| F3 | Samsung Gallery: ruota e "Salva" | Nuova versione, miniatura aggiornata entro 5 s, preferito conservato |
| F4 | Cestino di Samsung Gallery, poi ripristino | Sparisce e torna con preferito e nascosto (E-P11) |
| F5 | Foto, screenshot, WhatsApp, Quick Share nuovi | Compaiono entro 5 s con la sorgente giusta |
| F6 | Permessi su "foto selezionate", poi "tutte" | Modalità limitata, nessuna assenza; poi tutto torna (E-P1) |
| F7 | Revoca e riconcessione dell'accesso alla posizione dei media | Foto nuove in attesa; dopo, elaborate senza versioni nuove (E-P21) |
| F8 | Tablet: microSD tolta e rimessa (se presente) | Elementi non disponibili, poi di nuovo presenti (E-P6) |
| F9 | Fuso del telefono su Tokyo, poi di nuovo Roma | Date e gruppi invariati (E-P5) |
| F10 | Riavvio e `adb install -r` durante la prima lettura | Ripresa senza ricominciare (E-P4, E-P8) |
| F11 | Scorrimento, pinch, scrubber a mano; `check.ps1 -Bench` | Soglie del §2.6 su S25 e tablet |
| F12 | Viewer: apertura, swipe, swipe giù, zoom 200 MP sul tablet, Ultra HDR, video HDR, GIF e WebP, motion photo, raffica, RAW+JPEG, panorama | Nessun OOM (PSS annotata), HDR reso, motion photo riprodotta o spiegata (E-M16) |
| F13 | Rotazione, split screen e DeX con il viewer aperto e la Libreria scorsa | Stato conservato (E-V8, E-P18) |
| F14 | Nascosti | Credenziale richiesta, screenshot bloccato, recenti oscurati, assenti da Libreria, filtri e Raccolte (E-H1, E-S5, E-H3) |
| F15 | Elimina → dialogo di sistema → Eliminati di recente → Ripristina | Il file torna; nessuna cancellazione definitiva fatta dall'app (D14) |
| F16 | Modalità aereo e Wi-Fi spento per tutta la sessione; `dumpsys netstats` dell'app prima e dopo | Tutto funziona; nessun traffico dalle funzioni della Fase 3 |
| F17 | TalkBack e testo al massimo su Libreria, scrubber, viewer, info | Tutto raggiungibile e leggibile |
| F18 | APK di **release** firmato sull'S25 | Libreria, viewer, video e zoom funzionano con R8 |
| F19 | Debug → "Verifica luoghi": 50 foto vere a caso; resta sul telefono, niente esportazione | Almeno 95% dei nomi giudicati corretti dall'utente |

## 6. Cose che richiedono l'utente
| Cosa | Perché | Nel frattempo |
|---|---|---|
| Decisioni D1, D4, D14, D16–D19 (ordine, griglia in proporzione, eliminare in Fase 3, filtri, Raccolte minime, mini-mappa, autoplay) | Cambiano P7, P8, P10 | Valgono le scelte consigliate |
| Approvare le dipendenze del §3.2 e `READ_EXTERNAL_STORAGE` (max 32) | Policy di sicurezza | P0 le aggiunge come "proposta", reversibili |
| Consenso a scaricare i dump GeoNames (circa 200 MB in tutto, da geonames.org) e a committare il file derivato (circa 2,5 MB) | Download e licenza CC BY 4.0 | P4 lavora su un mini-dataset di prova |
| Nuovi edge case E-P21, E-P22, E-M15, E-M16 | Completare il §6 del piano | Già trattati |
| Telefono e tablet collegati, in 120 Hz adattivo | M1, P9, campo | Emulatori per il funzionamento, budget JVM per le regressioni |
| Campioni veri (§5.5 A.2) e consenso alle fixture su `Pictures/MCG-prova/` | E-M5, E-M6, E-M13, E-V4 | Fixture sintetiche su JVM ed emulatore |
| Prove che cambiano impostazioni del telefono (fuso, permessi, microSD, modalità aereo) | F6–F9, F16 | Simulate con i fake |
| Revisione degli screenshot (P7, P8, P10) e giudizio sui luoghi (F19) | Gate grafico e 3.2 | Generati in anticipo |

## 7. Rischi
| Rischio | Effetto | Mitigazione |
|---|---|---|
| 120 Hz con meno dell'1% di fotogrammi in ritardo non raggiunto in Compose | Fatto quando della 3.3 mancato | M1 presto; cella leggera, tocchi a livello di griglia, richieste guidate dalla finestra, `prepareToDraw`, Baseline Profile; piano B: `LazyLayout` proprio o riga disegnata in un solo nodo |
| `loadThumbnail` lento (HEIC, DNG) o senza cache al primo giro | Celle vuote durante lo scorrimento veloce | ThumbHash come segnaposto, anticipo nella direzione dello scorrimento, tetto di concorrenza; T-26; piano B: miniature proprie su disco generate in META |
| Memoria (cache, tasselli, video) e limitatore di Android 17 | OOM o processo chiuso | Budget per `memoryClass`, `onTrimMemory`, un solo player, PSS misurata in F12, `ApplicationExitInfo` in Diagnostica (T-23) |
| telephoto prima della 1.0 o non compatibile con il BOM | Blocco del viewer | `ZoomableMedia` lo avvolge; versione verificata in P0; piano B con i tasselli propri |
| Formati di motion photo Samsung | Parte video non riproducibile | Tre localizzatori, T-27, E-M16 dichiarato |
| Accuratezza dei luoghi su confini e frazioni | Sotto il 95% | Due insiemi (taratura e cieco), regola di precisione, F19 sui dati veri; i confini con poligoni restano un'evoluzione |
| `ACCESS_MEDIA_LOCATION` negato | Foto nuove non visibili | Banner e richiesta (E-P21); mai impronte oscurate |
| Cambio della versione del MediaStore su 100 mila elementi | Ore di letture | Scorciatoia del timbro, ripresa, nessuna assenza prima della fine (E-P22) |
| Ricollegamenti sbagliati (identità) | Preferiti o nascosti sul media sbagliato | Solo `LinkingRules` esistenti ("nel dubbio non si unisce"), test di rinumerazione e cestino; il nascosto vince nell'unione |
| Viewer come sovrapposizione: Indietro, morte del processo, Raccolte | Stato perso o doppio viewer | Host unico per schermata; id corrente nello stato salvato; test di ricreazione |
| Variante benchmark che finisce in release (receiver, fake, dati) | Superficie esposta | Source set dedicato; `check.ps1` su receiver, `Bench*` e `.testing.` nel dex di release |
| Pacchetti paralleli sullo stesso modulo (P7 e P10, P7 e P8 nel design system) | Conflitti | Sotto-package esclusivi, file di stringhe separati, stub e registrazioni fatti da P0, `GalleryMotion.kt` congelato |
| Robolectric fermo all'SDK 35 | Sorprese su 16 e 17 | Strumentati sugli emulatori (37.1 e 35) e prove sul campo |
| Rigenerazione dei checksum | Build rotta | Solo P0 e P11; il diff si legge |

### File cruciali
- `core-database/.../MediaCatalog.kt`, `dao/LibraryDaos.kt`, `entity/MediaEntities.kt`: il catalogo che la Fase 3 estende (v2, `recordAll`, flusso dei cambiamenti, campi derivati).
- `core-model/.../domain/LinkingRules.kt`, `Location.kt`, `MediaMetadata.kt`: identità, posizioni, timbri e date su cui poggia la lettura del telefono.
- `core-designsystem/.../motion/GalleryMotion.kt`, `media/GalleryMediaCell.kt`, `catalog/CatalogMotionSections.kt`: espansione, swipe e pinch già specificati (la demo mostra `driveExternally` e `fluidPhysicsClip`).
- `app/.../main/MainScaffold.kt`, `navigation/AppNavHost.kt`, `graph/LocalData.kt`, `app/src/main/AndroidManifest.xml`: dove si innestano Libreria, host del viewer, lavori e cancellazione locale.
- `tools/check.ps1`, `gradle/verify-release-manifest.gradle`, `versions-app.gradle`: la policy che P0 estende e il passo `-Bench` di P9.
