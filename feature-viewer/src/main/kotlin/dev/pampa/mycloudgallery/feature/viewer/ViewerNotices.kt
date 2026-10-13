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

  /** Servono i dati mobili: [sizeBytes] e' il peso dell'originale, se noto (E-V2). */
  data class MobileData(val sizeBytes: Long?) : PageNoticeKind

  data object VolumeUnmounted : PageNoticeKind

  data class ImageFailed(val code: ImageFailure) : PageNoticeKind

  data class VideoProblem(val problem: PlaybackProblem) : PageNoticeKind

  companion object {
    /**
     * Cosa dire per questa pagina, o null se non c'e' niente da dire. Un errore del video vince su tutto: la pagina e'
     * un video che non parte, non un'immagine che manca.
     */
    fun of(image: PageImage?, videoProblem: PlaybackProblem?): PageNoticeKind? = when {
      videoProblem != null -> VideoProblem(videoProblem)
      image == null -> null
      image.unavailable != null -> when (image.unavailable.reason) {
        UnavailableReason.NAS_UNREACHABLE -> NasUnreachable
        UnavailableReason.NOT_ALLOWED_HERE -> NotAllowedHere
        UnavailableReason.NEEDS_MOBILE_DATA_CONFIRMATION -> MobileData(image.unavailable.sizeBytes)
        UnavailableReason.VOLUME_UNMOUNTED -> VolumeUnmounted
      }
      image.failed != null -> ImageFailed(image.failed)
      image.sharp == null && image.loading != null -> Loading(image.loading.progress)
      else -> null
    }
  }
}

/** La nota di vetro in basso sulla foto per [kind]. */
@Composable
internal fun BoxScope.PageNotice(
  kind: PageNoticeKind,
  backdrop: GlassBackdropState,
  onConfirmMobileData: (() -> Unit)?,
  modifier: Modifier = Modifier,
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
    }
    PageNoticeKind.NotAllowedHere -> {
      icon = Icons.Rounded.WifiOff
      title = stringResource(R.string.viewer_notice_not_allowed_title)
      detail = stringResource(R.string.viewer_notice_not_allowed_detail)
    }
    is PageNoticeKind.MobileData -> {
      icon = Icons.Rounded.NetworkCell
      title = stringResource(R.string.viewer_notice_mobile_title)
      detail = if (kind.sizeBytes != null) {
        stringResource(R.string.viewer_notice_mobile_detail_size, Formatter.formatFileSize(context, kind.sizeBytes))
      } else {
        stringResource(R.string.viewer_notice_mobile_detail)
      }
      if (onConfirmMobileData != null) {
        actionLabel = stringResource(R.string.viewer_notice_mobile_action)
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
        },
      )
    }
    is PageNoticeKind.VideoProblem -> {
      icon = Icons.Rounded.VideocamOff
      title = stringResource(
        if (kind.problem == PlaybackProblem.UNSUPPORTED) R.string.viewer_notice_video_unsupported_title else R.string.viewer_notice_video_failed_title,
      )
      detail = stringResource(
        if (kind.problem == PlaybackProblem.UNSUPPORTED) R.string.viewer_notice_video_unsupported_detail else R.string.viewer_notice_video_failed_detail,
      )
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
      ViewerMessage.Copied, ViewerMessage.Hidden, ViewerMessage.Unhidden -> Icons.Rounded.CheckCircle
      ViewerMessage.ActionFailed, ViewerMessage.HideFailed, ViewerMessage.UnhideFailed, ViewerMessage.FavoriteFailed -> Icons.Rounded.ReportProblem
      else -> Icons.Rounded.Info
    },
    title = stringResource(message.title),
    detail = message.detail?.let { stringResource(it) },
    backdrop = backdrop,
    modifier = modifier,
  )
}
