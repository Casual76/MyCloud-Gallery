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

/**
 * Le righe delle Raccolte come in produzione (CS-17): l'Utility dal costruttore vero e i Nascosti che la scheda fa da sola. Le raccolte-filtro del
 * vecchio "Telefono" stanno nella scheda Album dalla G2 e qui non ci sono.
 */
private fun utilitySection(canTrash: Boolean = true, counts: Map<dev.pampa.mycloudgallery.feature.library.utility.UtilityPage, Int> = emptyMap()): CollectionsSection {
  val resources = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.app.Application>().resources
  return CollectionsLayout.sectionOf(CollectionSections.UTILITY, dev.pampa.mycloudgallery.feature.library.utility.utilityEntryGroup(resources, counts, canTrash))!!
}

/** Senza il cestino della scheda (in produzione lo toglie il fornitore vuoto di `:app`): gli Eliminati di recente stanno nell'Utility. */
private fun productionRows(canTrash: Boolean = true, counts: Map<dev.pampa.mycloudgallery.feature.library.utility.UtilityPage, Int> = emptyMap()) =
  layoutOf(canTrash = false, providers = listOf(utilitySection(canTrash, counts)))

/** L'elenco delle Raccolte sul telefono: le voci di produzione, nessun numero per i Nascosti, un tocco apre la pagina giusta. */
class CollectionsListPhoneTest : ComposeScreenTest() {

  private val openedKinds = mutableListOf<CollectionKind>()
  private val openedEntries = mutableListOf<String>()

  private fun showList(state: CollectionsLayoutState = productionRows()) = show {
    CollectionsScreen(
      state = state,
      contentPadding = PaddingValues(),
      backdropOut = {},
      onOpenCollection = { openedKinds += it },
      onOpenSettings = {},
      statusHeader = { Text("Stato globale") },
      detail = { kind -> Text("dettaglio ${kind.code}") },
      onOpenEntry = { openedEntries += it.code },
    )
  }

  @Test
  fun mostraLeVociEIlLoroNome_SenzaIDoppioniDellaSchedaAlbum() {
    showList()

    listOf("Modificate", "Doppioni", "Eliminati di recente", "Nascosti").forEach { composeRule.onNodeWithText(it).assertExists() }
    // CS-17: Preferiti, Fotocamera, Video, Screenshot e Messaggistica sono della scheda Album.
    listOf("Preferiti", "Fotocamera", "Video", "Screenshot", "Messaggistica").forEach { composeRule.onAllNodesWithText(it).assertCountEquals(0) }
    composeRule.onNodeWithText("Stato globale").assertExists()
  }

  @Test
  fun sulTelefonoNonCeIlPannelloDiDettaglio() {
    showList()

    composeRule.onAllNodesWithText("dettaglio", substring = true).assertCountEquals(0)
  }

  @Test
  fun lePagineFiltroDiconoQuantiElementiHannoEINascostiNo() {
    showList(productionRows(counts = mapOf(dev.pampa.mycloudgallery.feature.library.utility.UtilityPage.EDITED to 37)))

    // I1 (fase I): sulle piastrelle si vede l'etichetta; il conto e la frase li dice TalkBack. I Nascosti non dicono mai quanti sono (E-H1).
    composeRule.onNode(hasContentDescription("Modificate, 37 elementi")).assertExists()
    composeRule.onNode(hasContentDescription("Nascosti, Dietro lo sblocco")).assertExists()
    composeRule.onAllNodesWithText("37 elementi").assertCountEquals(0)
  }

  @Test
  fun unaPaginaSenzaConteggioDiceNessunElemento() {
    showList()

    composeRule.onAllNodes(hasContentDescription("Nessun elemento", substring = true)).assertCountEquals(1)
  }

  @Test
  fun senzaUnCestinoLaVoceNonCe() {
    showList(productionRows(canTrash = false))

    composeRule.onAllNodesWithText("Eliminati di recente").assertCountEquals(0)
    composeRule.onNodeWithText("Nascosti").assertExists()
  }

  @Test
  fun unTocco_apreLaPaginaGiusta() {
    showList()

    composeRule.onNodeWithText("Modificate").performClick()
    composeRule.onNodeWithText("Nascosti").performClick()

    assertThat(openedEntries).containsExactly("utility/edited")
    assertThat(openedKinds).containsExactly(CollectionKind.HIDDEN)
  }
}

/** Sul tablet l'elenco ha il dettaglio accanto (D17): le voci scelgono, non navigano, e niente si apre da solo. */
@Config(qualifiers = TABLET_QUALIFIERS)
class CollectionsListTabletTest : ComposeScreenTest() {

  private val opened = mutableListOf<String>()

  private fun showList() = show {
    CollectionsScreen(
      state = productionRows(),
      contentPadding = PaddingValues(),
      backdropOut = {},
      onOpenCollection = { opened += it.code },
      onOpenSettings = {},
      statusHeader = {},
      detail = { kind -> Text("dettaglio ${kind.code}") },
      onOpenEntry = { opened += it.code },
      entryDetail = { entry -> Text("voce ${entry.code}") },
    )
  }

  @Test
  fun duePannelli_SenzaUnaSceltaIlDettaglioDiceDiSceglierne() {
    showList()

    composeRule.onNodeWithContentDescription("Scegli una raccolta").assertExists()
    composeRule.onNodeWithText("Modificate").assertExists()
  }

  @Test
  fun scegliereUnaVoceCambiaIlDettaglioENonNavigaNiente() {
    showList()

    composeRule.onNodeWithText("Modificate").performClick()
    composeRule.waitForIdle()
    composeRule.onNodeWithText("voce utility/edited").assertExists()

    composeRule.onNodeWithText("Nascosti").performClick()
    composeRule.waitForIdle()
    composeRule.onNodeWithText("dettaglio hidden").assertExists()
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
      CollectionKind.WHATSAPP to "Niente dalle app di messaggi",
      CollectionKind.CAMERA to "Nessuna foto della fotocamera",
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
