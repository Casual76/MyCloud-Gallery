package dev.pampa.mycloudgallery.feature.albums.choose

import dev.pampa.mycloudgallery.core.albums.contract.AddOutcome
import dev.pampa.mycloudgallery.core.albums.contract.Album
import dev.pampa.mycloudgallery.core.albums.contract.AlbumOp
import dev.pampa.mycloudgallery.core.albums.contract.Albums
import dev.pampa.mycloudgallery.core.albums.contract.CreateOutcome
import dev.pampa.mycloudgallery.core.albums.contract.SharePlanOutcome
import dev.pampa.mycloudgallery.core.albums.contract.ShareRunner
import dev.pampa.mycloudgallery.core.albums.contract.SharedAlbums
import dev.pampa.mycloudgallery.core.albums.contract.SharedState
import dev.pampa.mycloudgallery.core.albums.contract.TitleProblem
import dev.pampa.mycloudgallery.core.library.contract.VisibleMedia
import dev.pampa.mycloudgallery.core.model.AlbumId
import dev.pampa.mycloudgallery.core.model.AlbumKind
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.ShareLevel
import dev.pampa.mycloudgallery.feature.albums.contract.AddToAlbumRequest
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

// "AGGIUNGI AD ALBUM" (Fase 7, P10; docs/fase7-progetto.md D35, D12, D22, §2.1 "Aggiunta", E-SH9): la scelta dell'album per dei media, da ogni
// punto d'ingresso (selezione in Libreria, viewer, Cerca, condivisione in arrivo). Offre i miei album manuali e i condivisi dove la tabella
// mi lascia aggiungere, piu' "Nuovo album" e "Nuovo album condiviso".
//  - su un album **personale** i media entrano subito (voci `item:` con impronta, nessun file toccato);
//  - su un album **condiviso** si apre sempre il foglio di conferma di D12, col conteggio (mai un'aggiunta diretta).
//
// **Nascosti** (D22, C27): fuori dai Nascosti un nascosto non si offre mai. I media arrivano da chi chiama (la selezione, il viewer), e qui
// passano comunque da `VisibleMedia` prima di contare e di aggiungere; senza quella porta non se ne offre nessuno (fallisce chiuso). Dai
// Nascosti ([AddToAlbumRequest.fromHidden]) l'album riceve il media ma non lo mostra finche' e' nascosto. Il foglio non mostra mai miniature
// dei media da aggiungere: solo il numero, e le copertine degli album (che vengono da `Albums`, senza nascosti).

/** Dove sono finiti i media. */
sealed interface AddResult {
  /** In un album personale: [added] nuovi, [alreadyThere] gia' elementi. */
  data class Added(val album: AlbumId, val added: Int, val alreadyThere: Int) : AddResult

  /** Il foglio di conferma di un album condiviso e' partito: la schermata porta alla pagina dell'album. */
  data class Sharing(val album: AlbumId) : AddResult
}

data class AddToAlbumUi(
  /** Quanti media si aggiungono (senza nascosti, salvo dai Nascosti). */
  val count: Int = 0,
  /** Gli album dove si puo' aggiungere, nell'ordine dell'elenco. */
  val choices: List<Album> = emptyList(),
  val busy: Boolean = false,
  val result: AddResult? = null,
  val message: AlbumMessage? = null,
  /** Il titolo di "Nuovo album" non va bene. */
  val titleProblem: TitleProblem? = null,
  val ready: Boolean = false,
)

class AddToAlbumController(
  private val request: AddToAlbumRequest,
  private val albums: Albums,
  private val shared: SharedAlbums,
  private val visible: VisibleMedia?,
  runner: ShareRunner,
  clock: AlbumScreensClock,
  private val scope: CoroutineScope,
) {
  /** Il foglio di conferma dell'aggiunta a un album condiviso (D12). */
  val share: ShareSheetController = ShareSheetController(runner, clock, scope)

  private data class Local(val busy: Boolean = false, val result: AddResult? = null, val message: AlbumMessage? = null, val titleProblem: TitleProblem? = null)

  private val offered = MutableStateFlow<List<MediaId>?>(null)
  private val local = MutableStateFlow(Local())

  val ui: StateFlow<AddToAlbumUi> = combine(offered, albums.all, local) { media, list, loc ->
    if (media == null) {
      AddToAlbumUi()
    } else {
      AddToAlbumUi(media.size, list.albums.filter { canAdd(it) }, loc.busy, loc.result, loc.message, loc.titleProblem, ready = true)
    }
  }.stateIn(scope, SharingStarted.Eagerly, AddToAlbumUi())

  /** I media offerti: i nascosti non si offrono fuori dai Nascosti (D22); senza la porta della visibilita' non se ne offre nessuno. */
  fun start() {
    if (offered.value != null) return
    scope.launch {
      val ids = request.media.distinct()
      offered.value = if (request.fromHidden) {
        ids
      } else {
        val ok = visible?.visible(ids).orEmpty()
        ids.filter { it in ok }
      }
      // P13: l'album scelto prima (la condivisione in arrivo): si aggiunge li', con le stesse regole di un tocco sull'elenco (un condiviso apre la
      // conferma col conteggio, D12). Un album che non c'e' piu' o che non riceve: resta l'elenco, e l'utente sceglie.
      request.album?.let { preset -> albums.all.value.albums.firstOrNull { it.id == preset }?.takeIf(::canAdd)?.let(::add) }
    }
  }

  /** Un album manuale e' sempre mio; uno smart non riceve media a mano da qui; un condiviso solo se la tabella mi lascia aggiungere. */
  private fun canAdd(album: Album): Boolean = when (album) {
    is Album.Manual -> true
    is Album.Smart -> false
    is Album.Shared -> album.state == SharedState.ACTIVE && shared.allowed(album.id, AlbumOp.ADD_MEDIA)
  }

  /** Aggiunge i media offerti a [album]: personale subito, condiviso con il foglio di conferma. */
  fun add(album: Album) {
    val media = offered.value.orEmpty()
    if (media.isEmpty() || local.value.busy) return
    local.update { it.copy(busy = true, message = null) }
    scope.launch {
      val out = albums.add(album.id, media)
      local.update { it.copy(busy = false) }
      when (out) {
        is AddOutcome.Added -> local.update { it.copy(result = AddResult.Added(album.id, out.added, out.alreadyThere)) }
        is AddOutcome.NeedsShareConfirmation, is AddOutcome.Blocked -> share.onAddOutcome(out)
        is AddOutcome.NothingToAdd -> local.update { it.copy(message = AlbumMessage.NothingToAdd(out.excluded)) }
        is AddOutcome.NotAllowed -> local.update { it.copy(message = AlbumMessage.NotAllowed) }
        AddOutcome.NotFound -> local.update { it.copy(message = AlbumMessage.NotFound) }
      }
    }
  }

  /** "Nuovo album": un album manuale con questi media. */
  fun addToNewAlbum(title: String) {
    val media = offered.value.orEmpty()
    if (media.isEmpty() || local.value.busy) return
    local.update { it.copy(busy = true, message = null, titleProblem = null) }
    scope.launch {
      when (val created = albums.create(title, AlbumKind.MANUAL, null)) {
        is CreateOutcome.Created -> {
          val out = albums.add(created.id, media)
          local.update {
            it.copy(busy = false, result = if (out is AddOutcome.Added) AddResult.Added(created.id, out.added, out.alreadyThere) else it.result)
          }
        }
        is CreateOutcome.Invalid -> local.update { it.copy(busy = false, titleProblem = created.problem) }
        CreateOutcome.RulesTooLong, CreateOutcome.Unavailable -> local.update { it.copy(busy = false, message = AlbumMessage.Blocked(WhyBlocked.NO_NAS)) }
      }
    }
  }

  /** "Nuovo album condiviso": il piano di un album nuovo con questi media (livello di partenza Q2, nascosti lasciati fuori, Q3). */
  fun addToNewSharedAlbum(title: String) {
    val media = offered.value.orEmpty()
    if (media.isEmpty() || local.value.busy) return
    local.update { it.copy(busy = true, message = null, titleProblem = null) }
    scope.launch {
      val out = shared.create(title, ShareLevel.DEFAULT, media)
      local.update { it.copy(busy = false) }
      if (out is SharePlanOutcome.InvalidTitle) local.update { it.copy(titleProblem = out.problem) } else share.onSharePlan(out)
    }
  }

  fun dismissMessage() = local.update { it.copy(message = null, titleProblem = null) }

  /** Il foglio e' partito: l'album a cui e' stato avviato lo condivide, e la schermata porta alla sua pagina. */
  fun onShareStarted(album: AlbumId) = local.update { it.copy(result = AddResult.Sharing(album)) }
}
