package dev.pampa.mycloudgallery.feature.albums.list

import dev.pampa.mycloudgallery.core.albums.contract.Album
import dev.pampa.mycloudgallery.core.albums.contract.AlbumListSort
import dev.pampa.mycloudgallery.core.albums.contract.Albums
import dev.pampa.mycloudgallery.core.albums.contract.CreateOutcome
import dev.pampa.mycloudgallery.core.albums.contract.SharedAlbumNotice
import dev.pampa.mycloudgallery.core.albums.contract.SharedAlbums
import dev.pampa.mycloudgallery.core.albums.contract.TitleProblem
import dev.pampa.mycloudgallery.core.model.AlbumId
import dev.pampa.mycloudgallery.core.model.AlbumKind
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

  /** L'elenco di adesso: `Loading` solo prima della prima lettura, `Empty` senza album ne' avvisi. */
  val ui: StateFlow<AlbumListUi> = combine(albums.all, shared.notices, shared.left, dismissed) { list, notices, left, gone ->
    val shown = notices.filter { it !in gone }
    val state = if (list.albums.isEmpty() && shown.isEmpty() && left.isEmpty()) {
      AlbumListState.Empty
    } else {
      AlbumListState.Ready(list.manualAndShared, list.smart, list.sort, shown)
    }
    AlbumListUi(state, left, reorderable = list.sort == AlbumListSort.CUSTOM)
  }.stateIn(scope, SharingStarted.Eagerly, AlbumListUi(AlbumListState.Loading))

  /** Crea un album personale. Per uno smart i dati (regole) li porta l'editor: qui nascono solo i manuali. */
  suspend fun createManual(title: String): CreateAlbumResult = when (val out = albums.create(title, AlbumKind.MANUAL, null)) {
    is CreateOutcome.Created -> CreateAlbumResult.Created(out.id)
    is CreateOutcome.Invalid -> CreateAlbumResult.InvalidTitle(out.problem)
    CreateOutcome.RulesTooLong, CreateOutcome.Unavailable -> CreateAlbumResult.Unavailable
  }

  fun setSort(sort: AlbumListSort) {
    scope.launch { albums.setListSort(sort) }
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
        scope.launch { albums.moveInList(id, after) }
      }
      ListMove.DOWN -> {
        val target = group.getOrNull(at + 1) ?: return
        scope.launch { albums.moveInList(id, target.id) }
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
    scope.launch { shared.dismissNotice(notice) }
  }

  /** "Mostra di nuovo" un album condiviso che avevo lasciato (E-SH21). */
  fun rejoin(id: AlbumId) {
    scope.launch { shared.rejoin(id) }
  }
}
