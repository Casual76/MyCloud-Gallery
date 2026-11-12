package dev.pampa.mycloudgallery.nas

import dev.pampa.mycloudgallery.albums.LibraryAlbumRefs
import dev.pampa.mycloudgallery.core.imaging.contract.ThumbnailLoader
import dev.pampa.mycloudgallery.core.library.contract.TimelineSnapshot
import dev.pampa.mycloudgallery.feature.nas.contract.NasThumbnails
import kotlinx.coroutines.flow.StateFlow

/**
 * I4 (fase I): le miniature delle anteprime di Cartelle NAS. La stessa cache delle griglie ([loader]) e la stessa porta della Libreria **visibile** degli
 * Album: un media che l'istantanea visibile di adesso non contiene (Nascosti, aree che l'utente non vede, eliminati) non ha richiesta, e la riga resta
 * con la sua piastrella. CG-2: la pagina osserva [snapshots] (la Libreria visibile che cambia) e rifa' le richieste a ogni istantanea nuova.
 */
internal fun nasLibraryThumbnails(loader: ThumbnailLoader?, snapshots: StateFlow<TimelineSnapshot?>): NasThumbnails {
  val refs = LibraryAlbumRefs { snapshots.value }
  return NasThumbnails(loader, visible = snapshots) { media -> refs.request(media) }
}
