package dev.pampa.mycloudgallery.feature.library.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

/**
 * Da dove la Libreria prende il suo stato. `:app` lo costruisce dal grafo vero (P11): repository della timeline,
 * preferenze, azioni locali, miniature, stato del telefono e i comandi verso l'activity; in debug puo' offrire anche la
 * Libreria sintetica (con un database separato). Lo stato nasce nello scope del ViewModel, cosi' sopravvive alla
 * ricreazione dell'activity.
 */
fun interface LibraryStateSource {

  /** Lo stato della scheda, nello [scope] del ViewModel. Cambia quando, in debug, si sceglie un'altra Libreria. */
  fun open(scope: CoroutineScope): StateFlow<LibraryTabState>
}

/**
 * Il ViewModel della Libreria.
 *
 * **Tutta la logica sta in [LibraryTabState]** (griglia, filtro e stile ricordati, stato del telefono, azioni), provata
 * su JVM senza Hilt; [LibraryTab] la riceve gia' costruita. Le dipendenze vere arrivano da [LibraryStateSource], che lega
 * `:app` (P11). La posizione della griglia sopravvive anche alla morte del processo (`rememberSaveable` come elemento
 * ancorato, E-P18).
 */
@HiltViewModel
class LibraryViewModel @Inject constructor(source: LibraryStateSource) : ViewModel() {

  /** Lo stato della scheda Libreria. */
  val state: StateFlow<LibraryTabState> = source.open(viewModelScope)
}
