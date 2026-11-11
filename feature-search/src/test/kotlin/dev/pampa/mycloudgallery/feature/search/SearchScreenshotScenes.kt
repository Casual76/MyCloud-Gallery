package dev.pampa.mycloudgallery.feature.search

import androidx.compose.runtime.Composable
import dev.pampa.mycloudgallery.feature.search.models.NoSearchSettingsHandlers
import dev.pampa.mycloudgallery.feature.search.models.SearchSettingsScreen
import dev.pampa.mycloudgallery.feature.search.models.TextReadingActivationScreen
import dev.pampa.mycloudgallery.feature.search.testing.SearchSamples

// Le SCENE degli screenshot di Fase 6, P8 (docs/screenshots/6-P8): le pagine della ricerca che restano pagine (Ricerca e modelli, attivazione della lettura).
// Solo dati finti, nessun dato personale. La ricerca stessa non e' piu' una pagina (H4): i suoi screenshot sono quelli dello scaffold di :app, dentro la
// Libreria (docs/screenshots/beta-grafica/H4).

/** Una scena degli screenshot: [file] e' il nome base del PNG; [tall] le vuole in una pagina alta (impostazioni, attivazione). */
internal enum class Scene(val file: String, val tall: Boolean) {
  SETTINGS("ricerca-e-modelli", true),
  SETTINGS_PROBLEMS("ricerca-e-modelli-problemi", true),
  ACTIVATION("attivazione-lettura-del-testo", true),
}

internal object SearchScenes {
  @Composable
  fun Content(scene: Scene) {
    when (scene) {
      Scene.SETTINGS -> SearchSettingsScreen(SearchSamples.settingsIndexer, false, true, true, true, NoSearchSettingsHandlers, onBack = {})
      Scene.SETTINGS_PROBLEMS -> SearchSettingsScreen(SearchSamples.settingsProblems, false, true, true, true, NoSearchSettingsHandlers, onBack = {})
      Scene.ACTIVATION -> TextReadingActivationScreen(onActivate = {}, onNotNow = {}, onBack = {})
    }
  }
}
