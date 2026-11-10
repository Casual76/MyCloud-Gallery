package dev.pampa.mycloudgallery.feature.nas

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
import androidx.test.core.app.ApplicationProvider
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import dev.antigravity.fluidengine.foundation.EngineSettings
import dev.antigravity.fluidengine.foundation.ThemeMode
import dev.antigravity.fluidengine.ui.fluid.FluidGlassModalHost
import dev.antigravity.fluidengine.ui.fluid.LocalFluidGlassModalHostState
import dev.antigravity.fluidengine.ui.fluid.glassBackdropSource
import dev.antigravity.fluidengine.ui.fluid.rememberFluidGlassModalHostState
import dev.antigravity.fluidengine.ui.fluid.rememberGlassBackdrop
import dev.pampa.mycloudgallery.core.designsystem.theme.MyCloudGalleryTheme
import dev.pampa.mycloudgallery.feature.nas.contract.NasArea
import dev.pampa.mycloudgallery.feature.nas.contract.NasAreaKind
import dev.pampa.mycloudgallery.feature.nas.contract.NasAreas
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Rule
import org.junit.rules.RuleChain
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Il telefono (412 dp) e il tablet (1280 dp, orizzontale) degli screenshot e dei test di layout. */
const val PHONE_QUALIFIERS = "w412dp-h915dp-xhdpi"

/** Il telefono degli screenshot per l'utente (K4): il suo Galaxy S25, 360 x 780 dp. Non 412 dp, dove entrano piu' cose che sul suo schermo. */
const val SHOT_PHONE_QUALIFIERS = "w360dp-h780dp-xhdpi"
const val TABLET_QUALIFIERS = "w1280dp-h800dp-land-xhdpi"

/** Il testo ingrandito dei test (come nella Libreria). */
const val LARGE_TEXT = 1.3f

/** Le aree dell'account di prova: la privata `test` e la comune `test-comune` (le share di prova del NAS vero, D30). */
class FakeNasAreas(areas: List<NasArea> = defaultAreas()) : NasAreas {
  private val state = MutableStateFlow(areas)
  override val areas: StateFlow<List<NasArea>> = state

  /** R11: i percorsi (d'origine) dei media che l'utente ha nascosto; lo sa `:app`. */
  val hiddenPaths: MutableSet<String> = HashSet()

  /** Il catalogo non risponde: ogni domanda sui nascosti e' un errore. */
  var failing: Boolean = false

  override suspend fun hiddenAmong(plan: dev.pampa.mycloudgallery.core.nasops.contract.OpPlan): Int {
    check(!failing) { "catalogo non raggiungibile" }
    return plan.items.count { !it.isFolder && it.srcPath.value in hiddenPaths }
  }

  /** Fase 7 (P9, I8): le domande sui file di un elenco; il test controlla che il VM chieda e che non mostri i nomi prima della risposta. */
  val hiddenFileAsks: MutableList<Pair<String, List<dev.pampa.mycloudgallery.core.trust.nas.SharePath>>> = ArrayList()

  /** Se non e' vuoto, la risposta sui file nascosti aspetta questo: l'elenco e' arrivato e `:app` non ha ancora risposto. */
  var hiddenFilesGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null

  override suspend fun hiddenFiles(share: String, files: Collection<dev.pampa.mycloudgallery.core.trust.nas.SharePath>): Set<dev.pampa.mycloudgallery.core.trust.nas.SharePath> {
    hiddenFileAsks += share to files.toList()
    hiddenFilesGate?.await()
    check(!failing) { "catalogo non raggiungibile" }
    return files.filterTo(HashSet()) { it.value in hiddenPaths }
  }

  fun set(areas: List<NasArea>) {
    state.value = areas
  }

  // NA-10: come il vero (`NasOrganizer.setPrivateAreaExclusions`): salva tutto l'insieme e dice se ci e' riuscito.
  private val excluded = MutableStateFlow<Set<dev.pampa.mycloudgallery.core.trust.nas.SharePath>>(emptySet())
  override val privateExclusions: StateFlow<Set<dev.pampa.mycloudgallery.core.trust.nas.SharePath>> = excluded

  /** Senza area privata o senza NAS il vero risponde falso. */
  var exclusionsFail: Boolean = false

  /** Quante volte e' stata chiesta una verifica del NAS dopo un cambio (il vero la chiede). */
  var verifies: Int = 0
    private set

  override suspend fun setPrivateExclusions(folders: Set<dev.pampa.mycloudgallery.core.trust.nas.SharePath>): Boolean {
    if (exclusionsFail || state.value.none { it.kind == NasAreaKind.PRIVATE }) return false
    excluded.value = folders
    verifies++
    return true
  }

  // NA-11: un salto che si toglie solo col gesto.
  private val jump = MutableStateFlow<Long?>(null)
  override val clockJumpMs: StateFlow<Long?> = jump

  /** Quante volte e' stato fatto il gesto che accetta l'ora. */
  var clockAccepted: Int = 0
    private set

  fun jumpTo(ms: Long?) {
    jump.value = ms
  }

  override fun acceptClockJump(): Boolean {
    if (jump.value == null) return false
    clockAccepted++
    jump.value = null
    return true
  }

  companion object {
    fun defaultAreas(): List<NasArea> = listOf(NasArea("test", NasAreaKind.PRIVATE), NasArea("test-comune", NasAreaKind.COMMON))
  }
}

/** Registra a mano l'activity vuota di Compose (non e' nel manifest di un modulo libreria). */
class RegisterComponentActivity : TestWatcher() {
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

/**
 * La base dei test Compose di questo modulo (Robolectric, grafica nativa, telefono da 412 dp): mostra un contenuto nel tema della
 * galleria dentro un host dei fogli (senza non si aprono) e un fondale di vetro.
 */
abstract class NasComposeBase {

  val composeRule: AndroidComposeTestRule<*, ComponentActivity> = createAndroidComposeRule<ComponentActivity>()

  @get:Rule
  val rules: RuleChain = RuleChain.outerRule(RegisterComponentActivity()).around(composeRule)

  /** Mostra [content] nel tema, con un host dei fogli in scena, e aspetta che tutto sia assestato. */
  protected fun show(dark: Boolean = false, fontScale: Float? = null, content: @Composable () -> Unit) {
    composeRule.setContent {
      val settings = EngineSettings(themeMode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT)
      MyCloudGalleryTheme(settings = settings) {
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

/** La base dei test Compose a un solo dispositivo (il telefono da 412 dp). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = PHONE_QUALIFIERS)
abstract class ComposeNasTest : NasComposeBase()
