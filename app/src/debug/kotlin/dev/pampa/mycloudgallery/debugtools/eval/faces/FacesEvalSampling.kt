package dev.pampa.mycloudgallery.debugtools.eval.faces

import dev.pampa.mycloudgallery.core.model.FaceLocator
import kotlin.math.ln
import kotlin.random.Random

// COSA SI MOSTRA ALL'UTENTE (docs/fase8-progetto.md §5.3): quali gruppi e quali volti. Codice puro e **deterministico** dato il seme (che sta
// nel file delle etichette: riaprendo lo strumento si ritrova la stessa scelta, e nessuno puo' rifare il sorteggio finche' non gli piace).
// Stesso file in `core-faces/src/test/.../eval/` e in `app/src/debug/.../debugtools/eval/faces/` (vedi FacesEvalLabels.kt).

object FacesEvalSampling {
  /** Sessione 1: 40 gruppi scelti a caso fra quelli mostrati, con peso alla dimensione. */
  const val CALIBRATION_GROUPS: Int = 40

  /** Sessione 2: tutti i gruppi mostrati se sono al piu' 80; altrimenti quelli con un nome piu' un campione casuale, fino a 80. */
  const val MEASURE_GROUPS: Int = 80

  /** Per gruppo: al piu' 60 volti, piu' i 10 meno simili al gruppo (dove gli errori sono piu' probabili). */
  const val FACES_SHOWN: Int = 60
  const val LEAST_SIMILAR: Int = 10

  /** Un gruppo che si potrebbe mostrare: la persona, quanti volti ha (il peso) e se ha un nome. */
  class Candidate(val person: String, val size: Int, val named: Boolean) {
    override fun toString(): String = "Candidate($size, named=$named)"
  }

  /** Sessione 1: [CALIBRATION_GROUPS] gruppi, pesati per dimensione, senza ripetizioni. Meno di 40 candidati: tutti. */
  fun calibration(candidates: List<Candidate>, seed: Long): List<Candidate> = ordered(weighted(candidates, CALIBRATION_GROUPS, seed))

  /** Sessione 2: la regola del §5.3 (tutti se sono al piu' 80, altrimenti i gruppi con un nome piu' un campione casuale fino a 80). */
  fun measure(candidates: List<Candidate>, seed: Long): List<Candidate> {
    if (candidates.size <= MEASURE_GROUPS) return ordered(candidates)
    val named = candidates.filter { it.named }
    // Con piu' di 80 nomi non ci stanno tutti: si pesca fra i nominati, sempre con peso alla dimensione.
    if (named.size >= MEASURE_GROUPS) return ordered(weighted(named, MEASURE_GROUPS, seed))
    val others = candidates.filter { !it.named }.sortedBy { it.person }
    val random = Random(seed)
    return ordered(named + others.shuffled(random).take(MEASURE_GROUPS - named.size))
  }

  /** Dal piu' grande al piu' piccolo, a parita' per id: l'ordine in cui l'utente li vede. */
  private fun ordered(groups: List<Candidate>): List<Candidate> = groups.sortedWith(compareByDescending<Candidate> { it.size }.thenBy { it.person })

  /**
   * Estrazione senza ripetizioni con probabilita' proporzionale al peso (Efraimidis-Spirakis: ogni candidato prende la chiave `ln(u) / peso` e
   * si tengono le [count] chiavi piu' alte). Il peso e' la dimensione del gruppo, almeno 1.
   */
  private fun weighted(candidates: List<Candidate>, count: Int, seed: Long): List<Candidate> {
    if (candidates.size <= count) return candidates
    val random = Random(seed)
    val keyed = candidates.sortedBy { it.person }.map { c ->
      // nextDouble e' in [0, 1): 1 - u sta in (0, 1], il logaritmo e' definito.
      c to ln(1.0 - random.nextDouble()) / c.size.coerceAtLeast(1)
    }
    return keyed.sortedWith(compareByDescending<Pair<Candidate, Double>> { it.second }.thenBy { it.first.person }).take(count).map { it.first }
  }

  /** Un volto del gruppo con la sua somiglianza al gruppo (il punteggio con cui e' stato assegnato). */
  class FaceCandidate(val locator: FaceLocator, val score: Float) {
    override fun toString(): String = "FaceCandidate"
  }

  /** I volti da mostrare: [main] dal piu' al meno simile, poi [leastSimilar] (i 10 piu' lontani dal gruppo) in un blocco a parte. */
  class FaceChoice(val main: List<FaceCandidate>, val leastSimilar: List<FaceCandidate>) {
    val all: List<FaceCandidate> get() = main + leastSimilar

    override fun toString(): String = "FaceChoice(${main.size} + ${leastSimilar.size})"
  }

  /**
   * I volti da mostrare di un gruppo ([person]): se sono al piu' [FACES_SHOWN] tutti, dal piu' al meno simile; altrimenti i [LEAST_SIMILAR]
   * meno simili piu' un campione casuale (stabile) di [FACES_SHOWN] fra gli altri.
   */
  fun faces(candidates: List<FaceCandidate>, person: String, seed: Long): FaceChoice {
    val byScoreDesc = candidates.sortedWith(compareByDescending<FaceCandidate> { it.score }.thenBy { it.locator.encode() })
    if (byScoreDesc.size <= FACES_SHOWN) return FaceChoice(byScoreDesc, emptyList())
    val least = byScoreDesc.takeLast(LEAST_SIMILAR).reversed()
    val rest = byScoreDesc.dropLast(LEAST_SIMILAR)
    val random = Random(seed xor person.hashCode().toLong())
    val chosen = if (rest.size <= FACES_SHOWN) rest else rest.shuffled(random).take(FACES_SHOWN)
    return FaceChoice(chosen.sortedWith(compareByDescending<FaceCandidate> { it.score }.thenBy { it.locator.encode() }), least)
  }
}
