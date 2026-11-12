package dev.pampa.mycloudgallery.feature.library.collections

import android.app.Application
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.designsystem.chrome.GalleryTabBarSlot
import dev.pampa.mycloudgallery.core.designsystem.chrome.LocalGalleryTabBarSlot
import dev.pampa.mycloudgallery.core.imaging.contract.ThumbBucket
import dev.pampa.mycloudgallery.core.imaging.contract.ThumbRequest
import dev.pampa.mycloudgallery.core.imaging.contract.ThumbnailLoader
import dev.pampa.mycloudgallery.core.imaging.testing.FakeThumbnailLoader
import dev.pampa.mycloudgallery.core.imaging.testing.SyntheticPixels
import dev.pampa.mycloudgallery.core.library.contract.LibraryFilter
import dev.pampa.mycloudgallery.core.model.ContentKey
import dev.pampa.mycloudgallery.core.model.FaceBox
import dev.pampa.mycloudgallery.core.model.FaceLocator
import dev.pampa.mycloudgallery.feature.library.collections.testing.CollectionsSectionSamples
import dev.pampa.mycloudgallery.feature.library.collections.testing.FakeCollectionsWorld
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.SharingStarted
import org.junit.After
import org.junit.Test
import org.robolectric.annotation.Config

/**
 * Fase I, giro di correzione 1, **CS-8**: sul tablet, con una voce aperta nel pannello accanto, le schede delle Raccolte chiedono ancora le loro foto.
 * Prima non le chiedevano piu' (la finestra delle miniature e' una sola, e con il pannello aperto e' della griglia accanto): le schede non ancora viste
 * restavano sul colore con il glifo. Ora le **tengono accanto** alla finestra della griglia ([ThumbnailLoader.keep], proprietario
 * [CollectionCovers.KEEP_OWNER]) senza sostituirla, e le lasciano quando il pannello si chiude o la scheda se ne va. Le richieste tenute sono solo di
 * media della Libreria visibile, come la finestra.
 */
@Config(application = Application::class, qualifiers = TABLET_QUALIFIERS)
class CollectionCoversBesideTest : ComposeScreenTest() {

  /** Il caricatore finto che in piu' registra le richieste tenute accanto alla finestra. */
  private class Recording(private val inner: FakeThumbnailLoader = FakeThumbnailLoader()) : ThumbnailLoader by inner {
    val windows get() = inner.windows
    val keeps = mutableListOf<Pair<String, List<ThumbRequest>>>()
    override fun keep(owner: String, requests: List<ThumbRequest>) {
      keeps += owner to requests
    }
  }

  private val world = FakeCollectionsWorld()
  private val photos = world.visibleIds.toList()
  private val faces = (1..5).map { n -> FaceLocator(ContentKey("%064x".format(n)), 0, FaceBox(10 * n, 10, 10 * n + 80, 90)) }
  private val crops = CollectionFaceCrops { face, px -> CollectionFaceCrop(SyntheticPixels.bitmap(face.box.x0.toLong(), px, px), setOf(photos[face.box.x0 / 10])) }
  private val loader = Recording()
  private val covers = CollectionCovers(loader, world.source.items(LibraryFilter.ALL), crops, Dispatchers.Unconfined)
  private val scope = CoroutineScope(Dispatchers.Unconfined + Job())

  @After
  fun stop() {
    scope.cancel()
  }

  @Test
  fun tenere_eLasciare_soloMediaVisibili() {
    val view = covers.view.value
    val visible = photos.take(2)
    val hidden = world.hiddenIds.first()
    covers.keep(view, visible + hidden, listOf(photos[2]), ThumbBucket.M)
    val (owner, kept) = loader.keeps.last()
    assertThat(owner).isEqualTo(CollectionCovers.KEEP_OWNER)
    // Due visibili e una in arrivo; il media nascosto non si chiede.
    assertThat(kept).hasSize(3)
    assertThat(kept.map { it.key }).containsNoDuplicates()
    // Tenere non tocca la finestra di nessuno.
    assertThat(loader.windows).isEmpty()
    covers.release()
    assertThat(loader.keeps.last()).isEqualTo(CollectionCovers.KEEP_OWNER to emptyList<ThumbRequest>())
  }

  @Test
  fun conUnaVoceAccanto_leSchedeTengonoLeLoroFoto_senzaToglierleAllaGriglia_ePoiLeLasciano() {
    var shown by mutableStateOf(true)
    show { if (shown) Tab() }
    // Pannello accanto vuoto: le schede sono la finestra delle miniature, e non tengono niente.
    assertThat(loader.windows).isNotEmpty()
    assertThat(loader.keeps.filter { it.second.isNotEmpty() }).isEmpty()
    val windowsBefore = loader.windows.size

    composeRule.onAllNodes(hasContentDescription("Giulia", substring = true) and hasClickAction())[0].performClick()
    composeRule.waitForIdle()
    composeRule.onNodeWithText("aperta: Giulia").assertExists()

    // Una voce aperta accanto: le schede tengono le loro foto accanto alla finestra della griglia, e la finestra non la toccano piu'.
    val kept = loader.keeps.last()
    assertThat(kept.first).isEqualTo(CollectionCovers.KEEP_OWNER)
    assertThat(kept.second).isNotEmpty()
    assertThat(loader.windows.size).isEqualTo(windowsBefore)

    // La scheda se ne va: niente resta tenuto.
    shown = false
    composeRule.waitForIdle()
    assertThat(loader.keeps.last()).isEqualTo(CollectionCovers.KEEP_OWNER to emptyList<ThumbRequest>())
  }

  @Composable
  private fun Tab() {
    val c = remember {
      val resources = ApplicationProvider.getApplicationContext<Application>().resources
      CollectionsLayoutController(true, CollectionsSectionSamples.production(resources, photos, faces, false), MemoryCollectionsLayoutStore(), scope, SharingStarted.Eagerly)
    }
    val inputs by c.inputs.collectAsState()
    val state = remember(inputs) { CollectionsLayout.state(inputs.present, emptyList(), inputs.saved, inputs.hidden, inputs.editing, inputs.saveFailed) }
    val slot = remember { GalleryTabBarSlot() }
    ModalTestHost {
      CompositionLocalProvider(LocalGalleryTabBarSlot provides slot, LocalCollectionCovers provides covers) {
        CollectionsScreen(
          state = state,
          contentPadding = PaddingValues(),
          backdropOut = {},
          onOpenCollection = {},
          onOpenSettings = {},
          statusHeader = { ProductionNetworkHeader() },
          detail = { kind -> Text(kind.code) },
          entryDetail = { e -> Text("aperta: ${e.title}") },
        )
      }
    }
  }
}
