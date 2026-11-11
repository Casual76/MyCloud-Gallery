package dev.pampa.mycloudgallery.feature.library.collections

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import com.github.takahirom.roborazzi.captureRoboImage
import dev.antigravity.fluidengine.ui.fluid.GlassBackdropState
import dev.pampa.mycloudgallery.core.designsystem.chrome.GalleryTab
import dev.pampa.mycloudgallery.core.designsystem.chrome.GalleryTabBar
import dev.pampa.mycloudgallery.core.designsystem.chrome.GalleryTabBarDefaults
import dev.pampa.mycloudgallery.core.designsystem.chrome.GalleryTabBarSlot
import dev.pampa.mycloudgallery.core.designsystem.chrome.GalleryTabBarSlotVisibility
import dev.pampa.mycloudgallery.core.designsystem.chrome.LocalGalleryTabBarSlot
import dev.pampa.mycloudgallery.core.library.contract.LibraryFilter
import dev.pampa.mycloudgallery.feature.library.collections.testing.CollectionsSectionSamples
import dev.pampa.mycloudgallery.feature.library.collections.testing.FakeCollectionsWorld
import dev.pampa.mycloudgallery.feature.library.collections.testing.fakeThumbnailLoader
import androidx.test.core.app.ApplicationProvider
import dev.pampa.mycloudgallery.core.imaging.testing.SyntheticPixels
import dev.pampa.mycloudgallery.core.model.ContentKey
import dev.pampa.mycloudgallery.core.model.FaceBox
import dev.pampa.mycloudgallery.core.model.FaceLocator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.SharingStarted
import org.junit.After
import androidx.compose.runtime.collectAsState
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * H5 (fase H, 11/11): la scheda **Raccolte** com'e' dopo H5, per l'utente. Come in produzione: la scheda dentro il posto della pillola (lo stesso di
 * `MainScaffold`), il fondo della pagina, la capsula della rete in testa, le sezioni nell'ordine predefinito e senza quelle passate alla scheda
 * Album, le copertine dalla cache delle miniature (qui quella finta, con le foto sintetiche della Libreria finta).
 *
 * Gli stati: in cima, scorsa fino in fondo, con il menu in alto aperto (Modifica Raccolte, Impostazioni), e la libreria **appena installata**
 * (nessun ricordo, nessuna persona, nessun viaggio, nessuna foto con la posizione). Il telefono dell'utente (360 x 780 dp) e il tablet orizzontale,
 * chiaro e scuro. CU-9 (giro 1): le sezioni sono quelle della produzione (`CollectionsSectionSamples.production`, composte dal controllore vero):
 * l'Utility dal costruttore vero, le Cartelle NAS con i testi veri, una sola voce "Eliminati di recente"; i volti delle persone e le foto dei luoghi
 * passano dalla stessa strada della produzione (`CollectionCovers`). I nomi di persone, ricordi e viaggi sono inventati.
 *
 * `gradlew :feature-library:recordRoborazziDebug --tests ...H5RaccoltePhoneShots` (e `...H5RaccolteTabletShots`) scrive in
 * `docs/screenshots/beta-grafica/H5/`.
 */
abstract class H5RaccolteShotBase(private val dark: Boolean, private val device: String) : ComposeScreenTest() {

  private val world = FakeCollectionsWorld()
  private val photos = world.visibleIds.toList()

  /** CU-9: tre volti di prova (il ritaglio e' un'immagine sintetica: in produzione e' il volto vero, ritagliato da feature-people). */
  private val faces = (1..3).map { n -> FaceLocator(ContentKey("%064x".format(n)), 0, FaceBox(10 * n, 10, 10 * n + 80, 90)) }
  private val crops = CollectionFaceCrops { face, px -> CollectionFaceCrop(SyntheticPixels.bitmap(face.box.x0.toLong() * 7919L, px, px), setOf(photos[face.box.x0 / 10])) }
  private val covers = CollectionCovers(fakeThumbnailLoader(), world.source.items(LibraryFilter.ALL), crops, Dispatchers.Unconfined)
  private val scope = CoroutineScope(Dispatchers.Unconfined + Job())

  @After
  fun stop() {
    scope.cancel()
  }

  private val look: String get() = if (dark) "scuro" else "chiaro"

  private fun shoot(name: String, after: () -> Unit = {}, content: @Composable () -> Unit) {
    show(dark = dark, content = content)
    composeRule.waitForIdle()
    after()
    composeRule.waitForIdle()
    composeRule.onRoot().captureRoboImage("$DIR/raccolte-$name-$look-$device.png")
  }

  /**
   * CU-9: lo stato come lo compone il **controllore vero** con le sezioni come le consegna `:app` in produzione
   * ([CollectionsSectionSamples.production]): la scheda toglie Album, Album smart e Telefono, e "Da tenere al sicuro" vuota toglie quella della scheda.
   */
  private fun controller(fresh: Boolean): CollectionsLayoutController {
    val resources = ApplicationProvider.getApplicationContext<Application>().resources
    val providers = CollectionsSectionSamples.production(resources, photos, faces, fresh)
    return CollectionsLayoutController(true, providers, MemoryCollectionsLayoutStore(), scope, SharingStarted.Eagerly)
  }

  @Composable
  private fun Tab(fresh: Boolean) {
    val c = remember { controller(fresh) }
    val inputs by c.inputs.collectAsState()
    val state = remember(inputs) { CollectionsLayout.state(inputs.present, emptyList(), inputs.saved, inputs.hidden, inputs.editing, inputs.saveFailed) }
    Tab(state)
  }

  /** La scheda nel posto della pillola, il fondo della pagina e l'host dei pop-up come in produzione. */
  @Composable
  private fun Tab(state: CollectionsLayoutState) {
    val slot = remember { GalleryTabBarSlot() }
    var backdrop by remember { mutableStateOf<GlassBackdropState?>(null) }
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
      ModalTestHost {
        CompositionLocalProvider(LocalGalleryTabBarSlot provides slot, LocalCollectionCovers provides covers) {
          CollectionsScreen(
            state = state,
            contentPadding = PaddingValues(bottom = GalleryTabBarDefaults.ContentInset),
            backdropOut = { backdrop = it },
            onOpenCollection = {},
            onOpenSettings = {},
            statusHeader = { ProductionNetworkHeader() },
            detail = { kind -> Text(kind.code) },
            entryDetail = { e -> Text(e.title) },
          )
        }
        GalleryTabBarSlotVisibility(visible = !slot.taken, modifier = Modifier.align(Alignment.BottomCenter)) {
          GalleryTabBar(selected = GalleryTab.Collections, onSelect = {}, backdrop = backdrop, onOpenSearch = {})
        }
      }
    }
  }

  private fun scrollTo(text: String) {
    composeRule.onAllNodes(hasScrollAction())[0].performScrollToNode(hasText(text))
    composeRule.waitForIdle()
  }

  @Test
  fun inCima() = shoot("in-cima") { Tab(fresh = false) }

  @Test
  fun scorsa() = shoot("scorsa", after = { scrollTo("Nascosti") }) { Tab(fresh = false) }

  @Test
  fun ricordiEViaggi() = shoot("ricordi", after = { scrollTo("Tutti i ricordi") }) { Tab(fresh = false) }

  /** CS-1: i Luoghi con la foto piu' recente di ogni luogo (e la Mappa con quella del luogo piu' fotografato). */
  @Test
  fun luoghi() = shoot("luoghi", after = { scrollTo("Milano") }) { Tab(fresh = false) }

  @Test
  fun appenaInstallata() = shoot("appena-installata") { Tab(fresh = true) }

  @Test
  fun appenaInstallataScorsa() = shoot("appena-installata-scorsa", after = { scrollTo("Nascosti") }) { Tab(fresh = true) }

  @Test
  fun menuAperto() = shoot("menu", after = {
    composeRule.onNodeWithContentDescription("Altre azioni sulle Raccolte").performClick()
    composeRule.waitForIdle()
  }) { Tab(fresh = false) }

  companion object {
    const val DIR = "../docs/screenshots/beta-grafica/H5"
    fun looks(): List<Array<Any>> = listOf(arrayOf<Any>(false), arrayOf<Any>(true))
  }
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = SHOT_PHONE_QUALIFIERS)
class H5RaccoltePhoneShots(dark: Boolean) : H5RaccolteShotBase(dark, "telefono") {
  companion object {
    @JvmStatic
    @ParameterizedRobolectricTestRunner.Parameters(name = "scuro={0}")
    fun data(): List<Array<Any>> = looks()
  }
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = TABLET_QUALIFIERS)
class H5RaccolteTabletShots(dark: Boolean) : H5RaccolteShotBase(dark, "tablet") {
  companion object {
    @JvmStatic
    @ParameterizedRobolectricTestRunner.Parameters(name = "scuro={0}")
    fun data(): List<Array<Any>> = looks()
  }
}
