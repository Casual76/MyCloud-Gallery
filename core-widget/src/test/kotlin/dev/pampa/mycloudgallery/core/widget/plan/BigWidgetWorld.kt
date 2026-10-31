package dev.pampa.mycloudgallery.core.widget.plan

import dev.pampa.mycloudgallery.core.library.contract.HiddenMask
import dev.pampa.mycloudgallery.core.library.contract.VisibleMedia
import dev.pampa.mycloudgallery.core.library.testing.FakeVisibility
import dev.pampa.mycloudgallery.core.library.testing.NasMix
import dev.pampa.mycloudgallery.core.library.testing.SyntheticLibrary
import dev.pampa.mycloudgallery.core.memories.contract.MemoryIds
import dev.pampa.mycloudgallery.core.memories.contract.MemoryKey
import dev.pampa.mycloudgallery.core.memories.contract.MemoryKind
import dev.pampa.mycloudgallery.core.model.CellFlags
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.model.MediaSource
import dev.pampa.mycloudgallery.core.widget.contract.WidgetMemoryPick
import dev.pampa.mycloudgallery.core.widget.contract.WidgetTitle
import dev.pampa.mycloudgallery.core.widget.testing.FakeWidgetLock
import dev.pampa.mycloudgallery.core.widget.testing.FakeWidgetMemories
import dev.pampa.mycloudgallery.core.widget.testing.FakeWidgetPrefs
import dev.pampa.mycloudgallery.core.widget.testing.FakeWidgetThumbs
import java.time.YearMonth
import kotlin.random.Random

/**
 * Il "mondo casuale" di P2 sulla **Libreria sintetica da 100 mila celle** (`SyntheticLibrary`, composizione del §5.2: screenshot 10%, animate,
 * raffiche e coppie RAW+JPEG con i loro membri fuori dall'indice, 0,5% di nascosti fuori dall'indice, 60% solo sul NAS). In piu': i **gruppi**
 * con il nascosto contagioso, le miniature locali (tutto cio' che e' sul telefono, e il 20% del resto: replica scaricata), la presenza sul
 * telefono, un ricordo di oggi e i nascosti "dell'ultimo momento". Deterministico dal [seed]; costa un paio di secondi, quindi una volta per classe.
 */
internal class BigWidgetWorld(val seed: Long = 20_261_031L) {
  val library = SyntheticLibrary(seed = seed, count = SyntheticLibrary.FULL_COUNT, mix = NasMix.BUDGET)
  val snapshot = library.snapshot
  val ids: List<MediaId> = List(snapshot.size) { snapshot.idAt(it) }

  /** Screenshot, documenti e immagini animate: il widget non li mostra mai. */
  val excluded: Set<MediaId>

  /** Cio' che e' anche sul telefono (D8, Q3 (c)). */
  val onPhone: Set<MediaId>

  val visibility: FakeVisibility
  val thumbs: FakeWidgetThumbs
  val lock: FakeWidgetLock
  val memories: FakeWidgetMemories = FakeWidgetMemories()
  val prefs: FakeWidgetPrefs = FakeWidgetPrefs()

  /** I nascosti fuori dall'istantanea (0,5%): la Libreria non li ha, il catalogo si'. */
  val hiddenOutside: Set<MediaId>

  /** I gruppi: la copertina (nell'istantanea) e i membri (fuori). Con un nascosto, il gruppo intero e' nascosto. */
  val groups: List<Set<MediaId>>

  init {
    val random = Random(seed)
    val excludedIds = HashSet<MediaId>()
    val phone = HashSet<MediaId>()
    val local = HashSet<MediaId>()
    for (i in 0 until snapshot.size) {
      val id = ids[i]
      if (snapshot.kindAt(i) == MediaKind.ANIMATED_IMAGE || snapshot.sourceAt(i) == MediaSource.SCREENSHOT ||
        CellFlags.has(snapshot.flagsAt(i), CellFlags.DOCUMENT)
      ) {
        excludedIds += id
      }
      if (CellFlags.has(snapshot.flagsAt(i), CellFlags.ON_PHONE)) {
        phone += id
        local += id
      } else if (random.nextInt(100) < 20) {
        local += id
      }
    }
    excluded = excludedIds
    onPhone = phone
    hiddenOutside = library.hidden.mapTo(HashSet()) { it.id }

    val byGroup = HashMap<MediaId, MutableSet<MediaId>>()
    for (i in 0 until snapshot.size) {
      if (snapshot.groupSizeAt(i) > 1) library.cellAt(i).groupId?.let { byGroup.getOrPut(it) { HashSet() } += ids[i] }
    }
    val memberIds = HashSet<MediaId>()
    for (member in library.groupMembers) {
      member.groupId?.let { byGroup.getOrPut(it) { HashSet() } += member.id }
      memberIds += member.id
    }
    groups = byGroup.values.map { it.toSet() }

    visibility = FakeVisibility(
      snapshot = snapshot, present = HashSet<MediaId>(ids).apply { addAll(hiddenOutside); addAll(memberIds) }, hidden = hiddenOutside, groups = groups,
    )
    thumbs = FakeWidgetThumbs(local)
    lock = FakeWidgetLock(onPhone = phone)
  }

  /** Un ricordo di oggi di [size] elementi non esclusi, della specie [kind]. */
  fun memoryOf(random: Random, kind: MemoryKind, size: Int): WidgetMemoryPick {
    val members = LinkedHashSet<MediaId>()
    while (members.size < size) ids[random.nextInt(ids.size)].let { if (it !in excluded) members += it }
    val list = members.toList()
    return WidgetMemoryPick(
      id = MemoryIds.of(MemoryKey.Month(YearMonth.of(2025, 1 + random.nextInt(12)))),
      kind = kind,
      title = WidgetTitle(yearsAgo = 1 + random.nextInt(5), placeIds = listOf(1 + random.nextInt(900)), tripLabel = null, month = null),
      cover = list[random.nextInt(list.size)],
      members = list,
    )
  }

  /** Tutti i nascosti adesso: quelli messi a mano, i nascosti fuori dall'istantanea e i compagni di gruppo di un nascosto. */
  fun hiddenNow(extra: Set<MediaId> = emptySet()): Set<MediaId> {
    val all = HashSet<MediaId>(visibility.hidden)
    all += extra
    for (group in groups) if (group.any { it in all }) all += group
    return all
  }

  /** La visibilita' con i nascosti **dell'ultimo momento** [late]: chi legge senza lanciare (il feed) non li vede ancora, il filtro finale si'. */
  fun racing(late: () -> Set<MediaId>): Pair<VisibleMedia, HiddenMask> {
    val base = visibility
    val visible = object : VisibleMedia by base {
      override suspend fun visibleOrThrow(ids: Collection<MediaId>): Set<MediaId> = base.visible(ids) - hiddenWith(late, ids)
    }
    val mask = object : HiddenMask {
      override suspend fun hiddenAmong(ids: Collection<MediaId>): Set<MediaId> = base.hiddenAmong(ids)

      override suspend fun hiddenAmongOrThrow(ids: Collection<MediaId>): Set<MediaId> = base.hiddenAmong(ids) + hiddenWith(late, ids)
    }
    return visible to mask
  }

  private fun hiddenWith(late: () -> Set<MediaId>, ids: Collection<MediaId>): Set<MediaId> {
    val all = hiddenNow(late())
    return ids.filterTo(HashSet()) { it in all }
  }
}
