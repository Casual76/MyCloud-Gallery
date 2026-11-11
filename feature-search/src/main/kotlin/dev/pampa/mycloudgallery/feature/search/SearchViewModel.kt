package dev.pampa.mycloudgallery.feature.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.BindsOptionalOf
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.components.SingletonComponent
import dev.pampa.mycloudgallery.core.ai.contract.DeviceProbe
import dev.pampa.mycloudgallery.core.ai.contract.TextReadingSwitch
import dev.pampa.mycloudgallery.core.models.contract.ModelManager
import dev.pampa.mycloudgallery.core.search.contract.SearchData
import dev.pampa.mycloudgallery.core.search.contract.SearchEngine
import dev.pampa.mycloudgallery.core.search.contract.SearchHistory
import dev.pampa.mycloudgallery.core.search.contract.Suggestions
import dev.pampa.mycloudgallery.core.search.contract.Today
import dev.pampa.mycloudgallery.core.search.noop.NoSearchEngine
import dev.pampa.mycloudgallery.core.search.noop.NoSearchHistory
import dev.pampa.mycloudgallery.core.search.noop.NoSuggestions
import dev.pampa.mycloudgallery.feature.search.contract.IndexerControl
import dev.pampa.mycloudgallery.feature.search.contract.SearchFaces
import dev.pampa.mycloudgallery.feature.search.contract.TextReadingAccepted
import java.util.Optional
import javax.inject.Inject

// Il ViewModel della ricerca (Fase 6, P8; D7 della Fase 2: @HiltViewModel; H4: la ricerca dentro la Libreria). Tutte le porte sono OPZIONALI per Dagger
// ([SearchPortsModule]): `:app` (P11) lega il motore, i suggerimenti, la cronologia, i dati e i modelli; finche' non lo fa, la ricerca parte lo stesso con i
// null-object (nessun risultato, nessun suggerimento: falliscono chiusi) e il grafo di `:app` compila.

@HiltViewModel
class SearchViewModel @Inject constructor(
  engine: Optional<SearchEngine>,
  suggestions: Optional<Suggestions>,
  history: Optional<SearchHistory>,
  data: Optional<SearchData>,
  models: Optional<ModelManager>,
  faces: Optional<SearchFaces>,
) : ViewModel() {

  val controller: SearchController = SearchController(
    engine = engine.orElse(NoSearchEngine),
    suggestions = suggestions.orElse(NoSuggestions),
    history = history.orElse(NoSearchHistory),
    data = data.orElse(null),
    models = models.orElse(null),
    scope = viewModelScope,
    // I2: gli esempi con la foto a rotazione, preparati fuori dal thread principale (SearchExamples).
    examples = SearchExamples(engine.orElse(NoSearchEngine), data.orElse(null), faces.orElse(null), viewModelScope),
  )
}

/** Le porte di Cerca e della pagina Ricerca e modelli, opzionali per Dagger: `:app` le lega a core-search, core-ai, core-models, core-personal e core-family. */
@Module
@InstallIn(SingletonComponent::class)
abstract class SearchPortsModule {

  @BindsOptionalOf
  abstract fun searchEngine(): SearchEngine

  @BindsOptionalOf
  abstract fun suggestions(): Suggestions

  @BindsOptionalOf
  abstract fun searchHistory(): SearchHistory

  @BindsOptionalOf
  abstract fun searchData(): SearchData

  @BindsOptionalOf
  abstract fun today(): Today

  @BindsOptionalOf
  abstract fun modelManager(): ModelManager

  @BindsOptionalOf
  abstract fun indexerControl(): IndexerControl

  @BindsOptionalOf
  abstract fun textReadingSwitch(): TextReadingSwitch

  @BindsOptionalOf
  abstract fun textReadingAccepted(): TextReadingAccepted

  @BindsOptionalOf
  abstract fun deviceProbe(): DeviceProbe

  /** I2: i volti delle persone per le anteprime dei suggerimenti (con il cancello dei volti delle Raccolte). */
  @BindsOptionalOf
  abstract fun searchFaces(): SearchFaces
}
