package dev.pampa.mycloudgallery.core.library.contract

import android.content.ClipData
import android.view.View

// LA PORTA DEL TRASCINAMENTO (Fase 10, P0; docs/fase10-progetto.md §1.1 "I feature-* restano ciechi", §1.2 "feature-integrations/.../drag/",
// §2.5, D20). La implementa `DocumentDragPayloads` di feature-integrations (P6), la usano le griglie e il viewer (Libreria, album, ricerca,
// ricordi, griglia della zona) con `Modifier.mediaDragSource` di core-designsystem; null = niente trascinamento. Qui c'e' il `ClipData` della
// piattaforma perche' il gesto lo consegna ad Android cosi' com'e': i tipi puri stanno in `Drag.kt`.

/** Prepara un trascinamento **prima** che il dito superi la soglia (D20): tetti, nascosti esclusi, URI del provider dei documenti con id opachi. */
fun interface MediaDragHandler {
  suspend fun prepare(request: DragRequest): DragPrepared
}

/** Il prestito delle copie temporanee di un trascinamento (con "Rimuovi la posizione"): si rilascia a fine gesto o all'annullamento. */
fun interface DragLease {
  fun release()
}

/** L'esito della preparazione. */
sealed interface DragPrepared {
  /**
   * Pronto: [clip] gli URI del **provider dei documenti** (mai un `file://`, mai un `FileProvider` nuovo), [flags] esattamente
   * [DragFlags.ALLOWED] (sola lettura sul singolo URI, mai scrittura, mai permessi persistenti), [count] gli elementi, [lease] le copie.
   */
  class Ready(val clip: ClipData, val flags: Int, val count: Int, val lease: DragLease) : DragPrepared {
    init {
      require(DragFlags.isAllowed(flags)) { "Permessi del trascinamento non ammessi." }
      require(count in 1..DragLimits.MAX_ITEMS) { "Numero di elementi fuori dai tetti." }
    }

    override fun toString(): String = "DragPrepared.Ready($count)"
  }

  data class Refused(val reason: DragRefusal) : DragPrepared
}

/** I flag del trascinamento verso un'altra app (D20, C436). */
object DragFlags {
  /** `DRAG_FLAG_GLOBAL | DRAG_FLAG_GLOBAL_URI_READ`: l'app che riceve legge con il permesso sul singolo URI (`CallerGuard`). */
  const val ALLOWED: Int = View.DRAG_FLAG_GLOBAL or View.DRAG_FLAG_GLOBAL_URI_READ

  /** Scrittura, permessi persistenti o per prefisso: mai. */
  const val FORBIDDEN: Int =
    View.DRAG_FLAG_GLOBAL_URI_WRITE or View.DRAG_FLAG_GLOBAL_PERSISTABLE_URI_PERMISSION or View.DRAG_FLAG_GLOBAL_PREFIX_URI_PERMISSION

  fun isAllowed(flags: Int): Boolean = flags == ALLOWED && flags and FORBIDDEN == 0
}
