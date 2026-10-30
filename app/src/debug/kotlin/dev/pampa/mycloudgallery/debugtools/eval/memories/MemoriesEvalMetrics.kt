package dev.pampa.mycloudgallery.debugtools.eval.memories

import java.util.Locale
import kotlin.math.sqrt

// LE METRICHE DELLA VALUTAZIONE DEI RICORDI (docs/fase9-progetto.md §5.3, riga P11 del §4.4): ricordi graditi, foto da togliere per ricordo,
// doppioni, ricordi "da non mostrare". Codice puro, senza dipendenze dal progetto: LO STESSO FILE sta in `core-memories/src/test/.../eval/` (provato
// con la Libreria sintetica) e in `app/src/debug/.../debugtools/eval/memories/` (lo strumento sul telefono); cambia solo la riga `package`, e
// `MemoriesEvalSourceParityTest` controlla che le due copie restino uguali.
//
// Un resoconto e' fatto di **soli numeri**: mai titoli, giorni, luoghi o id. Contano solo i ricordi con un giudizio. Con Q6 (a) non c'e' nessun
// filtro dei ricordi tristi: le foto "tristi" e i ricordi "da non mostrare" sono **misure**, non un filtro.

/**
 * Gli obiettivi di §5.3: ricordi "Bello" almeno il 70%; "Da non mostrare" al piu' il 3%; foto da togliere al piu' 1 su 12 (8,3%) in media per ricordo;
 * doppioni al piu' il 3% delle foto.
 */
data class MemoriesEvalTargets(
  val good: Double = 0.70,
  val dontShow: Double = 0.03,
  val removePerPhoto: Double = 1.0 / 12.0,
  val duplicates: Double = 0.03,
)

/** I numeri di un gruppo di ricordi (tutti, o di un solo tipo). */
data class MemoriesEvalNumbers(
  /** I ricordi giudicati. */
  val memories: Int,
  val good: Int,
  val soSo: Int,
  val dontShow: Int,
  /** Dei "Da non mostrare": quanti con il motivo triste, sbagliato, noioso (gli altri non dicono il motivo). */
  val dontShowSad: Int,
  val dontShowWrong: Int,
  val dontShowBoring: Int,
  /** Le foto dei ricordi giudicati. */
  val photos: Int,
  /** Le foto segnate come da togliere. */
  val faults: Int,
  val faultsByKind: Map<EvalFault, Int>,
  /** La somma, sui ricordi, della quota di foto da togliere di ciascuno (la media e' [meanFaultShare]). */
  val faultShareSum: Double,
  /** I ricordi senza nessuna foto da togliere. */
  val clean: Int,
) {
  /** "Bello" sui ricordi giudicati; NaN se non ce ne sono. */
  val goodShare: Double get() = ratio(good, memories)

  val dontShowShare: Double get() = ratio(dontShow, memories)

  /** Foto da togliere sul totale delle foto. */
  val faultShare: Double get() = ratio(faults, photos)

  /** La media, ricordo per ricordo, della quota di foto da togliere ("1 su 12 in media"). */
  val meanFaultShare: Double get() = if (memories == 0) Double.NaN else faultShareSum / memories

  /** I doppioni segnati sul totale delle foto. */
  val duplicateShare: Double get() = ratio(faultsByKind[EvalFault.DUPLICATE] ?: 0, photos)

  /** Le foto "tristi" segnate (solo una misura, Q6 (a)). */
  val sadFaults: Int get() = faultsByKind[EvalFault.SAD] ?: 0

  fun meets(targets: MemoriesEvalTargets): Boolean =
    memories > 0 && goodShare >= targets.good && dontShowShare <= targets.dontShow && meanFaultShare <= targets.removePerPhoto && duplicateShare <= targets.duplicates

  operator fun plus(other: MemoriesEvalNumbers): MemoriesEvalNumbers = MemoriesEvalNumbers(
    memories + other.memories, good + other.good, soSo + other.soSo, dontShow + other.dontShow, dontShowSad + other.dontShowSad,
    dontShowWrong + other.dontShowWrong, dontShowBoring + other.dontShowBoring, photos + other.photos, faults + other.faults,
    EvalFault.entries.associateWith { (faultsByKind[it] ?: 0) + (other.faultsByKind[it] ?: 0) }.filterValues { it > 0 },
    faultShareSum + other.faultShareSum, clean + other.clean,
  )

  companion object {
    val ZERO = MemoriesEvalNumbers(0, 0, 0, 0, 0, 0, 0, 0, 0, emptyMap(), 0.0, 0)

    private fun ratio(part: Int, whole: Int): Double = if (whole == 0) Double.NaN else part.toDouble() / whole
  }
}

object MemoriesEvalMetrics {

  /** I numeri di un insieme di ricordi giudicati (o di uno solo). I ricordi senza giudizio non contano. */
  fun of(labels: MemoriesLabelSet, only: (EvalMemory) -> Boolean = { true }): MemoriesEvalNumbers =
    labels.judged.filter(only).fold(MemoriesEvalNumbers.ZERO) { total, memory -> total + one(labels, memory) }

  private fun one(labels: MemoriesLabelSet, memory: EvalMemory): MemoriesEvalNumbers {
    val judgement = labels.judgement(memory.id)!!
    val faults = labels.faultsOf(memory.id)
    val dontShow = judgement.verdict == EvalVerdict.DONT_SHOW
    return MemoriesEvalNumbers(
      memories = 1,
      good = if (judgement.verdict == EvalVerdict.GOOD) 1 else 0,
      soSo = if (judgement.verdict == EvalVerdict.SO_SO) 1 else 0,
      dontShow = if (dontShow) 1 else 0,
      dontShowSad = if (dontShow && judgement.why == EvalWhy.SAD) 1 else 0,
      dontShowWrong = if (dontShow && judgement.why == EvalWhy.WRONG) 1 else 0,
      dontShowBoring = if (dontShow && judgement.why == EvalWhy.BORING) 1 else 0,
      photos = memory.members.size,
      faults = faults.size,
      faultsByKind = faults.values.groupingBy { it }.eachCount(),
      faultShareSum = faults.size.toDouble() / memory.members.size,
      clean = if (faults.isEmpty()) 1 else 0,
    )
  }

  /** I numeri per tipo di ricordo (solo i tipi con almeno un ricordo giudicato). */
  fun byKind(labels: MemoriesLabelSet): Map<String, MemoriesEvalNumbers> =
    MemoriesEvalKinds.CODES.associateWith { code -> of(labels) { it.kind == code } }.filterValues { it.memories > 0 }

  /** L'intervallo di Wilson al 95% di una quota [successes] su [total] (con 40-60 ricordi e' largo: il resoconto lo dice). */
  fun wilson(successes: Int, total: Int): Pair<Double, Double> {
    if (total == 0) return Double.NaN to Double.NaN
    val z = 1.96
    val p = successes.toDouble() / total
    val denominator = 1 + z * z / total
    val centre = (p + z * z / (2 * total)) / denominator
    val half = z * sqrt(p * (1 - p) / total + z * z / (4.0 * total * total)) / denominator
    return (centre - half).coerceAtLeast(0.0) to (centre + half).coerceAtMost(1.0)
  }

  fun report(labels: MemoriesLabelSet, targets: MemoriesEvalTargets = MemoriesEvalTargets()): MemoriesEvalReport = MemoriesEvalReport(labels, targets)
}

/** I numeri di F125: in totale e per tipo; solo numeri, da copiare in `docs/decisioni.md` (§5.3). */
class MemoriesEvalReport(labels: MemoriesLabelSet, val targets: MemoriesEvalTargets) {
  val total: MemoriesEvalNumbers = MemoriesEvalMetrics.of(labels)
  val byKind: Map<String, MemoriesEvalNumbers> = MemoriesEvalMetrics.byKind(labels)

  /** I ricordi della sessione che non hanno ancora un giudizio (non contano nei numeri). */
  val unjudged: Int = labels.pending.size

  /** Vero se tutti i ricordi della sessione hanno un giudizio e ce n'e' almeno uno. */
  val conclusive: Boolean get() = unjudged == 0 && total.memories > 0

  val meets: Boolean get() = total.meets(targets)

  fun toNumbersText(): String {
    val t = total
    val out = StringBuilder()
    out.append("F125, ricordi su ${t.memories} giudicati (${unjudged} senza giudizio): Bello >= ${pct(targets.good)}, Da non mostrare <= ${pct(targets.dontShow)}, ")
    out.append("foto da togliere <= ${pct(targets.removePerPhoto)} in media per ricordo, doppioni <= ${pct(targets.duplicates)} delle foto\n")
    out.append("tipo | ricordi | Bello | Cosi cosi | Da non mostrare | foto | da togliere | doppioni | tristi | media da togliere\n")
    out.append("---|---|---|---|---|---|---|---|---|---\n")
    for ((code, n) in byKind) out.append(line(MemoriesEvalKinds.label(code), n))
    out.append(line("totale", t))
    out.append('\n')
    val (goodLow, goodHigh) = MemoriesEvalMetrics.wilson(t.good, t.memories)
    val (noLow, noHigh) = MemoriesEvalMetrics.wilson(t.dontShow, t.memories)
    out.append("Bello: ${verdict(t.goodShare >= targets.good)} (${pct(t.goodShare)}, intervallo al 95%: ${pct(goodLow)}-${pct(goodHigh)})\n")
    out.append("Da non mostrare: ${verdict(t.dontShowShare <= targets.dontShow)} (${pct(t.dontShowShare)}, ${pct(noLow)}-${pct(noHigh)}); ")
    out.append("motivo: triste ${t.dontShowSad}, sbagliato ${t.dontShowWrong}, noioso ${t.dontShowBoring}\n")
    out.append("Foto da togliere in media: ${verdict(t.meanFaultShare <= targets.removePerPhoto)} (${pct(t.meanFaultShare)}; sul totale delle foto ${pct(t.faultShare)})\n")
    out.append("Doppioni: ${verdict(t.duplicateShare <= targets.duplicates)} (${pct(t.duplicateShare)} delle foto)\n")
    out.append("Foto da togliere per motivo: ")
    out.append(EvalFault.entries.joinToString(", ") { "${faultName(it)} ${t.faultsByKind[it] ?: 0}" }).append('\n')
    out.append("Foto tristi: ${t.sadFaults} (solo una misura: con Q6 (a) non c'e' nessun filtro)\n")
    out.append("Ricordi senza nessuna foto da togliere: ${t.clean}\n")
    out.append(if (unjudged == 0) "Misura completa.\n" else "Misura incompleta: $unjudged ricordi senza giudizio.\n")
    return out.toString()
  }

  private fun line(name: String, n: MemoriesEvalNumbers): String =
    "$name | ${n.memories} | ${n.good} | ${n.soSo} | ${n.dontShow} | ${n.photos} | ${n.faults} | ${n.faultsByKind[EvalFault.DUPLICATE] ?: 0} | ${n.sadFaults} | ${pct(n.meanFaultShare)}\n"

  override fun toString(): String = "MemoriesEvalReport(${total.memories} ricordi)"

  companion object {
    fun pct(v: Double): String = if (v.isNaN()) "-" else String.format(Locale.ROOT, "%.1f%%", v * 100)

    fun verdict(ok: Boolean): String = if (ok) "RAGGIUNTO" else "NON raggiunto"

    fun faultName(fault: EvalFault): String = when (fault) {
      EvalFault.DUPLICATE -> "doppioni"
      EvalFault.BLURRY_OR_DARK -> "mosse o buie"
      EvalFault.SCREENSHOT_OR_DOC -> "screenshot o documenti"
      EvalFault.UNRELATED -> "non c'entrano"
      EvalFault.SAD -> "tristi"
    }
  }
}
