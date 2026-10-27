package dev.pampa.mycloudgallery.core.albums.testing

import dev.pampa.mycloudgallery.core.albums.contract.AlbumCandidate
import dev.pampa.mycloudgallery.core.albums.contract.AlbumCandidates
import dev.pampa.mycloudgallery.core.albums.contract.AttestRequest
import dev.pampa.mycloudgallery.core.albums.contract.CandidateCursor
import dev.pampa.mycloudgallery.core.albums.contract.CandidateFilter
import dev.pampa.mycloudgallery.core.albums.contract.CandidatePage
import dev.pampa.mycloudgallery.core.albums.contract.CatalogFacts
import dev.pampa.mycloudgallery.core.albums.contract.MediaFacts
import dev.pampa.mycloudgallery.core.albums.contract.MediaPlace
import dev.pampa.mycloudgallery.core.albums.contract.NasItemRef
import dev.pampa.mycloudgallery.core.albums.contract.SmartSource
import dev.pampa.mycloudgallery.core.library.contract.HiddenMask
import dev.pampa.mycloudgallery.core.library.contract.TimelineSnapshot
import dev.pampa.mycloudgallery.core.library.contract.VisibleMedia
import dev.pampa.mycloudgallery.core.library.testing.InMemoryTimelineSnapshot
import dev.pampa.mycloudgallery.core.model.ContentKey
import dev.pampa.mycloudgallery.core.model.GeoPoint
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.model.MediaSource
import dev.pampa.mycloudgallery.core.model.QuickHash
import dev.pampa.mycloudgallery.core.model.Sha256
import dev.pampa.mycloudgallery.core.model.VersionId
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Un media della Libreria finta degli album (Fase 7, P0). [place] e' dove sta per la condivisione; [onPhone] se il telefono ne ha una
 * copia; [path] il file sul NAS (area privata o comune) o null; [hidden] nascosto (mai in nessuna superficie, D22). Date nell'ora del
 * posto dello scatto ([zoneOffsetMinutes]), come il catalogo vero (D36).
 */
data class FakeMedia(
  val id: MediaId,
  val takenAtUtcMs: Long,
  val zoneOffsetMinutes: Int = 60,
  val kind: MediaKind = MediaKind.PHOTO,
  val mediaSource: MediaSource = MediaSource.CAMERA,
  val place: MediaPlace = MediaPlace.MY_PRIVATE,
  val onPhone: Boolean = place == MediaPlace.PHONE_ONLY,
  val mine: Boolean = place != MediaPlace.OTHERS_PRIVATE,
  val path: String? = if (place == MediaPlace.PHONE_ONLY) null else "Foto/${id.value}.jpg",
  val gps: GeoPoint? = null,
  val placeId: Int = 0,
  val originDevice: String? = "questo-telefono",
  val size: Long = 1_000_000,
  val pixels: Long = 12_000_000,
  val hidden: Boolean = false,
  val version: VersionId? = null,
  /** L'hash completo, se il catalogo finto lo "conosce" (I1: la voce lo porta, l'attestazione lo confronta come il catalogo vero). */
  val sha256: Sha256? = null,
) {
  init {
    require((place == MediaPlace.PHONE_ONLY) == (path == null)) { "Un media sul NAS ha un percorso, uno solo sul telefono no." }
  }

  val localDateTime: java.time.LocalDateTime
    get() = java.time.LocalDateTime.ofInstant(Instant.ofEpochMilli(takenAtUtcMs), ZoneOffset.ofTotalSeconds(zoneOffsetMinutes * 60))

  val day: LocalDate get() = localDateTime.toLocalDate()

  /** La chiave di contenuto (deterministica dall'id e dalla dimensione: i file finti non hanno byte). */
  val contentKey: ContentKey get() = ContentKey.of(QuickHash(sha(id.value)), size)

  /** La sorgente per gli album smart e i viaggi (§1.2 `SmartSource`). */
  val smartSource: SmartSource
    get() = when {
      place == MediaPlace.OTHERS_PRIVATE -> SmartSource.FAMILY
      place == MediaPlace.COMMON && !mine -> SmartSource.FAMILY
      onPhone && mine -> SmartSource.MY_PHONE
      place == MediaPlace.COMMON -> SmartSource.COMMON
      else -> SmartSource.MY_PRIVATE
    }

  override fun toString(): String = "FakeMedia(${place.code}, hidden=$hidden)"

  private companion object {
    fun sha(text: String): String = java.security.MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
      .joinToString("") { "%02x".format(it.toInt() and 0xff) }
  }
}

/**
 * La Libreria finta degli album (Fase 7, P0): **una sola fonte** per visibilita' ([VisibleMedia], [HiddenMask]), fatti del catalogo
 * ([CatalogFacts]) e candidati ([AlbumCandidates]), come il catalogo vero. L'istantanea non ha i nascosti (E-H1) e ha una cella per media.
 * [move] simula la porta di completamento (D29): lo **stesso** `MediaId` cambia posto. Chi la usa si registra con [addListener] per sapere
 * quando cambia (gli esecutori di riferimento ricalcolano subito).
 */
class FakeAlbumLibrary(
  private val privateShare: String = "test",
  private val commonShare: String = "test-comune",
) : VisibleMedia, HiddenMask, CatalogFacts, AlbumCandidates {

  private val media = LinkedHashMap<MediaId, FakeMedia>()
  private val listeners = mutableListOf<() -> Unit>()
  private var seq = 0L

  override val snapshot: MutableStateFlow<TimelineSnapshot?> = MutableStateFlow(build())

  /** Il registro delle chiamate al filtro finale: una superficie che non lo usa si vede. */
  var maskCalls: Int = 0
    private set

  fun addListener(listener: () -> Unit) {
    listeners += listener
  }

  fun all(): List<FakeMedia> = media.values.toList()

  operator fun get(id: MediaId): FakeMedia? = media[id]

  fun put(vararg items: FakeMedia) {
    items.forEach { media[it.id] = it }
    changed()
  }

  fun hide(id: MediaId) = update(id) { it.copy(hidden = true) }

  fun unhide(id: MediaId) = update(id) { it.copy(hidden = false) }

  /** Il media esce dal catalogo (cancellato ovunque): gli album lo tengono come elemento, non lo mostrano. */
  fun remove(id: MediaId) {
    media.remove(id)
    changed()
  }

  /** La porta di completamento (D29): stesso media, nuovo posto e nuovo percorso. */
  fun move(id: MediaId, place: MediaPlace, path: String?, mine: Boolean? = null) = update(id) { it.copy(place = place, path = path, mine = mine ?: it.mine) }

  fun update(id: MediaId, change: (FakeMedia) -> FakeMedia) {
    val current = media[id] ?: return
    media[id] = change(current)
    changed()
  }

  fun nasRefOf(m: FakeMedia): NasItemRef? = m.path?.let { NasItemRef(shareOf(m.place), SharePath.parse(it)) }

  fun shareOf(place: MediaPlace): String = when (place) {
    MediaPlace.COMMON -> commonShare
    MediaPlace.OTHERS_PRIVATE -> "altri"
    else -> privateShare
  }

  /** Come [visible], senza sospendere: per gli esecutori di riferimento che ricalcolano nel listener. */
  fun visibleNow(ids: Collection<MediaId>): Set<MediaId> = ids.filter { media[it]?.hidden == false }.toSet()

  /** Come [attest], senza sospendere. */
  fun attestedNow(keys: Collection<ContentKey>): Map<ContentKey, MediaId> {
    val inCommon = media.values.filter { it.place == MediaPlace.COMMON && it.path != null }.associateBy { it.contentKey }
    return keys.mapNotNull { key -> inCommon[key]?.let { key to it.id } }.toMap()
  }

  // --- VisibleMedia, HiddenMask -------------------------------------------------------------------------------------------

  override suspend fun visible(ids: Collection<MediaId>): Set<MediaId> = ids.filter { media[it]?.hidden == false }.toSet()

  override suspend fun hiddenAmong(ids: Collection<MediaId>): Set<MediaId> {
    maskCalls++
    return ids.filter { media[it]?.hidden != false }.toSet()
  }

  // --- CatalogFacts ---------------------------------------------------------------------------------------------------------

  override suspend fun facts(media: Collection<MediaId>): Map<MediaId, MediaFacts> = media.mapNotNull { id ->
    val m = this.media[id] ?: return@mapNotNull null
    id to MediaFacts(id, m.place, m.mine, m.hidden, m.kind == MediaKind.VIDEO, m.size, m.contentKey, m.version, nasRefOf(m), m.onPhone, m.sha256)
  }.toMap()

  /** Come il catalogo vero (`AlbumCatalog.attestInCommon`): con l'hash completo noto da entrambe le parti, deve essere lo stesso (I1). */
  override suspend fun attest(items: Collection<AttestRequest>): Map<ContentKey, MediaId> {
    val found = attestedNow(items.map { it.contentKey })
    return found.filter { (key, id) ->
      val full = media[id]?.sha256
      items.filter { it.contentKey == key }.any { it.sha256 == null || full == null || it.sha256 == full }
    }
  }

  // --- AlbumCandidates ------------------------------------------------------------------------------------------------------

  override suspend fun page(filter: CandidateFilter, after: CandidateCursor?, limit: Int): CandidatePage {
    val ordered = media.values.asSequence()
      .filter { !it.hidden }
      .filter { filter.days == null || it.day in filter.days }
      .filter { it.smartSource in filter.sources }
      .filter { !filter.withGpsOnly || it.gps != null }
      .filter { filter.media == null || it.id in filter.media }
      .sortedWith(compareBy<FakeMedia>({ it.day }, { it.takenAtUtcMs }, { it.id.value }))
      .toList()
    val start = after?.value?.toInt() ?: 0
    val slice = ordered.drop(start).take(limit.coerceAtLeast(1))
    val next = if (start + slice.size < ordered.size) CandidateCursor((start + slice.size).toString()) else null
    return CandidatePage(slice.map { it.toCandidate() }, next)
  }

  override suspend fun visibleTotal(): Int = media.values.count { !it.hidden }

  private fun FakeMedia.toCandidate(): AlbumCandidate = AlbumCandidate(
    media = id,
    day = day,
    takenAtUtcMs = takenAtUtcMs,
    localTime = LocalTime.from(localDateTime),
    gps = gps,
    placeId = placeId,
    originDevice = originDevice,
    source = smartSource,
    kind = kind,
    screen = mediaSource == MediaSource.SCREENSHOT,
    pixels = pixels,
  )

  // --- L'istantanea della timeline -----------------------------------------------------------------------------------------

  private fun changed() {
    seq++
    snapshot.value = build()
    listeners.toList().forEach { it() }
  }

  private fun build(): TimelineSnapshot {
    val cells = media.values.filter { !it.hidden }
      .sortedWith(compareByDescending<FakeMedia> { it.takenAtUtcMs }.thenByDescending { it.id.value })
    val n = cells.size
    val uuids = cells.map { UUID.fromString(it.id.value) }
    return InMemoryTimelineSnapshot(
      seq = seq,
      idMsb = LongArray(n) { uuids[it].mostSignificantBits },
      idLsb = LongArray(n) { uuids[it].leastSignificantBits },
      sortTime = LongArray(n) { cells[it].takenAtUtcMs },
      localDay = IntArray(n) { cells[it].day.let { d -> d.year * 10_000 + d.monthValue * 100 + d.dayOfMonth } },
      kinds = ByteArray(n) { cells[it].kind.ordinal.toByte() },
      sources = ByteArray(n) { cells[it].mediaSource.ordinal.toByte() },
      flags = IntArray(n),
      aspectMilli = IntArray(n) { 1_000 },
      durationMs = LongArray(n),
      place = IntArray(n) { cells[it].placeId },
      groupSize = IntArray(n) { 1 },
    )
  }

  companion object {
    /** Un id deterministico dal numero [n] (test leggibili e ripetibili). */
    fun id(n: Int): MediaId = MediaId(UUID(0x7_0000_0000L + n, n.toLong()).toString())
  }
}

