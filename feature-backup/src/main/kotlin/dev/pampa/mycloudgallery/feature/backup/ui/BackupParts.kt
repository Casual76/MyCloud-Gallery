package dev.pampa.mycloudgallery.feature.backup.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import dev.antigravity.fluidengine.ui.fluid.ContinuousCornerShape
import dev.antigravity.fluidengine.ui.fluid.FluidButton
import dev.antigravity.fluidengine.ui.fluid.FluidButtonSize
import dev.antigravity.fluidengine.ui.fluid.FluidButtonStyle
import dev.antigravity.fluidengine.ui.fluid.FluidProgressBar
import dev.antigravity.fluidengine.ui.fluid.FluidRadius
import dev.antigravity.fluidengine.ui.fluid.FluidSpinner
import dev.antigravity.fluidengine.ui.fluid.fluidPressable
import dev.pampa.mycloudgallery.core.designsystem.a11y.GalleryLiveAnnouncement
import dev.pampa.mycloudgallery.feature.backup.BackupDimens
import dev.pampa.mycloudgallery.feature.backup.R
import dev.pampa.mycloudgallery.core.designsystem.design.GalleryInfoGlyph
import dev.pampa.mycloudgallery.core.designsystem.design.GalleryInfoDetail
import dev.pampa.mycloudgallery.core.designsystem.theme.GalleryDimens
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.res.stringResource
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf

// I pezzi delle pagine del backup che non sono chrome: la scheda di stato (un tono, un glifo, un titolo, una frase, un avanzamento e dei
// tasti), l'intestazione di un gruppo, la nota. Tutti dai ruoli dello schema (nessun colore a mano) e dai toni del piede, le cui coppie
// `GalleryFooterStatusTest` verifica su ogni accento; `BackupContrastTest` verifica quelle di questo file con le stesse soglie. Come
// `NasNotice` di feature-nas: i feature non si conoscono, e D30 vieta uno stile nuovo.

/**
 * La scheda di stato: il glifo del tono (o un pezzo suo, [leading]), il [title], la [detail] e, se serve, una barra di avanzamento ([progress]) o il cerchio che
 * gira ([busy]) e i tasti ([actions]). Sta nel contenuto (non e' vetro: non galleggia). Per TalkBack titolo e frase sono una cosa sola;
 * i tasti sono bersagli a parte, da 48 dp o piu'.
 */
@Composable
internal fun BackupCard(
  tone: BackupTone,
  glyph: ImageVector,
  title: String,
  modifier: Modifier = Modifier,
  detail: String? = null,
  hint: String? = null,
  progress: Float? = null,
  busy: Boolean = false,
  leading: (@Composable () -> Unit)? = null,
  actions: (@Composable ColumnScope.() -> Unit)? = null,
) {
  val style = backupToneStyle(tone, MaterialTheme.colorScheme)
  // Fase 10 P8 (D23 (6), §2.7 "Backup, NAS, Diagnostica"): lo stato che cambia da solo si annuncia **una volta** (regione viva educata) e non a ogni
  // numero: si confronta il titolo senza le cifre ("312 in coda" e "311 in coda" sono lo stesso stato) e il testo detto e' quello del cambio.
  // Gli stati tranquilli non sono una notizia e non si annunciano.
  val kind = title.replace(DigitsRegex, "#")
  val spoken = remember(kind) { title }
  Box(modifier = modifier.fillMaxWidth()) {
    if (tone != BackupTone.Calm) GalleryLiveAnnouncement(spoken)
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .clip(ContinuousCornerShape(FluidRadius.Card))
        .background(style.container)
        .padding(BackupDimens.CardPadding),
      horizontalArrangement = Arrangement.spacedBy(BackupDimens.BlockGap),
    ) {
      // IN-7: una scheda puo' avere al posto del glifo un pezzo suo (la miniatura di un file ricevuto).
      if (leading != null) {
        leading()
      } else if (busy) {
        FluidSpinner(size = BackupDimens.CardGlyph, color = style.glyph)
      } else {
        Icon(imageVector = glyph, contentDescription = null, tint = style.glyph, modifier = Modifier.size(BackupDimens.CardGlyph))
      }
      Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(BackupDimens.CardGap)) {
        Column(
          // Il titolo della scheda e' un'intestazione (Fase 10 P8, D23 (2)): titolo e dettaglio si leggono insieme.
          modifier = Modifier.semantics(mergeDescendants = true) { heading() },
          verticalArrangement = Arrangement.spacedBy(BackupDimens.LineGap),
        ) {
          Text(text = title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = style.text)
          if (detail != null) Text(text = detail, style = MaterialTheme.typography.bodyMedium, color = style.text)
          // Un'azione che e' solo una frase ("Chiedi all'admin del NAS"): niente da toccare, ma e' parte di cio' che la scheda dice.
          if (hint != null) Text(text = hint, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = style.text)
        }
        if (progress != null) {
          FluidProgressBar(progress = { progress }, color = style.text, height = BackupDimens.BarHeight)
        }
        if (actions != null) Column(verticalArrangement = Arrangement.spacedBy(BackupDimens.LineGap), content = actions)
      }
    }
  }
}

private val DigitsRegex = Regex("[0-9.,]+")

/** Un tasto di una scheda o di un foglio: 50 dp (`Large`), sopra i 48 del bersaglio di tocco. */
@Composable
internal fun BackupButton(
  label: String,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  style: FluidButtonStyle = FluidButtonStyle.Filled,
  enabled: Boolean = true,
  fillWidth: Boolean = true,
) {
  FluidButton(text = label, onClick = onClick, modifier = modifier, style = style, size = FluidButtonSize.Large, enabled = enabled, fillWidth = fillWidth)
}

/** Un'intestazione di gruppo quieta, nella lingua delle altre pagine (la didascalia sopra un gruppo). */
@Composable
internal fun BackupSectionTitle(text: String, modifier: Modifier = Modifier) {
  Text(
    text = text,
    modifier = modifier
      .fillMaxWidth()
      .padding(horizontal = BackupDimens.BlockGap)
      .semantics { heading() },
    style = MaterialTheme.typography.labelLarge,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
  )
}

/** Una nota in fondo a un gruppo: piccola, nel secondario. */
@Composable
internal fun BackupNote(text: String, modifier: Modifier = Modifier) {
  Text(
    text = text,
    modifier = modifier.padding(horizontal = BackupDimens.BlockGap),
    style = MaterialTheme.typography.bodySmall,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
  )
}

/**
 * I3 (fase I): una spiegazione dietro un tocco. In pagina c'e' solo la «i» con un'etichetta corta ([label]); il tocco apre la frase sotto, nello stesso
 * posto, e la richiude. Giro 1 (CS-5): la «i» e la frase sono quelle di core-designsystem ([GalleryInfoGlyph], [GalleryInfoDetail]), come nelle
 * Impostazioni e nel pop-up; la riga e' un tasto da 48 dp con il suo stato, e TalkBack sente la frase nella sua descrizione, senza tocco.
 */
@Composable
internal fun BackupInfoToggle(label: String, text: String, modifier: Modifier = Modifier) {
  var open by rememberSaveable(text) { mutableStateOf(false) }
  val openState = stringResource(R.string.backup_hero_detail_open)
  val closedState = stringResource(R.string.backup_hero_detail_closed)
  Column(modifier = modifier.padding(horizontal = BackupDimens.BlockGap), verticalArrangement = Arrangement.spacedBy(BackupDimens.LineGap)) {
    Row(
      modifier = Modifier
        .defaultMinSize(minHeight = GalleryDimens.MinTouchTarget)
        .fluidPressable(onClick = { open = !open }, role = Role.Button)
        .semantics(mergeDescendants = true) {
          role = Role.Button
          contentDescription = "$label. $text"
          stateDescription = if (open) openState else closedState
        },
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(BackupDimens.LineGap),
    ) {
      GalleryInfoGlyph(open = open, tint = MaterialTheme.colorScheme.primary)
      Text(text = label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
    }
    GalleryInfoDetail(text = text, visible = open, color = MaterialTheme.colorScheme.onSurfaceVariant)
  }
}
