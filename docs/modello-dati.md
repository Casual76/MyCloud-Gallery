# Modello dei dati (Fase 1.3, schema v2 della Fase 3, schema v3 della Fase 4)

> Obiettivo (piano 1.3): un media ha **identita' stabile**, **piu' posizioni** e **piu' versioni**. Backup,
> spostamenti, condivisioni, modifiche, correzioni e cambi di ID MediaStore non spezzano mai album,
> preferiti, nascosti, vettori o stati di lavoro.
>
> Codice: tipi e regole in `core-model` (Kotlin puro, `dev.pampa.mycloudgallery.core.model` e `.domain`),
> database Room in `core-database` (v1 della Fase 1.3, v2 della Fase 3: §10, **v3** della Fase 4: §11). Il KDoc spiega
> il perche' di ogni tipo; qui c'e' il quadro d'insieme.

## 1. Principi

1. **L'identita' non dipende da dove si trova il file ne' dai suoi byte.** `MediaId` e' un UUID casuale,
   assegnato una volta. Tutto cio' che puo' cambiare (posto, byte, riga MediaStore) cambia *dentro* il media.
2. **Nel dubbio non si unisce.** Unire per errore due media e' il danno peggiore (album, preferiti e soprattutto
   "nascosto" passerebbero all'altro). Tenere separati due media uguali costa un doppione temporaneo.
3. **Le regole sono pure, il database esegue.** `core-model/domain` decide (`LinkingRules`, `VersionRules`,
   `SafetyRules`, `MergeRules`, `WorkRules`, `LocationRules`); `MediaCatalog` applica le decisioni in una
   transazione. Le regole si provano sulla JVM con dati scritti a mano.
4. **Le invarianti stanno in due posti**: nei costruttori del dominio (rileggere una riga incoerente fallisce
   a voce alta) e, dove si puo', nelle chiavi del database.

## 2. Le entita'

```
                 media_alias (alias_id -> media_id)        media_group (raffica, RAW+JPEG)
                        |                                        ^
                        v                                        | group_id
  +--------------------------------------------------------------+------------------+
  | media                                                                            |
  |  media_id (UUID)  kind  source  owner_account  current_version_id               |
  |  [derivati] sort_time  presence  safety                                          |
  |  [derivati v2] local_day aspect_milli duration_ms place_id cell_flags thumb_hash |
  +----+----------------------+-----------------------+-------------------+---------+
       | 1:N                  | 1:1                   | 1:N               | 1:N
       v                      v                       v                   v
  media_version         media_personal           work_state           embedding
   version_id (UUID)     favorite hidden          (media, fase)        (media, spazio+dim+prec, indice)
                         [v2] *_changed_at
   kind ORIGINAL|         [dati personali]         status attempts      vector BLOB, version_id
        EDITED|CORRECTED                           last_error codice
   parent_version_id --+ (catena)                  version_id
   size quick_hash     |                                               album -- album_item --> media
   full_hash  <--------+                                               (personali, minimi in v1)
       | 1:N                  | 1:1
       v                      v
  media_location          version_metadata
   location_key UNICA      taken_at_utc + offset + confidenza + origine
   tipo phone | nas        dimensioni, durata, mime, GPS [sensibile]
                           [v2] specchio, tratti, luogo [sensibile], thumbhash
   phone: volume, percorso, nome, cache _ID + versione MediaStore
   nas:   account, share, percorso
   size, modified_at, state, absent_since, verified_at

  nas_share (account, share, area PRIVATE|COMMON)  <- configurazione di famiglia
  media_store_cursor (volume) [v2]                 <- dove e' arrivata la lettura del MediaStore
  catalog_meta (key, value) [v2]                   <- change_seq, places_dataset
```

- **Media**: l'identita'. Ha una catena di versioni e una versione *corrente* (quella che si vede).
- **Versione**: byte precisi (impronta) nati in un momento preciso. `ORIGINAL` (una sola), `EDITED`
  (Samsung Gallery, editor esterno, PC), `CORRECTED` (correzione dall'app, con i campi corretti).
- **Posizione**: un file in un posto, con i byte di **una** versione. Telefono (MediaStore) o NAS
  (account + share + percorso). Stati: `PRESENT`, `ABSENT` (sparita, con tolleranza), `UNAVAILABLE`
  (contenitore non raggiungibile: microSD tolta, share non accessibile).
- **Metadati**: per versione (una correzione di data crea una versione con metadati nuovi).
- **Stati di lavoro**: per (media, fase) - META, THUMB, ANALYSIS_COPY, EMBED, OCR, FACES.
- **Vettori**: per (media, spazio completo, indice); `version_id` dice da quale versione sono stati calcolati.
- **Dati personali**: preferito, nascosto, album. Separati: non si condividono mai (Fase 4.7).
- **Alias**: le identita' assorbite da un'unione puntano al sopravvissuto.
- **Gruppi**: raffiche e coppie RAW+JPEG sono piu' media (byte diversi, backup distinti) mostrati come uno.

## 3. Invarianti

| Invariante | Dove e' garantita |
|---|---|
| Un media ha almeno una versione e **un solo** originale | `Media` (costruttore) |
| La versione corrente appartiene al media | `Media`; FK composta `media(media_id, current_version_id)` -> `media_version(media_id, version_id)` |
| Genitori e posizioni puntano a versioni del media | `Media`; FK `media_location.version_id`, `media_version.parent_version_id` |
| Un posto contiene un solo file (maiuscole e forma Unicode non contano, E-N7) | `Media`; indice **unico** su `media_location.location_key` |
| Una versione con copie conosciute non si puo' cancellare | FK `media_location.version_id` senza cascata |
| La chiave salvata corrisponde alla posizione | `Mappers` (`check` alla rilettura) |
| `absent_since` c'e' se e solo se la posizione e' `ABSENT` | `MediaLocation` |
| Una verifica vale per i byte che ha visto: si azzera se il timbro cambia o arrivano byte nuovi | `MediaCatalog`, `VersionRules` |
| Vettori di spazi diversi non si mescolano (E-I4) | `EmbeddingSpace` (uguaglianza su modello+dimensione+precisione); PK e query di `embedding` |
| Nessun codice d'errore con testo libero (niente percorsi nella Diagnostica) | `WorkErrorCode` |
| GPS mai nel `toString()` | `GeoPoint` (`@Sensitive`) |
| Ogni tabella che punta a `media` e' gestita dall'unione; ogni tabella e' classificata (punta a `media`, segue le versioni o non contiene identita' di media) | `MergeReferencesTest`, `MediaCatalog.TABLES_WITHOUT_MEDIA_IDS` |
| I campi derivati di `media` sono quelli che dicono le regole, scritti nella stessa transazione | `MediaCatalog` (ricalcolo a fine transazione), `TimelineRules`; `ExportedSchemaTest` verifica che la migrazione scriva gli stessi valori del catalogo |
| Nascosti mai nell'indice della timeline (E-H1) | `TimelineIndexDao`: il nascosto si legge da `media_personal`, non da un campo derivato |
| Un `_ID` del MediaStore si usa per leggere i byte solo con la versione attuale del MediaStore (E-P22) | `TimelineIndexDao.phoneRefs` (unione con `media_store_cursor.ms_version`) |
| `change_seq` sale di uno per transazione che cambia l'indice e si annuncia dopo il commit | `MediaCatalog` (`Mutex`, conteggio nella transazione, emissione dopo) |
| Luogo mai nel `toString()` | `PlaceRef` (`@Sensitive`) |

Tutte le chiavi esterne del grafo sono `DEFERRABLE INITIALLY DEFERRED`: dentro una transazione si scrive in
qualunque ordine, il controllo avviene al commit.

## 4. Impronte e collegamento

**Impronte** (`Fingerprint`): dimensione (gratis), **hash rapido** `qh1` (sempre), **hash completo** SHA-256
(quando qualcuno l'ha calcolato; chi carica lo calcola sempre).

`qh1 = SHA-256("mcg-qh1\0" || dimensione 8 byte BE || testa || coda)`, testa e coda di 64 KiB; fino a 128 KiB
la testa e' tutto il file. E' un **contratto tra dispositivi** (vettori di riferimento nel test, calcolati a
parte): cambiarlo vuol dire introdurre `qh2`. Costa due letture da 64 KiB anche per un video da 9 GB via SMB.
Limite dichiarato e provato: due file della stessa dimensione diversi **solo nel mezzo** hanno lo stesso hash
rapido. Per questo l'hash rapido da solo non unisce mai.

Confronto (`Fingerprint.match`): dimensioni diverse -> diversi; hash completi presenti da entrambe le parti ->
decidono loro; hash rapidi diversi -> diversi; altrimenti **probabilmente uguali**.

**Regole di collegamento** (`LinkingRules`, in ordine):

| Caso | Prova richiesta | Esito |
|---|---|---|
| Stesso posto (stessa chiave), stessi byte | hash rapido uguale | stesso file (`SameFile`); se era assente torna presente (E-P11) |
| Stesso posto, byte diversi, **con continuita'** | NAS: posizione presente. Telefono: stesso `_ID` letto con la stessa `getVersion()`, oppure stessa data di scatto non stimata | nuova versione (`ContentChanged`), o ritorno a una versione nota (`RevertedToVersion`, E-E2: dentro lo stesso media basta l'hash rapido) |
| Stesso posto, byte diversi, senza continuita' | - | la vecchia posizione esce dal suo media (`Replaced`), i byte nuovi si collegano per contenuto |
| Telefono: stesso `_ID` **con la stessa versione del MediaStore**, altro posto | hash rapido uguale | spostamento/rinomina (`Moved`) |
| Posizione sparita dello stesso tipo, stessi byte probabili | **piu'** un indizio indipendente: hash completo uguale, stessa data di modifica, o stessa data di scatto | spostamento (`Moved`, E-N1) |
| Stessi byte in un altro posto | **hash completo uguale** | posizione in piu' (`NewLocation`, E-L1, E-L3, E-B5); altri media identici si uniscono subito |
| Solo hash rapido uguale | - | `NeedsFullHash`: si calcola l'hash completo e si ripete. Se non si puo', media separato (`WhenUndecided.KEEP_SEPARATE`) da unire poi |
| Nient'altro | - | media nuovo |

**Quando NON si unisce**: hash rapido uguale senza hash completo; `_ID` MediaStore uguale ma letto con un'altra
versione del MediaStore (dopo una ricostruzione gli ID ripartono e possono essere di **altri** file: provato
con una rinumerazione di massa a ID scambiati); stesso nome nello stesso posto senza continuita' (es. un
`IMG-...-WA0001.jpg` diverso); piu' media spariti candidati allo stesso spostamento senza hash completo;
originali non dimostrati identici (unione rifiutata); file comparso sul telefono e file sparito dal NAS (non e'
uno spostamento: e' backup o cancellazione).

**Unione** (`MergeRules`): solo con originali dall'hash completo uguale. Sopravvive l'identita' minore
(deterministico: due dispositivi arrivano allo stesso risultato senza parlarsi); il perdente diventa un
**alias**. Versioni con gli stessi byte si fondono, le altre passano al sopravvissuto; la corrente e' la piu'
recente. Preferito **o** nascosto se lo era uno dei due (nel dubbio si nasconde). Album, copertine, vettori
(vince quello della versione corrente) e stati di lavoro (vince il lavoro fatto sulla corrente) passano al
sopravvissuto **prima** che il perdente sia cancellato.

## 5. Versioni, stati di lavoro, "al sicuro"

- Una modifica sposta sulla nuova versione **solo** le posizioni che ne contengono i byte: la copia sul NAS
  resta sull'originale ("dove si trova l'originale?" ha sempre risposta). La nuova versione diventa corrente.
- Fasi da rifare (`VersionRules.stagesToRedo`): se tra vecchia e nuova corrente ci sono solo correzioni di data
  o luogo, **solo META**; altrimenti (pixel cambiati, rotazione, modifica, ritorno all'originale) tutte. I
  vettori vecchi restano finche' non arriva il nuovo (la ricerca non perde il media, E-A10).
- Versioni intermedie (E-E1): ne' originale ne' corrente, opzionali; eliminarle e' una scelta dell'utente.
- **Al sicuro** (`SafetyRules`): ogni versione da proteggere (originale e corrente, tra quelle che esistono
  ancora) ha una copia **presente** nell'**area privata del proprietario**, **verificata** (riletta con SHA-256
  uguale a quello della versione). Stati: `SAFE`, `PRIVATE_COPY_UNVERIFIED`, `ONLY_COMMON_COPY` (E-B13: va
  ricreata la copia privata), `NO_NAS_COPY`, `NOT_MINE`, `LOST` (E-B4). Una share di area sconosciuta non e'
  privata.
- **Libera spazio** (`canFreePhoneCopy`): servono i **byte della copia sul telefono** in una copia privata
  verificata *non prima di un istante dato* (verifica sul momento); con la sola copia comune la risposta e'
  `CREATE_PRIVATE_COPY_FIRST` (E-B14).
- **Proprietario**: telefono -> l'account del dispositivo; NAS -> il proprietario dell'area privata; area comune
  -> nessuno (non si fanno copie private dei file altrui). Un proprietario si acquisisce (es. una share diventa
  privata nella configurazione), **non si perde mai da solo** (un media condiviso resta di chi l'ha condiviso).
- **Tolleranze** (`LocationRules`): telefono 60 giorni (massimo del cestino Samsung), NAS 30 giorni (cestino
  della share, spostamenti visti in due scansioni). Intanto il media non conta come presente li'.

## 6. Database (v1; le aggiunte della v2 sono nel §10)

| Tabella | Chiave | Indici (per quali query) |
|---|---|---|
| `media` | `media_id` | (`sort_time`, `media_id`) timeline a chiave; (`source`, `sort_time`) filtri rapidi; `safety` conteggi "al sicuro"; `group_id` |
| `media_version` | `version_id` | (`media_id`, `version_id`) unico (FK composta); (`size`, `quick_hash`) e `full_hash` collegamento per impronta; `parent_version_id` |
| `media_location` | `location_id` (locale) | `location_key` **unico**; `version_id`; `folder_key` elenchi e scansioni a blocchi; (`volume`, `media_store_id`) cache MediaStore; (`state`, `absent_since`) pulizia |
| `version_metadata` | `version_id` | - |
| `work_state` | (`media_id`, `stage`) | (`stage`, `status`) code di lavoro; `version_id` |
| `embedding` | (`media_id`, `space_id`, `dimension`, `precision`, `vector_index`) | (`space_id`, `dimension`, `precision`, `media_id`, `vector_index`) scansione di uno spazio |
| `media_personal` | `media_id` | `favorite`, `hidden` |
| `media_alias` | `alias_id` | `media_id` |
| `media_group`, `album`, `album_item`, `nas_share` | - | copertine, `media_id`, (`album_id`, `position`) |

- **Campi derivati in `media`** (`sort_time`, `presence`, `safety`): calcolati dalle regole e riscritti da
  `MediaCatalog` nella stessa transazione; servono alla timeline di 100 mila elementi (ordinare e filtrare con
  un indice). `sort_time` e' la data di scatto della versione corrente, o provvisoriamente `DATE_TAKEN`/data del
  file finche' META non la legge.
- **Paginazione a chiave** (`TimelineDao`, `EmbeddingDao.scan`): ogni pagina riparte dall'ultima cella vista,
  costa uguale alla prima e alla millesima e non salta celle quando arrivano media nuovi. Un test controlla con
  `EXPLAIN QUERY PLAN` che la timeline usi l'indice senza ordinare in memoria. Paging 3 non e' tra le
  dipendenze: le query sono gia' nella forma che un `PagingSource` usera'.
- Enumerazioni salvate con **codici stabili** (`StableCode`), mai `name` o `ordinal`; hash in esadecimale
  minuscolo; istanti in millisecondi (ora di riferimento fornita da chi scrive).

## 7. Migrazioni

Lo schema di ogni versione e' esportato in `core-database/schemas/<classe>/<N>.json` (sotto controllo di
versione). `ExportedSchemaTest` (Robolectric, nessuna dipendenza in piu'):

1. verifica che esistano gli schemi da 1 alla versione corrente, senza buchi;
2. crea un database **dal JSON della versione corrente** e controlla che coincida con quello che Room crea oggi
   (nessuna modifica di schema senza alzare la versione);
3. per **ogni** versione esportata crea il database come l'avrebbe creato quella versione dell'app, ci mette dati
   realistici in SQL grezzo (`SchemaSeeds`, con chiavi e codici scritti a mano), lo apre con l'app di oggi (Room
   migra e valida lo schema risultante), controlla `integrity_check`, `foreign_key_check` e che i dati ci siano
   ancora (media, versioni, posizioni, "al sicuro", metadati, preferito, album, stati, vettore);
4. verifica che un database di una versione futura non si apra e **non venga cancellato**.

Mai `fallbackToDestructiveMigration`. Per una nuova versione N: alzare `GalleryDatabase.VERSION`, cambiare le
entita' (il build esporta `N.json`), scrivere `Migration(N-1, N)` in `GalleryMigrations.ALL`, aggiungere
`seedVN` in `SchemaSeeds` (lasciando gli altri). Il test copre 1 -> N da solo.

## 8. Scelte e alternative scartate

| Scelta | Alternative scartate e perche' |
|---|---|
| `MediaId` UUID casuale | **Hash del contenuto**: non esiste finche' non si legge tutto il file e cambia a ogni modifica. **`_ID` MediaStore**: non e' un'identita' (`docs/android.md`, scoperta 7). **Percorso**: cambia con spostamenti e condivisioni. |
| Posizione -> versione (non -> media) | Posizione -> media: dopo una modifica non si saprebbe piu' quale copia contiene l'originale. |
| Una tabella per le posizioni | Due tabelle (telefono/NAS): le domande vere sono trasversali ("dove sono le copie di questa versione?"). |
| Unione con alias | **Riassegnare le chiavi** in tutte le tabelle: fragile e invisibile ai riferimenti esterni. **Mai unire**: doppioni permanenti (E-L3). |
| Raffica e RAW+JPEG come gruppi | Come un `MediaKind`: "al sicuro" e cancellazione di un file coprirebbero l'altro. |
| Dati personali in tabelle separate | Colonne in `media`: si mescolerebbero con l'indice condiviso della Fase 4. |
| Campi derivati in cache in `media` | Vista SQL o calcolo al volo: join su versioni, posizioni e metadati per ogni cella della timeline. |
| Hash rapido SHA-256 di testa+coda | xxHash o simili: una dipendenza in piu' per pochi ms risparmiati su 128 KiB. Solo dimensione+data: falsi positivi troppo facili. |
| Codici stabili per gli enum | `ordinal`/`name` (default di Room): un riordino o una rinomina rileggono male i dati in silenzio. |
| Infrastruttura di migrazione Robolectric sul JSON esportato | `MigrationTestHelper` di `room-testing`: e' solo in `androidTestImplementation` (servirebbe un dispositivo) o andrebbe aggiunto ai test JVM; il test scritto a mano usa lo stesso JSON e la stessa validazione di Room. |
| Chiave esterna composta senza indice dedicato | Indice (`media_id`, `current_version_id`): `media_id` e' gia' la chiave primaria; megabyte in piu' senza guadagno (avviso di Room soppresso con motivazione). |

## 9. Rimandato e punti aperti

- ~~**Identita' tra dispositivi (Fase 4)**: chi pubblica per primo un media nell'indice condiviso ne fissa il
  `MediaId`; gli altri lo adottano.~~ Fatto nella v3 (`IdentityRules`, `MediaCatalog.adoptIdentities`, §11.5).
- **Rilevatore di doppioni**: chi chiama `merge` quando un hash completo arriva dopo (oggi lo fa da solo solo una
  terza copia identica). Dalla v3 lo fa il catalogo per i gruppi `PROBABLE_DUPLICATE` telefono-NAS (§11.4); fra due copie
  dello stesso lato resta aperto (Doppioni, Fase 7).
- ~~**Cursori di sincronizzazione MediaStore** (generazione per volume, `getVersion()`): Fase 3.1.~~ Fatto nella v2
  (`media_store_cursor`, §10).
- **OCR, FTS, volti**: tabelle nelle Fasi 6 e 8 (i volti nell'area privata, proposta 13). Il test delle
  referenze obblighera' ad aggiungerle all'unione.
- **Album completi, album smart e condivisi**: Fase 7, con migrazione.
- **Dimensione dei vettori** (MRL) e risoluzione delle anteprime: misure 0.3/6.1 ancora aperte.
- **Dove vivono le versioni precedenti sul NAS** (punto aperto 5.4): il modello le tratta come posizioni
  qualsiasi della versione.
- **Pulizia dei media senza posizioni** dopo la tolleranza: oggi restano (con identita' e riferimenti); la
  politica si decide con la Fase 3.1/5.5.
- **"Libera spazio" con media in errore o in caricamento** (5.5): il controllo sugli stati di lavoro si aggiunge
  quando esistera' lo stato del backup (5.2).

## 10. Schema v2 (Fase 3: la galleria del telefono)

Progetto: `docs/fase3-progetto.md` §1.2 (tabelle `core-model` e `core-database` di P1), §2.1, §2.2, §2.4.

### 10.1 Tipi nuovi in `core-model`

| Tipo | Cosa | Perche' |
|---|---|---|
| `MediaTrait` | panorama, sfera, Ultra HDR, video HDR, registrazione dello schermo; codice **e bit** stabili | metadati della versione, salvati come maschera (`version_metadata.traits`) |
| `ExifOrientation`, `MediaMetadata.mirrored` | gli 8 valori EXIF come rotazione + specchio (come `getRotationDegrees`/`isFlipped`) | E-M15: 5 e 7 scambiano i lati come 90 e 270 |
| `MediaMetadata.traits`, `.place`, `.thumbHash` | per versione, come la data | una modifica che ritaglia un panorama ha metadati suoi |
| `PlaceRef(geonameId, dataset)` | luogo del dataset offline, `@Sensitive`, `toString` muto | deriva dal GPS: e' sensibile come il GPS |
| `ThumbHash` | Base64 standard canonico, al massimo 48 caratteri (36 byte); nel database i byte | segnaposto del primo fotogramma (D5) |
| `MediaRef` | `Phone(volume, mediaStoreId, mime)`, `Synthetic(seed, w, h)` | come arrivare ai byte adesso; dalla Fase 4 `Nas` |
| `CellFlags` | bit stabili: modificata, data stimata, non leggibile, luogo, motion, panorama, Ultra HDR, preferito, membro di gruppo, copertina di gruppo | badge e filtri dall'indice senza join |
| `FileStamp.looksUnchangedFrom(previous, ignoreGeneration)` | dopo un cambio di `getVersion` la generazione si ignora | E-P22 |
| `domain/TimelineRules` | giorno locale, proporzione mostrata, flag della cella, copertina dei gruppi | regole pure dei derivati, usate dal catalogo **e** dalla migrazione |

### 10.2 Tabelle e colonne

| Dove | Aggiunta | Note |
|---|---|---|
| `media` (derivati) | `local_day` (aaaammgg nell'ora del posto), `aspect_milli` (mostrata, x1000, default 1000), `duration_ms`, `place_id`, `cell_flags`, `thumb_hash` (BLOB) | l'indice della timeline si legge da **una** tabella; NOT NULL con DEFAULT perche' `ALTER TABLE ADD COLUMN` lo vuole |
| `version_metadata` | `mirrored`, `traits` (maschera), `place_id`, `place_dataset`, `thumb_hash` | vuoti finche' META (Fase 3) non li legge |
| `media_personal` | `favorite_changed_at`, `hidden_changed_at` | sincronizzazione per campo della 4.7 senza un'altra migrazione |
| `media_store_cursor` (nuova) | `volume` (PK), `ms_version`, `generation`, `deleted_generation`, `scan_epoch`, `scan_resume_after_id`, `last_full_scan_at`, `last_reconcile_at` | si scrive nella stessa transazione delle osservazioni |
| `catalog_meta` (nuova) | `key` (PK), `value` (intero) | `change_seq`, `places_dataset` |

**Giorno locale** (E-P5, E-M14): con la data letta, il giorno nell'ora del posto dello scatto (`CaptureTime.offsetMinutes`).
Prima di META, il giorno provvisorio nel fuso del telefono **alla prima lettura**, salvato una volta e mai ricalcolato
con il fuso di oggi: cambiare fuso dopo non sposta nulla. **Flag**: `EDITED` se la corrente non e' l'originale,
`DATE_ESTIMATED`, `UNREADABLE` (META in `GAVE_UP`, E-M10), `HAS_PLACE`, `MOTION`, `PANORAMA`, `ULTRA_HDR`, `FAVORITE`
(copia del dato personale, solo per la cella). **Gruppi** (D20): nell'indice resta chi rappresenta il gruppo, cioe' la
copertina se si vede, altrimenti il membro visibile piu' recente (una coppia RAW+JPEG con il JPEG cancellato resta
con il DNG); gli altri hanno `GROUP_MEMBER` e stanno fuori.

### 10.3 Indice della timeline (`TimelineIndexDao`)

- `indexCursor(): Cursor`: tutti i media **visibili** (presenti da qualche parte, non nascosti, non `GROUP_MEMBER`)
  in ordine `sort_time DESC, media_id DESC`, con le colonne di `COLUMNS`; nessun oggetto per riga. 100 mila righe
  in circa 0,8 s su JVM (Robolectric); il budget del test e' 8 s.
- `EXPLAIN QUERY PLAN`: `SCAN m USING INDEX index_media_sort_time_media_id` piu' una ricerca per chiave su
  `media_personal`, **senza** `USE TEMP B-TREE`, con e senza `ANALYZE` (`TimelineIndexTest`).
- `indexRows(ids)` (al massimo 500 per chiamata; `MediaCatalog.indexRows` divide), `hiddenRows()` (solo dopo lo
  sblocco), `groupSizes()`, `phoneRefs(ids)`, `changeSeq()`.
- Il nascosto si legge da `media_personal` (unione per chiave primaria), **non** da un bit derivato: e' l'unico
  flag che non puo' essere in ritardo (E-H1).

### 10.4 Operazioni nuove di `MediaCatalog`

| Operazione | Cosa garantisce |
|---|---|
| `recordAll(obs, whenUndecided, cursor?, seenAt?)` | un lotto in **una** transazione e un solo cambiamento; o tutto o niente, cursore compreso; `seenAt` = inizio della scansione |
| `confirmPresent`, `confirmAllPresent` | scorciatoia del timbro: stesso posto, stessa dimensione e data (e generazione, salvo `ignoreGeneration`), posizione presente o non raggiungibile; aggiorna "visto a", timbro e cache del MediaStore senza leggere byte. Mai su una posizione assente |
| `markVanishedNotSeenSince(volume, epoch, cap)` | a fine scansione completa: assenti le posizioni del volume non viste dal giro, **al massimo `cap` per chiamata** (le viste meno di recente prima); chi chiama ripete finche' torna `cap` |
| `purgeExpiredAbsences(cap)` | pulizia delle assenze scadute con tetto per giro (E-P9) |
| `updateMetadata(versionId, metadata, kind?)` | il tipo definitivo di META vale solo se la versione e' ancora la corrente |
| `group(kind, members, cover)`, `ungroup(id)` | raffiche e RAW+JPEG; un gruppo rimasto con un membro solo si scioglie |
| `setPersonalFlags(id, flags)` e per una selezione | istante del cambio per ogni flag; nascondere o mostrare e' un cambiamento strutturale |
| `saveMediaStoreCursor`, `mediaStoreCursor(s)`, `setPlacesDataset`, `placesDataset` | stato della lettura e del dataset dei luoghi |
| `phoneRefs(ids)` | `MediaRef.Phone` solo per copie presenti con l'`_ID` della versione attuale del MediaStore |
| `changes: SharedFlow<CatalogChange>` | vedi sotto |

**Cambiamenti.** Ogni scrittura pubblica e' una transazione: alla fine i derivati dei media toccati (e dei loro
gruppi) si ricalcolano e, se l'indice e' cambiato, `catalog_meta.change_seq` sale di uno; **dopo il commit**
`changes` emette `CatalogChange(seq, ids, structural)`. `structural` vuol dire che e' cambiato l'insieme o l'ordine
(media nuovi, spariti, nascosti, uniti, raggruppati, data o giorno); altrimenti solo attributi (preferito,
proporzione, segnaposto, presenza, riferimento ai byte). Le scritture sono in fila (`Mutex`), quindi i `seq` escono in
ordine. Il flusso non trattiene chi resta indietro oltre 256 cambiamenti: un salto di `seq` vuol dire "ricostruisci
l'indice". Una scrittura del catalogo dentro un'altra transazione fallisce, perche' l'annuncio arriverebbe prima del
commit vero. Un cambio di `ms_version` di un volume annuncia tutti i media con copie su quel volume: i loro
riferimenti ai byte scadono. Le scritture che non toccano l'indice (vettori, stati di lavoro diversi da META, cursore
senza cambio di versione, solo "visto a") non alzano `change_seq`.

### 10.5 Migrazione 1 -> 2

`GalleryMigrations.migration1To2`: aggiunge le colonne (mai distruttiva), inizializza `*_changed_at` con
`updated_at`, crea `media_store_cursor` e `catalog_meta`, e **calcola i derivati dei media esistenti con le stesse
`TimelineRules` del catalogo** (giorno dalla data letta o, se manca, dal fuso del telefono adesso; proporzione;
flag; ruolo nel gruppo). `ExportedSchemaTest` apre la v1 con `seedV1`, migra, verifica i dati e che un ricalcolo del
catalogo non cambi nulla; apre la v2 con `seedV2`. Su API 29 SQLite e' la 3.22: niente `UPSERT`, `RETURNING`,
`RENAME COLUMN` o `DROP COLUMN` nelle query e nelle migrazioni.

### 10.6 Scelte e scarti della v2

| Scelta | Alternative scartate e perche' |
|---|---|
| Derivati dell'indice in `media` | Join con versioni e metadati a ogni lettura dell'indice: tre tabelle per ognuna delle 100 mila righe. |
| Nascosto da `media_personal` nella query dell'indice | Bit derivato in `cell_flags`: piu' semplice, ma un ritardo mostrerebbe un nascosto (E-H1). Il join e' per chiave primaria e non ordina (piano verificato). |
| Copertina effettiva calcolata (`GROUP_MEMBER`) | Solo `media_group.cover_media_id` nella query: una copertina cancellata o nascosta farebbe sparire tutto il gruppo. |
| Cursore del MediaStore nel database | DataStore: non atomico con le scritture del catalogo, la ripresa potrebbe saltare o ripetere un lotto. |
| `change_seq` nel database, flusso con perdita dichiarata | Flusso senza perdita: uno scrittore bloccato da un collector lento. `seq` solo in memoria: l'istantanea su file non saprebbe se e' vecchia. |
| `mirrored` in `version_metadata` (colonna in piu' rispetto al progetto) | Codici EXIF al posto dei gradi in `orientation`: avrebbe cambiato il significato di una colonna della v1. |
| `traits` e `*_changed_at` NOT NULL DEFAULT 0 | Colonne nullable: un valore "sconosciuto" in piu' da gestire, senza guadagno. |

## 11. Schema v3 (Fase 4: il NAS nella Libreria)

Progetto: `docs/fase4-progetto.md` §1.3 (righe `core-model`, `core-database`, `core-metadata` di P2), §2.1-§2.3, §2.5, §2.7,
D5-D8, D13, D18, D21. Pacchetto P2.

### 11.1 Tipi nuovi o estesi in `core-model`

| Tipo | Cosa | Perche' |
|---|---|---|
| `WorkStage.HQ`, `WorkStage.FULL_HASH` | codici `hq`, `full_hash`; **non** fra le fasi iniziali (`WorkRules.initialStages`) | `FULL_HASH` lo mette in coda il catalogo (gruppi provvisori, D21) o chi verifica "al sicuro"; `HQ` chi prepara |
| `Location.Nas.fileId` (P0) + `stampOf`, `folderKey(account, share, folder)`, `shareKeyOf` | il `FileId` SMB come indizio, la chiave di una cartella senza posizioni finte | scansione per cartelle (E-N5), confronto con il catalogo |
| `LinkingRules` | spostamento sul NAS corroborato anche dallo **stesso `FileId` nella stessa share** | E-N1: il PC puo' cambiare la data di modifica di un file che sposta |
| `TimelineRules.presenceFlags` | `ON_PHONE`, `ON_NAS` (posizioni **presenti**), `SAFE` (stato `SAFE`), `TO_VERIFY` (gruppo `PROBABLE_DUPLICATE`); anche dai derivati salvati (`presence`, `safety`) per la migrazione | badge della cella dai soli flag (D18, §1.5) |
| `domain/IdentityRules` con `RemoteIdentity`, `AdoptionDecision`, `AdoptRefusal` | l'adozione delle identita' dell'indice (D7), §11.5 | regola pura, provata sulla JVM |

`ContentKey`, `NasStamp`, `MediaRef.Nas`, `FreezeReason`, `MediaGroupKind.PROBABLE_DUPLICATE`, i bit 10-13 di `CellFlags` e
`PresenceBadge` sono di P0 (firme); P2 li riempie.

### 11.2 Tabelle e colonne

| Dove | Aggiunta | Note |
|---|---|---|
| `media_location` | `file_id` (NULL); indice (`account_id`, `share_key`, `last_seen_at`) | timbro del NAS (`NasStamp`); fine giro, conteggi del congelamento e share non raggiungibile senza ordinare in memoria |
| `work_state` | l'indice (`stage`, `status`) diventa (`stage`, `status`, `updated_at`) | i fallimenti da riprovare escono gia' in ordine |
| `media.cell_flags` | bit di presenza (D18) | derivati come gli altri, ricalcolati nella stessa transazione |
| `nas_scan_state` (nuova) | PK (`account_id`, `share_key`): `epoch`, `resume_after`, `last_full_scan_at`, `freeze_*` | il cursore della porta `NasScanStore`; si salva con le osservazioni |
| `index_publish_state`, `index_own_segment`, `index_outbox_record`, `index_outbox_blob` (nuove) | la pubblicazione di **questo** dispositivo per share: `next_seq` (D5), ultima testa, segmenti propri, outbox dei record (JSON canonico) e indice dei blob in coda (i byte stanno nei pacchi temporanei) | P3 (`IndexPublisher`): niente si perde se il processo muore |
| `index_head`, `index_segment`, `index_record`, `replica_pack`, `replica_blob`, `index_sync_state` (nuove) | la replica: `HeadMark` per (share, dispositivo), segmenti elencati e loro stato, record `meta` (colonne per le query e JSON intero), pacchi immutabili (D15) e i loro blob, giorni di accesso negato (E-I8) | P4 (`IndexSync`, `ReplicaBlobs`, `ReplicaRecords`) |
| `nas_op`, `nas_op_item`, `nas_trash_entry` (nuove) | il diario delle operazioni sul NAS e quello locale del cestino (4B, §2.7) | P12: ogni passo si scrive prima di farlo |

Regole delle tabelle nuove: `share_key` e' la forma di confronto (`Location.Nas.shareKeyOf`); le classi dei segmenti sono
il `SegmentClass.code` del formato (1 `meta`, ..., 6 `frames`, 16 `personal`); impronte e chiavi in esadecimale; la chiave
di contenuto ridotta (`ContentKey.short`) e' un `INTEGER`; gli istanti dell'indice e del diario sono in **ora del NAS**.
Nessuna punta a `media` o a `media_version`: le identita' nei record dell'indice sono **dati firmati** (colonne `remote_*`,
tabelle `TABLES_WITH_INDEX_IDS`), non riferimenti locali, e l'unione non le tocca; `MergeReferencesTest` lo controlla.

I DAO (`NasScanDao`, `IndexPublishDao`, `ReplicaDao`, `NasOpsDao`, `IdentityDao`) sono semplici: righe dentro e fuori. Le
regole sono di chi li usa (P3, P4, P12) e gli adattatori sopra i DAO li scrive P11; il grafo dei media lo scrive sempre e
solo `MediaCatalog`.

### 11.3 Operazioni nuove di `MediaCatalog`

| Operazione | Cosa garantisce | Porta |
|---|---|---|
| `recordAll(obs, whenUndecided, nasCursor, seenAt = epoch)` | come per il telefono: un lotto e il cursore in **una** transazione; con `KEEP_SEPARATE` i gruppi provvisori (§11.4); `inSameTransaction` per chi deve scrivere altro nella stessa transazione (P4) | `NasScanStore.recordAll` |
| `nasScanState`, `nasScanStates`, `saveNasScanState` | il cursore e il congelamento | `cursor`, `saveCursor` |
| `knownNasFiles(account, share, folder)` | i file noti (non assenti) di una cartella, per chiave di posto, con il `NasStamp` | `knownIn` |
| `countPresentNas(account, share)` | i presenti di una share | `countPresent` |
| `confirmAllPresentNas(seen, seenAt, cursor)` | scorciatoia del timbro (`NasStamp.sameAs`): "visto a", stato, nome com'e' scritto e `FileId` appena letto; mai su un assente | `confirmAllPresent` |
| `markVanishedInShare(account, share, epoch, cap, cursor)` | assenze con tetto, le viste meno di recente prima; **0 se la share e' congelata** (D10, difesa anche se chi chiama sbaglia) | `markVanishedInShare` |
| `markShareUnavailable(account, share, cap)` | presenti diventano non raggiungibili, a tappe (si ripete finche' torna `cap`) | `markShareUnavailable` (ciclo nell'adattatore) |
| `adoptIdentities(claims)`, `adoptIdentity` | D7 in una transazione (§11.5) | P4 |
| `nasRefs(ids)`, `nasLocation(locationId)` | `MediaRef.Nas` (posizione presente, prima la versione corrente; chiave di contenuto ridotta); il percorso solo su richiesta | P7, P8 |
| `pendingWork(stage, place, limit)`, `retryableWork`, `requestWork(ids, stage)` | code di lavoro per posto (`WorkPlace.PHONE`, `WorkPlace.NAS`), dal piu' recente; messa in coda di `HQ` e `FULL_HASH` | P6, P9, P11 |

**Code del telefono filtrate.** `WorkStateDao.nextPending` e `retryable` (le code che legge la fase META del telefono)
restituiscono solo media con una copia presente sul telefono (`presence`). Prima, 20 mila media solo-NAS con META in coda,
piu' recenti, arrivavano per primi e la fase META li saltava fino a `metaMaxSkips` (2.000), poi si fermava. Le code del NAS
sono `nextPendingOnNas` e `retryableOnNas`. Il piano: `media` scorsa con l'indice (`sort_time`, `media_id`) e la fase
cercata per chiave primaria (`CROSS JOIN` fissa l'ordine), nessun ordinamento in memoria; a coda vuota la lettura scorre
l'indice intero (100 mila righe, decine di ms), prezzo accettato per non ordinare 100 mila righe a ogni lotto della prima
lettura. Provato in `V3QueriesTest` (20 mila media solo-NAS: 0 salti).

### 11.4 Gruppi `PROBABLE_DUPLICATE` (D21, E-L9)

- **Quando**: con `KEEP_SEPARATE`, un file che le regole rimandano all'hash completo (`NeedsFullHash`) nasce separato; se fra
  i candidati c'e' un media **dall'altra parte** (telefono e NAS), il nuovo e quel media (con gli eventuali membri del loro
  gruppo provvisorio) diventano un gruppo `PROBABLE_DUPLICATE`. Copertina: il membro con una copia presente sul telefono.
  Un candidato in una raffica o in una coppia RAW+JPEG non si sposta (meglio un doppione temporaneo).
- **Cella**: una sola, la copertina, con `TO_VERIFY` (badge "da verificare"); gli altri membri sono `GROUP_MEMBER`.
- **Lavoro**: `FULL_HASH` in coda (sul NAS) per i membri con una copia sul NAS senza hash completo; quello del telefono lo
  calcola la manutenzione degli hash rimandati della Fase 3 (`deferredFullHashes` lo trova: stessa impronta rapida di un
  altro media).
- **Risoluzione**: quando un hash completo si impara (`learnFullHash`), la `FULL_HASH` di quella versione e' fatta e il
  gruppo prova a risolversi: con **tutti** gli originali noti, chi ha gli stessi byte si unisce (`MergeRules`, sopravvive il
  minore), poi il gruppo si scioglie e chi ha byte diversi torna una cella a se'. Mai un'unione senza hash completo.

### 11.5 Adozione delle identita' (D7, E-I18)

`IdentityRules.decide(locale, posto, remoto, mediaRemotoQui, proprietarioDellaVersioneRemota)`:

| Caso | Esito |
|---|---|
| Il record non descrive i byte del posto (dimensione o `qh1` diversi) | `Refuse(CONTENT_MISMATCH)`, non e' un conflitto |
| La versione remota e' qui con un contenuto diverso, o il media remoto e' qui e nessuna sua versione e' compatibile | `Refuse(ID_BOUND_TO_OTHER_CONTENT)`: **conflitto D7**, contato |
| La versione remota e' qui in un altro media rispetto a quello dichiarato | `Refuse(ID_BOUND_ELSEWHERE)`: conflitto |
| Il media remoto e' qui un altro media con byte compatibili | `MergeWith`: unione con `MergeRules` (hash completi degli originali); senza prova `NOT_PROVEN_IDENTICAL`, non un conflitto |
| Gia' le stesse identita' | `AlreadyShared` |
| Il media ha copie sul telefono | `KeepLocal` (D8: l'indice vale per l'identita' dei soli media solo-NAS) |
| Altrimenti | per media e versione, indipendenti: si adotta l'id remoto se e' **minore** del nostro (`Rename`), altrimenti si tiene il nostro (`KeepLocal`): tutti convergono al minore senza parlarsi |

La rinomina (`IdentityDao.renameMedia`, `renameVersion`) aggiorna nella stessa transazione ogni colonna di
`MEDIA_REFERENCES` e `VERSION_REFERENCES` (chiavi esterne differite: il controllo e' al commit) e lascia il vecchio id come
alias; l'indice della timeline riceve un cambiamento strutturale (esce il vecchio id, entra il nuovo).
`MergeReferencesTest` fallisce se una tabella nuova punta a `media` o a `media_version` senza essere elencata.

### 11.6 Migrazione 2 -> 3

`GalleryMigrations.MIGRATION_2_3`: colonna `file_id`, gli indici nuovi (quello di `work_state` sostituito), le tabelle
nuove vuote, e i bit di presenza dei media esistenti calcolati con `TimelineRules.presenceFlags` dai derivati gia' salvati
(`presence`, `safety`: un `UPDATE` per combinazione, nessuna seconda copia della regola in SQL). `ExportedSchemaTest`:
1 -> 3 (`seedV1`, passando dalla 2), 2 -> 3 (`seedV2`), 3 (`seedV3`, una riga in ogni tabella nuova); in tutti i casi un
ricalcolo del catalogo non cambia nulla.

### 11.7 `core-metadata` (spostamenti)

- `ExtractHints` ha i nomi **neutri** (`dateTakenMs`, `dateModifiedMs`, `width`, `height`, `orientation`, `durationMs`,
  dopo `scanZone`): gli indizi vengono anche dall'elenco del NAS. Il costruttore e le proprieta' `ms*` restano come alias
  deprecati, quindi nessun chiamante cambia.
- `hash/Fingerprints.quick/full` su `MediaBytes`: la stessa logica di `PhoneFingerprints` (core-mediastore), generica per
  il NAS (rilevamento, `FULL_HASH`). `PhoneFingerprints` resta dov'e' (fuori dal perimetro di P2) e puo' delegare.

### 11.8 Scelte e scarti della v3

| Scelta | Alternative scartate e perche' |
|---|---|
| Presenza nei bit di `cell_flags` | Colonne nuove in `media`: un'altra migrazione dell'indice e dell'istantanea per tre booleani. |
| Coda del telefono filtrata da `presence` | Filtro su `media_location` (EXISTS): un join per riga scorsa. Una colonna "posto" in `work_state`: andrebbe tenuta allineata a ogni cambio di posizione. |
| Riferimenti al NAS senza `ORDER BY` (scelta in Kotlin) | `ORDER BY` come `phoneRefs`: un ordinamento in memoria per ogni blocco di 500. |
| Chiave di contenuto calcolata da `qh1` e dimensione (`nasRefs`) | Colonna in `media_version`: 8 byte per riga e una migrazione che rilegge tutto, per uno SHA-256 di 40 byte. |
| Identita' remote come dati (`remote_*`), mai chiavi esterne | Chiavi esterne verso `media`: una testa remota potrebbe bloccare o cancellare in cascata righe locali. |
| Rinomina con alias | Unione con un media vuoto: `MergeRules` vuole la prova degli hash completi, qui l'identita' e' la stessa e cambia solo l'id. |
| Diario della 4B gia' nella v3 | Una v4 all'inizio della 4B: un'altra migrazione sul campo; le tabelle restano vuote finche' P12 non le usa. |
