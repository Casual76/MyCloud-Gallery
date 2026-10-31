package dev.pampa.mycloudgallery.feature.map.regions

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.NetworkCell
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import dev.antigravity.fluidengine.ui.fluid.FluidButton
import dev.antigravity.fluidengine.ui.fluid.FluidButtonSize
import dev.antigravity.fluidengine.ui.fluid.FluidButtonStyle
import dev.pampa.mycloudgallery.core.designsystem.sheet.GalleryConfirmSheet
import dev.pampa.mycloudgallery.core.designsystem.sheet.GallerySheetScaffold
import dev.pampa.mycloudgallery.core.designsystem.sheet.GallerySheetTone
import dev.pampa.mycloudgallery.core.map.contract.RegionDownloadConfirmation
import dev.pampa.mycloudgallery.core.map.contract.RegionId
import dev.pampa.mycloudgallery.core.map.contract.RegionStatus
import dev.pampa.mycloudgallery.feature.map.R

// IL FOGLIO DI CONFERMA DELLO SCARICAMENTO (Fase 9, P5; docs/fase9-progetto.md §2.9, D26; Phase9ArchitectureTest regola 5). **Solo questo file costruisce `RegionDownloadConfirmation`**, e solo dal tocco su
// "Scarica" (o "Aggiorna", o "Scarica con i dati mobili"): nessun altro codice puo' far partire uno scaricamento da solo. Il foglio dice cosa succede ("circa 27 MB, sul Wi-Fi; OpenFreeMap vedra'
// quale zona scarichi, una volta") e, sui dati mobili, chiede una seconda conferma con la dimensione. La gestione di una zona gia' scaricata (riprendi, aggiorna, elimina) sta qui accanto.

internal object RegionDownloadSheet {

  /** La conferma di uno scaricamento, **dal tocco**: [estimateBytes] la stima mostrata sul foglio, [mobile] la seconda conferma sui dati mobili, [nowMs] l'ora monotona (vale 10 minuti). */
  fun confirmation(region: RegionId, estimateBytes: Long, mobile: Boolean, nowMs: Long): RegionDownloadConfirmation =
    RegionDownloadConfirmation(region, estimateBytes, mobile, nowMs)
}

/** Cosa i fogli chiedono alla pagina. */
internal interface RegionSheetHandlers {
  /** "Scarica", "Aggiorna" o "Scarica con i dati mobili": il tocco che costruisce la conferma. */
  fun onConfirm()

  fun onDismiss()

  fun onResume()

  fun onUpdate()

  fun onAskDelete()

  fun onDelete()
}

/** Il foglio di conferma (e quello di gestione): uno solo e' aperto. */
@Composable
internal fun RegionSheets(sheet: RegionSheet?, handlers: RegionSheetHandlers) {
  val resources = LocalContext.current.resources
  when (sheet) {
    is RegionSheet.Confirm -> {
      val name = RegionText.title(resources, sheet.spec)
      val size = RegionText.approx(resources, sheet.estimate.bytes)
      val mobile = sheet.mobileBytes != null
      val title = when {
        mobile -> stringResource(R.string.regions_sheet_mobile_title)
        sheet.purpose == SheetPurpose.UPDATE -> stringResource(R.string.regions_sheet_update_title, name)
        sheet.purpose == SheetPurpose.RESUME -> stringResource(R.string.regions_sheet_resume_title, name)
        else -> stringResource(R.string.regions_sheet_title, name)
      }
      val message = when {
        mobile -> stringResource(R.string.regions_sheet_mobile_message, name, RegionText.approx(resources, sheet.mobileBytes ?: sheet.estimate.bytes))
        sheet.purpose == SheetPurpose.UPDATE -> stringResource(R.string.regions_sheet_update_message, size)
        sheet.purpose == SheetPurpose.RESUME -> stringResource(R.string.regions_sheet_resume_message, size)
        else -> stringResource(R.string.regions_sheet_message, size.replaceFirstChar { it.uppercase() }, RegionText.detail(resources, sheet.estimate.maxZoom).replaceFirstChar { it.uppercase() })
      }
      GalleryConfirmSheet(visible = true, onDismissRequest = handlers::onDismiss, paneTitle = stringResource(R.string.regions_sheet_pane)) {
        GallerySheetScaffold(
          icon = if (mobile) Icons.Rounded.NetworkCell else Icons.Rounded.CloudDownload,
          tone = if (mobile) GallerySheetTone.Caution else GallerySheetTone.Brand,
          title = title,
          message = message,
          actions = {
            FluidButton(
              text = stringResource(
                when {
                  mobile -> R.string.regions_sheet_mobile_confirm
                  sheet.purpose == SheetPurpose.UPDATE -> R.string.regions_sheet_update
                  sheet.purpose == SheetPurpose.RESUME -> R.string.regions_sheet_resume
                  else -> R.string.regions_sheet_download
                },
              ),
              onClick = handlers::onConfirm,
              size = FluidButtonSize.Large,
              fillWidth = true,
            )
            FluidButton(text = stringResource(R.string.regions_sheet_cancel), onClick = handlers::onDismiss, style = FluidButtonStyle.Tinted, size = FluidButtonSize.Large, fillWidth = true)
          },
        )
      }
    }
    is RegionSheet.Manage -> RegionManageSheet(sheet, handlers)
    null -> Unit
  }
}

@Composable
private fun RegionManageSheet(sheet: RegionSheet.Manage, handlers: RegionSheetHandlers) {
  val resources = LocalContext.current.resources
  val state = sheet.state
  val name = RegionText.title(resources, state.spec)
  val size = RegionText.size(resources, state.bytes)
  GalleryConfirmSheet(visible = true, onDismissRequest = handlers::onDismiss, paneTitle = stringResource(R.string.regions_sheet_pane)) {
    if (sheet.confirmDelete) {
      GallerySheetScaffold(
        icon = Icons.Rounded.DeleteForever,
        tone = GallerySheetTone.Destructive,
        title = stringResource(R.string.regions_delete_title, name),
        message = stringResource(R.string.regions_delete_message, size),
        actions = {
          FluidButton(text = stringResource(R.string.regions_delete_confirm), onClick = handlers::onDelete, style = FluidButtonStyle.Destructive, size = FluidButtonSize.Large, fillWidth = true)
          FluidButton(text = stringResource(R.string.regions_sheet_cancel), onClick = handlers::onDismiss, style = FluidButtonStyle.Tinted, size = FluidButtonSize.Large, fillWidth = true)
        },
      )
    } else {
      val message = when (state.status) {
        RegionStatus.READY -> stringResource(R.string.regions_manage_ready, size)
        RegionStatus.NEEDS_UPDATE -> stringResource(R.string.regions_manage_needs_update, size)
        RegionStatus.PARTIAL -> stringResource(R.string.regions_manage_partial, state.percent) + " " + RegionText.stop(resources, state.stop).replaceFirstChar { it.uppercase() } + "."
        RegionStatus.DOWNLOADING -> stringResource(R.string.regions_manage_downloading, state.percent)
      }
      GallerySheetScaffold(
        icon = Icons.Rounded.CloudDownload,
        tone = GallerySheetTone.Brand,
        title = stringResource(R.string.regions_manage_title, name),
        message = message,
        actions = {
          val resumable = state.status == RegionStatus.PARTIAL && state.stop?.terminal != true
          if (resumable) FluidButton(text = stringResource(R.string.regions_sheet_resume), onClick = handlers::onResume, size = FluidButtonSize.Large, fillWidth = true)
          if (state.status == RegionStatus.READY || state.status == RegionStatus.NEEDS_UPDATE) {
            FluidButton(text = stringResource(R.string.regions_sheet_update), onClick = handlers::onUpdate, size = FluidButtonSize.Large, fillWidth = true)
          }
          FluidButton(text = stringResource(R.string.regions_manage_delete), onClick = handlers::onAskDelete, style = FluidButtonStyle.Destructive, size = FluidButtonSize.Large, fillWidth = true)
          FluidButton(text = stringResource(R.string.regions_manage_close), onClick = handlers::onDismiss, style = FluidButtonStyle.Tinted, size = FluidButtonSize.Large, fillWidth = true)
        },
      )
    }
  }
}
