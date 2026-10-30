package dev.pampa.mycloudgallery.feature.people.erase

import dev.pampa.mycloudgallery.core.faces.contract.AckState
import dev.pampa.mycloudgallery.core.faces.contract.EraseStart
import dev.pampa.mycloudgallery.core.faces.contract.EraseStatus
import dev.pampa.mycloudgallery.core.faces.contract.EraseStep
import dev.pampa.mycloudgallery.core.faces.contract.EraseWait
import dev.pampa.mycloudgallery.core.faces.contract.ErasePreview
import dev.pampa.mycloudgallery.core.faces.contract.FacesClock
import dev.pampa.mycloudgallery.core.faces.contract.FacesEraseConfirmation
import dev.pampa.mycloudgallery.core.faces.contract.FacesEraser
import dev.pampa.mycloudgallery.core.faces.contract.PeopleAlbumsChoice
import dev.pampa.mycloudgallery.core.faces.contract.StepState
import dev.pampa.mycloudgallery.feature.people.contract.EraseSheetState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Cio' che il foglio deve sapere per costruire la conferma (docs/fase8-progetto.md §2.8, D29): l'impronta dell'anteprima mostrata e l'istante
 * (orologio monotono di [FacesClock], lo stesso con cui "Disattiva e cancella" la controlla) in cui l'anteprima e' comparsa. **Non e' la
 * conferma**: la conferma la costruisce solo il foglio (`FacesEraseSheet.kt`), al tocco del pulsante.
 */
data class EraseSheetToken(val previewDigest: Long, val shownAtMonotonicNanos: Long) {
  override fun toString(): String = "EraseSheetToken"
}

/**
 * La logica del foglio e della pagina "Disattiva e cancella" (Fase 8, P8; §1.5 `EraseSheetState`, §2.8). Senza una cancellazione in corso mostra
 * l'**anteprima** (con "Tieni le foto di adesso come album normali" preselezionata quando ci sono album smart con le persone); la conferma che il
 * foglio costruisce va a [FacesEraser.erase]; poi segue lo stato della cancellazione ([FacesEraser.status]): avanzamento, fine, dispositivi che
 * mancano. Una conferma scaduta o un'anteprima cambiata non toccano niente: si rilegge l'anteprima. Mai un'eccezione verso la schermata.
 */
class FacesEraseController(
  private val eraser: FacesEraser,
  private val clock: FacesClock,
  private val scope: CoroutineScope,
) {
  private val mutableState = MutableStateFlow<EraseSheetState?>(null)

  /** Lo stato del foglio (null = si sta preparando). */
  val state: StateFlow<EraseSheetState?> = mutableState.asStateFlow()

  private val mutableToken = MutableStateFlow<EraseSheetToken?>(null)

  /** L'anteprima mostrata adesso, per la conferma (null fuori dall'anteprima). */
  val token: StateFlow<EraseSheetToken?> = mutableToken.asStateFlow()

  private var following: Job? = null

  /** Apre il foglio: la cancellazione in corso, se c'e', altrimenti l'anteprima. */
  fun load() {
    scope.launch {
      // CE-1: un diario `DONE` resta 30 giorni per la pagina: dopo una riattivazione non e' una cancellazione in corso, e il foglio rifa' l'anteprima.
      val running = eraser.status.value?.takeIf { it.step != EraseStep.DONE }
      if (running != null) follow() else showPreview()
    }
  }

  /** La scelta sugli album smart con le persone. */
  fun choose(choice: PeopleAlbumsChoice) {
    val preview = mutableState.value as? EraseSheetState.Preview ?: return
    if (preview.albumsWithPeople == 0) return
    mutableState.value = preview.copy(choice = choice)
  }

  /** Il tocco su "Disattiva e cancella": la conferma la costruisce il foglio. */
  fun confirm(confirmation: FacesEraseConfirmation) {
    val preview = mutableState.value as? EraseSheetState.Preview ?: return
    if (!preview.canConfirm) return
    val choice = preview.choice ?: PeopleAlbumsChoice.KEEP_PHOTOS
    mutableState.value = EraseSheetState.Confirming
    mutableToken.value = null
    scope.launch {
      val outcome = try {
        eraser.erase(confirmation, choice)
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        EraseStart.Unavailable
      }
      when (outcome) {
        EraseStart.Started -> {
          mutableState.value = EraseSheetState.Started
          follow()
        }
        EraseStart.AlreadyRunning -> follow()
        EraseStart.NothingToErase -> mutableState.value = EraseSheetState.NothingToErase
        EraseStart.Expired, EraseStart.PreviewChanged, EraseStart.Unavailable -> mutableState.value = EraseSheetState.Expired
      }
    }
  }

  /** "Rileggi il riepilogo" dopo una conferma scaduta. */
  fun reload() {
    mutableState.value = null
    load()
  }

  private suspend fun showPreview() {
    val preview = try {
      eraser.preview()
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      null
    }
    if (preview == null || preview.digest == 0L) {
      // Niente da cancellare (Persone spenta qui): R12, non si dice "fatto ovunque" e non si parla del NAS ne' degli altri dispositivi.
      mutableState.value = EraseSheetState.NothingToErase
      return
    }
    mutableToken.value = EraseSheetToken(preview.digest, clock.monotonicNanos())
    mutableState.value = previewState(preview)
  }

  private fun follow() {
    if (following?.isActive == true) return
    following = scope.launch {
      eraser.status.collect { status -> if (status != null) mutableState.value = EraseSheetStates.of(status) }
    }
  }

  companion object {
    /** L'anteprima come la mostra il foglio: con album, "Tieni le foto" e' preselezionata. */
    fun previewState(preview: ErasePreview): EraseSheetState.Preview = EraseSheetState.Preview(
      namedPeople = preview.namedPeople,
      albumsWithPeople = preview.albumsWithPeople,
      historyWithNames = preview.historyWithNames,
      devices = preview.devices,
      choice = if (preview.albumsWithPeople > 0) PeopleAlbumsChoice.KEEP_PHOTOS else null,
      localOnly = preview.localOnly,
    )
  }
}

/**
 * Lo stato della pagina dallo stato della cancellazione ([EraseStatus]): per il foglio e per Impostazioni -> Persone (`PeopleSettingsFacts.erase`,
 * legato da `:app`, P13). Finita senza copie nel cestino di rete = [EraseSheetState.Done] (NAS e tutti i dispositivi) o [EraseSheetState.DoneHere]
 * (solo locale, R12); tutto fatto tranne le conferme dei dispositivi =
 * [EraseSheetState.WaitingDevices] (solo quelli che mancano); altrimenti l'avanzamento con il motivo dell'attesa (anche il cestino di rete, E-F34).
 */
object EraseSheetStates {
  fun of(status: EraseStatus): EraseSheetState {
    val waitingDevices = status.devices.filter { it.state == AckState.WAITING }
    return when {
      // R12: "Fatto ovunque" solo con il NAS e tutti i dispositivi confermati; la cancellazione solo locale (nessun passo sul NAS) dice quello che e' successo.
      status.step == EraseStep.DONE && status.waiting == null -> when {
        status.nas == StepState.NOT_NEEDED -> EraseSheetState.DoneHere
        status.nas == StepState.DONE && waitingDevices.isEmpty() -> EraseSheetState.Done
        else -> progress(status)
      }
      status.local == StepState.DONE && status.nas.settled && status.waiting == EraseWait.DEVICES && waitingDevices.isNotEmpty() ->
        EraseSheetState.WaitingDevices(waitingDevices)
      else -> progress(status)
    }
  }

  /** Sempre l'avanzamento (Impostazioni -> Persone lo mostra in una riga). */
  fun progress(status: EraseStatus): EraseSheetState.Progress = EraseSheetState.Progress(status.local, status.nas, status.devices, status.waiting)

  private val StepState.settled: Boolean get() = this == StepState.DONE || this == StepState.NOT_NEEDED
}
