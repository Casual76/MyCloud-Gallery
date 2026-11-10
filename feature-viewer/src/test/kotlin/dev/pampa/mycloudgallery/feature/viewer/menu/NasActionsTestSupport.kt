package dev.pampa.mycloudgallery.feature.viewer.menu

import android.app.Application
import android.content.pm.ActivityInfo
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import dev.antigravity.fluidengine.foundation.EngineSettings
import dev.antigravity.fluidengine.foundation.ThemeMode
import dev.antigravity.fluidengine.ui.fluid.FluidGlassModalHost
import dev.antigravity.fluidengine.ui.fluid.LocalFluidGlassModalHostState
import dev.antigravity.fluidengine.ui.fluid.glassBackdropSource
import dev.antigravity.fluidengine.ui.fluid.rememberFluidGlassModalHostState
import dev.antigravity.fluidengine.ui.fluid.rememberGlassBackdrop
import dev.pampa.mycloudgallery.core.designsystem.theme.MyCloudGalleryTheme
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaRef
import dev.pampa.mycloudgallery.core.nasops.contract.NasOperations
import dev.pampa.mycloudgallery.core.nasops.testing.NasOpsTestKit
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import org.junit.Rule
import org.junit.rules.RuleChain
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

const val NAS_PHONE = "w412dp-h915dp-xhdpi"

/** Il telefono degli screenshot per l'utente (K4): il suo Galaxy S25, 360 x 780 dp. Non 412 dp, dove entrano piu' cose che sul suo schermo. */
const val NAS_SHOT_PHONE = "w360dp-h780dp-xhdpi"
const val NAS_TABLET = "w1280dp-h800dp-land-xhdpi"
const val NAS_LARGE_TEXT = 1.3f

/** Un [NasMediaActions] sopra il NAS finto di core-nasops: risolve un media del NAS nel file di prova e nelle sue destinazioni. */
class KitNasMediaActions(
  val kit: NasOpsTestKit,
  private val share: String = "test",
  private val path: String = "Viaggi/IMG_1.jpg",
  var onPhone: Boolean = true,
  var isVideo: Boolean = false,
  var writable: Boolean = true,
  var destinations: List<NasMoveDestination> = defaultDestinations(),
  var others: List<NasLocationInfo> = emptyList(),
  /** P16 (E-D1): il bersaglio per un media mostrato dalla copia del telefono, se ne ha anche sul NAS. */
  var phoneCopy: (suspend (MediaId) -> NasActionTarget?)? = null,
) : NasMediaActions {

  override suspend fun targetForPhoneCopy(id: MediaId): NasActionTarget? = phoneCopy?.invoke(id)


  override val operations: NasOperations get() = kit.operations

  override fun monotonicMs(): Long = kit.clocks.monotonicMs()

  override suspend fun target(ref: MediaRef.Nas): NasActionTarget? = NasActionTarget(
    item = kit.itemOf(share, path),
    scope = if (share == NasOpsTestKit.COMMON_SHARE) NasAreaScope.COMMON else NasAreaScope.PRIVATE,
    writable = writable,
    onPhone = onPhone,
    isVideo = isVideo,
    others = others,
    destinations = destinations,
  )

  companion object {
    fun defaultDestinations(): List<NasMoveDestination> = listOf(
      NasMoveDestination("test-comune", SharePath.of("Famiglia"), NasAreaScope.COMMON, "Area comune · Famiglia"),
      NasMoveDestination("test", SharePath.of("Archivio"), NasAreaScope.PRIVATE, "La mia area · Archivio"),
    )

    val REF = MediaRef.Nas(locationId = 7, contentKey = 99, mime = "image/jpeg")
  }
}

class RegisterNasActivity : TestWatcher() {
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

/** La base dei test Compose delle voci del NAS: tema, host dei fogli e fondale di vetro. */
abstract class NasActionsComposeBase {

  val composeRule: AndroidComposeTestRule<*, ComponentActivity> = createAndroidComposeRule<ComponentActivity>()

  @get:Rule
  val rules: RuleChain = RuleChain.outerRule(RegisterNasActivity()).around(composeRule)

  protected fun show(dark: Boolean = false, fontScale: Float? = null, content: @Composable () -> Unit) {
    composeRule.setContent {
      MyCloudGalleryTheme(settings = EngineSettings(themeMode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT)) {
        val density = LocalDensity.current
        val host = rememberFluidGlassModalHostState()
        val backdrop = rememberGlassBackdrop()
        CompositionLocalProvider(
          LocalFluidGlassModalHostState provides host,
          LocalDensity provides if (fontScale == null) density else Density(density.density, fontScale),
        ) {
          Box(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().glassBackdropSource(backdrop)) { content() }
            FluidGlassModalHost(state = host, backdrop = backdrop)
          }
        }
      }
    }
    composeRule.waitForIdle()
  }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = NAS_PHONE)
abstract class NasActionsPhoneTest : NasActionsComposeBase()
