package dev.pampa.mycloudgallery.core.widget.testing

import dev.pampa.mycloudgallery.core.library.testing.FakeVisibility
import dev.pampa.mycloudgallery.core.library.testing.InMemoryTimelineSnapshot
import dev.pampa.mycloudgallery.core.memories.contract.MemoryIds
import dev.pampa.mycloudgallery.core.memories.contract.MemoryKey
import dev.pampa.mycloudgallery.core.memories.contract.MemoryKind
import dev.pampa.mycloudgallery.core.model.CellFlags
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.model.MediaSource
import dev.pampa.mycloudgallery.core.widget.contract.WidgetMemoryPick
import dev.pampa.mycloudgallery.core.widget.contract.WidgetTitle
import java.time.LocalDate
import java.util.UUID
import kotlin.random.Random

/**
 * Un mondo casuale per il widget (§5.1 "Libreria sintetica", §5.2): un'istantanea di [count] celle (foto, video, foto animate, screenshot,
 * documenti), una visibilita' con nascosti **fuori** dall'istantanea e la possibilita' di nascondere "all'ultimo momento" ([hideLate]: la
 * maschera li vede, l'istantanea no), miniature locali per una parte, presenza sul telefono per un'altra, e un ricordo di oggi (a volte
 * `PERSON`, a volte con pochi elementi). Deterministico dal [seed]. Lo usano la suite `WidgetPlannerContract` e le proprieta' di P2.
 */
class SyntheticWidgetWorld(
  val seed: Long,
  val count: Int = 120,
  thumbPercent: Int = 70,
  phonePercent: Int = 50,
  memoryPercent: Int = 60,
) {
  private val random = Random(seed)

  val ids: List<MediaId>
  val snapshot: InMemoryTimelineSnapshot
  val visibility: FakeVisibility
  val thumbs: FakeWidgetThumbs
  val lock: FakeWidgetLock
  val memories: FakeWidgetMemories
  val prefs: FakeWidgetPrefs = FakeWidgetPrefs()

  /** I media che il widget non deve mai mostrare per tipo o sorgente (screenshot, documenti, animate). */
  val excluded: Set<MediaId>

  init {
    val msb = LongArray(count) { random.nextLong() }
    val lsb = LongArray(count) { random.nextLong() }
    ids = List(count) { MediaId(UUID(msb[it], lsb[it]).toString()) }
    val kinds = ByteArray(count)
    val sources = ByteArray(count)
    val flags = IntArray(count)
    val days = IntArray(count)
    val excludedIds = LinkedHashSet<MediaId>()
    val base = LocalDate.of(2026, 10, 31)
    for (i in 0 until count) {
      val roll = random.nextInt(100)
      val kind = when {
        roll < 70 -> MediaKind.PHOTO
        roll < 85 -> MediaKind.VIDEO
        roll < 92 -> MediaKind.MOTION_PHOTO
        else -> MediaKind.ANIMATED_IMAGE
      }
      kinds[i] = kind.ordinal.toByte()
      val source = if (random.nextInt(100) < 10) MediaSource.SCREENSHOT else MediaSource.CAMERA
      sources[i] = source.ordinal.toByte()
      flags[i] = if (random.nextInt(100) < 5) CellFlags.DOCUMENT else 0
      val day = base.minusDays(i.toLong())
      days[i] = day.year * 10_000 + day.monthValue * 100 + day.dayOfMonth
      if (kind == MediaKind.ANIMATED_IMAGE || source == MediaSource.SCREENSHOT || flags[i] != 0) excludedIds += ids[i]
    }
    excluded = excludedIds
    snapshot = InMemoryTimelineSnapshot(
      seq = 1, idMsb = msb, idLsb = lsb, sortTime = LongArray(count) { (count - it) * 60_000L }, localDay = days, kinds = kinds,
      sources = sources, flags = flags, aspectMilli = IntArray(count) { 1000 }, durationMs = LongArray(count), place = IntArray(count),
      groupSize = IntArray(count) { 1 },
    )
    val outside = List(3) { MediaId.random() }
    visibility = FakeVisibility(snapshot = snapshot, present = (ids + outside).toSet(), hidden = outside.toSet())
    thumbs = FakeWidgetThumbs(ids.filter { random.nextInt(100) < thumbPercent }.toSet())
    lock = FakeWidgetLock(onPhone = ids.filter { random.nextInt(100) < phonePercent }.toSet())
    memories = FakeWidgetMemories(if (random.nextInt(100) < memoryPercent) randomMemory() else null)
  }

  private fun randomMemory(): WidgetMemoryPick {
    val size = 3 + random.nextInt(20)
    val members = ids.shuffled(random).take(size.coerceAtMost(count))
    val kind = MemoryKind.entries[random.nextInt(MemoryKind.entries.size)]
    val key = MemoryKey.Month(java.time.YearMonth.of(2025, 1 + random.nextInt(12)))
    return WidgetMemoryPick(
      id = MemoryIds.of(key), kind = kind, title = WidgetTitle(yearsAgo = 1 + random.nextInt(5), placeIds = listOf(1 + random.nextInt(1000)),
        tripLabel = null, month = null),
      cover = members.first(), members = members,
    )
  }

  /** Nasconde "all'ultimo momento" [n] media dell'istantanea (anche fra quelli mostrabili): la maschera li vede, l'istantanea no (E-W2, E-W3). */
  fun hideLate(n: Int): Set<MediaId> {
    val chosen = ids.shuffled(random).take(n).toSet()
    visibility.hidden += chosen
    return chosen
  }

  /**
   * Fa [n] gruppi di 2-4 media dell'istantanea (raffiche): nella visibilita' il nascosto e' **contagioso** (un membro di un gruppo con un
   * nascosto e' nascosto, F5-D36). Si chiama dopo la nascita del mondo: non sposta il flusso casuale di cio' che il mondo genera da solo.
   */
  fun addGroups(n: Int): List<Set<MediaId>> {
    if (count == 0) return emptyList()
    val made = List(n) { List(2 + random.nextInt(3)) { ids[random.nextInt(count)] }.toSet() }
    visibility.groups += made
    return made
  }

  /** Tutti i nascosti adesso: quelli fuori dall'istantanea, quelli dell'ultimo momento e i compagni di gruppo di un nascosto. */
  val hiddenNow: Set<MediaId>
    get() {
      val hidden = visibility.hidden
      val all = hidden.toMutableSet()
      for (group in visibility.groups) if (group.any { it in hidden }) all += group
      return all
    }

  fun planner(): ReferenceWidgetPlanner = ReferenceWidgetPlanner(visibility, visibility, memories, thumbs, lock, prefs)
}
