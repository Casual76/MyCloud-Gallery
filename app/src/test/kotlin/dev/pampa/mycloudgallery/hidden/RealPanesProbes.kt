package dev.pampa.mycloudgallery.hidden

import dev.pampa.mycloudgallery.core.library.contract.LibraryFilter
import dev.pampa.mycloudgallery.core.library.index.FilteredTimelineView
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.feature.viewer.ViewerJumps

// Fase 10 (P5, D13, D41, E-H11): la SONDA dei pannelli sul codice VERO. Il viewer affiancato alla Libreria scorre **la vista viva della Libreria** (nessuna copia): un tocco sulla griglia a lato e' un `ViewerJump`,
// e la pagina a cui porta la decide `ViewerJumps.pageOf` sulla vista, che ha le sole celle della Libreria (niente nascosti, una cella per gruppo). La sonda prova, su ogni filtro della Libreria e su ogni
// media del mondo casuale, che:
//  - un salto arriva a una pagina **se e solo se** il media e' una cella della Libreria;
//  - la pagina e' proprio quella foto (mai un'altra);
//  - un nascosto (o il membro non copertina di un gruppo, o un membro di un gruppo nascosto) non ha una pagina, nemmeno cercandolo per id.
// La griglia della zona accanto alla mappa e' `ZoneGridController`, lo stesso della pagina singola: la prova resta la riga `map-zone`, che gira sullo stesso codice.

internal object RealPanesProbes {

  val docked = SurfaceProbe { w ->
    val snapshot = w.snapshot
    val problems = ArrayList<String>()
    val shown = LinkedHashSet<MediaId>()
    val cells = w.libraryCells
    for (filter in LibraryFilter.entries) {
      val view = FilteredTimelineView(snapshot, filter)
      for (id in w.ids) {
        val page = ViewerJumps.pageOf(view, id)
        if (page == null) continue
        shown += id
        if (id !in cells) problems += "un salto del viewer affiancato arriva a un media che non e' una cella della Libreria"
        if (id in w.mustNeverShow) problems += "un salto del viewer affiancato arriva a un nascosto"
        if (view.snapshot.idAt(view.snapshotIndex(page)) != id) problems += "la pagina del salto non e' la foto richiesta"
      }
    }
    // Senza filtro, ogni cella della Libreria si raggiunge (nessuna cella persa).
    val all = FilteredTimelineView(snapshot, LibraryFilter.ALL)
    for (id in cells) if (ViewerJumps.pageOf(all, id) == null) problems += "una cella della Libreria non si raggiunge dal viewer affiancato"
    Probed(shown = shown, expected = cells, problems = problems.distinct())
  }
}
