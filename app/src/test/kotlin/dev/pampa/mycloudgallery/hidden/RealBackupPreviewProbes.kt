package dev.pampa.mycloudgallery.hidden

import dev.pampa.mycloudgallery.ai.VisibilitySignal
import dev.pampa.mycloudgallery.backup.AppBackupPreviewAccess
import dev.pampa.mycloudgallery.core.library.visibility.CatalogHiddenMask
import dev.pampa.mycloudgallery.core.library.visibility.CatalogVisibleMedia
import kotlinx.coroutines.flow.MutableStateFlow

internal object RealBackupPreviewProbes {
  val backup = SurfaceProbe { world ->
    val access = AppBackupPreviewAccess(CatalogVisibleMedia(world.db, MutableStateFlow(world.snapshot)), CatalogHiddenMask(world.db), VisibilitySignal.None)
    access.open().use {
      access.approve("probe", world.ids.toSet())
      Probed(shown = world.ids.filterTo(LinkedHashSet()) { access.allows(it) }, expected = world.liveShowable)
    }
  }
}
