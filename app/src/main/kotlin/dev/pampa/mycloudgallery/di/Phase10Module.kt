package dev.pampa.mycloudgallery.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import dev.pampa.mycloudgallery.ai.AiStack
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagnosticsContributor
import dev.pampa.mycloudgallery.core.describe.contract.MediaDescriber
import dev.pampa.mycloudgallery.core.release.contract.ReleaseStatus
import dev.pampa.mycloudgallery.core.security.lock.AppLockController
import dev.pampa.mycloudgallery.describe.DescribeWiring
import dev.pampa.mycloudgallery.describe.FacesDescribePeople
import dev.pampa.mycloudgallery.describe.IndexDescribeText
import dev.pampa.mycloudgallery.describe.SystemA11yActivity
import dev.pampa.mycloudgallery.faces.FacesGraph
import dev.pampa.mycloudgallery.faces.PeopleBridge
import dev.pampa.mycloudgallery.feature.settings.widget.WidgetSettings
import dev.pampa.mycloudgallery.gallery.GalleryGraph
import dev.pampa.mycloudgallery.memories.IndexMemoryPeople
import dev.pampa.mycloudgallery.memories.MemoriesGraph
import dev.pampa.mycloudgallery.release.ReleaseDiagnostics
import dev.pampa.mycloudgallery.release.ReleaseGraph
import dev.pampa.mycloudgallery.widget.WidgetDiagnostics
import dev.pampa.mycloudgallery.widget.WidgetGraph
import dev.pampa.mycloudgallery.widget.WidgetWiring
import dev.pampa.mycloudgallery.window.WindowDiagnostics
import dev.pampa.mycloudgallery.window.WindowRegistry
import javax.inject.Singleton

/**
 * Le parti della **Fase 10** legate in `:app` (docs/fase10-progetto.md §1.4, P13): il widget (`WidgetGraph`), il descrittore dei media (`MediaDescribers.install` con le porte
 * vere) e le voci della Diagnostica di widget, manifest e finestre. Il manifest (`ReleaseGraph`) nasce con `AppGraph` (`GraphModule`): la sessione e i cancelli lo leggono.
 * Nessun I/O nei costruttori: tutto si monta all'avvio del processo (`MyCloudGalleryApp`).
 */
@Module
@InstallIn(SingletonComponent::class)
object Phase10Module {

  @Provides
  @Singleton
  fun widgetGraph(
    @ApplicationContext context: Context,
    gallery: GalleryGraph,
    memories: MemoriesGraph,
    lock: AppLockController,
    settings: dagger.Lazy<WidgetSettings>,
    release: ReleaseGraph,
  ): WidgetGraph = WidgetWiring.build(context, gallery, memories, lock, { settings.get() }) { release.status.value is ReleaseStatus.Stopped }

  /**
   * Il descrittore del processo (P7, D22), legato in `MediaDescribers` qui: le persone dalle persone montate (la mappa da persone a celle si ricalcola solo quando cambiano
   * le persone o l'istantanea: `warm` riceve risposte da memoria), il testo letto con una lettura del catalogo per giro, lo strumento di accessibilita' dal sistema.
   */
  @Provides
  @Singleton
  fun mediaDescriber(@ApplicationContext context: Context, gallery: GalleryGraph, faces: FacesGraph, bridge: PeopleBridge, ai: AiStack): MediaDescriber {
    val people = IndexMemoryPeople({ faces.live.value?.people }, bridge) { faces.live.value?.people?.list?.value to gallery.visibleMedia.snapshot.value?.seq }
    return DescribeWiring.install(
      places = gallery.places,
      people = FacesDescribePeople(people, gallery.visibleMedia, gallery.hiddenMask),
      text = IndexDescribeText(ai.ai.textReadingHere) { ids -> ai.search.mediaText.texts(ids) },
      a11y = SystemA11yActivity(context),
    )
  }

  /** La sezione "Widget" della Diagnostica (§2.13): solo numeri e costanti. */
  @Provides
  @IntoSet
  fun widgetDiagnostics(widget: WidgetGraph): DiagnosticsContributor = WidgetDiagnostics(widget)

  /** La sezione "Versione e aggiornamenti" (§2.13): solo numeri e costanti, mai il contenuto del manifest. */
  @Provides
  @IntoSet
  fun releaseDiagnostics(release: ReleaseGraph): DiagnosticsContributor = ReleaseDiagnostics(release)

  /** `WINDOW_STATE` (§2.13): quante finestre e la classe di larghezza. */
  @Provides
  @IntoSet
  fun windowDiagnostics(@ApplicationContext context: Context, registry: WindowRegistry): DiagnosticsContributor = WindowDiagnostics(context.applicationContext, registry)
}
