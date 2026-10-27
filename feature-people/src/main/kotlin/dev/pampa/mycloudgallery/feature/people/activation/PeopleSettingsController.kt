package dev.pampa.mycloudgallery.feature.people.activation

import dev.pampa.mycloudgallery.core.faces.contract.FacesState
import dev.pampa.mycloudgallery.core.faces.contract.FacesSwitch
import dev.pampa.mycloudgallery.core.faces.contract.HereState
import dev.pampa.mycloudgallery.feature.people.contract.PeopleSettingsState
import dev.pampa.mycloudgallery.feature.people.contract.PeopleSwitchShown
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
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
) {
  val state: StateFlow<PeopleSettingsState> = combine(switch.state, facts) { current, extra -> compose(current, extra) }
    .stateIn(scope, SharingStarted.Eagerly, compose(switch.currentState(), facts.value))

  /** Lo stato dell'interruttore, per sapere cosa offrire (escludere, includere, cancellare). */
  val switchState: StateFlow<FacesState> = switch.state

  private fun compose(current: FacesState, extra: PeopleSettingsFacts): PeopleSettingsState = PeopleSettingsState(
    shown = PeopleSettingsRules.shownOf(current),
    devices = extra.devices,
    progress = extra.progress,
    excludedHere = current is FacesState.On && current.here == HereState.EXCLUDED,
    hiddenPeople = extra.hiddenPeople,
    models = extra.models,
    canErase = PeopleSettingsRules.canErase(current),
    erase = extra.erase,
  )

  /** "Non su questo dispositivo": esclusione locale. */
  fun exclude() {
    scope.launch { switch.excludeThisDevice() }
  }

  /** "Attiva qui" su un dispositivo escluso: nessuna generazione nuova. */
  fun include() {
    scope.launch { switch.includeThisDevice() }
  }
}
