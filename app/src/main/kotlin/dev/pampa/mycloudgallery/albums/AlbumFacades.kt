package dev.pampa.mycloudgallery.albums

import dev.pampa.mycloudgallery.core.albums.contract.AddOutcome
import dev.pampa.mycloudgallery.core.albums.contract.Album
import dev.pampa.mycloudgallery.core.albums.contract.AlbumContent
import dev.pampa.mycloudgallery.core.albums.contract.AlbumList
import dev.pampa.mycloudgallery.core.albums.contract.AlbumListSort
import dev.pampa.mycloudgallery.core.albums.contract.AlbumOp
import dev.pampa.mycloudgallery.core.albums.contract.Albums
import dev.pampa.mycloudgallery.core.albums.contract.AlbumsStatus
import dev.pampa.mycloudgallery.core.albums.contract.CloseMode
import dev.pampa.mycloudgallery.core.albums.contract.CloseOutcome
import dev.pampa.mycloudgallery.core.albums.contract.ClosePreviewOutcome
import dev.pampa.mycloudgallery.core.albums.contract.CreateOutcome
import dev.pampa.mycloudgallery.core.albums.contract.EditOutcome
import dev.pampa.mycloudgallery.core.albums.contract.HiddenChoice
import dev.pampa.mycloudgallery.core.albums.contract.InheritOutcome
import dev.pampa.mycloudgallery.core.albums.contract.OthersAfterClose
import dev.pampa.mycloudgallery.core.albums.contract.RuleProposal
import dev.pampa.mycloudgallery.core.albums.contract.ShareConfirmation
import dev.pampa.mycloudgallery.core.albums.contract.ShareOpId
import dev.pampa.mycloudgallery.core.albums.contract.ShareOpState
import dev.pampa.mycloudgallery.core.albums.contract.SharePlan
import dev.pampa.mycloudgallery.core.albums.contract.SharePlanOutcome
import dev.pampa.mycloudgallery.core.albums.contract.ShareRequest
import dev.pampa.mycloudgallery.core.albums.contract.ShareRunResult
import dev.pampa.mycloudgallery.core.albums.contract.ShareRunner
import dev.pampa.mycloudgallery.core.albums.contract.ShareStartOutcome
import dev.pampa.mycloudgallery.core.albums.contract.SharedAlbumNotice
import dev.pampa.mycloudgallery.core.albums.contract.SharedAlbums
import dev.pampa.mycloudgallery.core.albums.contract.SmartAlbumEngine
import dev.pampa.mycloudgallery.core.albums.contract.SmartChoices
import dev.pampa.mycloudgallery.core.albums.contract.SmartNameInterpreter
import dev.pampa.mycloudgallery.core.albums.contract.SmartOutcome
import dev.pampa.mycloudgallery.core.albums.contract.SmartPreview
import dev.pampa.mycloudgallery.core.albums.contract.SmartRules
import dev.pampa.mycloudgallery.core.albums.contract.TripAcceptOutcome
import dev.pampa.mycloudgallery.core.albums.contract.TripId
import dev.pampa.mycloudgallery.core.albums.contract.TripProposals
import dev.pampa.mycloudgallery.core.albums.contract.TripReport
import dev.pampa.mycloudgallery.core.albums.contract.Trips
import dev.pampa.mycloudgallery.core.albums.noop.NoAlbums
import dev.pampa.mycloudgallery.core.albums.noop.NoShareRunner
import dev.pampa.mycloudgallery.core.albums.noop.NoSharedAlbums
import dev.pampa.mycloudgallery.core.albums.noop.NoSmartAlbumEngine
import dev.pampa.mycloudgallery.core.albums.noop.NoSmartChoices
import dev.pampa.mycloudgallery.core.albums.noop.NoSmartNameInterpreter
import dev.pampa.mycloudgallery.core.albums.noop.NoTrips
import dev.pampa.mycloudgallery.core.albums.shared.DenyReason
import dev.pampa.mycloudgallery.core.albums.contract.AttestRequest
import dev.pampa.mycloudgallery.core.trust.data.AlbumValue
import dev.pampa.mycloudgallery.core.model.AccountId
import dev.pampa.mycloudgallery.core.model.AlbumId
import dev.pampa.mycloudgallery.core.model.AlbumKind
import dev.pampa.mycloudgallery.core.model.AlbumSort
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.ShareLevel
import dev.pampa.mycloudgallery.core.search.contract.Today
import dev.pampa.mycloudgallery.feature.albums.contract.AlbumMembers
import dev.pampa.mycloudgallery.feature.albums.contract.AlbumPending
import dev.pampa.mycloudgallery.feature.albums.contract.AlbumScreensClock
import java.time.LocalDate
import kotlin.time.Duration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn

// LE FACCIATE DEGLI ALBUM PER LE SCHERMATE (Fase 7, P13). I ViewModel di feature-albums ricevono i contratti una volta (Hilt, `AlbumPortsModule`):
// queste facciate esistono da subito e rispondono come i null-object (niente album, niente permessi, nessuna conferma) finche' [AlbumsGraph] non ha
// montato gli album; poi parlano con quelli veri. Nessuna logica nuova qui, tranne tre legami che i contratti non coprono e che sono di :app:
// "Nuove: N" degli smart (il motore di P6 sa il numero e il segno "visto", P3 non li legge), il foglio di chiusura ristretto a chi ha la cartella
// d'origine (P4: `BringBackCandidates.restrict` sul riepilogo di P2) e il primo passo di una condivisione appena confermata.

@OptIn(ExperimentalCoroutinesApi::class)
private fun <T> AlbumsGraph.follow(scope: CoroutineScope, initial: T, pick: (AlbumsLive) -> Flow<T>, none: Flow<T>): StateFlow<T> =
  live.flatMapLatest { l -> if (l == null) none else pick(l) }.stateIn(scope, SharingStarted.Eagerly, initial)

/** [Albums] e [SmartChoices] veri (`CatalogAlbums`, P3), con "Nuove: N" degli smart dal motore (P6, E-AL7). */
@OptIn(ExperimentalCoroutinesApi::class)
class AppAlbums(private val graph: AlbumsGraph, scope: CoroutineScope) : Albums, SmartChoices {
  private fun albums(): Albums = graph.live.value?.albums ?: NoAlbums

  private fun choices(): SmartChoices = graph.live.value?.albums ?: NoSmartChoices

  override val all: StateFlow<AlbumList> = graph.live.flatMapLatest { l ->
    if (l == null) NoAlbums.all else l.albums.all.mapLatest { list -> withNew(l, list) }
  }.stateIn(scope, SharingStarted.Eagerly, NoAlbums.all.value)

  override val status: StateFlow<AlbumsStatus> = graph.follow(scope, NoAlbums.status.value, { it.albums.status }, NoAlbums.status)

  /** Il numero delle novita' di ogni smart non congelato (dal segno "visto fino a" di questo dispositivo, L84). */
  private suspend fun withNew(live: AlbumsLive, list: AlbumList): AlbumList {
    if (list.albums.none { it is Album.Smart }) return list
    return list.copy(albums = list.albums.map { a -> if (a is Album.Smart && !a.frozen) a.copy(newSinceSeen = runCatching { live.smart.newSinceSeen(a.id) }.getOrDefault(0)) else a })
  }

  /**
   * Il contenuto di un album: aprire la pagina e' "vedere" lo smart. Il numero delle novita' si legge **prima** di segnare (la striscia "Nuove: 12"
   * di questa visita), poi il segno va a adesso (l'elenco non le conta piu').
   */
  override fun content(id: AlbumId): Flow<AlbumContent?> = graph.live.flatMapLatest { l ->
    if (l == null) {
      flowOf(null)
    } else {
      flow {
        val fresh = runCatching { l.smart.newSinceSeen(id) }.getOrDefault(0)
        runCatching { l.smart.markSeen(id) }
        l.albums.content(id).collect { c ->
          val album = c?.album
          emit(if (album is Album.Smart && !album.frozen && fresh > 0) c.copy(album = album.copy(newSinceSeen = fresh)) else c)
        }
      }
    }
  }

  override suspend fun create(title: String, kind: AlbumKind, rules: SmartRules?): CreateOutcome = albums().create(title, kind, rules)

  override suspend fun rename(id: AlbumId, title: String): EditOutcome = albums().rename(id, title)

  override suspend fun delete(id: AlbumId): EditOutcome = albums().delete(id)

  override suspend fun add(id: AlbumId, media: List<MediaId>): AddOutcome = albums().add(id, media)

  override suspend fun remove(id: AlbumId, media: List<MediaId>): EditOutcome = albums().remove(id, media)

  override suspend fun setCover(id: AlbumId, media: MediaId?): EditOutcome = albums().setCover(id, media)

  override suspend fun setSort(id: AlbumId, sort: AlbumSort): EditOutcome = albums().setSort(id, sort)

  override suspend fun reorder(id: AlbumId, moved: MediaId, after: MediaId?): EditOutcome = albums().reorder(id, moved, after)

  override suspend fun setRules(id: AlbumId, rules: SmartRules): EditOutcome = albums().setRules(id, rules)

  override suspend fun setListSort(sort: AlbumListSort) = albums().setListSort(sort)

  override suspend fun moveInList(id: AlbumId, after: AlbumId?): EditOutcome = albums().moveInList(id, after)

  override suspend fun exclude(id: AlbumId, media: List<MediaId>): EditOutcome = choices().exclude(id, media)

  override suspend fun accept(id: AlbumId, media: List<MediaId>): EditOutcome = choices().accept(id, media)

  override suspend fun freeze(id: AlbumId): EditOutcome = choices().freeze(id)

  override suspend fun unfreeze(id: AlbumId): EditOutcome = choices().unfreeze(id)
}

/** [SharedAlbums] veri (`ReplicaSharedAlbums`, P2), con il foglio di chiusura ristretto ai miei media con la cartella d'origine (P4, D31, L72). */
class AppSharedAlbums(private val graph: AlbumsGraph, scope: CoroutineScope) : SharedAlbums {
  private fun shared(): SharedAlbums = graph.live.value?.shared?.albums ?: NoSharedAlbums

  override val visible: StateFlow<List<Album.Shared>> = graph.follow(scope, emptyList(), { it.shared.albums.visible }, NoSharedAlbums.visible)

  private val baseNotices: StateFlow<List<SharedAlbumNotice>> = graph.follow(scope, emptyList(), { it.shared.albums.notices }, NoSharedAlbums.notices)

  /** Gli avvisi della replica piu' quelli di :app (E-SH16 con la cartella nuova, E-SH20), sugli album che vedo. */
  override val notices: StateFlow<List<SharedAlbumNotice>> = combine(baseNotices, visible, graph.folderMoves, graph.revokedCounts, graph.dismissedNotices) {
      base, shown, moves, revoked, dismissed ->
    val titles = shown.associate { it.id to it.title }
    val extra = AlbumFolderWatch.notices(moves, revoked, titles, dismissed)
    base.filterNot { it is SharedAlbumNotice.FolderChanged && it.album in moves } + extra
  }.stateIn(scope, SharingStarted.Eagerly, emptyList())

  override val left: StateFlow<List<Album.Shared>> = graph.follow(scope, emptyList(), { it.shared.albums.left }, NoSharedAlbums.left)

  override suspend fun create(title: String, level: ShareLevel, media: List<MediaId>, hidden: HiddenChoice): SharePlanOutcome = shared().create(title, level, media, hidden)

  override suspend fun rename(id: AlbumId, title: String): EditOutcome = shared().rename(id, title)

  override suspend fun remove(id: AlbumId, media: List<MediaId>): EditOutcome = shared().remove(id, media)

  override suspend fun setCover(id: AlbumId, media: MediaId?): EditOutcome = shared().setCover(id, media)

  override suspend fun setSort(id: AlbumId, sort: AlbumSort): EditOutcome = shared().setSort(id, sort)

  override suspend fun setLevel(id: AlbumId, level: ShareLevel): EditOutcome = shared().setLevel(id, level)

  override suspend fun closePreview(id: AlbumId, mode: CloseMode): ClosePreviewOutcome {
    val live = graph.live.value ?: return NoSharedAlbums.closePreview(id, mode)
    return when (val out = live.shared.albums.closePreview(id, mode)) {
      is ClosePreviewOutcome.Ready -> ClosePreviewOutcome.Ready(live.shared.bringBack.restrict(out.preview))
      else -> out
    }
  }

  override suspend fun close(id: AlbumId, mode: CloseMode, forOthers: OthersAfterClose): CloseOutcome = shared().close(id, mode, forOthers)

  override suspend fun leave(id: AlbumId): EditOutcome = shared().leave(id)

  override suspend fun rejoin(id: AlbumId): EditOutcome = shared().rejoin(id)

  override suspend fun inherit(id: AlbumId): InheritOutcome = shared().inherit(id)

  override fun allowed(id: AlbumId, op: AlbumOp, item: MediaId?): Boolean = shared().allowed(id, op, item)

  override suspend fun dismissNotice(notice: SharedAlbumNotice) {
    shared().dismissNotice(notice)
    graph.live.value?.let { live -> graph.dismissedNotices.value = live.shared.choices.dismissed() }
  }

  override suspend fun useNewFolder(id: AlbumId): EditOutcome {
    val live = graph.live.value ?: return EditOutcome.NotFound
    val out = AlbumFolderWatch.useNewFolder(live, graph.folderMoves.value[id], id)
    if (out == EditOutcome.Done) graph.scanNotices(live)
    return out
  }

  override suspend fun restoreRevoked(id: AlbumId): EditOutcome {
    val live = graph.live.value ?: return EditOutcome.NotFound
    val out = AlbumFolderWatch.restoreRevoked(live, id)
    graph.scanNotices(live)
    return out
  }
}

/** [ShareRunner] vero (`ShareOrchestrator`, P4): dopo una conferma il primo passo parte subito e il lavoro della condivisione e' chiesto. */
class AppShareRunner(private val graph: AlbumsGraph, scope: CoroutineScope) : ShareRunner {
  private fun runner(): ShareRunner = graph.live.value?.shared?.orchestrator ?: NoShareRunner

  override val operations: StateFlow<List<ShareOpState>> = graph.follow(scope, emptyList(), { it.shared.orchestrator.operations }, NoShareRunner.operations)

  override suspend fun plan(request: ShareRequest): SharePlanOutcome = runner().plan(request)

  override suspend fun start(plan: SharePlan, confirmation: ShareConfirmation): ShareStartOutcome =
    runner().start(plan, confirmation).also { if (it is ShareStartOutcome.Started) graph.shareStarted() }

  override suspend fun resumeAll(budget: Duration): ShareRunResult = runner().resumeAll(budget)

  override suspend fun cancel(op: ShareOpId) = runner().cancel(op)
}

/** Il motore degli album smart e l'interprete del nome (P6). */
class AppSmartAlbums(private val graph: AlbumsGraph) : SmartAlbumEngine, SmartNameInterpreter {
  override suspend fun evaluate(id: AlbumId, budget: Duration): SmartOutcome = graph.live.value?.smart?.evaluate(id, budget) ?: NoSmartAlbumEngine.evaluate(id, budget)

  override suspend fun preview(rules: SmartRules): SmartPreview = graph.live.value?.smart?.preview(rules) ?: NoSmartAlbumEngine.preview(rules)

  override suspend fun propose(title: String, today: LocalDate): RuleProposal =
    graph.live.value?.interpreter?.propose(title, today) ?: NoSmartNameInterpreter.propose(title, today)
}

/** I viaggi e gli eventi (P7). */
class AppTrips(private val graph: AlbumsGraph, scope: CoroutineScope) : Trips {
  override val proposals: StateFlow<TripProposals> = graph.follow(scope, NoTrips.proposals.value, { it.trips.proposals }, NoTrips.proposals)

  override suspend fun detect(budget: Duration): TripReport = graph.live.value?.trips?.detect(budget) ?: NoTrips.detect(budget)

  override suspend fun accept(id: TripId): TripAcceptOutcome = graph.live.value?.trips?.accept(id) ?: NoTrips.accept(id)

  override suspend fun dismiss(id: TripId) {
    graph.live.value?.trips?.dismiss(id)
  }
}

/** Chi ha aggiunto un elemento di un album condiviso (Q6), dalla replica (`SharedAlbumFacts.members`). */
class AppAlbumMembers(private val graph: AlbumsGraph) : AlbumMembers {
  override suspend fun addedBy(album: AlbumId, media: MediaId): AccountId? = graph.live.value?.shared?.albums?.members(album)?.get(media)
}

/**
 * Le strisce con un bottone della pagina di un album condiviso: i file aggiunti dal PC nella cartella (D39, `FolderAdoptions.pending`, senza nascosti)
 * e le mie foto che il livello nuovo ha scartato (E-SH13), fra quelle che posso riportare nelle mie cartelle (`BringBackCandidates`, L72).
 */
class AppAlbumPending(private val graph: AlbumsGraph) : AlbumPending {
  override suspend fun filesFromFolder(album: AlbumId): List<MediaId> = graph.live.value?.shared?.adoptions?.pending(album).orEmpty()

  override suspend fun notAdded(album: AlbumId): List<MediaId> {
    val shared = graph.live.value?.shared ?: return emptyList()
    val view = shared.replica.view(album) ?: return emptyList()
    val me = shared.ports.family.me() ?: return emptyList()
    val keys = view.merged.denied
      .filter { it.entry.account == me && it.op == AlbumOp.ADD_MEDIA && it.reason == DenyReason.NOT_PERMITTED }
      .mapNotNull { (it.entry.value as? AlbumValue.Item)?.contentKey }
      .distinct()
    if (keys.isEmpty()) return emptyList()
    val local = shared.ports.facts.attest(keys.map { AttestRequest(it, null) })
    return shared.bringBack.among(album, keys.mapNotNull { local[it] }.distinct())
  }
}

/** L'orologio delle schermate: "oggi" dalla porta `Today` (D36) e l'orologio monotono (conferme, scadenze). Senza questo nessun foglio conferma. */
class AppAlbumScreensClock(private val today: Today, private val monotonic: () -> Long) : AlbumScreensClock {
  override fun today(): LocalDate = today.date()

  override fun monotonicMs(): Long = monotonic()
}
