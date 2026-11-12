package dev.pampa.mycloudgallery.feature.backup.page

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import dev.pampa.mycloudgallery.core.designsystem.icon.rememberGalleryStatusColors
import dev.pampa.mycloudgallery.core.designsystem.icon.GalleryIconTileDefaults
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.border
import androidx.compose.material.icons.rounded.HourglassTop
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.FolderShared
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import dev.antigravity.fluidengine.ui.fluid.ContinuousCornerShape
import dev.antigravity.fluidengine.ui.fluid.FluidButton
import dev.antigravity.fluidengine.ui.fluid.FluidButtonSize
import dev.antigravity.fluidengine.ui.fluid.FluidButtonStyle
import dev.antigravity.fluidengine.ui.fluid.FluidMotion
import dev.antigravity.fluidengine.ui.fluid.FluidRadius
import dev.antigravity.fluidengine.ui.fluid.fluidPressable
import dev.pampa.mycloudgallery.core.backup.contract.BackupAction
import dev.pampa.mycloudgallery.core.backup.contract.BackupKind
import dev.pampa.mycloudgallery.core.backup.contract.BackupSetupStep
import dev.pampa.mycloudgallery.core.backup.contract.BackupStatus
import dev.pampa.mycloudgallery.core.backup.contract.BackupTrigger
import dev.pampa.mycloudgallery.core.backup.contract.SourceStatus
import dev.pampa.mycloudgallery.core.backup.contract.StopBy
import dev.pampa.mycloudgallery.core.backup.contract.WaitReason
import dev.pampa.mycloudgallery.core.designsystem.a11y.GalleryLiveAnnouncement
import dev.pampa.mycloudgallery.core.designsystem.icon.GalleryIconFamily
import dev.pampa.mycloudgallery.core.designsystem.icon.GalleryIconStatus
import dev.pampa.mycloudgallery.core.designsystem.icon.GalleryIconTile
import dev.pampa.mycloudgallery.core.designsystem.icon.GalleryStatusMark
import dev.pampa.mycloudgallery.core.designsystem.state.LocalWritesHeldByRelease
import dev.pampa.mycloudgallery.core.designsystem.theme.GalleryDimens
import dev.pampa.mycloudgallery.feature.backup.BackupDimens
import dev.pampa.mycloudgallery.feature.backup.R
import dev.pampa.mycloudgallery.feature.backup.contract.BackupScreen
import dev.pampa.mycloudgallery.feature.backup.ui.BackupActionTexts
import dev.pampa.mycloudgallery.feature.backup.ui.BackupProblemTexts
import dev.pampa.mycloudgallery.feature.backup.ui.BackupText
import dev.pampa.mycloudgallery.feature.backup.ui.atText
import dev.pampa.mycloudgallery.feature.backup.ui.etaText
import dev.pampa.mycloudgallery.core.library.text.GalleryNumbers
import dev.pampa.mycloudgallery.core.designsystem.design.GalleryInfoButton
import dev.pampa.mycloudgallery.core.designsystem.design.GalleryInfoDetail
import dev.pampa.mycloudgallery.core.designsystem.design.GalleryInfoGlyph
import dev.pampa.mycloudgallery.core.designsystem.media.GalleryProgressRing
import dev.pampa.mycloudgallery.core.designsystem.media.GalleryProgressRingDefaults
import dev.pampa.mycloudgallery.core.designsystem.media.galleryRingColor

// LA TESTATA DELLA PAGINA BACKUP (I3, fase I, 11/11). Prima: una scheda con un titolo, una frase lunga e i tasti, e sotto sei righe che ripetevano la
// stessa frase. L'utente: «se non sapessi leggere, potrei usare l'app?». Ora lo stato si vede **in un colpo d'occhio**:
//
//  - un **anello** con quanto e' al sicuro sul totale delle sorgenti attive (il percento grande, i numeri piccoli sotto);
//  - un **segno di stato** sull'anello (spunta, frecce che girano, pausa, trattino, punto esclamativo), con il suo colore e la sua forma;
//  - **un'etichetta** corta («Al sicuro», «In corso», «In pausa») e **al piu' una riga** corta; il perche' lungo sta dietro la «i»;
//  - l'azione principale come **un tasto con l'icona**.
//
// Lo stato «in prova» (recinzione D12) si dice qui, **una volta**: «In pausa · Il backup vero non e' ancora acceso». La recinzione non si tocca: si
// cambia solo come si mostra. Il movimento: l'anello avanza con una molla di `FluidMotion` (scatta con «Rimuovi animazioni»), letta nel disegno.
//
// Giro 1 della fase I (CS-5): l'anello e' quello della galleria (`GalleryProgressRing`, traccia dello stesso colore dell'arco) e la «i» quella di
// core-designsystem (`GalleryInfoButton`, `GalleryInfoGlyph`, `GalleryInfoDetail`): prima ne erano copie fatte qui.

/** Un tasto della testata: l'etichetta, il glifo e il tocco. Il primo e' pieno (la cosa da fare), gli altri tonali. */
@Immutable
internal class HeroAction(val label: String, val icon: ImageVector, val onClick: () -> Unit)

/** Un passo che manca prima di cominciare: il glifo, la famiglia e l'etichetta corta. */
@Immutable
internal class HeroStep(val icon: ImageVector, val family: GalleryIconFamily, val label: String)

/**
 * Cio' che la testata dice: il [mark] di stato, l'[label] corta, al piu' una [line], il perche' lungo dietro la «i» ([detail]), l'arco che gira senza
 * misura ([busy]), i passi che mancano ([steps]) e i tasti ([actions]). [calmAction] = il primo tasto e' tonale (lo stato di tutti i giorni non chiede
 * niente: «Fai ora» e' una possibilita', non la cosa da fare). [waiting] = il backup aspetta una condizione (la carica, il Wi-Fi): sull'anello la
 * clessidra, e solo quella (giro 1, CU-7: la pausa ha le due barre, l'attesa la clessidra).
 */
@Immutable
internal class HeroModel(
  val mark: GalleryIconStatus,
  val label: String,
  val line: String? = null,
  val detail: String? = null,
  val busy: Boolean = false,
  val steps: List<HeroStep> = emptyList(),
  val actions: List<HeroAction> = emptyList(),
  val calmAction: Boolean = false,
  val waiting: Boolean = false,
)

/** Quanto e' al sicuro: [safe] su [total] media delle sorgenti attive. */
@Immutable
internal class SafeShare(val safe: Int, val total: Int) {

  /** La parte dell'anello piena. */
  val fraction: Float get() = if (total <= 0) 0f else (safe.toFloat() / total).coerceIn(0f, 1f)

  /** Il percento che si scrive: 100 solo quando e' tutto al sicuro (mai «100%» con una foto che manca). */
  val percent: Int get() = when {
    total <= 0 -> 0
    safe >= total -> FullPercent
    else -> (safe.toLong() * FullPercent / total).toInt().coerceAtMost(FullPercent - 1)
  }

  companion object {
    private const val FullPercent = 100

    /** Le sorgenti attive; senza sorgenti, «Tutto al sicuro» vale il suo conteggio. */
    fun of(status: BackupStatus, sources: List<SourceStatus>): SafeShare {
      val active = sources.filter { it.active }
      val total = active.sumOf { it.media }
      if (total == 0 && status is BackupStatus.UpToDate) return SafeShare(status.safeCount, status.safeCount)
      return SafeShare(active.sumOf { it.safe.coerceAtMost(it.media) }, total)
    }
  }
}

/** Il modello della testata per [status]: un ramo per ogni caso del contratto (un caso nuovo non compila finche' non ha il suo). */
@Composable
internal fun heroModel(status: BackupStatus, actions: BackupPageActions): HeroModel = when (status) {
  is BackupStatus.NotConfigured -> {
    val missing = BackupSetupStep.entries.filter { it in status.missing }
    HeroModel(
      mark = GalleryIconStatus.Attention,
      label = stringResource(R.string.backup_hero_setup),
      // BK-9: l'area privata non si sistema da una pagina dell'app: lo si dice dietro la «i», con chi puo' farlo.
      detail = if (BackupSetupStep.PRIVATE_AREA in missing) stringResource(R.string.backup_action_ask_admin) else null,
      steps = missing.map { step -> heroStep(step) },
      actions = buildList {
        if (BackupSetupStep.MEDIA_PERMISSION in missing) {
          add(HeroAction(stringResource(R.string.backup_action_grant_media), Icons.Rounded.PhotoLibrary) { actions.perform(BackupAction.GRANT_MEDIA) })
        }
        if (BackupSetupStep.SOURCES in missing || BackupSetupStep.DEVICE_FOLDER in missing) {
          add(HeroAction(stringResource(R.string.backup_status_open_settings), Icons.Rounded.Tune) { actions.open(BackupScreen.SETTINGS) })
        }
      },
    )
  }
  BackupStatus.TestOnly -> HeroModel(
    mark = GalleryIconStatus.Paused,
    label = stringResource(R.string.backup_hero_paused),
    line = stringResource(R.string.backup_hero_test_only_line),
    detail = stringResource(R.string.backup_problem_test_only_detail),
  )
  is BackupStatus.FirstBackupReady -> {
    val summary = status.summary
    if (summary.comparing) {
      HeroModel(
        mark = GalleryIconStatus.Running,
        label = stringResource(R.string.backup_status_first_comparing_title),
        line = if (!summary.existing.privateAreaScanned) {
          stringResource(R.string.backup_first_searching)
        } else {
          pluralStringResource(R.plurals.backup_first_comparing, summary.existing.similarPending, BackupText.count(summary.existing.similarPending))
        },
        busy = true,
        actions = listOf(HeroAction(stringResource(R.string.backup_status_first_see), Icons.Rounded.Checklist, actions.openSheet)),
        calmAction = true,
      )
    } else {
      val items = summary.toUpload.items + summary.linked.items
      HeroModel(
        mark = GalleryIconStatus.Attention,
        label = stringResource(R.string.backup_hero_ready),
        line = stringResource(
          R.string.backup_hero_pair,
          pluralStringResource(R.plurals.backup_source_media, items, BackupText.count(items)),
          BackupText.size(summary.linked.bytes + summary.toUpload.bytes),
        ),
        actions = listOf(HeroAction(stringResource(R.string.backup_status_first_see), Icons.Rounded.Checklist, actions.openSheet)),
      )
    }
  }
  is BackupStatus.UpToDate -> HeroModel(
    mark = GalleryIconStatus.Done,
    label = stringResource(R.string.backup_hero_safe),
    line = status.atNasMillis?.let { stringResource(R.string.backup_status_last_run, atText(it)) },
    actions = listOf(HeroAction(stringResource(R.string.backup_do_now), Icons.Rounded.CloudUpload, actions.backupNow)),
    calmAction = true,
  )
  is BackupStatus.Waiting -> HeroModel(
    mark = GalleryIconStatus.Paused,
    label = stringResource(R.string.backup_hero_waiting),
    line = stringResource(R.string.backup_hero_waiting_line, BackupText.count(status.items), stringResource(waitRes(status.reason))),
    actions = listOf(HeroAction(stringResource(R.string.backup_do_now), Icons.Rounded.CloudUpload, actions.backupNow)),
    calmAction = true,
    waiting = true,
  )
  is BackupStatus.Running -> {
    val kind = stringResource(if (status.kind == BackupKind.VIDEOS) R.string.backup_kind_videos else R.string.backup_kind_photos)
    // Giro 1 (CU-11): quanto e' fatto lo dice l'anello (sulle sorgenti); la riga dice solo che cosa carica e quanto manca di tempo. Prima c'era una
    // seconda frazione con un altro totale («Foto: 2.052 di 6.968» sotto «2.052 / 5.314»).
    HeroModel(
      mark = GalleryIconStatus.Running,
      label = stringResource(if (status.trigger == BackupTrigger.FIRST_BACKUP) R.string.backup_hero_running_first else R.string.backup_hero_running),
      line = listOfNotNull(kind, status.etaMillis?.let { etaText(it) }).joinToString(LineSeparator),
      actions = listOf(HeroAction(stringResource(R.string.backup_stop), Icons.Rounded.Pause, actions.stop)),
      calmAction = true,
    )
  }
  is BackupStatus.Stopped -> when (status.by) {
    // R4: "Ferma" dell'utente e' una pausa che dura finche' non tocca «Riprendi».
    StopBy.USER -> HeroModel(
      mark = GalleryIconStatus.Paused,
      label = stringResource(R.string.backup_hero_paused),
      detail = stringResource(R.string.backup_status_stopped_user),
      actions = listOf(HeroAction(stringResource(R.string.backup_resume), Icons.Rounded.PlayArrow, actions.backupNow)),
    )
    StopBy.SYSTEM -> HeroModel(
      mark = GalleryIconStatus.Paused,
      label = stringResource(R.string.backup_hero_stopped),
      line = stringResource(R.string.backup_hero_resumes_alone),
      actions = listOf(HeroAction(stringResource(R.string.backup_do_now), Icons.Rounded.CloudUpload, actions.backupNow)),
      calmAction = true,
    )
    // Un reset riparte dalla configurazione, non da «Riprendi».
    StopBy.RESET -> HeroModel(
      mark = GalleryIconStatus.Stopped,
      label = stringResource(R.string.backup_hero_stopped),
      line = stringResource(R.string.backup_hero_reset_line),
    )
  }
  is BackupStatus.Problem -> problemHero(status, actions)
}

/** Un problema in testa: il segno del suo tono, il messaggio come etichetta, il perche' dietro la «i», le azioni come tasti con l'icona. */
@Composable
private fun problemHero(status: BackupStatus.Problem, actions: BackupPageActions): HeroModel {
  // CA-1 (Fase 10): le scritture ferme dal manifest firmato hanno la loro frase (la causa e cosa fare).
  val text = BackupProblemTexts.of(status.code, LocalWritesHeldByRelease.current)
  val countPlural = text.titleCount
  val label = if (countPlural != null && status.count > 0) {
    pluralStringResource(countPlural, status.count, GalleryNumbers.format(status.count))
  } else {
    stringResource(text.title)
  }
  val resolved = status.code.actions.map { it to BackupActionTexts.of(it) }
  // Un'azione che e' solo una frase («Chiedi all'admin del NAS») sta con il perche'.
  val hint = resolved.firstOrNull { !it.second.isButton }?.let { stringResource(it.second.label) }
  val detail = listOfNotNull(text.detail?.let { stringResource(it) }, hint).joinToString(ParagraphSeparator).ifEmpty { null }
  return HeroModel(
    mark = text.mark,
    label = label,
    detail = detail,
    actions = resolved.filter { it.second.isButton }.map { (action, texts) ->
      HeroAction(stringResource(texts.label), texts.icon) { actions.perform(action) }
    },
  )
}

@Composable
private fun heroStep(step: BackupSetupStep): HeroStep = when (step) {
  BackupSetupStep.NAS -> HeroStep(Icons.Rounded.Dns, GalleryIconFamily.Backup, stringResource(R.string.backup_step_nas))
  BackupSetupStep.PRIVATE_AREA -> HeroStep(Icons.Rounded.Lock, GalleryIconFamily.Security, stringResource(R.string.backup_step_private_area))
  BackupSetupStep.SOURCES -> HeroStep(Icons.Rounded.PhotoLibrary, GalleryIconFamily.Photos, stringResource(R.string.backup_step_sources))
  BackupSetupStep.DEVICE_FOLDER -> HeroStep(Icons.Rounded.FolderShared, GalleryIconFamily.Backup, stringResource(R.string.backup_step_device_folder))
  BackupSetupStep.MEDIA_PERMISSION -> HeroStep(Icons.Rounded.PhotoLibrary, GalleryIconFamily.Photos, stringResource(R.string.backup_step_media_permission))
}

internal fun waitRes(reason: WaitReason): Int = when (reason) {
  WaitReason.CHARGING -> R.string.backup_wait_charging
  WaitReason.WIFI -> R.string.backup_wait_wifi
  WaitReason.HOME_WIFI -> R.string.backup_wait_home_wifi
  WaitReason.NIGHT_WINDOW -> R.string.backup_wait_night_window
  WaitReason.BATTERY -> R.string.backup_wait_battery
  WaitReason.POWER_SAVER -> R.string.backup_wait_power_saver
  WaitReason.THERMAL -> R.string.backup_wait_thermal
  WaitReason.DATA_CAP -> R.string.backup_wait_data_cap
  WaitReason.NAS -> R.string.backup_wait_nas
  WaitReason.COMPARING -> R.string.backup_wait_comparing
  WaitReason.NEXT_RUN -> R.string.backup_wait_next_run
  WaitReason.NAS_BUSY -> R.string.backup_wait_nas_busy
}

/**
 * La testata: l'anello con il segno, l'etichetta, la riga con la «i» (il perche' si apre sotto, nello stesso posto), i passi che mancano e i tasti. Sta
 * nel contenuto su una superficie piena (come un gruppo di righe), non e' vetro.
 */
@Composable
internal fun BackupHero(model: HeroModel, share: SafeShare, modifier: Modifier = Modifier) {
  val container = MaterialTheme.colorScheme.surfaceContainerLow
  // Fase 10 P8 (D23 (6)): uno stato che cambia da solo si annuncia una volta (regione viva educata); quello di tutti i giorni non e' una notizia.
  if (model.mark != GalleryIconStatus.Done) GalleryLiveAnnouncement(model.label)
  Column(
    modifier = modifier
      .fillMaxWidth()
      .clip(ContinuousCornerShape(FluidRadius.Group))
      .background(container)
      .padding(BackupDimens.HeroPadding),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(BackupDimens.CardGap),
  ) {
    SafeRing(share = share, mark = model.mark, busy = model.busy, waiting = model.waiting, ringBackground = container)
    HeroTexts(label = model.label, line = model.line, detail = model.detail)
    if (model.steps.isNotEmpty()) HeroSteps(model.steps, container)
    if (model.actions.isNotEmpty()) {
      Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(BackupDimens.LineGap)) {
        model.actions.forEachIndexed { index, action ->
          FluidButton(
            text = action.label,
            onClick = action.onClick,
            style = if (index == 0 && !model.calmAction) FluidButtonStyle.Filled else FluidButtonStyle.Tinted,
            size = FluidButtonSize.Large,
            fillWidth = true,
            leading = { Icon(imageVector = action.icon, contentDescription = null) },
          )
        }
      }
    }
  }
}

/**
 * L'anello: quello della galleria ([GalleryProgressRing], del colore della famiglia del backup) con quanto e' al sicuro; al centro il percento grande e
 * i numeri piccoli (o il glifo del backup quando non c'e' ancora niente da contare, o mentre l'arco gira senza misura), e il segno di stato appoggiato
 * sull'arco in basso a destra. L'arco avanza con la molla dell'anello; il valore si legge nel disegno, non in composizione.
 */
@Composable
private fun SafeRing(share: SafeShare, mark: GalleryIconStatus, busy: Boolean, waiting: Boolean, ringBackground: androidx.compose.ui.graphics.Color) {
  val ring = GalleryProgressRingDefaults.Hero
  val description = if (share.total > 0 && !busy) {
    stringResource(R.string.backup_hero_ring_description, share.percent, BackupText.count(share.safe), BackupText.count(share.total))
  } else {
    null
  }
  Box(modifier = Modifier.size(ring), contentAlignment = Alignment.Center) {
    GalleryProgressRing(
      // Mentre confronta, l'arco gira senza misura (prima: un cerchio che girava al centro di un anello fermo).
      progress = if (busy) null else share.fraction,
      modifier = Modifier
        .matchParentSize()
        .semantics { if (description != null) contentDescription = description },
      color = galleryRingColor(GalleryIconFamily.Backup),
      strokeWidth = GalleryProgressRingDefaults.stroke(ring),
      animate = true,
    ) {
      Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clearAndSetSemantics {}) {
        RingCenter(share = share, busy = busy)
      }
    }
    val place = Modifier.align(Alignment.BottomEnd).offset(x = -BackupDimens.HeroMarkInset, y = -BackupDimens.HeroMarkInset)
    if (waiting) {
      WaitMark(size = BackupDimens.HeroMark, ring = ringBackground, modifier = place)
    } else {
      GalleryStatusMark(
        status = mark,
        size = BackupDimens.HeroMark,
        ring = ringBackground,
        // L'etichetta sotto l'anello dice gia' lo stato a voce: il segno non lo ripete.
        contentDescription = null,
        modifier = place,
      )
    }
  }
}

/**
 * CU-7 (giro 1): l'attesa ha la clessidra, e solo quella. Lo stesso disco del segno di stato (forma, anello, misura del glifo) con i colori quieti
 * della pausa: aspettare la carica non e' un problema. Decorativo: l'etichetta sotto l'anello dice gia' «In attesa».
 */
@Composable
private fun WaitMark(size: Dp, ring: androidx.compose.ui.graphics.Color, modifier: Modifier = Modifier) {
  val colors = rememberGalleryStatusColors(GalleryIconStatus.Paused)
  val shape = ContinuousCornerShape(size / 2)
  Box(
    modifier = modifier
      .size(size)
      .clip(shape)
      .background(colors.top)
      .border(GalleryIconTileDefaults.MarkRing, ring, shape),
    contentAlignment = Alignment.Center,
  ) {
    Icon(
      imageVector = Icons.Rounded.HourglassTop,
      contentDescription = null,
      tint = colors.glyph,
      modifier = Modifier.size(size * GalleryIconTileDefaults.MarkGlyphFraction),
    )
  }
}

/** Il centro dell'anello: il percento e i numeri, o il glifo del backup (niente da contare, o l'arco che gira senza misura). */
@Composable
private fun RingCenter(share: SafeShare, busy: Boolean) {
  when {
    !busy && share.total > 0 -> {
      Text(
        text = stringResource(R.string.backup_hero_percent, share.percent),
        style = MaterialTheme.typography.headlineMedium.copy(fontFeatureSettings = "tnum"),
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurface,
      )
      Text(
        text = stringResource(R.string.backup_hero_ring_count, BackupText.count(share.safe), BackupText.count(share.total)),
        style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
      )
    }
    else -> Icon(
      imageVector = Icons.Rounded.CloudUpload,
      contentDescription = null,
      tint = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.size(BackupDimens.HeroCenterGlyph),
    )
  }
}

/**
 * L'etichetta, la riga corta sotto e la «i» della galleria quando c'e' un perche': un tocco apre il perche' sotto, nello stesso posto (e lo richiude).
 * Con la riga, si tocca la riga (con il segno «i» accanto); senza riga, il tasto «i» sta accanto all'etichetta. TalkBack sente il perche' nella
 * descrizione di chi lo apre, senza tocco (la frase aperta non si rilegge).
 */
@Composable
private fun HeroTexts(label: String, line: String?, detail: String?) {
  var open by rememberSaveable(detail) { mutableStateOf(false) }
  val showLabel = stringResource(R.string.backup_hero_detail_show)
  val openState = stringResource(R.string.backup_hero_detail_open)
  val closedState = stringResource(R.string.backup_hero_detail_closed)
  val toggle: Modifier = Modifier
    .fluidPressable(onClick = { open = !open }, role = Role.Button)
    .semantics(mergeDescendants = true) {
      role = Role.Button
      if (line != null && detail != null) contentDescription = "$line. $detail"
      stateDescription = if (open) openState else closedState
    }
  Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(BackupDimens.LineGap)) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
      Text(
        text = label,
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Center,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.weight(1f, fill = false).semantics { heading() },
      )
      if (line == null && detail != null) {
        GalleryInfoButton(
          label = "$showLabel. $detail",
          open = open,
          onToggle = { open = !open },
          openState = openState,
          closedState = closedState,
          tint = MaterialTheme.colorScheme.primary,
        )
      }
    }
    if (line != null) {
      Row(
        modifier = Modifier
          .defaultMinSize(minWidth = GalleryDimens.MinTouchTarget, minHeight = GalleryDimens.MinTouchTarget)
          .then(if (detail != null) toggle else Modifier),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(BackupDimens.LineGap, Alignment.CenterHorizontally),
      ) {
        Text(
          text = line,
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          textAlign = TextAlign.Center,
          modifier = Modifier.weight(1f, fill = false),
        )
        if (detail != null) GalleryInfoGlyph(open = open, tint = MaterialTheme.colorScheme.primary)
      }
    }
    if (detail != null) {
      GalleryInfoDetail(
        text = detail,
        visible = open,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}

/** I passi che mancano prima di cominciare: una piastrella con il segno «attenzione» e un'etichetta, uno per riga. */
@Composable
private fun HeroSteps(steps: List<HeroStep>, background: androidx.compose.ui.graphics.Color) {
  Column(verticalArrangement = Arrangement.spacedBy(BackupDimens.CardGap)) {
    steps.forEach { step ->
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(BackupDimens.BlockGap)) {
        GalleryIconTile(icon = step.icon, family = step.family, size = BackupDimens.StepTile, status = GalleryIconStatus.Attention, ring = background)
        Text(text = step.label, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
      }
    }
  }
}

private const val LineSeparator = " · "
private const val ParagraphSeparator = "\n"
