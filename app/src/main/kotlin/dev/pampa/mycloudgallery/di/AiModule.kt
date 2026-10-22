package dev.pampa.mycloudgallery.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import dev.pampa.mycloudgallery.ai.AiDiagnostics
import dev.pampa.mycloudgallery.ai.AiGraph
import dev.pampa.mycloudgallery.ai.AiStack
import dev.pampa.mycloudgallery.ai.AiWiring
import dev.pampa.mycloudgallery.ai.AppIndexerControl
import dev.pampa.mycloudgallery.ai.PersonalSearchOrderStore
import dev.pampa.mycloudgallery.ai.PersonalTextReadingAccepted
import dev.pampa.mycloudgallery.core.ai.contract.DeviceProbe
import dev.pampa.mycloudgallery.core.ai.contract.TextReadingSwitch
import dev.pampa.mycloudgallery.core.ai.runtime.RealDeviceProbe
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagnosticsContributor
import dev.pampa.mycloudgallery.core.models.contract.ModelManager
import dev.pampa.mycloudgallery.core.search.contract.SearchData
import dev.pampa.mycloudgallery.core.search.contract.SearchEngine
import dev.pampa.mycloudgallery.core.search.contract.SearchHistory
import dev.pampa.mycloudgallery.core.search.contract.Suggestions
import dev.pampa.mycloudgallery.core.search.contract.Today
import dev.pampa.mycloudgallery.feature.search.contract.IndexerControl
import dev.pampa.mycloudgallery.feature.search.contract.SearchOrderStore
import dev.pampa.mycloudgallery.feature.search.contract.TextReadingAccepted
import dev.pampa.mycloudgallery.gallery.GalleryGraph
import dev.pampa.mycloudgallery.graph.AppGraph
import dev.pampa.mycloudgallery.graph.GalleryStopper
import dev.pampa.mycloudgallery.search.SearchGraph
import dev.pampa.mycloudgallery.work.AiWorkScheduling
import javax.inject.Singleton

/**
 * I contratti della **Fase 6** (la ricerca) legati alle implementazioni vere (P3-P10, P12, P14), tutti dentro un solo [AiStack] per
 * processo (docs/fase6-progetto.md §1.4): modelli, motori, lettura del testo, calcolo, replica `ai`, motore di ricerca. I `@HiltViewModel`
 * di `feature-search` vedono solo i contratti (porte opzionali di `SearchPortsModule`, legate qui).
 */
@Module
@InstallIn(SingletonComponent::class)
object AiModule {

  @Provides
  @Singleton
  fun aiStack(@ApplicationContext context: Context, graph: AppGraph, gallery: GalleryGraph): AiStack =
    AiWiring.build(context, graph, gallery, diag = { code -> graph.events.code(code) }).also { stack ->
      // La parte `ai` dell'indice per il NAS (legata prima che parta) e l'arresto per primo all'azzeramento (§1.4).
      graph.aiHooks = stack.ai.hooks
      graph.aiStopper = GalleryStopper {
        stack.ai.stop()
        stack.search.stop()
      }
    }

  @Provides fun aiGraph(stack: AiStack): AiGraph = stack.ai

  @Provides fun searchGraph(stack: AiStack): SearchGraph = stack.search

  @Provides fun searchEngine(stack: AiStack): SearchEngine = stack.search.engine

  @Provides fun suggestions(stack: AiStack): Suggestions = stack.search.suggestions

  @Provides fun searchHistory(stack: AiStack): SearchHistory = stack.search.history

  @Provides fun searchData(stack: AiStack): SearchData = stack.search.data

  @Provides fun today(stack: AiStack): Today = stack.search.today

  @Provides fun modelManager(stack: AiStack): ModelManager = stack.localModels.manager

  @Provides fun textReadingSwitch(stack: AiStack): TextReadingSwitch = stack.textSwitch

  @Provides
  @Singleton
  fun searchOrderStore(stack: AiStack, graph: AppGraph): SearchOrderStore = PersonalSearchOrderStore(stack.personal, graph.scope)

  @Provides
  @Singleton
  fun textReadingAccepted(stack: AiStack, graph: AppGraph): TextReadingAccepted = PersonalTextReadingAccepted(stack.personal, graph.scope)

  @Provides
  @Singleton
  fun indexerControl(@ApplicationContext context: Context, stack: AiStack, graph: AppGraph): IndexerControl =
    AppIndexerControl(stack, graph.scope) { AiWorkScheduling.indexNow(context) }

  @Provides fun deviceProbe(stack: AiStack): DeviceProbe = RealDeviceProbe(stack.ai.parts.images)

  @Provides
  @IntoSet
  fun aiDiagnostics(stack: AiStack): DiagnosticsContributor = AiDiagnostics(stack)
}
