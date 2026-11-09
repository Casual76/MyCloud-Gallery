package dev.pampa.mycloudgallery.core.library.contract

import androidx.compose.runtime.Immutable
import dev.pampa.mycloudgallery.core.model.MediaId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

// Fase grafica (G2b): quello che la cella dell'indice non sa dire di un filtro. La cella ha tipo, sorgente e flag; "scattata con questo dispositivo"
// (Fotocamera) e "arrivata da Telegram o Signal" (Messaggistica) dipendono invece dalle posizioni del catalogo (cartelle, dispositivo d'origine), che
// l'indice non porta. Si legge dal catalogo fuori dal thread principale e si applica sopra la vista filtrata (`AdjustedTimelineView`).

/**
 * Le correzioni di un filtro: [plus] le identita' da aggiungere alla vista anche se la cella non passa il filtro, [minus] quelle da togliere anche
 * se lo passa. Le identita' valgono solo se sono nell'indice: un nascosto non c'e' mai (E-H1), quindi non entra nemmeno da qui.
 */
@Immutable
class FilterExtras(val plus: Set<MediaId> = emptySet(), val minus: Set<MediaId> = emptySet()) {

  val isEmpty: Boolean get() = plus.isEmpty() && minus.isEmpty()

  override fun equals(other: Any?): Boolean = other is FilterExtras && other.plus == plus && other.minus == minus

  override fun hashCode(): Int = 31 * plus.hashCode() + minus.hashCode()

  // Gli id sono dati dell'utente: nei log solo i numeri.
  override fun toString(): String = "FilterExtras(plus=${plus.size}, minus=${minus.size})"

  companion object {
    val NONE: FilterExtras = FilterExtras()
  }
}

/**
 * Le correzioni dei filtri che la cella non basta a decidere ([LibraryFilter.needsMembership]). Per gli altri filtri e' sempre [FilterExtras.NONE].
 * Il flusso parte vuoto e si aggiorna quando il catalogo cambia: la vista si mostra subito con quello che la cella sa, e si completa dopo.
 */
fun interface FilterMembership {
  fun extras(filter: LibraryFilter): Flow<FilterExtras>
}

/** Nessuna correzione: i filtri valgono come li dice la cella (i test, le librerie senza catalogo). */
object NoFilterMembership : FilterMembership {
  override fun extras(filter: LibraryFilter): Flow<FilterExtras> = flowOf(FilterExtras.NONE)
}
