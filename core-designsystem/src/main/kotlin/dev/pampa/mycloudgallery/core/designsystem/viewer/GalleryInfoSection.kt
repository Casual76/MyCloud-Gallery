package dev.pampa.mycloudgallery.core.designsystem.viewer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.antigravity.fluidengine.ui.fluid.FluidCapsuleShape
import dev.antigravity.fluidengine.ui.fluid.FluidSectionHeader
import dev.antigravity.fluidengine.ui.fluid.fluidPressable
import dev.antigravity.fluidengine.ui.theme.FluidListDivider
import dev.antigravity.fluidengine.ui.theme.FluidListGroup
import dev.antigravity.fluidengine.ui.theme.FluidListRow
import dev.antigravity.fluidengine.ui.theme.FluidStatusBadge
import dev.antigravity.fluidengine.ui.theme.FluidTone
import dev.pampa.mycloudgallery.core.designsystem.R
import dev.pampa.mycloudgallery.core.designsystem.theme.GalleryDimens

/**
 * Una riga del pannello informazioni: il valore come titolo ("12 marzo 2024, 10:15"), la didascalia sotto ("ora di Oslo,
 * UTC+1"), un'icona e, se serve, una pillola di stato ("Stimata") o un'azione ([onClick], "Apri in Mappe").
 *
 * Le righe di una sezione vanno in un gruppo solo (regola 2 di Fluid): il tono sta sulla piastrella dell'icona, mai
 * sullo sfondo della riga.
 */
@Immutable
data class GalleryInfoRow(
  val title: String,
  val caption: String = "",
  val icon: ImageVector? = null,
  val tone: FluidTone = FluidTone.Neutral,
  val pill: String? = null,
  val pillTone: FluidTone = FluidTone.Warning,
  val onClick: (() -> Unit)? = null,
)

/**
 * Una sezione del pannello informazioni: un'intestazione discreta e un gruppo di righe. Il pannello (foglio a meta'
 * sul telefono, colonna laterale sul tablet) e' una pila di queste sezioni: data e ora, luogo, fotocamera, file,
 * posizione sul telefono. Persone, album e versioni compaiono solo quando esistono (Fasi 5, 7 e 8): una sezione
 * senza righe non si disegna.
 */
@Composable
fun GalleryInfoSection(
  rows: List<GalleryInfoRow>,
  modifier: Modifier = Modifier,
  header: String? = null,
) {
  if (rows.isEmpty()) return
  Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(SectionGap)) {
    if (header != null) FluidSectionHeader(title = header)
    FluidListGroup {
      rows.forEachIndexed { index, row ->
        if (index > 0) FluidListDivider()
        FluidListRow(
          title = row.title,
          subtitle = row.caption,
          tone = row.tone,
          leading = row.icon?.let { icon -> { Icon(imageVector = icon, contentDescription = null) } },
          labels = row.pill?.let { pill -> { FluidStatusBadge(label = pill, tone = row.pillTone) } },
          onClick = row.onClick,
          disclosure = row.onClick != null,
        )
      }
    }
  }
}

/**
 * L'intestazione del pannello informazioni: la maniglia (si trascina per chiudere o aprire il foglio), il titolo e il
 * tasto per chiudere da 48 dp. [showGrabber] e' falso nel pannello laterale del tablet, che non si trascina.
 */
@Composable
fun GalleryInfoHeader(
  title: String,
  onClose: () -> Unit,
  modifier: Modifier = Modifier,
  showGrabber: Boolean = true,
) {
  val scheme = MaterialTheme.colorScheme
  Column(modifier = modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
    if (showGrabber) {
      Box(
        modifier = Modifier
          .padding(top = GrabberTop, bottom = GrabberBottom)
          .size(width = GrabberWidth, height = GrabberHeight)
          .background(scheme.onSurfaceVariant.copy(alpha = GrabberAlpha), FluidCapsuleShape),
      )
    }
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(start = GalleryDimens.Gutter, end = HeaderEndInset),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(
        text = title,
        modifier = Modifier.weight(1f),
        style = MaterialTheme.typography.titleLarge,
        color = scheme.onSurface,
        maxLines = 1,
      )
      val closeDescription = stringResource(R.string.info_close)
      Box(
        modifier = Modifier
          .size(GalleryDimens.MinTouchTarget)
          .semantics { contentDescription = closeDescription }
          .fluidPressable(onClick = onClose, role = Role.Button, focusShape = FluidCapsuleShape),
        contentAlignment = Alignment.Center,
      ) {
        Icon(
          imageVector = Icons.Rounded.Close,
          contentDescription = null,
          modifier = Modifier.size(CloseGlyph),
          tint = scheme.onSurfaceVariant,
        )
      }
    }
  }
}

private val SectionGap = 8.dp
private val GrabberTop = 8.dp
private val GrabberBottom = 4.dp
private val GrabberWidth = 36.dp
private val GrabberHeight = 5.dp
private val HeaderEndInset = 8.dp
private val CloseGlyph = 22.dp
private const val GrabberAlpha = 0.4f
