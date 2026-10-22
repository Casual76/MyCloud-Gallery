package dev.pampa.mycloudgallery.core.albums.testing

import dev.pampa.mycloudgallery.core.albums.contract.Album
import dev.pampa.mycloudgallery.core.albums.contract.AlbumOp
import dev.pampa.mycloudgallery.core.albums.contract.AlbumPermissions
import dev.pampa.mycloudgallery.core.albums.contract.AlbumTitles
import dev.pampa.mycloudgallery.core.albums.contract.ClosePreview
import dev.pampa.mycloudgallery.core.albums.contract.ClosePreviewOutcome
import dev.pampa.mycloudgallery.core.albums.contract.CloseMode
import dev.pampa.mycloudgallery.core.albums.contract.CloseOutcome
import dev.pampa.mycloudgallery.core.albums.contract.CoverRef
import dev.pampa.mycloudgallery.core.albums.contract.EditOutcome
import dev.pampa.mycloudgallery.core.albums.contract.HiddenChoice
import dev.pampa.mycloudgallery.core.albums.contract.InheritOutcome
import dev.pampa.mycloudgallery.core.albums.contract.MediaPlace
import dev.pampa.mycloudgallery.core.albums.contract.OthersAfterClose
import dev.pampa.mycloudgallery.core.albums.contract.PermissionCondition
import dev.pampa.mycloudgallery.core.albums.contract.ShareFolder
import dev.pampa.mycloudgallery.core.albums.contract.SharePlanOutcome
import dev.pampa.mycloudgallery.core.albums.contract.ShareRequest
import dev.pampa.mycloudgallery.core.albums.contract.ShareRunner
import dev.pampa.mycloudgallery.core.albums.contract.SharedAlbumChange
import dev.pampa.mycloudgallery.core.albums.contract.SharedAlbumCopies
import dev.pampa.mycloudgallery.core.albums.contract.SharedAlbumFacts
import dev.pampa.mycloudgallery.core.albums.contract.SharedAlbumNotice
import dev.pampa.mycloudgallery.core.albums.contract.SharedAlbums
import dev.pampa.mycloudgallery.core.albums.contract.SharedState
import dev.pampa.mycloudgallery.core.model.AccountId
import dev.pampa.mycloudgallery.core.model.AlbumId
import dev.pampa.mycloudgallery.core.model.AlbumKind
import dev.pampa.mycloudgallery.core.model.AlbumSort
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.ShareLevel
import dev.pampa.mycloudgallery.core.albums.noop.NoShareRunner
import kotlinx.coroutines.flow.MutableStateFlow

/** Una copia personale di un album chiuso (D31, Q5 b), come la vede l'account che la riceve: [items] gli elementi attestati. */
data class KeptCopy(val copy: AlbumId, val shared: AlbumId, val title: String, val creator: AccountId, val mine: Boolean, val items: List<MediaId>) {
  override fun toString(): String = "KeptCopy(${items.size})"
}

/**
 * Gli album condivisi finti visti dal dispositivo di [family] (Fase 7, P0), sopra il mondo comune [world] e la Libreria [library]: gli
 * elementi si mostrano solo se il catalogo locale li attesta nell'area comune e non sono nascosti (D9, D22). Le modifiche passano dalle
 * regole di chi legge del mondo (stessa tabella, C25): `allowed` e cio' che il mondo accetta coincidono (`SharedAlbumsContract`). La
 * creazione e l'aggiunta passano da [runner] (piano e conferma). Le voci si applicano subito: il pubblicatore finto e' altrove.
 */
class FakeSharedAlbums(
  private val world: FakeSharedWorld,
  private val family: FakeAlbumFamily,
  private val library: FakeAlbumLibrary,
  var runner: ShareRunner = NoShareRunner,
) : SharedAlbums, SharedAlbumFacts {

  override val visible: MutableStateFlow<List<Album.Shared>> = MutableStateFlow(emptyList())
  override val notices: MutableStateFlow<List<SharedAlbumNotice>> = MutableStateFlow(emptyList())
  override val left: MutableStateFlow<List<Album.Shared>> = MutableStateFlow(emptyList())

  private val dismissed = mutableSetOf<AlbumId>()
  private val listeners = mutableListOf<() -> Unit>()

  /** Chi mostra gli album (l'esecutore di riferimento degli album) si registra qui per ricalcolare. */
  fun addListener(listener: () -> Unit) {
    listeners += listener
  }

  init {
    world.addListener(::refresh)
    library.addListener(::refresh)
    refresh()
  }

  private fun me(): AccountId? = family.me()

  /** Le copie personali che questo account tiene (Q5 b; per il creatore, l'album dopo "Smetti di condividere"). */
  fun keptCopies(): List<KeptCopy> {
    val me = me() ?: return emptyList()
    return world.albumIds().mapNotNull { world.album(it) }.mapNotNull { a ->
      val mine = a.creator == me || a.heir == me
      val keeps = when (a.state) {
        SharedState.ACTIVE -> false
        SharedState.CLOSED -> mine || (a.afterClose == OthersAfterClose.KEEP_COPY && a.id !in world.leftBy(me))
        SharedState.DELETED -> !mine && a.afterClose == OthersAfterClose.KEEP_COPY && a.id !in world.leftBy(me)
      }
      if (!keeps) null else KeptCopy(SharedAlbumCopies.copyIdFor(a.id, me), a.id, a.title, a.creator, mine, attestedVisible(a))
    }
  }

  private fun attestedVisible(a: FakeSharedWorld.AlbumState): List<MediaId> {
    val attested = library.attestedNow(a.items.values.map { it.contentKey })
    return library.visibleNow(attested.values).let { visible -> a.items.values.mapNotNull { attested[it.contentKey]?.takeIf { id -> id in visible } } }
  }

  /** Gli elementi mostrabili di un album attivo, nell'ordine dell'album (posizioni). */
  fun visibleItems(id: AlbumId): List<MediaId> {
    val a = world.album(id) ?: return emptyList()
    // D9: l'elemento e' il media del **mio** catalogo con la stessa chiave di contenuto nell'area comune (l'id puo' essere un altro).
    val attested = library.attestedNow(a.items.values.map { it.contentKey })
    val visible = library.visibleNow(attested.values)
    return a.items.values.sortedBy { it.position }.mapNotNull { attested[it.contentKey] }.filter { it in visible }
  }

  fun refresh() {
    val me = me()
    val shown = mutableListOf<Album.Shared>()
    val leftList = mutableListOf<Album.Shared>()
    val out = mutableListOf<SharedAlbumNotice>()
    for (id in world.albumIds()) {
      val a = world.album(id) ?: continue
      val mine = me != null && (a.creator == me || a.heir == me)
      if (a.state == SharedState.ACTIVE) {
        val album = toAlbum(a, mine)
        if (me != null && id in world.leftBy(me)) leftList += album else shown += album
      } else if (!mine && me != null && id !in world.leftBy(me) && id !in dismissed) {
        val mode = if (a.state == SharedState.CLOSED) CloseMode.STOP else CloseMode.DELETE
        val copy = if (a.afterClose == OthersAfterClose.KEEP_COPY) SharedAlbumCopies.copyIdFor(id, me) else null
        out += SharedAlbumNotice.Closed(id, a.title, mode, copy)
      }
    }
    visible.value = shown
    left.value = leftList
    notices.value = out
    listeners.toList().forEach { it() }
  }

  private fun toAlbum(a: FakeSharedWorld.AlbumState, mine: Boolean): Album.Shared {
    val items = attestedVisible(a)
    val chosen = a.cover?.takeIf { it in items }
    val cover = when {
      chosen != null -> CoverRef.Chosen(chosen)
      items.isNotEmpty() -> CoverRef.Auto(items.last())
      else -> CoverRef.None
    }
    val newest = items.mapNotNull { library[it]?.day }.maxOrNull()
    return Album.Shared(
      id = a.id, title = a.title, kind = AlbumKind.MANUAL, cover = cover, count = items.size, sort = a.sort, updatedAtNas = a.updatedAt,
      newestDay = newest, level = a.level, creator = a.creator, heir = a.heir, mine = mine, state = a.state,
    )
  }

  private fun write(id: AlbumId, change: SharedAlbumChange): Boolean {
    val me = me() ?: return false
    return world.apply(id, me, listOf(change)) == 1
  }

  override suspend fun create(title: String, level: ShareLevel, media: List<MediaId>, hidden: HiddenChoice): SharePlanOutcome =
    runner.plan(ShareRequest.NewAlbum(title, level, media, hidden))

  override suspend fun rename(id: AlbumId, title: String): EditOutcome {
    val a = world.album(id)?.takeIf { it.state == SharedState.ACTIVE } ?: return EditOutcome.NotFound
    AlbumTitles.problem(title)?.let { return EditOutcome.Invalid(it) }
    if (!allowed(id, AlbumOp.RENAME)) return EditOutcome.NotAllowed(AlbumOp.RENAME)
    val change = SharedAlbumChange.Definition(AlbumTitles.normalized(title), a.folder, SharedState.ACTIVE, a.creator, a.nonceHex, null)
    return if (write(id, change)) EditOutcome.Done else EditOutcome.NotAllowed(AlbumOp.RENAME)
  }

  override suspend fun remove(id: AlbumId, media: List<MediaId>): EditOutcome {
    if (world.album(id)?.state != SharedState.ACTIVE) return EditOutcome.NotFound
    // Tutto o niente: un elemento che la tabella non mi permette di togliere ferma la richiesta intera.
    if (media.any { !allowed(id, AlbumOp.REMOVE_ITEM, it) }) return EditOutcome.NotAllowed(AlbumOp.REMOVE_ITEM)
    media.forEach { write(id, SharedAlbumChange.ItemRemoved(it)) }
    return EditOutcome.Done
  }

  override suspend fun setCover(id: AlbumId, media: MediaId?): EditOutcome {
    val a = world.album(id)?.takeIf { it.state == SharedState.ACTIVE } ?: return EditOutcome.NotFound
    if (!allowed(id, AlbumOp.SET_COVER)) return EditOutcome.NotAllowed(AlbumOp.SET_COVER)
    if (media != null && media !in a.items) return EditOutcome.Invalid(null)
    return if (write(id, SharedAlbumChange.Cover(media, media?.let { a.items[it]?.contentKey }))) EditOutcome.Done else EditOutcome.NotAllowed(AlbumOp.SET_COVER)
  }

  override suspend fun setSort(id: AlbumId, sort: AlbumSort): EditOutcome {
    if (world.album(id)?.state != SharedState.ACTIVE) return EditOutcome.NotFound
    if (!allowed(id, AlbumOp.SET_SORT)) return EditOutcome.NotAllowed(AlbumOp.SET_SORT)
    return if (write(id, SharedAlbumChange.Sort(sort))) EditOutcome.Done else EditOutcome.NotAllowed(AlbumOp.SET_SORT)
  }

  override suspend fun setLevel(id: AlbumId, level: ShareLevel): EditOutcome {
    if (world.album(id) == null) return EditOutcome.NotFound
    if (!allowed(id, AlbumOp.CHANGE_LEVEL)) return EditOutcome.NotAllowed(AlbumOp.CHANGE_LEVEL)
    return if (write(id, SharedAlbumChange.Level(level))) EditOutcome.Done else EditOutcome.NotAllowed(AlbumOp.CHANGE_LEVEL)
  }

  override suspend fun closePreview(id: AlbumId, mode: CloseMode): ClosePreviewOutcome {
    val a = world.album(id)?.takeIf { it.state == SharedState.ACTIVE } ?: return ClosePreviewOutcome.NotFound
    val op = if (mode == CloseMode.STOP) AlbumOp.STOP_SHARING else AlbumOp.DELETE_ALBUM
    if (!allowed(id, op)) return ClosePreviewOutcome.NotAllowed(op)
    val me = me()!!
    val mineInCommon = a.items.values.filter { it.addedBy == me }.map { it.media }.filter { library[it]?.place == MediaPlace.COMMON }
    return ClosePreviewOutcome.Ready(ClosePreview(id, a.title, mode, mineInCommon, otherMembers = (world.accounts - a.creator).size))
  }

  override suspend fun close(id: AlbumId, mode: CloseMode, forOthers: OthersAfterClose): CloseOutcome {
    val a = world.album(id)?.takeIf { it.state == SharedState.ACTIVE } ?: return CloseOutcome.NotFound
    val op = if (mode == CloseMode.STOP) AlbumOp.STOP_SHARING else AlbumOp.DELETE_ALBUM
    if (!allowed(id, op)) return CloseOutcome.NotAllowed(op)
    val state = if (mode == CloseMode.STOP) SharedState.CLOSED else SharedState.DELETED
    val change = SharedAlbumChange.Definition(a.title, a.folder, state, a.creator, a.nonceHex, forOthers)
    return if (write(id, change)) CloseOutcome.Closed else CloseOutcome.NotAllowed(op)
  }

  override suspend fun leave(id: AlbumId): EditOutcome {
    if (world.album(id) == null) return EditOutcome.NotFound
    if (!allowed(id, AlbumOp.LEAVE)) return EditOutcome.NotAllowed(AlbumOp.LEAVE)
    world.leftBy(me()!!) += id
    world.touch()
    return EditOutcome.Done
  }

  override suspend fun rejoin(id: AlbumId): EditOutcome {
    val me = me() ?: return EditOutcome.NotFound
    if (!world.leftBy(me).remove(id)) return EditOutcome.NotFound
    world.touch()
    return EditOutcome.Done
  }

  override suspend fun inherit(id: AlbumId): InheritOutcome {
    val a = world.album(id)?.takeIf { it.state == SharedState.ACTIVE } ?: return InheritOutcome.NotFound
    val me = me() ?: return InheritOutcome.NotFound
    if (me !in world.admins) return InheritOutcome.NotAdmin
    if (a.creator !in world.gone) return InheritOutcome.CreatorStillInFamily
    return if (write(id, SharedAlbumChange.Heir(me, registryVersion = 1))) InheritOutcome.Inherited else InheritOutcome.NotAdmin
  }

  override fun allowed(id: AlbumId, op: AlbumOp, item: MediaId?): Boolean {
    val me = me() ?: return false
    val a = world.album(id)?.takeIf { it.state == SharedState.ACTIVE } ?: return false
    val addedBy = item?.let { a.items[it]?.addedBy }
    val roles = AlbumPermissions.rolesOf(me, a.creator, a.heir, addedBy, me in world.admins)
    if (!AlbumPermissions.admits(op, a.level, roles)) return false
    return when (AlbumPermissions.cell(op, a.level).condition) {
      PermissionCondition.NONE, PermissionCondition.ADDED_WHILE_PERMITTED -> true
      PermissionCondition.CREATOR_LEFT_FAMILY -> a.creator in world.gone
    }
  }

  override suspend fun dismissNotice(notice: SharedAlbumNotice) {
    dismissed += notice.album
    refresh()
  }

  // --- SharedAlbumFacts -------------------------------------------------------------------------------------------------------

  override suspend fun folder(album: AlbumId): ShareFolder? = world.album(album)?.let { ShareFolder(world.commonShare, it.folder) }

  override suspend fun members(album: AlbumId): Map<MediaId, AccountId> = world.album(album)?.items?.mapValues { it.value.addedBy }.orEmpty()

  override suspend fun state(album: AlbumId): SharedState? = world.album(album)?.state
}
