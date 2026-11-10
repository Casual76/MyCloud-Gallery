package dev.pampa.mycloudgallery.feature.albums.page

import dev.pampa.mycloudgallery.core.common.files.QuietWrites
import dev.pampa.mycloudgallery.core.albums.contract.Album
import dev.pampa.mycloudgallery.core.albums.contract.AlbumContent
import dev.pampa.mycloudgallery.core.albums.contract.Albums
import dev.pampa.mycloudgallery.core.albums.contract.EditOutcome
import dev.pampa.mycloudgallery.core.albums.contract.ShareRequest
import dev.pampa.mycloudgallery.core.albums.contract.ShareRunner
import dev.pampa.mycloudgallery.core.albums.contract.SmartChoices
import dev.pampa.mycloudgallery.core.library.contract.OrderedTimelineView
import dev.pampa.mycloudgallery.core.model.AlbumId
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.feature.albums.contract.AlbumMessage
import dev.pampa.mycloudgallery.feature.albums.contract.AlbumScreensClock
import dev.pampa.mycloudgallery.feature.albums.contract.WhyBlocked
import dev.pampa.mycloudgallery.feature.albums.share.ShareSheetController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// "SUGGERITE" E "DA CONDIVIDERE" (Fase 7, P10; docs/fase7-progetto.md §1.5 `SuggestionsState`, D14, D16, D17, Q4 (a), E-AL3-E-AL7). Due elenchi di dubbi
// che l'utente risolve **con un gesto suo**:
//  - **Suggerite** (uno smart): foto che potrebbero starci e il motore non ha messo da solo (un altro dispositivo, un familiare, appena fuori
//    raggio...). "Accetta" le fa entrare (voce `accept:`, per sempre), "Escludi" le toglie dai suggerimenti (voce `exclude:`, per sempre);
//  - **Da condividere** (uno smart condiviso): foto nuove che entrerebbero da sole e **non si spostano da sole** (D17, Q4 a). "Condividi" apre il
//    foglio di conferma col conteggio, lo stesso di ogni aggiunta a un album condiviso.
// Senza una selezione, l'azione vale per tutte e il bottone lo dice col numero ("Accetta tutte (12)").
// Le viste vengono da `Albums.content` (mai un nascosto); questa classe non ne costruisce altre.

enum class SuggestionKind { SUGGESTED, TO_SHARE }

data class SuggestionsUi(
  val album: Album? = null,
  val kind: SuggestionKind,
  /** La vista dei dubbi (senza nascosti); null prima della prima lettura o se l'album non ne ha. */
  val view: OrderedTimelineView? = null,
  val count: Int = 0,
  /** In selezione un tocco su una cella la sceglie; altrimenti apre il viewer sulla vista. */
  val selecting: Boolean = false,
  val selected: Set<MediaId> = emptySet(),
  val message: AlbumMessage? = null,
  val loaded: Boolean = false,
)

class SuggestionsController(
  val id: AlbumId,
  val kind: SuggestionKind,
  albums: Albums,
  private val choices: SmartChoices,
  runner: ShareRunner,
  clock: AlbumScreensClock,
  private val scope: CoroutineScope,
) {
  /** Il foglio di conferma di "Da condividere". */
  val share: ShareSheetController = ShareSheetController(runner, clock, scope)

  private data class Local(val selecting: Boolean = false, val selected: Set<MediaId> = emptySet(), val message: AlbumMessage? = null)

  private val local = MutableStateFlow(Local())

  val ui: StateFlow<SuggestionsUi> = combine(albums.content(id), local) { content, loc -> build(content, loc) }
    .stateIn(scope, SharingStarted.Eagerly, SuggestionsUi(kind = kind))

  private fun viewOf(content: AlbumContent): OrderedTimelineView? = if (kind == SuggestionKind.SUGGESTED) content.suggested else content.toShare

  private fun build(content: AlbumContent?, loc: Local): SuggestionsUi {
    if (content == null) return SuggestionsUi(kind = kind, message = loc.message, loaded = true)
    val view = viewOf(content)
    val present = view?.let { idsOf(it) }.orEmpty().toSet()
    // Una scelta che nel frattempo non c'e' piu' (accettata, nascosta, tolta) esce dalla selezione.
    val selected = loc.selected.filterTo(LinkedHashSet()) { it in present }
    return SuggestionsUi(content.album, kind, view, view?.size ?: 0, loc.selecting && view != null, selected, loc.message, loaded = true)
  }

  private fun idsOf(view: OrderedTimelineView): List<MediaId> = (0 until view.size).map { view.snapshot.idAt(view.snapshotIndex(it)) }

  fun toggleSelecting() = local.update { if (it.selecting) Local(message = it.message) else it.copy(selecting = true) }

  fun toggle(media: MediaId) = local.update {
    if (!it.selecting) it else it.copy(selected = if (media in it.selected) it.selected - media else it.selected + media)
  }

  fun selectAll() {
    val view = ui.value.view ?: return
    local.update { it.copy(selecting = true, selected = idsOf(view).toSet()) }
  }

  fun clearSelection() = local.update { it.copy(selected = emptySet()) }

  fun dismissMessage() = local.update { it.copy(message = null) }

  /** I media su cui un'azione vale adesso: la selezione, o tutti se non si e' scelto niente (il bottone lo dice col numero). */
  fun target(): List<MediaId> {
    val state = ui.value
    val view = state.view ?: return emptyList()
    return if (state.selected.isNotEmpty()) idsOf(view).filter { it in state.selected } else idsOf(view)
  }

  /** "Accetta": le foto entrano nell'album (voce `accept:`, vale per sempre, E-AL5, E-AL13). */
  fun accept() {
    val media = target().ifEmpty { return }
    scope.launch { finish(QuietWrites.attemptResult("suggerite accettate") { choices.accept(id, media) }) }
  }

  /** "Escludi": le foto escono dai suggerimenti per sempre (voce `exclude:`). */
  fun exclude() {
    val media = target().ifEmpty { return }
    scope.launch { finish(QuietWrites.attemptResult("suggerite escluse") { choices.exclude(id, media) }) }
  }

  /** "Condividi" (Da condividere): il piano e il foglio di conferma col conteggio (D12, D17). Niente si sposta senza il tocco sul foglio. */
  fun shareSelected() {
    val media = target().ifEmpty { return }
    share.request(ShareRequest.AddToShared(id, media))
  }

  /** [out] null: il registro non si e' potuto scrivere (memoria piena) e niente e' cambiato (K3b). */
  private fun finish(out: EditOutcome?) {
    local.update {
      when (out) {
        null -> it.copy(message = AlbumMessage.NotSaved)
        EditOutcome.Done -> Local()
        EditOutcome.NotFound -> it.copy(message = AlbumMessage.NotFound)
        is EditOutcome.NotAllowed -> it.copy(message = AlbumMessage.NotAllowed)
        is EditOutcome.Invalid -> it.copy(message = AlbumMessage.NotInAlbum)
        is EditOutcome.Blocked -> it.copy(message = AlbumMessage.Blocked(WhyBlocked.of(out.reason)))
      }
    }
  }
}
