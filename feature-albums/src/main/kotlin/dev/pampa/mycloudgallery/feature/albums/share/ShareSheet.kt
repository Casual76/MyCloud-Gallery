package dev.pampa.mycloudgallery.feature.albums.share

import android.text.format.Formatter
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddPhotoAlternate
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Group
import androidx.compose.material.icons.rounded.Undo
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import dev.antigravity.fluidengine.ui.fluid.FluidButton
import dev.antigravity.fluidengine.ui.fluid.FluidButtonSize
import dev.antigravity.fluidengine.ui.fluid.FluidButtonStyle
import dev.antigravity.fluidengine.ui.fluid.FluidRowValue
import dev.antigravity.fluidengine.ui.theme.FluidInlineMessage
import dev.antigravity.fluidengine.ui.theme.FluidListDivider
import dev.antigravity.fluidengine.ui.theme.FluidListGroup
import dev.antigravity.fluidengine.ui.theme.FluidListRow
import dev.antigravity.fluidengine.ui.theme.FluidTone
import dev.pampa.mycloudgallery.core.albums.contract.HiddenChoice
import dev.pampa.mycloudgallery.core.albums.contract.ShareBlock
import dev.pampa.mycloudgallery.core.albums.contract.ShareExclusion
import dev.pampa.mycloudgallery.core.albums.contract.ShareItemAction
import dev.pampa.mycloudgallery.core.albums.contract.SharePlan
import dev.pampa.mycloudgallery.core.albums.contract.SharePlanKind
import dev.pampa.mycloudgallery.core.designsystem.sheet.GalleryConfirmSheet
import dev.pampa.mycloudgallery.core.designsystem.sheet.GallerySheetScaffold
import dev.pampa.mycloudgallery.core.designsystem.sheet.GallerySheetTone
import dev.pampa.mycloudgallery.core.model.ShareLevel
import dev.pampa.mycloudgallery.feature.albums.R
import dev.pampa.mycloudgallery.feature.albums.contract.ShareSheetNotice
import dev.pampa.mycloudgallery.feature.albums.contract.ShareSheetState
import dev.pampa.mycloudgallery.feature.albums.contract.WhyBlocked
import dev.pampa.mycloudgallery.feature.albums.page.CancelButton
import dev.pampa.mycloudgallery.feature.albums.ui.AlbumText
import dev.pampa.mycloudgallery.feature.albums.ui.OptionRow

// IL FOGLIO DI CONDIVISIONE (Fase 7, P10; docs/fase7-progetto.md D12, D30, D35, §1.5 `ShareSheetState`, E-SH9, E-SH13): cio' che l'utente legge prima di
// toccare il bottone che sposta le foto nell'area comune. Dice **quanto** ("12 foto e 3 video (1,2 GB) verranno spostati nell'area comune"), **dove**
// (la cartella), **chi puo' fare cosa** (il livello, che alla creazione si sceglie qui, di partenza "Gli altri aggiungono", Q2), **i nascosti**
// («Lascia fuori i N nascosti» preselezionata; «Condividi anche i nascosti» con l'avviso che diventeranno visibili ai familiari: Q3, E-SH9) e la
// dichiarazione "I permessi li fa rispettare l'app: dal PC chi accede all'area comune puo' fare tutto" (L57).
//
// Il bottone e' l'unico gesto che emette la conferma ([ShareSheetController.confirm]). Un foglio puo' anche dire che non si puo' (D30: "Arriva dopo
// le prove"; cancelli delle scritture; sola lettura), che non c'e' niente da aggiungere o che l'album non lo permette piu'.

/** Cosa il foglio chiede fuori da se': i quattro gesti. */
interface ShareSheetHandlers {
  fun onConfirm()

  fun onDismiss()

  fun onHiddenChoice(choice: HiddenChoice)

  fun onLevel(level: ShareLevel)
}

/** Il foglio per lo stato dato. Con [state] null (o `Started`) non si vede. */
@Composable
fun ShareSheet(state: ShareSheetState?, handlers: ShareSheetHandlers) {
  // Durante `Confirming` il foglio resta quello di prima, con il bottone fermo: non salta a un altro contenuto.
  var lastPlan by remember { mutableStateOf<ShareSheetState.Plan?>(null) }
  if (state is ShareSheetState.Plan) lastPlan = state
  val shown: ShareSheetState? = if (state is ShareSheetState.Confirming) lastPlan else state
  val visible = shown != null && shown !is ShareSheetState.Started
  val title = shown?.let { titleOf(it) } ?: ""
  GalleryConfirmSheet(visible = visible, onDismissRequest = handlers::onDismiss, paneTitle = title) {
    when (val s = shown) {
      is ShareSheetState.Plan -> PlanContent(s, busy = state is ShareSheetState.Confirming, handlers = handlers)
      is ShareSheetState.Blocked -> InfoContent(blockedTitle(s.reason), blockedDetail(s.reason), handlers)
      is ShareSheetState.NothingToShare -> InfoContent(stringResource(R.string.share_nothing_title), stringResource(R.string.share_nothing_detail), handlers) { ExcludedGroup(s.excluded) }
      is ShareSheetState.Refused -> InfoContent(stringResource(R.string.share_refused_title), stringResource(R.string.share_refused_detail), handlers)
      is ShareSheetState.InvalidTitle -> InfoContent(stringResource(R.string.share_invalid_title), stringResource(AlbumText.titleProblem(s.problem)), handlers)
      ShareSheetState.Confirming, is ShareSheetState.Started, null -> Unit
    }
  }
}

@Composable
private fun titleOf(state: ShareSheetState): String = when (state) {
  is ShareSheetState.Plan -> planTitle(state.plan)
  is ShareSheetState.Blocked -> blockedTitle(state.reason)
  is ShareSheetState.NothingToShare -> stringResource(R.string.share_nothing_title)
  is ShareSheetState.Refused -> stringResource(R.string.share_refused_title)
  is ShareSheetState.InvalidTitle -> stringResource(R.string.share_invalid_title)
  ShareSheetState.Confirming, is ShareSheetState.Started -> ""
}

@Composable
private fun planTitle(plan: SharePlan): String = stringResource(
  when {
    plan.kind == SharePlanKind.BRING_BACK -> R.string.share_sheet_title_bring_back
    plan.kind == SharePlanKind.ADOPT -> R.string.share_sheet_title_adopt
    plan.level != null -> R.string.share_sheet_title
    else -> R.string.share_sheet_title_add
  },
)

@Composable
private fun blockedTitle(reason: ShareBlock): String =
  stringResource(if (WhyBlocked.of(reason) == WhyBlocked.AFTER_TESTS) R.string.share_blocked_tests_title else R.string.share_blocked_title)

@Composable
private fun blockedDetail(reason: ShareBlock): String {
  val why = WhyBlocked.of(reason)
  return stringResource(if (why == WhyBlocked.AFTER_TESTS) R.string.share_blocked_tests_detail else AlbumText.blocked(why))
}

/** Un foglio che dice una cosa e basta: non si puo', non c'e' niente da aggiungere, il livello e' cambiato. Un bottone: "Chiudi". */
@Composable
private fun InfoContent(title: String, detail: String, handlers: ShareSheetHandlers, extra: @Composable () -> Unit = {}) {
  GallerySheetScaffold(
    icon = Icons.Rounded.Warning,
    tone = GallerySheetTone.Caution,
    title = title,
    message = detail,
    body = { extra() },
    actions = { FluidButton(text = stringResource(R.string.share_close), onClick = handlers::onDismiss, style = FluidButtonStyle.Tinted, size = FluidButtonSize.Large, fillWidth = true) },
  )
}

@Composable
private fun PlanContent(state: ShareSheetState.Plan, busy: Boolean, handlers: ShareSheetHandlers) {
  val plan = state.plan
  val context = LocalContext.current
  val sharedKind = plan.kind == SharePlanKind.SHARE || plan.kind == SharePlanKind.ADOPT
  GallerySheetScaffold(
    icon = when (plan.kind) {
      SharePlanKind.SHARE -> if (plan.level != null) Icons.Rounded.Group else Icons.Rounded.AddPhotoAlternate
      SharePlanKind.BRING_BACK -> Icons.Rounded.Undo
      SharePlanKind.ADOPT -> Icons.Rounded.FolderOpen
    },
    tone = if (plan.kind == SharePlanKind.BRING_BACK) GallerySheetTone.Caution else GallerySheetTone.Brand,
    title = planTitle(plan),
    message = AlbumText.planMessage(context, plan),
    body = {
      state.notice?.let { notice ->
        FluidInlineMessage(
          message = stringResource(if (notice == ShareSheetNotice.CHANGED) R.string.share_notice_changed else R.string.share_notice_expired),
          title = stringResource(R.string.share_sheet_title),
          tone = FluidTone.Warning,
        )
      }
      // Prima le scelte (il foglio scorre: quello che si decide non deve stare sotto i dettagli), poi i dettagli. I nascosti per primi: e' la scelta con la
      // conseguenza piu' seria (E-SH9). Il livello alla creazione (Q2): tre righe, "Gli altri aggiungono" di partenza; su un album che c'e' gia' non si sceglie qui.
      HiddenGroup(plan, handlers)
      plan.level?.let { level -> LevelGroup(level, handlers) }
      ExcludedGroup(plan.excluded.filterKeys { it != ShareExclusion.HIDDEN_LEFT_OUT })
      WhereGroup(plan)
    },
    actions = {
      // Cio' che chi conferma deve sempre vedere sta **fisso** sopra il bottone, mai sotto lo scorrimento: l'avviso dei nascosti che diventano visibili
      // (E-SH9, solo se si condividono) e la dichiarazione dei permessi (D10, L57).
      if (plan.kind == SharePlanKind.SHARE && plan.hiddenIncluded > 0) {
        FluidInlineMessage(
          message = LocalContext.current.resources.getQuantityString(R.plurals.share_hidden_warning, plan.hiddenIncluded, plan.hiddenIncluded),
          title = stringResource(R.string.share_group_hidden),
          tone = FluidTone.Warning,
        )
      }
      // Revisione B (SG-1, D28): il guardiano e' fermo dai cancelli del backup; per questi media la copia privata non arrivera' subito. Il foglio lo
      // dice prima del tocco (la conferma vale anche per questo: e' nell'impronta del piano).
      if (plan.safeCopyBlocked > 0) {
        FluidInlineMessage(
          message = LocalContext.current.resources.getQuantityString(R.plurals.share_safe_copy_blocked, plan.safeCopyBlocked, plan.safeCopyBlocked),
          title = stringResource(R.string.share_safe_copy_blocked_title),
          tone = FluidTone.Warning,
        )
      }
      if (sharedKind) {
        Text(
          text = stringResource(R.string.album_declaration),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurface,
        )
      }
      FluidButton(
        text = stringResource(if (busy) R.string.share_confirming else confirmLabel(plan)),
        onClick = handlers::onConfirm,
        enabled = !busy,
        loading = busy,
        size = FluidButtonSize.Large,
        fillWidth = true,
      )
      CancelButton(handlers::onDismiss, label = stringResource(R.string.share_cancel))
    },
  )
}

private fun confirmLabel(plan: SharePlan): Int = when {
  plan.kind == SharePlanKind.BRING_BACK -> R.string.share_confirm_bring_back
  plan.kind == SharePlanKind.ADOPT -> R.string.share_confirm_adopt
  plan.level != null -> R.string.share_confirm
  else -> R.string.share_confirm_add
}

/** Dove vanno i media: l'album, la cartella, quanti si spostano, quanti si caricano, quanti ci sono gia', lo spazio richiesto. */
@Composable
private fun WhereGroup(plan: SharePlan) {
  val context = LocalContext.current
  val count = { action: ShareItemAction -> plan.items.count { it.action == action } }
  FluidListGroup {
    val rows = buildList<Pair<String, String>> {
      if (plan.kind != SharePlanKind.BRING_BACK) {
        add(stringResource(R.string.share_row_album) to plan.title)
        add(stringResource(R.string.share_row_folder) to plan.folderLabel)
      }
      if (count(ShareItemAction.MOVE_IN) > 0) add(stringResource(R.string.share_row_move) to count(ShareItemAction.MOVE_IN).toString())
      if (count(ShareItemAction.UPLOAD) > 0) add(stringResource(R.string.share_row_upload) to count(ShareItemAction.UPLOAD).toString())
      if (count(ShareItemAction.REFERENCE) > 0) add(stringResource(R.string.share_row_reference) to count(ShareItemAction.REFERENCE).toString())
      if (count(ShareItemAction.MOVE_OUT) > 0) add(stringResource(R.string.share_row_move_out) to count(ShareItemAction.MOVE_OUT).toString())
      if (count(ShareItemAction.ADOPT) > 0) add(stringResource(R.string.share_row_adopt) to count(ShareItemAction.ADOPT).toString())
      if (plan.requiredBytes > 0) add(stringResource(R.string.share_row_space) to stringResource(R.string.share_space_value, Formatter.formatFileSize(context, plan.requiredBytes)))
    }
    rows.forEachIndexed { index, (label, value) ->
      if (index > 0) FluidListDivider()
      FluidListRow(title = label, subtitle = "", disclosure = false, badge = { FluidRowValue(text = value) })
    }
  }
}

/** Il livello di un album nuovo: "Tutti possono tutto", "Gli altri aggiungono" (di partenza), "Gli altri guardano". */
@Composable
private fun LevelGroup(current: ShareLevel, handlers: ShareSheetHandlers) {
  Text(stringResource(R.string.share_group_level), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
  FluidListGroup {
    ShareLevel.entries.forEachIndexed { index, level ->
      if (index > 0) FluidListDivider()
      OptionRow(title = stringResource(AlbumText.level(level)), detail = stringResource(AlbumText.levelDetail(level)), selected = level == current, onSelect = { handlers.onLevel(level) })
    }
  }
}

/** I nascosti (Q3, E-SH9): la scelta c'e' solo se ce ne sono; «Lascia fuori» e' quella di partenza; includerli porta l'avviso. */
@Composable
private fun HiddenGroup(plan: SharePlan, handlers: ShareSheetHandlers) {
  val total = plan.hiddenIncluded + plan.hiddenLeftOut
  if (total == 0 || plan.kind != SharePlanKind.SHARE) return
  val resources = LocalContext.current.resources
  Text(stringResource(R.string.share_group_hidden), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
  FluidListGroup {
    OptionRow(
      title = resources.getQuantityString(R.plurals.share_hidden_leave, total, total),
      detail = "",
      selected = plan.hidden == HiddenChoice.LEAVE_OUT,
      onSelect = { handlers.onHiddenChoice(HiddenChoice.LEAVE_OUT) },
    )
    FluidListDivider()
    OptionRow(
      title = stringResource(R.string.share_hidden_include),
      detail = "",
      selected = plan.hidden == HiddenChoice.SHARE_TOO,
      onSelect = { handlers.onHiddenChoice(HiddenChoice.SHARE_TOO) },
    )
  }
}

/** Cosa resta fuori e perche' ("3 gia' nell'album"). I nascosti lasciati fuori hanno la loro scelta, non sono qui. */
@Composable
private fun ExcludedGroup(excluded: Map<ShareExclusion, Int>) {
  val rows = excluded.filterValues { it > 0 }
  if (rows.isEmpty()) return
  val resources = LocalContext.current.resources
  Text(stringResource(R.string.share_group_excluded), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
  FluidListGroup {
    rows.entries.forEachIndexed { index, (reason, n) ->
      if (index > 0) FluidListDivider()
      FluidListRow(title = AlbumText.excluded(resources, reason, n), subtitle = "", disclosure = false)
    }
  }
}
