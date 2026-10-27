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
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
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
import dev.pampa.mycloudgallery.feature.nas.ui.NasSelectDot
import dev.pampa.mycloudgallery.feature.nas.ui.NasText
import dev.pampa.mycloudgallery.feature.nas.ui.countText

// I fogli di conferma delle operazioni sul NAS (docs/fase4-progetto.md §2.7 "Regole comuni", D24, Q8): il riepilogo con numero e
// dimensione ("Spostare 312 elementi, 4,1 GB, in Famiglia / Viaggi?"), le cartelle intere, lo spostamento nell'area comune ("le
// vedranno tutti i familiari") e "Elimina subito" con doppia conferma e l'avviso sul backup esterno.
//
// **I nascosti (R11).** Spostando nell'area comune dei file che l'utente ha nascosto, i familiari li vedrebbero: il foglio lo dice e
// chiede, con due scelte, "lascia i nascosti dove sono" (quella di partenza, la prudente) o "sposta tutto". Sono **due piani**, non
// due modi di leggere lo stesso: ognuno ha la sua impronta (il secondo non contiene i nascosti e li elenca fra gli esclusi), e la
// conferma nasce dal piano scelto in quel momento, quindi non puo' avviare l'altro.
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
  /** R2: i file che l'operazione tocca davvero, contando quelli dentro le cartelle da eliminare (contati alla pianificazione). */
  val fileCount: Int,
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
  /** R11: quanti degli elementi che vanno nell'area comune l'utente ha nascosto (li vedranno tutti i familiari). */
  val hiddenCount: Int = 0,
) {
  val hasFolders: Boolean get() = folderCount > 0

  /** R2: l'eliminazione porta nel cestino molti file: il foglio lo dice in evidenza. */
  val manyFiles: Boolean get() = PlanWarning.MANY_FILES in warnings
}

/** Lo spostamento mette nell'area comune cose che stanno altrove ("le vedranno tutti i familiari"): vale anche per la schermata. */
internal fun movesIntoCommon(plan: OpPlan, areas: List<NasArea>): Boolean {
  val destShare = plan.summary.destShare ?: return false
  val destArea = areas.areaOf(destShare)
  val fromElsewhere = plan.items.any { !it.srcShare.equals(destShare, ignoreCase = true) }
  return plan.kind == OpKind.MOVE && destArea?.kind == NasAreaKind.COMMON && fromElsewhere
}

internal fun confirmFacts(plan: OpPlan, areas: List<NasArea>, hidden: Int = 0): ConfirmFacts {
  val summary = plan.summary
  val destArea = summary.destShare?.let { areas.areaOf(it) }
  val intoCommon = movesIntoCommon(plan, areas)
  return ConfirmFacts(
    kind = plan.kind,
    itemCount = summary.itemCount,
    folderCount = summary.folderCount,
    fileCount = summary.fileCount,
    totalBytes = summary.totalBytes,
    destArea = destArea,
    destShare = summary.destShare,
    destFolder = summary.destFolder?.components.orEmpty(),
    crossShare = PlanWarning.CROSS_SHARE_COPY in plan.warnings,
    intoCommon = intoCommon,
    skipped = plan.skipped.groupingBy { it.reason }.eachCount().toList().sortedBy { it.first.ordinal },
    warnings = plan.warnings,
    hiddenCount = if (intoCommon) hidden.coerceAtLeast(0) else 0,
  )
}

/**
 * R11: la scelta sui nascosti nel foglio di uno spostamento nell'area comune.
 *
 * @property count quanti nascosti ci sono fra gli elementi spostati.
 * @property leave "Lascia i nascosti dove sono" e' la scelta di adesso (quella di partenza: e' la piu' prudente).
 * @property somethingLeft lasciando i nascosti resta qualcosa da spostare; se no, la scelta c'e' ma non c'e' niente da confermare.
 */
internal class HiddenChoice(
  val count: Int,
  val leave: Boolean,
  val somethingLeft: Boolean,
  val onChange: (Boolean) -> Unit,
)

/** Cio' che il foglio sta mostrando: il piano, e per i nascosti il piano alternativo che li lascia dove sono. */
@Immutable
private class Offer(val plan: OpPlan, val hidden: Int, val leavePlan: OpPlan?)

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
 * @param hidden R11: quanti elementi del piano l'utente ha nascosto (per uno spostamento nell'area comune).
 * @param leavePlan R11: lo stesso spostamento **senza** i nascosti, che restano dove sono; null = lasciandoli non resterebbe niente da
 *   spostare. Con dei nascosti il foglio offre le due scelte e [onConfirmed] riceve il piano scelto, con la conferma di quel piano.
 */
@Composable
internal fun NasConfirmSheet(
  plan: OpPlan?,
  areas: List<NasArea>,
  monotonicMs: () -> Long,
  onConfirmed: (OpPlan, UserConfirmation) -> Unit,
  onDismiss: () -> Unit,
  hidden: Int = 0,
  leavePlan: OpPlan? = null,
) {
  // Il foglio che si chiude fa la sua uscita con il contenuto che aveva, non a vuoto.
  var shown by remember { mutableStateOf(plan?.let { Offer(it, hidden, leavePlan) }) }
  val current = shown
  if (plan != null && (current == null || plan !== current.plan || hidden != current.hidden || leavePlan !== current.leavePlan)) {
    shown = Offer(plan, hidden, leavePlan)
  }
  val showing = shown
  GalleryConfirmSheet(
    visible = plan != null,
    onDismissRequest = onDismiss,
    paneTitle = if (showing != null) confirmTitle(showing.plan, last = false) else "",
  ) {
    if (showing != null) NasConfirmBody(offer = showing, areas = areas, monotonicMs = monotonicMs, onConfirmed = onConfirmed, onDismiss = onDismiss)
  }
}

@Composable
private fun ColumnScope.NasConfirmBody(
  offer: Offer,
  areas: List<NasArea>,
  monotonicMs: () -> Long,
  onConfirmed: (OpPlan, UserConfirmation) -> Unit,
  onDismiss: () -> Unit,
) {
  val plan = offer.plan
  // R11: con dei nascosti da portare nell'area comune si sceglie fra due piani; di partenza quello che li lascia dov'erano.
  val choosing = remember(plan, areas, offer.hidden) { confirmFacts(plan, areas, offer.hidden).hiddenCount > 0 }
  var leave by remember(plan, offer.leavePlan) { mutableStateOf(true) }
  // Il piano che questo foglio confermerebbe adesso: null = lasciando i nascosti non resterebbe niente da spostare.
  val chosen: OpPlan? = if (choosing && leave) offer.leavePlan else plan
  // R6: il foglio sa quando e' comparso; per "Elimina subito" un tocco troppo vicino (doppio tocco) non conta.
  val sheet = remember(chosen) { chosen?.let { ConfirmationSheet(it, shownAtMonotonicMs = monotonicMs()) } }
  var taps by remember(chosen) { mutableIntStateOf(0) }
  NasConfirmSheetContent(
    plan = chosen ?: plan,
    areas = areas,
    hidden = if (chosen == null || chosen === plan) offer.hidden else 0,
    choice = if (choosing) {
      HiddenChoice(count = offer.hidden, leave = leave, somethingLeft = offer.leavePlan != null, onChange = { leave = it })
    } else {
      null
    },
    tapsDone = taps,
    onConfirm = {
      // L'unico punto del modulo in cui nasce una conferma: un tocco dell'utente sul pulsante di conferma.
      val confirmation = sheet?.tap(monotonicMs())
      taps = sheet?.tapsDone ?: 0
      if (confirmation != null && chosen != null) onConfirmed(chosen, confirmation)
    },
    onCancel = {
      sheet?.reset()
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
  hidden: Int = 0,
  choice: HiddenChoice? = null,
) {
  val facts = remember(plan, areas, hidden) { confirmFacts(plan, areas, hidden) }
  val last = tapsDone >= plan.requiredConfirmations - 1
  // R11: lasciando i nascosti non resta niente da spostare: la scelta si legge, ma il pulsante non ha niente da confermare.
  val nothingToMove = choice != null && choice.leave && !choice.somethingLeft
  GallerySheetScaffold(
    icon = confirmIcon(facts.kind),
    tone = confirmTone(facts.kind),
    title = confirmTitle(plan, last),
    message = confirmMessage(facts, areas, last),
    modifier = modifier.widthIn(max = NasDimens.SheetMaxWidth),
    body = {
      SummaryGroup(facts)
      if (choice != null) HiddenChoiceGroup(choice)
      NotesGroup(facts, last, hiddenNote = choice == null)
    },
    actions = {
      FluidButton(
        text = if (nothingToMove) stringResource(R.string.nas_confirm_action_nothing_to_move) else confirmActionLabel(facts, last),
        onClick = onConfirm,
        enabled = !nothingToMove,
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
        SkipReason.LEFT_BEHIND -> R.string.nas_confirm_skipped_left_behind
        // Fase 7, revisione B (R3, E-D16).
        SkipReason.SHARED_ALBUM_ITEM -> R.string.nas_confirm_skipped_shared_album_item
      },
    ),
    NasText.count(count),
  )
}.joinToString(", ")

/**
 * R11: le due scelte per i nascosti, due righe da 48 dp con la loro conseguenza scritta sotto, quella scelta con il cerchio pieno. Come la
 * scelta del luogo nel foglio "Elimina" della Libreria: per una decisione che ha conseguenze e' la forma piu' chiara e piu' facile da toccare.
 * Prima la scelta prudente, che e' anche quella di partenza.
 */
@Composable
private fun HiddenChoiceGroup(choice: HiddenChoice) {
  val number = GalleryNumbers.format(choice.count)
  FluidListGroup {
    ChoiceRow(
      title = pluralStringResource(R.plurals.nas_confirm_hidden_leave, choice.count, number),
      detail = stringResource(if (choice.somethingLeft) R.string.nas_confirm_hidden_leave_detail else R.string.nas_confirm_hidden_leave_nothing),
      icon = Icons.Rounded.VisibilityOff,
      tone = FluidTone.Primary,
      selected = choice.leave,
      onSelect = { choice.onChange(true) },
    )
    FluidListDivider()
    ChoiceRow(
      title = pluralStringResource(R.plurals.nas_confirm_hidden_all, choice.count, number),
      detail = stringResource(R.string.nas_confirm_hidden_all_detail),
      icon = Icons.Rounded.Group,
      tone = FluidTone.Warning,
      selected = !choice.leave,
      onSelect = { choice.onChange(false) },
    )
  }
}

@Composable
private fun ChoiceRow(title: String, detail: String, icon: ImageVector, tone: FluidTone, selected: Boolean, onSelect: () -> Unit) {
  FluidListRow(
    title = title,
    subtitle = detail,
    titleMaxLines = 4,
    tone = tone,
    disclosure = false,
    selected = selected,
    onClick = onSelect,
    leading = { Icon(imageVector = icon, contentDescription = null) },
    badge = { NasSelectDot(selected = selected) },
    modifier = Modifier.semantics(mergeDescendants = true) {
      role = Role.RadioButton
      this.selected = selected
    },
  )
}

/** Cosa succede, una riga per cosa: l'area comune, la copia tra aree, le cartelle intere, il cestino, il backup. */
@Composable
private fun NotesGroup(facts: ConfirmFacts, last: Boolean, hiddenNote: Boolean = true) {
  val notes = buildList<Note> {
    // R2: molti file in un'eliminazione: per primo, in evidenza (il riepilogo e' la difesa contro la cartella sbagliata).
    if (facts.manyFiles && facts.kind == OpKind.TRASH) {
      add(Note(Icons.Rounded.Warning, FluidTone.Danger, R.string.nas_confirm_many_title, R.string.nas_confirm_many_detail, countText(R.plurals.nas_files_count, facts.fileCount)))
    }
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
    // R11: fra gli elementi che vanno nell'area comune ce ne sono di nascosti dall'utente: i familiari li vedranno.
    if (hiddenNote && facts.hiddenCount > 0) {
      add(Note(Icons.Rounded.VisibilityOff, FluidTone.Warning, R.string.nas_confirm_hidden_title, R.string.nas_confirm_hidden_detail, countText(R.plurals.nas_items_count, facts.hiddenCount)))
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
        subtitle = if (note.arg != null) stringResource(note.detail, note.arg) else stringResource(note.detail),
        tone = note.tone,
        disclosure = false,
        leading = { Icon(imageVector = note.icon, contentDescription = null) },
      )
    }
  }
}

private class Note(val icon: ImageVector, val tone: FluidTone, val title: Int, val detail: Int, val arg: String? = null)
