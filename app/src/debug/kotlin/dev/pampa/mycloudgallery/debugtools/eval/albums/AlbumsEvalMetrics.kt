package dev.pampa.mycloudgallery.debugtools.eval.albums

import java.util.Locale

// LE METRICHE DELLA VALUTAZIONE DEGLI ALBUM SMART (docs/fase7-progetto.md §5.3, F88; riga P12 del §4.4): per ogni viaggio la **precisione dei
// sicuri** (quanti dei media che l'album ha inserito da solo sono davvero del viaggio) e la **copertura** (quanti dei media del viaggio
// l'album ha inserito o suggerito). Codice puro, senza dipendenze dal progetto: LO STESSO FILE sta in `core-albums/src/test/.../eval/`
// (provato con i viaggi sintetici) e in `app/src/debug/.../debugtools/eval/albums/` (lo strumento sul telefono); cambia solo la riga
// `package`, e un test di :app (`AlbumsEvalSourceParityTest`) controlla che le due copie restino uguali.
//
// I media sono id locali (UUID in stringa). Un resoconto e' fatto di **soli numeri**: mai titoli, date, luoghi o id.

/** Gli obiettivi di F88 (§5.3): precisione dei sicuri almeno 98% e copertura almeno 90% su 3 viaggi veri. */
data class AlbumsEvalTargets(val precision: Double = 0.98, val coverage: Double = 0.90)

/**
 * Cio' che il motore ha fatto per un viaggio: i media inseriti da soli ([sure]) e quelli solo suggeriti ([suggested], mai gli stessi dei primi),
 * piu' qualche numero dell'interpretazione del titolo. Senza doppioni, nell'ordine del motore.
 */
class TripRun(
  val tripId: String,
  sure: List<String>,
  suggested: List<String>,
  /** Quante serie di giorni ha proposto il raffinamento (E-AL12); 0 se il periodo non ha serie. */
  val series: Int = 0,
  /** Il titolo ha dato un luogo. */
  val placeRecognized: Boolean = false,
  /** Quanti omonimi alternativi (E-AL2). */
  val alternatives: Int = 0,
  /** Se descrizione ed esempi hanno contato (il motore di ricerca era pronto). */
  val searchUsed: Boolean = false,
) {
  val sure: List<String> = sure.distinct()
  val suggested: List<String> = suggested.filter { it !in this.sure }.distinct()

  /** Solo conteggi. */
  override fun toString(): String = "TripRun($tripId, ${this.sure.size} sicuri, ${this.suggested.size} suggeriti)"
}

/**
 * I numeri di un viaggio (o la somma di piu' viaggi). I **sicuri non giudicati** (ne' del viaggio ne' segnati sbagliati) contano come **non
 * giusti** nella precisione: e' la lettura prudente, e il resoconto li dice.
 */
data class TripMetrics(
  /** Le foto che l'utente dice del viaggio (la verita'). */
  val truth: Int,
  val sure: Int,
  val sureRight: Int,
  val sureWrong: Int,
  val sureUnjudged: Int,
  val suggested: Int,
  val suggestedRight: Int,
  /** Le foto del viaggio inserite o suggerite. */
  val covered: Int,
) {
  /** Giusti sul totale dei sicuri (i non giudicati non sono giusti); NaN se il motore non ha inserito niente. */
  val precision: Double get() = if (sure == 0) Double.NaN else sureRight.toDouble() / sure

  /** Come [precision], ma solo sui sicuri giudicati. */
  val precisionJudged: Double get() = if (sureRight + sureWrong == 0) Double.NaN else sureRight.toDouble() / (sureRight + sureWrong)

  /** Inserite o suggerite sul totale delle foto del viaggio; NaN se l'utente non ne ha segnata nessuna. */
  val coverage: Double get() = if (truth == 0) Double.NaN else covered.toDouble() / truth

  /** Le foto del viaggio inserite da sole, sul totale. */
  val sureCoverage: Double get() = if (truth == 0) Double.NaN else sureRight.toDouble() / truth

  /** Le foto del viaggio che l'album non ha ne' inserito ne' suggerito. */
  val missing: Int get() = truth - covered

  fun meets(targets: AlbumsEvalTargets): Boolean =
    truth > 0 && sure > 0 && precision >= targets.precision && coverage >= targets.coverage

  operator fun plus(other: TripMetrics): TripMetrics = TripMetrics(
    truth + other.truth, sure + other.sure, sureRight + other.sureRight, sureWrong + other.sureWrong, sureUnjudged + other.sureUnjudged,
    suggested + other.suggested, suggestedRight + other.suggestedRight, covered + other.covered,
  )

  companion object {
    val ZERO = TripMetrics(0, 0, 0, 0, 0, 0, 0, 0)
  }
}

object AlbumsEvalMetrics {

  /**
   * Giudica un'esecuzione con le etichette dell'utente: [inTrip] sono le foto del viaggio (la verita'), [notInTrip] quelle che l'utente ha detto
   * non essere del viaggio. Un sicuro e' **giusto** se e' del viaggio, **sbagliato** se l'utente l'ha segnato non del viaggio, **non giudicato**
   * negli altri casi. La copertura conta le foto del viaggio fra i sicuri e i suggeriti.
   */
  fun judge(run: TripRun, inTrip: Set<String>, notInTrip: Set<String>): TripMetrics {
    val right = run.sure.count { it in inTrip }
    val wrong = run.sure.count { it !in inTrip && it in notInTrip }
    val suggestedRight = run.suggested.count { it in inTrip }
    return TripMetrics(
      truth = inTrip.size,
      sure = run.sure.size,
      sureRight = right,
      sureWrong = wrong,
      sureUnjudged = run.sure.size - right - wrong,
      suggested = run.suggested.size,
      suggestedRight = suggestedRight,
      covered = right + suggestedRight,
    )
  }

  fun total(metrics: Collection<TripMetrics>): TripMetrics = metrics.fold(TripMetrics.ZERO) { a, b -> a + b }

  /** Il resoconto di F88 per i viaggi che hanno un'esecuzione. */
  fun report(entries: List<Entry>, targets: AlbumsEvalTargets = AlbumsEvalTargets()): AlbumsEvalReport = AlbumsEvalReport(entries, targets)

  /** Un viaggio giudicato: i giorni (un numero), l'esecuzione e i suoi numeri. */
  class Entry(val trip: EvalTrip, val run: TripRun, val metrics: TripMetrics) {
    override fun toString(): String = "Entry(${trip.id})"
  }
}

/** I numeri di F88: per viaggio e in totale; solo numeri, da copiare in `docs/decisioni.md` (§5.3). */
class AlbumsEvalReport(val entries: List<AlbumsEvalMetrics.Entry>, val targets: AlbumsEvalTargets) {
  val total: TripMetrics = AlbumsEvalMetrics.total(entries.map { it.metrics })

  /** Vero se in totale i due obiettivi sono raggiunti e nessun sicuro e' rimasto senza giudizio. */
  val conclusive: Boolean get() = total.sureUnjudged == 0 && entries.all { it.metrics.truth > 0 }

  val meets: Boolean get() = total.meets(targets)

  fun toNumbersText(): String {
    val out = StringBuilder()
    out.append("F88, album smart su ${entries.size} viaggi: precisione dei sicuri >= ${pct(targets.precision)}, ")
    out.append("copertura (sicuri + suggeriti) >= ${pct(targets.coverage)}\n")
    out.append("viaggio | giorni | foto del viaggio | sicuri | giusti | sbagliati | non giudicati | suggeriti | di cui del viaggio | coperte | mancanti | ")
    out.append("precisione | copertura | serie | luogo | omonimi\n")
    out.append("---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---\n")
    for (e in entries) out.append(line(e.trip.id, e.trip.days.toString(), e.metrics, e.run))
    out.append(line("totale", "-", total, null))
    out.append('\n')
    out.append("Precisione dei sicuri: ${verdict(total.precision >= targets.precision)} (${pct(total.precision)})\n")
    out.append("Copertura: ${verdict(total.coverage >= targets.coverage)} (${pct(total.coverage)})\n")
    out.append("Sicuri senza giudizio: ${total.sureUnjudged}")
    out.append(if (total.sureUnjudged == 0) " (misura completa)" else " (contano come sbagliati: giudicali per una misura completa)")
    out.append('\n')
    out.append("Precisione sui soli sicuri giudicati: ${pct(total.precisionJudged)}\n")
    return out.toString()
  }

  private fun line(name: String, days: String, m: TripMetrics, run: TripRun?): String =
    "$name | $days | ${m.truth} | ${m.sure} | ${m.sureRight} | ${m.sureWrong} | ${m.sureUnjudged} | ${m.suggested} | ${m.suggestedRight} | ${m.covered} | " +
      "${m.missing} | ${pct(m.precision)} | ${pct(m.coverage)} | ${run?.series ?: "-"} | ${run?.let { if (it.placeRecognized) "si" else "no" } ?: "-"} | " +
      "${run?.alternatives ?: "-"}\n"

  override fun toString(): String = "AlbumsEvalReport(${entries.size} viaggi)"

  companion object {
    fun pct(v: Double): String = if (v.isNaN()) "-" else String.format(Locale.ROOT, "%.1f%%", v * 100)

    fun verdict(ok: Boolean): String = if (ok) "RAGGIUNTA" else "NON raggiunta"
  }
}
