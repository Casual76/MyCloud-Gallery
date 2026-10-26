package dev.pampa.mycloudgallery.feature.viewer.menu

import android.content.res.Resources
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddPhotoAlternate
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.RemoveCircleOutline
import dev.pampa.mycloudgallery.core.designsystem.chrome.GalleryMenuEntry
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.feature.viewer.R

// Le tre voci del menu ⋮ del viewer per gli album (Fase 7, P10, docs/fase7-progetto.md §1.3 "viewer", D21, D35, D39, E-D16): "Aggiungi ad album",
// "Imposta come copertina" e "Rimuovi dall'album". Come "Trova simili" (Fase 6) e le voci del backup (5B): il viewer **non vede core-albums** (i feature
// non si conoscono) ne' la tabella dei permessi. Chiede a questa porta, che `:app` lega alle schermate degli album, cosa offrire per il media di adesso:
// i tre booleani li decide la tabella (`AlbumPolicy.itemActions` di feature-albums, la stessa di chi legge) e arrivano gia' decisi. Una voce che non si
// puo' eseguire non compare (D13 della Fase 3), e il viewer non scrive mai niente: ogni voce e' una richiesta a `:app`.
//
// "Rimuovi dall'album" toglie la voce, non il file (D39): "Elimina" e' un'altra voce, e a un elemento di un album condiviso aggiunto da un altro familiare,
// a "Gli altri aggiungono", la voce non c'e' (E-D16).

/** Che esito ha avuto una voce che scrive: il viewer lo dice con un avviso breve. */
enum class AlbumMenuResult { DONE, NOT_ALLOWED, FAILED }

/** Le voci che il menu offre per un media: le decide la tabella dei permessi, il viewer le disegna. */
data class ViewerAlbumMenu(
  /** "Aggiungi ad album": apre la scelta dell'album sopra il viewer (un album condiviso chiede sempre la conferma col conteggio, D12). */
  val addToAlbum: Boolean,
  /** "Rimuovi dall'album": solo se il viewer e' aperto da un album e la tabella lo permette per questo elemento. */
  val removeFromAlbum: Boolean,
  /** "Imposta come copertina": solo se il viewer e' aperto da un album e la tabella lo permette. */
  val setAsCover: Boolean,
  /** In un album smart un elemento automatico non si "rimuove", si "toglie": diventa un'esclusione che dura per sempre (E-AL5). */
  val removeIsExclude: Boolean = false,
) {
  val any: Boolean get() = addToAlbum || removeFromAlbum || setAsCover

  companion object {
    val NONE = ViewerAlbumMenu(addToAlbum = false, removeFromAlbum = false, setAsCover = false)
  }
}

/**
 * Cosa `:app` sa fare per gli album dal viewer. [menu] risponde da dati gia' pronti (la chiama il menu a ogni composizione: nessun lavoro lungo);
 * le altre voci sono richieste dopo un tocco dell'utente.
 */
interface ViewerAlbumActions {
  fun menu(id: MediaId): ViewerAlbumMenu

  /** Apre la scelta dell'album per [id] sopra il viewer (se il viewer e' aperto dai Nascosti, la richiesta lo dice: li' l'album riceve il media nascosto). */
  fun addToAlbum(id: MediaId)

  suspend fun removeFromAlbum(id: MediaId): AlbumMenuResult

  suspend fun setAsCover(id: MediaId): AlbumMenuResult
}

/** Nessun album: nessuna voce. */
object NoViewerAlbumActions : ViewerAlbumActions {
  override fun menu(id: MediaId): ViewerAlbumMenu = ViewerAlbumMenu.NONE

  override fun addToAlbum(id: MediaId) = Unit

  override suspend fun removeFromAlbum(id: MediaId): AlbumMenuResult = AlbumMenuResult.FAILED

  override suspend fun setAsCover(id: MediaId): AlbumMenuResult = AlbumMenuResult.FAILED
}

/** Le voci del menu ⋮ per [menu], nell'ordine: aggiungere, copertina, rimuovere. Nessuna e' distruttiva (il file resta dov'e'). */
internal fun albumMenuEntries(
  resources: Resources,
  menu: ViewerAlbumMenu,
  onAdd: () -> Unit,
  onCover: () -> Unit,
  onRemove: () -> Unit,
): List<GalleryMenuEntry> = buildList {
  if (menu.addToAlbum) add(GalleryMenuEntry.Item(resources.getString(R.string.viewer_album_add), onAdd, Icons.Rounded.AddPhotoAlternate))
  if (menu.setAsCover) add(GalleryMenuEntry.Item(resources.getString(R.string.viewer_album_cover), onCover, Icons.Rounded.Image))
  if (menu.removeFromAlbum) {
    add(GalleryMenuEntry.Item(resources.getString(if (menu.removeIsExclude) R.string.viewer_album_exclude else R.string.viewer_album_remove), onRemove, Icons.Rounded.RemoveCircleOutline))
  }
}
