package dev.pampa.mycloudgallery.albums

import dev.pampa.mycloudgallery.core.albums.contract.AlbumWriteBlock
import dev.pampa.mycloudgallery.core.albums.contract.EditOutcome
import dev.pampa.mycloudgallery.core.albums.contract.SharedAlbumChange
import dev.pampa.mycloudgallery.core.albums.contract.SharedAlbumLimits
import dev.pampa.mycloudgallery.core.albums.contract.SharedAlbumNotice
import dev.pampa.mycloudgallery.core.trust.data.AlbumTexts
import dev.pampa.mycloudgallery.core.albums.contract.SharedState
import dev.pampa.mycloudgallery.core.albums.share.RestoreOutcome
import dev.pampa.mycloudgallery.core.albums.shared.SharedAlbumView
import dev.pampa.mycloudgallery.core.model.AccountId
import dev.pampa.mycloudgallery.core.model.AlbumId
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import java.util.Locale

// E-SH16 ED E-SH20 IN :app (Fase 7, P13; contratto `SharedAlbums.useNewFolder`/`restoreRevoked`, avvisi `FolderChanged`/`RevokedItems`).
//
// **E-SH16.** Un familiare rinomina o sposta dal PC la cartella di un album condiviso: gli elementi si ritrovano da soli (per chiave di contenuto, D9),
// e il **creatore** (o l'erede) vede "La cartella e' cambiata: usa «…»". La cartella nuova la dice il catalogo di chi legge: nessun elemento attestato sta
// piu' nella cartella dichiarata, e la maggioranza di quelli presenti nell'area comune sta in una stessa cartella (mai dentro l'area dell'app). Adottarla
// e' una voce `definition` con la cartella nuova (stesso titolo, creatore e nonce): **nessun file si muove**, l'ha gia' spostato il PC.
//
// **E-SH20.** Gli elementi di un mio dispositivo revocato (`RevokedItemsRestore.candidates`, P4) diventano un avviso sugli album che vedo, con
// "Ripristina gli elementi". Mai titoli o cartelle nei log.

/** Una cartella nuova trovata per [album] (E-SH16). [folder] e' relativa all'area comune. */
data class FolderMove(val album: AlbumId, val title: String, val folder: SharePath) {
  override fun toString(): String = "FolderMove"
}

internal object AlbumFolderWatch {

  /** La prima parte dell'area dell'app nell'area comune: una cartella li' dentro non e' mai la casa di un album. */
  private const val APP_AREA = ".mycloudgallery"

  /**
   * Le cartelle nuove degli album di cui sono il creatore o l'erede. [foldersOf]: le cartelle (relative alla share) dei file presenti dei media nella
   * share comune (`CatalogCommonFolders.foldersOf`).
   */
  suspend fun scan(
    views: Collection<SharedAlbumView>,
    me: AccountId?,
    foldersOf: suspend (Collection<MediaId>) -> Map<MediaId, Set<SharePath>>,
  ): Map<AlbumId, FolderMove> {
    if (me == null) return emptyMap()
    val out = LinkedHashMap<AlbumId, FolderMove>()
    for (view in views) {
      val merged = view.merged
      if (merged.state != SharedState.ACTIVE || (merged.creator != me && merged.heir != me)) continue
      // Revisione B, SN-1: contano solo gli elementi che stavano nella cartella dell'album quando sono stati aggiunti (spostati o caricati li': il
      // percorso della voce). Un riferimento (E-SH11, un file gia' nell'area comune altrove) non dice niente della cartella: un album fatto solo di
      // riferimenti proponeva, per esempio, la cartella delle vacanze, e adottarla cambiava dove andavano le aggiunte dopo.
      val placed = merged.items.values.filter { item -> item.path.parent()?.let { within(it, merged.folder) } == true }
      val attested = placed.mapNotNullTo(HashSet()) { view.attested[it.media] }
      if (attested.isEmpty()) continue
      val present = foldersOf(attested).filterValues { it.isNotEmpty() }
      if (present.isEmpty()) continue
      if (present.values.any { folders -> folders.any { within(it, merged.folder) } }) continue
      val counts = HashMap<String, Pair<SharePath, Int>>()
      for (folders in present.values) {
        for (folder in folders.distinctBy { key(it) }) counts.merge(key(folder), folder to 1) { a, _ -> a.first to a.second + 1 }
      }
      val (best, n) = counts.values.maxByOrNull { it.second } ?: continue
      // La maggioranza dei media presenti: elementi sparsi (cartella cancellata, file rimessi a mano qua e la') non fanno una cartella nuova.
      if (n * 2 <= present.size) continue
      if (best.components.firstOrNull()?.equals(APP_AREA, ignoreCase = true) == true) continue
      // Revisione B, R15: solo una cartella che il formato degli album accetta (percorso di media, al piu' 1.024 byte, mai l'area dell'app): i percorsi
      // del catalogo arrivano a 4.096 byte, e il pubblicatore ne rifiuterebbe una piu' lunga (prima: l'app si chiudeva al tocco su "Usa la nuova cartella").
      if (!acceptedFolder(best)) continue
      out[merged.album] = FolderMove(merged.album, merged.title, best)
    }
    return out
  }

  /** La chiave dell'avviso come la scrive "Non mostrare piu'" (`ReplicaSharedAlbums.dismissNotice`): album e impronta della cartella nuova. */
  fun folderKey(move: FolderMove): String = "folder:${move.album.value}:${Integer.toHexString(move.folder.value.hashCode())}"

  fun revokedKey(album: AlbumId, count: Int): String = "revoked:${album.value}:$count"

  /** L'area comune e' una share SMB: i nomi non distinguono le maiuscole. */
  private fun key(path: SharePath): String = path.value.lowercase(Locale.ROOT)

  /** Revisione B, R15: [folder] sta in una voce `definition` come la legge chi legge (`AlbumTexts.mediaPath`, tetto del formato). */
  fun acceptedFolder(folder: SharePath): Boolean =
    folder.value.toByteArray(Charsets.UTF_8).size <= SharedAlbumLimits.MAX_PATH_BYTES && runCatching { AlbumTexts.mediaPath(folder.value) }.isSuccess

  private fun within(path: SharePath, folder: SharePath): Boolean {
    val p = key(path)
    val f = key(folder)
    return p == f || p.startsWith("$f/")
  }

  /** E-SH16: "Usa la nuova cartella" sugli album di adesso; [move] e' l'ultima scansione. Solo il creatore o l'erede, dietro i cancelli delle scritture. */
  suspend fun useNewFolder(live: AlbumsLive, move: FolderMove?, id: AlbumId): EditOutcome {
    val view = live.shared.replica.view(id) ?: return EditOutcome.NotFound
    val merged = view.merged
    if (merged.state != SharedState.ACTIVE) return EditOutcome.NotFound
    val me = live.shared.ports.family.me()
    if (me == null || (merged.creator != me && merged.heir != me)) return EditOutcome.NotAllowed(null)
    val target = move?.takeIf { it.album == id } ?: return EditOutcome.Invalid(null)
    if (!acceptedFolder(target.folder)) return EditOutcome.Invalid(null)
    blocked(live)?.let { return EditOutcome.Blocked(it) }
    live.shared.publisher.stage(id, listOf(SharedAlbumChange.Definition(merged.title, target.folder, SharedState.ACTIVE, merged.creator, merged.nonceHex, null)))
    live.shared.replica.recompute(listOf(id))
    return EditOutcome.Done
  }

  /** E-SH20: "Ripristina gli elementi" di un mio dispositivo revocato (P4, `RevokedItemsRestore.restore`), tradotto negli esiti del contratto. */
  suspend fun restoreRevoked(live: AlbumsLive, id: AlbumId): EditOutcome = when (val out = live.shared.revoked.restore(id)) {
    is RestoreOutcome.Restored -> EditOutcome.Done
    RestoreOutcome.Nothing -> EditOutcome.NotFound
    RestoreOutcome.NotAllowed -> EditOutcome.NotAllowed(null)
    is RestoreOutcome.Blocked -> EditOutcome.Blocked(out.reason)
  }

  /** Come le altre scritture degli album condivisi: area comune, cancello (D30, D38) e ora del NAS. */
  private suspend fun blocked(live: AlbumsLive): AlbumWriteBlock? {
    val ports = live.shared.ports
    val share = ports.family.commonShare() ?: return AlbumWriteBlock.NO_NAS
    ports.gate.blockFor(share)?.let { return it }
    return if (ports.clocks.nasNowMs() == null) AlbumWriteBlock.NO_NAS else null
  }

  /** La chiave dell'avviso dei file aggiunti dal PC come la scrive "Non mostrare piu'" (`ReplicaSharedAlbums.dismissNotice`). */
  fun pcFilesKey(album: AlbumId, count: Int): String = "pcfiles:${album.value}:$count"

  /**
   * Gli avvisi di :app (E-SH16, E-SH20 e, revisione B, SH-3: "N file nuovi nella cartella: aggiungili", D39) per gli album che vedo, tolti quelli che
   * l'utente ha chiuso ("Non mostrare piu'").
   */
  fun notices(
    moves: Map<AlbumId, FolderMove>,
    revoked: Map<AlbumId, Int>,
    titles: Map<AlbumId, String>,
    dismissed: Set<String>,
    pcFiles: Map<AlbumId, Int> = emptyMap(),
  ): List<SharedAlbumNotice> = buildList {
    for ((album, count) in pcFiles) {
      val title = titles[album] ?: continue
      if (count > 0 && pcFilesKey(album, count) !in dismissed) add(SharedAlbumNotice.FilesAddedFromPc(album, title, count))
    }
    for (move in moves.values) {
      if (move.album in titles && folderKey(move) !in dismissed) add(SharedAlbumNotice.FolderChanged(move.album, move.title, move.folder.value))
    }
    for ((album, count) in revoked) {
      val title = titles[album] ?: continue
      if (count > 0 && revokedKey(album, count) !in dismissed) add(SharedAlbumNotice.RevokedItems(album, title, count))
    }
  }
}
