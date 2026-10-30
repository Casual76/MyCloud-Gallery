package dev.pampa.mycloudgallery.feature.people.section

import dev.pampa.mycloudgallery.core.faces.contract.FacesDiag
import dev.pampa.mycloudgallery.core.faces.contract.FacesState
import dev.pampa.mycloudgallery.core.faces.contract.FacesStatus
import dev.pampa.mycloudgallery.core.faces.contract.FacesWait
import dev.pampa.mycloudgallery.core.faces.contract.HereState
import dev.pampa.mycloudgallery.core.faces.contract.PeopleList
import dev.pampa.mycloudgallery.feature.people.contract.PeopleSectionState
import dev.pampa.mycloudgallery.feature.people.contract.PeopleUnavailable

/**
 * Lo stato della sezione Persone delle Raccolte (docs/fase8-progetto.md §1.5 `PeopleSectionState`, §2.6 "Cosa si vede", Q2 (b), D4, D34): una
 * funzione pura da cio' che c'e' gia' (lo stato di Persone su questo dispositivo, le persone e il calcolo). Provata su ogni caso.
 *
 *  - spenta: la riga "Attiva riconoscimento dei volti" (con sopra, se l'ha spenta un altro dispositivo, la riga che lo dice: CE-3); in attesa dell'avviso: la riga dell'avviso; esclusa: "Non attiva su questo dispositivo";
 *  - il primo calcolo (nessuna persona ancora): "Sto riconoscendo i volti: 35%", o l'attesa (senza percentuale);
 *  - **con Persone accesa e nessuna persona la sezione dice sempre qualcosa** (AT-1, correzioni B): il calcolo in corso o in attesa (qualunque sia lo stato del
 *    calcolo, anche prima della sua prima tappa), "Ancora nessuna persona" solo a calcolo in pari ([PeopleSectionState.Ready] vuoto, e [PeopleSections] ne fa una riga);
 *  - su un dispositivo che non analizza le foto: "Qui le persone non si calcolano" ([PeopleSectionState.NotComputedHere], DR-1);
 *  - con Q2 (b) **solo le persone con un nome** in primo piano ([TOP]) e "Altri volti (N)"; con Q2 (a) anche le altre, dopo quelle con un nome;
 *  - con la recinzione dei volti (D34: sulle share vere i dati restano su questo dispositivo) la stessa cosa con la nota ([PeopleSectionState.LocalOnly]);
 *  - forma sconosciuta (un'app piu' nuova), cancellazione in corso e modelli non disponibili: [PeopleSectionState.Unavailable].
 */
object PeopleSectionStates {

  /** Fino a 8 persone in primo piano (§2.6). */
  const val TOP: Int = 8

  /**
   * [offElsewhere] (CE-3): da spenta, Persone e' stata disattivata da un altro dispositivo e qui i dati sono stati tolti. [computesHere] (DR-1): questo
   * dispositivo analizza le foto (ruolo di Indicizzatore); `null` = non si sa, e allora lo dice il solo motivo di attesa del calcolo (`Waiting(NOT_INDEXER)`). Quando
   * si sa, vale piu' di uno stato del calcolo vecchio: un ruolo appena acceso non lascia "qui le persone non si calcolano" fino alla tappa dopo.
   */
  fun of(state: FacesState, list: PeopleList, status: FacesStatus, offElsewhere: Boolean = false, computesHere: Boolean? = null): PeopleSectionState = when (state) {
    FacesState.Off -> if (offElsewhere) PeopleSectionState.OffElsewhere else PeopleSectionState.Off
    is FacesState.AwaitingNotice -> PeopleSectionState.AwaitingNotice
    is FacesState.Erasing -> PeopleSectionState.Unavailable(PeopleUnavailable.ERASING)
    FacesState.NeedsUpdate -> PeopleSectionState.Unavailable(PeopleUnavailable.NEEDS_UPDATE)
    is FacesState.On ->
      if (state.here == HereState.EXCLUDED) PeopleSectionState.Excluded else active(state.here == HereState.LOCAL_ONLY, list, status, computesHere)
  }

  private fun active(localOnly: Boolean, list: PeopleList, status: FacesStatus, computesHere: Boolean?): PeopleSectionState {
    val nobody = list.named.isEmpty() && list.unnamed.isEmpty() && list.othersCount == 0
    if (nobody) {
      if (isModelProblem(status)) return PeopleSectionState.Unavailable(PeopleUnavailable.MODELS)
      list.progressPercent?.let { return PeopleSectionState.FirstRun(it.coerceIn(0, 100)) }
      if (status is FacesStatus.Running) return PeopleSectionState.FirstRun(percentOf(status))
      // Se ci sono persone nascoste le persone ci sono (la sezione lo dice con "Le persone che hai sono nascoste"): niente di quel che segue.
      if (list.hiddenCount == 0) {
        // DR-1: chi non analizza le foto non le calcolera' mai da solo (con D34 nemmeno le persone degli altri dispositivi arrivano): la riga lo dice, invece di
        // "Sto per riconoscere i volti", che qui non succede.
        if (computesHere == false || (computesHere == null && status is FacesStatus.Waiting && status.reason == FacesWait.NOT_INDEXER)) return PeopleSectionState.NotComputedHere
        // AT-1: lo stato del calcolo parte da `Off` a ogni processo e cambia solo quando una tappa parte; con una libreria senza volti, un'attesa o un guasto la
        // lista resta vuota. Solo a calcolo in pari (`Idle`) "nessuna persona" e' la verita'; in tutti gli altri casi il calcolo e' da fare, in corso o in attesa.
        if (status !is FacesStatus.Idle) return PeopleSectionState.FirstRun(null)
      }
    }
    // Con Q2 (a) le persone senza nome stanno in Persone dopo quelle con un nome; con Q2 (b) sono contate in "Altri volti".
    val shown = list.named + list.unnamed
    val top = shown.take(TOP)
    val total = shown.size
    return if (localOnly) PeopleSectionState.LocalOnly(top, total, list.othersCount) else PeopleSectionState.Ready(top, total, list.othersCount)
  }

  private fun isModelProblem(status: FacesStatus): Boolean =
    (status is FacesStatus.Waiting && status.reason == FacesWait.MODELS) || (status is FacesStatus.Problem && status.code == FacesDiag.FACES_MODEL_MISMATCH)

  private fun percentOf(status: FacesStatus.Running): Int = if (status.total <= 0) 0 else (status.done.toLong() * 100 / status.total).toInt().coerceIn(0, 100)
}
