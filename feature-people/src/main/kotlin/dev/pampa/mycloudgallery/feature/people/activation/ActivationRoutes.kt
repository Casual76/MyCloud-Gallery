package dev.pampa.mycloudgallery.feature.people.activation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.BindsOptionalOf
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.components.SingletonComponent
import dev.pampa.mycloudgallery.feature.people.contract.FacesNoticeState
import java.util.Optional
import javax.inject.Inject

// LE ROTTE DELLA PAGINA DI ATTIVAZIONE, DELL'AVVISO E DI IMPOSTAZIONI -> PERSONE, per `:app` (P13). Tutte sulle stesse porte opzionali
// ([ActivationPorts]): finche' `:app` non le lega, vedono Persone spenta e non accendibile (i null-object di core-faces). Il consenso non
// passa da qui: lo costruisce la pagina (`FacesActivationScreen`) e il controllore lo porta all'interruttore.

/** Il modulo Hilt delle porte opzionali di P7: una sola dichiarazione, perche' i pacchetti vicini (P8, P9) dichiarano le loro e due dello stesso tipo romperebbero `:app`. */
@Module
@InstallIn(SingletonComponent::class)
abstract class ActivationPortsModule {

  @BindsOptionalOf
  abstract fun activationPorts(): ActivationPorts
}

@HiltViewModel
class FacesActivationViewModel @Inject constructor(ports: Optional<ActivationPorts>) : ViewModel() {
  private val bound = ports.orElse(NoActivationPorts)

  val controller: FacesActivationController =
    FacesActivationController(bound.switch, bound.requirements, bound.indexerRole, viewModelScope)

  init {
    controller.load()
  }
}

@HiltViewModel
class FacesNoticeViewModel @Inject constructor(ports: Optional<ActivationPorts>) : ViewModel() {
  private val bound = ports.orElse(NoActivationPorts)

  val controller: FacesNoticeController = FacesNoticeController(bound.switch, bound.activatedBy, viewModelScope)

  init {
    controller.load()
  }
}

@HiltViewModel
class PeopleSettingsViewModel @Inject constructor(ports: Optional<ActivationPorts>) : ViewModel() {
  private val bound = ports.orElse(NoActivationPorts)

  val controller: PeopleSettingsController = PeopleSettingsController(bound.switch, bound.facts, viewModelScope)
}

/** La pagina di attivazione. "Attiva" porta il consenso all'interruttore; "Non ora", "Fatto" e il tasto indietro chiudono senza cambiare niente. */
@Composable
fun FacesActivationRoute(
  onClose: () -> Unit,
  onOpenLicenses: () -> Unit,
  viewModel: FacesActivationViewModel = hiltViewModel(),
) {
  val controller = viewModel.controller
  val state by controller.state.collectAsStateWithLifecycle()
  FacesActivationScreen(
    state = state,
    onActivate = controller::activate,
    onNotNow = onClose,
    onBecomeIndexer = controller::becomeIndexer,
    onOpenLicenses = onOpenLicenses,
    onRetry = controller::retry,
    onDone = onClose,
    onBack = onClose,
  )
}

/**
 * L'avviso una tantum. "Va bene" e "Non su questo dispositivo" cambiano lo stato dell'interruttore e chiudono; "Leggi come funziona" apre la pagina
 * di attivazione ([onRead]) senza scegliere.
 */
@Composable
fun FacesNoticeRoute(
  onClose: () -> Unit,
  onRead: () -> Unit,
  viewModel: FacesNoticeViewModel = hiltViewModel(),
) {
  val controller = viewModel.controller
  val state by controller.state.collectAsStateWithLifecycle()
  // Scelto l'avviso, lo stato diventa `Hidden` da solo: la pagina si chiude.
  var wasShown by remember { mutableStateOf(false) }
  LaunchedEffect(state) {
    if (state is FacesNoticeState.Show) wasShown = true else if (wasShown) onClose()
  }
  FacesNoticeScreen(
    state = state,
    onOk = controller::accept,
    onExclude = controller::excludeHere,
    onRead = onRead,
    onBack = onClose,
  )
}

/** Impostazioni -> Persone. Le uscite sono scritte da `:app`: attivazione, avviso, nascoste (null = non c'e' la riga), licenze, Diagnostica, "Disattiva e cancella". */
@Composable
fun PeopleSettingsRoute(
  onOpenActivation: () -> Unit,
  onOpenNotice: () -> Unit,
  onOpenLicenses: () -> Unit,
  onBack: () -> Unit,
  onOpenHidden: (() -> Unit)? = null,
  onOpenDiagnostics: (() -> Unit)? = null,
  onErase: (() -> Unit)? = null,
  viewModel: PeopleSettingsViewModel = hiltViewModel(),
) {
  val controller = viewModel.controller
  val state by controller.state.collectAsStateWithLifecycle()
  val handlers = remember(controller, onOpenActivation, onOpenNotice, onOpenLicenses, onOpenHidden, onOpenDiagnostics, onErase) {
    PeopleSettingsHandlers(
      onActivate = onOpenActivation,
      onOpenNotice = onOpenNotice,
      onExclude = controller::exclude,
      onInclude = controller::include,
      onOpenHidden = onOpenHidden,
      onOpenLicenses = onOpenLicenses,
      onOpenDiagnostics = onOpenDiagnostics,
      onErase = onErase,
    )
  }
  PeopleSettingsScreen(state = state, handlers = handlers, onBack = onBack)
}
