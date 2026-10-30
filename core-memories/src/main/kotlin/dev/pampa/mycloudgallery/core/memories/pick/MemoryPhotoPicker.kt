package dev.pampa.mycloudgallery.core.memories.pick

import dev.pampa.mycloudgallery.core.memories.contract.CandidateCell
import dev.pampa.mycloudgallery.core.memories.contract.DropReason
import dev.pampa.mycloudgallery.core.memories.contract.MemoryExclusions
import dev.pampa.mycloudgallery.core.memories.contract.PhotoPicker
import dev.pampa.mycloudgallery.core.memories.contract.PhotoScore
import dev.pampa.mycloudgallery.core.memories.contract.PickResult
import dev.pampa.mycloudgallery.core.memories.contract.PickSignals
import dev.pampa.mycloudgallery.core.memories.contract.PickSpec
import dev.pampa.mycloudgallery.core.memories.contract.PickWeights
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.model.MediaSource
import java.util.EnumMap
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * La scelta delle foto di un ricordo (Fase 9, P7; `docs/fase9-progetto.md` §2.2, D5-D7, risposta Q6 (a): **nessun filtro sul contenuto**, solo
 * i comandi dell'utente). Fa le stesse cose di `ReferencePicker` (testing/) con gli stessi passi e lo stesso ordine, ma senza i costi
 * quadratici inutili: norme dei vettori calcolate una volta sola, somiglianza massima con le gia' scelte aggiornata a ogni scelta invece che
 * da capo, contatori per giorno e per ora al posto di scansioni. Gli elementi e la copertina sono quelli del riferimento (test di parita'
 * su ingressi casuali).
 *
 * I passi:
 *  1. esclusioni di D5 e "Mai" (periodi e persone), contate per motivo ([MemoryExclusions], una sola definizione);
 *  2. punteggio ([PhotoScore], pesi in [PickWeights]) e ordine: punteggio decrescente, a parita' lo scatto piu' vecchio, poi l'id minore;
 *  3. doppioni: resta il primo dell'ordine; coseno >= 0,92 se ci sono i due vettori, altrimenti la firma visiva se risponde, altrimenti
 *     3 minuti;
 *  4. varieta': con `perDayMin` >= 1 prima la migliore di ogni giorno, poi la massima rilevanza marginale (punteggio - 0,5 x somiglianza
 *     massima con le gia' scelte) fino a `maxSize`, con i tetti per giorno, per ora e di WhatsApp;
 *  5. WhatsApp al piu' un quarto (si tolgono le peggiori); sotto `minSize` il ricordo non nasce;
 *  6. copertina: il miglior elemento che non e' un video, non e' di WhatsApp, non ha persone "Mostra meno", orizzontale o quadrato se c'e';
 *  7. elementi in ordine di scatto.
 *
 * **Pura e deterministica**: nessuno stato fra una chiamata e l'altra, nessun orologio, nessun log, nessuna scrittura sugli ingressi. Il
 * risultato porta soli conteggi per motivo. Non conosce i nascosti: li toglie prima chi la chiama (`VisiblePhotoPicker`).
 */
class MemoryPhotoPicker : PhotoPicker {

  override fun pick(candidates: List<CandidateCell>, spec: PickSpec, signals: PickSignals): PickResult {
    val dropped = IntArray(REASONS.size)

    // 1. Esclusioni (la prima ripetizione di un id vale, le altre si ignorano senza contarle).
    val seen = HashSet<MediaId>(candidates.size * 2)
    val cells = ArrayList<CandidateCell>(candidates.size)
    for (cell in candidates) {
      if (!seen.add(cell.id)) continue
      val reason = MemoryExclusions.of(cell, spec.dateBound, signals.choices)
        ?: if (signals.hasNeverPerson(cell.id)) DropReason.NEVER_PERSON else null
      if (reason != null) dropped[reason.ordinal]++ else cells += cell
    }
    val n = cells.size

    // 2. Punteggio e ordine.
    val score = DoubleArray(n) { PhotoScore.of(cells[it], signals) }
    val byScore = Comparator<Int> { a, b -> compareRank(cells, score, a, b) }
    val ordered = (0 until n).sortedWith(byScore)

    val vectors = Vectors(cells, signals)

    // 3. Doppioni: ogni cella contro le gia' tenute, nell'ordine di punteggio.
    val kept = IntArray(n)
    var keptCount = 0
    for (i in ordered) {
      var duplicate = false
      for (k in 0 until keptCount) {
        if (isDuplicate(kept[k], i, cells, vectors, signals)) {
          duplicate = true
          break
        }
      }
      if (duplicate) dropped[DropReason.DUPLICATE.ordinal]++ else kept[keptCount++] = i
    }

    // 4. Varieta' con i tetti.
    val state = Selection(cells, vectors, keptCount, kept, spec)
    if (spec.perDayMin >= 1) {
      val byDay = java.util.TreeMap<Int, MutableList<Int>>()
      for (k in 0 until keptCount) byDay.getOrPut(cells[kept[k]].day) { ArrayList() }.add(kept[k])
      for ((_, ofDay) in byDay) {
        if (state.size >= spec.maxSize) break
        ofDay.firstOrNull { !state.isSelected(it) && state.fits(it) }?.let { state.select(it) }
      }
    }
    while (state.size < spec.maxSize) {
      var best = -1
      var bestMmr = 0.0
      for (k in 0 until keptCount) {
        val i = kept[k]
        if (state.isSelected(i) || !state.fits(i)) continue
        val mmr = state.marginal(i, score[i])
        if (best < 0 || compareMarginal(cells, mmr, i, bestMmr, best) < 0) {
          best = i
          bestMmr = mmr
        }
      }
      if (best < 0) break
      state.select(best)
    }
    dropped[DropReason.CAPPED.ordinal] += keptCount - state.size

    // 5. WhatsApp al piu' un quarto, poi il minimo.
    while (state.whatsapp * 4 > state.size) {
      var worst = -1
      for (i in state.selectedInOrder()) {
        if (cells[i].source != MediaSource.WHATSAPP) continue
        if (worst < 0 || compareWorst(cells, score, i, worst) < 0) worst = i
      }
      state.remove(worst)
      dropped[DropReason.WHATSAPP_SHARE.ordinal]++
    }
    if (state.size < spec.minSize) {
      dropped[DropReason.TOO_FEW.ordinal]++
      return PickResult.rejected(counts(dropped))
    }

    // 6. Copertina.
    var coverPool = state.selectedInOrder().filter {
      cells[it].kind != MediaKind.VIDEO && cells[it].source != MediaSource.WHATSAPP && !signals.hasLessPerson(cells[it].id)
    }
    if (coverPool.isEmpty()) {
      dropped[DropReason.NO_COVER.ordinal]++
      return PickResult.rejected(counts(dropped))
    }
    val wide = coverPool.filter { cells[it].aspectMilli >= SQUARE }
    if (wide.isNotEmpty()) coverPool = wide
    val cover = coverPool.minWithOrNull(byScore)!!

    // 7. Ordine di scatto.
    val members = state.selectedInOrder().sortedWith { a, b -> compareTime(cells, a, b) }.map { cells[it].id }
    return PickResult(members, cells[cover].id, counts(dropped))
  }

  override fun toString(): String = "MemoryPhotoPicker"

  // --- Ordinamenti (gli stessi del riferimento) ---

  private fun compareRank(cells: List<CandidateCell>, score: DoubleArray, a: Int, b: Int): Int {
    val s = java.lang.Double.compare(score[b], score[a])
    if (s != 0) return s
    return compareTime(cells, a, b)
  }

  private fun compareTime(cells: List<CandidateCell>, a: Int, b: Int): Int {
    val t = cells[a].timeMs.compareTo(cells[b].timeMs)
    if (t != 0) return t
    return cells[a].id.value.compareTo(cells[b].id.value)
  }

  /** Fra due con la stessa rilevanza: vince lo scatto piu' vecchio, poi l'id minore. Negativo = [a] meglio di [b]. */
  private fun compareMarginal(cells: List<CandidateCell>, mmrA: Double, a: Int, mmrB: Double, b: Int): Int {
    val m = java.lang.Double.compare(mmrB, mmrA)
    if (m != 0) return m
    return compareTime(cells, a, b)
  }

  /** Il peggiore e' quello col punteggio piu' basso, a parita' il piu' recente, poi l'id maggiore. Negativo = [a] peggio di [b]. */
  private fun compareWorst(cells: List<CandidateCell>, score: DoubleArray, a: Int, b: Int): Int {
    val s = java.lang.Double.compare(score[a], score[b])
    if (s != 0) return s
    val t = cells[b].timeMs.compareTo(cells[a].timeMs)
    if (t != 0) return t
    return cells[b].id.value.compareTo(cells[a].id.value)
  }

  private fun isDuplicate(a: Int, b: Int, cells: List<CandidateCell>, vectors: Vectors, signals: PickSignals): Boolean {
    if (vectors.has(a) && vectors.has(b)) return vectors.cosine(a, b) >= PickWeights.DUPLICATE_COSINE
    signals.lookAlike?.similar(cells[a].id, cells[b].id)?.let { return it }
    return abs(cells[a].timeMs - cells[b].timeMs) <= PickWeights.DUPLICATE_WINDOW_MS
  }

  private fun counts(dropped: IntArray): Map<DropReason, Int> {
    val map = EnumMap<DropReason, Int>(DropReason::class.java)
    for (reason in REASONS) if (dropped[reason.ordinal] > 0) map[reason] = dropped[reason.ordinal]
    return map
  }

  /** I vettori dei candidati come array per posizione, con la norma gia' calcolata: il coseno costa solo il prodotto scalare. */
  private class Vectors(cells: List<CandidateCell>, signals: PickSignals) {
    private val vectors: Array<FloatArray?> = Array(cells.size) { signals.vectors[cells[it].id] }
    private val norms = DoubleArray(cells.size) { i ->
      vectors[i]?.let { v ->
        var sum = 0.0
        for (x in v) sum += x * x
        sqrt(sum)
      } ?: 0.0
    }

    fun has(i: Int): Boolean = vectors[i] != null

    /** Come `PhotoScore.cosine`: 0 se le lunghezze differiscono o una norma e' nulla. */
    fun cosine(a: Int, b: Int): Double {
      val va = vectors[a] ?: return 0.0
      val vb = vectors[b] ?: return 0.0
      if (va.size != vb.size || norms[a] == 0.0 || norms[b] == 0.0) return 0.0
      var dot = 0.0
      for (k in va.indices) dot += va[k] * vb[k]
      return dot / (norms[a] * norms[b])
    }
  }

  /** Le scelte fatte finora, con i contatori dei tetti e la somiglianza massima di ogni candidato con le scelte. */
  private class Selection(
    private val cells: List<CandidateCell>,
    private val vectors: Vectors,
    private val keptCount: Int,
    private val kept: IntArray,
    spec: PickSpec,
  ) {
    private val perDayMax = spec.perDayMax
    private val perHourMax = spec.perHourMax
    private val whatsappCap = (spec.maxSize * PickWeights.WHATSAPP_MAX_SHARE).toInt()
    private val selected = LinkedHashSet<Int>()
    private val perDay = HashMap<Int, Int>()
    private val perHour = HashMap<Long, Int>()
    private val maxSimilarity = DoubleArray(cells.size)

    var whatsapp: Int = 0
      private set
    val size: Int get() = selected.size

    fun isSelected(i: Int): Boolean = i in selected

    fun selectedInOrder(): List<Int> = selected.toList()

    fun fits(i: Int): Boolean {
      val cell = cells[i]
      if ((perDay[cell.day] ?: 0) >= perDayMax) return false
      if ((perHour[cell.timeMs / HOUR_MS] ?: 0) >= perHourMax) return false
      if (cell.source == MediaSource.WHATSAPP && whatsapp >= whatsappCap) return false
      return true
    }

    /** Punteggio meno 0,5 x la somiglianza massima con le gia' scelte (solo dove c'e' il vettore). */
    fun marginal(i: Int, score: Double): Double =
      if (vectors.has(i)) score - PickWeights.MMR_LAMBDA * maxSimilarity[i] else score

    fun select(i: Int) {
      selected += i
      count(i, +1)
      if (!vectors.has(i)) return
      for (k in 0 until keptCount) {
        val other = kept[k]
        if (other == i || !vectors.has(other)) continue
        val similarity = vectors.cosine(other, i)
        if (similarity > maxSimilarity[other]) maxSimilarity[other] = similarity
      }
    }

    fun remove(i: Int) {
      selected -= i
      count(i, -1)
    }

    private fun count(i: Int, delta: Int) {
      val cell = cells[i]
      perDay.merge(cell.day, delta, Int::plus)
      perHour.merge(cell.timeMs / HOUR_MS, delta, Int::plus)
      if (cell.source == MediaSource.WHATSAPP) whatsapp += delta
    }
  }

  private companion object {
    const val HOUR_MS: Long = 3_600_000
    const val SQUARE: Int = 1_000
    val REASONS: Array<DropReason> = DropReason.entries.toTypedArray()
  }
}
