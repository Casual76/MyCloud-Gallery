package dev.pampa.mycloudgallery.core.designsystem.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import dev.antigravity.fluidengine.foundation.EngineSettings
import dev.antigravity.fluidengine.ui.theme.AccentPoles
import dev.antigravity.fluidengine.ui.theme.AccentPreset
import dev.antigravity.fluidengine.ui.theme.FluidTheme
import dev.antigravity.fluidengine.ui.theme.fluidAccentPresets

/**
 * Il marchio della galleria.
 *
 * **Ametista** e' il colore dell'app (#9966CC in chiaro, #B88CE8 in scuro). Un viola vive vicino
 * all'indaco che l'engine usa come ancora del secondario, e senza i poli i tre ruoli (primario,
 * secondario, terziario) collasserebbero in tre sfumature dello stesso viola: per questo l'Ametista
 * porta i suoi poli, uno verso il blu e uno verso il rosa, con la stessa taratura verificata dal
 * test `violetBrandWithPoles` dell'engine.
 */
object GalleryAccent {

  /** Il nome con cui l'Ametista si salva nelle impostazioni. */
  const val AMETHYST_NAME: String = "amethyst"

  val Amethyst: AccentPreset = AccentPreset(
    name = AMETHYST_NAME,
    label = "Ametista",
    light = Color(0xFF9966CC),
    dark = Color(0xFFB88CE8),
    poles = AccentPoles(
      secondaryLight = Color(0xFF007AFF),
      secondaryDark = Color(0xFF0A84FF),
      tertiaryLight = Color(0xFFFF2D55),
      tertiaryDark = Color(0xFFFF375F),
      secondaryBlend = 0.45f,
      tertiaryBlend = 0.40f,
    ),
  )

  /**
   * I preset fra cui l'utente potra' scegliere: l'Ametista per prima (e' anche quella a cui ripiega
   * un nome sconosciuto), poi i preset Fluid. La UI di scelta arriva dopo; il tema li risolve gia'.
   */
  val presets: List<AccentPreset> = listOf(Amethyst) + fluidAccentPresets
}

/**
 * Il tema di MyCloud Gallery.
 *
 * Il default (`EngineSettings()`) e' l'Ametista che segue il tema del sistema. Le altre modalita'
 * sono tutte nel parametro [settings], senza altro codice:
 *  - **Material You**: `EngineSettings(accentMode = AccentMode.DYNAMIC)` (dal sistema, Android 12+;
 *    sotto ripiega sull'Ametista);
 *  - **un preset Fluid**: `EngineSettings(accentMode = AccentMode.CUSTOM_PRESET, customAccentName = "ocean")`;
 *  - chiaro, scuro o AMOLED: `themeMode`.
 *
 * La schermata delle impostazioni che li fa scegliere all'utente, e il salvataggio, arrivano dopo.
 */
@Composable
fun MyCloudGalleryTheme(
  settings: EngineSettings = EngineSettings(),
  content: @Composable () -> Unit,
) {
  FluidTheme(
    settings = settings,
    brand = GalleryAccent.Amethyst,
    presets = GalleryAccent.presets,
    content = content,
  )
}
