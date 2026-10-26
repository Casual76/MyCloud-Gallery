package dev.pampa.mycloudgallery.feature.nas

import android.app.Application
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import dev.pampa.mycloudgallery.core.nasops.contract.NasFolderState
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import dev.pampa.mycloudgallery.feature.nas.folders.FolderWhere
import dev.pampa.mycloudgallery.feature.nas.folders.FoldersActions
import dev.pampa.mycloudgallery.feature.nas.folders.FoldersUiState
import dev.pampa.mycloudgallery.feature.nas.folders.HiddenFiles
import dev.pampa.mycloudgallery.feature.nas.folders.NasFoldersScreen
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Gli screenshot di P9 (Fase 7, I8 della 4B): la riga "N elementi nascosti" di Cartelle NAS, senza nessun nome, e lo stato "non riesco a controllare".
 * Chiaro e scuro, telefono (412 dp) e tablet (1280 dp), piu' il testo grande. Sui fake di [NasFixtures]; i due file nascosti sono i nomi che nella
 * pagina **non** compaiono.
 *
 * `gradlew :feature-nas:recordRoborazziDebug --tests "*NasHidden*Screenshot*"` scrive i PNG in `docs/screenshots/7-P9/`. Nei normali
 * `testDebugUnitTest` si verifica solo che le schermate compongano. (Una classe a parte: `NasScreenshotTest` riscrive le immagini della 4B.)
 */
abstract class NasHiddenScreenshotBase(private val dark: Boolean, private val device: String) : NasComposeBase() {

  private val areas = FakeNasAreas.defaultAreas()
  private val look: String get() = if (dark) "scuro" else "chiaro"
  private val entries = NasFixtures.tripEntries()

  private fun shoot(name: String, fontScale: Float? = null, content: @Composable () -> Unit) {
    show(dark = dark, fontScale = fontScale, content = content)
    val suffix = if (fontScale != null) "-testo-grande" else ""
    composeRule.onRoot().captureRoboImage("$DIR/$name-$look-$device$suffix.png")
  }

  private fun folders(hidden: HiddenFiles) = FoldersUiState(
    areas = areas,
    where = FolderWhere("test", SharePath.of("Viaggi")),
    listing = NasFolderState.Listing(entries, readOnly = false, cachedAt = null),
    hidden = hidden,
  )

  private fun twoHidden(): HiddenFiles =
    HiddenFiles.Known(entries.filter { it.name == "Biglietti aerei.pdf" || it.name == "DJI_0142.mp4" }.map { it.path }.toSet())

  @Composable
  private fun FoldersPage(state: FoldersUiState) {
    NasFoldersScreen(state = state, actions = FoldersActions(), monotonicMs = { 0L }, onBack = {})
  }

  @Test
  fun cartellaConDueNascosti() = shoot("nas-nascosti") { FoldersPage(folders(twoHidden())) }

  @Test
  fun cartellaConDueNascostiTestoGrande() = shoot("nas-nascosti", fontScale = LARGE_TEXT) { FoldersPage(folders(twoHidden())) }

  @Test
  fun nascostiNonControllabili() = shoot("nas-nascosti-non-controllabili") { FoldersPage(folders(HiddenFiles.Unknown)) }

  @Test
  fun nascostiNonControllabiliTestoGrande() = shoot("nas-nascosti-non-controllabili", fontScale = LARGE_TEXT) {
    FoldersPage(folders(HiddenFiles.Unknown))
  }

  companion object {
    const val DIR = "../docs/screenshots/7-P9"
    fun looks(): List<Array<Any>> = listOf(arrayOf<Any>(false), arrayOf<Any>(true))
  }
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = PHONE_QUALIFIERS)
class NasHiddenPhoneScreenshotTest(dark: Boolean) : NasHiddenScreenshotBase(dark, "telefono") {
  companion object {
    @JvmStatic
    @ParameterizedRobolectricTestRunner.Parameters(name = "scuro={0}")
    fun data(): List<Array<Any>> = looks()
  }
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = TABLET_QUALIFIERS)
class NasHiddenTabletScreenshotTest(dark: Boolean) : NasHiddenScreenshotBase(dark, "tablet") {
  companion object {
    @JvmStatic
    @ParameterizedRobolectricTestRunner.Parameters(name = "scuro={0}")
    fun data(): List<Array<Any>> = looks()
  }
}
