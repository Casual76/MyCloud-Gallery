package dev.pampa.mycloudgallery.albums

import dev.pampa.mycloudgallery.core.albums.contract.AlbumOp
import dev.pampa.mycloudgallery.core.albums.contract.ShareFolder
import dev.pampa.mycloudgallery.core.albums.contract.ShareItemAction
import dev.pampa.mycloudgallery.core.albums.contract.SharedState
import dev.pampa.mycloudgallery.core.albums.shared.SharedAlbumView
import dev.pampa.mycloudgallery.core.model.AlbumId
import dev.pampa.mycloudgallery.core.model.Location
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.NasPath
import dev.pampa.mycloudgallery.core.nasops.contract.NasItem
import dev.pampa.mycloudgallery.core.nasops.contract.ProtectedFiles
import dev.pampa.mycloudgallery.core.trust.nas.SharePath

// LE RIGHE "ELIMINARE IL FILE" E "NUOVA VERSIONE" DELLA TABELLA DEI PERMESSI (Fase 7, revisione B, R3; docs/fase7-progetto.md §2.4, E-D16, D39, L57).
//
// Sono le due righe che per progetto controlla **solo chi scrive**: sul NAS non c'e' un permesso da leggere. Prima nessuno le applicava, e un familiare
// mandava nel cestino il file di un elemento altrui a ogni livello (dal viewer, da Cartelle NAS, con "Ovunque", con le versioni). Adesso:
// - **Eliminare il file** (e, per la stessa ragione, spostarlo o rinominarlo: il file uscirebbe dal suo posto): il piano di core-nasops, il punto in
//   comune a tutte quelle strade, chiede [AlbumFileGuard] per ogni file dell'area comune che l'utente vuole togliere dal suo posto; un elemento vivo di
//   un album attivo che la tabella non mi lascia eliminare (a "Gli altri aggiungono" il creatore e chi l'ha aggiunto, a "Gli altri guardano" solo il
//   creatore) resta fuori dal piano con il suo motivo. Il viewer non offre le voci su quel file (C25).
// - **Nuova versione**: nessuna strada dell'app scrive una versione nell'area comune (scelta del P14 della Fase 5B, `CorrectionReadiness.COMMON_AREA_NOTICE`):
//   la riga e' rispettata per costruzione, anche dove la tabella lo permetterebbe. Una modifica di un elemento nasce nell'area privata (E-E9).
//
// "Riporta nelle mie cartelle" e' un'altra riga (BRING_BACK) con il suo controllo, nel piano della condivisione: i file che una mia operazione di
// "Riporta" sta portando via (nel diario, confermata) non sono protetti qui. Dal PC nessuna protezione e' possibile (L57).

/** La decisione, pura: quali media del mio catalogo sono elementi protetti degli album di [views]. */
internal object AlbumFileRules {

  /**
   * I media del mio catalogo che attestano un elemento vivo di un album attivo e che la tabella non mi lascia eliminare ([allowedToDelete], la riga
   * `DELETE_FILE` per quell'elemento), tolti quelli che una mia operazione di "Riporta" sta portando via ([leaving]).
   */
  fun protectedMedia(views: Collection<SharedAlbumView>, leaving: Set<MediaId>, allowedToDelete: (AlbumId, MediaId) -> Boolean): Set<MediaId> {
    val out = HashSet<MediaId>()
    for (view in views) {
      if (view.merged.state != SharedState.ACTIVE) continue
      for (local in view.attested.values) {
        if (local in out || local in leaving) continue
        if (!allowedToDelete(view.merged.album, local)) out += local
      }
    }
    return out
  }
}

/**
 * [ProtectedFiles] sopra gli album di questo processo: la replica (gli elementi e chi li ha aggiunti), la tabella applicata a me
 * (`ReplicaSharedAlbums.allowed`), il catalogo locale (da un percorso dell'area comune al suo media; per una cartella, i media che contiene) e il diario
 * della condivisione (i "Riporta" in corso). Solo l'area comune: un file della mia area privata non e' mai un elemento di un album (D9).
 */
class AlbumFileGuard(private val graph: AlbumsGraph) : ProtectedFiles {

  override suspend fun protectedAmong(share: String, items: List<NasItem>): Set<SharePath> {
    if (items.isEmpty()) return emptySet()
    // Senza album montati (famiglia non pronta) non ci sono album condivisi visti da qui: niente da proteggere.
    val live = graph.ensureLive() ?: return emptySet()
    val shared = live.shared
    val family = shared.ports.family
    val common = family.commonShare() ?: return emptySet()
    if (!share.equals(common, ignoreCase = true)) return emptySet()
    // Un account che non si sa: non si puo' dire chi sono rispetto alla tabella; si fallisce chiusi.
    val me = family.me() ?: return items.mapTo(LinkedHashSet()) { it.path }
    val protectedMedia = AlbumFileRules.protectedMedia(shared.replica.views.value.values, leavingNow(shared)) { album, media ->
      shared.albums.allowed(album, AlbumOp.DELETE_FILE, media)
    }
    if (protectedMedia.isEmpty()) return emptySet()
    val out = LinkedHashSet<SharePath>()
    for (item in items) {
      val inside: Set<MediaId> = if (item.isFolder) {
        shared.folders.contents(ShareFolder(share, item.path))
      } else {
        val location = live.catalog.nasLocationAt(Location.Nas(me, share, NasPath.of(item.path.value)))
        setOfNotNull(location?.let { live.catalog.mediaIdOfLocation(it.locationId) })
      }
      if (inside.any { it in protectedMedia }) out += item.path
    }
    return out
  }

  /** I media che un mio "Riporta" confermato sta portando via dall'area comune (elementi non chiusi): li ha gia' giudicati la riga BRING_BACK. */
  private suspend fun leavingNow(shared: SharedLive): Set<MediaId> {
    val out = HashSet<MediaId>()
    for (op in shared.diary.openOps()) {
      shared.diary.items(op.id).filter { it.action == ShareItemAction.MOVE_OUT && !it.state.terminal }.mapTo(out) { it.media }
    }
    return out
  }
}
