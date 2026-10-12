package dev.pampa.mycloudgallery.core.qr

import android.Manifest
import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.core.app.ActivityOptionsCompat
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import dev.antigravity.fluidengine.foundation.EngineSettings
import dev.pampa.mycloudgallery.core.common.log.GalleryLog
import dev.pampa.mycloudgallery.core.designsystem.theme.MyCloudGalleryTheme
import dev.pampa.mycloudgallery.core.qr.contract.ScanRejection
import dev.pampa.mycloudgallery.core.qr.contract.ScanVerdict
import dev.pampa.mycloudgallery.core.qr.scanner.message
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * `QrScanner` intero, con un registro dei risultati finto al posto del sistema: il percorso del permesso
 * (prima richiesta, negato, "non chiedere piu'", ritorno dalle impostazioni), il ripiego a mano, i verdetti
 * e la protezione della finestra. Robolectric non ha fotocamere: una fotocamera che non parte e' proprio il
 * caso da provare (ripiego sul campo a mano).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h915dp-xhdpi")
class QrScannerTest {

  @get:Rule
  val rule = createAndroidComposeRule<ComponentActivity>()

  private val app: Application get() = ApplicationProvider.getApplicationContext()

  private val accepted = mutableListOf<String>()
  private var verdict: (String) -> ScanVerdict = { ScanVerdict.Accept }
  private var cancelled = 0
  private val logs = mutableListOf<String>()

  /** Il registro che risponde al posto del sistema: ogni richiesta del permesso riceve [answer]. */
  private inner class FakeRegistry : ActivityResultRegistry() {
    var launched = 0
    var answer = false
    val owner = object : ActivityResultRegistryOwner {
      override val activityResultRegistry: ActivityResultRegistry get() = this@FakeRegistry
    }

    override fun <I : Any?, O : Any?> onLaunch(
      requestCode: Int,
      contract: ActivityResultContract<I, O>,
      input: I,
      options: ActivityOptionsCompat?,
    ) {
      launched++
      if (answer) shadowOf(app).grantPermissions(Manifest.permission.CAMERA)
      dispatchResult(requestCode, answer)
    }
  }

  private val registry = FakeRegistry()

  @Before
  fun setUp() {
    shadowOf(app).grantPermissions("android.permission.HIDE_OVERLAY_WINDOWS")
    GalleryLog.install { _, _, message, _ -> logs += message }
  }

  @After
  fun tearDown() {
    GalleryLog.uninstall()
  }

  private fun str(id: Int): String = rule.activity.getString(id)

  private fun withCamera(rationaleAfterDenial: Boolean = true) {
    shadowOf(app.packageManager).setSystemFeature(PackageManager.FEATURE_CAMERA_ANY, true)
    shadowOf(app.packageManager).setShouldShowRequestPermissionRationale(Manifest.permission.CAMERA, rationaleAfterDenial)
  }

  private fun showScanner() {
    rule.setContent {
      MyCloudGalleryTheme(settings = EngineSettings()) {
        CompositionLocalProvider(LocalActivityResultRegistryOwner provides registry.owner) {
          QrScanner(
            accept = { text ->
              accepted += text
              verdict(text)
            },
            onCancel = { cancelled++ },
          )
        }
      }
    }
    rule.waitForIdle()
  }

  private fun click(textRes: Int) {
    rule.onNodeWithText(str(textRes)).performClick()
    rule.waitForIdle()
  }

  private fun type(text: String) {
    rule.onNode(hasSetTextAction()).performTextInput(text)
    rule.waitForIdle()
  }

  private fun assertShown(textRes: Int) = rule.onNodeWithText(str(textRes)).assertIsDisplayed()

  private fun assertAbsent(textRes: Int) = rule.onNodeWithText(str(textRes)).assertDoesNotExist()

  // --- senza fotocamera: il campo a mano -------------------------------------------------------------------------

  @Test
  fun senzaFotocamera_siVedeSubitoIlCampoAMano_enonSiChiedeNiente() {
    showScanner()
    assertShown(R.string.qr_no_camera_title)
    rule.onNode(hasSetTextAction()).assertIsDisplayed()
    assertThat(registry.launched).isEqualTo(0)
    assertThat(logs.filter { it.contains("fotocamera non avviata") }).isEmpty()
  }

  @Test
  fun aMano_ilCodiceArrivaSenzaSpaziEACapo_eAccettatoFermaLoScanner() {
    showScanner()
    rule.onNodeWithText(str(R.string.qr_manual_submit)).assertIsNotEnabled()

    type("  MCG1:ABC123\n")
    rule.onNodeWithText(str(R.string.qr_manual_submit)).assertIsEnabled()
    click(R.string.qr_manual_submit)

    assertThat(accepted).containsExactly("MCG1:ABC123")
    assertShown(R.string.qr_read_done)
    assertAbsent(R.string.qr_manual_submit) // finito: niente piu' bottoni
    assertAbsent(R.string.qr_cancel)
  }

  @Test
  fun aLetturaAvvenuta_unaRegioneVivaAnnuncia_codiceLetto() {
    showScanner()
    type("MCG1:ABC")
    click(R.string.qr_manual_submit)

    val live = SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite)
    rule.onNode(live).assertIsDisplayed()
    val node = rule.onNode(live).fetchSemanticsNode()
    val text = node.config[SemanticsProperties.Text].joinToString { it.text }
    assertThat(text).isEqualTo(str(R.string.qr_read_done))
  }

  @Test
  fun rifiutato_siMostraIlMotivo_esiPuoRiprovare() {
    verdict = { ScanVerdict.Reject(ScanRejection.WIREGUARD_SECRET) }
    showScanner()
    type("WG-CONFIG")
    click(R.string.qr_manual_submit)

    assertShown(R.string.qr_reject_wireguard)
    assertAbsent(R.string.qr_read_done)
    rule.onNodeWithText(str(R.string.qr_manual_submit)).assertIsEnabled() // si puo' correggere e riprovare

    verdict = { ScanVerdict.Accept }
    type("-2")
    click(R.string.qr_manual_submit)
    assertThat(accepted).containsExactly("WG-CONFIG", "WG-CONFIG-2").inOrder()
    assertShown(R.string.qr_read_done)
    assertAbsent(R.string.qr_reject_wireguard)
  }

  @Test
  fun ognunoDeiMotiviHaIlSuoTesto() {
    val messages = ScanRejection.entries.map { rule.activity.getString(it.message()) }
    assertThat(messages).containsNoneIn(listOf(""))
    assertThat(messages.toSet()).hasSize(ScanRejection.entries.size)
  }

  @Test
  fun ignora_nonMostraNienteEIlCampoResta() {
    verdict = { ScanVerdict.Ignore }
    showScanner()
    type("MCG1:UGUALE")
    click(R.string.qr_manual_submit)
    assertThat(accepted).containsExactly("MCG1:UGUALE")
    for (reason in ScanRejection.entries) assertAbsent(reason.message())
    assertAbsent(R.string.qr_read_done)
  }

  @Test
  fun unTestoOltreIlTetto_ENotOurs_enonArrivaAChiDecide() {
    showScanner()
    type("a".repeat(QrLimits.MAX_CHARS + 1))
    click(R.string.qr_manual_submit)
    assertShown(R.string.qr_reject_not_ours)
    assertThat(accepted).isEmpty()
  }

  @Test
  fun annulla_chiamaOnCancel() {
    showScanner()
    click(R.string.qr_cancel)
    assertThat(cancelled).isEqualTo(1)
  }

  @Test
  fun ilTestoDigitatoNonSiSalvaConLoStatoDellIstanza() {
    val tester = StateRestorationTester(rule)
    tester.setContent {
      MyCloudGalleryTheme(settings = EngineSettings()) {
        CompositionLocalProvider(LocalActivityResultRegistryOwner provides registry.owner) {
          QrScanner(accept = { ScanVerdict.Accept }, onCancel = {})
        }
      }
    }
    rule.waitForIdle()
    type("MCG1:SEGRETO")
    tester.emulateSavedInstanceStateRestore()
    rule.waitForIdle()
    // il campo e' tornato vuoto: l'invito non finisce in un Bundle
    val field = rule.onNode(hasSetTextAction()).fetchSemanticsNode()
    assertThat(field.config[SemanticsProperties.EditableText].text).isEmpty()
  }

  // --- finestra protetta -------------------------------------------------------------------------------------------

  private fun secure() = (rule.activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE) != 0

  @Test
  fun finestraProtettaFincheLoScannerEAperto() {
    showScanner()
    assertThat(secure()).isTrue()
    assertThat(rule.activity.window.decorView.filterTouchesWhenObscured).isTrue()
  }

  // --- permesso della fotocamera ---------------------------------------------------------------------------------

  @Test
  fun primaRichiesta_siSpiegaPerche_enonSiChiedeSenzaIlGesto() {
    withCamera()
    showScanner()
    assertShown(R.string.qr_perm_ask_title)
    assertShown(R.string.qr_perm_ask_detail)
    assertShown(R.string.qr_perm_ask_allow)
    assertShown(R.string.qr_manual_open)
    rule.onAllNodes(hasSetTextAction()).assertCountEquals(0)
    assertThat(registry.launched).isEqualTo(0)
  }

  @Test
  fun primaRichiesta_inserisciAMano_apreIlCampo_etornaIndietro() {
    withCamera()
    showScanner()
    click(R.string.qr_manual_open)
    assertShown(R.string.qr_manual_title)
    rule.onNode(hasSetTextAction()).assertIsDisplayed()
    assertThat(registry.launched).isEqualTo(0)

    click(R.string.qr_manual_back_to_camera)
    assertShown(R.string.qr_perm_ask_title)
  }

  @Test
  fun negato_siSpiegaSiOffreDiRiprovare_eCeIlCampoAMano() {
    withCamera(rationaleAfterDenial = true)
    registry.answer = false
    showScanner()
    click(R.string.qr_perm_ask_allow)

    assertThat(registry.launched).isEqualTo(1)
    assertShown(R.string.qr_perm_denied_title)
    assertShown(R.string.qr_perm_denied_retry)
    assertAbsent(R.string.qr_perm_blocked_settings)
    rule.onNode(hasSetTextAction()).assertIsDisplayed()

    // il ripiego funziona
    type("MCG1:A MANO")
    click(R.string.qr_manual_submit)
    assertThat(accepted).containsExactly("MCG1:A MANO")
  }

  @Test
  fun negato_riprova_richiedeDiNuovo() {
    withCamera(rationaleAfterDenial = true)
    registry.answer = false
    showScanner()
    click(R.string.qr_perm_ask_allow)
    click(R.string.qr_perm_denied_retry)
    assertThat(registry.launched).isEqualTo(2)
    assertShown(R.string.qr_perm_denied_title)
  }

  @Test
  fun nonChiedereCiu_siOffrono_leImpostazioni_enonUnaDomandaCheNonCompare() {
    withCamera(rationaleAfterDenial = false)
    registry.answer = false
    showScanner()
    click(R.string.qr_perm_ask_allow)

    assertShown(R.string.qr_perm_blocked_title)
    assertShown(R.string.qr_perm_blocked_settings)
    assertAbsent(R.string.qr_perm_denied_retry)
    assertAbsent(R.string.qr_perm_ask_allow)
    rule.onNode(hasSetTextAction()).assertIsDisplayed()

    click(R.string.qr_perm_blocked_settings)
    val started = shadowOf(app).nextStartedActivity
    assertThat(started.action).isEqualTo(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
    assertThat(started.data).isEqualTo(Uri.fromParts("package", app.packageName, null))
    assertThat(started.flags and Intent.FLAG_ACTIVITY_NEW_TASK).isEqualTo(0) // parte dall'activity
    assertThat(registry.launched).isEqualTo(1)
  }

  @Test
  fun alRitornoDalleImpostazioni_ilPermessoSbloccato_accendeLaFotocamera() {
    withCamera(rationaleAfterDenial = false)
    registry.answer = false
    showScanner()
    click(R.string.qr_perm_ask_allow)
    assertShown(R.string.qr_perm_blocked_title)

    // l'utente va nelle impostazioni, consente la fotocamera e torna all'app
    shadowOf(app).grantPermissions(Manifest.permission.CAMERA)
    rule.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
    rule.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
    rule.waitForIdle()

    // la schermata del blocco e' sparita: lo scanner ha riletto il permesso e provato ad aprire la fotocamera,
    // che in Robolectric non c'e' e quindi ripiega sul campo (con il suo motivo nel log)
    assertAbsent(R.string.qr_perm_blocked_title)
    assertAbsent(R.string.qr_perm_blocked_settings)
    rule.waitUntil(timeoutMillis = 5_000) { logs.any { it.contains("fotocamera non avviata") } }
    rule.waitForIdle()
    assertShown(R.string.qr_no_camera_title)
  }

  @Test
  fun permessoGiaConcesso_siApreLaFotocamera_esenzaPartireRipiegaSulCampo() {
    withCamera()
    shadowOf(app).grantPermissions(Manifest.permission.CAMERA)
    showScanner()
    assertAbsent(R.string.qr_perm_ask_title)
    assertThat(registry.launched).isEqualTo(0)
    rule.waitUntil(timeoutMillis = 5_000) { logs.any { it.contains("fotocamera non avviata") } }
    rule.waitForIdle()
    assertShown(R.string.qr_no_camera_title)
    rule.onNode(hasSetTextAction()).assertIsDisplayed()
  }
}
