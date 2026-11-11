package dev.pampa.mycloudgallery.feature.settings.devices

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.HourglassTop
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.PhonelinkErase
import androidx.compose.material.icons.rounded.TabletAndroid
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import dev.antigravity.fluidengine.ui.theme.FluidListRow
import dev.antigravity.fluidengine.ui.theme.FluidTone
import dev.pampa.mycloudgallery.core.family.contract.DeviceRoleView
import dev.pampa.mycloudgallery.core.trust.registry.DeviceStatus
import dev.pampa.mycloudgallery.feature.settings.R
import dev.pampa.mycloudgallery.feature.settings.ui.RowTile
import dev.pampa.mycloudgallery.feature.settings.ui.StatusPill
import dev.pampa.mycloudgallery.feature.settings.ui.agoText

/** Come si chiama uno stato del registro, per chi legge. */
@StringRes
internal fun DeviceStatus.labelRes(): Int = when (this) {
  DeviceStatus.APPROVED -> R.string.status_approved
  DeviceStatus.RETIRED -> R.string.status_retired
  DeviceStatus.REVOKED -> R.string.status_revoked
  DeviceStatus.LOST -> R.string.status_lost
}

/** Il tono di uno stato: verde se lavora, grigio se dismesso, rosso se non e' piu' fidato. */
internal fun DeviceStatus.tone(): FluidTone = when (this) {
  DeviceStatus.APPROVED -> FluidTone.Success
  DeviceStatus.RETIRED -> FluidTone.Neutral
  DeviceStatus.REVOKED, DeviceStatus.LOST -> FluidTone.Danger
}

/** L'icona di un dispositivo: segue lo stato quando non lavora piu', altrimenti il tipo (telefono o tablet). */
internal fun DeviceUi.icon(): ImageVector = when (status) {
  DeviceStatus.LOST -> Icons.Rounded.PhonelinkErase
  DeviceStatus.REVOKED -> Icons.Rounded.Block
  DeviceStatus.RETIRED, DeviceStatus.APPROVED -> if (isTablet) Icons.Rounded.TabletAndroid else Icons.Rounded.PhoneAndroid
}

@StringRes
internal fun DeviceRoleView.labelRes(): Int = when (this) {
  DeviceRoleView.INDEXER -> R.string.role_indexer
  DeviceRoleView.PREPARER -> R.string.role_preparer
}

/**
 * La riga di un dispositivo nell'elenco. Il tono sta sulla piastrella dell'icona (mai sulla riga) e le
 * pillole dicono cio' che conta: se e' questo dispositivo, se e' admin, se non lavora piu', se potrebbe
 * essere una reinstallazione.
 */
@Composable
internal fun DeviceRow(device: DeviceUi, onClick: () -> Unit, modifier: Modifier = Modifier) {
  val seen = device.lastSeenAgoMs?.let { stringResource(R.string.device_seen, agoText(it)) }
    ?: stringResource(R.string.device_never_seen)
  FluidListRow(
    title = device.name,
    subtitle = stringResource(R.string.device_model_user, device.model, device.nasUser),
    meta = seen,
    modifier = modifier,
    tone = if (device.isApproved) FluidTone.Primary else device.status.tone(),
    leading = { RowTile(device.icon()) },
    leadingTile = false,
    labels = {
      if (device.isThisDevice) StatusPill(stringResource(R.string.pill_this_device), FluidTone.Info)
      if (device.admin && device.isApproved) StatusPill(stringResource(R.string.pill_admin), FluidTone.Primary)
      if (!device.isApproved) StatusPill(stringResource(device.status.labelRes()), device.status.tone())
      if (device.lookAlikeOfName != null) StatusPill(stringResource(R.string.pill_look_alike), FluidTone.Warning)
    },
    onClick = onClick,
  )
}

/** La riga di una richiesta d'ingresso: chi chiede, da quanto, e se c'e' ancora tempo per approvarla. */
@Composable
internal fun RequestRow(request: RequestUi, onClick: () -> Unit, modifier: Modifier = Modifier) {
  FluidListRow(
    title = request.name,
    subtitle = stringResource(R.string.request_model_user, request.model, request.declaredNasUser),
    meta = stringResource(R.string.request_seen, agoText(request.ageMs)),
    modifier = modifier,
    tone = if (request.windowOpen) FluidTone.Warning else FluidTone.Neutral,
    leading = { RowTile(if (request.windowOpen) Icons.Rounded.HourglassTop else Icons.Rounded.Verified) },
    leadingTile = false,
    labels = {
      StatusPill(
        stringResource(if (request.windowOpen) R.string.pill_to_approve else R.string.pill_expired),
        if (request.windowOpen) FluidTone.Warning else FluidTone.Neutral,
      )
      if (request.lookAlikeOfName != null) StatusPill(stringResource(R.string.pill_look_alike), FluidTone.Warning)
    },
    onClick = onClick,
  )
}
