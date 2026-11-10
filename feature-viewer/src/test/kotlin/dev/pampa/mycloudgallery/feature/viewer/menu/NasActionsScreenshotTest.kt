package dev.pampa.mycloudgallery.feature.viewer.menu

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import dev.antigravity.fluidengine.ui.fluid.rememberGlassBackdrop
import dev.pampa.mycloudgallery.core.designsystem.chrome.GalleryMenuEntry
import dev.pampa.mycloudgallery.core.designsystem.chrome.GalleryMenuPanel
import dev.pampa.mycloudgallery.core.designsystem.chrome.GalleryMenuSurface
import dev.pampa.mycloudgallery.core.designsystem.sheet.GalleryDeleteScope
import dev.pampa.mycloudgallery.core.nasops.contract.OpPlan
import dev.pampa.mycloudgallery.core.nasops.contract.OpRequest
import dev.pampa.mycloudgallery.core.nasops.contract.PlanOutcome
import dev.pampa.mycloudgallery.core.nasops.testing.NasOpsTestKit
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Gli screenshot delle voci del NAS nel viewer (P14): il menu ⋮ con "Sposta", "Rinomina" ed "Elimina…" (e spento, con la
 * spiegazione, in sola lettura), e i fogli di "Elimina…" con "Solo dal telefono / Ovunque", "Rinomina" e "Sposta" nell'area comune.
 * Chiaro e scuro, telefono e tablet, piu' il testo grande. `gradlew :feature-viewer:recordRoborazziDebug --tests "*NasActionsScreenshot*"`
 * scrive i PNG in `docs/screenshots/4-P14/` (con il prefisso `viewer-`).
 */
abstract class NasActionsScreenshotBase(private val dark: Boolean, private val device: String) : NasActionsComposeBase() {

  private val kit = NasOpsTestKit()
  private val look: String get() = if (dark) "scuro" else "chiaro"

  private fun target(onPhone: Boolean = true, writable: Boolean = true, video: Boolean = false, others: List<NasLocationInfo> = emptyList()): NasActionTarget {
    kit.putFile("test", "Viaggi/IMG_1.jpg", ByteArray(4_812))
    kit.putDir("test", "Archivio")
    kit.putDir("test-comune", "Famiglia")
    return runBlocking { KitNasMediaActions(kit, onPhone = onPhone, writable = writable, isVideo = video, others = others).target(KitNasMediaActions.REF)!! }
  }

  private fun plan(request: OpRequest): OpPlan = (runBlocking { kit.operations.plan(request) } as PlanOutcome.Ready).plan

  private fun shoot(name: String, fontScale: Float? = null, content: @Composable () -> Unit) {
    show(dark = dark, fontScale = fontScale) { content() }
    val suffix = if (fontScale != null) "-testo-grande" else ""
    composeRule.onRoot().captureRoboImage("$DIR/viewer-$name-$look-$device$suffix.png")
  }

  @Composable
  private fun Sheets(state: NasActionsState) {
    // Il viewer di sfondo (una pagina scura come una foto a tutta pagina): il foglio la sfoca.
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerHighest))
    NasActionSheets(
      state = state, monotonicMs = { 0L }, onScope = {}, onRenameText = {}, onPick = {}, onConfirmed = { _, _ -> },
      onPhoneOnly = {}, onEverywhereDone = {}, onDismiss = {},
    )
  }

  @Composable
  private fun MenuStand(readOnly: Boolean) {
    val resources = LocalContext.current.resources
    val target = target(writable = !readOnly)
    val menu = nasMenuEntries(resources, NasActionCapabilities.of(target), NasActionHandlers({}, {}, {}))
    val backdrop = rememberGlassBackdrop()
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(24.dp), contentAlignment = Alignment.TopEnd) {
      GalleryMenuSurface(backdrop = backdrop) {
        GalleryMenuPanel(entries = menu.actions + listOf(GalleryMenuEntry.GroupBreak) + menu.delete)
      }
    }
  }

  // --- Il menu ---------------------------------------------------------------------------------------------------------------

  @Test
  fun menu() = shoot("menu") { MenuStand(readOnly = false) }

  @Test
  fun menuInSolaLettura() = shoot("menu-sola-lettura") { MenuStand(readOnly = true) }

  // --- Elimina… ------------------------------------------------------------------------------------------------------------

  @Test
  fun eliminaSoloDalTelefono() {
    val t = target()
    shoot("elimina-solo-telefono") { Sheets(NasActionsState.Delete(t, GalleryDeleteScope.PhoneOnly, plan(OpRequest.Trash(listOf(t.item))), planning = false)) }
  }

  @Test
  fun eliminaOvunque() {
    val t = target()
    shoot("elimina-ovunque") { Sheets(NasActionsState.Delete(t, GalleryDeleteScope.Everywhere, plan(OpRequest.Trash(listOf(t.item))), planning = false)) }
  }

  @Test
  fun eliminaOvunqueTestoGrande() {
    val t = target()
    shoot("elimina-ovunque", fontScale = NAS_LARGE_TEXT) { Sheets(NasActionsState.Delete(t, GalleryDeleteScope.Everywhere, plan(OpRequest.Trash(listOf(t.item))), planning = false)) }
  }

  @Test
  fun eliminaConUnaCopiaInSolaLettura() {
    val t = target(others = listOf(NasLocationInfo(NasLocationInfo.Kind.NAS_COMMON, writable = false)))
    shoot("elimina-copia-sola-lettura") { Sheets(NasActionsState.Delete(t, GalleryDeleteScope.Everywhere, plan(OpRequest.Trash(listOf(t.item))), planning = false)) }
  }

  @Test
  fun eliminaSoloSulNas() {
    val t = target(onPhone = false, video = true)
    shoot("elimina-solo-nas") { Sheets(NasActionsState.Delete(t, GalleryDeleteScope.Everywhere, plan(OpRequest.Trash(listOf(t.item))), planning = false)) }
  }

  // --- Rinomina ------------------------------------------------------------------------------------------------------------

  @Test
  fun rinomina() {
    val t = target()
    shoot("rinomina") { Sheets(NasActionsState.Rename(t, "Tramonto sul fiordo.jpg", RenameCheck.Ready(plan(OpRequest.Rename(t.item, "Tramonto sul fiordo.jpg"))))) }
  }

  @Test
  fun rinominaNomeNonValido() {
    val t = target()
    shoot("rinomina-non-valido") { Sheets(NasActionsState.Rename(t, "Oslo: giorno 1 ", RenameCheck.Invalid("Oslo_ giorno 1"))) }
  }

  // --- Sposta --------------------------------------------------------------------------------------------------------------

  @Test
  fun spostaScelta() {
    val t = target()
    shoot("sposta-scelta") { Sheets(NasActionsState.PickDestination(t)) }
  }

  @Test
  fun spostaNellAreaComune() {
    val t = target()
    val common = t.destinations.first { it.scope == NasAreaScope.COMMON }
    shoot("sposta-area-comune") { Sheets(NasActionsState.ConfirmMove(t, common, plan(OpRequest.Move(listOf(t.item), common.share, common.folder)))) }
  }

  @Test
  fun spostaNellAreaComuneTestoGrande() {
    val t = target()
    val common = t.destinations.first { it.scope == NasAreaScope.COMMON }
    shoot("sposta-area-comune", fontScale = NAS_LARGE_TEXT) { Sheets(NasActionsState.ConfirmMove(t, common, plan(OpRequest.Move(listOf(t.item), common.share, common.folder)))) }
  }

  @Test
  fun spostaNellaPropriaArea() {
    val t = target()
    val mine = t.destinations.first { it.scope == NasAreaScope.PRIVATE }
    shoot("sposta-propria-area") { Sheets(NasActionsState.ConfirmMove(t, mine, plan(OpRequest.Move(listOf(t.item), mine.share, mine.folder)))) }
  }

  companion object {
    const val DIR = "../docs/screenshots/4-P14"
    fun looks(): List<Array<Any>> = listOf(arrayOf<Any>(false), arrayOf<Any>(true))
  }
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = NAS_SHOT_PHONE)
class NasActionsPhoneScreenshotTest(dark: Boolean) : NasActionsScreenshotBase(dark, "telefono") {
  companion object {
    @JvmStatic
    @ParameterizedRobolectricTestRunner.Parameters(name = "scuro={0}")
    fun data(): List<Array<Any>> = looks()
  }
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = NAS_TABLET)
class NasActionsTabletScreenshotTest(dark: Boolean) : NasActionsScreenshotBase(dark, "tablet") {
  companion object {
    @JvmStatic
    @ParameterizedRobolectricTestRunner.Parameters(name = "scuro={0}")
    fun data(): List<Array<Any>> = looks()
  }
}
