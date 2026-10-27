package dev.pampa.mycloudgallery.core.albums.share

import dev.pampa.mycloudgallery.core.albums.contract.AlbumOp
import dev.pampa.mycloudgallery.core.albums.contract.ClosePreview
import dev.pampa.mycloudgallery.core.albums.contract.MediaPlace
import dev.pampa.mycloudgallery.core.albums.contract.ShareItemState
import dev.pampa.mycloudgallery.core.albums.contract.SharedState
import dev.pampa.mycloudgallery.core.model.AlbumId
import dev.pampa.mycloudgallery.core.model.ContentKey
import dev.pampa.mycloudgallery.core.model.MediaId

// LE PROPOSTE DELLA CONDIVISIONE (docs/fase7-progetto.md D31, D39, §2.6; P4): cosa il foglio puo' offrire, calcolato solo dal catalogo locale, dai
// miei dati personali e dal diario. **Nessuna proposta fa partire niente**: un "Riporta" o un'adozione diventano un piano (`ShareRunner.plan`),
// un foglio con il conteggio e una conferma dell'utente, come ogni condivisione.

/**
 * I candidati di "Riporta nelle mie cartelle" (D31, §2.6) per il foglio di chiusura e per un singolo elemento: i miei media (aggiunti dal mio
 * account) che il mio catalogo vede nell'area comune **e** di cui ho la cartella d'origine (`shared_origin`, nei miei dati personali). Restringe
 * [ClosePreview.bringBackCandidates] di P2 (che conta gli elementi miei attestati, con o senza cartella d'origine).
 */
class BringBackCandidates(private val ports: ShareOrchestratorPorts) {

  /** I candidati fra [media] (gli elementi di [album] aggiunti da me, di solito). */
  suspend fun among(album: AlbumId, media: Collection<MediaId>): List<MediaId> {
    val me = ports.family.me() ?: return emptyList()
    val members = ports.shared.members(album)
    val facts = ports.facts.facts(media.distinct())
    return media.distinct().filter { id ->
      val f = facts[id] ?: return@filter false
      val addedBy = members[id]
      f.mine && f.place == MediaPlace.COMMON && f.nas != null && (addedBy == null || addedBy == me) && ports.origins.originOf(id) != null
    }
  }

  /** Il riepilogo del foglio di chiusura con i soli media che si possono davvero riportare (`bringBackHurtsCopies` segue da se'). */
  suspend fun restrict(preview: ClosePreview): ClosePreview = preview.copy(bringBackCandidates = among(preview.album, preview.bringBackCandidates))
}

/**
 * Revisione B, SH-3: cio' che un album condiviso ha gia' avuto, vivo o tolto: gli id con cui gli elementi sono stati pubblicati (anche quelli con la
 * lapide di "Rimuovi dall'album") e le loro chiavi di contenuto, da tutti i contributi che la replica ricorda e dal mio. Un file della cartella che
 * e' stato un elemento non e' "aggiunto dal PC" (D39: "Rimuovi dall'album" lascia il file nella cartella).
 */
data class AlbumHistory(val media: Set<MediaId>, val contentKeys: Set<ContentKey>) {
  override fun toString(): String = "AlbumHistory(${media.size}, ${contentKeys.size})"

  companion object {
    val NONE: AlbumHistory = AlbumHistory(emptySet(), emptySet())
  }
}

/**
 * D39: "N file nuovi nella cartella: aggiungili". Un file aggiunto **dal PC** nella cartella dell'album non entra da solo nell'album (l'appartenenza
 * sta nella definizione); l'album lo propone. Sono i media che il mio catalogo vede nella cartella dell'album e che non sono elementi, tolti i
 * nascosti (D22) e quelli che una mia condivisione sta ancora portando li' (la voce non e' ancora pubblicata: non sono "del PC"). Solo per un album
 * attivo in cui posso adottare (§2.4). L'adozione e' un piano `FolderAdoption` con il suo foglio e la sua conferma: nessuno spostamento.
 *
 * Revisione B, SH-3: tolti anche i file che sono stati elementi dell'album ([history]: tolti con "Rimuovi dall'album", D39), per id pubblicato o per
 * chiave di contenuto. Il numero lo calcola :app a ogni scansione (l'avviso `FilesAddedFromPc`), e la voce del menu c'e' solo con la lista non vuota.
 */
class FolderAdoptions(
  private val ports: ShareOrchestratorPorts,
  private val history: suspend (AlbumId) -> AlbumHistory = { AlbumHistory.NONE },
  /** I media visibili fra quelli dati (`VisibleMedia.visible`): i nascosti non si propongono. */
  private val visible: suspend (Collection<MediaId>) -> Set<MediaId>,
) {
  suspend fun pending(album: AlbumId): List<MediaId> {
    if (ports.shared.state(album) != SharedState.ACTIVE) return emptyList()
    if (!ports.allowed(album, AlbumOp.ADOPT_FOLDER_FILES, null)) return emptyList()
    val folder = ports.shared.folder(album) ?: return emptyList()
    val inFolder = ports.folderContents(folder) ?: return emptyList()
    if (inFolder.isEmpty()) return emptyList()
    val members = ports.shared.members(album).keys
    val inFlight = HashSet<MediaId>()
    for (op in ports.diary.openOps().filter { it.album == album }) {
      ports.diary.items(op.id).filter { !it.state.terminal || it.state == ShareItemState.DONE }.forEach { inFlight += it.media }
    }
    val past = history(album)
    val candidates = inFolder.filter { it !in members && it !in inFlight && it !in past.media }
    if (candidates.isEmpty()) return emptyList()
    val shown = visible(candidates)
    val facts = ports.facts.facts(candidates)
    return candidates.filter { id ->
      val f = facts[id]
      id in shown && f?.place == MediaPlace.COMMON && f.contentKey !in past.contentKeys
    }.sortedBy { it.value }
  }
}
