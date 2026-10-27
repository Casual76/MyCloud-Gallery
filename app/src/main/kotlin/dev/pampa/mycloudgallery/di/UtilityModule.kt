package dev.pampa.mycloudgallery.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.pampa.mycloudgallery.albums.AlbumsGraph
import dev.pampa.mycloudgallery.core.library.duplicates.IdenticalDuplicates
import dev.pampa.mycloudgallery.core.library.duplicates.SimilarShots
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.personal.contract.FullHashPersonalSettings
import dev.pampa.mycloudgallery.core.personal.settings.SyncedFullHashSettings
import dev.pampa.mycloudgallery.feature.library.trash.NasTrashPort
import dev.pampa.mycloudgallery.feature.library.trash.NoNasTrash
import dev.pampa.mycloudgallery.feature.library.utility.CleanupNasPort
import dev.pampa.mycloudgallery.feature.library.utility.FullHashSwitch
import dev.pampa.mycloudgallery.feature.library.utility.NoCleanupNas
import dev.pampa.mycloudgallery.feature.library.utility.NoFullHashSwitch
import dev.pampa.mycloudgallery.gallery.GalleryGraph
import dev.pampa.mycloudgallery.graph.AppGraph
import dev.pampa.mycloudgallery.utility.AppAlbumMembership
import dev.pampa.mycloudgallery.utility.OperationsCleanupNas
import dev.pampa.mycloudgallery.utility.OperationsNasTrash
import dev.pampa.mycloudgallery.utility.PersonalFullHashSwitch
import dev.pampa.mycloudgallery.utility.UtilityGraph
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** L'impostazione personale `full_hash_nightly` (core-personal), null finche' i dati personali non ci sono: la usano l'interruttore e la notte. */
class FullHashSettingsSource(val settings: StateFlow<FullHashPersonalSettings?>)

/**
 * Le porte dell'Utility e degli Eliminati di recente (Fase 7, P8) legate in `:app` (P13): una per porta, tutte opzionali in feature-library
 * (`OptionalUtilityBindingsModule`). Senza NAS i null-object di feature-library (nessun cestino del NAS, nessuna pulizia sul NAS, nessun
 * interruttore): le pagine del telefono funzionano lo stesso. `DuplicatesNames` resta senza legame: il registro non dice di chi e' una share
 * privata di un altro account, e la pagina dice "un altro familiare" (E-U1).
 */
@Module
@InstallIn(SingletonComponent::class)
object UtilityModule {

  @Provides
  @Singleton
  fun fullHashSettings(gallery: GalleryGraph, graph: AppGraph): FullHashSettingsSource {
    val parts = gallery.nas?.organizer?.partsState ?: return FullHashSettingsSource(MutableStateFlow(null))
    val initial = parts.value?.personalStore?.let { SyncedFullHashSettings(it, graph.scope) }
    return FullHashSettingsSource(
      parts.map { p -> p?.personalStore?.let { SyncedFullHashSettings(it, graph.scope) } }.stateIn(graph.scope, SharingStarted.Eagerly, initial),
    )
  }

  @Provides
  @Singleton
  fun utilityGraph(gallery: GalleryGraph, albums: AlbumsGraph, source: FullHashSettingsSource): UtilityGraph =
    UtilityGraph(
      catalog = { gallery.coreIfReady()?.catalog },
      visible = gallery.visibleMedia,
      hidden = gallery.hiddenMask,
      albums = AppAlbumMembership(gallery.database) { sharedMembers(albums) },
      signatures = { gallery.similarCopies?.signatureSource() },
      fullHashOn = { source.settings.value?.fullHashNightly?.value == true },
    )

  /** I media del mio catalogo che attestano un elemento vivo di un album condiviso (E-U3). */
  private fun sharedMembers(albums: AlbumsGraph): Set<MediaId> {
    val views = albums.live.value?.shared?.replica?.views?.value ?: return emptySet()
    return views.values.flatMapTo(HashSet()) { it.attested.values }
  }

  @Provides fun identicalDuplicates(utility: UtilityGraph): IdenticalDuplicates = utility.identicalDuplicates

  @Provides fun similarShots(utility: UtilityGraph): SimilarShots = utility.similarShots

  @Provides
  @Singleton
  fun nasTrashPort(gallery: GalleryGraph, graph: AppGraph): NasTrashPort {
    val organizer = gallery.nas?.organizer ?: return NoNasTrash
    return OperationsNasTrash(organizer.trash, organizer.operations, organizer.clocks::monotonicMs, graph.scope)
  }

  @Provides
  @Singleton
  fun cleanupNasPort(gallery: GalleryGraph): CleanupNasPort {
    val organizer = gallery.nas?.organizer ?: return NoCleanupNas
    return OperationsCleanupNas(organizer.operations, organizer.clocks::monotonicMs)
  }

  @Provides
  @Singleton
  fun fullHashSwitch(
    gallery: GalleryGraph,
    graph: AppGraph,
    utility: UtilityGraph,
    source: FullHashSettingsSource,
  ): FullHashSwitch {
    if (gallery.nas == null) return NoFullHashSwitch
    return PersonalFullHashSwitch(source.settings, graph.scope) { on -> gallery.runIfActive { utility.applyFullHash(on) } }
  }
}
