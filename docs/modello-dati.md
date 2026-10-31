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
  **Fase 7 (`docs/fase7-progetto.md` D7, D29, D31):** per un media spostato in un album condiviso il proprietario e' l'account che
  l'ha aggiunto, quello della voce `item:` (`addedBy`), e lo spostamento non cambia ne' il `MediaId` ne' il proprietario (porta di
  completamento: la nuova posizione si collega allo stesso media prima che la sorgente vada nel cestino). La cartella d'origine sta nei
  dati personali (`shared_origin`), non nella definizione comune; "Riporta nelle mie cartelle" vale solo per i media propri.
- **Stati di lavoro e volti (Fase 8, `docs/fase8-progetto.md` D15, D16):** `FACES` resta fuori dalle fasi iniziali di `work_state`:
  lo stato dei volti (fatto con una ricetta, "non supportato", unita' fatte) sta nel database dei volti della generazione (§9), per chiave
  di contenuto e per account, non per media. Si cancella con la generazione e non tocca mai lo stato del media.
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
  dello stesso lato lo fa `NasTwins` (`core-library/.../duplicates/`, revisione di Fase 7, pacchetto C, DP-1): mette in coda `FULL_HASH`
  solo per i media del NAS che sembrano uguali (stessa dimensione e stesso hash rapido) e li unisce con `merge` quando gli hash completi
  sono uguali; le regole d'identita' (`MergeRules`) non cambiano.
- ~~**Cursori di sincronizzazione MediaStore** (generazione per volume, `getVersion()`): Fase 3.1.~~ Fatto nella v2
  (`media_store_cursor`, §10).
- **OCR, FTS, volti**: tabelle nelle Fasi 6 e 8 (i volti nell'area privata, proposta 13). Il test delle
  referenze obblighera' ad aggiungerle all'unione.
  - **OCR e FTS: fatti nella Fase 6** (progetto del 21/10, `docs/fase6-progetto.md` D23, D25, D37; schema **v5**, §13, scritto da
    P1): testo letto in `ai_text` con la tabella FTS `ai_text_fts` e il vocabolario `ai_vocabulary`, vettori in `ai_vector`.
    Sono tabelle **per chiave di contenuto**, non per media: lo stesso contenuto in tre posti (telefono, area privata, area
    comune) si calcola e si conserva una volta, e nessuna punta a `media` (non entrano nell'unione d'identita').
  - **La tabella `embedding` della v1 resta vuota e superata** (D23): nessuno la scrive o la legge (`MediaCatalog.putEmbedding` non
    ha chiamanti) e `ai_vector` e `ai_text`, per chiave di contenuto, la sostituiscono. Non si toglie: la migrazione 4→5 non e'
    distruttiva.
  - **Volti: progettati nella Fase 8** (`docs/fase8-progetto.md` D14, D15; P0 del 27/10): **non** stanno nel database della galleria,
    che resta **v6** (poi v7 solo per una colonna della cronologia delle ricerche, correzioni D), ma in un database a parte per generazione, `faces-<G>.db` (`FacesDatabase` di `core-database`, schema proprio
    **v1** esportato in `schemas/`): volti, persone, esemplari, assegnazioni, voci `people`, segni anti-rollback, outbox e unita' fatte,
    con vettori, centroidi e nomi **cifrati** con la chiave della generazione (C39 di `docs/sicurezza.md`). Un file si cancella e si
    verifica ("non c'e'"); nessuna migrazione sul campo per una funzione che molti non attivano. Lo descrive il §15 "Database dei volti,
    v1", scritto da P6; i contratti (archivio `FacesArchive` di `core-faces`, chiave `FacesKeys` di `core-security`) sono di P0.
- ~~**Album completi, album smart e condivisi**: Fase 7, con migrazione.~~ Progettati nella Fase 7 (`docs/fase7-progetto.md`, 21-22/10):
  album personali e smart nei dati personali (D3, D4, campi dentro `ALBUM`, nessun tipo nuovo), proiettati sulle tabelle **derivate** della
  v6 (D5, D34); album condivisi come contributi firmati nell'area comune (D6-D11), replicati senza chiavi esterne (`shared_album_entry`);
  diario della condivisione (`album_share_op`, `album_share_item`); "casa" locale (`home_estimate`, mai sincronizzata, D18). Lo schema
  **v6** (una sola migrazione, 5→6, mai distruttiva) e' di P1: §14, scritto da P1. I codici salvati (`ShareLevel`, `AlbumKind`,
  `AlbumSort`, `TripKind`) sono in `core-model` da P0.
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
| `placesToRecompute(dataset, limit)`, `savePlaces(dataset, places)` | il ricalcolo dei luoghi a un dataset nuovo (`PlacesRecompute`, core-mediastore; debito del 27/10): le versioni con GPS il cui `place_dataset` manca o e' un altro; la riscrittura (una transazione) porta il dataset nuovo anche a chi non ha piu' un luogo vicino e lo fa uscire dalla coda, quindi non serve un cursore. `media.place_id` cambia solo se cambia il luogo della versione corrente |
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

## 12. Schema v4 (Fase 5A: il backup al sicuro e le copie simili)

Progetto: `docs/fase5-progetto.md` §1.2 (porta `BackupCatalog`), §1.3 (righe `core-model` e `core-database`), §2.1-§2.3, §2.11,
§2.12, §4.3, riga P1 del §4.4; D3, D8, D9, D14, D15, D27, D36. Pacchetto P1. La v4 e' **una sola migrazione sul campo per tutta la
Fase 5**: le tabelle della 5B nascono gia' qui, vuote (D27).

### 12.1 Tipi nuovi o estesi in `core-model`

Solo aggiunte (gli altri pacchetti programmano gia' sui tipi). I bit 14 e 15 di `CellFlags`, `PresenceBadge.PROBLEM`,
`MediaGroupKind.SIMILAR_COPIES` e `VisualSignature` sono di P0; P1 li riempie.

| Tipo | Cosa | Perche' |
|---|---|---|
| `TimelineRules.backupTodo(presence, safety, markedProblem)` | il media e' un candidato del backup: copia presente sul telefono, `NO_NAS_COPY` o `PRIVATE_COPY_UNVERIFIED`, nessun problema che chiede l'utente. **Mai `ONLY_COMMON_COPY`** | §2.11 punto 3 e Q4 (b): la sola copia comune non si ricarica dal telefono, la copia privata la crea il guardiano. Funzione dei soli derivati salvati: la migrazione la calcola in SQL senza l'account del dispositivo |
| `TimelineRules.backupProblemFlags(markedProblem, safety)` | `CellFlags.BACKUP_PROBLEM` finche' il media non e' di nuovo al sicuro | un errore vecchio non nasconde "al sicuro" |
| `TimelineRules.similarCopiesCover(members, designated, anyHidden)` | come `groupCover`, ma con una copia nascosta nessuna rappresenta il gruppo | nascosto contagioso (E-H1): mai "un'altra copia al suo posto" |
| `TimelineRules.similarCopiesFlags(coverFlags, memberFlags)` | `SIMILAR_COPIES` sulla copertina e i segni di presenza **del gruppo** | §2.12 "Segni della cella": al sicuro solo se ogni copia sul telefono lo e'; problema se una copia lo ha; sul telefono / sul NAS se almeno una copia lo e' |

### 12.2 Tabelle e colonne

| Dove | Aggiunta | Note |
|---|---|---|
| `media` | `backup_todo` (NOT NULL DEFAULT 0) e indice (`backup_todo`, `sort_time`, `media_id`) | derivato come gli altri, ricalcolato nella stessa transazione; **non** e' in `IndexRow`: da solo non annuncia nulla. L'indice scorre solo i candidati: dopo il primo backup una pagina non legge 100.000 righe |
| `media_location` | indice (`account_id`, `share_key`, `verified_at`) | la riverifica a rotazione (dalla verifica piu' vecchia, senza ordinare) e le copie non ancora verificate (`verified_at` NULL) senza scorrere il telefono ne' altre share. Con questo indice `countPresentInShare` puo' scegliere l'uno o l'altro dei due con lo stesso prefisso (stessa spesa) |
| `backup_item` (nuova) | il diario: i campi di `BackupItem` di `core-backup` uno per uno (telefono, timbro atteso, destinazione, "nome (n)", temporaneo, punto di ripresa con l'identita' del temporaneo a quel punto (`resume_file_id`, R3 della revisione 5A), impronte, timbri del temporaneo e del finale, sorgente del guardiano, problema) piu' `is_open`, `created_at`, `updated_at` | FK differite verso `media` e `media_version`, **senza cascata**. `is_open` (vero se lo stato non e' terminale) lo tiene il DAO: gli aperti sono pochi e la tabella cresce di una riga per file. Indici: (`is_open`, `item_id`), (`version_id`, `is_open`), `media_id`, `state`. Nessun indice unico sugli aperti: un'unione potrebbe farne collidere due |
| `backup_problem` (nuova) | PK `media_id`: `code`, `marks_cell`, `updated_at` | un problema per media; cascata (si ricalcola). Si toglie da solo quando il media torna al sicuro |
| `nas_copy_mismatch` (nuova) | PK `location_id` (FK con cascata): `size`, `modified_at`, `found_at` | una copia la cui rilettura non e' tornata; vale solo per quel timbro |
| `visual_signature` (nuova) | PK `version_id` (FK con cascata): `algorithm`, `frames`, `data` (BLOB), `signed_at` | D36: solo locale, una per versione; una firma di un altro algoritmo la sostituisce |
| `pending_task` (nuova, 5B) | `task_id`, `kind`, `state`, `media_id`, `version_id`, `bytes`, `created_at`, `updated_at` | D20, "Da decidere" (tipo, media, byte, da quando); indice (`state`, `created_at`) |
| `edit_session` (nuova, 5B) | media, versione di partenza, `target`, `state`, la riga del telefono e il timbro all'inizio, `relative_path`, `taken_at_utc`, `cache_name`, `cache_sha256`, `started_at`, `expires_at` | D25, E-X3: istanti e non durate, la sessione sopravvive alla morte del processo; indice (`state`, `expires_at`) |
| `free_space_run`, `free_space_item` (nuove, 5B) | il giro (filtro, impronta del piano, modo, lotti) e i suoi elementi (media, versione, `phone_location_id` **senza FK**, copia privata verificata e suo timbro, lotto, motivo) | D21: l'elemento "sa" della posizione del telefono prima che sparisca. La verifica sul momento **non** si salva (vale 6 ore di orologio monotono). Indici su `phone_location_id`, (`run_id`, `batch_index`) |
| `incoming_item` (nuova, 5B) | file in arrivo: `stage_id`, `state`, `file_name` (derivato dall'hash), `sha256`, `size`, `mime`, destinazione, `backup_item_id` (FK, SET NULL) | D24: non e' ancora un media, nessun riferimento a `media` |

Regole come per le altre tabelle: codici stabili per le enumerazioni, hash in esadecimale, istanti in millisecondi. **I `toString`
delle righe con dati dell'utente sono muti** (nomi, percorsi, share, id, impronte: mai nei log, §4.3).

### 12.3 Il diario (`BackupDiaryDao`)

`insert` e `update` tengono coerente `is_open` con lo stato (qualunque cosa porti l'entita'); `itemsToResume(limit)` (aperti, dal piu'
vecchio), `openForVersion`, `forMedia`, `citedTemps` (D33: i temporanei degli elementi non finiti), `countsByState`, `openBytes`.
La macchina a stati e l'adattatore Room (`RoomBackupStore`) sono di P2; qui ci sono le righe e le query con gli indici.

### 12.4 Operazioni di `MediaCatalog` e porta `BackupCatalog`

`core-database` non dipende da `core-backup`: i tipi sono lo specchio di quelli della porta (`BackupCatalogTypes.kt`) e `:app` (P7)
li lega 1:1 con una traduzione campo per campo. Percorsi, nomi, share e id non vanno mai nei log.

| `MediaCatalog` | Metodo della porta | Cosa garantisce |
|---|---|---|
| `backupCandidates(sources: Set<CatalogSource>, kind: CatalogBackupKind, after: CatalogCandidateCursor?, limit: Int): List<CatalogBackupCandidate>` | `candidates(CandidateQuery)` | per `sort_time` decrescente dall'indice dei candidati, senza ordinare in memoria; **un candidato per media**: la riga del telefono piu' vecchia di una versione da proteggere (originale o corrente) **presente**, con un `_ID` valido per la versione attuale del MediaStore (E-P22), in una sorgente scelta e senza copia privata verificata. Mai `ONLY_COMMON_COPY` (D9), mai un media con un problema che chiede l'utente. `dateEstimated`: META non l'ha ancora letta o e' una stima |
| `safetyCounts(sources): CatalogSafetyCounts` | `safetyCounts(BackupSources)` | per stato e problemi per codice, dei media propri delle sorgenti; legge i derivati |
| `phoneRefOf(mediaId, versionId): CatalogPhonePlace?` | `phoneRefOf` | la copia presente piu' vecchia con `_ID` valido; null finche' la lettura del telefono non la rilegge (E-P12); un alias vale per il sopravvissuto |
| `recordFullHash(mediaId, versionId, sha256): FullHashRecord` | `recordFullHash` → `HashRecord` | impara l'hash (e con lui `FULL_HASH` e un gruppo `PROBABLE_DUPLICATE`); `SAME`, `CONFLICT` (E-B15), `UNKNOWN_VERSION`. **In ogni esito la fase `FULL_HASH` di quella versione si chiude** (`DONE` con l'hash noto, `GAVE_UP` con una versione che non c'e': fuori dalla coda ma ri-richiedibile) |
| `attachCopies(copies: List<AttachedCopy>): Int` | `attachVerifiedCopies(List<VerifiedCopy>)` | **un lotto, una transazione, un solo cambiamento dell'indice**; idempotente sulla stessa posizione; una versione sparita nel frattempo si salta, una dimensione sbagliata o una verifica senza hash completo annullano il lotto (`IllegalArgumentException`). `VerifiedCopy.path: SharePath` → `Location.Nas(account, share, NasPath)` |
| `markBackupProblem(mediaId, code: String?, marksCell: Boolean = true)` | `markProblem(mediaId, BackupProblemCode?)` → `(code?.code, code?.marksMedia)` | `BACKUP_PROBLEM` e `backup_todo` **nella stessa transazione**; su un media al sicuro non resta; dopo `markVerified(.., falso)` si segna il problema, non prima. **`lost_before_backup` (E-B4) si toglie anche quando la lettura del telefono (`recordAll`, `confirmPresent`/`confirmAllPresent`) registra di nuovo una posizione presente del media** (la foto recuperata dal cestino, E-P11): un solo punto, `refreshDerived`; gli altri codici no |
| `privateCopies(share, size, quickHash, limit): List<CatalogNasCopy>` | `privateCopies` → `NasCopyRef` | D8: solo l'area privata **dell'account del dispositivo**, in qualunque cartella; mai l'area comune ne' un'altra area privata; dall'indice (`size`, `quick_hash`) |
| `verifyCandidates(scope: CatalogVerifyScope, limit): List<CatalogCopyToVerify>` | `verifyCandidates(VerifyScope, limit)` → `CopyToVerify` | `Unverified` (hash della versione noto; **non** le copie segnate non integre sullo stesso timbro ne' di un media con un problema che chiede l'utente), `FullHash` (la coda `FULL_HASH`, **anche l'area comune**: li' si impara solo l'hash), `Rotation` (dalla verifica piu' vecchia), `OnTheSpot(ids)` (rilegge comunque) |
| `markVerified(locationId, readBack, atMillis): Boolean` | `markVerified` | falso per una posizione che non c'e' piu'; una rilettura che non torna **si ricorda** (`nas_copy_mismatch`); una che torna toglie il segno |
| `onlyCommonCopies(ids: Collection<MediaId>?, limit): List<CatalogGuardCandidate>` | `onlyCommonCopies(GuardScope, limit)` | `ONLY_COMMON_COPY` propri, una riga per versione da proteggere senza copia privata e con una copia **comune dichiarata** (mai una share di area sconosciuta) |
| `mediaSafety(mediaId): CatalogMediaSafety?` | `mediaSafety` | stato, ultima verifica di una copia privata, problema |
| `versionLocations(mediaId): List<VersionLocations>` | (5B, non ancora in porta) | per versione, le posizioni con l'area della share |

### 12.5 Copie simili: le operazioni per P15 (D36, §2.12)

| Operazione | Cosa garantisce |
|---|---|
| `similarRuns(fromSecond, limit): SimilarRunsPage` | i candidati per `sort_time` dall'indice: stesso tipo (foto/video), **stesso secondo di scatto letto dai metadati** (EXIF o contenitore; mai nome, data del file, `MEDIASTORE` o stima), visibili, fuori da raffiche, coppie RAW+JPEG e `PROBABLE_DUPLICATE`; a gruppi per secondo e tipo, **una pagina non spezza mai un secondo**; ogni candidato porta `aspectMilli`, `durationMs`, il gruppo e l'algoritmo della firma salvata |
| `similarRunsFor(ids): List<SimilarRun>` | gli stessi gruppi per i secondi di alcuni media (dopo che META ha letto l'ora di uno nuovo) |
| `similarUnsigned(algorithm): Int` | quanti candidati con un compagno aspettano ancora la firma (`SimilarCopiesProgress.pending`) |
| `saveSignature(versionId, VisualSignature): Boolean`, `signatureOf`, `signaturesOf` | firma locale per versione; una riga rovinata si legge come "manca" |
| `groupSimilar(members, cover): SimilarGroupOutcome` | **rifiuta senza scrivere nulla** (`Refused` con i membri colpevoli) se un membro e' in una raffica, in una coppia RAW+JPEG o in un `PROBABLE_DUPLICATE`, con meno di due media (dopo gli alias) o con una copertina che non e' un membro; i membri di un altro gruppo di copie simili lo lasciano (un gruppo rimasto con meno di due si scioglie): si passano **tutti** i membri voluti. **Nascosto contagioso**: se un membro e' nascosto lo diventano tutti nella stessa transazione, con `hidden_changed_at` |
| `separate(member): SeparateOutcome` | il membro torna una cella a se'; il gruppo rimasto con un solo media si scioglie; se era la copertina ne resta una provvisoria (la piu' recente) e `coverChanged` lo dice; **non toglie il nascosto** |
| `setSimilarCover(group, media): Boolean` | "Mostra questa come principale"; falso se non e' un membro o il gruppo non e' di copie simili |
| `similarGroupOf(mediaId)`, `similarGroup(group)`, `similarGroupIds(after, limit)` | le copie con misure, dove stanno e se sono nascoste, per la striscia del viewer e per rivalutare i gruppi a giri |

**Nascosto contagioso (E-H1, regola di sicurezza).** `setPersonalFlags` (singolo e per selezione) propaga il nascosto a tutte le
copie di un gruppo di copie simili passando dalla **stessa funzione** che scrive `hidden_changed_at` (la sincronizzazione dei dati
personali vede ogni copia); nascondere e mostrare, tutte e due le direzioni. L'unione che porta un media nascosto in un gruppo lo
fa lo stesso. Difesa in piu': se un dato arrivasse a meta' (una sola copia nascosta, scritta senza passare di qui), il gruppo non
ha copertina (`similarCopiesCover`) e **nessun altro membro compare nell'indice della timeline**. Il preferito non e' contagioso.
`MediaCatalog.group(SIMILAR_COPIES, ..)` e' un errore di programmazione: l'unico ingresso e' `groupSimilar`.

### 12.6 Unione e rinomina d'identita'

Ogni colonna nuova che punta a un media o a una versione e' in `MEDIA_REFERENCES` / `VERSION_REFERENCES` (`MergeReferencesTest` li
confronta con lo schema) ed e' spostata dall'unione (`MergeDao`) e dalla rinomina D7 (`IdentityDao`): `backup_item`, `pending_task`,
`edit_session`, `free_space_item` per media e versione; `backup_problem` per media (se il sopravvissuto ne ha gia' uno resta il suo);
`visual_signature` per versione (resta la firma gia' presente). `free_space_run`, `incoming_item` e `nas_copy_mismatch` non
contengono identita' di media (`TABLES_WITHOUT_MEDIA_IDS`). Un gruppo di copie simili non si perde: il sopravvissuto prende il posto
del perdente, copertina compresa; rimasto con un media solo, si scioglie. `MergeV4Test`.

### 12.7 Migrazione 3 -> 4

`GalleryMigrations.MIGRATION_3_4`: `media.backup_todo` (calcolato da `TimelineRules.backupTodo` per ogni combinazione di
`presence` e `safety`, un `UPDATE` per combinazione, nessuna seconda copia della regola in SQL), i due indici, le nove tabelle vuote
(le istruzioni sono copiate da `4.json`: la validazione dello schema dopo la migrazione le confronta). Mai distruttiva.
`ExportedSchemaTest`: 1 -> 4, 2 -> 4, 3 -> 4 con `seedV3` e 4 con `seedV4` (una riga in ogni tabella nuova, codici scritti a mano);
`laMigrazioneDalla3CalcolaIlBackupTodoDiOgniCombinazione` apre una v3 con un media per ogni combinazione.

### 12.8 Invarianti e prove

| Invariante | Prova |
|---|---|
| `backup_todo` = `TimelineRules.backupTodo` per ogni media, scritto nella stessa transazione, mai nell'indice della timeline | `BackupCatalogTest`, `ExportedSchemaTest` |
| Un lotto di copie verificate e' una transazione e **un solo** cambiamento (`change_seq` + 1) | `BackupCatalogTest.unLottoDiCopieVerificate…` |
| `BACKUP_PROBLEM` derivato nella stessa transazione, mai su un media al sicuro | `BackupCatalogTest.ilProblemaAccende…` |
| Una foto `lost_before_backup` rivista presente dalla lettura del telefono perde il segno nella stessa transazione e torna candidata (un cambiamento); gli altri codici restano | `BackupCatalogTest.unaFotoRecuperataDalCestino…`, `glAltriProblemiNonSiTolgono…`; piano in `BackupQueriesTest` |
| `ONLY_COMMON_COPY` non e' un candidato; la copia comune letta non fa mai "al sicuro" | `BackupCatalogTest` |
| Una copia non integra o di un media con un problema non si rilegge a ogni giro; `FULL_HASH` si chiude in ogni esito | `BackupCatalogTest` |
| Nessuna riga di diario, problema, firma o gruppo si perde in un'unione o in una rinomina | `MergeV4Test`, `MergeReferencesTest` |
| Nascosto contagioso nelle due direzioni e per tutte le vie; "Separa" non toglie il nascosto; un membro di una raffica e' rifiutato | `SimilarCopiesCatalogTest` |
| Un gruppo di copie simili con la copertina nascosta non fa comparire un altro membro | `SimilarCopiesCatalogTest.unGruppoConLaCopertinaNascosta…` |
| Piani: indici giusti, **nessun `USE TEMP B-TREE`** (candidati, verifiche, deduplica, diario, indice della timeline, copie simili) | `BackupQueriesTest`, `BackupDiaryTest`, `SimilarQueriesTest`, `TimelineIndexTest` |

### 12.9 Misure (JVM, Robolectric; il dispositivo si misura a M2)

| Misura | Esito |
|---|---|
| `backupCandidates`, 100.000 media tutti candidati, pagine da 500 (180 pagine di foto) | 4-14 ms a pagina (prima 14); a meta' libreria 7 ms |
| `backupCandidates`, 100.000 media con 350 candidati (la fine del primo backup) | 4 ms; la pagina vuota dopo l'ultimo 0 ms |
| `safetyCounts` su 100.000 media | 21 ms |
| `verifyCandidates` su 100.000 copie private | `Rotation` 32-37 ms, `Unverified` 9-11 ms (500 righe) |
| `privateCopies` su 100.000 media | 2 ms |
| diario con 100.000 elementi terminali e 12 aperti: `itemsToResume` + `citedTemps` + conteggi | 9 ms |
| indice della timeline, 100.000 media con 1.000 gruppi di copie simili | 681 ms (751 ms senza i gruppi); budget 8 s |
| `similarRuns` su tutta la libreria (100.000 media, 1.666 gruppi) | 118 ms in 7 pagine, la piu' lenta 32 ms; `similarUnsigned` 46 ms |

**Una trappola del pianificatore** (provata, 35 secondi a pagina): senza statistiche SQLite sceglie per `media_location` l'indice
(`state`, `absent_since`) o quello di una share e rilegge ~100.000 posizioni per candidato (come per `phoneRefs`, §10). Le query del
backup usano `CROSS JOIN` per fissare l'ordine e `+` davanti alle colonne di `media_location` che non devono scegliere l'indice;
il piano di ogni query e' nei test.

### 12.10 Scelte e scarti della v4

| Scelta | Alternative scartate e perche' |
|---|---|
| `media.backup_todo` con il suo indice | Filtrare sull'indice della timeline: dopo il primo backup ogni pagina scorrerebbe 100.000 righe per non trovare niente. Un indice su (`safety`, `sort_time`): tre stati diversi non si ordinano insieme senza `TEMP B-TREE` |
| `is_open` in `backup_item` | Indice su `state` con `IN (...)`: l'elenco degli aperti si ordinerebbe in memoria e scorrerebbe i terminali; indice unico parziale sugli aperti: Room non li esporta, e un'unione potrebbe farne collidere due |
| Diario senza cascata, problema e firma con cascata | Cascata sul diario: una riga si perderebbe in silenzio se un'unione sbagliasse. Il problema e la firma si ricalcolano |
| `nas_copy_mismatch` come tabella a parte | Una colonna su `media_location`: ogni aggiornamento della posizione dal dominio la riscriverebbe a NULL e la copia rovinata si rileggerebbe a ogni scansione |
| `markBackupProblem` non resta su un media al sicuro | Lasciarlo: il badge `PROBLEM` prevale su "al sicuro" e un errore vecchio nasconderebbe una copia buona |
| `groupSimilar` rifiuta tutto senza scrivere | Escludere i membri colpevoli e raggruppare gli altri: nascerebbe un gruppo che chi chiama non ha chiesto |
| Un gruppo di copie simili senza una colonna "copertina scelta" | La scelta dell'utente e' un dato personale sincronizzato (P15): il catalogo tiene solo la copertina attuale in `media_group.cover_media_id` |
| Candidati e firme per versione corrente | Una firma per media: una correzione di data o una modifica cambiano i pixel, e la firma di una versione e' immutabile |
| Il pianificatore: `CROSS JOIN` e `+` nelle query del backup | `ANALYZE` sul telefono: nessuno lo esegue, e le statistiche invecchiano |

## 13. Schema v5 (Fase 6: la ricerca per contenuto e per testo)

Progetto: `docs/fase6-progetto.md` §0.1 (D23, D25, D32, D34, D37), §1.3 (riga `core-database`), §2.3, §2.4, §4.3, riga P1 del §4.4, §5.2.
Pacchetto P1. La v5 e' **una sola migrazione sul campo per tutta la Fase 6** (D37): le tabelle dell'indice condiviso `ai` che P2 si
aspetta (`AiStores.kt` di core-index) ci sono gia', vuote, cosi' non serve una v6.

**Nessuna tabella nuova punta a `media` ne' a `media_version`** (D37): vettori e testo stanno per **chiave di contenuto**
(`ContentKey.hex`, 64 cifre: SHA-256 di `qh1` e dimensione), da qualunque origine (telefono, area privata, area comune), e il
collegamento a un media passa dalle versioni (`size`, `quick_hash`). Per questo l'unione di due media e la rinomina d'identita' non le
toccano: stanno tutte in `MediaCatalog.TABLES_WITHOUT_MEDIA_IDS` (`MergeReferencesTest`).

### 13.1 Tipi in `core-model`

Nessuna modifica: i codici stabili (`SpaceCode`, `VecSource`, `OcrSource`, `OcrEngineCode`), `CellFlags.DOCUMENT` (bit 16) e `Float16`
sono di P0. Le tabelle scrivono i **codici** (`StableCode.code`) come testo; una riga con un codice che questa versione non conosce
(scritta da un'altra app) e' "non per me" e si salta in lettura (E-I20). I tipi di P1 stanno in `core-database` (`AiCatalogTypes.kt`):
`CellContent`, `AiVectorRow`, `AiTextRecord`, `TextMatchCounts`, `SearchHistoryRecord`.

### 13.2 Tabelle

Indice locale e cronologia (per chiave di contenuto):

| Tabella | Chiave | Colonne | Note |
|---|---|---|---|
| `ai_vector` | `content_key`, `space`, `recipe`, `frame` | `source`, `instant_ms`, `byte_offset`, `byte_length`, `crc`, `created_at` | la **riga di indice** di un vettore float16 che sta nel file in sola aggiunta `noBackupFilesDir/search/vectors-<spazio>.bin` (e' l'unica copia dei vettori: Room tiene dove sta e il CRC32 del record, D24). Due spazi convivono (D21); per chiave vince la ricetta piu' alta; un video ha fino a 9 fotogrammi. Indice (`space`, `recipe`) |
| `ai_text` | `text_id` (rowid) | `content_key` (unico), `recipe`, `engine`, `source`, `text`, `lines`, `chars`, `layout` (BLOB), `created_at` | una riga per chiave. `text` e' il testo **gia' normalizzato** (`SearchText.normalize`); `lines` e `chars` misurano il testo **letto** (regola del bit `DOCUMENT`); `layout` e' il testo con righe e riquadri per il testo dal vivo, **opaco** per il database |
| `ai_text_fts` | rowid = `ai_text.text_id` | `text` | FTS4 a contenuto esterno su `ai_text.text`, tokenizzatore `simple` (D25); Room crea i quattro trigger che lo tengono allineato |
| `ai_vocabulary` | `word` | `docs` | per ogni parola letta, in quanti testi compare (una parola conta una volta per testo, al piu' 64 lettere); una parola a 0 esce. Per i refusi e i completamenti: **mai** proposte nella schermata Cerca (D29) |
| `search_history` | `id` (`AUTOINCREMENT`) | `text`, `chips` (etichette separate da `\n`), dalla **v7** `persons` (id delle persone dei chip "Persona", UUID separati da una virgola; vuota se non ce n'erano) | D32: mai sincronizzata ne' esportata; al massimo 50 righe; niente orario (l'id non si riusa e dice l'ordine); indice su `text`. `persons` (Fase 8, correzioni D, R2): serve a togliere le ricerche di una persona anche quando il nome di prima non si ritrova piu' (Rinomina, Dimentica, Nascondi persona, "Disattiva e cancella"); mai un nome, solo id casuali |

Le nove tabelle dell'indice condiviso (porte `AiPublishStore` e `AiReplicaStore` di P2). `share` e' gia' una chiave (minuscolo); `cls` e'
il codice di `AiClass` (7 `vec`, 8 `ocr`, quelli di `SegmentClass`); `body` e' il corpo come pubblicato:

| Tabella | Chiave | Colonne | Note |
|---|---|---|---|
| `ai_pending_publish` | `id` (`AUTOINCREMENT`) | `share`, `state` (`outbox`/`held`), `cls`, `content_key`, `recipe`, `body`, `digest` | outbox e voci in attesa di una share pubblicabile (D8) in una tabella sola: spostare una voce e' un `UPDATE`. Indici (`share`, `state`, `id`) e (`share`, `cls`, `content_key`) |
| `ai_own_head` | `share` | `version`, `head_hash`, `published_at` | la testa `ai` propria (ora del NAS, M3) |
| `ai_own_segment` | `share`, `hash` | `cls`, `size`, `entries` | i segmenti elencati dalla testa propria (e' la colonna `segments` del commento di P2: le righe di questa tabella, in ordine di impronta) |
| `ai_own_live` | `share`, `cls`, `content_key` | `recipe`, `digest`, `segment` | la voce viva di ogni (classe, chiave): per non ripubblicare un corpo identico. Indice (`share`, `segment`) |
| `ai_replica_head` | `share`, `device` | `version`, `content_hash`, `registry_version`, `created_at` | l'anti-rollback (E-I5) |
| `ai_replica_segment` | `share`, `device`, `hash` | `cls`, `size`, `state`, `entries` | `state` = `SegmentState.code`; `entries` e' il tetto delle voci per dispositivo e share |
| `ai_replica_entry` | `share`, `device`, `segment`, `cls`, `content_key` | `recipe`, `digest`, `body` | i **candidati della fusione** (D7). Indice (`share`, `cls`, `content_key`) |
| `ai_replica_applied` | `share`, `cls`, `content_key` | `device`, `recipe`, `digest` | il vincitore consegnato all'indice locale |
| `ai_replica_dirty` | `share`, `cls`, `content_key` | | le chiavi da rifondere |

La tabella `embedding` per media della v1 resta **vuota e superata** (D23): nessuno la scrive o la legge (`putEmbedding` non ha
chiamanti) e la migrazione non la tocca.

### 13.3 Operazioni del catalogo

In `MediaCatalog`, perche' toccano il grafo dei media (e annunciano `changes`):

| Operazione | Cosa |
|---|---|
| `cellContentKeys(afterSeq, limit)` | le chiavi di contenuto delle celle della Libreria, a pagine (§13.4) |
| `cellContentKeysOf(ids)` | le stesse per le celle di un `CatalogChange` |
| `setDerivedFlags(ids, flags, on, batch)` | il bit `DOCUMENT` a lotti (§13.6) |

In `MediaCatalog.aiIndex` (`AiIndexCatalog`), che non annuncia niente (l'indice della timeline non cambia): `putVectors`, `vectors`
(la ricetta piu' alta per chiave), `vectorPage` (per costruire l'indice in memoria), `keysWithVector`, `countVectorKeys`,
`removeVectors`, `removeVectorSpace`, `removeSupersededVectors`; `putText`, `text`, `texts`, `keysWithText`, `textStats`,
`removeTexts`, `clearTexts`; `textMatches`; `wordCounts`, `vocabularyPage`, `completeWords`; `recordSearch`, `searchHistory`,
`searchHistoryChanges` (vivo), `deleteSearch`, `clearSearchHistory`. Le tabelle `ai_*` dell'indice condiviso si leggono e scrivono
con i DAO `AiPublishDao` e `AiReplicaDao`: gli adattatori delle porte di core-index (P11) usano `GalleryDatabase.transaction`, **una
transazione per metodo della porta**.

### 13.4 Le chiavi di contenuto delle celle

Per ogni **cella** dell'istantanea (visibile, non nascosta, non dietro la copertina del suo gruppo: gli stessi predicati
dell'indice della timeline) `cellContentKeys` da' le chiavi delle versioni del suo media (la **corrente** per prima, poi le
precedenti dalla piu' recente: se la nuova non ha ancora il vettore vale una precedente, E-A10) e, per ogni **altro membro
visibile e non nascosto** del suo gruppo, le sue (D29: un membro nascosto non porta mai il suo contenuto nella cella).

- **Cursore**: `afterSeq` e' il `rowid` di inserimento di `media` (0 = dall'inizio); `CellContentPage.next` e' il cursore della
  pagina dopo, null alla fine. Un media nuovo ha sempre un `rowid` piu' alto: chi ha letto fino a `afterSeq` trova i **media nuovi**
  nelle pagine dopo e i cambiamenti delle celle esistenti con `cellContentKeysOf(ids)` e gli id di `changes`. Il `rowid` e'
  stabile finche' nessuno esegue `VACUUM` (Room non lo fa).
- **Piano**: `ORDER BY m.rowid` e' l'ordine dell'albero di `media`, quindi **nessun `USE TEMP B-TREE`**, senza statistiche e con
  (`CellContentKeysTest`, `EXPLAIN QUERY PLAN`). Il join e' in ordine fisso (`CROSS JOIN`: sul telefono nessuno esegue
  `ANALYZE`): dal media (per `rowid`), giu' per `media_id` alle versioni e a `media_personal`.
- **Fette**: dentro una pagina si legge a fette di 4.096 media. Una fetta sta in una finestra del cursore di SQLite (2 MB): un
  risultato piu' grande la riempie piu' volte e ogni riempimento rilegge la query dall'inizio (quadratico: 1,4 s invece di 0,4 s su
  100.000 media). Le chiavi (SHA-256) di una fetta si calcolano su `Dispatchers.Default` mentre si legge la fetta dopo.
- **Chiave veloce**: `ContentKey.of` formatta ogni byte con `String.format` (su 100.000 media e' da solo piu' del tetto): `FastContentKey`
  da' lo stesso risultato con una tabella di cifre; `CellContentKeysTest` prova la parita' su 300 impronte casuali e fallisce se la
  definizione della chiave cambia in `core-model`.

### 13.5 Testo, FTS4 e vocabolario

- `putText` salva una riga per chiave: una **ricetta piu' bassa** di quella salvata non la sostituisce (D7), a parita' o piu' alta si.
  Cambia con `UPDATE` (mai `INSERT OR REPLACE`: con `recursive_triggers` spento la cancellazione implicita non scatterebbe sui
  trigger e l'indice del testo resterebbe sporco). Nella stessa transazione cambiano l'indice FTS4 (i trigger di Room) e il
  vocabolario (+1 alle parole nuove, -1 a quelle tolte, via le parole a 0).
- Il testo **deve** essere normalizzato (solo `a-z`, `0-9` e singoli spazi): `AiTextRecord` rifiuta il resto, perche' indice e
  vocabolario si fidano. Un testo vuoto e' valido ("letto, nessun testo": non si rilegge).
- `textMatches(terms)`: ogni termine e' l'elenco delle sue **alternative** (la parola, la parola corretta dal vocabolario, una
  frase fra virgolette); una chiave risponde a un termine se contiene una qualunque alternativa. `TextMatchCounts.all` (tutti i
  termini: "sicuro") e `.atLeastHalf` ("meno probabile", D27). **Mai sintassi `MATCH` dall'utente** (E-Q9): ogni alternativa si
  riduce a `[a-z0-9 ]` in minuscolo e va al database come **una frase fra virgolette**, quindi senza operatori (`OR`, `NEAR`,
  `-`), colonne (`text:`), prefissi (`*`); le alternative si uniscono in Kotlin, non con `OR`. Tetti: 16 termini, 8 alternative,
  200 caratteri.
- Il tokenizzatore `simple` cerca parole **intere**: i refusi li copre il vocabolario (`wordCounts`, `vocabularyPage` per costruire
  l'indice a cancellazioni di P6, `completeWords` per prefisso).

### 13.6 Il bit `DOCUMENT` (D34)

`setDerivedFlags(ids, CellFlags.DOCUMENT)` accende (o con `on = false` spegne) il bit sui media, **a lotti di 500, una transazione per
lotto**: ogni lotto che cambia qualcosa annuncia **un solo** `CatalogChange` (un `change_seq` in piu', non uno per media, non
strutturale) e l'indice della timeline si aggiorna una volta; un lotto senza cambiamenti non annuncia niente. Gli id assorbiti da
un'unione si risolvono, quelli spariti si saltano. Accetta solo i bit di `MediaCatalog.AI_DERIVED_MASK` (oggi `DOCUMENT`). Il bit non
lo calcolano le regole: il ricalcolo dei derivati (`derive`) **lo conserva**, altrimenti ogni scrittura lo spegnerebbe.

### 13.7 Cronologia (D32)

`recordSearch(text, chipLabels)`: la stessa ricerca (stesso testo, tagliato) resta una volta sola e passa in cima; al massimo
**50 righe** (`SEARCH_HISTORY_MAX`, come `SearchHistory.MAX_ENTRIES` di core-search); testo vuoto = niente; testo a 200 caratteri e
al piu' 16 etichette da 80. Le etichette non portano a capo. Stesso comportamento di `InMemorySearchHistory` (P0).

### 13.8 Migrazione 4 -> 5

`Migration4To5`: **solo tabelle nuove**, tutte vuote (le SQL sono copiate da `5.json`). Non tocca una riga ne' una colonna: nessun dato si
perde. Il bit `DOCUMENT` non si calcola qui (non c'e' ancora testo letto). Room valida colonne e indici dopo la migrazione ma **non i
trigger** dell'FTS4: `ExportedSchemaTest.loSchemaMigratoCoincideConQuelloCheRoomCreaDaZero` confronta il testo di `sqlite_master`
(tabelle, indici, trigger) di ogni versione migrata con quello che Room crea da zero.

### 13.9 Invarianti e prove

| Invarianti | Prova |
|---|---|
| 1 -> 5, 2 -> 5, 3 -> 5 e 4 -> 5 (con `seedV4`) si aprono e non perdono nulla; una v5 con una riga per tabella si riapre | `ExportedSchemaTest` |
| 4 -> 5 non cambia **nessuna riga** delle tabelle esistenti e le nuove nascono vuote | `laMigrazioneDalla4NonCambiaNessunaRigaEsistente` |
| lo schema migrato (compresi i trigger) e' quello che Room crea | `loSchemaMigratoCoincideConQuelloCheRoomCreaDaZero` |
| nessuna tabella nuova punta a `media`; tutte fra quelle senza id di media | `MergeReferencesTest` |
| chiavi delle celle: versioni, gruppi, nascosti, pagine, media nuovi, piano senza ordinamento, prestazioni | `CellContentKeysTest` |
| vettori, testo e FTS4, frasi sempre letterali, vocabolario, cronologia | `AiIndexCatalogTest` |
| `setDerivedFlags`: un cambiamento per lotto, il bit sopravvive ai ricalcoli | `DerivedFlagsTest` |
| i DAO `ai_*` bastano alle sequenze delle porte di P2, anche con la morte a meta' transazione | `AiShareTablesTest` |

### 13.10 Misure (JVM, Robolectric; il dispositivo si misura a M2)

| Misura | Esito |
|---|---|
| `cellContentKeys`, 100.000 media (98.167 celle, 1.000 gruppi), pagine da 5.000 | **ogni pagina 24-29 ms** (21 pagine, mediana 24 ms), 482 ms in tutto |
| `cellContentKeys`, 100.000 media in **una chiamata sola** (`limit` = 100.000) | 420-530 ms (lettura ~300 ms: il cursore di Robolectric costa ~3 us a riga; chiavi ~160 ms, in parte in parallelo alla lettura) |
| piano di `cellContentKeys` | `SEARCH m USING INTEGER PRIMARY KEY (rowid>? AND rowid<?)`, `SEARCH v USING INDEX index_media_version_media_id_version_id`, `SEARCH p USING INDEX sqlite_autoindex_media_personal_1 LEFT-JOIN`; nessun `TEMP B-TREE` |

### 13.11 Scelte e scarti della v5

| Scelta | Alternative scartate e perche' |
|---|---|
| Righe di indice in `ai_vector`, vettori nel file | I vettori in un BLOB: 100.000 x 1,5 KB in Room, nessuna scansione mappata; D24 vuole il file |
| `ai_text` con `text_id` e `UPDATE` | `content_key` come chiave con `INSERT OR REPLACE`: l'FTS4 a contenuto esterno non vede la cancellazione implicita |
| Termini sempre frasi letterali, alternative in Kotlin | Passare la query a `MATCH` ripulita: ogni operatore dimenticato (`NEAR`, `OR`, `col:`) sarebbe un'iniezione |
| Vocabolario tenuto da `putText` nella stessa transazione | Ricostruirlo a ogni avvio da tutto il testo: 100.000 testi letti per correggere un refuso |
| `afterSeq` = `rowid` | Un `change_seq` per riga: richiederebbe una colonna in piu' in `media`; `(sort_time, media_id)`: ordine della timeline ma un cursore a due valori |
| Fette da 4.096 media | Una query sola: il riempimento ripetuto delle finestre del cursore e' quadratico |
| `FastContentKey` locale con test di parita' | Ottimizzare `ContentKey.of` (e' di core-model: proposta nel report) |
| Tabelle `ai_*` con DAO semplici, adattatori a P11 | Gli adattatori in `core-database`: non vede core-index |

## 14. Schema v6 (Fase 7: album smart e condivisi, condivisione, viaggi)

Progetto: `docs/fase7-progetto.md` D34 (e D4, D5, D9, D12, D18, D19, D25, D28, D29, D31), §1.2-§1.3 (riga `core-database`), §2.1-§2.5, §4.3, riga P1 del
§4.4, §5.2; decisioni di P0 e di P1 in `docs/decisioni.md` ("Fase 7 P0", "Fase 7 P1"). Pacchetto P1. La v6 e' **una sola migrazione sul campo per tutta
la Fase 7** (5 -> 6, mai distruttiva). Le tabelle degli album sono **derivate** (D5): lo stato vero sta nei dati personali (album personali) o nei
contributi firmati dell'area comune (album condivisi) e si ricostruisce; la proiezione sul catalogo e le repliche sono di P3 e P2. Qui lo schema, le
colonne derivate, le letture del catalogo per gli album e la porta di completamento degli spostamenti.

**Nessun dato dell'utente nei log** (D37): titoli, cartelle, percorsi, luoghi e coordinate stanno solo nelle colonne; i `toString()` delle entita' e dei
tipi di questo paragrafo non li stampano.

### 14.1 Tipi

I codici stabili (`ShareLevel`, `AlbumKind`, `AlbumSort`, `TripKind`, `SharedState`, `ShareItemState`, ...) sono di P0 (`core-model`, `core-albums`): le tabelle
scrivono il **codice** (`StableCode.code`) come testo; una riga con un codice che questa versione non conosce (scritta da un'altra app) e' "non per me". I tipi
di P1 stanno in `core-database` e hanno la **forma** dei contratti di P0 (core-database non vede core-albums): `AlbumCatalogTypes.kt` (`CandidateQuery`,
`CandidateKey`, `CatalogCandidate`, `CatalogMediaFacts`, `CommonAttestRequest`, `DuplicateGroup`, `AttachMovedOutcome`, ...), `OriginDevice` e `CopyAreas`.

### 14.2 Tabelle e colonne

Colonne **in piu'** di tabelle esistenti (tutte con DEFAULT; `ALTER TABLE ADD COLUMN`):

| Tabella | Colonna | Cosa |
|---|---|---|
| `album` | `kind` (`manual`) | `AlbumKind.code`: `manual` o `smart` |
| `album` | `sort` (`newest`) | `AlbumSort.code`: l'ordine **dentro** l'album (D21); l'ordine dell'elenco e' un'impostazione sincronizzata, non una colonna |
| `album` | `frozen` (0) | uno smart congelato non applica le regole (D16) |
| `album` | `shared_id` | l'album condiviso nato da questo (voce personale `shared`, D4), o NULL |
| `album` | `state` | NULL per un album personale; `SharedState.code` (`active`, `closed`, `deleted`) per la riga di un album **condiviso** (D5: la replica fusa proiettata nella stessa tabella). Titolo, copertina e ordine dei condivisi stanno qui; il resto in `shared_album` |
| `album_item` | `origin` (`manual`) | come l'elemento e' entrato: `auto` (un "sicuro" di uno smart), `manual` (voce `item:`), `accepted` (un suggerito accettato, voce `accept:`) |
| `album_item` | `fp` | l'**impronta** `<qh1 esadecimale>:<dimensione>` che la voce porta (D4): serve a ritrovare il media se gli id divergono fra dispositivi (la stessa dimensione e `qh1` di `PersonalFingerprint`, in esadecimale cosi' si confronta con `media_version.quick_hash` e `size`) |
| `album_item` | `added_by` | l'account di chi l'ha aggiunto (solo album condivisi: "Aggiunta da ...", Q6, e i permessi); NULL per i personali |
| `media` | `origin_device` | il dispositivo d'origine, **derivato** (§14.3) |
| `media` | `copy_areas` (0) | dove stanno le copie presenti sul NAS, **derivato** (§14.3) |

Tabelle **nuove**:

| Tabella | Chiave | Colonne | Note |
|---|---|---|---|
| `album_rule` | `album_id`, `rule`, `position` | `value`, `media_id` | le regole di uno smart (D13): una riga per regola (`period`, `place`, `text`, `sources`, `types`, `position` 0) e una per ogni foto d'esempio (`rule` = `example`, `position` 0..5, `media_id`). Il **valore** e' un testo che il database non interpreta (lo scrivono P3 e P6). Chiavi esterne: `album` in cascata, `media` in cascata |
| `album_exclusion` | `album_id`, `media_id` | `fp` | le esclusioni per sempre (voce `exclude:`, E-AL5). Chiavi esterne in cascata |
| `album_suggestion` | `album_id`, `media_id` | `reason`, `added_at` | i "Suggerite" calcolati dal motore (D14, S1-S5): non e' un dato personale, non si sincronizza. `added_at` serve a "Nuove: 12" (`AlbumChoiceDao.suggestionCounts`) |
| `shared_album` | `album_id` | `state`, `level`, `creator`, `heir`, `folder`, `after_close`, `updated_at_nas`, `closed_at_nas` | la definizione **fusa** di un album condiviso (D6-D11, Q5). **Senza chiavi esterne** (replica). La cartella e' il percorso nell'area comune (E-SH16: puo' cambiare) |
| `shared_album_entry` | `album_id`, `device_id`, `entry_key` | `field`, `at`, `account`, `content_key`, `tombstone`, `body` | le voci **verificate** di ogni contributo (`albums/<album>/<dispositivo>/`), su cui `AlbumFusion` rifonde (D7). `entry_key` e' SHA-256("mcg-album\0" \|\| id album \|\| campo), 64 cifre; `field` = `definition`, `level`, `cover`, `sort`, `item`, `heir`; `content_key` solo per `item` (la chiave di contenuto intera, per attestare e per accorgersi di un doppione, E-SH12); `body` e' il corpo canonico (al piu' 4 KiB) **opaco** per il database. Senza chiavi esterne: i `MediaId` dentro il corpo sono quelli di chi ha scritto. Indici (`album_id`, `content_key`) e (`album_id`, `field`) |
| `shared_album_head_mark` | `album_id`, `device_id` | `version`, `content_hash`, `registry_version`, `created_at` | l'ultima testa accettata di un dispositivo: l'anti-rollback e l'equivocazione (`HeadMark`, D8) e la `createdAt` con cui si rifiuta una voce nuova con `at` troppo vecchia (§2.4) |
| `album_share_op` | `op_id` | `album_id`, `kind`, `plan_hash` (unico), `title`, `level`, `nonce_hex`, `folder_share`, `folder_path`, `hidden`, `finished`, `created_at`, `updated_at` | il diario della condivisione (D29, §2.5), campo per campo `ShareOpRecord`. **Locale**, mai sincronizzato. Senza chiavi esterne (un album nuovo non ha ancora la sua riga) |
| `album_share_item` | `op_id`, `item_index` | `media_id`, `action`, `state`, `mine`, `size`, `content_key`, `source_share`, `source_path`, `dest_share`, `dest_path`, `transfer_key`, `ticket`, `stage_seq`, `safe_copy_requested`, `failure`, `wait`, `updated_at` | `ShareItemRecord`. **Ogni valore di un passo si scrive prima della chiamata che lo usa.** Chiavi esterne: `album_share_op` in cascata, `media` senza cascata (come il diario del backup: il media segue le unioni). Indici su `media_id`, `transfer_key`, `state` |
| `trip_proposal` | `trip_id` (64 cifre) | `kind`, `from_day`, `to_day`, `label`, `media_count`, `cover_media_id`, `place_ids`, `album_id`, `detected_at` | le proposte della sezione Viaggi (D19, D20). **Locale**; la riscrive la rilevazione notturna. `album_id`: l'album nato da "Crea album" (una proposta gia' album non si ripropone). `cover_media_id` in `SET NULL` |
| `home_estimate` | `slot` | `kind`, `latitude`, `longitude`, `radius_km`, `days`, `total_days`, `estimated_at` | la "casa" (slot 0) e i luoghi abituali (1..) dedotti **solo in locale** dalle foto proprie con GPS (D18, C19): mai sincronizzata, pubblicata, esportata o scritta nei log. La legge e la scrive solo `core-albums/.../trips/` (`Phase7ArchitectureTest`); la toglie l'azzeramento dei dati locali (§1.4 del progetto) |

Indici **in piu'**: su `media` (`local_day`, `sort_time`, `media_id`), (`place_id`, `local_day`, `sort_time`, `media_id`) e (`origin_device`, `local_day`, `sort_time`,
`media_id`) per i candidati; su `media_location` (`version_id`, `location_type`) per i doppioni. Gli indici delle chiavi esterne delle tabelle nuove.

### 14.3 Le colonne derivate di `media`

Come `backup_todo` (v4) si ricalcolano nella stessa transazione che cambia le posizioni (`MediaCatalog.derive`) e **non** sono nell'indice della timeline:
cambiarle da sole non annuncia nulla. La **migrazione le calcola con le stesse regole** (`OriginDevice`, `CopyAreas`); `ExportedSchemaTest` e
`Migration5To6Test` provano che il primo ricalcolo dopo la migrazione non cambia niente.

**`origin_device`** (regola B di D14, "stesso dispositivo"):
- `phone`: il media ha una copia **non assente** sul telefono di questo dispositivo;
- altrimenti `backup:<cartella>`: una copia non assente in una cartella `Backup/<cartella>/` di un'area **privata** (la cartella del dispositivo che il backup
  sceglie una volta, F5-D6; solo la radice vera `Backup/`: la recinzione di prova del debug scrive sotto un'altra radice che la release non ha e che il dex della release
  non puo' nominare, `check.ps1`; in debug quei media non hanno un dispositivo). `<cartella>` e' la forma di confronto (NFC, minuscolo) delle chiavi delle
  posizioni. La cartella `Area comune` (le copie del guardiano) non e' un dispositivo; con piu' cartelle vince la prima in ordine alfabetico;
- altrimenti NULL. **Marca e modello EXIF** (l'ultimo ripiego di §2.2) non stanno nel catalogo (`version_metadata` non li conserva): finche' nessuno li salva
  quei media non hanno un dispositivo e le foto sono "suggerite" invece che "sicure" (regola S1); il prefisso `cam:` e' riservato a quel ripiego;
- **due etichette per lo stesso telefono**: un media che perde la copia sul telefono ("Libera spazio") passa da `phone` a `backup:<la cartella di questo
  dispositivo>`. Chi confronta (P6) tratta `backup:<propria cartella>` come `phone` (la cartella la sa `AlbumFamily.backupFolderName`, il catalogo no e non gli serve).

**`copy_areas`**: maschera di bit delle copie **presenti** sul NAS: 1 = in una share dichiarata area privata, 2 = nell'area comune, 4 = in una share di area
sconosciuta (non privata, nel dubbio). Da qui le **sorgenti** degli album smart (`SmartSource`), una **partizione** in quest'ordine: `MY_PHONE` se ha una copia
presente sul telefono (bit `ON_PHONE`); `MY_PRIVATE` se ha il bit 1; `FAMILY` se `owner_account` e' un altro account; `COMMON` altrimenti. Un mio media passato
nell'area comune e' quindi `COMMON` (lo dice `CatalogCandidate.ownedByMe`, che serve alla "casa"). Una query con piu' sorgenti prende l'unione delle classi.

### 14.4 Le letture del catalogo per gli album (`MediaCatalog.albums`)

`AlbumCatalog` (letture, non annuncia `changes`; **mai un nascosto**: si legge da `media_personal`, D22). Il testo delle query e' pubblico in `AlbumQueries`
(i test ne guardano il piano con `EXPLAIN QUERY PLAN`, senza statistiche e con `ANALYZE`).

| Lettura | Cosa | Porta di P0 |
|---|---|---|
| `candidates(query, after, limit)` | una pagina di **celle visibili** (non nascoste, una per gruppo, con almeno una copia), con giorno e ora locali, GPS, luogo, dispositivo, sorgente, tipo, schermo, pixel. Il criterio **guida** sceglie l'indice: `originDevice` -> (`origin_device`, `local_day`, `sort_time`, `media_id`); `placeIds` -> (`place_id`, ...), un luogo alla volta in ordine di id; altrimenti il giorno -> (`local_day`, `sort_time`, `media_id`). Gli altri criteri (sorgenti, solo GPS, giorni) sono filtri sulle righe. Ordine (giorno, istante, id) (per luogo: luogo, giorno, istante, id); cursore `CandidateKey`; `next` esatto (si legge una riga in piu') | `AlbumCandidates.page` |
| `candidatesOf(ids)` | le celle visibili fra queste identita', in ordine | rivalutare i media di un `CatalogChange` |
| `visibleCount()` | quante celle visibili ha la Libreria (E-AL6) | `AlbumCandidates.visibleTotal` |
| `daysOf(ids)` | il giorno locale di queste identita' | il ricalcolo incrementale di P6 |
| `visibleAmong(ids)` | i media presenti e non nascosti, alias compresi | `VisibleMedia.visible` (D22) |
| `facts(ids)` | dove sta ogni media (`PHONE_ONLY`, `MY_PRIVATE`, `COMMON`, `OTHERS_PRIVATE`: con una copia nell'area comune vince `COMMON`, E-SH11), di chi e', nascosto, video, dimensione, chiave di contenuto, versione, il file del NAS da cui si parte, copia sul telefono. Un id assorbito da un'unione si risolve (la chiave della mappa e' l'id chiesto). Anche i nascosti: li filtra il piano (Q3) | `CatalogFacts.facts` |
| `attestInCommon(requests)` | D9: per ogni chiave di contenuto (e hash completo, se entrambi lo hanno) il media del catalogo di **chi legge** con una copia **presente nell'area comune**; un elemento che rimanda a un file privato, sparito o diverso non c'e' e non avvia nulla. Con due media con la stessa chiave vince l'id piu' basso. Legge a cursore le versioni dell'area comune | `CatalogFacts.attest` |
| `duplicates(after, limit)` | i **doppioni identici** (D25): le copie presenti di una stessa versione di un media visibile dalla stessa **parte** (`PHONE`, oppure `NAS` = area privata e comune insieme; una versione puo' dare due gruppi), con area di ogni copia (`PHONE`, `MY_PRIVATE`, `COMMON`, `OTHERS`: E-U1), percorso, dimensione, data, ultima verifica e hash completo della versione (senza, "da verificare", D27). Pagine per `version_id` | Utility -> Doppioni (P8) |

Le **chiavi di contenuto** si calcolano da `qh1` e dimensione con `FastContentKey` (stesso risultato di `ContentKey.of`, provato da `CellContentKeysTest`): non c'e'
una colonna con la chiave nel catalogo, cosi' `media_version` non cambia; `attestInCommon` le calcola mentre scorre le versioni dell'area comune.

**Fase 9 (P3):** una lettura nuova sullo stesso schema, i **punti della mappa** (`MapPointsQueries.page`, file a parte in `dao/`, nessuna tabella
ne' indice nuovi): §16.

### 14.5 La porta di completamento degli spostamenti: `MediaCatalog.attachMoved`

`attachMoved(media, to, stamp, from, sha256, verifiedAtMillis)` collega il file `to` (arrivato nell'area comune, o in un'altra cartella) allo **stesso** media
(D29, E-SH2), **prima** che la sorgente vada nel cestino:
- con `from` (uno spostamento) la riga della posizione di partenza **cambia posto** (stessa `location_id`, stessa versione: `nas_adopted_fingerprint` e
  tutto cio' che la cita restano); se la scansione aveva gia' visto il file nuovo come un'altra posizione dello stesso media, la partenza diventa **assente**
  (come la scansione farebbe). Senza `from` (un caricamento `INCOMING` dal telefono) la posizione e' nuova e la copia del telefono resta;
- `sha256` e' l'hash che il motore ha verificato: se la versione ha l'hash completo e **non** coincide, niente si collega (`ContentMismatch`); se coincide la copia
  e' verificata adesso. La dimensione deve essere quella della versione (`SizeMismatch`). Non insegna l'hash alla versione (lo fa `FULL_HASH`);
- **idempotente**: il secondo giro con gli stessi argomenti non scrive e non annuncia niente (`AlreadyAttached`); **una transazione e un solo cambiamento**
  dell'indice della timeline (un `change_seq` in piu') anche se la riga cambia posto e la partenza diventa assente;
- un file che e' gia' di **un altro** media non si tocca (`DestinationOfOtherMedia`: la scansione lo unira' con le regole d'identita'); un media che non c'e' piu'
  (`UnknownMedia`); un id assorbito da un'unione vale il sopravvissuto.

### 14.6 Unione e rinomina d'identita'

Le tabelle nuove che puntano a `media` sono in `MediaCatalog.MEDIA_REFERENCES` e l'unione le sposta (`MergeDao`): le foto d'esempio (`album_rule.media_id`), le
esclusioni, i suggeriti, gli elementi del diario (`album_share_item.media_id`) e la copertina di una proposta; gli elementi degli album passano con **tutte** le
colonne (come sono entrati, impronta, chi li ha aggiunti). Dove il sopravvissuto ha gia' la riga resta la sua (un solo elemento per (album, media)). Le altre
(`shared_album`, `shared_album_entry`, `shared_album_head_mark`, `album_share_op`, `home_estimate`) non hanno identita' di media: stanno in
`TABLES_WITHOUT_MEDIA_IDS` (`MergeReferencesTest`; `MergeV6Test` prova lo spostamento). Nessuna tabella nuova punta a una versione.

### 14.7 Migrazione 5 -> 6

`Migration5To6`, in una transazione (quella di Room), **mai distruttiva**: 1. le colonne in piu' con i DEFAULT; 2. `album_item.fp` dalla versione corrente del
media (`<qh1>:<dimensione>`, la stessa che `fingerprintOfMedia` di core-personal da' alle voci); 3. `origin_device` (`phone` con un `UPDATE` solo; le cartelle
`Backup/<dispositivo>/` con `OriginDevice.ofNasKey`, la stessa funzione del catalogo) e `copy_areas` (un `UPDATE` per area); 4. le tabelle nuove, vuote; 5. gli indici,
**dopo** aver riempito i derivati. Gli album e gli elementi che c'erano diventano album manuali personali (`kind` `manual`, `sort` `newest` il predefinito dei
manuali D21, `origin` `manual`, `state` NULL). Le SQL sono copiate da `6.json`; `ExportedSchemaTest` confronta le definizioni di `sqlite_master` migrate con
quelle che Room crea da zero. Su API 29 (SQLite 3.22) niente `UPSERT`, `RETURNING`, `DROP COLUMN`.

**Mai una sottoquery correlata fra `media` e le posizioni** (`UPDATE ... WHERE EXISTS (... v.media_id = media.media_id ...)`): senza statistiche SQLite puo' scorrere le
posizioni per ogni media e la migrazione diventa quadratica (10.000 media = 90 s). I derivati si scrivono con `WHERE media_id IN (sottoquery indipendente)` e `CROSS JOIN`
che fissa l'ordine (dalle posizioni, giu' per chiave primaria): 10.000 media = 0,3 s, 100.000 = 7 s. La prova e' `Migration5To6ScaleTest`.

### 14.8 Invarianti e prove

| Invarianti | Prova |
|---|---|
| 1 -> 6, 2 -> 6, 3 -> 6, 4 -> 6 e 5 -> 6 (con i seed, `seedV5` per la 5) si aprono e non perdono nulla; una v6 con una riga per tabella si riapre | `ExportedSchemaTest` |
| 4 -> 6 e 5 -> 6 non cambiano **nessuna riga** delle tabelle esistenti (colonna per colonna fra quelle che c'erano) e le tabelle nuove nascono vuote | `leMigrazioniDalla4EDalla5NonCambianoNessunaRigaEsistente` |
| lo schema migrato (tabelle, indici, FTS4 e trigger) e' quello che Room crea da zero | `loSchemaMigratoCoincideConQuelloCheRoomCreaDaZero` |
| `origin_device` e `copy_areas` dalla migrazione = quelli del catalogo, in quattordici situazioni (telefono, `Backup/<dispositivo>/`, una radice che non e' `Backup`, `Area comune`, area comune, share sconosciuta, assenti, non raggiungibili); l'impronta degli elementi e' quella della versione corrente; il ricalcolo li riscrive uguali | `Migration5To6Test` |
| la stessa migrazione su 100.000 media (in un file vero): derivati giusti per ognuno, nessuna chiave esterna rotta | `Migration5To6ScaleTest` |
| le tabelle nuove che puntano a `media` sono nell'unione, le altre classificate; la replica non ha colonne con un'identita' locale | `MergeReferencesTest` |
| l'unione sposta foto d'esempio, esclusioni, suggeriti, diario e copertina di una proposta, e gli elementi con tutte le colonne | `MergeV6Test` |
| i DAO bastano alle sequenze dei pacchetti dopo (riepiloghi, replica, diario atomico, viaggi, casa), con la cascata e i rifiuti | `AlbumTablesV6Test` |
| candidati: ordine, pagine esatte, mai un nascosto ne' un membro di gruppo, sorgenti, giorno e ora locali, indici guida | `AlbumCandidatesTest` |
| fatti, attestazione (chiave, hash, area, stato), doppioni per parte, visibilita', alias | `AlbumFactsTest` |
| `attachMoved`: stessa riga, secondo giro muto, un solo cambiamento, scansione arrivata prima, rifiuti | `AttachMovedTest` |
| piani senza `USE TEMP B-TREE`, tempi, risultati uguali al calcolo a mano su 100.000 media | `AlbumQueriesPerformanceTest` |
| le regole del dispositivo d'origine e delle aree (funzioni pure) | `OriginDeviceTest` |

### 14.9 Misure (JVM, Robolectric; il dispositivo si misura a M2)

Libreria sintetica da 100.000 media (`BigLibrary`: 3 anni di giorni, 200 luoghi, tre dispositivi, le tre aree, nascosti, membri di gruppo, 3.016 doppioni).

| Misura | Esito |
|---|---|
| candidati per giorno / luogo / dispositivo, una pagina da 500 | **0-4 ms**; la seconda pagina 4-9 ms; nessun `USE TEMP B-TREE` (senza statistiche e dopo `ANALYZE`) |
| doppioni identici, 3.016 gruppi in una chiamata | **72-87 ms** |
| `facts` di 500 media; `candidatesOf` di 500; `attestInCommon` di 1.000 elementi su 10.000 file dell'area comune | 8 ms; 4 ms; 30-47 ms |
| `visibleCount` | 20 ms |
| migrazione 5 -> 6 di 100.000 media | **6,7 s** (apertura, migrazione, indici e validazione di Room, file vero); il database passa da 151 a 178 MB (+27 MB) |

### 14.10 Scelte e scarti della v6

| Scelta | Alternative scartate e perche' |
|---|---|
| Album condivisi nella tabella `album` (`state` non NULL) | Una tabella `album` per i personali e una per i condivisi: due elenchi, due copertine, due conteggi per le Raccolte |
| `fp` come testo `<qh1>:<dimensione>` | Due colonne (`fp_size`, `fp_qh`): due indici per ritrovare un media che ne ha gia' uno (`size`, `quick_hash`); base32 come nel formato: non si confronta con le versioni |
| `album_rule` una riga per regola, valore opaco | Una colonna per regola: ogni regola nuova e' una migrazione; un solo JSON: due modifiche contemporanee di due regole si perdono (D4) |
| `origin_device` e `copy_areas` derivati nello stesso passo di `derive` | Calcolarli a ogni query unendo le posizioni (un join in piu' per candidato: da 3 ms a decine); una tabella a parte (un'altra chiave esterna da tenere) |
| Chiave di contenuto senza colonna | Una colonna in `media_version` per ogni versione: riempirla in migrazione e a ogni scrittura per una lettura di 30 ms |
| `place_id IN (...)` con un ordinamento | `USE TEMP B-TREE`; un luogo alla volta evita l'ordinamento in memoria |
| Indice (`version_id`, `location_type`) per i doppioni | Leggere le righe di tutte le versioni con piu' posizioni (telefono + NAS e' il caso comune: 271 ms su 100.000); una colonna derivata di conteggio (un altro derivato da tenere) |
| `attachMoved` sposta la riga | Creare la posizione nuova e lasciare la vecchia (la sorgente in cestino resterebbe "presente" fino alla scansione: il media sembrerebbe al sicuro); `relocate` (non guarda l'altro media ne' l'hash) |
| Replica senza chiavi esterne | Tabelle con chiavi esterne a `media` per gli elementi dei contributi: i `MediaId` dentro le voci sono di chi ha scritto, non i locali (D9) |

## 15. Schema v1 del database dei volti (Fase 8: Persone)

Progetto: `docs/fase8-progetto.md` D14, D15 (e D16, D20-D24, D29, E-F19, E-F35), §1.2 (`FacesArchive`, `FacesKeys`), §2.3-§2.5, §2.8, riga P6 del §4.4, §5.2, §6.1
(C39); decisioni di P0 e di P6 in `docs/decisioni.md` ("Fase 8 P0", "Fase 8 P6"). Pacchetto P6. E' un **database a parte, uno per generazione**: il database della
galleria **non riceve nessuna tabella** (nessuna tabella condivisa; lo prova `FacesSchemaTest`). La galleria passa da v6 a **v7** solo con le correzioni dopo le revisioni (pacchetto D, R2): una colonna, `search_history.persons` (`Migration6To7`, mai distruttiva; lo schema v6 resta invariato, impronta in `FacesSchemaTest`). Qui il file e la chiave, le tabelle, cosa
e' cifrato e come, le operazioni dell'archivio, come si apre, si rifa' e si cancella, le misure.

**Nessun valore nei log** (D37): chiavi di contenuto, riquadri, id di persone, autori, nomi, vettori e generazioni non escono da qui; i `toString()` delle entita' e dei tipi di
questo paragrafo dicono solo di che tabella o che tipo sono; i file si nominano nei log con un'etichetta fissa (`faces-db`, `faces-key`).

### 15.1 Il file e la chiave

| Cosa | Dove | Note |
|---|---|---|
| Database | `faces-<G>.db` (+ `-wal`, `-shm`, `-journal`) in una cartella che sceglie chi lo costruisce (`FacesStores(directory, ...)`, di norma `noBackupFilesDir/faces/`) | un file per generazione `G` (base32, 26 caratteri); Room, **WAL**; **mai** `fallbackToDestructiveMigration`; fuori da qualunque backup e trasferimento fra telefoni |
| Schema esportato | `core-database/schemas/dev.pampa.mycloudgallery.core.database.faces.FacesDatabase/1.json` | accanto a quelli di `GalleryDatabase` (`.../GalleryDatabase/1.json`..`6.json`, invariati) |
| Chiave del Keystore | alias `mcg-faces-<G>` (AES-256-GCM, StrongBox se c'e', altrimenti TEE; **senza** autenticazione ne' telefono sbloccato) | `FacesKeyStore` (core-security); in release senza Keystore hardware la chiave non si crea e Persone non parte |
| Chiave dati | 256 bit casuali, **avvolta** dalla chiave del Keystore in `<alias>.key` (di norma `noBackupFilesDir/faces-keys/`) | mai su disco in chiaro; in memoria solo mentre il negozio e' aperto; `FacesDataKey.close()` la azzera |

Il file non sta in `databases/`: `FacesDatabase.build(context, file)` usa un contesto che mette i database nella cartella data (Room vuole un nome e lo cerca con
`getDatabasePath`; un percorso assoluto come nome non funziona sulla JVM di Windows dei test). Un database di una versione futura (app tornata indietro) non si apre e **non si
cancella** (`FacesDatabaseFiles.versionOf` lo guarda prima di Room).

### 15.2 Tabelle

I codici (`space`, `detector`, `source`, `tier`, `state`, `origin`, `kind`, `area`) sono `StableCode.code` di core-model, scritti come testo; una riga con un codice che questa
versione non conosce (scritta da un'app piu' nuova) e' "non per me" e chi legge la salta. Le tabelle sono **riferimenti e numeri**: i valori sensibili stanno solo nelle colonne
BLOB sigillate (§15.3).

| Tabella | Chiave | Colonne | Note |
|---|---|---|---|
| `face_body` | `content_key`, `author` | `is_winner`, `space`, `detector`, `recipe`, `source`, `digest`, `face_count`, **`secret`** (BLOB sigillato) | un corpo `faces` per chiave di contenuto e dispositivo (§2.4). `is_winner`: il vincitore della chiave (ricetta piu' alta, poi autore minore, poi impronta minore: `FaceBody.winner`), rifatto **nella transazione che scrive il corpo**. Indice (`is_winner`, `space`, `content_key`): l'ingresso del raggruppamento senza ordinare |
| `face` | `content_key`, `author`, `idx` | `frame`, `x0..y1` (1/65535), `score`, `tier`, `reasons`, `side_px`, `yaw`, `sharpness` | i volti **in chiaro** che le query chiedono senza decifrare (livello, riquadro). Chiave esterna sul corpo, **in cascata** |
| `face_unsupported` | `content_key` | `recipe` | formato non decodificabile, con la ricetta piu' alta vista (non si abbassa) |
| `group_state` | `id` (= 1) | `seq`, `base`, `unassigned_at_births` | i numeri dell'istantanea del raggruppamento (D21) |
| `group_person` | `person_id` | `state`, `alias_of`, `born_seq`, `changed_seq` | persone dell'istantanea (§2.4): `active`, `merged` (con alias), `forgotten`. **Nessun nome** |
| `group_exemplar` | `person_id`, `position` | `content_key`, `frame`, `face_index`, `body_digest` | al piu' 16 esemplari per persona, riferimenti a volti. Cascata sulla persona |
| `group_assignment` | `content_key`, `frame`, `face_index`, `body_digest` | `loc_key`, `loc_frame` (NULL = quelli del riferimento), `x0..y1`, `person_id`, `score` (REAL), `origin` | le assegnazioni. Indice su `person_id`. Cascata sulla persona |
| `people_record` | `subject`, `field` | `value`, **`sealed_value`**, `at_nas`, `author` | le voci `people` (nomi e correzioni, §2.4): l'ultima scrittura per chiave. Il campo `name` ha il testo **solo** in `sealed_value`; gli altri campi in `value`; `value` e `sealed_value` entrambi NULL = lapide. `at_nas` NULL = "da datare" |
| `faces_head_mark` | `device_id` | `version`, `content_hash`, `registry_version`, `created_at` | l'anti-rollback e l'equivocazione delle teste (`HeadMark`, §2.4) per dispositivo |
| `faces_outbox` | `entry_id` (AUTOINCREMENT), unico (`kind`, `ref`) | `kind` (`faces`, `snapshot`, `people`), `ref` | cosa c'e' da pubblicare: durevole, una voce sola per la stessa cosa |
| `faces_unit_done` | `area`, `month`, `part` | `recipe`, `done_at_nas` | le unita' di lavoro finite (§2.3) |
| `faces_meta` | `name` | `data` (BLOB) | valori di servizio; oggi `key_check` (§15.3) |
| `replica_device` | `device_id` | `mark_version`, `mark_hash`, `snapshot_seq`, `snapshot_base`, `snapshot_head_version`, `snapshot_head_hash`, `snap_seq`, `snap_base`, `checked_version`, `checked_hash` | quello che la replica ricorda di un dispositivo dell'account (`DeviceReplica`, P3): l'ultima testa accettata (anti-rollback ed equivocazione), l'istantanea che dichiara, la testa da cui viene l'ultima istantanea valida (e la sua `seq`), la testa gia' giudicata. NULL = non c'e' |
| `replica_segment` | `device_id`, `position` | `cls` (codice del formato), `hash`, `size` | i segmenti che la testa elenca, nell'ordine della testa |
| `replica_segment_state` | `device_id`, `hash` | `done`, `entries` | un segmento letto (applicato o rifiutato per sempre) e quante voci ha portato (per i tetti) |
| `replica_revoked` | `device_id` | | i dispositivi revocati o persi gia' tolti dall'archivio (L99) |
| `faces_chunk` | `scope`, `owner`, `name`, `part` | `enc_hash`, `enc_size`, **`data`** (BLOB sigillato) | le voci `cluster` (persone **con il centroide**, assegnazioni) di un segmento letto dalla replica (`replica.cluster`, `owner` = dispositivo, `name` = impronta del segmento), di un'istantanea di un dispositivo (`replica.snapshot`) o dell'istantanea in coda (`publish.snapshot`). Un insieme grande e' spezzato in righe da circa 200 KB (`part`): Android non legge una riga oltre i 2 MB |
| `publish_state` | `id` (= 1) | `snap_seq`, `snap_base`, `pub_version`, `pub_mark_*`, `pub_at_nas`, `pub_snap_*`, `pub_entries`, `pending`, `adopted`, `uncertain` | gli scalari del pubblicatore (`FacesPublishState`, P3): l'istantanea in coda, l'ultima testa pubblicata da qui, le modifiche in coda e i due segni |
| `publish_key` | `content_key` | | le chiavi dei corpi calcolati qui (si pubblicano se passano "M3 dei volti") |
| `publish_segment` | `hash` | | i segmenti elencati dall'ultima testa pubblicata |
| `publish_diary` | `hash` | `ord` | il diario dei segmenti pubblicati da qui (la prova "e' mio" della pulizia dei propri file, M6), in ordine: i piu' vecchi escono per primi (al piu' 50.000) |

La **galleria non e' toccata**: `GalleryDatabase` resta v6, il suo `6.json` ha la stessa impronta di prima della Fase 8 e i due database non hanno nessun nome di tabella in
comune. I centroidi stanno **solo** nei blocchi sigillati della sincronizzazione (`faces_chunk`); `GroupingState` (contratto di P0) non li porta, quindi le tabelle `group_*`
non hanno la colonna (§15.10). La v1 e' ancora quella non rilasciata: P3 e P6 l'hanno completata con le tabelle di sincronizzazione prima del rilascio, senza migrazione.

### 15.3 Cosa e' cifrato, come, e cosa resta in chiaro

**Cifrato** (D14, C39): i **vettori** (float16, 256 byte a volto), i **landmark** (cinque punti, geometria del volto: li aggiunge P6 per minimizzazione, C40) e i **nomi** delle
persone. Mai i riquadri, i livelli, gli id, le chiavi di contenuto e le assegnazioni: servono alle query (meno di 50 ms senza decifrare) e non dicono chi e' una persona (L111).

- **Un blocco per corpo** (`face_body.secret`): landmark e vettori di tutti i suoi volti, un'operazione AES-GCM per contenuto e non per volto. Formato in chiaro, big endian,
  prima di sigillare: `versione (1) | N volti | N x [10 x 2 byte landmark | 1 byte "ha un vettore" | se 1: D x 2 byte float16]`. Autodescrittivo: chi lo legge lo confronta con le
  righe `face` e scarta il corpo se non tornano.
- **Un blocco per nome** (`people_record.sealed_value`): il testo UTF-8 del nome. Una riga `name` con il testo in chiaro non l'abbiamo scritta noi e non vale.
- **Formato del blocco sigillato** (`FacesDataKey.seal`): `versione (1) | nonce (12 byte casuali) | testo cifrato + tag (n + 16)`, AES-256-GCM con la chiave dati. Nonce nuovo a
  ogni cifratura. Il **`aad`** lega il blocco alla sua riga: per un corpo `contentKey|autore|impronta|ricetta|numero di volti`, per un nome `soggetto|campo`; un blocco spostato in
  un'altra riga, o una colonna di metadati alterata, non si apre piu' (la riga si salta: il contenuto si rifa' o si riscarica).
- **Blocchi delle voci `cluster`** (`faces_chunk.data`): ogni blocco e' un segmento `cluster` del formato (`FacesDocs.clusterSegment`: lo stesso codec e le stesse regole di chi
  legge dal NAS, quindi un blocco rovinato e' un rifiuto e non un'eccezione) sigillato con la chiave dati e un `aad` di ambito, proprietario, nome, pezzo e riferimento del segmento.
  Dentro ci sono i centroidi e le assegnazioni dell'istantanea del custode: mai in chiaro nel file (`RoomFacesSyncStoresTest`, con il controllo positivo sul segmento non sigillato).
- **Controllo della chiave** (`faces_meta.key_check`): un testo noto sigillato alla creazione del database. All'apertura, se con la chiave di oggi non si apre, il database e' di
  un'altra chiave (sparita e ricreata da altri) e **si rifa'** invece di fingere un archivio vuoto con righe che nessuno legge.
- **Nessun vettore, landmark o nome in chiaro nel file**: `FacesStoreEncryptionTest` cerca nei byte di `faces-<G>.db`, `-wal` e `-shm` ogni valore seminato in ogni forma
  (nomi in UTF-8 e UTF-16 LE/BE; vettori in float16 big e little endian e in float32; landmark) e prima si assicura, con un database in chiaro, che il cercatore li trovi.

### 15.4 L'archivio (`FacesArchive` sul database)

`DatabaseFacesArchive` (core-faces, `store/`) passa la stessa suite di contratto dell'archivio in memoria di P0 (`FacesArchiveContract`) e risponde come lui a ogni lettura dopo
la stessa storia di scritture (`FacesStoreParityTest`, storie a caso). Le regole:

| Operazione | Cosa fa |
|---|---|
| `saveBody` | **una transazione** (`FaceBodyDao.replaceBody`): cancella i volti del corpo che c'era per (chiave, autore), scrive il corpo sigillato e i volti, rifa' il vincitore. Idempotente; dopo una morte del processo un media gia' salvato non si rifa' |
| `winners` | per le chiavi chieste (a gruppi da 500: SQLite di Android 8-10 accetta 999 variabili) i corpi con `is_winner`, i loro volti, i blocchi decifrati. Un corpo che non si apre, di una forma sconosciuta o con righe che non tornano **non si restituisce** (come se non ci fosse: si rifa' o si riscarica) |
| `analyzed` | le chiavi con un corpo di ricetta >= `recipe` (di un autore qualunque) piu' i "non supportati" con ricetta >= `recipe`: dalle colonne in chiaro, senza decifrare |
| `markUnsupported` | tiene la ricetta piu' alta vista |
| `groupFaces` | i volti CORE e WEAK dei corpi vincenti nello spazio corrente, in ordine di chiave e indice, con i vettori in chiaro **solo in memoria**. La prima volta legge e decifra tutto; poi `GroupFaceCache` tiene i volti e rilegge solo i contenuti salvati nel frattempo (tetto 120.000 volti, ~65 MB; oltre, ogni chiamata rilegge) |
| `unrecognizable` | i volti NONE del corpo vincente per chiave: dalle colonne in chiaro |
| `grouping` / `saveGrouping` | l'istantanea si legge e si scrive **intera**: la scrittura sostituisce tutto in una transazione (la vecchia intera o la nuova intera, mai una a meta'); un'assegnazione a una persona che non c'e' fa fallire la scrittura e lascia la vecchia |
| `bodiesOf(author, keys)` (P3) | i corpi scritti da [author] per le chiavi chieste, **vincenti o no** (il pubblicatore pubblica tutti i propri): come `winners`, ma per autore |
| `datePeople(nasNow)` (P3) | le voci "da datare" (`at_nas` NULL) prendono l'ora del NAS, una `UPDATE` sola; quante. Idempotente. Sotto lo stesso lucchetto di `addPeople` |
| `dropAuthors(authors)` (P3) | la revoca (L99): toglie i corpi e le voci `people` scritti da [authors] in una transazione, **rifa' il vincitore** delle chiavi che lo avevano di loro (solo quelle) e lascia andare i volti per il raggruppamento in memoria; quanti corpi e quante voci |
| `people` / `addPeople` | l'ultima scrittura per chiave con `PeopleRecord.winner`, piu' due regole per le voci "da datare" (`at_nas` NULL): due voci entrambe da datare si **sostituiscono** (la nuova vince, qualunque sia il valore), e una voce **datata** che e' la stessa scelta (stessa modifica, stesso autore) di una da datare **la data** (senza, la voce da datare, che vince su tutte, non verrebbe mai datata). Una riga che non si apre o non si capisce si salta e si riscrive alla voce dopo |

Un archivio **chiuso** (chiave distrutta, generazione cambiata, `FacesStore.close`) lancia `IllegalStateException` a ogni chiamata (chi chiama riconcilia, §2.8).

### 15.5 Aprire, rifare, cancellare (`FacesStores`)

`FacesStores(directory, databases, keys)` e' l'unica porta del database e della chiave per il resto dell'app (`Phase8ArchitectureTest`). `open(G)` risponde con un esito, mai
un'eccezione per un caso previsto:

| Situazione | Esito |
|---|---|
| generazione nuova (nessun file, nessuna chiave) | `Ready`: crea la chiave (`keys.create`), il file e il controllo della chiave |
| file e chiave ci sono | `Ready` (lo stesso oggetto finche' e' aperto) |
| **chiave sparita** (ripristino, aggiornamento del sistema: E-F19) con il file ancora li' | `Recovered(KEY_LOST)`: i dati sono illeggibili, si **cancellano**, il negozio e' nuovo e **vuoto**; chi riceve l'esito riscarica dal NAS o ricalcola e segnala `FACES_KEY_LOST`. Nessun crollo |
| il Keystore **non risponde** (guasto passeggero) | `Unavailable(KEYSTORE)`: **niente si cancella**. Con chiavi che sanno dire come stanno (`FacesKeyProbe`, il Keystore vero) lo si distingue da "sparita"; senza (il fake di P0), tre risposte "nessuna chiave" di fila sono "sparita" |
| il file e' di un'altra chiave (chiave ricreata da altri) | `Recovered(KEY_LOST)` (controllo della chiave) |
| il file e' danneggiato | `Recovered(DATABASE_DAMAGED)`: la chiave resta, il file si rifa' |
| il file e' di una versione piu' nuova | `Unavailable(NEWER_DATABASE)`: non si apre e non si cancella |
| il file non si apre o non si toglie | `Unavailable(STORAGE)` |

**Cancellazione come file** (`eraseLocal(G)`, D29 passo 6; `eraseAllExcept(keep)` per le generazioni non correnti, E-F33; `eraseEverything()` per l'azzeramento, E-F35): 1. il
negozio aperto si **ritira** (da quel momento lancia); 2. **la chiave per prima** (`keys.destroy`: alias del Keystore, poi file avvolto); 3. si chiude il database e si azzera la
chiave dati in memoria; 4. si tolgono `faces-<G>.db`, `-wal`, `-shm` e `-journal`, **uno per uno con la cancellazione verificata di `StateFiles`** (vera solo se il file non c'e'
piu'; un file che non si toglie non ferma gli altri). `LocalErase(keyDestroyed, filesGone)`: finita solo se tutte e due sono vere, altrimenti si richiama (ogni passo e' idempotente).
L'azzeramento distrugge **tutte** le chiavi prima di toccare un file.

**Le porte per la sincronizzazione** (P3, legate da `:app`): `FacesStores.archives()` (l'archivio della generazione, o null se il negozio non si apre adesso), `replicaStore()` e
`publishStore()` (gli stati di §15.6, con il negozio che si apre da solo; un negozio che non si apre da' uno stato vuoto in lettura e `IllegalStateException` in scrittura) e il
parametro `onRecovered` del costruttore (chiamato quando un negozio e' stato rifatto da capo: la Diagnostica conta `FACES_KEY_LOST`).

### 15.6 Segni, outbox e unita' fatte

Tre piccoli magazzini nello stesso database, per P3 (pubblicazione e replica) e P4 (calcolo); spariscono con la generazione (§2.8: "la sua outbox sparisce con il database").

| Magazzino | API (`store/`) | Regole |
|---|---|---|
| Segni delle teste | `FacesHeadMarks.get/all/record/forget` | `record` non abbassa mai il numero (risponde falso); uguale o piu' nuovo si'. L'equivocazione (stesso numero, altra impronta) la decide chi sincronizza |
| Outbox | `FacesOutbox.stage/pending/count/acknowledge`, `stage(FacesChange)` | una voce per (`kind`, `ref`); rimettere in coda da' un id nuovo; `acknowledge` toglie solo cio' che e' stato letto (per id), una voce rimessa in coda nel frattempo resta; un `kind` sconosciuto resta e non si legge |
| Unita' fatte | `FacesUnitsDone.markDone/isDone/done` | per `FacesResource.WorkUnit` e ricetta; una ricetta piu' vecchia non abbassa quella che c'e'; una ricetta nuova le rifa' |

**Lo stato della sincronizzazione** (P3 + P6). La replica (`NasFacesSync`) e il pubblicatore (`NasFacesPublisher`) tengono il loro stato dietro due porte, `FacesReplicaStore` e
`FacesPublishStore` (`sync/FacesSyncPorts.kt`); P3 le ha consegnate con una versione in memoria, P6 le realizza sul database (`RoomFacesReplicaStore`, `RoomFacesPublishStore`, in
`store/`; li da' `FacesStore.replicaStore`/`publishStore`). Cosa cambia: una morte del processo non fa rileggere tutte le teste e i segmenti dal NAS e non perde cio' che era in coda
(le chiavi dei corpi, l'istantanea del custode, l'ultima testa pubblicata, il diario delle impronte, il segno "incerto"). Come:

| Regola | Perche' |
|---|---|
| **Un solo stato per negozio**, per la sua generazione: un'altra generazione e' vuota in lettura e un errore in scrittura | il database di una generazione non ospita le altre; il negozio cancellato lancia `IllegalStateException` |
| **Ogni `save` e' una transazione** (`FacesSyncDao.writeReplica`/`writePublish`) con la **sola differenza** rispetto all'ultimo stato scritto, che si tiene in memoria | un salvataggio costa quanto il suo cambiamento, non quanto lo stato: con 3.000 chiavi in coda una chiave in piu' costa ~0,5 ms e la scrittura dei soli scalari ~0,3 ms |
| **`load` decodifica una volta sola** (la prima), poi risponde dall'ultimo stato | P5 chiede le istantanee a ogni suo giro (`ClusterSnapshots`): decodificare 30.000 assegnazioni a ogni richiesta costerebbe mezzo secondo |
| Un segmento e' il suo contenuto: se l'impronta c'era gia', **non si riscrive**; l'istantanea di un dispositivo si riscrive solo se cambia (testa o `seq`) | lo stato di un giro cambia un dispositivo e pochi segmenti |
| Le voci `cluster` sono **sigillate** (§15.3) e spezzate in righe da ~200 KB | i centroidi sono vettori (C39); una riga oltre i 2 MB non si legge su Android |
| Un blocco che non si apre o non si decodifica **non rompe lo stato**: il segmento torna "da fare" (non piu' fatto, istantanea da rigiudicare) o l'istantanea in coda si lascia | il database e' tutto ricostruibile dal NAS |

### 15.7 La chiave (`FacesKeyStore`, core-security)

Implementa `FacesKeys` (contratto di P0) sopra l'`AndroidSecretKeyStore` delle credenziali, piu' `FacesKeyProbe` (`state(G)`: `USABLE`, `MISSING`, `UNUSABLE`, `UNAVAILABLE`).
`create` e' idempotente e **non rifa'** una chiave che funziona (rifarla renderebbe illeggibili i dati); se c'e' solo meta' (alias senza file avvolto, o il contrario) la rifa' da
zero; con un guasto passeggero risponde falso senza toccare niente; in release senza Keystore hardware non la crea. `open` non ha effetti. `destroy` toglie **prima** l'alias dal
Keystore, **poi** il file avvolto, e dice vero solo se non resta ne' l'uno ne' l'altro; anche con il Keystore che non risponde il file avvolto si toglie (la chiave dati esiste solo
li'). `destroyAll` toglie ogni alias `mcg-faces-*` e ogni file avvolto, anche orfani, e nessun'altra chiave. Il test strumentato con la chiave vera (`FacesKeyStoreInstrumentedTest`,
`FacesStoreInstrumentedTest`) e' scritto e non eseguito (vedi "Fase 8 P6" in `docs/decisioni.md`).

### 15.8 Invarianti e prove

| Invariante | Prova |
|---|---|
| lo schema v1 e' esportato, coincide con quello che Room crea oggi e un database creato dal JSON con una riga per tabella si apre e si legge | `FacesSchemaTest` |
| `GalleryDatabase` resta v6: `6.json` con la stessa impronta, nessuna v7, nessuna tabella in comune | `FacesSchemaTest.laV6DellaGalleriaRestaInvariata` |
| chiavi esterne accese e in cascata; il giornale e' WAL e `FacesDatabaseFiles` conosce `-wal`, `-shm`, `-journal` | `FacesSchemaTest` |
| il vincitore e' uno solo e segue `FaceBody.winner`; sostituire un corpo toglie i volti vecchi; una scrittura che fallisce non lascia un corpo senza volti; l'istantanea si sostituisce intera o per niente | `FacesDaoTest` |
| l'archivio vero passa la suite di contratto di P0 e risponde come quello in memoria su storie a caso, anche dopo chiusura e riapertura | `FacesStoreContractTest`, `FacesStoreParityTest` |
| **nessun vettore, landmark o nome in chiaro nel file** (anche nel `-wal`); il cercatore trova i valori in un database in chiaro | `FacesStoreEncryptionTest` |
| **chiave distrutta = dati illeggibili** anche se il file resta; blocco spostato o alterato non si apre; un nome non si sposta in un'altra voce | `FacesStoreEncryptionTest` |
| **chiave sparita = `FACES_KEY_LOST` senza crollo** (E-F19); un guasto passeggero non cancella; file danneggiato e versione futura | `FacesStoresTest` |
| **cancellazione come file**: la chiave prima, poi `.db`, `-wal`, `-shm`; verificata; ripetibile; azzeramento con tutte le chiavi prima dei file | `FacesStoresTest` |
| ogni tipo di voce `people` va e torna uguale, il nome non e' mai in chiaro, "da datare" | `FacesStoreStateTest` |
| nessuna generazione, nome, chiave o percorso nei log | `FacesStoresTest`, `FacesKeyStoreTest` |
| query su 20.000 volti in meno di 50 ms; piani senza `USE TEMP B-TREE` | `FacesDaoTest`, `FacesStorePerformanceTest` |
| la chiave: contratto, avvolta, distruzione in ordine, sparita contro passeggero, nessun alias orfano | `FacesKeyStoreTest` (JVM), `FacesKeyStoreInstrumentedTest` (telefono) |
| `bodiesOf`, `datePeople`, `dropAuthors` (P3): le tre prove di P3 nella suite di contratto dell'archivio passano sul database; il vincitore si rifa' togliendo un autore; i volti per il raggruppamento seguono | `FacesStoreContractTest`, `FacesDaoTest`, `RoomFacesSyncStoresTest` |
| la replica e il pubblicatore sul database passano le **suite di contratto degli stati** (le stesse degli stati in memoria di P3), anche dopo la chiusura e la riapertura del database | `FacesReplicaStoreContract`, `FacesPublishStoreContract`: `InMemory*ContractTest` (P3) e `RoomFaces*StoreContractTest` (P6) |
| le **prove di P3 intere** (replica, pubblicatore, isolamento, banco da 200 giri con guasti, morti del processo e stato locale perso) passano con ogni dispositivo sul suo database, e ogni "morte del processo" del banco chiude e riapre il file | `RoomFacesReplicaTest`, `RoomFacesPublisherTest`, `RoomFacesIsolationTest`, `RoomFacesSyncBenchTest` (sulle stesse classi di P3, con `RoomSyncStorage`) |
| nessun centroide e nessuna chiave di contenuto delle assegnazioni in chiaro nel file; nessuna riga dei blocchi oltre il milione di byte; una scrittura che fallisce lascia lo stato di prima; un blocco alterato fa rifare cio' che conteneva | `RoomFacesSyncStoresTest` |

### 15.9 Misure (JVM, Robolectric; il dispositivo si misura sul campo)

Un database cifrato vero con **6.700 contenuti e 20.100 volti** (CORE, WEAK e NONE), 300 persone con 20.000 assegnazioni e 1.100 voci `people`. Mediana di cinque giri dopo due
di riscaldamento (`FacesDaoTest`, `FacesStorePerformanceTest`).

| Misura | Esito |
|---|---|
| **query per chiave su 20.000 volti**: gia' fatto (200 chiavi), non riconoscibili (200), vincitori di un'unita' (200 chiavi, con la decifratura), vincitori di una schermata (30), quanti corpi e volti | **0-4 ms** (tetto del requisito: 50 ms); al livello del solo database 1-4 ms |
| piani | per chiave `SEARCH ... USING INDEX sqlite_autoindex_face_body_1`; ingresso del raggruppamento `USING INDEX index_face_body_is_winner_space_content_key (is_winner=? AND space=?)` senza `USE TEMP B-TREE`; volti `SEARCH face USING INDEX sqlite_autoindex_face_1` (anche dopo `ANALYZE`) |
| `groupFaces` **la prima volta** dopo l'apertura (legge 6 MB di blocchi e 18.700 righe di volti, decifra e decodifica tutti i vettori) | **~450 ms** (lettura intera dei soli corpi 89 ms, dei volti 227 ms) |
| `groupFaces` dalla seconda volta (in memoria, `GroupFaceCache`) | **0 ms** |
| `groupFaces` dopo un'unita' di 200 contenuti rifatti (solo la lettura) | **7 ms** |
| istantanea di 300 persone e 20.000 assegnazioni: scrittura intera / lettura intera | 139 ms / 85 ms |
| 1.100 voci `people`: unione (ognuna con la sua lettura) / lettura intera con la decifratura dei nomi | 99 ms / 18 ms |
| scrivere 6.700 contenuti (una transazione ciascuno, con la cifratura) | 1,9 s (0,29 ms l'uno) |

Il requisito "query su 20.000 volti in meno di 50 ms" vale per tutte le query che l'app fa a ogni schermata e a ogni unita'. La lettura **intera** per il raggruppamento (tutti i
vettori in chiaro) costa la prima volta circa 450 ms: sono 6 MB da leggere e decifrare, e il contratto (`groupFaces`) li vuole tutti; per questo la si fa **una volta** per negozio
aperto e poi si rileggono solo i contenuti salvati (`GroupFaceCache`). Sul telefono si rimisura (L112 dice cosa resta in memoria).

Lo **stato della sincronizzazione** (`RoomFacesSyncPerformanceTest`, stesse condizioni): pubblicatore con 3.000 chiavi in coda, una chiave in piu' ~0,5 ms (i primi cento
~2 ms, con il riscaldamento), i soli scalari ~0,3 ms; replica con l'istantanea di un dispositivo da 3.000 persone e 30.000 assegnazioni: primo salvataggio ~300 ms, lettura a freddo
(dopo la riapertura, decodifica e controllo dell'istantanea) ~650 ms, a caldo 0,1 ms, aggiornare un altro dispositivo ~0,8 ms. Il banco di P3 da 200 giri su tre mondi: ~10-16 s sul
database contro ~2,5 s in memoria.

### 15.10 Scelte e scarti

| Scelta | Alternative scartate e perche' |
|---|---|
| Un file per generazione, in `noBackupFilesDir` | Tabelle nel database della galleria (v7): una migrazione sul campo per una funzione che molti non attivano, e nessun modo di "cancellare un file" per verificare che non ci sia piu' (D15) |
| Vettori e landmark di un corpo in **un blocco sigillato**; riquadro e livello in chiaro | Un blocco per volto (3 volte le operazioni AES, 3 nonce, lo stesso costo di lettura); tutto cifrato, riquadri compresi (ogni query per livello o per posto dovrebbe decifrare: da 3 ms a centinaia); tutto in un solo blocco per database (ogni lettura decifra tutto) |
| Landmark cifrati (in piu' di D14) | Lasciarli in chiaro nelle righe `face`: sono geometria del volto e non servono a nessuna query (C40) |
| `aad` legato alla riga e controllo della chiave nel database | Nessun `aad` (un blocco si sposterebbe in un'altra riga); nessun controllo (con la chiave ricreata da altri il database si aprirebbe "vuoto" con righe che nessuno legge) |
| `is_winner` scritto nella transazione del corpo | Calcolare il vincitore a ogni lettura (una sottoquery correlata per riga, o una finestra: SQLite 3.22 di Android 10 non le ha); ricalcolare in memoria (ogni lettura del raggruppamento rilegge tutti gli autori) |
| L'istantanea del raggruppamento riscritta intera | Una differenza riga per riga (leggere, confrontare, scrivere: 139 ms per 20.000 assegnazioni non lo giustificano; il custode scrive a fine unita') |
| `GroupFaceCache` (vettori in memoria fino a 120.000 volti) | Rileggere e decifrare a ogni `groupFaces` (450 ms ogni unita'); una copia dei vettori in chiaro su disco (mai) |
| Chiave dati avvolta in un file accanto al database | La chiave del Keystore che cifra direttamente ogni riga (un'operazione del Keystore per riga: ms l'una, StrongBox decine); la chiave dati dentro il database (non si aprirebbe senza averla) |
| `FacesKeyProbe` (sparita contro non risponde) | Cancellare a ogni `open == null` (un guasto passeggero del Keystore farebbe perdere nomi che con la recinzione esistono solo qui); non cancellare mai (un database illeggibile per sempre) |
| Nessuna colonna per i centroidi | Una colonna `centroid` inutilizzata: lo schema esportato la promette e nessuno la scrive. Quando P5 ne avra' bisogno e' una colonna sigillata di `group_person`, quindi una v2 con una migrazione vera (mai distruttiva) |
| Lo stato della sincronizzazione sul database con una **differenza** e un'immagine in memoria | Riscrivere lo stato intero a ogni `save` (P4 ne fa uno per media: O(n) righe per salvataggio, O(n^2) in tutto); un blob unico per stato (un salvataggio riscrive anche l'istantanea da 12 MB per cambiare un numero); decodificare a ogni `load` (P5 lo chiede a ogni giro) |
| Le voci `cluster` come **segmenti del formato** sigillati | Una colonna per voce (decine di migliaia di righe con un vettore ciascuna, e un'altra tabella da tenere in pari con il codec); una forma binaria nostra (un secondo codec da provare contro il materiale ostile: quello di P3 rifiuta gia' tutto cio' che e' fuori dai tetti) |
| Una sola tabella di blocchi (`faces_chunk`) per replica e pubblicatore | Una tabella per ambito (tre tabelle uguali, con chiavi esterne: la cascata non serve, i blocchi si tolgono per nome nella stessa transazione) |
| `faces_head_mark`, `faces_outbox` e `faces_unit_done` di prima restano | Toglierli: `FacesHeadMarks` e `FacesOutbox` sono ridondanti con lo stato di P3 (`replica_device.mark_*`, `publish_*`) e nessun codice di produzione li usa oggi; restano finche' P4 non conferma che non li vuole, e si tolgono prima del rilascio della v1 |

## 16. Dati locali della Fase 9 (ricordi, notifiche, mappa)

**Nessuna tabella nuova nel database della galleria, che resta alla v6** (`docs/fase9-progetto.md` D10, D27; alternative al §3 del progetto: una
migrazione sul campo per una vista e per 40 KB di ricordi non si giustifica). I dati della Fase 9 sono **file locali** in `noBackupFilesDir`
(fuori dai backup, tolti dall'azzeramento, D37) e una query nuova sulle tabelle che ci sono. Niente di questo va sul NAS: sul NAS viaggiano solo
le voci `memory` e i campi `memories_*` dei dati personali (formato §5.5-bis, A1, A2).

| Dato | Dove | Formato | Chi | Note |
|---|---|---|---|---|
| **Scaffale dei ricordi** | `noBackupFilesDir/memories/shelf.bin` | versione del file e CRC in testa, scrittura atomica (temporaneo, `fsync`, rinomina, come `StateFiles`); il contenuto e' `ShelfFile` di `core-memories` (ricordi pubblicati con chiave, titolo come dati, al piu' 40 elementi in ordine, copertina, giorno di pubblicazione, origine; ultime pubblicazioni per tipo) | `FileShelfStore`, P6 | al piu' 60 ricordi e 30 giorni (~40 KB); un file rovinato vale "scaffale vuoto" e si rigenera (`MEMORY_SHELF_RESET`); suite `ShelfStoreContract` |
| **Misure dei pixel** | nessun file | nitidezza e luminanza (D6) misurate a ogni giro sulla miniatura **locale** a 256 px (`LocalMemoryPixels`, P13), al piu' 200 finalisti per ricordo entro il budget | P7 (funzione pura), P13 (decodifica) | solo le miniature del telefono, mai il NAS ne' la rete; con il risparmio energetico nessuna misura. Una cache su file non e' servita (P13): si aggiunge se le misure sull'S25 lo chiedono |
| **Registro delle notifiche** | `noBackupFilesDir/memories/notified` | gli ultimi 14 giorni con una notifica (`NotifyLedger`: giorno e "speciale"), scritto **prima** di pubblicare (E-NT5) | P10 | al piu' una notifica al giorno, Q4 |
| **Preferenze delle notifiche** | `noBackupFilesDir/memories/notify-prefs` | `NotifyPrefs` (accese, ora a passi di 15 minuti, frequenza, schermata di blocco), file atomico con CRC (`FileNotifySettings`) | P10 | locali per dispositivo e profilo (E-NT10), mai sincronizzate; un file rovinato vale le scelte di serie |
| **Notifica in tendina e ritardi** | `noBackupFilesDir/memories/notified-memory`, `memories/notify-late` | l'id del ricordo e della copertina in tendina, giorno e modalita' (mai un titolo); i giorni (`aaaammgg`) delle consegne oltre H+1 h negli ultimi 14 giorni | P10, P13 | per la revoca dopo la morte del processo (D19) e per `NOTIFY_LATE` (piano B di D20); esce solo il conteggio |
| **Conti della Diagnostica dei ricordi** | `noBackupFilesDir/memories/diag-counts` | una riga per giorno e per codice (`aaaammgg codice quanti`), solo codici fatti di lettere minuscole, cifre, `.` e `_`; gli ultimi 14 giorni (gli stessi di `notify-late`), scrittura atomica (`StateFiles`) | DG-1, DG-2 (correzioni del 31/10, `DayTally`) | esclusi per motivo, notifiche saltate per motivo, scaffali rigenerati, doppioni senza vettori, notifiche tolte e consegnate; solo numeri (mai un titolo, un luogo, una data di un ricordo, un id o un nome); esce solo il conteggio; un file illeggibile vale zero; tolto dall'azzeramento con la cartella `memories/` |
| **Musica della presentazione** | `noBackupFilesDir/memories/music` | una riga col tipo (`none`, `own`, `free`) e il riferimento `content://` del documento scelto con il selettore (o l'id del brano libero) | P13 (`FileMusicSettings`) | mai il file della canzone ne' un percorso; il permesso di lettura e' quello persistito del selettore, rilasciato a ogni cambio di canzone e all'azzeramento (I4, C414) |
| **Impostazioni della mappa** | DataStore in `noBackupFilesDir/map/settings/map.preferences_pb` | mappa online (di serie accesa, Q1 (c); spenta se l'utente la spegne), "Solo sul Wi-Fi", ultima camera | P4 | locali, mai sincronizzate; dentro `map/`, quindi l'azzeramento le toglie con la camera (D29, D37) |
| **Database di MapLibre** | `noBackupFilesDir/map/` (e `map/tilejson-version`) | il database offline di MapLibre (SQLite con le tessere vettoriali compresse cosi' come arrivano), cache delle tessere viste online e regioni scaricate | P1, P5 | tetti: 1 GB per regione, 3 GB in tutto, 1 GiB libero; non e' un database nostro: non ha migrazioni nostre |
| **Giudizi della valutazione dei ricordi** | `noBackupFilesDir/eval/memories/` | `MediaId` e giudizi (solo build debug) | P11 | mai nel repository; esporta solo numeri (§5.3 del progetto) |

**La query dei punti della mappa** (P3, `core-database/.../dao/MapPointsQueries.kt`, D27): `page(after: MediaId?, limit: Int)` sulle **sole celle
visibili** (non nascoste, una per gruppo, come le letture degli album del §14.4), con le **coordinate effettive** (la correzione di luogo di
`metadata_correction` se c'e', altrimenti `version_metadata.latitude/longitude`: la stessa regola del pannello info), anche per i media solo sul
NAS (il campo `geo` adottato dall'indice), il giorno locale, i flag, la sorgente, il tipo e la durata (per `MapCoverScore`) e il luogo; ordinata
per `media_id`, a pagine per chiave (2.000), **senza `USE TEMP B-TREE`** nel piano. Coordinate fuori dai limiti, (0, 0) esatto e latitudini
oltre +-85 gradi si scartano e si contano (E-MAP7). Nessun indice nuovo: si legge all'apertura della mappa e dopo i cambi dell'istantanea
(~100.000 righe in meno di 400 ms sulla JVM); l'indice in memoria (`GeoClusterIndex`, ~24 byte a punto) sta in `core-map`.

**Azzeramento** (D37): `MemoriesGraph.stop()` e `MapGraph.stop()` per primi, poi `AppLocalDataEraser` toglie `memories/`, `map/`, `eval/memories/`
e le preferenze; il lavoro `memories-notify` si annulla e la notifica si toglie (P13), **sempre**, anche quando si tolgono i soli dati di un account con
la galleria mai partita in questo processo (`Phase9EraseTest`).
