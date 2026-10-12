package dev.pampa.mycloudgallery.status

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import dev.antigravity.fluidengine.ui.fluid.FluidCapsuleShape
import dev.antigravity.fluidengine.ui.fluid.fluidPressable
import dev.antigravity.fluidengine.ui.theme.FluidStatusBadge
import dev.pampa.mycloudgallery.core.designsystem.theme.GalleryDimens

/**
 * L'indicatore di stato globale in cima alla Libreria (e alle altre schede): la pillola di stato dell'engine
 * (`FluidStatusBadge`, tono dai ruoli del tema) dentro un bersaglio di tocco di 48 dp. Toccandolo si aprono
 * i dettagli (o WireGuard, o la password: [GlobalStatus.action]).
 *
 * E' lo stato della RETE e del NAS; `GalleryStatusIndicator` del design system (backup, coda, carica)
 * resta per lo stato del lavoro, che arriva con le fasi del backup.
 */
@Composable
fun GlobalStatusIndicator(
  status: GlobalStatus,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val label = stringResource(status.label)
  Box(
    modifier = modifier
      .defaultMinSize(minWidth = GalleryDimens.MinTouchTarget, minHeight = GalleryDimens.MinTouchTarget)
      .semantics(mergeDescendants = true) { contentDescription = label }
      .fluidPressable(onClick = onClick, role = Role.Button, focusShape = FluidCapsuleShape),
    contentAlignment = Alignment.CenterStart,
  ) {
    FluidStatusBadge(label = label, tone = status.tone)
  }
}
