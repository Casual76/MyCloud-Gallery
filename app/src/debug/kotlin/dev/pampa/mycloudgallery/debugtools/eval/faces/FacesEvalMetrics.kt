package dev.pampa.mycloudgallery.debugtools.eval.faces

import java.util.Locale
import kotlin.math.sqrt

// LE METRICHE DELLA VALUTAZIONE DELLE PERSONE (docs/fase8-progetto.md §5.3, Fase 8 P11): purezza dei gruppi, errori per volto, frammentazione
// ("e' la stessa di ...") e copertura dei volti CORE. Codice puro: stesso file in `core-faces/src/test/.../eval/` (provato su storie
// sintetiche con errori noti) e in `app/src/debug/.../debugtools/eval/faces/` (lo strumento sul telefono); vedi FacesEvalLabels.kt.
//
// Le metriche partono dalle etichette di una sessione e dicono **solo numeri**: nessun id, nessun posto, nessun nome.

/** Gli obiettivi del §5.3: purezza almeno 97% (8.1), calibrazione puntata a 98,5% (margine), copertura dei volti CORE almeno 50% (piano B, §7). */
data class EvalTargets(val purity: Double = 0.97, val calibrationPurity: Double = 0.985, val minCoreCoverage: Double = 0.5)

/** I numeri di una sessione. Le frazioni sono 0..1; [purity] e' 1,0 se nessun gruppo e' stato guardato (e [groups] dice che non vale). */
class SessionReport(
  val kind: EvalSessionKind,
  /** I gruppi scelti all'inizio. */
  val selected: Int,
  /** I gruppi guardati: sono i soli che contano nei numeri. */
  val groups: Int,
  val pureGroups: Int,
  val mixedGroups: Int,
  /** I volti guardati un per uno (i gruppi misti non ne danno: non si sa quali sono sbagliati). */
  val facesJudged: Int,
  val facesWrong: Int,
  /** Le persone vere dietro i gruppi guardati (gruppi collegati da "e' la stessa di ..." contano una volta) e quanti gruppi hanno in tutto. */
  val identities: Int,
  val identityGroups: Int,
  /** I gruppi guardati che l'utente ha detto essere la stessa persona di un altro gruppo. */
  val splitGroups: Int,
  val totals: EvalTotals,
) {
  val purity: Double get() = if (groups == 0) 1.0 else pureGroups.toDouble() / groups

  /** L'intervallo di Wilson al 95% sulla purezza: con 40 gruppi e' largo, e il numero si legge con lui. */
  val purityInterval: Pair<Double, Double> get() = FacesEvalMetrics.wilson(pureGroups, groups)

  val errorsPerFace: Double get() = if (facesJudged == 0) 0.0 else facesWrong.toDouble() / facesJudged

  /** Persone viste per ogni persona vera con un gruppo guardato: 1,00 = nessuna divisa. */
  val fragmentation: Double get() = if (identities == 0) 1.0 else identityGroups.toDouble() / identities

  val coreCoverage: Double get() = totals.coreCoverage

  fun meetsPurity(targets: EvalTargets = EvalTargets()): Boolean = groups > 0 && purity >= targets.purity

  /** Vero se la copertura dei volti CORE e' sotto il 50%: si passa al piano B del modello (§7, D9). */
  fun planB(targets: EvalTargets = EvalTargets()): Boolean = totals.coreFaces > 0 && coreCoverage < targets.minCoreCoverage

  /** Solo numeri. */
  override fun toString(): String = "SessionReport(${kind.code}, $groups gruppi, purezza=${FacesEvalMetrics.pct(purity)})"
}

object FacesEvalMetrics {

  /** La sessione in numeri. Un gruppo conta solo se l'utente l'ha guardato ([GroupLabel.reviewed]). */
  fun report(session: SessionLabels): SessionReport {
    val reviewed = session.reviewedGroups
    val detailed = reviewed.filter { !it.mixed }
    val (identities, identityGroups, split) = identities(reviewed)
    return SessionReport(
      kind = session.kind,
      selected = session.groups.size,
      groups = reviewed.size,
      pureGroups = reviewed.count { it.pure },
      mixedGroups = reviewed.count { it.mixed },
      facesJudged = detailed.sumOf { it.shown },
      facesWrong = detailed.sumOf { it.wrong.size },
      identities = identities,
      identityGroups = identityGroups,
      splitGroups = split,
      totals = session.totals,
    )
  }

  /**
   * La frammentazione: i gruppi guardati collegati da "e' la stessa di ..." (in un verso o nell'altro) sono la stessa persona. Ritorna (persone
   * vere, gruppi che le compongono, gruppi guardati che fanno parte di una persona con piu' gruppi). Un gruppo a cui si punta e che l'utente non
   * ha guardato conta come gruppo della persona (e' un gruppo in piu' che la divide), ma non e' un gruppo guardato.
   */
  private fun identities(reviewed: List<GroupLabel>): Triple<Int, Int, Int> {
    val parent = HashMap<String, String>()
    fun find(x: String): String {
      var r = x
      while (parent.getValue(r) != r) r = parent.getValue(r)
      var c = x
      while (parent.getValue(c) != r) c = parent.getValue(c).also { parent[c] = r }
      return r
    }
    fun add(x: String) {
      parent.putIfAbsent(x, x)
    }
    for (g in reviewed) {
      add(g.person)
      for (other in g.sameAs) {
        add(other)
        val a = find(g.person)
        val b = find(other)
        if (a != b) parent[maxOf(a, b)] = minOf(a, b)
      }
    }
    val sizes = parent.keys.groupingBy { find(it) }.eachCount()
    val inReviewed = reviewed.map { find(it.person) }.toSet()
    val split = reviewed.count { sizes.getValue(find(it.person)) > 1 }
    return Triple(inReviewed.size, inReviewed.sumOf { sizes.getValue(it) }, split)
  }

  /** L'intervallo di Wilson al 95% per [successes] su [n] (z = 1,96). Con n = 0: tutto l'intervallo. */
  fun wilson(successes: Int, n: Int): Pair<Double, Double> {
    if (n == 0) return 0.0 to 1.0
    val z = 1.96
    val p = successes.toDouble() / n
    val denominator = 1 + z * z / n
    val centre = (p + z * z / (2 * n)) / denominator
    val half = z * sqrt(p * (1 - p) / n + z * z / (4.0 * n * n)) / denominator
    return (centre - half).coerceAtLeast(0.0) to (centre + half).coerceAtMost(1.0)
  }

  fun pct(v: Double): String = String.format(Locale.ROOT, "%.1f%%", v * 100)

  fun num(v: Double): String = String.format(Locale.ROOT, "%.3f", v)

  /** Il testo da copiare in `docs/decisioni.md`: **solo numeri**, mai id, posti dei volti, nomi o chiavi (§5.3). */
  fun toNumbersText(report: SessionReport, targets: EvalTargets = EvalTargets()): String {
    val (low, high) = report.purityInterval
    val t = report.totals
    val out = StringBuilder()
    out.append("sessione ").append(report.kind.number).append(" (").append(report.kind.code).append(")\n")
    out.append("gruppi scelti: ").append(report.selected).append(", guardati: ").append(report.groups).append('\n')
    out.append("purezza: ").append(pct(report.purity)).append(" (").append(report.pureGroups).append('/').append(report.groups)
      .append("), intervallo al 95%: ").append(pct(low)).append(" - ").append(pct(high)).append('\n')
    out.append("gruppi misti: ").append(report.mixedGroups).append('\n')
    out.append("errori per volto: ").append(pct(report.errorsPerFace)).append(" (").append(report.facesWrong).append('/').append(report.facesJudged).append(")\n")
    out.append("frammentazione: ").append(String.format(Locale.ROOT, "%.2f", report.fragmentation)).append(" gruppi per persona (")
      .append(report.identities).append(" persone, ").append(report.identityGroups).append(" gruppi, ").append(report.splitGroups).append(" guardati divisi)\n")
    out.append("volti CORE in un gruppo: ").append(pct(report.coreCoverage)).append(" (").append(t.coreAssigned).append('/').append(t.coreFaces).append(")\n")
    out.append("volti WEAK in un gruppo: ").append(t.weakAssigned).append('/').append(t.weakFaces).append('\n')
    out.append("gruppi mostrati in Persone: ").append(t.groupsShown).append('\n')
    if (report.kind == EvalSessionKind.MEASURE) {
      out.append("obiettivo purezza >= ").append(pct(targets.purity)).append(": ").append(if (report.meetsPurity(targets)) "raggiunto" else "mancato").append('\n')
    } else {
      out.append("obiettivo di calibrazione >= ").append(pct(targets.calibrationPurity)).append(": ")
        .append(if (report.groups > 0 && report.purity >= targets.calibrationPurity) "raggiunto" else "mancato").append('\n')
    }
    out.append("copertura CORE sotto ").append(pct(targets.minCoreCoverage)).append(" (piano B del modello): ").append(if (report.planB(targets)) "SI" else "no").append('\n')
    return out.toString()
  }
}
