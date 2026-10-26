package dev.pampa.mycloudgallery.hidden

import dev.pampa.mycloudgallery.core.albums.contract.Album
import dev.pampa.mycloudgallery.core.albums.contract.CloseMode
import dev.pampa.mycloudgallery.core.albums.contract.ClosePreviewOutcome
import dev.pampa.mycloudgallery.core.albums.contract.CoverRef
import dev.pampa.mycloudgallery.core.albums.share.BringBackCandidates
import dev.pampa.mycloudgallery.core.albums.share.ClosedAlbumCopies
import dev.pampa.mycloudgallery.core.albums.share.FolderAdoptions
import dev.pampa.mycloudgallery.core.albums.share.RevokedItemsRestore
import dev.pampa.mycloudgallery.core.model.AlbumId
import dev.pampa.mycloudgallery.core.model.MediaId

// Fase 7 (P9, D22, E-H5, E-H6; integrazione 26/10): le SONDE sul codice VERO di P2 (album condivisi: replica, fusione, attestazione e
// `ReplicaSharedAlbums`) e di P4 (le proposte della condivisione che mostrano o scelgono media), sul catalogo vero del mondo casuale. Prendono il posto
// delle sonde di riferimento (`ReferenceProbes.sharedAlbums`): quando il codice vero e' arrivato la stessa proprieta' gira su di lui.
//
// Cio' che le superfici dicono si confronta con l'oracolo ricavato dal mondo: un album mostra **esattamente** i suoi elementi che il mio catalogo
// attesta nell'area comune (D9) e che non sono nascosti (D22) ne' membri di un gruppo con un nascosto (E-L11), contati e con la copertina giusta
// (E-H6); chi non ha nascosto niente li vede tutti (E-H4, E-H5).

internal object RealSharedProbes {

  private fun ids(world: BuiltWorld, indices: Collection<Int>): Set<MediaId> = indices.mapTo(LinkedHashSet()) { world.ids[it] }

  private fun album(list: List<Album.Shared>, id: AlbumId): Album.Shared? = list.firstOrNull { it.id == id }

  /** Gli album condivisi veri: elenco, conteggi, copertine, elementi, album lasciati, copie personali e foglio di chiusura. */
  val sharedAlbums = SurfaceProbe { w ->
    val stack = RealSharedStack.build(w)
    val shared = stack.shared
    val problems = ArrayList<String>()
    val shown = LinkedHashSet<MediaId>()
    val showable = w.liveShowable
    fun expected(members: List<Int>): Set<MediaId> = ids(w, members).filterTo(LinkedHashSet()) { it in showable }

    for ((name, seeded) in listOf("di un altro familiare" to stack.familyAlbum, "mio" to stack.mine)) {
      val listed = album(shared.visible.value, seeded.id)
      if (listed == null) {
        problems += "l'album $name non e' fra quelli visibili"
        continue
      }
      val want = expected(seeded.members)
      val items = shared.visibleItems(seeded.id)
      shown += items
      if (items.toSet() != want || items.size != want.size) problems += "l'album $name non mostra esattamente i suoi elementi visibili"
      if (listed.count != want.size) problems += "il conteggio dell'album $name e' ${listed.count}, ne attendeva ${want.size}"
      listed.cover.media?.let { shown += it }
      if (want.isEmpty() && listed.cover != CoverRef.None) problems += "l'album $name senza elementi visibili ha una copertina"
      if (want.isNotEmpty() && listed.cover.media !in want) problems += "la copertina dell'album $name non e' un suo elemento visibile"
    }

    // E-H6: la copertina scelta da un altro dispositivo e' il primo elemento; se per me e' nascosto la copertina diventa automatica, mai la scelta.
    val chosen = stack.common.firstOrNull()?.let { w.ids[it] }
    val cover = album(shared.visible.value, stack.familyAlbum.id)?.cover
    if (chosen != null && cover != null) {
      if (chosen in w.liveMustNeverShow && cover.media == chosen) problems += "la copertina scelta e' un mio nascosto e resta la copertina"
      if (chosen in showable && cover != CoverRef.Chosen(chosen)) problems += "la copertina scelta e' visibile ma non e' quella dell'album"
    }

    // Gli album che ho lasciato (E-SH21) e le copie personali di un album chiuso con "ognuno tiene una copia" (Q5): stessi elementi visibili.
    val left = album(shared.left.value, stack.left.id)
    if (left == null) problems += "l'album lasciato non e' fra i lasciati"
    if (left != null) {
      if (left.count != expected(stack.left.members).size) problems += "il conteggio dell'album lasciato non esclude i nascosti"
      left.cover.media?.let { shown += it }
    }
    val copies = shared.keptCopies()
    shown += copies.flatMap { it.items }
    val closedCopy = copies.singleOrNull { it.shared == stack.closed.id }
    if (closedCopy == null) problems += "la copia personale dell'album chiuso non c'e'"
    if (closedCopy != null && closedCopy.items.toSet() != expected(stack.closed.members)) problems += "la copia personale non ha esattamente gli elementi visibili"

    // Il foglio di chiusura (D31): i miei elementi che si possono riportare sono quelli che vedo, mai un mio nascosto.
    when (val preview = shared.closePreview(stack.mine.id, CloseMode.STOP)) {
      is ClosePreviewOutcome.Ready -> {
        shown += preview.preview.bringBackCandidates
        if (preview.preview.bringBackCandidates.toSet() != expected(stack.mineAddedByMe)) problems += "il foglio di chiusura non propone esattamente i miei elementi visibili"
      }
      else -> problems += "il foglio di chiusura dell'album mio non si apre"
    }

    // E-H5: per un altro familiare, che non ha nascosto niente, gli album contano tutto (nascondere vale solo per me).
    val another = stack.viewOfAnother()
    val theirs = album(another.visible.value, stack.familyAlbum.id)
    if (theirs == null || theirs.count != stack.common.size) problems += "per gli altri l'album di famiglia non conta tutti i suoi elementi"
    Probed(shown = shown, within = showable, problems = problems)
  }

  /** "File nuovi nella cartella dell'album" (D39): i media che il catalogo vede nella cartella e non sono elementi, mai un nascosto. */
  val shareAdoptions = SurfaceProbe { w ->
    val stack = RealSharedStack.build(w)
    val adoptions = FolderAdoptions(stack.ports()) { candidates -> stack.visibility.visible(candidates) }
    val pending = adoptions.pending(stack.mine.id)
    Probed(shown = pending.toSet(), expected = ids(w, stack.notYetInMine).filterTo(LinkedHashSet()) { it in w.liveShowable })
  }

  /** "Riporta nelle mie cartelle" dal foglio di chiusura (D31): il riepilogo di P2 ristretto da P4 ai media che si possono davvero riportare. */
  val shareBringBack = SurfaceProbe { w ->
    val stack = RealSharedStack.build(w)
    val preview = (stack.shared.closePreview(stack.mine.id, CloseMode.STOP) as? ClosePreviewOutcome.Ready)?.preview
    val problems = ArrayList<String>()
    if (preview == null) problems += "il foglio di chiusura dell'album mio non si apre"
    val candidates = preview?.let { BringBackCandidates(stack.ports()).restrict(it).bringBackCandidates }.orEmpty()
    Probed(shown = candidates.toSet(), expected = ids(w, stack.mineAddedByMe).filterTo(LinkedHashSet()) { it in w.liveShowable }, problems = problems)
  }

  /** Le copie personali degli album chiusi (Q5): quello che `ClosedAlbumCopies` scrive nei dati personali. */
  val closedCopies = SurfaceProbe { w ->
    val stack = RealSharedStack.build(w)
    val store = MemoryKeptCopyStore()
    ClosedAlbumCopies({ stack.shared.keptCopies() }, store).sync()
    Probed(shown = store.items.values.flatten().toSet(), expected = ids(w, stack.closed.members).filterTo(LinkedHashSet()) { it in w.liveShowable })
  }

  /** "Ripristina gli elementi di un dispositivo revocato" (E-SH20): quanti elementi dice di poter ripristinare, senza contare i nascosti. */
  val revokedRestore = SurfaceProbe { w ->
    val stack = RealSharedStack.build(w)
    val restore = RevokedItemsRestore(stack.identity, stack.albumFamily, stack.store, stack.replica, stack.publisher, stack.gate, stack.clocks, stack.visibility) { album, op, media ->
      stack.shared.allowed(album, op, media)
    }
    val missing = stack.common.filter { it !in stack.mine.members }
    val want = ids(w, missing).count { it in w.liveShowable }
    val said = restore.candidates()[stack.mine.id] ?: 0
    val problems = ArrayList<String>()
    if (said != want) problems += "il ripristino dice $said elementi, ne attendeva $want (visibili)"
    Probed(shown = emptySet(), problems = problems)
  }
}
