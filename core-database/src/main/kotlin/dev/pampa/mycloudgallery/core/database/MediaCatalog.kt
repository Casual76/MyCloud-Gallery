package dev.pampa.mycloudgallery.core.database

import androidx.room.withTransaction
import dev.pampa.mycloudgallery.core.database.dao.BackupQueries
import dev.pampa.mycloudgallery.core.database.dao.CellVersionRow
import dev.pampa.mycloudgallery.core.database.dao.TimelineIndexDao
import dev.pampa.mycloudgallery.core.database.entity.BackupCandidateRow
import dev.pampa.mycloudgallery.core.database.entity.BackupProblemEntity
import dev.pampa.mycloudgallery.core.database.entity.NasAdoptedFingerprintEntity
import dev.pampa.mycloudgallery.core.database.entity.EmbeddingEntity
import dev.pampa.mycloudgallery.core.database.entity.IndexRow
import dev.pampa.mycloudgallery.core.database.entity.MediaAliasEntity
import dev.pampa.mycloudgallery.core.database.entity.MediaEntity
import dev.pampa.mycloudgallery.core.database.entity.MediaGroupEntity
import dev.pampa.mycloudgallery.core.database.entity.MediaLocationEntity
import dev.pampa.mycloudgallery.core.database.entity.MediaPersonalEntity
import dev.pampa.mycloudgallery.core.database.entity.MetadataCorrectionEntity
import dev.pampa.mycloudgallery.core.database.entity.NasCopyMismatchEntity
import dev.pampa.mycloudgallery.core.database.entity.NasCopyRow
import dev.pampa.mycloudgallery.core.database.entity.NasRefRow
import dev.pampa.mycloudgallery.core.database.entity.SimilarCandidateRow
import dev.pampa.mycloudgallery.core.database.entity.VisualSignatureEntity
import dev.pampa.mycloudgallery.core.model.AccountId
import dev.pampa.mycloudgallery.core.model.CaptureTime
import dev.pampa.mycloudgallery.core.model.CaptureTimeConfidence
import dev.pampa.mycloudgallery.core.model.CaptureTimeSource
import dev.pampa.mycloudgallery.core.model.CellFlags
import dev.pampa.mycloudgallery.core.model.ContentKey
import dev.pampa.mycloudgallery.core.model.CorrectionField
import dev.pampa.mycloudgallery.core.model.EmbeddingVector
import dev.pampa.mycloudgallery.core.model.FileStamp
import dev.pampa.mycloudgallery.core.model.Fingerprint
import dev.pampa.mycloudgallery.core.model.FingerprintMatch
import dev.pampa.mycloudgallery.core.model.GeoPoint
import dev.pampa.mycloudgallery.core.model.Location
import dev.pampa.mycloudgallery.core.model.LocationId
import dev.pampa.mycloudgallery.core.model.LocationKey
import dev.pampa.mycloudgallery.core.model.LocationState
import dev.pampa.mycloudgallery.core.model.Media
import dev.pampa.mycloudgallery.core.model.MediaGroupId
import dev.pampa.mycloudgallery.core.model.MediaGroupKind
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.model.MediaLocation
import dev.pampa.mycloudgallery.core.model.MediaMetadata
import dev.pampa.mycloudgallery.core.model.MediaRef
import dev.pampa.mycloudgallery.core.model.MediaSource
import dev.pampa.mycloudgallery.core.model.MediaVersion
import dev.pampa.mycloudgallery.core.model.NasArea
import dev.pampa.mycloudgallery.core.model.NasAreaLookup
import dev.pampa.mycloudgallery.core.model.NasPath
import dev.pampa.mycloudgallery.core.model.NasShare
import dev.pampa.mycloudgallery.core.model.NasStamp
import dev.pampa.mycloudgallery.core.model.PersonalFlags
import dev.pampa.mycloudgallery.core.model.Presence
import dev.pampa.mycloudgallery.core.model.QuickHash
import dev.pampa.mycloudgallery.core.model.SafetyStatus
import dev.pampa.mycloudgallery.core.model.Sha256
import dev.pampa.mycloudgallery.core.model.VersionId
import dev.pampa.mycloudgallery.core.model.VersionKind
import dev.pampa.mycloudgallery.core.model.VisualSignature
import dev.pampa.mycloudgallery.core.model.WorkStage
import dev.pampa.mycloudgallery.core.model.WorkState
import dev.pampa.mycloudgallery.core.model.WorkStatus
import dev.pampa.mycloudgallery.core.model.domain.AdoptRefusal
import dev.pampa.mycloudgallery.core.model.domain.AdoptionDecision
import dev.pampa.mycloudgallery.core.model.domain.ContentCandidate
import dev.pampa.mycloudgallery.core.model.domain.IdentityRules
import dev.pampa.mycloudgallery.core.model.domain.GroupMember
import dev.pampa.mycloudgallery.core.model.domain.KnownLocation
import dev.pampa.mycloudgallery.core.model.domain.LinkContext
import dev.pampa.mycloudgallery.core.model.domain.LinkDecision
import dev.pampa.mycloudgallery.core.model.domain.LinkingRules
import dev.pampa.mycloudgallery.core.model.domain.LocationRules
import dev.pampa.mycloudgallery.core.model.domain.MergePlan
import dev.pampa.mycloudgallery.core.model.domain.MergeRefusal
import dev.pampa.mycloudgallery.core.model.domain.MergeRules
import dev.pampa.mycloudgallery.core.model.domain.Observation
import dev.pampa.mycloudgallery.core.model.domain.RemoteIdentity
import dev.pampa.mycloudgallery.core.model.domain.SafetyReport
import dev.pampa.mycloudgallery.core.model.domain.SafetyRules
import dev.pampa.mycloudgallery.core.model.domain.TimelineRules
import dev.pampa.mycloudgallery.core.model.domain.VersionPlan
import dev.pampa.mycloudgallery.core.model.domain.VersionRules
import dev.pampa.mycloudgallery.core.model.domain.WorkRules
import dev.pampa.mycloudgallery.core.model.enumByCode
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.ZoneId

/**
 * Il catalogo dei media: applica **in una transazione** le decisioni delle regole di dominio
 * (`core-model/domain`) al database. E' l'unico punto che scrive il grafo media/versioni/posizioni,
 * cosi' i campi derivati (`presence`, `safety`, `sort_time` e, dalla v2, quelli dell'indice della
 * timeline) e gli stati di lavoro restano coerenti con cio' che dicono le regole.
 *
 * Le regole decidono, il catalogo esegue: qui non c'e' logica su "che cosa e' lo stesso media".
 *
 * **Scritture e cambiamenti (v2).** Ogni scrittura pubblica e' una transazione sola: alla fine i campi
 * derivati dei media toccati si ricalcolano (`TimelineRules`, `SafetyRules`), e se l'indice della
 * timeline e' cambiato `change_seq` sale di uno; **dopo il commit** [changes] annuncia il
 * [CatalogChange]. Le scritture sono in fila (un `Mutex`): i numeri di sequenza escono in ordine. Non
 * vanno chiamate dentro un'altra transazione (falliscono): l'annuncio arriverebbe prima del commit vero.
 * Un catalogo per database: il grafo dell'app ne costruisce uno.
 *
 * [localAccount] e' l'account NAS di questo dispositivo (o profilo): il proprietario dei media del
 * telefono e il riferimento per "al sicuro". [zone] e' il fuso del telefono, usato solo per il giorno
 * provvisorio di un media appena visto, prima che META legga la data vera (E-P5). [clock] e' l'ora di
 * riferimento (piano §1: dell'ora del NAS quando c'e').
 */
class MediaCatalog(
  private val database: GalleryDatabase,
  private val localAccount: AccountId,
  private val zone: () -> ZoneId = ZoneId::systemDefault,
  // Ultimo, cosi' resta la lambda finale di chi costruisce il catalogo con un orologio: `MediaCatalog(db, me) { now }`.
  private val clock: () -> Long = System::currentTimeMillis,
) {

  private val mediaDao = database.mediaDao()
  private val versionDao = database.versionDao()
  private val locationDao = database.locationDao()
  private val shareDao = database.nasShareDao()
  private val workDao = database.workStateDao()
  private val embeddingDao = database.embeddingDao()
  private val personalDao = database.personalDao()
  private val mergeDao = database.mergeDao()
  private val indexDao = database.timelineIndexDao()
  private val metaDao = database.catalogMetaDao()
  private val cursorDao = database.mediaStoreCursorDao()
  private val nasScanDao = database.nasScanDao()
  private val identityDao = database.identityDao()
  private val problemDao = database.backupProblemDao()
  private val mismatchDao = database.nasCopyMismatchDao()
  private val signatureDao = database.visualSignatureDao()
  private val backupQueryDao = database.backupQueryDao()
  private val similarDao = database.similarDao()
  private val correctionDao = database.metadataCorrectionDao()
  private val cellContentDao = database.cellContentDao()

  /**
   * Fase 6 (P1, schema v5): l'indice locale dei dati AI per chiave di contenuto (vettori, testo letto con FTS4, vocabolario) e la
   * cronologia della ricerca. Non tocca il grafo dei media e non annuncia [changes].
   */
  val aiIndex: AiIndexCatalog = AiIndexCatalog(database)

  /**
   * Fase 7 (P1, schema v6): le letture degli album (candidati degli smart e dei viaggi, fatti per la condivisione, attestazione degli
   * elementi condivisi, Doppioni). Solo letture: non scrive il grafo dei media e non annuncia [changes].
   */
  val albums: AlbumCatalog = AlbumCatalog(database, localAccount)

  private val writeLock = Mutex()
  private val changeFlow = MutableSharedFlow<CatalogChange>(
    extraBufferCapacity = CHANGE_BUFFER,
    onBufferOverflow = BufferOverflow.DROP_OLDEST,
  )

  /**
   * I cambiamenti dell'indice, uno per transazione, **dopo il commit**, con `seq` che sale di uno alla
   * volta. Nessuna ripetizione per chi arriva tardi: si parte da [changeSeq] e dall'indice letto. Chi
   * resta indietro di oltre [CHANGE_BUFFER] cambiamenti perde i piu' vecchi e lo vede dal salto di `seq`.
   */
  val changes: SharedFlow<CatalogChange> = changeFlow.asSharedFlow()

  private val revertFlow = MutableSharedFlow<MediaId>(extraBufferCapacity = CHANGE_BUFFER, onBufferOverflow = BufferOverflow.DROP_OLDEST)

  /**
   * P14 (E-E2, nota di P8): i media che la lettura del telefono ha visto **tornare a una versione nota** (Samsung "Ripristina originale",
   * `LinkDecision.RevertedToVersion`), dopo il commit. Il backup ne scambia le versioni sul NAS (`NasVersionEngine.planRealign`). Chi si perde un
   * annuncio (processo morto) li ritrova con [mediaWithVersions] all'avvio.
   */
  val reverts: SharedFlow<MediaId> = revertFlow.asSharedFlow()

  /** P14: i media con piu' di una versione (al piu' [limit]): l'avvio ricontrolla se le loro versioni sul NAS vanno rimesse in pari. */
  suspend fun mediaWithVersions(limit: Int): List<MediaId> {
    require(limit > 0) { "Il tetto e' positivo." }
    return backupQueryDao.mediaWithVersions(limit).map(::MediaId)
  }

  /** Che cosa fare quando le regole chiedono l'hash completo e chi chiama non puo' calcolarlo. */
  enum class WhenUndecided {
    /** Non toccare nulla: si riprovera' con l'hash completo. */
    WAIT,

    /** Creare un media separato (nel dubbio non si unisce); si unira' quando le impronte lo proveranno. */
    KEEP_SEPARATE,
  }

  /** Esito di [record]: la decisione applicata e, se c'e', dove e' finita l'osservazione. */
  data class RecordOutcome(
    val decision: LinkDecision,
    val mediaId: MediaId?,
    val locationId: LocationId?,
  )

  sealed interface MergeOutcome {
    data class Merged(val survivor: MediaId, val loser: MediaId) : MergeOutcome

    data class Refused(val reason: MergeRefusal) : MergeOutcome

    data object NotFound : MergeOutcome
  }

  // --- Osservazioni delle scansioni ------------------------------------------------------------------

  /**
   * Registra cio' che una scansione ha visto in un posto (piano 3.1, 4.2).
   *
   * [hidden]: il media nasce **nascosto**, nella stessa transazione che lo registra (il ritaglio salvato dai Nascosti, E-V11, VR-1). Nessun istante in cui
   * il media e' visibile e nessun cambiamento intermedio da annunciare: l'indice della Libreria non lo vede mai senza il suo "nascosto". Se il media
   * c'era gia' (stessi byte) diventa nascosto, come `setPersonalFlags` (si sbaglia dal lato della riservatezza, E-H1).
   */
  suspend fun record(observation: Observation, whenUndecided: WhenUndecided = WhenUndecided.WAIT, hidden: Boolean = false): RecordOutcome =
    write { tx ->
      val outcome = recordOne(tx, observation, whenUndecided, seenAt = null)
      if (hidden) {
        outcome.mediaId?.let { id ->
          val current = personalDao.get(id.value)?.toDomain() ?: PersonalFlags()
          writePersonal(tx, id, current.copy(hidden = true))
        }
      }
      outcome
    }

  /**
   * Registra un lotto di osservazioni (un lotto di 500 righe del MediaStore) in **una** transazione, con un
   * solo cambiamento annunciato: o tutte o nessuna. Gli esiti sono nell'ordine delle osservazioni; un
   * posto osservato due volte nello stesso lotto vale come due osservazioni in fila.
   *
   * [cursor], se c'e', si salva nella stessa transazione (la ripresa dopo un riavvio non perde ne' ripete
   * nulla, E-P4). [seenAt] e' l'istante "visto" da scrivere nelle posizioni (di norma l'inizio della
   * scansione, `scanEpoch`), cosi' [markVanishedNotSeenSince] non dipende da salti dell'orologio a meta'
   * giro; senza, l'ora di [clock].
   *
   * **NAS (v3).** [nasCursor] e' il cursore della scansione del NAS (`nas_scan_state`), salvato nella stessa transazione
   * come [cursor] per il MediaStore; [seenAt] e' l'`epoch` del giro (ora del NAS). Con [WhenUndecided.KEEP_SEPARATE] un
   * file con la stessa impronta rapida di un media che sta **dall'altra parte** (telefono e NAS) e senza hash completo
   * nasce separato ma in un gruppo provvisorio `PROBABLE_DUPLICATE` (D21, E-L9): una cella sola "da verificare", con la
   * copia del telefono come copertina, e `FULL_HASH` in coda per le copie del NAS. Quando gli hash completi arrivano il
   * gruppo si risolve da solo: stessi byte, unione (`MergeRules`); byte diversi, di nuovo separati. Mai un'unione senza
   * hash completo.
   *
   * [inSameTransaction], se c'e', gira nella stessa transazione dopo le osservazioni (es. P4: le righe di `index_record`
   * e il `HeadMark` del segmento applicato, con i DAO di [GalleryDatabase]): o tutto o niente.
   *
   * **Impronte adottate** (R6 della revisione 4A, D8). Con [fingerprintAdopted] l'impronta rapida delle osservazioni non e'
   * stata letta qui ma viene da un record dell'indice di un altro dispositivo: niente legami per contenuto (nessun media del
   * telefono ne' del NAS con la stessa impronta, nessuno spostamento riconosciuto dall'impronta, nessun gruppo provvisorio) e
   * la posizione resta segnata "adottata" ([KnownNasLocation.adoptedQuickHash]) finche' la scansione non rileva il file
   * qui. Senza, le posizioni del NAS registrate perdono il segno: l'impronta e' quella letta adesso.
   */
  suspend fun recordAll(
    observations: List<Observation>,
    whenUndecided: WhenUndecided = WhenUndecided.WAIT,
    cursor: MediaStoreCursor? = null,
    seenAt: Long? = null,
    nasCursor: NasScanState? = null,
    fingerprintAdopted: Boolean = false,
    inSameTransaction: (suspend () -> Unit)? = null,
  ): List<RecordOutcome> = write { tx ->
    val outcomes = observations.map { recordOne(tx, it, whenUndecided, seenAt, fingerprintAdopted) }
    cursor?.let { saveCursor(tx, it) }
    nasCursor?.let { nasScanDao.upsert(it.toEntity()) }
    inSameTransaction?.invoke()
    outcomes
  }

  /**
   * La **scorciatoia del timbro** (`docs/fase3-progetto.md` §2.1): nello stesso posto c'e' una posizione
   * conosciuta, presente o non raggiungibile, con la stessa dimensione e la stessa data di modifica (e la
   * stessa generazione, salvo [ignoreGeneration] dopo un cambio di versione del MediaStore, E-P22). Allora
   * e' lo stesso file e non si legge un byte: si aggiornano solo "visto a" ([seenAt]), il timbro e la cache
   * del MediaStore (`_ID` e versione nuovi dopo una rinumerazione).
   *
   * Falso se la scorciatoia non vale (posto sconosciuto, posizione assente, timbro diverso): chi chiama
   * calcola l'impronta e usa [recordAll]. Una posizione assente non si conferma mai a occhio: puo' essere
   * un altro file con lo stesso nome (E-P11 passa dall'impronta).
   */
  suspend fun confirmPresent(phone: Location.Phone, stamp: FileStamp, seenAt: Long, ignoreGeneration: Boolean = false): Boolean =
    write { tx -> confirmOne(tx, phone, stamp, seenAt, ignoreGeneration) }

  /** [confirmPresent] per un lotto, in una transazione, con [cursor] salvato insieme. */
  suspend fun confirmAllPresent(
    rows: List<PhoneStamp>,
    seenAt: Long,
    ignoreGeneration: Boolean = false,
    cursor: MediaStoreCursor? = null,
  ): List<Boolean> = write { tx ->
    val confirmed = rows.map { confirmOne(tx, it.location, it.stamp, seenAt, ignoreGeneration) }
    cursor?.let { saveCursor(tx, it) }
    confirmed
  }

  /**
   * Fine di una scansione **completa** di [volume] iniziata a [epoch] (solo con accesso completo: con
   * l'accesso parziale non si segna mai un'assenza, E-P1, E-P15): le posizioni del volume non viste dal
   * giro diventano assenti, con la tolleranza di `LocationRules` (cestino di Samsung, E-P11).
   *
   * **Con tetto**: al massimo [cap] posizioni per chiamata (le viste meno di recente prima), ognuna
   * chiamata una transazione e un cambiamento. Restituisce quante ne ha segnate: chi chiama ripete finche'
   * il numero e' uguale a [cap]. Cosi' una transazione non tiene il database per 100 mila righe e chi
   * chiama puo' fermarsi se le assenze sono sospette. [cursor], se c'e', si salva insieme.
   */
  suspend fun markVanishedNotSeenSince(volume: String, epoch: Long, cap: Int, cursor: MediaStoreCursor? = null): Int {
    require(cap > 0) { "Il tetto e' positivo." }
    return write { tx ->
      val now = clock()
      val unseen = locationDao.notSeenSince(volume, epoch, cap)
      unseen.forEach { entity ->
        locationDao.update(LocationRules.vanished(entity.toDomain(), now).toEntity())
        tx.touch(mediaOf(LocationId(entity.locationId)))
      }
      cursor?.let { saveCursor(tx, it) }
      unseen.size
    }
  }

  /** Un posto non contiene piu' il file (riga sparita dal MediaStore, file non trovato sul NAS). */
  suspend fun markVanished(key: LocationKey): Boolean = write { tx ->
    val entity = locationDao.byKey(key.value) ?: return@write false
    val location = entity.toDomain()
    locationDao.update(LocationRules.vanished(location, clock()).toEntity())
    tx.touch(mediaOf(location.id))
    true
  }

  /** Un volume del telefono non e' piu' montato (microSD tolta, E-P6): niente e' stato cancellato. */
  suspend fun markVolumeUnavailable(volume: String): Unit = write { tx ->
    locationDao.presentOnVolume(volume).forEach { entity ->
      val location = entity.toDomain()
      locationDao.update(LocationRules.unavailable(location).toEntity())
      tx.touch(mediaOf(location.id))
    }
  }

  /**
   * Dimentica le posizioni assenti oltre la tolleranza. I media restano, con le altre posizioni.
   *
   * [cap]: al massimo tante posizioni per giro, le assenti da piu' tempo prima (E-P9: un salto in avanti
   * dell'orologio non svuota la libreria in un colpo; la manutenzione quotidiana passa [cap]).
   */
  suspend fun purgeExpiredAbsences(cap: Int = Int.MAX_VALUE): Int = write { tx ->
    require(cap > 0) { "Il tetto e' positivo." }
    val now = clock()
    val shortest = minOf(LocationRules.PHONE_ABSENCE_TOLERANCE_MILLIS, LocationRules.NAS_ABSENCE_TOLERANCE_MILLIS)
    val expired = locationDao.absentSince(now - shortest).map { it.toDomain() }.filter { LocationRules.isExpired(it, now) }.take(cap)
    expired.forEach { tx.touch(mediaOf(it.id)) }
    expired.forEach { locationDao.delete(it.id.value) }
    expired.size
  }

  // --- Fase 4: il NAS (schema v3; porta `NasScanStore` di core-nasmedia, `docs/fase4-progetto.md` §2.3) -------------

  /** Il cursore della scansione di [share] per [account], se c'e' (`NasScanStore.cursor`). */
  suspend fun nasScanState(account: AccountId, share: String): NasScanState? =
    nasScanDao.get(account.value, Location.Nas.shareKeyOf(share))?.toDomain()

  /** I cursori di tutte le share (Diagnostica, stato del congelamento all'avvio). */
  suspend fun nasScanStates(): List<NasScanState> = nasScanDao.all().map { it.toDomain() }

  /**
   * Salva il cursore da solo (inizio o fine di un giro, congelamento deciso o tolto: `NasScanStore.saveCursor`). Non tocca
   * l'indice della timeline: nessun cambiamento annunciato.
   */
  suspend fun saveNasScanState(state: NasScanState): Unit = write { nasScanDao.upsert(state.toEntity()) }

  /**
   * I file noti (presenti o non raggiungibili, non gli assenti) che stanno direttamente nella cartella [folder] della share
   * (`null` = la radice), per chiave di posto (`NasScanStore.knownIn`): la scansione li confronta con l'elenco senza
   * leggere byte (D9).
   */
  suspend fun knownNasFiles(account: AccountId, share: String, folder: NasPath?): Map<LocationKey, KnownNasLocation> {
    val rows = locationDao.knownInFolder(Location.Nas.folderKey(account, share, folder))
    // R6: le impronte adottate dall'indice (e non lette qui) della cartella.
    val adopted = HashMap<Long, String>()
    rows.map { it.locationId }.chunked(TimelineIndexDao.MAX_IDS).forEach { chunk ->
      nasScanDao.adoptedAmong(chunk).forEach { adopted[it.locationId] = it.quickHash }
    }
    return rows.associate { entity ->
      LocationKey(entity.locationKey) to KnownNasLocation(
        locationId = LocationId(entity.locationId),
        stamp = NasStamp(entity.size, entity.modifiedAt, entity.fileId),
        state = enumByCode(entity.state),
        adoptedQuickHash = adopted[entity.locationId]?.let { runCatching { QuickHash(it) }.getOrNull() },
      )
    }
  }

  /** Quanti file presenti ha la share nel catalogo (le soglie del congelamento, D10; `NasScanStore.countPresent`). */
  suspend fun countPresentNas(account: AccountId, share: String): Int =
    locationDao.countPresentInShare(account.value, Location.Nas.shareKeyOf(share))

  /**
   * La **scorciatoia del timbro** per il NAS (D9; `NasScanStore.confirmAllPresent`), in una transazione con [cursor]: dove
   * il posto e' noto, non assente e con lo stesso timbro (`NasStamp.sameAs`: dimensione e data uguali, `FileId` uguale se
   * noto da entrambe le parti) si aggiornano solo "visto a" ([seenAt], l'`epoch` del giro), lo stato (una share tornata
   * raggiungibile) e il nome com'e' scritto (solo maiuscole cambiate, E-N7) o il `FileId` appena letto. Falso dove non
   * vale: chi chiama rileva il file e usa [recordAll].
   */
  suspend fun confirmAllPresentNas(seen: List<SeenNasFile>, seenAt: Long, cursor: NasScanState? = null): List<Boolean> = write { tx ->
    val confirmed = seen.map { confirmNas(tx, it, seenAt) }
    cursor?.let { nasScanDao.upsert(it.toEntity()) }
    confirmed
  }

  /**
   * Fine di un giro **completo** di una share iniziato a [epoch] (`NasScanStore.markVanishedInShare`): le posizioni della
   * share non viste dal giro diventano assenti, con la tolleranza di 30 giorni di `LocationRules` (E-N3; uno spostamento
   * visto al giro dopo si ricollega, E-N1). Al massimo [cap] per chiamata (le viste meno di recente prima): chi chiama
   * ripete finche' il numero e' uguale a [cap].
   *
   * **Con la share congelata (D10) non segna nulla e restituisce 0**, anche se chi chiama se ne dimentica: le posizioni
   * restano "da verificare" (E-S11, C11). [cursor], se c'e', si salva insieme.
   */
  suspend fun markVanishedInShare(account: AccountId, share: String, epoch: Long, cap: Int, cursor: NasScanState? = null): Int {
    require(cap > 0) { "Il tetto e' positivo." }
    return write { tx ->
      val shareKey = Location.Nas.shareKeyOf(share)
      if (nasScanDao.get(account.value, shareKey)?.freezeReason != null) return@write 0
      val now = clock()
      val unseen = locationDao.notSeenInShareSince(account.value, shareKey, epoch, cap)
      unseen.forEach { entity ->
        locationDao.update(LocationRules.vanished(entity.toDomain(), now).toEntity())
        tx.touch(mediaOf(LocationId(entity.locationId)))
      }
      cursor?.let { nasScanDao.upsert(it.toEntity()) }
      unseen.size
    }
  }

  /**
   * La share non e' piu' accessibile (E-R10, E-I8: accesso negato in due giorni diversi; `NasScanStore.markShareUnavailable`):
   * le sue posizioni presenti diventano "non raggiungibili", **non** assenti (niente e' stato cancellato). Al massimo [cap]
   * per chiamata (una transazione non tiene il database per 60 mila righe): chi chiama ripete finche' torna [cap].
   */
  suspend fun markShareUnavailable(account: AccountId, share: String, cap: Int = DEFAULT_NAS_CAP): Int {
    require(cap > 0) { "Il tetto e' positivo." }
    return write { tx ->
      val rows = locationDao.presentInShare(account.value, Location.Nas.shareKeyOf(share), cap)
      rows.forEach { entity ->
        locationDao.update(LocationRules.unavailable(entity.toDomain()).toEntity())
        tx.touch(mediaOf(LocationId(entity.locationId)))
      }
      rows.size
    }
  }

  /**
   * L'adozione delle identita' pubblicate da altri dispositivi (D7, E-I18; `IdentityRules`), in **una** transazione: per
   * ogni [claims] il media del posto adotta gli id remoti minori, tiene i suoi, si unisce al media che l'id remoto indica
   * qui (solo con hash completi uguali) o rifiuta (`AdoptRefusal`: l'id remoto e' gia' legato qui a un contenuto diverso).
   * Un id adottato lascia il vecchio come alias. [inSameTransaction] come in [recordAll].
   */
  suspend fun adoptIdentities(claims: List<IdentityClaim>, inSameTransaction: (suspend () -> Unit)? = null): List<AdoptOutcome> =
    write { tx ->
      val outcomes = claims.map { adoptOne(tx, it) }
      inSameTransaction?.invoke()
      outcomes
    }

  /** [adoptIdentities] per un posto solo. */
  suspend fun adoptIdentity(location: LocationKey, remote: RemoteIdentity): AdoptOutcome =
    adoptIdentities(listOf(IdentityClaim(location, remote))).single()

  /**
   * Come leggere adesso i byte di [ids] dal NAS (D18; miniature dalla replica, viewer): la posizione presente sul NAS
   * (prima le copie della versione corrente, poi la posizione piu' vecchia) e la chiave di contenuto ridotta dei suoi
   * byte. Il percorso non esce di qui: si risolve dalla posizione solo quando serve aprire l'originale ([nasLocation]).
   */
  suspend fun nasRefs(ids: Collection<MediaId>): Map<MediaId, MediaRef.Nas> =
    ids.map { it.value }.distinct().chunked(TimelineIndexDao.MAX_IDS)
      .flatMap { indexDao.nasRefs(it) }
      .groupBy { it.mediaId }
      .mapKeys { MediaId(it.key) }
      .mapValues { (_, rows) ->
        // Prima le copie della versione corrente, poi quelle presenti (una "non raggiungibile" vale solo se e' l'unica: le
        // miniature vengono comunque dalla replica, E-L4), poi la piu' vecchia.
        val row = rows.minWith(
          compareBy<NasRefRow>({ if (it.isCurrent) 0 else 1 }, { if (it.state == LocationState.PRESENT.code) 0 else 1 }, { it.locationId }),
        )
        MediaRef.Nas(row.locationId, ContentKey.of(QuickHash(row.quickHash), row.size).short, row.mimeType)
      }

  /** La posizione del NAS di un [MediaRef.Nas] (per aprire l'originale), se c'e' ancora. */
  suspend fun nasLocation(locationId: LocationId): Location.Nas? = locationDao.get(locationId.value)?.toDomain()?.location as? Location.Nas

  // --- P11: le operazioni aggiunte da P5 alla porta `NasScanStore` e quelle della porta `ReplicaCatalog` di P4 ----------

  /** Le posizioni della share per l'account, in qualunque stato (la replica le confronta per percorso, D6). */
  suspend fun nasLocationsInShare(account: AccountId, share: String): List<NasLocationInfo> =
    locationDao.inShare(account.value, Location.Nas.shareKeyOf(share)).mapNotNull { it.toNasInfo() }

  /** La posizione del NAS in quel posto, se c'e' (qualunque stato). */
  suspend fun nasLocationAt(location: Location.Nas): NasLocationInfo? = locationDao.byKey(location.key.value)?.toNasInfo()

  /**
   * Le posizioni della share con uno di questi `FileId` (E-N1), a qualunque percorso e anche assenti: per ogni `FileId` al
   * piu' una, la piu' vecchia (`NasScanStore.findByFileId`).
   */
  suspend fun nasByFileIds(account: AccountId, share: String, fileIds: Collection<Long>): Map<Long, NasLocationInfo> {
    val out = LinkedHashMap<Long, NasLocationInfo>()
    fileIds.distinct().chunked(TimelineIndexDao.MAX_IDS).forEach { chunk ->
      locationDao.byFileIds(account.value, Location.Nas.shareKeyOf(share), chunk).forEach { entity ->
        val info = entity.toNasInfo() ?: return@forEach
        val id = entity.fileId ?: return@forEach
        if (id !in out) out[id] = info
      }
    }
    return out
  }

  /**
   * Gli spostamenti riconosciuti dalla scansione (E-N1, `NasScanStore.relocateAll`) in **una** transazione con [cursor]: la
   * posizione cambia posto e timbro, resta della stessa versione e dello stesso media (identita', preferiti, preparazione e
   * verifica conservati), torna presente con "visto a" [seenAt]. Falso dove il posto d'arrivo e' gia' di un'altra posizione.
   */
  suspend fun relocateNas(moves: List<NasRelocation>, seenAt: Long, cursor: NasScanState? = null): List<Boolean> = write { tx ->
    val result = moves.map { move ->
      val entity = locationDao.get(move.locationId.value) ?: return@map false
      val taken = locationDao.byKey(move.to.key.value)
      if (taken != null && taken.locationId != entity.locationId) return@map false
      val before = entity.toDomain()
      if (before.location !is Location.Nas) return@map false
      val after = before.copy(
        location = move.to.copy(fileId = move.stamp.fileId ?: move.to.fileId),
        stamp = move.stamp.toFileStamp(),
        state = LocationState.PRESENT,
        absentSinceMillis = null,
        lastSeenAtMillis = seenAt,
      )
      locationDao.update(after.toEntity())
      val mediaId = mediaOf(before.id)
      tx.touch(mediaId)
      tx.refChanged(mediaId)
      true
    }
    cursor?.let { nasScanDao.upsert(it.toEntity()) }
    result
  }

  /** Quante posizioni (presenti o non raggiungibili) della share il giro iniziato a [epoch] non ha visto (D10). */
  suspend fun countUnseenNas(account: AccountId, share: String, epoch: Long): Int =
    locationDao.countNotSeenInShare(account.value, Location.Nas.shareKeyOf(share), epoch)

  /**
   * Esclusioni (D11, E-N14, E-N15; `NasScanStore.retireExcluded`): le posizioni non assenti sotto [folders] o con una di
   * queste [keys] escono dalla Libreria **per scelta**, quindi diventano assenti subito. Una transazione; quante ne ha tolte.
   */
  suspend fun retireNas(account: AccountId, share: String, folders: List<NasPath>, keys: List<LocationKey>): Int = write { tx ->
    // R9 (revisione 4A): come le assenze, niente esce dalla Libreria da una share congelata (D10), anche se chi chiama se
    // ne dimentica: le copertine "nuove" (un file audio messo in ogni cartella) non devono svuotarla mentre e' ferma.
    if (nasScanDao.get(account.value, Location.Nas.shareKeyOf(share))?.freezeReason != null) return@write 0
    val now = clock()
    val chosen = LinkedHashMap<Long, MediaLocationEntity>()
    for (folder in folders) {
      val exact = Location.Nas.folderKey(account, share, folder)
      locationDao.underFolder(exact, exact + "/", exact + "0").forEach { chosen[it.locationId] = it }
    }
    for (key in keys) locationDao.byKey(key.value)?.takeIf { it.state != LocationState.ABSENT.code }?.let { chosen[it.locationId] = it }
    val shareKey = Location.Nas.shareKeyOf(share)
    var count = 0
    for (entity in chosen.values) {
      if (entity.accountId != account.value || entity.shareKey != shareKey) continue
      locationDao.update(LocationRules.vanished(entity.toDomain(), now).toEntity())
      tx.touch(mediaOf(LocationId(entity.locationId)))
      count++
    }
    count
  }

  /** Le posizioni con queste chiavi diventano assenti (es. quelle nate solo dall'indice che l'indice non elenca piu', D6). */
  suspend fun markNasVanished(keys: Collection<LocationKey>): Int = write { tx ->
    val now = clock()
    var count = 0
    for (key in keys) {
      val entity = locationDao.byKey(key.value) ?: continue
      if (entity.state == LocationState.ABSENT.code) continue
      locationDao.update(LocationRules.vanished(entity.toDomain(), now).toEntity())
      tx.touch(mediaOf(LocationId(entity.locationId)))
      count++
    }
    count
  }

  /**
   * I metadati letti al rilevamento dei file del NAS (o adottati da un record dell'indice), con la fase META di ogni media
   * (`NasScanStore.applyMetadata`): **una** transazione. Come [updateMetadata] + [updateWork] per ogni posizione.
   */
  suspend fun applyNasMetadata(updates: List<NasMetadataUpdate>): Unit = write { tx ->
    val now = clock()
    for (update in updates) {
      val entity = locationDao.get(update.locationId.value) ?: continue
      val versionId = VersionId(entity.versionId)
      val mediaId = mediaOf(update.locationId)
      val meta = workDao.forMedia(mediaId.value).firstOrNull { it.stage == WorkStage.META.code }?.toDomain()
      when (val result = update.result) {
        is NasMetadataResult.Read -> {
          versionDao.upsertMetadata(result.metadata.toEntity(versionId, now))
          val row = mediaDao.get(mediaId.value)
          if (row != null && row.currentVersionId == versionId.value && row.kind != result.kind.code) {
            mediaDao.setKind(mediaId.value, result.kind.code, now)
            tx.markChanged(mediaId, structural = false)
          }
          if (meta != null) workDao.upsert(listOf(WorkRules.succeeded(meta, versionId, now).toEntity(mediaId)))
        }
        is NasMetadataResult.Failed ->
          if (meta != null) workDao.upsert(listOf(WorkRules.failed(meta.copy(versionId = versionId), result.code, now).toEntity(mediaId)))
        NasMetadataResult.Unsupported ->
          if (meta != null) workDao.upsert(listOf(WorkRules.unsupported(meta.copy(versionId = versionId), now).toEntity(mediaId)))
      }
      tx.touch(mediaId)
    }
  }


  /**
   * R10 (revisione 4A): le chiavi di contenuto ridotte (`ContentKey.short`) delle posizioni del NAS non assenti, di ogni
   * share: una miniatura provvisoria di una chiave che nessuna usa piu' si puo' togliere.
   */
  suspend fun liveNasShortKeys(): Set<Long> =
    locationDao.liveNasFingerprints().mapNotNullTo(HashSet()) { row ->
      runCatching { ContentKey.of(QuickHash(row.quickHash), row.size).short }.getOrNull()
    }

  /** R10: la chiave di contenuto ridotta (impronta rapida e dimensione) della versione di una posizione, se nota. */
  suspend fun nasContentKeyOf(locationId: LocationId): Long? {
    val entity = locationDao.get(locationId.value) ?: return null
    val quick = versionDao.get(entity.versionId)?.quickHash ?: return null
    return runCatching { ContentKey.of(QuickHash(quick), entity.size).short }.getOrNull()
  }

  /** Il media che ha la posizione [locationId] (alias risolti), se c'e' ancora (P11: "Scarica sul telefono", D20). */
  suspend fun mediaIdOfLocation(locationId: LocationId): MediaId? = locationDao.mediaIdOf(locationId.value)?.let { resolve(MediaId(it)) }

  /** I metadati della versione [versionId] (P11: il record dell'indice che la preparazione completa, P6). */
  suspend fun versionMetadata(versionId: VersionId): MediaMetadata? = versionDao.metadata(versionId.value)?.toDomain()

  /**
   * I file **presenti** della share, versione corrente, con quanto serve alle unita' di lavoro della preparazione (D13,
   * porta `WorkSource` di core-index): posto, timbro, impronta rapida, tipo, giorno dello scatto, MIME.
   */
  suspend fun nasWorkRows(account: AccountId, share: String): List<NasWorkItem> =
    locationDao.nasWorkRows(account.value, Location.Nas.shareKeyOf(share)).mapNotNull { row ->
      val quick = row.quickHash ?: return@mapNotNull null
      val path = runCatching { NasPath.of(row.path) }.getOrNull() ?: return@mapNotNull null
      NasWorkItem(
        locationId = LocationId(row.locationId),
        path = path,
        stamp = NasStamp(row.size, row.modifiedAt, row.fileId),
        quickHash = QuickHash(quick),
        mediaId = MediaId(row.mediaId),
        versionId = VersionId(row.versionId),
        kind = enumByCode(row.kind),
        localDay = row.localDay,
        mime = row.mime,
        source = enumByCode(row.source),
      )
    }

  /**
   * Per i percorsi [paths] della share, il timbro e l'impronta rapida che il catalogo conosce (posizioni non assenti): la
   * regola M3 dell'indice ("si pubblica solo cio' che viene da un file di questa share", porta `ShareCatalog` di P3).
   */
  suspend fun nasFiles(account: AccountId, share: String, paths: Collection<NasPath>): Map<NasPath, Pair<NasStamp, QuickHash?>> {
    val out = LinkedHashMap<NasPath, Pair<NasStamp, QuickHash?>>()
    for (path in paths) {
      val entity = locationDao.byKey(Location.Nas(account, share, path).key.value) ?: continue
      if (entity.state == LocationState.ABSENT.code) continue
      val quick = versionDao.get(entity.versionId)?.quickHash?.let(::QuickHash)
      out[path] = NasStamp(entity.size, entity.modifiedAt, entity.fileId) to quick
    }
    return out
  }
  private fun MediaLocationEntity.toNasInfo(): NasLocationInfo? {
    val domain = toDomain()
    val nas = domain.location as? Location.Nas ?: return null
    return NasLocationInfo(LocationId(locationId), nas, NasStamp(size, modifiedAt, fileId), domain.state, lastSeenAt)
  }

  /**
   * La coda di una fase per un posto (§1.3: "code di lavoro esposte"): i media con [stage] da fare e una copia presente
   * sul telefono o sul NAS, dal piu' recente. La coda del telefono non vede i media solo-NAS (20 mila media del NAS in
   * coda META non fermano piu' la META del telefono).
   */
  suspend fun pendingWork(stage: WorkStage, place: WorkPlace, limit: Int): List<MediaId> = when (place) {
    WorkPlace.PHONE -> workDao.nextPending(stage.code, limit)
    WorkPlace.NAS -> workDao.nextPendingOnNas(stage.code, limit)
  }.map { MediaId(it.mediaId) }

  /** I fallimenti di [stage] da riprovare per un posto, falliti prima di [failedBefore], i piu' vecchi prima. */
  suspend fun retryableWork(stage: WorkStage, place: WorkPlace, failedBefore: Long, limit: Int): List<MediaId> = when (place) {
    WorkPlace.PHONE -> workDao.retryable(stage.code, failedBefore, limit)
    WorkPlace.NAS -> workDao.retryableOnNas(stage.code, failedBefore, limit)
  }.map { MediaId(it.mediaId) }

  /**
   * Mette in coda [stage] (es. `HQ`, `FULL_HASH` per la verifica "al sicuro") per la versione corrente di [ids], dove non
   * e' gia' fatta o in coda per quella versione. Una transazione; restituisce quanti ne ha messi.
   */
  suspend fun requestWork(ids: Collection<MediaId>, stage: WorkStage): Int = write { tx ->
    ids.mapNotNull { resolve(it) }.distinct().count { id -> requestStage(tx, requireMedia(id), stage) }
  }

  // --- Operazioni fatte dall'app ---------------------------------------------------------------------

  /**
   * Registra o aggiorna una share con la sua area (configurazione di famiglia). Proprietario e "al
   * sicuro" si ricalcolano per i media che hanno una copia in quella share: gli unici che cambiano.
   */
  suspend fun registerShare(share: NasShare): Unit = write { tx ->
    shareDao.upsert(share.toEntity())
    tx.areas = null
    locationDao.mediaIdsInShare(share.account.value, share.shareKey).forEach { tx.touch(MediaId(it)) }
  }

  /**
   * Una copia scritta dall'app stessa (backup verificato, "Scarica sul telefono", copia privata
   * ricreata: E-B13). Nessuna euristica: l'app sa quali byte ha scritto. [verifiedAtMillis] e' l'ora
   * della rilettura con hash uguale, se c'e' stata.
   */
  suspend fun attachCopy(
    mediaId: MediaId,
    versionId: VersionId,
    location: Location,
    stamp: FileStamp,
    verifiedAtMillis: Long?,
  ): LocationId = write { tx -> attachInTransaction(tx, mediaId, versionId, location, stamp, verifiedAtMillis) }

  /**
   * [attachCopy] per un lotto, **in una sola transazione e con un solo cambiamento dell'indice** (Fase 5A, §5.5: durante il primo
   * backup il catalogo si aggiorna a lotti, non a file). Restituisce quante copie ha attaccato; le altre non si attaccano:
   * una versione che non esiste piu' (unita nel frattempo) si salta e si conta in meno, mentre una copia con una dimensione
   * diversa dalla versione, o "verificata" contro un hash completo non noto, e' un errore di programmazione che annulla il lotto
   * (`IllegalArgumentException`: o tutte o nessuna). Idempotente sulla stessa posizione.
   */
  suspend fun attachCopies(copies: List<AttachedCopy>): Int = write { tx ->
    var attached = 0
    for (copy in copies) {
      // Dopo un'unione la versione passa al sopravvissuto: vale il media a cui appartiene adesso.
      val owner = versionDao.get(copy.versionId.value)?.mediaId ?: continue
      attachInTransaction(tx, MediaId(owner), copy.versionId, copy.location, copy.stamp, copy.verifiedAtMillis)
      attached++
    }
    attached
  }

  private suspend fun attachInTransaction(
    tx: Tx,
    mediaId: MediaId,
    versionId: VersionId,
    location: Location,
    stamp: FileStamp,
    verifiedAtMillis: Long?,
  ): LocationId {
    val now = clock()
    val version = requireMedia(mediaId).version(versionId)
    require(stamp.size == version.fingerprint.size) { "La copia ha una dimensione diversa dalla versione." }
    require(verifiedAtMillis == null || version.fingerprint.fullHash != null) { "Si verifica solo contro un hash completo noto." }
    val existing = locationDao.byKey(location.key.value)
    val id = if (existing == null) {
      locationDao.insert(locationEntity(0, versionId, location, stamp, LocationState.PRESENT, null, verifiedAtMillis, now))
    } else {
      tx.touch(mediaOf(LocationId(existing.locationId)))
      locationDao.update(locationEntity(existing.locationId, versionId, location, stamp, LocationState.PRESENT, null, verifiedAtMillis, now))
      existing.locationId
    }
    tx.touch(mediaId)
    tx.refChanged(mediaId)
    return LocationId(id)
  }

  /** Uno spostamento fatto dall'app (condivisione nell'area comune, 7.4): stessa posizione, altro posto. */
  suspend fun relocate(locationId: LocationId, to: Location, stamp: FileStamp, verifiedAtMillis: Long?): Unit = write { tx ->
    val before = requireLocation(locationId)
    val moved = before.copy(
      location = to,
      stamp = stamp,
      state = LocationState.PRESENT,
      absentSinceMillis = null,
      verifiedAtMillis = verifiedAtMillis,
      lastSeenAtMillis = clock(),
    )
    locationDao.update(moved.toEntity())
    val mediaId = mediaOf(locationId)
    tx.touch(mediaId)
    tx.refChanged(mediaId)
  }

  /**
   * **La porta di completamento degli spostamenti** (Fase 7, D29, E-SH2; `MoveCompletionSink` e fine di un caricamento `INCOMING`):
   * collega il file [to], appena arrivato nell'area comune (o in un'altra cartella), allo **stesso** [media] (stessa identita',
   * stessa versione), cosi' preferiti, nascosti, album, vettori e gruppi restano collegati **prima** che la sorgente vada nel cestino.
   *
   * - [from] e' la posizione di partenza (uno spostamento): se e' una posizione di questo media la sua riga **passa** al posto nuovo
   *   (stessa riga, stessa versione); se la destinazione c'era gia' (la scansione l'ha vista per prima) la sorgente diventa assente,
   *   come la scansione farebbe. Senza [from] (un caricamento dal telefono) la posizione e' nuova e la copia del telefono resta;
   * - [sha256] e' l'hash verificato dal motore: se la versione ha l'hash completo e **non** coincide, i byte non sono quelli e non si
   *   collega niente ([AttachMovedOutcome.ContentMismatch]); se coincide la copia e' verificata adesso ([verifiedAtMillis], o l'ora del
   *   catalogo). Non insegna l'hash alla versione: lo fa il giro `FULL_HASH`;
   * - **idempotente**: il secondo giro con gli stessi argomenti non scrive niente e non annuncia niente ([AttachMovedOutcome.AlreadyAttached]);
   * - **una transazione e un solo cambiamento** dell'indice della timeline, anche se la riga cambia posto e la sorgente diventa assente;
   * - mai niente sul NAS: solo il catalogo locale. Un file che e' gia' di **un altro** media non si tocca (la scansione lo unira' con
   *   le regole d'identita'): niente e' scritto e l'esito lo dice.
   */
  suspend fun attachMoved(
    media: MediaId,
    to: Location.Nas,
    stamp: FileStamp,
    from: LocationKey? = null,
    sha256: Sha256? = null,
    verifiedAtMillis: Long? = null,
  ): AttachMovedOutcome = write { tx ->
    val id = resolve(media) ?: return@write AttachMovedOutcome.UnknownMedia
    val graph = mediaDao.graph(id.value)?.toDomain() ?: return@write AttachMovedOutcome.UnknownMedia
    val source = from?.let { locationDao.byKey(it.value) }?.takeIf { versionDao.get(it.versionId)?.mediaId == id.value }
    val dest = locationDao.byKey(to.key.value)
    if (dest != null && versionDao.get(dest.versionId)?.mediaId != id.value) return@write AttachMovedOutcome.DestinationOfOtherMedia
    val versionId = VersionId((dest ?: source)?.versionId ?: graph.currentVersionId.value)
    val version = graph.versions.firstOrNull { it.id == versionId } ?: return@write AttachMovedOutcome.UnknownMedia
    if (stamp.size != version.fingerprint.size) return@write AttachMovedOutcome.SizeMismatch
    val full = version.fingerprint.fullHash
    if (sha256 != null && full != null && full != sha256) return@write AttachMovedOutcome.ContentMismatch
    val now = clock()
    val verified = if (sha256 != null && full == sha256) verifiedAtMillis ?: now else null
    var changed = false
    val destId: Long
    if (dest != null) {
      destId = dest.locationId
      val needsUpdate = dest.state != LocationState.PRESENT.code || dest.size != stamp.size || dest.modifiedAt != stamp.modifiedAtMillis ||
        (verified != null && dest.verifiedAt == null) || (to.fileId != null && dest.fileId != to.fileId)
      if (needsUpdate) {
        val keep = dest.verifiedAt.takeIf { dest.size == stamp.size && dest.modifiedAt == stamp.modifiedAtMillis }
        locationDao.update(locationEntity(dest.locationId, versionId, to, stamp, LocationState.PRESENT, null, verified ?: keep, now))
        changed = true
      }
    } else if (source != null) {
      // La stessa riga cambia posto: location_id, versione e tutto cio' che ci punta (nas_adopted_fingerprint, ...) restano.
      destId = source.locationId
      locationDao.update(locationEntity(source.locationId, versionId, to, stamp, LocationState.PRESENT, null, verified, now))
      changed = true
    } else {
      destId = locationDao.insert(locationEntity(0, versionId, to, stamp, LocationState.PRESENT, null, verified, now))
      changed = true
    }
    // Il file e' arrivato anche dove la scansione l'ha visto per prima: la partenza non c'e' piu' (e' nel cestino), come direbbe la scansione.
    if (dest != null && source != null && source.locationId != destId && source.state != LocationState.ABSENT.code) {
      locationDao.update(source.copy(state = LocationState.ABSENT.code, absentSince = now, verifiedAt = null))
      changed = true
    }
    if (!changed) return@write AttachMovedOutcome.AlreadyAttached
    tx.touch(id)
    tx.refChanged(id)
    AttachMovedOutcome.Attached
  }

  /**
   * I byte in [locationId] sono stati riletti e hanno dato [readBack]. Vero se coincidono con l'hash
   * completo della versione (copia verificata); falso se diversi (copia non integra, E-B8) o se
   * l'hash della versione non e' noto. In caso di falso la verifica precedente decade.
   *
   * **Una copia che non torna si ricorda** (`nas_copy_mismatch`, stesso timbro): [verifyCandidates] con
   * [CatalogVerifyScope.Unverified] non la restituisce piu' finche' il file non cambia, cosi' la stessa copia rovinata non si
   * rilegge a ogni giro. Una rilettura che torna toglie il segno. Una posizione che non c'e' piu' e' falso, non un errore.
   */
  suspend fun markVerified(locationId: LocationId, readBack: Sha256, atMillis: Long): Boolean = write { tx ->
    // Una posizione che non c'e' piu' (unita, tolta) non e' verificata: falso, non un errore (porta `BackupCatalog`).
    val before = locationDao.get(locationId.value)?.toDomain() ?: return@write false
    val expected = versionDao.get(before.versionId.value)?.fullHash
    val ok = expected != null && expected == readBack.hex
    locationDao.update(before.copy(verifiedAtMillis = if (ok) atMillis else null).toEntity())
    // Byte diversi da quelli della versione (hash noto): la copia non e' integra e non si rilegge finche' il timbro e' lo stesso.
    if (ok) {
      mismatchDao.delete(locationId.value)
    } else if (expected != null) {
      mismatchDao.upsert(NasCopyMismatchEntity(locationId.value, before.stamp.size, before.stamp.modifiedAtMillis, atMillis))
    }
    tx.touch(mediaOf(locationId))
    ok
  }

  /**
   * I metadati letti (fase META) o corretti di una versione. [kind], se c'e', e' il tipo definitivo
   * deciso da META (motion photo, RAW, animata: `docs/fase3-progetto.md` §2.2): vale solo se [versionId]
   * e' ancora la versione corrente (una lettura arrivata dopo una modifica non cambia il tipo).
   */
  suspend fun updateMetadata(versionId: VersionId, metadata: MediaMetadata, kind: MediaKind? = null): Unit = write { tx ->
    // P14 (Q6 (a)): una correzione solo nell'app vale sopra i metadati letti dal file, anche a ogni rilettura di META.
    val corrected = correctionDao.get(versionId.value)?.let { applyCorrection(metadata, it) } ?: metadata
    versionDao.upsertMetadata(corrected.toEntity(versionId, clock()))
    versionDao.get(versionId.value)?.let { version ->
      val mediaId = MediaId(version.mediaId)
      tx.touch(mediaId)
      if (kind != null) {
        val row = mediaDao.get(version.mediaId)
        if (row != null && row.currentVersionId == versionId.value && row.kind != kind.code) {
          mediaDao.setKind(version.mediaId, kind.code, clock())
          tx.markChanged(mediaId, structural = false)
        }
      }
    }
  }

  /** Una correzione (data, luogo, rotazione) scritta nei file [holders] come nuova versione (E-L8). */
  suspend fun correct(
    mediaId: MediaId,
    fields: Set<CorrectionField>,
    fingerprint: Fingerprint,
    holders: Map<LocationId, FileStamp>,
    metadata: MediaMetadata?,
  ): VersionId = write { tx ->
    val media = requireMedia(mediaId)
    val plan = VersionRules.corrected(media, fields, fingerprint, holders, clock())
    persist(tx, media, plan)
    val newVersion = checkNotNull(plan.newVersion).id
    if (metadata != null) versionDao.upsertMetadata(metadata.toEntity(newVersion, clock()))
    newVersion
  }

  /** "Ripristina originale" (5.4). */
  suspend fun restoreOriginal(mediaId: MediaId): Unit = write { tx ->
    val media = requireMedia(mediaId)
    persist(tx, media, VersionRules.restoreOriginal(media))
  }

  /**
   * Preferito e nascosto di un media. Ogni flag ricorda quando e' cambiato (`*_changed_at`, per la
   * sincronizzazione per campo della 4.7). Nascondere o mostrare cambia l'insieme dell'indice (E-H1):
   * il cambiamento e' strutturale. Un'identita' assorbita da un'unione vale per il sopravvissuto.
   */
  suspend fun setPersonalFlags(mediaId: MediaId, flags: PersonalFlags): Unit = write { tx ->
    resolve(mediaId)?.let { writePersonal(tx, it, flags) }
  }

  /** Preferito o nascosto per una selezione (Libreria, P5): una transazione, un cambiamento. */
  suspend fun setPersonalFlags(ids: Collection<MediaId>, change: (PersonalFlags) -> PersonalFlags): Unit = write { tx ->
    ids.mapNotNull { resolve(it) }.distinct().forEach { id ->
      writePersonal(tx, id, change(personalDao.get(id.value)?.toDomain() ?: PersonalFlags()))
    }
  }

  suspend fun putEmbedding(mediaId: MediaId, vector: EmbeddingVector): Unit = write {
    embeddingDao.upsert(vector.toEntity(mediaId, clock()))
  }

  /** Lo stato di una fase. Per META cambia anche la cella ("non leggibile" dopo `GAVE_UP`, E-M10). */
  suspend fun updateWork(mediaId: MediaId, state: WorkState): Unit = write { tx ->
    workDao.upsert(listOf(state.toEntity(mediaId)))
    if (state.stage == WorkStage.META) tx.touch(mediaId)
  }

  /**
   * Raggruppa [members] (raffica o RAW+JPEG, D20) con [cover] come copertina: nell'indice resta solo chi
   * rappresenta il gruppo (la copertina, o il membro visibile piu' recente se la copertina non si vede).
   * Un membro che era in un altro gruppo lo lascia; un gruppo rimasto con un membro solo si scioglie.
   * Le identita' assorbite da un'unione valgono per il sopravvissuto.
   */
  suspend fun group(kind: MediaGroupKind, members: Collection<MediaId>, cover: MediaId): MediaGroupId {
    // Le copie simili hanno regole loro (rifiuti, nascosto contagioso): un solo ingresso, `groupSimilar`.
    require(kind != MediaGroupKind.SIMILAR_COPIES) { "Le copie simili si raggruppano con groupSimilar." }
    return write { tx -> groupInTransaction(tx, kind, members, cover) }
  }

  /** Scioglie un gruppo: i membri tornano elementi singoli della timeline. */
  suspend fun ungroup(id: MediaGroupId): Unit = write { tx -> dissolve(tx, id.value) }

  /**
   * Unisce due media che si sono rivelati lo stesso (`MergeRules`: originali con hash completi
   * uguali). Ogni riferimento del perdente passa al sopravvissuto e il perdente diventa un alias.
   */
  suspend fun merge(a: MediaId, b: MediaId): MergeOutcome = write { tx -> mergeInTransaction(tx, a, b) }

  /**
   * Salva il cursore di un volume da solo (inizio di una scansione completa, nuova generazione). Se cambia
   * la versione del MediaStore (`msVersion`, E-P22) gli `_ID` in cache del volume smettono di valere: i
   * media con copie su quel volume si annunciano cambiati, cosi' nessuno tiene un riferimento ai byte
   * scaduto ([phoneRefs]).
   */
  suspend fun saveMediaStoreCursor(cursor: MediaStoreCursor): Unit = write { tx -> saveCursor(tx, cursor) }

  /** La versione del dataset dei luoghi con cui sono stati calcolati i `place_id` (`catalog_meta`). */
  suspend fun setPlacesDataset(dataset: Int): Unit = write { metaDao.put(PLACES_DATASET, dataset.toLong()) }

  // --- Fase 5A (P1): il backup e le copie simili (schema v4; docs/modello-dati.md §12) ----------------------------------------
  //
  // Le operazioni della porta `BackupCatalog` di core-backup (che core-database non vede: i tipi sono lo specchio di
  // `BackupCatalogTypes.kt`, `:app` li lega 1:1 in P7) e quelle che il rilevatore delle copie simili (P15, core-library) chiede al
  // catalogo. **Ogni decisione parte da qui**, mai da dati letti dal NAS (E-S11); "al sicuro" nasce solo da [attachCopy],
  // [attachCopies] e [markVerified], cioe' da una rilettura fatta qui (F4-D8, L28). Percorsi, nomi e id non vanno mai nei log.

  /**
   * I candidati del backup (`BackupCatalog.candidates`, §2.1): media **propri**, con una copia presente sul telefono in una delle
   * [sources] (con un `_ID` valido per la versione attuale del MediaStore, E-P22), per una versione da proteggere senza copia
   * privata verificata e senza un problema che chiede l'utente. Una pagina di al massimo [limit] media per `sort_time`
   * decrescente (poi identita'), dopo il cursore [after]: dall'indice (`backup_todo`, `sort_time`, `media_id`), quindi si scorrono
   * solo i candidati e non si ordina in memoria. Un candidato per media: la versione da proteggere con la riga del telefono piu'
   * vecchia. Le righe `IS_PENDING` non entrano mai nel catalogo (la lettura del telefono le salta).
   */
  suspend fun backupCandidates(
    sources: Set<CatalogSource>,
    kind: CatalogBackupKind,
    after: CatalogCandidateCursor?,
    limit: Int,
  ): List<CatalogBackupCandidate> {
    require(limit in 1..MAX_CANDIDATE_PAGE) { "Pagina di candidati fuori misura." }
    val query = BackupQueries.candidates(localAccount.value, sources, kind, after, limit) ?: return emptyList()
    return backupQueryDao.candidates(query).map { it.toCandidate(sources) }
  }

  private fun BackupCandidateRow.toCandidate(sources: Set<CatalogSource>): CatalogBackupCandidate {
    val place = CatalogPhonePlace(
      volume = checkNotNull(volume) { "Candidato senza volume." },
      mediaStoreId = checkNotNull(mediaStoreId) { "Candidato senza _ID." },
      mediaStoreVersion = checkNotNull(mediaStoreVersion) { "Candidato senza versione del MediaStore." },
    )
    // La sorgente: la cartella scelta in cui sta la riga, se c'e'; altrimenti per `MediaSource`. O-2 (prove sul campo): prima vinceva il tipo,
    // e uno "Screenshot_….png" dentro una cartella scelta finiva in `Screenshot/` sul NAS (o, con la sorgente Screenshot spenta o fuori dalla
    // recinzione D13, contava come non suo). Un file in una cartella scelta dall'utente si salva con quella cartella, qualunque sia il suo tipo.
    val chosen: CatalogSource = sources.filterIsInstance<CatalogSource.Folder>().firstOrNull { it.volume == volume && it.relativePath == relativePath }
      ?: when {
        source == MediaSource.CAMERA.code && CatalogSource.Camera in sources -> CatalogSource.Camera
        source == MediaSource.SCREENSHOT.code && CatalogSource.Screenshots in sources -> CatalogSource.Screenshots
        else -> sources.first()
      }
    return CatalogBackupCandidate(
      mediaId = MediaId(mediaId),
      versionId = VersionId(versionId),
      phone = place,
      stamp = FileStamp(stampSize, stampModifiedAt, stampGeneration),
      size = versionSize,
      kind = enumByCode<MediaKind>(kind),
      source = chosen,
      localDay = localDay.takeIf { it != 0 },
      dateEstimated = dateEstimated,
      fileName = checkNotNull(displayName) { "Candidato senza nome." },
      sortTime = sortTime,
      quickHash = QuickHash(quickHash),
      original = isOriginal,
    )
  }

  /**
   * I conteggi della sicurezza dei media propri delle [sources] (`BackupCatalog.safetyCounts`): per stato e i problemi del
   * backup per codice. Legge i derivati (`safety`) e la tabella dei problemi: nessun grafo.
   *
   * Con [outside] (le cartelle scelte) non conta i media che hanno una posizione del telefono in una di quelle cartelle: le righe della
   * fotocamera e degli screenshot della pagina Backup, dove la cartella scelta vince sul tipo come nel piano (O-2). Prima uno screenshot dentro
   * una cartella scelta si contava due volte, nella riga Screenshot e in quella della cartella.
   */
  suspend fun safetyCounts(sources: Set<CatalogSource>, outside: Set<CatalogSource.Folder> = emptySet()): CatalogSafetyCounts {
    val me = localAccount.value
    val byStatus = BackupQueries.safetyByStatus(me, sources, outside)?.let { backupQueryDao.statusCounts(it) }?.associate { it.status to it.count }
      ?: return CatalogSafetyCounts.ZERO
    val problems = BackupQueries.problemsByCode(me, sources, outside)?.let { backupQueryDao.codeCounts(it) }.orEmpty().associate { it.code to it.count }
    return CatalogSafetyCounts(
      safe = byStatus[SafetyStatus.SAFE.code] ?: 0,
      unverified = byStatus[SafetyStatus.PRIVATE_COPY_UNVERIFIED.code] ?: 0,
      onlyCommonCopy = byStatus[SafetyStatus.ONLY_COMMON_COPY.code] ?: 0,
      noNasCopy = byStatus[SafetyStatus.NO_NAS_COPY.code] ?: 0,
      lost = byStatus[SafetyStatus.LOST.code] ?: 0,
      problems = problems,
    )
  }

  /**
   * D-9, D-11 (prove sul campo): i byte da caricare dei media propri delle [sources] ancora senza una copia sul NAS e senza un problema che li
   * ferma (`BackupCatalog.queuedBytes`): la dimensione della versione attuale. Zero senza sorgenti.
   */
  suspend fun queuedBytes(sources: Set<CatalogSource>): Long =
    BackupQueries.queuedBytes(localAccount.value, sources)?.let { backupQueryDao.total(it) } ?: 0L

  /**
   * D8 (`BackupCatalog.privateCopies`): le copie dell'area privata [share] **del proprietario** (l'account del dispositivo) con la
   * stessa [size] (e lo stesso `qh1`, se noto) in **qualunque cartella**, anche i vecchi backup fatti con altri strumenti (§2.11).
   * Mai un'altra area privata, mai l'area comune. Sono solo candidati: decide la rilettura completa (F4-D8). Dall'indice
   * (`size`, `quick_hash`) di `media_version`.
   */
  suspend fun privateCopies(share: String, size: Long, quickHash: QuickHash?, limit: Int): List<CatalogNasCopy> {
    require(limit > 0) { "Il tetto e' positivo." }
    val query = BackupQueries.privateCopies(localAccount.value, Location.Nas.shareKeyOf(share), size, quickHash?.hex, limit)
    return backupQueryDao.nasCopies(query).map { it.toNasCopy() }
  }

  /**
   * Le copie private da rileggere per [scope] (`BackupCatalog.verifyCandidates`, D14), al massimo [limit]: le non verificate (in
   * ordine di identita'), `FULL_HASH` (le fasi in coda, le piu' vecchie prima; [CatalogCopyToVerify.expected] null) e la
   * **rotazione** (dalla verifica piu' vecchia, Q10: dall'indice `account_id`, `share_key`, `verified_at`, senza ordinare in
   * memoria). Solo l'area privata dell'account del dispositivo; con piu' aree private si fondono liste gia' ordinate, al
   * massimo [limit] per area.
   */
  suspend fun verifyCandidates(scope: CatalogVerifyScope, limit: Int): List<CatalogCopyToVerify> {
    require(limit > 0) { "Il tetto e' positivo." }
    val me = localAccount.value
    val shares = backupQueryDao.privateShareKeys(me)
    val rows: List<NasCopyRow> = when (scope) {
      CatalogVerifyScope.Unverified ->
        shares.flatMap { backupQueryDao.nasCopies(BackupQueries.unverified(me, it, limit)) }.sortedBy { it.locationId }
      CatalogVerifyScope.Rotation ->
        shares.flatMap { backupQueryDao.nasCopies(BackupQueries.rotation(me, it, limit)) }
          .sortedWith(compareBy<NasCopyRow>({ it.verifiedAt }, { it.locationId }))
      CatalogVerifyScope.FullHash -> backupQueryDao.nasCopies(BackupQueries.fullHash(me, limit))
      is CatalogVerifyScope.OnTheSpot -> {
        val ids = scope.media.mapNotNull { resolve(it)?.value }.distinct()
        buildList {
          for (chunk in ids.chunked(BackupQueries.MAX_IDS)) {
            for (share in shares) {
              if (size >= limit) break
              addAll(backupQueryDao.nasCopies(BackupQueries.onTheSpot(me, share, chunk, limit - size)))
            }
          }
        }
      }
    }
    return rows.take(limit).map { row ->
      CatalogCopyToVerify(row.toNasCopy(), row.size, row.fullHash?.let(::Sha256), row.verifiedAt)
    }
  }

  /**
   * L'hash completo calcolato **qui** dai byte originali (`BackupCatalog.recordFullHash`, D10): serve prima di attaccare una
   * copia verificata. Se la versione non lo aveva lo impara (e con lui la fase `FULL_HASH` e un eventuale gruppo
   * `PROBABLE_DUPLICATE`, come per [record]); lo stesso hash e' [FullHashRecord.SAME]; uno diverso e' [FullHashRecord.CONFLICT]
   * (i byte letti non sono quelli della versione, E-B15) e l'hash noto non cambia.
   *
   * **In ogni esito la fase `FULL_HASH` di quella versione si chiude**, cosi' non si ripete a ogni giro: con l'hash noto
   * ([FullHashRecord.RECORDED], [FullHashRecord.SAME], [FullHashRecord.CONFLICT]) e' fatta; con una versione che non c'e' o non
   * e' di quel media ([FullHashRecord.UNKNOWN_VERSION]) e' "arresa" (`GAVE_UP`: fuori dalla coda, ma una nuova richiesta di
   * `requestWork` la rimette in coda). Nessuna di queste chiusure tocca l'indice della timeline.
   */
  suspend fun recordFullHash(mediaId: MediaId, versionId: VersionId, sha256: Sha256): FullHashRecord = write { tx ->
    val entity = versionDao.get(versionId.value)
    val owner = resolve(mediaId)
    if (entity == null || owner == null || entity.mediaId != owner.value) {
      closeFullHashWork(listOfNotNull(owner, entity?.let { MediaId(it.mediaId) }), versionId, entity?.fullHash != null)
      return@write FullHashRecord.UNKNOWN_VERSION
    }
    when {
      entity.fullHash == null -> {
        learnFullHash(tx, versionId, Fingerprint(entity.size, QuickHash(entity.quickHash), sha256))
        closeFullHashWork(listOf(owner), versionId, hashKnown = true)
        FullHashRecord.RECORDED
      }
      entity.fullHash == sha256.hex -> {
        closeFullHashWork(listOf(owner), versionId, hashKnown = true)
        FullHashRecord.SAME
      }
      else -> {
        closeFullHashWork(listOf(owner), versionId, hashKnown = true)
        FullHashRecord.CONFLICT
      }
    }
  }

  /**
   * Chiude la fase `FULL_HASH` ancora da fare dei [medias] che riguarda [versionId] (o nessuna versione, o una che non c'e'
   * piu'): `DONE` se la versione ha un hash completo noto, altrimenti `GAVE_UP`. Fuori dalla coda in tutti e due i casi.
   */
  private suspend fun closeFullHashWork(medias: List<MediaId>, versionId: VersionId, hashKnown: Boolean) {
    val now = clock()
    val status = if (hashKnown) WorkStatus.DONE else WorkStatus.GAVE_UP
    for (media in medias.distinct()) {
      workDao.forMedia(media.value)
        .filter { it.stage == WorkStage.FULL_HASH.code && it.status != WorkStatus.DONE.code && it.status != status.code }
        .filter { row -> row.versionId == null || row.versionId == versionId.value || versionDao.get(row.versionId) == null }
        .forEach { row -> workDao.upsert(listOf(row.copy(status = status.code, lastError = null, updatedAt = now))) }
    }
  }

  /**
   * Il problema del backup di un media (`BackupCatalog.markProblem`, D15): [code] e' un `BackupProblemCode`, null lo toglie.
   * Con [marksCell] (come `BackupProblemCode.marksMedia`) il catalogo accende [CellFlags.BACKUP_PROBLEM] **nella stessa
   * transazione** e toglie il media dai candidati (un media con la verifica fallita tre volte non si ricarica all'infinito).
   * Un problema vale per un media **non al sicuro**: su un media al sicuro non resta (e se il media torna al sicuro il problema
   * di prima si toglie da solo). Dopo `markVerified(.., falso)` si segna il problema, non prima.
   *
   * **`lost_before_backup`** (E-B4) si toglie anche quando la lettura del telefono registra di nuovo una posizione presente del
   * media (la foto recuperata dal cestino di Galleria, E-P11): vedi [releaseRecoveredLostMedia]. Gli altri codici no.
   */
  suspend fun markBackupProblem(mediaId: MediaId, code: String?, marksCell: Boolean = true): Unit = write { tx ->
    val id = resolve(mediaId) ?: return@write
    if (code == null) problemDao.delete(id.value) else problemDao.upsert(BackupProblemEntity(id.value, code, marksCell, clock()))
    tx.touch(id)
  }

  /**
   * D9 (`BackupCatalog.onlyCommonCopies`): i media propri la cui sola copia sul NAS e' nell'area **comune**, una riga per versione
   * da proteggere senza copia privata e con una copia comune presente. [ids] null = tutti quelli che questo telefono ha avuto (campo, gruppo
   * A, D2, Q4 (b): `BackupQueries.onlyCommonCopies`), altrimenti solo questi media. Mai una share di area sconosciuta: potrebbe essere
   * un'area privata altrui.
   */
  suspend fun onlyCommonCopies(ids: Collection<MediaId>?, limit: Int): List<CatalogGuardCandidate> {
    require(limit > 0) { "Il tetto e' positivo." }
    val me = localAccount.value
    val rows: List<NasCopyRow> = if (ids == null) {
      backupQueryDao.nasCopies(BackupQueries.onlyCommonCopies(me, null, limit))
    } else {
      buildList {
        for (chunk in ids.mapNotNull { resolve(it)?.value }.distinct().chunked(BackupQueries.MAX_IDS)) {
          if (size >= limit) break
          addAll(backupQueryDao.nasCopies(BackupQueries.onlyCommonCopies(me, chunk, limit - size)))
        }
      }
    }
    return rows.map { row ->
      val copy = row.toNasCopy()
      CatalogGuardCandidate(
        common = copy,
        size = row.size,
        fullHash = row.fullHash?.let(::Sha256),
        quickHash = row.quickHash?.let(::QuickHash),
        localDay = row.localDay.takeIf { it != 0 },
        fileName = copy.path.fileName,
      )
    }
  }

  /**
   * Lo stato di sicurezza di un media per il viewer (`BackupCatalog.mediaSafety`): lo stato derivato, l'ultima verifica di una sua
   * copia privata presente e il problema del backup, se c'e'. Null se il media non esiste (un alias vale per il sopravvissuto).
   */
  suspend fun mediaSafety(mediaId: MediaId): CatalogMediaSafety? {
    val id = resolve(mediaId) ?: return null
    val row = mediaDao.get(id.value) ?: return null
    return CatalogMediaSafety(
      status = enumByCode<SafetyStatus>(row.safety),
      verifiedAtMillis = backupQueryDao.lastPrivateVerification(id.value, localAccount.value),
      problem = problemDao.get(id.value)?.code,
    )
  }

  /**
   * La riga del telefono attuale di una versione (`BackupCatalog.phoneRefOf`, E-P12): la copia presente piu' vecchia con un `_ID`
   * valido per la versione attuale del MediaStore del suo volume. Null se il catalogo non la sa (finche' la lettura del telefono
   * non l'ha riletta dopo una rinumerazione): l'elemento aspetta. Il media puo' essere un alias.
   */
  suspend fun phoneRefOf(mediaId: MediaId, versionId: VersionId): CatalogPhonePlace? {
    val owner = resolve(mediaId) ?: return null
    if (versionDao.get(versionId.value)?.mediaId != owner.value) return null
    val row = backupQueryDao.phonePlace(versionId.value).firstOrNull() ?: return null
    return CatalogPhonePlace(row.volume, row.mediaStoreId, row.mediaStoreVersion)
  }

  /**
   * Dove sta ogni versione di un media (5B, §1.3; riga "Backup" e "Versioni" del viewer): per ogni versione, dalla piu' vecchia, le sue
   * posizioni con l'area della share se sono sul NAS. Vuoto se il media non esiste.
   */
  suspend fun versionLocations(mediaId: MediaId): List<VersionLocations> {
    val media = media(mediaId) ?: return emptyList()
    val areas = areas()
    return media.versions.sortedWith(compareBy({ it.createdAtMillis }, { it.id.value })).map { version ->
      VersionLocations(
        versionId = version.id,
        kind = version.kind,
        isCurrent = version.id == media.currentVersionId,
        size = version.fingerprint.size,
        fullHash = version.fingerprint.fullHash,
        locations = media.locationsOf(version.id).sortedBy { it.id.value }.map { located ->
          VersionLocation(located, (located.location as? Location.Nas)?.let { areas.areaOf(it.account, it.shareKey) })
        },
      )
    }
  }

  // --- Fase 5B (P14): "Libera spazio", "Modifica con…", correzioni solo nell'app, file in arrivo -------------------------------------
  //
  // Le operazioni che le porte della 5B chiedono al catalogo (`FreeSpaceCatalog`, `EditCatalog`, `VersionCatalog.correctInApp`, la coda dei
  // file in arrivo). Come tutto il resto: una transazione per chiamata, solo dati locali, mai dati letti dal NAS che decidono (E-S11).

  /**
   * P9 (`FreeSpaceCatalog.phoneMedia`): una pagina dei media con una copia **presente** sul telefono, dal piu' recente (`sort_time`, poi
   * identita', decrescenti), dopo [after]. Tutti i media, propri e no: le regole di "Libera spazio" decidono. Dall'indice della timeline.
   */
  suspend fun phoneMedia(after: CatalogPhoneCursor?, limit: Int): List<CatalogPhoneMedia> {
    require(limit > 0) { "Il tetto e' positivo." }
    val rows = if (after == null) backupQueryDao.phoneMediaFirst(limit) else backupQueryDao.phoneMediaAfter(after.sortTime, after.mediaId.value, limit)
    return rows.map { CatalogPhoneMedia(MediaId(it.mediaId), it.sortTime, it.localDay.takeIf { day -> day != 0 }) }
  }

  /**
   * Fase 6 (§2.2, D14): una pagina dei media del **solo telefono** (nessuna copia sul NAS, nascosti compresi), dal piu' recente, dopo [after],
   * con la chiave di contenuto della versione corrente. Una versione con un'impronta non valida si salta. Il calcolo AI li prende da qui:
   * i risultati restano locali, perche' il file non e' in nessuna share.
   */
  suspend fun phoneOnlyWork(after: CatalogPhoneCursor?, limit: Int): List<CatalogPhoneOnly> {
    require(limit > 0) { "Il tetto e' positivo." }
    val rows = if (after == null) cellContentDao.phoneOnlyFirst(limit) else cellContentDao.phoneOnlyAfter(after.sortTime, after.mediaId.value, limit)
    return rows.mapNotNull { row ->
      val key = FastContentKey.of(row.quickHash, row.size) ?: return@mapNotNull null
      CatalogPhoneOnly(
        mediaId = MediaId(row.mediaId),
        versionId = VersionId(row.versionId),
        contentKey = key,
        kind = enumByCode(row.kind),
        source = enumByCode(row.source),
        size = row.size,
        quickHash = QuickHash(row.quickHash),
        sortTime = row.sortTime,
        localDay = row.localDay.takeIf { it != 0 },
      )
    }
  }

  /**
   * P8/P14 (`VersionCatalog.relocate`): un file proprio spostato dalle versioni (archiviato in `Versioni/` o riportato al solito percorso) e'
   * **la stessa posizione** in un altro percorso della stessa share, con il timbro dopo la rinomina; **la verifica resta** (i byte sono gli
   * stessi: una rinomina non li cambia). Falso se la posizione non c'e' piu' o se al percorso nuovo il catalogo conosce gia' un'altra posizione
   * (la scansione l'ha vista per prima): allora non si tocca niente e la scansione mette in pari.
   */
  suspend fun relocateKeepingVerification(locationId: LocationId, to: Location.Nas, stamp: FileStamp): Boolean = write { tx ->
    val before = locationDao.get(locationId.value)?.toDomain() ?: return@write false
    val other = locationDao.byKey(to.key.value)
    if (other != null && other.locationId != locationId.value) return@write false
    val moved = before.copy(
      location = to,
      stamp = stamp,
      state = LocationState.PRESENT,
      absentSinceMillis = null,
      verifiedAtMillis = before.verifiedAtMillis,
      lastSeenAtMillis = clock(),
    )
    locationDao.update(moved.toEntity())
    val mediaId = mediaOf(locationId)
    tx.touch(mediaId)
    tx.refChanged(mediaId)
    true
  }

  /**
   * P9 (`FreeSpaceCatalog.freedFromPhone`): Android ha tolto queste copie del telefono (dialogo di sistema confermato, o lettura del telefono
   * dopo un esito sconosciuto). Diventano **assenti** come una riga sparita dal MediaStore (la finestra di tolleranza resta: un ripristino dal
   * cestino di Android le ricollega, E-P11), cosi' la Libreria mostra subito la cella "solo NAS". Solo posizioni del telefono presenti; il
   * resto si salta. Quante ne ha segnate.
   */
  suspend fun markPhoneLocationsVanished(ids: Collection<LocationId>): Int = markPhoneRowsVanished(ids.associateWith { null })

  /**
   * D-3 delle prove sul campo: come [markPhoneLocationsVanished], ma una posizione si segna solo se sta ancora sulla riga consegnata ad
   * Android ([rows]: posizione → `_ID` di quella riga; null = non noto). Su One UI il file va nel Cestino di Galleria e l'utente puo'
   * ripristinarlo prima che l'esito si risolva: una posizione che la lettura del telefono ha gia' ricollegato a un'altra riga resta presente.
   */
  suspend fun markPhoneRowsVanished(rows: Map<LocationId, Long?>): Int = write { tx ->
    var marked = 0
    for ((id, mediaStoreId) in rows) {
      val location = locationDao.get(id.value)?.toDomain() ?: continue
      val phone = location.location as? Location.Phone ?: continue
      if (location.state != LocationState.PRESENT) continue
      if (mediaStoreId != null && phone.mediaStoreId != null && phone.mediaStoreId != mediaStoreId) continue
      locationDao.update(LocationRules.vanished(location, clock()).toEntity())
      tx.touch(mediaOf(location.id))
      marked++
    }
    marked
  }

  /**
   * P11 (`EditCatalog.mediaOfPhoneRow`): il media che ha la riga del telefono `_ID` [mediaStoreId] del volume [volume], letta con la versione
   * [mediaStoreVersion] del MediaStore (un `_ID` di un'altra versione puo' essere di un altro file, E-P22). Null se il catalogo non la conosce.
   */
  suspend fun mediaOfPhoneRow(volume: String, mediaStoreId: Long, mediaStoreVersion: String): MediaId? {
    val row = locationDao.byMediaStoreId(volume, mediaStoreId)
      .firstOrNull { it.mediaStoreVersion == mediaStoreVersion && it.state == LocationState.PRESENT.code } ?: return null
    return locationDao.mediaIdOf(row.locationId)?.let { resolve(MediaId(it)) }
  }

  /**
   * P14 (D13): la cartella (`RELATIVE_PATH`) della riga del telefono `_ID` [mediaStoreId] di [volume], com'e' nel catalogo; null se il catalogo
   * non la conosce. La recinzione di debug la confronta prima di scrivere o togliere una riga (riscrittura, dialoghi di sistema).
   */
  suspend fun phoneRowFolder(volume: String, mediaStoreId: Long): String? =
    locationDao.byMediaStoreId(volume, mediaStoreId).firstOrNull { it.state == LocationState.PRESENT.code }?.relativePath

  /**
   * P11 (E-E3, `EditCatalog.addEditedVersion`): il risultato di un editor esterno su un media (gia' tenuto come file preparato, con [sha256],
   * [quickHash] e [size]) diventa una versione `EDITED` figlia di [parent] e la versione corrente, **senza posizione**: la pubblica il lavoro
   * `VERSION` del backup, che poi attacca la copia verificata. Null se il media non c'e' piu', se [parent] non e' una sua versione o se i byte
   * sono quelli di una versione gia' nota del media (non e' una versione nuova). Mai una sostituzione: le versioni di prima restano.
   */
  suspend fun addEditedVersion(into: MediaId, parent: VersionId, sha256: Sha256, quickHash: QuickHash, size: Long): VersionId? = write { tx ->
    val id = resolve(into) ?: return@write null
    val media = mediaDao.graph(id.value)?.toDomain() ?: return@write null
    if (media.versions.none { it.id == parent }) return@write null
    if (media.versions.any { it.fingerprint.fullHash == sha256 }) return@write null
    val plan = VersionRules.edited(media, parent, Fingerprint(size, quickHash, sha256), clock())
    persist(tx, media, plan)
    checkNotNull(plan.newVersion).id
  }

  /**
   * P11 (E-X3, `EditCatalog.linkCopyAsVersion`): [copy] e' il media nato dalla riga che un editor ha scritto come copia ("Salva copia") durante una
   * sessione di "Modifica con…" di [into]: era una versione di [into]. La sua versione diventa una versione `EDITED` di [into] figlia di [parent]
   * e la corrente; le sue posizioni la seguono; i riferimenti di [copy] (album, diari, attivita') passano a [into]; [copy] resta come alias.
   *
   * **Nel dubbio non si unisce** (null, niente cambia): [copy] o [into] che non ci sono, lo stesso media, [parent] che non e' di [into], una copia
   * che non e' appena nata (piu' di una versione, in un gruppo), un nascosto (E-H1), proprietari diversi, byte uguali a una versione di [into]
   * (non e' una versione nuova: la lettura del telefono li unisce da sola per hash completo). Mai una sostituzione.
   */
  suspend fun linkCopyAsVersion(into: MediaId, copy: MediaId, parent: VersionId): VersionId? = write { tx ->
    val target = resolve(into) ?: return@write null
    val source = resolve(copy) ?: return@write null
    if (target == source) return@write null
    val targetMedia = mediaDao.graph(target.value)?.toDomain() ?: return@write null
    val sourceMedia = mediaDao.graph(source.value)?.toDomain() ?: return@write null
    if (targetMedia.versions.none { it.id == parent }) return@write null
    if (sourceMedia.versions.size != 1 || sourceMedia.groupId != null) return@write null
    if (personalDao.get(target.value)?.hidden == true || personalDao.get(source.value)?.hidden == true) return@write null
    if (setOfNotNull(targetMedia.owner, sourceMedia.owner).size > 1) return@write null
    val version = sourceMedia.versions.single()
    val hash = version.fingerprint.fullHash
    if (hash != null && targetMedia.versions.any { it.fingerprint.fullHash == hash }) return@write null
    val now = clock()
    // 1. La versione (e con lei le sue posizioni, che puntano alla versione) passa a [into] come modifica di [parent].
    mergeDao.moveVersion(version.id.value, target.value, parent.value)
    mergeDao.setVersionKind(version.id.value, VersionKind.EDITED.code, parent.value)
    // 2. I riferimenti della copia, **gli stessi** di un'unione (R21 della revisione di sicurezza della Fase 7): album, copertine, le tabelle
    //    della v6 (foto d'esempio, esclusioni, suggeriti, diario della condivisione, copertine delle proposte) e quelle della v4.
    repointMediaReferences(source.value, target.value)
    // 3. La versione nuova e' la corrente: i pixel sono cambiati, le fasi si rifanno.
    mediaDao.setCurrentVersion(target.value, version.id.value, now)
    val states = workDao.forMedia(target.value).map { it.toDomain() }
    workDao.upsert(WorkRules.invalidate(states, WorkStage.entries.toSet(), version.id, now).map { it.toEntity(target) })
    // 4. La copia resta come alias (anche gli alias che puntavano a lei); la sua riga se ne va (in cascata le sue righe ricalcolabili).
    mergeDao.repointAliases(source.value, target.value)
    mediaDao.insertAlias(MediaAliasEntity(aliasId = source.value, mediaId = target.value, mergedAt = now))
    mediaDao.delete(source.value)
    tx.touch(target)
    tx.touch(source)
    tx.markChanged(target, structural = true)
    tx.markChanged(source, structural = true)
    version.id
  }

  /**
   * Q6 (a), L40 (`VersionCatalog.correctInApp`): una correzione di data, luogo o rotazione di un formato che non si scrive nel file (HEIC, RAW,
   * video) resta **nell'app e nell'indice**: si salva per la versione [versionId] di [mediaId] e vale subito sui suoi metadati e **a ogni
   * rilettura** di META ([updateMetadata]). Il file non si tocca. Una correzione dopo l'altra si somma (i campi nuovi vincono). Il nome del
   * luogo di un luogo corretto qui non si ricalcola (resta vuoto: il geocodificatore e' dell'app). Falso se il media o la versione non ci sono.
   */
  suspend fun correctInApp(mediaId: MediaId, versionId: VersionId, correction: InAppCorrection): Boolean = write { tx ->
    val id = resolve(mediaId) ?: return@write false
    val version = versionDao.get(versionId.value) ?: return@write false
    if (version.mediaId != id.value || correction.isEmpty) return@write false
    val now = clock()
    val row = mergedCorrection(correctionDao.get(versionId.value), versionId, correction, now)
    correctionDao.upsert(row)
    val metadata = versionDao.metadata(versionId.value)?.toDomain() ?: MediaMetadata()
    versionDao.upsertMetadata(applyCorrection(metadata, row).toEntity(versionId, now))
    tx.touch(id)
    tx.markChanged(id, structural = false)
    true
  }

  /** La correzione solo nell'app di [versionId], se c'e' (viewer, test). */
  suspend fun inAppCorrection(versionId: VersionId): InAppCorrection? = correctionDao.get(versionId.value)?.let { row ->
    InAppCorrection(
      takenAtUtcMillis = row.takenAtUtc,
      offsetMinutes = row.offsetMinutes,
      geo = when (row.geo) {
        MetadataCorrectionEntity.GEO_SET -> InAppGeo.Set(checkNotNull(row.latitude), checkNotNull(row.longitude))
        MetadataCorrectionEntity.GEO_CLEAR -> InAppGeo.Clear
        else -> InAppGeo.Keep
      },
      orientationDegrees = row.orientationDegrees,
      mirrored = row.mirrored,
    )
  }

  private fun mergedCorrection(before: MetadataCorrectionEntity?, versionId: VersionId, c: InAppCorrection, now: Long): MetadataCorrectionEntity {
    val geo = c.geo
    return MetadataCorrectionEntity(
      versionId = versionId.value,
      takenAtUtc = c.takenAtUtcMillis ?: before?.takenAtUtc,
      offsetMinutes = if (c.takenAtUtcMillis != null) c.offsetMinutes else before?.offsetMinutes,
      geo = when (geo) {
        InAppGeo.Keep -> before?.geo
        is InAppGeo.Set -> MetadataCorrectionEntity.GEO_SET
        InAppGeo.Clear -> MetadataCorrectionEntity.GEO_CLEAR
      },
      latitude = when (geo) {
        InAppGeo.Keep -> before?.latitude
        is InAppGeo.Set -> geo.latitude
        InAppGeo.Clear -> null
      },
      longitude = when (geo) {
        InAppGeo.Keep -> before?.longitude
        is InAppGeo.Set -> geo.longitude
        InAppGeo.Clear -> null
      },
      orientationDegrees = c.orientationDegrees ?: before?.orientationDegrees,
      mirrored = c.mirrored ?: before?.mirrored,
      correctedAt = now,
    )
  }

  /** I metadati [metadata] con sopra la correzione solo nell'app [row]: la data dell'utente e' esatta, il luogo corretto non ha un nome. */
  private fun applyCorrection(metadata: MediaMetadata, row: MetadataCorrectionEntity): MediaMetadata {
    var out = metadata
    row.takenAtUtc?.let { at ->
      out = out.copy(
        captureTime = CaptureTime(
          utcMillis = at,
          offsetMinutes = row.offsetMinutes ?: metadata.captureTime?.offsetMinutes ?: 0,
          confidence = CaptureTimeConfidence.EXACT,
          source = CaptureTimeSource.USER_CORRECTION,
        ),
      )
    }
    when (row.geo) {
      MetadataCorrectionEntity.GEO_SET -> {
        val lat = row.latitude
        val lon = row.longitude
        if (lat != null && lon != null) out = out.copy(location = GeoPoint(lat, lon), place = null)
      }
      MetadataCorrectionEntity.GEO_CLEAR -> out = out.copy(location = null, place = null)
    }
    val degrees = row.orientationDegrees
    val mirrored = row.mirrored
    if (degrees != null && mirrored != null) out = out.copy(orientationDegrees = degrees, mirrored = mirrored)
    return out
  }

  /**
   * D24 (P14, la coda dei file in arrivo): il media e la versione che un file in arrivo ("Salva sul NAS") diventera'. Se il catalogo conosce
   * gia' una versione con gli stessi byte (SHA-256 [fingerprint]) **di un media dell'utente e non nascosto**, e' quella: il file caricato ne sara'
   * una copia in piu'. Altrimenti nasce un media nuovo con una sola versione e **nessuna posizione** (presenza `none`: la Libreria non lo mostra
   * finche' il backup non attacca la copia verificata sul NAS). [sortTimeHint] e' l'ordine provvisorio (la data di scatto vera arriva con META).
   * Proprietario: l'account del dispositivo.
   *
   * **R15 della revisione di sicurezza 5B.** Prima si prendeva qualunque versione con quei byte: un arrivo uguale a un media **nascosto** gli si
   * agganciava (il file salvato non si vedeva mai, e il nascosto prendeva una copia che non aveva chiesto: E-H1, nascosto contagioso) e uno uguale a
   * un media di **un altro account** diventava "suo". Adesso valgono solo i media dello stesso account e non nascosti; un media ancora senza posizione
   * (il primo arrivo uguale, in volo: R14) e' dell'utente e vale. Fra piu' candidati si preferisce quello che si vede. Nel dubbio ne nasce uno nuovo.
   */
  suspend fun incomingMedia(fingerprint: Fingerprint, kind: MediaKind, sortTimeHint: Long): CatalogIncomingMedia = write { tx ->
    val hash = requireNotNull(fingerprint.fullHash) { "Un file in arrivo ha il suo SHA-256." }
    val known = versionDao.byFullHash(hash.hex).filter { it.size == fingerprint.size }.mapNotNull { version ->
      val owner = resolve(MediaId(version.mediaId)) ?: MediaId(version.mediaId)
      val row = mediaDao.get(owner.value) ?: return@mapNotNull null
      if (row.ownerAccount != localAccount.value) return@mapNotNull null
      if (personalDao.get(owner.value)?.hidden == true) return@mapNotNull null
      Triple(owner, VersionId(version.versionId), row.presence != Presence.NONE.code)
    }.sortedByDescending { it.third }.firstOrNull()
    if (known != null) return@write CatalogIncomingMedia(known.first, known.second, created = false)
    val now = clock()
    val mediaId = MediaId.random()
    val version = MediaVersion(VersionId.random(), VersionKind.ORIGINAL, null, fingerprint, now)
    mediaDao.insert(
      MediaEntity(
        mediaId = mediaId.value,
        kind = kind.code,
        source = MediaSource.OTHER.code,
        ownerAccount = localAccount.value,
        groupId = null,
        currentVersionId = version.id.value,
        createdAt = now,
        updatedAt = now,
        sortTime = sortTimeHint,
        presence = Presence.NONE.code,
        safety = SafetyStatus.LOST.code,
        localDay = TimelineRules.localDay(sortTimeHint, zone()),
      ),
    )
    versionDao.insert(version.toEntity(mediaId))
    workDao.upsert(WorkRules.initialStates(kind, version.id, now).map { it.toEntity(mediaId) })
    tx.touch(mediaId)
    CatalogIncomingMedia(mediaId, version.id, created = true)
  }

  private fun NasCopyRow.toNasCopy(): CatalogNasCopy = CatalogNasCopy(
    locationId = LocationId(locationId),
    mediaId = MediaId(mediaId),
    versionId = VersionId(versionId),
    share = checkNotNull(shareName) { "Copia sul NAS senza share." },
    path = NasPath.of(checkNotNull(nasPath) { "Copia sul NAS senza percorso." }),
    stamp = NasStamp(size, modifiedAt, fileId),
  )

  // --- Copie simili (D36, §2.12): le operazioni per il rilevatore (P15) --------------------------------------------------------

  /**
   * Una pagina dei candidati alle copie simili a partire dal secondo [fromSecond] (epoca, secondi): i media che hanno almeno un
   * altro candidato dello stesso tipo nello **stesso secondo di scatto letto dai metadati** (mai nome o data del file, mai una
   * stima), visibili da qualche parte e fuori da raffiche, coppie RAW+JPEG e `PROBABLE_DUPLICATE` (§2.12 condizioni 1, 2, 5),
   * raggruppati per secondo e tipo. Per `sort_time` dall'indice (`sort_time`, `media_id`): nessun ordinamento in memoria.
   * Una pagina non spezza mai un secondo; [SimilarRunsPage.nextSecond] e' da dove riprendere, null a fine corsa. Proporzione,
   * durata e firma (condizioni 3 e 4) sono del rilevatore: ogni candidato porta [SimilarCandidate.aspectMilli] e
   * [SimilarCandidate.durationMs].
   */
  suspend fun similarRuns(fromSecond: Long, limit: Int): SimilarRunsPage {
    require(limit > 0) { "Il tetto e' positivo." }
    val rows = similarDao.runRows(fromSecond * MILLIS_PER_SECOND, limit)
    if (rows.size < limit) return SimilarRunsPage(similarRunsOf(rows.map { it.toSimilar() }), null)
    // Pagina piena: l'ultimo secondo puo' continuare, quindi si rilegge intero e la pagina arriva fino a li'.
    val lastSecond = Math.floorDiv(rows.last().sortTime, MILLIS_PER_SECOND)
    val complete = rows.filter { Math.floorDiv(it.sortTime, MILLIS_PER_SECOND) < lastSecond }.map { it.toSimilar() } +
      similarDao.secondRows(lastSecond * MILLIS_PER_SECOND, (lastSecond + 1) * MILLIS_PER_SECOND).map { it.toSimilar() }
    return SimilarRunsPage(similarRunsOf(complete), lastSecond + 1)
  }

  /** I gruppi di candidati dei secondi di scatto di [ids] (dopo che META ha letto l'ora di un media nuovo): lo stesso criterio di [similarRuns]. */
  suspend fun similarRunsFor(ids: Collection<MediaId>): List<SimilarRun> {
    val seconds = ids.mapNotNull { resolve(it) }.distinct().mapNotNull { mediaDao.get(it.value)?.sortTime }
      .map { Math.floorDiv(it, MILLIS_PER_SECOND) }.distinct().sorted()
    return seconds.flatMap { second ->
      similarRunsOf(similarDao.secondRows(second * MILLIS_PER_SECOND, (second + 1) * MILLIS_PER_SECOND).map { it.toSimilar() })
    }
  }

  /** Quanti candidati con almeno un compagno non hanno ancora la firma dell'algoritmo [algorithm] (`SimilarCopiesProgress.pending`). */
  suspend fun similarUnsigned(algorithm: Int): Int = similarDao.unsignedCount(algorithm)

  private fun similarRunsOf(candidates: List<SimilarCandidate>): List<SimilarRun> =
    candidates.groupBy { it.second to it.isVideo }.filterValues { it.size >= 2 }.map { (key, list) -> SimilarRun(key.first, list) }
      .sortedBy { it.second }

  private fun SimilarCandidateRow.toSimilar(): SimilarCandidate = SimilarCandidate(
    mediaId = MediaId(mediaId),
    versionId = VersionId(versionId),
    kind = enumByCode<MediaKind>(kind),
    takenAtUtcMillis = sortTime,
    aspectMilli = aspectMilli,
    durationMs = durationMs,
    group = groupId?.let(::MediaGroupId),
    signatureAlgorithm = signatureAlgorithm,
  )

  /**
   * Salva la firma visiva di una versione (D36): **solo locale**, una per versione; una firma di un altro algoritmo sostituisce la
   * vecchia (si ricalcola). Falso se la versione non esiste (piu'). Non tocca l'indice della timeline.
   */
  suspend fun saveSignature(versionId: VersionId, signature: VisualSignature): Boolean = write {
    if (versionDao.get(versionId.value) == null) return@write false
    signatureDao.upsert(VisualSignatureEntity(versionId.value, signature.algorithm, signature.frames, signature.bytes(), clock()))
    true
  }

  /** La firma salvata per la versione; null se manca o se la riga e' rovinata (una firma illeggibile non si confronta: si ricalcola). */
  suspend fun signatureOf(versionId: VersionId): VisualSignature? = signatureDao.get(versionId.value)?.toSignature()

  /** Le firme salvate delle versioni (quelle che mancano o sono rovinate non compaiono). */
  suspend fun signaturesOf(versionIds: Collection<VersionId>): Map<VersionId, VisualSignature> =
    versionIds.map { it.value }.distinct().chunked(TimelineIndexDao.MAX_IDS).flatMap { signatureDao.forVersions(it) }
      .mapNotNull { row -> row.toSignature()?.let { VersionId(row.versionId) to it } }.toMap()

  private fun VisualSignatureEntity.toSignature(): VisualSignature? =
    runCatching { VisualSignature.of(algorithm, frames, data) }.getOrNull()

  /**
   * Raggruppa [members] come copie dello stesso scatto (`SIMILAR_COPIES`, §2.12), con [cover] come copertina: in Libreria resta
   * una cella sola. **Rifiuta, senza scrivere nulla**, se un membro sta gia' in una raffica, in una coppia RAW+JPEG o in un
   * `PROBABLE_DUPLICATE` (non si spostano: E-L12, "nel dubbio non si unisce"), se ci sono meno di due media diversi (dopo gli
   * alias) o se la copertina non e' un membro. I membri che erano in un altro gruppo di copie simili lo lasciano (un gruppo
   * rimasto con meno di due media si scioglie): chi chiama passa **tutti** i membri che vuole nel gruppo.
   *
   * **Nascosto contagioso (E-H1, regola di sicurezza)**: se anche un solo membro e' nascosto, nella stessa transazione lo
   * diventano tutti, con `hidden_changed_at` come per ogni nascondere, cosi' la sincronizzazione dei dati personali lo vede:
   * la stessa foto non deve ricomparire dalla porta della sua copia. La scelta della copertina e' di P15.
   */
  suspend fun groupSimilar(members: Collection<MediaId>, cover: MediaId): SimilarGroupOutcome = write { tx ->
    val resolved = LinkedHashSet<MediaId>()
    for (member in members) {
      resolved += resolve(member)
        ?: return@write SimilarGroupOutcome.Refused(SimilarRefusal.UNKNOWN_MEDIA, mapOf(member to SimilarRefusal.UNKNOWN_MEDIA))
    }
    if (resolved.size < 2) return@write SimilarGroupOutcome.Refused(SimilarRefusal.TOO_FEW_MEMBERS, emptyMap())
    val coverId = resolve(cover)
    if (coverId == null || coverId !in resolved) return@write SimilarGroupOutcome.Refused(SimilarRefusal.COVER_NOT_A_MEMBER, emptyMap())

    val offenders = LinkedHashMap<MediaId, SimilarRefusal>()
    for (id in resolved) {
      val group = mediaDao.get(id.value)?.groupId ?: continue
      when (groupKind(tx, group)) {
        MediaGroupKind.BURST.code -> offenders[id] = SimilarRefusal.IN_BURST
        MediaGroupKind.RAW_JPEG_PAIR.code -> offenders[id] = SimilarRefusal.IN_RAW_JPEG_PAIR
        MediaGroupKind.PROBABLE_DUPLICATE.code -> offenders[id] = SimilarRefusal.IN_PROBABLE_DUPLICATE
      }
    }
    if (offenders.isNotEmpty()) return@write SimilarGroupOutcome.Refused(offenders.values.first(), offenders)

    val oldGroups = resolved.mapNotNull { mediaDao.get(it.value)?.groupId }.toSet()
    val anyHidden = resolved.any { personalDao.get(it.value)?.hidden == true }
    val group = groupInTransaction(tx, MediaGroupKind.SIMILAR_COPIES, resolved, coverId)
    if (anyHidden) {
      for (id in resolved) {
        val current = personalDao.get(id.value)?.toDomain() ?: PersonalFlags()
        writePersonal(tx, id, current.copy(hidden = true), spread = false)
      }
    }
    SimilarGroupOutcome.Grouped(group, resolved.toList(), hidden = anyHidden, dissolved = oldGroups.count { mediaDao.group(it) == null })
  }

  /**
   * "Separa" (E-L13): [member] esce dal suo gruppo di copie simili. Un gruppo rimasto con meno di due media **si scioglie**. Se
   * [member] era la copertina ne resta una provvisoria (la piu' recente) e [SeparateOutcome.Separated.coverChanged] lo dice: il
   * rilevatore rivaluta la sua regola. **Il nascosto non cambia mai**: separare non mostra niente. Che la separazione valga per
   * sempre e su tutti i dispositivi (dato personale) e' di P15.
   */
  suspend fun separate(member: MediaId): SeparateOutcome = write { tx ->
    val id = resolve(member) ?: return@write SeparateOutcome.NotInGroup
    val group = mediaDao.get(id.value)?.groupId ?: return@write SeparateOutcome.NotInGroup
    val groupRow = mediaDao.group(group)?.takeIf { it.kind == MediaGroupKind.SIMILAR_COPIES.code } ?: return@write SeparateOutcome.NotInGroup
    mediaDao.setGroup(id.value, null, clock())
    tx.touch(id)
    tx.markChanged(id, structural = true)
    val coverChanged = groupRow.coverMediaId == id.value
    val rest = mediaDao.groupMembers(group)
    if (rest.size < 2) {
      dissolve(tx, group)
      SeparateOutcome.Separated(null, rest.map { MediaId(it.mediaId) }, dissolved = true, coverChanged = coverChanged)
    } else {
      if (coverChanged) mediaDao.setGroupCover(group, rest.maxWith(compareBy<MediaEntity>({ it.sortTime }, { it.mediaId })).mediaId)
      tx.dirtyGroups += group
      SeparateOutcome.Separated(MediaGroupId(group), rest.map { MediaId(it.mediaId) }, dissolved = false, coverChanged = coverChanged)
    }
  }

  /**
   * "Mostra questa come principale": [media] diventa la copertina del gruppo di copie simili [group]. Falso se il gruppo non
   * esiste, non e' di copie simili o [media] non ne e' un membro. La scelta dell'utente (dato personale) e' di P15.
   */
  suspend fun setSimilarCover(group: MediaGroupId, media: MediaId): Boolean = write { tx ->
    val id = resolve(media) ?: return@write false
    val row = mediaDao.group(group.value)?.takeIf { it.kind == MediaGroupKind.SIMILAR_COPIES.code } ?: return@write false
    if (mediaDao.get(id.value)?.groupId != group.value) return@write false
    if (row.coverMediaId != id.value) {
      mediaDao.setGroupCover(group.value, id.value)
      tx.dirtyGroups += group.value
    }
    true
  }

  /** Il gruppo di copie simili di [mediaId] con le sue copie (striscia del viewer, scelta della copertina), o null se non ne ha uno. */
  suspend fun similarGroupOf(mediaId: MediaId): SimilarGroupInfo? {
    val id = resolve(mediaId) ?: return null
    return mediaDao.get(id.value)?.groupId?.let { similarGroup(MediaGroupId(it)) }
  }

  /** Il gruppo di copie simili [group], o null se non c'e' o e' d'un altro tipo. */
  suspend fun similarGroup(group: MediaGroupId): SimilarGroupInfo? {
    val row = mediaDao.group(group.value)?.takeIf { it.kind == MediaGroupKind.SIMILAR_COPIES.code } ?: return null
    val areas = areas()
    val members = mediaDao.groupMembers(group.value).mapNotNull { mediaDao.graph(it.mediaId) }.map { graph ->
      val media = graph.toDomain()
      val metadata = graph.versions.firstOrNull { it.version.versionId == graph.media.currentVersionId }?.metadata
      val present = media.locations.filter { it.isPresent }
      val nas = present.mapNotNull { it.location as? Location.Nas }
      SimilarMember(
        mediaId = media.id,
        versionId = media.currentVersionId,
        kind = media.kind,
        bytes = media.currentVersion.fingerprint.size,
        width = metadata?.width,
        height = metadata?.height,
        orientationDegrees = metadata?.orientation ?: 0,
        durationMs = metadata?.durationMs,
        mime = metadata?.mimeType,
        sortTime = graph.media.sortTime,
        onPhone = present.any { it.isPhone },
        onNasPrivate = nas.any { areas.areaOf(it.account, it.shareKey) == NasArea.PRIVATE },
        onNasCommon = nas.any { areas.areaOf(it.account, it.shareKey) == NasArea.COMMON },
        hidden = personalDao.get(media.id.value)?.hidden == true,
        safety = enumByCode<SafetyStatus>(graph.media.safety),
        backupProblem = CellFlags.has(graph.media.cellFlags, CellFlags.BACKUP_PROBLEM),
      )
    }
    return SimilarGroupInfo(group, row.coverMediaId?.let(::MediaId), members, hidden = members.any { it.hidden })
  }

  /** Gli identificativi dei gruppi di copie simili in ordine, dopo [after] (null = dall'inizio): per rivalutarli a giri. */
  suspend fun similarGroupIds(after: MediaGroupId?, limit: Int): List<MediaGroupId> {
    require(limit > 0) { "Il tetto e' positivo." }
    return similarDao.groupIds(after?.value.orEmpty(), limit).map { MediaGroupId(it.groupId) }
  }

  // --- Fase 6 (P1): le celle per la ricerca e i bit derivati dall'indice AI (schema v5; docs/modello-dati.md §13) -------------------

  /**
   * Le chiavi di contenuto delle celle della Libreria dopo [afterSeq] (il `rowid` di inserimento del media: 0 = dall'inizio), in ordine
   * di [CellContent.seq], di al piu' [limit] media per pagina (le celle di una pagina possono essere meno: nascosti e membri di
   * gruppo non lo sono). [CellContentPage.next] e' il cursore della pagina dopo: si legge finche' e' null. Per ogni cella le versioni
   * del suo media e quelle dei membri visibili del suo gruppo (la mappa delle celle di P6, §2.4).
   *
   * L'ordine e' quello dell'albero di `media` (nessun `USE TEMP B-TREE`) e un media nuovo ha sempre un `seq` piu' alto: chi ha letto
   * fino a [afterSeq] trova i **media nuovi** nelle pagine dopo, e i cambiamenti delle celle esistenti in [cellContentKeysOf] (con gli id di
   * [changes]). Il `rowid` e' stabile finche' nessuno esegue `VACUUM`: Room non lo fa.
   */
  suspend fun cellContentKeys(
    afterSeq: Long = 0,
    limit: Int = CELL_KEYS_PAGE,
    compute: CoroutineDispatcher = Dispatchers.Default,
  ): CellContentPage {
    require(afterSeq >= 0) { "Cursore negativo." }
    require(limit in 1..MAX_CELL_KEYS_PAGE) { "Pagina fuori misura." }
    // A fette di [CELL_KEYS_SLICE] media: una fetta sta in una finestra del cursore di SQLite (2 MB). Un risultato piu' grande la
    // riempirebbe piu' volte e ogni riempimento rilegge la query dall'inizio (quadratico). Le chiavi (SHA-256) di una fetta si calcolano
    // su [compute] mentre si legge la fetta dopo.
    return coroutineScope {
      val slices = ArrayList<Deferred<List<CellContent>>>()
      var cursor = afterSeq
      var remaining = limit
      while (remaining > 0) {
        val bound = cellContentDao.seqBound(cursor, minOf(remaining, CELL_KEYS_SLICE)) ?: break
        val rows = cellContentDao.cellVersions(cursor, bound)
        val members = rows.mapNotNullTo(LinkedHashSet()) { it.groupId }.chunked(TimelineIndexDao.MAX_IDS)
          .flatMap { cellContentDao.memberVersions(it) }
        slices += async(compute) { CellContentAssembler.assemble(rows, members) }
        remaining -= minOf(remaining, CELL_KEYS_SLICE)
        cursor = bound
      }
      if (slices.isEmpty()) CellContentPage(emptyList(), null) else CellContentPage(slices.awaitAll().flatten(), cursor)
    }
  }

  /**
   * Le chiavi di contenuto delle celle [ids] (solo dove sono ancora celle: gli altri non ci sono nel risultato), in ordine di `seq`:
   * per rileggere le celle di un [CatalogChange].
   */
  suspend fun cellContentKeysOf(ids: Collection<MediaId>): List<CellContent> {
    val rows = ids.map { it.value }.distinct().chunked(TimelineIndexDao.MAX_IDS).flatMap { cellContentDao.cellVersionsOf(it) }
      .sortedBy { it.seq }
    val groups = rows.mapNotNullTo(LinkedHashSet()) { it.groupId }
    val members = groups.chunked(TimelineIndexDao.MAX_IDS).flatMap { cellContentDao.memberVersions(it) }
    return CellContentAssembler.assemble(rows, members)
  }

  /**
   * Revisione A (VT-1, D29): le chiavi di contenuto delle versioni dei media [ids] **anche se nascosti o membri di un gruppo** (senza i
   * membri del gruppo), la corrente per prima. Solo per il viewer: il testo dal vivo e la lettura al volo funzionano anche nei Nascosti.
   */
  suspend fun mediaContentKeysOf(ids: Collection<MediaId>): List<CellContent> {
    val rows = ids.map { it.value }.distinct().chunked(TimelineIndexDao.MAX_IDS).flatMap { cellContentDao.mediaVersionsOf(it) }.sortedBy { it.seq }
    return CellContentAssembler.assemble(rows, emptyList())
  }

  /**
   * Revisione A (PR-1, D31): per ogni media di [ids] le share (chiave piegata, come `Location.Nas.shareKey`) in cui ha una copia presente
   * sul NAS; un media senza copie sul NAS non c'e'. A blocchi, una lettura per blocco.
   */
  suspend fun nasSharesOf(ids: Collection<MediaId>): Map<MediaId, Set<String>> {
    val out = HashMap<MediaId, MutableSet<String>>()
    for (chunk in ids.map { it.value }.distinct().chunked(TimelineIndexDao.MAX_IDS)) {
      for (row in cellContentDao.nasSharesOf(chunk)) {
        val share = row.shareKey ?: continue
        out.getOrPut(MediaId(row.mediaId)) { LinkedHashSet() } += share
      }
    }
    return out
  }

  /**
   * Revisione A (CE-6, D29): dei media [ids], quelli nascosti adesso (o uniti a un media nascosto), con due letture per blocco di id invece
   * di tre per media. Il filtro finale dei nascosti della ricerca.
   */
  suspend fun hiddenAmong(ids: Collection<MediaId>): Set<MediaId> {
    val out = HashSet<MediaId>()
    for (chunk in ids.map { it.value }.distinct().chunked(TimelineIndexDao.MAX_IDS)) {
      cellContentDao.hiddenAmong(chunk).mapTo(out, ::MediaId)
      cellContentDao.hiddenAliasesAmong(chunk).mapTo(out, ::MediaId)
    }
    return out
  }

  /**
   * Accende (o con [on] = false spegne) i bit **derivati dall'indice AI** [flags] (oggi solo `CellFlags.DOCUMENT`, D34) sui media
   * [ids], a lotti di [batch] (500) in **una transazione per lotto**: ogni lotto che cambia qualcosa annuncia **un** [CatalogChange]
   * (un `change_seq` in piu', non uno per media) e l'indice della timeline si aggiorna una volta sola. Un lotto che non cambia nulla non
   * annuncia niente. Gli id uniti a un altro media si risolvono; quelli spariti si saltano. Quanti media sono cambiati.
   *
   * Il bit e' **locale**: ogni dispositivo lo ricava dagli stessi dati e niente si pubblica. Sopravvive ai ricalcoli dei derivati
   * (`derive` lo conserva: [AI_DERIVED_MASK]).
   */
  suspend fun setDerivedFlags(ids: Collection<MediaId>, flags: Int, on: Boolean = true, batch: Int = DERIVED_FLAGS_BATCH): Int {
    require(flags != 0 && flags and AI_DERIVED_MASK.inv() == 0) { "Solo i bit derivati dall'indice AI." }
    require(batch in 1..MAX_DERIVED_FLAGS_BATCH) { "Lotto fuori misura." }
    var changed = 0
    for (chunk in ids.distinct().chunked(batch)) {
      changed += write { tx ->
        var n = 0
        for (id in chunk) {
          val row = mediaDao.get((resolve(id) ?: continue).value) ?: continue
          val updated = if (on) row.cellFlags or flags else row.cellFlags and flags.inv()
          if (updated == row.cellFlags) continue
          mediaDao.setCellFlags(row.mediaId, updated, clock())
          tx.markChanged(MediaId(row.mediaId), structural = false)
          n++
        }
        n
      }
    }
    return changed
  }

  // --- Letture ---------------------------------------------------------------------------------------

  /** L'identita' attuale di [id]: se stessa, o il sopravvissuto se e' stata assorbita da un'unione. */
  suspend fun resolve(id: MediaId): MediaId? =
    if (mediaDao.get(id.value) != null) id else mediaDao.aliasTarget(id.value)?.let(::MediaId)

  suspend fun media(id: MediaId): Media? = resolve(id)?.let { mediaDao.graph(it.value)?.toDomain() }

  suspend fun safety(id: MediaId): SafetyReport? =
    media(id)?.let { SafetyRules.evaluate(it, localAccount, areas()) }

  suspend fun workStates(id: MediaId): List<WorkState> = workDao.forMedia(id.value).map { it.toDomain() }

  /** Il numero di sequenza dell'ultimo cambiamento dell'indice (0 su un database nuovo). */
  suspend fun changeSeq(): Long = indexDao.changeSeq()

  /** Le righe dell'indice di [ids] (vedi `TimelineIndexDao.indexRows`), a blocchi sotto il limite di SQLite. */
  suspend fun indexRows(ids: Collection<MediaId>): List<IndexRow> =
    ids.map { it.value }.distinct().chunked(TimelineIndexDao.MAX_IDS).flatMap { indexDao.indexRows(it) }

  /**
   * Come leggere adesso i byte di [ids] dal telefono (miniature, viewer, condivisione): solo copie
   * presenti con un `_ID` valido per la versione attuale del MediaStore. Chi manca non ha una copia
   * leggibile sul telefono adesso.
   */
  suspend fun phoneRefs(ids: Collection<MediaId>): Map<MediaId, MediaRef.Phone> =
    ids.map { it.value }.distinct().chunked(TimelineIndexDao.MAX_IDS)
      .flatMap { indexDao.phoneRefs(it) }
      .groupBy { it.mediaId }
      .mapKeys { MediaId(it.key) }
      .mapValues { (_, rows) -> rows.first().let { MediaRef.Phone(it.volume, it.mediaStoreId, it.mimeType) } }

  suspend fun mediaStoreCursor(volume: String): MediaStoreCursor? = cursorDao.get(volume)?.toDomain()

  suspend fun mediaStoreCursors(): List<MediaStoreCursor> = cursorDao.all().map { it.toDomain() }

  suspend fun placesDataset(): Int? = metaDao.get(PLACES_DATASET)?.toInt()

  // --- Transazioni e cambiamenti ---------------------------------------------------------------------

  /**
   * Lo stato di una transazione di scrittura: i media da ricalcolare alla fine ([dirty], [dirtyGroups]) e
   * quelli la cui riga dell'indice e' cambiata ([changed]).
   */
  private class Tx {
    val dirty = LinkedHashSet<MediaId>()
    val dirtyGroups = LinkedHashSet<String>()
    val changed = LinkedHashSet<MediaId>()

    /**
     * Le posizioni del telefono che un'osservazione di questa transazione ha registrato o confermato **presenti** (id di
     * posizione, non di media: nella conferma a tappeto del timbro non costa una ricerca per riga). La regola "la foto e'
     * tornata" ([releaseRecoveredLostMedia]) le risolve in un colpo solo alla fine, in [refreshDerived].
     */
    val phoneSeen = LinkedHashSet<Long>()

    /** P14 (E-E2): i media tornati a una versione nota in questa transazione ([reverts], dopo il commit). */
    val reverted = LinkedHashSet<MediaId>()
    var structural = false
    var areas: NasAreaLookup? = null
    var seq: Long? = null

    /** I tipi dei gruppi letti in questa transazione (codice, o null se il gruppo non c'e' piu'). */
    val groupKinds = HashMap<String, String?>()

    /** Il media va ricalcolato alla fine della transazione (i suoi derivati possono cambiare). */
    fun touch(id: MediaId) {
      dirty += id
    }

    /** La riga dell'indice di [id] e' cambiata comunque (anche se i derivati restano uguali). */
    fun markChanged(id: MediaId, structural: Boolean) {
      changed += id
      if (structural) this.structural = true
    }

    /** E' cambiato come si arriva ai byte (posizione del telefono, `_ID` in cache). */
    fun refChanged(id: MediaId) = markChanged(id, structural = false)
  }

  private suspend fun <T> write(block: suspend (Tx) -> T): T = writeLock.withLock {
    check(!database.inTransaction()) {
      "Le scritture del catalogo non stanno dentro un'altra transazione: il cambiamento si annuncia dopo il commit."
    }
    val tx = Tx()
    val result = database.withTransaction {
      val value = block(tx)
      refreshDerived(tx)
      if (tx.changed.isNotEmpty()) {
        metaDao.createIfMissing(TimelineIndexDao.CHANGE_SEQ)
        metaDao.add(TimelineIndexDao.CHANGE_SEQ, 1)
        tx.seq = checkNotNull(metaDao.get(TimelineIndexDao.CHANGE_SEQ))
      }
      value
    }
    // Dopo il commit: chi riceve il cambiamento e rilegge vede gia' i dati nuovi.
    tx.seq?.let { changeFlow.tryEmit(CatalogChange(it, tx.changed.toSet(), tx.structural)) }
    tx.reverted.forEach { revertFlow.tryEmit(it) }
    result
  }

  // --- Interni ---------------------------------------------------------------------------------------

  private suspend fun saveCursor(tx: Tx, cursor: MediaStoreCursor) {
    val before = cursorDao.get(cursor.volume)
    cursorDao.upsert(cursor.toEntity())
    if (before?.msVersion != cursor.msVersion) {
      locationDao.mediaIdsOnVolume(cursor.volume).forEach { tx.refChanged(MediaId(it)) }
    }
  }

  private suspend fun recordOne(
    tx: Tx,
    observation: Observation,
    whenUndecided: WhenUndecided,
    seenAt: Long?,
    fingerprintAdopted: Boolean = false,
  ): RecordOutcome {
    val decided = LinkingRules.decide(observation, loadContext(observation, byContent = !fingerprintAdopted))
    val decision = if (whenUndecided == WhenUndecided.KEEP_SEPARATE) keepSeparate(decided) else decided
    val target = apply(tx, decision, observation, seenAt ?: clock())
    // Una posizione del telefono registrata presente: se il media era "perso prima del backup" non lo e' piu' (refreshDerived).
    if (target != null && observation.location is Location.Phone) tx.phoneSeen += target.second.value
    if (whenUndecided == WhenUndecided.KEEP_SEPARATE && target != null && !fingerprintAdopted) {
      undecidedCandidates(decided)?.let { groupProbableDuplicates(tx, target.first, observation, it) }
    }
    // R6: il segno "impronta adottata" segue l'ultima osservazione della posizione del NAS.
    if (target != null && observation.location is Location.Nas) {
      if (fingerprintAdopted) {
        nasScanDao.upsertAdopted(listOf(NasAdoptedFingerprintEntity(target.second.value, observation.fingerprint.quickHash.hex)))
      } else {
        nasScanDao.deleteAdopted(listOf(target.second.value))
      }
    }
    // Un gruppo provvisorio risolto qui (hash completo appena imparato) puo' aver unito il media: vale il sopravvissuto.
    val mediaId = target?.first?.let { resolve(it) ?: it }
    return RecordOutcome(decision, mediaId, target?.second)
  }

  /** I candidati di una decisione rimandata all'hash completo, se e' quella. */
  private fun undecidedCandidates(decision: LinkDecision): List<ContentCandidate>? = when (decision) {
    is LinkDecision.NeedsFullHash -> decision.candidates
    is LinkDecision.Replaced -> (decision.next as? LinkDecision.NeedsFullHash)?.candidates
    else -> null
  }

  /**
   * D21, E-L9: il media appena nato [mediaId] ha la stessa impronta rapida dei [candidates] ma non si puo' unire senza
   * hash completo. Se fra i candidati c'e' un media che sta **dall'altra parte** (telefono e NAS), si mostra una cella
   * sola: un gruppo `PROBABLE_DUPLICATE` con la copia del telefono come copertina (con loro i membri dei gruppi
   * provvisori gia' esistenti). Un candidato in un gruppo d'altro tipo (raffica, RAW+JPEG) resta dov'e': meglio un
   * doppione temporaneo che rompere quel gruppo. Le copie del NAS senza hash completo vanno in coda `FULL_HASH`.
   */
  private suspend fun groupProbableDuplicates(tx: Tx, mediaId: MediaId, observation: Observation, candidates: List<ContentCandidate>) {
    val type = observation.location.type
    val self = mediaDao.graph(mediaId.value)?.toDomain() ?: return
    if (self.groupId != null) return
    val others = candidates.map { it.mediaId }.distinct().filter { it != mediaId }
      .mapNotNull { id -> resolve(id)?.let { mediaDao.graph(it.value)?.toDomain() } }
      .distinctBy { it.id }
      .filter { media -> media.locations.any { it.location.type != type } }
    val eligible = others.filter { media ->
      val group = media.groupId ?: return@filter true
      groupKind(tx, group.value) == MediaGroupKind.PROBABLE_DUPLICATE.code
    }
    if (eligible.isEmpty()) return
    val eligibleIds = eligible.map { it.id }.toSet()
    val mates = eligible.mapNotNull { it.groupId?.value }.distinct()
      .flatMap { group -> mediaDao.groupMembers(group).map { MediaId(it.mediaId) } }
      .filter { it != mediaId && it !in eligibleIds }
      .mapNotNull { mediaDao.graph(it.value)?.toDomain() }
    val members = (listOf(self) + eligible + mates).distinctBy { it.id }
    val cover = members.filter { media -> media.locations.any { it.isPhone && it.isPresent } }.minByOrNull { it.id }?.id
      ?: members.filter { media -> media.locations.any { it.isPhone } }.minByOrNull { it.id }?.id
      ?: members.minOf { it.id }
    groupInTransaction(tx, MediaGroupKind.PROBABLE_DUPLICATE, members.map { it.id }, cover)
    members
      .filter { media -> media.locations.any { it.isNas } && media.currentVersion.fingerprint.fullHash == null }
      .forEach { requestStage(tx, it, WorkStage.FULL_HASH) }
  }

  /** Il tipo di un gruppo (codice), letto una volta per transazione; null se il gruppo non c'e'. */
  private suspend fun groupKind(tx: Tx, groupId: String): String? =
    if (tx.groupKinds.containsKey(groupId)) tx.groupKinds[groupId] else mediaDao.group(groupId)?.kind.also { tx.groupKinds[groupId] = it }

  /**
   * Un gruppo `PROBABLE_DUPLICATE` si risolve quando **tutti** i membri hanno l'hash completo dell'originale: chi ha gli
   * stessi byte si unisce (`MergeRules`, sopravvive il minore), poi il gruppo si scioglie e chi ha byte diversi torna una
   * cella a se'. Finche' ne manca uno si aspetta (la cella resta "da verificare").
   */
  private suspend fun settleProbableDuplicate(tx: Tx, groupId: String) {
    if (groupKind(tx, groupId) != MediaGroupKind.PROBABLE_DUPLICATE.code) return
    val members = mediaDao.groupMembers(groupId).mapNotNull { mediaDao.graph(it.mediaId)?.toDomain() }
    if (members.size < 2) {
      dissolve(tx, groupId)
      return
    }
    if (members.any { it.originalVersion.fingerprint.fullHash == null }) return
    members.groupBy { it.originalVersion.fingerprint.fullHash }.values.forEach { same ->
      var survivor = same.first().id
      same.drop(1).forEach { other ->
        (mergeInTransaction(tx, survivor, other.id) as? MergeOutcome.Merged)?.let { survivor = it.survivor }
      }
    }
    if (mediaDao.group(groupId) != null) dissolve(tx, groupId)
  }

  /**
   * Mette in coda [stage] per la versione corrente di [media], se non e' gia' fatta o in coda per quella versione. Vero
   * se ha scritto qualcosa.
   */
  private suspend fun requestStage(tx: Tx, media: Media, stage: WorkStage): Boolean {
    val current = workDao.forMedia(media.id.value).firstOrNull { it.stage == stage.code }?.toDomain()
    val alreadyThere = current != null && current.versionId == media.currentVersionId &&
      current.status in setOf(WorkStatus.PENDING, WorkStatus.IN_PROGRESS, WorkStatus.DONE)
    if (alreadyThere) return false
    workDao.upsert(listOf(WorkRules.pending(stage, media.currentVersionId, clock()).toEntity(media.id)))
    if (stage == WorkStage.META) tx.touch(media.id)
    return true
  }

  private suspend fun confirmOne(tx: Tx, phone: Location.Phone, stamp: FileStamp, seenAt: Long, ignoreGeneration: Boolean): Boolean {
    val entity = locationDao.byKey(phone.key.value) ?: return false
    val before = entity.toDomain()
    if (before.state == LocationState.ABSENT) return false
    if (!stamp.looksUnchangedFrom(before.stamp, ignoreGeneration)) return false
    val after = before.copy(
      location = phone,
      stamp = stamp,
      state = LocationState.PRESENT,
      absentSinceMillis = null,
      lastSeenAtMillis = seenAt,
    )
    locationDao.update(after.toEntity())
    tx.phoneSeen += entity.locationId
    if (before.location != after.location || before.state != after.state) {
      val mediaId = mediaOf(before.id)
      tx.touch(mediaId)
      tx.refChanged(mediaId)
    }
    return true
  }

  private suspend fun confirmNas(tx: Tx, seen: SeenNasFile, seenAt: Long): Boolean {
    val entity = locationDao.byKey(seen.location.key.value) ?: return false
    val before = entity.toDomain()
    if (before.state == LocationState.ABSENT) return false
    val beforeNas = before.location as? Location.Nas ?: return false
    if (!seen.stamp.sameAs(beforeNas.stampOf(before.stamp))) return false
    val after = before.copy(
      location = seen.location.copy(fileId = seen.stamp.fileId ?: seen.location.fileId ?: beforeNas.fileId),
      stamp = seen.stamp.toFileStamp(),
      state = LocationState.PRESENT,
      absentSinceMillis = null,
      lastSeenAtMillis = seenAt,
    )
    locationDao.update(after.toEntity())
    if (before.location != after.location || before.state != after.state) {
      val mediaId = mediaOf(before.id)
      tx.touch(mediaId)
      tx.refChanged(mediaId)
    }
    return true
  }

  private suspend fun adoptOne(tx: Tx, claim: IdentityClaim): AdoptOutcome {
    val entity = locationDao.byKey(claim.location.value) ?: return AdoptOutcome.NotFound
    val localId = mediaOf(LocationId(entity.locationId))
    val local = requireMedia(localId)
    val remote = claim.remote
    val here = resolve(remote.mediaId)?.let { if (it == localId) local else mediaDao.graph(it.value)?.toDomain() }
    val owner = versionDao.get(remote.versionId.value)?.let { row ->
      if (row.mediaId == localId.value) local else mediaDao.graph(row.mediaId)?.toDomain()
    }
    return when (val decision = IdentityRules.decide(local, LocationId(entity.locationId), remote, here, owner)) {
      AdoptionDecision.AlreadyShared -> AdoptOutcome.AlreadyShared(localId)
      AdoptionDecision.KeepLocal -> AdoptOutcome.KeptLocal(localId)
      is AdoptionDecision.Refuse -> AdoptOutcome.Refused(decision.reason)
      is AdoptionDecision.MergeWith -> when (val merged = mergeInTransaction(tx, localId, decision.other)) {
        is MergeOutcome.Merged -> AdoptOutcome.Merged(merged.survivor)
        is MergeOutcome.Refused -> AdoptOutcome.Refused(AdoptRefusal.NOT_PROVEN_IDENTICAL)
        MergeOutcome.NotFound -> AdoptOutcome.NotFound
      }
      is AdoptionDecision.Rename -> {
        val version = VersionId(entity.versionId)
        decision.versionTo?.let { to -> identityDao.renameVersion(version.value, to.value) }
        val result = decision.mediaTo?.let { to ->
          identityDao.renameMedia(localId.value, to.value)
          mediaDao.insertAlias(MediaAliasEntity(aliasId = localId.value, mediaId = to.value, mergedAt = clock()))
          tx.markChanged(localId, structural = true)
          tx.markChanged(to, structural = true)
          to
        } ?: localId
        tx.touch(result)
        AdoptOutcome.Adopted(result, mediaRenamed = decision.mediaTo != null, versionRenamed = decision.versionTo != null)
      }
    }
  }

  private suspend fun loadContext(observation: Observation, byContent: Boolean = true): LinkContext {
    val key = observation.location.key.value
    val fingerprint = observation.fingerprint
    val atSameKey = locationDao.byKey(key)?.let { known(it) }
    val atSameMediaStoreId = (observation.location as? Location.Phone)?.let { phone ->
      phone.mediaStoreId?.let { id ->
        locationDao.byMediaStoreId(phone.volume, id).firstOrNull { it.locationKey != key }?.let { known(it) }
      }
    }
    // R6: un'impronta adottata dall'indice non lega niente per contenuto (D8: serve l'impronta letta qui).
    if (!byContent) return LinkContext(atSameKey, atSameMediaStoreId)
    val sameContent = versionDao.byQuickFingerprint(fingerprint.size, fingerprint.quickHash.hex)
      .map { ContentCandidate(MediaId(it.mediaId), it.toDomain()) }
    val vanished = locationDao.vanishedWithContent(observation.location.type.code, fingerprint.size, fingerprint.quickHash.hex)
      .filter { it.locationKey != key }
      .map { known(it) }
    return LinkContext(atSameKey, atSameMediaStoreId, sameContent, vanished)
  }

  private suspend fun known(entity: MediaLocationEntity): KnownLocation {
    val graph = checkNotNull(mediaDao.graph(mediaOf(LocationId(entity.locationId)).value))
    val hint = graph.versions.firstOrNull { it.version.versionId == entity.versionId }?.metadata?.captureTimeHint()
    return KnownLocation(graph.toDomain(), LocationId(entity.locationId), hint)
  }

  private fun keepSeparate(decision: LinkDecision): LinkDecision = when (decision) {
    is LinkDecision.NeedsFullHash -> LinkDecision.NewMedia
    is LinkDecision.Replaced ->
      if (decision.next is LinkDecision.NeedsFullHash) decision.copy(next = LinkDecision.NewMedia) else decision
    else -> decision
  }

  private suspend fun apply(tx: Tx, decision: LinkDecision, observation: Observation, seenAt: Long): Pair<MediaId, LocationId>? {
    val now = clock()
    return when (decision) {
      is LinkDecision.SameFile -> {
        val before = requireLocation(decision.locationId)
        val after = before.copy(
          location = observation.location,
          stamp = observation.stamp,
          state = LocationState.PRESENT,
          absentSinceMillis = null,
          // Timbro cambiato: la verifica valeva per i byte di prima.
          verifiedAtMillis = if (decision.stampChanged) null else before.verifiedAtMillis,
          lastSeenAtMillis = seenAt,
        )
        locationDao.update(after.toEntity())
        learnFullHash(tx, before.versionId, observation.fingerprint)
        tx.touch(decision.mediaId)
        if (before.location != after.location || before.state != after.state) tx.refChanged(decision.mediaId)
        decision.mediaId to decision.locationId
      }

      is LinkDecision.ContentChanged -> {
        val media = requireMedia(decision.mediaId)
        val plan = VersionRules.contentChangedAt(media, decision.locationId, observation.fingerprint, observation.stamp, now)
        persist(tx, media, plan, observed = mapOf(decision.locationId to observation.location), seenAt = seenAt)
        decision.mediaId to decision.locationId
      }

      is LinkDecision.RevertedToVersion -> {
        val media = requireMedia(decision.mediaId)
        val plan = VersionRules.revertedAt(media, decision.locationId, decision.versionId, observation.stamp, now)
        persist(tx, media, plan, observed = mapOf(decision.locationId to observation.location), seenAt = seenAt)
        learnFullHash(tx, decision.versionId, observation.fingerprint)
        // P14 (E-E2): il telefono e' tornato a una versione nota: dopo il commit si annuncia, il backup scambia le versioni sul NAS.
        tx.reverted += decision.mediaId
        decision.mediaId to decision.locationId
      }

      is LinkDecision.Moved -> {
        val before = requireLocation(decision.locationId)
        val after = before.copy(
          location = observation.location,
          stamp = observation.stamp,
          state = LocationState.PRESENT,
          absentSinceMillis = null,
          verifiedAtMillis = if (observation.stamp.looksUnchangedFrom(before.stamp)) before.verifiedAtMillis else null,
          lastSeenAtMillis = seenAt,
        )
        locationDao.update(after.toEntity())
        learnFullHash(tx, before.versionId, observation.fingerprint)
        tx.touch(decision.mediaId)
        tx.refChanged(decision.mediaId)
        decision.mediaId to decision.locationId
      }

      is LinkDecision.NewLocation -> {
        val id = locationDao.insert(
          locationEntity(0, decision.versionId, observation.location, observation.stamp, LocationState.PRESENT, null, null, seenAt),
        )
        learnFullHash(tx, decision.versionId, observation.fingerprint)
        tx.touch(decision.mediaId)
        tx.refChanged(decision.mediaId)
        // Altri media con gli stessi byte (prova: hash completo) si uniscono subito.
        var survivor = decision.mediaId
        decision.alsoIdenticalTo.forEach { other ->
          (mergeInTransaction(tx, survivor, other) as? MergeOutcome.Merged)?.let { survivor = it.survivor }
        }
        survivor to LocationId(id)
      }

      is LinkDecision.NeedsFullHash -> null

      LinkDecision.NewMedia -> createMedia(tx, observation, now, seenAt)

      is LinkDecision.Replaced -> {
        if (decision.next is LinkDecision.NeedsFullHash) return null
        locationDao.delete(decision.previousLocationId.value)
        tx.touch(decision.previousMediaId)
        tx.refChanged(decision.previousMediaId)
        apply(tx, decision.next, observation, seenAt)
      }
    }
  }

  private suspend fun createMedia(tx: Tx, observation: Observation, now: Long, seenAt: Long): Pair<MediaId, LocationId> {
    val mediaId = MediaId.random()
    val version = MediaVersion(VersionId.random(), VersionKind.ORIGINAL, null, observation.fingerprint, observation.stamp.modifiedAtMillis)
    val owner = SafetyRules.ownerForNewMedia(observation.location, localAccount, areas(tx))
    // Provvisoria: la data di scatto vera arriva con la fase META.
    val sortTime = observation.captureTimeHintMillis ?: observation.stamp.modifiedAtMillis
    mediaDao.insert(
      MediaEntity(
        mediaId = mediaId.value,
        kind = observation.kind.code,
        source = observation.source.code,
        ownerAccount = owner?.value,
        groupId = null,
        currentVersionId = version.id.value,
        createdAt = now,
        updatedAt = now,
        sortTime = sortTime,
        presence = Presence.NONE.code,
        safety = SafetyStatus.LOST.code,
        // Il giorno nel fuso del telefono adesso, salvato una volta (E-P5): META lo sostituira'.
        localDay = TimelineRules.localDay(sortTime, zone()),
      ),
    )
    versionDao.insert(version.toEntity(mediaId))
    val locationId = locationDao.insert(
      locationEntity(0, version.id, observation.location, observation.stamp, LocationState.PRESENT, null, null, seenAt),
    )
    workDao.upsert(WorkRules.initialStates(observation.kind, version.id, now).map { it.toEntity(mediaId) })
    tx.touch(mediaId)
    tx.markChanged(mediaId, structural = true)
    return mediaId to LocationId(locationId)
  }

  /** Scrive un cambio di versione: versione nuova, posizioni spostate, corrente, fasi da rifare. */
  private suspend fun persist(
    tx: Tx,
    before: Media,
    plan: VersionPlan,
    observed: Map<LocationId, Location> = emptyMap(),
    seenAt: Long? = null,
  ) {
    val now = clock()
    plan.newVersion?.let { version ->
      versionDao.insert(version.toEntity(before.id))
      // Metadati provvisori: quelli del genitore, finche' la fase META non rilegge i byte nuovi.
      version.parentId?.let { parent ->
        versionDao.metadata(parent.value)?.let { versionDao.upsertMetadata(it.copy(versionId = version.id.value, updatedAt = now)) }
      }
    }
    plan.changedLocations.forEach { location ->
      val updated = observed[location.id]?.let { location.copy(location = it, lastSeenAtMillis = seenAt ?: location.lastSeenAtMillis) }
        ?: location
      locationDao.update(updated.toEntity())
    }
    mediaDao.setCurrentVersion(before.id.value, plan.media.currentVersionId.value, now)
    if (plan.stagesToRedo.isNotEmpty()) {
      val states = workDao.forMedia(before.id.value).map { it.toDomain() }
      workDao.upsert(WorkRules.invalidate(states, plan.stagesToRedo, plan.media.currentVersionId, now).map { it.toEntity(before.id) })
    }
    tx.touch(before.id)
    // Versione corrente nuova: miniatura e segnaposto vanno riletti anche se i derivati restano uguali.
    if (plan.media.currentVersionId != before.currentVersionId) tx.markChanged(before.id, structural = false)
    if (plan.changedLocations.isNotEmpty()) tx.refChanged(before.id)
  }

  /**
   * Ogni riga che punta al media [loser] (per id, non per versione) passa a [survivor], prima che [loser] se ne vada: album e copertine, gruppi,
   * le tabelle della v6 (foto d'esempio delle regole, esclusioni, suggeriti, diario della condivisione, copertine delle proposte di viaggio) e
   * quelle della v4 (diario del backup, problemi, attivita', sessioni di modifica, "Libera spazio"). **Una sola strada** per l'unione
   * ([mergeInTransaction]) e per la copia di un editor ([linkCopyAsVersion]): una tabella nuova che punta a `media` si aggiunge qui, e vale
   * per entrambe (R21 della revisione di sicurezza della Fase 7; `MergeReferencesTest` elenca le colonne, `MergeV6Test` prova le due strade).
   */
  private suspend fun repointMediaReferences(loser: String, survivor: String) {
    mergeDao.copyAlbumItems(loser, survivor)
    mergeDao.repointAlbumCovers(loser, survivor)
    mergeDao.repointGroupCovers(loser, survivor)
    // v6: foto d'esempio, esclusioni, suggeriti, diario della condivisione e copertine delle proposte restano attaccati al sopravvissuto.
    mergeDao.repointAlbumRules(loser, survivor)
    mergeDao.copyAlbumExclusions(loser, survivor)
    mergeDao.copyAlbumSuggestions(loser, survivor)
    mergeDao.repointAlbumShareItems(loser, survivor)
    mergeDao.repointTripCovers(loser, survivor)
    // v4: nessuna riga del diario, del problema o delle attivita' si perde con il perdente.
    mergeDao.repointBackupItemMedia(loser, survivor)
    mergeDao.repointBackupProblem(loser, survivor)
    mergeDao.repointPendingTaskMedia(loser, survivor)
    mergeDao.repointEditSessionMedia(loser, survivor)
    mergeDao.repointFreeSpaceItemMedia(loser, survivor)
  }

  private suspend fun mergeInTransaction(tx: Tx, a: MediaId, b: MediaId): MergeOutcome {
    val first = mediaDao.graph(a.value) ?: return MergeOutcome.NotFound
    val second = mediaDao.graph(b.value) ?: return MergeOutcome.NotFound
    val plan = MergeRules.plan(first.toDomain(), second.toDomain(), localAccount)
    if (plan is MergePlan.Refused) return MergeOutcome.Refused(plan.reason)
    plan as MergePlan.Merge

    val now = clock()
    val survivor = plan.survivor.value
    val loser = plan.loser.value
    val survivorRow = if (first.media.mediaId == survivor) first.media else second.media
    val loserRow = if (first.media.mediaId == loser) first.media else second.media
    val current = plan.merged.currentVersionId

    // 1. Le versioni del perdente con gli stessi byte di una del sopravvissuto: chi le usa passa all'altra.
    plan.versionMapping.forEach { (from, to) ->
      mergeDao.repointLocations(from.value, to.value)
      mergeDao.repointWorkVersion(from.value, to.value)
      mergeDao.repointEmbeddingVersion(from.value, to.value)
      mergeDao.repointParents(from.value, to.value)
      // v4: il diario del backup, le firme visive e le tabelle della 5B seguono la versione.
      mergeDao.repointBackupItemVersion(from.value, to.value)
      mergeDao.repointSignature(from.value, to.value)
      mergeDao.repointPendingTaskVersion(from.value, to.value)
      mergeDao.repointEditSessionVersion(from.value, to.value)
      mergeDao.repointFreeSpaceItemVersion(from.value, to.value)
      // P14: la correzione solo nell'app segue la versione (se la destinazione ne ha gia' una resta quella).
      mergeDao.repointMetadataCorrection(from.value, to.value)
      mergeDao.repointBackupItemCurrent(from.value, to.value)
    }
    // 2. Le altre versioni passano al sopravvissuto, con posizioni e metadati.
    plan.movedVersions.forEach { mergeDao.moveVersion(it.id.value, survivor, it.parentId?.value) }

    // 3. Dati personali: preferito o nascosto se lo era uno dei due (nel dubbio si nasconde).
    val mine = personalDao.get(survivor)
    val theirs = personalDao.get(loser)
    if (mine != null || theirs != null) {
      val merged = (mine?.toDomain() ?: PersonalFlags()).mergedWith(theirs?.toDomain() ?: PersonalFlags())
      personalDao.upsert(mergedPersonal(plan.survivor, merged, mine, theirs, now))
    }

    // 4. Album, copertine, gruppi, le tabelle della v6 e della v4 (una sola strada con `linkCopyAsVersion`).
    repointMediaReferences(loser, survivor)

    // 5. Vettori: per ogni spazio e indice, quello calcolato dalla versione corrente.
    val survivorVectors = embeddingDao.forMedia(survivor).associateBy { slotOf(it) }
    embeddingDao.forMedia(loser).forEach { loserRow ->
      val survivorVector = survivorVectors[slotOf(loserRow)]?.toDomain()
      val loserVector = loserRow.toDomain()
      val keepLoser = survivorVector == null || MergeRules.chooseVector(survivorVector, loserVector, current) === loserVector
      if (keepLoser) embeddingDao.upsert(loserRow.withMedia(survivor))
    }

    // 6. Stati di lavoro.
    val states = WorkRules.merge(
      workDao.forMedia(survivor).map { it.toDomain() },
      workDao.forMedia(loser).map { it.toDomain() },
      current,
    )
    workDao.upsert(states.map { it.toEntity(plan.survivor) })

    // 7. L'identita' del perdente resta come alias (anche gli alias che puntavano a lui).
    mergeDao.repointAliases(loser, survivor)
    mediaDao.insertAlias(MediaAliasEntity(aliasId = loser, mediaId = survivor, mergedAt = now))

    // 8. Il media unito. Il perdente se ne va: in cascata partono solo righe gia' copiate.
    mediaDao.update(
      survivorRow.copy(
        currentVersionId = current.value,
        ownerAccount = plan.merged.owner?.value,
        source = plan.merged.source.code,
        groupId = plan.merged.groupId?.value,
        createdAt = plan.merged.createdAtMillis,
        updatedAt = now,
      ),
    )
    mediaDao.delete(loser)
    // Il gruppo del perdente perde un membro: la sua copertina si ricalcola; un gruppo di copie simili rimasto con un media solo si scioglie.
    loserRow.groupId?.let { old ->
      if (mediaDao.group(old)?.kind == MediaGroupKind.SIMILAR_COPIES.code) dissolveIfAlone(tx, old) else tx.dirtyGroups += old
    }
    tx.touch(plan.survivor)
    tx.touch(plan.loser)
    tx.markChanged(plan.survivor, structural = true)
    tx.markChanged(plan.loser, structural = true)
    // Il media unito e' nascosto (nel dubbio si nasconde): se sta in un gruppo di copie simili lo sono tutte (E-H1).
    if (personalDao.get(survivor)?.hidden == true) spreadHidden(tx, plan.survivor, true)
    return MergeOutcome.Merged(plan.survivor, plan.loser)
  }

  private fun slotOf(row: EmbeddingEntity): String = "${row.spaceId}/${row.dimension}/${row.precision}/${row.vectorIndex}"

  /**
   * Impara l'hash completo di una versione da un'osservazione con gli stessi byte. Dalla v3: la fase `FULL_HASH` di quel
   * media, se era in coda per quella versione, e' fatta; e se il media e' in un gruppo `PROBABLE_DUPLICATE` il gruppo prova
   * a risolversi (D21).
   */
  private suspend fun learnFullHash(tx: Tx, versionId: VersionId, observed: Fingerprint) {
    val hash = observed.fullHash ?: return
    val entity = versionDao.get(versionId.value) ?: return
    if (entity.fullHash != null) return
    if (entity.toDomain().fingerprint.match(observed) == FingerprintMatch.DIFFERENT) return
    versionDao.update(entity.copy(fullHash = hash.hex))
    val mediaId = MediaId(entity.mediaId)
    workDao.forMedia(entity.mediaId).firstOrNull { it.stage == WorkStage.FULL_HASH.code && it.versionId == entity.versionId }
      ?.takeIf { it.status != WorkStatus.DONE.code }
      ?.let { workDao.upsert(listOf(WorkRules.succeeded(it.toDomain(), versionId, clock()).toEntity(mediaId))) }
    mediaDao.get(entity.mediaId)?.groupId?.let { settleProbableDuplicate(tx, it) }
  }

  /**
   * Scrive i flag personali di [id] se cambiano, con l'istante del cambio di ciascuno. **Nascondere o mostrare un media di un
   * gruppo di copie simili vale per tutte le copie** (D36, E-H1): [spread] lo propaga ai compagni passando da qui, quindi anche
   * per loro si registra `hidden_changed_at` e la sincronizzazione dei dati personali vede il cambiamento.
   */
  private suspend fun writePersonal(tx: Tx, id: MediaId, flags: PersonalFlags, spread: Boolean = true) {
    val row = personalDao.get(id.value)
    val before = row?.toDomain() ?: PersonalFlags()
    if (before == flags) return
    val now = clock()
    personalDao.upsert(
      MediaPersonalEntity(
        mediaId = id.value,
        favorite = flags.favorite,
        hidden = flags.hidden,
        updatedAt = now,
        favoriteChangedAt = if (flags.favorite != before.favorite || row == null) now else row.favoriteChangedAt,
        hiddenChangedAt = if (flags.hidden != before.hidden || row == null) now else row.hiddenChangedAt,
      ),
    )
    tx.touch(id)
    if (flags.hidden != before.hidden) {
      tx.markChanged(id, structural = true)
      if (spread) spreadHidden(tx, id, flags.hidden)
    }
  }

  /** Il nascosto di [id] passa a tutte le altre copie del suo gruppo di copie simili, se ne ha uno (E-H1). */
  private suspend fun spreadHidden(tx: Tx, id: MediaId, hidden: Boolean) {
    val group = mediaDao.get(id.value)?.groupId ?: return
    if (groupKind(tx, group) != MediaGroupKind.SIMILAR_COPIES.code) return
    mediaDao.groupMembers(group).forEach { member ->
      if (member.mediaId == id.value) return@forEach
      val current = personalDao.get(member.mediaId)?.toDomain() ?: PersonalFlags()
      if (current.hidden != hidden) writePersonal(tx, MediaId(member.mediaId), current.copy(hidden = hidden), spread = false)
    }
  }

  /**
   * I flag del media unito: per ogni flag l'istante del cambio resta quello di chi aveva gia' quel valore
   * (prima il sopravvissuto), altrimenti e' adesso.
   */
  private fun mergedPersonal(
    survivor: MediaId,
    merged: PersonalFlags,
    mine: MediaPersonalEntity?,
    theirs: MediaPersonalEntity?,
    now: Long,
  ): MediaPersonalEntity {
    fun changedAt(value: Boolean, read: (MediaPersonalEntity) -> Pair<Boolean, Long>): Long =
      listOfNotNull(mine, theirs).map(read).firstOrNull { it.first == value }?.second ?: now
    return MediaPersonalEntity(
      mediaId = survivor.value,
      favorite = merged.favorite,
      hidden = merged.hidden,
      updatedAt = now,
      favoriteChangedAt = changedAt(merged.favorite) { it.favorite to it.favoriteChangedAt },
      hiddenChangedAt = changedAt(merged.hidden) { it.hidden to it.hiddenChangedAt },
    )
  }

  private suspend fun groupInTransaction(tx: Tx, kind: MediaGroupKind, members: Collection<MediaId>, cover: MediaId): MediaGroupId {
    val resolved = members.map { checkNotNull(resolve(it)) { "Media sconosciuto." } }.distinct()
    val coverId = checkNotNull(resolve(cover)) { "Media sconosciuto." }
    require(resolved.size >= 2) { "Un gruppo ha almeno due media." }
    require(coverId in resolved) { "La copertina e' uno dei membri." }
    val group = MediaGroupId.random()
    mediaDao.insertGroup(MediaGroupEntity(group.value, kind.code, coverId.value, clock()))
    tx.groupKinds[group.value] = kind.code
    val left = mutableSetOf<String>()
    resolved.forEach { id ->
      mediaDao.get(id.value)?.groupId?.let { left += it }
      mediaDao.setGroup(id.value, group.value, clock())
      tx.touch(id)
      tx.markChanged(id, structural = true)
    }
    left.forEach { old -> dissolveIfAlone(tx, old) }
    tx.dirtyGroups += group.value
    return group
  }

  private suspend fun dissolve(tx: Tx, groupId: String) {
    mediaDao.groupMembers(groupId).forEach { member ->
      mediaDao.setGroup(member.mediaId, null, clock())
      val id = MediaId(member.mediaId)
      tx.touch(id)
      tx.markChanged(id, structural = true)
    }
    mediaDao.deleteGroup(groupId)
    tx.groupKinds[groupId] = null
  }

  private suspend fun dissolveIfAlone(tx: Tx, groupId: String) {
    if (mediaDao.groupMembers(groupId).size < 2) dissolve(tx, groupId) else tx.dirtyGroups += groupId
  }

  /** I derivati di un media, calcolati e non ancora scritti. */
  private class Derived(
    val row: MediaEntity,
    val owner: AccountId?,
    val presence: Presence,
    val safety: SafetyStatus,
    val sortTime: Long,
    val localDay: Int,
    val aspectMilli: Int,
    val durationMs: Long?,
    val placeId: Int?,
    var cellFlags: Int,
    val thumbHash: ByteArray?,
    val hidden: Boolean,
    /** v4: il media e' un candidato del backup (`TimelineRules.backupTodo`). */
    val backupTodo: Boolean,
    /** v4: il media e' tornato al sicuro e un problema del backup di prima non vale piu': la riga si toglie. */
    val staleProblem: Boolean,
    /** Campo, gruppo A (D4): il candidato del backup e' rimasto senza copie: la riga `lost_before_backup` si scrive (E-B4). */
    val lostBeforeBackup: Boolean = false,
    /** v6 (Fase 7): il dispositivo d'origine e dove stanno le copie sul NAS ([OriginDevice], [CopyAreas]). */
    val originDevice: String? = null,
    val copyAreas: Int = 0,
  ) {
    val visible: Boolean get() = presence != Presence.NONE && !hidden
  }

  /**
   * Ricalcola i campi derivati dei media toccati dalla transazione (e dei loro gruppi) con le regole di
   * dominio, li scrive se sono cambiati e annota che cosa cambia per l'indice.
   */
  private suspend fun refreshDerived(tx: Tx) {
    // Prima del ricalcolo, cosi' il bit della cella e `backup_todo` nascono gia' senza il segno (stessa transazione, un cambiamento).
    releaseRecoveredLostMedia(tx)
    if (tx.dirty.isEmpty() && tx.dirtyGroups.isEmpty()) return
    val rows = LinkedHashMap<String, MediaEntity?>()
    tx.dirty.forEach { rows[it.value] = mediaDao.get(it.value) }
    val groups = LinkedHashSet(tx.dirtyGroups)
    rows.values.forEach { row -> row?.groupId?.let { groups += it } }
    groups.forEach { group -> mediaDao.groupMembers(group).forEach { rows.putIfAbsent(it.mediaId, it) } }

    val derived = LinkedHashMap<String, Derived>()
    rows.forEach { (id, row) ->
      if (row == null) {
        // Cancellato (perdente di un'unione): esce dall'indice.
        tx.markChanged(MediaId(id), structural = true)
      } else {
        derived[id] = derive(tx, row)
      }
    }

    groups.forEach { group ->
      val members = derived.values.filter { it.row.groupId == group }
      val list = members.map { GroupMember(MediaId(it.row.mediaId), it.sortTime, it.visible) }
      val designated = mediaDao.group(group)?.coverMediaId?.let(::MediaId)
      val similar = groupKind(tx, group) == MediaGroupKind.SIMILAR_COPIES.code
      // Copie simili (D36, E-H1): il nascosto e' contagioso, quindi con una copia nascosta nessuna rappresenta il gruppo.
      val cover = if (similar) TimelineRules.similarCopiesCover(list, designated, members.any { it.hidden }) else TimelineRules.groupCover(list, designated)
      val memberFlags = members.map { it.cellFlags }
      members.forEach { it.cellFlags = TimelineRules.withGroupRole(it.cellFlags, inGroup = true, isCover = it.row.mediaId == cover?.value) }
      // La cella del gruppo di copie simili riassume i segni di presenza di tutte le copie (§2.12).
      if (similar && cover != null) {
        derived[cover.value]?.let { it.cellFlags = TimelineRules.similarCopiesFlags(it.cellFlags, memberFlags) }
      }
    }
    derived.values.filter { it.row.groupId == null }.forEach {
      it.cellFlags = TimelineRules.withGroupRole(it.cellFlags, inGroup = false, isCover = false)
    }

    derived.values.forEach { store(tx, it) }
  }

  /**
   * "Persa prima del backup" (E-B4) vale finche' la foto non c'e': il messaggio all'utente dice "Recuperala dal cestino di
   * Galleria" (E-P11, F26), e se la riga ricompare nel MediaStore il segno non deve tenerla fuori dai candidati per sempre. Un
   * media segnato `LOST_BEFORE_BACKUP` con una posizione del telefono che un'osservazione di questa transazione ha registrato o
   * confermato **presente** perde il segno qui: la riga di `backup_problem` si toglie e il media si tocca, cosi' il ricalcolo che
   * segue (bit `BACKUP_PROBLEM` della cella e `backup_todo`) lo rimette fra i candidati nella stessa transazione.
   *
   * E' l'unico posto della regola: le vie d'ingresso ([recordOne], [confirmOne]) annotano soltanto la posizione in [Tx.phoneSeen].
   * Si guarda lo stato **letto adesso** dal database (una posizione tolta o rimasta assente in questa transazione non conta). Gli
   * altri codici di problema non cambiano comportamento: si tolgono solo da [markBackupProblem] o quando il media torna al sicuro.
   */
  private suspend fun releaseRecoveredLostMedia(tx: Tx) {
    if (tx.phoneSeen.isEmpty()) return
    for (chunk in tx.phoneSeen.chunked(TimelineIndexDao.MAX_IDS)) {
      problemDao.mediaIdsWithCodeAt(LOST_BEFORE_BACKUP, LocationState.PRESENT.code, chunk).distinct().forEach { id ->
        problemDao.delete(id)
        tx.touch(MediaId(id))
      }
    }
  }

  private suspend fun derive(tx: Tx, row: MediaEntity): Derived {
    val graph = checkNotNull(mediaDao.graph(row.mediaId))
    val areas = areas(tx)
    val stored = graph.toDomain()
    val owner = SafetyRules.claimedOwner(stored, areas)
    val media = stored.copy(owner = owner)
    val metadataRow = graph.versions.firstOrNull { it.version.versionId == row.currentVersionId }?.metadata
    val metadata = metadataRow?.toDomain()
    val personal = personalDao.get(row.mediaId)
    val unreadable = workDao.status(row.mediaId, WorkStage.META.code) == WorkStatus.GAVE_UP.code
    val capture = metadata?.captureTime
    val sortTime = capture?.utcMillis ?: row.sortTime
    val safety = SafetyRules.evaluate(media, localAccount, areas).status
    val probableDuplicate = row.groupId?.let { groupKind(tx, it) } == MediaGroupKind.PROBABLE_DUPLICATE.code
    // v4: il problema che il backup ha registrato; vale finche' il media non e' di nuovo al sicuro.
    val problem = problemDao.get(row.mediaId)
    // Campo, gruppo A (D4, F26, E-B4): un candidato del backup (`backup_todo` di prima: presente sul telefono, proprio, non al sicuro) che resta senza
    // nessuna copia e' "perso prima del backup", anche se la foto e' sparita **prima** che un giro la prendesse in lista (prima lo segnava solo il
    // motore, per un candidato sparito a giro in corso, e la foto usciva dalla Libreria in silenzio). Se la riga torna, la regola di
    // [releaseRecoveredLostMedia] toglie il segno.
    val lostBeforeBackup = problem == null && row.backupTodo && safety == SafetyStatus.LOST
    val markedProblem = (problem?.marksCell == true || lostBeforeBackup) && safety != SafetyStatus.SAFE
    val presence = SafetyRules.presence(media)
    return Derived(
      row = row,
      owner = owner,
      presence = presence,
      safety = safety,
      sortTime = sortTime,
      // Con la data letta, il giorno nell'ora del posto; senza, quello provvisorio salvato alla prima lettura.
      localDay = capture?.let(TimelineRules::localDay)
        ?: row.localDay.takeIf { it != 0 && sortTime == row.sortTime }
        ?: TimelineRules.localDay(sortTime, zone()),
      aspectMilli = TimelineRules.aspectMilli(metadata),
      durationMs = metadata?.durationMillis,
      placeId = metadata?.place?.geonameId,
      cellFlags = TimelineRules.cellFlags(
        kind = media.kind,
        currentVersionKind = media.currentVersion.kind,
        metadata = metadata,
        favorite = personal?.favorite == true,
        unreadable = unreadable,
      ) or TimelineRules.presenceFlags(media, safety, probableDuplicate) or TimelineRules.backupProblemFlags(markedProblem, safety) or
        // v5: i bit derivati dall'indice AI li scrive `setDerivedFlags`, non le regole: il ricalcolo li conserva.
        (row.cellFlags and AI_DERIVED_MASK),
      thumbHash = metadataRow?.thumbHash,
      hidden = personal?.hidden == true,
      backupTodo = TimelineRules.backupTodo(presence, safety, markedProblem),
      staleProblem = problem != null && safety == SafetyStatus.SAFE,
      lostBeforeBackup = lostBeforeBackup,
      originDevice = OriginDevice.of(media.locations, areas),
      copyAreas = CopyAreas.of(media.locations, areas),
    )
  }

  /** Scrive i derivati se sono cambiati e annota il cambiamento per l'indice. */
  private suspend fun store(tx: Tx, d: Derived) {
    val row = d.row
    val id = MediaId(row.mediaId)
    val now = clock()
    if (d.owner?.value != row.ownerAccount) mediaDao.setOwner(row.mediaId, d.owner?.value, now)
    // Un problema del backup di prima, e il media e' di nuovo al sicuro: non vale piu' (stessa transazione).
    if (d.staleProblem) problemDao.delete(row.mediaId)
    if (d.lostBeforeBackup) problemDao.upsert(BackupProblemEntity(row.mediaId, LOST_BEFORE_BACKUP, true, now))
    val same = row.presence == d.presence.code && row.safety == d.safety.code && row.sortTime == d.sortTime &&
      row.localDay == d.localDay && row.aspectMilli == d.aspectMilli && row.durationMs == d.durationMs &&
      row.placeId == d.placeId && row.cellFlags == d.cellFlags && row.thumbHash.contentEquals(d.thumbHash) &&
      row.backupTodo == d.backupTodo && row.originDevice == d.originDevice && row.copyAreas == d.copyAreas
    if (same) return
    mediaDao.updateDerived(
      mediaId = row.mediaId,
      presence = d.presence.code,
      safety = d.safety.code,
      sortTime = d.sortTime,
      localDay = d.localDay,
      aspectMilli = d.aspectMilli,
      durationMs = d.durationMs,
      placeId = d.placeId,
      cellFlags = d.cellFlags,
      thumbHash = d.thumbHash,
      backupTodo = d.backupTodo,
      originDevice = d.originDevice,
      copyAreas = d.copyAreas,
      now = now,
    )
    // `backup_todo`, `origin_device` e `copy_areas` non sono nell'indice della timeline: da soli non annunciano nulla.
    if (row.presence == d.presence.code && row.safety == d.safety.code && row.sortTime == d.sortTime && row.localDay == d.localDay &&
      row.aspectMilli == d.aspectMilli && row.durationMs == d.durationMs && row.placeId == d.placeId && row.cellFlags == d.cellFlags &&
      row.thumbHash.contentEquals(d.thumbHash)
    ) {
      return
    }
    val wasVisible = row.presence != Presence.NONE.code
    val memberBefore = CellFlags.has(row.cellFlags, CellFlags.GROUP_MEMBER)
    val memberAfter = CellFlags.has(d.cellFlags, CellFlags.GROUP_MEMBER)
    val structural = wasVisible != (d.presence != Presence.NONE) || memberBefore != memberAfter ||
      row.sortTime != d.sortTime || row.localDay != d.localDay
    tx.markChanged(id, structural)
  }

  private suspend fun areas(tx: Tx): NasAreaLookup = tx.areas ?: areas().also { tx.areas = it }

  private suspend fun areas(): NasAreaLookup = NasAreaLookup.of(shareDao.all().map { it.toDomain() })

  private suspend fun requireMedia(id: MediaId): Media =
    checkNotNull(mediaDao.graph(id.value)) { "Media sconosciuto." }.toDomain()

  private suspend fun requireLocation(id: LocationId): MediaLocation =
    checkNotNull(locationDao.get(id.value)) { "Posizione sconosciuta." }.toDomain()

  private suspend fun mediaOf(id: LocationId): MediaId =
    MediaId(checkNotNull(locationDao.mediaIdOf(id.value)) { "Posizione senza media." })

  companion object {
    /** Quanti cambiamenti il flusso trattiene per un collector lento prima di perdere i piu' vecchi. */
    const val CHANGE_BUFFER: Int = 256

    /** Chiave di `catalog_meta`: la versione del dataset dei luoghi dei `place_id`. */
    const val PLACES_DATASET: String = "places_dataset"

    /**
     * Ogni colonna che punta a `media` (tabella.colonna). [merge] le gestisce tutte prima di
     * cancellare il perdente; `MergeReferencesTest` confronta questo elenco con lo schema vero e
     * fallisce se una tabella nuova lo referenzia senza essere stata aggiunta qui (e all'unione).
     */
    val MEDIA_REFERENCES: Set<String> = setOf(
      "media_version.media_id",
      "media_personal.media_id",
      "work_state.media_id",
      "embedding.media_id",
      "media_alias.media_id",
      "media_group.cover_media_id",
      "album.cover_media_id",
      "album_item.media_id",
      // v6 (Fase 7): le regole (foto d'esempio), le esclusioni, i suggeriti, il diario della condivisione e la copertina di una proposta
      "album_rule.media_id",
      "album_exclusion.media_id",
      "album_suggestion.media_id",
      "album_share_item.media_id",
      "trip_proposal.cover_media_id",
      // v4 (Fase 5): il diario del backup, il problema di un media e le tabelle della 5B che riguardano un media
      "backup_item.media_id",
      "backup_problem.media_id",
      "pending_task.media_id",
      "edit_session.media_id",
      "free_space_item.media_id",
    )

    /**
     * Ogni colonna che punta a una versione (`media_version.version_id`) (tabella.colonna). La rinomina di una versione
     * adottata (D7, `IdentityDao.renameVersion`) le aggiorna tutte; l'unione le traduce con `MergeDao`.
     * `MergeReferencesTest` confronta questo elenco con lo schema vero.
     */
    val VERSION_REFERENCES: Set<String> = setOf(
      "media.current_version_id",
      "media_version.parent_version_id",
      "media_location.version_id",
      "version_metadata.version_id",
      "work_state.version_id",
      "embedding.version_id",
      // v4 (Fase 5): il diario, la firma visiva e le tabelle della 5B seguono la versione
      "backup_item.version_id",
      "visual_signature.version_id",
      "pending_task.version_id",
      "edit_session.version_id",
      "free_space_item.version_id",
      // P14: le correzioni solo nell'app (Q6 (a)) seguono la versione
      "metadata_correction.version_id",
    )

    /**
     * Le tabelle che **non** contengono identita' di media (ne' con chiave esterna ne' senza): l'unione
     * non le tocca. `MergeReferencesTest` obbliga a classificare ogni tabella nuova qui o in
     * [MEDIA_REFERENCES] (o a puntare a `media_version`, che l'unione gestisce per versione), o in
     * [TABLES_WITH_INDEX_IDS].
     */
    val TABLES_WITHOUT_MEDIA_IDS: Set<String> = setOf(
      "nas_share",
      "media_store_cursor",
      "catalog_meta",
      // v3
      "nas_scan_state",
      "index_publish_state",
      "index_own_segment",
      "index_outbox_blob",
      "index_own_record",
      "index_own_blob",
      "index_head",
      "index_segment",
      "replica_dirty",
      "replica_pack",
      "replica_blob",
      "replica_share_access",
      "replica_setting",
      "replica_provisional",
      "nas_op",
      "nas_op_item",
      "nas_trash_entry",
      // revisione 4A (R6): per posizione, e una posizione resta la stessa anche quando il suo media si unisce a un altro.
      "nas_adopted_fingerprint",
      // v4 (Fase 5): per posizione, come sopra (e con cascata sulla posizione)
      "nas_copy_mismatch",
      // v4 (Fase 5): il giro di "Libera spazio" (i suoi elementi puntano ai media, lui no) e un file in arrivo (non e' ancora un media)
      "free_space_run",
      "incoming_item",
      // v5 (Fase 6, D37): tutte per chiave di contenuto, nessuna punta a `media` ne' a `media_version`. Il collegamento ai media passa
      // dalle versioni (`size`, `quick_hash`: `cellContentKeys`), quindi l'unione e la rinomina d'identita' non le toccano.
      // `ai_text_fts` e' la tabella FTS4 di `ai_text` (le sue tabelle d'appoggio `ai_text_fts_*` le crea SQLite).
      "ai_vector",
      "ai_text",
      "ai_text_fts",
      "ai_vocabulary",
      "search_history",
      "ai_pending_publish",
      "ai_own_head",
      "ai_own_segment",
      "ai_own_live",
      "ai_replica_head",
      "ai_replica_segment",
      "ai_replica_entry",
      "ai_replica_applied",
      "ai_replica_dirty",
      // v6 (Fase 7): la replica degli album condivisi porta gli id di media di chi ha scritto (non quelli locali: si attestano per
      // chiave di contenuto, D9) e nessuna chiave esterna; il diario ha l'operazione (i suoi elementi puntano ai media, lui no); la
      // "casa" non e' di nessun media.
      "shared_album",
      "shared_album_entry",
      "shared_album_head_mark",
      "album_share_op",
      "home_estimate",
    )

    /**
     * Le tabelle (v3) che contengono identita' di media **come dati dell'indice condiviso** (com'erano nei record firmati o
     * in coda per esserlo), non come riferimenti locali: l'unione e la rinomina non le toccano (lo stato di un dispositivo
     * e' funzione della sua testa, D5) e l'adozione D7 passa dal catalogo. Le colonne con quelle identita' si chiamano
     * `remote_*` (`MergeReferencesTest`).
     */
    val TABLES_WITH_INDEX_IDS: Set<String> = setOf(
      "index_record",
      "index_outbox_record",
    )

    /**
     * I bit di `cell_flags` che non si calcolano dalle regole ma dall'indice AI (`setDerivedFlags`) e che il ricalcolo dei derivati
     * conserva: oggi `CellFlags.DOCUMENT` (D34).
     */
    const val AI_DERIVED_MASK: Int = CellFlags.DOCUMENT

    /** Quanti media per pagina di [cellContentKeys] (e quanti al massimo). */
    const val CELL_KEYS_PAGE: Int = 5_000
    const val MAX_CELL_KEYS_PAGE: Int = 200_000

    /** Quanti media si leggono con una query sola dentro una pagina (una finestra del cursore: vedi [cellContentKeys]). */
    const val CELL_KEYS_SLICE: Int = 4_096

    /** Il lotto di [setDerivedFlags] (D34: lotti di 500, un cambiamento dell'indice della timeline per lotto) e il suo massimo. */
    const val DERIVED_FLAGS_BATCH: Int = 500
    const val MAX_DERIVED_FLAGS_BATCH: Int = 5_000

    /** Tetto di posizioni per transazione delle operazioni su una share intera ([markShareUnavailable]). */
    const val DEFAULT_NAS_CAP: Int = 500

    /** Il tetto di una pagina di candidati del backup (`CandidateQuery.MAX_LIMIT`). */
    const val MAX_CANDIDATE_PAGE: Int = 1_000

    /** Il codice di `BackupProblemCode.LOST_BEFORE_BACKUP` (core-backup, che core-database non vede): la foto e' sparita prima del backup (E-B4). */
    private const val LOST_BEFORE_BACKUP: String = "lost_before_backup"

    private const val MILLIS_PER_SECOND: Long = 1_000L
  }
}
