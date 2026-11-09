package dev.pampa.mycloudgallery.feature.settings.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import dev.antigravity.fluidengine.ui.fluid.FluidScreenDefaults
import dev.antigravity.fluidengine.ui.fluid.fluidReadingWidth

/**
 * Una pagina con i tasti **fermi in fondo** (grafica beta, G3, B6: "Approva" stava sotto la piega e la tastiera lo copriva).
 *
 * Il contenuto scorre sotto una barra che non si muove: l'azione principale e' sempre raggiungibile, anche con il testo grande, su un telefono basso e con la tastiera aperta. Il segreto e'
 * `imePadding` sul contenitore: la tastiera spinge in su la pagina intera, barra compresa (con `enableEdgeToEdge` la finestra non si ridimensiona da sola). L'altezza della barra e'
 * misurata e restituita alla lista come margine in fondo ([FluidScreenDefaults] `extraBottomPadding`, dentro [page]), cosi' l'ultima riga non resta nascosta sotto il tasto. Su uno
 * schermo largo la barra segue la colonna di lettura. Sul fondo della pagina, una sfumatura (mai un colore a parte).
 *
 * E' lo stesso disegno della barra dei passi dell'onboarding (`StepScaffold`): li' sta nel suo modulo, qui nel modulo delle Impostazioni.
 *
 * @param page la pagina: riceve l'altezza della barra da dare come `extraBottomPadding` della sua `FluidScreen`.
 * @param actions i tasti della barra (di solito uno, `fillWidth`); se e' `null` non c'e' barra (la pagina non ha niente da confermare) e il margine e' zero.
 */
@Composable
internal fun PinnedActionDock(
  modifier: Modifier = Modifier,
  actions: (@Composable () -> Unit)?,
  page: @Composable (bottomPadding: Dp) -> Unit,
) {
  val density = LocalDensity.current
  var dockPx by remember { mutableIntStateOf(0) }
  val dockHeight: Dp = if (actions == null) NoDock else with(density) { dockPx.toDp() }
  Box(modifier = modifier.fillMaxSize().imePadding()) {
    page(dockHeight)
    if (actions != null) {
      val background = MaterialTheme.colorScheme.background
      val fade = with(density) { SettingsDimens.DockFade.toPx() }
      Box(
        modifier = Modifier
          .align(Alignment.BottomCenter)
          .fillMaxWidth()
          .onSizeChanged { dockPx = it.height }
          .drawBehind {
            drawRect(
              brush = Brush.verticalGradient(colors = listOf(background.copy(alpha = 0f), background), startY = 0f, endY = fade),
              size = Size(size.width, fade),
            )
            drawRect(color = background, topLeft = Offset(0f, fade), size = Size(size.width, size.height - fade))
          },
      ) {
        Column(
          modifier = Modifier
            .fluidReadingWidth()
            .padding(horizontal = FluidScreenDefaults.HorizontalPadding)
            .padding(top = SettingsDimens.DockFade)
            .navigationBarsPadding()
            .padding(bottom = SettingsDimens.DockPadding),
        ) {
          actions()
        }
      }
    }
  }
}

/** Senza barra, nessun margine in fondo alla lista (`Dp(0f)`, non un `0.dp`: le misure di questo modulo stanno in [SettingsDimens]). */
private val NoDock = Dp(0f)
