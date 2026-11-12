package dev.pampa.mycloudgallery.feature.update.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.InstallMobile
import androidx.compose.material.icons.rounded.NewReleases
import androidx.compose.material.icons.rounded.SignalCellularAlt
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import dev.antigravity.fluidengine.foundation.UpdateChannel
import dev.antigravity.fluidengine.ui.fluid.FluidButton
import dev.antigravity.fluidengine.ui.fluid.FluidButtonSize
import dev.antigravity.fluidengine.ui.fluid.FluidButtonStyle
import dev.antigravity.fluidengine.ui.fluid.FluidUpdate
import dev.pampa.mycloudgallery.core.designsystem.icon.GalleryIconFamily
import dev.pampa.mycloudgallery.core.designsystem.icon.GalleryIconStatus
import dev.pampa.mycloudgallery.core.designsystem.icon.GalleryIconTile
import dev.pampa.mycloudgallery.core.designsystem.media.GalleryProgressRing
import dev.pampa.mycloudgallery.core.designsystem.media.GalleryProgressRingDefaults
import dev.pampa.mycloudgallery.core.designsystem.media.GalleryRingPercent
import dev.pampa.mycloudgallery.core.designsystem.media.galleryRingColor
import dev.pampa.mycloudgallery.feature.update.AppUpdates
import dev.pampa.mycloudgallery.feature.update.CheckState
import dev.pampa.mycloudgallery.feature.update.R
import dev.pampa.mycloudgallery.feature.update.UpdatesState
import dev.pampa.mycloudgallery.feature.update.install.InstallStep

// LA FIGURA DELL'AGGIORNAMENTO (pacchetto U). Il metro dell'utente: «se non sapessi leggere potrei usarla?». Una figura che si capisce da sola (la
// piastrella con il suo segno: la spunta, il punto esclamativo, la croce; l'anello comune della galleria che si riempie mentre scarica), due parole
// che la nominano, un tasto pieno quando c'e' una cosa da fare. Lo stesso pezzo nella pagina, nell'avviso e nella pagina «versione fermata».

/** Le azioni dell'aggiornamento come lambda: le prove e gli screenshot passano le loro. */
class UpdateActions(
  val check: () -> Unit = {},
  val install: () -> Unit = {},
  val downloadMetered: () -> Unit = {},
  val cancelMetered: () -> Unit = {},
  val allow: () -> Unit = {},
  val cancelDownload: () -> Unit = {},
  val close: () -> Unit = {},
  val chooseChannel: (UpdateChannel) -> Unit = {},
  val later: () -> Unit = {},
) {
  companion object {
    val None: UpdateActions = UpdateActions()

    /** Le azioni vere sopra [updates]. */
    fun of(updates: AppUpdates): UpdateActions = UpdateActions(
      check = { updates.checkNow() },
      install = { updates.install() },
      downloadMetered = { updates.install(acceptMetered = true) },
      cancelMetered = updates::cancelMetered,
      allow = updates::openInstallPermission,
      cancelDownload = updates::cancelInstall,
      close = updates::dismissFailure,
      chooseChannel = updates::chooseChannel,
      later = updates::dismissPrompt,
    )
  }
}

/**
 * La figura dell'aggiornamento in grande, centrata: la testata della pagina Aggiornamenti e il cuore della pagina «versione fermata».
 * [showCheck] falso toglie «Controlla adesso» (chi la mostra ha gia' il suo).
 */
@Composable
fun UpdatePanel(state: UpdatesState, actions: UpdateActions, modifier: Modifier = Modifier, showCheck: Boolean = true) {
  val model = UpdatePanels.of(state).let { if (!showCheck && it.primary == UpdateAction.CHECK) it.copy(primary = null) else it }
  val words = panelWords(state, model)
  Column(
    modifier = modifier.fillMaxWidth().padding(UpdateDimens.HeroPadding),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(UpdateDimens.Gap),
  ) {
    UpdateFigureView(model, state, hero = true, spoken = words.spoken)
    if (words.headline != null) {
      Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(UpdateDimens.TightGap),
        modifier = Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
      ) {
        Text(
          text = words.headline,
          style = MaterialTheme.typography.headlineSmall,
          fontWeight = FontWeight.Bold,
          color = MaterialTheme.colorScheme.onSurface,
          textAlign = TextAlign.Center,
          modifier = Modifier.semantics { heading() },
        )
        words.detail?.let { detail ->
          Text(text = detail, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        }
      }
    }
    PanelButtons(model, state, actions, fillWidth = true)
  }
}

/** I due tasti del modello: il pieno sopra, il leggero sotto. */
@Composable
internal fun PanelButtons(model: UpdatePanelModel, state: UpdatesState, actions: UpdateActions, fillWidth: Boolean) {
  val size = state.found?.sizeBytes ?: 0L
  model.primary?.let { action ->
    FluidButton(
      text = actionLabel(action, size),
      onClick = actionFor(action, actions),
      style = FluidButtonStyle.Filled,
      size = FluidButtonSize.Large,
      fillWidth = fillWidth,
      leading = if (action == UpdateAction.INSTALL || action == UpdateAction.DOWNLOAD_METERED) {
        { Icon(Icons.Rounded.Download, contentDescription = null) }
      } else {
        null
      },
    )
  }
  model.secondary?.let { action ->
    FluidButton(text = actionLabel(action, size), onClick = actionFor(action, actions), style = FluidButtonStyle.Plain, size = FluidButtonSize.Medium)
  }
}

@Composable
private fun actionLabel(action: UpdateAction, sizeBytes: Long): String =
  if (action == UpdateAction.DOWNLOAD_METERED) {
    stringResource(action.label, FluidUpdate.sizeLabel(sizeBytes).orEmpty()).trim()
  } else {
    stringResource(action.label)
  }

internal fun actionFor(action: UpdateAction, actions: UpdateActions): () -> Unit = when (action) {
  UpdateAction.CHECK -> actions.check
  UpdateAction.INSTALL -> actions.install
  UpdateAction.DOWNLOAD_METERED -> actions.downloadMetered
  UpdateAction.CANCEL_METERED -> actions.cancelMetered
  UpdateAction.ALLOW -> actions.allow
  UpdateAction.CANCEL_DOWNLOAD -> actions.cancelDownload
  UpdateAction.RETRY -> actions.install
  UpdateAction.CLOSE -> actions.close
}

/**
 * Le parole della figura: il titolo (null = nessuno: prima di ogni controllo bastano la figura e «Controlla adesso»), il dettaglio (una riga) e cio'
 * che sente TalkBack sulla figura. La versione installata NON e' qui: la pagina la dice una volta, nella sua riga.
 */
internal class PanelWords(val headline: String?, val detail: String?, val spoken: String)

@Composable
internal fun panelWords(state: UpdatesState, model: UpdatePanelModel): PanelWords {
  val resources = LocalContext.current.resources
  val found = (state.check as? CheckState.Found)?.update ?: (state.check as? CheckState.Unusable)?.update
  val size = found?.sizeBytes?.let { FluidUpdate.sizeLabel(it) }
  val version = found?.version.orEmpty()
  val (headline, detail) = when (model.figure) {
    UpdateFigure.IDLE -> null to null
    UpdateFigure.CHECKING -> resources.getString(R.string.update_checking) to null
    UpdateFigure.UP_TO_DATE -> resources.getString(R.string.update_up_to_date) to null
    UpdateFigure.UNAVAILABLE -> resources.getString(R.string.update_unavailable) to null
    UpdateFigure.FOUND -> resources.getString(R.string.update_found, version) to size
    UpdateFigure.UNUSABLE -> resources.getString(R.string.update_unusable, version) to resources.getString(R.string.update_unusable_detail)
    UpdateFigure.METERED -> resources.getString(R.string.update_metered) to size
    UpdateFigure.PERMISSION -> resources.getString(R.string.update_permission) to null
    UpdateFigure.DOWNLOADING -> resources.getString(R.string.update_downloading) to size
    UpdateFigure.WORKING -> resources.getString(
      if (state.install == InstallStep.Installing) R.string.update_installing else R.string.update_verifying,
    ) to null
    UpdateFigure.AWAITING -> resources.getString(R.string.update_awaiting) to null
    UpdateFigure.INSTALLED -> resources.getString(R.string.update_installed) to null
    UpdateFigure.FAILED -> {
      val failed = state.install as? InstallStep.Failed
      val text = failed?.detail ?: model.problem?.let { resources.getString(UpdatePanels.problemText(it)) }
      resources.getString(R.string.update_failed) to text
    }
  }
  val spoken = when (model.figure) {
    UpdateFigure.DOWNLOADING -> resources.getString(R.string.update_progress_spoken, FluidUpdate.percent(model.progress ?: 0f))
    UpdateFigure.IDLE -> resources.getString(R.string.update_title)
    else -> listOfNotNull(headline, detail).joinToString(". ")
  }
  return PanelWords(headline, detail, spoken)
}

/** La figura: l'anello mentre lavora (che si riempie mentre scarica), altrimenti la piastrella con il suo segno. */
@Composable
internal fun UpdateFigureView(model: UpdatePanelModel, state: UpdatesState, hero: Boolean, spoken: String) {
  val ringSize: Dp = if (hero) UpdateDimens.HeroRing else UpdateDimens.PromptRing
  val tileSize: Dp = if (hero) UpdateDimens.HeroTile else UpdateDimens.PromptTile
  val glyphSize: Dp = if (hero) UpdateDimens.HeroGlyph else UpdateDimens.PromptGlyph
  val figureSemantics = Modifier.clearAndSetSemantics { contentDescription = spoken }
  when (model.figure) {
    UpdateFigure.CHECKING, UpdateFigure.WORKING, UpdateFigure.DOWNLOADING, UpdateFigure.AWAITING -> {
      val color = galleryRingColor(GalleryIconFamily.Photos)
      val progress = when (model.figure) {
        UpdateFigure.DOWNLOADING -> model.progress ?: 0f
        UpdateFigure.AWAITING -> 1f
        else -> null
      }
      GalleryProgressRing(
        progress = progress,
        color = color,
        strokeWidth = GalleryProgressRingDefaults.stroke(ringSize),
        animate = true,
        modifier = Modifier.size(ringSize).then(figureSemantics),
      ) {
        when (model.figure) {
          UpdateFigure.DOWNLOADING -> GalleryRingPercent(
            model.progress ?: 0f,
            if (hero) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.labelMedium,
          )
          UpdateFigure.AWAITING -> RingGlyph(Icons.Rounded.TouchApp, glyphSize)
          UpdateFigure.CHECKING -> RingGlyph(Icons.Rounded.SystemUpdate, glyphSize)
          else -> RingGlyph(if (state.install == InstallStep.Installing) Icons.Rounded.InstallMobile else Icons.Rounded.Verified, glyphSize)
        }
      }
    }
    else -> {
      val (icon, family, status) = tileOf(model.figure)
      Box(modifier = figureSemantics) {
        GalleryIconTile(icon = icon, family = family, size = tileSize, status = status)
      }
    }
  }
}

@Composable
private fun RingGlyph(icon: ImageVector, size: Dp) {
  Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(size))
}

/** La piastrella di una figura ferma: il glifo, la famiglia (l'Ametista dell'app, la rete per i dati mobili, la sicurezza per il permesso), il segno. */
internal fun tileOf(figure: UpdateFigure): Triple<ImageVector, GalleryIconFamily, GalleryIconStatus?> = when (figure) {
  UpdateFigure.IDLE -> Triple(Icons.Rounded.SystemUpdate, GalleryIconFamily.Photos, null)
  UpdateFigure.UP_TO_DATE -> Triple(Icons.Rounded.SystemUpdate, GalleryIconFamily.Photos, GalleryIconStatus.Done)
  UpdateFigure.UNAVAILABLE -> Triple(Icons.Rounded.CloudOff, GalleryIconFamily.Photos, GalleryIconStatus.Waiting)
  UpdateFigure.FOUND -> Triple(Icons.Rounded.NewReleases, GalleryIconFamily.Photos, GalleryIconStatus.Attention)
  UpdateFigure.UNUSABLE -> Triple(Icons.Rounded.NewReleases, GalleryIconFamily.Photos, GalleryIconStatus.Problem)
  UpdateFigure.METERED -> Triple(Icons.Rounded.SignalCellularAlt, GalleryIconFamily.Network, GalleryIconStatus.Attention)
  UpdateFigure.PERMISSION -> Triple(Icons.Rounded.InstallMobile, GalleryIconFamily.Security, GalleryIconStatus.Attention)
  UpdateFigure.INSTALLED -> Triple(Icons.Rounded.SystemUpdate, GalleryIconFamily.Photos, GalleryIconStatus.Done)
  UpdateFigure.FAILED -> Triple(Icons.Rounded.SystemUpdate, GalleryIconFamily.Photos, GalleryIconStatus.Problem)
  UpdateFigure.CHECKING, UpdateFigure.WORKING, UpdateFigure.DOWNLOADING, UpdateFigure.AWAITING ->
    Triple(Icons.Rounded.SystemUpdate, GalleryIconFamily.Photos, GalleryIconStatus.Running)
}

/** Il riassunto della nota di rilascio: il primo paragrafo, senza Markdown (`FluidUpdate.notesSummary`), vuoto se non c'e'. */
internal fun notesOf(state: UpdatesState): String = state.found?.changelog?.let { FluidUpdate.notesSummary(it) }.orEmpty()

/** Le note senza la prima riga se e' il titolo del riquadro (il `### Novità` del changelog): «Novità» non si legge due volte. */
internal fun withoutHeading(notes: String, heading: String): String {
  val lines = notes.lines()
  val first = lines.firstOrNull()?.trim().orEmpty()
  return if (first.equals(heading, ignoreCase = true)) lines.drop(1).joinToString("\n").trim() else notes
}

/** Una riga corta per l'avviso: «1.1.0 · 112 MB». */
internal fun shortLine(state: UpdatesState): String {
  val found = state.found ?: return ""
  return listOfNotNull(found.version, FluidUpdate.sizeLabel(found.sizeBytes)).joinToString(" · ")
}
