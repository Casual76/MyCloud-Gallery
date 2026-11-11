package dev.pampa.mycloudgallery

import android.app.Application
import android.content.pm.ActivityInfo
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
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
 * Gli screenshot della ricerca nella forma della fase I (I2, 11/11 sera: «la ricerca che mostra invece di spiegare»), per l'utente. Il disegno della fase H
 * resta (la barra in basso, i risultati sopra, una X sola); cambiano:
 *  - **gli esempi con la foto**: a campo vuoto la ricerca recente piu' nuova e due esempi con la foto del loro primo risultato, che a ogni apertura
 *    girano (qui due aperture di fila e la barra con un esempio solo, che e' una capsula con il suo tondo e non sembra un titolo);
 *  - **mentre si scrive** i completamenti hanno la stessa forma (tondo + etichetta);
 *  - **la riga sopra la barra**: un segno e al piu' due parole («Niente per…» e' il segno con cio' che si e' cercato), "Trova simili" con la foto da cui
 *    vengono, e la copertura della ricerca **una riga sola con la barra che avanza** (gli avvisi «nessun dispositivo analizza…» sono solo in Impostazioni).
 * Chiaro e scuro, il telefono dell'utente (360 dp) e il tablet; sul telefono anche il testo a 1,3x. Tutto dallo scaffold delle prove: le schede vere su
 * stati dati, le foto dalla Libreria sintetica.
 *
 * `gradlew :app:recordRoborazziDebug --tests dev.pampa.mycloudgallery.I2RicercaPhoneScreenshotTest` (e `...TabletScreenshotTest`) scrive i PNG in
 * `docs/screenshots/beta-grafica/I2/`.
 */
internal abstract class I2RicercaScreenshotBase(private val dark: Boolean, private val device: String) {

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

  protected val look: String get() = if (dark) "scuro" else "chiaro"

  /** Porta la scena e la lascia assestare (la barra che si apre, le dissolvenze, la barra della copertura che si allunga), poi scatta. */
  protected fun shoot(name: String, fontScale: Float? = null, content: @Composable () -> Unit) {
    composeRule.setContent {
      MyCloudGalleryTheme(settings = EngineSettings(themeMode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT)) {
        val density = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale ?: density.fontScale)) { content() }
      }
    }
    composeRule.waitForIdle()
    composeRule.mainClock.advanceTimeBy(SETTLE_MS)
    composeRule.waitForIdle()
    val suffix = if (fontScale != null) "-testo-grande" else ""
    composeRule.onRoot().captureRoboImage("$DIR/$name-$look-$device$suffix.png")
  }

  /** A campo vuoto: la ricerca recente e due esempi con la foto del primo risultato (un luogo, un mese pieno). */
  @Test
  fun esempiConLaFoto() {
    val scenes = ScaffoldScenes()
    shoot("esempi-con-foto") { GalleryScaffold(scenes, GalleryTab.Library, searchOpen = true, search = scenes.searchIdle) }
  }

  /** L'apertura successiva: la terna e' girata (una persona con il suo volto, un tipo che c'e'). */
  @Test
  fun esempiGirati() {
    val scenes = ScaffoldScenes()
    shoot("esempi-girati") { GalleryScaffold(scenes, GalleryTab.Library, searchOpen = true, search = scenes.searchIdleRotated) }
  }

  /** Mentre si scrive «mare»: i completamenti con il loro tondo, i risultati sotto. */
  @Test
  fun scrivendoICompletamentiHannoLaFoto() {
    val scenes = ScaffoldScenes()
    shoot("scrivendo-completamenti") { GalleryScaffold(scenes, GalleryTab.Library, searchOpen = true, search = scenes.searchTyping) }
  }

  /** Nessun risultato: il segno con cio' che si e' cercato e, in alto, le recenti con la loro foto. */
  @Test
  fun nessunRisultato() {
    val scenes = ScaffoldScenes()
    shoot("nessun-risultato") { GalleryScaffold(scenes, GalleryTab.Library, searchOpen = true, search = scenes.searchNothing) }
  }

  /** "Trova simili": la riga con la foto da cui vengono e «Simili». */
  @Test
  fun trovaSimili() {
    val scenes = ScaffoldScenes()
    shoot("trova-simili") { GalleryScaffold(scenes, GalleryTab.Library, searchOpen = true, search = scenes.searchSimilar) }
  }

  /** La copertura sotto il 98% (nessuno analizza l'area comune): una riga sola, il segno, la barra che avanza e la percentuale. */
  @Test
  fun coperturaUnaRigaConLaBarra() {
    val scenes = ScaffoldScenes()
    shoot("copertura-riga") { GalleryScaffold(scenes, GalleryTab.Library, searchOpen = true, search = scenes.searchCoverage) }
  }

  companion object {
    const val DIR = "../docs/screenshots/beta-grafica/I2"

    /** Piu' di ogni animazione della barra; il riflesso della barra della copertura puo' essere a meta' corsa (si vede che e' viva). */
    const val SETTLE_MS = 2_000L

    fun looks(): List<Array<Any>> = listOf(arrayOf<Any>(false), arrayOf<Any>(true))
  }
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = SHOT_PHONE)
internal class I2RicercaPhoneScreenshotTest(private val dark: Boolean) : I2RicercaScreenshotBase(dark, "telefono") {

  /** Un esempio solo: una capsula con il suo tondo, al centro, che si legge come un tasto e non come un titolo. */
  @Test
  fun unEsempioSolo() {
    val scenes = ScaffoldScenes()
    shoot("esempio-solo") { GalleryScaffold(scenes, GalleryTab.Library, searchOpen = true, search = scenes.searchIdleSingle) }
  }

  /** Il testo a 1,3x (solo in chiaro): le voci restano intere (meno voci se serve), il tondo resta tondo. */
  @Test
  fun esempiTestoGrande() {
    if (dark) return
    val scenes = ScaffoldScenes()
    shoot("esempi-con-foto", fontScale = LARGE_TEXT) { GalleryScaffold(scenes, GalleryTab.Library, searchOpen = true, search = scenes.searchIdle) }
  }

  /** La riga della copertura col testo a 1,3x (solo in chiaro). */
  @Test
  fun coperturaTestoGrande() {
    if (dark) return
    val scenes = ScaffoldScenes()
    shoot("copertura-riga", fontScale = LARGE_TEXT) { GalleryScaffold(scenes, GalleryTab.Library, searchOpen = true, search = scenes.searchCoverage) }
  }

  companion object {
    private const val LARGE_TEXT = 1.3f

    @JvmStatic
    @ParameterizedRobolectricTestRunner.Parameters(name = "scuro={0}")
    fun data(): List<Array<Any>> = looks()
  }
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = SHOT_TABLET)
internal class I2RicercaTabletScreenshotTest(dark: Boolean) : I2RicercaScreenshotBase(dark, "tablet") {
  companion object {
    @JvmStatic
    @ParameterizedRobolectricTestRunner.Parameters(name = "scuro={0}")
    fun data(): List<Array<Any>> = looks()
  }
}
