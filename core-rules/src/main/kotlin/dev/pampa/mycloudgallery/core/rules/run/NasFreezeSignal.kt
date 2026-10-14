package dev.pampa.mycloudgallery.core.rules.run

import dev.pampa.mycloudgallery.core.model.FreezeReason
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * "Una share del NAS e' congelata" (D10, C11 di docs/fase4-progetto.md): il segnale che il motore delle regole chiede alla
 * Fase 4. Lo implementa `:app` (P11) traducendo `FreezeState.frozen` di `core-nasmedia` (che `core-rules` non vede):
 * `Freeze?` diventa `Freeze?.reason`. Con piu' share congelate vale la ragione piu' grave decisa da chi lo implementa.
 *
 * Non e' un permesso ne' un dato del NAS: e' lo stato che la scansione ha salvato nel database locale. Lo leggono i lavori
 * che scrivono sul NAS (`WorkRequest.writesToNas`) e li fermano con `WaitReason.Frozen`.
 */
interface NasFreezeSignal {
  /** La ragione del congelamento, o null se nessuna share e' congelata. */
  val frozen: StateFlow<FreezeReason?>
}

/** Finche' la Fase 4 non e' collegata (e in tutti i test che non lo riguardano): mai congelato. */
object NeverFrozenSignal : NasFreezeSignal {
  override val frozen: StateFlow<FreezeReason?> = MutableStateFlow(null)
}

class FakeNasFreezeSignal(initial: FreezeReason? = null) : NasFreezeSignal {
  override val frozen: MutableStateFlow<FreezeReason?> = MutableStateFlow(initial)
}
