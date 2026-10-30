package dev.pampa.mycloudgallery.core.memories.eval

import dev.pampa.mycloudgallery.core.memories.contract.DayRange
import dev.pampa.mycloudgallery.core.memories.contract.PersonDigest
import dev.pampa.mycloudgallery.core.memories.contract.PixelProbe
import dev.pampa.mycloudgallery.core.memories.contract.TripDigestView
import dev.pampa.mycloudgallery.core.memories.testing.SyntheticMemoryLibrary
import dev.pampa.mycloudgallery.core.memories.testing.SyntheticVectors
import dev.pampa.mycloudgallery.core.model.CellFlags
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.model.MediaSource
import dev.pampa.mycloudgallery.core.model.PersonId
import dev.pampa.mycloudgallery.core.model.TripKind
import java.time.LocalDate
import java.time.LocalTime
import java.util.Random
import kotlin.math.exp
import kotlin.math.ln

// LA LIBRERIA SINTETICA "DI UNA FAMIGLIA" (docs/fase9-progetto.md §5.3 punto 1, riga P11 del §4.4): cinque anni di foto di una famiglia di adulti, con
// anniversari, viaggi, un evento, tre persone, raffiche e riprese ripetute, screenshot e documenti nei giorni giusti, foto mosse e buie come misure
// finte, "giorni tristi" come concetti nei vettori finti e nascosti perfetti che non devono mai uscire. Nessun dato vero: tutto da un seme, con
// **date relative al giorno della prova** (`today`): gli anniversari cadono sempre nei giorni giusti, qualunque sia oggi.
//
// La verita' di ogni foto (cosa direbbe un utente ideale) sta in [FamilyShot]: il motore non la vede mai. Serve a [FamilyIdealUser], che compila i
// giudizi come li compilerebbe una persona che sa tutto, e quindi a calcolare i numeri attesi del riferimento.

/** Un utente ideale giudica un ricordo "triste" quando almeno questa quota delle foto lo e' (o lo e' la copertina). */
internal const val SAD_SHARE: Double = 0.25

/** Cio' che il banco sa di una foto e il motore no. */
internal class FamilyShot(
  val id: MediaId,
  val day: LocalDate,
  val secondOfDay: Int,
  val source: MediaSource,
  val kind: MediaKind,
  val flags: Int,
  /** Il difetto della foto in se' (mossa o buia, documento sfuggito, non c'entra, triste); null se va bene. Le riprese ripetute stanno in [dupGroup]. */
  val fault: EvalFault?,
  /** Il gruppo di riprese quasi uguali della stessa scena (stesso numero = doppioni); null se la foto e' unica. */
  var dupGroup: Int?,
  /** Le esclusioni di D5 (screenshot, documento riconosciuto, immagine animata): non devono mai comparire in un ricordo. */
  val excluded: Boolean,
  /** La foto e' davvero mossa o davvero buia (la verita' dietro la misura finta: il motore vede solo la misura). */
  val blurry: Boolean = false,
  val dark: Boolean = false,
) {
  /** Se la foto e' "simile ma diversa" da un'altra dello stesso giorno (coseno 0,82-0,90: non e' un doppione e non va tolta). */
  var similarTo: MediaId? = null

  override fun toString(): String = "FamilyShot(${kind.code})"
}

/** Un viaggio o evento seminato: i giorni, il luogo (id finto) e l'etichetta neutra. */
internal class FamilyTrip(val kind: TripKind, val days: DayRange, val place: Int, val label: String, val startMinute: Int = 0, val endMinute: Int = 24 * 60)

/** Il mondo finto: la Libreria, la verita' delle foto e le porte che il motore chiede (vettori, pixel, persone, viaggi). */
internal class FamilyWorld(
  val today: LocalDate,
  val library: SyntheticMemoryLibrary,
  val shots: Map<MediaId, FamilyShot>,
  val vectors: Map<MediaId, FloatArray>,
  val probes: Map<MediaId, PixelProbe>,
  val personMedia: Map<PersonId, Set<MediaId>>,
  val trips: List<TripDigestView>,
  val hiddenPerfect: Set<MediaId>,
) {
  /** Le persone con il loro nome neutro ("persona-a"): mai un nome vero. */
  fun personName(id: PersonId): String = "persona-" + ('a' + FamilyBuilder.PEOPLE.indexOf(id))

  /** Le foto della Libreria non nascoste, per id. */
  val visibleShots: List<FamilyShot> get() = shots.values.filter { it.id !in library.visibility.hidden }

  /** I giorni dei viaggi e degli eventi seminati. */
  val seededDays: Int get() = trips.sumOf { it.days.days }
}

internal class FamilyBuilder(private val today: LocalDate, private val seed: Long = SEED) {
  private enum class Mood { NORMAL, SAD }

  private class DayPlan(val count: Int, val place: Int, val mood: Mood = Mood.NORMAL, val screenshotsOnly: Boolean = false, val startMinute: Int = 9 * 60, val gapMinutes: Int = 25)

  private val random = Random(seed * 31 + 5)
  private val library = SyntheticMemoryLibrary(seed)
  private val shots = LinkedHashMap<MediaId, FamilyShot>()
  private val latent = HashMap<MediaId, FloatArray>()
  private val vectors = LinkedHashMap<MediaId, FloatArray>()
  private val probes = LinkedHashMap<MediaId, PixelProbe>()
  private val persons = PEOPLE.associateWith { LinkedHashSet<MediaId>() }
  private val groupBase = HashMap<Int, FloatArray>()
  private val tripSpecs = ArrayList<FamilyTrip>()
  private var conceptSeed = 1L
  private var groups = 0

  fun build(): FamilyWorld {
    val special = specialDays()
    val start = today.minusYears(YEARS.toLong())
    library.batch {
      var day = start
      while (day.isBefore(today)) {
        // Un 29 febbraio non ha un modo relativo di dirsi ("N anni fa" cade sempre sul 28): le sue foto stanno sul 28.
        fillDay(if (day.monthValue == 2 && day.dayOfMonth == 29) day.minusDays(1) else day, special[day] ?: ordinary())
        day = day.plusDays(1)
      }
    }
    val perfect = addHiddenPerfect()
    val trips = tripSpecs.map { digest(it) }
    return FamilyWorld(today, library, shots, vectors, probes, persons, trips, perfect)
  }

  // --- I giorni seminati, tutti relativi a oggi -------------------------------------------------------------------------------------

  private fun relative(offsetDays: Int, yearsBack: Int): LocalDate = today.minusDays(offsetDays.toLong()).minusYears(yearsBack.toLong())

  private fun specialDays(): Map<LocalDate, DayPlan> {
    val out = HashMap<LocalDate, DayPlan>()
    // Giorni di soli screenshot (E-MEM2): in un anniversario non fanno un "anni fa".
    for ((offset, years) in SCREENSHOT_DAYS) out[relative(offset, years)] = DayPlan(3 + (offset + years) % 4, HOME, screenshotsOnly = true)
    // Anniversari: una festa a casa, ogni volta con 14-20 foto, per uno, due e tre anni fa.
    for (offset in PARTY_OFFSETS) for (years in 1..3) out[relative(offset, years)] = DayPlan(14 + (offset + years) % 7, HOME + (offset % 3), startMinute = 12 * 60, gapMinutes = 6)
    // Un evento: molte foto in poche ore, il pomeriggio di un giorno di festa.
    for ((offset, years) in EVENT_DAYS) {
      val day = relative(offset, years)
      out[day] = DayPlan(26, HOME, startMinute = 15 * 60, gapMinutes = 8)
      tripSpecs += FamilyTrip(TripKind.EVENT, DayRange.of(day), HOME, "evento-${tripSpecs.size}", startMinute = 15 * 60, endMinute = 19 * 60)
    }
    // I viaggi: giorni di molte foto in un luogo lontano. Gli anniversari cadono nella finestra di 45 giorni, gli altri sono "riscoperte".
    for ((n, spec) in TRIPS.withIndex()) {
      val first = relative(spec.offset, spec.years)
      for (k in 0 until spec.length) out[first.plusDays(k.toLong())] = DayPlan(10 + (k * 7 + spec.offset) % 24, spec.place, startMinute = 8 * 60, gapMinutes = 12)
      tripSpecs += FamilyTrip(TripKind.TRIP, DayRange(first, first.plusDays(spec.length - 1L)), spec.place, "viaggio-$n")
    }
    // Giorni tristi: le foto di quel giorno hanno concetti tristi nei vettori. Uno cade in un anniversario della finestra, uno nel mese prima.
    for ((offset, years) in SAD_DAYS) out[relative(offset, years)] = DayPlan(9 + (offset + years) % 5, HOME, Mood.SAD, startMinute = 10 * 60, gapMinutes = 40)
    return out
  }

  /** Un giorno qualunque: il 35% ha foto, il 18% di quelli molte. */
  private fun ordinary(): DayPlan? {
    if (random.nextDouble() >= PHOTO_DAY) return null
    val busy = random.nextDouble() < BUSY_SHARE
    val count = if (busy) 10 + random.nextInt(16) else 2 + random.nextInt(6)
    // Nei giorni di molte foto si scatta a raffiche di minuti (feste, gite): la finestra di 3 minuti dei doppioni ha qualcosa da sbagliare.
    val gap = if (busy) 3 + random.nextInt(8) else 15 + random.nextInt(30)
    return DayPlan(count, HOME + random.nextInt(3), startMinute = (8 + random.nextInt(8)) * 60, gapMinutes = gap)
  }

  private fun fillDay(day: LocalDate, plan: DayPlan?) {
    if (plan == null) return
    var second = plan.startMinute * 60 + random.nextInt(600)
    var previous: FamilyShot? = null
    var baseShot: FamilyShot? = null
    repeat(plan.count) {
      if (second >= SECONDS_PER_DAY) return
      val retake = previous != null && !plan.screenshotsOnly && random.nextDouble() < RETAKE_SHARE
      if (retake) {
        val gap = if (random.nextDouble() < 0.8) 20 + random.nextInt(150) else 240 + random.nextInt(360)
        second = (previous!!.secondOfDay + gap).coerceAtMost(SECONDS_PER_DAY - 1)
      }
      val shot = addShot(day, second, plan, if (retake) previous else null, baseShot)
      baseShot = shot
      previous = shot
      if (!retake) second += plan.gapMinutes * 60 + random.nextInt(300)
    }
  }

  // --- Una foto ---------------------------------------------------------------------------------------------------------------------

  /**
   * Una foto del giorno [day]. Un solo numero ([roll]) decide la categoria, cosi' le quote sommano a uno: documento riconosciuto (3%), documento
    * sfuggito al riconoscimento (0,6%), ricevuta da WhatsApp (8%, un quarto sono cose che "non c'entrano"), mossa (3,5%), buia (2%), estranea (0,5%), poi
   * le foto buone. Una ripresa ripetuta di [retakeOf] e' sempre buona (e' un doppione: il difetto e' la ripetizione).
   */
  private fun addShot(day: LocalDate, second: Int, plan: DayPlan, retakeOf: FamilyShot?, baseShot: FamilyShot?): FamilyShot {
    val screenshot = plan.screenshotsOnly || (retakeOf == null && random.nextDouble() < SCREENSHOT_SHARE)
    val sad = !screenshot && retakeOf == null && plan.mood == Mood.SAD && random.nextDouble() < SAD_IN_SAD_DAY
    val roll = if (screenshot || sad || retakeOf != null) 1.0 else random.nextDouble()
    var source = if (screenshot) MediaSource.SCREENSHOT else MediaSource.CAMERA
    var flags = 0
    var fault: EvalFault? = null
    var blurry = false
    var dark = false
    when {
      sad -> fault = EvalFault.SAD
      roll < 0.030 -> flags = flags or CellFlags.DOCUMENT
      roll < 0.036 -> fault = EvalFault.SCREENSHOT_OR_DOC
      roll < 0.116 -> {
        source = MediaSource.WHATSAPP
        if (random.nextDouble() < 0.25) fault = EvalFault.UNRELATED
      }
      roll < 0.151 -> {
        blurry = true
        fault = EvalFault.BLURRY_OR_DARK
      }
      roll < 0.171 -> {
        dark = true
        fault = EvalFault.BLURRY_OR_DARK
      }
      roll < 0.176 -> fault = EvalFault.UNRELATED
    }
    if (!screenshot && random.nextDouble() < 0.04) flags = flags or CellFlags.FAVORITE
    if (!screenshot && random.nextDouble() < 0.03) flags = flags or CellFlags.EDITED
    val panorama = !screenshot && random.nextDouble() < 0.01
    if (panorama) flags = flags or CellFlags.PANORAMA
    if (!screenshot && random.nextDouble() < 0.02) flags = flags or CellFlags.MOTION
    if (random.nextDouble() < 0.01) flags = flags or CellFlags.DATE_ESTIMATED
    val animated = !screenshot && random.nextDouble() < 0.005
    val video = !screenshot && !animated && random.nextDouble() < 0.05
    val kind = if (animated) MediaKind.ANIMATED_IMAGE else if (video) MediaKind.VIDEO else MediaKind.PHOTO
    val duration = if (!video) 0L else if (random.nextDouble() < 0.9) 5_000L + random.nextInt(40_000) else 400_000L
    val aspect = if (panorama) 2_500 else if (random.nextDouble() < 0.3) 750 else 1_333
    val id = library.add(
      day, LocalTime.ofSecondOfDay(second.toLong()), source = source, kind = kind, flags = flags, durationMs = duration, place = plan.place, aspectMilli = aspect,
    )
    val excluded = screenshot || CellFlags.has(flags, CellFlags.DOCUMENT) || animated
    val shot = FamilyShot(id, day, second, source, kind, flags, fault, null, excluded, blurry, dark)
    shots[id] = shot
    seedVector(shot, retakeOf, baseShot, sad)
    if (!screenshot && random.nextDouble() < 0.85) probes[id] = probe(blurry, dark)
    if (!excluded) for ((person, chance) in PERSON_CHANCE) if (random.nextDouble() < chance) persons.getValue(person) += id
    return shot
  }

  /** Il vettore finto: concetti diversi sono quasi ortogonali, le riprese ripetute hanno coseno 0,94-0,995, le "simili ma diverse" 0,82-0,90. */
  private fun seedVector(shot: FamilyShot, retakeOf: FamilyShot?, baseShot: FamilyShot?, sad: Boolean) {
    val vector: FloatArray = when {
      retakeOf != null -> {
        val group = retakeOf.dupGroup ?: (++groups).also { retakeOf.dupGroup = it }
        val base = groupBase.getOrPut(group) { latent.getValue(retakeOf.id) }
        shot.dupGroup = group
        SyntheticVectors.withCosine(base, 0.94 + 0.055 * random.nextDouble(), seed * 977 + conceptSeed++)
      }
      sad -> SyntheticVectors.withCosine(SyntheticVectors.concept(SAD_CONCEPTS[random.nextInt(SAD_CONCEPTS.size)]), 0.80 + 0.15 * random.nextDouble(), seed * 977 + conceptSeed++)
      shot.fault == EvalFault.SCREENSHOT_OR_DOC -> SyntheticVectors.withCosine(SyntheticVectors.concept(DOCUMENT_CONCEPT), 0.90, seed * 977 + conceptSeed++)
      baseShot != null && random.nextDouble() < SIMILAR_SHARE -> {
        shot.similarTo = baseShot.id
        SyntheticVectors.withCosine(latent.getValue(baseShot.id), 0.82 + 0.08 * random.nextDouble(), seed * 977 + conceptSeed++)
      }
      else -> SyntheticVectors.concept(1_000 + conceptSeed++)
    }
    latent[shot.id] = vector
    if (random.nextDouble() < VECTOR_SHARE) vectors[shot.id] = vector
  }

  /** Nitidezza e luce misurate: le foto buone sono nitide (mediana 600, sempre un po' di coda verso il basso), le mosse molto meno (mediana 45, qualcuna sopra 100). */
  private fun probe(blurry: Boolean, dark: Boolean): PixelProbe {
    val sharpness = exp(ln(if (blurry) 45.0 else 600.0) + (if (blurry) 0.6 else 0.7) * random.nextGaussian()).coerceAtMost(50_000.0)
    val luma = if (dark) 0.03 + 0.10 * random.nextDouble() else (0.45 + 0.15 * random.nextGaussian()).coerceIn(0.2, 0.8)
    return PixelProbe(sharpness.toFloat(), luma.toFloat())
  }

  // --- I nascosti perfetti e i viaggi -----------------------------------------------------------------------------------------------

  /**
   * Foto **perfette** (preferite, modificate, nitide, di persone) nei giorni delle feste, tutte nascoste: se un nascosto uscisse in un ricordo sarebbe
   * la prima scelta. Nessun ricordo, metrica o strumento deve mai vederle (D31).
   */
  private fun addHiddenPerfect(): Set<MediaId> {
    val perfect = LinkedHashSet<MediaId>()
    library.batch {
      for ((n, offset) in PARTY_OFFSETS.withIndex()) for (years in 1..3) {
        val day = relative(offset, years)
        repeat(3) { k ->
          val id = library.add(day, LocalTime.of(13, 5 * k + n), flags = CellFlags.FAVORITE or CellFlags.EDITED, place = HOME)
          shots[id] = FamilyShot(id, day, (13 * 60 + 5 * k + n) * 60, MediaSource.CAMERA, MediaKind.PHOTO, CellFlags.FAVORITE or CellFlags.EDITED, null, null, excluded = false)
          val vector = SyntheticVectors.concept(900_000 + perfect.size.toLong())
          latent[id] = vector
          vectors[id] = vector
          probes[id] = PixelProbe(900f, 0.5f)
          persons.getValue(PEOPLE[0]) += id
          perfect += id
        }
      }
    }
    library.batch { perfect.forEach { library.hide(it) } }
    return perfect
  }

  /** Il viaggio o evento come lo darebbe la porta dei viaggi: solo foto proprie e visibili, mai screenshot, nell'ordine di scatto. */
  private fun digest(trip: FamilyTrip): TripDigestView {
    val members = shots.values
      .filter { it.day in trip.days && it.id !in library.visibility.hidden && it.source != MediaSource.SCREENSHOT }
      .filter { it.secondOfDay / 60 in trip.startMinute until trip.endMinute }
      .sortedWith(compareBy<FamilyShot> { it.day }.thenBy { it.secondOfDay }.thenBy { it.id.value })
      .map { it.id }
    return TripDigestView(
      trip.kind, trip.days, trip.label, listOf(trip.place), members, album = null,
      startQuarter = if (trip.kind == TripKind.EVENT) trip.startMinute / 15 else null,
    )
  }

  internal class TripSpec(val offset: Int, val years: Int, val length: Int, val place: Int)

  companion object {
    const val SEED: Long = 20_261_030L
    const val YEARS: Int = 5

    /** Il luogo di casa e due altri vicini (id GeoNames inventati: mai un luogo vero). */
    const val HOME: Int = 9_000_001

    private const val SECONDS_PER_DAY = 86_400
    private const val PHOTO_DAY = 0.35
    private const val BUSY_SHARE = 0.18
    private const val RETAKE_SHARE = 0.12
    private const val SCREENSHOT_SHARE = 0.06
    private const val SAD_IN_SAD_DAY = 0.85
    private const val SIMILAR_SHARE = 0.08
    private const val VECTOR_SHARE = 0.90
    private const val DOCUMENT_CONCEPT = 777_001L
    private val SAD_CONCEPTS = longArrayOf(777_101L, 777_102L, 777_103L)

    /** Le tre persone con un nome: adulti, ognuna in una quota diversa di foto. */
    val PEOPLE: List<PersonId> = listOf(
      PersonId("00000000-0000-4000-8000-0000000000a1"),
      PersonId("00000000-0000-4000-8000-0000000000a2"),
      PersonId("00000000-0000-4000-8000-0000000000a3"),
    )
    private val PERSON_CHANCE: List<Pair<PersonId, Double>> = listOf(PEOPLE[0] to 0.30, PEOPLE[1] to 0.25, PEOPLE[2] to 0.12)

    /** (giorni prima di oggi, anni indietro): i giorni seminati, tutti relativi a oggi. */
    private val SCREENSHOT_DAYS = listOf(5 to 1, 14 to 2, 22 to 1, 30 to 3, 41 to 2)
    private val PARTY_OFFSETS = listOf(3, 9, 17, 24, 31, 38)
    private val EVENT_DAYS = listOf(15 to 2)
    private val SAD_DAYS = listOf(12 to 2, 50 to 0, 140 to 1, 200 to 2, 330 to 3)

    /** Quattro viaggi con l'anniversario nella finestra di 45 giorni (inizio a 2, 10, 26 e 40 giorni da oggi) e tre piu' vecchi. */
    private val TRIPS = listOf(
      TripSpec(2, 1, 5, 9_100_001), TripSpec(10, 2, 4, 9_100_002), TripSpec(26, 3, 7, 9_100_003), TripSpec(40, 4, 6, 9_100_004),
      TripSpec(100, 1, 9, 9_100_005), TripSpec(220, 2, 5, 9_100_006), TripSpec(300, 3, 4, 9_100_007),
    )
  }
}

/** Tutta la Libreria di prova per un giorno: l'unico punto d'ingresso dei test. */
internal fun familyWorld(today: LocalDate, seed: Long = FamilyBuilder.SEED): FamilyWorld = FamilyBuilder(today, seed).build()

/** Le persone seminate come `PersonDigest` (solo con i loro media: la visibilita' la applica la porta). */
internal fun FamilyWorld.personDigests(): List<PersonDigest> = personMedia.map { (id, media) -> PersonDigest(id, media.toList()) }
