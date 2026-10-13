package dev.pampa.mycloudgallery.feature.library.collections

import android.app.Application
import android.content.pm.ActivityInfo
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ApplicationProvider
import dev.antigravity.fluidengine.foundation.EngineSettings
import dev.antigravity.fluidengine.foundation.ThemeMode
import dev.pampa.mycloudgallery.core.designsystem.theme.MyCloudGalleryTheme
import dev.pampa.mycloudgallery.core.imaging.contract.ThumbKey
import dev.pampa.mycloudgallery.core.imaging.contract.ThumbRequest
import dev.pampa.mycloudgallery.core.imaging.contract.ThumbnailLoader
import dev.pampa.mycloudgallery.core.library.contract.TimelineView
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaRef
import dev.pampa.mycloudgallery.core.security.lock.AuthAvailability
import dev.pampa.mycloudgallery.core.security.lock.AuthPromptText
import dev.pampa.mycloudgallery.core.security.lock.AuthResult
import dev.pampa.mycloudgallery.core.security.lock.MonotonicClock
import dev.pampa.mycloudgallery.core.security.lock.SystemCredentialAuthenticator
import kotlinx.coroutines.CompletableDeferred
import org.junit.Rule
import org.junit.rules.RuleChain
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** L'orologio monotono dei test: lo muove il test. */
class TestClock(var now: Long = 10_000L) : MonotonicClock {
  override fun elapsedRealtimeMillis(): Long = now
}

/** Una credenziale di sistema finta: l'esito lo sceglie il test, e si puo' tenere in sospeso finche' non lo si libera. */
class FakeAuthenticator(
  var availability: AuthAvailability = AuthAvailability.AVAILABLE,
  var result: AuthResult = AuthResult.Success,
) : SystemCredentialAuthenticator {

  /** Quante volte e' stata mostrata la schermata di sistema. */
  var calls: Int = 0
    private set

  /** Se non e' nullo, `authenticate` aspetta qui (la schermata di sistema e' aperta). */
  var hold: CompletableDeferred<Unit>? = null

  override fun availability(): AuthAvailability = availability

  override suspend fun authenticate(text: AuthPromptText): AuthResult {
    calls++
    hold?.await()
    return result
  }
}

/**
 * Un caricatore che ricorda **ogni identita'** per cui una cella ha chiesto una miniatura o la griglia ha chiesto una
 * finestra: se un nascosto compare in una superficie, passa di qui (E-H1). Il resto lo fa il caricatore finto.
 */
class RecordingLoader(private val inner: ThumbnailLoader) : ThumbnailLoader by inner {

  val seen: MutableSet<MediaId> = linkedSetOf()

  override fun image(key: ThumbKey, ref: MediaRef) = inner.image(key, ref).also { seen += key.mediaId }

  override fun window(visible: List<ThumbRequest>, ahead: List<ThumbRequest>) {
    visible.forEach { seen += it.key.mediaId }
    ahead.forEach { seen += it.key.mediaId }
    inner.window(visible, ahead)
  }
}

/** Gli identificativi degli elementi di una vista, nell'ordine della vista. */
fun idsOf(view: TimelineView): List<MediaId> = (0 until view.size).map { view.snapshot.idAt(view.snapshotIndex(it)) }

/**
 * La base dei test Compose di questo modulo (Robolectric, grafica nativa, telefono da 412 dp): registra l'activity di
 * Compose, mostra un contenuto con il tema della galleria e da' accesso alla finestra (per `FLAG_SECURE`).
 *
 * L'activity vuota della regola di Compose non e' nel manifest di un modulo libreria: la si registra a mano, come fa il
 * test degli stub di P0.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = PHONE_QUALIFIERS)
abstract class ComposeScreenTest {

  val composeRule: AndroidComposeTestRule<*, ComponentActivity> = createAndroidComposeRule<ComponentActivity>()

  @get:Rule
  val rules: RuleChain = RuleChain.outerRule(RegisterComponentActivity()).around(composeRule)

  private class RegisterComponentActivity : TestWatcher() {
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

  /** Mostra [content] nel tema della galleria e aspetta che tutto sia assestato. */
  protected fun show(dark: Boolean = false, fontScale: Float? = null, content: @Composable () -> Unit) {
    composeRule.setContent {
      val settings = EngineSettings(themeMode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT)
      MyCloudGalleryTheme(settings = settings) {
        if (fontScale == null) {
          content()
        } else {
          val density = LocalDensity.current
          CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) { content() }
        }
      }
    }
    composeRule.waitForIdle()
  }

  /** Una cella della griglia, di qualunque genere: la descrizione comincia con "Foto" o "Video". */
  protected val anyCell: SemanticsMatcher =
    hasContentDescription("Foto", substring = true) or hasContentDescription("Video", substring = true)

  /**
   * Una cella **tutta dentro lo schermo e lontana dalla barra**, la [index]-esima per vicinanza al centro. La griglia
   * compone anche qualche riga sopra e sotto (sovrascansione) e una cella in cima sta mezza sotto la barra di vetro:
   * un tocco sul suo centro non arriverebbe alla cella.
   */
  protected fun visibleCell(index: Int = 0): SemanticsNodeInteraction {
    val nodes = composeRule.onAllNodes(anyCell).fetchSemanticsNodes()
    val height = composeRule.onRoot().fetchSemanticsNode().boundsInRoot.height
    val tallest = nodes.maxOf { it.boundsInRoot.height }
    val whole = nodes.indices.filter {
      val b = nodes[it].boundsInRoot
      b.top >= 0f && b.bottom <= height && b.height >= tallest * 0.95f
    }
    val byCentre = whole.sortedBy { kotlin.math.abs(nodes[it].boundsInRoot.center.y - height / 2f) }
    return composeRule.onAllNodes(anyCell)[byCentre[index]]
  }

  /** `FLAG_SECURE` e' acceso adesso sulla finestra dell'activity? */
  protected fun windowIsSecure(): Boolean =
    (composeRule.activity.window.attributes.flags and android.view.WindowManager.LayoutParams.FLAG_SECURE) != 0
}

/** Il telefono (412 dp) e il tablet (1280 dp, orizzontale) degli screenshot e dei test di layout. */
const val PHONE_QUALIFIERS = "w412dp-h915dp-xhdpi"
const val TABLET_QUALIFIERS = "w1280dp-h800dp-land-xhdpi"
