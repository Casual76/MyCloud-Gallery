package dev.pampa.mycloudgallery.feature.viewer

import android.text.format.Formatter
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.NetworkCell
import androidx.compose.material.icons.rounded.ReportProblem
import androidx.compose.material.icons.rounded.SdCardAlert
import androidx.compose.material.icons.rounded.VideocamOff
import androidx.compose.material.icons.rounded.VpnKeyOff
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import dev.antigravity.fluidengine.ui.fluid.GlassBackdropState
import dev.pampa.mycloudgallery.core.designsystem.viewer.GalleryNoticeProgress
import dev.pampa.mycloudgallery.core.designsystem.viewer.GalleryViewerNotice
import dev.pampa.mycloudgallery.core.imaging.contract.ImageFailure
import dev.pampa.mycloudgallery.core.imaging.contract.UnavailableReason
import dev.pampa.mycloudgallery.feature.viewer.video.PlaybackProblem

/**
 * Perche' la pagina corrente non mostra l'originale, in forma di dato. Il viewer non resta mai muto: una sorgente lenta
 * (E-V1), irraggiungibile o da confermare (E-V2, E-V1 di Fase 4), un file che non si decodifica o un video che il
 * dispositivo non sa riprodurre hanno ciascuno una frase. La miniatura resta sotto in ogni caso.
 */
internal sealed interface PageNoticeKind {

  /** L'originale sta arrivando: [progress] se la sorgente lo sa (dal NAS), altrimenti una barra che scorre. */
  data class Loading(val progress: Float?) : PageNoticeKind

  data object NasUnreachable : PageNoticeKind

  data object NotAllowedHere : PageNoticeKind

  /**
   * D-15: fuori casa con la VPN di casa spenta. Il NAS non si raggiunge, nemmeno con i dati mobili: la nota non propone di usarli. Gli stessi
   * parole della Diagnostica ("VPN spenta fuori casa") e dell'indicatore di stato ("VPN spenta · Apri WireGuard").
   */
  data object VpnOffAway : PageNoticeKind

  /** D-15: nessuna rete (come l'indicatore di stato: "Offline"). */
  data object Offline : PageNoticeKind

  /**
   * Servono i dati mobili: [sizeBytes] e' il peso dell'originale, se noto (E-V2). [what] dice cosa si sta per leggere: la
   * frase cambia ("Questo video pesa...", "Questo file pesa...").
   */
  data class MobileData(val sizeBytes: Long?, val what: MobileDataFor = MobileDataFor.ORIGINAL) : PageNoticeKind

  data object VolumeUnmounted : PageNoticeKind

  data class ImageFailed(val code: ImageFailure) : PageNoticeKind

  data class VideoProblem(val problem: PlaybackProblem) : PageNoticeKind

  /** Il video del NAS sta partendo: finche' non c'e' il primo fotogramma (Fase 4, "Loading fino al primo fotogramma"). */
  data object VideoLoading : PageNoticeKind

  /** "Scarica sul telefono" in corso o in coda ([queued]); [progress] da 0 a 1 se noto. */
  data class Downloading(val progress: Float?, val queued: Boolean = false) : PageNoticeKind

  companion object {
    /**
     * Cosa dire per questa pagina, o null se non c'e' niente da dire. Un errore del video vince su tutto: la pagina e'
     * un video che non parte, non un'immagine che manca.
     *
     * @param isVideo la pagina e' un video: un'immagine che non si apre non e' un problema suo (il lettore decide).
     * @param original l'originale del NAS chiesto con lo zoom (Fase 4), o per un video il permesso di partire (dati
     *   mobili da confermare): se c'e' qualcosa da dire.
     */
    fun of(
      image: PageImage?,
      videoProblem: PlaybackProblem?,
      isVideo: Boolean = false,
      original: OriginalProgress? = null,
    ): PageNoticeKind? = when {
      videoProblem != null -> VideoProblem(videoProblem)
      // Un video che non puo' partire (dati mobili da confermare, NAS non raggiungibile) lo dice prima di ogni immagine.
      isVideo && original != null -> ofOriginal(original, isVideo = true)
      image == null -> ofOriginal(original, isVideo)
      image.unavailable != null -> when (image.unavailable.reason) {
        UnavailableReason.NAS_UNREACHABLE -> NasUnreachable
        UnavailableReason.NOT_ALLOWED_HERE -> NotAllowedHere
        UnavailableReason.VPN_OFF_AWAY -> VpnOffAway
        UnavailableReason.OFFLINE -> Offline
        UnavailableReason.NEEDS_MOBILE_DATA_CONFIRMATION ->
          MobileData(image.unavailable.sizeBytes, if (isVideo) MobileDataFor.VIDEO else MobileDataFor.ORIGINAL)
        UnavailableReason.VOLUME_UNMOUNTED -> VolumeUnmounted
      }
      image.failed != null -> if (isVideo) null else ImageFailed(image.failed)
      image.sharp == null && image.loading != null -> Loading(image.loading.progress)
      else -> ofOriginal(original, isVideo)
    }

    /** L'originale chiesto con lo zoom, mentre la nitida (l'HQ) e' gia' sullo schermo. */
    private fun ofOriginal(original: OriginalProgress?, isVideo: Boolean): PageNoticeKind? = when (original) {
      null -> null
      is OriginalProgress.Reading -> Loading(original.progress)
      is OriginalProgress.Blocked -> when (original.reason) {
        UnavailableReason.NAS_UNREACHABLE -> NasUnreachable
        UnavailableReason.NOT_ALLOWED_HERE -> NotAllowedHere
        UnavailableReason.VPN_OFF_AWAY -> VpnOffAway
        UnavailableReason.OFFLINE -> Offline
        UnavailableReason.NEEDS_MOBILE_DATA_CONFIRMATION ->
          MobileData(original.sizeBytes, if (isVideo) MobileDataFor.VIDEO else MobileDataFor.ORIGINAL)
        UnavailableReason.VOLUME_UNMOUNTED -> VolumeUnmounted
      }
      is OriginalProgress.Failed -> ImageFailed(original.code)
    }
  }
}

/** Cosa si sta per leggere con i dati mobili: cambia la frase della nota. */
internal enum class MobileDataFor { ORIGINAL, VIDEO, DOWNLOAD }

/** La nota di vetro in basso sulla foto per [kind]. */
@Composable
internal fun BoxScope.PageNotice(
  kind: PageNoticeKind,
  backdrop: GlassBackdropState,
  onConfirmMobileData: (() -> Unit)?,
  modifier: Modifier = Modifier,
  onRetry: (() -> Unit)? = null,
  onCancelDownload: (() -> Unit)? = null,
) {
  val context = LocalContext.current
  val icon: ImageVector
  val title: String
  var detail: String? = null
  var progress: GalleryNoticeProgress? = null
  var actionLabel: String? = null
  var onAction: (() -> Unit)? = null
  when (kind) {
    is PageNoticeKind.Loading -> {
      icon = Icons.Rounded.CloudDownload
      title = stringResource(R.string.viewer_notice_loading_title)
      detail = stringResource(R.string.viewer_notice_loading_detail)
      progress = kind.progress?.let { GalleryNoticeProgress.Determinate(it) } ?: GalleryNoticeProgress.Indeterminate
    }
    PageNoticeKind.NasUnreachable -> {
      icon = Icons.Rounded.CloudOff
      title = stringResource(R.string.viewer_notice_nas_title)
      detail = stringResource(R.string.viewer_notice_nas_detail)
      if (onRetry != null) {
        actionLabel = stringResource(R.string.viewer_notice_retry)
        onAction = onRetry
      }
    }
    PageNoticeKind.NotAllowedHere -> {
      icon = Icons.Rounded.WifiOff
      title = stringResource(R.string.viewer_notice_not_allowed_title)
      detail = stringResource(R.string.viewer_notice_not_allowed_detail)
    }
    PageNoticeKind.VpnOffAway -> {
      icon = Icons.Rounded.VpnKeyOff
      title = stringResource(R.string.viewer_notice_vpn_title)
      detail = stringResource(R.string.viewer_notice_vpn_detail)
      if (onRetry != null) {
        actionLabel = stringResource(R.string.viewer_notice_retry)
        onAction = onRetry
      }
    }
    PageNoticeKind.Offline -> {
      icon = Icons.Rounded.WifiOff
      title = stringResource(R.string.viewer_notice_offline_title)
      detail = stringResource(R.string.viewer_notice_offline_detail)
      if (onRetry != null) {
        actionLabel = stringResource(R.string.viewer_notice_retry)
        onAction = onRetry
      }
    }
    is PageNoticeKind.MobileData -> {
      icon = Icons.Rounded.NetworkCell
      title = stringResource(R.string.viewer_notice_mobile_title)
      detail = if (kind.sizeBytes != null) {
        stringResource(
          when (kind.what) {
            MobileDataFor.ORIGINAL -> R.string.viewer_notice_mobile_detail_size
            MobileDataFor.VIDEO -> R.string.viewer_notice_mobile_video_detail_size
            MobileDataFor.DOWNLOAD -> R.string.viewer_notice_mobile_download_detail_size
          },
          Formatter.formatFileSize(context, kind.sizeBytes),
        )
      } else {
        stringResource(R.string.viewer_notice_mobile_detail)
      }
      if (onConfirmMobileData != null) {
        actionLabel = stringResource(
          when (kind.what) {
            MobileDataFor.ORIGINAL -> R.string.viewer_notice_mobile_action
            MobileDataFor.VIDEO -> R.string.viewer_notice_mobile_video_action
            MobileDataFor.DOWNLOAD -> R.string.viewer_notice_mobile_download_action
          },
        )
        onAction = onConfirmMobileData
      }
    }
    PageNoticeKind.VolumeUnmounted -> {
      icon = Icons.Rounded.SdCardAlert
      title = stringResource(R.string.viewer_notice_volume_title)
      detail = stringResource(R.string.viewer_notice_volume_detail)
    }
    is PageNoticeKind.ImageFailed -> {
      icon = Icons.Rounded.ReportProblem
      title = stringResource(R.string.viewer_notice_failed_title)
      detail = stringResource(
        when (kind.code) {
          ImageFailure.DECODE_FAILED -> R.string.viewer_notice_failed_decode
          ImageFailure.OUT_OF_MEMORY -> R.string.viewer_notice_failed_memory
          ImageFailure.UNSUPPORTED_FORMAT -> R.string.viewer_notice_failed_format
          ImageFailure.NOT_FOUND -> R.string.viewer_notice_failed_missing
          ImageFailure.STORAGE_FULL -> R.string.viewer_notice_failed_storage
        },
      )
    }
    is PageNoticeKind.VideoProblem -> {
      icon = Icons.Rounded.VideocamOff
      title = stringResource(
        when (kind.problem) {
          PlaybackProblem.UNSUPPORTED -> R.string.viewer_notice_video_unsupported_title
          PlaybackProblem.NAS_UNREACHABLE -> R.string.viewer_notice_video_nas_title
          PlaybackProblem.FAILED -> R.string.viewer_notice_video_failed_title
        },
      )
      detail = stringResource(
        when (kind.problem) {
          PlaybackProblem.UNSUPPORTED -> R.string.viewer_notice_video_unsupported_detail
          PlaybackProblem.NAS_UNREACHABLE -> R.string.viewer_notice_video_nas_detail
          PlaybackProblem.FAILED -> R.string.viewer_notice_video_failed_detail
        },
      )
      if (kind.problem == PlaybackProblem.NAS_UNREACHABLE && onRetry != null) {
        actionLabel = stringResource(R.string.viewer_notice_retry)
        onAction = onRetry
      }
    }
    PageNoticeKind.VideoLoading -> {
      icon = Icons.Rounded.CloudDownload
      title = stringResource(R.string.viewer_notice_video_loading_title)
      detail = stringResource(R.string.viewer_notice_video_loading_detail)
      progress = GalleryNoticeProgress.Indeterminate
    }
    is PageNoticeKind.Downloading -> {
      icon = Icons.Rounded.CloudDownload
      title = stringResource(R.string.viewer_notice_download_title)
      detail = stringResource(if (kind.queued) R.string.viewer_notice_download_queued else R.string.viewer_notice_download_detail)
      progress = kind.progress?.let { GalleryNoticeProgress.Determinate(it) } ?: GalleryNoticeProgress.Indeterminate
      if (onCancelDownload != null) {
        actionLabel = stringResource(R.string.viewer_notice_download_cancel)
        onAction = onCancelDownload
      }
    }
  }
  GalleryViewerNotice(
    icon = icon,
    title = title,
    detail = detail,
    backdrop = backdrop,
    progress = progress,
    actionLabel = actionLabel,
    onAction = onAction,
    modifier = modifier,
  )
}

/** L'avviso breve in alto (si spegne da solo): E-V6, nascosto, copiato. */
@Composable
internal fun MessageNotice(message: ViewerMessage, backdrop: GlassBackdropState, modifier: Modifier = Modifier) {
  GalleryViewerNotice(
    icon = when (message) {
      ViewerMessage.Copied, ViewerMessage.TextCopied, ViewerMessage.Hidden, ViewerMessage.Unhidden, ViewerMessage.Downloaded,
      ViewerMessage.CutoutCopied, ViewerMessage.CutoutSaved,
      -> Icons.Rounded.CheckCircle
      ViewerMessage.ActionFailed, ViewerMessage.HideFailed, ViewerMessage.UnhideFailed, ViewerMessage.FavoriteFailed,
      ViewerMessage.DownloadFailed, ViewerMessage.DownloadNasUnreachable, ViewerMessage.DownloadPhoneFull,
      ViewerMessage.DownloadIntegrity, ViewerMessage.CutoutFailed, ViewerMessage.CutoutSaveFailed,
      ViewerMessage.CutoutLowMemory, ViewerMessage.CutoutNoSpace,
      -> Icons.Rounded.ReportProblem
      else -> Icons.Rounded.Info
    },
    title = stringResource(message.title),
    detail = message.detail?.let { stringResource(it) },
    backdrop = backdrop,
    modifier = modifier,
  )
}

/**
 * L'esito di "Sposta", "Rinomina" o "Elimina…" su un media del NAS (Fase 4B): "Operazione avviata" o il motivo per cui non e' partita.
 * Solo una frase delle risorse, mai un testo del NAS. Sta dove sta l'avviso breve e si spegne da solo come lui.
 */
@Composable
internal fun NasNoticeView(notice: dev.pampa.mycloudgallery.feature.viewer.menu.NasActionNotice, backdrop: GlassBackdropState, modifier: Modifier = Modifier) {
  GalleryViewerNotice(
    icon = if (notice.started) Icons.Rounded.CheckCircle else Icons.Rounded.ReportProblem,
    title = stringResource(notice.text),
    backdrop = backdrop,
    modifier = modifier,
  )
}
