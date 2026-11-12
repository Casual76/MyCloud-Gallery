package dev.pampa.mycloudgallery.feature.backup.free

import dev.pampa.mycloudgallery.core.designsystem.icon.GalleryIconStatus
import dev.pampa.mycloudgallery.core.designsystem.icon.glyph
import dev.pampa.mycloudgallery.core.designsystem.design.GalleryInfoRow
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.runtime.CompositionLocalProvider
import dev.pampa.mycloudgallery.feature.backup.ui.LocalBackupCovers
import androidx.compose.material3.Icon
import androidx.compose.material.icons.rounded.DeleteForever
import dev.antigravity.fluidengine.ui.fluid.FluidButtonSize
import dev.antigravity.fluidengine.ui.fluid.FluidButton
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.antigravity.fluidengine.ui.fluid.FluidButtonStyle
import dev.antigravity.fluidengine.ui.fluid.GlassBackdropState
import dev.antigravity.fluidengine.ui.theme.FluidListDivider
import dev.antigravity.fluidengine.ui.theme.FluidListGroup
import dev.pampa.mycloudgallery.core.backup.contract.BackupAction
import dev.pampa.mycloudgallery.core.backup.contract.FreeSpaceExclusion
import dev.pampa.mycloudgallery.core.backup.contract.FreeSpaceKind
import dev.pampa.mycloudgallery.core.backup.contract.PhoneConfirmation
import dev.pampa.mycloudgallery.core.designsystem.state.phoneTrashText
import dev.pampa.mycloudgallery.core.designsystem.theme.GalleryDimens
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.feature.backup.BackupDimens
import dev.pampa.mycloudgallery.feature.backup.R
import dev.pampa.mycloudgallery.feature.backup.ui.BackupButton
import dev.pampa.mycloudgallery.feature.backup.ui.BackupCard
import dev.pampa.mycloudgallery.feature.backup.ui.BackupLabel
import dev.pampa.mycloudgallery.feature.backup.ui.BackupNote
import dev.pampa.mycloudgallery.feature.backup.ui.BackupPage
import dev.pampa.mycloudgallery.feature.backup.ui.BackupSectionTitle
import dev.pampa.mycloudgallery.feature.backup.ui.BackupText
import dev.pampa.mycloudgallery.feature.backup.ui.BackupTone
import dev.pampa.mycloudgallery.feature.backup.ui.etaText
import kotlinx.coroutines.delay

// Fase 5B (P12, docs/fase5-progetto.md §1.5 `FreeSpaceState`, §2.5, D21, Q9, C13, R6 della 4B; grafica-progetto.md §12.11): la pagina "Libera
// spazio" (Raccolte → Utility → Libera spazio, o "Libera spazio" sulla selezione). Una scheda per passo del §2.5:
//   Idle → Summary (l'AVVISO FORTE in cima, quanti e quanti GB, i filtri, gli esclusi per motivo) → [foglio di conferma: un tocco] →
//   Guarding → Verifying → Ready → [dialogo del sistema] Confirming → Done, o Blocked con il suo perche'.
// "Libera subito…" ha un foglio a due gesti distinti. Solo componenti G2 (la chrome e le schede della pagina Backup): niente testo su vetro, nessun
// tasto sopra un altro. Solo numeri: mai il nome di un file.

/** Le uscite della pagina: tutte partono da un tocco. */
@Immutable
internal class FreeSpaceActions(
  val choose: (FreeSpaceKind) -> Unit = {},
  val openConfirm: () -> Unit = {},
  val openConfirmNow: () -> Unit = {},
  val closeSheet: () -> Unit = {},
  val confirm: (PhoneConfirmation) -> Unit = {},
  val cancelVerification: () -> Unit = {},
  val free: () -> Unit = {},
  val reload: () -> Unit = {},
  val perform: (BackupAction) -> Unit = {},
  val done: () -> Unit = {},
)

/**
 * La pagina "Libera spazio" con il suo ViewModel. [selection] sono i media scelti dall'utente (la selezione della Libreria): al massimo quelli,
 * senza escludere gli ultimi giorni; null = tutto il sicuro tranne gli ultimi 30 giorni (Q9). [onBack] e' null nel pannello di dettaglio del tablet.
 */
@Composable
fun FreeSpaceRoute(
  modifier: Modifier = Modifier,
  onBack: (() -> Unit)? = null,
  hostPadding: PaddingValues = PaddingValues(),
  backdropOut: ((GlassBackdropState) -> Unit)? = null,
  selection: Set<MediaId>? = null,
  viewModel: FreeSpaceViewModel = hiltViewModel(),
) {
  val state by viewModel.state.collectAsStateWithLifecycle()
  val sheet by viewModel.sheet.collectAsStateWithLifecycle()
  BindDialogBridge(viewModel.bridge)
  LaunchedEffect(selection) { viewModel.start(selection) }
  val plan = when (val open = sheet) {
    FreeSheet.None -> null
    is FreeSheet.Confirm -> open.plan
    is FreeSheet.ConfirmNow -> open.plan
  }
  val taps = rememberConfirmTaps(plan, viewModel::monotonicMs)
  // La validita' della verifica si conta con l'orologio monotono: la pagina la rinfresca una volta al minuto.
  val now by produceState(initialValue = viewModel.monotonicMs()) {
    while (true) {
      delay(NOW_REFRESH_MS)
      value = viewModel.monotonicMs()
    }
  }
  // Giro 1 (CU-6): le anteprime di cio' che si toglie passano dalla Libreria visibile, come le copertine del backup.
  CompositionLocalProvider(LocalBackupCovers provides viewModel.covers) {
  FreeSpaceScreen(
    state = state,
    sheet = sheet,
    taps = taps,
    actions = FreeSpaceActions(
      choose = viewModel::choose,
      openConfirm = viewModel::openConfirm,
      openConfirmNow = viewModel::openConfirmNow,
      closeSheet = viewModel::closeSheet,
      confirm = viewModel::confirm,
      cancelVerification = viewModel::cancelVerification,
      free = viewModel::free,
      reload = viewModel::reload,
      perform = viewModel::perform,
      done = onBack ?: viewModel::reload,
    ),
    nowMs = now,
    modifier = modifier,
    onBack = onBack,
    hostPadding = hostPadding,
    backdropOut = backdropOut,
  )
  }
}

private const val NOW_REFRESH_MS = 60_000L

/** La pagina per uno [state]: nessun effetto. La provano i test e gli screenshot. */
@Composable
internal fun FreeSpaceScreen(
  state: FreeSpaceUi,
  sheet: FreeSheet,
  taps: ConfirmTaps?,
  actions: FreeSpaceActions,
  nowMs: Long,
  modifier: Modifier = Modifier,
  onBack: (() -> Unit)? = null,
  hostPadding: PaddingValues = PaddingValues(),
  backdropOut: ((GlassBackdropState) -> Unit)? = null,
) {
  BackupPage(
    label = BackupLabel(title = stringResource(R.string.backup_free_title)),
    modifier = modifier,
    onBack = onBack,
    backdropOut = backdropOut,
    hostPadding = hostPadding,
  ) { insets ->
    Box(modifier = Modifier.fillMaxHeight().fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
      LazyColumn(
        modifier = Modifier.fillMaxHeight().widthIn(max = BackupDimens.PageMaxWidth).fillMaxWidth(),
        contentPadding = PaddingValues(start = GalleryDimens.Gutter, end = GalleryDimens.Gutter, top = insets.top, bottom = insets.bottom),
        verticalArrangement = Arrangement.spacedBy(BackupDimens.BlockGap),
      ) {
        when (state) {
          FreeSpaceUi.Idle -> item(key = "idle") {
            BackupCard(
              tone = BackupTone.Working,
              glyph = Icons.Rounded.CleaningServices,
              title = stringResource(R.string.backup_free_idle_title),
              detail = stringResource(R.string.backup_free_idle_detail),
              busy = true,
            )
          }
          is FreeSpaceUi.Summary -> summary(state, actions)
          is FreeSpaceUi.Guarding -> progress(state.done, state.total, bytes = null, actions = actions, waitingForBackup = state.waitingForBackup)
          is FreeSpaceUi.Verifying -> progress(state.done, state.total, bytes = state.bytes, actions = actions)
          is FreeSpaceUi.Ready -> ready(state, nowMs, actions)
          is FreeSpaceUi.Confirming -> item(key = "confirming") {
            BackupCard(
              tone = BackupTone.Working,
              // I6 (CU-33): aspetto la risposta al dialogo del telefono: l'attesa e' la clessidra comune.
              glyph = GalleryIconStatus.Waiting.glyph(),
              title = stringResource(R.string.backup_free_confirming_title),
              detail = stringResource(R.string.backup_free_confirming_detail, state.batch, state.of),
              busy = true,
            )
          }
          is FreeSpaceUi.Done -> done(state, actions)
          is FreeSpaceUi.Blocked -> blocked(state, actions)
        }
      }
    }
  }
  FreeSheets(sheet = sheet, taps = taps, actions = actions)
}

private fun fractionOf(done: Int, total: Int): Float = if (total <= 0) 0f else (done.toFloat() / total).coerceIn(0f, 1f)

// --- L'avviso forte --------------------------------------------------------------------------------------------------------------

/**
 * L'avviso forte (C13): "Il NAS non ha un backup esterno: dopo, l'unica copia sara' sul NAS". E' una scheda piena, nel tono piu' alto della pagina,
 * in cima al riepilogo, al passo "pronto" e in ogni foglio di conferma: non un rigo in fondo. Con [permanent] ("Libera subito") ne segue una
 * seconda che dice che non si torna indietro.
 */
@Composable
internal fun StrongWarning(permanent: Boolean, modifier: Modifier = Modifier) {
  Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(BackupDimens.BlockGap)) {
    BackupCard(
      tone = BackupTone.Alarm,
      glyph = Icons.Rounded.Warning,
      title = stringResource(R.string.backup_free_warning_title),
      detail = stringResource(R.string.backup_free_warning_detail),
    )
    if (permanent) {
      BackupCard(
        tone = BackupTone.Alarm,
        glyph = Icons.Rounded.ErrorOutline,
        title = stringResource(R.string.backup_free_permanent_title),
        detail = stringResource(R.string.backup_free_permanent_detail),
      )
    }
  }
}

// --- Il riepilogo ----------------------------------------------------------------------------------------------------------------

/**
 * Il riepilogo (giro 1 della fase I, CU-6): l'avviso forte in cima (C13), la testata con quanto si libera, le anteprime e la garanzia ([FreeHero]),
 * le scelte con la piastrella, i tasti con l'icona e chi resta sul telefono come piastrelle con il numero. Le frasi lunghe (gli ultimi giorni, il
 * cestino, il perche' di ogni motivo) stanno dietro la «i»; resta in pagina solo cio' che dice uno stato (l'ora del NAS che manca, LS-11).
 */
private fun LazyListScope.summary(state: FreeSpaceUi.Summary, actions: FreeSpaceActions) {
  val plan = state.plan
  item(key = "warning") { StrongWarning(permanent = false) }
  item(key = "summary") { FreeHero(plan) }
  // LS-11: senza l'ora del NAS ogni media con una data conta come recente (fallire chiusi): lo si dice, e il riepilogo si ricalcola da solo.
  if (!state.nasTimeKnown) {
    item(key = "nas-time") { BackupNote(stringResource(R.string.backup_free_nas_time_note)) }
  }
  item(key = "filter-title") { BackupSectionTitle(stringResource(R.string.backup_free_filter_title)) }
  item(key = "filter") {
    FluidListGroup {
      FreeSpaceKind.entries.forEachIndexed { index, kind ->
        if (index > 0) FluidListDivider()
        FreeKindRow(kind = kind, selected = kind == state.kind, onSelect = { actions.choose(kind) })
      }
    }
  }
  if (plan.needsGuardian > 0) {
    item(key = "guardian") { FluidListGroup { GuardianRow(plan.needsGuardian) } }
  }
  item(key = "buttons") {
    Column(verticalArrangement = Arrangement.spacedBy(BackupDimens.BlockGap)) {
      FluidButton(
        text = stringResource(R.string.backup_free_action),
        onClick = actions.openConfirm,
        style = FluidButtonStyle.Filled,
        size = FluidButtonSize.Large,
        fillWidth = true,
        enabled = plan.itemCount > 0,
        leading = { Icon(imageVector = Icons.Rounded.CleaningServices, contentDescription = null) },
      )
      FluidButton(
        text = stringResource(R.string.backup_free_action_now),
        onClick = actions.openConfirmNow,
        style = FluidButtonStyle.Tinted,
        size = FluidButtonSize.Large,
        fillWidth = true,
        enabled = plan.itemCount > 0,
        leading = { Icon(imageVector = Icons.Rounded.DeleteForever, contentDescription = null) },
      )
    }
  }
  item(key = "how") {
    val how = listOfNotNull(
      if (plan.filter.excludeRecentDays > 0) stringResource(R.string.backup_free_recent_note, plan.filter.excludeRecentDays) else null,
      stringResource(phoneTrashText(R.string.backup_free_trash_note, R.string.backup_free_trash_note_gallery)),
    ).joinToString("\n")
    GalleryInfoRow(label = stringResource(R.string.backup_free_how_info), detail = how, modifier = Modifier.padding(horizontal = BackupDimens.BlockGap))
  }
  if (plan.excluded.isNotEmpty()) {
    // Nell'ordine del contratto, non in quello di una mappa: la pagina non cambia da una volta all'altra.
    val reasons = FreeSpaceExclusion.entries.filter { it in plan.excluded }
    item(key = "excluded-title") { BackupSectionTitle(stringResource(R.string.backup_free_excluded_title)) }
    item(key = "excluded") {
      FluidListGroup {
        reasons.forEachIndexed { index, reason ->
          if (index > 0) FluidListDivider()
          ExclusionRow(reason = reason, count = plan.excluded.getValue(reason))
        }
      }
    }
    item(key = "excluded-info") {
      val why = reasons.map { reason ->
        val (title, detail) = exclusionText(reason)
        stringResource(title) + ": " + stringResource(detail)
      }.joinToString("\n")
      GalleryInfoRow(label = stringResource(R.string.backup_free_excluded_info), detail = why, modifier = Modifier.padding(horizontal = BackupDimens.BlockGap))
    }
  }
}

/** Il titolo e la frase di ogni motivo di esclusione del §2.5: un `when` esaustivo, un motivo nuovo non compila senza il suo testo. */
internal fun exclusionText(reason: FreeSpaceExclusion): Pair<Int, Int> = when (reason) {
  FreeSpaceExclusion.NOT_VERIFIED -> R.string.backup_free_ex_not_verified to R.string.backup_free_ex_not_verified_detail
  FreeSpaceExclusion.VERSION_NOT_SAFE -> R.string.backup_free_ex_version_not_safe to R.string.backup_free_ex_version_not_safe_detail
  FreeSpaceExclusion.IN_PROGRESS_OR_ERROR -> R.string.backup_free_ex_in_progress to R.string.backup_free_ex_in_progress_detail
  FreeSpaceExclusion.AREA_FROZEN -> R.string.backup_free_ex_frozen to R.string.backup_free_ex_frozen_detail
  FreeSpaceExclusion.NAS_UNREACHABLE -> R.string.backup_free_ex_nas to R.string.backup_free_ex_nas_detail
  FreeSpaceExclusion.OUTSIDE_TEST_FENCE -> R.string.backup_free_ex_fence to R.string.backup_free_ex_fence_detail
  FreeSpaceExclusion.RECENT -> R.string.backup_free_ex_recent to R.string.backup_free_ex_recent_detail
  FreeSpaceExclusion.COPY_CHANGED -> R.string.backup_free_ex_copy_changed to R.string.backup_free_ex_copy_changed_detail
  FreeSpaceExclusion.GONE_FROM_PHONE -> R.string.backup_free_ex_gone to R.string.backup_free_ex_gone_detail
  FreeSpaceExclusion.NOT_MINE -> R.string.backup_free_ex_not_mine to R.string.backup_free_ex_not_mine_detail
  FreeSpaceExclusion.BACKUP_NOT_ACTIVE -> R.string.backup_free_ex_backup_inactive to R.string.backup_free_ex_backup_inactive_detail
  FreeSpaceExclusion.AWAITING_PHONE -> R.string.backup_free_ex_awaiting to R.string.backup_free_ex_awaiting_detail
}

// --- Guardiano e verifica sul momento ----------------------------------------------------------------------------------------------

/**
 * Il guardiano ([bytes] null) e la verifica sul momento: la stessa scheda, con la barra che avanza. LS-8: con [waitingForBackup] il guardiano
 * aspetta che il backup finisca il file in corso, e la scheda lo dice (prima "0 di N" senza spiegazione).
 */
private fun LazyListScope.progress(done: Int, total: Int, bytes: Long?, actions: FreeSpaceActions, waitingForBackup: Boolean = false) {
  item(key = "progress") {
    BackupCard(
      tone = BackupTone.Working,
      glyph = Icons.Rounded.Shield,
      title = stringResource(if (bytes == null) R.string.backup_free_guarding_title else R.string.backup_free_verifying_title),
      detail = if (bytes == null) {
        val counts = stringResource(R.string.backup_free_guarding_detail, BackupText.count(done), BackupText.count(total))
        if (waitingForBackup) counts + " · " + stringResource(R.string.backup_free_guarding_waiting) else counts
      } else {
        stringResource(R.string.backup_free_verifying_detail, BackupText.count(done), BackupText.count(total), BackupText.size(bytes))
      },
      progress = fractionOf(done, total),
      actions = { BackupButton(label = stringResource(R.string.backup_free_cancel), onClick = actions.cancelVerification, style = FluidButtonStyle.Tinted) },
    )
  }
  item(key = "progress-hint") { BackupNote(stringResource(R.string.backup_free_verifying_hint)) }
}

// --- Pronto e dialogo del sistema ------------------------------------------------------------------------------------------------

private fun LazyListScope.ready(state: FreeSpaceUi.Ready, nowMs: Long, actions: FreeSpaceActions) {
  item(key = "warning") { StrongWarning(permanent = state.permanent) }
  item(key = "ready") {
    val left = state.validUntilMonotonicMs?.let { (it - nowMs).coerceAtLeast(0L) }
    val moreBatches = state.batchesDone > 0
    BackupCard(
      tone = if (state.permanent) BackupTone.Alarm else BackupTone.Calm,
      glyph = Icons.Rounded.CleaningServices,
      title = pluralStringResource(R.plurals.backup_free_ready_title, state.items, BackupText.count(state.items), BackupText.size(state.bytes)),
      detail = buildString {
        append(stringResource(if (state.permanent) R.string.backup_free_ready_detail_now else R.string.backup_free_ready_detail_trash))
        if (left != null) append(' ').append(stringResource(R.string.backup_free_ready_valid, etaText(left)))
      },
      hint = if (state.batchesTotal > 1) stringResource(R.string.backup_free_ready_batch, state.batchesDone + 1, state.batchesTotal) else null,
      actions = {
        BackupButton(
          label = stringResource(
            when {
              moreBatches -> R.string.backup_free_ready_action_next
              state.permanent -> R.string.backup_free_ready_action_now
              else -> R.string.backup_free_ready_action
            },
          ),
          onClick = actions.free,
          style = if (state.permanent) FluidButtonStyle.Destructive else FluidButtonStyle.Filled,
        )
      },
    )
  }
}

// --- La fine ---------------------------------------------------------------------------------------------------------------------

private fun LazyListScope.done(state: FreeSpaceUi.Done, actions: FreeSpaceActions) {
  item(key = "done") {
    val nothing = state.items == 0
    BackupCard(
      tone = if (nothing) BackupTone.Waiting else BackupTone.Calm,
      glyph = if (nothing) Icons.Rounded.Shield else Icons.Rounded.CheckCircle,
      title = when {
        // LS-7: "non e' cambiato niente" solo se davvero niente e' stato tolto; dopo un primo lotto liberato si dice quanto.
        state.cancelled && nothing -> stringResource(R.string.backup_free_done_cancelled_title)
        nothing -> stringResource(R.string.backup_free_done_nothing_title)
        else -> stringResource(R.string.backup_free_done_title, BackupText.size(state.freedBytes))
      },
      detail = buildString {
        when {
          state.cancelled && nothing -> append(stringResource(R.string.backup_free_done_cancelled_detail))
          nothing -> append(stringResource(R.string.backup_free_done_nothing_detail))
          else -> {
            append(pluralStringResource(R.plurals.backup_free_done_detail, state.items, BackupText.count(state.items)))
            append(' ').append(stringResource(if (state.toAndroidTrash) phoneTrashText(R.string.backup_free_done_trash, R.string.backup_free_done_trash_gallery) else R.string.backup_free_done_gone))
            // LS-7: un "no" al lotto dopo il primo: quelli gia' liberati restano liberati, gli altri restano sul telefono.
            if (state.cancelled) append(' ').append(stringResource(R.string.backup_free_done_stopped))
          }
        }
        if (state.kept > 0 && !state.cancelled && !nothing) {
          append(' ').append(pluralStringResource(R.plurals.backup_free_done_kept, state.kept, BackupText.count(state.kept)))
        }
        // LS-13: i media confermati che erano gia' cambiati quando il giro e' partito.
        if (state.changedBeforeStart > 0) {
          append(' ').append(pluralStringResource(R.plurals.backup_free_done_changed, state.changedBeforeStart, BackupText.count(state.changedBeforeStart)))
        }
        droppedLines(state).forEach { append(' ').append(it) }
        // LS-12: consegnati ad Android, esito non ancora noto.
        if (state.inDoubt > 0) {
          append(' ').append(pluralStringResource(R.plurals.backup_free_done_in_doubt, state.inDoubt, BackupText.count(state.inDoubt)))
        }
      },
      actions = { BackupButton(label = stringResource(R.string.backup_free_done_action), onClick = actions.done) },
    )
  }
}

/**
 * LS-7: chi e' uscito durante il giro, con il motivo vero (prima tutti "la copia sul NAS non tornava", anche "gia' tolto" o "in caricamento").
 * Senza il dettaglio per motivo (un giro raccontato prima delle correzioni) resta la frase di sempre.
 */
@Composable
private fun droppedLines(state: FreeSpaceUi.Done): List<String> {
  if (state.droppedBy.isEmpty()) {
    return if (state.dropped > 0) listOf(pluralStringResource(R.plurals.backup_free_done_dropped, state.dropped, BackupText.count(state.dropped))) else emptyList()
  }
  return FreeSpaceExclusion.entries.filter { (state.droppedBy[it] ?: 0) > 0 }.map { reason ->
    val count = state.droppedBy.getValue(reason)
    if (reason == FreeSpaceExclusion.NOT_VERIFIED) {
      pluralStringResource(R.plurals.backup_free_done_dropped, count, BackupText.count(count))
    } else {
      pluralStringResource(R.plurals.backup_free_done_dropped_by, count, BackupText.count(count), stringResource(exclusionText(reason).first))
    }
  }
}

// --- Quando non si puo' ----------------------------------------------------------------------------------------------------------

private fun LazyListScope.blocked(state: FreeSpaceUi.Blocked, actions: FreeSpaceActions) {
  item(key = "blocked") {
    val text = blockedText(state.reason)
    BackupCard(
      tone = text.tone,
      glyph = text.glyph,
      title = stringResource(text.title),
      detail = stringResource(text.detail),
      actions = if (text.action == null) {
        null
      } else {
        {
          BackupButton(label = stringResource(text.action.first), onClick = { text.action.second(actions) }, style = FluidButtonStyle.Tinted)
        }
      },
    )
  }
}

/** Cosa dice un blocco: il tono, il glifo, il titolo, la frase e (se c'e' qualcosa da fare) il tasto. */
@Immutable
private class BlockedText(
  val tone: BackupTone,
  val glyph: ImageVector,
  val title: Int,
  val detail: Int,
  val action: Pair<Int, (FreeSpaceActions) -> Unit>? = null,
)

private fun blockedText(reason: FreeSpaceBlockedBy): BlockedText = when (reason) {
  FreeSpaceBlockedBy.AREA_FROZEN -> BlockedText(
    BackupTone.Alarm, Icons.Rounded.ErrorOutline, R.string.backup_free_blocked_frozen_title, R.string.backup_free_blocked_frozen_detail,
    R.string.backup_free_blocked_frozen_action to { actions -> actions.perform(BackupAction.DIAGNOSTICS) },
  )
  FreeSpaceBlockedBy.NAS_UNREACHABLE -> BlockedText(
    BackupTone.Attention, Icons.Rounded.CloudOff, R.string.backup_free_blocked_nas_title, R.string.backup_free_blocked_nas_detail,
    R.string.backup_free_blocked_retry to { actions -> actions.reload() },
  )
  FreeSpaceBlockedBy.CONFIRMATION_REJECTED -> BlockedText(
    BackupTone.Attention, Icons.Rounded.Warning, R.string.backup_free_blocked_rejected_title, R.string.backup_free_blocked_rejected_detail,
    R.string.backup_free_blocked_retry to { actions -> actions.reload() },
  )
  // I6 (CU-33): le due attese (il backup non ancora pronto, il telefono che non puo') con la clessidra comune; la verifica scaduta resta l'orologio
  // (e' una questione di tempo, non un'attesa).
  FreeSpaceBlockedBy.NOT_AVAILABLE -> BlockedText(
    BackupTone.Waiting, GalleryIconStatus.Waiting.glyph(), R.string.backup_free_blocked_unavailable_title, R.string.backup_free_blocked_unavailable_detail,
  )
  FreeSpaceBlockedBy.NO_SYSTEM_DIALOG -> BlockedText(
    BackupTone.Waiting, GalleryIconStatus.Waiting.glyph(), R.string.backup_free_blocked_dialog_title, R.string.backup_free_blocked_dialog_detail,
  )
  FreeSpaceBlockedBy.EXPIRED -> BlockedText(
    BackupTone.Attention, Icons.Rounded.Schedule, R.string.backup_free_blocked_expired_title, R.string.backup_free_blocked_expired_detail,
    R.string.backup_free_blocked_restart to { actions -> actions.reload() },
  )
}
