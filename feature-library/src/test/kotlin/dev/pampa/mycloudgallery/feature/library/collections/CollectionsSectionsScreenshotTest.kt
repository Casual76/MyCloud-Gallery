package dev.pampa.mycloudgallery.feature.library.collections

import android.app.Application
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.geometry.Offset
import com.github.takahirom.roborazzi.captureRoboImage
import dev.pampa.mycloudgallery.feature.library.collections.testing.CollectionsSectionSamples
import dev.pampa.mycloudgallery.feature.library.collections.testing.FakeCollectionsWorld
import dev.pampa.mycloudgallery.feature.library.collections.testing.fakeGridEnvironment
import dev.pampa.mycloudgallery.feature.library.collections.testing.fakeThumbnailLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.SharingStarted
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Gli screenshot di P11 (Raccolte ordinabili e nascondibili, D33): la scheda con tutte le sezioni del §2.7 -- quelle della scheda e quelle
 * che gli altri pacchetti registreranno, qui i fornitori finti di `testing/` --, l'ordine scelto, una sezione nascosta, "Modifica
 * Raccolte" e il trascinamento di una riga. Chiaro e scuro, telefono (412 dp) e tablet (1280 dp), piu' il testo a 1,3x.
 *
 * `gradlew :feature-library:recordRoborazziDebug --tests "*CollectionsSectionsScreenshot*"` scrive i PNG in `docs/screenshots/7-P11/`.
 * Nei normali `testDebugUnitTest` si verifica solo che le schermate compongano.
 */
abstract class CollectionsSectionsShotsBase(private val dark: Boolean, private val device: String) : ComposeScreenTest() {

  private val world = FakeCollectionsWorld()
  private val environment = fakeGridEnvironment(loader = fakeThumbnailLoader())
  private val scope = CoroutineScope(Dispatchers.Unconfined + Job())
  private val look: String get() = if (dark) "scuro" else "chiaro"

  @After
  fun stop() {
    scope.cancel()
  }

  private fun controller(
    store: CollectionsLayoutStore = MemoryCollectionsLayoutStore(),
    providers: List<CollectionsSectionProvider> = CollectionsSectionSamples.all(),
  ) = CollectionsLayoutController(world.source, true, providers, store, scope, SharingStarted.Eagerly)

  @Composable
  private fun Host(c: CollectionsLayoutController) {
    val inputs by c.inputs.collectAsState()
    val state = remember(inputs) {
      CollectionsLayout.state(inputs.present, emptyList(), inputs.saved, inputs.hidden, inputs.editing, inputs.saveFailed)
    }
    CollectionsScreen(
      state = state,
      contentPadding = PaddingValues(),
      backdropOut = {},
      onOpenCollection = {},
      onOpenSettings = {},
      statusHeader = {},
      // Il pannello di dettaglio del tablet: la stessa griglia della rotta, senza il tasto indietro.
      detail = { kind ->
        kind.filter?.let { filter -> CollectionGridScreen(kind, world.source.items(filter), environment, { _, _ -> }, {}, {}, onBack = null) }
      },
      onOpenEntry = {},
      onEdit = c::startEditing,
      onDone = c::stopEditing,
      onReorder = c::reorder,
      onSetShown = c::setShown,
    )
  }

  private fun shoot(name: String, fontScale: Float? = null, afterShow: () -> Unit = {}, c: CollectionsLayoutController = controller()) {
    show(dark = dark, fontScale = fontScale) { Host(c) }
    afterShow()
    val suffix = if (fontScale != null) "-testo-grande" else ""
    composeRule.onRoot().captureRoboImage("$DIR/$name-$look-$device$suffix.png")
  }

  private fun scrollTo(text: String) {
    composeRule.onAllNodes(hasScrollAction())[0].performScrollToNode(hasText(text))
    composeRule.waitForIdle()
  }

  private fun edit() {
    composeRule.onNodeWithContentDescription("Modifica Raccolte").performClick()
    composeRule.waitForIdle()
  }

  // --- La scheda -----------------------------------------------------------------------------------------------------------------------------

  @Test
  fun raccolte() = shoot("raccolte")

  @Test
  fun raccolteTestoGrande() = shoot("raccolte", fontScale = LARGE_TEXT)

  @Test
  fun raccolteInFondo() = shoot("raccolte-in-fondo", afterShow = { scrollTo("Nascosti") })

  @Test
  fun raccolteConPersoneSpente() = shoot("raccolte-persone", afterShow = { scrollTo("Attiva riconoscimento dei volti") })

  @Test
  fun raccolteConOrdineEVisibilitaScelti() {
    val store = MemoryCollectionsLayoutStore(
      order = listOf("phone", "albums", "nas", "smart", "utility", "hidden"),
      hidden = setOf("trips", "safe"),
    )
    shoot("raccolte-scelte", c = controller(store))
  }

  @Test
  fun raccolteSenzaFornitori() = shoot("raccolte-senza-fornitori", c = controller(providers = emptyList()))

  @Test
  fun raccolteConTuttoNascosto() {
    val store = MemoryCollectionsLayoutStore(hidden = setOf("phone", "safe", "hidden"))
    shoot("raccolte-tutto-nascosto", c = controller(store, providers = emptyList()))
  }

  // --- Modifica Raccolte ------------------------------------------------------------------------------------------------------------

  @Test
  fun modifica() = shoot("modifica", afterShow = { edit() })

  @Test
  fun modificaTestoGrande() = shoot("modifica", fontScale = LARGE_TEXT, afterShow = { edit() })

  @Test
  fun modificaConSezioniNascoste() {
    val store = MemoryCollectionsLayoutStore(hidden = setOf("trips", "hidden", "utility"))
    shoot("modifica-nascoste", afterShow = { edit() }, c = controller(store))
  }

  @Test
  fun modificaTrascinandoUnaRiga() {
    val c = controller()
    shoot(
      "modifica-trascinamento",
      afterShow = {
        edit()
        val row = composeRule.onNodeWithText("Viaggi").fetchSemanticsNode().size.height.toFloat()
        composeRule.onNodeWithTag(handleTag("trips"), useUnmergedTree = true).performTouchInput {
          down(center)
          repeat(6) { moveBy(Offset(0f, row * 0.7f / 6)) }
        }
        composeRule.waitForIdle()
      },
      c = c,
    )
    composeRule.onNodeWithTag(handleTag("trips"), useUnmergedTree = true).performTouchInput { up() }
  }

  companion object {
    const val DIR = "../docs/screenshots/7-P11"
    const val LARGE_TEXT = 1.3f
    fun looks(): List<Array<Any>> = listOf(arrayOf<Any>(false), arrayOf<Any>(true))
  }
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = PHONE_QUALIFIERS)
class CollectionsSectionsPhoneShotsTest(dark: Boolean) : CollectionsSectionsShotsBase(dark, "telefono") {
  companion object {
    @JvmStatic
    @ParameterizedRobolectricTestRunner.Parameters(name = "scuro={0}")
    fun data(): List<Array<Any>> = looks()
  }
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = TABLET_QUALIFIERS)
class CollectionsSectionsTabletShotsTest(dark: Boolean) : CollectionsSectionsShotsBase(dark, "tablet") {
  companion object {
    @JvmStatic
    @ParameterizedRobolectricTestRunner.Parameters(name = "scuro={0}")
    fun data(): List<Array<Any>> = looks()
  }
}
