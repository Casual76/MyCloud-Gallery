package dev.pampa.mycloudgallery.feature.albums.list

import dev.pampa.mycloudgallery.core.common.files.QuietWrites
import dev.pampa.mycloudgallery.core.albums.contract.Album
import dev.pampa.mycloudgallery.core.albums.contract.AlbumListSort
import dev.pampa.mycloudgallery.core.albums.contract.Albums
import dev.pampa.mycloudgallery.core.albums.contract.AlbumsStatus
import dev.pampa.mycloudgallery.core.albums.contract.SharedAlbumNotice
import dev.pampa.mycloudgallery.core.albums.contract.SharedAlbums
import dev.pampa.mycloudgallery.core.albums.contract.TitleProblem
import dev.pampa.mycloudgallery.core.model.AlbumId
import dev.pampa.mycloudgallery.feature.albums.contract.AlbumListState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// L'ELENCO DEGLI ALBUM (Fase 7, P10; docs/fase7-progetto.md §1.5 `AlbumListState`, §2.7, D21, D33, E-SH21): Raccolte -> Album e "Tutti gli
// album". Non sa niente di Compose. Legge `Albums.all` (i miei, gli smart e i condivisi insieme, senza quelli lasciati), gli avvisi una volta
// sola e gli album che non si mostrano piu'; scrive solo l'ordine dell'elenco, la creazione e gli avvisi letti. **Nessuna azione sui file.**

/** Perche' una creazione non e' andata a buon fine: la schermata lo dice sotto il campo del titolo. */
sealed interface CreateAlbumResult {
  data class Created(val id: AlbumId) : CreateAlbumResult

  data class InvalidTitle(val problem: TitleProblem) : CreateAlbumResult

  /** Senza famiglia pronta non si deriva l'id: "Non posso creare album adesso". */
  data object Unavailable : CreateAlbumResult

  /** K3b (SOL-10): il registro non si e' potuto scrivere (memoria piena): niente e' stato creato, il foglio resta aperto col nome e si puo' riprovare. */
  data object NotSaved : CreateAlbumResult

  /** K3b (SOL-11): un'altra creazione e' ancora in corso: questo invio non ha chiesto niente (chi chiama non fa nulla). */
  data object Busy : CreateAlbumResult
}

/** Una mossa di un album nell'ordine personalizzato dell'elenco (Sposta su / Sposta giu': anche le azioni di TalkBack). */
enum class ListMove { UP, DOWN }

/** Cosa l'elenco sa dire in piu' dello stato del §1.5: gli album che non si mostrano piu' (E-SH21) e se si puo' riordinare. */
data class AlbumListUi(
  val state: AlbumListState,
  /** Gli album condivisi che ho lasciato: si ritrovano da "Album nascosti dalle Raccolte" (E-SH21). */
  val left: List<Album.Shared> = emptyList(),
  /** Vero con l'ordine "Personalizzato": ogni riga offre "Sposta su" e "Sposta giu'". */
  val reorderable: Boolean = false,
)

class AlbumListController(
  private val albums: Albums,
  private val shared: SharedAlbums,
  private val scope: CoroutineScope,
) {
  private val dismissed = MutableStateFlow<Set<SharedAlbumNotice>>(emptySet())

  /**
   * L'elenco di adesso: `Loading` finche' gli album non sono stati letti (AP-1: `AlbumsStatus.Loading` con l'elenco ancora vuoto; prima un elenco vuoto
   * del primo secondo dopo l'avvio diceva "Nessun album, creane uno"), `Empty` senza album ne' avvisi.
   */
  val ui: StateFlow<AlbumListUi> = combine(albums.all, albums.status, shared.notices, shared.left, dismissed) { list, status, notices, left, gone ->
    val shown = notices.filter { it !in gone }
    val nothing = list.albums.isEmpty() && shown.isEmpty() && left.isEmpty()
    if (nothing && status == AlbumsStatus.Loading) return@combine AlbumListUi(AlbumListState.Loading)
    val state = if (nothing) {
      AlbumListState.Empty
    } else {
      AlbumListState.Ready(list.manualAndShared, list.smart, list.sort, shown)
    }
    AlbumListUi(state, left, reorderable = list.sort == AlbumListSort.CUSTOM)
  }.stateIn(scope, SharingStarted.Eagerly, AlbumListUi(AlbumListState.Loading))

  private val creator = ManualAlbumCreator(albums)

  /** Vero mentre "Crea" sta salvando (SOL-11): il foglio spegne il tasto. */
  val creating: StateFlow<Boolean> get() = creator.saving

  /** Crea un album personale. Per uno smart i dati (regole) li porta l'editor: qui nascono solo i manuali. Un invio alla volta, e mai una chiusura con la memoria piena. */
  suspend fun createManual(title: String): CreateAlbumResult = creator.create(title)

  fun setSort(sort: AlbumListSort) {
    // Memoria piena: l'ordine non cambia (K3b, SOL-10) e l'app non si chiude.
    scope.launch { QuietWrites.attempt("ordine dell'elenco degli album") { albums.setListSort(sort) } }
  }

  /**
   * Sposta [id] di un posto nel suo gruppo (i miei e condivisi, o gli smart) con l'ordine personalizzato. L'ordine e' uno solo per tutto
   * l'elenco (voce `order` di ogni album, D21): l'album va subito dopo l'album che precede, nell'elenco intero, quello con cui si scambia.
   */
  fun move(id: AlbumId, move: ListMove) {
    val state = ui.value.state as? AlbumListState.Ready ?: return
    val group: List<Album> = if (state.smart.any { it.id == id }) state.smart else state.mineAndShared
    val at = group.indexOfFirst { it.id == id }
    if (at < 0) return
    val everything = (albums.all.value.albums)
    when (move) {
      ListMove.UP -> {
        val target = group.getOrNull(at - 1) ?: return
        val before = everything.indexOfFirst { it.id == target.id }
        val after = everything.getOrNull(before - 1)?.id
        scope.launch { QuietWrites.attempt("posto dell'album nell'elenco") { albums.moveInList(id, after) } }
      }
      ListMove.DOWN -> {
        val target = group.getOrNull(at + 1) ?: return
        scope.launch { QuietWrites.attempt("posto dell'album nell'elenco") { albums.moveInList(id, target.id) } }
      }
    }
  }

  /** Se [move] e' possibile per [id] (la riga non offre "Sposta su" in cima). */
  fun canMove(id: AlbumId, move: ListMove): Boolean {
    val state = ui.value.state as? AlbumListState.Ready ?: return false
    if (!ui.value.reorderable) return false
    val group: List<Album> = if (state.smart.any { it.id == id }) state.smart else state.mineAndShared
    val at = group.indexOfFirst { it.id == id }
    return when (move) {
      ListMove.UP -> at > 0
      ListMove.DOWN -> at in 0 until group.size - 1
    }
  }

  /** L'avviso e' stato letto: non torna (lo ricorda anche il contratto; qui sparisce subito dalla schermata). */
  fun dismissNotice(notice: SharedAlbumNotice) {
    dismissed.update { it + notice }
    scope.launch { QuietWrites.attempt("avviso letto") { shared.dismissNotice(notice) } }
  }

  /** "Mostra di nuovo" un album condiviso che avevo lasciato (E-SH21). */
  fun rejoin(id: AlbumId) {
    scope.launch { QuietWrites.attempt("album mostrato di nuovo") { shared.rejoin(id) } }
  }
}
