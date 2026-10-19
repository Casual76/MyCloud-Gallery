package dev.pampa.mycloudgallery.feature.backup.free

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.antigravity.fluidengine.ui.fluid.FluidButtonStyle
import dev.antigravity.fluidengine.ui.fluid.FluidSpinner
import dev.antigravity.fluidengine.ui.theme.FluidListDivider
import dev.antigravity.fluidengine.ui.theme.FluidListGroup
import dev.antigravity.fluidengine.ui.theme.FluidListRow
import dev.antigravity.fluidengine.ui.theme.FluidTone
import dev.pampa.mycloudgallery.core.backup.contract.FreeSpaceFilter
import dev.pampa.mycloudgallery.core.backup.free.PhoneOnlyAdvice
import dev.pampa.mycloudgallery.core.backup.free.PhoneOnlyAssessment
import dev.pampa.mycloudgallery.core.designsystem.sheet.GalleryConfirmSheet
import dev.pampa.mycloudgallery.core.designsystem.sheet.GallerySheetScaffold
import dev.pampa.mycloudgallery.core.designsystem.sheet.GallerySheetTone
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.feature.backup.BackupDimens
import dev.pampa.mycloudgallery.feature.backup.R
import dev.pampa.mycloudgallery.feature.backup.contract.BackupActions
import dev.pampa.mycloudgallery.feature.backup.ui.BackupButton
import dev.pampa.mycloudgallery.feature.backup.ui.BackupText
import java.util.Optional
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

// Fase 5B (P12, docs/fase5-progetto.md §2.3 "Elimina solo dal telefono", §2.5, D22, E-D1, E-D14, E-B14): il foglio di "Elimina solo dal telefono".
// Il NAS non si tocca; la copia del telefono se ne va **solo** come in "Libera spazio" (guardiano, verifica sul momento, controllo del timbro,
// dialogo del sistema): niente resta senza una copia sotto il controllo del proprietario. Il foglio usa `PhoneOnlyAssessment` (P9): per ogni
// media dice se si puo' liberare, se prima serve il guardiano (la sola copia e' nell'area comune), se non e' al sicuro ("Prima mettilo al sicuro",
// **preselezionata**, E-D14), o perche' aspetta. Non toglie niente da solo: "Continua" apre la pagina "Libera spazio" con la scelta dell'utente
// (`FreeSpaceFilter` senza giorni esclusi: li ha scelti lui), dove stanno la verifica, il timbro e il dialogo.

/** Cosa mostra il foglio. */
@Immutable
internal sealed interface PhoneOnlyUi {
  data object Loading : PhoneOnlyUi

  /** La funzione non e' ancora legata (nessuna valutazione): il foglio lo dice. */
  data object Unavailable : PhoneOnlyUi

  /**
   * La valutazione dei media scelti. [safeFirst] e' la scelta preselezionata quando c'e' almeno un media non al sicuro (E-D14); l'utente puo'
   * cambiarla in "togli solo quelli gia' al sicuro" se ce ne sono.
   */
  data class Advice(val assessment: PhoneOnlyAssessment, val safeFirst: Boolean) : PhoneOnlyUi
}

@HiltViewModel
class PhoneOnlyViewModel @Inject constructor(
  assessor: Optional<PhoneOnlyAssessor>,
  actions: Optional<BackupActions>,
) : ViewModel() {

  private val port: PhoneOnlyAssessor? = assessor.orElse(null)
  private val outside: BackupActions? = actions.orElse(null)
  private val flow = MutableStateFlow<PhoneOnlyUi>(if (port == null) PhoneOnlyUi.Unavailable else PhoneOnlyUi.Loading)
  private var bound: Set<MediaId>? = null

  internal val state: StateFlow<PhoneOnlyUi> = flow

  /** Valuta [ids]. Una seconda chiamata per gli stessi media non rifa' niente. */
  fun bind(ids: Set<MediaId>) {
    val service = port ?: return
    if (bound == ids) return
    bound = ids
    flow.value = PhoneOnlyUi.Loading
    viewModelScope.launch {
      val assessment = service.assess(ids)
      flow.value = PhoneOnlyUi.Advice(assessment, safeFirst = assessment.preselectSafeFirst)
    }
  }

  fun reset() {
    bound = null
    flow.value = if (port == null) PhoneOnlyUi.Unavailable else PhoneOnlyUi.Loading
  }

  /** L'utente cambia scelta: "prima mettilo al sicuro" o "togli solo quelli gia' al sicuro". */
  fun chooseSafeFirst(safeFirst: Boolean) {
    flow.value = (flow.value as? PhoneOnlyUi.Advice)?.copy(safeFirst = safeFirst) ?: return
  }

  /** "Metti al sicuro": parte il backup (lo fa `:app`); `:app` ripropone il foglio quando i media sono al sicuro (D22). */
  fun safeguard() {
    outside?.backupNow()
  }
}

/**
 * Il foglio di "Elimina solo dal telefono" per [ids] (`null` = chiuso). [onFree] riceve il filtro con cui aprire "Libera spazio" per i media che si
 * possono liberare adesso: lo apre `:app` (la pagina `FreeSpaceRoute` con la selezione).
 */
@Composable
fun PhoneOnlySheetHost(ids: Set<MediaId>?, onDismiss: () -> Unit, onFree: (FreeSpaceFilter) -> Unit) {
  var shown by remember { mutableStateOf<Set<MediaId>?>(null) }
  if (ids != null) shown = ids
  val showing = shown ?: return
  PhoneOnlySheetRoute(ids = showing, visible = ids != null, onDismiss = onDismiss, onFree = onFree)
}

@Composable
private fun PhoneOnlySheetRoute(
  ids: Set<MediaId>,
  visible: Boolean,
  onDismiss: () -> Unit,
  onFree: (FreeSpaceFilter) -> Unit,
  viewModel: PhoneOnlyViewModel = hiltViewModel(key = "solo-telefono"),
) {
  val state by viewModel.state.collectAsStateWithLifecycle()
  LaunchedEffect(ids, visible) {
    if (visible) {
      viewModel.reset()
      viewModel.bind(ids)
    }
  }
  PhoneOnlySheet(
    visible = visible,
    state = state,
    onChoose = viewModel::chooseSafeFirst,
    onSafeguard = {
      viewModel.safeguard()
      onDismiss()
    },
    onContinue = { filter ->
      onDismiss()
      onFree(filter)
    },
    onDismiss = onDismiss,
  )
}

/** Il foglio nel suo pop-up (con lo scudo contro le sovrapposizioni di `GalleryConfirmSheet`). */
@Composable
internal fun PhoneOnlySheet(
  visible: Boolean,
  state: PhoneOnlyUi,
  onChoose: (Boolean) -> Unit,
  onSafeguard: () -> Unit,
  onContinue: (FreeSpaceFilter) -> Unit,
  onDismiss: () -> Unit,
) {
  GalleryConfirmSheet(visible = visible, onDismissRequest = onDismiss, paneTitle = stringResource(R.string.backup_free_phone_title)) {
    PhoneOnlyContent(state = state, onChoose = onChoose, onSafeguard = onSafeguard, onContinue = onContinue, onClose = onDismiss)
  }
}

/** Il contenuto del foglio (anche per gli screenshot e i test, senza il pop-up). */
@Composable
internal fun PhoneOnlyContent(
  state: PhoneOnlyUi,
  onChoose: (Boolean) -> Unit,
  onSafeguard: () -> Unit,
  onContinue: (FreeSpaceFilter) -> Unit,
  onClose: () -> Unit,
  modifier: Modifier = Modifier,
) {
  GallerySheetScaffold(
    icon = Icons.Rounded.PhoneAndroid,
    tone = GallerySheetTone.Caution,
    title = stringResource(R.string.backup_free_phone_title),
    message = stringResource(R.string.backup_free_phone_message),
    modifier = modifier,
    body = {
      when (state) {
        PhoneOnlyUi.Loading -> FluidListGroup {
          FluidListRow(
            title = stringResource(R.string.backup_free_phone_loading),
            subtitle = "",
            tone = FluidTone.Info,
            leading = { FluidSpinner(size = BackupDimens.RowGlyph) },
            disclosure = false,
          )
        }
        PhoneOnlyUi.Unavailable -> FluidListGroup {
          FluidListRow(
            title = stringResource(R.string.backup_free_phone_unavailable_title),
            subtitle = stringResource(R.string.backup_free_phone_unavailable_detail),
            tone = FluidTone.Neutral,
            leading = { Icon(imageVector = Icons.Rounded.Schedule, contentDescription = null) },
            disclosure = false,
          )
        }
        is PhoneOnlyUi.Advice -> AdviceBody(state, onChoose)
      }
    },
    actions = {
      if (state is PhoneOnlyUi.Advice) {
        val filter = state.assessment.freeFilter
        when {
          // E-D14: con un media non al sicuro la scelta di partenza e' "Prima mettilo al sicuro".
          state.assessment.preselectSafeFirst && state.safeFirst ->
            BackupButton(label = stringResource(R.string.backup_free_phone_action_safe), onClick = onSafeguard)
          filter != null ->
            BackupButton(label = stringResource(R.string.backup_free_phone_action_free), onClick = { onContinue(filter) })
        }
      }
      BackupButton(label = stringResource(R.string.backup_free_phone_close), onClick = onClose, style = FluidButtonStyle.Tinted)
    },
  )
}

@Composable
private fun AdviceBody(state: PhoneOnlyUi.Advice, onChoose: (Boolean) -> Unit) {
  val counts = state.assessment.advice.values.groupingBy { it }.eachCount()
  FluidListGroup {
    // Nell'ordine del contratto (dal meno al piu' grave): il foglio non cambia da una volta all'altra.
    PhoneOnlyAdvice.entries.filter { it in counts }.forEachIndexed { index, advice ->
      if (index > 0) FluidListDivider()
      val (plural, tone, icon) = adviceLook(advice)
      val count = counts.getValue(advice)
      FluidListRow(
        title = pluralStringResource(plural, count, BackupText.count(count)),
        subtitle = "",
        tone = tone,
        leading = { Icon(imageVector = icon, contentDescription = null) },
        disclosure = false,
        titleMaxLines = 4,
      )
    }
  }
  val canFree = state.assessment.freeFilter != null
  if (state.assessment.preselectSafeFirst && canFree) {
    FluidListGroup {
      BackupChoiceRow(
        title = stringResource(R.string.backup_free_phone_choice_safe_first),
        detail = stringResource(R.string.backup_free_phone_choice_safe_first_detail),
        selected = state.safeFirst,
        onSelect = { onChoose(true) },
      )
      FluidListDivider()
      BackupChoiceRow(
        title = stringResource(R.string.backup_free_phone_choice_free_ready),
        detail = stringResource(R.string.backup_free_phone_choice_free_ready_detail),
        selected = !state.safeFirst,
        onSelect = { onChoose(false) },
      )
    }
  }
}

private data class AdviceLook(val plural: Int, val tone: FluidTone, val icon: androidx.compose.ui.graphics.vector.ImageVector)

private fun adviceLook(advice: PhoneOnlyAdvice): AdviceLook = when (advice) {
  PhoneOnlyAdvice.FREE -> AdviceLook(R.plurals.backup_free_phone_free, FluidTone.Success, Icons.Rounded.CheckCircle)
  PhoneOnlyAdvice.GUARD_FIRST -> AdviceLook(R.plurals.backup_free_phone_guard, FluidTone.Info, Icons.Rounded.Shield)
  PhoneOnlyAdvice.SAFE_FIRST -> AdviceLook(R.plurals.backup_free_phone_safe_first, FluidTone.Warning, Icons.Rounded.Warning)
  PhoneOnlyAdvice.WAIT -> AdviceLook(R.plurals.backup_free_phone_wait, FluidTone.Info, Icons.Rounded.Schedule)
  PhoneOnlyAdvice.FROZEN -> AdviceLook(R.plurals.backup_free_phone_frozen, FluidTone.Danger, Icons.Rounded.Warning)
  PhoneOnlyAdvice.UNAVAILABLE -> AdviceLook(R.plurals.backup_free_phone_unavailable, FluidTone.Neutral, Icons.Rounded.CloudOff)
  PhoneOnlyAdvice.OUTSIDE_TEST_FENCE -> AdviceLook(R.plurals.backup_free_phone_fence, FluidTone.Neutral, Icons.Rounded.Warning)
  PhoneOnlyAdvice.NOT_ON_PHONE -> AdviceLook(R.plurals.backup_free_phone_not_on_phone, FluidTone.Neutral, Icons.Rounded.PhoneAndroid)
}
