package dev.pampa.mycloudgallery.feature.search

import androidx.compose.runtime.Composable
import dev.pampa.mycloudgallery.feature.search.models.NoSearchSettingsHandlers
import dev.pampa.mycloudgallery.feature.search.models.SearchSettingsScreen
import dev.pampa.mycloudgallery.feature.search.models.TextReadingActivationScreen
import dev.pampa.mycloudgallery.core.imaging.contract.ThumbBucket
import dev.pampa.mycloudgallery.core.imaging.contract.ThumbKey
import dev.pampa.mycloudgallery.core.imaging.contract.ThumbRequest
import dev.pampa.mycloudgallery.core.imaging.testing.FakePlaceholderCache
import dev.pampa.mycloudgallery.core.imaging.testing.FakeThumbnailLoader
import dev.pampa.mycloudgallery.core.library.contract.LibraryAnalysisStatus
import dev.pampa.mycloudgallery.core.library.contract.LibraryAnalysisWait
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaRef
import dev.pampa.mycloudgallery.core.model.ThumbHash
import dev.pampa.mycloudgallery.feature.search.contract.AnalysisFlow
import dev.pampa.mycloudgallery.feature.search.contract.AnalysisThumb
import dev.pampa.mycloudgallery.feature.search.contract.SearchRefs
import dev.pampa.mycloudgallery.feature.search.contract.SearchThumbnails
import dev.pampa.mycloudgallery.feature.search.testing.SearchSamples
import java.util.UUID

// Le SCENE degli screenshot delle pagine della ricerca che restano pagine (Ricerca e modelli, attivazione della lettura). Solo dati finti, nessun dato
// personale. La ricerca stessa non e' piu' una pagina (H4): i suoi screenshot sono quelli dello scaffold di :app, dentro la Libreria. I2 (fase I): la pagina
// Ricerca e modelli ridisegnata con anelli e segni va in docs/screenshots/beta-grafica/I2 (prima: docs/screenshots/6-P8, copiati in I2/prima).

/** Una scena degli screenshot: [file] e' il nome base del PNG; [tall] le vuole in una pagina alta (impostazioni, attivazione); [dir] dove va. */
internal enum class Scene(val file: String, val tall: Boolean, val dir: String = I2_DIR) {
  SETTINGS("ricerca-e-modelli", true),
  SETTINGS_PROBLEMS("ricerca-e-modelli-problemi", true),
  SETTINGS_DONE("ricerca-e-modelli-completa", true),
  SETTINGS_ABSENT("ricerca-e-modelli-assenti", true),
  ACTIVATION("attivazione-lettura-del-testo", true, P8_DIR),
}

private const val I2_DIR = "../docs/screenshots/beta-grafica/I2"
private const val P8_DIR = "../docs/screenshots/6-P8"

internal object SearchScenes {
  @Composable
  fun Content(scene: Scene) {
    when (scene) {
      Scene.SETTINGS -> SearchSettingsScreen(SearchSamples.settingsIndexer, false, true, true, true, NoSearchSettingsHandlers, onBack = {})
      Scene.SETTINGS_PROBLEMS -> SearchSettingsScreen(SearchSamples.settingsProblems, false, true, true, true, NoSearchSettingsHandlers, onBack = {})
      Scene.SETTINGS_DONE -> SearchSettingsScreen(SearchSamples.settingsReadingOn, false, true, true, true, NoSearchSettingsHandlers, onBack = {})
      Scene.SETTINGS_ABSENT -> SearchSettingsScreen(SearchSamples.settingsAbsent, false, true, true, true, NoSearchSettingsHandlers, onBack = {})
      Scene.ACTIVATION -> TextReadingActivationScreen(onActivate = {}, onNotNow = {}, onBack = {})
    }
  }
}

/**
 * Pacchetto X: i dati finti della pagina "Analisi delle foto" (solo immagini sintetiche; una voce neutra per un media non mostrabile). Gli screenshot
 * a misura di schermo stanno in `AnalysisScreenshotTest` (grafica XU).
 */
internal object AnalysisScenes {
  val RUNNING = LibraryAnalysisStatus.Running(remaining = 2_700, etaMinutes = null, ready = 12_300, total = 15_000)
  val PAUSED = LibraryAnalysisStatus.Paused(LibraryAnalysisWait.THERMAL, remaining = 2_700, ready = 12_300, total = 15_000)

  val thumbnails = SearchThumbnails(FakeThumbnailLoader(), FakePlaceholderCache(), SearchRefs { _, _ -> null })

  private fun shown(seed: Long, video: Boolean = false): AnalysisThumb.Shown {
    val id = MediaId(UUID(0L, seed).toString())
    return AnalysisThumb.Shown(
      id = id,
      request = ThumbRequest(ThumbKey(id, 1L, ThumbBucket.M), MediaRef.Synthetic(seed, 600, 450)),
      thumbHash = ThumbHash("MTIzNDU2Nzg="),
      video = video,
    )
  }

  /** A riposo: niente di adesso ne' prossime, le ultime finite ferme. */
  fun restFlow(seed: Long = 41): AnalysisFlow =
    AnalysisFlow(current = null, recent = listOf(shown(seed), shown(seed - 1, video = true), shown(seed - 2), shown(seed - 3), shown(seed - 4)), next = emptyList())

  /** XU-9: una fila di foto che la Libreria non mostra: la casella neutra nel mirino, a sinistra e tre di fila a destra. */
  fun quietFlow(): AnalysisFlow = AnalysisFlow(
    current = AnalysisThumb.Quiet,
    recent = listOf(shown(41), AnalysisThumb.Quiet, shown(39)),
    next = listOf(AnalysisThumb.Quiet, AnalysisThumb.Quiet, AnalysisThumb.Quiet, shown(46)),
  )

  fun shownFor(seed: Long, video: Boolean = false): AnalysisThumb.Shown = shown(seed, video)

  fun flow(): AnalysisFlow = AnalysisFlow(
    current = shown(42),
    recent = listOf(shown(41), shown(40, video = true), AnalysisThumb.Quiet, shown(38), shown(37)),
    next = listOf(shown(43), shown(44), shown(45, video = true), shown(46)),
  )
}
