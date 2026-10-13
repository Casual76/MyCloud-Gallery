package dev.pampa.mycloudgallery.feature.settings.about

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.designsystem.theme.MyCloudGalleryTheme
import dev.pampa.mycloudgallery.feature.settings.R
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * "Informazioni e licenze" (Fase 3, P11): l'attribuzione di GeoNames, le tre librerie di altri autori e la
 * versione dell'app. Gli indirizzi delle licenze sono **testo, mai un collegamento** (la Fase 3 non fa traffico di
 * rete, D18/D24): lo prova sia la schermata (nessun tocco su quelle righe) sia il sorgente (nessun intent).
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "it-w412dp-h1800dp-xhdpi")
class AboutScreenTest {

  @get:Rule
  val rule = createAndroidComposeRule<ComponentActivity>()

  private fun show(versionName: String? = "1.2.3", onBack: () -> Unit = {}) {
    rule.setContent {
      MyCloudGalleryTheme {
        AboutScreen(versionName = versionName, onBack = onBack)
      }
    }
    rule.waitForIdle()
  }

  private fun string(id: Int): String = rule.activity.getString(id)

  // --- GeoNames (CC BY 4.0) -----------------------------------------------------------------------------------

  @Test
  fun mostraLAttribuzioneDiGeoNamesParolaPerParola() {
    show()

    // Il testo che la licenza chiede, uguale a PlacesAttribution.TEXT (lo verifica un test di :app).
    val expected = "Nomi e fusi orari dei luoghi: dati di GeoNames (geonames.org), licenza Creative Commons Attribuzione 4.0. " +
      "I dati sono stati modificati: ridotti, in parte tradotti in italiano e convertiti in un formato compatto."
    assertThat(string(R.string.about_geonames_attribution)).isEqualTo(expected)
    rule.onNodeWithText(expected).assertIsDisplayed()
  }

  @Test
  fun avvisaCheInQuestaVersioneIDatiDeiLuoghiPotrebberoNonEsserciAncora() {
    show()

    rule.onNodeWithText(
      "Se in questa versione i luoghi non compaiono, i dati non sono ancora inclusi: la galleria funziona lo stesso.",
    ).assertIsDisplayed()
  }

  @Test
  fun mostraNomeEIndirizzoDellaLicenzaCcByComeTestoSenzaTocco() {
    show()

    rule.onNodeWithText("Creative Commons Attribuzione 4.0 (CC BY 4.0)").assertIsDisplayed()
    // L'indirizzo e' una riga di testo: niente azione di clic (nessun browser, nessuna rete).
    rule.onNodeWithText("https://creativecommons.org/licenses/by/4.0/").assertIsDisplayed().assertHasNoClickAction()
  }

  // --- Le librerie ----------------------------------------------------------------------------------------------

  @Test
  fun elencaLeTreLibrerieConAutoreVersioneELicenza() {
    show()

    rule.onNodeWithText("telephoto").assertIsDisplayed()
    rule.onNodeWithText("Saket Narayan · zoomable-image e sub-sampling-image 0.19.0").assertIsDisplayed()
    rule.onNodeWithText("AndroidX Media3 ExoPlayer").assertIsDisplayed()
    rule.onNodeWithText("The Android Open Source Project · versione 1.11.1").assertIsDisplayed()
    rule.onNodeWithText("AndroidX ExifInterface").assertIsDisplayed()
    rule.onNodeWithText("The Android Open Source Project · versione 1.4.2").assertIsDisplayed()
    // Una riga di licenza per libreria.
    rule.onAllNodesWithText("Apache License 2.0").assertCountEquals(3)
  }

  @Test
  fun mostraLIndirizzoDellaLicenzaApacheComeTestoSenzaTocco() {
    show()

    rule.onNodeWithText("Apache License, versione 2.0").assertIsDisplayed()
    rule.onNodeWithText("https://www.apache.org/licenses/LICENSE-2.0").assertIsDisplayed().assertHasNoClickAction()
  }

  @Test
  fun dichiaraCheLeLibrerieNonInviamoDatiENonCiSonoConnessioniDiRete() {
    show()

    rule.onNodeWithText(
      "Le librerie non inviano dati. Per la galleria del telefono l’app non fa nessuna connessione di rete.",
    ).assertIsDisplayed()
  }

  // --- La versione ----------------------------------------------------------------------------------------------------

  @Test
  fun mostraLaVersioneDellApp() {
    show(versionName = "1.2.3")

    rule.onNodeWithText("MyCloud Gallery").assertIsDisplayed()
    rule.onNodeWithText("Versione 1.2.3").assertIsDisplayed()
  }

  @Test
  fun senzaVersioneNonMostraLaRiga() {
    show(versionName = null)

    rule.onNodeWithText("MyCloud Gallery").assertIsDisplayed()
    rule.onAllNodesWithText("Versione ", substring = true).assertCountEquals(0)
  }

  @Test
  fun laVersioneVieneDalGestoreDeiPacchetti() {
    val context = ApplicationProvider.getApplicationContext<android.app.Application>()
    shadowOf(context.packageManager).getInternalMutablePackageInfo(context.packageName).versionName = "9.8.7"

    assertThat(appVersionName(context)).isEqualTo("9.8.7")
  }

  // --- Navigazione --------------------------------------------------------------------------------------------------

  @Test
  fun ilTastoIndietroTornaAlleImpostazioni() {
    var back = 0
    show(onBack = { back++ })

    rule.onNodeWithContentDescription("Indietro").performClick()

    assertThat(back).isEqualTo(1)
  }

  // --- Nessun traffico di rete ----------------------------------------------------------------------------------

  @Test
  fun ilSorgenteDellaPaginaNonApreCollegamentiNeFaRete() {
    val sources = File("src/main/kotlin/dev/pampa/mycloudgallery/feature/settings/about").walkTopDown()
      .filter { it.isFile && it.extension == "kt" }
      .toList()
    assertThat(sources).isNotEmpty()

    // Un indirizzo qui e' testo (sta nelle risorse), non un intent ne' un collegamento ne' una connessione.
    val forbidden = Regex(
      """\bIntent\b|ACTION_VIEW|LocalUriHandler|UriHandler|openUri|LinkAnnotation|ClickableText|Uri\.parse|\bURL\(|HttpURLConnection|https?://""",
    )
    val found = sources.flatMap { file ->
      file.readLines().mapIndexedNotNull { index, line ->
        val code = line.substringBefore("//")
        val trimmed = code.trimStart()
        val isDoc = trimmed.startsWith("*") || trimmed.startsWith("/**")
        if (!isDoc && forbidden.containsMatchIn(code)) "${file.name}:${index + 1}: ${line.trim()}" else null
      }
    }
    assertThat(found).isEmpty()
  }
}
