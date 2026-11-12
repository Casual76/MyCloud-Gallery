package dev.pampa.mycloudgallery.feature.map.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import dev.antigravity.fluidengine.ui.fluid.FluidCapsuleShape
import dev.antigravity.fluidengine.ui.fluid.fluidPressable
import androidx.compose.foundation.layout.fillMaxWidth
import dev.pampa.mycloudgallery.core.designsystem.state.GalleryFigureState
import dev.pampa.mycloudgallery.core.designsystem.state.GalleryIllustrationTone

// Le PARTI COMUNI delle schermate della mappa (Fase 9, P4): i controlli sopra la carta e le righe discrete. **Superfici piene**, mai vetro sopra la mappa (un vetro sopra una
// carta che cambia a ogni gesto e' poco leggibile) e mai due controlli uno sopra l'altro; il testo non sta mai sopra una foto o sopra la carta senza una superficie sotto.

/** Un tasto tondo sopra la mappa: l'icona su una superficie piena, il bersaglio da 48 dp tutto cliccabile, il nome per TalkBack. */
@Composable
internal fun MapControlButton(icon: ImageVector, contentDescription: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
  Box(
    modifier = modifier
      .size(MapDimens.Touch)
      .semantics { this.contentDescription = contentDescription }
      .fluidPressable(onClick = onClick, role = Role.Button, focusShape = FluidCapsuleShape)
      .clip(FluidCapsuleShape)
      .background(MaterialTheme.colorScheme.surfaceContainerHigh),
    contentAlignment = Alignment.Center,
  ) {
    Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(MapDimens.Glyph), tint = MaterialTheme.colorScheme.onSurface)
  }
}

/** Una capsula di testo su superficie piena: il titolo della mappa, il credito, l'avviso. Se [onClick] c'e', tutta la capsula si tocca (almeno 48 dp). */
@Composable
internal fun MapChip(text: String, modifier: Modifier = Modifier, icon: ImageVector? = null, onClick: (() -> Unit)? = null, maxLines: Int = 2) {
  val tap = if (onClick != null) Modifier.defaultMinSize(minHeight = MapDimens.Touch).fluidPressable(onClick = onClick, role = Role.Button) else Modifier
  Row(
    modifier = modifier
      .then(tap)
      .clip(FluidCapsuleShape)
      .background(MaterialTheme.colorScheme.surfaceContainerHigh)
      .padding(horizontal = MapDimens.Inset, vertical = MapDimens.TightGap),
    horizontalArrangement = Arrangement.spacedBy(MapDimens.TightGap),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    if (icon != null) Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(MapDimens.Glyph), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    Text(
      text = text,
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurface,
      maxLines = maxLines,
      overflow = TextOverflow.Ellipsis,
    )
  }
}

/** Il credito della carta (L430): una scritta piccola su una superficie piena, **mai un collegamento** (il pulsante di MapLibre apre il browser e si spegne). */
@Composable
internal fun MapCredit(text: String, modifier: Modifier = Modifier) {
  Text(
    text = text,
    modifier = modifier
      .clip(MaterialTheme.shapes.small)
      .background(MaterialTheme.colorScheme.surfaceContainerHigh)
      .padding(horizontal = MapDimens.TightGap, vertical = MapDimens.TightGap / 2),
    style = MaterialTheme.typography.labelSmall,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    maxLines = 2,
    overflow = TextOverflow.Ellipsis,
  )
}

/**
 * Fase I, giro di correzione 2 (CS-17): uno stato vuoto o di errore della Mappa nella forma unica della galleria ([GalleryFigureState]): la figura,
 * il titolo, al piu' i tasti ([action]). Il perche' ([why]), dove spiega una regola o rassicura (le foto non sono perse, nascoste o sparite, la carta
 * che resta sul telefono), e' il `why` della forma comune: la «i» sotto il titolo (giro 3: niente piu' copia della «i» qui).
 */
@Composable
internal fun MapFigureState(
  icon: ImageVector,
  title: String,
  modifier: Modifier = Modifier,
  why: String? = null,
  tone: GalleryIllustrationTone = GalleryIllustrationTone.Brand,
  action: (@Composable () -> Unit)? = null,
) {
  GalleryFigureState(
    icon = icon,
    title = title,
    tone = tone,
    fill = false,
    modifier = modifier.fillMaxWidth(),
    why = why,
    action = action,
  )
}
