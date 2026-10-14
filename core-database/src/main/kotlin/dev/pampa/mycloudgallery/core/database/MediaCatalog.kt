package dev.pampa.mycloudgallery.core.database

import androidx.room.withTransaction
import dev.pampa.mycloudgallery.core.database.dao.TimelineIndexDao
import dev.pampa.mycloudgallery.core.database.entity.EmbeddingEntity
import dev.pampa.mycloudgallery.core.database.entity.IndexRow
import dev.pampa.mycloudgallery.core.database.entity.MediaAliasEntity
import dev.pampa.mycloudgallery.core.database.entity.MediaEntity
import dev.pampa.mycloudgallery.core.database.entity.MediaGroupEntity
import dev.pampa.mycloudgallery.core.database.entity.MediaLocationEntity
import dev.pampa.mycloudgallery.core.database.entity.MediaPersonalEntity
import dev.pampa.mycloudgallery.core.database.entity.NasRefRow
import dev.pampa.mycloudgallery.core.model.AccountId
import dev.pampa.mycloudgallery.core.model.CellFlags
import dev.pampa.mycloudgallery.core.model.ContentKey
import dev.pampa.mycloudgallery.core.model.CorrectionField
import dev.pampa.mycloudgallery.core.model.EmbeddingVector
import dev.pampa.mycloudgallery.core.model.FileStamp
import dev.pampa.mycloudgallery.core.model.Fingerprint
import dev.pampa.mycloudgallery.core.model.FingerprintMatch
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
import dev.pampa.mycloudgallery.core.model.MediaVersion
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
import kotlinx.coroutines.channels.BufferOverflow
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

  /** Registra cio' che una scansione ha visto in un posto (piano 3.1, 4.2). */
  suspend fun record(observation: Observation, whenUndecided: WhenUndecided = WhenUndecided.WAIT): RecordOutcome =
    write { tx -> recordOne(tx, observation, whenUndecided, seenAt = null) }

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
   */
  suspend fun recordAll(
    observations: List<Observation>,
    whenUndecided: WhenUndecided = WhenUndecided.WAIT,
    cursor: MediaStoreCursor? = null,
    seenAt: Long? = null,
    nasCursor: NasScanState? = null,
    inSameTransaction: (suspend () -> Unit)? = null,
  ): List<RecordOutcome> = write { tx ->
    val outcomes = observations.map { recordOne(tx, it, whenUndecided, seenAt) }
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
  suspend fun knownNasFiles(account: AccountId, share: String, folder: NasPath?): Map<LocationKey, KnownNasLocation> =
    locationDao.knownInFolder(Location.Nas.folderKey(account, share, folder)).associate { entity ->
      LocationKey(entity.locationKey) to KnownNasLocation(
        locationId = LocationId(entity.locationId),
        stamp = NasStamp(entity.size, entity.modifiedAt, entity.fileId),
        state = enumByCode(entity.state),
      )
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
        val row = rows.minWith(compareBy<NasRefRow>({ if (it.isCurrent) 0 else 1 }, { it.locationId }))
        MediaRef.Nas(row.locationId, ContentKey.of(QuickHash(row.quickHash), row.size).short, row.mimeType)
      }

  /** La posizione del NAS di un [MediaRef.Nas] (per aprire l'originale), se c'e' ancora. */
  suspend fun nasLocation(locationId: LocationId): Location.Nas? = locationDao.get(locationId.value)?.toDomain()?.location as? Location.Nas

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
  ): LocationId = write { tx ->
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
    LocationId(id)
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
   * I byte in [locationId] sono stati riletti e hanno dato [readBack]. Vero se coincidono con l'hash
   * completo della versione (copia verificata); falso se diversi (copia non integra, E-B8) o se
   * l'hash della versione non e' noto. In caso di falso la verifica precedente decade.
   */
  suspend fun markVerified(locationId: LocationId, readBack: Sha256, atMillis: Long): Boolean = write { tx ->
    val before = requireLocation(locationId)
    val expected = versionDao.get(before.versionId.value)?.fullHash
    val ok = expected != null && expected == readBack.hex
    locationDao.update(before.copy(verifiedAtMillis = if (ok) atMillis else null).toEntity())
    tx.touch(mediaOf(locationId))
    ok
  }

  /**
   * I metadati letti (fase META) o corretti di una versione. [kind], se c'e', e' il tipo definitivo
   * deciso da META (motion photo, RAW, animata: `docs/fase3-progetto.md` §2.2): vale solo se [versionId]
   * e' ancora la versione corrente (una lettura arrivata dopo una modifica non cambia il tipo).
   */
  suspend fun updateMetadata(versionId: VersionId, metadata: MediaMetadata, kind: MediaKind? = null): Unit = write { tx ->
    versionDao.upsertMetadata(metadata.toEntity(versionId, clock()))
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
  suspend fun group(kind: MediaGroupKind, members: Collection<MediaId>, cover: MediaId): MediaGroupId = write { tx ->
    groupInTransaction(tx, kind, members, cover)
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

  private suspend fun recordOne(tx: Tx, observation: Observation, whenUndecided: WhenUndecided, seenAt: Long?): RecordOutcome {
    val decided = LinkingRules.decide(observation, loadContext(observation))
    val decision = if (whenUndecided == WhenUndecided.KEEP_SEPARATE) keepSeparate(decided) else decided
    val target = apply(tx, decision, observation, seenAt ?: clock())
    if (whenUndecided == WhenUndecided.KEEP_SEPARATE && target != null) {
      undecidedCandidates(decided)?.let { groupProbableDuplicates(tx, target.first, observation, it) }
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

  private suspend fun loadContext(observation: Observation): LinkContext {
    val key = observation.location.key.value
    val fingerprint = observation.fingerprint
    val atSameKey = locationDao.byKey(key)?.let { known(it) }
    val atSameMediaStoreId = (observation.location as? Location.Phone)?.let { phone ->
      phone.mediaStoreId?.let { id ->
        locationDao.byMediaStoreId(phone.volume, id).firstOrNull { it.locationKey != key }?.let { known(it) }
      }
    }
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

    // 4. Album, copertine, gruppi.
    mergeDao.copyAlbumItems(loser, survivor)
    mergeDao.repointAlbumCovers(loser, survivor)
    mergeDao.repointGroupCovers(loser, survivor)

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
    // Il gruppo del perdente perde un membro: la sua copertina si ricalcola.
    loserRow.groupId?.let { tx.dirtyGroups += it }
    tx.touch(plan.survivor)
    tx.touch(plan.loser)
    tx.markChanged(plan.survivor, structural = true)
    tx.markChanged(plan.loser, structural = true)
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

  /** Scrive i flag personali di [id] se cambiano, con l'istante del cambio di ciascuno. */
  private suspend fun writePersonal(tx: Tx, id: MediaId, flags: PersonalFlags) {
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
    if (flags.hidden != before.hidden) tx.markChanged(id, structural = true)
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
  ) {
    val visible: Boolean get() = presence != Presence.NONE && !hidden
  }

  /**
   * Ricalcola i campi derivati dei media toccati dalla transazione (e dei loro gruppi) con le regole di
   * dominio, li scrive se sono cambiati e annota che cosa cambia per l'indice.
   */
  private suspend fun refreshDerived(tx: Tx) {
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
      val cover = TimelineRules.groupCover(
        members.map { GroupMember(MediaId(it.row.mediaId), it.sortTime, it.visible) },
        mediaDao.group(group)?.coverMediaId?.let(::MediaId),
      )
      members.forEach { it.cellFlags = TimelineRules.withGroupRole(it.cellFlags, inGroup = true, isCover = it.row.mediaId == cover?.value) }
    }
    derived.values.filter { it.row.groupId == null }.forEach {
      it.cellFlags = TimelineRules.withGroupRole(it.cellFlags, inGroup = false, isCover = false)
    }

    derived.values.forEach { store(tx, it) }
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
    return Derived(
      row = row,
      owner = owner,
      presence = SafetyRules.presence(media),
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
      ) or TimelineRules.presenceFlags(media, safety, probableDuplicate),
      thumbHash = metadataRow?.thumbHash,
      hidden = personal?.hidden == true,
    )
  }

  /** Scrive i derivati se sono cambiati e annota il cambiamento per l'indice. */
  private suspend fun store(tx: Tx, d: Derived) {
    val row = d.row
    val id = MediaId(row.mediaId)
    val now = clock()
    if (d.owner?.value != row.ownerAccount) mediaDao.setOwner(row.mediaId, d.owner?.value, now)
    val same = row.presence == d.presence.code && row.safety == d.safety.code && row.sortTime == d.sortTime &&
      row.localDay == d.localDay && row.aspectMilli == d.aspectMilli && row.durationMs == d.durationMs &&
      row.placeId == d.placeId && row.cellFlags == d.cellFlags && row.thumbHash.contentEquals(d.thumbHash)
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
      now = now,
    )
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
      "index_head",
      "index_segment",
      "replica_pack",
      "replica_blob",
      "index_sync_state",
      "nas_op",
      "nas_op_item",
      "nas_trash_entry",
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

    /** Tetto di posizioni per transazione delle operazioni su una share intera ([markShareUnavailable]). */
    const val DEFAULT_NAS_CAP: Int = 500
  }
}
