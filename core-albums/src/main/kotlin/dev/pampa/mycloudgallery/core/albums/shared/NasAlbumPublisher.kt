package dev.pampa.mycloudgallery.core.albums.shared

import dev.pampa.mycloudgallery.core.albums.contract.AlbumClocks
import dev.pampa.mycloudgallery.core.albums.contract.AlbumFamily
import dev.pampa.mycloudgallery.core.albums.contract.AlbumPublisher
import dev.pampa.mycloudgallery.core.albums.contract.AlbumWriteBlock
import dev.pampa.mycloudgallery.core.albums.contract.AlbumWriteGate
import dev.pampa.mycloudgallery.core.albums.contract.FlushReason
import dev.pampa.mycloudgallery.core.albums.contract.PublishOutcome
import dev.pampa.mycloudgallery.core.albums.contract.PublishRules
import dev.pampa.mycloudgallery.core.albums.contract.SharedAlbumChange
import dev.pampa.mycloudgallery.core.albums.contract.SharedState
import dev.pampa.mycloudgallery.core.albums.contract.StageTicket
import dev.pampa.mycloudgallery.core.model.AccountId
import dev.pampa.mycloudgallery.core.model.AlbumId
import dev.pampa.mycloudgallery.core.trust.TrustLimits
import dev.pampa.mycloudgallery.core.trust.Verdict
import dev.pampa.mycloudgallery.core.trust.data.AcceptedAlbumHead
import dev.pampa.mycloudgallery.core.trust.data.AlbumDefinitionState
import dev.pampa.mycloudgallery.core.trust.data.AlbumDocs
import dev.pampa.mycloudgallery.core.trust.data.AlbumEntry
import dev.pampa.mycloudgallery.core.trust.data.AlbumField
import dev.pampa.mycloudgallery.core.trust.data.AlbumHead
import dev.pampa.mycloudgallery.core.trust.data.AlbumIds
import dev.pampa.mycloudgallery.core.trust.data.AlbumKey
import dev.pampa.mycloudgallery.core.trust.data.AlbumLimits
import dev.pampa.mycloudgallery.core.trust.data.AlbumSegmentContext
import dev.pampa.mycloudgallery.core.trust.data.AlbumValue
import dev.pampa.mycloudgallery.core.trust.data.HeadMark
import dev.pampa.mycloudgallery.core.trust.data.LevelChange
import dev.pampa.mycloudgallery.core.trust.data.SegmentRef
import dev.pampa.mycloudgallery.core.trust.nas.NasFileSystem
import dev.pampa.mycloudgallery.core.trust.nas.NasLayout
import dev.pampa.mycloudgallery.core.trust.nas.NasRead
import dev.pampa.mycloudgallery.core.trust.nas.PublishResult
import dev.pampa.mycloudgallery.core.trust.registry.RegistryState
import java.io.IOException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * IL PUBBLICATORE DEL MIO CONTRIBUTO (D6, D30, D38; §2.5 passo 6; `AlbumPublisher` del §1.2). Accoda le modifiche come voci (ora del NAS del
 * momento in cui l'utente le fa, autore = questo dispositivo, account = il mio `nasUser`), le accorpa (30 s) e pubblica al piu' una testa ogni
 * 5 minuti per album, dietro [gate]. **Durevole** ([store]) e **idempotente** per (album, chiave, valore). Ogni pubblicazione riscrive lo stato
 * intero del contributo (segmenti stabili per secchi: di solito cambia un segmento solo).
 *
 * **Scrive solo sotto `albums/<album>/<questo dispositivo>/`, solo con `publishExclusive`** (test di proprieta' `PublisherWritesOnlyHereTest`):
 * prima i segmenti (nome = impronta: una ripetizione trova "esiste gia'" con gli stessi byte), poi la testa al primo numero libero. Non scrive
 * mai una voce che chi legge rifiuterebbe ([AlbumDocs.entryBytesOrNull]): un errore di chi chiama e' un'[IllegalArgumentException] in [stage],
 * mai un contributo rifiutato intero sul NAS. Non controlla i permessi: la tabella la applica chi offre l'azione (`ReplicaSharedAlbums`, P4).
 */
class NasAlbumPublisher(
  private val identity: AlbumIdentity,
  private val family: AlbumFamily,
  private val fileSystem: (String) -> NasFileSystem,
  private val store: AlbumPublishStore,
  private val gate: AlbumWriteGate,
  private val clocks: AlbumClocks,
  /** La storia dei livelli fusa (la replica): un cambio di livello si aggiunge a quella. */
  private val levels: suspend (AlbumId) -> LevelHistory = { LevelHistory.NONE },
  private val io: CoroutineDispatcher = Dispatchers.IO,
  /**
   * R12: il mio contributo come l'ha letto la replica di questo dispositivo (null se non c'e'). Dopo la perdita dello store del pubblicatore
   * vale piu' di cio' che si trova sul NAS, che dal PC si puo' togliere o rovinare.
   */
  private val ownReplica: suspend (AlbumId) -> StoredContribution? = { null },
) : AlbumPublisher, LocalContributions {

  private val mutex = Mutex()
  private val lastStage = HashMap<AlbumId, Long>()
  private val pendingCount = MutableStateFlow(0)

  override val pending: StateFlow<Int> = pendingCount

  /**
   * R12: pubblicazioni non fatte perche' il mio contributo sul NAS non si rilegge per intero (un mio segmento rovinato, nessuna testa valida fra
   * quelle mie) e la replica non ne ha una copia: una testa nuova toglierebbe a tutti le voci gia' pubblicate. Diagnostica: solo il numero.
   */
  @Volatile var adoptionRefused: Int = 0
    private set

  /** R2: i miei segmenti spariti dal NAS (tolti dal PC) e ripubblicati uguali. Diagnostica: solo il numero. */
  @Volatile var segmentsRestored: Int = 0
    private set

  /** L'account delle mie voci (forma minuscola del `nasUser` del registro), o quello della famiglia se il registro non c'e' ancora. */
  private fun myAccount(): AccountId? = identity.registry()?.registry?.device(identity.deviceId)?.nasUser?.let(AccountId::of) ?: family.me()

  override suspend fun stage(album: AlbumId, changes: List<SharedAlbumChange>): StageTicket = mutex.withLock {
    var state = store.load(album) ?: OwnContribution(album)
    val nasNow = clocks.nasNowMs()
    val account = myAccount() ?: throw IllegalStateException("Famiglia non pronta: nessun account per le voci.")
    var last = 0L
    for (change in changes) {
      val (field, value) = when (change) {
        is SharedAlbumChange.Level -> levelValue(album, state, change, nasNow) ?: run {
          // Il livello e' gia' quello: nessuna voce nuova; il numero e' gia' "pubblicato" se lo e' la voce che lo dice.
          last = maxOf(last, levelSeq(state))
          continue
        }
        else -> fieldValue(change)
      }
      // Una storia dei livelli non ha mai un cambio dopo la voce che la porta: la voce prende almeno l'ora dell'ultimo cambio.
      val entryAt = (value as? AlbumValue.Levels)?.history?.lastOrNull()?.at?.let { last -> maxOf(nasNow ?: last, last) } ?: nasNow
      val key = AlbumKey.of(album, field)
      val staged = state.staged[key]
      val published = state.published[key]
      when {
        staged != null && staged.value == value -> last = maxOf(last, staged.seq)
        staged == null && published != null && published.entry.value == value -> last = maxOf(last, published.seq)
        else -> {
          val probe = AlbumEntry(album, field, entryAt ?: PROBE_AT, identity.deviceId, account, value)
          require(AlbumDocs.admits(probe)) { "Modifica non ammessa dal formato (titolo, percorso o tetti)." }
          val seq = state.seq + 1
          state = state.copy(seq = seq, staged = state.staged + (key to StagedEntry(seq, field, value, entryAt)))
          last = seq
        }
      }
    }
    require(liveCount(state) <= AlbumLimits.MAX_ENTRIES_PER_CONTRIBUTION) { "Contributo oltre ${AlbumLimits.MAX_ENTRIES_PER_CONTRIBUTION} voci." }
    store.save(state)
    lastStage[album] = clocks.monotonicMs()
    refreshPending()
    StageTicket(last)
  }

  override suspend fun flush(album: AlbumId, reason: FlushReason): PublishOutcome = mutex.withLock {
    val share = family.commonShare() ?: return@withLock PublishOutcome.Blocked(AlbumWriteBlock.NO_NAS)
    gate.blockFor(share)?.let { return@withLock PublishOutcome.Blocked(it) }
    val registry = identity.registry() ?: return@withLock PublishOutcome.Blocked(AlbumWriteBlock.NOT_TRUSTED)
    if (!registry.canWrite(identity.deviceId)) return@withLock PublishOutcome.Blocked(AlbumWriteBlock.NOT_TRUSTED)
    if (!registry.registry.config.commonShare.equals(share, ignoreCase = true)) return@withLock PublishOutcome.Blocked(AlbumWriteBlock.NO_NAS)
    var state = store.load(album)
    if (state == null || state.staged.isEmpty()) return@withLock PublishOutcome.NothingToPublish
    val now = clocks.monotonicMs()
    if (reason == FlushReason.DEBOUNCE) {
      val since = now - (lastStage[album] ?: Long.MIN_VALUE / 2)
      if (since < PublishRules.DEBOUNCE_MS) return@withLock PublishOutcome.TooSoon(PublishRules.DEBOUNCE_MS - since)
    }
    val nasNow = clocks.nasNowMs() ?: return@withLock PublishOutcome.Failed
    val account = myAccount() ?: return@withLock PublishOutcome.Blocked(AlbumWriteBlock.NOT_TRUSTED)
    val fs = fileSystem(share)
    try {
      // Dopo la perdita dello store locale: il numero, l'ora e le voci della mia ultima testa (R12: dalla replica, se ce l'ha; dal NAS solo se si
      // rilegge tutto, altrimenti niente testa nuova).
      if (state.headVersion == 0L) {
        state = adoptOwnHead(fs, registry, state) ?: run {
          adoptionRefused++
          return@withLock PublishOutcome.Failed
        }
      }
      val lastHead = state.headCreatedAt
      if (lastHead != null && lastHead <= nasNow && nasNow - lastHead < PublishRules.MIN_HEAD_INTERVAL_MS) {
        return@withLock PublishOutcome.TooSoon(PublishRules.MIN_HEAD_INTERVAL_MS - (nasNow - lastHead))
      }
      // Le modifiche da datare prendono l'ora del NAS adesso, e si salva prima di scrivere: una ripresa pubblica gli stessi byte. R7: anche
      // una modifica rimasta in coda oltre l'eta' massima (`PublishRules.MAX_ENTRY_AGE_MS`, il NAS irraggiungibile per ore) prende l'ora di
      // adesso: chi legge scarta come retrodatata una voce nuova piu' vecchia di cosi'.
      val oldest = nasNow - PublishRules.MAX_ENTRY_AGE_MS
      if (state.staged.values.any { it.at == null || it.at < oldest }) {
        state = state.copy(staged = state.staged.mapValues { (_, s) -> if (s.at == null || s.at < oldest) s.copy(at = nasNow) else s })
        store.save(state)
      }
      val entries = contribution(state, account, lastHead)
      val segments = AlbumSegmentPlan.plan(album, entries.values)
      val text = AlbumIds.text(album)
      for (segment in segments) {
        val path = NasLayout.albumSegment(text, identity.deviceId, segment.ref.hash)
        if (segment.ref.hash in state.segments) {
          // R2: un segmento gia' pubblicato che si riusa si controlla (una lettura senza byte): se dal PC l'hanno tolto, si rimette uguale.
          if (io { fs.read(path, 0) } is NasRead.NotFound && io { fs.publishExclusive(path, segment.bytes) } == PublishResult.CREATED) segmentsRestored++
          continue
        }
        if (io { fs.publishExclusive(path, segment.bytes) } == PublishResult.ALREADY_EXISTS) {
          // Il nome e' l'impronta: deve essere lo stesso contenuto (una ripresa), mai un file messo li' da altri.
          val existing = io { fs.read(path, segment.bytes.size) }
          if (existing !is NasRead.Data || !existing.bytes.contentEquals(segment.bytes)) return@withLock PublishOutcome.Failed
        }
      }
      val refs = segments.map { it.ref }.sortedBy { it.hash }
      val createdAt = maxOf(nasNow, lastHead ?: nasNow)
      var after = state.headVersion
      repeat(MAX_ATTEMPTS) {
        val result = io {
          AlbumHeadSeries.publish(fs, registry, identity.signer, album, after) { version ->
            AlbumDocs.encodeHead(AlbumHead(registry.registry.familyId, album, identity.deviceId, version, registry.registry.version, createdAt, refs), identity.signer)
          }
        }
        when (result) {
          is AlbumHeadPublish.Published -> return@withLock done(state, entries, refs, result.version, result.mark, createdAt)
          is AlbumHeadPublish.ExistingOwn ->
            if (result.head.head.segments == refs) {
              return@withLock done(state, entries, refs, result.version, result.head.mark, result.head.head.createdAt)
            } else {
              after = result.version
            }
          is AlbumHeadPublish.Blocked, AlbumHeadPublish.NoRoom -> return@withLock PublishOutcome.Failed
        }
      }
      PublishOutcome.Failed
    } catch (e: IOException) {
      PublishOutcome.Failed
    }
  }

  override suspend fun publishedUpTo(album: AlbumId): StageTicket = StageTicket(store.load(album)?.upTo ?: 0)

  // --- LocalContributions ------------------------------------------------------------------------------------------------------

  override suspend fun albums(): Set<AlbumId> = store.albums()

  override suspend fun local(album: AlbumId): VerifiedContribution? {
    val state = store.load(album) ?: return null
    val account = myAccount() ?: return null
    val entries = contribution(state, account, state.headCreatedAt, clocks.nasNowMs())
    if (entries.isEmpty()) return null
    return VerifiedContribution(album, identity.deviceId, account, state.headVersion + 1, LOCAL_HASH, clocks.nasNowMs() ?: state.headCreatedAt ?: PROBE_AT, entries.values.toList())
  }

  // --- Interni -------------------------------------------------------------------------------------------------------------------

  /**
   * Il contributo intero: le voci pubblicate con sopra quelle in coda. Una voce in coda non e' mai datata prima della mia ultima testa (con un
   * salto indietro dell'ora del NAS chi legge la scarterebbe come retrodatata, §2.4). [undatedAt] data le modifiche ancora senza ora (vista
   * locale); senza, restano fuori.
   */
  private fun contribution(state: OwnContribution, account: AccountId, lastHead: Long?, undatedAt: Long? = null): Map<AlbumKey, AlbumEntry> {
    val out = LinkedHashMap<AlbumKey, AlbumEntry>()
    state.published.forEach { (key, p) -> out[key] = p.entry }
    for ((key, staged) in state.staged) {
      val at = staged.at ?: undatedAt ?: continue
      out[key] = AlbumEntry(state.album, staged.field, maxOf(at, lastHead ?: at), identity.deviceId, account, staged.value)
    }
    return out
  }

  private suspend fun done(
    state: OwnContribution,
    entries: Map<AlbumKey, AlbumEntry>,
    refs: List<SegmentRef>,
    version: Long,
    mark: HeadMark,
    createdAt: Long,
  ): PublishOutcome {
    val seqOf = state.published.mapValues { it.value.seq } + state.staged.mapValues { it.value.seq }
    val upTo = maxOf(state.upTo, state.staged.values.maxOfOrNull { it.seq } ?: 0)
    val published = entries.mapValues { (key, entry) -> PublishedEntry(entry, seqOf.getValue(key)) }
    val count = state.staged.size
    store.save(
      state.copy(
        upTo = upTo, headVersion = version, headMark = mark, headCreatedAt = createdAt, segments = refs.map { it.hash }.toSet(),
        published = published, staged = emptyMap(),
      ),
    )
    refreshPending()
    return PublishOutcome.Published(version, StageTicket(upTo), count)
  }

  /**
   * La ripresa dopo la perdita dello store locale (R12 della revisione di sicurezza della Fase 7): numero, ora e voci gia' pubblicate, cosi'
   * la testa nuova le ripubblica invece di farle sparire per tutti. Prima la copia che la **replica** di questo dispositivo ha letto (se e'
   * almeno della versione dell'ultima mia testa sul NAS: dal PC le teste piu' recenti si possono togliere, e ripubblicare uno stato vecchio
   * con un numero gia' visto sarebbe un'equivocazione); altrimenti la mia ultima testa sul NAS, riletta con le regole di chi legge, **solo se
   * si rilegge tutta**. Null (niente testa nuova: `Failed`) se il NAS ha teste mie ma nessuna valida, o un mio segmento rovinato. Senza
   * nessuna testa mia si riparte da zero. Le modifiche in coda restano sopra.
   */
  private suspend fun adoptOwnHead(fs: NasFileSystem, registry: RegistryState, state: OwnContribution): OwnContribution? {
    val read = io { AlbumHeadSeries.readLatest(fs, registry, state.album, identity.deviceId, null, AlbumReadBudget(TrustLimits.MAX_HEAD_READS_PER_SHARE)) }
    val accepted = (read as? AlbumHeadRead.Accepted)?.head
    val own = ownReplica(state.album)?.takeIf { it.device == identity.deviceId && !it.barred }
    if (own != null && (accepted == null || own.mark.version >= accepted.head.version)) return fromReplica(state, own, accepted)
    if (accepted == null) return if (read == AlbumHeadRead.None) state else null
    val context = AlbumSegmentContext(state.album, identity.deviceId, accepted.account)
    val published = LinkedHashMap<AlbumKey, PublishedEntry>()
    for (ref in accepted.head.segments) {
      val bytes = (io { fs.read(NasLayout.albumSegment(AlbumIds.text(state.album), identity.deviceId, ref.hash), ref.size.toInt()) } as? NasRead.Data)?.bytes
        ?: throw IOException("Segmento proprio non leggibile: si riprova.")
      // R12: un mio segmento che non si rilegge (rovinato dal PC): una testa senza le sue voci le toglierebbe a tutti. Niente testa.
      val entries = (AlbumDocs.decodeSegment(bytes, ref, context) as? Verdict.Accepted)?.value ?: return null
      entries.forEach { published[it.key] = PublishedEntry(it, 0) }
    }
    return state.copy(
      headVersion = accepted.head.version,
      headMark = accepted.mark,
      headCreatedAt = accepted.head.createdAt,
      segments = accepted.head.segments.map { it.hash }.toSet(),
      published = published,
    )
  }

  /** R12: il mio contributo dalla copia della replica (le voci tutte, anche quelle che un lettore ha scartato: sono mie), dopo la versione piu' alta. */
  private fun fromReplica(state: OwnContribution, own: StoredContribution, onNas: AcceptedAlbumHead?): OwnContribution {
    val published = LinkedHashMap<AlbumKey, PublishedEntry>()
    own.entries.forEach { published[it.entry.key] = PublishedEntry(it.entry, 0) }
    val nasVersion = onNas?.head?.version ?: 0L
    return state.copy(
      headVersion = maxOf(own.mark.version, nasVersion),
      headMark = own.mark,
      headCreatedAt = maxOf(own.createdAt, onNas?.head?.createdAt ?: own.createdAt),
      segments = own.segments.map { it.hash }.toSet(),
      published = published,
    )
  }

  /**
   * R2: rimette sul NAS i segmenti della mia testa che non ci sono piu' (tolti dal PC), uguali a prima: chi legge, che li aspetta, li ritrova
   * senza una testa nuova. Dietro gli stessi cancelli della pubblicazione; solo se le voci pubblicate rifanno esattamente i segmenti della testa.
   * Restituisce quanti segmenti ha rimesso.
   */
  suspend fun repairSegments(album: AlbumId): Int = mutex.withLock {
    val share = family.commonShare() ?: return@withLock 0
    if (gate.blockFor(share) != null) return@withLock 0
    val registry = identity.registry() ?: return@withLock 0
    if (!registry.canWrite(identity.deviceId) || !registry.registry.config.commonShare.equals(share, ignoreCase = true)) return@withLock 0
    val state = store.load(album) ?: return@withLock 0
    if (state.headVersion == 0L || state.published.isEmpty()) return@withLock 0
    val segments = AlbumSegmentPlan.plan(album, state.published.values.map { it.entry })
    if (segments.map { it.ref.hash }.toSet() != state.segments) return@withLock 0
    val fs = fileSystem(share)
    val text = AlbumIds.text(album)
    var restored = 0
    try {
      for (segment in segments) {
        val path = NasLayout.albumSegment(text, identity.deviceId, segment.ref.hash)
        if (io { fs.read(path, 0) } is NasRead.NotFound && io { fs.publishExclusive(path, segment.bytes) } == PublishResult.CREATED) restored++
      }
    } catch (e: IOException) {
      // Il NAS non risponde: si riprova al giro dopo.
    }
    segmentsRestored += restored
    restored
  }

  private fun fieldValue(change: SharedAlbumChange): Pair<AlbumField, AlbumValue?> = when (change) {
    is SharedAlbumChange.Definition -> AlbumField.Definition to AlbumValue.Definition(
      change.title, change.folder, stateOf(change.state), change.creator, change.nonceHex, change.afterClose?.code,
    )
    is SharedAlbumChange.Cover -> AlbumField.Cover to change.media?.let { AlbumValue.Cover(it, requireNotNull(change.contentKey) { "Copertina senza chiave." }) }
    is SharedAlbumChange.Sort -> AlbumField.Sort to AlbumValue.Sort(change.sort)
    is SharedAlbumChange.Item -> AlbumField.Item(change.media) to AlbumValue.Item(change.contentKey, change.path, change.position, change.addedBy, change.version, change.sha256)
    is SharedAlbumChange.ItemRemoved -> AlbumField.Item(change.media) to null
    is SharedAlbumChange.Heir -> AlbumField.Heir to AlbumValue.Heir(change.account, change.registryVersion)
    is SharedAlbumChange.LevelHistory -> AlbumField.Level to AlbumValue.Levels(change.steps.map { LevelChange(it.atNas, it.level) })
    is SharedAlbumChange.Level -> error("Il livello ha la sua strada.")
  }

  /**
   * La voce `level` di un cambio a [SharedAlbumChange.Level.level]: la storia piu' recente fra quella fusa e la mia (in coda o pubblicata),
   * con il cambio aggiunto adesso. Null se il livello e' gia' quello. La storia ha al piu' 32 cambi: oltre, nessun cambio (si direbbe il
   * falso sul passato togliendone uno).
   */
  private suspend fun levelValue(album: AlbumId, state: OwnContribution, change: SharedAlbumChange.Level, nasNow: Long?): Pair<AlbumField, AlbumValue>? {
    val key = AlbumKey.of(album, AlbumField.Level)
    val mine = listOfNotNull(
      (state.staged[key]?.value as? AlbumValue.Levels)?.history,
      (state.published[key]?.entry?.value as? AlbumValue.Levels)?.history,
    )
    val base = (mine + listOf(levels(album).changes)).maxByOrNull { it.lastOrNull()?.at ?: Long.MIN_VALUE }.orEmpty()
    if (base.lastOrNull()?.level == change.level) return null
    val now = nasNow ?: throw IllegalStateException("Senza l'ora del NAS un cambio di livello non si data.")
    check(base.size < AlbumLimits.MAX_LEVEL_CHANGES) { "Storia dei livelli piena (${AlbumLimits.MAX_LEVEL_CHANGES} cambi)." }
    val at = maxOf(now, (base.lastOrNull()?.at ?: Long.MIN_VALUE) + 1)
    return AlbumField.Level to AlbumValue.Levels(base + LevelChange(at, change.level))
  }

  private fun levelSeq(state: OwnContribution): Long {
    val key = AlbumKey.of(state.album, AlbumField.Level)
    return state.staged[key]?.seq ?: state.published[key]?.seq ?: 0L
  }

  private fun liveCount(state: OwnContribution): Int = (state.published.keys + state.staged.keys).size

  private suspend fun refreshPending() {
    pendingCount.value = store.albums().sumOf { store.load(it)?.staged?.size ?: 0 }
  }

  private suspend fun <T> io(block: () -> T): T = withContext(io) { block() }

  private fun stateOf(state: SharedState): AlbumDefinitionState = when (state) {
    SharedState.ACTIVE -> AlbumDefinitionState.ACTIVE
    SharedState.CLOSED -> AlbumDefinitionState.CLOSED
    SharedState.DELETED -> AlbumDefinitionState.DELETED
  }

  private companion object {
    const val MAX_ATTEMPTS = 3
    const val LOCAL_HASH = "local"

    /** Un'ora plausibile per provare una voce prima di conoscere quella del NAS (solo la forma, mai pubblicata). */
    const val PROBE_AT: Long = 1_800_000_000_000L
  }
}
