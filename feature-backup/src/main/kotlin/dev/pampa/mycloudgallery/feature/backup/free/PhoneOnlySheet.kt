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
import dev.pampa.mycloudgallery.core.backup.contract.Corrections
import dev.pampa.mycloudgallery.core.backup.contract.FreeSpaceFilter
import dev.pampa.mycloudgallery.core.backup.contract.SafeguardStart
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

// Fase 5B (P12, docs/fase5-progetto.md §2.3 "Elimina solo dal telefono", §2.5, D22, E-D1, E-D14, E-B14): il foglio di "Elimina solo dal telefono".
// Il NAS non si tocca; la copia del telefono se ne va **solo** come in "Libera spazio" (guardiano, verifica sul momento, controllo del timbro,
// dialogo del sistema): niente resta senza una copia sotto il controllo del proprietario. Il foglio usa `PhoneOnlyAssessment` (P9): per ogni
// media dice se si puo' liberare, se prima serve il guardiano (la sola copia e' nell'area comune), se non e' al sicuro ("Prima mettilo al sicuro",
// **preselezionata**, E-D14), o perche' aspetta. Non toglie niente da solo: "Continua" apre la pagina "Libera spazio" con la scelta dell'utente
// (`FreeSpaceFilter` senza giorni esclusi: li ha scelti lui), dove stanno la verifica, il timbro e il dialogo.
//
// Correzioni 5B (20/10). LS-1, decisione dell'utente "Elimina con avviso": dal viewer, un media che **non risulta al sicuro sul NAS** (backup non
// attivo, come in release con D12 fino a Q3; nessuna copia privata verificata) va nel cestino di Android come nella 4B (30 giorni), ma prima il
// foglio lo dice chiaro ("Questa foto non e' sul NAS") e vuole una conferma in piu'. Un media al sicuro segue il flusso della 5B, con il
// guardiano prima del cestino. LS-4: "Metti al sicuro" fa partire davvero cio' che serve a **quel** media e il foglio si aggiorna da solo.

/** Cosa mostra il foglio. */
@Immutable
internal sealed interface PhoneOnlyUi {
  data object Loading : PhoneOnlyUi

  /** La funzione non e' ancora legata (nessuna valutazione): il foglio lo dice. */
  data object Unavailable : PhoneOnlyUi

  /**
   * La valutazione dei media scelti. [safeFirst] e' la scelta preselezionata quando c'e' almeno un media non al sicuro (E-D14); l'utente puo'
   * cambiarla in "togli solo quelli gia' al sicuro" se ce ne sono. [note] e' l'esito di "Metti al sicuro" quando non e' partito (LS-4).
   */
  data class Advice(val assessment: PhoneOnlyAssessment, val safeFirst: Boolean, val note: SafeguardNote? = null) : PhoneOnlyUi

  /** LS-4: "Metti al sicuro" e' partito per questi media; il foglio aspetta e si aggiorna da solo quando sono al sicuro. */
  data class Safeguarding(val assessment: PhoneOnlyAssessment) : PhoneOnlyUi
}

/** LS-4: perche' "Metti al sicuro" non e' partito, o non e' ancora finito. */
internal enum class SafeguardNote {
  /** Il media sta in una cartella che il backup non guarda: va aggiunta nelle impostazioni del backup. */
  OUTSIDE_SOURCES,

  /** Adesso non si puo' far partire niente (backup non pronto, cartella del dispositivo non scelta). */
  NOT_NOW,

  /** E' partito, ma non e' ancora al sicuro: ci vuole piu' tempo (il foglio si puo' riaprire dopo). */
  STILL_WAITING,
}

@HiltViewModel
class PhoneOnlyViewModel @Inject constructor(
  assessor: Optional<PhoneOnlyAssessor>,
  actions: Optional<BackupActions>,
  corrections: Optional<Corrections>,
) : ViewModel() {

  private val port: PhoneOnlyAssessor? = assessor.orElse(null)
  private val outside: BackupActions? = actions.orElse(null)

  /** LS-4: "Metti al sicuro" per un media (`Corrections.safeguard`, in `:app` `BackupGraph.safeguard`: verifica sul momento o "Fai ora"). */
  private val safeguards: Corrections? = corrections.orElse(null)
  private var job: Job? = null
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
    job?.cancel()
    bound = null
    flow.value = if (port == null) PhoneOnlyUi.Unavailable else PhoneOnlyUi.Loading
  }

  /** L'utente cambia scelta: "prima mettilo al sicuro" o "togli solo quelli gia' al sicuro". */
  fun chooseSafeFirst(safeFirst: Boolean) {
    flow.value = (flow.value as? PhoneOnlyUi.Advice)?.copy(safeFirst = safeFirst) ?: return
  }

  /**
   * "Metti al sicuro" (LS-4). Prima chiedeva solo "Fai ora" e chiudeva il foglio: niente lo riapriva, e un media fuori dalle sorgenti non si
   * caricava mai. Adesso parte cio' che serve a **ognuno** dei media non al sicuro (la verifica sul momento della sua copia sul NAS, o "Fai
   * ora" se sta in una sorgente) e il foglio aspetta: appena la valutazione cambia si aggiorna da solo (quelli ora al sicuro si possono
   * togliere). Se un media sta fuori dalle sorgenti, o non parte niente, il foglio lo dice.
   */
  fun safeguard() {
    val advice = flow.value as? PhoneOnlyUi.Advice ?: return
    val ids = advice.assessment.toSafeguard
    val service = port ?: return
    val corrections = safeguards
    if (corrections == null) {
      // Senza la porta delle correzioni (varianti senza backup): il backup generale, come prima.
      outside?.backupNow()
      return
    }
    job?.cancel()
    job = viewModelScope.launch {
      flow.value = PhoneOnlyUi.Safeguarding(advice.assessment)
      val starts = ids.map { corrections.safeguard(it) }
      if (SafeguardStart.STARTED !in starts) {
        val note = if (SafeguardStart.NOT_IN_SOURCES in starts) SafeguardNote.OUTSIDE_SOURCES else SafeguardNote.NOT_NOW
        flow.value = advice.copy(note = note)
        return@launch
      }
      repeat(MAX_POLLS) {
        delay(POLL_MS)
        val now = service.assess(bound ?: advice.assessment.advice.keys)
        if (now.toSafeguard != ids) {
          flow.value = PhoneOnlyUi.Advice(now, safeFirst = now.preselectSafeFirst)
          return@launch
        }
      }
      flow.value = advice.copy(note = SafeguardNote.STILL_WAITING)
    }
  }

  private companion object {
    /** Ogni quanto il foglio riguarda se i media sono al sicuro, e per quanto (come "Metti al sicuro adesso" delle correzioni). */
    const val POLL_MS = 3_000L
    const val MAX_POLLS = 100
  }
}

/**
 * Il foglio di "Elimina solo dal telefono" per [ids] (`null` = chiuso). [onFree] riceve il filtro con cui aprire "Libera spazio" per i media che si
 * possono liberare adesso: lo apre `:app` (la pagina `FreeSpaceRoute` con la selezione).
 *
 * LS-1: [onTrash] (dal viewer) mette nel cestino di Android i media che **non risultano al sicuro sul NAS**, dopo l'avviso del foglio e il tocco
 * su "Elimina dal telefono"; il dialogo del sistema chiede poi il suo permesso. Null = non offerto. [isVideo] sceglie le parole ("questo video").
 */
@Composable
fun PhoneOnlySheetHost(
  ids: Set<MediaId>?,
  onDismiss: () -> Unit,
  onFree: (FreeSpaceFilter) -> Unit,
  onTrash: ((Set<MediaId>) -> Unit)? = null,
  isVideo: Boolean = false,
) {
  var shown by remember { mutableStateOf<Set<MediaId>?>(null) }
  if (ids != null) shown = ids
  val showing = shown ?: return
  PhoneOnlySheetRoute(ids = showing, visible = ids != null, onDismiss = onDismiss, onFree = onFree, onTrash = onTrash, isVideo = isVideo)
}

@Composable
private fun PhoneOnlySheetRoute(
  ids: Set<MediaId>,
  visible: Boolean,
  onDismiss: () -> Unit,
  onFree: (FreeSpaceFilter) -> Unit,
  onTrash: ((Set<MediaId>) -> Unit)?,
  isVideo: Boolean,
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
    // LS-4: il foglio resta aperto e si aggiorna da solo quando il media e' al sicuro ("Poi ti riporto qui").
    onSafeguard = viewModel::safeguard,
    onContinue = { filter ->
      onDismiss()
      onFree(filter)
    },
    onDismiss = onDismiss,
    onTrash = onTrash?.let { trash ->
      { notOnNas: Set<MediaId> ->
        onDismiss()
        trash(notOnNas)
      }
    },
    isVideo = isVideo,
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
  onTrash: ((Set<MediaId>) -> Unit)? = null,
  isVideo: Boolean = false,
) {
  GalleryConfirmSheet(visible = visible, onDismissRequest = onDismiss, paneTitle = stringResource(R.string.backup_free_phone_title)) {
    PhoneOnlyContent(
      state = state,
      onChoose = onChoose,
      onSafeguard = onSafeguard,
      onContinue = onContinue,
      onClose = onDismiss,
      onTrash = onTrash,
      isVideo = isVideo,
    )
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
  onTrash: ((Set<MediaId>) -> Unit)? = null,
  isVideo: Boolean = false,
) {
  // LS-1: niente di questi media risulta al sicuro sul NAS e il viewer offre il cestino di Android: prima l'avviso, chiaro.
  val notOnNas = (state as? PhoneOnlyUi.Advice)?.assessment?.takeIf { onTrash != null && it.freeFilter == null }?.notOnNas.orEmpty()
  if (state is PhoneOnlyUi.Advice && notOnNas.isNotEmpty() && onTrash != null) {
    NotOnNasContent(state, notOnNas, isVideo, onSafeguard, onTrash, onClose, modifier)
    return
  }
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
        is PhoneOnlyUi.Advice -> {
          AdviceBody(state, onChoose)
          state.note?.let { SafeguardNoteRow(it) }
        }
        is PhoneOnlyUi.Safeguarding -> SafeguardingRow()
      }
    },
    actions = {
      if (state is PhoneOnlyUi.Advice) {
        val filter = state.assessment.freeFilter
        when {
          // E-D14: con un media non al sicuro la scelta di partenza e' "Prima mettilo al sicuro" (LS-4: non dopo che si e' visto che non parte).
          state.assessment.preselectSafeFirst && state.safeFirst && state.note == null ->
            BackupButton(label = stringResource(R.string.backup_free_phone_action_safe), onClick = onSafeguard)
          filter != null ->
            BackupButton(label = stringResource(R.string.backup_free_phone_action_free), onClick = { onContinue(filter) })
        }
      }
      BackupButton(label = stringResource(R.string.backup_free_phone_close), onClick = onClose, style = FluidButtonStyle.Tinted)
    },
  )
}

/**
 * LS-1, "Elimina con avviso": il media non risulta al sicuro sul NAS. Il foglio lo dice (perche', e che dal cestino di Android si recupera per 30
 * giorni, poi non c'e' piu' da nessuna parte) e offre: "Metti al sicuro" quando si puo' (preselezionata, E-D14), "Elimina dal telefono" (il
 * cestino di Android, con il dialogo del sistema) e "Chiudi". Il tocco su "Elimina dal telefono" e' la conferma in piu' rispetto alla 4B.
 */
@Composable
private fun NotOnNasContent(
  state: PhoneOnlyUi.Advice,
  notOnNas: Set<MediaId>,
  isVideo: Boolean,
  onSafeguard: () -> Unit,
  onTrash: (Set<MediaId>) -> Unit,
  onClose: () -> Unit,
  modifier: Modifier,
) {
  val single = notOnNas.size == 1
  val title = stringResource(
    when {
      !single -> R.string.backup_free_phone_not_on_nas_title_many
      isVideo -> R.string.backup_free_phone_not_on_nas_title_video
      else -> R.string.backup_free_phone_not_on_nas_title_photo
    },
  )
  val why = state.assessment.advice.filterKeys { it in notOnNas }.values.maxOrNull()
  val reason = when (why) {
    PhoneOnlyAdvice.UNAVAILABLE -> R.string.backup_free_phone_not_on_nas_inactive
    PhoneOnlyAdvice.WAIT -> R.string.backup_free_phone_not_on_nas_wait
    PhoneOnlyAdvice.FROZEN -> R.string.backup_free_phone_not_on_nas_frozen
    PhoneOnlyAdvice.OUTSIDE_TEST_FENCE -> R.string.backup_free_phone_not_on_nas_fence
    else -> R.string.backup_free_phone_not_on_nas_not_verified
  }
  val canSafeguard = state.assessment.preselectSafeFirst && state.note == null
  GallerySheetScaffold(
    icon = Icons.Rounded.CloudOff,
    tone = GallerySheetTone.Destructive,
    title = title,
    message = stringResource(reason) + " " + stringResource(R.string.backup_free_phone_not_on_nas_trash),
    modifier = modifier,
    body = { state.note?.let { SafeguardNoteRow(it) } },
    actions = {
      if (canSafeguard) BackupButton(label = stringResource(R.string.backup_free_phone_action_safe), onClick = onSafeguard)
      BackupButton(
        label = stringResource(R.string.backup_free_phone_action_trash),
        onClick = { onTrash(notOnNas) },
        style = if (canSafeguard) FluidButtonStyle.Tinted else FluidButtonStyle.Destructive,
      )
      BackupButton(label = stringResource(R.string.backup_free_phone_close), onClick = onClose, style = FluidButtonStyle.Tinted)
    },
  )
}

/** LS-4: "Metti al sicuro" e' partito: il foglio aspetta. */
@Composable
private fun SafeguardingRow() {
  FluidListGroup {
    FluidListRow(
      title = stringResource(R.string.backup_free_phone_safeguarding_title),
      subtitle = stringResource(R.string.backup_free_phone_safeguarding_detail),
      tone = FluidTone.Info,
      leading = { FluidSpinner(size = BackupDimens.RowGlyph) },
      disclosure = false,
      titleMaxLines = 3,
    )
  }
}

/** LS-4: perche' "Metti al sicuro" non e' partito (o non ha ancora finito). */
@Composable
private fun SafeguardNoteRow(note: SafeguardNote) {
  val (title, detail) = when (note) {
    SafeguardNote.OUTSIDE_SOURCES -> R.string.backup_free_phone_note_sources_title to R.string.backup_free_phone_note_sources_detail
    SafeguardNote.NOT_NOW -> R.string.backup_free_phone_note_not_now_title to R.string.backup_free_phone_note_not_now_detail
    SafeguardNote.STILL_WAITING -> R.string.backup_free_phone_note_waiting_title to R.string.backup_free_phone_note_waiting_detail
  }
  FluidListGroup {
    FluidListRow(
      title = stringResource(title),
      subtitle = stringResource(detail),
      tone = FluidTone.Warning,
      leading = { Icon(imageVector = Icons.Rounded.Warning, contentDescription = null) },
      disclosure = false,
      titleMaxLines = 3,
    )
  }
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
