package dev.pampa.mycloudgallery.core.designsystem.catalog

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Info
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import com.github.takahirom.roborazzi.captureRoboImage
import dev.antigravity.fluidengine.ui.fluid.FluidGlassModalHost
import dev.antigravity.fluidengine.ui.fluid.LocalFluidGlassModalHostState
import dev.antigravity.fluidengine.ui.fluid.glassBackdropSource
import dev.antigravity.fluidengine.ui.fluid.rememberFluidGlassModalHostState
import dev.antigravity.fluidengine.ui.fluid.rememberGlassBackdrop
import dev.pampa.mycloudgallery.core.designsystem.chrome.GalleryBarButton
import dev.pampa.mycloudgallery.core.designsystem.chrome.GalleryMenuButton
import dev.pampa.mycloudgallery.core.designsystem.chrome.GalleryNavigationIcon
import dev.pampa.mycloudgallery.core.designsystem.chrome.GalleryTopBar
import dev.pampa.mycloudgallery.core.designsystem.sheet.GalleryDeleteLocation
import dev.pampa.mycloudgallery.core.designsystem.sheet.GalleryDeleteLocationKind
import dev.pampa.mycloudgallery.core.designsystem.sheet.GalleryDeleteScope
import dev.pampa.mycloudgallery.core.designsystem.sheet.GalleryDeleteSheet
import dev.pampa.mycloudgallery.core.designsystem.sheet.GalleryDeviceApprovalSheet
import dev.pampa.mycloudgallery.core.designsystem.sheet.GalleryDeviceRequest
import dev.pampa.mycloudgallery.core.designsystem.sheet.GalleryMobileDataSheet
import dev.pampa.mycloudgallery.core.designsystem.sheet.GalleryShareSheet
import dev.pampa.mycloudgallery.core.designsystem.theme.MyCloudGalleryTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Quale foglio vero si apre. */
internal enum class RealSheet(val slug: String) {
  Delete("elimina"),
  Share("condividi"),
  MobileData("dati-mobili"),
  Device("nuovo-dispositivo"),
}

/**
 * I pop-up **veri**, non le anteprime del catalogo: un `FluidGlassModalHost` alla radice e il foglio
 * (o il menu ⋮) aperto dal portale dell'engine, con il vetro che rifrange la pagina. Servono a
 * confrontare cio' che l'utente vedra' con le anteprime statiche, e a provare che il percorso
 * `GalleryConfirmSheet` -> portale -> host compone davvero.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h915dp-xhdpi")
internal class RealSheetScreenshotTest(
  private val sheet: RealSheet,
  private val look: CatalogLook,
) {

  @get:Rule
  val composeRule = createComposeRule()

  @Composable
  private fun Stage(content: @Composable () -> Unit) {
    val host = rememberFluidGlassModalHostState()
    val backdrop = rememberGlassBackdrop()
    CompositionLocalProvider(LocalFluidGlassModalHostState provides host) {
      Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().glassBackdropSource(backdrop)) {
          CatalogStaticGrid(columns = 5, rows = 12, firstSeed = 60, modifier = Modifier.fillMaxSize())
        }
        content()
        FluidGlassModalHost(state = host, backdrop = backdrop)
      }
    }
  }

  @Test
  fun capture() {
    composeRule.setContent {
      MyCloudGalleryTheme(settings = look.settings()) {
        Stage {
          when (sheet) {
            RealSheet.Delete -> GalleryDeleteSheet(
              visible = true,
              count = 12,
              scope = GalleryDeleteScope.PhoneOnly,
              onScopeChange = {},
              locations = listOf(
                GalleryDeleteLocation("Telefono", GalleryDeleteLocationKind.Phone),
                GalleryDeleteLocation("NAS · Area privata", GalleryDeleteLocationKind.NasPrivate),
                GalleryDeleteLocation("NAS · Area comune", GalleryDeleteLocationKind.NasShared, deletable = false),
              ),
              onConfirm = {},
              onDismissRequest = {},
            )
            RealSheet.Share -> GalleryShareSheet(
              visible = true,
              count = 12,
              onConfirm = {},
              onDismissRequest = {},
              albumName = "Oslo 2026",
              permissionLabel = "Gli altri aggiungono soltanto",
            )
            RealSheet.MobileData -> GalleryMobileDataSheet(
              visible = true,
              estimate = "1,2 GB",
              onConfirm = {},
              onWaitForWifi = {},
              onDismissRequest = {},
              budgetLabel = "Ti restano 3,8 GB del tetto mensile di 5 GB",
              budgetUsedFraction = 0.24f,
            )
            RealSheet.Device -> GalleryDeviceApprovalSheet(
              visible = true,
              request = GalleryDeviceRequest(
                deviceName = "Tablet di casa",
                profileName = "Mamma",
                model = "Galaxy Tab S10",
                requestedAgo = "2 minuti fa",
                fingerprint = "4F3A 91C2 7BD0 E5A8",
              ),
              onApprove = {},
              onReject = {},
              onDismissRequest = {},
            )
          }
        }
      }
    }
    composeRule.waitForIdle()
    composeRule.onRoot().captureRoboImage(CatalogShots.path("foglio-vero-${sheet.slug}-${look.slug}-telefono"))
  }

  companion object {
    @JvmStatic
    @ParameterizedRobolectricTestRunner.Parameters(name = "{0} {1}")
    fun data(): List<Array<Any>> =
      RealSheet.entries.flatMap { sheet -> CatalogLook.all.map { look -> arrayOf<Any>(sheet, look) } }
  }
}

/** Il menu ⋮ vero: si tocca il tasto e il pop-up dell'engine cresce da li'. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h915dp-xhdpi")
internal class RealMenuScreenshotTest(private val look: CatalogLook) {

  @get:Rule
  val composeRule = createComposeRule()

  @Test
  fun menuAperto() {
    composeRule.setContent {
      MyCloudGalleryTheme(settings = look.settings()) {
        val host = rememberFluidGlassModalHostState()
        val backdrop = rememberGlassBackdrop()
        // Il fondale del pop-up e' la pagina FINITA, barra compresa (come in un'app: la chrome la
        // rifrange l'host); quello della barra e' solo la foto, o si campionerebbe da sola.
        val pageBackdrop = rememberGlassBackdrop()
        CompositionLocalProvider(LocalFluidGlassModalHostState provides host) {
          Box(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().glassBackdropSource(pageBackdrop)) {
              Box(Modifier.fillMaxSize().glassBackdropSource(backdrop)) {
                SamplePhoto(SampleScene.Meadow, Modifier.fillMaxSize())
              }
            GalleryTopBar(
              title = "12 marzo 2024",
              subtitle = "12:41 · Oslo",
              backdrop = backdrop,
              modifier = Modifier.align(Alignment.TopCenter),
              onPhoto = true,
              onNavigate = {},
              navigationIcon = GalleryNavigationIcon.Back,
              actions = {
                GalleryBarButton(icon = Icons.Rounded.Info, contentDescription = "Informazioni", onClick = {})
                GalleryMenuButton(entries = sampleViewerMenu(), backdrop = backdrop)
              },
            )
            }
            FluidGlassModalHost(state = host, backdrop = pageBackdrop)
          }
        }
      }
    }
    composeRule.waitForIdle()
    composeRule.onNodeWithContentDescription("Altre azioni").performClick()
    composeRule.waitForIdle()
    composeRule.onRoot().captureRoboImage(CatalogShots.path("menu-vero-${look.slug}-telefono"))
  }

  companion object {
    @JvmStatic
    @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
    fun data(): List<Array<Any>> = CatalogLook.all.map { arrayOf<Any>(it) }
  }
}
