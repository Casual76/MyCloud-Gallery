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
 *  - spenta: la riga "Attiva riconoscimento dei volti"; in attesa dell'avviso: la riga dell'avviso; esclusa: "Non attiva su questo dispositivo";
 *  - il primo calcolo (nessuna persona ancora): "Sto riconoscendo i volti: 35%", o l'attesa (senza percentuale);
 *  - con Q2 (b) **solo le persone con un nome** in primo piano ([TOP]) e "Altri volti (N)"; con Q2 (a) anche le altre, dopo quelle con un nome;
 *  - con la recinzione dei volti (D34: sulle share vere i dati restano su questo dispositivo) la stessa cosa con la nota ([PeopleSectionState.LocalOnly]);
 *  - forma sconosciuta (un'app piu' nuova), cancellazione in corso e modelli non disponibili: [PeopleSectionState.Unavailable].
 */
object PeopleSectionStates {

  /** Fino a 8 persone in primo piano (§2.6). */
  const val TOP: Int = 8

  fun of(state: FacesState, list: PeopleList, status: FacesStatus): PeopleSectionState = when (state) {
    FacesState.Off -> PeopleSectionState.Off
    is FacesState.AwaitingNotice -> PeopleSectionState.AwaitingNotice
    is FacesState.Erasing -> PeopleSectionState.Unavailable(PeopleUnavailable.ERASING)
    FacesState.NeedsUpdate -> PeopleSectionState.Unavailable(PeopleUnavailable.NEEDS_UPDATE)
    is FacesState.On -> if (state.here == HereState.EXCLUDED) PeopleSectionState.Excluded else active(state.here == HereState.LOCAL_ONLY, list, status)
  }

  private fun active(localOnly: Boolean, list: PeopleList, status: FacesStatus): PeopleSectionState {
    val nobody = list.named.isEmpty() && list.unnamed.isEmpty() && list.othersCount == 0
    if (nobody) {
      if (isModelProblem(status)) return PeopleSectionState.Unavailable(PeopleUnavailable.MODELS)
      list.progressPercent?.let { return PeopleSectionState.FirstRun(it.coerceIn(0, 100)) }
      when (status) {
        is FacesStatus.Running -> return PeopleSectionState.FirstRun(percentOf(status))
        is FacesStatus.Waiting -> if (status.reason != FacesWait.NOTICE && status.reason != FacesWait.EXCLUDED) return PeopleSectionState.FirstRun(null)
        else -> Unit
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
