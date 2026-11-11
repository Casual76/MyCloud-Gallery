package dev.pampa.mycloudgallery.feature.settings.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import dev.antigravity.fluidengine.ui.fluid.ContinuousCornerShape
import dev.antigravity.fluidengine.ui.fluid.FluidMotion
import dev.antigravity.fluidengine.ui.fluid.FluidRadius
import dev.antigravity.fluidengine.ui.fluid.LocalFluidMotionPolicy
import dev.antigravity.fluidengine.ui.fluid.fluidPressable
import dev.pampa.mycloudgallery.core.designsystem.icon.GalleryIconFamily
import dev.pampa.mycloudgallery.core.designsystem.icon.GalleryIconStatus
import dev.pampa.mycloudgallery.core.designsystem.icon.GalleryIconTile
import dev.pampa.mycloudgallery.core.designsystem.theme.GalleryDimens
import dev.pampa.mycloudgallery.feature.settings.R

// LE PIASTRELLE DELLE RIGHE DELLE IMPOSTAZIONI (I3, fase I, 11/11). L'utente: «le icone sono troppo poco espressive»; «roba come le impostazioni diventa
// molto confusionaria». Prima ogni riga aveva il glifo grigio sulla piastrella del tono (quasi sempre lo stesso grigio-viola): le righe si
// distinguevano solo leggendo. Ora ogni riga ha la piastrella espressiva di core-designsystem (`GalleryIconTile`): il glifo bianco sul colore della
// sua **famiglia**, scelta dal glifo, cosi' la stessa cosa ha lo stesso colore in ogni pagina (la chiave e' sempre della sicurezza, il NAS sempre
// del backup, il Wi-Fi sempre della rete). Va nello slot `leading` di `FluidListRow` con `leadingTile = false`. Qui anche i due pezzi che tolgono i
// paragrafi dalle pagine: la spiegazione dietro un tocco ([SettingsInfo]) e la scheda di una scelta con le sue condizioni in glifi ([ChoiceCard]).

/** La piastrella di una riga: il glifo [icon] sul colore della sua famiglia ([SettingsFamilies.of]), con un segno di stato facoltativo. */
@Composable
internal fun RowTile(icon: ImageVector, status: GalleryIconStatus? = null, family: GalleryIconFamily = SettingsFamilies.of(icon)) {
  GalleryIconTile(icon = icon, family = family, status = status)
}

/**
 * Il segno di stato di una voce dal suo tono (I3): acceso e a posto la spunta, da guardare il punto esclamativo; le voci informative e quelle spente
 * senza segno (la riga dice gia' lo stato).
 */
internal fun toneMark(tone: dev.antigravity.fluidengine.ui.theme.FluidTone): GalleryIconStatus? = when (tone) {
  dev.antigravity.fluidengine.ui.theme.FluidTone.Success -> GalleryIconStatus.Done
  dev.antigravity.fluidengine.ui.theme.FluidTone.Warning -> GalleryIconStatus.Attention
  dev.antigravity.fluidengine.ui.theme.FluidTone.Danger -> GalleryIconStatus.Problem
  else -> null
}

/** Il valore corto a destra di una riga: uno stato («Attiva», «Ogni 3 h», il nome di una cartella), mai una descrizione. */
@Composable
internal fun RowValue(text: String) {
  Text(
    text = text,
    style = MaterialTheme.typography.bodyMedium,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    maxLines = 1,
    overflow = TextOverflow.Ellipsis,
    modifier = Modifier.widthIn(max = SettingsDimens.ValueMaxWidth),
  )
}

/**
 * Una spiegazione dietro un tocco: in pagina solo la «i» con un'etichetta corta ([label]); il tocco apre la frase sotto, nello stesso posto, e la
 * richiude. Un tasto da 48 dp con il suo stato per TalkBack; con «Rimuovi animazioni» la frase compare senza movimento.
 */
@Composable
internal fun SettingsInfo(label: String, text: String, modifier: Modifier = Modifier) {
  var open by rememberSaveable(text) { mutableStateOf(false) }
  val reduced = LocalFluidMotionPolicy.current.reducedMotion
  val openState = stringResource(R.string.info_open)
  val closedState = stringResource(R.string.info_closed)
  Column(modifier = modifier.fillMaxWidth().padding(horizontal = SettingsDimens.CardPadding)) {
    Row(
      modifier = Modifier
        .defaultMinSize(minHeight = GalleryDimens.MinTouchTarget)
        .fluidPressable(onClick = { open = !open }, role = Role.Button)
        .semantics(mergeDescendants = true) {
          role = Role.Button
          stateDescription = if (open) openState else closedState
        },
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(SettingsDimens.TightGap),
    ) {
      Icon(imageVector = Icons.Rounded.Info, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(SettingsDimens.InfoGlyph))
      Text(text = label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
    }
    AnimatedVisibility(
      visible = open,
      enter = if (reduced) EnterTransition.None else expandVertically(FluidMotion.intSize()),
      exit = if (reduced) ExitTransition.None else shrinkVertically(FluidMotion.intSize()),
    ) {
      Text(text = text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
  }
}

/** Una condizione di una scelta: il glifo e la parola che lo legge a voce. */
internal class Condition(val icon: ImageVector, val label: String)

/**
 * La scheda di una scelta fra poche (i preset di «Quando lavora l'app»): la piastrella grande, il nome e le condizioni come glifi in fila (la notte, la
 * corrente, il Wi-Fi, i dati mobili) al posto della frase. La scelta di adesso ha il bordo dell'accento e la spunta. Per TalkBack e' un tasto di
 * scelta con il nome, le condizioni a voce e lo stato.
 */
@Composable
internal fun ChoiceCard(
  title: String,
  icon: ImageVector,
  family: GalleryIconFamily,
  conditions: List<Condition>,
  selected: Boolean,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val shape = ContinuousCornerShape(FluidRadius.Card)
  val selectedState = stringResource(R.string.selected)
  val spoken = title + ". " + conditions.joinToString(", ") { it.label }
  Column(
    modifier = modifier
      .clip(shape)
      .background(MaterialTheme.colorScheme.surfaceContainerLow)
      .then(if (selected) Modifier.border(SettingsDimens.ChoiceBorder, MaterialTheme.colorScheme.primary, shape) else Modifier)
      .fluidPressable(onClick = onClick, role = Role.RadioButton)
      .semantics(mergeDescendants = true) {
        role = Role.RadioButton
        contentDescription = spoken
        if (selected) stateDescription = selectedState
        this.selected = selected
      }
      .padding(SettingsDimens.CardPadding),
    verticalArrangement = Arrangement.spacedBy(SettingsDimens.PillGap),
  ) {
    GalleryIconTile(
      icon = icon,
      family = family,
      size = SettingsDimens.ChoiceTile,
      status = if (selected) GalleryIconStatus.Done else null,
      ring = MaterialTheme.colorScheme.surfaceContainerLow,
    )
    Text(text = title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
    Row(horizontalArrangement = Arrangement.spacedBy(SettingsDimens.TightGap)) {
      conditions.forEach { condition ->
        Icon(
          imageVector = condition.icon,
          contentDescription = null,
          tint = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.size(SettingsDimens.ConditionGlyph),
        )
      }
    }
  }
}

/**
 * La famiglia di un glifo delle Impostazioni: per nome del glifo Material (`Rounded.Key` -> `Key`). Un glifo che qui non c'e' e' del sistema (grigio).
 * Le famiglie: foto, backup e NAS, persone e famiglia, ricordi, luoghi, ricerca e IA, sicurezza, rete, sistema, pericolo.
 */
internal object SettingsFamilies {

  fun of(icon: ImageVector): GalleryIconFamily = byName[icon.name.substringAfterLast('.')] ?: GalleryIconFamily.System

  private val byName: Map<String, GalleryIconFamily> = buildMap {
    fun put(family: GalleryIconFamily, vararg names: String) = names.forEach { put(it, family) }
    put(GalleryIconFamily.Photos, "PhotoLibrary", "Photo", "PhotoSizeSelectSmall", "HighQuality", "Album", "MotionPhotosOn", "Collections", "Image")
    put(GalleryIconFamily.Backup, "Backup", "CloudUpload", "Dns", "Storage", "Schedule", "Sync", "Cloud", "SdStorage", "BatterySaver", "Balance", "AllInclusive")
    put(GalleryIconFamily.People, "Groups", "Face", "Person", "PersonAdd", "SwitchAccount", "IosShare", "Share", "FamilyRestroom", "Inventory2", "QrCode2")
    put(GalleryIconFamily.Memories, "AutoAwesome", "Today", "NotificationImportant", "Notifications")
    put(GalleryIconFamily.Places, "Map", "LocationOff", "LocationOn", "Place", "Check", "Verified")
    put(GalleryIconFamily.Search, "ImageSearch", "Search", "ManageSearch", "Nightlight", "Bedtime")
    put(
      GalleryIconFamily.Security,
      "Key", "VisibilityOff", "AdminPanelSettings", "LockClock", "Fingerprint", "Lock", "Security", "Pin", "Shield", "QrCodeScanner", "HourglassTop",
    )
    put(GalleryIconFamily.Network, "Wifi", "VpnLock", "VpnKey", "Router", "Language", "DataUsage", "Home", "SwapHoriz", "SignalCellularAlt", "Public")
    put(GalleryIconFamily.Danger, "RestartAlt", "PhonelinkErase", "PersonRemove", "Block", "Delete")
  }
}
