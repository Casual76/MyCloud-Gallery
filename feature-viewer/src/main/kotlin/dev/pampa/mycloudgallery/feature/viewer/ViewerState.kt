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
import dev.pampa.mycloudgallery.core.imaging.contract.UnavailableReason
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

/**
 * L'originale di un media del NAS chiesto con lo zoom (Fase 4, D19): a che punto e', o perche' non arriva. Quando arriva la
 * voce esce dalla mappa e [ViewerState.originalReady] sale (le misure per lo zoom e i tasselli si rifanno).
 */
@Immutable
internal sealed interface OriginalProgress {

  data class Reading(val progress: Float?) : OriginalProgress

  data class Blocked(val reason: UnavailableReason, val sizeBytes: Long?) : OriginalProgress

  data class Failed(val code: ImageFailure) : OriginalProgress
}

/** Un avviso breve sopra la foto (si spegne da solo): cosa e' successo a cio' che stavi guardando. */
internal enum class ViewerMessage(@StringRes val title: Int, @StringRes val detail: Int?) {
  /** E-V6: l'elemento e' sparito dalla vista (cancellato da Galleria o da un altro dispositivo). */
  ItemGone(R.string.viewer_msg_gone_title, R.string.viewer_msg_gone_detail),

  /** L'utente l'ha nascosto dal viewer: sparisce dalla Libreria. */
  Hidden(R.string.viewer_msg_hidden_title, R.string.viewer_msg_hidden_detail),

  /**
   * Fase 7 (P9, D23, E-H2): nascosto, ma l'elemento sta nell'area comune o in un album condiviso e i familiari lo vedono ancora. Prende il
   * posto di [Hidden] solo quando e' vero: Nascosti protegge solo dentro l'app.
   */
  HiddenFamilySees(R.string.viewer_msg_hidden_family_title, R.string.viewer_msg_hidden_family_detail),

  /** L'utente l'ha riportato nella Libreria (dai Nascosti). */
  Unhidden(R.string.viewer_msg_unhidden_title, R.string.viewer_msg_unhidden_detail),

  /** Copiato negli appunti. */
  Copied(R.string.viewer_msg_copied_title, null),

  /** Il testo selezionato e' negli appunti (testo dal vivo, D43): annunciato da TalkBack. */
  TextCopied(R.string.livetext_msg_copied_title, null),

  /** "Testo nella foto" su una foto senza testo da mostrare (E-V9). */
  NoText(R.string.livetext_msg_none_title, R.string.livetext_msg_none_detail),

  /** Un'azione non e' riuscita (nessuna app per aprire la posizione, nessuna app per condividere). */
  ActionFailed(R.string.viewer_msg_failed_title, R.string.viewer_msg_failed_detail),

  /** "Nascondi" non e' riuscito (disco pieno, database in errore): l'elemento e' ancora nella Libreria (R8). */
  HideFailed(R.string.viewer_msg_hide_failed_title, R.string.viewer_msg_hide_failed_detail),

  /** "Mostra nella Libreria" non e' riuscito: l'elemento resta tra i Nascosti. */
  UnhideFailed(R.string.viewer_msg_unhide_failed_title, R.string.viewer_msg_unhide_failed_detail),

  /** Il preferito non e' stato salvato. */
  FavoriteFailed(R.string.viewer_msg_favorite_failed_title, R.string.viewer_msg_favorite_failed_detail),

  /** La foto in movimento non ha una parte video che sappiamo riprodurre (E-M16). */
  MotionUnplayable(R.string.viewer_msg_motion_title, R.string.viewer_msg_motion_detail),

  /** "Trova simili" su un media senza vettore (Fase 6, P8, E-Q14): la voce spiega perche' non si puo'. */
  SimilarUnavailable(R.string.viewer_msg_similar_title, R.string.viewer_msg_similar_detail),

  /** Il ritaglio del soggetto e' negli appunti (Fase 6, P13, D47): annunciato da TalkBack. */
  CutoutCopied(R.string.cutout_msg_copied_title, null),

  /** "Salva come nuova foto" e' riuscito: la foto sta in `Ritagli` (dai Nascosti, nascosta). */
  CutoutSaved(R.string.cutout_msg_saved_title, R.string.cutout_msg_saved_detail),

  /** Nessun soggetto chiaro (E-V10): detto solo quando il ritaglio e' chiesto dal menu o da TalkBack; con il dito basta una vibrazione. */
  CutoutNone(R.string.cutout_msg_none_title, R.string.cutout_msg_none_detail),

  /** Il ritaglio non e' riuscito (`CUTOUT_FAILED`). */
  CutoutFailed(R.string.cutout_msg_failed_title, R.string.cutout_msg_failed_detail),

  /** Il ritaglio non si e' potuto salvare (`CUTOUT_SAVE_FAILED`): spazio o permesso. */
  CutoutSaveFailed(R.string.cutout_msg_save_failed_title, R.string.cutout_msg_save_failed_detail),

  /** La memoria e' finita durante il ritaglio, la copia o il salvataggio (R20): niente cade, si dice di chiudere qualche app. */
  CutoutLowMemory(R.string.cutout_msg_memory_title, R.string.cutout_msg_memory_detail),

  /** Manca lo spazio anche per il piccolo modello del ritaglio (VR-2): lo si dice, non e' un "riprova". */
  CutoutNoSpace(R.string.cutout_msg_space_title, R.string.cutout_msg_space_detail),

  /** Fase 7 (P10, D21): "Imposta come copertina" e' riuscito. */
  AlbumCoverSet(R.string.viewer_msg_album_cover_title, R.string.viewer_msg_album_cover_detail),

  /** Fase 7 (P10, D39): "Rimuovi dall'album" e' riuscito: il file resta dov'e'. */
  AlbumRemoved(R.string.viewer_msg_album_removed_title, R.string.viewer_msg_album_removed_detail),

  /** Fase 7 (P10, §2.4): la tabella dei permessi non lo permette piu' (il livello e' cambiato mentre il menu era aperto). */
  AlbumDenied(R.string.viewer_msg_album_denied_title, R.string.viewer_msg_album_denied_detail),

  /** Fase 7 (P10): la voce degli album non e' riuscita; l'album non e' cambiato. */
  AlbumFailed(R.string.viewer_msg_album_failed_title, R.string.viewer_msg_album_failed_detail),

  /** "Scarica sul telefono" e' riuscito (Fase 4, D20). */
  Downloaded(R.string.viewer_msg_downloaded_title, R.string.viewer_msg_downloaded_detail),

  /** Lo scaricamento non e' riuscito: nessun file a meta' sul telefono. */
  DownloadFailed(R.string.viewer_msg_download_failed_title, R.string.viewer_msg_download_failed_detail),
  DownloadNasUnreachable(R.string.viewer_msg_download_nas_title, R.string.viewer_msg_download_nas_detail),

  /** D-15: fuori casa con la VPN spenta: il NAS non si raggiunge, nemmeno con i dati mobili. */
  DownloadVpnOff(R.string.viewer_msg_download_vpn_title, R.string.viewer_msg_download_vpn_detail),
  DownloadPhoneFull(R.string.viewer_msg_download_full_title, R.string.viewer_msg_download_full_detail),
  DownloadIntegrity(R.string.viewer_msg_download_integrity_title, R.string.viewer_msg_download_integrity_detail),
  DownloadCancelled(R.string.viewer_msg_download_cancelled_title, R.string.viewer_msg_download_cancelled_detail),
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

  /**
   * Quante volte l'utente ha detto "Riprova" (o ha accettato i dati mobili) per ogni pagina: e' la chiave che rifa il
   * caricamento della pagina e l'avvio del suo video. Non si salva: dopo una ricreazione si riparte da zero (Fase 4).
   */
  val retries = mutableStateMapOf<MediaId, Int>()

  /** L'originale del NAS chiesto con lo zoom, per pagina. */
  val originals = mutableStateMapOf<MediaId, OriginalProgress>()

  /** Quante volte l'originale di una pagina e' arrivato sul telefono: rifa le misure dello zoom e i tasselli. */
  val originalReady = mutableStateMapOf<MediaId, Int>()

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
