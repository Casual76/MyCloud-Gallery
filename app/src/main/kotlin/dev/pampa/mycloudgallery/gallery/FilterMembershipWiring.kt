package dev.pampa.mycloudgallery.gallery

import dev.pampa.mycloudgallery.backup.BackupGraph
import dev.pampa.mycloudgallery.core.database.GalleryDatabase
import dev.pampa.mycloudgallery.core.library.contract.TimelineRepository
import dev.pampa.mycloudgallery.core.library.membership.CatalogFilterMembership
import dev.pampa.mycloudgallery.core.library.membership.MembershipTimeline
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

// FASE GRAFICA (G2b): Fotocamera e Messaggistica nell'indice della galleria. La cella dice la sorgente (`CAMERA`, `WHATSAPP`); il resto (un media
// `camera` che non viene da questo dispositivo, Telegram e Signal) lo legge `CatalogFilterMembership` dal catalogo, fuori dal thread principale e
// solo quando qualcuno guarda quelle viste. La cartella del backup di questo dispositivo arriva dalle impostazioni del backup quando sono lette
// (AS-9: si segue il flusso, non si legge una volta al montaggio).

/** Il repository della timeline con le correzioni del catalogo: sotto l'accesso parziale, sopra l'indice. */
internal fun filterMembershipTimeline(
  repository: TimelineRepository,
  database: GalleryDatabase,
  backup: BackupGraph?,
  scope: CoroutineScope,
): TimelineRepository {
  val folder: Flow<String?> = backup?.settings?.deviceFolder?.map { it?.name } ?: flowOf(null)
  return MembershipTimeline(repository, CatalogFilterMembership(database, repository.snapshot, folder), scope)
}
