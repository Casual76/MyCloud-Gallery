package dev.pampa.mycloudgallery.core.library.testing

import dev.pampa.mycloudgallery.core.model.CellFlags
import dev.pampa.mycloudgallery.core.model.MediaRef
import dev.pampa.mycloudgallery.core.model.ThumbHash
import dev.pampa.mycloudgallery.core.library.contract.LibraryFilter
import dev.pampa.mycloudgallery.core.library.contract.TimelineSnapshot
import dev.pampa.mycloudgallery.core.library.contract.TimelineView
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.model.MediaSource
import java.time.LocalDate
import java.util.SplittableRandom
import java.util.UUID
import kotlin.math.max
import kotlin.math.min

// La timeline in memoria dei test (D23): in src/main, cosi' i moduli a valle (feature-library, feature-viewer, :app) la
// riusano nei loro test e negli screenshot senza cicli. Mai nella release: tools/check.ps1 fallisce se una classe di un
// package `testing` compare nel dex dell'APK di release. P5 possiede questo file e puo' riscriverlo (la Libreria
// sintetica "deterministica da 100 mila" e' nel suo "Fatto quando"): le firme pubbliche di [SyntheticLibrary] restano.

/**
 * Un [TimelineSnapshot] su array semplici: la versione dei test dell'indice a colonne. Gli id sono due `long` per
 * elemento come nell'indice vero; il ThumbHash e la [MediaRef] si ricavano dal seme dell'elemento solo quando servono,
 * cosi' 100 mila elementi occupano pochi megabyte.
 */
class InMemoryTimelineSnapshot(
  override val seq: Long,
  private val idMsb: LongArray,
  private val idLsb: LongArray,
  private val sortTime: LongArray,
  private val localDay: IntArray,
  private val kinds: ByteArray,
  private val sources: ByteArray,
  private val flags: IntArray,
  private val aspectMilli: IntArray,
  private val durationMs: LongArray,
  private val place: IntArray,
  private val groupSize: IntArray,
  private val thumbHashOf: (Int) -> ThumbHash? = { null },
) : TimelineSnapshot {

  override val size: Int = idMsb.size

  private val indexById: Map<MediaId, Int> by lazy {
    HashMap<MediaId, Int>(size * 2).also { map -> for (i in 0 until size) map[idAt(i)] = i }
  }

  override fun idAt(i: Int): MediaId = MediaId(UUID(idMsb[i], idLsb[i]).toString())

  override fun sortTimeAt(i: Int): Long = sortTime[i]

  override fun localDayAt(i: Int): Int = localDay[i]

  override fun kindAt(i: Int): MediaKind = MediaKind.entries[kinds[i].toInt()]

  override fun sourceAt(i: Int): MediaSource = MediaSource.entries[sources[i].toInt()]

  override fun flagsAt(i: Int): Int = flags[i]

  override fun aspectMilliAt(i: Int): Int = aspectMilli[i]

  override fun durationMsAt(i: Int): Long = durationMs[i]

  override fun placeAt(i: Int): Int = place[i]

  override fun thumbHashAt(i: Int): ThumbHash? = thumbHashOf(i)

  override fun groupSizeAt(i: Int): Int = groupSize[i]

  override fun indexOf(id: MediaId): Int = indexById[id] ?: -1

  /** La copia con la riga [i] senza il flag e/o con il flag cambiato (preferiti, nascosti): per i fake delle azioni. */
  fun withFlags(i: Int, newFlags: Int, newSeq: Long = seq + 1): InMemoryTimelineSnapshot {
    val copy = flags.copyOf().also { it[i] = newFlags }
    return InMemoryTimelineSnapshot(newSeq, idMsb, idLsb, sortTime, localDay, kinds, sources, copy, aspectMilli, durationMs, place, groupSize, thumbHashOf)
  }
}

/** La vista filtrata di un indice in memoria. Senza filtro (`ALL`) la mappa e' l'identita' e non costa memoria. */
class InMemoryTimelineView(
  override val snapshot: TimelineSnapshot,
  override val filter: LibraryFilter,
) : TimelineView {

  private val map: IntArray? = if (filter == LibraryFilter.ALL) null else buildMap()

  override val size: Int = map?.size ?: snapshot.size

  override fun snapshotIndex(viewIndex: Int): Int = map?.get(viewIndex) ?: viewIndex

  override fun viewIndexOf(id: MediaId): Int {
    val index = snapshot.indexOf(id)
    if (index < 0) return -1
    val m = map ?: return index
    val found = m.binarySearch(index)
    return if (found >= 0) found else -1
  }

  private fun buildMap(): IntArray {
    val out = IntArray(snapshot.size)
    var n = 0
    for (i in 0 until snapshot.size) {
      if (filter.accepts(snapshot.kindAt(i), snapshot.sourceAt(i), snapshot.flagsAt(i))) out[n++] = i
    }
    return out.copyOf(n)
  }
}

/**
 * La Libreria sintetica (docs/fase3-progetto.md §5.2): una timeline deterministica di [count] elementi su 15 anni, con
 * giorni "a raffica" (feste, viaggi), giornate vuote, video, screenshot, WhatsApp, preferiti, date stimate, luoghi e
 * viaggi con fuso diverso. Stesso seme, stessa libreria: i test, i budget e gli screenshot sono ripetibili.
 *
 * Questa e' la versione di P0 (una libreria piccola di default, 2.000 elementi, quanto serve agli screenshot e allo
 * scaffold): P5 la porta ai 100 mila elementi del §5.2 e ai suoi budget.
 *
 * @property placeIds gli id dei luoghi da cui campionare `place_id` (di solito `FakePlaceData.IDS` di core-places, che
 *   questo modulo non vede); vuoto = nessun luogo.
 */
class SyntheticLibrary(
  val seed: Long = 1L,
  val count: Int = DEFAULT_COUNT,
  val nowMs: Long = DEFAULT_NOW_MS,
  private val placeIds: IntArray = IntArray(0),
) {

  /** L'indice: 0 e' il piu' recente. */
  val snapshot: InMemoryTimelineSnapshot by lazy { build() }

  /** La vista senza filtro. */
  fun view(filter: LibraryFilter = LibraryFilter.ALL): TimelineView = InMemoryTimelineView(snapshot, filter)

  /** I byte (finti) dell'elemento [i] dell'indice: stesse proporzioni e stessi colori della cella e del viewer. */
  fun refAt(i: Int): MediaRef.Synthetic {
    val aspect = snapshot.aspectMilliAt(i)
    val width = if (aspect >= 1000) LONG_SIDE else LONG_SIDE * aspect / 1000
    val height = if (aspect >= 1000) LONG_SIDE * 1000 / aspect else LONG_SIDE
    return MediaRef.Synthetic(seedOf(seed, i), max(1, width), max(1, height))
  }

  /** Come [refAt], per id; un id sconosciuto da' un riferimento con il seme dell'id (mai un errore nel viewer). */
  fun ref(id: MediaId): MediaRef {
    val i = snapshot.indexOf(id)
    return if (i >= 0) refAt(i) else MediaRef.Synthetic(id.value.hashCode().toLong(), LONG_SIDE, LONG_SIDE)
  }

  private fun build(): InMemoryTimelineSnapshot {
    val rnd = SplittableRandom(seed)
    val spanDays = YEARS * 365
    // Una libreria vera ha piu' scatti recenti che vecchi: i giorni si pescano con una distribuzione quadratica verso oggi.
    fun recentDay(): Int = (spanDays * rnd.nextDouble().let { it * it }).toInt().coerceIn(0, spanDays - 1)
    val busyDays = IntArray(max(8, count / 30)) { recentDay() }

    // Gli istanti: il 70% cade in un giorno "a raffica", il resto sparso; i piu' recenti per primi.
    val times = LongArray(count) {
      val dayOffset = if (rnd.nextInt(100) < 70) busyDays[rnd.nextInt(busyDays.size)] else recentDay()
      val dayStart = Math.floorDiv(nowMs, DAY_MS) * DAY_MS - dayOffset * DAY_MS
      min(dayStart + 8 * HOUR_MS + rnd.nextLong(14 * HOUR_MS), nowMs - 1)
    }
    times.sort()
    times.reverse()

    val idMsb = LongArray(count)
    val idLsb = LongArray(count)
    val localDay = IntArray(count)
    val kinds = ByteArray(count)
    val sources = ByteArray(count)
    val flags = IntArray(count)
    val aspect = IntArray(count)
    val duration = LongArray(count)
    val place = IntArray(count)
    val groups = IntArray(count)

    for (i in 0 until count) {
      val itemSeed = seedOf(seed, i)
      val r = SplittableRandom(itemSeed)
      idMsb[i] = itemSeed
      idLsb[i] = seedOf(itemSeed, 7)
      val dayIndex = Math.floorDiv(times[i], DAY_MS).toInt()
      localDay[i] = dayNumber(times[i] + zoneOffsetMs(dayIndex))

      val pick = r.nextInt(100)
      val source = when {
        pick < 10 -> MediaSource.SCREENSHOT
        pick < 30 -> MediaSource.WHATSAPP
        pick < 32 -> MediaSource.QUICK_SHARE
        else -> MediaSource.CAMERA
      }
      val isVideo = source == MediaSource.CAMERA && r.nextInt(100) < 11
      val isMotion = !isVideo && source == MediaSource.CAMERA && r.nextInt(100) < 3
      val panorama = !isVideo && source == MediaSource.CAMERA && r.nextInt(100) < 2
      kinds[i] = (if (isVideo) MediaKind.VIDEO else if (isMotion) MediaKind.MOTION_PHOTO else MediaKind.PHOTO).ordinal.toByte()
      sources[i] = source.ordinal.toByte()
      aspect[i] = when {
        panorama -> 3000
        source == MediaSource.SCREENSHOT -> 450
        else -> ASPECTS[r.nextInt(ASPECTS.size)]
      }
      duration[i] = if (isVideo) 3_000L + r.nextLong(5 * 60_000L) else 0L

      var f = 0
      if (r.nextInt(100) < 2) f = f or CellFlags.FAVORITE
      if (source == MediaSource.WHATSAPP || r.nextInt(100) < 3) f = f or CellFlags.DATE_ESTIMATED
      if (isMotion) f = f or CellFlags.MOTION
      if (panorama) f = f or CellFlags.PANORAMA
      if (placeIds.isNotEmpty() && source == MediaSource.CAMERA && r.nextInt(100) < 75) {
        // Lo stesso giorno, lo stesso posto: un viaggio ha un luogo solo.
        place[i] = placeIds[Math.floorMod(dayIndex, placeIds.size)]
        f = f or CellFlags.HAS_PLACE
      }
      flags[i] = f
      groups[i] = if (r.nextInt(100) < 1) 2 + r.nextInt(7) else 1
    }
    return InMemoryTimelineSnapshot(
      seq = 1, idMsb = idMsb, idLsb = idLsb, sortTime = times, localDay = localDay, kinds = kinds, sources = sources,
      flags = flags, aspectMilli = aspect, durationMs = duration, place = place, groupSize = groups,
      thumbHashOf = { i -> thumbHashFor(seedOf(seed, i)) },
    )
  }

  companion object {
    const val DEFAULT_COUNT: Int = 2_000

    /** 9 ottobre 2025, mezzogiorno UTC circa: un "adesso" fisso, cosi' le date non dipendono dall'orologio. */
    const val DEFAULT_NOW_MS: Long = 1_760_000_000_000L

    private const val YEARS = 15
    private const val HOUR_MS = 3_600_000L
    private const val DAY_MS = 24 * HOUR_MS
    private const val LONG_SIDE = 4000

    /** 4:3, 3:4, 16:9, 9:16, 1:1 (in millesimi). */
    private val ASPECTS = intArrayOf(1333, 1333, 750, 1778, 562, 1000)

    /** Il fuso del giorno: l'Italia (+1 h), con un giorno su sette in viaggio (UTC-5...+9, E-M14). */
    private val TRIP_HOURS = intArrayOf(-5, 9, 1, -8, 2, 0, 5)

    private fun zoneOffsetMs(dayIndex: Int): Long = (if (Math.floorMod(dayIndex, 7) == 0) TRIP_HOURS[Math.floorMod(dayIndex / 7, TRIP_HOURS.size)] else 1) * HOUR_MS

    private fun dayNumber(localMs: Long): Int {
      val date = LocalDate.ofEpochDay(Math.floorDiv(localMs, DAY_MS))
      return date.year * 10_000 + date.monthValue * 100 + date.dayOfMonth
    }

    /** Un seme deterministico per l'elemento [i] (mescola il seme della libreria con l'indice). */
    fun seedOf(seed: Long, i: Int): Long {
      var z = seed * -0x61c8864680b583ebL + i.toLong() * 0x2545F4914F6CDD1DL
      z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
      z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
      return z xor (z ushr 31)
    }

    /** Un ThumbHash finto e valido (25 byte) derivato dal seme dell'elemento. */
    fun thumbHashFor(itemSeed: Long): ThumbHash {
      val bytes = ByteArray(25)
      var z = itemSeed
      for (b in bytes.indices) {
        z = z * 6364136223846793005L + 1442695040888963407L
        bytes[b] = (z ushr 56).toByte()
      }
      return ThumbHash.of(bytes)
    }
  }
}
