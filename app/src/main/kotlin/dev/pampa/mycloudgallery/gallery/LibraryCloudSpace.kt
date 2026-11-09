package dev.pampa.mycloudgallery.gallery

import dev.pampa.mycloudgallery.core.nasops.contract.NasSpace
import dev.pampa.mycloudgallery.core.nasops.contract.NasSpaceState
import dev.pampa.mycloudgallery.feature.library.library.LibraryNasSpace

// B2 della beta: il pop-up della nuvola della Libreria mostra lo spazio usato sul totale del NAS, con **la stessa fonte della pagina
// "Spazio NAS"** (`NasSpace` di core-nasops, sull'area che quella pagina sceglie per prima: la privata). La Libreria non vede
// core-nasops: qui la misura diventa il suo dato. Si legge solo quando l'utente apre il pop-up, mai in background (come la pagina).

/**
 * Lo spazio per la nuvola: lo spazio del disco di [share] ([NasSpace.load]), usato = totale - libero. Null senza un'area (nessun NAS
 * collegato: la sezione non c'e'); [LibraryNasSpace.Unavailable] se il NAS non risponde o l'area non c'e' (la pagina lo dice).
 */
internal suspend fun libraryNasSpace(space: NasSpace?, share: String?): LibraryNasSpace? {
  if (space == null || share == null) return null
  return when (val state = space.load(share)) {
    is NasSpaceState.Ready -> {
      val details = state.details
      if (details.totalBytes <= 0L) {
        LibraryNasSpace.Unavailable
      } else {
        LibraryNasSpace.Ready(usedBytes = (details.totalBytes - details.freeBytes).coerceIn(0L, details.totalBytes), totalBytes = details.totalBytes)
      }
    }
    is NasSpaceState.Unavailable, NasSpaceState.Loading -> LibraryNasSpace.Unavailable
  }
}

/** Lo spazio della Libreria vera: l'organizzatore del NAS del grafo e la prima delle sue aree (la privata), come "Spazio NAS". */
internal suspend fun GalleryGraph.libraryNasSpace(): LibraryNasSpace? {
  val organizer = nas?.organizer ?: return null
  return libraryNasSpace(organizer.space, organizer.areas.areas.value.firstOrNull()?.share)
}
