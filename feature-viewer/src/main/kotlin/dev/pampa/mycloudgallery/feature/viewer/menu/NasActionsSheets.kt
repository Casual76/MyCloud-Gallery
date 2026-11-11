package dev.pampa.mycloudgallery.feature.viewer.menu

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.DriveFileMove
import androidx.compose.material.icons.rounded.CloudSync
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.DriveFileRenameOutline
import androidx.compose.material.icons.rounded.Group
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.material3.Icon
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import dev.antigravity.fluidengine.ui.fluid.FluidButton
import dev.antigravity.fluidengine.ui.fluid.FluidButtonSize
import dev.antigravity.fluidengine.ui.fluid.FluidButtonStyle
import dev.antigravity.fluidengine.ui.fluid.FluidRowValue
import dev.antigravity.fluidengine.ui.fluid.FluidTextField
import dev.antigravity.fluidengine.ui.theme.FluidListDivider
import dev.antigravity.fluidengine.ui.theme.FluidListGroup
import dev.antigravity.fluidengine.ui.theme.FluidListRow
import dev.antigravity.fluidengine.ui.theme.FluidTone
import dev.pampa.mycloudgallery.core.designsystem.sheet.GalleryConfirmSheet
import dev.pampa.mycloudgallery.core.designsystem.sheet.GalleryDeleteLocation
import dev.pampa.mycloudgallery.core.designsystem.sheet.GalleryDeleteLocationKind
import dev.pampa.mycloudgallery.core.designsystem.sheet.GalleryDeleteScope
import dev.pampa.mycloudgallery.core.designsystem.sheet.GalleryDeleteSheetContent
import dev.pampa.mycloudgallery.core.designsystem.sheet.GallerySheetScaffold
import dev.pampa.mycloudgallery.core.designsystem.sheet.GallerySheetTone
import dev.pampa.mycloudgallery.core.nasops.contract.ConfirmationSheet
import dev.pampa.mycloudgallery.core.nasops.contract.OpPlan
import dev.pampa.mycloudgallery.core.nasops.contract.PlanWarning
import dev.pampa.mycloudgallery.core.nasops.contract.UserConfirmation
import dev.pampa.mycloudgallery.feature.viewer.R
import java.util.Locale

// I fogli delle voci "Elimina…", "Rinomina" e "Sposta" per un media del NAS. Sono fogli di conferma del design system
// (`GalleryConfirmSheet`, con la difesa dal tapjacking); la conferma dell'operazione nasce **solo** dal gestore del pulsante di
// conferma, dopo un tocco dell'utente, e passa a `NasActionsController.confirmed`. Niente nasce da una composizione o da un dato
// letto dal NAS (stessa regola di `feature-nas`, vedi `NasActionsTapTest`).

/**
 * I fogli delle voci del NAS per il [controller] del viewer. [onDeleteFromPhone] e' l'eliminazione dal telefono che il viewer ha gia'
 * (il cestino di sistema): si chiama per "Solo dal telefono" e, con "Ovunque", insieme al cestino del NAS.
 */
@Composable
fun NasActionSheets(
  controller: NasActionsController,
  onDeleteFromPhone: () -> Unit,
  extraLocations: List<GalleryDeleteLocation> = emptyList(),
  onPhoneOnlyDelete: (() -> Unit)? = null,
  /** LS-14: "Ovunque" toglie dal telefono tutte le copie del media (con il backup); senza, il cestino del viewer per quella di adesso. */
  onEverywherePhone: (() -> Unit)? = null,
) {
  val state by controller.state.collectAsState()
  NasActionSheets(
    state = state,
    monotonicMs = controller::monotonicMs,
    onScope = controller::setScope,
    onRenameText = controller::onRenameText,
    onPick = controller::pick,
    onConfirmed = controller::confirmed,
    // Fase 5B (D22): "Solo dal telefono" puo' passare dalla verifica del backup; "Ovunque" no (la copia del NAS sta per andare nel cestino).
    onPhoneOnly = {
      controller.phoneOnlyDone()
      (onPhoneOnlyDelete ?: onDeleteFromPhone)()
    },
    onEverywhereDone = onEverywherePhone ?: onDeleteFromPhone,
    onDismiss = controller::dismiss,
    extraLocations = extraLocations,
  )
}

/** I fogli per uno [state] dato, senza il controller: li provano i test e gli screenshot. */
@Composable
internal fun NasActionSheets(
  state: NasActionsState,
  monotonicMs: () -> Long,
  onScope: (GalleryDeleteScope) -> Unit,
  onRenameText: (String) -> Unit,
  onPick: (NasMoveDestination) -> Unit,
  onConfirmed: (OpPlan, UserConfirmation) -> Unit,
  onPhoneOnly: () -> Unit,
  onEverywhereDone: () -> Unit,
  onDismiss: () -> Unit,
  extraLocations: List<GalleryDeleteLocation> = emptyList(),
) {
  // Un foglio che si chiude fa la sua uscita con il contenuto che aveva, non a vuoto.
  var shown by remember { mutableStateOf<NasActionsState>(NasActionsState.Idle) }
  if (state !is NasActionsState.Idle) shown = state
  val showing = shown
  GalleryConfirmSheet(visible = state !is NasActionsState.Idle, onDismissRequest = onDismiss, paneTitle = sheetTitle(showing)) {
    when (showing) {
      NasActionsState.Idle -> Unit
      is NasActionsState.Delete -> DeleteBody(showing, monotonicMs, onScope, onConfirmed, onPhoneOnly, onEverywhereDone, onDismiss, extraLocations)
      is NasActionsState.Rename -> RenameBody(showing, monotonicMs, onRenameText, onConfirmed, onDismiss)
      is NasActionsState.PickDestination -> PickBody(showing, onPick, onDismiss)
      is NasActionsState.ConfirmMove -> MoveBody(showing, monotonicMs, onConfirmed, onDismiss)
    }
  }
}

@Composable
private fun sheetTitle(state: NasActionsState): String = when (state) {
  NasActionsState.Idle -> ""
  is NasActionsState.Delete -> stringResource(R.string.viewer_nas_action_delete)
  is NasActionsState.Rename -> stringResource(R.string.viewer_nas_rename_title)
  is NasActionsState.PickDestination -> stringResource(R.string.viewer_nas_move_pick_title)
  is NasActionsState.ConfirmMove -> stringResource(R.string.viewer_nas_move_common_title)
}

// --- Elimina… ---------------------------------------------------------------------------------------------------------------

@Composable
private fun ColumnScope.DeleteBody(
  state: NasActionsState.Delete,
  monotonicMs: () -> Long,
  onScope: (GalleryDeleteScope) -> Unit,
  onConfirmed: (OpPlan, UserConfirmation) -> Unit,
  onPhoneOnly: () -> Unit,
  onEverywhereDone: () -> Unit,
  onDismiss: () -> Unit,
  extraLocations: List<GalleryDeleteLocation>,
) {
  val plan = state.plan
  val sheet = remember(plan) { plan?.let { ConfirmationSheet(it) } }
  NasDeleteSheetContent(
    state = state,
    onScope = onScope,
    extraLocations = extraLocations,
    onConfirm = {
      // Il tocco sul pulsante: "Solo dal telefono" non tocca il NAS; "Ovunque" confida il piano del cestino del NAS con una conferma.
      when {
        state.scope == GalleryDeleteScope.PhoneOnly -> onPhoneOnly()
        plan != null && sheet != null -> {
          val confirmation = sheet.tap(monotonicMs())
          if (confirmation != null) {
            onConfirmed(plan, confirmation)
            if (state.target.onPhone) onEverywhereDone()
          }
        }
        // Il piano e' ancora in calcolo: un tocco adesso non fa niente (il pulsante ha il suo attimo di attesa).
        state.planning -> Unit
        // Niente da eliminare sul NAS (sola lettura, E-D11): "Ovunque" agisce dove si puo', cioe' sul telefono.
        else -> if (state.target.onPhone) {
          onPhoneOnly()
        }
      }
    },
    onCancel = {
      sheet?.reset()
      onDismiss()
    },
  )
}

/** Il contenuto del foglio "Elimina…", senza stato (lo mostrano il foglio vero e gli screenshot). */
@Composable
internal fun NasDeleteSheetContent(
  state: NasActionsState.Delete,
  onScope: (GalleryDeleteScope) -> Unit,
  onConfirm: () -> Unit,
  onCancel: () -> Unit,
  extraLocations: List<GalleryDeleteLocation> = emptyList(),
) {
  val target = state.target
  if (!target.onPhone) {
    // Sta solo sul NAS (E-D2): niente scelta del luogo, solo il cestino del NAS.
    val title = stringResource(if (target.isVideo) R.string.viewer_nas_delete_nas_only_video_title else R.string.viewer_nas_delete_nas_only_photo_title)
    GallerySheetScaffold(
      icon = Icons.Rounded.DeleteForever,
      tone = GallerySheetTone.Destructive,
      title = title,
      // Fase 5B (E-D15): anche le versioni precedenti vanno nel cestino del NAS, e il foglio lo dice prima di confermare.
      message = (listOf(stringResource(R.string.viewer_nas_delete_nas_only_message)) + extraLocations.map { it.title }).joinToString(" "),
      actions = {
        FluidButton(
          text = stringResource(R.string.viewer_nas_delete_nas_only_confirm),
          onClick = onConfirm,
          style = FluidButtonStyle.Destructive,
          size = FluidButtonSize.Large,
          fillWidth = true,
          enabled = !state.planning,
        )
        FluidButton(text = stringResource(R.string.viewer_nas_cancel), onClick = onCancel, style = FluidButtonStyle.Tinted, size = FluidButtonSize.Large, fillWidth = true)
      },
    )
    return
  }
  // Sul telefono e sul NAS (E-D1): "Solo dal telefono" / "Ovunque", con l'elenco dei posti e cosa succede in ognuno (E-D11).
  val nasKind = if (target.scope == NasAreaScope.COMMON) GalleryDeleteLocationKind.NasShared else GalleryDeleteLocationKind.NasPrivate
  val nasTitle = stringResource(if (target.scope == NasAreaScope.COMMON) R.string.viewer_nas_delete_nas_common else R.string.viewer_nas_delete_nas_private)
  val locations = buildList {
    // I4 (fase I): i 30 giorni nel cestino li dice una volta la nota in fondo al foglio; sulle righe resta solo cio' che cambia (sola lettura,
    // album protetto).
    add(GalleryDeleteLocation(stringResource(R.string.viewer_nas_delete_phone), GalleryDeleteLocationKind.Phone))
    add(
      GalleryDeleteLocation(
        title = nasTitle,
        kind = nasKind,
        detail = locationDetail(target.writable, target.albumProtected)?.let { stringResource(it) },
        deletable = target.writable && !target.albumProtected,
      ),
    )
    // Le versioni precedenti (E-D15) sono nella loro riga ([extraLocations]): qui solo le altre aree.
    target.others.filterNot { it.previousVersion }.forEach { other ->
      add(
        GalleryDeleteLocation(
          title = stringResource(if (other.kind == NasLocationInfo.Kind.NAS_COMMON) R.string.viewer_nas_delete_nas_common else R.string.viewer_nas_delete_nas_private),
          kind = if (other.kind == NasLocationInfo.Kind.NAS_COMMON) GalleryDeleteLocationKind.NasShared else GalleryDeleteLocationKind.NasPrivate,
          detail = locationDetail(other.writable, other.albumProtected)?.let { stringResource(it) },
          deletable = other.writable && !other.albumProtected,
        ),
      )
    }
    // Fase 5B (E-D15): le versioni precedenti che "Ovunque" mette nel cestino del NAS, elencate prima di confermare.
    addAll(extraLocations)
  }
  GalleryDeleteSheetContent(
    count = 1,
    scope = state.scope,
    onScopeChange = onScope,
    locations = locations,
    onConfirm = onConfirm,
    onCancel = onCancel,
    // Il titolo di serie del foglio parla di "foto": per un video lo dice il viewer, come fa gia' quando il media sta solo sul NAS.
    title = if (target.isVideo) stringResource(R.string.viewer_nas_delete_both_video_title) else null,
  )
}

/** Cosa succede a una copia del NAS con "Ovunque": nel cestino, oppure resta (sola lettura; revisione B, R3: elemento protetto di un album condiviso). */
private fun locationDetail(writable: Boolean, albumProtected: Boolean): Int? = when {
  !writable -> R.string.viewer_nas_delete_nas_read_only
  albumProtected -> R.string.viewer_nas_delete_nas_album_protected
  else -> null
}

// --- Rinomina ---------------------------------------------------------------------------------------------------------------

@Composable
private fun ColumnScope.RenameBody(
  state: NasActionsState.Rename,
  monotonicMs: () -> Long,
  onText: (String) -> Unit,
  onConfirmed: (OpPlan, UserConfirmation) -> Unit,
  onDismiss: () -> Unit,
) {
  val plan = (state.check as? RenameCheck.Ready)?.plan
  val sheet = remember(plan) { plan?.let { ConfirmationSheet(it) } }
  NasRenameSheetContent(
    state = state,
    onText = onText,
    onConfirm = {
      // Un tocco sul pulsante: e' la conferma del piano per il nome che si vede nel campo.
      val confirmation = sheet?.tap(monotonicMs())
      if (plan != null && confirmation != null) onConfirmed(plan, confirmation)
    },
    onCancel = {
      sheet?.reset()
      onDismiss()
    },
  )
}

@Composable
internal fun NasRenameSheetContent(state: NasActionsState.Rename, onText: (String) -> Unit, onConfirm: () -> Unit, onCancel: () -> Unit) {
  val check = state.check
  val problem = check is RenameCheck.Invalid || check is RenameCheck.Exists || check is RenameCheck.Same || check is RenameCheck.Refused
  val support = when (check) {
    RenameCheck.Empty -> null
    RenameCheck.Checking -> stringResource(R.string.viewer_nas_rename_checking)
    is RenameCheck.Ready -> stringResource(R.string.viewer_nas_rename_ok)
    is RenameCheck.Invalid -> stringResource(R.string.viewer_nas_rename_invalid)
    RenameCheck.Exists -> stringResource(R.string.viewer_nas_rename_exists)
    RenameCheck.Same -> stringResource(R.string.viewer_nas_rename_same)
    is RenameCheck.Refused -> stringResource(check.text)
  }
  GallerySheetScaffold(
    icon = Icons.Rounded.DriveFileRenameOutline,
    tone = GallerySheetTone.Brand,
    title = stringResource(R.string.viewer_nas_rename_title),
    message = stringResource(R.string.viewer_nas_rename_message),
    body = {
      FluidTextField(
        value = state.text,
        onValueChange = onText,
        label = stringResource(R.string.viewer_nas_rename_field),
        isError = problem,
        supportingText = support,
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
      )
      val suggestion = (check as? RenameCheck.Invalid)?.suggestion
      if (suggestion != null) {
        FluidButton(
          text = stringResource(R.string.viewer_nas_rename_suggestion, suggestion),
          onClick = { onText(suggestion) },
          style = FluidButtonStyle.Tinted,
          size = FluidButtonSize.Medium,
          fillWidth = true,
        )
      }
    },
    actions = {
      FluidButton(
        text = stringResource(R.string.viewer_nas_rename_confirm),
        onClick = onConfirm,
        enabled = check is RenameCheck.Ready,
        size = FluidButtonSize.Large,
        fillWidth = true,
      )
      FluidButton(text = stringResource(R.string.viewer_nas_cancel), onClick = onCancel, style = FluidButtonStyle.Tinted, size = FluidButtonSize.Large, fillWidth = true)
    },
  )
}

// --- Sposta ---------------------------------------------------------------------------------------------------------------

@Composable
private fun ColumnScope.PickBody(state: NasActionsState.PickDestination, onPick: (NasMoveDestination) -> Unit, onDismiss: () -> Unit) {
  NasPickSheetContent(state = state, onPick = onPick, onCancel = onDismiss)
}

/** Dove spostarlo: una riga per destinazione (le sceglie `:app` fra le aree dell'utente); toccarne una chiede il piano. */
@Composable
internal fun NasPickSheetContent(state: NasActionsState.PickDestination, onPick: (NasMoveDestination) -> Unit, onCancel: () -> Unit) {
  GallerySheetScaffold(
    icon = Icons.AutoMirrored.Rounded.DriveFileMove,
    tone = GallerySheetTone.Brand,
    title = stringResource(R.string.viewer_nas_move_pick_title),
    message = stringResource(R.string.viewer_nas_move_pick_message),
    body = {
      FluidListGroup {
        state.target.destinations.forEachIndexed { index, destination ->
          if (index > 0) FluidListDivider()
          FluidListRow(
            title = destination.label,
            subtitle = "",
            tone = if (destination.scope == NasAreaScope.COMMON) FluidTone.Info else FluidTone.Primary,
            leading = {
              Icon(imageVector = if (destination.scope == NasAreaScope.COMMON) Icons.Rounded.Group else Icons.Rounded.Person, contentDescription = null)
            },
            onClick = { onPick(destination) },
          )
        }
      }
    },
    actions = {
      FluidButton(text = stringResource(R.string.viewer_nas_cancel), onClick = onCancel, style = FluidButtonStyle.Tinted, size = FluidButtonSize.Large, fillWidth = true)
    },
  )
}

@Composable
private fun ColumnScope.MoveBody(
  state: NasActionsState.ConfirmMove,
  monotonicMs: () -> Long,
  onConfirmed: (OpPlan, UserConfirmation) -> Unit,
  onDismiss: () -> Unit,
) {
  val sheet = remember(state.plan) { ConfirmationSheet(state.plan) }
  NasMoveSheetContent(
    state = state,
    onConfirm = {
      // L'unico punto dove nasce la conferma di uno spostamento: un tocco sul pulsante.
      val confirmation = sheet.tap(monotonicMs())
      if (confirmation != null) onConfirmed(state.plan, confirmation)
    },
    onCancel = {
      sheet.reset()
      onDismiss()
    },
  )
}

/**
 * Il foglio di conferma dello spostamento: nell'area comune dice "le vedranno tutti i familiari"; fra aree diverse dice che prima si
 * copia e si controlla (D24); il numero e la dimensione sono sempre in vista.
 */
@Composable
internal fun NasMoveSheetContent(state: NasActionsState.ConfirmMove, onConfirm: () -> Unit, onCancel: () -> Unit) {
  val target = state.target
  val toCommon = state.destination.scope == NasAreaScope.COMMON && target.scope != NasAreaScope.COMMON
  val cross = PlanWarning.CROSS_SHARE_COPY in state.plan.warnings
  GallerySheetScaffold(
    icon = if (toCommon) Icons.Rounded.Group else Icons.AutoMirrored.Rounded.DriveFileMove,
    tone = GallerySheetTone.Brand,
    title = if (toCommon) stringResource(R.string.viewer_nas_move_common_title) else stringResource(R.string.viewer_nas_move_title, state.destination.label),
    message = if (toCommon) {
      stringResource(if (target.isVideo) R.string.viewer_nas_move_common_message_video else R.string.viewer_nas_move_common_message_photo)
    } else {
      stringResource(R.string.viewer_nas_move_message)
    },
    body = {
      FluidListGroup {
        FluidListRow(
          title = stringResource(R.string.viewer_nas_move_row_size),
          subtitle = target.item.path.name,
          titleMaxLines = 1,
          disclosure = false,
          badge = { FluidRowValue(text = sizeText(state.plan.summary.totalBytes)) },
        )
        if (toCommon) {
          FluidListDivider()
          FluidListRow(
            title = stringResource(R.string.viewer_nas_move_common_note_title),
            subtitle = stringResource(R.string.viewer_nas_move_common_note_detail),
            tone = FluidTone.Info,
            disclosure = false,
            leading = { Icon(imageVector = Icons.Rounded.Group, contentDescription = null) },
          )
        }
        // R11: l'utente l'ha nascosto; nell'area comune lo vedranno i familiari (i loro dispositivi non sanno cosa ha nascosto).
        if (toCommon && target.hidden) {
          FluidListDivider()
          FluidListRow(
            title = stringResource(R.string.viewer_nas_move_hidden_title),
            subtitle = stringResource(R.string.viewer_nas_move_hidden_detail),
            tone = FluidTone.Warning,
            disclosure = false,
            leading = { Icon(imageVector = Icons.Rounded.VisibilityOff, contentDescription = null) },
          )
        }
        if (cross) {
          FluidListDivider()
          FluidListRow(
            title = stringResource(R.string.viewer_nas_move_cross_title),
            subtitle = stringResource(R.string.viewer_nas_move_cross_detail),
            tone = FluidTone.Neutral,
            disclosure = false,
            leading = { Icon(imageVector = Icons.Rounded.CloudSync, contentDescription = null) },
          )
        }
      }
    },
    actions = {
      FluidButton(
        text = stringResource(if (toCommon) R.string.viewer_nas_move_confirm_common else R.string.viewer_nas_move_confirm),
        onClick = onConfirm,
        size = FluidButtonSize.Large,
        fillWidth = true,
      )
      FluidButton(text = stringResource(R.string.viewer_nas_cancel), onClick = onCancel, style = FluidButtonStyle.Tinted, size = FluidButtonSize.Large, fillWidth = true)
    },
  )
}

/** Il peso come lo scrive il telefono: "4,1 MB" (unita' da 1.000, virgola italiana, una cifra decimale sotto il 10). */
internal fun sizeText(bytes: Long): String {
  val units = listOf("B", "kB", "MB", "GB", "TB")
  var value = bytes.coerceAtLeast(0L).toDouble()
  var unit = 0
  while (value >= THOUSAND && unit < units.lastIndex) {
    value /= THOUSAND
    unit++
  }
  val text = if (unit == 0 || value >= TEN) Math.round(value).toString() else String.format(Locale.ROOT, "%.1f", value).replace('.', ',')
  // Uno spazio che non va a capo: "4,1" e "MB" restano insieme.
  return "$text\u00A0${units[unit]}"
}

private const val THOUSAND = 1_000.0
private const val TEN = 10.0
