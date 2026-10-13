package dev.pampa.mycloudgallery.feature.library.hidden

import dev.pampa.mycloudgallery.core.library.contract.LocalMediaActions
import dev.pampa.mycloudgallery.core.library.contract.ViewerItems
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.security.lock.AuthPromptText
import dev.pampa.mycloudgallery.core.security.lock.SystemCredentialAuthenticator
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Cosa mostra la pagina dei Nascosti. Solo [Content] ha dei media. */
sealed interface HiddenUiState {

  /** Chiusi: si aspetta lo sblocco. Non e' stato letto niente. */
  data object Locked : HiddenUiState

  /** La schermata di sistema e' aperta. */
  data object Authenticating : HiddenUiState

  /** Lo sblocco non e' riuscito. */
  data class Denied(val reason: HiddenDenial) : HiddenUiState

  /** Sbloccati: si leggono i media. */
  data object Loading : HiddenUiState

  /** La lettura dal database e' fallita. */
  data object Failed : HiddenUiState

  /** Sbloccati e letti. [items] puo' avere zero elementi: la pagina dice che non c'e' niente di nascosto. */
  data class Content(val items: ViewerItems) : HiddenUiState
}

/**
 * La logica della pagina dei Nascosti, senza Android (la prova un test JVM): decide **quando** si leggono i media.
 *
 *  - **Mai prima dello sblocco.** [HiddenLibrary.read] si chiama solo con il cancello aperto, e il risultato si butta
 *    se nel frattempo si e' richiuso.
 *  - **Mai dopo l'uscita.** [hidden] dimentica i media appena la pagina non e' piu' visibile: la memoria del processo
 *    non li tiene mentre si guarda altro. Rientrando entro la tolleranza si rileggono (e' una query a chiave).
 *  - **Mostra** (`setHidden(false)`) solo da sbloccati.
 *
 * Il cancello ([HiddenAccessGate]) e' condiviso e dura piu' di questo oggetto, che segue la destinazione.
 */
class HiddenController(
  private val gate: HiddenAccessGate,
  private val library: HiddenLibrary,
  private val actions: LocalMediaActions,
  private val scope: CoroutineScope,
) {

  private val content = MutableStateFlow<ViewerItems?>(null)
  private val failed = MutableStateFlow(false)
  private var loading: Job? = null

  /** Cresce a ogni lettura e a ogni uscita: l'esito di una lettura di un'epoca vecchia si butta. */
  private var epoch = 0

  /** Cosa mostrare. Calcolato dal cancello e da cio' che e' stato letto: senza cancello aperto non c'e' mai un contenuto. */
  val state: StateFlow<HiddenUiState> = combine(gate.state, content, failed) { access, items, didFail ->
    when (access) {
      HiddenAccess.Locked -> HiddenUiState.Locked
      HiddenAccess.Authenticating -> HiddenUiState.Authenticating
      is HiddenAccess.Denied -> HiddenUiState.Denied(access.reason)
      HiddenAccess.Unlocked -> when {
        didFail -> HiddenUiState.Failed
        items != null -> HiddenUiState.Content(items)
        else -> HiddenUiState.Loading
      }
    }
  }.stateIn(scope, SharingStarted.Eagerly, HiddenUiState.Locked)

  /** Se questa pagina e' visibile: il cancello conta le schermate protette, quindi ingresso e uscita vanno a coppie. */
  private var visible = false

  /** La pagina e' visibile (ingresso, o ritorno in primo piano). Se la tolleranza vale ancora, legge. */
  fun shown() {
    if (!visible) {
      visible = true
      gate.onShown()
    }
    if (gate.isUnlocked) load()
  }

  /** La pagina non e' piu' visibile: si dimentica tutto e, se non resta visibile altro, comincia la tolleranza. */
  fun hidden() {
    epoch++
    loading?.cancel()
    loading = null
    content.value = null
    failed.value = false
    if (visible) {
      visible = false
      gate.onHidden()
    }
  }

  /** Chiede la credenziale; se va a buon fine legge. */
  fun unlock(authenticator: SystemCredentialAuthenticator, prompt: AuthPromptText) {
    scope.launch {
      if (gate.unlock(authenticator, prompt) == HiddenAccess.Unlocked) load()
    }
  }

  /** Non c'e' modo di mostrare la schermata di sistema (nessuna activity adatta): restano chiusi. */
  fun unlockUnavailable() {
    gate.deny(HiddenDenial.UNAVAILABLE)
  }

  /** "Mostra di nuovo nella Libreria": toglie il nascosto da [ids] e rilegge. Solo da sbloccati. */
  fun show(ids: Set<MediaId>) {
    if (ids.isEmpty()) return
    scope.launch {
      if (!gate.isUnlocked) return@launch
      actions.setHidden(ids, false)
      load()
    }
  }

  /** Il ViewModel finisce: niente resta in memoria. */
  fun close() {
    hidden()
  }

  private fun load() {
    loading?.cancel()
    val mine = ++epoch
    loading = scope.launch {
      if (!gate.isUnlocked) return@launch
      failed.value = false
      val items = try {
        library.read()
      } catch (e: CancellationException) {
        throw e
      } catch (_: Exception) {
        failed.value = true
        return@launch
      }
      // Mentre si leggeva ci si puo' essere richiusi o essere usciti: l'esito di una lettura vecchia non si mostra.
      if (mine == epoch && gate.isUnlocked) content.value = items
    }
  }
}
