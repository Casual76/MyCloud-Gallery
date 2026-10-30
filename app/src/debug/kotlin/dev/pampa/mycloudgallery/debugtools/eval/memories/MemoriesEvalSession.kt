package dev.pampa.mycloudgallery.debugtools.eval.memories

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * SOLO debug (Fase 9, P11): lo stato dello strumento di valutazione dei ricordi, senza interfaccia (si prova sulla JVM). Il metodo (§5.3, F125):
 *  1. **la corsa** ([start]): la porta genera in memoria i ricordi degli ultimi 45 giorni come se "oggi" fosse ognuno di quei giorni (circa 40-60 ricordi), senza scrivere
 *     lo scaffale ne' i dati personali; se ne scelgono al piu' 60 (sempre gli stessi per la stessa corsa) e si salvano nel file dei giudizi;
 *  2. **i giudizi** ([judge], [setFault]): per ogni ricordo un tocco ("Bello", "Cosi cosi", "Da non mostrare", col motivo facoltativo) e le foto che **non** dovevano
 *    esserci (doppione, mossa o buia, screenshot o documento, non c'entra, triste);
 *  3. **i numeri** ([report], [saveReport]): ricordi graditi, foto da togliere per ricordo, doppioni, "da non mostrare"; solo numeri, da copiare in `docs/decisioni.md`.
 *
 * I giudizi sono id locali, salvati a ogni modifica ([MemoriesEvalStore]). Con Q6 (a) non c'e' nessun filtro dei ricordi tristi: "triste" e' solo una voce da contare.
 * **Mai un nascosto**: [start] e [dropHidden] tolgono dallo strumento (e quindi dalle metriche) cio' che e' nascosto o eliminato.
 */
class MemoriesEvalSession(private val env: MemoriesEvalEnvironment, private val store: MemoriesEvalStore) {

  private val state = MutableStateFlow(MemoriesLabelSet())
  val labels: StateFlow<MemoriesLabelSet> get() = state

  /** Se il file dei giudizi era rovinato: il motivo (con il numero di riga, mai il contenuto) e una sessione vuota al suo posto. */
  var loadError: String? = null
    private set

  fun load() {
    state.value = try {
      store.loadLabels()
    } catch (e: IllegalArgumentException) {
      loadError = e.message
      MemoriesLabelSet()
    }
  }

  // --- La corsa ------------------------------------------------------------------------------------------------------------------

  /**
   * Genera i ricordi degli ultimi 45 giorni e comincia una sessione nuova (i giudizi di prima si perdono: chi chiama lo chiede all'utente). Dice quanti ricordi ci sono da
   * giudicare. Senza ricordi lancia `IllegalStateException` con il motivo (la Libreria non e' pronta o e' troppo giovane: E-MEM1).
   */
  suspend fun start(): Int {
    val chosen = MemoriesEvalDays.select(env.generate(MemoriesEvalDays.WINDOW))
    if (chosen.isEmpty()) throw IllegalStateException(MemoriesEvalStrings.NO_MEMORIES)
    val visible = env.visibleAmong(chosen.flatMap { it.members }.toSet())
    val fresh = MemoriesLabelSet().withRun(chosen).withVisibleOnly { it in visible }
    if (fresh.run.isEmpty()) throw IllegalStateException(MemoriesEvalStrings.NO_MEMORIES)
    change { fresh }
    return fresh.run.size
  }

  /**
   * Toglie dalla sessione cio' che nel frattempo e' stato nascosto o eliminato (D31): mai un nascosto nello strumento ne' nei numeri. Si chiama quando si apre la sessione e
   * prima dei numeri. Dice se ha tolto qualcosa.
   */
  suspend fun dropHidden(): Boolean {
    val current = state.value
    if (current.run.isEmpty()) return false
    val visible = env.visibleAmong(current.run.flatMap { it.members }.toSet())
    val next = current.withVisibleOnly { it in visible }
    if (next.run.size == current.run.size && next.run.zip(current.run).all { (a, b) -> a === b }) return false
    change { next }
    return true
  }

  // --- I giudizi -----------------------------------------------------------------------------------------------------------------

  fun judge(id: String, verdict: EvalVerdict, why: EvalWhy? = null) = change { it.withJudgement(id, verdict, why) }

  fun clearJudgement(id: String) = change { it.withoutJudgement(id) }

  /** Segna [media] come foto da togliere per [fault], o toglie il segno ([fault] nullo). */
  fun setFault(id: String, media: String, fault: EvalFault?) = change { it.withFault(id, media, fault) }

  /** "Sono tutte giuste": toglie tutti i segni di un ricordo. */
  fun clearFaults(id: String) = change { it.withoutFaults(id) }

  /** Il prossimo ricordo senza giudizio dopo [id] (o dal primo, se [id] e' nullo); null se non ce ne sono. */
  fun nextPending(id: String?): String? {
    val run = state.value.run
    val start = if (id == null) 0 else run.indexOfFirst { it.id == id } + 1
    val pending = state.value.pending.map { it.id }.toSet()
    return (run.drop(start) + run.take(start)).firstOrNull { it.id in pending && it.id != id }?.id
  }

  private fun change(block: (MemoriesLabelSet) -> MemoriesLabelSet) {
    val next = block(state.value)
    store.saveLabels(next)
    state.value = next
  }

  // --- I numeri ------------------------------------------------------------------------------------------------------------------

  /** I numeri di adesso, o null se nessun ricordo ha un giudizio. */
  fun report(): MemoriesEvalReport? = if (state.value.judged.isEmpty()) null else MemoriesEvalMetrics.report(state.value)

  /** Il resoconto di F125 (soli numeri) nel file `report-NN.txt`; il nome del file e il testo. */
  fun saveReport(): Pair<String, String>? {
    val report = report() ?: return null
    val text = report.toNumbersText()
    return store.saveReport(text) to text
  }

  /** Toglie i giudizi, i resoconti e la sessione. */
  fun wipe() {
    store.wipe()
    state.value = MemoriesLabelSet()
    loadError = null
  }
}
