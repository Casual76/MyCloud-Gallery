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

  /**
   * Un file del NAS (Fase 4, `docs/fase4-progetto.md` §1.2, D18): firma fissata da P0, schema e riempimento di P2/P7.
   *
   * - [locationId] e' la riga della posizione nella replica locale (`LocationId.value`): il percorso (share, cartella,
   *   nome) **non** sta qui, si risolve dal database solo quando serve aprire l'originale (viewer, miniatura
   *   interattiva). Cosi' una cella dell'istantanea `MCGT2` resta di pochi byte e un percorso del NAS, che e' un dato
   *   sensibile come il GPS, non gira per la UI.
   * - [contentKey] e' la chiave di contenuto ridotta a 64 bit ([ContentKey.short]): con questa le miniature si trovano
   *   nella replica (`ReplicaBlobs.locate`) anche dopo una rinomina fatta dal PC (D4).
   */
  data class Nas(
    val locationId: Long,
    val contentKey: Long,
    val mime: String?,
  ) : MediaRef {
    init {
      require(locationId > 0) { "L'identificativo di una posizione e' positivo." }
    }
  }
}
