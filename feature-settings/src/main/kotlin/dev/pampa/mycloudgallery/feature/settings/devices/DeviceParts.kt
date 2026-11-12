package dev.pampa.mycloudgallery.feature.settings.devices

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.HourglassTop
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.PhonelinkErase
import androidx.compose.material.icons.rounded.TabletAndroid
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
import dev.pampa.mycloudgallery.feature.settings.ui.RowValue
import dev.pampa.mycloudgallery.core.designsystem.icon.GalleryIconFamily
import dev.pampa.mycloudgallery.core.designsystem.icon.GalleryIconStatus
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics

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

/** Il segno sulla piastrella: perso o revocato (problema), dismesso (fermo), forse una reinstallazione (attenzione); un dispositivo che lavora, nessuno. */
internal fun DeviceUi.mark(): GalleryIconStatus? = when {
  status == DeviceStatus.LOST || status == DeviceStatus.REVOKED -> GalleryIconStatus.Problem
  status == DeviceStatus.RETIRED -> GalleryIconStatus.Stopped
  lookAlikeOfName != null -> GalleryIconStatus.Attention
  else -> null
}

/**
 * La riga di un dispositivo nell'elenco (giro 1 della fase I, CU-5: prima modello, account, «Visto…» e fino a quattro capsule per riga). In pagina:
 * il nome, la piastrella del tipo con il segno dello stato, a destra lo stato se non lavora piu' (altrimenti quando e' stato visto), e la sola
 * capsula «Admin». Modello, account e il resto li sente TalkBack e li mostra la pagina del dispositivo.
 */
@Composable
internal fun DeviceRow(device: DeviceUi, onClick: () -> Unit, modifier: Modifier = Modifier) {
  val seen = device.lastSeenAgoMs?.let { agoText(it) }
  val statusLabel = if (device.isApproved) null else stringResource(device.status.labelRes())
  val value = statusLabel ?: seen ?: stringResource(R.string.device_never_seen_short)
  val admin = device.admin && device.isApproved
  val spoken = listOfNotNull(
    device.name,
    statusLabel,
    if (admin) stringResource(R.string.pill_admin) else null,
    if (device.isThisDevice) stringResource(R.string.pill_this_device) else null,
    if (device.lookAlikeOfName != null) stringResource(R.string.pill_look_alike) else null,
    stringResource(R.string.device_model_user, device.model, device.nasUser),
    seen?.let { stringResource(R.string.device_seen, it) } ?: stringResource(R.string.device_never_seen),
  ).joinToString(", ")
  FluidListRow(
    title = device.name,
    subtitle = "",
    modifier = modifier.semantics { contentDescription = spoken },
    tone = if (device.isApproved) FluidTone.Primary else device.status.tone(),
    leading = {
      RowTile(
        device.icon(),
        status = device.mark(),
        family = if (device.status == DeviceStatus.LOST || device.status == DeviceStatus.REVOKED) GalleryIconFamily.Danger else GalleryIconFamily.People,
      )
    },
    leadingTile = false,
    labels = if (admin) ({ StatusPill(stringResource(R.string.pill_admin), FluidTone.Primary) }) else null,
    badge = { RowValue(value) },
    onClick = onClick,
  )
}

/** La riga di una richiesta d'ingresso: chi chiede (il nome), e a destra se c'e' ancora tempo per approvarla; la clessidra e' l'attesa. */
@Composable
internal fun RequestRow(request: RequestUi, onClick: () -> Unit, modifier: Modifier = Modifier) {
  val value = stringResource(if (request.windowOpen) R.string.pill_to_approve else R.string.pill_expired)
  val spoken = listOfNotNull(
    request.name,
    value,
    if (request.lookAlikeOfName != null) stringResource(R.string.pill_look_alike) else null,
    stringResource(R.string.request_model_user, request.model, request.declaredNasUser),
    stringResource(R.string.request_seen, agoText(request.ageMs)),
  ).joinToString(", ")
  FluidListRow(
    title = request.name,
    subtitle = "",
    modifier = modifier.semantics { contentDescription = spoken },
    tone = if (request.windowOpen) FluidTone.Warning else FluidTone.Neutral,
    leading = {
      RowTile(
        Icons.Rounded.HourglassTop,
        status = when {
          !request.windowOpen -> GalleryIconStatus.Stopped
          request.lookAlikeOfName != null -> GalleryIconStatus.Attention
          else -> null
        },
        family = GalleryIconFamily.People,
      )
    },
    leadingTile = false,
    badge = { RowValue(value) },
    onClick = onClick,
  )
}
