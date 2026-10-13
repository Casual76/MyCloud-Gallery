package dev.pampa.mycloudgallery.core.model

/**
 * Come si arriva ai **byte** di un media per mostrarlo (miniatura, viewer, condivisione): non e'
 * un'identita' (quella e' [MediaId]) ma l'indirizzo di una copia leggibile adesso.
 *
 * Sigillata perche' ogni sorgente ha il suo caricatore (`core-imaging`): il telefono legge dal
 * MediaStore, la libreria sintetica disegna gradienti deterministici. Dalla Fase 4 si aggiunge `Nas`.
 */
sealed interface MediaRef {

  /**
   * Una riga del MediaStore. **L'`_ID` vale solo con la `MediaStore.getVersion()` con cui e' stato
   * letto** (`docs/android.md`, scoperta 7): dopo una ricostruzione gli ID ripartono e possono essere
   * di altri file. Il catalogo da' un riferimento solo se la versione in cache e' quella attuale del
   * volume (`media_store_cursor.ms_version`): lo garantisce la sincronizzazione.
   */
  data class Phone(
    /** Il volume MediaStore: `external_primary` o l'ID di una microSD. */
    val volume: String,
    val mediaStoreId: Long,
    val mime: String?,
  ) : MediaRef {
    init {
      require(isValidMediaStoreVolume(volume)) { "Nome di volume MediaStore non valido." }
      require(mediaStoreId > 0) { "Un _ID MediaStore e' positivo." }
    }
  }

  /** Un elemento della libreria sintetica (test, budget, variante benchmark): pixel deterministici dal [seed]. */
  data class Synthetic(val seed: Long, val width: Int, val height: Int) : MediaRef {
    init {
      require(width > 0 && height > 0) { "Dimensioni non valide." }
    }
  }
}
