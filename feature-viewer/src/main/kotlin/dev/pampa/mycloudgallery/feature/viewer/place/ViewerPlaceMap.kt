package dev.pampa.mycloudgallery.feature.viewer.place

import android.graphics.Bitmap
import dev.pampa.mycloudgallery.core.model.MediaId
import kotlinx.coroutines.flow.Flow

// LA MAPPA NEL VIEWER (docs/fase9-progetto.md §1.2 "feature-viewer", §1.5 `MiniMapState`, §2.10 "Mini-mappa del viewer", D29, D30, E-MAP16,
// E-MAP19, E-H9). Porta e dati di P0; la riga "Mostra sulla mappa" e la mini-mappa nelle info (`InfoBuilder`, solo P4) e l'aspetto (G3)
// vengono dopo. Il viewer **non vede core-map ne' MapLibre** (Phase9ArchitectureTest): la porta la definisce lui e la lega :app sopra
// feature-map (P13). Senza porta ([ViewerServices.placeMap] null) non c'e' ne' la riga ne' la mini-mappa.
//
// Due regole che valgono per chi la lega e per chi la usa:
//  - **mai per un media aperto dai Nascosti** (`ViewerRequest.secure`): il viewer non chiama la porta, la riga non c'e' (E-H9, D30);
//  - **la mini-mappa non va mai in rete**: e' disegnata con lo stile solo offline (carta di base e regioni scaricate), anche con la mappa
//    online accesa (D29, E-MAP16). "Mostra sulla mappa" apre la mappa vera, con le sue regole (E-MAP19).

/** La posizione del media aperto, per le info (§1.5 `MiniMapState`). */
sealed interface ViewerMapPlace {
  /** Si sta leggendo la posizione o disegnando la mini-mappa. */
  data object Loading : ViewerMapPlace

  /** Nessuna posizione (o coordinate scartate, E-MAP7): nessuna riga "Mostra sulla mappa", nessuna mini-mappa. */
  data object NoPosition : ViewerMapPlace

  /**
   * Il media ha una posizione (quella effettiva: la correzione se c'e', D27, E-MAP8). [miniMap] l'immagine statica gia' disegnata (null =
   * non disponibile, per esempio la carta di base non ancora pronta: resta la riga "Mostra sulla mappa"). Nel `toString()` niente.
   */
  class Located(val miniMap: Bitmap?) : ViewerMapPlace {
    override fun toString(): String = "ViewerMapPlace.Located(${miniMap != null})"
  }
}

/**
 * La mappa del media aperto (D30), legata da :app sopra feature-map (P4, P13). [place] emette lo stato della posizione e della mini-mappa
 * di [media] alla larghezza [sizePx]; [show] apre la mappa vera centrata sul media ("Mostra sulla mappa": la navigazione la fa :app).
 */
interface ViewerPlaceMap {
  fun place(media: MediaId, sizePx: Int): Flow<ViewerMapPlace>

  fun show(media: MediaId)
}
