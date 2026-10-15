package dev.pampa.mycloudgallery.feature.nas.confirm

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.DriveFileMove
import androidx.compose.material.icons.rounded.CloudSync
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Group
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.RestoreFromTrash
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.material.icons.rounded.Update
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import dev.antigravity.fluidengine.ui.fluid.FluidButton
import dev.antigravity.fluidengine.ui.fluid.FluidButtonSize
import dev.antigravity.fluidengine.ui.fluid.FluidButtonStyle
import dev.antigravity.fluidengine.ui.fluid.FluidRowValue
import dev.antigravity.fluidengine.ui.theme.FluidListDivider
import dev.antigravity.fluidengine.ui.theme.FluidListGroup
import dev.antigravity.fluidengine.ui.theme.FluidListRow
import dev.antigravity.fluidengine.ui.theme.FluidTone
import dev.pampa.mycloudgallery.core.designsystem.sheet.GalleryConfirmSheet
import dev.pampa.mycloudgallery.core.designsystem.sheet.GallerySheetScaffold
import dev.pampa.mycloudgallery.core.designsystem.sheet.GallerySheetTone
import dev.pampa.mycloudgallery.core.library.text.GalleryNumbers
import dev.pampa.mycloudgallery.core.nasops.contract.ConfirmationSheet
import dev.pampa.mycloudgallery.core.nasops.contract.OpKind
import dev.pampa.mycloudgallery.core.nasops.contract.OpPlan
import dev.pampa.mycloudgallery.core.nasops.contract.PlanWarning
import dev.pampa.mycloudgallery.core.nasops.contract.SkipReason
import dev.pampa.mycloudgallery.core.nasops.contract.UserConfirmation
import dev.pampa.mycloudgallery.feature.nas.NasDimens
import dev.pampa.mycloudgallery.feature.nas.R
import dev.pampa.mycloudgallery.feature.nas.contract.NasArea
import dev.pampa.mycloudgallery.feature.nas.contract.NasAreaKind
import dev.pampa.mycloudgallery.feature.nas.contract.areaOf
import dev.pampa.mycloudgallery.feature.nas.ui.NasText
import dev.pampa.mycloudgallery.feature.nas.ui.countText

// I fogli di conferma delle operazioni sul NAS (docs/fase4-progetto.md §2.7 "Regole comuni", D24, Q8): il riepilogo con numero e
// dimensione ("Spostare 312 elementi, 4,1 GB, in Famiglia / Viaggi?"), le cartelle intere, lo spostamento nell'area comune ("le
// vedranno tutti i familiari") e "Elimina subito" con doppia conferma e l'avviso sul backup esterno.
//
// **Una conferma nasce da un tocco.** Il foglio e' [ConfirmationSheet] (la macchina a tocchi di core-nasops) avvolta in una
// composizione; la [UserConfirmation] esce solo dal gestore del pulsante di conferma, dopo il numero di tocchi che il piano chiede (2
// per "Elimina subito"). Nessun'altra riga di questo modulo costruisce una conferma, e nessun dato letto dal NAS arriva qui se non
// dentro un [OpPlan] che l'utente ha davanti (`ConfirmationTapTest` lo verifica sul codice).

/** Quello che un foglio deve dire di un piano, ricavato dal piano e dalle aree. Pura: la provano i test su JVM. */
internal data class ConfirmFacts(
  val kind: OpKind,
  val itemCount: Int,
  val folderCount: Int,
  val totalBytes: Long,
  /** L'area di arrivo (spostamenti), se e' fra quelle dell'utente. */
  val destArea: NasArea?,
  val destShare: String?,
  /** La cartella di arrivo, componente per componente; vuota = la radice dell'area. */
  val destFolder: List<String>,
  /** Tra share diverse: copia lato server, verifica completa, sorgente nel cestino. */
  val crossShare: Boolean,
  /** Lo spostamento mette nell'area comune cose che stanno altrove: "le vedranno tutti i familiari". */
  val intoCommon: Boolean,
  val skipped: List<Pair<SkipReason, Int>>,
  val warnings: Set<PlanWarning>,
) {
  val fileCount: Int get() = itemCount - folderCount
  val hasFolders: Boolean get() = folderCount > 0
}

internal fun confirmFacts(plan: OpPlan, areas: List<NasArea>): ConfirmFacts {
  val summary = plan.summary
  val destArea = summary.destShare?.let { areas.areaOf(it) }
  val fromElsewhere = summary.destShare != null && plan.items.any { !it.srcShare.equals(summary.destShare, ignoreCase = true) }
  return ConfirmFacts(
    kind = plan.kind,
    itemCount = summary.itemCount,
    folderCount = summary.folderCount,
    totalBytes = summary.totalBytes,
    destArea = destArea,
    destShare = summary.destShare,
    destFolder = summary.destFolder?.components.orEmpty(),
    crossShare = PlanWarning.CROSS_SHARE_COPY in plan.warnings,
    intoCommon = plan.kind == OpKind.MOVE && destArea?.kind == NasAreaKind.COMMON && fromElsewhere,
    skipped = plan.skipped.groupingBy { it.reason }.eachCount().toList().sortedBy { it.first.ordinal },
    warnings = plan.warnings,
  )
}

/** Il nome di un'area per le persone: quello scelto da `:app`, o "La mia area" / "Area comune". */
@Composable
internal fun areaLabel(share: String?, areas: List<NasArea>): String {
  val area = share?.let { areas.areaOf(it) }
  return when {
    area?.label != null -> area.label
    area?.kind == NasAreaKind.PRIVATE -> stringResource(R.string.nas_area_private)
    area?.kind == NasAreaKind.COMMON -> stringResource(R.string.nas_area_common)
    else -> stringResource(R.string.nas_area_unknown)
  }
}

/** "Area comune / Famiglia / Viaggi". */
@Composable
internal fun destinationText(facts: ConfirmFacts, areas: List<NasArea>): String =
  NasText.path(listOf(areaLabel(facts.destShare, areas)) + facts.destFolder)

/**
 * Il foglio di conferma di un piano, **con la sua macchina a tocchi**: sceglie il pannello (il primo, o l'ultimo di "Elimina subito"),
 * costruisce la [UserConfirmation] solo dal gestore del pulsante di conferma e la consegna a [onConfirmed].
 *
 * @param plan il piano da confermare; null = il foglio e' chiuso.
 * @param monotonicMs l'orologio monotono con cui si data la conferma (lo stesso con cui `NasOperations.start` la controlla).
 */
@Composable
internal fun NasConfirmSheet(
  plan: OpPlan?,
  areas: List<NasArea>,
  monotonicMs: () -> Long,
  onConfirmed: (OpPlan, UserConfirmation) -> Unit,
  onDismiss: () -> Unit,
) {
  // Il foglio che si chiude fa la sua uscita con il contenuto che aveva, non a vuoto.
  var shown by remember { mutableStateOf(plan) }
  if (plan != null && plan !== shown) shown = plan
  val showing = shown
  GalleryConfirmSheet(
    visible = plan != null,
    onDismissRequest = onDismiss,
    paneTitle = if (showing != null) confirmTitle(showing, last = false) else "",
  ) {
    if (showing != null) NasConfirmBody(plan = showing, areas = areas, monotonicMs = monotonicMs, onConfirmed = onConfirmed, onDismiss = onDismiss)
  }
}

@Composable
private fun ColumnScope.NasConfirmBody(
  plan: OpPlan,
  areas: List<NasArea>,
  monotonicMs: () -> Long,
  onConfirmed: (OpPlan, UserConfirmation) -> Unit,
  onDismiss: () -> Unit,
) {
  val sheet = remember(plan) { ConfirmationSheet(plan) }
  var taps by remember(plan) { mutableIntStateOf(0) }
  NasConfirmSheetContent(
    plan = plan,
    areas = areas,
    tapsDone = taps,
    onConfirm = {
      // L'unico punto del modulo in cui nasce una conferma: un tocco dell'utente sul pulsante di conferma.
      val confirmation = sheet.tap(monotonicMs())
      taps = sheet.tapsDone
      if (confirmation != null) onConfirmed(plan, confirmation)
    },
    onCancel = {
      sheet.reset()
      taps = 0
      onDismiss()
    },
  )
}

/**
 * Il contenuto del foglio, senza stato: [tapsDone] dice a che punto e' la doppia conferma (0 = il primo pannello). Lo mostrano il
 * foglio vero e gli screenshot. [onConfirm] lo chiama solo il pulsante di conferma.
 */
@Composable
internal fun NasConfirmSheetContent(
  plan: OpPlan,
  areas: List<NasArea>,
  tapsDone: Int,
  onConfirm: () -> Unit,
  onCancel: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val facts = remember(plan, areas) { confirmFacts(plan, areas) }
  val last = tapsDone >= plan.requiredConfirmations - 1
  GallerySheetScaffold(
    icon = confirmIcon(facts.kind),
    tone = confirmTone(facts.kind),
    title = confirmTitle(plan, last),
    message = confirmMessage(facts, areas, last),
    modifier = modifier.widthIn(max = NasDimens.SheetMaxWidth),
    body = {
      SummaryGroup(facts)
      NotesGroup(facts, last)
    },
    actions = {
      FluidButton(
        text = confirmActionLabel(facts, last),
        onClick = onConfirm,
        style = if (facts.kind == OpKind.TRASH || facts.kind.permanent) FluidButtonStyle.Destructive else FluidButtonStyle.Filled,
        size = FluidButtonSize.Large,
        fillWidth = true,
      )
      FluidButton(
        text = stringResource(R.string.nas_cancel),
        onClick = onCancel,
        style = FluidButtonStyle.Tinted,
        size = FluidButtonSize.Large,
        fillWidth = true,
      )
    },
  )
}

@Composable
private fun confirmTitle(plan: OpPlan, last: Boolean): String {
  val count = plan.summary.itemCount
  val number = GalleryNumbers.format(count)
  return when (plan.kind) {
    OpKind.MOVE, OpKind.RENAME -> pluralStringResource(R.plurals.nas_confirm_move_title, count, number)
    OpKind.TRASH -> pluralStringResource(R.plurals.nas_confirm_trash_title, count, number)
    OpKind.RESTORE -> pluralStringResource(R.plurals.nas_confirm_restore_title, count, number)
    OpKind.PURGE_NOW -> if (last && plan.requiredConfirmations > 1) {
      stringResource(R.string.nas_confirm_purge_last_title)
    } else {
      pluralStringResource(R.plurals.nas_confirm_purge_title, count, number)
    }
    OpKind.CREATE_FOLDER -> stringResource(R.string.nas_name_new_folder_title)
  }
}

@Composable
private fun confirmMessage(facts: ConfirmFacts, areas: List<NasArea>, last: Boolean): String = when (facts.kind) {
  OpKind.MOVE, OpKind.RENAME -> if (facts.destShare != null) {
    stringResource(R.string.nas_confirm_move_message, destinationText(facts, areas))
  } else {
    stringResource(R.string.nas_confirm_move_message_unknown)
  }
  OpKind.TRASH -> stringResource(R.string.nas_confirm_trash_message)
  OpKind.RESTORE -> stringResource(R.string.nas_confirm_restore_message)
  OpKind.PURGE_NOW -> stringResource(if (last) R.string.nas_confirm_purge_last_message else R.string.nas_confirm_purge_message)
  OpKind.CREATE_FOLDER -> ""
}

@Composable
private fun confirmActionLabel(facts: ConfirmFacts, last: Boolean): String = stringResource(
  when (facts.kind) {
    OpKind.MOVE, OpKind.RENAME -> if (facts.intoCommon) R.string.nas_confirm_action_move_common else R.string.nas_confirm_action_move
    OpKind.TRASH -> R.string.nas_confirm_action_trash
    OpKind.RESTORE -> R.string.nas_confirm_action_restore
    OpKind.PURGE_NOW -> if (last) R.string.nas_confirm_action_purge_now else R.string.nas_confirm_action_purge_continue
    OpKind.CREATE_FOLDER -> R.string.nas_name_new_folder_action
  },
)

internal fun confirmIcon(kind: OpKind): ImageVector = when (kind) {
  OpKind.MOVE, OpKind.RENAME -> Icons.AutoMirrored.Rounded.DriveFileMove
  OpKind.TRASH, OpKind.PURGE_NOW -> Icons.Rounded.DeleteForever
  OpKind.RESTORE -> Icons.Rounded.RestoreFromTrash
  OpKind.CREATE_FOLDER -> Icons.Rounded.Folder
}

internal fun confirmTone(kind: OpKind): GallerySheetTone = when (kind) {
  OpKind.TRASH, OpKind.PURGE_NOW -> GallerySheetTone.Destructive
  else -> GallerySheetTone.Brand
}

/**
 * Il numero e la dimensione: le due cose che l'utente deve aver letto prima di toccare, in una riga sola ("312 elementi" e "4,1 GB",
 * con sotto "2 cartelle e 310 file"). In una scheda piena, non sul vetro; quello che resta fuori ("restano dove sono") ha la sua riga.
 */
@Composable
private fun SummaryGroup(facts: ConfirmFacts) {
  val items = countText(R.plurals.nas_items_count, facts.itemCount)
  val detail = if (facts.hasFolders) {
    stringResource(R.string.nas_confirm_folders_and_files, countText(R.plurals.nas_folders_count, facts.folderCount), countText(R.plurals.nas_files_count, facts.fileCount))
  } else {
    ""
  }
  val size = NasText.size(facts.totalBytes)
  FluidListGroup {
    FluidListRow(
      title = items,
      subtitle = detail,
      titleMaxLines = 2,
      disclosure = false,
      badge = { FluidRowValue(text = size) },
      modifier = Modifier.semantics(mergeDescendants = true) { contentDescription = listOf(items, detail, size).filter { it.isNotEmpty() }.joinToString(", ") },
    )
    if (facts.skipped.isNotEmpty()) {
      FluidListDivider()
      FluidListRow(
        title = stringResource(R.string.nas_confirm_row_skipped),
        subtitle = skippedText(facts.skipped),
        disclosure = false,
        badge = { FluidRowValue(text = NasText.count(facts.skipped.sumOf { it.second })) },
      )
    }
  }
}

@Composable
private fun skippedText(skipped: List<Pair<SkipReason, Int>>): String = skipped.map { (reason, count) ->
  stringResource(
    R.string.nas_confirm_skipped_line,
    stringResource(
      when (reason) {
        SkipReason.READ_ONLY -> R.string.nas_confirm_skipped_read_only
        SkipReason.SAME_LOCATION -> R.string.nas_confirm_skipped_same_location
        SkipReason.INTO_ITSELF -> R.string.nas_confirm_skipped_into_itself
        SkipReason.SOURCE_MISSING -> R.string.nas_confirm_skipped_source_missing
      },
    ),
    NasText.count(count),
  )
}.joinToString(", ")

/** Cosa succede, una riga per cosa: l'area comune, la copia tra aree, le cartelle intere, il cestino, il backup. */
@Composable
private fun NotesGroup(facts: ConfirmFacts, last: Boolean) {
  val notes = buildList<Note> {
    if (facts.kind == OpKind.PURGE_NOW) {
      val noRestore = Note(Icons.Rounded.Lock, FluidTone.Danger, R.string.nas_confirm_purge_note_title, R.string.nas_confirm_purge_note_detail)
      val backup = Note(Icons.Rounded.Save, FluidTone.Danger, R.string.nas_confirm_backup_title, R.string.nas_confirm_backup_detail)
      // Nell'ultimo pannello l'avviso sul backup esterno viene per primo: e' l'ultima cosa che si legge prima di toccare.
      if (last) {
        add(backup)
        add(noRestore)
      } else {
        add(noRestore)
        add(backup)
      }
    }
    if (facts.intoCommon) {
      add(Note(Icons.Rounded.Group, FluidTone.Info, R.string.nas_confirm_common_title, R.string.nas_confirm_common_detail))
    }
    if (facts.crossShare) {
      add(Note(Icons.Rounded.CloudSync, FluidTone.Neutral, R.string.nas_confirm_cross_title, R.string.nas_confirm_cross_detail))
    }
    if (facts.hasFolders && facts.kind != OpKind.PURGE_NOW) {
      add(
        when (facts.kind) {
          OpKind.TRASH -> Note(Icons.Rounded.Folder, FluidTone.Primary, R.string.nas_confirm_folders_trash_title, R.string.nas_confirm_folders_trash_detail)
          OpKind.RESTORE -> Note(Icons.Rounded.Folder, FluidTone.Primary, R.string.nas_confirm_folders_restore_title, R.string.nas_confirm_folders_restore_detail)
          else -> Note(Icons.Rounded.Folder, FluidTone.Primary, R.string.nas_confirm_folders_move_title, R.string.nas_confirm_folders_move_detail)
        },
      )
    }
    if (facts.kind == OpKind.TRASH) {
      add(Note(Icons.Rounded.Update, FluidTone.Neutral, R.string.nas_confirm_trash_note_title, R.string.nas_confirm_trash_note_detail))
    }
    if (facts.kind == OpKind.RESTORE) {
      add(Note(Icons.Rounded.Update, FluidTone.Neutral, R.string.nas_confirm_restore_note_title, R.string.nas_confirm_restore_note_detail))
    }
    if (PlanWarning.NAME_ADJUSTED in facts.warnings) {
      add(Note(Icons.Rounded.TextFields, FluidTone.Neutral, R.string.nas_confirm_names_title, R.string.nas_confirm_names_detail))
    }
  }
  if (notes.isEmpty()) return
  FluidListGroup {
    notes.forEachIndexed { index, note ->
      if (index > 0) FluidListDivider()
      FluidListRow(
        title = stringResource(note.title),
        subtitle = stringResource(note.detail),
        tone = note.tone,
        disclosure = false,
        leading = { Icon(imageVector = note.icon, contentDescription = null) },
      )
    }
  }
}

private class Note(val icon: ImageVector, val tone: FluidTone, val title: Int, val detail: Int)
