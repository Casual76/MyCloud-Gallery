package dev.pampa.mycloudgallery.feature.search

import dev.pampa.mycloudgallery.core.search.contract.Suggestion

/**
 * Tutto cio' che la ricerca dentro la Libreria puo' chiedere (H4): il testo del campo, il tasto «Cerca» della tastiera, un suggerimento della barra in
 * alto, «Riprova». La implementa [SearchController]; i test e gli screenshot usano [NoSearchHandlers]. Le uscite (viewer, pagine) sono `SearchActions`.
 */
interface SearchHandlers {
  /** Il testo del campo cambia: i suggerimenti e i risultati lo seguono dopo una pausa. */
  fun onQueryChange(text: String)

  /** «Cerca» sulla tastiera: la ricerca parte subito e si ricorda fra le recenti. */
  fun onSubmit()

  /** Un suggerimento della barra in alto: diventa il testo cercato (una persona con il suo filtro gia' fatto) e si ricorda. */
  fun onSuggestion(suggestion: Suggestion)

  /** «Riprova» dopo un errore interno. */
  fun onRetry()
}

/** Nessuna azione: per le anteprime e gli screenshot. */
object NoSearchHandlers : SearchHandlers {
  override fun onQueryChange(text: String) = Unit

  override fun onSubmit() = Unit

  override fun onSuggestion(suggestion: Suggestion) = Unit

  override fun onRetry() = Unit
}
