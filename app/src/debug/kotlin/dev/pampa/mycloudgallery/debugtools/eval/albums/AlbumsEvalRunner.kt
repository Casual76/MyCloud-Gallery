package dev.pampa.mycloudgallery.debugtools.eval.albums

import dev.pampa.mycloudgallery.core.albums.contract.AlbumClocks
import dev.pampa.mycloudgallery.core.albums.contract.SmartOutcome
import dev.pampa.mycloudgallery.core.albums.contract.SmartPeriod
import dev.pampa.mycloudgallery.core.albums.contract.SmartRules
import dev.pampa.mycloudgallery.core.albums.smart.AutoRow
import dev.pampa.mycloudgallery.core.albums.smart.DeviceNormalizer
import dev.pampa.mycloudgallery.core.albums.smart.RuleSmartNameInterpreter
import dev.pampa.mycloudgallery.core.albums.smart.RulesSmartEngine
import dev.pampa.mycloudgallery.core.albums.smart.SmartAlbumState
import dev.pampa.mycloudgallery.core.albums.smart.SmartCandidates
import dev.pampa.mycloudgallery.core.albums.smart.SmartContentSearch
import dev.pampa.mycloudgallery.core.albums.smart.SmartRows
import dev.pampa.mycloudgallery.core.albums.smart.SmartRowsChange
import dev.pampa.mycloudgallery.core.albums.smart.SuggestionRow
import dev.pampa.mycloudgallery.core.library.contract.HiddenMask
import dev.pampa.mycloudgallery.core.library.contract.VisibleMedia
import dev.pampa.mycloudgallery.core.model.AlbumId
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.places.contract.ReverseGeocoder
import dev.pampa.mycloudgallery.core.search.contract.PlaceLookup
import dev.pampa.mycloudgallery.core.search.contract.QueryParser
import java.time.LocalDate
import kotlin.time.Duration

// LA CORSA DI UN VIAGGIO (docs/fase7-progetto.md §5.3; "Pronto per gli altri pacchetti" di P6): dal titolo che l'utente scrive, l'interprete
// (`RuleSmartNameInterpreter`) propone le regole, si crea un album smart **in memoria** ([EvalSmartRows]: mai `RoomSmartRows`, che scriverebbe nella
// Libreria vera) e il motore (`RulesSmartEngine`) lo valuta sui candidati veri; poi si leggono i sicuri e i suggeriti. Lo strumento non scrive mai
// nella Libreria. Le porte sono le stesse che P13 lega per gli album veri (catalogo, visibilita', luoghi, ricerca). LO STESSO FILE in
// `core-albums/src/test/.../eval/` (viaggi sintetici) e in `app/src/debug/.../debugtools/eval/albums/` (Libreria vera).

/** Tutto cio' che la corsa chiede a chi la lega. Nessuna porta scrive: i candidati si leggono, la visibilita' si interroga. */
class AlbumsEvalPorts(
  val candidates: SmartCandidates,
  val visible: VisibleMedia,
  val mask: HiddenMask,
  val parser: QueryParser,
  val lookup: PlaceLookup,
  val search: SmartContentSearch,
  val devices: DeviceNormalizer,
  val clocks: AlbumClocks,
  val geocoder: ReverseGeocoder?,
  val today: () -> LocalDate,
)

class AlbumsEvalRunner(private val ports: AlbumsEvalPorts) {

  /**
   * Crea in memoria l'album smart di [title] e lo valuta intero (senza budget: la corsa e' una misura, non un lavoro di sottofondo). Ogni corsa e'
   * un album nuovo con righe nuove: due corse di fila danno lo stesso risultato se la Libreria non cambia.
   */
  suspend fun run(tripId: String, title: String): TripRun {
    val interpreter = RuleSmartNameInterpreter(ports.parser, ports.visible, ports.mask, ports.candidates, ports.lookup, ports.search, ports.geocoder)
    val proposal = interpreter.propose(title, ports.today())
    val rows = EvalSmartRows()
    val album = AlbumId.random()
    rows.create(album, proposal.rules)
    val engine = RulesSmartEngine(rows, ports.candidates, ports.visible, ports.mask, ports.search, ports.devices, ports.lookup, ports.clocks)
    val outcome = engine.evaluate(album, Duration.INFINITE)
    check(outcome is SmartOutcome.Done) { "La corsa non e' arrivata in fondo." }
    return TripRun(
      tripId = tripId,
      sure = rows.sure(album).map { it.value },
      suggested = rows.suggested(album).map { it.value },
      series = (proposal.rules.period as? SmartPeriod.Ranges)?.ranges?.size ?: 0,
      placeRecognized = proposal.rules.place != null,
      alternatives = proposal.alternatives.size,
      searchUsed = ports.search.ready,
    )
  }
}

/**
 * Le righe degli album della corsa, **in memoria** (una ventina di righe per album): lo stesso comportamento di `RoomSmartRows` per il motore
 * (mai una riga dell'utente, le righe del motore sovrascritte), ma niente database. Nessun album esiste fuori da qui.
 */
class EvalSmartRows : SmartRows {
  private class Album(val rules: SmartRules) {
    val auto = LinkedHashMap<MediaId, AutoRow>()
    val suggestions = LinkedHashMap<MediaId, SuggestionRow>()
  }

  private val albums = LinkedHashMap<AlbumId, Album>()

  fun create(id: AlbumId, rules: SmartRules) {
    albums[id] = Album(rules)
  }

  override suspend fun smartAlbums(): List<AlbumId> = albums.keys.toList()

  override suspend fun state(id: AlbumId): SmartAlbumState? = albums[id]?.let {
    SmartAlbumState(id, it.rules, frozen = false, exclusions = emptySet(), owned = emptySet(), auto = LinkedHashMap(it.auto), suggestions = LinkedHashMap(it.suggestions))
  }

  override suspend fun apply(id: AlbumId, change: SmartRowsChange) {
    val album = albums[id] ?: return
    change.removeAuto.forEach { album.auto.remove(it) }
    album.auto.putAll(change.putAuto)
    change.removeSuggestions.forEach { album.suggestions.remove(it) }
    album.suggestions.putAll(change.putSuggestions)
  }

  /** I media che il motore ha inserito da soli, nell'ordine di scatto. */
  fun sure(id: AlbumId): List<MediaId> =
    albums[id]?.auto?.entries.orEmpty().sortedWith(compareBy({ it.value.position }, { it.key.value })).map { it.key }

  /** I media solo suggeriti, in ordine di id (il motivo non conta per la misura). */
  fun suggested(id: AlbumId): List<MediaId> = albums[id]?.suggestions?.keys.orEmpty().sortedBy { it.value }
}
