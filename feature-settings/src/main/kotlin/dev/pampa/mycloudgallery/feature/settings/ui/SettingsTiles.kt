package dev.pampa.mycloudgallery.feature.settings.ui

import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import dev.antigravity.fluidengine.ui.fluid.ContinuousCornerShape
import dev.antigravity.fluidengine.ui.fluid.FluidRadius
import dev.antigravity.fluidengine.ui.fluid.fluidPressable
import dev.pampa.mycloudgallery.core.designsystem.icon.GalleryIconFamily
import dev.pampa.mycloudgallery.core.designsystem.icon.GalleryIconStatus
import dev.pampa.mycloudgallery.core.designsystem.icon.GalleryIconTile
import dev.pampa.mycloudgallery.core.designsystem.icon.GalleryIconTileDefaults
import dev.pampa.mycloudgallery.core.designsystem.design.GalleryInfoDetail
import dev.pampa.mycloudgallery.core.designsystem.design.GalleryInfoGlyph
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
 * Una spiegazione dietro un tocco, con la «i» unica della galleria (`GalleryInfoGlyph` e `GalleryInfoDetail`, giro 1 della fase I): in pagina il segno
 * con un'etichetta corta ([label]); il tocco apre la frase sotto, nello stesso posto e senza animazione di misura, e la richiude. Per TalkBack e' un
 * tasto con il suo stato, e il perche' lo sente gia' nella descrizione (senza toccare).
 */
@Composable
internal fun SettingsInfo(label: String, text: String, modifier: Modifier = Modifier) {
  var open by rememberSaveable(text) { mutableStateOf(false) }
  val openState = stringResource(R.string.info_open)
  val closedState = stringResource(R.string.info_closed)
  Column(modifier = modifier.fillMaxWidth().padding(horizontal = SettingsDimens.CardPadding)) {
    Row(
      modifier = Modifier
        .defaultMinSize(minHeight = GalleryDimens.MinTouchTarget)
        .fluidPressable(onClick = { open = !open }, role = Role.Button)
        .semantics(mergeDescendants = true) {
          role = Role.Button
          contentDescription = label + ". " + text
          stateDescription = if (open) openState else closedState
        },
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(SettingsDimens.TightGap),
    ) {
      GalleryInfoGlyph(open = open)
      // Giro 2 (CU-30): la «i» ha una tinta sola, quella del componente comune; l'etichetta accanto la segue.
      Text(text = label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    GalleryInfoDetail(text = text, visible = open, color = MaterialTheme.colorScheme.onSurfaceVariant)
  }
}

/**
 * La testata di una sottopagina (giro 1 della fase I, CU-5): **lo stato e basta**. La piastrella grande della famiglia con il segno dello stato e il
 * valore in una o due parole («In casa», «Collegato», «4 dispositivi»); [label] solo se dice una cosa che la pagina non ripete («Sei l'admin»).
 * Niente frase di spiegazione: il come sta dietro la «i» della pagina. Al posto di `FluidHero`, che scrive sempre titolo e descrizione e ha la
 * piastrella grigia.
 */
@Composable
internal fun SettingsStatusHeader(
  icon: ImageVector,
  family: GalleryIconFamily,
  value: String,
  modifier: Modifier = Modifier,
  status: GalleryIconStatus? = null,
  label: String? = null,
  action: (@Composable () -> Unit)? = null,
) {
  val container = MaterialTheme.colorScheme.surfaceContainerLow
  Column(
    modifier = modifier
      .fillMaxWidth()
      .clip(ContinuousCornerShape(FluidRadius.Group))
      .background(container)
      .padding(SettingsDimens.HeaderPadding),
    verticalArrangement = Arrangement.spacedBy(SettingsDimens.CardGap),
  ) {
    Row(
      modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) { heading() },
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(SettingsDimens.HeaderGap),
    ) {
      GalleryIconTile(icon = icon, family = family, size = GalleryIconTileDefaults.Large, status = status, ring = container)
      Column(modifier = Modifier.weight(1f)) {
        Text(
          text = value,
          style = MaterialTheme.typography.headlineSmall,
          fontWeight = FontWeight.Bold,
          color = MaterialTheme.colorScheme.onSurface,
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
        )
        if (label != null) {
          Text(text = label, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
      }
    }
    action?.invoke()
  }
}

/**
 * Una riga che porta a una pagina, con la descrizione stabile **solo per TalkBack** (giro 1 della fase I, CG-4): in pagina il titolo e la piastrella
 * (con il segno se c'e' uno stato), la frase che diceva che cosa fa la sente chi usa TalkBack e la legge chi apre la «i» del gruppo.
 */
@Composable
internal fun DescribedRow(
  title: String,
  detail: String,
  icon: ImageVector,
  onClick: () -> Unit,
  tone: dev.antigravity.fluidengine.ui.theme.FluidTone = dev.antigravity.fluidengine.ui.theme.FluidTone.Info,
  status: GalleryIconStatus? = null,
  family: GalleryIconFamily = SettingsFamilies.of(icon),
) {
  dev.antigravity.fluidengine.ui.theme.FluidListRow(
    title = title,
    subtitle = "",
    tone = tone,
    leading = { RowTile(icon, status = status, family = family) },
    leadingTile = false,
    onClick = onClick,
    modifier = Modifier.semantics { contentDescription = title + ". " + detail },
  )
}

/**
 * Il segno di una scelta fra piu' opzioni (giro 1 della fase I, CU-16: prima un interruttore per riga, e sembrava che se ne potessero accendere
 * piu' d'uno): il tondo vuoto, e la spunta piena dell'accento sulla scelta di adesso. Decorativo: la riga dice a TalkBack che e' una scelta.
 */
@Composable
internal fun ChoiceMark(selected: Boolean) {
  Icon(
    imageVector = if (selected) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
    contentDescription = null,
    tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
    modifier = Modifier.size(SettingsDimens.ChoiceMark),
  )
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
 * La famiglia di un glifo delle Impostazioni: per nome del glifo Material (`Rounded.Key` -> `Key`). Un glifo che qui non c'e' e' del sistema (grigio): da usare solo dove non ci sono piastrelle colorate accanto.
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
    // Giro 1 della fase I (regola comune: nessuna piastrella grigia accanto alle colorate): il widget mostra le foto, i dispositivi sono della
    // famiglia, la Diagnostica guarda il backup e il NAS, le informazioni e le licenze sono testi (la famiglia della ricerca).
    put(GalleryIconFamily.Photos, "Widgets")
    put(GalleryIconFamily.People, "Devices", "Edit")
    put(GalleryIconFamily.Backup, "MonitorHeart")
    put(GalleryIconFamily.Search, "Info", "Description", "Code", "Help")
  }
}
