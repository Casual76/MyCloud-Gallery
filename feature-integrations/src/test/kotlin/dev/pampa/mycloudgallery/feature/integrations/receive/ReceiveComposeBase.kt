package dev.pampa.mycloudgallery.feature.integrations.receive

import android.app.Application
import android.content.pm.ActivityInfo
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ApplicationProvider
import dev.antigravity.fluidengine.foundation.EngineSettings
import dev.antigravity.fluidengine.foundation.ThemeMode
import dev.pampa.mycloudgallery.core.backup.contract.DestinationArea
import dev.pampa.mycloudgallery.core.backup.contract.IncomingItem
import dev.pampa.mycloudgallery.core.backup.contract.IncomingReject
import dev.pampa.mycloudgallery.core.backup.contract.IncomingStage
import dev.pampa.mycloudgallery.core.backup.contract.IncomingStageId
import dev.pampa.mycloudgallery.core.designsystem.theme.MyCloudGalleryTheme
import dev.pampa.mycloudgallery.core.model.Sha256
import dev.pampa.mycloudgallery.feature.integrations.receive.testing.FakeIncomingDestinations
import org.junit.Rule
import org.junit.rules.RuleChain
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.robolectric.Shadows.shadowOf

// Fase 5B (P11): il supporto dei test Compose della ricezione (stesso schema dei moduli di P5): telefono da 412 dp, tablet da 1280 dp, testo a 1,3x.

const val RECEIVE_PHONE = "w412dp-h915dp-xhdpi"
const val RECEIVE_TABLET = "w1280dp-h800dp-land-xhdpi"
const val RECEIVE_LARGE_TEXT = 1.3f

/** Registra a mano l'activity vuota di Compose (non e' nel manifest di un modulo libreria). */
class RegisterReceiveComposeActivity : TestWatcher() {
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

abstract class ReceiveComposeBase {

  val composeRule: AndroidComposeTestRule<*, ComponentActivity> = createAndroidComposeRule<ComponentActivity>()

  @get:Rule
  val rules: RuleChain = RuleChain.outerRule(RegisterReceiveComposeActivity()).around(composeRule)

  private val fontScale: MutableState<Float> = mutableStateOf(1f)

  /** Mostra [content] nel tema della galleria e aspetta che tutto sia assestato. `setContent` si chiama una volta per prova. */
  protected fun show(dark: Boolean = false, fontScale: Float? = null, content: @Composable () -> Unit) {
    this.fontScale.value = fontScale ?: 1f
    composeRule.setContent {
      val settings = EngineSettings(themeMode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT)
      MyCloudGalleryTheme(settings = settings) {
        val density = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(density.density, this@ReceiveComposeBase.fontScale.value)) {
          Box(Modifier.fillMaxSize()) { content() }
        }
      }
    }
    composeRule.waitForIdle()
  }

  protected fun clickable(): List<SemanticsNode> =
    composeRule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.OnClick)).fetchSemanticsNodes()
      .filter { it.boundsInRoot.width > 0f && it.boundsInRoot.height > 0f }

  protected fun SemanticsNode.name(): String =
    config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(", ").orEmpty().ifEmpty {
      config.getOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text }.orEmpty()
    }.ifEmpty { children.joinToString(" ") { it.name() }.trim() }
}

/** I campioni della schermata: una condivisione di prova nei vari stati. */
object ReceiveUiSamples {

  private val stage: IncomingStage = IncomingStage(
    IncomingStageId("campione"),
    listOf(
      IncomingItem("a".repeat(64), Sha256("a".repeat(64)), 3_400_000, "image/jpeg", null),
      IncomingItem("b".repeat(64), Sha256("b".repeat(64)), 2_900_000, "image/jpeg", null),
      IncomingItem("c".repeat(64), Sha256("c".repeat(64)), 18_700_000, "video/mp4", null),
    ),
    emptyMap(),
  )

  private val roots = FakeIncomingDestinations.defaultRoots()

  fun staging() = ReceiveUi(screen = ReceiveScreenState.STAGING)

  fun ready(common: Boolean = false, rejected: Map<IncomingReject, Int> = emptyMap(), deep: Boolean = false): ReceiveUi {
    val choices = if (deep) listOf(FakeIncomingDestinations.choice("Mare", DestinationArea.PRIVATE, "Ricevute/Mare"), FakeIncomingDestinations.choice("Città", DestinationArea.PRIVATE, "Ricevute/Citta")) else roots
    return ReceiveUi(
      screen = ReceiveScreenState.READY,
      stage = stage,
      photos = 2,
      videos = 1,
      bytes = stage.totalBytes,
      rejected = rejected,
      trail = if (deep) listOf(roots.first()) else emptyList(),
      choices = choices,
      selected = if (common) roots[1] else choices.first(),
    )
  }

  val manyRejected: Map<IncomingReject, Int> = mapOf(
    IncomingReject.FILE_SCHEME to 1,
    IncomingReject.NOT_MEDIA to 3,
    IncomingReject.TOO_LARGE to 1,
  )

  fun nothing(rejected: Map<IncomingReject, Int> = manyRejected) = ReceiveUi(screen = ReceiveScreenState.NOTHING, rejected = rejected)

  fun done(offline: Boolean = false) = ReceiveUi(screen = ReceiveScreenState.DONE, queued = 3, nasReachable = !offline)

  fun failed(reason: ReceiveFailure = ReceiveFailure.DESTINATION) = ReceiveUi(screen = ReceiveScreenState.FAILED, stage = stage, failure = reason)

  fun locked(failed: Boolean = false, unlocking: Boolean = false) = ReceiveUi(screen = ReceiveScreenState.LOCKED, unlockFailed = failed, unlocking = unlocking)

  fun notReady() = ReceiveUi(screen = ReceiveScreenState.NOT_READY)
}
