package dev.pampa.mycloudgallery.feature.people.activation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.Face
import androidx.compose.material.icons.rounded.Gavel
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.PhonelinkOff
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.vector.ImageVector
import dev.antigravity.fluidengine.ui.fluid.FluidButton
import dev.antigravity.fluidengine.ui.fluid.FluidButtonSize
import dev.antigravity.fluidengine.ui.fluid.FluidButtonStyle
import dev.antigravity.fluidengine.ui.fluid.FluidScreen
import dev.antigravity.fluidengine.ui.fluid.FluidSectionFootnote
import dev.antigravity.fluidengine.ui.fluid.FluidSectionHeader
import dev.antigravity.fluidengine.ui.theme.FluidInlineMessage
import dev.antigravity.fluidengine.ui.theme.FluidListDivider
import dev.antigravity.fluidengine.ui.theme.FluidListGroup
import dev.antigravity.fluidengine.ui.theme.FluidListRow
import dev.antigravity.fluidengine.ui.theme.FluidStatusBadge
import dev.antigravity.fluidengine.ui.theme.FluidTone
import dev.pampa.mycloudgallery.core.designsystem.instant.InstantBlock
import dev.pampa.mycloudgallery.core.designsystem.instant.InstantShape
import dev.pampa.mycloudgallery.core.designsystem.instant.instantSkeleton
import dev.pampa.mycloudgallery.core.designsystem.theme.GalleryDimens
import dev.pampa.mycloudgallery.core.faces.contract.FacesStatus
import dev.pampa.mycloudgallery.core.faces.contract.FacesWait
import dev.pampa.mycloudgallery.core.faces.contract.StepState
import dev.pampa.mycloudgallery.core.library.text.GalleryNumbers
import dev.pampa.mycloudgallery.feature.people.R
import dev.pampa.mycloudgallery.feature.people.contract.EraseSheetState
import dev.pampa.mycloudgallery.feature.people.contract.PeopleSettingsState
import dev.pampa.mycloudgallery.feature.people.contract.PeopleSwitchShown

// IMPOSTAZIONI -> PERSONE (docs/fase8-progetto.md §1.5 `PeopleSettingsState`, §2.1, D4, D34; riga P7 del §4.4): lo stato in una frase, "Attiva riconoscimento dei
// volti" (che porta alla pagina di attivazione), chi analizza le foto e a che punto e', "Non su questo dispositivo" / "Attiva qui", le persone nascoste, i modelli
// con le licenze e "Disattiva e cancella" (il foglio e' di P8). Composable fatte solo con i componenti dell'engine (G2, D38). D37: mai nomi, id o conteggi per persona.

/** Le richieste della pagina. Le uscite (attivazione, avviso, nascoste, licenze, Diagnostica, cancellazione) le scrive `:app`; le azioni dell'interruttore il controllore. */
class PeopleSettingsHandlers(
  val onActivate: () -> Unit,
  val onOpenNotice: () -> Unit,
  val onExclude: () -> Unit,
  val onInclude: () -> Unit,
  val onOpenHidden: (() -> Unit)? = null,
  val onOpenLicenses: () -> Unit,
  val onOpenDiagnostics: (() -> Unit)? = null,
  val onErase: (() -> Unit)? = null,
  /** CA-2: «Analizza ora» (chiede la conferma), «Annulla» e la conferma che fa partire il calcolo adesso. */
  val onAnalyzeAsk: () -> Unit = {},
  val onAnalyzeCancel: () -> Unit = {},
  val onAnalyzeConfirm: () -> Unit = {},
)

@Composable
fun PeopleSettingsScreen(
  state: PeopleSettingsState,
  handlers: PeopleSettingsHandlers,
  onBack: () -> Unit,
  modifier: Modifier = Modifier,
) {
  // La conferma dell'esclusione e' dentro la pagina (nessun foglio): cancella dati di questo dispositivo, e sotto la recinzione sono i soli che ci sono.
  var confirmingExclusion by rememberSaveable { mutableStateOf(false) }
  FluidScreen(title = stringResource(R.string.faces_settings_title), modifier = modifier, onBack = onBack) {
    if (!state.known) {
      // L350: finche' le persone non sono montate lo stato non si sa: uno scheletro, mai «Persone e' spenta». I modelli sono fatti dell'app e non dipendono dallo stato.
      loadingBlock()
      modelsSection(state, handlers)
    } else {
      // L322 (CE-3): da spenta perche' l'ha spenta un altro dispositivo, la riga che lo dice sta sopra lo stato (come nelle Raccolte).
      if (state.offElsewhere && state.shown == PeopleSwitchShown.OFF) item(key = "off-elsewhere") { OffElsewhereMessage() }
      item(key = "state") { StateMessage(state.shown) }
      stateAction(state, handlers)
      if (state.shown.showsWork()) workSection(state, handlers)
      deviceSection(state, handlers, confirmingExclusion, onAsk = { confirmingExclusion = true }, onCancel = { confirmingExclusion = false }) {
        confirmingExclusion = false
      }
      if (state.hiddenPeople > 0 && state.shown.showsWork() && handlers.onOpenHidden != null) hiddenSection(state.hiddenPeople, handlers.onOpenHidden)
      modelsSection(state, handlers)
      if ((state.canErase && handlers.onErase != null) || state.erase != null) eraseSection(state, handlers.onErase.takeIf { state.canErase })
    }
  }
}

/**
 * L350: lo scheletro di una pagina il cui stato non si sa ancora (il principio del primo disegno istantaneo: uno scheletro fermo, mai una rotella; TalkBack sente «Sto
 * caricando»). Nessun testo di stato: ogni frase di stato, a questo punto, potrebbe essere sbagliata.
 */
private fun LazyListScope.loadingBlock() {
  instantSkeleton(InstantShape.of(InstantBlock.Rows(SKELETON_ROWS)), keyPrefix = "instant-people-settings")
}

private const val SKELETON_ROWS = 3

/** L322: «Persone e' stata disattivata da un altro tuo dispositivo», con cio' che e' successo ai dati di qui e che si puo' riattivare. */
@Composable
private fun OffElsewhereMessage() {
  FluidInlineMessage(
    message = stringResource(R.string.faces_settings_off_elsewhere_message),
    title = stringResource(R.string.faces_settings_off_elsewhere_title),
    tone = FluidTone.Info,
    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
  )
}

private fun PeopleSwitchShown.showsWork(): Boolean = this == PeopleSwitchShown.ON || this == PeopleSwitchShown.LOCAL_ONLY

@Composable
private fun StateMessage(shown: PeopleSwitchShown) {
  val (title, message, tone) = when (shown) {
    PeopleSwitchShown.OFF -> Triple(R.string.faces_settings_off_title, R.string.faces_settings_off_message, FluidTone.Info)
    PeopleSwitchShown.ON -> Triple(R.string.faces_settings_on_title, R.string.faces_settings_on_message, FluidTone.Success)
    PeopleSwitchShown.LOCAL_ONLY -> Triple(R.string.faces_settings_local_title, R.string.faces_settings_local_message, FluidTone.Info)
    PeopleSwitchShown.AWAITING_NOTICE -> Triple(R.string.faces_settings_awaiting_title, R.string.faces_settings_awaiting_message, FluidTone.Warning)
    PeopleSwitchShown.EXCLUDED_HERE -> Triple(R.string.faces_settings_excluded_title, R.string.faces_settings_excluded_message, FluidTone.Neutral)
    PeopleSwitchShown.ERASING -> Triple(R.string.faces_settings_erasing_title, R.string.faces_settings_erasing_message, FluidTone.Warning)
    PeopleSwitchShown.NEEDS_UPDATE -> Triple(R.string.faces_settings_update_title, R.string.faces_settings_update_message, FluidTone.Warning)
  }
  FluidInlineMessage(
    message = stringResource(message),
    title = stringResource(title),
    tone = tone,
    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
  )
}

/** Il tasto principale: spenta porta all'attivazione, in attesa dell'avviso a leggerlo e scegliere. */
private fun LazyListScope.stateAction(state: PeopleSettingsState, handlers: PeopleSettingsHandlers) {
  val (label, action) = when (state.shown) {
    PeopleSwitchShown.OFF -> R.string.faces_settings_off_action to handlers.onActivate
    PeopleSwitchShown.AWAITING_NOTICE -> R.string.faces_settings_awaiting_action to handlers.onOpenNotice
    else -> return
  }
  item(key = "action") {
    FluidButton(text = stringResource(label), onClick = action, style = FluidButtonStyle.Filled, size = FluidButtonSize.Large, fillWidth = true)
  }
}

/** Chi analizza le foto e a che punto e'. */
private fun LazyListScope.workSection(state: PeopleSettingsState, handlers: PeopleSettingsHandlers) {
  item(key = "work-header") { FluidSectionHeader(title = stringResource(R.string.faces_settings_section_work)) }
  item(key = "work") {
    FluidListGroup {
      if (state.devices.isEmpty()) {
        FluidListRow(
          title = stringResource(R.string.faces_settings_devices_none),
          subtitle = "",
          tone = FluidTone.Warning,
          leading = { Glyph(Icons.Rounded.PhonelinkOff) },
          disclosure = false,
        )
      }
      state.devices.forEachIndexed { index, device ->
        if (index > 0) FluidListDivider()
        FluidListRow(
          title = if (device.thisDevice) stringResource(R.string.faces_settings_device_here) else device.label,
          subtitle = if (device.excluded) stringResource(R.string.faces_settings_device_excluded) else stringResource(R.string.faces_settings_device_computing),
          tone = if (device.excluded) FluidTone.Neutral else FluidTone.Primary,
          leading = { Glyph(Icons.Rounded.PhoneAndroid) },
          disclosure = false,
        )
      }
      if (!state.computesHere) {
        // DR-1 (correzioni B): un dispositivo che non analizza le foto non ha un avanzamento suo da mostrare: dice che qui le persone non si calcolano e dove si
        // accende il ruolo che serve. Non calcola per conto suo, nemmeno con la recinzione dei volti (D34).
        if (state.devices.isNotEmpty()) FluidListDivider()
        FluidListRow(
          title = stringResource(R.string.faces_settings_not_here_title),
          subtitle = stringResource(R.string.faces_settings_not_here_detail),
          tone = FluidTone.Warning,
          leading = { Glyph(Icons.Rounded.PhonelinkOff) },
          disclosure = false,
        )
      } else {
        progressLine(state.progress)?.let { (text, tone) ->
          if (state.devices.isNotEmpty()) FluidListDivider()
          FluidListRow(
            title = text,
            subtitle = "",
            tone = tone,
            leading = { Glyph(Icons.Rounded.Face) },
            onClick = if (state.progress is FacesStatus.Problem) handlers.onOpenDiagnostics else null,
            disclosure = state.progress is FacesStatus.Problem && handlers.onOpenDiagnostics != null,
          )
        }
      }
    }
  }
  // CA-2: la stima e «Analizza ora», dove questo dispositivo analizza e il calcolo non e' gia' in corso.
  if (state.computesHere && state.progress !is FacesStatus.Running) {
    analyzeNowBlock(state.analyze, onAsk = handlers.onAnalyzeAsk, onCancel = handlers.onAnalyzeCancel, onConfirm = handlers.onAnalyzeConfirm)
  }
}

/** L'avanzamento del calcolo in una riga: numeri e motivi, mai nomi (D37). Null = niente da dire. */
@Composable
private fun progressLine(progress: FacesStatus): Pair<String, FluidTone>? = when (progress) {
  FacesStatus.Off -> null
  // CA-1: i fatti e il totale sono della libreria (non dell'unita' da 200 media); senza totale ("non so quanti") niente numeri.
  is FacesStatus.Running -> if (progress.total > 0) {
    stringResource(R.string.faces_settings_progress_running, GalleryNumbers.format(progress.done), GalleryNumbers.format(progress.total)) to FluidTone.Info
  } else {
    stringResource(R.string.faces_settings_progress_running_unknown) to FluidTone.Info
  }
  is FacesStatus.Idle -> stringResource(R.string.faces_settings_progress_idle) to FluidTone.Success
  is FacesStatus.Problem -> stringResource(R.string.faces_settings_progress_problem) to FluidTone.Warning
  is FacesStatus.Waiting -> stringResource(waitText(progress.reason)) to FluidTone.Neutral
}

internal fun waitText(reason: FacesWait): Int = when (reason) {
  FacesWait.NOTICE -> R.string.faces_settings_wait_notice
  FacesWait.EXCLUDED -> R.string.faces_settings_wait_excluded
  FacesWait.NOT_INDEXER -> R.string.faces_settings_wait_not_indexer
  FacesWait.RULES -> R.string.faces_settings_wait_rules
  FacesWait.NAS -> R.string.faces_settings_wait_nas
  FacesWait.PREVIEWS -> R.string.faces_settings_wait_previews
  FacesWait.MODELS -> R.string.faces_settings_wait_models
  FacesWait.ERASING -> R.string.faces_settings_wait_erasing
  FacesWait.NEEDS_UPDATE -> R.string.faces_settings_wait_update
  FacesWait.NEXT_STAGE -> R.string.faces_settings_wait_next_stage
  FacesWait.STORAGE -> R.string.faces_settings_wait_storage
  FacesWait.KEYSTORE -> R.string.faces_settings_wait_keystore
}

/** "Non su questo dispositivo" (con una conferma nella pagina: cancella i dati di qui) oppure "Attiva qui". */
private fun LazyListScope.deviceSection(
  state: PeopleSettingsState,
  handlers: PeopleSettingsHandlers,
  confirming: Boolean,
  onAsk: () -> Unit,
  onCancel: () -> Unit,
  onConfirmed: () -> Unit,
) {
  val excluded = state.shown == PeopleSwitchShown.EXCLUDED_HERE
  val canExclude = state.shown == PeopleSwitchShown.ON || state.shown == PeopleSwitchShown.LOCAL_ONLY || state.shown == PeopleSwitchShown.AWAITING_NOTICE
  if (!excluded && !canExclude) return
  item(key = "device-header") { FluidSectionHeader(title = stringResource(R.string.faces_settings_section_device)) }
  if (excluded) {
    item(key = "device-include") {
      FluidListGroup {
        FluidListRow(
          title = stringResource(R.string.faces_settings_include_title),
          subtitle = stringResource(R.string.faces_settings_include_message),
          tone = FluidTone.Primary,
          leading = { Glyph(Icons.Rounded.PhoneAndroid) },
          onClick = handlers.onInclude,
        )
      }
    }
    return
  }
  val dataHere = state.shown != PeopleSwitchShown.AWAITING_NOTICE
  if (confirming && dataHere) {
    item(key = "device-confirm") {
      Column(verticalArrangement = Arrangement.spacedBy(ActivationDimens.BlockGap)) {
        FluidInlineMessage(
          message = stringResource(
            if (state.shown == PeopleSwitchShown.LOCAL_ONLY) R.string.faces_settings_exclude_confirm_local else R.string.faces_settings_exclude_confirm_synced,
          ),
          title = stringResource(R.string.faces_settings_exclude_confirm_title),
          tone = FluidTone.Warning,
          modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(ActivationDimens.TightGap)) {
          FluidButton(
            text = stringResource(R.string.faces_settings_exclude_cancel),
            onClick = onCancel,
            style = FluidButtonStyle.Tinted,
            size = FluidButtonSize.Large,
            modifier = Modifier.weight(1f),
          )
          FluidButton(
            text = stringResource(R.string.faces_settings_exclude_confirm),
            onClick = {
              onConfirmed()
              handlers.onExclude()
            },
            style = FluidButtonStyle.Destructive,
            size = FluidButtonSize.Large,
            modifier = Modifier.weight(1f),
          )
        }
      }
    }
    return
  }
  item(key = "device-exclude") {
    FluidListGroup {
      FluidListRow(
        title = stringResource(R.string.faces_settings_exclude_title),
        subtitle = stringResource(R.string.faces_settings_exclude_message),
        tone = FluidTone.Neutral,
        leading = { Glyph(Icons.Rounded.PhonelinkOff) },
        // In attesa dell'avviso qui non c'e' ancora niente da cancellare: si esclude subito, come dall'avviso.
        onClick = if (dataHere) onAsk else handlers.onExclude,
      )
    }
  }
}

private fun LazyListScope.hiddenSection(count: Int, onOpen: () -> Unit) {
  item(key = "hidden") {
    FluidListGroup {
      FluidListRow(
        title = stringResource(R.string.faces_settings_hidden_title),
        subtitle = stringResource(R.string.faces_settings_hidden_message),
        tone = FluidTone.Neutral,
        leading = { Glyph(Icons.Rounded.VisibilityOff) },
        badge = { FluidStatusBadge(label = stringResource(R.string.faces_settings_hidden_count, count), tone = FluidTone.Neutral) },
        onClick = onOpen,
      )
    }
  }
}

/** I modelli sono dentro l'app (Q1): nome, versione e licenza, e il rimando a Informazioni e licenze. */
private fun LazyListScope.modelsSection(state: PeopleSettingsState, handlers: PeopleSettingsHandlers) {
  item(key = "models-header") { FluidSectionHeader(title = stringResource(R.string.faces_settings_section_models)) }
  item(key = "models") {
    FluidListGroup {
      state.models.forEach { model ->
        FluidListRow(
          title = stringResource(R.string.faces_settings_model_row, model.name, model.version),
          subtitle = stringResource(R.string.faces_settings_model_license, model.license),
          tone = FluidTone.Neutral,
          leading = { Glyph(Icons.Rounded.Face) },
          disclosure = false,
        )
        FluidListDivider()
      }
      FluidListRow(
        title = stringResource(R.string.faces_settings_licenses),
        subtitle = "",
        tone = FluidTone.Neutral,
        leading = { Glyph(Icons.Rounded.Gavel) },
        onClick = handlers.onOpenLicenses,
      )
    }
  }
  item(key = "models-note") { FluidSectionFootnote(text = stringResource(R.string.faces_settings_models_footnote)) }
}

/** "Disattiva e cancella": la riga porta al foglio di P8 ([onErase] null = non si offre); qui l'avanzamento, se c'e' (anche mentre la cancellazione e' in corso). */
private fun LazyListScope.eraseSection(state: PeopleSettingsState, onErase: (() -> Unit)?) {
  item(key = "erase-header") { FluidSectionHeader(title = stringResource(R.string.faces_settings_section_erase)) }
  if (onErase != null) {
    item(key = "erase") {
      FluidListGroup {
        FluidListRow(
          title = stringResource(R.string.faces_settings_erase_title),
          subtitle = stringResource(R.string.faces_settings_erase_message),
          tone = FluidTone.Danger,
          leading = { Glyph(Icons.Rounded.DeleteForever) },
          onClick = onErase,
        )
      }
    }
  }
  state.erase?.let { progress ->
    item(key = "erase-progress") {
      FluidSectionFootnote(
        text = eraseProgressText(progress),
        modifier = Modifier.padding(bottom = GalleryDimens.Gutter).semantics { liveRegion = LiveRegionMode.Polite },
      )
    }
  }
}

@Composable
private fun eraseProgressText(progress: EraseSheetState.Progress): String = stringResource(
  R.string.faces_settings_erase_progress,
  stringResource(stepText(progress.local)),
  stringResource(stepText(progress.nas)),
  progress.confirmed,
  progress.devices.size,
)

private fun stepText(step: StepState): Int = when (step) {
  StepState.NOT_STARTED -> R.string.faces_settings_step_not_started
  StepState.RUNNING -> R.string.faces_settings_step_running
  StepState.WAITING -> R.string.faces_settings_step_waiting
  StepState.DONE -> R.string.faces_settings_step_done
  StepState.NOT_NEEDED -> R.string.faces_settings_step_not_needed
}

@Composable
private fun Glyph(icon: ImageVector) {
  Icon(imageVector = icon, contentDescription = null)
}
