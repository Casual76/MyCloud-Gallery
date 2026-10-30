package dev.pampa.mycloudgallery.core.faces.testing

import dev.pampa.mycloudgallery.core.faces.contract.ActivateOutcome
import dev.pampa.mycloudgallery.core.faces.contract.DeviceAck
import dev.pampa.mycloudgallery.core.faces.contract.EraseReport
import dev.pampa.mycloudgallery.core.faces.contract.EraseStart
import dev.pampa.mycloudgallery.core.faces.contract.EraseStatus
import dev.pampa.mycloudgallery.core.faces.contract.EraseStep
import dev.pampa.mycloudgallery.core.faces.contract.ErasePreview
import dev.pampa.mycloudgallery.core.faces.contract.FacesActivationConsent
import dev.pampa.mycloudgallery.core.faces.contract.FacesEraseConfirmation
import dev.pampa.mycloudgallery.core.faces.contract.FacesEraser
import dev.pampa.mycloudgallery.core.faces.contract.FacesRequirements
import dev.pampa.mycloudgallery.core.faces.contract.FacesState
import dev.pampa.mycloudgallery.core.faces.contract.FacesSwitch
import dev.pampa.mycloudgallery.core.faces.contract.HereState
import dev.pampa.mycloudgallery.core.faces.contract.PeopleAlbumsChoice
import dev.pampa.mycloudgallery.core.faces.contract.PrivateAreaState
import dev.pampa.mycloudgallery.core.faces.contract.ReconcileOutcome
import dev.pampa.mycloudgallery.core.faces.contract.StepState
import dev.pampa.mycloudgallery.core.model.FaceGeneration
import dev.pampa.mycloudgallery.core.model.FacesSetting
import kotlin.time.Duration
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * L'interruttore finto (P0) per le schermate e i ViewModel (P7, P9): le regole di D3-D5 e D30 nel modo piu' semplice. [setting] e' la voce
 * `faces` che scriverebbe; [erasedLocally] le generazioni i cui dati locali ha cancellato (chiave per prima); [noticed] l'avviso gia' visto.
 * Non e' l'interruttore vero (P7): niente file, niente dati personali, niente orologi.
 */
class FakeFacesSwitch(
  private val requirements: FacesRequirements = FakeFacesRequirements(),
  private val generations: () -> FaceGeneration = { FaceGeneration.random() },
  initial: FacesState = FacesState.Off,
) : FacesSwitch {
  override val state = MutableStateFlow(initial)
  override val fresh = MutableStateFlow(true)
  override val syncPending = MutableStateFlow(false)

  /** L350: di serie lo stato e' quello vero; un test mette `false` per provare "non ancora". */
  override val known = MutableStateFlow(true)

  /** CE-3 e L322: Persone e' spenta da un altro dispositivo (un test lo mette a `true` da spenta). */
  override val offElsewhere = MutableStateFlow(false)

  var setting: FacesSetting? = null
    private set

  val erasedLocally: MutableList<FaceGeneration> = mutableListOf()
  private var noticed: Pair<FaceGeneration, Int>? = null
  private var localData: FaceGeneration? = (initial as? FacesState.On)?.generation

  override suspend fun activate(consent: FacesActivationConsent): ActivateOutcome {
    if (state.value == FacesState.NeedsUpdate) return ActivateOutcome.NeedsUpdate
    if (consent.textVersion != FacesSwitch.CURRENT_TEXT_VERSION) return ActivateOutcome.StaleConsent
    val area = requirements.privateArea()
    if (area !is PrivateAreaState.Declared) return ActivateOutcome.NoPrivateArea
    val indexers = requirements.indexers()
    if (indexers.accountIndexers == 0) return ActivateOutcome.NoIndexer(indexers.canBecomeIndexer)
    val g = generations()
    setting = FacesSetting.On(g, consent.textVersion)
    noticed = g to consent.textVersion
    localData = g
    syncPending.value = true
    state.value = FacesState.On(g, if (requirements.localOnly(area.share)) HereState.LOCAL_ONLY else HereState.ACTIVE)
    return ActivateOutcome.Activated(g)
  }

  override suspend fun acceptNotice(textVersion: Int) {
    val current = state.value as? FacesState.AwaitingNotice ?: return
    if (textVersion != current.textVersion) return
    noticed = current.generation to textVersion
    state.value = FacesState.On(current.generation, HereState.ACTIVE)
  }

  override suspend fun excludeThisDevice() {
    val g = state.value.generationOrNull ?: return
    localData?.let { erasedLocally += it }
    localData = null
    state.value = FacesState.On(g, HereState.EXCLUDED)
  }

  override suspend fun includeThisDevice() {
    val current = state.value as? FacesState.On ?: return
    if (current.here == HereState.EXCLUDED) state.value = current.copy(here = HereState.ACTIVE)
  }

  override suspend fun reconcile(setting: FacesSetting?): ReconcileOutcome {
    when (setting) {
      FacesSetting.Unknown -> state.value = FacesState.NeedsUpdate
      null, is FacesSetting.Off -> {
        localData?.let { erasedLocally += it }
        localData = null
        state.value = FacesState.Off
      }
      is FacesSetting.On -> {
        if (localData != null && localData != setting.generation) {
          erasedLocally += localData!!
          localData = null
        }
        val seen = noticed == setting.generation to setting.textVersion
        val excluded = (state.value as? FacesState.On)?.here == HereState.EXCLUDED
        state.value = when {
          excluded -> FacesState.On(setting.generation, HereState.EXCLUDED)
          seen -> FacesState.On(setting.generation, HereState.ACTIVE)
          else -> FacesState.AwaitingNotice(setting.generation, setting.textVersion)
        }
      }
    }
    return ReconcileOutcome.Done
  }
}

/** La cancellazione finta (P0) per il foglio e la pagina (P8, P9): l'anteprima a mano, la conferma che scade, lo stato per passi. */
class FakeFacesEraser(
  var preview: ErasePreview = ErasePreview(0, 0, false, emptyList<DeviceAck>(), localOnly = false, digest = 1),
  private val nowNanos: () -> Long = { 0L },
) : FacesEraser {
  private val state = MutableStateFlow<EraseStatus?>(null)
  override val status: StateFlow<EraseStatus?> = state

  val started: MutableList<Pair<FacesEraseConfirmation, PeopleAlbumsChoice>> = mutableListOf()

  override suspend fun preview(): ErasePreview = preview

  override suspend fun erase(confirmation: FacesEraseConfirmation, albums: PeopleAlbumsChoice): EraseStart {
    if (state.value != null) return EraseStart.AlreadyRunning
    if (!confirmation.isValidAt(nowNanos())) return EraseStart.Expired
    if (confirmation.previewDigest != preview.digest) return EraseStart.PreviewChanged
    started += confirmation to albums
    state.value = EraseStatus(FaceGeneration.random(), EraseStep.LOCAL_DONE, StepState.DONE, StepState.WAITING, preview.devices, null)
    return EraseStart.Started
  }

  override suspend fun resume(budget: Duration): EraseReport {
    val current = state.value ?: return EraseReport(EraseStep.DONE, 0, null, done = true)
    state.value = current.copy(step = EraseStep.DONE, nas = StepState.DONE)
    return EraseReport(EraseStep.DONE, 0, null, done = true)
  }
}
