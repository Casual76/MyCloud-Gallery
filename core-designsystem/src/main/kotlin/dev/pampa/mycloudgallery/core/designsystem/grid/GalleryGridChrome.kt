package dev.pampa.mycloudgallery.core.designsystem.grid

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.antigravity.fluidengine.ui.fluid.ContinuousCornerShape
import dev.antigravity.fluidengine.ui.fluid.FluidButton
import dev.antigravity.fluidengine.ui.fluid.FluidButtonSize
import dev.antigravity.fluidengine.ui.fluid.FluidButtonStyle
import dev.antigravity.fluidengine.ui.fluid.FluidCapsuleShape
import dev.antigravity.fluidengine.ui.fluid.FluidRadius
import dev.antigravity.fluidengine.ui.fluid.FluidSegmentedControl
import dev.antigravity.fluidengine.ui.fluid.GlassBackdropState
import dev.antigravity.fluidengine.ui.fluid.GlassDefaults
import dev.antigravity.fluidengine.ui.fluid.GlassRole
import dev.antigravity.fluidengine.ui.fluid.fluidPressable
import dev.pampa.mycloudgallery.core.designsystem.a11y.galleryGlass
import dev.pampa.mycloudgallery.core.designsystem.theme.GalleryDimens

// La chrome della griglia: i pezzi di vetro che galleggiano sopra le foto della Libreria. Pochi e piccoli, perche' ogni
// lastra di vetro sopra un contenuto che scorre si ricattura a ogni fotogramma (regola del vetro: sul contenitore, mai
// sulla riga).

/**
 * I **segmenti dei livelli** (§2.4): Anni · Mesi · Giorni · Tutte, in una capsula di vetro che galleggia sopra la
 * griglia. E' l'alternativa al pinch per chi non puo' pizzicare (accessibilita') e la via piu' rapida per saltare
 * piu' livelli; dentro c'e' il controllo a segmenti dell'engine (la lente che scivola sul segmento scelto).
 */
@Composable
fun GalleryGridLevelBar(
  labels: List<String>,
  selected: Int,
  onSelect: (Int) -> Unit,
  backdrop: GlassBackdropState,
  modifier: Modifier = Modifier,
) {
  Box(
    modifier = modifier
      .widthIn(max = LevelBarMaxWidth)
      .fillMaxWidth()
      .galleryGlass(state = backdrop, tint = GlassDefaults.floatingTint(), shape = FluidCapsuleShape, role = GlassRole.Floating)
      .padding(LevelBarPadding),
  ) {
    FluidSegmentedControl(
      options = labels.indices.toList(),
      selected = selected.coerceIn(0, labels.lastIndex),
      onSelect = onSelect,
      label = { labels[it] },
    )
  }
}

/**
 * Un **avviso** sopra la griglia (accesso parziale E-P1, foto in attesa E-P21): una lastra di vetro compatta con l'icona
 * in una piastrella del tono, il titolo, una frase breve e, sulla stessa riga, l'azione che risolve. Mai un muro: la
 * griglia resta sotto e si scorre; due avvisi insieme occupano meno di un terzo dello schermo.
 */
@Composable
fun GalleryGridNotice(
  icon: ImageVector,
  title: String,
  message: String,
  backdrop: GlassBackdropState,
  modifier: Modifier = Modifier,
  actionLabel: String? = null,
  onAction: (() -> Unit)? = null,
) {
  val scheme = MaterialTheme.colorScheme
  val shape = ContinuousCornerShape(FluidRadius.Card)
  Row(
    modifier = modifier
      .widthIn(max = NoticeMaxWidth)
      .fillMaxWidth()
      .galleryGlass(state = backdrop, tint = GlassDefaults.floatingTint(), shape = shape, role = GlassRole.Floating)
      .padding(NoticePadding),
    horizontalArrangement = Arrangement.spacedBy(NoticeGap),
    verticalAlignment = Alignment.Top,
  ) {
    Box(
      modifier = Modifier
        .size(NoticeTile)
        .background(scheme.primaryContainer, ContinuousCornerShape(FluidRadius.Small)),
      contentAlignment = Alignment.Center,
    ) {
      Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(NoticeGlyph), tint = scheme.onPrimaryContainer)
    }
    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(NoticeTextGap)) {
      Text(text = title, style = MaterialTheme.typography.titleSmall, color = scheme.onSurface)
      Text(
        text = message,
        style = MaterialTheme.typography.bodySmall,
        color = scheme.onSurfaceVariant,
        maxLines = NoticeMessageLines,
        overflow = TextOverflow.Ellipsis,
      )
      if (actionLabel != null && onAction != null) {
        // L'azione sotto la frase, allineata al testo: la riga resta tutta per le parole.
        Box(modifier = Modifier.padding(top = NoticeActionGap).defaultMinSize(minHeight = GalleryDimens.MinTouchTarget), contentAlignment = Alignment.CenterStart) {
          FluidButton(text = actionLabel, onClick = onAction, style = FluidButtonStyle.Tinted, size = FluidButtonSize.Medium)
        }
      }
    }
  }
}

/**
 * Il tasto **"Oggi"** (§2.4, "torna a oggi"): una capsula di vetro che compare quando si e' lontani dai piu' recenti e
 * riporta in fondo. Bersaglio di 48 dp, cresce sotto il dito come ogni tasto (`fluidPressable` prima del vetro).
 */
@Composable
fun GalleryGridJumpButton(
  label: String,
  icon: ImageVector,
  onClick: () -> Unit,
  backdrop: GlassBackdropState,
  modifier: Modifier = Modifier,
  contentDescription: String = label,
) {
  Box(
    modifier = modifier
      .defaultMinSize(minHeight = GalleryDimens.MinTouchTarget)
      .semantics { this.contentDescription = contentDescription }
      .fluidPressable(onClick = onClick, role = Role.Button, focusShape = FluidCapsuleShape),
    contentAlignment = Alignment.Center,
  ) {
    Row(
      modifier = Modifier
        .height(JumpHeight)
        .galleryGlass(state = backdrop, tint = GlassDefaults.floatingTint(), shape = FluidCapsuleShape, role = GlassRole.Floating)
        .padding(horizontal = JumpPadding),
      horizontalArrangement = Arrangement.spacedBy(JumpGap),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(JumpGlyph), tint = MaterialTheme.colorScheme.primary)
      Text(
        text = label,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    }
  }
}

/** La larghezza massima della capsula dei livelli: su un tablet non attraversa lo schermo. */
val LevelBarMaxWidth: Dp = 360.dp

private val LevelBarPadding = 6.dp
private val NoticeMaxWidth = 560.dp
private val NoticePadding = 12.dp
private val NoticeGap = 12.dp
private val NoticeTextGap = 2.dp
private val NoticeActionGap = 4.dp
private val NoticeTile = 36.dp
private val NoticeGlyph = 20.dp
private const val NoticeMessageLines = 3
private val JumpHeight = 40.dp
private val JumpPadding = 14.dp
private val JumpGap = 6.dp
private val JumpGlyph = 18.dp
