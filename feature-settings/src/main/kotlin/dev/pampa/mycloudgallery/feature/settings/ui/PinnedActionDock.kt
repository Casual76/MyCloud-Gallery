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
 * Il contenuto scorre sopra una barra che non si muove: l'azione principale e' sempre raggiungibile, anche con il testo grande, su un telefono basso e con la tastiera aperta. Il segreto e'
 * `imePadding` sul contenitore: la tastiera spinge in su la pagina intera, barra compresa (con `enableEdgeToEdge` la finestra non si ridimensiona da sola).
 *
 * **La lista finisce dove comincia la barra**, non sotto di lei: l'altezza della barra (misurata) e' tolta in fondo alla finestra della pagina. Cosi' un campo che prende il fuoco
 * viene portato in vista dentro la parte che si vede, e non finisce nascosto dietro il tasto (una lista che si estende sotto la barra porterebbe il campo "in vista" proprio li' sotto).
 * Solo la sfumatura in cima alla barra ([SettingsDimens.DockFade]) si sovrappone alla lista, per non tagliarla di netto. Su uno schermo largo la barra segue la colonna di lettura.
 *
 * E' lo stesso disegno della barra dei passi dell'onboarding (`StepScaffold`), che sta nel suo modulo e porta invece il contenuto sotto la barra.
 *
 * @param page la pagina: riceve il modificatore da dare alla sua `FluidScreen`, che la fa finire sopra la barra.
 * @param actions i tasti della barra (di solito uno, `fillWidth`); se e' `null` non c'e' barra (la pagina non ha niente da confermare) e la pagina occupa tutto.
 */
@Composable
internal fun PinnedActionDock(
  modifier: Modifier = Modifier,
  actions: (@Composable () -> Unit)?,
  page: @Composable (Modifier) -> Unit,
) {
  val density = LocalDensity.current
  var dockPx by remember { mutableIntStateOf(0) }
  val fade = with(density) { SettingsDimens.DockFade.toPx() }
  // La finestra della pagina finisce dove comincia la parte piena della barra: l'altezza della barra meno la sfumatura, che invece le sta sopra.
  val pageBottom: Dp = if (actions == null) NoDock else with(density) { (dockPx - fade).coerceAtLeast(0f).toDp() }
  Box(modifier = modifier.fillMaxSize().imePadding()) {
    page(Modifier.padding(bottom = pageBottom))
    if (actions != null) {
      val background = MaterialTheme.colorScheme.background
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

/** Senza barra, nessun margine in fondo alla pagina (`Dp(0f)`, non un `0.dp`: le misure di questo modulo stanno in [SettingsDimens]). */
private val NoDock = Dp(0f)
