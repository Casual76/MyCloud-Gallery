package dev.pampa.mycloudgallery.core.memories.eval

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.memories.contract.PickWeights
import kotlin.math.abs
import org.junit.Test

/**
 * La regola di §5.3 punto 3 (`MemoriesEvalChoice`) sulle misure della Libreria sintetica "di una famiglia" (riga P11 del §4.4): per i parametri che si possono
 * provare senza toccare il codice si provano alcuni valori e si scrive cosa si perde e cosa si guadagna. **Il valore che esce e' quello che sta nel codice**
 * (`PickWeights`): se una misura cambiasse, il test direbbe quale costante va rivista. Le tabelle sono stampate (`println`) e copiate in `docs/decisioni.md`.
 *
 * Cosa NON si calibra qui: i pesi dei segnali dell'utente (preferito +3, modificato +1, in un album +1, persona +1), il tetto dei WhatsApp, la lambda della varieta'
 * e i minimi di D7. Sul sintetico non c'e' una verita' indipendente ("questa foto e' piu' bella") per nessuno di loro, e il punteggio **ordina senza escludere**:
 * le misure del sintetico non li muovono. Restano provvisori fino alla misura vera (F125).
 */
class MemoriesEvalCalibrationTest {

  private val world = familyWorld(EVAL_TODAY)

  @Test
  fun laNitidezzaRestaCentoSulSintetico() {
    // Criteri: le foto davvero mosse rimaste nei ricordi non oltre il 2,5% delle foto; le foto davvero nitide punite non oltre lo 0,5% (il danno collaterale).
    val values = listOf(40, 60, 80, 100, 130, 160, 200, 300)
    val figures = values.associateWith { sharpnessFigures(world, it) }
    figures.values.forEach { println("CALIBRAZIONE $it") }
    val trials = values.map { v ->
      val f = figures.getValue(v)
      Trial(v, f.toString(), f.blurryShare <= MAX_BLURRY_KEPT && f.wrongShare <= MAX_WRONG_PENALTY, minOf(MAX_BLURRY_KEPT - f.blurryShare, MAX_WRONG_PENALTY - f.wrongShare))
    }
    val choice = MemoriesEvalChoice.choose(PROVISIONAL_SHARPNESS, trials) { a, b -> abs(a - b).toDouble() }
    assertThat(choice.reachable).isTrue()
    assertThat(choice.value).isEqualTo(PickWeights.SHARPNESS_MIN.toInt())
    assertThat(choice.changed).isEqualTo(PROVISIONAL_SHARPNESS != PickWeights.SHARPNESS_MIN.toInt())
    // La forma della curva: sotto 100 restano piu' mosse, sopra si puniscono sempre piu' foto nitide (a 300 sono oltre il 5% delle foto).
    assertThat(figures.getValue(40).blurryKept).isAtLeast(figures.getValue(100).blurryKept)
    assertThat(figures.getValue(300).wrongPenalty).isGreaterThan(figures.getValue(100).wrongPenalty)
  }

  @Test
  fun ilCosenoDeiDoppioniRestaZeroVirgolaNovantaduSulSintetico() {
    // Criteri: almeno il 95% delle riprese ripetute riconosciute, al piu' il 5% delle foto "simili ma diverse" scambiate per doppioni.
    val values = listOf(0.86, 0.88, 0.90, 0.92, 0.94, 0.96)
    val figures = values.associateWith { cosineFigures(world, it) }
    figures.values.forEach { println("CALIBRAZIONE $it") }
    val trials = values.map { v ->
      val f = figures.getValue(v)
      Trial(v, f.toString(), f.recall >= MIN_RECALL && f.falsePositive <= MAX_FALSE_POSITIVE, minOf(f.recall - MIN_RECALL, MAX_FALSE_POSITIVE - f.falsePositive))
    }
    val choice = MemoriesEvalChoice.choose(PROVISIONAL_COSINE, trials) { a, b -> abs(a - b) }
    assertThat(choice.reachable).isTrue()
    assertThat(choice.value).isEqualTo(PickWeights.DUPLICATE_COSINE)
    assertThat(choice.changed).isFalse()
    // La forma della curva: abbassare il coseno scambia per doppioni le foto simili, alzarlo lascia passare le riprese.
    assertThat(figures.getValue(0.86).falsePositive).isGreaterThan(figures.getValue(0.92).falsePositive)
    assertThat(figures.getValue(0.96).recall).isLessThan(figures.getValue(0.92).recall)
  }

  @Test
  fun laFinestraDeiDoppioniSenzaVettoriDaTreACinqueMinuti() {
    // Criteri: almeno il 95% dei doppioni seminati tolti (§5.3 punto 1); le foto uniche perse non oltre un punto in piu' di quelle che si perdono con la finestra
    // di progetto (D7: 3 minuti).
    val values = listOf(1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 8.0, 10.0)
    val figures = values.associateWith { dedupeFigures(world, it) }
    figures.values.forEach { println("CALIBRAZIONE $it") }
    val baseline = figures.getValue(PROVISIONAL_WINDOW).lostShare
    val trials = values.map { v ->
      val f = figures.getValue(v)
      Trial(v, f.toString(), f.recall >= MIN_RECALL_DOUBLES && f.lostShare <= baseline + MAX_EXTRA_LOSS, minOf(f.recall - MIN_RECALL_DOUBLES, baseline + MAX_EXTRA_LOSS - f.lostShare))
    }
    val choice = MemoriesEvalChoice.choose(PROVISIONAL_WINDOW, trials) { a, b -> abs(a - b) }
    assertThat(choice.reachable).isTrue()
    // Con 3 minuti il sintetico non arriva al 95%: la regola porta la finestra al valore piu' piccolo che ci arriva, ed e' quello che sta nel codice.
    assertThat(figures.getValue(PROVISIONAL_WINDOW).recall).isLessThan(MIN_RECALL_DOUBLES)
    assertThat(choice.changed).isTrue()
    assertWithMessage("la finestra scelta e' quella di PickWeights").that(choice.value * 60_000.0).isEqualTo(PickWeights.DUPLICATE_WINDOW_MS.toDouble())
    // La forma della curva: piu' larga e' la finestra, piu' doppioni si tolgono e piu' foto uniche si perdono.
    assertThat(figures.getValue(10.0).recall).isGreaterThan(figures.getValue(1.0).recall)
    assertThat(figures.getValue(10.0).lostShare).isGreaterThan(figures.getValue(1.0).lostShare)
  }

  private companion object {
    /** I valori di progetto (D6, D7) da cui parte la regola: non sono letti dal codice, perche' il codice e' cio' che la regola puo' avere cambiato. */
    const val PROVISIONAL_SHARPNESS = 100
    const val PROVISIONAL_COSINE = 0.92
    const val PROVISIONAL_WINDOW = 3.0

    const val MAX_BLURRY_KEPT = 0.025
    const val MAX_WRONG_PENALTY = 0.005
    const val MIN_RECALL = 0.95
    const val MAX_FALSE_POSITIVE = 0.05
    const val MIN_RECALL_DOUBLES = 0.95
    const val MAX_EXTRA_LOSS = 0.01
  }
}
