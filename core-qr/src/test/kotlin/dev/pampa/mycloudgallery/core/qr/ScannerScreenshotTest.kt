package dev.pampa.mycloudgallery.core.qr

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.activity.ComponentActivity
import dev.pampa.mycloudgallery.core.qr.contract.ScanRejection
import dev.pampa.mycloudgallery.core.qr.scanner.CameraAccess
import dev.pampa.mycloudgallery.core.qr.scanner.ScannerActions
import dev.pampa.mycloudgallery.core.qr.scanner.ScannerContent
import dev.pampa.mycloudgallery.core.qr.scanner.ScannerUi
import dev.pampa.mycloudgallery.core.qr.scanner.TorchUi
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Le schermate dello scanner, una per stato: la richiesta del permesso, il permesso negato (con il campo a
 * mano), "non chiedere piu'" (con le impostazioni), nessuna fotocamera, il mirino (con torcia, con un rifiuto),
 * il codice letto e l'inserimento a mano. Chiaro e scuro, telefono (412 dp), tablet (1280 dp) e testo grande.
 *
 * `gradlew :core-qr:recordRoborazziDebug` scrive i PNG in `docs/screenshots/2-P5/`.
 *
 * Il mirino vero (CameraX) non c'e' in Robolectric: al suo posto una scena finta con un QR storto. Le pagine,
 * invece, sono quelle vere (`ScannerContent`), pilotate da un `ScannerUi`.
 */
internal abstract class ScannerScreenshotBase(private val dark: Boolean, private val device: String, private val largeText: Boolean) {

  @get:Rule
  val rule = createAndroidComposeRule<ComponentActivity>()

  private fun shoot(name: String, ui: ScannerUi) {
    rule.shoot(
      path = P5Shots.path(name, dark, device, largeText),
      dark = dark,
      fontScale = if (largeText) P5Shots.LARGE_TEXT else null,
    ) {
      ScannerContent(ui = ui, actions = ScannerActions(), feed = { FakeCameraFeed() })
    }
  }

  @Test
  fun permessoRichiesta() = shoot("scanner-permesso-richiesta", ScannerUi(access = CameraAccess.NotAsked))

  @Test
  fun permessoNegato() = shoot("scanner-permesso-negato", ScannerUi(access = CameraAccess.Denied))

  @Test
  fun permessoBloccato() = shoot(
    "scanner-permesso-bloccato",
    ScannerUi(access = CameraAccess.DeniedPermanently, manualText = "MCG1:Qj9Wk2LpZ0aTci"),
  )

  @Test
  fun senzaFotocamera() = shoot("scanner-senza-fotocamera", ScannerUi(access = CameraAccess.NoCamera))

  @Test
  fun mirino() = shoot(
    "scanner-mirino",
    ScannerUi(access = CameraAccess.Granted, torch = TorchUi(available = true, on = false)),
  )

  @Test
  fun mirinoConRifiuto() = shoot(
    "scanner-mirino-rifiutato",
    ScannerUi(
      access = CameraAccess.Granted,
      rejection = ScanRejection.WIREGUARD_SECRET,
      torch = TorchUi(available = true, on = true),
    ),
  )

  @Test
  fun letto() = shoot("scanner-letto", ScannerUi(access = CameraAccess.Granted, read = true))

  @Test
  fun inserimentoAMano() = shoot(
    "scanner-manuale",
    ScannerUi(access = CameraAccess.Granted, manualOpen = true, manualText = "MCG1:Qj9Wk2LpZ0aTciaoXyZ"),
  )

  @Test
  fun inserimentoAManoConErrore() = shoot(
    "scanner-manuale-errore",
    ScannerUi(
      access = CameraAccess.Denied,
      manualText = "MCG1:Qj9Wk2LpZ0aTciaoXyZ",
      rejection = ScanRejection.OTHER_FAMILY,
    ),
  )

  @Test
  fun inserimentoAManoLetto() = shoot(
    "scanner-manuale-letto",
    ScannerUi(access = CameraAccess.NoCamera, manualText = "MCG1:Qj9Wk2LpZ0aTciaoXyZ", read = true),
  )

}

@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h915dp-xhdpi")
internal class ScannerPhoneScreenshotTest(dark: Boolean) :
  ScannerScreenshotBase(dark, P5Shots.PHONE, largeText = false) {
  companion object {
    @JvmStatic
    @ParameterizedRobolectricTestRunner.Parameters(name = "scuro={0}")
    fun data(): List<Array<Any>> = P5Shots.looks()
  }
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w1280dp-h800dp-land-xhdpi")
internal class ScannerTabletScreenshotTest(dark: Boolean) :
  ScannerScreenshotBase(dark, P5Shots.TABLET, largeText = false) {
  companion object {
    @JvmStatic
    @ParameterizedRobolectricTestRunner.Parameters(name = "scuro={0}")
    fun data(): List<Array<Any>> = P5Shots.looks()
  }
}

/** Testo al massimo sul telefono, su una finestra piu' alta (1400 dp) cosi' la pagina si vede intera senza scorrere. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h1400dp-xhdpi")
internal class ScannerPhoneLargeTextScreenshotTest(dark: Boolean) :
  ScannerScreenshotBase(dark, P5Shots.PHONE, largeText = true) {
  companion object {
    @JvmStatic
    @ParameterizedRobolectricTestRunner.Parameters(name = "scuro={0}")
    fun data(): List<Array<Any>> = P5Shots.looks()
  }
}
