package dev.pampa.mycloudgallery.core.albums.shared

import dev.pampa.mycloudgallery.core.albums.contract.Album
import dev.pampa.mycloudgallery.core.albums.contract.AlbumClocks
import dev.pampa.mycloudgallery.core.albums.contract.AlbumFamily
import dev.pampa.mycloudgallery.core.albums.contract.AlbumOp
import dev.pampa.mycloudgallery.core.albums.contract.AlbumPublisher
import dev.pampa.mycloudgallery.core.albums.contract.AlbumTitles
import dev.pampa.mycloudgallery.core.albums.contract.AlbumWriteBlock
import dev.pampa.mycloudgallery.core.albums.contract.AlbumWriteGate
import dev.pampa.mycloudgallery.core.albums.contract.CloseMode
import dev.pampa.mycloudgallery.core.albums.contract.CloseOutcome
import dev.pampa.mycloudgallery.core.albums.contract.ClosePreview
import dev.pampa.mycloudgallery.core.albums.contract.ClosePreviewOutcome
import dev.pampa.mycloudgallery.core.albums.contract.CoverRef
import dev.pampa.mycloudgallery.core.albums.contract.EditOutcome
import dev.pampa.mycloudgallery.core.albums.contract.HiddenChoice
import dev.pampa.mycloudgallery.core.albums.contract.InheritOutcome
import dev.pampa.mycloudgallery.core.albums.contract.LevelStep
import dev.pampa.mycloudgallery.core.albums.contract.OthersAfterClose
import dev.pampa.mycloudgallery.core.albums.contract.ShareFolder
import dev.pampa.mycloudgallery.core.albums.contract.SharePlanOutcome
import dev.pampa.mycloudgallery.core.albums.contract.ShareRequest
import dev.pampa.mycloudgallery.core.albums.contract.ShareRunner
import dev.pampa.mycloudgallery.core.albums.contract.SharedAlbumChange
import dev.pampa.mycloudgallery.core.albums.contract.SharedAlbumCopies
import dev.pampa.mycloudgallery.core.albums.contract.SharedAlbumFacts
import dev.pampa.mycloudgallery.core.albums.contract.SharedAlbumLimits
import dev.pampa.mycloudgallery.core.albums.contract.SharedAlbumNotice
import dev.pampa.mycloudgallery.core.albums.contract.SharedAlbums
import dev.pampa.mycloudgallery.core.albums.contract.SharedState
import dev.pampa.mycloudgallery.core.albums.noop.NoShareRunner
import dev.pampa.mycloudgallery.core.library.contract.VisibleMedia
import dev.pampa.mycloudgallery.core.model.AccountId
import dev.pampa.mycloudgallery.core.model.AlbumId
import dev.pampa.mycloudgallery.core.model.AlbumKind
import dev.pampa.mycloudgallery.core.model.AlbumSort
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.ShareLevel
import dev.pampa.mycloudgallery.core.trust.data.AlbumField
import dev.pampa.mycloudgallery.core.trust.data.AlbumKey
import dev.pampa.mycloudgallery.core.trust.registry.DeviceStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * La copia personale di un album chiuso (Q5 b; per il creatore l'album dopo "Smetti di condividere"): P3/P4 la scrivono nei dati personali.
 * [cause] (R1): il dispositivo che ha firmato la chiusura; se viene revocato, la copia si ritira.
 */
data class SharedAlbumCopy(
  val copy: AlbumId,
  val shared: AlbumId,
  val title: String,
  val creator: AccountId,
  val mine: Boolean,
  val items: List<MediaId>,
  val cause: String? = null,
) {
  override fun toString(): String = "SharedAlbumCopy(${items.size}, mine=$mine)"
}

/**
 * GLI ALBUM CONDIVISI VISTI DA QUESTO DISPOSITIVO (`SharedAlbums` e `SharedAlbumFacts` di P0, P2): la proiezione ([SharedAlbumReplica]) per le
 * schermate (solo album attivi e non lasciati; elementi attestati e **non nascosti**, D9, D22) e le modifiche che non spostano file, ognuna
 * dietro la **stessa** tabella che usa chi legge ([SharedAlbumRules.allows], C25) e dietro il cancello delle scritture (D30, D38). Le
 * modifiche si accodano al pubblicatore e si vedono subito qui (vista locale); agli altri arrivano con la testa. La creazione e l'aggiunta
 * passano da [runner] (piano e conferma, P4).
 */
class ReplicaSharedAlbums(
  private val identity: AlbumIdentity,
  private val family: AlbumFamily,
  private val replica: SharedAlbumReplica,
  private val publisher: AlbumPublisher,
  private val gate: AlbumWriteGate,
  private val clocks: AlbumClocks,
  private val visibility: VisibleMedia,
  private val choices: SharedAlbumChoices,
  @Volatile var runner: ShareRunner = NoShareRunner,
  /** R1: i tetti delle copie personali di Q5 (b), per creatore. */
  private val copyLimits: ClosedCopyLimits = ClosedCopyLimits(),
) : SharedAlbums, SharedAlbumFacts {

  /** R1: gli album la cui copia personale non nasce perche' oltre i tetti per creatore (Diagnostica: [copiesCapped], solo il numero). */
  private val cappedAlbums: MutableSet<AlbumId> = java.util.concurrent.ConcurrentHashMap.newKeySet()

  val copiesCapped: Int get() = cappedAlbums.size

  override val visible: MutableStateFlow<List<Album.Shared>> = MutableStateFlow(emptyList())
  override val notices: MutableStateFlow<List<SharedAlbumNotice>> = MutableStateFlow(emptyList())
  override val left: MutableStateFlow<List<Album.Shared>> = MutableStateFlow(emptyList())

  init {
    replica.addListener { refresh() }
  }

  private fun me(): AccountId? = identity.registry()?.registry?.device(identity.deviceId)?.nasUser?.let(AccountId::of) ?: family.me()

  private fun meIsAdmin(): Boolean = identity.registry()?.registry?.device(identity.deviceId)?.let { it.admin && it.status == DeviceStatus.APPROVED } ?: family.amAdmin()

  private fun creatorLeft(album: MergedAlbum): Boolean = identity.registry()?.let { RegistryAlbumView(it).accountLeft(album.creator) } ?: family.leftFamily(album.creator)

  private fun active(id: AlbumId): SharedAlbumView? = replica.view(id)?.takeIf { it.merged.state == SharedState.ACTIVE }

  /** L'elemento pubblicato che corrisponde a [media] (l'id di chi l'ha pubblicato o il media del mio catalogo che lo attesta). */
  private fun published(view: SharedAlbumView, media: MediaId): MediaId? =
    media.takeIf { it in view.merged.items } ?: view.attested.entries.firstOrNull { it.value == media }?.key

  /** R1: i segni locali, o nessuno se non si leggono (allora niente avvisi di chiusura e niente copie: non so se l'album era stato visto). */
  private suspend fun marks(): Map<AlbumId, AlbumMarks> = try {
    replica.marks.all()
  } catch (e: CancellationException) {
    throw e
  } catch (e: Exception) {
    emptyMap()
  }

  /** Ricalcola elenchi e avvisi dalla proiezione (dopo ogni giro, modifica o cambiamento del catalogo). */
  suspend fun refresh() {
    val me = me()
    val leftIds = choices.left()
    val dismissed = choices.dismissed()
    val known = marks()
    val promised = if (me == null) emptySet() else copyPlan(me, leftIds, known).map { it.shared }.toSet()
    val shown = ArrayList<Album.Shared>()
    val leftList = ArrayList<Album.Shared>()
    val out = ArrayList<SharedAlbumNotice>()
    for (view in replica.views.value.values.sortedBy { it.merged.album.value }) {
      val m = view.merged
      val mine = me != null && (m.creator == me || m.heir == me)
      if (m.state == SharedState.ACTIVE) {
        val album = toAlbum(view, mine)
        if (m.album in leftIds) leftList += album else shown += album
        if (me == null) continue
        // E-SH13: mie aggiunte scartate perche' il livello ora non le permette.
        val notAdded = m.denied.count { it.entry.account == me && it.entry.value != null && it.op == AlbumOp.ADD_MEDIA && it.reason == DenyReason.NOT_PERMITTED }
        if (notAdded > 0 && "notadded:${m.album.value}:$notAdded" !in dismissed) out += SharedAlbumNotice.NotAddedLevelChanged(m.album, m.title, notAdded)
        if (m.heir == me && m.creator != me && "inherited:${m.album.value}" !in dismissed) out += SharedAlbumNotice.Inherited(m.album, m.title)
      } else if (!mine && me != null && m.album !in leftIds && known[m.album]?.seenActive == true && "closed:${m.album.value}" !in dismissed) {
        // R1: l'avviso solo per un album che questo dispositivo ha visto attivo; la copia solo se c'e' (o nascera': entro i tetti).
        val mode = if (m.state == SharedState.CLOSED) CloseMode.STOP else CloseMode.DELETE
        val made = known[m.album]?.copy?.takeIf { !it.withdrawn }
        val copy = if (m.afterClose == OthersAfterClose.KEEP_COPY && (made != null || m.album in promised)) SharedAlbumCopies.copyIdFor(m.album, me) else null
        out += SharedAlbumNotice.Closed(m.album, m.title, mode, copy)
      }
    }
    visible.value = shown
    left.value = leftList
    notices.value = out
  }

  /** Gli elementi mostrabili da me di [view], nell'ordine dell'album: attestati (D9), non nascosti (D22). */
  private suspend fun shownItems(view: SharedAlbumView): List<MediaId> {
    val ordered = view.merged.items.values.sortedWith(compareBy({ it.position }, { it.media.value })).mapNotNull { view.attested[it.media] }.distinct()
    if (ordered.isEmpty()) return ordered
    val ok = visibility.visible(ordered)
    return ordered.filter { it in ok }
  }

  private suspend fun toAlbum(view: SharedAlbumView, mine: Boolean): Album.Shared {
    val m = view.merged
    val items = shownItems(view)
    val chosen = m.cover?.let { view.attested[it.media] }?.takeIf { it in items }
    val cover = when {
      chosen != null -> CoverRef.Chosen(chosen)
      items.isNotEmpty() -> CoverRef.Auto(items.last())
      else -> CoverRef.None
    }
    return Album.Shared(
      id = m.album, title = m.title, kind = AlbumKind.MANUAL, cover = cover, count = items.size, sort = m.sort ?: AlbumSort.defaultFor(AlbumKind.MANUAL),
      updatedAtNas = m.updatedAt, newestDay = null, level = m.levels.current, creator = m.creator, heir = m.heir, mine = mine, state = m.state,
    )
  }

  /**
   * Le copie personali che questo account deve ancora scrivere (Q5 b; il creatore dopo "Smetti di condividere"), con gli elementi che il mio
   * catalogo attesta. R1 della revisione di sicurezza della Fase 7: solo per un album che **questo dispositivo ha visto attivo** (un album nato
   * chiuso, o mai visto, non fa nascere niente), una volta sola (gli elementi si fissano alla prima scrittura: [copyKept]), entro i tetti per
   * creatore ([ClosedCopyLimits]). Chi le scrive chiama [copyKept] dopo ognuna.
   */
  suspend fun keptCopies(): List<SharedAlbumCopy> {
    val me = me() ?: return emptyList()
    return copyPlan(me, choices.left(), marks())
  }

  /**
   * R1: le copie da scrivere adesso, in un ordine fisso (prima le chiusure piu' vecchie), con i tetti per creatore che contano anche le copie gia'
   * scritte e non ritirate. Una copia oltre i tetti non nasce (e l'avviso non la promette).
   */
  private suspend fun copyPlan(me: AccountId, leftIds: Set<AlbumId>, known: Map<AlbumId, AlbumMarks>): List<SharedAlbumCopy> {
    val copies = HashMap<AccountId, Int>()
    val items = HashMap<AccountId, Int>()
    for (mark in known.values) {
      val made = mark.copy?.takeIf { !it.withdrawn } ?: continue
      copies[made.creator] = (copies[made.creator] ?: 0) + 1
      items[made.creator] = (items[made.creator] ?: 0) + made.items
    }
    val out = ArrayList<SharedAlbumCopy>()
    val closed = replica.views.value.values.filter { it.merged.state != SharedState.ACTIVE }
      .sortedWith(compareBy<SharedAlbumView>({ it.merged.closedAt ?: Long.MAX_VALUE }, { it.merged.album.value }))
    for (view in closed) {
      val m = view.merged
      // Revisione E (SC-1 e R1): la copia di un album chiuso nasce solo se questo dispositivo l'ha visto attivo (un album nato chiuso, o mai visto, non fa
      // scrivere niente a nessuno), **tranne per il creatore**: l'id dell'album e' legato all'account che l'ha creato (nessun dispositivo di un altro account
      // puo' spacciarlo per mio), quindi un album mio non e' mai "l'album di un dispositivo ostile", e "Smetti di condividere" fatto da me deve lasciarmi
      // l'album personale anche se il segno "visto attivo" manca (un segno che non si e' potuto scrivere o leggere). I tetti per creatore valgono lo stesso.
      val byMe = m.creator == me
      val mark = known[m.album] ?: if (byMe) AlbumMarks() else continue
      if ((!mark.seenActive && !byMe) || mark.copy != null) continue
      val mine = m.creator == me || m.heir == me
      val keeps = when (m.state) {
        SharedState.ACTIVE -> false
        SharedState.CLOSED -> mine || (m.afterClose == OthersAfterClose.KEEP_COPY && m.album !in leftIds)
        SharedState.DELETED -> !mine && m.afterClose == OthersAfterClose.KEEP_COPY && m.album !in leftIds
      }
      if (!keeps) continue
      val shown = shownItems(view)
      val usedCopies = copies[m.creator] ?: 0
      val usedItems = items[m.creator] ?: 0
      if (usedCopies + 1 > copyLimits.maxCopiesPerCreator || usedItems + shown.size > copyLimits.maxItemsPerCreator) {
        cappedAlbums += m.album
        continue
      }
      copies[m.creator] = usedCopies + 1
      items[m.creator] = usedItems + shown.size
      val cause = mark.closure?.author ?: m.winners[AlbumKey.of(m.album, AlbumField.Definition)]?.author
      out += SharedAlbumCopy(SharedAlbumCopies.copyIdFor(m.album, me), m.album, m.title, m.creator, mine, shown, cause)
    }
    return out
  }

  /** R1: la copia [copy] e' scritta (o l'utente l'aveva gia' eliminata): non si riscrive piu', e i suoi elementi restano quelli di adesso. */
  suspend fun copyKept(copy: SharedAlbumCopy) {
    replica.marks.update { all ->
      val mark = all[copy.shared] ?: AlbumMarks()
      if (mark.copy == null) all[copy.shared] = mark.copy(copy = CopyMark(copy.copy, copy.creator, copy.items.size, copy.cause))
    }
  }

  /**
   * R1: le copie da ritirare, perche' il dispositivo che ha firmato la chiusura che le ha fatte nascere adesso e' revocato o perso ("la revoca
   * toglie cio' che il dispositivo revocato ha causato"), **tranne la copia di un mio album**. Chi le ritira chiama [copyWithdrawn] dopo ognuna.
   */
  suspend fun copiesToWithdraw(): List<SharedAlbumCopy> {
    val registry = identity.registry() ?: return emptyList()
    val me = me()
    return marks().mapNotNull { (album, mark) ->
      val made = mark.copy?.takeIf { !it.withdrawn } ?: return@mapNotNull null
      // Revisione E (SC-1): la copia di un album che ho creato io e' mia, non una copia nata dalla chiusura di un altro: dopo il mio "Smetti di condividere"
      // il telefono che ha chiuso puo' essere dato per perso (si cambia telefono) e il mio album personale non deve sparire con lui.
      if (made.creator == me) return@mapNotNull null
      val cause = made.cause ?: return@mapNotNull null
      if (SharedAlbumReplica.authorTrusted(registry, cause) || registry.registry.device(cause) == null) return@mapNotNull null
      SharedAlbumCopy(made.copy, album, "", made.creator, mine = false, items = emptyList(), cause = cause)
    }
  }

  /** R1: la copia dell'album [shared] e' ritirata. */
  suspend fun copyWithdrawn(shared: AlbumId) {
    replica.marks.update { all ->
      val mark = all[shared] ?: return@update
      mark.copy?.let { all[shared] = mark.copy(copy = it.copy(withdrawn = true)) }
    }
  }

  /** Gli elementi mostrabili di un album attivo, nell'ordine dell'album. */
  suspend fun visibleItems(id: AlbumId): List<MediaId> = active(id)?.let { shownItems(it) }.orEmpty()

  // --- Scritture -----------------------------------------------------------------------------------------------------------------

  /** Il motivo per cui non si scrive adesso (cancello D30/D38, ora del NAS), o null. */
  private suspend fun blocked(): AlbumWriteBlock? {
    val share = family.commonShare() ?: return AlbumWriteBlock.NO_NAS
    gate.blockFor(share)?.let { return it }
    if (identity.registry()?.canWrite(identity.deviceId) != true) return AlbumWriteBlock.NOT_TRUSTED
    return if (clocks.nasNowMs() == null) AlbumWriteBlock.NO_NAS else null
  }

  private suspend fun write(id: AlbumId, changes: List<SharedAlbumChange>) {
    publisher.stage(id, changes)
    replica.recompute(listOf(id))
  }

  override suspend fun create(title: String, level: ShareLevel, media: List<MediaId>, hidden: HiddenChoice): SharePlanOutcome =
    runner.plan(ShareRequest.NewAlbum(title, level, media, hidden))

  override suspend fun rename(id: AlbumId, title: String): EditOutcome {
    val m = active(id)?.merged ?: return EditOutcome.NotFound
    AlbumTitles.problem(title)?.let { return EditOutcome.Invalid(it) }
    if (!allowed(id, AlbumOp.RENAME)) return EditOutcome.NotAllowed(AlbumOp.RENAME)
    blocked()?.let { return EditOutcome.Blocked(it) }
    write(id, listOf(SharedAlbumChange.Definition(AlbumTitles.normalized(title), m.folder, SharedState.ACTIVE, m.creator, m.nonceHex, null)))
    return EditOutcome.Done
  }

  override suspend fun remove(id: AlbumId, media: List<MediaId>): EditOutcome {
    val view = active(id) ?: return EditOutcome.NotFound
    // Tutto o niente: un elemento che la tabella non mi permette di togliere ferma la richiesta intera.
    if (media.any { !allowed(id, AlbumOp.REMOVE_ITEM, it) }) return EditOutcome.NotAllowed(AlbumOp.REMOVE_ITEM)
    blocked()?.let { return EditOutcome.Blocked(it) }
    val removed = media.mapNotNull { published(view, it) }.distinct().map { SharedAlbumChange.ItemRemoved(it) }
    if (removed.isNotEmpty()) write(id, removed)
    return EditOutcome.Done
  }

  override suspend fun setCover(id: AlbumId, media: MediaId?): EditOutcome {
    val view = active(id) ?: return EditOutcome.NotFound
    if (!allowed(id, AlbumOp.SET_COVER)) return EditOutcome.NotAllowed(AlbumOp.SET_COVER)
    val change = if (media == null) {
      SharedAlbumChange.Cover(null, null)
    } else {
      val item = published(view, media)?.let { view.merged.items[it] }
      if (item == null || view.attested[item.media] !in shownItems(view)) return EditOutcome.Invalid(null)
      SharedAlbumChange.Cover(item.media, item.contentKey)
    }
    blocked()?.let { return EditOutcome.Blocked(it) }
    write(id, listOf(change))
    return EditOutcome.Done
  }

  override suspend fun setSort(id: AlbumId, sort: AlbumSort): EditOutcome {
    active(id) ?: return EditOutcome.NotFound
    if (!allowed(id, AlbumOp.SET_SORT)) return EditOutcome.NotAllowed(AlbumOp.SET_SORT)
    blocked()?.let { return EditOutcome.Blocked(it) }
    write(id, listOf(SharedAlbumChange.Sort(sort)))
    return EditOutcome.Done
  }

  override suspend fun setLevel(id: AlbumId, level: ShareLevel): EditOutcome {
    val m = active(id)?.merged ?: return EditOutcome.NotFound
    if (!allowed(id, AlbumOp.CHANGE_LEVEL)) return EditOutcome.NotAllowed(AlbumOp.CHANGE_LEVEL)
    if (m.levels.current == level && m.levels.changes.isNotEmpty()) return EditOutcome.Done
    // La storia ha al piu' 32 cambi: togliere il primo direbbe il falso sui livelli del passato (le voci di allora cambierebbero esito).
    if (m.levels.changes.size >= SharedAlbumLimits.MAX_LEVEL_CHANGES) return EditOutcome.NotAllowed(AlbumOp.CHANGE_LEVEL)
    blocked()?.let { return EditOutcome.Blocked(it) }
    write(id, listOf(SharedAlbumChange.Level(level)))
    return EditOutcome.Done
  }

  override suspend fun closePreview(id: AlbumId, mode: CloseMode): ClosePreviewOutcome {
    val view = active(id) ?: return ClosePreviewOutcome.NotFound
    val op = if (mode == CloseMode.STOP) AlbumOp.STOP_SHARING else AlbumOp.DELETE_ALBUM
    if (!allowed(id, op)) return ClosePreviewOutcome.NotAllowed(op)
    val me = me() ?: return ClosePreviewOutcome.NotFound
    val m = view.merged
    // "Riporta" vale per i miei elementi che il mio catalogo vede nell'area comune; la cartella d'origine la controlla P4 (`shared_origin`).
    val attested = m.items.values.filter { it.addedBy == me }.sortedBy { it.position }.mapNotNull { view.attested[it.media] }.distinct()
    // D22: un mio elemento nascosto non si propone ne' si conta fra quelli da riportare: il foglio non ne fa trapelare l'esistenza (Fase 7, P9).
    val shown = if (attested.isEmpty()) emptySet() else visibility.visible(attested)
    val mine = attested.filter { it in shown }
    val others = m.winners.values.map { it.account }.toSet() - m.creator
    return ClosePreviewOutcome.Ready(ClosePreview(id, m.title, mode, mine, others.size))
  }

  override suspend fun close(id: AlbumId, mode: CloseMode, forOthers: OthersAfterClose): CloseOutcome {
    val m = active(id)?.merged ?: return CloseOutcome.NotFound
    val op = if (mode == CloseMode.STOP) AlbumOp.STOP_SHARING else AlbumOp.DELETE_ALBUM
    if (!allowed(id, op)) return CloseOutcome.NotAllowed(op)
    blocked()?.let { return CloseOutcome.Blocked(it) }
    val state = if (mode == CloseMode.STOP) SharedState.CLOSED else SharedState.DELETED
    write(id, listOf(SharedAlbumChange.Definition(m.title, m.folder, state, m.creator, m.nonceHex, forOthers)))
    return CloseOutcome.Closed
  }

  override suspend fun leave(id: AlbumId): EditOutcome {
    replica.view(id) ?: return EditOutcome.NotFound
    if (!allowed(id, AlbumOp.LEAVE)) return EditOutcome.NotAllowed(AlbumOp.LEAVE)
    choices.setLeft(id, true)
    refresh()
    return EditOutcome.Done
  }

  override suspend fun rejoin(id: AlbumId): EditOutcome {
    if (id !in choices.left()) return EditOutcome.NotFound
    choices.setLeft(id, false)
    refresh()
    return EditOutcome.Done
  }

  /**
   * D11, E-SH11: un dispositivo admin eredita l'album di un creatore uscito dalla famiglia. Uscito vuol dire senza dispositivi approvati ne'
   * dismessi, quindi i contributi del creatore non valgono piu' (A7) e l'album non ha piu' una creazione valida: l'erede lo **ricostituisce**
   * dalla memoria locale ([SharedAlbumReplica.orphans]) con lo stesso id, la stessa cartella, la stessa storia dei livelli (le voci degli altri
   * si giudicano come prima) e gli elementi del creatore i cui file il mio catalogo vede ancora nell'area comune (D9; a mio nome: nessun file
   * si sposta). Gli elementi degli altri familiari restano i loro.
   */
  override suspend fun inherit(id: AlbumId): InheritOutcome {
    val activeView = active(id)
    val m = activeView?.merged ?: replica.orphans.value[id]?.takeIf { it.state == SharedState.ACTIVE } ?: return InheritOutcome.NotFound
    val me = me() ?: return InheritOutcome.NotFound
    if (!meIsAdmin()) return InheritOutcome.NotAdmin
    if (!creatorLeft(m)) return InheritOutcome.CreatorStillInFamily
    blocked()?.let { return InheritOutcome.Blocked(it) }
    val registryVersion = identity.registry()?.registry?.version ?: return InheritOutcome.Blocked(AlbumWriteBlock.NOT_TRUSTED)
    val changes = ArrayList<SharedAlbumChange>()
    changes += SharedAlbumChange.Heir(me, registryVersion)
    if (activeView == null) {
      changes += SharedAlbumChange.Definition(m.title, m.folder, SharedState.ACTIVE, m.creator, m.nonceHex, null)
      if (m.levels.changes.isNotEmpty()) changes += SharedAlbumChange.LevelHistory(m.levels.changes.map { LevelStep(it.at, it.level) })
      m.sort?.let { changes += SharedAlbumChange.Sort(it) }
      val creatorItems = m.items.values.filter { it.addedBy == m.creator }
      val attested = replica.attest(creatorItems)
      for (item in creatorItems.filter { it.contentKey in attested }) {
        changes += SharedAlbumChange.Item(item.media, item.version, item.contentKey, item.path, item.position, me, item.sha256)
      }
    }
    write(id, changes)
    return InheritOutcome.Inherited
  }

  override fun allowed(id: AlbumId, op: AlbumOp, item: MediaId?): Boolean {
    val view = active(id) ?: return false
    val me = me() ?: return false
    val published = item?.let { published(view, it) }
    // Senza l'ora del NAS vale il livello di adesso (l'ultimo cambio): e' quello con cui chi legge giudichera' la mia voce.
    val now = clocks.nasNowMs() ?: Long.MAX_VALUE
    return SharedAlbumRules.allows(view.merged, me, meIsAdmin(), op, published, now, creatorLeft(view.merged))
  }

  override suspend fun dismissNotice(notice: SharedAlbumNotice) {
    val key = when (notice) {
      is SharedAlbumNotice.Closed -> "closed:${notice.album.value}"
      is SharedAlbumNotice.NotAddedLevelChanged -> "notadded:${notice.album.value}:${notice.count}"
      is SharedAlbumNotice.Inherited -> "inherited:${notice.album.value}"
      // P13: con la cartella nuova nella chiave (come impronta): un secondo cambio dal PC si dice di nuovo.
      is SharedAlbumNotice.FolderChanged -> "folder:${notice.album.value}:${notice.newFolder?.let { Integer.toHexString(it.hashCode()) } ?: "-"}"
      is SharedAlbumNotice.FilesAddedFromPc -> "pcfiles:${notice.album.value}:${notice.count}"
      is SharedAlbumNotice.RevokedItems -> "revoked:${notice.album.value}:${notice.count}"
    }
    choices.dismiss(key)
    refresh()
  }

  // --- SharedAlbumFacts ------------------------------------------------------------------------------------------------------------

  override suspend fun folder(album: AlbumId): ShareFolder? {
    val m = replica.view(album)?.merged ?: return null
    val share = family.commonShare() ?: identity.registry()?.registry?.config?.commonShare ?: return null
    return ShareFolder(share, m.folder)
  }

  /** Gli elementi vivi e chi li ha aggiunti: con l'id di chi li ha pubblicati e, se il mio catalogo li attesta, con il mio. */
  override suspend fun members(album: AlbumId): Map<MediaId, AccountId> {
    val view = replica.view(album) ?: return emptyMap()
    val out = LinkedHashMap<MediaId, AccountId>()
    for (item in view.merged.items.values) {
      out[item.media] = item.addedBy
      view.attested[item.media]?.let { out[it] = item.addedBy }
    }
    return out
  }

  override suspend fun state(album: AlbumId): SharedState? = replica.view(album)?.merged?.state
}
