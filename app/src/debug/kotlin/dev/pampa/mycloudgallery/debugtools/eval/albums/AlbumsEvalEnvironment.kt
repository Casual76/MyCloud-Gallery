package dev.pampa.mycloudgallery.debugtools.eval.albums

import androidx.compose.ui.graphics.ImageBitmap
import dev.pampa.mycloudgallery.core.model.MediaId
import java.time.LocalDate

/**
 * SOLO debug (Fase 7, P12): tutto cio' che lo strumento "Valutazione degli album" chiede all'app, in una porta sola (come `EvalEnvironment` della ricerca,
 * Fase 6). La lega [AppAlbumsEvalEnvironment] sopra la Libreria vera (`GalleryGraph`): i candidati del catalogo, la visibilita' dei nascosti, i luoghi,
 * la ricerca per contenuto. **Sola lettura**: lo strumento non scrive mai nella Libreria, ne' nel NAS, ne' nei dati personali.
 *
 * Mai rete, mai NAS: solo il catalogo locale e le miniature locali.
 */
interface AlbumsEvalEnvironment {
  /** Le porte della corsa di un album (candidati, visibilita', luoghi, ricerca, orologi). Si costruiscono alla prima richiesta: il catalogo aspetta la famiglia pronta. */
  suspend fun ports(): AlbumsEvalPorts

  /**
   * Le celle visibili della Libreria nei giorni locali [from]..[to] (estremi compresi), nell'ordine di scatto: **una per gruppo, mai un nascosto**
   * (passano da `VisibleMedia` e dal filtro finale `HiddenMask`). E' la prima fase del metodo a pozzo: tutto cio' che c'e' in quei giorni, senza guardare l'album.
   */
  suspend fun cellsOfDays(from: LocalDate, to: LocalDate): List<AlbumsEvalCell>

  /** Le celle visibili con questi id (con il loro giorno): per i sicuri e i suggeriti di un album. Un id nascosto o sconosciuto manca dal risultato. */
  suspend fun cellsOf(ids: Collection<String>): List<AlbumsEvalCell>

  /** La miniatura **locale** del media (mai dal NAS), o null se non c'e': la schermata mostra un riquadro con il giorno. */
  suspend fun thumbnail(id: MediaId): ImageBitmap?

  /**
   * Le proposte di viaggio e di evento di `CatalogTrips` sulla Libreria vera (F89). La rilevazione scrive la casa e le proposte in un **database di lavoro
   * in memoria**, non in quello della Libreria: lo strumento resta di sola lettura. Senza i viaggi collegati risponde [TripsView.UNAVAILABLE].
   */
  suspend fun tripProposals(): TripsView

  /** I media del catalogo per nome di file (`display_name`): per la prova generale sui viaggi sintetici. Un nome sconosciuto manca dalla mappa. */
  suspend fun mediaByFileName(names: Set<String>): Map<String, MediaId>

  /** Il giorno di oggi (porta `Today` della ricerca): il titolo "Oslo 2026" non dipende dall'orologio del telefono. */
  val today: LocalDate
}

/** Una cella della Libreria che si puo' etichettare: il suo media e il giorno locale. Mai un nome di file, mai il luogo. */
class AlbumsEvalCell(val id: String, val day: LocalDate) {
  override fun toString(): String = "AlbumsEvalCell"
}

/** Le proposte di `CatalogTrips` come le vede il confronto: solo i giorni e il tipo (mai luoghi ne' coordinate) e se una casa e' stata stimata. */
class TripsView(val proposals: List<ProposedDays>, val homeKnown: Boolean, val available: Boolean = true) {
  override fun toString(): String = "TripsView(${proposals.size})"

  companion object {
    val UNAVAILABLE = TripsView(emptyList(), homeKnown = false, available = false)
  }
}

/** Il punto in cui `installSearch` (debug) mette la porta quando l'app parte. Fino ad allora e' vuoto. */
object AlbumsEvalEnvironmentHolder {
  @Volatile var current: AlbumsEvalEnvironment? = null
}
