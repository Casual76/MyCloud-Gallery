package dev.pampa.mycloudgallery.feature.viewer

import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import dev.pampa.mycloudgallery.core.imaging.contract.ImageFailure
import dev.pampa.mycloudgallery.core.imaging.contract.ViewerImageState
import dev.pampa.mycloudgallery.core.model.MediaId

/**
 * Cio' che il viewer ha deciso di mostrare per una pagina, ricavato dalla sequenza di [ViewerImageState] della sua
 * sorgente. **Non si perde niente per strada**: l'anteprima resta quando arriva la versione nitida o un errore, cosi'
 * un NAS lento (E-V1) o irraggiungibile (E-V2) lascia sempre la miniatura sotto la nota che spiega.
 */
@Immutable
internal data class PageImage(
  val preview: ImageBitmap? = null,
  val sharp: ImageBitmap? = null,
  val hdr: Boolean = false,
  val loading: ViewerImageState.Loading? = null,
  val unavailable: ViewerImageState.Unavailable? = null,
  val failed: ImageFailure? = null,
) {

  /** La migliore bitmap che c'e': la nitida, altrimenti l'anteprima. */
  val best: ImageBitmap? get() = sharp ?: preview

  /** Vero se stiamo ancora aspettando l'originale e non c'e' un motivo per cui non arrivera'. */
  val isWaiting: Boolean get() = sharp == null && unavailable == null && failed == null

  fun reduce(state: ViewerImageState): PageImage = when (state) {
    is ViewerImageState.Preview -> copy(preview = state.bitmap)
    is ViewerImageState.Sharp -> copy(sharp = state.bitmap, hdr = state.hdr, loading = null, unavailable = null, failed = null)
    is ViewerImageState.Loading -> copy(loading = state, unavailable = null)
    is ViewerImageState.Unavailable -> copy(unavailable = state, loading = null)
    is ViewerImageState.Failed -> copy(failed = state.code, loading = null)
  }
}

/** Un avviso breve sopra la foto (si spegne da solo): cosa e' successo a cio' che stavi guardando. */
internal enum class ViewerMessage(@StringRes val title: Int, @StringRes val detail: Int?) {
  /** E-V6: l'elemento e' sparito dalla vista (cancellato da Galleria o da un altro dispositivo). */
  ItemGone(R.string.viewer_msg_gone_title, R.string.viewer_msg_gone_detail),

  /** L'utente l'ha nascosto dal viewer: sparisce dalla Libreria. */
  Hidden(R.string.viewer_msg_hidden_title, R.string.viewer_msg_hidden_detail),

  /** L'utente l'ha riportato nella Libreria (dai Nascosti). */
  Unhidden(R.string.viewer_msg_unhidden_title, R.string.viewer_msg_unhidden_detail),

  /** Copiato negli appunti. */
  Copied(R.string.viewer_msg_copied_title, null),

  /** Un'azione non e' riuscita (nessuna app per aprire la posizione, nessuna app per condividere). */
  ActionFailed(R.string.viewer_msg_failed_title, R.string.viewer_msg_failed_detail),

  /** La foto in movimento non ha una parte video che sappiamo riprodurre (E-M16). */
  MotionUnplayable(R.string.viewer_msg_motion_title, R.string.viewer_msg_motion_detail),
}

/**
 * Lo stato di una sessione del viewer. Sta in `rememberSaveable`: **id corrente e barre si ritrovano** dopo la
 * ricreazione dell'activity, la rotazione, lo split screen (E-V8). Lo zoom lo salva telephoto per la pagina corrente.
 *
 * L'identita' si salva come testo (`MediaId.value`): l'indice puo' essere ricostruito, l'id no.
 */
@Stable
internal class ViewerState(
  initialId: MediaId,
  chromeVisible: Boolean = true,
  infoOpen: Boolean = false,
) {

  /** L'elemento che si sta guardando (la pagina assestata del pager). */
  var currentId: MediaId by mutableStateOf(initialId)

  /** Le barre sono visibili. Un tocco sulla foto le nasconde: sfondo scuro e barre di sistema nascoste. */
  var chromeVisible: Boolean by mutableStateOf(chromeVisible)

  /** Il pannello informazioni e' aperto (foglio a meta' o colonna laterale). */
  var infoOpen: Boolean by mutableStateOf(infoOpen)

  /** La pagina corrente e' ingrandita: i gesti di un dito sono dello zoom, non del viewer. */
  var zoomed: Boolean by mutableStateOf(false)

  /** Vero se questo stato e' stato ritrovato dopo una ricreazione: il viewer si ripresenta aperto, senza rifare l'espansione. */
  var restored: Boolean = false

  /** Riporta la pagina corrente interamente nello schermo, con animazione. La imposta la pagina; non si salva. */
  var zoomReset: (suspend () -> Unit)? = null

  /** L'avviso breve in corso, se c'e'. */
  var message: ViewerMessage? by mutableStateOf(null)

  /** Cosa ogni pagina ha caricato finora: l'espansione parte da qui, e la nota sulla foto la legge da qui. */
  val images = mutableStateMapOf<MediaId, PageImage>()

  /**
   * Quante decodifiche di immagini possono andare insieme: la pagina corrente e le due vicine (E-V5). Un limite per
   * costruzione, non per fortuna: una pagina nuova aspetta che quella annullata abbia finito davvero.
   */
  val loadGate: kotlinx.coroutines.sync.Semaphore = kotlinx.coroutines.sync.Semaphore(MAX_DECODES)

  /** I preferiti toccati dall'utente e non ancora confermati dall'indice: la stella risponde subito. */
  val favoriteOverride = mutableStateMapOf<MediaId, Boolean>()

  companion object {
    const val MAX_DECODES = 3

    val Saver: Saver<ViewerState, Any> = listSaver(
      save = { listOf(it.currentId.value, it.chromeVisible, it.infoOpen) },
      restore = {
        ViewerState(initialId = MediaId(it[0] as String), chromeVisible = it[1] as Boolean, infoOpen = it[2] as Boolean)
          .also { restored -> restored.restored = true }
      },
    )
  }
}
