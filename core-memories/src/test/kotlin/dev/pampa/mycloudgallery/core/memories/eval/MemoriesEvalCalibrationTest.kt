package dev.pampa.mycloudgallery.core.memories.eval

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.memories.contract.CandidateCell
import dev.pampa.mycloudgallery.core.memories.contract.PhotoScore
import dev.pampa.mycloudgallery.core.memories.contract.PickSignals
import dev.pampa.mycloudgallery.core.memories.contract.PickWeights
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaSource
import dev.pampa.mycloudgallery.core.model.PersonId
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

  @Test
  fun ilPunteggioDeiPesiProvvisoriSeparaIDifettiVisibiliDalleFotoBuone() {
    // Una misura, non una calibrazione: i pesi di D6 non si possono variare senza toccare il codice, ma si puo' dire quanto bene i valori attuali separano i difetti
    // che il punteggio vede (mossa, buia, ricevuta da WhatsApp e "non c'entra") dalle foto buone dello stesso giorno: la probabilita' che una foto buona abbia un
    // punteggio piu' alto di una col difetto (a pari punteggio, meta'). Solo nei giorni con almeno 8 candidati, dove l'ordine conta: nei giorni piccoli le foto
    // stanno tutte nel ricordo e il punteggio non decide niente (e' il limite che la calibrazione dei pesi non puo' correggere: servirebbe una soglia minima).
    val people = HashMap<MediaId, Set<PersonId>>()
    for ((person, media) in world.personMedia) for (m in media) people[m] = (people[m] ?: emptySet()) + person
    val signals = PickSignals(pixels = world.probes, people = people)
    fun score(s: FamilyShot): Double = PhotoScore.of(
      CandidateCell(s.id, 0, 0, s.kind, s.source, s.flags, 0, 1_333, null, false), signals,
    )
    val wins = DoubleArray(3)
    val pairs = IntArray(3)
    for ((_, shots) in world.visibleShots.filter { !it.excluded }.groupBy { it.day }) {
      if (shots.size < 8) continue
      val good = shots.filter { it.fault == null && it.dupGroup == null && it.source == MediaSource.CAMERA && !it.blurry && !it.dark }
      val classes = listOf(
        shots.filter { it.blurry },
        shots.filter { it.dark },
        shots.filter { it.source == MediaSource.WHATSAPP && it.fault == EvalFault.UNRELATED },
      )
      for ((k, faulty) in classes.withIndex()) for (f in faulty) for (g in good) {
        val diff = score(g) - score(f)
        pairs[k]++
        wins[k] += if (diff > 0) 1.0 else if (diff == 0.0) 0.5 else 0.0
      }
    }
    val auc = List(3) { wins[it] / pairs[it] }
    println("CALIBRAZIONE punteggio (giorni da 8 candidati): mosse ${pairs[0]} coppie, ${"%.3f".format(java.util.Locale.ROOT, auc[0])}; buie ${pairs[1]} coppie, ${"%.3f".format(java.util.Locale.ROOT, auc[1])}; WhatsApp non pertinenti ${pairs[2]} coppie, ${"%.3f".format(java.util.Locale.ROOT, auc[2])}")
    for (k in 0 until 3) assertThat(pairs[k]).isGreaterThan(300)
    // Una guardia, non un obiettivo: le mosse (BLURRY -1,5) si separano bene (0,86), le buie (BAD_LIGHT -1,0) meno (0,75: un'esposizione sbagliata pesa meno di una
    // preferita o di una persona, e il 10% delle buie sta sopra la soglia di luce), le ricevute da WhatsApp non pertinenti (-1,5) abbastanza (0,90). Se la misura vera
    // (F125) dice che ci sono troppe foto buie nei ricordi, il primo sospetto e' BAD_LIGHT.
    assertThat(auc[0]).isAtLeast(0.80)
    assertThat(auc[1]).isAtLeast(0.70)
    assertThat(auc[2]).isAtLeast(0.80)
    assertThat(auc.all { it < 1.0 }).isTrue()
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
