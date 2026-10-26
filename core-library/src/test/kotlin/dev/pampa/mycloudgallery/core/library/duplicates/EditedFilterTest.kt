package dev.pampa.mycloudgallery.core.library.duplicates

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.library.contract.LibraryFilter
import dev.pampa.mycloudgallery.core.library.data.CatalogTimelineSource
import dev.pampa.mycloudgallery.core.library.index.FilteredTimelineView
import dev.pampa.mycloudgallery.core.library.similar.SimilarCatalogTestBase
import dev.pampa.mycloudgallery.core.model.CellFlags
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.QuickHashAlgorithm
import dev.pampa.mycloudgallery.core.model.Sha256
import kotlin.random.Random
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * "Modificate" (Fase 7, P8, D24, F75): il filtro `LibraryFilter.EDITED` sul catalogo vero. Una cella e' "modificata" quando la versione corrente non
 * e' l'originale (`CellFlags.EDITED`); la pagina dell'Utility mostra esattamente quelle, con le versioni seminate dalle prove.
 */
@RunWith(RobolectricTestRunner::class)
class EditedFilterTest : SimilarCatalogTestBase() {

  private suspend fun edit(media: MediaId, seed: Int) {
    val bytes = Random(seed).nextBytes(2_500)
    val added = catalog.addEditedVersion(media, versionOf(media), Sha256.of(bytes), QuickHashAlgorithm.ofContent(bytes), bytes.size.toLong())
    checkNotNull(added)
  }

  private suspend fun editedIds(): List<MediaId> {
    val snapshot = CatalogTimelineSource(db, catalog).buildIndex()
    val view = FilteredTimelineView(snapshot, LibraryFilter.EDITED)
    return (0 until view.size).map { snapshot.idAt(view.snapshotIndex(it)) }
  }

  @Test
  fun laPaginaModificateMostraSoloLeCelleConUnaVersioneModificata() = runBlocking<Unit> {
    val plain = phoneShot()
    val edited = phoneShot()
    val editedAndHidden = phoneShot()
    val screenshot = phoneShot()
    edit(edited, 1)
    edit(editedAndHidden, 2)
    catalog.setPersonalFlags(listOf(editedAndHidden)) { it.copy(hidden = true) }

    val shown = editedIds()

    // Una sola: la modificata. Non l'originale, e mai la modificata nascosta (l'indice non ha nascosti, E-H1).
    assertThat(shown).containsExactly(edited)
    assertThat(shown).doesNotContain(plain)
    assertThat(shown).doesNotContain(editedAndHidden)
    assertThat(shown).doesNotContain(screenshot)
    val snapshot = CatalogTimelineSource(db, catalog).buildIndex()
    assertThat(snapshot.hasFlag(snapshot.indexOf(edited), CellFlags.EDITED)).isTrue()
    assertThat(snapshot.hasFlag(snapshot.indexOf(plain), CellFlags.EDITED)).isFalse()
  }

  @Test
  fun ripristinareLOriginaleLaTogliDalleModificate() = runBlocking<Unit> {
    val media = phoneShot()
    edit(media, 3)
    assertThat(editedIds()).containsExactly(media)

    catalog.restoreOriginal(media)

    assertThat(editedIds()).isEmpty()
  }

  @Test
  fun senzaModificateLaPaginaEVuota() = runBlocking<Unit> {
    phoneShot()
    phoneShot()
    assertThat(editedIds()).isEmpty()
  }
}
