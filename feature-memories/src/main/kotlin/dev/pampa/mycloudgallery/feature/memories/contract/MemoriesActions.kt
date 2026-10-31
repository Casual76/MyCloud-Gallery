package dev.pampa.mycloudgallery.feature.memories.contract

import dev.pampa.mycloudgallery.core.library.contract.OrderedTimelineView
import dev.pampa.mycloudgallery.core.memories.contract.MemoryId
import dev.pampa.mycloudgallery.core.model.AlbumId
import dev.pampa.mycloudgallery.core.model.MediaId

/**
 * Cosa le schermate dei ricordi chiedono a :app (P13): la navigazione e le pagine di sistema. feature-memories non conosce il viewer, gli album,
 * le persone ne' le impostazioni di Android (D2): chiede e basta. Nessun parametro porta un nome, un titolo o un luogo.
 */
interface MemoriesActions {
  /** Il viewer della Libreria sulla vista [view] di un ricordo, da [start] (mai dai Nascosti: un ricordo non ha nascosti). */
  fun openViewer(view: OrderedTimelineView, start: MediaId)

  fun openMemory(id: MemoryId)

  fun openAllMemories()

  /** "Apri l'album" del viaggio (E-MEM13). */
  fun openAlbum(album: AlbumId)

  /** "Consenti" da un tocco, se Android lo puo' ancora chiedere (D18); altrimenti le impostazioni di sistema dell'app. */
  fun requestNotificationPermission()

  /** Il canale "Ricordi" nelle impostazioni di Android (E-NT2). */
  fun openNotificationChannel()

  /**
   * Il selettore di sistema per la canzone propria (Q3: `ACTION_OPEN_DOCUMENT` sui file audio, permesso persistito). [onFailed] parte se la scelta non si conserva
   * (il fornitore non concede il permesso persistente: MU-3, 31/10): la pagina lo dice invece di restare com'era senza una parola.
   */
  fun pickOwnSong(onFailed: () -> Unit)
}
