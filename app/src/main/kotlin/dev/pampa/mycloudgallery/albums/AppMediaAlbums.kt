package dev.pampa.mycloudgallery.albums

import dev.pampa.mycloudgallery.core.albums.contract.Album
import dev.pampa.mycloudgallery.core.albums.contract.Albums
import dev.pampa.mycloudgallery.core.model.AlbumId
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.feature.viewer.info.ViewerAlbumEntry
import dev.pampa.mycloudgallery.feature.viewer.info.ViewerMediaAlbums
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map

/**
 * Gli album in cui sta un media, per il pannello informazioni del viewer (H2, giro di correzione 1). [itemsOf] dice in quali album personali e
 * smart sta il media (gli elementi degli album nel catalogo); [sharedItemsOf] da' gli elementi visibili di un album condiviso della famiglia
 * (giro 2: gli elementi pubblicati, attestati sui media di questo telefono). Tutti e due si leggono una volta quando il pannello si apre su quel
 * media; i nomi, l'ordine e chi si vede vengono dall'elenco degli album ([Albums.all]: senza i condivisi lasciati), che li tiene aggiornati
 * mentre il pannello resta aperto. Un errore di lettura non apre niente: quell'album (o la sezione) semplicemente non c'e'.
 */
class AppMediaAlbums(
  private val albums: () -> Albums,
  private val itemsOf: suspend (MediaId) -> Set<String>,
  private val opener: AlbumOpener,
  private val sharedItemsOf: suspend (AlbumId) -> Collection<MediaId> = { emptyList() },
) : ViewerMediaAlbums {

  override fun albumsOf(media: MediaId): Flow<List<ViewerAlbumEntry>> = flow {
    val inAlbums = quietly(emptySet()) { itemsOf(media) }
    val source = albums()
    // Gli album condivisi che ci sono adesso: per ognuno, se il media e' fra i suoi elementi visibili (pochi album, una lettura a testa).
    val inShared = source.all.value.albums.filterIsInstance<Album.Shared>().filter { album -> quietly(false) { media in sharedItemsOf(album.id) } }
      .mapTo(HashSet()) { it.id.value }
    if (inAlbums.isEmpty() && inShared.isEmpty()) {
      emit(emptyList())
    } else {
      emitAll(
        source.all.map { list ->
          list.albums.filter { it.id.value in inAlbums || it.id.value in inShared }
            .map { ViewerAlbumEntry(key = it.id.value, title = it.title, count = it.count, shared = it is Album.Shared) }
        },
      )
    }
  }

  /** [block], o [fallback] se la lettura non riesce (la cancellazione passa). */
  private suspend fun <T> quietly(fallback: T, block: suspend () -> T): T = try {
    block()
  } catch (e: CancellationException) {
    throw e
  } catch (e: Exception) {
    fallback
  }

  override fun open(album: ViewerAlbumEntry) {
    val id = runCatching { AlbumId(album.key) }.getOrNull() ?: return
    opener.open(id)
  }
}

/** Dove porta il tocco su un album dal viewer: lo scrive la navigazione (chiude il viewer e apre la pagina dell'album). */
class AlbumOpener {
  private class Binding(val onOpen: (AlbumId) -> Unit, val onTop: () -> AlbumId?)

  @Volatile private var binding: Binding? = null

  /**
   * La navigazione si registra: [onOpen] apre la pagina dell'album, [onTop] dice quale album e' gia' in cima (CS-22). CS-24: le due lambda tengono
   * la navigazione (e con lei l'attivita'), e questo oggetto vive quanto il processo: chi si e' registrato chiude cio' che riceve quando se ne va.
   * Chiudere una registrazione vecchia non toglie quella nuova.
   */
  fun bind(onOpen: (AlbumId) -> Unit, onTop: () -> AlbumId? = { null }): AutoCloseable {
    val mine = Binding(onOpen, onTop)
    binding = mine
    return AutoCloseable { if (binding === mine) binding = null }
  }

  /** Apre l'album [id], se non e' gia' la pagina in cima: toccare la capsula dell'album da cui si guarda non ne apre una seconda copia. */
  fun open(id: AlbumId) {
    val current = binding ?: return
    if (current.onTop() == id) return
    current.onOpen(id)
  }
}
