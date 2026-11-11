package dev.pampa.mycloudgallery

import android.app.Application
import android.content.pm.ActivityInfo
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import dev.antigravity.fluidengine.foundation.EngineSettings
import dev.antigravity.fluidengine.foundation.ThemeMode
import dev.pampa.mycloudgallery.core.designsystem.chrome.GalleryTab
import dev.pampa.mycloudgallery.core.designsystem.theme.MyCloudGalleryTheme
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Gli screenshot del pacchetto G2 della fase grafica (per l'utente, prima della beta): la pillola **Libreria · Album · Raccolte** su ogni scheda,
 * il cerchio di Cerca (aperto, dalla fase H, e' la barra di ricerca: i suoi screenshot sono in `H4/`), la pillola ripiegata dallo scorrimento della Libreria (la scheda scelta e il cerchio) e la Libreria in
 * selezione con la barra delle azioni al posto della pillola. Chiaro e scuro, telefono dell'utente (360 dp) e tablet (1280 dp).
 *
 * K3a (GF-4, ORC-1): tutto passa dallo scaffold delle prove ([GalleryScaffold]): le schede sono quelle vere su stati dati, come in produzione. La
 * scheda Album e' la griglia di copertine di G2b (non piu' l'elenco di prima), le Raccolte
 * sono quelle alleggerite con la capsula della rete in testa e il fondo della pagina. Le Raccolte alleggerite di G2 (`raccolte-alleggerite`) sono
 * `pillola-raccolte`: erano la stessa schermata. Il foglio "Prima mettilo al sicuro" ha i suoi in feature-backup (`G2SelectionSheet*Shots`), il
 * pannello delle Raccolte in selezione sul tablet in feature-library (`G2Raccolte*`).
 *
 * `gradlew :app:recordRoborazziDebug --tests "*G2*Screenshot*"` scrive i PNG in `docs/screenshots/beta-grafica/G2/`.
 */
internal abstract class G2ScreenshotBase(private val dark: Boolean, private val device: String) {

  val composeRule = createComposeRule()

  @get:Rule
  val rules: RuleChain = RuleChain.outerRule(RegisterComponentActivity()).around(composeRule)

  private class RegisterComponentActivity : TestWatcher() {
    override fun starting(description: Description) {
      val app = ApplicationProvider.getApplicationContext<Application>()
      val info = ActivityInfo().apply {
        name = ComponentActivity::class.java.name
        packageName = app.packageName
        flags = flags or ActivityInfo.FLAG_HARDWARE_ACCELERATED
      }
      shadowOf(app.packageManager).addOrUpdateActivity(info)
    }
  }

  private val look: String get() = if (dark) "scuro" else "chiaro"

  /** [afterShow] porta la schermata nello stato da fotografare; poi si lascia assestare ogni animazione (la capsula, la pillola) e si scatta. */
  private fun shoot(name: String, afterShow: () -> Unit = {}, content: @Composable () -> Unit) {
    composeRule.setContent {
      MyCloudGalleryTheme(settings = EngineSettings(themeMode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT)) { content() }
    }
    composeRule.waitForIdle()
    afterShow()
    composeRule.waitForIdle()
    composeRule.mainClock.advanceTimeBy(SETTLE_MS)
    composeRule.waitForIdle()
    composeRule.onRoot().captureRoboImage("$DIR/$name-$look-$device.png")
  }

  @Test
  fun pillolaLibreria() {
    val scenes = ScaffoldScenes()
    shoot("pillola-libreria") { GalleryScaffold(scenes, GalleryTab.Library) }
  }

  /** La scheda Album vera (G2b): Preferiti, Fotocamera e Video in testa, poi gli album; il vetro della pillola sopra le copertine. */
  @Test
  fun pillolaAlbum() {
    val scenes = ScaffoldScenes()
    shoot("pillola-album") { GalleryScaffold(scenes, GalleryTab.Albums) }
  }

  /** Le Raccolte alleggerite di G2 (senza Album, Album smart e Telefono), con la capsula della rete in testa come in produzione. */
  @Test
  fun pillolaRaccolte() {
    val scenes = ScaffoldScenes()
    shoot("pillola-raccolte") { GalleryScaffold(scenes, GalleryTab.Collections) }
  }

  /** La pillola ripiegata dallo scorrimento della Libreria verso i piu' vecchi (il dito che scende): la scheda scelta e, accanto, il cerchio. */
  @Test
  fun pillolaRipiegata() {
    val scenes = ScaffoldScenes()
    shoot(
      "pillola-ripiegata",
      afterShow = { composeRule.onRoot().performTouchInput { swipeDown(startY = height * 0.3f, endY = height * 0.7f) } },
    ) { GalleryScaffold(scenes, GalleryTab.Library) }
  }

  /** B3: la Libreria in selezione, con la barra delle azioni al posto della pillola (e del cerchio); la capsula "3 selezionati" a riposo. */
  @Test
  fun libreriaInSelezione() {
    val scenes = ScaffoldScenes()
    val state = scenes.library
    shoot(
      "libreria-selezione",
      afterShow = {
        composeRule.runOnIdle {
          state.grid.startSelecting()
          val view = state.grid.view
          state.grid.selection.selectAll((0 until 3).map { view.snapshot.idAt(view.snapshotIndex(it)) })
        }
      },
    ) { GalleryScaffold(scenes, GalleryTab.Library) }
  }

  companion object {
    const val DIR = "../docs/screenshots/beta-grafica/G2"

    /** Piu' di ogni animazione della chrome (la capsula che cambia misura, la pillola che esce, la barra che entra). */
    const val SETTLE_MS = 2_000L

    fun looks(): List<Array<Any>> = listOf(arrayOf<Any>(false), arrayOf<Any>(true))
  }
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = SHOT_PHONE)
internal class G2PhoneScreenshotTest(dark: Boolean) : G2ScreenshotBase(dark, "telefono") {
  companion object {
    @JvmStatic
    @ParameterizedRobolectricTestRunner.Parameters(name = "scuro={0}")
    fun data(): List<Array<Any>> = looks()
  }
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = SHOT_TABLET)
internal class G2TabletScreenshotTest(dark: Boolean) : G2ScreenshotBase(dark, "tablet") {
  companion object {
    @JvmStatic
    @ParameterizedRobolectricTestRunner.Parameters(name = "scuro={0}")
    fun data(): List<Array<Any>> = looks()
  }
}
