package dev.pampa.mycloudgallery.feature.library.collections

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.library.contract.LibraryFilter
import dev.pampa.mycloudgallery.core.library.contract.ViewerItems
import dev.pampa.mycloudgallery.core.library.contract.ViewerRequest
import dev.pampa.mycloudgallery.core.library.noop.EmptyViewerItems
import dev.pampa.mycloudgallery.feature.library.collections.testing.FakeCollectionsWorld
import dev.pampa.mycloudgallery.feature.library.collections.testing.fakeGridEnvironment
import dev.pampa.mycloudgallery.feature.library.collections.testing.fakeThumbnailLoader
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.robolectric.annotation.Config

/** L'elenco delle Raccolte sul telefono: sei voci, nessun numero per i Nascosti, un tocco apre la raccolta. */
class CollectionsListPhoneTest : ComposeScreenTest() {

  private val world = FakeCollectionsWorld()

  private fun showList(
    canTrash: Boolean = true,
    onOpen: (CollectionKind) -> Unit = {},
  ) = show {
    CollectionsScreen(
      state = collectionsUiState(world.source.countsNow(), canTrash),
      contentPadding = PaddingValues(),
      backdropOut = {},
      onOpenCollection = onOpen,
      onOpenSettings = {},
      statusHeader = { Text("Stato globale") },
      detail = { kind -> Text("dettaglio ${kind.code}") },
    )
  }

  @Test
  fun mostraLeSeiRaccolteEIlLoroNome() {
    showList()

    listOf("Preferiti", "Video", "Screenshot", "WhatsApp", "Nascosti", "Eliminati di recente").forEach {
      composeRule.onNodeWithText(it).assertExists()
    }
    composeRule.onNodeWithText("Stato globale").assertExists()
  }

  @Test
  fun sulTelefonoNonCeIlPannelloDiDettaglio() {
    showList()

    composeRule.onAllNodesWithText("dettaglio", substring = true).assertCountEquals(0)
  }

  @Test
  fun leRaccolteFiltroDiconoQuantiElementiHannoEINascostiNo() {
    showList()

    val videos = world.repository.view(LibraryFilter.VIDEOS).value.size
    composeRule.onNodeWithText("$videos elementi").assertExists()
    composeRule.onNodeWithText("Protetti dallo sblocco del telefono").assertExists()
    composeRule.onNodeWithText("Il cestino di Android, protetto dallo sblocco").assertExists()
  }

  @Test
  fun unaRaccoltaSenzaElementiDiceNessunElemento() {
    show {
      CollectionsScreen(
        state = collectionsUiState(emptyMap(), canTrash = true),
        contentPadding = PaddingValues(),
        backdropOut = {},
        onOpenCollection = {},
        onOpenSettings = {},
        statusHeader = {},
        detail = {},
      )
    }

    composeRule.onAllNodesWithText("Nessun elemento").assertCountEquals(4)
  }

  @Test
  fun senzaIlCestinoDiSistemaLaVoceNonCe() {
    showList(canTrash = false)

    composeRule.onAllNodesWithText("Eliminati di recente").assertCountEquals(0)
    composeRule.onNodeWithText("Nascosti").assertExists()
  }

  @Test
  fun unTocco_apreLaRaccoltaGiusta() {
    val opened = mutableListOf<CollectionKind>()
    showList(onOpen = { opened += it })

    composeRule.onNodeWithText("Video").performClick()
    composeRule.onNodeWithText("Nascosti").performClick()
    composeRule.onNodeWithText("Eliminati di recente").performClick()

    assertThat(opened).containsExactly(CollectionKind.VIDEOS, CollectionKind.HIDDEN, CollectionKind.RECENTLY_DELETED).inOrder()
  }
}

/** Sul tablet l'elenco ha il dettaglio accanto (D17): le voci scelgono, non navigano, e i Nascosti non si aprono da soli. */
@Config(qualifiers = TABLET_QUALIFIERS)
class CollectionsListTabletTest : ComposeScreenTest() {

  private val world = FakeCollectionsWorld()
  private val opened = mutableListOf<CollectionKind>()

  private fun showList() = show {
    CollectionsScreen(
      state = collectionsUiState(world.source.countsNow(), canTrash = true),
      contentPadding = PaddingValues(),
      backdropOut = {},
      onOpenCollection = { opened += it },
      onOpenSettings = {},
      statusHeader = {},
      detail = { kind -> Text("dettaglio ${kind.code}") },
    )
  }

  @Test
  fun duePannelliConLaPrimaRaccoltaScelta() {
    showList()

    composeRule.onNodeWithText("dettaglio favorites").assertExists()
    composeRule.onNodeWithText("Video").assertExists()
  }

  @Test
  fun scegliereUnaVoceCambiaIlDettaglioENonNavigaNiente() {
    showList()

    composeRule.onNodeWithText("Screenshot").performClick()
    composeRule.waitForIdle()

    composeRule.onNodeWithText("dettaglio screenshots").assertExists()
    assertThat(opened).isEmpty()
  }

  @Test
  fun iNascostiNonSiScelgonoDaSoli() {
    showList()

    composeRule.onAllNodesWithText("dettaglio hidden").assertCountEquals(0)
    composeRule.onNodeWithText("Nascosti").performClick()
    composeRule.waitForIdle()
    composeRule.onNodeWithText("dettaglio hidden").assertExists()
  }
}

/** La pagina di una raccolta-filtro: la griglia della Libreria, lo stesso viewer, il vuoto spiegato, la selezione. */
class CollectionGridScreenTest : ComposeScreenTest() {

  private val world = FakeCollectionsWorld()
  private val loader = RecordingLoader(fakeThumbnailLoader())
  private val environment = fakeGridEnvironment(loader = loader)

  private fun showGrid(
    kind: CollectionKind,
    items: ViewerItems = world.source.items(kind.filter!!),
    onViewer: (ViewerRequest) -> Unit = {},
  ) = show {
    CollectionGridScreen(
      kind = kind,
      items = items,
      environment = environment,
      onFavorite = { ids, on -> runBlocking { world.actions.setFavorite(ids, on) } },
      onHide = { ids -> runBlocking { world.actions.setHidden(ids, true) } },
      onOpenViewer = onViewer,
      onBack = {},
    )
  }

  @Test
  fun mostraLeCelleDellaRaccoltaConIlTitolo() {
    showGrid(CollectionKind.VIDEOS)

    // L'etichetta in alto dice la sezione in vista e, sotto, il nome della raccolta ("Video · Oslo", G2).
    composeRule.onNodeWithText("Video", substring = true).assertExists()
    composeRule.onAllNodes(anyCell).fetchSemanticsNodes().also { assertThat(it).isNotEmpty() }
    // Tutto cio' che e' stato disegnato sta nella vista dei Video.
    val videos = idsOf(world.source.items(CollectionKind.VIDEOS.filter!!).view.value).toSet()
    assertThat(loader.seen).isNotEmpty()
    assertThat(videos).containsAtLeastElementsIn(loader.seen)
  }

  @Test
  fun unTocco_apreIlViewerConLaStessaVistaENonSicuro() {
    val requests = mutableListOf<ViewerRequest>()
    val items = world.source.items(CollectionKind.FAVORITES.filter!!)
    showGrid(CollectionKind.FAVORITES, items = items, onViewer = { requests += it })

    visibleCell().performClick()

    assertThat(requests).hasSize(1)
    val request = requests.single()
    // Lo stesso oggetto che la griglia scorre: scorrere nel viewer e' scorrere la raccolta.
    assertThat(request.items).isSameInstanceAs(items)
    assertThat(request.secure).isFalse()
    assertThat(request.items.view.value.viewIndexOf(request.start)).isAtLeast(0)
    assertThat(request.anchor.boundsOf(request.start)).isNotNull()
  }

  @Test
  fun senzaElementiDicePercheEVuota() {
    showGrid(CollectionKind.FAVORITES, items = EmptyViewerItems)

    composeRule.onNodeWithText("Nessun preferito").assertExists()
    composeRule.onAllNodes(anyCell).assertCountEquals(0)
  }

  @Test
  fun ognunaDelleQuattroRaccolteHaIlSuoVuoto() {
    val expected = mapOf(
      CollectionKind.FAVORITES to "Nessun preferito",
      CollectionKind.VIDEOS to "Nessun video",
      CollectionKind.SCREENSHOTS to "Nessuno screenshot",
      CollectionKind.WHATSAPP to "Niente da WhatsApp",
    )
    var current by mutableStateOf(CollectionKind.FAVORITES)
    show { CollectionGridScreen(current, EmptyViewerItems, environment, { _, _ -> }, {}, {}) }

    for ((kind, title) in expected) {
      composeRule.runOnUiThread { current = kind }
      composeRule.waitForIdle()
      composeRule.onNodeWithText(title).assertExists()
    }
  }

  @Test
  fun nascondereLaSelezioneLaTogliDaTuttoESpariscelaBarra() {
    showGrid(CollectionKind.FAVORITES)
    val before = idsOf(world.source.items(CollectionKind.FAVORITES.filter!!).view.value)

    visibleCell().performTouchInput { longClick() }
    composeRule.waitForIdle()
    composeRule.onNodeWithContentDescription("Nascondi").performClick()
    composeRule.waitForIdle()

    val after = idsOf(world.source.items(CollectionKind.FAVORITES.filter!!).view.value)
    assertThat(after.size).isEqualTo(before.size - 1)
    // Il nascosto e' uscito dall'indice e sta nei Nascosti.
    val gone = (before - after.toSet()).single()
    assertThat(world.hiddenIds).contains(gone)
    assertThat(world.visibleIds).doesNotContain(gone)
    composeRule.onAllNodes(hasContentDescription("Nascondi")).assertCountEquals(0)
  }

  @Test
  fun ilPulsanteSelezionaEntraNellaSelezioneEUnTocco_sceglieLaCella() {
    showGrid(CollectionKind.FAVORITES)
    val before = idsOf(world.source.items(CollectionKind.FAVORITES.filter!!).view.value)

    composeRule.onNodeWithContentDescription("Seleziona").performClick()
    composeRule.waitForIdle()
    composeRule.onNodeWithText("Seleziona elementi").assertExists()
    visibleCell().performClick()
    composeRule.waitForIdle()

    composeRule.onNodeWithText("1 selezionato").assertExists()
    composeRule.onNodeWithContentDescription("Nascondi").performClick()
    composeRule.waitForIdle()
    assertThat(idsOf(world.source.items(CollectionKind.FAVORITES.filter!!).view.value)).hasSize(before.size - 1)
    // Finita l'azione si esce dalla selezione: l'etichetta torna a dire la raccolta.
    composeRule.onNodeWithText("Preferiti", substring = true).assertExists()
  }

  @Test
  fun togliereUnPreferitoLoFaSparireDaiPreferitiMaNonDallaLibreria() {
    showGrid(CollectionKind.FAVORITES)
    val before = idsOf(world.source.items(LibraryFilter.FAVORITES).view.value)

    visibleCell().performTouchInput { longClick() }
    composeRule.waitForIdle()
    composeRule.onNodeWithContentDescription("Togli dai preferiti").performClick()
    composeRule.waitForIdle()

    val after = idsOf(world.source.items(LibraryFilter.FAVORITES).view.value)
    val removed = (before - after.toSet()).single()
    assertThat(world.source.items(LibraryFilter.ALL).view.value.viewIndexOf(removed)).isAtLeast(0)
  }
}
