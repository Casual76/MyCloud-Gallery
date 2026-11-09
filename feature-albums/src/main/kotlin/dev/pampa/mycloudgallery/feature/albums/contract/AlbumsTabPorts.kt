package dev.pampa.mycloudgallery.feature.albums.contract

import dev.pampa.mycloudgallery.core.library.contract.LibraryFilter
import dev.pampa.mycloudgallery.core.library.contract.TimelineView
import dev.pampa.mycloudgallery.core.library.noop.EmptyTimelineView
import dev.pampa.mycloudgallery.core.model.AlbumId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

// LE PORTE DELLA SCHEDA ALBUM (fase grafica, G2b): la scheda in stile Galleria Samsung mette insieme gli album (core-albums) e le voci speciali, che sono
// raccolte-filtro della Libreria (feature-library): i feature non si conoscono, quindi la scheda vede le loro viste dell'indice da qui, e `:app` le lega.
// Opzionali per Dagger ([dev.pampa.mycloudgallery.feature.albums.tab.AlbumsTabPortsModule]): senza, nessuna voce speciale e nessuna "ultima apertura".

/**
 * Le voci speciali della scheda Album: ognuna e' una raccolta-filtro della Libreria e si apre nella sua griglia (`CollectionGridScreen`).
 * [code] e' il codice della raccolta (`CollectionKind.code` di feature-library): `:app` apre la rotta con quello. Messaggistica ha il codice di quando
 * era solo WhatsApp (`whatsapp`), per lo stato salvato.
 */
enum class SpecialAlbum(val code: String, val filter: LibraryFilter) {
  FAVORITES("favorites", LibraryFilter.FAVORITES),
  CAMERA("camera", LibraryFilter.CAMERA),
  VIDEOS("videos", LibraryFilter.VIDEOS),
  SCREENSHOTS("screenshots", LibraryFilter.SCREENSHOTS),
  MESSAGING("whatsapp", LibraryFilter.MESSAGING),
  ;

  companion object {
    fun fromCode(code: String?): SpecialAlbum? = entries.firstOrNull { it.code == code }
  }
}

/**
 * Le viste dell'indice delle voci speciali (mai i nascosti: vengono dall'indice della Libreria visibile, E-H1). `:app` le lega alle raccolte-filtro
 * (`CollectionItemsSource.items(filter).view`), la stessa vista che la griglia della raccolta e il suo viewer scorrono: il conto e la copertina della
 * scheda sono quelli della pagina che si apre.
 */
fun interface SpecialAlbums {
  fun view(kind: SpecialAlbum): StateFlow<TimelineView>
}

/** Nessuna voce speciale: la scheda mostra solo gli album (e i Preferiti vuoti, che spiegano come aggiungerne). */
object NoSpecialAlbums : SpecialAlbums {
  private val empty = MutableStateFlow<TimelineView>(EmptyTimelineView())

  override fun view(kind: SpecialAlbum): StateFlow<TimelineView> = empty
}

/**
 * L'**ultima apertura** delle voci della scheda Album (G2b): per ogni voce aperta un numero che cresce a ogni apertura (non un'ora: l'ordine e' tutto
 * cio' che serve, e nessun orologio del telefono entra nelle schermate). Dato **locale del profilo**: non si sincronizza (decisioni.md, "G2b").
 * Le chiavi sono quelle di [AlbumsTabKeys].
 */
interface AlbumOpenings {
  /**
   * Chiave -> numero dell'apertura; piu' alto = piu' recente. **Null finche' il file non e' letto** (pochi millisecondi dopo la prima richiesta):
   * la scheda intanto disegna i fissi e le copertine ferme, invece di un ordine che cambierebbe sotto gli occhi.
   */
  val order: StateFlow<Map<String, Long>?>

  /** La voce [key] e' stata aperta adesso. */
  suspend fun opened(key: String)
}

/** Le chiavi stabili delle voci della scheda: mai un titolo (un album rinominato resta lui). */
object AlbumsTabKeys {
  fun album(id: AlbumId): String = "album:" + id.value

  fun special(kind: SpecialAlbum): String = "special:" + kind.code
}

/** Nessuna memoria delle aperture: tutte le voci sono "mai aperte" e vanno per contenuto. */
object NoAlbumOpenings : AlbumOpenings {
  override val order: StateFlow<Map<String, Long>?> = MutableStateFlow(emptyMap())

  override suspend fun opened(key: String) = Unit
}

/** Le aperture in memoria: per i test, le anteprime e come base dell'implementazione su file di `:app`. Tiene al piu' [limit] voci (le piu' recenti). */
class InMemoryAlbumOpenings(initial: Map<String, Long> = emptyMap(), private val limit: Int = DEFAULT_LIMIT) : AlbumOpenings {
  private val state = MutableStateFlow<Map<String, Long>?>(initial)

  override val order: StateFlow<Map<String, Long>?> = state.asStateFlow()

  override suspend fun opened(key: String) {
    state.update { current -> next(current.orEmpty(), key, limit) }
  }

  companion object {
    /** Le voci ricordate: molte piu' di quelle che una scheda mostra, poche abbastanza da non pesare. */
    const val DEFAULT_LIMIT: Int = 400

    /** [current] con [key] aperta adesso: il numero dopo il piu' alto; oltre [limit] voci si dimenticano le piu' vecchie. */
    fun next(current: Map<String, Long>, key: String, limit: Int = DEFAULT_LIMIT): Map<String, Long> {
      val top = current.values.maxOrNull() ?: 0L
      val updated = current + (key to top + 1)
      if (updated.size <= limit) return updated
      return updated.entries.sortedByDescending { it.value }.take(limit).associate { it.key to it.value }
    }
  }
}
