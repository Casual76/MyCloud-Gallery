package dev.pampa.mycloudgallery.feature.library.collections

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import dev.antigravity.fluidengine.ui.fluid.GlassBackdropState
import dev.pampa.mycloudgallery.core.designsystem.chrome.GalleryTab
import dev.pampa.mycloudgallery.core.designsystem.chrome.GalleryTabBar
import dev.pampa.mycloudgallery.core.designsystem.chrome.GalleryTabBarDefaults
import dev.pampa.mycloudgallery.core.designsystem.chrome.GalleryTabBarSlot
import dev.pampa.mycloudgallery.core.designsystem.chrome.GalleryTabBarSlotVisibility
import dev.pampa.mycloudgallery.core.designsystem.chrome.LocalGalleryTabBarSlot
import dev.pampa.mycloudgallery.feature.library.collections.testing.CollectionsSectionSamples
import dev.pampa.mycloudgallery.feature.library.collections.testing.FakeCollectionsWorld
import dev.pampa.mycloudgallery.feature.library.collections.testing.fakeGridEnvironment
import dev.pampa.mycloudgallery.feature.library.collections.testing.fakeThumbnailLoader
import dev.pampa.mycloudgallery.feature.library.collections.testing.fakeTrashedItems
import dev.pampa.mycloudgallery.feature.library.hidden.HiddenScreen
import dev.pampa.mycloudgallery.feature.library.hidden.HiddenUiState
import dev.pampa.mycloudgallery.feature.library.trash.RecentlyDeletedController
import dev.pampa.mycloudgallery.feature.library.trash.RecentlyDeletedScreen
import dev.pampa.mycloudgallery.feature.library.trash.TrashEntry
import dev.pampa.mycloudgallery.feature.library.trash.TrashUiState
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Gli screenshot delle **Raccolte alleggerite** del pacchetto G2: senza Album, Album smart e Telefono (vanno nella scheda Album), con la pillola
 * nuova e il cerchio di Cerca. Sul tablet anche il pannello di dettaglio in selezione (Eliminati di recente): "Ripristina" prende il posto della
 * pillola invece di posarsi sopra di lei (B3). Chiaro e scuro, telefono e tablet.
 *
 * `gradlew :feature-library:recordRoborazziDebug --tests "*G2Raccolte*"` scrive i PNG in `docs/screenshots/beta-grafica/G2/`.
 */
abstract class G2RaccolteShotBase(private val dark: Boolean, private val device: String) : ComposeScreenTest() {

  private val world = FakeCollectionsWorld()
  private val environment = fakeGridEnvironment(loader = fakeThumbnailLoader())
  private val now = 1_800_000_000_000L

  private val look: String get() = if (dark) "scuro" else "chiaro"

  private fun shoot(name: String, content: @Composable () -> Unit) {
    show(dark = dark, content = content)
    composeRule.onRoot().captureRoboImage("$DIR/$name-$look-$device.png")
  }

  private fun trashEntries(count: Int): List<TrashEntry> =
    fakeTrashedItems(now, count).map { item ->
      TrashEntry(RecentlyDeletedController.trashEntryId(item.ref), item, RecentlyDeletedController.daysLeft(item.expiresAtMs, now))
    }

  /** Le Raccolte come le compone il controllore vero: le sezioni di tutti i fornitori, senza quelle della scheda Album. */
  private fun g2Layout(): CollectionsLayoutState {
    val providers = CollectionsSectionSamples.all().mapNotNull { CollectionsLayout.sectionOf(it.code, it.group.value) }
    val all = layoutOf(world, providers = providers)
    return all.copy(sections = all.sections.filter { it.code !in CollectionSections.inAlbumsTab })
  }

  /** Le Raccolte nella scheda, con la pillola sotto; il posto della pillola come in `MainScaffold`. */
  @Composable
  private fun Tab(initial: CollectionKind? = null, trashSelection: Boolean = false) {
    val slot = remember { GalleryTabBarSlot() }
    var backdrop by remember { mutableStateOf<GlassBackdropState?>(null) }
    val entries = remember { trashEntries(14) }
    Box(Modifier.fillMaxSize()) {
      CompositionLocalProvider(LocalGalleryTabBarSlot provides slot) {
        CollectionsScreen(
          state = g2Layout(),
          contentPadding = PaddingValues(bottom = GalleryTabBarDefaults.ContentInset),
          backdropOut = { backdrop = it },
          onOpenCollection = {},
          onOpenSettings = {},
          statusHeader = {},
          initialSelection = initial,
          detail = { kind ->
            when (kind) {
              CollectionKind.RECENTLY_DELETED -> RecentlyDeletedScreen(
                TrashUiState.Loaded(entries),
                selection = if (trashSelection) entries.take(3).map { it.id }.toSet() else emptySet(),
                loader = environment.loader,
                onToggle = {}, onSelectAll = {}, onClearSelection = {}, onRestore = {}, onRetry = {},
                contentPadding = PaddingValues(bottom = GalleryTabBarDefaults.ContentInset),
              )
              else -> HiddenScreen(HiddenUiState.Locked, environment, onUnlock = {}, onRetry = {}, onOpenSecuritySettings = {}, onShow = {}, onOpenViewer = {})
            }
          },
        )
      }
      GalleryTabBarSlotVisibility(visible = !slot.taken, modifier = Modifier.align(Alignment.BottomCenter)) {
        GalleryTabBar(selected = GalleryTab.Collections, onSelect = {}, backdrop = backdrop, onOpenSearch = {})
      }
    }
  }

  @Test
  fun raccolteAlleggerite() = shoot("raccolte-alleggerite") { Tab() }

  /** Sul tablet il pannello degli Eliminati di recente in selezione: "Ripristina" sta nel posto della pillola, che esce. */
  @Test
  fun raccoltePannelloInSelezione() = shoot("raccolte-pannello-selezione") { Tab(initial = CollectionKind.RECENTLY_DELETED, trashSelection = true) }

  companion object {
    const val DIR = "../docs/screenshots/beta-grafica/G2"
    fun looks(): List<Array<Any>> = listOf(arrayOf<Any>(false), arrayOf<Any>(true))
  }
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = PHONE_QUALIFIERS)
class G2RaccoltePhoneShots(dark: Boolean) : G2RaccolteShotBase(dark, "telefono") {
  companion object {
    @JvmStatic
    @ParameterizedRobolectricTestRunner.Parameters(name = "scuro={0}")
    fun data(): List<Array<Any>> = looks()
  }
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = TABLET_QUALIFIERS)
class G2RaccolteTabletShots(dark: Boolean) : G2RaccolteShotBase(dark, "tablet") {
  companion object {
    @JvmStatic
    @ParameterizedRobolectricTestRunner.Parameters(name = "scuro={0}")
    fun data(): List<Array<Any>> = looks()
  }
}
