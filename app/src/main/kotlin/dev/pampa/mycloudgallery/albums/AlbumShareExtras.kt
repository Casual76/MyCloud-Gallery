package dev.pampa.mycloudgallery.albums

import dev.pampa.mycloudgallery.core.albums.contract.Album
import dev.pampa.mycloudgallery.core.albums.contract.AlbumContent
import dev.pampa.mycloudgallery.core.albums.contract.MediaPlace
import dev.pampa.mycloudgallery.core.albums.contract.ShareItemState
import dev.pampa.mycloudgallery.core.albums.contract.SharePlanKind
import dev.pampa.mycloudgallery.core.library.contract.OrderedTimelineView
import dev.pampa.mycloudgallery.core.library.index.IndexOrderedTimelineView
import dev.pampa.mycloudgallery.core.model.AlbumId
import dev.pampa.mycloudgallery.core.model.MediaId

/**
 * Cio' che la pagina di un album condiviso dice **solo su questo dispositivo** (Fase 7, revisione B; docs/fase7-progetto.md §2.5 "Stato visibile", D9,
 * D17, Q4). Prima il contratto li aveva (`AlbumContent.incoming`, `AlbumContent.toShare`) ma nessuno li calcolava: "In arrivo" e "Da condividere" non
 * comparivano mai.
 *
 * - **"In arrivo"** (SP-2, D9): i miei elementi ancora in viaggio verso l'area comune, dal diario locale della condivisione: le operazioni aperte di
 *   condivisione o di adozione di questo album, con gli elementi non ancora in coda al pubblicatore (`PLANNED`, `TRANSFERRING`, `IN_COMMON`). Da
 *   `ENTRY_STAGED` in poi l'elemento e' gia' nella vista locale dell'album. Gli altri familiari non lo vedono (il diario e' locale).
 * - **"Da condividere"** (SH-4, D17, Q4 (a)): per il creatore di un album condiviso nato da un suo **album smart**, i media che lo smart (le sue regole,
 *   personali) ha adesso e che l'album condiviso non ha: le foto nuove **non si spostano da sole**, si condividono con un tocco che apre il foglio col
 *   conteggio (`ShareRequest.AddToShared`). Fuori quelli gia' in viaggio e quelli nell'area privata di un altro account (non si possono aggiungere).
 *   I nascosti non ci sono mai: la vista dello smart non li ha (D22).
 */
internal object AlbumShareExtras {

  /** I passi di un elemento "in arrivo": il file non e' ancora un elemento visibile dell'album. */
  private val ARRIVING = setOf(ShareItemState.PLANNED, ShareItemState.TRANSFERRING, ShareItemState.IN_COMMON)

  /** [content] (di un album condiviso [id]) con "In arrivo" e, se [source] e' lo smart da cui l'ho condiviso, "Da condividere". */
  suspend fun of(live: AlbumsLive, id: AlbumId, content: AlbumContent, source: AlbumContent?): AlbumContent {
    val album = content.album as? Album.Shared ?: return content
    val diary = live.shared.diary
    var incoming = 0
    val inFlight = HashSet<MediaId>()
    for (op in diary.openOps()) {
      for (item in diary.items(op.id)) {
        if (item.state.terminal) continue
        inFlight += item.media
        if (op.album == id && op.kind != SharePlanKind.BRING_BACK && item.state in ARRIVING) incoming++
      }
    }
    val toShare = if (album.mine && source?.album is Album.Smart) toShareOf(live, id, content, source, inFlight) else null
    return content.copy(album = album.copy(incoming = incoming, toShare = toShare?.size ?: 0), incoming = incoming, toShare = toShare)
  }

  private suspend fun toShareOf(live: AlbumsLive, id: AlbumId, content: AlbumContent, source: AlbumContent, inFlight: Set<MediaId>): OrderedTimelineView? {
    val members = live.shared.albums.members(id).keys
    val candidates = idsOf(source.items).filter { it !in members && it !in inFlight }
    if (candidates.isEmpty()) return null
    val facts = live.shared.ports.facts.facts(candidates)
    val shareable = candidates.filter { media -> facts[media]?.place.let { it != null && it != MediaPlace.OTHERS_PRIVATE } }
    if (shareable.isEmpty()) return null
    return IndexOrderedTimelineView.of(content.items.snapshot, shareable)
  }
}
