package dev.pampa.mycloudgallery.feature.backup.free

import android.app.Application
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import com.github.takahirom.roborazzi.captureRoboImage
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.backup.contract.FreeSpaceFilter
import dev.pampa.mycloudgallery.core.backup.free.PhoneOnlyAdvice
import dev.pampa.mycloudgallery.core.backup.free.PhoneOnlyAssessment
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.feature.backup.BackupComposeBase
import dev.pampa.mycloudgallery.feature.backup.ComposeBackupTallTest
import dev.pampa.mycloudgallery.feature.backup.PHONE_QUALIFIERS
import dev.pampa.mycloudgallery.feature.backup.TABLET_QUALIFIERS
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 5B I3 (decisione dell'utente, fase grafica G2): "Elimina dal telefono" dalla **selezione della Libreria**, con il backup attivo, passa dallo
 * stesso foglio del viewer ("Prima mettilo al sicuro") con il cestino di Android offerto per i media che non sono sul NAS (`offerTrash` in `:app`).
 * Con piu' elementi il foglio dice quanti non sono ancora al sicuro e offre la stessa scelta del viewer. Il foglio di sempre (backup spento) e'
 * provato nella Libreria (`LibraryDeleteSafetyTest`).
 */
class G2SelezioneLibreriaTest : ComposeBackupTallTest() {

  private val trashed = ArrayList<Set<MediaId>>()
  private val continued = ArrayList<FreeSpaceFilter>()
  private val chosen = ArrayList<Boolean>()
  private var safeguards = 0

  private fun id(n: Int) = FreeSpaceSamples.mediaId(n)

  private fun selection(vararg values: PhoneOnlyAdvice): PhoneOnlyUi.Advice {
    val assessment = PhoneOnlyAssessment(values.withIndex().associate { (i, a) -> id(i + 1) to a })
    return PhoneOnlyUi.Advice(assessment, safeFirst = assessment.preselectSafeFirst)
  }

  private fun showSheet(ui: PhoneOnlyUi) {
    show {
      PhoneOnlyContent(
        state = ui,
        onChoose = { chosen += it },
        onSafeguard = { safeguards++ },
        onContinue = { continued += it },
        onClose = {},
        // Dalla selezione della Libreria il cestino di Android e' offerto come dal viewer.
        onTrash = { ids -> trashed += ids },
      )
    }
  }

  private fun button(text: String) = composeRule.onNode(hasText(text) and hasClickAction())

  private fun noButton(text: String) = assertThat(composeRule.onAllNodes(hasText(text) and hasClickAction()).fetchSemanticsNodes()).isEmpty()

  /**
   * Selezione mista: tre al sicuro, due no. Il foglio dice quanti si possono togliere e quanti non sono ancora al sicuro, e offre la scelta del
   * viewer con "Prima mettilo al sicuro" preselezionata (E-D14); niente avviso "Non sono sul NAS" e niente cestino diretto (qualcuno e' al sicuro).
   */
  @Test
  fun i3_selezioneMista_diceQuantiNonSonoAlSicuroEOffreLaSceltaDelViewer() {
    showSheet(selection(PhoneOnlyAdvice.FREE, PhoneOnlyAdvice.FREE, PhoneOnlyAdvice.FREE, PhoneOnlyAdvice.SAFE_FIRST, PhoneOnlyAdvice.SAFE_FIRST))
    composeRule.onNodeWithText("3 si possono togliere", substring = true).assertExists()
    composeRule.onNodeWithText("2 non sono ancora al sicuro sul NAS", substring = true).assertExists()
    composeRule.onNodeWithText("Prima mettilo al sicuro").assertExists()
    composeRule.onNodeWithText("Togli solo quelli già al sicuro").assertExists()
    assertThat(composeRule.onAllNodes(hasText("Non sono sul NAS")).fetchSemanticsNodes()).isEmpty()
    noButton("Elimina dal telefono")
    // Preselezionata: "Metti al sicuro" parte per quelli che non lo sono.
    button("Metti al sicuro").performClick()
    assertThat(safeguards).isEqualTo(1)
    assertThat(trashed).isEmpty()
  }

  /** Scelto "Togli solo quelli gia' al sicuro", "Continua" apre "Libera spazio" con i soli tre al sicuro: gli altri restano sul telefono. */
  @Test
  fun i3_selezioneMista_togliSoloQuelliAlSicuro() {
    val mixed = selection(PhoneOnlyAdvice.FREE, PhoneOnlyAdvice.FREE, PhoneOnlyAdvice.FREE, PhoneOnlyAdvice.SAFE_FIRST, PhoneOnlyAdvice.SAFE_FIRST)
    showSheet(mixed.copy(safeFirst = false))
    button("Continua").performClick()
    assertThat(continued.single().selection).containsExactly(id(1), id(2), id(3))
    assertThat(trashed).isEmpty()
  }

  /** Nessuno al sicuro: l'avviso del viewer al plurale ("Non sono sul NAS"), "Metti al sicuro" e il cestino di Android per tutti. */
  @Test
  fun i3_nessunoAlSicuro_avvisoAlPluraleEIlCestinoPerTutti() {
    showSheet(selection(PhoneOnlyAdvice.SAFE_FIRST, PhoneOnlyAdvice.SAFE_FIRST, PhoneOnlyAdvice.SAFE_FIRST))
    composeRule.onNodeWithText("Non sono sul NAS").assertExists()
    button("Metti al sicuro").assertExists()
    button("Elimina dal telefono").performClick()
    assertThat(trashed).containsExactly(setOf(id(1), id(2), id(3)))
  }
}

// --- Gli screenshot per l'utente (docs/screenshots/beta-grafica/G2/) ---------------------------------------------------------------

/**
 * Il foglio "Prima mettilo al sicuro" aperto dalla selezione della Libreria (5B I3): selezione mista e selezione senza nessun media al sicuro.
 * Chiaro e scuro, telefono e tablet. `gradlew :feature-backup:recordRoborazziDebug --tests "*G2SelectionSheet*"`.
 */
abstract class G2SelectionSheetShotBase(private val dark: Boolean, private val device: String) : BackupComposeBase() {

  private val look: String get() = if (dark) "scuro" else "chiaro"

  private fun shoot(name: String, content: @Composable () -> Unit) {
    show(dark = dark, content = content)
    composeRule.onRoot().captureRoboImage("$DIR/$name-$look-$device.png")
  }

  private fun selection(vararg values: PhoneOnlyAdvice): PhoneOnlyUi.Advice {
    val assessment = PhoneOnlyAssessment(values.withIndex().associate { (i, a) -> FreeSpaceSamples.mediaId(i + 1) to a })
    return PhoneOnlyUi.Advice(assessment, safeFirst = assessment.preselectSafeFirst)
  }

  @Composable
  private fun Sheet(state: PhoneOnlyUi) {
    PhoneOnlySheet(visible = true, state = state, onChoose = {}, onSafeguard = {}, onContinue = {}, onDismiss = {}, onTrash = {})
  }

  @Test
  fun selezioneMista() = shoot("foglio-prima-al-sicuro-selezione-mista") {
    Sheet(selection(PhoneOnlyAdvice.FREE, PhoneOnlyAdvice.FREE, PhoneOnlyAdvice.FREE, PhoneOnlyAdvice.SAFE_FIRST, PhoneOnlyAdvice.SAFE_FIRST))
  }

  @Test
  fun selezioneNessunoAlSicuro() = shoot("foglio-prima-al-sicuro-nessuno-al-sicuro") {
    Sheet(selection(PhoneOnlyAdvice.SAFE_FIRST, PhoneOnlyAdvice.SAFE_FIRST, PhoneOnlyAdvice.SAFE_FIRST))
  }

  companion object {
    const val DIR = "../docs/screenshots/beta-grafica/G2"
    fun looks(): List<Array<Any>> = listOf(arrayOf<Any>(false), arrayOf<Any>(true))
  }
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = PHONE_QUALIFIERS)
class G2SelectionSheetPhoneShots(dark: Boolean) : G2SelectionSheetShotBase(dark, "telefono") {
  companion object {
    @JvmStatic
    @ParameterizedRobolectricTestRunner.Parameters(name = "scuro={0}")
    fun data(): List<Array<Any>> = looks()
  }
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = TABLET_QUALIFIERS)
class G2SelectionSheetTabletShots(dark: Boolean) : G2SelectionSheetShotBase(dark, "tablet") {
  companion object {
    @JvmStatic
    @ParameterizedRobolectricTestRunner.Parameters(name = "scuro={0}")
    fun data(): List<Array<Any>> = looks()
  }
}
