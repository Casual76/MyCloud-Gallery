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
import dev.pampa.mycloudgallery.ai.PersonalTextReadingAccepted
import dev.pampa.mycloudgallery.ai.aiIndexContributor
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
import dev.pampa.mycloudgallery.feature.search.contract.TextReadingAccepted
import dev.pampa.mycloudgallery.ai.AppAnalysisLive
import dev.pampa.mycloudgallery.feature.search.contract.AnalysisLiveSource
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
  fun aiStack(@ApplicationContext context: Context, graph: AppGraph, gallery: GalleryGraph, people: dev.pampa.mycloudgallery.faces.PeopleBridge): AiStack =
    AiWiring.build(context, graph, gallery, diag = { code -> graph.events.code(code) }, people = people).also { stack ->
      // La parte `ai` dell'indice per il NAS (legata prima che parta) e l'arresto per primo all'azzeramento (§1.4).
      graph.aiHooks = stack.ai.hooks
      graph.aiStopper = GalleryStopper {
        // L'interruttore della lettura per primo (I13): niente piu' emissioni che riscrivano `text-reading` mentre si azzera.
        stack.textSwitch.stop()
        stack.ai.stop()
        stack.search.stop()
      }
      graph.aiForgetter = GalleryStopper {
        stack.textSwitch.forget()
        stack.personal.forget()
      }
    }

  @Provides fun aiGraph(stack: AiStack): AiGraph = stack.ai

  @Provides fun searchGraph(stack: AiStack): SearchGraph = stack.search

  @Provides fun searchEngine(stack: AiStack): SearchEngine = stack.search.engine

  @Provides fun suggestions(stack: AiStack): Suggestions = stack.search.suggestions

  @Provides fun searchHistory(stack: AiStack): SearchHistory = stack.search.history

  @Provides fun searchData(stack: AiStack): SearchData = stack.search.data

  @Provides fun today(stack: AiStack): Today = stack.search.today

  @Provides fun modelManager(stack: AiStack): ModelManager = stack.requestingModels

  @Provides fun textReadingSwitch(stack: AiStack): TextReadingSwitch = stack.textSwitch

  @Provides
  @Singleton
  fun textReadingAccepted(stack: AiStack, graph: AppGraph): TextReadingAccepted = PersonalTextReadingAccepted(stack.personal, graph.scope)

  @Provides
  @Singleton
  fun indexerControl(@ApplicationContext context: Context, stack: AiStack, graph: AppGraph): IndexerControl =
    AppIndexerControl(stack, graph.scope, java.io.File(context.noBackupFilesDir, AppIndexerControl.PROBE_FILE)) { AiWorkScheduling.indexNow(context) }

  /** Pacchetto X: il nastro della pagina "Analisi delle foto" (le chiavi del motore dietro il cancello della Libreria). */
  @Provides
  @Singleton
  fun analysisLive(stack: AiStack, graph: AppGraph, gallery: GalleryGraph): AnalysisLiveSource =
    AppAnalysisLive(stack.ai, gallery.repository.snapshot, stack.search.keys, stack.ai.parts.facts, { key -> stack.search.index.hasVectors(key) }, graph.scope)

  @Provides @Singleton fun deviceProbe(stack: AiStack, graph: AppGraph): DeviceProbe {
    return dev.pampa.mycloudgallery.core.ai.runtime.SingleFlightDeviceProbe(
      RealDeviceProbe(stack.ai.images, canMeasure = { graph.rulesEngine.canMeasureDevice() })
    )
  }

  @Provides
  @Singleton
  fun motionAnimationSettings(stack: AiStack, graph: AppGraph): dev.pampa.mycloudgallery.feature.settings.library.MotionAnimationSettings =
    dev.pampa.mycloudgallery.ai.PersonalMotionAnimationSettings(stack.personal, graph.scope)

  @Provides
  @Singleton
  fun searchEntryStatus(stack: AiStack, graph: AppGraph): dev.pampa.mycloudgallery.feature.settings.search.SearchEntryStatus =
    dev.pampa.mycloudgallery.ai.AppSearchEntryStatus(stack, graph.scope)

  @Provides
  @IntoSet
  fun aiDiagnostics(stack: AiStack): DiagnosticsContributor = AiDiagnostics(stack)

  /** DG-1 (revisione A): i dati AI dell'indice in Diagnostica (rifiuti, disaccordi, stato della pubblicazione e dei tetti). */
  @Provides
  @IntoSet
  fun aiIndexDiagnostics(stack: AiStack): DiagnosticsContributor =
    aiIndexContributor(stack.ai.indexCounters, { stack.ai.publishStore.pending() }, { stack.search.index.counters })
}
