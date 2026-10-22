package dev.pampa.mycloudgallery.core.albums.testing

import dev.pampa.mycloudgallery.core.albums.contract.AddOutcome
import dev.pampa.mycloudgallery.core.albums.contract.Album
import dev.pampa.mycloudgallery.core.albums.contract.AlbumClocks
import dev.pampa.mycloudgallery.core.albums.contract.AlbumContent
import dev.pampa.mycloudgallery.core.albums.contract.AlbumFamily
import dev.pampa.mycloudgallery.core.albums.contract.AlbumList
import dev.pampa.mycloudgallery.core.albums.contract.AlbumListSort
import dev.pampa.mycloudgallery.core.albums.contract.AlbumOp
import dev.pampa.mycloudgallery.core.albums.contract.AlbumTitles
import dev.pampa.mycloudgallery.core.albums.contract.Albums
import dev.pampa.mycloudgallery.core.albums.contract.AlbumsStatus
import dev.pampa.mycloudgallery.core.albums.contract.CreateOutcome
import dev.pampa.mycloudgallery.core.albums.contract.EditOutcome
import dev.pampa.mycloudgallery.core.albums.contract.FormerShare
import dev.pampa.mycloudgallery.core.albums.contract.SharePlanOutcome
import dev.pampa.mycloudgallery.core.albums.contract.ShareRequest
import dev.pampa.mycloudgallery.core.albums.contract.ShareRunner
import dev.pampa.mycloudgallery.core.albums.contract.SmartChoices
import dev.pampa.mycloudgallery.core.albums.contract.SmartRules
import dev.pampa.mycloudgallery.core.albums.noop.NoShareRunner
import dev.pampa.mycloudgallery.core.model.AlbumId
import dev.pampa.mycloudgallery.core.model.AlbumKind
import dev.pampa.mycloudgallery.core.model.AlbumSort
import dev.pampa.mycloudgallery.core.model.MediaId
import java.text.Normalizer
import java.util.Locale
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** I membri di un album smart secondo le regole (D14): sicuri e dubbi. Nel riferimento li decide chi lo costruisce (il motore e' di P6). */
data class SmartMembership(val confident: List<MediaId>, val suggested: List<MediaId>) {
  companion object {
    val EMPTY = SmartMembership(emptyList(), emptyList())
  }
}

/**
 * L'ESECUTORE DI RIFERIMENTO degli album (docs/fase7-progetto.md §2.1, D3-D5, D12, D16, D21, D22, D31 con Q5, D39): in memoria, sopra la
 * Libreria finta. Album personali (manuali e smart, con le scelte di [SmartChoices]), gli album condivisi di [shared] (le modifiche passano
 * dalla sua tabella dei permessi, l'aggiunta dal piano di [runner]) e le copie personali di Q5 (b). P3 scrive la proiezione vera e la fa
 * passare dalla stessa suite (`AlbumsContract`); P10 e P11 lo usano finche' non c'e'.
 *
 * Mai un nascosto: le viste nascono dall'istantanea (che non li ha) e dal controllo `visibleNow`. Nessuna modifica tocca un file.
 */
class InMemoryAlbums(
  private val library: FakeAlbumLibrary,
  private val family: AlbumFamily,
  private val clocks: AlbumClocks,
  private val shared: FakeSharedAlbums? = null,
  private val runner: ShareRunner = NoShareRunner,
  private val smart: (SmartRules) -> SmartMembership = { SmartMembership.EMPTY },
  private val newNonce: () -> ByteArray = { ByteArray(16).also { java.security.SecureRandom().nextBytes(it) } },
) : Albums, SmartChoices {

  private class Item(var position: Long, val addedSeq: Long)

  private class State(
    val id: AlbumId,
    val kind: AlbumKind,
    var title: String,
    var sort: AlbumSort,
    var rules: SmartRules?,
    var updatedAt: Long,
    val formerShare: FormerShare? = null,
  ) {
    var cover: MediaId? = null
    var frozen = false
    val items = LinkedHashMap<MediaId, Item>()
    val excluded = mutableSetOf<MediaId>()
    val accepted = mutableSetOf<MediaId>()
  }

  private val albums = LinkedHashMap<AlbumId, State>()
  private val deleted = mutableSetOf<AlbumId>()
  private val listOrder = HashMap<AlbumId, Long>()
  private var listSort = AlbumListSort.DEFAULT
  private var seq = 0L
  private val revision = MutableStateFlow(0L)

  override val all: MutableStateFlow<AlbumList> = MutableStateFlow(AlbumList.EMPTY)
  override val status: MutableStateFlow<AlbumsStatus> = MutableStateFlow(AlbumsStatus.Ready)

  init {
    library.addListener(::refresh)
    shared?.addListener(::refresh)
    refresh()
  }

  private fun stamp(): Long = (clocks.nasNowMs() ?: 0L) + (++seq)

  // --- Lettura ----------------------------------------------------------------------------------------------------------------

  /** Le copie personali di Q5 (b) (e l'album del creatore dopo "Smetti di condividere") diventano album personali, una volta sola. */
  private fun adoptKeptCopies() {
    for (copy in shared?.keptCopies().orEmpty()) {
      if (copy.copy in albums || copy.copy in deleted) continue
      val state = State(copy.copy, AlbumKind.MANUAL, copy.title, AlbumSort.defaultFor(AlbumKind.MANUAL), null, stamp(), FormerShare(copy.shared, copy.creator, copy.mine, stamp()))
      copy.items.forEachIndexed { i, media -> state.items[media] = Item((i + 1) * POSITION_STEP, ++seq) }
      albums[copy.copy] = state
    }
  }

  private fun members(s: State): List<MediaId> {
    if (s.kind == AlbumKind.MANUAL || s.frozen) return s.items.keys.toList()
    val rules = s.rules ?: return emptyList()
    return (smart(rules).confident + s.accepted + s.items.keys).distinct().filter { it !in s.excluded }
  }

  private fun ordered(s: State, ids: Collection<MediaId>): List<MediaId> = when (s.sort) {
    AlbumSort.CUSTOM -> ids.sortedWith(compareBy<MediaId>({ s.items[it]?.position ?: Long.MAX_VALUE }, { library[it]?.takenAtUtcMs ?: 0L }, { it.value }))
    AlbumSort.OLDEST_FIRST -> ids.sortedWith(compareBy<MediaId>({ library[it]?.takenAtUtcMs ?: 0L }, { it.value }))
    AlbumSort.NEWEST_FIRST -> ids.sortedWith(compareByDescending<MediaId> { library[it]?.takenAtUtcMs ?: 0L }.thenByDescending { it.value })
  }

  private fun visibleMembers(s: State): List<MediaId> = members(s).let { m -> library.visibleNow(m).let { v -> m.filter { it in v } } }

  private fun toAlbum(s: State): Album {
    val visible = visibleMembers(s)
    val chosen = s.cover?.takeIf { it in visible }
    val auto = when (s.kind) {
      AlbumKind.MANUAL -> visible.maxByOrNull { s.items[it]?.addedSeq ?: 0L }
      AlbumKind.SMART -> visible.groupBy { library[it]?.day }.maxByOrNull { it.value.size }?.value?.maxByOrNull { library[it]?.pixels ?: 0L }
    }
    val cover = when {
      chosen != null -> dev.pampa.mycloudgallery.core.albums.contract.CoverRef.Chosen(chosen)
      auto != null -> dev.pampa.mycloudgallery.core.albums.contract.CoverRef.Auto(auto)
      else -> dev.pampa.mycloudgallery.core.albums.contract.CoverRef.None
    }
    val newest = visible.mapNotNull { library[it]?.day }.maxOrNull()
    return when (s.kind) {
      AlbumKind.MANUAL -> Album.Manual(s.id, s.title, cover, visible.size, s.sort, s.updatedAt, newest, s.formerShare)
      AlbumKind.SMART -> {
        val suggested = if (s.frozen) 0 else suggestedOf(s).size
        Album.Smart(s.id, s.title, cover, visible.size, s.sort, s.updatedAt, newest, s.rules!!, s.frozen, suggested, newSinceSeen = 0)
      }
    }
  }

  private fun suggestedOf(s: State): List<MediaId> {
    val rules = s.rules ?: return emptyList()
    val raw = smart(rules).suggested.filter { it !in s.accepted && it !in s.excluded && it !in s.items }
    return library.visibleNow(raw).let { v -> raw.filter { it in v } }
  }

  private fun refresh() {
    adoptKeptCopies()
    val personal = albums.values.map { toAlbum(it) }
    val list = personal + shared?.visible?.value.orEmpty()
    all.value = AlbumList(sortList(list), listSort)
    revision.value = revision.value + 1
  }

  private fun sortList(list: List<Album>): List<Album> = when (listSort) {
    AlbumListSort.CUSTOM -> list.sortedWith(compareBy<Album>({ listOrder[it.id] ?: Long.MAX_VALUE }, { it.id.value }))
    AlbumListSort.RECENT -> list.sortedWith(compareByDescending<Album> { it.updatedAtNas }.thenBy { it.id.value })
    AlbumListSort.NAME -> list.sortedWith(compareBy<Album>({ fold(it.title) }, { it.id.value }))
    AlbumListSort.MEDIA_DATE -> list.sortedWith(compareByDescending<Album> { it.newestDay?.toEpochDay() ?: Long.MIN_VALUE }.thenBy { it.id.value })
  }

  override fun content(id: AlbumId): Flow<AlbumContent?> = revision.map { contentOf(id) }.distinctUntilChanged()

  private fun contentOf(id: AlbumId): AlbumContent? {
    val snap = library.snapshot.value ?: return null
    albums[id]?.let { s ->
      val view = dev.pampa.mycloudgallery.core.library.index.IndexOrderedTimelineView.of(snap, ordered(s, visibleMembers(s)))
      val suggested = if (s.kind == AlbumKind.SMART && !s.frozen) {
        dev.pampa.mycloudgallery.core.library.index.IndexOrderedTimelineView.of(snap, ordered(s, suggestedOf(s)))
      } else {
        null
      }
      return AlbumContent(toAlbum(s), view, suggested)
    }
    val album = shared?.visible?.value?.firstOrNull { it.id == id } ?: return null
    val items = shared.visibleItems(id)
    return AlbumContent(album, dev.pampa.mycloudgallery.core.library.index.IndexOrderedTimelineView.of(snap, items))
  }

  // --- Modifiche ----------------------------------------------------------------------------------------------------------------

  private inline fun personal(id: AlbumId, block: (State) -> EditOutcome): EditOutcome {
    val s = albums[id] ?: return if (shared?.visible?.value?.any { it.id == id } == true) EditOutcome.NotAllowed(null) else EditOutcome.NotFound
    val out = block(s)
    if (out == EditOutcome.Done) {
      s.updatedAt = stamp()
      refresh()
    }
    return out
  }

  private fun isShared(id: AlbumId): Boolean = albums[id] == null && shared?.visible?.value?.any { it.id == id } == true

  override suspend fun create(title: String, kind: AlbumKind, rules: SmartRules?): CreateOutcome {
    AlbumTitles.problem(title)?.let { return CreateOutcome.Invalid(it) }
    require((kind == AlbumKind.SMART) == (rules != null)) { "Le regole sono di un album smart, e solo sue." }
    val me = family.me() ?: return CreateOutcome.Unavailable
    val id = AlbumId.derived(me, newNonce())
    albums[id] = State(id, kind, AlbumTitles.normalized(title), AlbumSort.defaultFor(kind), rules, stamp())
    refresh()
    return CreateOutcome.Created(id)
  }

  override suspend fun rename(id: AlbumId, title: String): EditOutcome {
    if (isShared(id)) return shared!!.rename(id, title).also { refresh() }
    AlbumTitles.problem(title)?.let { return if (albums[id] == null) EditOutcome.NotFound else EditOutcome.Invalid(it) }
    return personal(id) { s ->
      s.title = AlbumTitles.normalized(title)
      EditOutcome.Done
    }
  }

  override suspend fun delete(id: AlbumId): EditOutcome {
    if (isShared(id)) return EditOutcome.NotAllowed(AlbumOp.DELETE_ALBUM)
    albums.remove(id) ?: return EditOutcome.NotFound
    deleted += id
    refresh()
    return EditOutcome.Done
  }

  override suspend fun add(id: AlbumId, media: List<MediaId>): AddOutcome {
    if (isShared(id)) {
      return when (val plan = runner.plan(ShareRequest.AddToShared(id, media))) {
        is SharePlanOutcome.Ready -> AddOutcome.NeedsShareConfirmation(plan.plan)
        is SharePlanOutcome.Blocked -> AddOutcome.Blocked(plan.reason)
        is SharePlanOutcome.NotAllowed -> AddOutcome.NotAllowed(plan.op)
        is SharePlanOutcome.NothingToShare -> AddOutcome.NothingToAdd(plan.excluded)
        is SharePlanOutcome.InvalidTitle -> AddOutcome.NothingToAdd(emptyMap())
      }
    }
    val s = albums[id] ?: return AddOutcome.NotFound
    var added = 0
    var already = 0
    for (m in media.distinct()) {
      val present = if (s.kind == AlbumKind.SMART && !s.frozen) m in members(s) else m in s.items
      if (present) {
        already++
        continue
      }
      added++
      s.excluded -= m
      if (s.kind == AlbumKind.SMART && !s.frozen) s.accepted += m else s.items[m] = Item(nextPosition(s), ++seq)
    }
    if (added > 0) {
      s.updatedAt = stamp()
      refresh()
    }
    return AddOutcome.Added(added, already)
  }

  private fun nextPosition(s: State): Long = (s.items.values.maxOfOrNull { it.position } ?: 0L) + POSITION_STEP

  override suspend fun remove(id: AlbumId, media: List<MediaId>): EditOutcome {
    if (isShared(id)) return shared!!.remove(id, media).also { refresh() }
    return personal(id) { s ->
      for (m in media) {
        s.items.remove(m)
        s.accepted -= m
        // Su un album smart togliere un elemento automatico e' un'esclusione per sempre (E-AL5).
        if (s.kind == AlbumKind.SMART) s.excluded += m
        if (s.cover == m) s.cover = null
      }
      EditOutcome.Done
    }
  }

  override suspend fun setCover(id: AlbumId, media: MediaId?): EditOutcome {
    if (isShared(id)) return shared!!.setCover(id, media).also { refresh() }
    return personal(id) { s ->
      if (media != null && media !in visibleMembers(s)) return@personal EditOutcome.Invalid(null)
      s.cover = media
      EditOutcome.Done
    }
  }

  override suspend fun setSort(id: AlbumId, sort: AlbumSort): EditOutcome {
    if (isShared(id)) return shared!!.setSort(id, sort).also { refresh() }
    return personal(id) { s ->
      s.sort = sort
      EditOutcome.Done
    }
  }

  override suspend fun reorder(id: AlbumId, moved: MediaId, after: MediaId?): EditOutcome {
    if (isShared(id)) return EditOutcome.NotAllowed(AlbumOp.SET_SORT)
    return personal(id) { s ->
      if (moved !in s.items || (after != null && after !in s.items)) return@personal EditOutcome.Invalid(null)
      val order = s.items.entries.sortedBy { it.value.position }.map { it.key }.filter { it != moved }.toMutableList()
      order.add(if (after == null) 0 else order.indexOf(after) + 1, moved)
      // Ricompattazione a intervalli (D21): nel riferimento a ogni spostamento, il vero solo quando non c'e' spazio.
      order.forEachIndexed { i, m -> s.items.getValue(m).position = (i + 1) * POSITION_STEP }
      EditOutcome.Done
    }
  }

  override suspend fun setListSort(sort: AlbumListSort) {
    listSort = sort
    refresh()
  }

  override suspend fun moveInList(id: AlbumId, after: AlbumId?): EditOutcome {
    val ids = all.value.albums.map { it.id }
    if (id !in ids || (after != null && after !in ids)) return EditOutcome.NotFound
    val order = ids.sortedBy { listOrder[it] ?: Long.MAX_VALUE }.filter { it != id }.toMutableList()
    order.add(if (after == null) 0 else order.indexOf(after) + 1, id)
    order.forEachIndexed { i, a -> listOrder[a] = (i + 1) * POSITION_STEP }
    refresh()
    return EditOutcome.Done
  }

  // --- SmartChoices ------------------------------------------------------------------------------------------------------------

  private inline fun smartAlbum(id: AlbumId, block: (State) -> Unit): EditOutcome = personal(id) { s ->
    if (s.kind != AlbumKind.SMART) return@personal EditOutcome.NotAllowed(null)
    block(s)
    EditOutcome.Done
  }

  override suspend fun exclude(id: AlbumId, media: List<MediaId>): EditOutcome = smartAlbum(id) { s ->
    media.forEach {
      s.excluded += it
      s.accepted -= it
    }
  }

  override suspend fun accept(id: AlbumId, media: List<MediaId>): EditOutcome = smartAlbum(id) { s ->
    media.forEach {
      s.accepted += it
      s.excluded -= it
    }
  }

  override suspend fun freeze(id: AlbumId): EditOutcome = smartAlbum(id) { s ->
    if (!s.frozen) {
      members(s).forEach { m -> if (m !in s.items) s.items[m] = Item(nextPosition(s), ++seq) }
      s.frozen = true
    }
  }

  override suspend fun unfreeze(id: AlbumId): EditOutcome = smartAlbum(id) { s ->
    if (s.frozen) {
      s.items.clear()
      s.frozen = false
    }
  }

  companion object {
    const val POSITION_STEP: Long = 1_000

    /** L'ordine "Nome": minuscolo, senza accenti. */
    fun fold(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").lowercase(Locale.ITALIAN)
  }
}
