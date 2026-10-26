package dev.pampa.mycloudgallery.core.albums.smart

import dev.pampa.mycloudgallery.core.albums.contract.AlbumCandidate
import dev.pampa.mycloudgallery.core.albums.contract.CandidateCursor
import dev.pampa.mycloudgallery.core.albums.contract.CandidateFilter
import dev.pampa.mycloudgallery.core.albums.contract.PlaceRule
import dev.pampa.mycloudgallery.core.albums.contract.PlaceScope
import dev.pampa.mycloudgallery.core.albums.contract.SmartPeriod
import dev.pampa.mycloudgallery.core.albums.contract.SmartRules
import dev.pampa.mycloudgallery.core.albums.contract.SmartSource
import dev.pampa.mycloudgallery.core.albums.contract.LocalDayRange
import dev.pampa.mycloudgallery.core.albums.testing.FakeAlbumLibrary
import dev.pampa.mycloudgallery.core.library.contract.HiddenMask
import dev.pampa.mycloudgallery.core.library.contract.TimelineSnapshot
import dev.pampa.mycloudgallery.core.library.contract.VisibleMedia
import dev.pampa.mycloudgallery.core.library.testing.InMemoryTimelineSnapshot
import dev.pampa.mycloudgallery.core.model.AlbumId
import dev.pampa.mycloudgallery.core.model.GeoPoint
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.search.contract.PlaceLookup
import dev.pampa.mycloudgallery.core.search.contract.PlaceName
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

// IL BANCO DEI TEST DEGLI ALBUM SMART (P6): candidati in memoria, un mondo con nascosti, le righe in memoria e qualche scorciatoia per scrivere i
// casi. Nessun dato personale: luoghi pubblici (Oslo, Roma, Firenze, Puglia...), coordinate arrotondate al centro della citta'.

/** Alcuni luoghi pubblici (centro approssimato) e i loro id GeoNames. */
object Spots {
  val OSLO = GeoPoint(59.9139, 10.7522)
  val BERGEN = GeoPoint(60.3913, 5.3221)
  val ROMA = GeoPoint(41.9028, 12.4964)
  val FIRENZE = GeoPoint(43.7696, 11.2558)
  val LECCE = GeoPoint(40.3515, 18.1750)
  val BARI = GeoPoint(41.1171, 16.8719)
  val LISBONA = GeoPoint(38.7223, -9.1393)
  val NEW_YORK = GeoPoint(40.7128, -74.0060)

  const val OSLO_ID = 3143244
  const val BERGEN_ID = 3161732
  const val ROMA_ID = 3169070
  const val FIRENZE_ID = 3176959
  const val LECCE_ID = 3174530
  const val BARI_ID = 3182351
  const val LISBONA_ID = 2267057
  const val NEW_YORK_ID = 5128581

  /** Un punto a [km] chilometri a nord di [from] (1 grado di latitudine = 111,195 km). */
  fun north(from: GeoPoint, km: Double): GeoPoint = GeoPoint(from.latitude + km / 111.195, from.longitude)

  val PLACES: Map<Int, PlaceName> = mapOf(
    OSLO_ID to PlaceName("Oslo", "Oslo", "Norvegia"),
    BERGEN_ID to PlaceName("Bergen", "Vestland", "Norvegia"),
    ROMA_ID to PlaceName("Roma", "Lazio", "Italia"),
    FIRENZE_ID to PlaceName("Firenze", "Toscana", "Italia"),
    LECCE_ID to PlaceName("Lecce", "Puglia", "Italia"),
    BARI_ID to PlaceName("Bari", "Puglia", "Italia"),
    3173435 to PlaceName("Gallipoli", "Puglia", "Italia"),
    LISBONA_ID to PlaceName("Lisbona", "Lisbona", "Portogallo"),
    NEW_YORK_ID to PlaceName("New York", "New York", "Stati Uniti"),
  )

  val LOOKUP = PlaceLookup { PLACES[it] }
}

/** Il candidato scritto in breve. [minutes] sono i minuti dalla mezzanotte **locale**; [zone] il fuso dello scatto in minuti dall'UTC. */
fun cand(
  n: Int,
  day: LocalDate,
  minutes: Int = 12 * 60,
  gps: GeoPoint? = null,
  placeId: Int = 0,
  device: String? = "phone",
  source: SmartSource = SmartSource.MY_PHONE,
  kind: MediaKind = MediaKind.PHOTO,
  screen: Boolean = false,
  zone: Int = 120,
  owned: Boolean = false,
  pixels: Long = 12_000_000,
): SmartCandidate {
  val utcMs = day.atStartOfDay().toInstant(ZoneOffset.ofTotalSeconds(zone * 60)).toEpochMilli() + minutes * 60_000L
  return SmartCandidate(
    AlbumCandidate(
      media = FakeAlbumLibrary.id(n),
      day = day,
      takenAtUtcMs = utcMs,
      localTime = LocalTime.of(minutes / 60 % 24, minutes % 60),
      gps = gps,
      placeId = placeId,
      originDevice = device,
      source = source,
      kind = kind,
      screen = screen,
      pixels = pixels,
    ),
    ownedByMe = owned,
  )
}

fun id(n: Int): MediaId = FakeAlbumLibrary.id(n)

fun albumId(n: Int): AlbumId = AlbumId(UUID(0x6_0000_0000L + n, n.toLong()).toString())

fun day(y: Int, m: Int, d: Int): LocalDate = LocalDate.of(y, m, d)

/** Un luogo `AROUND` con il raggio dato. */
fun around(center: GeoPoint, km: Int = 25, ids: List<Int> = listOf(Spots.OSLO_ID), label: String = "Luogo"): PlaceRule =
  PlaceRule(ids, center, km, label, PlaceScope.AROUND)

fun ranges(vararg r: Pair<LocalDate, LocalDate>): SmartPeriod = SmartPeriod.Ranges(r.map { LocalDayRange(it.first, it.second) })

/** I candidati in memoria, ordinati una volta (giorno, istante, id): le pagine sono fette. Per le prove grandi. */
class MemoryCandidates(items: Collection<SmartCandidate>) : SmartCandidates {
  private val sorted: List<SmartCandidate> = items.sortedWith(compareBy({ it.c.day }, { it.c.takenAtUtcMs }, { it.media.value }))
  private val byId: Map<MediaId, SmartCandidate> = sorted.associateBy { it.media }
  var pages: Int = 0
    private set

  override suspend fun page(filter: CandidateFilter, after: CandidateCursor?, limit: Int): SmartCandidatePage {
    pages++
    val from = after?.value?.toInt() ?: lowerBound(filter.days?.from)
    var i = from
    val out = ArrayList<SmartCandidate>()
    while (i < sorted.size && out.size < limit) {
      val c = sorted[i]
      if (filter.days != null && c.c.day.isAfter(filter.days.to)) {
        i = sorted.size
        break
      }
      if (c.c.source in filter.sources && (!filter.withGpsOnly || c.c.gps != null) && (filter.media == null || c.media in filter.media)) out += c
      i++
    }
    // C'e' ancora qualcosa solo se c'e' un'altra riga che passa i filtri.
    var next: CandidateCursor? = null
    var j = i
    while (j < sorted.size) {
      val c = sorted[j]
      if (filter.days != null && c.c.day.isAfter(filter.days.to)) break
      if (c.c.source in filter.sources && (!filter.withGpsOnly || c.c.gps != null) && (filter.media == null || c.media in filter.media)) {
        next = CandidateCursor(j.toString())
        break
      }
      j++
    }
    return SmartCandidatePage(out, next)
  }

  private fun lowerBound(day: LocalDate?): Int {
    if (day == null) return 0
    var lo = 0
    var hi = sorted.size
    while (lo < hi) {
      val mid = (lo + hi) ushr 1
      if (sorted[mid].c.day.isBefore(day)) lo = mid + 1 else hi = mid
    }
    return lo
  }

  override suspend fun placePage(places: Set<Int>, after: CandidateCursor?, limit: Int): SmartCandidatePage {
    val matching = sorted.filter { it.c.placeId in places && it.c.gps != null }
    val start = after?.value?.toInt() ?: 0
    val slice = matching.drop(start).take(limit)
    return SmartCandidatePage(slice, if (start + slice.size < matching.size) CandidateCursor((start + slice.size).toString()) else null)
  }

  override suspend fun candidatesOf(media: Collection<MediaId>): List<SmartCandidate> = media.mapNotNull { byId[it] }.sortedWith(compareBy({ it.c.day }, { it.c.takenAtUtcMs }, { it.media.value }))

  override suspend fun daysOf(media: Collection<MediaId>): Map<MediaId, LocalDate> = media.mapNotNull { m -> byId[m]?.let { m to it.c.day } }.toMap()

  override suspend fun visibleTotal(): Int = sorted.size
}

/**
 * Un mondo che cambia: i candidati (con i nascosti), i nascosti scelti dal test e i gruppi di cui un membro e' nascosto. E' la fonte unica di
 * [VisibleMedia], [HiddenMask] e dei candidati: come il catalogo vero, e come lui i candidati **non** conoscono il contagio dei gruppi (lo fa
 * `VisibleMedia`).
 */
class MemoryWorld(items: Collection<SmartCandidate> = emptyList(), private val lookup: Map<Int, PlaceName> = Spots.PLACES) : VisibleMedia, HiddenMask, SmartCandidates {
  private val all = LinkedHashMap<MediaId, SmartCandidate>().apply { items.forEach { put(it.media, it) } }
  val hidden = LinkedHashSet<MediaId>()

  /** I gruppi: se un membro e' nascosto, tutti i membri lo sono (per `VisibleMedia`; i candidati li hanno ancora). */
  val groups = ArrayList<Set<MediaId>>()

  val calls = intArrayOf(0)

  override val snapshot: MutableStateFlow<TimelineSnapshot?> = MutableStateFlow(null)

  private var cache: MemoryCandidates? = null

  fun put(vararg more: SmartCandidate) {
    more.forEach { all[it.media] = it }
    cache = null
  }

  fun remove(media: Collection<MediaId>) {
    media.forEach { all.remove(it) }
    cache = null
  }

  fun remove(media: MediaId) = remove(listOf(media))

  fun hide(media: Collection<MediaId>) {
    hidden += media
    cache = null
  }

  fun unhide(media: Collection<MediaId>) {
    hidden -= media.toSet()
    cache = null
  }

  fun hide(media: MediaId) = hide(listOf(media))

  fun items(): List<SmartCandidate> = all.values.toList()

  /** I candidati come li darebbe il catalogo: tutti quelli che non sono nascosti da soli (il contagio dei gruppi non c'e'). */
  fun candidates(): MemoryCandidates = cache ?: MemoryCandidates(all.values.filter { it.media !in hidden }).also { cache = it }

  override suspend fun page(filter: CandidateFilter, after: CandidateCursor?, limit: Int): SmartCandidatePage = candidates().page(filter, after, limit)

  override suspend fun placePage(places: Set<Int>, after: CandidateCursor?, limit: Int): SmartCandidatePage = candidates().placePage(places, after, limit)

  override suspend fun candidatesOf(media: Collection<MediaId>): List<SmartCandidate> = candidates().candidatesOf(media)

  /** Come il catalogo: il giorno di un media c'e' anche se e' nascosto (e' proprio il giorno da ricalcolare). */
  override suspend fun daysOf(media: Collection<MediaId>): Map<MediaId, LocalDate> = media.mapNotNull { m -> all[m]?.let { m to it.c.day } }.toMap()

  override suspend fun visibleTotal(): Int = candidates().visibleTotal()

  private fun hiddenWithGroups(): Set<MediaId> = hidden + groups.filter { g -> g.any { it in hidden } }.flatten()

  override suspend fun visible(ids: Collection<MediaId>): Set<MediaId> {
    val bad = hiddenWithGroups()
    return ids.filterTo(HashSet()) { it in all && it !in bad }
  }

  override suspend fun hiddenAmong(ids: Collection<MediaId>): Set<MediaId> {
    calls[0]++
    val bad = hiddenWithGroups()
    return ids.filterTo(HashSet()) { it !in all || it in bad }
  }

  /** Costruisce l'istantanea (per l'interprete: i luoghi dei media visibili). */
  fun publish() {
    val bad = hiddenWithGroups()
    val cells = all.values.filter { it.media !in bad }.sortedByDescending { it.c.takenAtUtcMs }
    val n = cells.size
    val uuids = cells.map { UUID.fromString(it.media.value) }
    snapshot.value = InMemoryTimelineSnapshot(
      seq = 1,
      idMsb = LongArray(n) { uuids[it].mostSignificantBits },
      idLsb = LongArray(n) { uuids[it].leastSignificantBits },
      sortTime = LongArray(n) { cells[it].c.takenAtUtcMs },
      localDay = IntArray(n) { cells[it].c.day.let { d -> d.year * 10_000 + d.monthValue * 100 + d.dayOfMonth } },
      kinds = ByteArray(n) { cells[it].c.kind.ordinal.toByte() },
      sources = ByteArray(n),
      flags = IntArray(n),
      aspectMilli = IntArray(n) { 1_000 },
      durationMs = LongArray(n),
      place = IntArray(n) { cells[it].c.placeId },
      groupSize = IntArray(n) { 1 },
    )
  }

  val placeLookup: PlaceLookup get() = PlaceLookup { lookup[it] }
}

/** Le righe in memoria: lo stesso comportamento di [RoomSmartRows] (mai una riga dell'utente, le righe del motore sovrascritte). */
class InMemorySmartRows : SmartRows {
  class Album(var rules: SmartRules, var frozen: Boolean = false) {
    val exclusions = LinkedHashSet<MediaId>()
    val owned = LinkedHashSet<MediaId>()
    val auto = LinkedHashMap<MediaId, AutoRow>()
    val suggestions = LinkedHashMap<MediaId, SuggestionRow>()
  }

  val albums = LinkedHashMap<AlbumId, Album>()
  var applies = 0
    private set

  fun create(id: AlbumId, rules: SmartRules): Album = Album(rules).also { albums[id] = it }

  override suspend fun smartAlbums(): List<AlbumId> = albums.keys.toList()

  override suspend fun state(id: AlbumId): SmartAlbumState? = albums[id]?.let {
    SmartAlbumState(id, it.rules, it.frozen, it.exclusions.toSet(), it.owned.toSet(), LinkedHashMap(it.auto), LinkedHashMap(it.suggestions))
  }

  override suspend fun apply(id: AlbumId, change: SmartRowsChange) {
    val album = albums[id] ?: return
    if (!change.isEmpty) applies++
    change.removeAuto.forEach { album.auto.remove(it) }
    for ((media, row) in change.putAuto) if (media !in album.owned) album.auto[media] = row
    change.removeSuggestions.forEach { album.suggestions.remove(it) }
    for ((media, row) in change.putSuggestions) if (media !in album.owned) album.suggestions[media] = row
  }

  /** Cio' che l'album ha adesso, per confronti: sicuri e suggeriti con la regola. */
  fun snapshot(id: AlbumId): Pair<Map<MediaId, AutoRow>, Map<MediaId, SuggestionRow>> = albums.getValue(id).let { LinkedHashMap(it.auto) to LinkedHashMap(it.suggestions) }
}

/** La ricerca finta: le classi le sceglie il test, per media (indipendenti dall'insieme che cerca: una soglia fissa). */
class ScriptedContent(override var ready: Boolean = true) : SmartContentSearch {
  var sure: Set<MediaId> = emptySet()
  var likely: Set<MediaId> = emptySet()
  var calls = 0
    private set

  override suspend fun match(text: String?, examples: List<MediaId>, period: SmartPeriod?): ContentMatches {
    calls++
    return if (!ready) ContentMatches.NONE else ContentMatches(sure, likely - sure, used = true)
  }
}
