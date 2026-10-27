package dev.pampa.mycloudgallery.core.memories.testing

import dev.pampa.mycloudgallery.core.memories.contract.CandidateCell
import dev.pampa.mycloudgallery.core.memories.contract.DropReason
import dev.pampa.mycloudgallery.core.memories.contract.MemoryExclusions
import dev.pampa.mycloudgallery.core.memories.contract.PhotoPicker
import dev.pampa.mycloudgallery.core.memories.contract.PhotoScore
import dev.pampa.mycloudgallery.core.memories.contract.PickResult
import dev.pampa.mycloudgallery.core.memories.contract.PickSignals
import dev.pampa.mycloudgallery.core.memories.contract.PickSpec
import dev.pampa.mycloudgallery.core.memories.contract.PickWeights
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.model.MediaSource
import java.util.EnumMap
import kotlin.math.abs

/**
 * La scelta delle foto di riferimento (Fase 9, P0, D5-D7, §5.2): esclusioni, punteggio ([PhotoScore]), doppioni confrontando **tutte le
 * coppie**, varieta' a massima rilevanza marginale ricalcolata da capo a ogni passo, tetti, WhatsApp, copertina. Nessuna ottimizzazione: e'
 * l'oracolo di P7, che deve dare gli stessi elementi e la stessa copertina salvo i casi a +-1e-4 dalle soglie (§5.2).
 *
 * L'ordine dei passi e le regole di parita' sono il contratto (`PickerContract`):
 *  1. esclusioni di D5 e "Mai" (periodi, persone), contate per motivo;
 *  2. ordine per punteggio decrescente, a parita' lo scatto piu' vecchio, poi l'id minore;
 *  3. doppioni in quest'ordine: resta il primo; coseno >= 0,92 se ci sono i due vettori, altrimenti la firma se risponde, altrimenti 3 minuti;
 *  4. con `perDayMin` >= 1 prima la migliore di ogni giorno (dal giorno piu' vecchio), poi la massima rilevanza marginale (punteggio - 0,5 x
 *     coseno massimo con le gia' scelte, 0 senza vettori) fino a `maxSize`, rispettando i tetti per giorno, per ora e di WhatsApp;
 *  5. WhatsApp al piu' un quarto alla fine (si tolgono le peggiori); sotto `minSize` il ricordo non nasce;
 *  6. copertina: la migliore fra le foto non video, non WhatsApp, senza persone "Mostra meno", orizzontali o quadrate se ce ne sono;
 *  7. elementi in ordine di scatto.
 */
class ReferencePicker : PhotoPicker {

  override fun pick(candidates: List<CandidateCell>, spec: PickSpec, signals: PickSignals): PickResult {
    val dropped = EnumMap<DropReason, Int>(DropReason::class.java)
    fun drop(reason: DropReason, n: Int = 1) {
      if (n > 0) dropped[reason] = (dropped[reason] ?: 0) + n
    }

    // 1. Esclusioni.
    val ok = ArrayList<CandidateCell>()
    for (cell in candidates.distinctBy { it.id }) {
      val reason = MemoryExclusions.of(cell, spec.dateBound, signals.choices)
        ?: if (signals.hasNeverPerson(cell.id)) DropReason.NEVER_PERSON else null
      if (reason != null) drop(reason) else ok += cell
    }

    // 2. Punteggio e ordine.
    val score = ok.associate { it.id to PhotoScore.of(it, signals) }
    val byScore = compareByDescending<CandidateCell> { score.getValue(it.id) }.thenBy { it.timeMs }.thenBy { it.id.value }
    val ordered = ok.sortedWith(byScore)

    // 3. Doppioni: tutte le coppie con le gia' tenute.
    val kept = ArrayList<CandidateCell>()
    for (cell in ordered) {
      if (kept.any { duplicate(it, cell, signals) }) drop(DropReason.DUPLICATE) else kept += cell
    }

    // 4. Varieta' con i tetti.
    val selected = ArrayList<CandidateCell>()
    val whatsappCap = (spec.maxSize * PickWeights.WHATSAPP_MAX_SHARE).toInt()
    fun fits(cell: CandidateCell): Boolean {
      if (selected.count { it.day == cell.day } >= spec.perDayMax) return false
      if (selected.count { it.timeMs / HOUR_MS == cell.timeMs / HOUR_MS } >= spec.perHourMax) return false
      if (cell.source == MediaSource.WHATSAPP && selected.count { it.source == MediaSource.WHATSAPP } >= whatsappCap) return false
      return true
    }
    if (spec.perDayMin >= 1) {
      for (day in kept.map { it.day }.distinct().sorted()) {
        if (selected.size >= spec.maxSize) break
        kept.firstOrNull { it.day == day && it !in selected && fits(it) }?.let { selected += it }
      }
    }
    while (selected.size < spec.maxSize) {
      val remaining = kept.filter { it !in selected && fits(it) }
      if (remaining.isEmpty()) break
      val best = remaining.sortedWith(
        compareByDescending<CandidateCell> { mmr(it, selected, score.getValue(it.id), signals) }.thenBy { it.timeMs }.thenBy { it.id.value },
      ).first()
      selected += best
    }
    drop(DropReason.CAPPED, kept.size - selected.size)

    // 5. WhatsApp al piu' un quarto, poi il minimo.
    while (selected.count { it.source == MediaSource.WHATSAPP } * 4 > selected.size) {
      val worst = selected.filter { it.source == MediaSource.WHATSAPP }
        .sortedWith(compareBy<CandidateCell> { score.getValue(it.id) }.thenByDescending { it.timeMs }.thenByDescending { it.id.value }).first()
      selected -= worst
      drop(DropReason.WHATSAPP_SHARE)
    }
    if (selected.size < spec.minSize) {
      drop(DropReason.TOO_FEW)
      return PickResult.rejected(dropped)
    }

    // 6. Copertina.
    val eligible = selected.filter { it.kind != MediaKind.VIDEO && it.source != MediaSource.WHATSAPP && !signals.hasLessPerson(it.id) }
    if (eligible.isEmpty()) {
      drop(DropReason.NO_COVER)
      return PickResult.rejected(dropped)
    }
    val preferred = eligible.filter { it.aspectMilli >= SQUARE }.ifEmpty { eligible }
    val cover = preferred.sortedWith(byScore).first()

    // 7. Ordine di scatto.
    val members = selected.sortedWith(compareBy<CandidateCell> { it.timeMs }.thenBy { it.id.value }).map { it.id }
    return PickResult(members, cover.id, dropped)
  }

  private fun duplicate(a: CandidateCell, b: CandidateCell, signals: PickSignals): Boolean {
    val va = signals.vectors[a.id]
    val vb = signals.vectors[b.id]
    if (va != null && vb != null) return PhotoScore.cosine(va, vb) >= PickWeights.DUPLICATE_COSINE
    signals.lookAlike?.similar(a.id, b.id)?.let { return it }
    return abs(a.timeMs - b.timeMs) <= PickWeights.DUPLICATE_WINDOW_MS
  }

  private fun mmr(cell: CandidateCell, selected: List<CandidateCell>, score: Double, signals: PickSignals): Double {
    val v = signals.vectors[cell.id] ?: return score
    var maxSim = 0.0
    for (other in selected) {
      val w = signals.vectors[other.id] ?: continue
      val sim = PhotoScore.cosine(v, w)
      if (sim > maxSim) maxSim = sim
    }
    return score - PickWeights.MMR_LAMBDA * maxSim
  }

  private companion object {
    const val HOUR_MS: Long = 3_600_000
    const val SQUARE: Int = 1_000
  }
}
