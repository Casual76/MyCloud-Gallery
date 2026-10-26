package dev.pampa.mycloudgallery.debugtools.eval.albums

import androidx.compose.ui.graphics.ImageBitmap
import dev.pampa.mycloudgallery.core.albums.contract.AlbumCandidate
import dev.pampa.mycloudgallery.core.albums.contract.CandidateCursor
import dev.pampa.mycloudgallery.core.albums.contract.CandidateFilter
import dev.pampa.mycloudgallery.core.albums.contract.SmartSource
import dev.pampa.mycloudgallery.core.albums.smart.DeviceNormalizer
import dev.pampa.mycloudgallery.core.albums.smart.NoSmartContentSearch
import dev.pampa.mycloudgallery.core.albums.smart.SmartCandidate
import dev.pampa.mycloudgallery.core.albums.smart.SmartCandidatePage
import dev.pampa.mycloudgallery.core.albums.smart.SmartCandidates
import dev.pampa.mycloudgallery.core.albums.testing.FakeAlbumClocks
import dev.pampa.mycloudgallery.core.albums.testing.FakeAlbumLibrary
import dev.pampa.mycloudgallery.core.library.contract.HiddenMask
import dev.pampa.mycloudgallery.core.library.contract.TimelineSnapshot
import dev.pampa.mycloudgallery.core.library.contract.VisibleMedia
import dev.pampa.mycloudgallery.core.library.testing.InMemoryTimelineSnapshot
import dev.pampa.mycloudgallery.core.model.GeoPoint
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.search.contract.PlaceLookup
import dev.pampa.mycloudgallery.core.search.contract.PlaceName
import dev.pampa.mycloudgallery.core.search.parse.ItalianQueryParser
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Il banco di prova dello strumento di valutazione degli album: una Libreria sintetica in memoria (Oslo e Firenze, 2025), la visibilita' dei nascosti, il motore
 * vero degli album smart sopra di lei (stesse porte di [AlbumsEvalPorts]) e le proposte dei viaggi scritte dal test. Nessun dato personale, nessuna foto vera.
 * Gli id sono quelli di `FakeAlbumLibrary.id(n)`.
 */
internal class AlbumsTestRig : AlbumsEvalEnvironment, VisibleMedia, HiddenMask, SmartCandidates {
  private val all = LinkedHashMap<MediaId, SmartCandidate>()
  val hidden = LinkedHashSet<MediaId>()
  val files = HashMap<String, MediaId>()

  /** Le proposte che `tripProposals` risponde (il test le sceglie). */
  var proposals: TripsView = TripsView(emptyList(), homeKnown = true)

  var tripProposalCalls = 0
    private set

  override val snapshot = MutableStateFlow<TimelineSnapshot?>(null)

  override val today: LocalDate = LocalDate.of(2026, 1, 15)

  fun id(n: Int): MediaId = FakeAlbumLibrary.id(n)

  /** Un media: `minutes` dalla mezzanotte locale; col GPS a Oslo ([osloKm] a nord del centro) o Firenze. */
  fun put(
    n: Int,
    day: LocalDate,
    minutes: Int = 12 * 60,
    gps: GeoPoint? = null,
    place: Int = 0,
    device: String? = "phone",
    source: SmartSource = SmartSource.MY_PHONE,
    screen: Boolean = false,
  ): MediaId {
    val utc = day.atStartOfDay().toInstant(ZoneOffset.ofTotalSeconds(120 * 60)).toEpochMilli() + minutes * 60_000L
    val media = id(n)
    all[media] = SmartCandidate(
      AlbumCandidate(media, day, utc, LocalTime.of(minutes / 60 % 24, minutes % 60), gps, if (gps == null) 0 else place, device, source, MediaKind.PHOTO, screen, 12_000_000),
      ownedByMe = false,
    )
    return media
  }

  fun osloPhotos(from: Int, day: LocalDate, count: Int = 4): List<MediaId> =
    (0 until count).map { k -> put(from + k, day, 9 * 60 + k * 60, GeoPoint(OSLO.latitude + (1.0 + k) / 111.195, OSLO.longitude), OSLO_ID) }

  /** Dopo aver messo i media: costruisce l'istantanea della Libreria (l'interprete ne ricava i luoghi). */
  fun publish() {
    val cells = all.values.filter { it.media !in hidden }.sortedByDescending { it.c.takenAtUtcMs }
    val uuids = cells.map { UUID.fromString(it.media.value) }
    val n = cells.size
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

  // --- AlbumsEvalEnvironment ---------------------------------------------------------------------------------------------------------

  override suspend fun ports(): AlbumsEvalPorts = AlbumsEvalPorts(
    candidates = this,
    visible = this,
    mask = this,
    parser = ItalianQueryParser(),
    lookup = PlaceLookup { if (it == OSLO_ID) PlaceName("Oslo", "Oslo", "Norvegia") else if (it == FIRENZE_ID) PlaceName("Firenze", "Toscana", "Italia") else null },
    search = NoSmartContentSearch,
    devices = DeviceNormalizer(null),
    clocks = FakeAlbumClocks(),
    geocoder = null,
    today = { today },
  )

  override suspend fun cellsOfDays(from: LocalDate, to: LocalDate): List<AlbumsEvalCell> =
    cells(all.values.filter { !it.c.day.isBefore(from) && !it.c.day.isAfter(to) })

  override suspend fun cellsOf(ids: Collection<String>): List<AlbumsEvalCell> = cells(all.values.filter { it.media.value in ids })

  private fun cells(items: List<SmartCandidate>): List<AlbumsEvalCell> =
    items.filter { it.media !in hidden }.sortedWith(compareBy({ it.c.day }, { it.c.takenAtUtcMs }, { it.media.value })).map { AlbumsEvalCell(it.media.value, it.c.day) }

  override suspend fun thumbnail(id: MediaId): ImageBitmap? = null

  override suspend fun tripProposals(): TripsView {
    tripProposalCalls++
    return proposals
  }

  override suspend fun mediaByFileName(names: Set<String>): Map<String, MediaId> = names.mapNotNull { n -> files[n]?.let { n to it } }.toMap()

  // --- La Libreria: visibilita' e candidati ------------------------------------------------------------------------------------------

  override suspend fun visible(ids: Collection<MediaId>): Set<MediaId> = ids.filterTo(HashSet()) { it in all && it !in hidden }

  override suspend fun hiddenAmong(ids: Collection<MediaId>): Set<MediaId> = ids.filterTo(HashSet()) { it !in all || it in hidden }

  private fun sorted(): List<SmartCandidate> =
    all.values.filter { it.media !in hidden }.sortedWith(compareBy({ it.c.day }, { it.c.takenAtUtcMs }, { it.media.value }))

  override suspend fun page(filter: CandidateFilter, after: CandidateCursor?, limit: Int): SmartCandidatePage {
    val days = filter.days
    val wanted = filter.media
    val matching = sorted().filter { c ->
      c.c.source in filter.sources && (!filter.withGpsOnly || c.c.gps != null) && (days == null || c.c.day in days) && (wanted == null || c.media in wanted)
    }
    val start = after?.value?.toInt() ?: 0
    val slice = matching.drop(start).take(limit)
    return SmartCandidatePage(slice, if (start + slice.size < matching.size) CandidateCursor((start + slice.size).toString()) else null)
  }

  override suspend fun placePage(places: Set<Int>, after: CandidateCursor?, limit: Int): SmartCandidatePage {
    val matching = sorted().filter { it.c.placeId in places && it.c.gps != null }
    val start = after?.value?.toInt() ?: 0
    val slice = matching.drop(start).take(limit)
    return SmartCandidatePage(slice, if (start + slice.size < matching.size) CandidateCursor((start + slice.size).toString()) else null)
  }

  override suspend fun candidatesOf(media: Collection<MediaId>): List<SmartCandidate> = sorted().filter { it.media in media }

  override suspend fun daysOf(media: Collection<MediaId>): Map<MediaId, LocalDate> = media.mapNotNull { m -> all[m]?.let { m to it.c.day } }.toMap()

  override suspend fun visibleTotal(): Int = sorted().size

  companion object {
    val OSLO = GeoPoint(59.9139, 10.7522)
    val FIRENZE = GeoPoint(43.7696, 11.2558)
    const val OSLO_ID = 3143244
    const val FIRENZE_ID = 3176959

    fun day(m: Int, d: Int): LocalDate = LocalDate.of(2025, m, d)

    /** Tre giorni a Oslo (14, 15, 16 marzo), quattro foto al giorno; uno screenshot e un familiare il 15; due foto di casa a Firenze fuori viaggio. */
    fun osloTrip(): AlbumsTestRig = AlbumsTestRig().also { rig ->
      rig.osloPhotos(1, day(3, 14))
      rig.osloPhotos(5, day(3, 15))
      rig.osloPhotos(9, day(3, 16))
      rig.put(100, day(3, 15), 11 * 60, screen = true)
      rig.put(101, day(3, 15), 13 * 60, GeoPoint(OSLO.latitude + 2.0 / 111.195, OSLO.longitude), OSLO_ID, device = null, source = SmartSource.FAMILY)
      rig.put(200, day(3, 2), 20 * 60, FIRENZE, FIRENZE_ID)
      rig.put(201, day(3, 3), 20 * 60, FIRENZE, FIRENZE_ID)
      rig.publish()
    }
  }
}
