package dev.pampa.mycloudgallery.core.models.contract

import java.util.concurrent.atomic.AtomicInteger

/**
 * I contatori dei modelli per la Diagnostica (§2.6, D40: solo numeri, mai nomi, indirizzi, impronte o percorsi). P11 li legge nel
 * catalogo dei codici: [mismatch] e' `AI_MODEL_MISMATCH` (E-A2, E-S16), [fetchFailed] `AI_MODEL_FETCH_FAILED` (E-A1), [noSpace]
 * `AI_MODEL_NO_SPACE` (E-A3); [verifyFailed] conta i file tolti alla verifica di un'apertura (E-A11).
 */
class ModelCounters {
  private val mismatchCount = AtomicInteger()
  private val verifyFailedCount = AtomicInteger()
  private val fetchFailedCount = AtomicInteger()
  private val noSpaceCount = AtomicInteger()
  private val skippedCount = AtomicInteger()

  /** File o copie scartati per byte diversi da quelli fissati (lunghezza, impronta, redirezione fuori dall'elenco, risposta incoerente). */
  val mismatch: Int get() = mismatchCount.get()

  /** File gia' verificati che alla verifica di un'apertura non tornavano piu' (tolti e rimessi in coda). */
  val verifyFailed: Int get() = verifyFailedCount.get()

  /** Richieste finite senza il modello (nessuna sorgente l'ha dato). */
  val fetchFailed: Int get() = fetchFailedCount.get()

  val noSpace: Int get() = noSpaceCount.get()

  /** Sorgenti saltate perche' in attesa del giorno dopo (due errori in un giorno, E-A2). */
  val skippedSources: Int get() = skippedCount.get()

  internal fun mismatch() = mismatchCount.incrementAndGet()

  internal fun verifyFailed() = verifyFailedCount.incrementAndGet()

  internal fun fetchFailed() = fetchFailedCount.incrementAndGet()

  internal fun noSpace() = noSpaceCount.incrementAndGet()

  internal fun skippedSource() = skippedCount.incrementAndGet()

  override fun toString(): String = "ModelCounters(mismatch=$mismatch, verifyFailed=$verifyFailed, fetchFailed=$fetchFailed, noSpace=$noSpace)"
}
