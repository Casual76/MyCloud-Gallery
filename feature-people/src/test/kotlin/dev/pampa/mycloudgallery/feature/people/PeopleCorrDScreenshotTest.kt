package dev.pampa.mycloudgallery.feature.people

import android.app.Application
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import dev.pampa.mycloudgallery.feature.people.contract.PeopleMessage
import dev.pampa.mycloudgallery.feature.people.contract.PersonHeader
import dev.pampa.mycloudgallery.feature.people.contract.PersonPageState
import dev.pampa.mycloudgallery.feature.people.contract.PersonPageUi
import dev.pampa.mycloudgallery.feature.people.page.NoPersonPageHandlers
import dev.pampa.mycloudgallery.feature.people.page.PersonPageScreen
import dev.pampa.mycloudgallery.feature.people.review.FaceReviewScreen
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Gli screenshot delle schermate di Persone cambiate dalle correzioni dopo le revisioni (Fase 8, pacchetto D): **GE-2** ("Controlla le foto" con un'azione fallita resta
 * sulla schermata e dice cosa e' successo, invece di tornare indietro) e **NV-1** (una persona con le foto tutte nascoste dice "Tutte le foto sono nascoste", non
 * "Persona dimenticata"). Telefono (412 dp), chiaro e scuro, e il testo a 1,3x. **Nessun volto vero, nomi inventati.**
 *
 * `gradlew :feature-people:recordRoborazziDebug --tests "*PeopleCorrDScreenshot*"` scrive i PNG in `docs/screenshots/8-corr-d/`. Nei normali `testDebugUnitTest` si
 * verifica solo che le schermate compongano e dicano le cose giuste. Il foglio "Senza nome" del viewer (GE-3) e' negli screenshot di `feature-viewer`.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = SHOT_PHONE_QUALIFIERS)
class PeopleCorrDScreenshotTest(private val dark: Boolean) : PeopleScreenTest() {

  private val look: String get() = if (dark) "scuro" else "chiaro"

  private fun shoot(name: String, vararg expect: String, fontScale: Float? = null, content: @Composable () -> Unit) {
    showAll(dark = dark, fontScale = fontScale, content = content)
    for (text in expect) assertShows(name, text)
    val suffix = if (fontScale != null) "-testo-grande" else ""
    composeRule.onRoot().captureRoboImage("../docs/screenshots/8-corr-d/$name-$look-telefono$suffix.png")
  }

  // --- GE-2: "Controlla le foto" con un'azione fallita -----------------------------------------------------------------------------------

  private fun reviewFailed() = scenes.check().copy(message = PeopleMessage.NOT_ACTIVE)

  @Test fun controllaConAzioneFallita() =
    shoot("controlla-azione-fallita", "Controlla le foto", "Persone non è attiva", "Non è Anna") { FaceReviewScreen(reviewFailed(), "Anna", scenes.crops, RecordingReview()) }

  @Test fun controllaConAzioneFallitaTestoGrande() =
    shoot("controlla-azione-fallita", "Persone non è attiva", fontScale = LARGE_TEXT) { FaceReviewScreen(reviewFailed(), "Anna", scenes.crops, RecordingReview()) }

  @Test fun controllaVoltoSparito() =
    shoot("controlla-volto-sparito", "Controlla le foto", "non c") {
      FaceReviewScreen(scenes.check().copy(message = PeopleMessage.NOT_FOUND), "Anna", scenes.crops, RecordingReview())
    }

  // --- NV-1: una persona con le foto tutte nascoste ----------------------------------------------------------------------------------------

  private fun allHidden(name: String?) = PersonPageUi(PersonPageState.AllHidden(PersonHeader(scenes.world.anna, name, 0, null)))

  private fun page(ui: PersonPageUi): @Composable () -> Unit = { PersonPageScreen(ui, scenes.thumbs.thumbnails, scenes.crops, NoPersonPageHandlers) }

  @Test fun personaConLeFotoTutteNascoste() =
    shoot("persona-foto-nascoste", "Anna", "Tutte le foto sono nascoste", "Il nome e il gruppo ci sono ancora", content = page(allHidden("Anna")))

  @Test fun personaConLeFotoTutteNascosteTestoGrande() =
    shoot("persona-foto-nascoste", "Tutte le foto sono nascoste", fontScale = LARGE_TEXT, content = page(allHidden("Anna")))

  @Test fun gruppoSenzaNomeConLeFotoTutteNascoste() =
    shoot("gruppo-foto-nascoste", "Tutte le foto sono nascoste", content = page(allHidden(null)))

  @Test fun personaDimenticataDiceAncoraDimenticata() =
    shoot("persona-dimenticata", "Persona dimenticata", content = page(PersonPageUi(PersonPageState.Forgotten)))

  companion object {
    @JvmStatic
    @ParameterizedRobolectricTestRunner.Parameters(name = "scuro={0}")
    fun data(): List<Array<Any>> = listOf(arrayOf<Any>(false), arrayOf<Any>(true))
  }
}
