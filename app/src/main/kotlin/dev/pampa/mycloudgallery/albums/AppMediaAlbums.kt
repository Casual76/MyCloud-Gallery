package dev.pampa.mycloudgallery.albums

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
 * Gli album in cui sta un media, per il pannello informazioni del viewer (H2, giro di correzione 1). [itemsOf] dice in quali album sta il media
 * (gli elementi degli album nel catalogo, letti una volta quando il pannello si apre su quel media); i nomi, l'ordine e chi si vede vengono
 * dall'elenco degli album ([Albums.all]: senza i condivisi lasciati), che li tiene aggiornati mentre il pannello resta aperto. Un errore di
 * lettura non apre niente: la sezione semplicemente non c'e'.
 */
class AppMediaAlbums(
  private val albums: () -> Albums,
  private val itemsOf: suspend (MediaId) -> Set<String>,
  private val opener: AlbumOpener,
) : ViewerMediaAlbums {

  override fun albumsOf(media: MediaId): Flow<List<ViewerAlbumEntry>> = flow {
    val inAlbums = try {
      itemsOf(media)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      emptySet()
    }
    if (inAlbums.isEmpty()) {
      emit(emptyList())
    } else {
      emitAll(
        albums().all.map { list ->
          list.albums.filter { it.id.value in inAlbums }.map { ViewerAlbumEntry(key = it.id.value, title = it.title, count = it.count) }
        },
      )
    }
  }

  override fun open(album: ViewerAlbumEntry) {
    val id = runCatching { AlbumId(album.key) }.getOrNull() ?: return
    opener.open(id)
  }
}

/** Dove porta il tocco su un album dal viewer: lo scrive la navigazione (chiude il viewer e apre la pagina dell'album). */
class AlbumOpener {
  @Volatile var onOpen: (AlbumId) -> Unit = {}

  fun open(id: AlbumId) = onOpen(id)
}
