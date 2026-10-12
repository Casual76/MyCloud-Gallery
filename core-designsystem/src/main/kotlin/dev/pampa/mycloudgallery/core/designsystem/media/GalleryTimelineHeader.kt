package dev.pampa.mycloudgallery.core.designsystem.media

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.antigravity.fluidengine.ui.fluid.GlassBackdropState
import dev.antigravity.fluidengine.ui.fluid.GlassDefaults
import dev.antigravity.fluidengine.ui.fluid.GlassEdge
import dev.antigravity.fluidengine.ui.fluid.GlassRole
import dev.antigravity.fluidengine.ui.fluid.fluidPressable
import dev.antigravity.fluidengine.ui.fluid.glassSurface
import dev.pampa.mycloudgallery.core.designsystem.theme.GalleryDimens

/** A che livello della timeline sta l'intestazione: decide quanto e' grande il titolo. */
enum class GalleryTimelineLevel { Year, Month, Day, All }

/**
 * L'intestazione di un gruppo della timeline: la data, e sotto il luogo.
 *
 * "Sticky-ready" vuol dire due cose, e nessuna delle due la fa questo componente da solo:
 *  - ha **un'altezza minima stabile** (48 dp, il bersaglio di tocco), cosi' un `stickyHeader` che la
 *    porta non fa saltare la lista quando si aggancia e cambia il suo sfondo;
 *  - ha uno stato [stuck]: quando e' agganciata al bordo, con le foto che le scorrono sotto, smette di
 *    essere trasparente. Con un [backdrop] diventa una fascia di vetro (la regola: il vetro sta sulle
 *    barre); senza, una fascia piena del colore della pagina.
 *
 * Il livello cambia la scala del titolo con gli stili dell'engine: un anno e' un titolo, un giorno
 * una riga. [trailing] e' lo spazio per un'azione ("Seleziona" in modalita' selezione): chi lo riempie
 * rispetta i 48 dp del bersaglio di tocco.
 */
@Composable
fun GalleryTimelineHeader(
  title: String,
  modifier: Modifier = Modifier,
  place: String? = null,
  level: GalleryTimelineLevel = GalleryTimelineLevel.Day,
  stuck: Boolean = false,
  backdrop: GlassBackdropState? = null,
  trailing: (@Composable () -> Unit)? = null,
) {
  val scheme = MaterialTheme.colorScheme
  val background: Modifier = when {
    !stuck -> Modifier
    backdrop != null -> Modifier.glassSurface(
      state = backdrop,
      tint = GlassDefaults.barTint(),
      edge = GlassEdge.Bottom,
      role = GlassRole.Bar,
    )
    else -> Modifier.background(scheme.background)
  }
  Row(
    modifier = modifier
      .fillMaxWidth()
      .then(background)
      .defaultMinSize(minHeight = GalleryDimens.MinTouchTarget)
      .padding(horizontal = GalleryDimens.Gutter, vertical = HeaderVerticalPadding)
      .semantics(mergeDescendants = true) { heading() },
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(HeaderGap),
  ) {
    Column(modifier = Modifier.weight(1f)) {
      Text(
        text = title,
        style = level.titleStyle(),
        color = scheme.onSurface,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      if (!place.isNullOrBlank()) {
        Text(
          text = place,
          style = MaterialTheme.typography.bodyMedium,
          color = scheme.onSurfaceVariant,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
    }
    if (trailing != null) Box(contentAlignment = Alignment.Center) { trailing() }
  }
}

/**
 * L'azione di testo in fondo a un'intestazione ("Seleziona"): alta 48 dp, col testo dell'accento. Per
 * chi non ha altro da mettere in [GalleryTimelineHeader.trailing].
 */
@Composable
fun GalleryTimelineHeaderAction(
  label: String,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Box(
    modifier = modifier
      .defaultMinSize(minWidth = GalleryDimens.MinTouchTarget, minHeight = GalleryDimens.MinTouchTarget)
      .fluidPressable(onClick = onClick, role = Role.Button),
    contentAlignment = Alignment.Center,
  ) {
    Text(
      text = label,
      style = MaterialTheme.typography.labelLarge,
      color = MaterialTheme.colorScheme.primary,
      maxLines = 1,
    )
  }
}

/** Lo stile del titolo di un livello: la scala dell'engine, da "titolo" (anno) a "riga" (tutte). */
@Composable
private fun GalleryTimelineLevel.titleStyle(): TextStyle = when (this) {
  GalleryTimelineLevel.Year -> MaterialTheme.typography.headlineSmall
  GalleryTimelineLevel.Month -> MaterialTheme.typography.titleLarge
  GalleryTimelineLevel.Day -> MaterialTheme.typography.titleMedium
  GalleryTimelineLevel.All -> MaterialTheme.typography.titleSmall
}

private val HeaderVerticalPadding = 8.dp
private val HeaderGap = 12.dp
