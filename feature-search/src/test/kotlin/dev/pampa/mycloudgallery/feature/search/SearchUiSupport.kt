package dev.pampa.mycloudgallery.feature.search

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.unit.Density
import dev.antigravity.fluidengine.foundation.EngineSettings
import dev.antigravity.fluidengine.foundation.ThemeMode
import dev.pampa.mycloudgallery.core.designsystem.theme.MyCloudGalleryTheme
import dev.pampa.mycloudgallery.core.imaging.testing.FakePlaceholderCache
import dev.pampa.mycloudgallery.core.imaging.testing.FakeThumbnailLoader
import dev.pampa.mycloudgallery.core.library.contract.OrderedTimelineView
import dev.pampa.mycloudgallery.core.library.testing.SyntheticLibrary
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaRef
import dev.pampa.mycloudgallery.core.models.contract.ModelId
import dev.pampa.mycloudgallery.core.search.contract.HistoryEntry
import dev.pampa.mycloudgallery.core.search.contract.PersonOption
import dev.pampa.mycloudgallery.core.search.contract.PlaceOption
import dev.pampa.mycloudgallery.core.search.contract.ResultOrder
import dev.pampa.mycloudgallery.core.search.contract.SearchChip
import dev.pampa.mycloudgallery.core.search.contract.SearchMediaType
import dev.pampa.mycloudgallery.core.search.contract.Suggestion
import dev.pampa.mycloudgallery.feature.search.contract.SearchThumbnails
import dev.pampa.mycloudgallery.feature.search.models.SearchSettingsHandlers
import dev.pampa.mycloudgallery.feature.search.ui.SearchHost

// Il banco della UI di Cerca (Fase 6, P8): tema chiaro o scuro, testo a 1,3x, un caricatore di miniature finto che registra cosa gli si chiede.

/** Le richieste della schermata Cerca, registrate come testo. */
internal class RecordingHandlers : SearchHandlers {
  val calls: MutableList<String> = mutableListOf()
  var opened: OrderedTimelineView? = null

  override fun onQueryChange(text: String) { calls += "query:$text" }

  override fun onSubmit() { calls += "submit" }

  override fun onRecent(entry: HistoryEntry) { calls += "recent:${entry.text}" }

  override fun onDeleteRecent(entry: HistoryEntry) { calls += "delete:${entry.text}" }

  override fun onClearRecents() { calls += "clearAll" }

  override fun onPlace(option: PlaceOption) { calls += "place:${option.label}" }

  override fun onType(type: SearchMediaType, label: String) { calls += "type:$label" }

  override fun onCompletion(suggestion: Suggestion) { calls += "completion:${suggestion.label}" }

  override fun onRemoveChip(chip: SearchChip) { calls += "removeChip:${chip.label}" }

  override fun onChooseAlternative(chip: SearchChip.Place, option: PlaceOption) { calls += "alternative:${option.label}" }

  override fun onPlaceAsWord(chip: SearchChip.Place) { calls += "asWord:${chip.word}" }

  override fun onPerson(option: PersonOption) { calls += "person:${option.label}" }

  override fun onSwitchReading(chip: SearchChip, to: SearchChip) { calls += "reading:${chip.label}->${to.label}" }

  override fun onSetOrder(order: ResultOrder) { calls += "order:${order.code}" }

  override fun onShowLikely() { calls += "showLikely" }

  override fun onOpen(view: OrderedTimelineView, media: MediaId) {
    opened = view
    calls += "open:${media.value}"
  }

  override fun onDownloadModel() { calls += "download" }

  override fun onEnableIndexerHere() { calls += "enableIndexer" }

  override fun onWhyCoverage() { calls += "why" }

  override fun onLearnTextReading() { calls += "learn" }

  override fun onAllowAccess() { calls += "allow" }

  override fun onRetry() { calls += "retry" }

  override fun onDiagnostics() { calls += "diagnostics" }
}

/** Le richieste della pagina Ricerca e modelli, registrate come testo. */
internal class RecordingSettingsHandlers : SearchSettingsHandlers {
  val calls: MutableList<String> = mutableListOf()

  override fun onSetIndexer(on: Boolean) { calls += "indexer:$on" }

  override fun onIndexNow() { calls += "indexNow" }

  override fun onOpenActivation() { calls += "activation" }

  override fun onExcludeThisDevice() { calls += "exclude" }

  override fun onIncludeThisDevice() { calls += "include" }

  override fun onDisableReading() { calls += "disable" }

  override fun onDownloadModel(id: ModelId) { calls += "download:${id.value}" }

  override fun onConfirmMobileDownload(id: ModelId) { calls += "confirmMobile:${id.value}" }

  override fun onRemoveModel(id: ModelId) { calls += "remove:${id.value}" }

  override fun onKeepIndexModel() { calls += "keepIndexModel" }

  override fun onClearHistory() { calls += "clearHistory" }

  override fun onOpenDiagnostics() { calls += "diagnostics" }

  override fun onFreeSpace() { calls += "freeSpace" }
}

/** Le miniature locali della Libreria sintetica; ogni riferimento chiesto al caricatore finisce in [asked]. */
internal class TestThumbnails(library: SyntheticLibrary, nasEvery: Int = 0) {
  val asked: MutableList<MediaRef> = mutableListOf()
  val loader = FakeThumbnailLoader { ref, _ ->
    asked += ref
    dev.pampa.mycloudgallery.core.imaging.testing.SyntheticThumbnailSource().loadNow(ref, SIDE)
  }

  /** Con [nasEvery] > 0 ogni n-esima cella ha solo una copia sul NAS (nessuna sorgente locale vera): il filtro locale la deve scartare. */
  val thumbnails = SearchThumbnails(
    loader = loader,
    placeholders = FakePlaceholderCache(),
    refs = { _, index ->
      if (nasEvery > 0 && index % nasEvery == 0) MediaRef.Nas(locationId = index + 1L, contentKey = 1L, mime = null) else library.refAt(index)
    },
  )

  private companion object {
    const val SIDE = 96
  }
}

/** Mostra [content] nel tema chiaro o scuro, con il testo a [fontScale] se dato (1,3x per l'accessibilita'), dentro l'host dei fogli. */
internal fun ComposeContentTestRule.setSearchContent(dark: Boolean = false, fontScale: Float? = null, content: @Composable () -> Unit) {
  setContent {
    MyCloudGalleryTheme(settings = EngineSettings(themeMode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT)) {
      val density = LocalDensity.current
      CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale ?: density.fontScale)) {
        SearchHost { content() }
      }
    }
  }
  waitForIdle()
}
