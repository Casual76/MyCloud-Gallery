package dev.pampa.mycloudgallery.core.designsystem.catalog

import dev.antigravity.fluidengine.foundation.AccentMode
import dev.antigravity.fluidengine.foundation.EngineSettings
import dev.antigravity.fluidengine.foundation.ThemeMode

/**
 * Gli screenshot del catalogo (piano 1.2): dove vanno e come si chiamano.
 *
 * I PNG si scrivono con `gradlew :core-designsystem:recordRoborazziDebug` in `docs/screenshots/1.2/`
 * (il percorso e' relativo alla cartella del modulo, che e' la directory di lavoro dei test). Nei
 * normali `testDebugUnitTest` i test verificano solo che le pagine compongano, e non scrivono niente.
 */
internal object CatalogShots {

  private const val DIR = "../docs/screenshots/1.2"

  /** Il percorso di un PNG: [name] senza estensione. */
  fun path(name: String): String = "$DIR/$name.png"
}

/** Il formato del dispositivo su cui si guarda il catalogo. */
internal enum class CatalogDevice(val slug: String) {
  /** Un telefono alto come il Galaxy S25: 412 dp di larghezza. */
  Phone("telefono"),

  /** Un tablet in orizzontale: 1280 dp di larghezza. */
  Tablet("tablet"),
}

/** Con quale accento si guarda il catalogo. */
internal enum class CatalogAccent(val suffix: String) {
  /** L'Ametista: il marchio, il default dell'app. */
  Amethyst(""),

  /** Un secondo accento (il preset "ocean" dell'engine): prova che nessun colore e' scritto a mano. */
  Ocean("-oceano"),
}

/** Chiaro o scuro, con quale accento. */
internal data class CatalogLook(val dark: Boolean, val accent: CatalogAccent) {

  val slug: String get() = if (dark) "scuro" else "chiaro"

  fun settings(): EngineSettings = when (accent) {
    CatalogAccent.Amethyst -> EngineSettings(themeMode = mode())
    CatalogAccent.Ocean -> EngineSettings(
      themeMode = mode(),
      accentMode = AccentMode.CUSTOM_PRESET,
      customAccentName = "ocean",
    )
  }

  private fun mode(): ThemeMode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT

  override fun toString(): String = slug + accent.suffix

  companion object {
    val all: List<CatalogLook> = listOf(
      CatalogLook(dark = false, accent = CatalogAccent.Amethyst),
      CatalogLook(dark = true, accent = CatalogAccent.Amethyst),
    )
    val withSecondAccent: List<CatalogLook> = all + listOf(
      CatalogLook(dark = false, accent = CatalogAccent.Ocean),
      CatalogLook(dark = true, accent = CatalogAccent.Ocean),
    )
  }
}

/** Il nome parlante di uno screenshot di gruppo: `celle-chiaro-telefono`, `badge-scuro-tablet-oceano`. */
internal fun shotName(group: CatalogGroup, look: CatalogLook, device: CatalogDevice): String =
  "${group.slug}-${look.slug}-${device.slug}${look.accent.suffix}"
