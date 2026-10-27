package dev.pampa.mycloudgallery.core.albums.smart

import dev.pampa.mycloudgallery.core.library.contract.HiddenMask
import dev.pampa.mycloudgallery.core.library.contract.TimelineSnapshot
import dev.pampa.mycloudgallery.core.library.contract.VisibleMedia
import dev.pampa.mycloudgallery.core.model.MediaId
import kotlinx.coroutines.flow.StateFlow

/**
 * Un mondo la cui lettura puo' guastarsi (R17): come il catalogo vero con un guasto del database, `visible` e `hiddenAmong` falliscono **chiusi**
 * ("niente e' visibile", "tutto e' nascosto") e le versioni `OrThrow` lanciano. Chi riscrive uno stato (il motore degli smart) deve usare le seconde.
 */
class FlakyWorld(val world: MemoryWorld) : VisibleMedia, HiddenMask {
  @Volatile var failing = false

  override val snapshot: StateFlow<TimelineSnapshot?> get() = world.snapshot

  override suspend fun visible(ids: Collection<MediaId>): Set<MediaId> = if (failing) emptySet() else world.visible(ids)

  override suspend fun visibleOrThrow(ids: Collection<MediaId>): Set<MediaId> {
    check(!failing) { "database non risponde" }
    return world.visible(ids)
  }

  override suspend fun hiddenAmong(ids: Collection<MediaId>): Set<MediaId> = if (failing) ids.toSet() else world.hiddenAmong(ids)

  override suspend fun hiddenAmongOrThrow(ids: Collection<MediaId>): Set<MediaId> {
    check(!failing) { "database non risponde" }
    return world.hiddenAmong(ids)
  }
}
