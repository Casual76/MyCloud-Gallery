package dev.pampa.mycloudgallery.feature.library.collections

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import dev.pampa.mycloudgallery.core.designsystem.chrome.GalleryTabBarDefaults
import dev.pampa.mycloudgallery.feature.library.collections.testing.FakeCollectionsWorld
import dev.pampa.mycloudgallery.feature.library.collections.testing.fakeGridEnvironment
import dev.pampa.mycloudgallery.feature.library.collections.testing.fakeThumbnailLoader
import dev.pampa.mycloudgallery.feature.library.library.LibraryTab
import dev.pampa.mycloudgallery.feature.library.library.testing.syntheticLibraryState
import org.junit.Test
import org.robolectric.annotation.Config

/**
 * Gli screenshot dei pannelli della Libreria e delle Raccolte (Fase 10, P5, §2.3) alle quattro larghezze del riquadro, chiaro e scuro:
 * `gradlew :feature-library:recordRoborazziDebug --tests "*LibraryPanesShots*"` scrive i PNG in `docs/screenshots/10-P5/`. Nei test normali le immagini non si scrivono.
 * Raccolte: una pagina sola a 360 e 600 dp, elenco e dettaglio a 840 e 1280. Libreria: la griglia a tutta larghezza (il viewer affiancato e' negli screenshot del viewer).
 */
abstract class LibraryPanesShots(private val width: Int) : ComposeScreenTest() {

  private val world = FakeCollectionsWorld()
  private val environment = fakeGridEnvironment(loader = fakeThumbnailLoader())

  private fun shoot(name: String, dark: Boolean, content: @Composable () -> Unit) {
    show(dark = dark, content = content)
    composeRule.onRoot().captureRoboImage("../docs/screenshots/10-P5/$name-${width}dp-${if (dark) "scuro" else "chiaro"}.png")
  }

  @Composable
  private fun Collections() {
    CollectionsScreen(
      state = layoutOf(world),
      contentPadding = PaddingValues(bottom = GalleryTabBarDefaults.ContentInset),
      backdropOut = {},
      onOpenCollection = {},
      onOpenSettings = {},
      statusHeader = {},
      detail = { kind ->
        CollectionGridScreen(
          kind = kind,
          items = world.source.items(requireNotNull(kind.filter)),
          environment = environment,
          onFavorite = { _, _ -> },
          onHide = { },
          onOpenViewer = { },
          contentPadding = PaddingValues(bottom = GalleryTabBarDefaults.ContentInset),
        )
      },
    )
  }

  @Composable
  private fun Library() {
    Box(modifier = Modifier.fillMaxSize()) {
      LibraryTab(
        contentPadding = PaddingValues(bottom = GalleryTabBarDefaults.ContentInset),
        backdropOut = {},
        onOpenViewer = {},
        onOpenSettings = {},
        state = syntheticLibraryState(),
      )
    }
  }

  @Test
  fun raccolteChiaro() = shoot("raccolte", dark = false) { Collections() }

  @Test
  fun raccolteScuro() = shoot("raccolte", dark = true) { Collections() }

  @Test
  fun libreriaChiaro() = shoot("libreria", dark = false) { Library() }

  @Test
  fun libreriaScuro() = shoot("libreria", dark = true) { Library() }
}

@Config(qualifiers = "w360dp-h800dp-xhdpi")
class LibraryPanesShots360 : LibraryPanesShots(360)

@Config(qualifiers = "w600dp-h900dp-xhdpi")
class LibraryPanesShots600 : LibraryPanesShots(600)

@Config(qualifiers = "w840dp-h900dp-xhdpi")
class LibraryPanesShots840 : LibraryPanesShots(840)

@Config(qualifiers = "w1280dp-h800dp-land-xhdpi")
class LibraryPanesShots1280 : LibraryPanesShots(1280)
