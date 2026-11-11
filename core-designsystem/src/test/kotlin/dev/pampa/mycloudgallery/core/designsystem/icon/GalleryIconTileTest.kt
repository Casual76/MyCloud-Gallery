package dev.pampa.mycloudgallery.core.designsystem.icon

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.antigravity.fluidengine.foundation.AccentMode
import dev.antigravity.fluidengine.foundation.EngineSettings
import dev.antigravity.fluidengine.ui.theme.fluidColorScheme
import dev.pampa.mycloudgallery.core.designsystem.design.GalleryLegibility
import dev.pampa.mycloudgallery.core.designsystem.theme.GalleryAccent
import dev.pampa.mycloudgallery.core.designsystem.theme.MyCloudGalleryTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * La piastrella delle icone espressive (I3, fase I): il glifo bianco si legge su ogni famiglia e ogni segno di stato, con **ogni** accento in chiaro
 * e in scuro (3:1, le icone che accompagnano un'etichetta, sul punto piu' chiaro e su quello piu' scuro della sfumatura); le famiglie non si
 * confondono; il segno di stato si legge anche con il lettore dello schermo.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w360dp-h780dp-xhdpi")
class GalleryIconTileTest {

  @get:Rule
  val composeRule = createComposeRule()

  private val schemes: List<Triple<String, ColorScheme, Boolean>> = GalleryAccent.presets.flatMap { preset ->
    listOf(false, true).map { dark ->
      val settings = EngineSettings(accentMode = AccentMode.CUSTOM_PRESET, customAccentName = preset.name)
      Triple(
        "${preset.name} ${if (dark) "scuro" else "chiaro"}",
        fluidColorScheme(settings = settings, isDark = dark, brand = GalleryAccent.Amethyst, presets = GalleryAccent.presets),
        dark,
      )
    }
  }

  private fun assertReadable(what: String, colors: GalleryIconColors) {
    val top = GalleryLegibility.contrast(colors.glyph, colors.top)
    val bottom = GalleryLegibility.contrast(colors.glyph, colors.bottom)
    assertWithMessage("$what: glifo sul punto chiaro $top").that(top).isAtLeast(GalleryLegibility.LargeContrast - Tolerance)
    assertWithMessage("$what: glifo sul punto scuro $bottom").that(bottom).isAtLeast(top)
  }

  @Test
  fun ilGlifoSiLeggeSuOgniFamigliaConOgniAccento() {
    for ((name, scheme, dark) in schemes) {
      for (family in GalleryIconFamily.entries) {
        assertReadable("$name, $family", GalleryIconPalette.colors(family, scheme, dark))
      }
    }
  }

  @Test
  fun ilGlifoSiLeggeSuOgniSegnoDiStatoConOgniAccento() {
    for ((name, scheme, dark) in schemes) {
      for (status in GalleryIconStatus.entries) {
        assertReadable("$name, $status", GalleryIconPalette.status(status, scheme, dark))
      }
    }
  }

  /** Le famiglie sono colori diversi: due famiglie con lo stesso colore direbbero la stessa cosa. */
  @Test
  fun leFamiglieHannoColoriDiversi() {
    for ((name, scheme, dark) in schemes) {
      val tops = GalleryIconFamily.entries.map { GalleryIconPalette.colors(it, scheme, dark).top }
      for (i in tops.indices) {
        for (j in i + 1 until tops.size) {
          val distance = distance(tops[i], tops[j])
          assertWithMessage("$name: ${GalleryIconFamily.entries[i]} e ${GalleryIconFamily.entries[j]} quasi uguali ($distance)")
            .that(distance).isAtLeast(MinDistance)
        }
      }
    }
  }

  /** Le famiglie colorate non seguono l'accento: il backup resta blu anche con l'Indaco o con Material You. */
  @Test
  fun leFamiglieColorateNonSeguonoLAccento() {
    val lights = schemes.filter { !it.third }
    val blue = lights.map { GalleryIconPalette.colors(GalleryIconFamily.Backup, it.second, false).top }.distinct()
    assertThat(blue).hasSize(1)
  }

  /** Lo scurimento tiene la tinta: l'arancio resta arancio (il rosso domina, poi il verde, poi il blu). */
  @Test
  fun loScurimentoTieneLaTinta() {
    val orange = GalleryAccent.presets.first { it.name == "ember" }.light
    val deep = GalleryIconPalette.deepen(orange)
    assertThat(deep.red).isGreaterThan(deep.green)
    assertThat(deep.green).isGreaterThan(deep.blue)
    assertThat(GalleryIconPalette.deepen(deep)).isEqualTo(deep)
  }

  @Test
  fun ilSegnoDiStatoSiLeggeEStaNellaPiastrella() {
    composeRule.setContent {
      MyCloudGalleryTheme(protectAccessibilityData = false) {
        GalleryIconTile(
          icon = Icons.Rounded.CloudUpload,
          family = GalleryIconFamily.Backup,
          status = GalleryIconStatus.Paused,
          contentDescription = "Backup",
          modifier = Modifier.testTag("tile"),
        )
      }
    }
    composeRule.onNodeWithTag("tile").assertWidthIsEqualTo(GalleryIconTileDefaults.Row).assertHeightIsEqualTo(GalleryIconTileDefaults.Row)
    composeRule.onNodeWithContentDescription("Backup").assertExists()
    composeRule.onNodeWithContentDescription("In pausa").assertExists()
  }

  @Test
  fun ilSegnoScalaConLaPiastrellaFraIlMinimoEIlMassimo() {
    assertThat(galleryStatusMarkSize(GalleryIconTileDefaults.Row)).isEqualTo(GalleryIconTileDefaults.MarkMin)
    assertThat(galleryStatusMarkSize(GalleryIconTileDefaults.Medium).value).isWithin(0.01f).of(44f * GalleryIconTileDefaults.MarkFraction)
    assertThat(galleryStatusMarkSize(200.dp)).isEqualTo(GalleryIconTileDefaults.MarkMax)
  }

  private fun distance(a: Color, b: Color): Float {
    val dr = a.red - b.red
    val dg = a.green - b.green
    val db = a.blue - b.blue
    return kotlin.math.sqrt(dr * dr + dg * dg + db * db)
  }

  private companion object {
    /** La ricerca dello scurimento si ferma a un passo dalla soglia: un millesimo di tolleranza sul rapporto. */
    const val Tolerance = 0.01f

    /** Due colori piu' vicini di cosi' (distanza RGB su 0..1) si confondono in una riga. */
    const val MinDistance = 0.12f
  }
}
