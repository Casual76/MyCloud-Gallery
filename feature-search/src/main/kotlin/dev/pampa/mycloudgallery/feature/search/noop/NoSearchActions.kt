package dev.pampa.mycloudgallery.feature.search.noop

import dev.pampa.mycloudgallery.core.library.contract.OrderedTimelineView
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.feature.search.contract.SearchActions

/** Fase 6 (P0): nessuna azione (finche' :app non lega la schermata, P11). Non apre niente. */
object NoSearchActions : SearchActions {
  override fun openResults(view: OrderedTimelineView, start: MediaId) = Unit

  override fun openSearchSettings() = Unit

  override fun openTextReadingActivation() = Unit

  override fun openDiagnostics() = Unit

  override fun requestMediaAccess() = Unit
}