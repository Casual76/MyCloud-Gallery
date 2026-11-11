package dev.pampa.mycloudgallery.feature.search.models

import dev.pampa.mycloudgallery.core.models.contract.ModelId

/** Tutto cio' che la pagina Ricerca e modelli puo' chiedere (§2.6). La implementa [SearchSettingsController] (con le uscite di `SearchActions`). */
interface SearchSettingsHandlers {
  /** Il ruolo Indicizzatore di questo dispositivo (D15, Q3). */
  fun onSetIndexer(on: Boolean)

  fun onRetryProbe() = Unit

  fun onIndexNow()

  /** La pagina di attivazione della lettura del testo (D41). */
  fun onOpenActivation()

  fun onExcludeThisDevice()

  fun onIncludeThisDevice()

  fun onDisableReading()

  fun onDownloadModel(id: ModelId)

  /** "Scarica con i dati mobili": il "Si'" alla conferma con la dimensione (MO-2). */
  fun onConfirmMobileDownload(id: ModelId)

  fun onRemoveModel(id: ModelId)

  /** "Tienilo": il ruolo e' spento ma il modello di analisi resta (IX-8). */
  fun onKeepIndexModel()

  fun onClearHistory()

  fun onOpenDiagnostics()

  fun onFreeSpace()
}

/** Nessuna azione: per le anteprime e gli screenshot. */
object NoSearchSettingsHandlers : SearchSettingsHandlers {
  override fun onSetIndexer(on: Boolean) = Unit

  override fun onIndexNow() = Unit

  override fun onOpenActivation() = Unit

  override fun onExcludeThisDevice() = Unit

  override fun onIncludeThisDevice() = Unit

  override fun onDisableReading() = Unit

  override fun onDownloadModel(id: ModelId) = Unit

  override fun onConfirmMobileDownload(id: ModelId) = Unit

  override fun onRemoveModel(id: ModelId) = Unit

  override fun onKeepIndexModel() = Unit

  override fun onClearHistory() = Unit

  override fun onOpenDiagnostics() = Unit

  override fun onFreeSpace() = Unit
}
