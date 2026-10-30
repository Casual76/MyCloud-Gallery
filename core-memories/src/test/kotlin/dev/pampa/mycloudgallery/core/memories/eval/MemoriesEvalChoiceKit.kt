package dev.pampa.mycloudgallery.core.memories.eval

import dev.pampa.mycloudgallery.core.memories.contract.CandidateCell
import dev.pampa.mycloudgallery.core.memories.contract.MemoryKind
import dev.pampa.mycloudgallery.core.memories.contract.PickSignals
import dev.pampa.mycloudgallery.core.memories.contract.PickSpec
import dev.pampa.mycloudgallery.core.memories.contract.PickWeights
import dev.pampa.mycloudgallery.core.memories.pick.MemoryPhotoPicker
import dev.pampa.mycloudgallery.core.model.MediaId
import java.time.ZoneOffset
import java.util.Locale
import kotlinx.coroutines.runBlocking

// LA REGOLA DI SCELTA DI PESI E SOGLIE (docs/fase9-progetto.md §5.3 punto 3, riga P11 del §4.4), scritta in codice prima di guardare i numeri, e le misure che
// la alimentano sulla Libreria sintetica. Solo test: la regola e' un metodo di lavoro, non una funzione dell'app.
//
// "Se le misure mancano, si cambiano **solo** i parametri, guardando i numeri e mai le foto." La regola:
//  1. si provano alcuni valori di un parametro, tutti con lo stesso mondo, e per ognuno si dice se **regge i criteri** (quelli di §5.3, piu' un tetto al danno
//     collaterale che il parametro puo' fare);
//  2. se il valore provvisorio regge, **resta** (senza una prova contraria non si tocca un valore di progetto);
//  3. altrimenti si prende il valore che regge **piu' vicino** al provvisorio (il cambiamento piu' piccolo), a parita' il piu' basso;
//  4. se nessun valore regge, si prende quello col margine migliore e si dice che l'obiettivo **non e' raggiungibile** cosi' (ci vuole altro, non un numero).

/** Un valore provato: [holds] dice se regge i criteri; [margin] (piu' alto e' meglio) serve a scegliere quando nessuno regge; [figures] sono le misure da scrivere. */
internal class Trial<T>(val value: T, val figures: String, val holds: Boolean, val margin: Double)

/** Il valore scelto, se e' diverso dal provvisorio e se i criteri si possono raggiungere con questo parametro. */
internal class Choice<T>(val value: T, val changed: Boolean, val reachable: Boolean)

internal object MemoriesEvalChoice {
  fun <T> choose(provisional: T, trials: List<Trial<T>>, distance: (T, T) -> Double): Choice<T> {
    require(trials.any { it.value == provisional }) { "Il valore provvisorio e' fra i provati." }
    if (trials.first { it.value == provisional }.holds) return Choice(provisional, changed = false, reachable = true)
    val passing = trials.filter { it.holds }
    if (passing.isNotEmpty()) {
      val best = passing.minWithOrNull(compareBy<Trial<T>> { distance(it.value, provisional) }.thenBy { passing.indexOf(it) })!!
      return Choice(best.value, changed = true, reachable = true)
    }
    return Choice(trials.maxByOrNull { it.margin }!!.value, changed = true, reachable = false)
  }
}

// --- Le misure ----------------------------------------------------------------------------------------------------------------------------

/**
 * I doppioni a livello di scelta, per una finestra di [minutes] minuti: per ogni giorno con riprese ripetute la scelta (con un tetto di dimensione largo,
 * cosi' conta solo il doppione) tiene al piu' una ripresa del gruppo? [lost] sono le foto **uniche** (non doppioni, non WhatsApp) che la scelta ha tolto: il danno
 * collaterale di una finestra larga o di un coseno basso. La finestra si prova senza toccare il codice: due scatti sono doppioni se distano al piu'
 * `DUPLICATE_WINDOW_MS`, quindi riportare i tempi a un'altra finestra equivale a moltiplicarli per `DUPLICATE_WINDOW_MS / (minutes * 60.000)` (e' esatto: le altre
 * regole guardano solo l'ordine dei tempi).
 */
internal class DedupeFigures(val minutes: Double, val groups: Int, val removed: Int, val singles: Int, val lost: Int) {
  val recall: Double get() = removed.toDouble() / groups
  val lostShare: Double get() = lost.toDouble() / singles

  override fun toString(): String = String.format(
    Locale.ROOT, "finestra %.0f min: %d/%d doppioni tolti (%.1f%%), %d/%d foto uniche perse (%.2f%%)", minutes, removed, groups, recall * 100, lost, singles, lostShare * 100,
  )
}

internal fun dedupeFigures(world: FamilyWorld, minutes: Double = PickWeights.DUPLICATE_WINDOW_MS / 60_000.0): DedupeFigures {
  val spec = PickSpec(MemoryKind.ON_THIS_DAY, 3, 40, perDayMin = 0, perDayMax = 40, perHourMax = 40, dateBound = false)
  val scale = PickWeights.DUPLICATE_WINDOW_MS / (minutes * 60_000.0)
  var groups = 0
  var removed = 0
  var singles = 0
  var lost = 0
  for ((_, shots) in world.visibleShots.filter { !it.excluded }.groupBy { it.day }) {
    if (shots.size < 3) continue
    val cells = shots.map { s ->
      CandidateCell(
        s.id, s.day.atTime(0, 0).toInstant(ZoneOffset.UTC).toEpochMilli() + (s.secondOfDay * 1_000L * scale).toLong(), s.day.year * 10_000 + s.day.monthValue * 100 + s.day.dayOfMonth,
        s.kind, s.source, s.flags, 0, 1_333, null, false,
      )
    }
    val ids = cells.map { it.id }.toSet()
    val pick = MemoryPhotoPicker().pick(cells, spec, PickSignals(vectors = world.vectors.filterKeys { it in ids }, pixels = world.probes))
    for ((_, group) in shots.filter { it.dupGroup != null }.groupBy { it.dupGroup }.filterValues { it.size >= 2 }) {
      groups++
      if (group.count { it.id in pick.members } <= 1) removed++
    }
    val unique = shots.filter { it.dupGroup == null && it.source != dev.pampa.mycloudgallery.core.model.MediaSource.WHATSAPP }
    singles += unique.size
    lost += unique.count { it.id !in pick.members }
  }
  return DedupeFigures(minutes, groups, removed, singles, lost)
}

/**
 * La nitidezza: la corsa intera del motore vero con la soglia portata a [threshold] (le misure moltiplicate per `SHARPNESS_MIN / threshold`: una foto e' mossa se
 * `nitidezza < SHARPNESS_MIN`, quindi e' esatto). [blurryKept] sono le foto davvero mosse rimaste nei ricordi; [wrongPenalty] quelle davvero nitide che la soglia ha
 * punito (danno collaterale).
 */
internal class SharpnessFigures(val threshold: Int, val memories: Int, val photos: Int, val blurryKept: Int, val wrongPenalty: Int, val meanFaultShare: Double) {
  val blurryShare: Double get() = blurryKept.toDouble() / photos
  val wrongShare: Double get() = wrongPenalty.toDouble() / photos

  override fun toString(): String = String.format(
    Locale.ROOT, "soglia %d: %d ricordi, %d foto, %d mosse rimaste (%.2f%%), %d nitide punite (%.2f%%), da togliere in media %.1f%%", threshold, memories, photos, blurryKept, blurryShare * 100, wrongPenalty, wrongShare * 100, meanFaultShare * 100,
  )
}

internal fun sharpnessFigures(world: FamilyWorld, threshold: Int): SharpnessFigures = runBlocking {
  FamilyRig(world, Executor.REAL, sharpnessScale = PickWeights.SHARPNESS_MIN.toDouble() / threshold).use { rig ->
    val run = rig.run()
    val shots = run.flatMap { m -> m.members.map { world.shots.getValue(MediaId(it)) } }
    val punished = shots.count { s -> !s.blurry && (world.probes[s.id]?.sharpness ?: Float.MAX_VALUE) < threshold }
    val n = MemoriesEvalMetrics.of(FamilyIdealUser.label(world, run))
    SharpnessFigures(threshold, run.size, shots.size, shots.count { it.blurry }, punished, n.meanFaultShare)
  }
}

/**
 * Il coseno dei doppioni, direttamente sulle coppie seminate (non c'e' modo di portarlo a un'altra soglia senza toccare il codice): la quota delle riprese ripetute
 * (coseno col primo scatto della scena >= 0,94 per costruzione) che la soglia [threshold] riconosce, e la quota delle foto "simili ma diverse" (0,82-0,90, non
 * sono doppioni) che scambia per doppioni. Solo coppie con i due vettori: senza vettori non c'e' coseno.
 */
internal class CosineFigures(val threshold: Double, val retakes: Int, val caught: Int, val similar: Int, val wrong: Int) {
  val recall: Double get() = caught.toDouble() / retakes
  val falsePositive: Double get() = wrong.toDouble() / similar

  override fun toString(): String = String.format(
    Locale.ROOT, "coseno %.2f: %d/%d riprese riconosciute (%.1f%%), %d/%d simili scambiate per doppioni (%.1f%%)", threshold, caught, retakes, recall * 100, wrong, similar, falsePositive * 100,
  )
}

internal fun cosineFigures(world: FamilyWorld, threshold: Double): CosineFigures {
  val byGroup = world.shots.values.filter { it.dupGroup != null }.groupBy { it.dupGroup }
  var retakes = 0
  var caught = 0
  for ((_, group) in byGroup) {
    val first = group.minWithOrNull(compareBy<FamilyShot> { it.day }.thenBy { it.secondOfDay }.thenBy { it.id.value })!!
    val a = world.vectors[first.id] ?: continue
    for (other in group) {
      val b = world.vectors[other.id] ?: continue
      if (other === first) continue
      retakes++
      if (dev.pampa.mycloudgallery.core.memories.contract.PhotoScore.cosine(a, b) >= threshold) caught++
    }
  }
  var similar = 0
  var wrong = 0
  for (shot in world.shots.values) {
    val base = shot.similarTo ?: continue
    val a = world.vectors[shot.id] ?: continue
    val b = world.vectors[base] ?: continue
    similar++
    if (dev.pampa.mycloudgallery.core.memories.contract.PhotoScore.cosine(a, b) >= threshold) wrong++
  }
  return CosineFigures(threshold, retakes, caught, similar, wrong)
}
