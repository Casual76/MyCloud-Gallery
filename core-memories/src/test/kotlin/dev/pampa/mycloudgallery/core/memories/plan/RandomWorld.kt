package dev.pampa.mycloudgallery.core.memories.plan

import dev.pampa.mycloudgallery.core.memories.contract.DayRange
import dev.pampa.mycloudgallery.core.memories.contract.PersonDigest
import dev.pampa.mycloudgallery.core.memories.contract.TripDigestView
import dev.pampa.mycloudgallery.core.memories.testing.SyntheticMemoryLibrary
import dev.pampa.mycloudgallery.core.model.AlbumId
import dev.pampa.mycloudgallery.core.model.CellFlags
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.model.MediaSource
import dev.pampa.mycloudgallery.core.model.PersonId
import dev.pampa.mycloudgallery.core.model.TripKind
import java.time.LocalDate
import java.time.LocalTime
import java.util.Random
import java.util.UUID

/**
 * Un mondo casuale e sintetico per le prove di parita' (docs/fase9-progetto.md §5.2): una Libreria con anniversari agganciati a giorni scelti
 * (e al 28 e 29 febbraio), esclusioni di D5 sparse (screenshot, documenti, date stimate, animate, WhatsApp, illeggibili), un mese pieno, viaggi
 * ed eventi (con media che non ci sono, ripetuti, di un album), persone e ritagli. Tutto dal seme: stesso seme, stesso mondo, stessi id.
 * Nessun dato vero: date e luoghi sono numeri e giorni inventati.
 */
internal class RandomWorld(
  val seed: Long,
  years: IntRange = 2019..2026,
  anchorsPerYear: Int = 8,
  photosPerAnchor: IntRange = 3..11,
  noise: Int = 200,
  months: Int = 3,
  tripCount: Int = 6,
) {

  private val random = Random(seed)
  val lib = SyntheticMemoryLibrary(seed)

  /** I giorni dell'anno (mese, giorno) intorno a cui ci sono anniversari. */
  val anchors: List<Pair<Int, Int>>

  /** Ogni media creato, per giorno. */
  private val byDay = HashMap<LocalDate, MutableList<MediaId>>()
  val trips = ArrayList<TripDigestView>()
  val people = ArrayList<PersonDigest>()
  val personIds = List(3) { PersonId.fromBytes(ByteArray(16) { b -> (seed + it * 31 + b + 1).toByte() }) }
  val cutouts = HashSet<MediaId>()
  val monthClusters = ArrayList<Pair<Int, Int>>()

  init {
    val specials = listOf(2 to 29, 2 to 28, 3 to 1, 12 to 31, 1 to 1)
    anchors = (specials.shuffled(random).take(2) + List(anchorsPerYear) { (1 + random.nextInt(12)) to (1 + random.nextInt(28)) }).distinct()
    lib.batch {
      for (year in years) for ((month, day) in anchors) {
        if (month == 2 && day == 29 && !java.time.Year.isLeap(year.toLong())) continue
        if (random.nextInt(10) < 3) continue
        val n = photosPerAnchor.first + random.nextInt(photosPerAnchor.last - photosPerAnchor.first + 1)
        repeat(n) { addRandom(LocalDate.of(year, month, day)) }
      }
      repeat(noise) { addRandom(LocalDate.of(years.first, 1, 1).plusDays(random.nextInt(365 * years.count() + 250).toLong())) }
      // Qualche mese pieno negli ultimi due anni.
      repeat(months) {
        val year = years.last - random.nextInt(2)
        val month = 1 + random.nextInt(if (year == years.last) 9 else 12)
        monthClusters += year to month
        for (d in 1..LocalDate.of(year, month, 1).lengthOfMonth()) repeat(2 + random.nextInt(3)) { addRandom(LocalDate.of(year, month, d), allowBad = false) }
      }
      repeat(tripCount) { addTrip(years) }
    }
    val all = byDay.values.flatten()
    for (person in personIds) people += PersonDigest(person, all.shuffled(random).take(40 + random.nextInt(80)))
    for (id in all) if (random.nextInt(25) == 0) cutouts += id
  }

  private fun addRandom(day: LocalDate, allowBad: Boolean = true): MediaId {
    val roll = random.nextInt(100)
    val source = when {
      !allowBad -> MediaSource.CAMERA
      roll < 78 -> MediaSource.CAMERA
      roll < 86 -> MediaSource.SCREENSHOT
      roll < 93 -> MediaSource.WHATSAPP
      else -> MediaSource.OTHER
    }
    val kindRoll = random.nextInt(100)
    val kind = when {
      !allowBad || kindRoll < 88 -> MediaKind.PHOTO
      kindRoll < 94 -> MediaKind.VIDEO
      kindRoll < 97 -> MediaKind.ANIMATED_IMAGE
      else -> MediaKind.MOTION_PHOTO
    }
    var flags = 0
    if (allowBad && random.nextInt(100) < 5) flags = flags or CellFlags.DOCUMENT
    if (allowBad && random.nextInt(100) < 6) flags = flags or CellFlags.DATE_ESTIMATED
    if (allowBad && random.nextInt(100) < 3) flags = flags or CellFlags.UNREADABLE
    if (random.nextInt(100) < 10) flags = flags or CellFlags.FAVORITE
    val time = LocalTime.of(random.nextInt(24), random.nextInt(60))
    val id = lib.add(day, time, source = source, kind = kind, flags = flags, durationMs = if (kind == MediaKind.VIDEO) 5_000L else 0L, place = 3_000_000 + random.nextInt(4))
    byDay.getOrPut(day) { ArrayList() } += id
    return id
  }

  private fun addTrip(years: IntRange) {
    val from = LocalDate.of(years.first + random.nextInt(years.count()), 1 + random.nextInt(12), 1 + random.nextInt(25))
    val kind = if (random.nextInt(4) == 0) TripKind.EVENT else TripKind.TRIP
    val length = if (kind == TripKind.EVENT) 1 else 1 + random.nextInt(7)
    val members = ArrayList<MediaId>()
    for (d in 0 until length) {
      val day = from.plusDays(d.toLong())
      // Un evento e' "molte foto in poche ore": il minimo di D7 e' 10.
      val count = if (kind == TripKind.EVENT) 10 + random.nextInt(5) else 3 + random.nextInt(6)
      repeat(count) { members += addRandom(day, allowBad = random.nextBoolean()) }
    }
    // Media che non ci sono piu' e ripetuti: la porta li lascia passare, il generatore li ignora.
    repeat(random.nextInt(3)) { members += MediaId(UUID(random.nextLong(), random.nextLong()).toString()) }
    if (members.isNotEmpty() && random.nextBoolean()) members += members.first()
    members.shuffle(random)
    trips += TripDigestView(
      kind = kind,
      days = DayRange(from, from.plusDays(length - 1L)),
      label = "Viaggio sintetico ${trips.size}",
      placeIds = listOf(3_100_000 + trips.size),
      members = members,
      album = if (random.nextBoolean()) AlbumId(UUID(random.nextLong(), random.nextLong()).toString()) else null,
      startQuarter = if (kind == TripKind.EVENT) random.nextInt(96) else null,
    )
  }

  /** Tutti i media creati. */
  fun allMedia(): List<MediaId> = byDay.values.flatten()

  /** I media creati in [day]. */
  fun mediaOn(day: LocalDate): List<MediaId> = byDay[day].orEmpty()

  fun random(): Random = random
}
