package dev.pampa.mycloudgallery.core.faces.switch

import dev.pampa.mycloudgallery.core.faces.contract.FacesLocal
import dev.pampa.mycloudgallery.core.faces.contract.FacesState
import dev.pampa.mycloudgallery.core.faces.contract.FacesStop
import dev.pampa.mycloudgallery.core.faces.contract.FacesSwitch
import dev.pampa.mycloudgallery.core.faces.contract.FacesWait
import dev.pampa.mycloudgallery.core.faces.contract.HereState
import dev.pampa.mycloudgallery.core.model.FaceGeneration
import dev.pampa.mycloudgallery.core.model.FacesSetting

// LE REGOLE DELLO STATO DI PERSONE SU QUESTO DISPOSITIVO (docs/fase8-progetto.md §2.1, D3, D4, D34, E-F7, E-F12). Una funzione pura: dalla
// voce dell'account, dallo stato locale e dalla recinzione allo stato che questo dispositivo mostra e su cui lavora. Ogni riga della tabella
// del §2.1 e' un caso di `FacesStateRulesTest`.

object FacesStateRules {

  /**
   * Lo stato di questo dispositivo. Nell'ordine:
   *  1. **Una cancellazione in corso** ([FacesLocal.erasing], la scrive "Disattiva e cancella"): `Erasing`, qualunque cosa dica la voce: la
   *     scelta e' dell'utente e continua fino alla fine.
   *  2. **Voce assente o spenta** (`null`, [FacesSetting.Off]): `Off`. L'esclusione locale resta ricordata, ma da spenta non si vede.
   *  3. **Forma sconosciuta** ([FacesSetting.Unknown], un'app piu' nuova su un altro dispositivo, E-F12): `NeedsUpdate`. Niente calcolo,
   *     niente pubblicazione e nessuna cancellazione.
   *  4. **Attiva** ([FacesSetting.On] con la generazione `g` e il testo `v`):
   *     - **l'esclusione vince su tutto** (D4: "Non su questo dispositivo" vale anche prima dell'avviso, e dopo un testo nuovo): `On(g, EXCLUDED)`;
   *     - il testo e' di un'app piu' nuova (`v` > [currentTextVersion]): questa pagina non sa mostrare quel testo, `NeedsUpdate` (la scelta
   *       dell'utente e' stata fatta su parole che qui non ci sono);
   *     - **l'avviso non e' stato visto** per questa generazione e per la versione di questa app ([noticeSeen]): `AwaitingNotice(g, versione)`.
   *       Vale anche quando l'account ha una versione piu' vecchia del testo (il testo e' cambiato: "tutti i dispositivi tornano all'avviso");
   *       la versione che l'avviso mostra e' sempre quella di questa app;
   *     - altrimenti `On(g, ACTIVE)`, o `On(g, LOCAL_ONLY)` con la recinzione delle share vere ([fenced], D34).
   */
  fun derive(
    setting: FacesSetting?,
    local: FacesLocal,
    fenced: Boolean,
    currentTextVersion: Int = FacesSwitch.CURRENT_TEXT_VERSION,
  ): FacesState {
    local.erasing?.let { return FacesState.Erasing(it) }
    return when (setting) {
      null, is FacesSetting.Off -> FacesState.Off
      FacesSetting.Unknown -> FacesState.NeedsUpdate
      is FacesSetting.On -> when {
        local.excluded -> FacesState.On(setting.generation, HereState.EXCLUDED)
        setting.textVersion > currentTextVersion -> FacesState.NeedsUpdate
        !noticeSeen(local, setting.generation, currentTextVersion) -> FacesState.AwaitingNotice(setting.generation, currentTextVersion)
        else -> FacesState.On(setting.generation, if (fenced) HereState.LOCAL_ONLY else HereState.ACTIVE)
      }
    }
  }

  /**
   * L'avviso della generazione [generation] e' stato visto qui per il testo [required] (o uno piu' nuovo)? E' per generazione: dopo
   * "Disattiva e cancella" e una riattivazione (generazione nuova, E-F33) l'avviso si rivede, come per un testo cambiato.
   */
  fun noticeSeen(local: FacesLocal, generation: FaceGeneration, required: Int): Boolean =
    local.noticedGeneration == generation && (local.noticedTextVersion ?: 0) >= required

  /**
   * I dati locali di [dataGeneration] sono **non piu' correnti** secondo la voce [evidence]? Solo una prova esplicita lo dice: la voce
   * spenta ([FacesSetting.Off], di qualunque generazione: l'account e' spento, nessuna generazione e' corrente) o accesa con **un'altra**
   * generazione. Una voce assente o sconosciuta non e' una prova (E-F12; e uno store non ancora aperto, o un registro azzerato, non
   * devono far cancellare dati biometrici).
   */
  fun replacedBy(evidence: FacesSetting?, dataGeneration: FaceGeneration): Boolean = when (evidence) {
    is FacesSetting.Off -> true
    is FacesSetting.On -> evidence.generation != dataGeneration
    FacesSetting.Unknown, null -> false
  }

  /**
   * Perche' un lavoro dei volti (calcolo, scaricamento, pubblicazione) **non parte** su questo dispositivo, o null se puo' partire. E' il cancello che
   * tutti i lavori guardano prima di ogni passo (`FacesSwitch.currentState`): con l'avviso da vedere, un'esclusione, la voce spenta o sconosciuta niente si
   * scarica, si calcola o si mostra (D4, E-F7, E-F12).
   */
  fun stopReason(state: FacesState): FacesStop? = when {
    state.activeHere -> null
    state is FacesState.Erasing -> FacesStop.ERASING
    else -> FacesStop.NOT_ACTIVE
  }

  /** Lo stesso motivo per lo stato del lavoro (`FacesStatus.Waiting`): null se Persone e' spenta (nessuna attesa, e' spenta) o se lavora. */
  fun waitReason(state: FacesState): FacesWait? = when (state) {
    FacesState.Off -> null
    is FacesState.On -> if (state.here == HereState.EXCLUDED) FacesWait.EXCLUDED else null
    is FacesState.AwaitingNotice -> FacesWait.NOTICE
    is FacesState.Erasing -> FacesWait.ERASING
    FacesState.NeedsUpdate -> FacesWait.NEEDS_UPDATE
  }
}
