package dev.pampa.mycloudgallery.core.faces.eval

import java.util.Locale

// LA REGOLA DI SCELTA DELLE SOGLIE (docs/fase8-progetto.md §2.5 "I parametri", tabella "Come si calibra"; §5.3; Fase 8 P11). Dalle osservazioni
// della sessione 1 -- per ogni volto mostrato: quanto somiglia al suo gruppo, quanto alla seconda persona, e se l'utente ha detto "e' lui" o
// "non e' lui" -- sceglie `T_join`, `T_join_weak`, `M` (il margine), `T_edge` e `T_birth` in modo che la purezza dei gruppi sul set di calibrazione
// sia almeno il 98,5% (margine sul 97% del piano) e nessuna persona nasca mista. Codice puro: stesso file in `core-faces/src/test/.../eval/`
// (provato sul set sintetico) e in `app/src/debug/.../debugtools/eval/faces/` (sul telefono); vedi FacesEvalLabels.kt.
//
// **La regola** (scritta prima di vedere i dati veri, e quindi non si cambia dopo averli visti):
//  1. Un volto *entra* in un gruppo se `s >= T_join` (`T_join_weak` per i WEAK) e `s - seconda >= M`. Un gruppo e' *puro* se nessun volto
//     segnato "non e' lui" avrebbe ancora potuto entrarci. La purezza e' gruppi puri / gruppi guardati.
//  2. `T_join` e `M`, insieme: fra le coppie che tengono la purezza dei volti CORE almeno al 98,5%, quella che trattiene piu' volti giusti; a
//     parita' la `T_join` piu' bassa, poi la `M` piu' bassa. (Per una `M` fissa e' "il valore piu' basso che tiene la purezza"; la scelta congiunta
//     evita di alzare `T_join` per un errore che un margine piu' stretto toglierebbe da solo: gemelli e fratelli, E-F1.)
//  3. `T_join_weak`: il valore piu' basso, non sotto `T_join` ne' sotto quello di partenza, che tiene la purezza di tutti i volti almeno al 98,5%.
//  4. `T_birth`: il valore piu' basso per cui **nessuna persona nasce mista** nei gruppi guardati: i volti CORE di ogni gruppo, raggruppati con il
//     legame medio (archi fra foto diverse, mai due volti della stessa foto insieme, nasce chi ha almeno 3 volti), non mettono mai insieme un volto
//     giusto e uno sbagliato. Un gruppo dato per **misto** (non si sa quali volti sono sbagliati: e' proprio una persona nata mista) vale cosi': a quella
//     soglia i suoi volti CORE non devono restare quasi tutti in un gruppo solo (il gruppo piu' grande, di almeno 3 volti, ne tiene meno dell'80%).
//     `T_edge` segue `T_birth` con lo stesso scarto che avevano.
//  5. Nessuna soglia scende sotto quella con cui sono stati formati i gruppi: sotto non c'e' nessun dato (i volti che non sono entrati non si
//     vedono nella sessione 1).
//  6. Se nemmeno la soglia piu' alta (0,90) o il margine piu' largo (0,20) tengono il 98,5%, la proposta prende la coppia con la purezza migliore e
//     dice **non raggiungibile**. Se la copertura stimata dei volti CORE a fine regola scende sotto il 50%, dice **piano B** (§7: il modello).
// `T_merge`, `T_suggest` e `T_eject` non si scelgono qui: servono le risposte alle proposte e agli espulsi, che la sessione 1 non raccoglie.

/** Le soglie che la regola sceglie (i nomi e i valori di partenza sono quelli di `GroupingParams`, che un test di core-faces tiene allineati). */
data class ThresholdValues(
  val tJoin: Float = 0.45f,
  val tJoinWeak: Float = 0.55f,
  val margin: Float = 0.06f,
  val tEdge: Float = 0.45f,
  val tBirth: Float = 0.48f,
) {
  override fun toString(): String = String.format(
    Locale.ROOT, "T_join %.2f, T_join_weak %.2f, M %.2f, T_edge %.2f, T_birth %.2f", tJoin, tJoinWeak, margin, tEdge, tBirth,
  )
}

/** Un volto guardato in un gruppo (non misto): [group] e' un indice qualunque del gruppo; [second] e' la somiglianza alla seconda persona (null = non ce n'e'). */
class JoinObservation(val group: Int, val score: Float, val second: Float?, val weak: Boolean, val right: Boolean) {
  override fun toString(): String = "JoinObservation(weak=$weak, right=$right)"
}

/**
 * I volti CORE guardati di un gruppo con i loro coseni a due a due ([cosine] e' n x n per righe), la foto di ogni volto (stesso numero = stessa foto) e se e'
 * giusto. [right] null = "Gruppo misto": non si sa quali volti sono sbagliati, ma il gruppo non doveva nascere cosi'.
 */
class BirthGroup(val right: BooleanArray?, val photo: IntArray, val cosine: FloatArray) {
  init {
    require((right == null || right.size == photo.size) && cosine.size == photo.size * photo.size) { "Forme non coerenti." }
  }

  val size: Int get() = photo.size

  override fun toString(): String = "BirthGroup($size, misto=${right == null})"
}

/** Un punto della curva (per il resoconto): la purezza e la quota di volti giusti trattenuti con la soglia [t] per tutti i volti. */
class CurvePoint(val t: Float, val purity: Double, val kept: Double)

class ThresholdProposal(
  val values: ThresholdValues,
  val current: ThresholdValues,
  val groups: Int,
  val mixedGroups: Int,
  val purityAtCurrent: Double,
  val purityAtProposal: Double,
  val joinReachable: Boolean,
  val keptRight: Double,
  val bornMixedAtCurrent: Int,
  val bornMixedAtProposal: Int,
  val birthReachable: Boolean,
  val estimatedCoreCoverage: Double,
  val planB: Boolean,
  val curve: List<CurvePoint>,
) {
  /** La proposta cambia qualcosa rispetto a quello con cui sono stati formati i gruppi. */
  val changes: Boolean get() = values != current

  override fun toString(): String = "ThresholdProposal($values, raggiungibile=${joinReachable && birthReachable}, pianoB=$planB)"
}

object FacesEvalThresholds {
  private const val EPS = 1e-6f
  private const val JOIN_TOP = 90
  private const val MARGIN_TOP = 20
  private const val MIN_FACES = 3

  /** Un gruppo misto non si e' separato se il suo gruppo piu' grande tiene almeno questa parte dei suoi volti CORE. */
  const val MIXED_DOMINANCE: Double = 0.80

  /** Il valore piu' basso che tiene la purezza: 98,5% (§2.5). */
  const val TARGET_PURITY: Double = 0.985

  private fun h(percent: Int): Float = percent / 100f

  private fun hundredths(v: Float): Int = Math.round(v * 100f)

  private fun joins(o: JoinObservation, tCore: Float, tWeak: Float, margin: Float): Boolean =
    o.score + EPS >= (if (o.weak) tWeak else tCore) && (o.second == null || o.score - o.second + EPS >= margin)

  /** La purezza dei gruppi con queste soglie: gruppi senza nessun volto sbagliato che entra, su tutti i gruppi con almeno un volto. */
  fun purity(obs: List<JoinObservation>, tCore: Float, tWeak: Float, margin: Float): Double {
    val byGroup = obs.groupBy { it.group }
    if (byGroup.isEmpty()) return 1.0
    val pure = byGroup.values.count { list -> list.none { !it.right && joins(it, tCore, tWeak, margin) } }
    return pure.toDouble() / byGroup.size
  }

  /** La quota di volti giusti che entrano con queste soglie. */
  fun kept(obs: List<JoinObservation>, tCore: Float, tWeak: Float, margin: Float): Double {
    val rights = obs.filter { it.right }
    return if (rights.isEmpty()) 1.0 else rights.count { joins(it, tCore, tWeak, margin) }.toDouble() / rights.size
  }

  /** Quanti gruppi vedrebbero nascere una persona mista con queste soglie ([BirthGroup]: solo CORE). */
  fun bornMixed(births: List<BirthGroup>, tEdge: Float, tBirth: Float): Int = births.count { bornMixed(it, tEdge, tBirth) }

  fun choose(
    join: List<JoinObservation>,
    births: List<BirthGroup>,
    mixedGroups: Int,
    current: ThresholdValues,
    totals: EvalTotals,
  ): ThresholdProposal {
    val core = join.filter { !it.weak }
    val startCore = hundredths(current.tJoin)
    val startWeak = hundredths(current.tJoinWeak)
    val startMargin = hundredths(current.margin)

    // 2. T_join e M, insieme, sui volti CORE (i WEAK non contano: li decide il punto 3).
    var best: Triple<Int, Int, Pair<Double, Double>>? = null
    var reachable = false
    for (t in startCore..maxOf(startCore, JOIN_TOP)) {
      for (m in startMargin..maxOf(startMargin, MARGIN_TOP)) {
        val p = purity(core, h(t), h(t), h(m))
        val k = kept(core, h(t), h(t), h(m))
        val ok = p >= TARGET_PURITY - 1e-9
        val candidate = Triple(t, m, p to k)
        best = when {
          best == null -> candidate
          ok && !reachable -> candidate
          !ok && reachable -> best
          ok && reachable -> if (k > best.third.second + 1e-12) candidate else best
          else -> if (p > best.third.first + 1e-12 || (p >= best.third.first - 1e-12 && k > best.third.second + 1e-12)) candidate else best
        }
        if (ok) reachable = true
      }
    }
    val tCore = best?.first ?: startCore
    val margin = best?.second ?: startMargin

    // 3. T_join_weak: il piu' basso, non sotto T_join ne' sotto quello di partenza, che tiene la purezza di tutti i volti.
    var weak = maxOf(startWeak, tCore)
    var weakOk = purity(join, h(tCore), h(weak), h(margin)) >= TARGET_PURITY - 1e-9
    while (!weakOk && weak < JOIN_TOP) {
      weak++
      weakOk = purity(join, h(tCore), h(weak), h(margin)) >= TARGET_PURITY - 1e-9
    }

    // 4. T_birth: il piu' basso per cui nessun gruppo fa nascere una persona mista; T_edge con lo scarto di prima.
    val gap = hundredths(current.tBirth) - hundredths(current.tEdge)
    var birth = hundredths(current.tBirth)
    var birthOk = bornMixed(births, h(maxOf(0, birth - gap)), h(birth)) == 0
    while (!birthOk && birth < JOIN_TOP) {
      birth++
      birthOk = bornMixed(births, h(maxOf(0, birth - gap)), h(birth)) == 0
    }
    val edge = maxOf(0, birth - gap)

    val values = ThresholdValues(h(tCore), h(weak), h(margin), h(edge), h(birth))
    val tw = values.tJoinWeak
    val keptRight = kept(join, values.tJoin, tw, values.margin)
    // La copertura stimata: i volti CORE in un gruppo di adesso, per la quota di quelli giusti che restano dentro con le soglie proposte.
    val coreRight = core.filter { it.right }
    val coreKept = if (coreRight.isEmpty()) 1.0 else coreRight.count { joins(it, values.tJoin, values.tJoin, values.margin) }.toDouble() / coreRight.size
    val estimated = totals.coreCoverage * coreKept
    val curve = (startCore..JOIN_TOP step 5).map { t ->
      CurvePoint(h(t), purity(join, h(t), h(maxOf(t, startWeak)), h(margin)), kept(join, h(t), h(maxOf(t, startWeak)), h(margin)))
    }
    return ThresholdProposal(
      values = values,
      current = current,
      groups = join.map { it.group }.distinct().size,
      mixedGroups = mixedGroups,
      purityAtCurrent = purity(join, current.tJoin, current.tJoinWeak, current.margin),
      purityAtProposal = purity(join, values.tJoin, tw, values.margin),
      joinReachable = reachable && weakOk,
      keptRight = keptRight,
      bornMixedAtCurrent = bornMixed(births, current.tEdge, current.tBirth),
      bornMixedAtProposal = bornMixed(births, values.tEdge, values.tBirth),
      birthReachable = birthOk,
      estimatedCoreCoverage = estimated,
      planB = totals.coreFaces > 0 && estimated < EvalTargets().minCoreCoverage,
      curve = curve,
    )
  }

  /**
   * Una persona nata mista in questo gruppo? Legame medio fra i volti CORE guardati: partono singoli, si uniscono i due gruppi con la somiglianza
   * media piu' alta (a parita' gli indici minori), solo se hanno almeno un arco (coseno >= [tEdge]) fra foto diverse, nessuna foto in comune e media
   * >= [tBirth]; nasce chi ha almeno 3 volti. Mista = ha un volto giusto e uno sbagliato.
   */
  fun bornMixed(group: BirthGroup, tEdge: Float, tBirth: Float): Boolean {
    val n = group.size
    if (n < MIN_FACES) return false
    val sum = Array(n) { a -> DoubleArray(n) { b -> group.cosine[a * n + b].toDouble() } }
    val maxCos = Array(n) { a -> FloatArray(n) { b -> group.cosine[a * n + b] } }
    val members = Array(n) { mutableListOf(it) }
    val alive = BooleanArray(n) { true }
    while (true) {
      var bestA = -1
      var bestB = -1
      var bestAvg = -1.0
      for (a in 0 until n) {
        if (!alive[a]) continue
        for (b in a + 1 until n) {
          if (!alive[b] || maxCos[a][b] + EPS < tEdge) continue
          val avg = sum[a][b] / (members[a].size.toDouble() * members[b].size)
          if (avg + EPS < tBirth || avg <= bestAvg + 1e-12) continue
          if (members[a].any { x -> members[b].any { y -> group.photo[x] == group.photo[y] } }) continue
          bestA = a
          bestB = b
          bestAvg = avg
        }
      }
      if (bestA < 0) break
      for (c in 0 until n) {
        if (!alive[c] || c == bestA || c == bestB) continue
        sum[bestA][c] += sum[bestB][c]
        sum[c][bestA] = sum[bestA][c]
        maxCos[bestA][c] = maxOf(maxCos[bestA][c], maxCos[bestB][c])
        maxCos[c][bestA] = maxCos[bestA][c]
      }
      members[bestA].addAll(members[bestB])
      alive[bestB] = false
    }
    val right = group.right
    if (right == null) {
      // Un gruppo dato per misto "nasce misto" se i suoi volti non si sono separati: il gruppo piu' grande ne tiene quasi tutti.
      val biggest = (0 until n).filter { alive[it] }.maxOf { members[it].size }
      return biggest >= MIN_FACES && biggest >= MIXED_DOMINANCE * n
    }
    return (0 until n).any { a -> alive[a] && members[a].size >= MIN_FACES && members[a].any { right[it] } && members[a].any { !right[it] } }
  }

  /** Il resoconto della proposta: **solo numeri** (nessun id, posto o nome). */
  fun toNumbersText(p: ThresholdProposal): String {
    val out = StringBuilder()
    out.append("soglie proposte dalla sessione 1 (regola del 2.5, purezza >= ").append(FacesEvalMetrics.pct(TARGET_PURITY)).append(")\n")
    out.append("di partenza: ").append(p.current).append('\n')
    out.append("proposte:    ").append(p.values).append('\n')
    out.append("gruppi usati: ").append(p.groups).append(", misti (non usati): ").append(p.mixedGroups).append('\n')
    out.append("purezza ai valori di partenza: ").append(FacesEvalMetrics.pct(p.purityAtCurrent)).append(", ai proposti: ").append(FacesEvalMetrics.pct(p.purityAtProposal)).append('\n')
    out.append("volti giusti trattenuti: ").append(FacesEvalMetrics.pct(p.keptRight)).append('\n')
    out.append("persone nate miste (gruppi) ai valori di partenza: ").append(p.bornMixedAtCurrent).append(", ai proposti: ").append(p.bornMixedAtProposal).append('\n')
    out.append("copertura stimata dei volti CORE: ").append(FacesEvalMetrics.pct(p.estimatedCoreCoverage)).append('\n')
    if (!p.joinReachable) out.append("ATTENZIONE: la purezza non e' raggiungibile con le sole soglie di ingresso (T_join fino a 0,90, M fino a 0,20)\n")
    if (!p.birthReachable) out.append("ATTENZIONE: con T_birth fino a 0,90 nasce ancora almeno una persona mista\n")
    if (p.planB) out.append("PIANO B: copertura stimata dei volti CORE sotto il 50%, vedi 7 del progetto (il modello)\n")
    out.append("soglia | purezza | volti giusti trattenuti\n")
    for (c in p.curve) out.append(String.format(Locale.ROOT, "%.2f | %s | %s\n", c.t, FacesEvalMetrics.pct(c.purity), FacesEvalMetrics.pct(c.kept)))
    return out.toString()
  }
}
