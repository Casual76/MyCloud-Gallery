package dev.pampa.mycloudgallery.feature.viewer.menu

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.IosShare
import androidx.compose.material.icons.rounded.LocationOff
import androidx.compose.material.icons.rounded.ReportProblem
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import dev.antigravity.fluidengine.ui.fluid.FluidButton
import dev.antigravity.fluidengine.ui.fluid.FluidButtonSize
import dev.antigravity.fluidengine.ui.fluid.FluidButtonStyle
import dev.antigravity.fluidengine.ui.fluid.GlassBackdropState
import dev.pampa.mycloudgallery.core.designsystem.sheet.GalleryConfirmSheet
import dev.pampa.mycloudgallery.core.designsystem.sheet.GalleryDeleteLocation
import dev.pampa.mycloudgallery.core.designsystem.sheet.GalleryDeleteLocationKind
import dev.pampa.mycloudgallery.core.designsystem.sheet.GallerySheetScaffold
import dev.pampa.mycloudgallery.core.designsystem.sheet.GallerySheetTone
import dev.pampa.mycloudgallery.core.designsystem.viewer.GalleryNoticeProgress
import dev.pampa.mycloudgallery.core.designsystem.viewer.GalleryViewerNotice
import dev.pampa.mycloudgallery.feature.viewer.R

// Fase 5B (P12, docs/fase5-progetto.md §2.7 "Condividere senza posizione", D22, D26, E-D15, E-V7, L41; §1.5 `ShareOutState`): cio' che il viewer mostra per
// "Condividi" e per l'elenco di "Ovunque". Tutto nel linguaggio del viewer (G3): l'avviso con l'avanzamento sopra la foto e un foglio di conferma del design
// system (`GalleryConfirmSheet`, con lo scudo contro le sovrapposizioni) per la decisione. Niente parte da solo: "Condividi con la posizione" e' un tocco
// dell'utente dopo che ha letto perche' la copia senza posizione non c'e'.

/**
 * L'avviso sopra la foto per la preparazione di una condivisione: l'avanzamento mentre si copia e si toglie la posizione, poi "Posizione tolta e
 * controllata" quando il file e' pronto e verificato. [Idle][BackupShareUi.Idle] e il rifiuto non disegnano niente qui (il rifiuto ha il suo foglio).
 */
@Composable
internal fun BackupShareNotice(share: BackupShareUi, backdrop: GlassBackdropState, onCancel: () -> Unit, modifier: Modifier = Modifier) {
  when (share) {
    BackupShareUi.Idle, is BackupShareUi.Refused -> Unit
    is BackupShareUi.Preparing -> GalleryViewerNotice(
      icon = Icons.Rounded.IosShare,
      title = stringResource(if (share.removingLocation) R.string.viewer_backup_share_preparing_no_location else R.string.viewer_backup_share_preparing),
      detail = if (share.total > 1) stringResource(R.string.viewer_backup_share_preparing_detail, share.done.coerceAtMost(share.total), share.total) else null,
      progress = if (share.total > 0) GalleryNoticeProgress.Determinate((share.done.toFloat() / share.total).coerceIn(0f, 1f)) else GalleryNoticeProgress.Indeterminate,
      backdrop = backdrop,
      modifier = modifier,
      actionLabel = stringResource(R.string.viewer_backup_share_cancel),
      onAction = onCancel,
    )
    is BackupShareUi.Ready -> GalleryViewerNotice(
      icon = Icons.Rounded.CheckCircle,
      title = if (share.verified > 0) {
        pluralStringResource(R.plurals.viewer_backup_share_ready_title, share.verified, share.verified)
      } else {
        stringResource(R.string.viewer_backup_share_ready_direct_title)
      },
      detail = if (share.verified > 0) stringResource(R.string.viewer_backup_share_ready_detail) else null,
      backdrop = backdrop,
      modifier = modifier,
    )
  }
}

/** Una frase breve dopo un'azione (la sessione di "Modifica con…", un esito): una riga sopra la foto, che si spegne da sola. */
@Composable
internal fun BackupNoticeView(notice: BackupNotice, backdrop: GlassBackdropState, modifier: Modifier = Modifier) {
  GalleryViewerNotice(
    icon = if (notice.ok) Icons.Rounded.CheckCircle else Icons.Rounded.ReportProblem,
    title = notice.text,
    backdrop = backdrop,
    modifier = modifier,
  )
}

/**
 * Il foglio di una condivisione ferma (D26, L41): la frase del motivo e, se si puo', la scelta esplicita "Condividi con la posizione"; altrimenti solo
 * "Ho capito". **Un file con la posizione non parte mai da solo**: nemmeno chiudendo il foglio.
 */
@Composable
internal fun BackupShareRefusedSheet(
  state: BackupShareUi.Refused?,
  onShareWithLocation: () -> Unit,
  onDismiss: () -> Unit,
) {
  // Il foglio che si chiude fa la sua uscita con la frase che aveva.
  var shown by remember { mutableStateOf<BackupShareUi.Refused?>(null) }
  if (state != null) shown = state
  val showing = shown
  GalleryConfirmSheet(visible = state != null, onDismissRequest = onDismiss, paneTitle = stringResource(R.string.viewer_backup_share_stopped_title)) {
    if (showing != null) BackupShareRefusedContent(showing, onShareWithLocation, onDismiss)
  }
}

/** Il contenuto del foglio (anche per gli screenshot e i test, senza il pop-up). */
@Composable
internal fun BackupShareRefusedContent(state: BackupShareUi.Refused, onShareWithLocation: () -> Unit, onDismiss: () -> Unit) {
  GallerySheetScaffold(
    icon = Icons.Rounded.LocationOff,
    tone = GallerySheetTone.Caution,
    title = stringResource(R.string.viewer_backup_share_stopped_title),
    message = state.message,
    actions = {
      if (state.canShareWithLocation) {
        FluidButton(
          text = stringResource(R.string.viewer_backup_share_with_location),
          onClick = onShareWithLocation,
          style = FluidButtonStyle.Tinted,
          size = FluidButtonSize.Large,
          fillWidth = true,
        )
        FluidButton(text = stringResource(R.string.viewer_backup_share_cancel_sheet), onClick = onDismiss, size = FluidButtonSize.Large, fillWidth = true)
      } else {
        FluidButton(text = stringResource(R.string.viewer_backup_share_understood), onClick = onDismiss, size = FluidButtonSize.Large, fillWidth = true)
      }
    },
  )
}

/**
 * Le righe in piu' del foglio "Elimina…" (E-D15): con "Ovunque" vanno nel cestino del NAS anche le versioni precedenti, e il foglio le **elenca** prima di
 * confermare; con "Solo dal telefono" restano dove sono. Si aggiungono alle posizioni di `GalleryDeleteSheetContent`: lo stato ("Verra' eliminata" o
 * "Resta") lo dice il design system.
 */
@Composable
internal fun everywhereLocations(summary: EverywhereSummary?): List<GalleryDeleteLocation> {
  if (summary == null || summary.isEmpty) return emptyList()
  return buildList {
    if (summary.previousVersions > 0) {
      add(
        GalleryDeleteLocation(
          title = pluralStringResource(R.plurals.viewer_backup_everywhere_versions, summary.previousVersions, summary.previousVersions),
          kind = GalleryDeleteLocationKind.NasPrivate,
          detail = stringResource(R.string.viewer_backup_everywhere_versions_detail),
        ),
      )
    }
    if (summary.commonCopies > 0) {
      add(
        GalleryDeleteLocation(
          title = pluralStringResource(R.plurals.viewer_backup_everywhere_common, summary.commonCopies, summary.commonCopies),
          kind = GalleryDeleteLocationKind.NasShared,
          detail = stringResource(R.string.viewer_backup_everywhere_common_detail),
        ),
      )
    }
  }
}
