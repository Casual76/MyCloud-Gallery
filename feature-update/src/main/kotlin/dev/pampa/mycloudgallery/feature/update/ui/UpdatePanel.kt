package dev.pampa.mycloudgallery.feature.update.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.InstallMobile
import androidx.compose.material.icons.rounded.SignalCellularAlt
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material.icons.rounded.ToggleOn
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import dev.antigravity.fluidengine.foundation.UpdateChannel
import dev.antigravity.fluidengine.ui.fluid.FluidButton
import dev.antigravity.fluidengine.ui.fluid.FluidButtonSize
import dev.antigravity.fluidengine.ui.fluid.FluidButtonStyle
import dev.antigravity.fluidengine.ui.fluid.FluidUpdate
import dev.pampa.mycloudgallery.core.designsystem.design.GalleryInfoButton
import dev.pampa.mycloudgallery.core.designsystem.design.GalleryInfoDetail
import dev.pampa.mycloudgallery.core.designsystem.icon.GalleryIconFamily
import dev.pampa.mycloudgallery.core.designsystem.icon.GalleryIconStatus
import dev.pampa.mycloudgallery.core.designsystem.icon.GalleryIconTile
import dev.pampa.mycloudgallery.core.designsystem.icon.GalleryIconTileDefaults
import dev.pampa.mycloudgallery.core.designsystem.icon.GalleryStatusMark
import dev.pampa.mycloudgallery.core.designsystem.media.GalleryProgressRing
import dev.pampa.mycloudgallery.core.designsystem.media.GalleryProgressRingDefaults
import dev.pampa.mycloudgallery.core.designsystem.media.GalleryRingPercent
import dev.pampa.mycloudgallery.core.designsystem.media.galleryRingColor
import dev.pampa.mycloudgallery.feature.update.AppUpdates
import dev.pampa.mycloudgallery.feature.update.CheckState
import dev.pampa.mycloudgallery.feature.update.R
import dev.pampa.mycloudgallery.feature.update.UpdatesState
import dev.pampa.mycloudgallery.feature.update.install.InstallStep
import java.util.Locale

// LA FIGURA DELL'AGGIORNAMENTO (pacchetto U; giro 1 delle critiche). Il metro dell'utente: «se non sapessi leggere potrei usarla?». La figura e' l'icona
// VERA dell'app (quella scelta in «Icona dell'app», data da `:app` con [LocalUpdateAppIcon]) con il segno nell'angolo: il punto esclamativo per una
// novita' e per «pronta» (c'e' da toccare «Installa»: giro 2, UU2-3), la spunta solo per «aggiornata», la clessidra per «non ancora», la croce per un
// guasto; l'anello comune della galleria mentre lavora (alla misura dell'icona mentre controlla, perche' la pagina non salti: UU2-9);
// l'interruttore quando serve il permesso. Due parole la nominano, un tasto la risolve. Lo stesso pezzo nella pagina, nell'avviso e nella pagina
// «versione fermata».

/** L'icona dell'app come la vede il launcher, alla misura data. Null (le prove di questo modulo) = la piastrella dell'aggiornamento. */
val LocalUpdateAppIcon = staticCompositionLocalOf<(@Composable (Dp) -> Unit)?> { null }

/** Le azioni dell'aggiornamento come lambda: le prove e gli screenshot passano le loro. */
class UpdateActions(
  val check: () -> Unit = {},
  val install: () -> Unit = {},
  val downloadMetered: () -> Unit = {},
  val cancelMetered: () -> Unit = {},
  val allow: () -> Unit = {},
  val cancelDownload: () -> Unit = {},
  val cancelReady: () -> Unit = {},
  val close: () -> Unit = {},
  val chooseChannel: (UpdateChannel) -> Unit = {},
  val later: () -> Unit = {},
  val hide: () -> Unit = {},
) {
  companion object {
    val None: UpdateActions = UpdateActions()

    /** Le azioni vere sopra [updates]. «Aggiorna», «Riprova» e «Installa» sono lo stesso tocco: `install` usa il file pronto se c'e'. */
    fun of(updates: AppUpdates): UpdateActions = UpdateActions(
      check = { updates.checkNow() },
      install = { updates.install() },
      downloadMetered = { updates.install(acceptMetered = true) },
      cancelMetered = updates::cancelMetered,
      allow = updates::openInstallPermission,
      cancelDownload = updates::cancelInstall,
      cancelReady = updates::cancelReady,
      close = updates::dismiss,
      chooseChannel = updates::chooseChannel,
      later = updates::later,
      hide = updates::hidePrompt,
    )
  }
}

/**
 * La figura dell'aggiornamento in grande, centrata: la testata della pagina Aggiornamenti e il cuore della pagina «versione fermata» ([stopped]).
 * [showCheck] falso toglie «Controlla adesso» (chi la mostra ha gia' il suo).
 */
@Composable
fun UpdatePanel(state: UpdatesState, actions: UpdateActions, modifier: Modifier = Modifier, showCheck: Boolean = true, stopped: Boolean = false) {
  val model = UpdatePanels.of(state, stopped).let { if (!showCheck && it.primary == UpdateAction.CHECK) it.copy(primary = null) else it }
  val words = panelWords(state, model)
  var whyOpen by rememberSaveable(words.why) { mutableStateOf(false) }
  Column(
    modifier = modifier.fillMaxWidth().padding(UpdateDimens.HeroPadding),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(UpdateDimens.Gap),
  ) {
    UpdateFigureView(model, hero = true, step = state.install)
    Column(
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(UpdateDimens.TightGap),
      modifier = Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
    ) {
      Text(
        text = words.headline,
        style = MaterialTheme.typography.headlineSmall,
        color = MaterialTheme.colorScheme.onSurface,
        textAlign = TextAlign.Center,
        modifier = Modifier.semantics { heading() },
      )
      words.detail?.let { detail ->
        Text(text = detail, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
      }
    }
    words.why?.let { why ->
      GalleryInfoButton(label = why, open = whyOpen, onToggle = { whyOpen = !whyOpen })
      GalleryInfoDetail(text = why, visible = whyOpen, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
    PanelButtons(model, actions, fillWidth = true)
  }
}

/**
 * I due tasti del modello: il principale sopra (pieno, o leggero quando non e' la cosa da fare), il leggero sotto. Alti almeno 48 dp. Il pieno a tutta
 * larghezza al piu' largo come l'avviso: sul tablet in orizzontale non diventa una barra di 700 dp (UU2-9).
 */
@Composable
internal fun PanelButtons(model: UpdatePanelModel, actions: UpdateActions, fillWidth: Boolean) {
  model.primary?.let { action ->
    FluidButton(
      text = stringResource(action.label),
      onClick = actionFor(action, actions),
      modifier = Modifier.widthIn(max = UpdateDimens.PromptMaxWidth),
      style = if (model.primaryFilled) FluidButtonStyle.Filled else FluidButtonStyle.Plain,
      size = FluidButtonSize.Large,
      fillWidth = fillWidth && model.primaryFilled,
      leading = if (action == UpdateAction.INSTALL || action == UpdateAction.DOWNLOAD_METERED) {
        { Icon(Icons.Rounded.Download, contentDescription = null) }
      } else {
        null
      },
    )
  }
  model.secondary?.let { action ->
    FluidButton(text = stringResource(action.label), onClick = actionFor(action, actions), style = FluidButtonStyle.Plain, size = FluidButtonSize.Large)
  }
}

internal fun actionFor(action: UpdateAction, actions: UpdateActions): () -> Unit = when (action) {
  UpdateAction.CHECK, UpdateAction.RECHECK -> actions.check
  UpdateAction.INSTALL, UpdateAction.INSTALL_READY, UpdateAction.RETRY -> actions.install
  UpdateAction.CANCEL_READY -> actions.cancelReady
  UpdateAction.DOWNLOAD_METERED -> actions.downloadMetered
  UpdateAction.CANCEL_METERED -> actions.cancelMetered
  UpdateAction.ALLOW -> actions.allow
  UpdateAction.CANCEL_DOWNLOAD -> actions.cancelDownload
  UpdateAction.CLOSE -> actions.close
}

/** Le parole della figura: il titolo, il dettaglio (una riga) e il perche' dietro la «i» (solo per il permesso). */
internal class PanelWords(val headline: String, val detail: String?, val why: String?)

@Composable
internal fun panelWords(state: UpdatesState, model: UpdatePanelModel): PanelWords {
  val resources = LocalContext.current.resources
  val offer = (state.check as? CheckState.Found)?.update ?: (state.check as? CheckState.Unusable)?.update
  val size = offer?.sizeBytes?.let(::sizeText)
  val installed = resources.getString(R.string.update_current, state.installedVersion)
  val closes = resources.getString(R.string.update_closes)
  var why: String? = null
  val (headline, detail) = when (model.figure) {
    UpdateFigure.IDLE -> installed to null
    UpdateFigure.CHECKING -> resources.getString(R.string.update_checking) to null
    UpdateFigure.UP_TO_DATE -> resources.getString(R.string.update_up_to_date) to installed
    UpdateFigure.UNAVAILABLE -> resources.getString(R.string.update_unavailable) to installed
    UpdateFigure.FOUND -> resources.getString(R.string.update_found) to
      resources.getString(R.string.update_found_detail, state.installedVersion, offer?.version.orEmpty(), size.orEmpty()).removeSuffix(" · ")
    // La clessidra dice gia' «aspetta»: niente «Riprova domani» accanto a «Controlla adesso» (UU2-10).
    UpdateFigure.UNUSABLE -> resources.getString(R.string.update_unusable) to null
    UpdateFigure.COMING -> resources.getString(R.string.update_coming) to null
    UpdateFigure.METERED -> resources.getString(R.string.update_metered) to size
    UpdateFigure.PERMISSION -> {
      why = resources.getString(R.string.update_permission_why)
      resources.getString(R.string.update_permission) to resources.getString(R.string.update_permission_detail)
    }
    UpdateFigure.DOWNLOADING -> resources.getString(R.string.update_downloading) to size
    UpdateFigure.WORKING -> when (state.install) {
      InstallStep.Installing -> resources.getString(R.string.update_installing) to closes
      InstallStep.Verifying -> resources.getString(R.string.update_verifying) to null
      else -> resources.getString(R.string.update_downloading) to size
    }
    UpdateFigure.READY -> resources.getString(R.string.update_ready) to
      if ((state.install as? InstallStep.Ready)?.interrupted == true) resources.getString(R.string.update_interrupted) else closes
    UpdateFigure.AWAITING -> resources.getString(R.string.update_awaiting) to closes
    UpdateFigure.INSTALLED -> resources.getString(R.string.update_installed) to null
    UpdateFigure.FAILED -> {
      val problem = model.problem
      val text = problem?.let { resources.getString(UpdatePanels.problemText(it)) }
      val tell = if (problem != null && !problem.retryable) resources.getString(R.string.update_tell_sender) else null
      resources.getString(R.string.update_failed) to listOfNotNull(text, tell).joinToString(". ").ifEmpty { null }
    }
  }
  return PanelWords(headline, detail, why)
}

/**
 * La figura: l'anello mentre lavora (che si riempie mentre scarica), altrimenti l'icona dell'app con il suo segno. Per TalkBack la figura non dice
 * niente (le parole sotto dicono lo stato, una volta sola: UU-11), tranne l'anello dello scaricamento, che e' una barra d'avanzamento vera e annuncia
 * ogni quarto.
 */
@Composable
internal fun UpdateFigureView(model: UpdatePanelModel, hero: Boolean, step: InstallStep? = null) {
  val iconSize: Dp = if (hero) UpdateDimens.HeroIcon else UpdateDimens.PromptIcon
  // «Sto controllando» dura meno di un secondo: l'anello alla misura dell'icona, cosi' la testata non cresce e ricala (UU2-9).
  val checking = model.figure == UpdateFigure.CHECKING
  val ringSize: Dp = if (checking) iconSize else if (hero) UpdateDimens.HeroRing else UpdateDimens.PromptRing
  val glyphSize: Dp = if (hero && !checking) UpdateDimens.HeroGlyph else UpdateDimens.PromptGlyph
  when (model.figure) {
    UpdateFigure.CHECKING, UpdateFigure.WORKING, UpdateFigure.DOWNLOADING, UpdateFigure.AWAITING -> {
      val progress = when (model.figure) {
        UpdateFigure.DOWNLOADING -> model.progress ?: 0f
        UpdateFigure.AWAITING -> 1f
        else -> null
      }
      val ringSemantics = if (model.figure == UpdateFigure.DOWNLOADING) {
        val quarter = (FluidUpdate.percent(progress ?: 0f) / QUARTER) * QUARTER
        val spoken = stringResource(R.string.update_progress_spoken, quarter)
        Modifier.clearAndSetSemantics {
          progressBarRangeInfo = ProgressBarRangeInfo(progress ?: 0f, 0f..1f)
          stateDescription = spoken
          liveRegion = LiveRegionMode.Polite
        }
      } else {
        Modifier.clearAndSetSemantics { }
      }
      GalleryProgressRing(
        progress = progress,
        color = galleryRingColor(GalleryIconFamily.Photos),
        strokeWidth = GalleryProgressRingDefaults.stroke(ringSize),
        animate = true,
        modifier = Modifier.size(ringSize).then(ringSemantics),
      ) {
        when (model.figure) {
          UpdateFigure.DOWNLOADING -> GalleryRingPercent(
            model.progress ?: 0f,
            if (hero) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.labelMedium,
          )
          UpdateFigure.AWAITING -> RingGlyph(Icons.Rounded.TouchApp, glyphSize)
          UpdateFigure.CHECKING -> RingGlyph(Icons.Rounded.SystemUpdate, glyphSize)
          else -> RingGlyph(
            when (step) {
              InstallStep.Installing -> Icons.Rounded.InstallMobile
              InstallStep.Verifying -> Icons.Rounded.Verified
              else -> Icons.Rounded.Download
            },
            glyphSize,
          )
        }
      }
    }
    UpdateFigure.METERED -> Box(Modifier.clearAndSetSemantics { }) {
      GalleryIconTile(icon = Icons.Rounded.SignalCellularAlt, family = GalleryIconFamily.Network, size = iconSize, status = GalleryIconStatus.Attention)
    }
    UpdateFigure.PERMISSION -> Box(Modifier.clearAndSetSemantics { }) {
      GalleryIconTile(icon = Icons.Rounded.ToggleOn, family = GalleryIconFamily.Photos, size = iconSize, status = GalleryIconStatus.Attention)
    }
    else -> AppFigure(size = iconSize, status = statusOf(model.figure))
  }
}

/** Il segno nell'angolo dell'icona dell'app, per ogni figura ferma. */
internal fun statusOf(figure: UpdateFigure): GalleryIconStatus? = when (figure) {
  UpdateFigure.IDLE -> null
  // La spunta verde vuol dire «fatto»: solo per l'app aggiornata. «Pronta» ha il segno di chi deve toccare (UU2-3).
  UpdateFigure.UP_TO_DATE, UpdateFigure.INSTALLED -> GalleryIconStatus.Done
  UpdateFigure.UNAVAILABLE, UpdateFigure.UNUSABLE, UpdateFigure.COMING -> GalleryIconStatus.Waiting
  UpdateFigure.FOUND, UpdateFigure.READY, UpdateFigure.METERED, UpdateFigure.PERMISSION -> GalleryIconStatus.Attention
  UpdateFigure.FAILED -> GalleryIconStatus.Problem
  UpdateFigure.CHECKING, UpdateFigure.WORKING, UpdateFigure.DOWNLOADING, UpdateFigure.AWAITING -> GalleryIconStatus.Running
}

/** L'icona dell'app (o, senza, la piastrella dell'aggiornamento) con il segno [status] nell'angolo. Decorativa: lo stato lo dicono le parole. */
@Composable
internal fun AppFigure(size: Dp, status: GalleryIconStatus?) {
  val appIcon = LocalUpdateAppIcon.current
  Box(modifier = Modifier.clearAndSetSemantics { }) {
    if (appIcon == null) {
      GalleryIconTile(icon = Icons.Rounded.SystemUpdate, family = GalleryIconFamily.Photos, size = size, status = status)
    } else {
      Box(modifier = Modifier.size(size)) {
        appIcon(size)
        if (status != null) {
          val mark = (size * GalleryIconTileDefaults.MarkFraction).coerceIn(GalleryIconTileDefaults.MarkMin, GalleryIconTileDefaults.MarkMax)
          GalleryStatusMark(
            status = status,
            modifier = Modifier.align(Alignment.BottomEnd).offset(x = mark / MARK_OVERHANG, y = mark / MARK_OVERHANG),
            size = mark,
            ring = MaterialTheme.colorScheme.surfaceContainerLow,
            contentDescription = null,
          )
        }
      }
    }
  }
}

@Composable
private fun RingGlyph(icon: ImageVector, size: Dp) {
  Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(size))
}

/** Il peso come lo dice una persona (UU-13): «110 MB» sopra i 10 MB, «8,5 MB» sotto, i KB sotto il mega. Null se non si sa. */
internal fun sizeText(bytes: Long): String? {
  if (bytes <= 0L) return null
  val mb = bytes / MEGA
  return when {
    mb >= 10.0 -> String.format(Locale.ITALIAN, "%.0f MB", mb)
    mb >= 1.0 -> String.format(Locale.ITALIAN, "%.1f MB", mb)
    else -> String.format(Locale.ITALIAN, "%.0f KB", bytes / KILO)
  }
}

/** Il riassunto della nota di rilascio: il primo paragrafo, senza Markdown (`FluidUpdate.notesSummary`), vuoto se non c'e'. */
internal fun notesOf(state: UpdatesState): String = state.found?.changelog?.let { FluidUpdate.notesSummary(it) }.orEmpty()

/** Le note senza la prima riga se e' il titolo del riquadro (il `### Novità` del changelog): «Novità» non si legge due volte. */
internal fun withoutHeading(notes: String, heading: String): String {
  val lines = notes.lines()
  val first = lines.firstOrNull()?.trim().orEmpty()
  return if (first.equals(heading, ignoreCase = true)) lines.drop(1).joinToString("\n").trim() else notes
}

/** Una riga corta per l'avviso: «1.1.0 · 110 MB». */
internal fun shortLine(state: UpdatesState): String {
  val found = state.found ?: return ""
  return listOfNotNull(found.version, sizeText(found.sizeBytes)).joinToString(" · ")
}

private const val QUARTER = 25
private const val MARK_OVERHANG = 5
private const val KILO = 1024.0
private const val MEGA = 1024.0 * 1024.0
