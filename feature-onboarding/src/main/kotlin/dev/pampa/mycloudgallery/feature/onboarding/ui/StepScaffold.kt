package dev.pampa.mycloudgallery.feature.onboarding.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import dev.antigravity.fluidengine.ui.fluid.FluidAmbient
import dev.antigravity.fluidengine.ui.fluid.FluidButton
import dev.antigravity.fluidengine.ui.fluid.FluidButtonSize
import dev.antigravity.fluidengine.ui.fluid.FluidButtonStyle
import dev.antigravity.fluidengine.ui.fluid.FluidHeroMotif
import dev.antigravity.fluidengine.ui.fluid.FluidHeroTone
import dev.antigravity.fluidengine.ui.fluid.FluidScreen
import dev.antigravity.fluidengine.ui.fluid.fluidReadingWidth
import dev.pampa.mycloudgallery.core.family.contract.OnboardingStep
import dev.pampa.mycloudgallery.core.family.contract.OnboardingWarning
import dev.pampa.mycloudgallery.feature.onboarding.model.OnboardingBanner

/** Un bottone della barra in fondo. */
@Immutable
internal data class DockAction(
  val label: String,
  val onClick: () -> Unit,
  val enabled: Boolean = true,
  val loading: Boolean = false,
  val style: FluidButtonStyle = FluidButtonStyle.Filled,
)

/** "Passo 3 di 14": il numero del passo di adesso e quanti sono nel percorso. L'etichetta la compone la pagina (e' una risorsa). */
@Immutable
internal data class StepProgressInfo(val step: Int, val total: Int) {
  val fraction: Float get() = if (total <= 0) 0f else (step.toFloat() / total).coerceIn(0f, 1f)
}

/**
 * Cio' che sta intorno a un passo e non e' del passo: dov'e' l'utente (la barra), come torna indietro, la
 * conferma del passo appena riuscito e gli avvisi non bloccanti arrivati da prima.
 */
@Immutable
internal data class StepChrome(
  val progress: StepProgressInfo? = null,
  val onBack: (() -> Unit)? = null,
  val banner: OnboardingBanner? = null,
  val onDismissBanner: () -> Unit = {},
  val warnings: List<OnboardingWarning> = emptyList(),
  val onDismissWarning: (OnboardingWarning) -> Unit = {},
)

/** Il fondale di ogni passo: un tono e un motivo, scelti dal tema del passo (sempre dalla palette). */
internal fun stepAmbient(step: OnboardingStep?, urgent: Boolean = false): FluidAmbient {
  val (tone, motif) = when (step) {
    null, OnboardingStep.WELCOME -> FluidHeroTone.Primary to FluidHeroMotif.Glow
    OnboardingStep.NAS_ADDRESS -> FluidHeroTone.PrimaryToSecondary to FluidHeroMotif.Ripples
    OnboardingStep.ACCOUNT -> FluidHeroTone.Primary to FluidHeroMotif.Dots
    OnboardingStep.AREAS -> FluidHeroTone.Secondary to FluidHeroMotif.Cards
    OnboardingStep.FOLDERS -> FluidHeroTone.SecondaryToTertiary to FluidHeroMotif.Bars
    OnboardingStep.BACKUP_SOURCES -> FluidHeroTone.Secondary to FluidHeroMotif.Ticks
    OnboardingStep.ROLES -> FluidHeroTone.TertiaryToPrimary to FluidHeroMotif.Figures
    OnboardingStep.PRESET -> FluidHeroTone.PrimaryToSecondary to FluidHeroMotif.Bars
    OnboardingStep.PERMISSIONS -> FluidHeroTone.Secondary to FluidHeroMotif.Dots
    OnboardingStep.BATTERY -> FluidHeroTone.Tertiary to FluidHeroMotif.Ticks
    OnboardingStep.VPN -> FluidHeroTone.SecondaryToTertiary to FluidHeroMotif.Ripples
    OnboardingStep.FAMILY_KEY -> FluidHeroTone.Tertiary to FluidHeroMotif.Glow
    OnboardingStep.SECURITY_NOTICE -> FluidHeroTone.Alert to FluidHeroMotif.Glow
    OnboardingStep.SUMMARY -> FluidHeroTone.Primary to FluidHeroMotif.Glow
  }
  return FluidAmbient(tone = tone, motif = motif, intensity = AmbientIntensity, urgent = urgent)
}

/**
 * L'ossatura di ogni passo: una [FluidScreen] con il titolo grande, il fondale del passo, il contenuto, e in
 * fondo la barra dei tasti.
 *
 * La barra sta ferma e il contenuto le scorre sotto, con una sfumatura sul fondo della pagina (mai un colore
 * a parte): l'azione principale e' sempre in vista, anche con il testo grande o con la tastiera aperta
 * (`imePadding`). L'altezza della barra e' misurata e restituita alla lista come margine in fondo, cosi' niente
 * resta nascosto sotto i bottoni. Su uno schermo largo la barra segue la colonna di lettura.
 */
@Composable
internal fun StepScaffold(
  title: String,
  modifier: Modifier = Modifier,
  subtitle: String? = null,
  chrome: StepChrome = StepChrome(),
  ambient: FluidAmbient? = null,
  primary: DockAction? = null,
  secondary: DockAction? = null,
  content: LazyListScope.() -> Unit,
) {
  val density = LocalDensity.current
  var dockPx by remember { mutableIntStateOf(0) }
  val dockHeight: Dp = with(density) { dockPx.toDp() }
  Box(modifier = modifier.fillMaxSize().imePadding()) {
    FluidScreen(
      title = title,
      subtitle = subtitle,
      onBack = chrome.onBack,
      ambient = ambient,
      extraBottomPadding = dockHeight,
    ) {
      chromeItems(chrome)
      content()
      warningItems(chrome)
    }
    if (primary != null || secondary != null) {
      StepDock(
        primary = primary,
        secondary = secondary,
        modifier = Modifier
          .align(Alignment.BottomCenter)
          .onSizeChanged { dockPx = it.height },
      )
    }
  }
}

/** La barra dei tasti in fondo: il principale pieno e largo, l'eventuale secondo come testo dell'accento. */
@Composable
private fun StepDock(primary: DockAction?, secondary: DockAction?, modifier: Modifier = Modifier) {
  val background = MaterialTheme.colorScheme.background
  val fade = with(LocalDensity.current) { OnboardingDimens.DockFade.toPx() }
  Box(
    modifier = modifier
      .fillMaxWidth()
      .drawBehind {
        drawRect(
          brush = Brush.verticalGradient(colors = listOf(Color.Transparent, background), startY = 0f, endY = fade),
          size = Size(size.width, fade),
        )
        drawRect(color = background, topLeft = Offset(0f, fade), size = Size(size.width, size.height - fade))
      },
  ) {
    Column(
      modifier = Modifier
        .fluidReadingWidth()
        .padding(horizontal = OnboardingDimens.Gutter)
        .padding(top = OnboardingDimens.DockFade)
        .navigationBarsPadding()
        .padding(bottom = OnboardingDimens.DockPadding),
      verticalArrangement = Arrangement.spacedBy(OnboardingDimens.DockGap),
    ) {
      if (primary != null) DockButton(primary)
      if (secondary != null) DockButton(secondary)
    }
  }
}

@Composable
private fun DockButton(action: DockAction) {
  FluidButton(
    text = action.label,
    onClick = action.onClick,
    style = action.style,
    size = FluidButtonSize.Large,
    enabled = action.enabled,
    loading = action.loading,
    fillWidth = true,
  )
}

private const val AmbientIntensity = 0.85f
