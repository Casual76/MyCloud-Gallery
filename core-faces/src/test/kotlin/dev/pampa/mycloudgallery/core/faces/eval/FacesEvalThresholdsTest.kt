package dev.pampa.mycloudgallery.core.faces.eval

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.faces.contract.GroupingParams
import org.junit.Test

/**
 * La regola di scelta delle soglie del §2.5, a mano: osservazioni inventate, soglie che si calcolano sulla carta. La prova sul set sintetico
 * (gruppi veri, errori che nascono dal raggruppamento) e' in `FacesEvalSyntheticTest`.
 */
class FacesEvalThresholdsTest {
  private val start = ThresholdValues()
  private val totals = EvalTotals(1_000, 800, 200, 100, 30)

  private fun right(group: Int, score: Float, second: Float? = null, weak: Boolean = false) = JoinObservation(group, score, second, weak, true)

  private fun wrong(group: Int, score: Float, second: Float? = null, weak: Boolean = false) = JoinObservation(group, score, second, weak, false)

  /** 20 gruppi da 10 volti giusti con punteggi da 0,60 a 0,78 e una seconda persona lontana (0,30). */
  private fun clean(): List<JoinObservation> = (0 until 20).flatMap { g -> (0 until 10).map { right(g, 0.60f + 0.02f * it, second = 0.30f) } }

  @Test
  fun iValoriDiPartenzaSonoQuelliDiGroupingParams() {
    // La regola vive in due copie senza dipendenze da core-faces: questo test tiene i suoi valori di partenza uguali a quelli del contratto.
    assertThat(start.tJoin).isEqualTo(GroupingParams.T_JOIN)
    assertThat(start.tJoinWeak).isEqualTo(GroupingParams.T_JOIN_WEAK)
    assertThat(start.margin).isEqualTo(GroupingParams.MARGIN)
    assertThat(start.tEdge).isEqualTo(GroupingParams.T_EDGE)
    assertThat(start.tBirth).isEqualTo(GroupingParams.T_BIRTH)
  }

  @Test
  fun senzaErroriLeSoglieNonCambiano() {
    val p = FacesEvalThresholds.choose(clean(), emptyList(), 0, start, totals)
    assertThat(p.values).isEqualTo(start)
    assertThat(p.changes).isFalse()
    assertThat(p.purityAtProposal).isEqualTo(1.0)
    assertThat(p.keptRight).isEqualTo(1.0)
    assertThat(p.joinReachable).isTrue()
    assertThat(p.planB).isFalse()
  }

  @Test
  fun laPurezzaSiContaPerGruppoEQuelloCheContaESoloIlVoltoSbagliatoCheEntra() {
    val obs = clean() + wrong(0, 0.50f) + wrong(1, 0.55f) + wrong(1, 0.40f)
    // A 0,45 il gruppo 0 e 1 hanno un volto sbagliato che entra (il 0,40 no): 18 puri su 20.
    assertThat(FacesEvalThresholds.purity(obs, 0.45f, 0.55f, 0.06f)).isWithin(1e-12).of(0.9)
    // A 0,52 solo il 0,55 entra.
    assertThat(FacesEvalThresholds.purity(obs, 0.52f, 0.55f, 0.06f)).isWithin(1e-12).of(0.95)
    assertThat(FacesEvalThresholds.purity(obs, 0.56f, 0.56f, 0.06f)).isEqualTo(1.0)
    // Il confine e' incluso: un volto a 0,50 esatto entra con T = 0,50.
    assertThat(FacesEvalThresholds.purity(obs, 0.50f, 0.55f, 0.06f)).isLessThan(1.0)
  }

  @Test
  fun tJoinEIlValorePiuBassoCheTieneLaPurezza() {
    val obs = clean() + wrong(0, 0.50f) + wrong(1, 0.55f)
    val p = FacesEvalThresholds.choose(obs, emptyList(), 0, start, totals)
    // 19 gruppi su 20 sono il 95%: servono tutti e venti. Il piu' basso che esclude 0,55 e' 0,56 (gli altri erano gia' fuori).
    assertThat(p.values.tJoin).isEqualTo(0.56f)
    assertThat(p.values.margin).isEqualTo(start.margin)
    assertThat(p.purityAtCurrent).isWithin(1e-12).of(0.9)
    assertThat(p.purityAtProposal).isEqualTo(1.0)
    assertThat(p.keptRight).isEqualTo(1.0)
    assertThat(p.changes).isTrue()
  }

  @Test
  fun ilCaso19su200ENonBastaIlNovantacinque() {
    // 200 gruppi, 2 con un volto sbagliato: 99% di purezza basta gia' a 98,5%.
    val obs = (0 until 200).flatMap { g -> (0 until 5).map { right(g, 0.70f, second = 0.30f) } } + wrong(0, 0.50f) + wrong(1, 0.52f)
    val p = FacesEvalThresholds.choose(obs, emptyList(), 0, start, totals)
    assertThat(p.purityAtCurrent).isWithin(1e-12).of(0.99)
    assertThat(p.values).isEqualTo(start)
  }

  @Test
  fun unaSogliaNonScendeSottoQuellaDiPartenza() {
    val high = ThresholdValues(tJoin = 0.60f, tJoinWeak = 0.70f, margin = 0.10f, tEdge = 0.55f, tBirth = 0.58f)
    val p = FacesEvalThresholds.choose(clean(), emptyList(), 0, high, totals)
    assertThat(p.values).isEqualTo(high)
  }

  @Test
  fun ilMargineCheToglieLErroreDaSoloNonCostaVolti() {
    // Dieci gruppi: un volto sbagliato a 0,80 con la seconda persona a 0,71 (margine 0,09, sopra il 0,06 di partenza). I giusti hanno punteggi 0,60-0,75.
    // Alzare T_join sopra 0,80 perderebbe tutti i giusti; un margine di 0,10 toglie solo gli ambigui e nessun volto giusto (la loro seconda e' a 0,30).
    val obs = (0 until 10).flatMap { g -> (0 until 8).map { right(g, 0.60f + 0.02f * it, second = 0.30f) } } +
      (0 until 10).map { wrong(it, 0.80f, second = 0.71f) }
    val p = FacesEvalThresholds.choose(obs, emptyList(), 0, start, totals)
    assertThat(p.values.margin).isEqualTo(0.10f)
    assertThat(p.values.tJoin).isEqualTo(start.tJoin)
    assertThat(p.keptRight).isEqualTo(1.0)
    assertThat(p.purityAtProposal).isEqualTo(1.0)
  }

  @Test
  fun fraDueStradeVinceQuellaCheTieneIPiuVoltiGiusti() {
    // Gli errori stanno a 0,62 con margine 0,08. Alzare T a 0,63 perde i giusti sotto 0,63 (la meta' circa); il margine 0,09 perde i giusti con margine < 0,09 (nessuno).
    val rights = (0 until 10).flatMap { g -> (0 until 8).map { right(g, 0.55f + 0.03f * it, second = 0.30f) } }
    val wrongs = (0 until 10).map { wrong(it, 0.62f, second = 0.54f) }
    val p = FacesEvalThresholds.choose(rights + wrongs, emptyList(), 0, start, totals)
    assertThat(p.values.margin).isGreaterThan(0.08f)
    assertThat(p.values.tJoin).isEqualTo(start.tJoin)
    assertThat(p.keptRight).isEqualTo(1.0)
  }

  @Test
  fun iVoltiWeakHannoLaLoroSogliaChePartDaQuellaDiPartenza() {
    val obs = clean() + (0 until 20).map { right(it, 0.80f, second = 0.30f, weak = true) } + wrong(3, 0.58f, weak = true)
    val p = FacesEvalThresholds.choose(obs, emptyList(), 0, start, totals)
    // I CORE sono puri a 0,45; il WEAK sbagliato a 0,58 si toglie con 0,59.
    assertThat(p.values.tJoin).isEqualTo(0.45f)
    assertThat(p.values.tJoinWeak).isEqualTo(0.59f)
    assertThat(p.purityAtProposal).isEqualTo(1.0)
  }

  @Test
  fun laSogliaDeiWeakNonScendeMaiSottoQuellaDeiCore() {
    val obs = clean() + wrong(5, 0.62f) // CORE: serve 0,63; il WEAK (partenza 0,55) deve seguire
    val p = FacesEvalThresholds.choose(obs, emptyList(), 0, start, totals)
    assertThat(p.values.tJoin).isEqualTo(0.63f)
    assertThat(p.values.tJoinWeak).isAtLeast(p.values.tJoin)
  }

  @Test
  fun seNonSiRaggiungeLaPurezzaLoDice() {
    val obs = clean() + wrong(0, 0.95f) + wrong(1, 0.96f)
    val p = FacesEvalThresholds.choose(obs, emptyList(), 0, start, totals)
    assertThat(p.joinReachable).isFalse()
    assertThat(FacesEvalThresholds.toNumbersText(p)).contains("non e' raggiungibile")
  }

  @Test
  fun conLaCoperturaStimataSottoMetaScattaIlPianoB() {
    // Il 60% dei volti CORE guardati sta sotto la soglia che toglie gli errori: copertura stimata 0,8 * 0,4 = 32%.
    val rights = (0 until 10).flatMap { g -> (0 until 10).map { right(g, if (it < 6) 0.50f else 0.80f, second = 0.30f) } }
    val obs = rights + wrong(0, 0.70f)
    val p = FacesEvalThresholds.choose(obs, emptyList(), 0, start, totals)
    assertThat(p.values.tJoin).isEqualTo(0.71f)
    assertThat(p.estimatedCoreCoverage).isWithin(0.02).of(0.8 * 40.0 / 101)
    assertThat(p.planB).isTrue()
    assertThat(FacesEvalThresholds.toNumbersText(p)).contains("PIANO B")
    val fine = FacesEvalThresholds.choose(clean(), emptyList(), 0, start, totals)
    assertThat(fine.planB).isFalse()
  }

  @Test
  fun senzaVoltiCoreSullArchivioNonSiParlaDiPianoB() {
    val p = FacesEvalThresholds.choose(clean(), emptyList(), 0, start, EvalTotals.EMPTY)
    assertThat(p.planB).isFalse()
  }

  @Test
  fun senzaOsservazioniLaProposteECioCheCera() {
    val p = FacesEvalThresholds.choose(emptyList(), emptyList(), 0, start, totals)
    assertThat(p.values).isEqualTo(start)
    assertThat(p.groups).isEqualTo(0)
    assertThat(p.purityAtProposal).isEqualTo(1.0)
  }

  // --- Le nascite -----------------------------------------------------------------------------------------------------------------

  /** Quattro volti giusti e due sbagliati, foto tutte diverse; coseni: giusti-giusti 0,70, sbagliati-sbagliati 0,70, giusti-sbagliati [cross]. */
  private fun mixedGroup(cross: Float, photos: IntArray = intArrayOf(0, 1, 2, 3, 4, 5)): BirthGroup {
    val right = booleanArrayOf(true, true, true, true, false, false)
    val n = right.size
    val cos = FloatArray(n * n) { k ->
      val a = k / n
      val b = k % n
      when {
        a == b -> 1f
        right[a] == right[b] -> 0.70f
        else -> cross
      }
    }
    return BirthGroup(right, photos, cos)
  }

  @Test
  fun unaPersonaNascePuraSeIGiustiSiUnisconoPrimaEIlLegameConGliSbagliatiETroppoDebole() {
    val g = mixedGroup(cross = 0.52f)
    assertThat(FacesEvalThresholds.bornMixed(g, 0.45f, 0.48f)).isTrue()
    assertThat(FacesEvalThresholds.bornMixed(g, 0.50f, 0.53f)).isFalse()
    assertThat(FacesEvalThresholds.bornMixed(g, 0.45f, 0.52f)).isTrue()
  }

  @Test
  fun tBirthEIlPiuBassoPerCuiNessunoNasceMisto() {
    val p = FacesEvalThresholds.choose(clean(), listOf(mixedGroup(0.52f), mixedGroup(0.40f)), 0, start, totals)
    assertThat(p.values.tBirth).isEqualTo(0.53f)
    // T_edge segue T_birth con lo scarto di prima (0,48 - 0,45).
    assertThat(p.values.tEdge).isEqualTo(0.50f)
    assertThat(p.bornMixedAtCurrent).isEqualTo(1)
    assertThat(p.bornMixedAtProposal).isEqualTo(0)
    assertThat(p.birthReachable).isTrue()
  }

  @Test
  fun dueVoltiDellaStessaFotoNonStannoMaiInsiemeNeiGruppiNati() {
    // Tre volti quasi identici (coseno 0,99), uno sbagliato. Da foto tutte diverse nascerebbe una persona mista; se due volti vengono dalla stessa foto non
    // possono stare nello stesso gruppo, e il terzo non basta da solo: non nasce niente.
    val right = booleanArrayOf(true, true, false)
    val cos = FloatArray(9) { k -> if (k / 3 == k % 3) 1f else 0.99f }
    assertThat(FacesEvalThresholds.bornMixed(BirthGroup(right, intArrayOf(0, 1, 2), cos), 0.45f, 0.48f)).isTrue()
    assertThat(FacesEvalThresholds.bornMixed(BirthGroup(right, intArrayOf(0, 0, 1), cos), 0.45f, 0.48f)).isFalse()
  }

  @Test
  fun sotto3VoltiNonNasceNiente() {
    val g = BirthGroup(booleanArrayOf(true, false), intArrayOf(0, 1), floatArrayOf(1f, 0.9f, 0.9f, 1f))
    assertThat(FacesEvalThresholds.bornMixed(g, 0.45f, 0.48f)).isFalse()
    assertThat(FacesEvalThresholds.bornMixed(BirthGroup(BooleanArray(0), IntArray(0), FloatArray(0)), 0.45f, 0.48f)).isFalse()
  }

  @Test
  fun seNessunaSogliaBastaLoDice() {
    // Quattro volti giusti e due sbagliati tutti a 0,95 fra loro e a 0,99 con l'altro gruppo: con T_birth fino a 0,90 nasce sempre una persona mista.
    val right = booleanArrayOf(true, true, true, true, false, false)
    val n = right.size
    val cos = FloatArray(n * n) { k -> if (k / n == k % n) 1f else if (right[k / n] == right[k % n]) 0.95f else 0.99f }
    val g = BirthGroup(right, IntArray(n) { it }, cos)
    val p = FacesEvalThresholds.choose(clean(), listOf(g), 0, start, totals)
    assertThat(p.birthReachable).isFalse()
    assertThat(p.values.tBirth).isEqualTo(0.90f)
    assertThat(FacesEvalThresholds.toNumbersText(p)).contains("nasce ancora almeno una persona mista")
  }

  /** Due persone vere (tre volti ciascuna, coseno 0,70 dentro) che il gruppo ha messo insieme; fra loro [cross]. */
  private fun twoPeople(cross: Float, right: BooleanArray?): BirthGroup {
    val n = 6
    val cos = FloatArray(n * n) { k -> if (k / n == k % n) 1f else if ((k / n < 3) == (k % n < 3)) 0.70f else cross }
    return BirthGroup(right, IntArray(n) { it }, cos)
  }

  @Test
  fun unGruppoDatoPerMistoNascePuroSoloSeIVoltiSiSeparano() {
    val mixed = twoPeople(cross = 0.60f, right = null)
    // A 0,48 le due persone si uniscono (media 0,60): un solo gruppo di sei, e il gruppo e' nato misto.
    assertThat(FacesEvalThresholds.bornMixed(mixed, 0.45f, 0.48f)).isTrue()
    // A 0,65 restano due gruppi da tre: il gruppo piu' grande ne tiene la meta', non l'80%.
    assertThat(FacesEvalThresholds.bornMixed(mixed, 0.50f, 0.65f)).isFalse()
    // Con l'80% di dominanza la soglia e' il minimo per cui i due gruppi non si fondono: 0,61 (la media e' 0,60).
    val p = FacesEvalThresholds.choose(clean(), listOf(mixed), 1, start, totals)
    assertThat(p.values.tBirth).isEqualTo(0.61f)
    assertThat(p.values.tEdge).isEqualTo(0.58f)
    assertThat(p.bornMixedAtCurrent).isEqualTo(1)
    assertThat(p.bornMixedAtProposal).isEqualTo(0)
  }

  @Test
  fun unGruppoMistoDiPocheVoltiNonDaNiente() {
    val tiny = BirthGroup(null, intArrayOf(0, 1), floatArrayOf(1f, 0.9f, 0.9f, 1f))
    assertThat(FacesEvalThresholds.bornMixed(tiny, 0.45f, 0.48f)).isFalse()
  }

  @Test
  fun ilTestoDelleSoglieHaSoloNumeri() {
    val p = FacesEvalThresholds.choose(clean() + wrong(0, 0.50f), listOf(mixedGroup(0.52f)), 2, start, totals)
    val text = FacesEvalThresholds.toNumbersText(p)
    assertThat(text).contains("di partenza: T_join 0.45")
    assertThat(text).contains("misti (non usati): 2")
    assertThat(text).contains("soglia | purezza | volti giusti trattenuti")
    assertThat(p.toString()).doesNotContain("Observation")
    assertThat(JoinObservation(1, 0.5f, null, false, true).toString()).doesNotContain("0.5")
  }
}
