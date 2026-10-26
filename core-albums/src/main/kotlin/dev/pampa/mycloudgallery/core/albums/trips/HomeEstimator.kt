package dev.pampa.mycloudgallery.core.albums.trips

import dev.pampa.mycloudgallery.core.albums.contract.AlbumCandidate
import dev.pampa.mycloudgallery.core.albums.contract.TripRules
import java.time.LocalDate
import java.util.BitSet

// LA "CASA" (docs/fase7-progetto.md §2.3 "Casa", D18, E-AL15, E-AL16). Dedotta solo in locale dalle foto proprie con GPS: celle di ~1 km
// pesate per giorni distinti con foto serali o notturne, "casa" = la cella piu' pesante con le sue 8 vicine se copre almeno il 15% dei giorni
// con GPS; i luoghi abituali (oltre il 10%: la casa al mare) non sono viaggi. Tutto `internal` (TripGeometry): niente esce da trips/.

/** Un luogo dove si vive: centro, raggio (km) e quanti giorni distinti lo pesano. Plain class: il `toString()` non mostra niente. */
internal class Anchor(val lat: Double, val lon: Double, val radiusKm: Double, val days: Int) {
  override fun toString(): String = "Anchor"
}

/**
 * La casa e i luoghi abituali di una finestra di tempo. [gpsDays] i giorni con GPS della finestra (la quota si ricalcola da qui).
 * Un giorno e' "lontano" se la mediana delle distanze dal luogo piu' vicino supera [farKm] (D19: max(50 km, 3 volte il raggio di casa)).
 */
internal class Anchors(val home: Anchor, val habitual: List<Anchor>, val gpsDays: Int) {
  val all: List<Anchor> = listOf(home) + habitual

  val farKm: Double = maxOf(TripRules.FAR_MIN_KM, TripRules.FAR_HOME_RADIUS_FACTOR * home.radiusKm)

  fun nearestKm(lat: Double, lon: Double): Double {
    var best = Double.MAX_VALUE
    for (a in all) {
      val d = TripGeometry.km(a.lat, a.lon, lat, lon)
      if (d < best) best = d
    }
    return best
  }

  /** Vicino a casa o a un luogo abituale: quel posto non va nei titoli (rivelerebbe dove si abita) ne' fra i luoghi di un viaggio. */
  fun isNear(lat: Double, lon: Double): Boolean = all.any { TripGeometry.km(it.lat, it.lon, lat, lon) <= maxOf(NEAR_KM, it.radiusKm) }

  override fun toString(): String = "Anchors(${all.size})"

  companion object {
    const val NEAR_KM: Double = 20.0
  }
}

/**
 * Le foto proprie con un GPS utilizzabile, in colonne: un indice per giorno (giorni dell'epoca, ordine crescente) che permette di leggere
 * una finestra di tempo senza scorrere tutto. [shots] arrivano in ordine di giorno (i candidati escono per giorno, istante e id); se non lo
 * sono si ordinano.
 */
internal class GeoIndex(shots: List<AlbumCandidate>) {
  val size: Int
  val epochDay: IntArray
  val lat: DoubleArray
  val lon: DoubleArray
  val evening: BooleanArray

  /** I giorni distinti, e per ognuno l'indice del primo scatto (con un elemento in piu' in fondo: la fine). */
  val days: IntArray
  val dayStart: IntArray

  init {
    val sorted = if (shots.zipWithNext().all { (a, b) -> a.day <= b.day }) shots else shots.sortedBy { it.day }
    size = sorted.size
    epochDay = IntArray(size)
    lat = DoubleArray(size)
    lon = DoubleArray(size)
    evening = BooleanArray(size)
    val distinct = ArrayList<Int>()
    val starts = ArrayList<Int>()
    for ((i, c) in sorted.withIndex()) {
      val day = c.day.toEpochDay().toInt()
      val gps = checkNotNull(c.gps) { "GeoIndex vuole solo scatti con GPS." }
      epochDay[i] = day
      lat[i] = gps.latitude
      lon[i] = gps.longitude
      evening[i] = c.localTime?.hour?.let { it >= TripRules.EVENING_FROM_HOUR || it < TripRules.MORNING_UNTIL_HOUR } ?: false
      if (distinct.isEmpty() || distinct.last() != day) {
        distinct += day
        starts += i
      }
    }
    days = distinct.toIntArray()
    dayStart = IntArray(days.size + 1).also { out ->
      starts.forEachIndexed { i, s -> out[i] = s }
      out[days.size] = size
    }
  }

  /** L'indice (in [days]) del primo giorno che non precede [day]. */
  fun dayLowerBound(day: Int): Int {
    var lo = 0
    var hi = days.size
    while (lo < hi) {
      val mid = (lo + hi) ushr 1
      if (days[mid] < day) lo = mid + 1 else hi = mid
    }
    return lo
  }

  override fun toString(): String = "GeoIndex($size)"
}

internal object HomeEstimator {
  /** Almeno questi giorni sul luogo perche' sia "casa" (o abituale): poche foto di una vacanza non fanno una casa. */
  const val MIN_ANCHOR_DAYS: Int = 5

  /** Al piu' questi luoghi abituali oltre alla casa. */
  const val MAX_HABITUAL: Int = 4

  /** Il raggio di casa e' il 90° percentile delle foto della casa entro questa distanza dal centro. */
  const val RADIUS_CONTEXT_KM: Double = 30.0

  /** Due celle a questa distanza (in passi) o meno sono lo stesso posto: i blocchi 3x3 si sovrapporrebbero. */
  private const val SAME_PLACE_STEPS: Int = 2

  /**
   * Un luogo abituale (non la casa) non e' un viaggio perche' ci si torna: serve oltre il 10% dei giorni **e** almeno due visite separate (piu' di
   * [VISIT_GAP_DAYS] giorni senza foto serali li' in mezzo) **oppure** una presenza continua di almeno [MIN_RUN_DAYS] giorni (un'altra casa, un
   * trasloco). Un solo soggiorno piu' breve, anche di un mese, e' una vacanza: senza questa condizione il viaggio piu' lungo dell'anno, che da solo
   * supera il 10% dei giorni, non sarebbe mai proposto. (Scostamento dal progetto, §2.3: li' basta il 10%. docs/decisioni.md, "Fase 7 P7".)
   */
  const val VISIT_GAP_DAYS: Int = 10
  const val MIN_RUN_DAYS: Int = 45

  /**
   * La casa e i luoghi abituali dai giorni [fromDay]..[toDay] (giorni dell'epoca, estremi compresi), o null se non si stima (E-AL16).
   * **Ripiego**: se con le sole foto serali nessun luogo arriva al 15% (chi non fotografa a casa la sera), si riprova con tutte le ore;
   * e' lo stesso conto su piu' giorni, e scatta solo quando altrimenti non ci sarebbe nessuna casa e nessun viaggio.
   */
  fun estimate(g: GeoIndex, fromDay: Int, toDay: Int): Anchors? =
    estimate(g, fromDay, toDay, eveningOnly = true) ?: estimate(g, fromDay, toDay, eveningOnly = false)

  /** Un blocco di celle: i giorni con foto serali, le visite separate e la presenza continua piu' lunga (in giorni). */
  private class Block(val cell: Long, val days: Int, val visits: Int, val longestRun: Int)

  private class DayList {
    var data = IntArray(4)
    var size = 0

    fun addDistinctLast(day: Int) {
      if (size > 0 && data[size - 1] == day) return
      if (size == data.size) data = data.copyOf(size * 2)
      data[size++] = day
    }
  }

  private fun estimate(g: GeoIndex, fromDay: Int, toDay: Int, eveningOnly: Boolean): Anchors? {
    val d0 = g.dayLowerBound(fromDay)
    val d1 = g.dayLowerBound(toDay + 1)
    val gpsDays = d1 - d0
    if (gpsDays <= 0) return null
    val s0 = g.dayStart[d0]
    val s1 = g.dayStart[d1]

    val byCell = HashMap<Long, DayList>()
    for (i in s0 until s1) {
      if (eveningOnly && !g.evening[i]) continue
      byCell.getOrPut(TripGeometry.cellOf(g.lat[i], g.lon[i])) { DayList() }.addDistinctLast(g.epochDay[i])
    }
    if (byCell.isEmpty()) return null

    val span = BitSet(toDay - fromDay + 1)
    val blocks = byCell.keys.map { cell ->
      span.clear()
      for (neighbour in TripGeometry.block(cell)) {
        val list = byCell[neighbour] ?: continue
        for (k in 0 until list.size) span.set(list.data[k] - fromDay)
      }
      // Le visite: serie di giorni con al piu' VISIT_GAP_DAYS giorni di distanza l'uno dall'altro; la presenza continua e' la serie piu' lunga.
      var visits = 0
      var longest = 0
      var runStart = -1
      var previous = -1
      var day = span.nextSetBit(0)
      while (day >= 0) {
        if (runStart < 0 || day - previous > VISIT_GAP_DAYS) {
          if (runStart >= 0) longest = maxOf(longest, previous - runStart + 1)
          visits++
          runStart = day
        }
        previous = day
        day = span.nextSetBit(day + 1)
      }
      if (runStart >= 0) longest = maxOf(longest, previous - runStart + 1)
      Block(cell, span.cardinality(), visits, longest)
    }.sortedWith(compareByDescending<Block> { it.days }.thenBy { TripGeometry.latOf(it.cell) }.thenBy { TripGeometry.lonOf(it.cell) })

    val chosen = ArrayList<Block>()
    for (b in blocks) {
      val share = b.days.toDouble() / gpsDays
      val min = if (chosen.isEmpty()) TripRules.HOME_MIN_SHARE else TripRules.HABITUAL_MIN_SHARE
      if (share < min || b.days < MIN_ANCHOR_DAYS) break
      if (chosen.any { TripGeometry.closeCells(it.cell, b.cell, SAME_PLACE_STEPS) }) continue
      // Un solo soggiorno breve non fa un luogo abituale: e' un viaggio (la casa e' la casa, qualunque sia la sua storia).
      if (chosen.isNotEmpty() && b.visits < 2 && b.longestRun < MIN_RUN_DAYS) continue
      chosen += b
      if (chosen.size > MAX_HABITUAL) break
    }
    if (chosen.isEmpty()) return null

    val anchors = chosen.map { anchorOf(g, s0, s1, eveningOnly, it) }
    return Anchors(anchors.first(), anchors.drop(1), gpsDays)
  }

  /** Il centro (la media delle foto del blocco) e il raggio (90° percentile entro [RADIUS_CONTEXT_KM], almeno 2 km). */
  private fun anchorOf(g: GeoIndex, s0: Int, s1: Int, eveningOnly: Boolean, block: Block): Anchor {
    val cells = TripGeometry.block(block.cell).toHashSet()
    var sumLat = 0.0
    var sumLon = 0.0
    var n = 0
    for (i in s0 until s1) {
      if (eveningOnly && !g.evening[i]) continue
      if (TripGeometry.cellOf(g.lat[i], g.lon[i]) !in cells) continue
      sumLat += g.lat[i]
      sumLon += g.lon[i]
      n++
    }
    val lat = sumLat / n
    val lon = sumLon / n
    val near = ArrayList<Double>()
    for (i in s0 until s1) {
      if (eveningOnly && !g.evening[i]) continue
      val d = TripGeometry.km(lat, lon, g.lat[i], g.lon[i])
      if (d <= RADIUS_CONTEXT_KM) near += d
    }
    val sorted = near.toDoubleArray().also { it.sort() }
    val radius = if (sorted.isEmpty()) TripRules.HOME_RADIUS_MIN_KM else maxOf(TripRules.HOME_RADIUS_MIN_KM, TripGeometry.percentile(sorted, TripRules.HOME_RADIUS_PERCENTILE))
    return Anchor(lat, lon, radius, block.days)
  }
}

/**
 * La casa **nel tempo** (E-AL15, "trasloco"). Ogni mese ha la sua stima, fatta sui 12 mesi attorno a lui (6 prima, 6 dopo, ma mai oltre
 * oggi: per i mesi recenti e' proprio "gli ultimi 12 mesi" di D18), cosi' i giorni di una casa lasciata da piu' di un anno non diventano
 * viaggi e i viaggi gia' proposti non cambiano quando la casa cambia. Un mese senza stima (pochi giorni con GPS) prende quella del mese
 * piu' vicino entro 6 mesi; oltre non si sa: quei giorni non si giudicano.
 *
 * [current] e' la stima di oggi (gli ultimi 12 mesi): e' quella che si conserva in `home_estimate` e che usa `AwayFromHome`.
 */
internal class HomeTimeline private constructor(private val byMonth: Map<Int, Anchors?>, val current: Anchors?) {

  /** Le sole stime che servono a giudicare il giorno [epochDay]; null se non si sa dov'e' casa. */
  fun at(epochDay: Int): Anchors? = byMonth[monthKey(LocalDate.ofEpochDay(epochDay.toLong()))]

  val known: Boolean get() = current != null || byMonth.values.any { it != null }

  override fun toString(): String = "HomeTimeline(${byMonth.size})"

  companion object {
    /** I mesi attorno a quello da stimare. */
    private const val CONTEXT_MONTHS: Long = 6
    private const val BORROW_MONTHS: Int = 6

    fun monthKey(day: LocalDate): Int = day.year * 12 + day.monthValue - 1

    fun build(g: GeoIndex, today: LocalDate): HomeTimeline {
      val window = TripRules.HOME_WINDOW_MONTHS
      val cache = HashMap<Pair<Int, Int>, Anchors?>()
      fun estimateIn(from: LocalDate, to: LocalDate): Anchors? =
        cache.getOrPut(from.toEpochDay().toInt() to to.toEpochDay().toInt()) { HomeEstimator.estimate(g, from.toEpochDay().toInt(), to.toEpochDay().toInt()) }

      val months = g.days.map { monthKey(LocalDate.ofEpochDay(it.toLong())) }.distinct().sorted()
      val firstDay = LocalDate.ofEpochDay(g.days.first().toLong())
      val own = LinkedHashMap<Int, Anchors?>()
      for (key in months) {
        val first = LocalDate.of(key / 12, key % 12 + 1, 1)
        var end = minOf(first.plusMonths(1).minusDays(1).plusMonths(CONTEXT_MONTHS), today)
        var start = end.minusMonths(window).plusDays(1)
        if (start.isBefore(firstDay)) {
          // Ai bordi della Libreria la finestra non si accorcia (poche settimane di dati fanno quote troppo ballerine): scorre in avanti.
          start = firstDay
          end = minOf(start.plusMonths(window).minusDays(1), today)
        }
        own[key] = estimateIn(start, end)
      }
      val filled = LinkedHashMap<Int, Anchors?>()
      for (key in months) {
        filled[key] = own[key] ?: months.filter { own[it] != null && kotlin.math.abs(it - key) <= BORROW_MONTHS }
          .minWithOrNull(compareBy<Int> { kotlin.math.abs(it - key) }.thenByDescending { it })?.let { own[it] }
      }
      val current = estimateIn(today.minusMonths(window).plusDays(1), today)
      return HomeTimeline(filled, current)
    }
  }
}
