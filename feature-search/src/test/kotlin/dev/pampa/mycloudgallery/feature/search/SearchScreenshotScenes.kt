package dev.pampa.mycloudgallery.feature.search

import androidx.compose.runtime.Composable
import dev.pampa.mycloudgallery.feature.search.models.NoSearchSettingsHandlers
import dev.pampa.mycloudgallery.feature.search.models.SearchSettingsScreen
import dev.pampa.mycloudgallery.feature.search.models.TextReadingActivationScreen
import dev.pampa.mycloudgallery.feature.search.testing.SearchSamples

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
