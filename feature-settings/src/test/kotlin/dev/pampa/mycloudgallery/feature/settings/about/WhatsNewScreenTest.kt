package dev.pampa.mycloudgallery.feature.settings.about

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.designsystem.theme.MyCloudGalleryTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * "Novita' di questa versione" (Fase 10, P12, D33): la pagina semplice con le frasi di `CHANGELOG.md`. Qui con un testo di prova e con la risorsa
 * vera dell'app (la copia di `CHANGELOG.md`, che un altro test tiene uguale alla fonte); la riga che la apre sta in Informazioni e licenze.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "it-w412dp-h1800dp-xhdpi")
class WhatsNewScreenTest {

  @get:Rule
  val rule = createAndroidComposeRule<ComponentActivity>()

  private val notes = ReleaseNotes(
    version = "7.8.9",
    sections = listOf(
      ReleaseNotesSection("Novità", listOf("Una cosa nuova.", "Un'altra cosa nuova.")),
      ReleaseNotesSection("Correzioni", listOf("Una cosa sistemata.")),
      ReleaseNotesSection("Sicurezza", listOf("Una cosa più sicura.")),
      ReleaseNotesSection("Da sapere", listOf("Una cosa da sapere.")),
    ),
  )

  private fun scrollTo(text: String) {
    rule.onNode(hasScrollAction()).performScrollToNode(hasText(text))
    rule.waitForIdle()
  }

  @Test
  fun mostraLaVersioneLeVociEOgniFrase() {
    rule.setContent { MyCloudGalleryTheme { WhatsNewScreen(notes = notes, matchesVersion = true, versionName = "7.8.9", onBack = {}) } }
    rule.waitForIdle()

    // Il titolo della pagina compare due volte (grande e raccolto): ne basta uno in vista.
    rule.onAllNodesWithText("Novità di questa versione").onFirst().assertIsDisplayed()
    rule.onNodeWithText("Versione 7.8.9".uppercase()).assertIsDisplayed()
    for (title in listOf("Novità", "Correzioni", "Sicurezza", "Da sapere")) {
      scrollTo(title.uppercase())
      rule.onNodeWithText(title.uppercase()).assertIsDisplayed()
    }
    for (sentence in notes.sections.flatMap { it.items }) {
      scrollTo(sentence)
      rule.onNodeWithText(sentence).assertIsDisplayed()
    }
    // Una versione che coincide non porta nessuna avvertenza.
    rule.onAllNodesWithText("Qui sotto ci sono le novità dell’ultima versione", substring = true).assertCountEquals(0)
  }

  @Test
  fun seLaVersioneNonCoincideLoDiceInveceDiScambiarla() {
    rule.setContent { MyCloudGalleryTheme { WhatsNewScreen(notes = notes, matchesVersion = false, versionName = "9.9.9", onBack = {}) } }
    rule.waitForIdle()

    rule.onNodeWithText("Qui sotto ci sono le novità dell’ultima versione scritta nel file. Questa app è la versione 9.9.9.").assertIsDisplayed()
  }

  @Test
  fun senzaTestoDicePerchePaginaNonVuota() {
    rule.setContent { MyCloudGalleryTheme { WhatsNewScreen(notes = null, matchesVersion = false, versionName = "1.0.0", onBack = {}) } }
    rule.waitForIdle()

    rule.onNodeWithText("Le novità di questa versione non si riescono a leggere. L’app funziona lo stesso.").assertIsDisplayed()
  }

  @Test
  fun laRisorsaVeraDellAppSiLeggeEMostraLaSezioneDellaVersione() {
    rule.setContent { MyCloudGalleryTheme { WhatsNewRoute(versionName = "1.0.0-debug", onBack = {}) } }
    rule.waitForIdle()

    val real = ReleaseNotesParser.forVersion(
      rule.activity.resources.openRawResource(dev.pampa.mycloudgallery.feature.settings.R.raw.changelog).bufferedReader().readText(),
      "1.0.0",
    )!!
    rule.onNodeWithText("Versione 1.0.0".uppercase()).assertIsDisplayed()
    val first = real.sections.first().items.first()
    scrollTo(first)
    rule.onNodeWithText(first).assertIsDisplayed()
    rule.onAllNodesWithText("Qui sotto ci sono le novità dell’ultima versione", substring = true).assertCountEquals(0)
  }

  @Test
  fun laRigaDiInformazioniApreLaPaginaSoloSeCEIlGancio() {
    var opened = 0
    rule.setContent { MyCloudGalleryTheme { AboutScreen(versionName = "1.0.0", onBack = {}, onOpenNews = { opened++ }) } }
    rule.waitForIdle()

    rule.onNodeWithText("Novità di questa versione").assertIsDisplayed().performClick()
    assertThat(opened).isEqualTo(1)
  }

  @Test
  fun senzaIlGancioInInformazioniNonCompareLaRiga() {
    rule.setContent { MyCloudGalleryTheme { AboutScreen(versionName = "1.0.0", onBack = {}) } }
    rule.waitForIdle()

    rule.onAllNodesWithText("Novità di questa versione").assertCountEquals(0)
  }
}
