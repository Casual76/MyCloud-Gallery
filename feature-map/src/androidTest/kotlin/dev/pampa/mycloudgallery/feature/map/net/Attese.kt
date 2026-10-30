package dev.pampa.mycloudgallery.feature.map.net

import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * Le attese dei test strumentati della mappa (P1), con le regole di `app/src/test/.../integration/Attese.kt`: si aspetta **un evento** (lo stile
 * caricato, i fotogrammi disegnati, una richiesta vista dal server finto, un rifiuto contato), mai un tempo fisso; il tetto e' largo e uguale per
 * tutti, e trasforma un blocco in un fallimento che dice cosa si aspettava. Qui il thread del test puo' bloccarsi (non e' quello principale).
 */
internal object Attese {

  /** Il tetto di ogni attesa (l'emulatore con la GPU software e altre build sul PC possono essere molto lenti). */
  val TETTO: Duration = 2.minutes

  /** Ogni quanto si ricontrolla una condizione che non ha un flusso da seguire (un contatore, un elenco registrato). */
  const val SONDA_MS: Long = 20L
}

/** Aspetta che [condition] diventi vera, riguardandola ogni [Attese.SONDA_MS] ms; dopo [Attese.TETTO] fallisce dicendo [what]. */
internal fun until(what: String, condition: () -> Boolean) {
  val deadline = System.nanoTime() + Attese.TETTO.inWholeNanoseconds
  while (!condition()) {
    if (System.nanoTime() > deadline) throw AssertionError("Dopo ${Attese.TETTO} non e' successo: $what")
    Thread.sleep(Attese.SONDA_MS)
  }
}
