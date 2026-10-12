# Modello dei dati (Fase 1.3)

> Obiettivo (piano 1.3): un media ha **identita' stabile**, **piu' posizioni** e **piu' versioni**. Backup,
> spostamenti, condivisioni, modifiche, correzioni e cambi di ID MediaStore non spezzano mai album,
> preferiti, nascosti, vettori o stati di lavoro.
>
> Codice: tipi e regole in `core-model` (Kotlin puro, `dev.pampa.mycloudgallery.core.model` e `.domain`),
> database Room v1 in `core-database`. Il KDoc spiega il perche' di ogni tipo; qui c'e' il quadro d'insieme.

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
  +----+----------------------+-----------------------+-------------------+---------+
       | 1:N                  | 1:1                   | 1:N               | 1:N
       v                      v                       v                   v
  media_version         media_personal           work_state           embedding
   version_id (UUID)     favorite hidden          (media, fase)        (media, spazio+dim+prec, indice)
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
   phone: volume, percorso, nome, cache _ID + versione MediaStore
   nas:   account, share, percorso
   size, modified_at, state, absent_since, verified_at

  nas_share (account, share, area PRIVATE|COMMON)  <- configurazione di famiglia
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
| Ogni tabella che punta a `media` e' gestita dall'unione | `MergeReferencesTest` |

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

## 6. Database v1

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

- **Identita' tra dispositivi (Fase 4)**: chi pubblica per primo un media nell'indice condiviso ne fissa il
  `MediaId`; gli altri lo adottano. I conflitti (due UUID per lo stesso file) si risolvono con la stessa unione
  deterministica e gli alias. Da progettare con il formato firmato della 1.4.
- **Rilevatore di doppioni**: chi chiama `merge` quando un hash completo arriva dopo (oggi lo fa da solo solo una
  terza copia identica). Fase 4.4.
- **Cursori di sincronizzazione MediaStore** (generazione per volume, `getVersion()`): Fase 3.1.
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
