package dev.pampa.mycloudgallery.feature.library.utility

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.library.contract.LibraryFilter
import dev.pampa.mycloudgallery.core.library.contract.ViewerRequest
import dev.pampa.mycloudgallery.core.library.noop.EmptyViewerItems
import dev.pampa.mycloudgallery.core.model.CellFlags
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.model.MediaSource
import dev.pampa.mycloudgallery.feature.library.collections.CollectionEntries
import dev.pampa.mycloudgallery.feature.library.collections.CollectionEntry
import dev.pampa.mycloudgallery.feature.library.collections.CollectionEntryGroup
import dev.pampa.mycloudgallery.feature.library.collections.ComposeScreenTest
import dev.pampa.mycloudgallery.feature.library.collections.RecordingLoader
import dev.pampa.mycloudgallery.feature.library.collections.idsOf
import dev.pampa.mycloudgallery.feature.library.collections.testing.FakeCollectionsWorld
import dev.pampa.mycloudgallery.feature.library.collections.testing.fakeGridEnvironment
import dev.pampa.mycloudgallery.feature.library.collections.testing.fakeThumbnailLoader
import kotlinx.coroutines.runBlocking
import org.junit.Test

/** Le pagine dell'Utility come dati (D24): codici stabili, filtri, e le voci che le Raccolte mostrano. */
class UtilityPagesDataTest {

  @Test
  fun lePagineHannoCodiciStabili() {
    assertThat(UtilityPage.entries.map { it.code })
      .containsExactly("edited", "duplicates", "recently_deleted")
      .inOrder()
    assertThat(UtilityPage.entries.map { it.code }.toSet()).hasSize(UtilityPage.entries.size)
  }

  @Test
  fun ilCodiceVaEVieneSenzaPerdersi() {
    for (page in UtilityPage.entries) {
      assertThat(UtilityPage.fromCode(page.code)).isEqualTo(page)
      assertThat(UtilityPage.fromEntryCode(page.entryCode)).isEqualTo(page)
    }
    // Un codice sconosciuto (una versione piu' nuova dell'app) o di un'altra voce non e' una pagina dell'Utility.
    assertThat(UtilityPage.fromCode("sconosciuto")).isNull()
    assertThat(UtilityPage.fromCode(null)).isNull()
    assertThat(UtilityPage.fromEntryCode("nas/folders")).isNull()
    assertThat(UtilityPage.fromEntryCode("utility/sconosciuto")).isNull()
    assertThat(UtilityPage.fromEntryCode(null)).isNull()
  }

  @Test
  fun laPaginaFiltroEModificate_GliAltriFiltriStannoNellaSchedaAlbum() {
    // H5 (11/11): Screenshot, Video e WhatsApp erano doppioni della scheda Album (Screenshot, Video, Messaggistica) e sono usciti.
    assertThat(UtilityPage.filters).containsExactly(UtilityPage.EDITED)
    assertThat(UtilityPage.EDITED.filter).isEqualTo(LibraryFilter.EDITED)
    // Una rotta o una scelta del tablet salvata con il codice di una pagina uscita non apre niente (la rotta torna indietro da sola).
    for (old in listOf("screenshots", "videos", "whatsapp")) {
      assertThat(UtilityPage.fromCode(old)).isNull()
      assertThat(UtilityPage.fromEntryCode("utility/$old")).isNull()
    }
    // Doppioni ed Eliminati di recente non passano dall'indice.
    assertThat(UtilityPage.DUPLICATES.filter).isNull()
    assertThat(UtilityPage.RECENTLY_DELETED.filter).isNull()
  }

  @Test
  fun ilFiltroModificateAccettaSoloLeCelleConIlBitEdited() {
    assertThat(LibraryFilter.EDITED.accepts(MediaKind.PHOTO, MediaSource.CAMERA, CellFlags.EDITED)).isTrue()
    assertThat(LibraryFilter.EDITED.accepts(MediaKind.VIDEO, MediaSource.SCREENSHOT, CellFlags.EDITED or CellFlags.FAVORITE)).isTrue()
    assertThat(LibraryFilter.EDITED.accepts(MediaKind.PHOTO, MediaSource.CAMERA, 0)).isFalse()
    assertThat(LibraryFilter.EDITED.accepts(MediaKind.VIDEO, MediaSource.CAMERA, CellFlags.FAVORITE)).isFalse()
  }
}

/** Le voci dell'Utility nell'elenco delle Raccolte: testi, conteggi, e la voce degli Eliminati di recente solo dove c'e' un cestino. */
class UtilityEntriesTest : ComposeScreenTest() {

  private lateinit var group: CollectionEntryGroup

  @Composable
  private fun Capture(counts: Map<UtilityPage, Int>, canTrash: Boolean) {
    group = utilityEntryGroup(counts, canTrash)
  }

  private fun compose(counts: Map<UtilityPage, Int> = emptyMap(), canTrash: Boolean = true): List<CollectionEntry> {
    show { Capture(counts, canTrash) }
    return group.entries
  }

  @Test
  fun ilGruppoUtilityHaLeTreVoci_ConIlTitoloGiusto() {
    val entries = compose()

    assertThat(group.title).isEqualTo("Utility")
    // H5: senza i doppioni della scheda Album (Screenshot, Video, WhatsApp).
    assertThat(entries.map { it.title })
      .containsExactly("Modificate", "Doppioni", "Eliminati di recente")
      .inOrder()
    // I codici sono unici e riportano alla pagina (il pannello salvato di un tablet).
    assertThat(CollectionEntries.codes(listOf(group))).containsNoDuplicates()
    assertThat(entries.map { UtilityPage.fromEntryCode(it.code) }).containsExactlyElementsIn(UtilityPage.entries).inOrder()
  }

  @Test
  fun lePagineFiltroDiconoQuanteSono_DoppioniEEliminatiNo() {
    val bySubtitle = compose(counts = mapOf(UtilityPage.EDITED to 1)).associate { UtilityPage.fromEntryCode(it.code)!! to it.subtitle }
    assertThat(bySubtitle.getValue(UtilityPage.EDITED)).isEqualTo("1 elemento")
    assertThat(compose(counts = mapOf(UtilityPage.EDITED to 12)).first { it.code == UtilityPage.EDITED.entryCode }.subtitle).isEqualTo("12 elementi")
    // Un conteggio che non c'e' (il flusso non ha ancora risposto) non finge uno zero.
    assertThat(compose().first { it.code == UtilityPage.EDITED.entryCode }.subtitle).isEqualTo("Nessun elemento")
    val entries = compose(counts = mapOf(UtilityPage.EDITED to 0))
    // Doppioni ed Eliminati di recente non dicono mai quanti sono: dal di fuori non si deve capire (E-H1).
    assertThat(bySubtitle.getValue(UtilityPage.DUPLICATES)).doesNotContainMatch("\\d")
    assertThat(bySubtitle.getValue(UtilityPage.RECENTLY_DELETED)).doesNotContainMatch("\\d")
    assertThat(entries.map { it.badge }.toSet()).containsExactly(0)
  }

  @Test
  fun lAvvisoDelNasGliEliminatiDiRecenteDiconoIDueCestini() {
    val entries = compose()

    val trash = entries.single { UtilityPage.fromEntryCode(it.code) == UtilityPage.RECENTLY_DELETED }
    assertThat(trash.subtitle).contains("NAS")
  }

  @Test
  fun senzaIlCestinoLaVoceNonCe() {
    val entries = compose(canTrash = false)

    assertThat(entries.map { UtilityPage.fromEntryCode(it.code) }).doesNotContain(UtilityPage.RECENTLY_DELETED)
    assertThat(entries).hasSize(5)
  }
}

/**
 * La pagina di una pagina-filtro dell'Utility: la griglia della Libreria con il filtro `EDITED`, lo stesso viewer, il vuoto spiegato. Come per le
 * Raccolte, nulla che sia nascosto compare (E-H1).
 */
class UtilityFilterScreenTest : ComposeScreenTest() {

  private val world = FakeCollectionsWorld()
  private val loader = RecordingLoader(fakeThumbnailLoader())
  private val environment = fakeGridEnvironment(loader = loader)

  private fun showPage(
    page: UtilityPage,
    items: dev.pampa.mycloudgallery.core.library.contract.ViewerItems = world.source.items(page.filter!!),
    onViewer: (ViewerRequest) -> Unit = {},
  ) = show {
    UtilityFilterScreen(
      page = page,
      items = items,
      environment = environment,
      onFavorite = { ids, on -> runBlocking { world.actions.setFavorite(ids, on) } },
      onHide = { ids -> runBlocking { world.actions.setHidden(ids, true) } },
      onOpenViewer = onViewer,
      onBack = {},
    )
  }

  @Test
  fun leModificateMostranoSoloLeCelleModificate_EMaiUnNascosto() {
    val edited = world.source.items(LibraryFilter.EDITED).view.value
    assertThat(edited.size).isGreaterThan(0)
    showPage(UtilityPage.EDITED)

    composeRule.onNodeWithText("Modificate", substring = true).assertExists()
    composeRule.onAllNodesWithText("Nessuna foto modificata").assertCountEquals(0)
    val shown = idsOf(edited).toSet()
    assertThat(loader.seen).isNotEmpty()
    assertThat(shown).containsAtLeastElementsIn(loader.seen)
    assertThat(loader.seen.intersect(world.hiddenIds)).isEmpty()
  }

  @Test
  fun ogniCellaDelleModificateHaIlBitEdited() {
    val view = world.source.items(LibraryFilter.EDITED).view.value
    for (i in 0 until view.size) {
      assertThat(CellFlags.has(view.snapshot.flagsAt(view.snapshotIndex(i)), CellFlags.EDITED)).isTrue()
    }
  }

  @Test
  fun senzaModificateDicePercheEVuota() {
    showPage(UtilityPage.EDITED, items = EmptyViewerItems)

    composeRule.onNodeWithText("Nessuna foto modificata").assertExists()
    composeRule.onAllNodesWithText("Foto", substring = true).assertCountEquals(0)
  }

  @Test
  fun laPaginaFiltroHaIlSuoVuoto() {
    val expected = mapOf(UtilityPage.EDITED to "Nessuna foto modificata")
    var current by mutableStateOf(UtilityPage.EDITED)
    show { UtilityFilterScreen(current, EmptyViewerItems, environment, { _, _ -> }, {}, {}) }

    for ((page, title) in expected) {
      composeRule.runOnUiThread { current = page }
      composeRule.waitForIdle()
      composeRule.onNodeWithText(title).assertExists()
    }
  }

  @Test
  fun unTocco_apreIlViewerConLaStessaVistaENonSicuro() {
    val requests = mutableListOf<ViewerRequest>()
    val items = world.source.items(LibraryFilter.EDITED)
    showPage(UtilityPage.EDITED, items = items, onViewer = { requests += it })

    visibleCell().performClick()

    assertThat(requests).hasSize(1)
    assertThat(requests.single().items).isSameInstanceAs(items)
    assertThat(requests.single().secure).isFalse()
  }

  @Test
  fun nascondereLaSelezioneLaTogliDaTutto() {
    showPage(UtilityPage.EDITED)
    val before = idsOf(world.source.items(LibraryFilter.EDITED).view.value)

    visibleCell().performTouchInput { longClick() }
    composeRule.waitForIdle()
    composeRule.onNodeWithContentDescription("Nascondi").performClick()
    composeRule.waitForIdle()

    val after = idsOf(world.source.items(LibraryFilter.EDITED).view.value)
    assertThat(after.size).isEqualTo(before.size - 1)
    val gone = (before - after.toSet()).single()
    assertThat(world.hiddenIds).contains(gone)
    assertThat(world.visibleIds).doesNotContain(gone)
  }
}
