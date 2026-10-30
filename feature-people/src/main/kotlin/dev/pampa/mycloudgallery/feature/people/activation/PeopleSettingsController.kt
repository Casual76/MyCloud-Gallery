package dev.pampa.mycloudgallery.feature.people.activation

import dev.pampa.mycloudgallery.core.faces.contract.FacesState
import dev.pampa.mycloudgallery.core.faces.contract.FacesStatus
import dev.pampa.mycloudgallery.core.faces.contract.FacesSwitch
import dev.pampa.mycloudgallery.core.faces.contract.HereState
import dev.pampa.mycloudgallery.feature.people.contract.AnalyzeNowState
import dev.pampa.mycloudgallery.feature.people.contract.PeopleSettingsState
import dev.pampa.mycloudgallery.feature.people.contract.PeopleSwitchShown
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

// IMPOSTAZIONI -> PERSONE (docs/fase8-progetto.md §1.5 `PeopleSettingsState`, §2.1, D4, D34; riga P7 del §4.4): lo stato dell'interruttore e cio' che
// `:app` aggiunge (chi analizza, avanzamento, nascoste, modelli, cancellazione) in una sola struttura per la schermata. Le azioni di questa pagina sono
// quelle dell'interruttore: "Non su questo dispositivo", "Attiva qui", "Va bene" sull'avviso. "Disattiva e cancella" e' di P8 (foglio e conferma).

/** Le regole che portano lo stato dell'interruttore alla riga mostrata e a cosa si offre. Pure: si provano con una tabella. */
object PeopleSettingsRules {

  fun shownOf(state: FacesState): PeopleSwitchShown = when (state) {
    FacesState.Off -> PeopleSwitchShown.OFF
    is FacesState.On -> when (state.here) {
      HereState.ACTIVE -> PeopleSwitchShown.ON
      HereState.LOCAL_ONLY -> PeopleSwitchShown.LOCAL_ONLY
      HereState.EXCLUDED -> PeopleSwitchShown.EXCLUDED_HERE
    }
    is FacesState.AwaitingNotice -> PeopleSwitchShown.AWAITING_NOTICE
    is FacesState.Erasing -> PeopleSwitchShown.ERASING
    FacesState.NeedsUpdate -> PeopleSwitchShown.NEEDS_UPDATE
  }

  /** "Disattiva e cancella" si offre con Persone accesa o in attesa dell'avviso (anche da escluso: l'account e' acceso), mai due volte (§1.5). */
  fun canErase(state: FacesState): Boolean = state is FacesState.On || state is FacesState.AwaitingNotice

  /** "Non su questo dispositivo" si offre dove Persone potrebbe lavorare: attiva qui o in attesa dell'avviso. */
  fun canExclude(state: FacesState): Boolean = (state is FacesState.On && state.here != HereState.EXCLUDED) || state is FacesState.AwaitingNotice

  /** "Attiva qui" si offre solo a un dispositivo escluso. */
  fun canInclude(state: FacesState): Boolean = state is FacesState.On && state.here == HereState.EXCLUDED

  /** "Chi analizza" e l'avanzamento hanno senso solo dove Persone e' accesa per l'account e non si sta cancellando. */
  fun showsWork(state: FacesState): Boolean = state is FacesState.On && state.here != HereState.EXCLUDED
}

class PeopleSettingsController(
  private val switch: FacesSwitch,
  facts: StateFlow<PeopleSettingsFacts>,
  private val scope: CoroutineScope,
  /** CA-2: «Analizza ora» con la stima (null = non si offre). */
  analyze: FacesAnalyzeNow? = null,
) {
  private val analyzeNow = AnalyzeNowController(analyze, scope)

  /** Tutto cio' che cambia lo stato della pagina, preso insieme: i pezzi non si leggono mai a meta' (una pagina "nota" con lo stato di prima). */
  private class Inputs(
    val current: FacesState,
    val extra: PeopleSettingsFacts,
    val known: Boolean,
    val offElsewhere: Boolean,
    val analyze: AnalyzeNowState,
  )

  private val inputs = combine(switch.state, facts, switch.known, switch.offElsewhere, analyzeNow.state) { current, extra, known, off, analyzeState ->
    Inputs(current, extra, known, off, analyzeState)
  }

  val state: StateFlow<PeopleSettingsState> = inputs.map(::compose).stateIn(scope, SharingStarted.Eagerly, compose(initialInputs(facts)))

  /** Lo stato dell'interruttore, per sapere cosa offrire (escludere, includere, cancellare). */
  val switchState: StateFlow<FacesState> = switch.state

  init {
    // CA-2: la stima si conta quando «Analizza ora» ha senso (Persone lavora qui, questo dispositivo analizza, il calcolo non e' in corso) e di nuovo quando il
    // calcolo cambia di fase: dopo il primo disegno e fuori dal thread principale (il conto vive nella porta). Con lo stato non ancora noto non si conta niente.
    scope.launch {
      var first = true
      combine(switch.state, switch.known, facts) { current, known, extra ->
        val offered = known && PeopleSettingsRules.showsWork(current) && extra.computesHere && extra.progress !is FacesStatus.Running
        offered to extra.progress::class
      }.distinctUntilChanged().collect { (offered, _) ->
        if (offered) {
          // Il calcolo e' cambiato di fase dopo una richiesta (e' finito, o aspetta di nuovo le regole): «Fatto: l'analisi parte adesso» non e' piu' vero, si rilegge.
          if (!first) analyzeNow.reset()
          analyzeNow.refresh()
        } else {
          analyzeNow.hide()
        }
        first = false
      }
    }
  }

  private fun initialInputs(facts: StateFlow<PeopleSettingsFacts>) =
    Inputs(switch.currentState(), facts.value, switch.known.value, switch.offElsewhere.value, analyzeNow.state.value)

  private fun compose(i: Inputs): PeopleSettingsState = PeopleSettingsState(
    shown = PeopleSettingsRules.shownOf(i.current),
    devices = i.extra.devices,
    progress = i.extra.progress,
    excludedHere = i.current is FacesState.On && i.current.here == HereState.EXCLUDED,
    hiddenPeople = i.extra.hiddenPeople,
    models = i.extra.models,
    canErase = PeopleSettingsRules.canErase(i.current),
    erase = i.extra.erase,
    computesHere = i.extra.computesHere,
    known = i.known,
    // L322: la riga c'e' solo da spenta; con Persone accesa (o di nuovo accesa) non si dice piu' niente.
    offElsewhere = i.offElsewhere && i.current == FacesState.Off,
    analyze = i.analyze,
  )

  /** "Non su questo dispositivo": esclusione locale. */
  fun exclude() {
    scope.launch { switch.excludeThisDevice() }
  }

  /** "Attiva qui" su un dispositivo escluso: nessuna generazione nuova. */
  fun include() {
    scope.launch { switch.includeThisDevice() }
  }

  /** «Analizza ora»: il tocco chiede la conferma, che puo' portare la stima dei dati mobili. */
  fun askAnalyze() = analyzeNow.ask()

  fun cancelAnalyze() = analyzeNow.cancel()

  /** La conferma: il calcolo parte adesso e scavalca le regole di sottofondo. */
  fun confirmAnalyze() = analyzeNow.confirm()
}
