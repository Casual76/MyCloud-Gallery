package dev.pampa.mycloudgallery.feature.backup.free

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.pampa.mycloudgallery.core.backup.contract.ConfirmablePlan
import dev.pampa.mycloudgallery.core.backup.contract.PhoneConfirmation
import dev.pampa.mycloudgallery.core.backup.contract.PhoneConfirmationSheet

// Fase 5B (P12, docs/fase5-progetto.md D21, R6 della 4B; contratto `PhoneConfirmation` di P0-B): i TOCCHI di conferma di un foglio. Una
// conferma del telefono nasce **solo** da un tocco dell'utente su un pulsante di un foglio di riepilogo: questo e' l'unico punto di
// feature-backup dove nasce, e la fa nascere il gestore del pulsante, mai una composizione o un dato (`Phase5BArchitectureTest`, e
// `ConfirmationTapTest` di questo modulo, che prova che nessun'altra riga la chiede). Per un piano normale basta un tocco; per
// "Libera subito" ne servono due, **distinti** (R6): un tocco arrivato meno di 600 ms dopo il precedente, o dopo la comparsa del foglio,
// non conta, e fra i due il foglio cambia pannello (con l'avviso sul backup esterno).

/**
 * I tocchi di conferma di [plan]: avvolge `PhoneConfirmationSheet` (la macchina a tocchi del contratto) in uno stato che Compose osserva.
 * [clock] e' l'orologio **monotono** con cui si datano i tocchi (lo stesso con cui i servizi controllano la conferma).
 */
@Stable
internal class ConfirmTaps(private val plan: ConfirmablePlan, private val clock: () -> Long) {

  private val sheet = PhoneConfirmationSheet(plan, shownAtMonotonicMs = clock())

  /** Quanti tocchi sono stati contati: il foglio mostra il pannello giusto (il secondo di "Libera subito" dopo il primo tocco). */
  var taps: Int by mutableIntStateOf(0)
    private set

  /** I tocchi che servono in tutto. */
  val required: Int get() = plan.requiredConfirmations

  /**
   * Un tocco dell'utente sul pulsante di conferma. Restituisce la conferma solo all'ultimo tocco richiesto; un tocco troppo vicino al
   * precedente (un doppio tocco) non conta.
   */
  fun tap(): PhoneConfirmation? {
    val confirmation = sheet.tap(clock())
    taps = sheet.tapsDone
    return confirmation
  }

  /** L'utente rinuncia: si ricomincia da capo, nessuna conferma resta in giro. */
  fun reset() {
    sheet.reset()
    taps = 0
  }
}

/** I tocchi di conferma di [plan] (null = nessun piano: niente da confermare). Cambiando piano si ricomincia da capo. */
@Composable
internal fun rememberConfirmTaps(plan: ConfirmablePlan?, clock: () -> Long): ConfirmTaps? =
  if (plan == null) null else remember(plan.hash) { ConfirmTaps(plan, clock) }
