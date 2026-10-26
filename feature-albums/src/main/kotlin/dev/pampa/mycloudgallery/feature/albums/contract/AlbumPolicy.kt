package dev.pampa.mycloudgallery.feature.albums.contract

import dev.pampa.mycloudgallery.core.albums.contract.Album
import dev.pampa.mycloudgallery.core.albums.contract.AlbumOp
import dev.pampa.mycloudgallery.core.albums.contract.SharedState
import dev.pampa.mycloudgallery.core.model.AlbumSort

// LE AZIONI CHE UN ALBUM OFFRE (docs/fase7-progetto.md §2.4, D10, D39; C25). Una funzione pura: dice, per un album e per le operazioni che la
// tabella dei permessi (`AlbumPermissions`, la stessa di chi legge) da' per permesse a **me**, quali azioni la schermata mostra. Chi scrive non
// offre l'azione che chi legge scarterebbe: un test generato dalla tabella (`AlbumPolicyTableTest`) lo prova per ogni riga e ogni livello.
//
// Le operazioni "sempre permesse a tutti" (vedere, scaricare, condividere verso altre app, preferito e nascosto, aggiungere a un proprio album
// personale) non passano da qui: sono azioni del viewer e della selezione, non dell'album.

/** Le azioni della pagina di un album (il menu ⋮ e la sua intestazione). */
enum class AlbumAction(
  /** L'operazione della tabella del §2.4 che la decide; null = non e' nella tabella (un album personale e' tutto mio). */
  val op: AlbumOp?,
) {
  ADD_MEDIA(AlbumOp.ADD_MEDIA),
  ADOPT_FOLDER_FILES(AlbumOp.ADOPT_FOLDER_FILES),
  RENAME(AlbumOp.RENAME),
  SET_COVER(AlbumOp.SET_COVER),
  SET_SORT(AlbumOp.SET_SORT),

  /** Il foglio "Permessi": c'e' sempre su un album condiviso (la dichiarazione "li fa rispettare l'app" va mostrata, D10, L57). */
  SHOW_PERMISSIONS(null),
  CHANGE_LEVEL(AlbumOp.CHANGE_LEVEL),
  STOP_SHARING(AlbumOp.STOP_SHARING),
  DELETE_ALBUM(AlbumOp.DELETE_ALBUM),

  /** "Non mostrare piu'" (E-SH21). */
  LEAVE(AlbumOp.LEAVE),

  /** "Sei il nuovo responsabile" (E-SH11): un admin, solo con il creatore uscito dalla famiglia. */
  INHERIT(AlbumOp.INHERIT),

  /** "Condividi...": un album personale (manuale o smart) diventa un album condiviso nuovo (D4, §2.1). */
  SHARE(null),
  EDIT_RULES(null),
  FREEZE(null),
  UNFREEZE(null),

  /** "Elimina l'album" personale: i media restano dove sono. */
  DELETE_PERSONAL(null),
}

/** Le azioni su **un elemento** di un album (la pressione prolungata su una cella, il menu del viewer). */
data class ItemActions(
  /** "Rimuovi dall'album" (D39: il file resta dov'e'); su uno smart un elemento automatico diventa un'esclusione ("Togli dall'album"). */
  val removeFromAlbum: Boolean,
  val setAsCover: Boolean,
  /** "Rimuovi dall'album e riporta nelle mie cartelle" (D31): solo un proprio elemento di un album condiviso attivo. */
  val bringBack: Boolean,
  /** "Porta in cima" e "Porta in fondo": l'ordine personalizzato dell'album (D21), solo se l'album e' ordinato a mano e la tabella lo permette. */
  val reorder: Boolean = false,
) {
  val any: Boolean get() = removeFromAlbum || setAsCover || bringBack || reorder

  companion object {
    val NONE = ItemActions(removeFromAlbum = false, setAsCover = false, bringBack = false)
  }
}

object AlbumPolicy {

  /**
   * Le azioni della pagina di [album], nell'ordine in cui il menu le mostra. [ops] sono le operazioni della tabella permesse a me su di lui
   * (`SharedAlbums.allowedOps`); per un album personale non contano. Un album condiviso che non e' (piu') attivo non offre niente.
   */
  fun pageActions(album: Album, ops: Set<AlbumOp>): List<AlbumAction> = when (album) {
    is Album.Manual -> PERSONAL
    is Album.Smart -> PERSONAL + smartOnly(album)
    is Album.Shared -> sharedActions(album, ops)
  }

  private val PERSONAL: List<AlbumAction> = listOf(
    AlbumAction.ADD_MEDIA, AlbumAction.RENAME, AlbumAction.SET_COVER, AlbumAction.SET_SORT, AlbumAction.SHARE, AlbumAction.DELETE_PERSONAL,
  )

  private fun smartOnly(album: Album.Smart): List<AlbumAction> =
    listOf(AlbumAction.EDIT_RULES, if (album.frozen) AlbumAction.UNFREEZE else AlbumAction.FREEZE)

  private val SHARED_ORDER: List<AlbumAction> = listOf(
    AlbumAction.ADD_MEDIA, AlbumAction.ADOPT_FOLDER_FILES, AlbumAction.RENAME, AlbumAction.SET_COVER, AlbumAction.SET_SORT,
    AlbumAction.SHOW_PERMISSIONS, AlbumAction.INHERIT, AlbumAction.STOP_SHARING, AlbumAction.DELETE_ALBUM, AlbumAction.LEAVE,
  )

  private fun sharedActions(album: Album.Shared, ops: Set<AlbumOp>): List<AlbumAction> {
    if (album.state != SharedState.ACTIVE) return emptyList()
    return SHARED_ORDER.filter { action ->
      val op = action.op
      op == null || op in ops
    }
  }

  /**
   * Le azioni su un elemento di [album]. [itemOps] sono le operazioni permesse a me **su quell'elemento** (`allowedOps(id, item)`: chi
   * l'ha aggiunto decide "Togli" e "Riporta"); [albumOps] quelle dell'album (copertina). Su un album personale tutto e' permesso, tranne
   * "Riporta", che e' dei soli condivisi.
   */
  fun itemActions(album: Album, albumOps: Set<AlbumOp>, itemOps: Set<AlbumOp>): ItemActions = when (album) {
    is Album.Manual, is Album.Smart -> ItemActions(removeFromAlbum = true, setAsCover = true, bringBack = false, reorder = album.sort == AlbumSort.CUSTOM)
    is Album.Shared ->
      if (album.state != SharedState.ACTIVE) {
        ItemActions.NONE
      } else {
        ItemActions(
          removeFromAlbum = AlbumOp.REMOVE_ITEM in itemOps,
          setAsCover = AlbumOp.SET_COVER in albumOps,
          bringBack = AlbumOp.BRING_BACK in itemOps,
          reorder = album.sort == AlbumSort.CUSTOM && AlbumOp.SET_SORT in albumOps,
        )
      }
  }

  /** Se la pagina offre un modo di aggiungere (il pulsante "Aggiungi foto" in testa a un album vuoto, lo stato vuoto). */
  fun canAdd(actions: List<AlbumAction>): Boolean = AlbumAction.ADD_MEDIA in actions
}
