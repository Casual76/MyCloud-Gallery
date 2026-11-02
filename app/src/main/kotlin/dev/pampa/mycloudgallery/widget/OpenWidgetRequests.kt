package dev.pampa.mycloudgallery.widget

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import dev.pampa.mycloudgallery.core.widget.contract.WidgetTapRequest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

// IL TOCCO SUL WIDGET, DENTRO L'APP (Fase 10, P3, docs/fase10-progetto.md §2.2, D9, C17, C433). `MainActivity` riconosce l'azione fissa del widget mentre ripulisce
// l'intent (`MainActivity.entryOf`) e la trasforma in questo evento interno, **che porta solo i due numeri** del tocco (il widget e la cella, `WidgetTapRequest`):
// nessun id di media, nessun dato, nessun extra. Resta in attesa finche' l'app non e' sbloccata; lo serve chi sta **sotto** il blocco app ([ServeOpenWidgetRequests],
// dentro `AppLockGate`) e **solo la finestra principale** (`ServesEntryRequests`). Cosa aprire lo decide `WidgetTaps.resolve` sul registro locale (P13: serve il grafo).

/**
 * Il tocco sul widget in attesa di essere servito. Uno per processo, costruito da Hilt. Una richiesta nuova **sostituisce** quella ancora in attesa (conta l'ultimo tocco: due
 * tocchi prima dello sblocco non aprono due pagine).
 */
@Singleton
class OpenWidgetRequests @Inject constructor() {
  private val pendingFlow = MutableStateFlow<WidgetTapRequest?>(null)

  /** Il tocco che nessuno ha ancora servito, o null. */
  val pending: StateFlow<WidgetTapRequest?> = pendingFlow

  /** Un tocco sul widget (dall'azione fissa): [tap] porta solo i due numeri gia' validati da `WidgetTap.parse`. */
  fun request(tap: WidgetTapRequest) {
    pendingFlow.value = tap
  }

  /** La serve chi e' sotto il blocco app: il tocco **una volta sola** (null se non c'era o se l'ha gia' preso un altro). */
  fun take(): WidgetTapRequest? {
    val current = pendingFlow.value ?: return null
    return if (pendingFlow.compareAndSet(current, null)) current else null
  }
}

/**
 * Serve il tocco sul widget **dopo il blocco app** (D9): va messo dentro il contenuto di `AppLockGate`, che non e' in composizione finche' l'app e' bloccata, e dentro
 * `ServesEntryRequests` (con due finestre lo serve la principale). [onOpen] parte quando l'app e' sbloccata e c'e' un tocco, una volta per tocco. Lo mette in `AppRoot` P13 con
 * `WidgetTaps.resolve` e la navigazione verso il viewer, la pagina del ricordo o la Libreria.
 */
@Composable
fun ServeOpenWidgetRequests(requests: OpenWidgetRequests, onOpen: (WidgetTapRequest) -> Unit) {
  val pending by requests.pending.collectAsState()
  LaunchedEffect(pending) {
    if (pending != null) requests.take()?.let(onOpen)
  }
}
