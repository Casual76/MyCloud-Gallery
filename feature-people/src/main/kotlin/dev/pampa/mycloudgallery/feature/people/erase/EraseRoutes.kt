package dev.pampa.mycloudgallery.feature.people.erase

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.BindsOptionalOf
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.components.SingletonComponent
import dev.pampa.mycloudgallery.core.faces.contract.AckState
import dev.pampa.mycloudgallery.core.faces.contract.EraseWait
import dev.pampa.mycloudgallery.core.faces.contract.FacesClock
import dev.pampa.mycloudgallery.core.faces.contract.FacesEraser
import dev.pampa.mycloudgallery.core.faces.contract.StepState
import dev.pampa.mycloudgallery.core.faces.noop.NoFacesEraser
import dev.pampa.mycloudgallery.feature.people.R
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.Optional
import javax.inject.Inject

// LE ROTTE E LE PORTE DEL FOGLIO "DISATTIVA E CANCELLA" (Fase 8, P8; docs/fase8-progetto.md §1.4, §2.8). `:app` (P13) lega una sola `ErasePorts`
// (opzionale per Dagger, come `ActivationPorts` di P7): finche' non c'e', il foglio vede `NoFacesEraser` (niente da cancellare). La conferma non
// passa da qui: la costruisce il foglio (`FacesEraseSheet`).

/** Cio' che il foglio chiede a `:app`: "Disattiva e cancella" e l'orologio monotono dei volti (lo stesso con cui la conferma si controlla). */
interface ErasePorts {
  val eraser: FacesEraser
  val clock: FacesClock
}

/** Niente legato: niente da cancellare. */
object NoErasePorts : ErasePorts {
  override val eraser: FacesEraser = NoFacesEraser
  override val clock: FacesClock = object : FacesClock {
    override fun monotonicNanos(): Long = 0L

    override fun nasNowMillis(): Long? = null
  }
}

/** Il modulo Hilt della porta opzionale di P8 (una dichiarazione sola, di un tipo suo). */
@Module
@InstallIn(SingletonComponent::class)
abstract class ErasePortsModule {

  @BindsOptionalOf
  abstract fun erasePorts(): ErasePorts
}

@HiltViewModel
class FacesEraseViewModel @Inject constructor(ports: Optional<ErasePorts>) : ViewModel() {
  private val bound = ports.orElse(NoErasePorts)

  val controller: FacesEraseController = FacesEraseController(bound.eraser, bound.clock, viewModelScope)

  init {
    controller.load()
  }
}

/** Il foglio "Disattiva e cancella" e la sua pagina d'avanzamento. "Annulla", "Chiudi" e il tasto indietro chiudono senza cambiare niente. */
@Composable
fun FacesEraseRoute(onClose: () -> Unit, viewModel: FacesEraseViewModel = hiltViewModel()) {
  val controller = viewModel.controller
  val state by controller.state.collectAsStateWithLifecycle()
  val token by controller.token.collectAsStateWithLifecycle()
  FacesEraseSheet(
    state = state,
    token = token,
    onConfirm = controller::confirm,
    onChoose = controller::choose,
    onCancel = onClose,
    onReload = controller::reload,
    onClose = onClose,
    onBack = onClose,
  )
}

/** I testi del foglio che dipendono dallo stato. */
internal object EraseText {

  fun step(step: StepState): Int = when (step) {
    StepState.NOT_STARTED -> R.string.faces_erase_step_not_started
    StepState.RUNNING -> R.string.faces_erase_step_running
    StepState.WAITING -> R.string.faces_erase_step_waiting
    StepState.DONE -> R.string.faces_erase_step_done
    StepState.NOT_NEEDED -> R.string.faces_erase_step_not_needed
  }

  fun wait(wait: EraseWait): Int = when (wait) {
    EraseWait.NAS -> R.string.faces_erase_wait_nas
    EraseWait.FROZEN -> R.string.faces_erase_wait_frozen
    EraseWait.CLOCK_JUMP -> R.string.faces_erase_wait_clock_jump
    EraseWait.READ_ONLY -> R.string.faces_erase_wait_read_only
    EraseWait.EMERGENCY_STOP -> R.string.faces_erase_wait_emergency_stop
    EraseWait.NOT_TRUSTED -> R.string.faces_erase_wait_not_trusted
    EraseWait.DEVICES -> R.string.faces_erase_wait_devices
    EraseWait.RECYCLE_BIN -> R.string.faces_erase_wait_recycle_bin
  }

  fun ack(state: AckState): Int = when (state) {
    AckState.CONFIRMED -> R.string.faces_erase_step_done
    AckState.WAITING -> R.string.faces_erase_step_waiting
    AckState.NOT_EXPECTED -> R.string.faces_erase_step_not_needed_short
  }

  /**
   * Il giorno dell'ultimo contatto ("3 novembre"), dall'ora del NAS della testa: nessun orologio del telefono. In italiano come le frasi intorno
   * (e come le date del resto della galleria).
   */
  fun day(atNasMillis: Long): String = DayFormat.format(Instant.ofEpochMilli(atNasMillis).atZone(ZoneId.systemDefault()))

  private val DayFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMMM", Locale.ITALIAN)
}
