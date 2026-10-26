package dev.pampa.mycloudgallery.feature.albums.share

import dev.pampa.mycloudgallery.core.albums.contract.ClosePreview
import dev.pampa.mycloudgallery.core.albums.contract.ClosePreviewOutcome
import dev.pampa.mycloudgallery.core.albums.contract.CloseMode
import dev.pampa.mycloudgallery.core.albums.contract.CloseOutcome
import dev.pampa.mycloudgallery.core.albums.contract.OthersAfterClose
import dev.pampa.mycloudgallery.core.albums.contract.ShareRequest
import dev.pampa.mycloudgallery.core.albums.contract.SharedAlbums
import dev.pampa.mycloudgallery.core.model.AlbumId
import dev.pampa.mycloudgallery.feature.albums.contract.CloseSheetState
import dev.pampa.mycloudgallery.feature.albums.contract.WhyBlocked
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

// I FOGLI DI CHIUSURA (Fase 7, P10; docs/fase7-progetto.md D31 adeguata alla risposta Q5 del 22/10, §1.5 `CloseSheetState`, §2.6, E-SH7).
// "Smetti di condividere" e "Elimina l'album": li fa il creatore (o l'erede). **Lo decide chi chiude, ogni volta, senza preselezione**: il
// foglio chiede cosa succede agli altri familiari ("Sparisce per gli altri" oppure "Ognuno tiene una copia") e la conferma resta spenta
// finche' non si sceglie. La scelta viaggia nella definizione firmata (`afterClose`): ogni dispositivo che legge la chiusura fa la stessa cosa.
// In entrambi i casi i file restano nell'area comune, salvo "Riporta nelle mie cartelle (N miei)": uno spostamento vero, con il suo foglio di
// conferma e il suo conteggio (lo stesso della condivisione, [ShareSheetController]).
//
// L'ordine, quando si riporta: prima il foglio di "Riporta" (se l'utente non conferma, l'album non si e' chiuso e niente e' cambiato), poi la
// chiusura. Chiudere non sposta mai un file da sola.

/** Il foglio di chiusura con il riepilogo e cio' che sta facendo. */
data class CloseSheetUi(
  val sheet: CloseSheetState,
  val preview: ClosePreview,
  /** Quanti altri familiari vedono l'album oggi (il foglio lo dice nella frase sulle copie). */
  val otherMembers: Int = preview.otherMembers,
  val busy: Boolean = false,
  val problem: WhyBlocked? = null,
)

class CloseSheetController(
  private val shared: SharedAlbums,
  private val share: ShareSheetController,
  private val scope: CoroutineScope,
) {
  private val _ui = MutableStateFlow<CloseSheetUi?>(null)

  /** Il foglio di chiusura di adesso, o null. */
  val ui: StateFlow<CloseSheetUi?> = _ui.asStateFlow()

  /** L'ultima chiusura riuscita: la schermata porta l'utente fuori dalla pagina (diventata personale, o eliminata). */
  private val _closed = MutableStateFlow<Pair<AlbumId, CloseMode>?>(null)
  val closed: StateFlow<Pair<AlbumId, CloseMode>?> = _closed.asStateFlow()

  /** Apre il foglio per [id]: il riepilogo (quanti miei posso riportare, quanti altri lo vedono) viene dal catalogo e dalla replica. */
  fun open(id: AlbumId, mode: CloseMode) {
    scope.launch {
      when (val out = shared.closePreview(id, mode)) {
        is ClosePreviewOutcome.Ready -> {
          val p = out.preview
          _ui.value = CloseSheetUi(CloseSheetState(id, p.title, mode, forOthers = null, bringBackCount = p.bringBackCandidates.size, bringBack = false), p)
        }
        is ClosePreviewOutcome.NotAllowed, ClosePreviewOutcome.NotFound -> _ui.value = null
      }
    }
  }

  /** La scelta per gli altri (Q5). Non c'e' un valore di partenza: finche' non si sceglie, [CloseSheetState.canConfirm] e' falso. */
  fun chooseOthers(choice: OthersAfterClose) {
    _ui.value = _ui.value?.let { it.copy(sheet = it.sheet.copy(forOthers = choice), problem = null) }
  }

  /** "Riporta nelle mie cartelle (N miei)": un'opzione a parte, spenta all'apertura. */
  fun setBringBack(on: Boolean) {
    _ui.value = _ui.value?.let { if (it.sheet.bringBackCount == 0) it else it.copy(sheet = it.sheet.copy(bringBack = on), problem = null) }
  }

  /**
   * Il tocco di conferma del foglio di chiusura. Senza una scelta per gli altri non fa niente. Con "Riporta" apre prima il foglio dello
   * spostamento (che ha la sua conferma); la chiusura parte quando quello e' partito.
   */
  fun confirm() {
    val current = _ui.value ?: return
    val sheet = current.sheet
    val others = sheet.forOthers
    if (!sheet.canConfirm || others == null || current.busy) return
    if (sheet.bringBack && sheet.bringBackCount > 0) {
      share.request(ShareRequest.BringBack(sheet.album, current.preview.bringBackCandidates)) { close(sheet.album, sheet.mode, others) }
    } else {
      scope.launch { close(sheet.album, sheet.mode, others) }
    }
  }

  private suspend fun close(id: AlbumId, mode: CloseMode, others: OthersAfterClose) {
    _ui.value = _ui.value?.copy(busy = true, problem = null)
    when (val out = shared.close(id, mode, others)) {
      CloseOutcome.Closed -> {
        _ui.value = null
        _closed.value = id to mode
      }
      is CloseOutcome.Blocked -> _ui.value = _ui.value?.copy(busy = false, problem = WhyBlocked.of(out.reason))
      is CloseOutcome.NotAllowed, CloseOutcome.NotFound -> _ui.value = _ui.value?.copy(busy = false, problem = WhyBlocked.NOT_ACTIVE)
    }
  }

  fun dismiss() {
    _ui.value = null
  }
}
