package dev.pampa.mycloudgallery.feature.backup

import android.app.Application
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import dev.pampa.mycloudgallery.core.backup.contract.BackupStatus
import dev.pampa.mycloudgallery.core.backup.contract.WaitReason
import dev.pampa.mycloudgallery.feature.backup.page.BackupPageActions
import dev.pampa.mycloudgallery.feature.backup.page.BackupPageScreen
import dev.pampa.mycloudgallery.feature.backup.page.BackupPageUi
import dev.pampa.mycloudgallery.feature.backup.testing.BackupSamples
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * FAB-4 della review generale (pacchetto RG-D): con il risparmio energetico di Android acceso il backup in coda diceva «Aspetta che la batteria sia
 * piu' carica», anche con la batteria piena, e senza la via d'uscita. Ora ha la sua voce: «Risparmio energetico acceso: riprende quando lo spegni.»
 *
 * `gradlew :feature-backup:recordRoborazziDebug --tests "*BackupPowerSaverScreenshot*"` scrive i PNG in `docs/screenshots/beta-grafica/RG-D/`;
 * nei normali `testDebugUnitTest` si verifica solo che la pagina componga e dica la frase giusta.
 */
abstract class BackupPowerSaverShotsBase(private val dark: Boolean, private val device: String) : BackupComposeBase() {

  private val look: String get() = if (dark) "scuro" else "chiaro"

  private fun waiting(reason: WaitReason) = BackupPageUi(
    BackupStatus.Waiting(items = 312, bytes = 1_400_000_000, reason = reason),
    BackupSamples.sources,
    BackupSamples.deviceFolder,
    false,
  )

  @Test
  fun paginaInCodaConIlRisparmioEnergeticoAcceso() {
    show(dark = dark) { BackupPageScreen(state = waiting(WaitReason.POWER_SAVER), actions = BackupPageActions(), onBack = {}) }

    composeRule.onAllNodesWithText("Risparmio energetico acceso: riprende quando lo spegni.", substring = true).onFirst().assertExists()
    // Non e' la batteria bassa: la frase vecchia non c'e' piu'.
    composeRule.onAllNodesWithText("Aspetta che la batteria sia più carica", substring = true).assertCountEquals(0)
    composeRule.onRoot().captureRoboImage("$DIR/pagina-backup-in-coda-risparmio-energetico-$look-$device.png")
  }

  @Test
  fun laBatteriaBassaDiceAncoraLaSuaFrase() {
    show(dark = dark) { BackupPageScreen(state = waiting(WaitReason.BATTERY), actions = BackupPageActions(), onBack = {}) }

    composeRule.onAllNodesWithText("Aspetta che la batteria sia più carica", substring = true).onFirst().assertExists()
    composeRule.onAllNodesWithText("Risparmio energetico", substring = true).assertCountEquals(0)
  }

  companion object {
    const val DIR = "../docs/screenshots/beta-grafica/RG-D"
    fun looks(): List<Array<Any>> = listOf(arrayOf<Any>(false), arrayOf<Any>(true))
  }
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = PHONE_QUALIFIERS)
class BackupPowerSaverPhoneScreenshotTest(dark: Boolean) : BackupPowerSaverShotsBase(dark, "telefono") {
  companion object {
    @JvmStatic
    @ParameterizedRobolectricTestRunner.Parameters(name = "scuro={0}")
    fun data(): List<Array<Any>> = looks()
  }
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = TABLET_QUALIFIERS)
class BackupPowerSaverTabletScreenshotTest(dark: Boolean) : BackupPowerSaverShotsBase(dark, "tablet") {
  companion object {
    @JvmStatic
    @ParameterizedRobolectricTestRunner.Parameters(name = "scuro={0}")
    fun data(): List<Array<Any>> = looks()
  }
}
