package dev.pampa.mycloudgallery.feature.viewer.menu

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import dev.antigravity.fluidengine.ui.fluid.rememberGlassBackdrop
import dev.pampa.mycloudgallery.core.designsystem.chrome.GalleryMenuPanel
import dev.pampa.mycloudgallery.core.designsystem.chrome.GalleryMenuSurface
import dev.pampa.mycloudgallery.core.designsystem.sheet.GalleryDeleteScope
import dev.pampa.mycloudgallery.core.nasops.testing.NasOpsTestKit
import dev.pampa.mycloudgallery.feature.viewer.ViewerActionHandlers
import dev.pampa.mycloudgallery.feature.viewer.ViewerCapabilities
import dev.pampa.mycloudgallery.feature.viewer.viewerMenuEntries
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Gli screenshot del viewer della 5B (P12): il menu ⋮ con le voci del backup, l'avviso e il foglio di "Condividi" (anche senza posizione e fermo), e
 * "Elimina…" con le versioni precedenti elencate. Chiaro e scuro, telefono e tablet, piu' il testo grande. `gradlew
 * :feature-viewer:recordRoborazziDebug --tests "*BackupActionsScreenshot*"` scrive i PNG in `docs/screenshots/5-P12/` (con il prefisso `viewer-`).
 */
abstract class BackupActionsScreenshotBase(private val dark: Boolean, private val device: String) : NasActionsComposeBase() {

  private val kit = NasOpsTestKit()

  /** Il tablet fotografa solo le schermate principali: un PNG da 2560 px pesa un mega. */
  private fun phoneOnly() = org.junit.Assume.assumeTrue(device == "telefono")

  private val look: String get() = if (dark) "scuro" else "chiaro"

  private fun shoot(name: String, fontScale: Float? = null, content: @Composable () -> Unit) {
    show(dark = dark, fontScale = fontScale) { content() }
    val suffix = if (fontScale != null) "-testo-grande" else ""
    composeRule.onRoot().captureRoboImage("$DIR/viewer-$name-$look-$device$suffix.png")
  }

  /** La foto a tutta pagina dietro: una superficie scura, come il viewer; il foglio la sfoca. */
  @Composable
  private fun Photo() {
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerHighest))
  }

  // --- Il menu ----------------------------------------------------------------------------------------------------------------------

  @Composable
  private fun MenuStand(plan: dev.pampa.mycloudgallery.core.backup.contract.NasVersionPlan?, removeLocation: Boolean) {
    val resources = androidx.compose.ui.platform.LocalContext.current.resources
    val backup = backupMenuEntries(resources, BackupActionsSamples.everything, BackupMenuTarget(plan, isVideo = false), BackupMenuHandlers {})
    val menu = viewerMenuEntries(
      resources,
      ViewerCapabilities(favorite = true, hide = true, share = true, copy = true, trash = false),
      isFavorite = false,
      secure = false,
      handlers = ViewerActionHandlers({}, {}, {}, {}, {}, {}, {}),
      backupActions = backup,
      shareWithoutLocation = removeLocation,
    )
    val backdrop = rememberGlassBackdrop()
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(24.dp), contentAlignment = Alignment.TopEnd) {
      GalleryMenuSurface(backdrop = backdrop) { GalleryMenuPanel(entries = menu) }
    }
  }

  @Test
  fun menuConIlBackup() = shoot("menu-backup") { MenuStand(BackupActionsSamples.plan(), removeLocation = false) }

  @Test
  fun menuConIlBackupSenzaPosizione() {
    phoneOnly()
    shoot("menu-backup-senza-posizione") { MenuStand(BackupActionsSamples.plan(), removeLocation = true) }
  }

  @Test
  fun menuConUnaSolaVersione() {
    phoneOnly()
    shoot("menu-backup-una-versione") { MenuStand(BackupActionsSamples.single(), removeLocation = false) }
  }

  // --- Condividi --------------------------------------------------------------------------------------------------------------------

  @Composable
  private fun NoticeOver(share: BackupShareUi) {
    Photo()
    Box(Modifier.fillMaxSize().statusBarsPadding().padding(top = 64.dp), contentAlignment = Alignment.TopCenter) {
      BackupShareNotice(share, rememberGlassBackdrop(), onCancel = {})
    }
  }

  @Test
  fun condividiPrepara() = shoot("condividi-prepara") { NoticeOver(BackupShareUi.Preparing(2, 3, removingLocation = true)) }

  @Test
  fun condividiPronto() {
    phoneOnly()
    shoot("condividi-pronto") { NoticeOver(BackupShareUi.Ready(verified = 3, direct = 0)) }
  }

  @Composable
  private fun RefusedOver(state: BackupShareUi.Refused) {
    Photo()
    BackupShareRefusedSheet(state = state, onShareWithLocation = {}, onDismiss = {})
  }

  private val withChoice = BackupShareUi.Refused(
    "Questa foto non si può condividere senza posizione: condividila con la posizione o annulla.",
    canShareWithLocation = true,
  )

  @Test
  fun condividiFermo() = shoot("condividi-fermo") { RefusedOver(withChoice) }

  @Test
  fun condividiFermoTestoGrande() {
    phoneOnly()
    shoot("condividi-fermo", NAS_LARGE_TEXT) { RefusedOver(withChoice) }
  }

  @Test
  fun condividiFermoSenzaScelta() {
    phoneOnly()
    shoot("condividi-fermo-nas") {
      RefusedOver(BackupShareUi.Refused("Il NAS non risponde: non posso scaricare il file da condividere. Riprova quando sei a casa.", canShareWithLocation = false))
    }
  }

  // --- Elimina… con le versioni ---------------------------------------------------------------------------------------------------

  private fun deleteState(scope: GalleryDeleteScope): NasActionsState.Delete {
    kit.putFile("test", "Viaggi/IMG_1.jpg", ByteArray(4_812))
    val target = runBlocking { KitNasMediaActions(kit).target(KitNasMediaActions.REF)!! }
    return NasActionsState.Delete(target, scope, plan = null, planning = false)
  }

  @Composable
  private fun DeleteOver(scope: GalleryDeleteScope) {
    Photo()
    val extra = everywhereLocations(EverywhereSummary(previousVersions = 2, commonCopies = 0))
    NasActionSheets(
      state = deleteState(scope),
      monotonicMs = { 0L },
      onScope = {},
      onRenameText = {},
      onPick = {},
      onConfirmed = { _, _ -> },
      onPhoneOnly = {},
      onEverywhereDone = {},
      onDismiss = {},
      extraLocations = extra,
    )
  }

  @Test
  fun eliminaOvunqueConLeVersioni() = shoot("elimina-ovunque-versioni") { DeleteOver(GalleryDeleteScope.Everywhere) }

  @Test
  fun eliminaOvunqueConLeVersioniTestoGrande() {
    phoneOnly()
    shoot("elimina-ovunque-versioni", NAS_LARGE_TEXT) { DeleteOver(GalleryDeleteScope.Everywhere) }
  }

  @Test
  fun eliminaSoloDalTelefonoConLeVersioni() {
    phoneOnly()
    shoot("elimina-solo-telefono-versioni") { DeleteOver(GalleryDeleteScope.PhoneOnly) }
  }

  companion object {
    const val DIR = "../docs/screenshots/5-P12"
    fun looks(): List<Array<Any>> = listOf(arrayOf<Any>(false), arrayOf<Any>(true))
  }
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = NAS_SHOT_PHONE)
class BackupActionsPhoneScreenshotTest(dark: Boolean) : BackupActionsScreenshotBase(dark, "telefono") {
  companion object {
    @JvmStatic
    @ParameterizedRobolectricTestRunner.Parameters(name = "scuro={0}")
    fun data(): List<Array<Any>> = looks()
  }
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = NAS_TABLET)
class BackupActionsTabletScreenshotTest(dark: Boolean) : BackupActionsScreenshotBase(dark, "tablet") {
  companion object {
    @JvmStatic
    @ParameterizedRobolectricTestRunner.Parameters(name = "scuro={0}")
    fun data(): List<Array<Any>> = looks()
  }
}
