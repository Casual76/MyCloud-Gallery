package dev.pampa.mycloudgallery.feature.search

import dev.pampa.mycloudgallery.core.library.contract.OrderedTimelineView
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.search.contract.HistoryEntry
import dev.pampa.mycloudgallery.core.search.contract.PersonOption
import dev.pampa.mycloudgallery.core.search.contract.PlaceOption
import dev.pampa.mycloudgallery.core.search.contract.ResultOrder
import dev.pampa.mycloudgallery.core.search.contract.SearchChip
import dev.pampa.mycloudgallery.core.search.contract.SearchMediaType
import dev.pampa.mycloudgallery.core.search.contract.Suggestion

/**
 * Tutto cio' che la schermata Cerca puo' chiedere (docs/fase6-progetto.md §2.6). La implementa [SearchController]; i test e gli
 * screenshot usano [NoSearchHandlers]. Ogni azione dei messaggi vuoti e d'errore e' una di queste (o una `SearchActions`).
 */
interface SearchHandlers {
  fun onQueryChange(text: String)

  fun onSubmit()

  // --- A riposo ---
  fun onRecent(entry: HistoryEntry)

  fun onDeleteRecent(entry: HistoryEntry)

  fun onClearRecents()

  fun onPlace(option: PlaceOption)

  fun onType(type: SearchMediaType, label: String)

  // --- Mentre si scrive ---
  fun onCompletion(suggestion: Suggestion)

  // --- Chip e risultati ---
  fun onRemoveChip(chip: SearchChip)

  fun onChooseAlternative(chip: SearchChip.Place, option: PlaceOption)

  /** "Cerca come parola" (E-Q10): il luogo torna a essere una parola del testo. */
  fun onPlaceAsWord(chip: SearchChip.Place)

  /**
   * Una persona della riga "Persone" a riposo o dei completamenti (Fase 8, §2.7): cerca quel nome, con il chip "Persona" gia' fatto (non passa
   * dall'analizzatore: un nome che e' anche un luogo non cambia lettura).
   */
  fun onPerson(option: PersonOption)

  /**
   * Un'altra lettura della stessa parola (Fase 8, E-F36, come E-Q10): "Cerca la festa", "Cerca il luogo", "Cerca come parola" da un chip "Persona";
   * "Cerca la persona" da una data o da un luogo che ha una persona con lo stesso nome. [to] e' il chip che prende il posto di [chip].
   */
  fun onSwitchReading(chip: SearchChip, to: SearchChip)

  fun onSetOrder(order: ResultOrder)

  /** "Mostra anche risultati meno probabili (N)" (E-Q12). */
  fun onShowLikely()

  fun onOpen(view: OrderedTimelineView, media: MediaId)

  // --- Azioni dei messaggi (§2.6) ---
  /** "Scarica" (E-Q11): il modello della ricerca (270M), con la richiesta dell'utente. */
  fun onDownloadModel()

  /** "Attiva su questo dispositivo" (E-I12): il ruolo Indicizzatore di questo dispositivo. */
  fun onEnableIndexerHere()

  /** "Perche'?" (copertura): Impostazioni, Ricerca e modelli. */
  fun onWhyCoverage()

  /** "Scopri di piu'": la pagina di attivazione della lettura del testo (D41). */
  fun onLearnTextReading()

  /** "Consenti accesso" (libreria vuota o permessi negati). */
  fun onAllowAccess()

  fun onRetry()

  fun onDiagnostics()
}

/** Nessuna azione: per le anteprime e gli screenshot. */
object NoSearchHandlers : SearchHandlers {
  override fun onQueryChange(text: String) = Unit

  override fun onSubmit() = Unit

  override fun onRecent(entry: HistoryEntry) = Unit

  override fun onDeleteRecent(entry: HistoryEntry) = Unit

  override fun onClearRecents() = Unit

  override fun onPlace(option: PlaceOption) = Unit

  override fun onType(type: SearchMediaType, label: String) = Unit

  override fun onCompletion(suggestion: Suggestion) = Unit

  override fun onRemoveChip(chip: SearchChip) = Unit

  override fun onChooseAlternative(chip: SearchChip.Place, option: PlaceOption) = Unit

  override fun onPlaceAsWord(chip: SearchChip.Place) = Unit

  override fun onPerson(option: PersonOption) = Unit

  override fun onSwitchReading(chip: SearchChip, to: SearchChip) = Unit

  override fun onSetOrder(order: ResultOrder) = Unit

  override fun onShowLikely() = Unit

  override fun onOpen(view: OrderedTimelineView, media: MediaId) = Unit

  override fun onDownloadModel() = Unit

  override fun onEnableIndexerHere() = Unit

  override fun onWhyCoverage() = Unit

  override fun onLearnTextReading() = Unit

  override fun onAllowAccess() = Unit

  override fun onRetry() = Unit

  override fun onDiagnostics() = Unit
}
