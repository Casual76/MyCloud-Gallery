package dev.pampa.mycloudgallery.core.designsystem.a11y

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import dev.antigravity.fluidengine.ui.fluid.GlassDefaults
import dev.antigravity.fluidengine.ui.theme.FluidMotionScheme
import dev.pampa.mycloudgallery.core.designsystem.design.ContentRange
import dev.pampa.mycloudgallery.core.designsystem.design.GalleryChromeStyles
import dev.pampa.mycloudgallery.core.designsystem.theme.GalleryOnPhoto

// Fase 10, P8 (D26): lo schema a contrasto alto. Carta e inchiostro pieni (nero e bianco: quelli gia' del design system, il
// `scrim` dello schema e `GalleryOnPhoto.Content`; nessun colore nuovo scritto a mano), gli accenti portati verso
// l'inchiostro finche' reggono 7:1 sulla carta (AAA), i contenitori appena distinti dalla carta e bordi pieni. Il tema lo
// applica sopra `FluidTheme` solo quando il sistema chiede "piu' contrasto".

/** Il contrasto che gli accenti raggiungono sulla carta con il contrasto alto: AAA per il testo piccolo. */
const val HighContrastAccent: Float = 7f

/**
 * [base] con carta e inchiostro pieni. [dark] dice da che parte sta la carta (nera nel tema scuro).
 * Funzione pura: la provano `GalleryHighContrastTest` e il catalogo degli screenshot.
 */
fun highContrastScheme(base: ColorScheme, dark: Boolean): ColorScheme {
  val black = base.scrim.copy(alpha = 1f)
  val white = GalleryOnPhoto.Content
  val paper = if (dark) black else white
  val ink = if (dark) white else black
  val range = ContentRange(listOf(paper))

  fun accent(color: Color): Color = GalleryChromeStyles.legibleTowards(color, ink, paper, range, HighContrastAccent)
  fun tone(amount: Float): Color = lerp(paper, ink, amount)

  val primary = accent(base.primary)
  val secondary = accent(base.secondary)
  val tertiary = accent(base.tertiary)
  val error = accent(base.error)
  return base.copy(
    background = paper,
    onBackground = ink,
    surface = paper,
    onSurface = ink,
    surfaceVariant = tone(0.08f),
    onSurfaceVariant = ink,
    surfaceBright = paper,
    surfaceDim = tone(0.04f),
    surfaceContainerLowest = paper,
    surfaceContainerLow = tone(0.03f),
    surfaceContainer = tone(0.06f),
    surfaceContainerHigh = tone(0.09f),
    surfaceContainerHighest = tone(0.12f),
    outline = ink,
    outlineVariant = tone(0.55f),
    primary = primary,
    onPrimary = paper,
    primaryContainer = tone(0.10f),
    onPrimaryContainer = ink,
    secondary = secondary,
    onSecondary = paper,
    secondaryContainer = tone(0.10f),
    onSecondaryContainer = ink,
    tertiary = tertiary,
    onTertiary = paper,
    tertiaryContainer = tone(0.10f),
    onTertiaryContainer = ink,
    error = error,
    onError = paper,
    errorContainer = tone(0.10f),
    onErrorContainer = ink,
    inverseSurface = ink,
    inverseOnSurface = paper,
  )
}

/**
 * Fornisce [prefs] ai discendenti e, con il contrasto alto, lo schema a contrasto alto. Sta dentro `FluidTheme`: la forma,
 * il movimento e la tipografia restano quelli del tema.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun GalleryAccessibilityScope(prefs: GalleryAccessibilityPrefs, content: @Composable () -> Unit) {
  val base = MaterialTheme.colorScheme
  val dark = GlassDefaults.isDarkSurface()
  CompositionLocalProvider(LocalGalleryAccessibility provides prefs) {
    if (prefs.highContrast) {
      MaterialTheme(
        colorScheme = highContrastScheme(base, dark),
        motionScheme = FluidMotionScheme,
        shapes = MaterialTheme.shapes,
        typography = MaterialTheme.typography,
        content = content,
      )
    } else {
      content()
    }
  }
}
