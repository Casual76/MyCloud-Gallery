package dev.pampa.mycloudgallery.feature.people.erase

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.RadioButtonChecked
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import dev.antigravity.fluidengine.ui.fluid.FluidButton
import dev.antigravity.fluidengine.ui.fluid.FluidButtonSize
import dev.antigravity.fluidengine.ui.fluid.FluidButtonStyle
import dev.antigravity.fluidengine.ui.fluid.FluidLoadingBlock
import dev.antigravity.fluidengine.ui.fluid.FluidScreen
import dev.antigravity.fluidengine.ui.fluid.FluidSectionFootnote
import dev.antigravity.fluidengine.ui.fluid.FluidSectionHeader
import dev.antigravity.fluidengine.ui.theme.FluidInlineMessage
import dev.antigravity.fluidengine.ui.theme.FluidListDivider
import dev.antigravity.fluidengine.ui.theme.FluidListGroup
import dev.antigravity.fluidengine.ui.theme.FluidListRow
import dev.antigravity.fluidengine.ui.theme.FluidStatusBadge
import dev.antigravity.fluidengine.ui.theme.FluidTone
import dev.pampa.mycloudgallery.core.designsystem.theme.GalleryDimens
import dev.pampa.mycloudgallery.core.faces.contract.AckState
import dev.pampa.mycloudgallery.core.faces.contract.DeviceAck
import dev.pampa.mycloudgallery.core.faces.contract.EraseWait
import dev.pampa.mycloudgallery.core.faces.contract.FacesEraseConfirmation
import dev.pampa.mycloudgallery.core.faces.contract.PeopleAlbumsChoice
import dev.pampa.mycloudgallery.core.faces.contract.StepState
import dev.pampa.mycloudgallery.feature.people.R
import dev.pampa.mycloudgallery.feature.people.contract.EraseSheetState
import dev.pampa.mycloudgallery.feature.people.ui.PeopleDimens

// IL FOGLIO E LA PAGINA "DISATTIVA E CANCELLA" (docs/fase8-progetto.md §2.8, D29, D30, D34, E-F30, E-F34; riga P8 del §4.4). Prima l'anteprima: cosa si
// perde (i nomi, gli album smart con le persone e la scelta su cosa farne, le ricerche recenti con i nomi), i dispositivi dell'account e chi e' spento,
// la nota della recinzione; "Annulla" e "Disattiva e cancella" (colore di pericolo). Poi l'avanzamento: su questo dispositivo, sul NAS, chi ha
// confermato e perche' si aspetta. Composable fatte solo con i componenti dell'engine (G2, D38); misure da PeopleDimens.
//
// **E' l'unico file che costruisce un `FacesEraseConfirmation`** (Phase8ArchitectureTest, `erase/FacesErase*.kt`), al tocco del pulsante: con
// l'impronta dell'anteprima mostrata e l'istante (orologio monotono dei volti) in cui e' comparsa ([EraseSheetToken]).

@Composable
fun FacesEraseSheet(
  state: EraseSheetState?,
  token: EraseSheetToken?,
  onConfirm: (FacesEraseConfirmation) -> Unit,
  onChoose: (PeopleAlbumsChoice) -> Unit,
  onCancel: () -> Unit,
  onReload: () -> Unit,
  onClose: () -> Unit,
  onBack: () -> Unit,
  modifier: Modifier = Modifier,
  /**
   * Correzioni F (L193): porta a Impostazioni, Dispositivi, dove si revoca un dispositivo dismesso che tiene ferma la fine. `:app` lo lega alla rotta delle
   * Impostazioni dei dispositivi; senza (null) la riga del dismesso dice lo stesso cosa fare, ma non c'e' il tasto.
   */
  onOpenDevices: (() -> Unit)? = null,
) {
  FluidScreen(title = stringResource(R.string.faces_erase_title), modifier = modifier, onBack = onBack) {
    when (state) {
      null -> loading(R.string.faces_erase_loading)
      is EraseSheetState.Preview -> preview(state, token, onConfirm, onChoose, onCancel, onOpenDevices)
      EraseSheetState.Confirming -> loading(R.string.faces_erase_confirming)
      EraseSheetState.Started -> message(R.string.faces_erase_started_title, R.string.faces_erase_started_message, FluidTone.Success, onClose)
      EraseSheetState.Expired -> expired(onReload, onClose)
      is EraseSheetState.Progress -> progress(state, onClose, onOpenDevices)
      EraseSheetState.Done -> message(R.string.faces_erase_done_title, R.string.faces_erase_done_message, FluidTone.Success, onClose)
      EraseSheetState.DoneHere -> message(R.string.faces_erase_done_here_title, R.string.faces_erase_done_here_message, FluidTone.Success, onClose)
      EraseSheetState.NothingToErase -> message(R.string.faces_erase_nothing_title, R.string.faces_erase_nothing_message, FluidTone.Info, onClose)
      is EraseSheetState.WaitingDevices -> waitingDevices(state, onClose, onOpenDevices)
    }
  }
}

private fun LazyListScope.loading(message: Int) {
  item(key = "loading") {
    Column(modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
      FluidLoadingBlock()
      Text(
        text = stringResource(message),
        modifier = Modifier.fillMaxWidth().padding(vertical = PeopleDimens.BlockGap),
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}

private fun LazyListScope.preview(
  state: EraseSheetState.Preview,
  token: EraseSheetToken?,
  onConfirm: (FacesEraseConfirmation) -> Unit,
  onChoose: (PeopleAlbumsChoice) -> Unit,
  onCancel: () -> Unit,
  onOpenDevices: (() -> Unit)?,
) {
  if (state.localOnly) {
    item(key = "local-only") {
      FluidInlineMessage(
        message = stringResource(R.string.faces_erase_local_only),
        title = stringResource(R.string.faces_erase_local_only_title),
        tone = FluidTone.Info,
        modifier = Modifier.padding(bottom = PeopleDimens.BlockGap),
      )
    }
  }
  item(key = "what") {
    Column(verticalArrangement = Arrangement.spacedBy(PeopleDimens.TightGap)) {
      // Con la recinzione sul NAS non c'e' niente: la frase non lo nomina (la nota sopra dice "si cancellano qui").
      Paragraph(stringResource(if (state.localOnly) R.string.faces_erase_intro_local else R.string.faces_erase_intro))
      Paragraph(
        if (state.namedPeople > 0) {
          pluralStringResource(R.plurals.faces_erase_named, state.namedPeople, state.namedPeople)
        } else {
          stringResource(R.string.faces_erase_named_none)
        },
      )
      if (state.historyWithNames) Paragraph(stringResource(R.string.faces_erase_history))
    }
  }
  if (state.albumsWithPeople > 0) {
    item(key = "albums-text") {
      Paragraph(pluralStringResource(R.plurals.faces_erase_albums, state.albumsWithPeople, state.albumsWithPeople), Modifier.padding(top = PeopleDimens.BlockGap))
    }
    item(key = "albums") {
      FluidListGroup(modifier = Modifier.padding(top = PeopleDimens.TightGap)) {
        ChoiceRow(PeopleAlbumsChoice.KEEP_PHOTOS, state.choice, R.string.faces_erase_keep_photos_title, R.string.faces_erase_keep_photos_message, onChoose)
        FluidListDivider()
        ChoiceRow(PeopleAlbumsChoice.DROP_RULE, state.choice, R.string.faces_erase_drop_rule_title, R.string.faces_erase_drop_rule_message, onChoose)
      }
    }
  }
  if (!state.localOnly && state.devices.isNotEmpty()) devices(state.devices, footnote = true, onOpenDevices = onOpenDevices)
  item(key = "buttons") {
    val cancelDescription = stringResource(R.string.faces_erase_cancel_description)
    val confirmDescription = stringResource(if (state.localOnly) R.string.faces_erase_confirm_description_local else R.string.faces_erase_confirm_description)
    // Uno sopra l'altro, a tutta larghezza: "Disattiva e cancella" non si spezza ne' si taglia nemmeno a testo grande.
    Column(
      modifier = Modifier.fillMaxWidth().padding(top = PeopleDimens.BlockGap, bottom = GalleryDimens.Gutter),
      verticalArrangement = Arrangement.spacedBy(PeopleDimens.TightGap),
    ) {
      FluidButton(
        text = stringResource(R.string.faces_erase_confirm),
        // La conferma nasce qui, al tocco, con l'anteprima che l'utente ha davanti (e scade dopo 10 minuti).
        onClick = { token?.let { onConfirm(FacesEraseConfirmation(it.previewDigest, it.shownAtMonotonicNanos)) } },
        style = FluidButtonStyle.Destructive,
        size = FluidButtonSize.Large,
        enabled = state.canConfirm && token != null,
        fillWidth = true,
        modifier = Modifier.semantics { contentDescription = confirmDescription },
      )
      FluidButton(
        text = stringResource(R.string.faces_erase_cancel),
        onClick = onCancel,
        style = FluidButtonStyle.Tinted,
        size = FluidButtonSize.Large,
        fillWidth = true,
        modifier = Modifier.semantics { contentDescription = cancelDescription },
      )
    }
  }
}

@Composable
private fun ChoiceRow(choice: PeopleAlbumsChoice, selected: PeopleAlbumsChoice?, title: Int, message: Int, onChoose: (PeopleAlbumsChoice) -> Unit) {
  val isSelected = choice == selected
  FluidListRow(
    title = stringResource(title),
    subtitle = stringResource(message),
    tone = FluidTone.Neutral,
    leading = { Glyph(if (isSelected) Icons.Rounded.RadioButtonChecked else Icons.Rounded.RadioButtonUnchecked) },
    onClick = { onChoose(choice) },
    selected = isSelected,
    disclosure = false,
    modifier = Modifier.semantics { role = Role.RadioButton },
  )
}

private fun LazyListScope.devices(devices: List<DeviceAck>, footnote: Boolean, onOpenDevices: (() -> Unit)?) {
  item(key = "devices-header") { FluidSectionHeader(title = stringResource(R.string.faces_erase_section_devices)) }
  item(key = "devices") {
    FluidListGroup {
      devices.forEachIndexed { index, device ->
        if (index > 0) FluidListDivider()
        DeviceRow(device)
      }
    }
  }
  // L193: un dismesso che tiene ferma la fine si revoca da Impostazioni, Dispositivi: il tasto sta subito sotto l'elenco, da 48 dp, con una frase per TalkBack.
  if (onOpenDevices != null && devices.any(EraseText::holdsTheEnd)) item(key = "devices-open") { OpenDevicesButton(onOpenDevices) }
  if (footnote) item(key = "devices-note") { FluidSectionFootnote(text = stringResource(R.string.faces_erase_devices_footnote)) }
}

@Composable
private fun OpenDevicesButton(onOpenDevices: () -> Unit) {
  val description = stringResource(R.string.faces_erase_open_devices_description)
  FluidButton(
    text = stringResource(R.string.faces_erase_open_devices),
    onClick = onOpenDevices,
    style = FluidButtonStyle.Tinted,
    size = FluidButtonSize.Large,
    fillWidth = true,
    modifier = Modifier.padding(top = PeopleDimens.TightGap).semantics { contentDescription = description },
  )
}

@Composable
private fun DeviceRow(device: DeviceAck) {
  val title = if (device.thisDevice) stringResource(R.string.faces_erase_device_this, device.label) else device.label
  val (subtitle, tone) = when (device.state) {
    AckState.CONFIRMED -> stringResource(R.string.faces_erase_device_confirmed) to FluidTone.Success
    AckState.NOT_EXPECTED -> stringResource(R.string.faces_erase_device_not_expected) to FluidTone.Neutral
    // L193: un dismesso si aspetta ancora (D30), ma le sue teste sono congelate e non puo' piu' dire `off`: la riga dice cosa fare, non "cancellera' appena si collega".
    AckState.WAITING -> if (EraseText.holdsTheEnd(device)) {
      stringResource(R.string.faces_erase_device_dismissed) to FluidTone.Warning
    } else {
      val since = device.lastContactAtNas?.let { EraseText.day(it) }
      (if (since != null) stringResource(R.string.faces_erase_device_waiting_since, since) else stringResource(R.string.faces_erase_device_waiting)) to FluidTone.Warning
    }
  }
  FluidListRow(
    title = title,
    subtitle = subtitle,
    tone = FluidTone.Neutral,
    leading = { Glyph(Icons.Rounded.PhoneAndroid) },
    badge = { FluidStatusBadge(label = stringResource(EraseText.ack(device.state)), tone = tone) },
    disclosure = false,
  )
}

private fun LazyListScope.expired(onReload: () -> Unit, onClose: () -> Unit) {
  item(key = "expired") {
    FluidInlineMessage(
      message = stringResource(R.string.faces_erase_expired_message),
      title = stringResource(R.string.faces_erase_expired_title),
      tone = FluidTone.Warning,
      modifier = Modifier.padding(bottom = PeopleDimens.BlockGap).semantics { liveRegion = LiveRegionMode.Polite },
    )
  }
  item(key = "reload") {
    FluidButton(
      text = stringResource(R.string.faces_erase_reload),
      onClick = onReload,
      style = FluidButtonStyle.Filled,
      size = FluidButtonSize.Large,
      fillWidth = true,
    )
  }
  closeButton(onClose)
}

private fun LazyListScope.progress(state: EraseSheetState.Progress, onClose: () -> Unit, onOpenDevices: (() -> Unit)?) {
  item(key = "progress-header") { FluidSectionHeader(title = stringResource(R.string.faces_erase_section_progress)) }
  item(key = "progress") {
    FluidListGroup(modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
      StepRow(R.string.faces_erase_row_local, state.local, Icons.Rounded.PhoneAndroid)
      FluidListDivider()
      StepRow(R.string.faces_erase_row_nas, state.nas, Icons.Rounded.Cloud)
    }
  }
  state.waiting?.let { wait ->
    item(key = "waiting") {
      FluidInlineMessage(
        message = stringResource(EraseText.wait(wait)),
        title = stringResource(if (wait == EraseWait.RECYCLE_BIN) R.string.faces_erase_recycle_title else R.string.faces_erase_wait_title),
        tone = if (wait == EraseWait.RECYCLE_BIN) FluidTone.Warning else FluidTone.Info,
        modifier = Modifier.padding(vertical = PeopleDimens.BlockGap).semantics { liveRegion = LiveRegionMode.Polite },
      )
    }
  }
  if (state.devices.isNotEmpty()) devices(state.devices, footnote = false, onOpenDevices = onOpenDevices)
  closeButton(onClose)
}

@Composable
private fun StepRow(title: Int, step: StepState, icon: ImageVector) {
  FluidListRow(
    title = stringResource(title),
    subtitle = stringResource(EraseText.step(step)),
    tone = FluidTone.Neutral,
    leading = { Glyph(if (step == StepState.DONE) Icons.Rounded.CheckCircle else icon) },
    disclosure = false,
  )
}

private fun LazyListScope.waitingDevices(state: EraseSheetState.WaitingDevices, onClose: () -> Unit, onOpenDevices: (() -> Unit)?) {
  item(key = "waiting-devices") {
    FluidInlineMessage(
      message = stringResource(R.string.faces_erase_waiting_message),
      title = stringResource(R.string.faces_erase_waiting_title),
      tone = FluidTone.Info,
      modifier = Modifier.padding(bottom = PeopleDimens.BlockGap).semantics { liveRegion = LiveRegionMode.Polite },
    )
  }
  devices(state.devices, footnote = false, onOpenDevices = onOpenDevices)
  closeButton(onClose)
}

private fun LazyListScope.message(title: Int, message: Int, tone: FluidTone, onClose: () -> Unit) {
  item(key = "message") {
    FluidInlineMessage(
      message = stringResource(message),
      title = stringResource(title),
      tone = tone,
      modifier = Modifier.padding(bottom = PeopleDimens.BlockGap).semantics { liveRegion = LiveRegionMode.Polite },
    )
  }
  closeButton(onClose)
}

private fun LazyListScope.closeButton(onClose: () -> Unit) {
  item(key = "close") {
    FluidButton(
      text = stringResource(R.string.faces_erase_close),
      onClick = onClose,
      style = FluidButtonStyle.Tinted,
      size = FluidButtonSize.Large,
      fillWidth = true,
      modifier = Modifier.padding(top = PeopleDimens.TightGap, bottom = GalleryDimens.Gutter),
    )
  }
}

@Composable
private fun Paragraph(text: String, modifier: Modifier = Modifier) {
  Text(
    text = text,
    modifier = modifier.widthIn(max = PeopleDimens.SheetWidth).fillMaxWidth(),
    style = MaterialTheme.typography.bodyLarge,
    color = MaterialTheme.colorScheme.onSurface,
  )
}

@Composable
private fun Glyph(icon: ImageVector) {
  Icon(imageVector = icon, contentDescription = null)
}
