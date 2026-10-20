package dev.pampa.mycloudgallery.work

import java.util.concurrent.atomic.AtomicBoolean

/**
 * IN-7 della verifica funzionale 5B: il "Ferma" del lavoro dei **file ricevuti** ("Salva sul NAS", una condivisione grande). Prima era la pausa R4 di
 * **tutto** il backup (`BackupGraph.requestStop(USER)`): chi non aveva ancora fatto il primo backup non vedeva nemmeno "Riprendi", e le foto del
 * telefono restavano ferme per un video ricevuto. Adesso chiede solo che il giro dei file ricevuti finisca al passo dopo (come a tempo scaduto: nessun
 * file a meta', il punto di ripresa e' nel diario). I file restano in coda e riprendono con il giro dopo; il backup delle foto non si accorge di niente.
 *
 * Uno per lavoro: lo crea il servizio all'avvio e lo passa a [BackupStages.incoming]; il ricevitore di "Ferma" lo accende.
 */
class IncomingStop {
  private val flag = AtomicBoolean(false)

  /** L'utente ha toccato "Ferma". */
  fun request() = flag.set(true)

  /** Il giro dei file ricevuti deve finire. */
  fun requested(): Boolean = flag.get()
}
