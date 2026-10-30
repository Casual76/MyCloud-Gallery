package dev.pampa.mycloudgallery.feature.people.activation

import dev.pampa.mycloudgallery.feature.people.contract.AnalyzeNowState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

// «ANALIZZA ORA» (CA-2, correzioni C; risposta dell'utente del 30/10): la logica comune a Impostazioni -> Persone e alla pagina di attivazione. Il primo calcolo
// dopo l'accensione segue le regole di sottofondo (notte, carica, Wi-Fi); questo tasto, con la sua conferma, e' l'unico modo di scavalcarle. Il principio del
// primo disegno istantaneo (docs/decisioni.md, "Avvio e pagine istantanei"): la pagina si disegna subito e la stima arriva dopo, perche' contare il lavoro che resta
// rilegge il catalogo (L242): il conto gira dentro la porta, fuori dal thread principale, e qui si aspetta solo il risultato.

/**
 * Lo stato di «Analizza ora» e i suoi gesti. [analyze] null = non si offre mai ([AnalyzeNowState.Hidden]). Un solo controllore per pagina, sul suo [scope].
 */
class AnalyzeNowController(
  private val analyze: FacesAnalyzeNow?,
  private val scope: CoroutineScope,
) {
  private val mutableState = MutableStateFlow<AnalyzeNowState>(AnalyzeNowState.Hidden)

  val state: StateFlow<AnalyzeNowState> = mutableState.asStateFlow()

  private var counting: Job? = null

  /** Si offre dove Persone lavora qui e non e' gia' in corso. Con [analyze] null non si offre mai. */
  val available: Boolean get() = analyze != null

  /**
   * Conta cio' che resta (la pagina e' gia' disegnata: lo stato intanto e' [AnalyzeNowState.Counting]) e decide se offrire il tasto. Niente da analizzare, o una
   * stima che non si sa dare, nasconde tutto. Una richiesta gia' partita ([AnalyzeNowState.Started]) non si tocca. Idempotente: un conto in corso non se ne
   * fa partire un altro.
   */
  fun refresh() {
    val port = analyze ?: return
    if (mutableState.value is AnalyzeNowState.Started || counting?.isActive == true) return
    // Una conferma aperta non si chiude da sola: lo stato resta finche' l'utente non sceglie.
    if (mutableState.value is AnalyzeNowState.Confirming) return
    // Una stima gia' in vista resta li' mentre si rilegge; solo da "niente" si parte dallo scheletro.
    if (mutableState.value is AnalyzeNowState.Hidden) mutableState.value = AnalyzeNowState.Counting
    counting = scope.launch {
      val estimate = try {
        port.estimate()
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        null
      }
      val current = mutableState.value
      if (current is AnalyzeNowState.Counting || current is AnalyzeNowState.Ready) {
        mutableState.value = if (estimate != null && estimate.remaining > 0) AnalyzeNowState.Ready(estimate) else AnalyzeNowState.Hidden
      }
    }
  }

  /** Persone non lavora qui (spenta, esclusa, in cancellazione) o il calcolo e' in corso: niente da offrire, ma una richiesta gia' fatta resta detta. */
  fun hide() {
    counting?.cancel()
    if (mutableState.value !is AnalyzeNowState.Started) mutableState.value = AnalyzeNowState.Hidden
  }

  /** Il tocco su «Analizza ora»: chiede la conferma. Sui dati mobili la conferma porta la frase delle regole con la stima dei dati. */
  fun ask() {
    val port = analyze ?: return
    val ready = mutableState.value as? AnalyzeNowState.Ready ?: return
    scope.launch {
      val network = try {
        port.networkNote(ready.estimate)
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        null
      }
      // Nel frattempo la stima puo' essere cambiata (il lavoro e' partito, e' finito): si conferma solo cio' che si era visto.
      if (mutableState.value == ready) mutableState.value = AnalyzeNowState.Confirming(ready.estimate, network)
    }
  }

  /** «Annulla»: torna a offrire il tasto. */
  fun cancel() {
    val confirming = mutableState.value as? AnalyzeNowState.Confirming ?: return
    mutableState.value = AnalyzeNowState.Ready(confirming.estimate)
  }

  /** «Analizza ora» confermato: il calcolo parte adesso. Solo da una conferma aperta, mai da un tocco solo. */
  fun confirm() {
    val port = analyze ?: return
    if (mutableState.value !is AnalyzeNowState.Confirming) return
    mutableState.value = AnalyzeNowState.Started
    port.start()
  }

  /** Per chi vuole ricominciare (la richiesta e' finita o la pagina e' stata riaperta): lo stato torna a quello di prima della richiesta. */
  fun reset() {
    if (mutableState.value is AnalyzeNowState.Started) mutableState.value = AnalyzeNowState.Hidden
  }
}
