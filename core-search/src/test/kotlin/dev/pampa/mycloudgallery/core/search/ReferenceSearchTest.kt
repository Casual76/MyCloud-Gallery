package dev.pampa.mycloudgallery.core.search

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.ai.testing.AiTestInputs
import dev.pampa.mycloudgallery.core.ai.testing.FakeImageEmbedder
import dev.pampa.mycloudgallery.core.ai.testing.FakeTextEmbedder
import dev.pampa.mycloudgallery.core.library.contract.TimelineView
import dev.pampa.mycloudgallery.core.library.testing.SyntheticLibrary
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.search.contract.ResultOrder
import dev.pampa.mycloudgallery.core.search.contract.SearchNotice
import dev.pampa.mycloudgallery.core.search.contract.SearchRequest
import dev.pampa.mycloudgallery.core.search.contract.SearchThresholds
import dev.pampa.mycloudgallery.core.search.testing.FakeSearchData
import dev.pampa.mycloudgallery.core.search.testing.FixedToday
import dev.pampa.mycloudgallery.core.search.testing.InMemorySearchEngine
import dev.pampa.mycloudgallery.core.search.testing.SimpleQueryParser
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Fase 6 (P0, §5.2): l'esecutore di riferimento della ricerca sui motori finti. Contenuto con la soglia relativa, testo, solo filtri,
 * nessun motore, "Trova simili", e soprattutto **nascosti mai** (D29) su ogni percorso.
 */
class ReferenceSearchTest {

  private val library = SyntheticLibrary(seed = 21, count = 120)
  private val snapshot = library.snapshot
  private val data = FakeSearchData(snapshot)
  private val images = FakeImageEmbedder()
  private val dogs = (0 until snapshot.size).filter { it % 12 == 3 }
  private val receipts = (0 until snapshot.size).filter { it % 17 == 5 }

  private fun id(i: Int): MediaId = snapshot.idAt(i)

  private suspend fun fill() {
    val fillers = listOf("tramonto", "montagna", "torta", "fiore", "albero", "auto", "pizza", "bicicletta", "gatto", "neve")
    for (i in 0 until snapshot.size) {
      val concepts = when (i) {
        in dogs -> "cane spiaggia"
        else -> fillers[i % fillers.size]
      }
      // L'istante "di fotogramma" diverso per ogni cella da' a ognuna il suo rumore: niente due immagini identiche.
      data.vectors[id(i)] = images.embed(listOf(AiTestInputs.image(concepts, frameMs = i)))
    }
    receipts.forEach { data.texts[id(it)] = "SCONTRINO Esselunga — totale 12,50 €" }
  }

  private fun engine(withModel: Boolean = true) =
    InMemorySearchEngine(data, if (withModel) FakeTextEmbedder() else null, SimpleQueryParser(), FixedToday())

  private fun ids(view: TimelineView): List<MediaId> = (0 until view.size).map { view.snapshot.idAt(view.snapshotIndex(it)) }

  @Test
  fun ilContenutoTrovaICaniSicuriEINascostiMai() = runTest {
    fill()
    val hiddenDog = id(dogs.first())
    data.hiddenIds += hiddenDog
    val results = engine().search(SearchRequest("cane in spiaggia", showLikely = true))
    val found = ids(results.confident)
    assertThat(found).containsExactlyElementsIn(dogs.drop(1).map { id(it) })
    assertThat(found).doesNotContain(hiddenDog)
    assertThat(ids(results.likely)).doesNotContain(hiddenDog)
    // Recenti (predefinito): dal piu' recente (l'indice 0 dell'istantanea e' il piu' recente).
    assertThat(found.map { snapshot.indexOf(it) }).isInOrder()
  }

  @Test
  fun ilTestoLettoTrovaGliScontriniAncheSenzaModello() = runTest {
    fill()
    val results = engine(withModel = false).search(SearchRequest("scontrino esselunga"))
    assertThat(ids(results.confident)).containsExactlyElementsIn(receipts.map { id(it) })
    assertThat(results.notices).contains(SearchNotice.OnlyFiltersAndText)
    val exact = engine().search(SearchRequest("\"totale 12 50 euro\""))
    assertThat(ids(exact.confident)).containsExactlyElementsIn(receipts.map { id(it) })
    assertThat(ids(engine().search(SearchRequest("\"totale 13 euro\"")).confident)).isEmpty()
  }

  @Test
  fun soloFiltriDannoTuttiICandidatiPerData() = runTest {
    fill()
    val videos = (0 until snapshot.size).filter { snapshot.kindAt(it) == MediaKind.VIDEO }.map { id(it) }
    val results = engine().search(SearchRequest("video"))
    assertThat(ids(results.confident)).containsExactlyElementsIn(videos).inOrder()
    assertThat(results.likely.size).isEqualTo(0)
  }

  @Test
  fun trovaSimiliEscludeIlMediaEINascosti() = runTest {
    fill()
    val dog = id(dogs[1])
    val similar = engine().similar(dog, ResultOrder.RELEVANT)
    assertThat(ids(similar.confident)).doesNotContain(dog)
    assertThat(ids(similar.confident)).containsAtLeastElementsIn(dogs.drop(2).map { id(it) })
    data.hiddenIds += dog
    val fromHidden = engine().similar(dog)
    assertThat(fromHidden.confident.size + fromHidden.likely.size).isEqualTo(0)
  }

  /** Una distribuzione piatta (la cosa cercata non c'e') non da' sicuri (D27, E-Q12). */
  @Test
  fun unaRicercaSenzaRispostaNonInventaSicuri() = runTest {
    fill()
    val results = engine().search(SearchRequest("astronave"))
    assertThat(results.confident.size).isEqualTo(0)
  }

  /** Le soglie di partenza del §2.4 (calibrate poi da P9): un test le rilegge, come chiede la riga di P6. */
  @Test
  fun leSoglieDiPartenzaSonoQuelleDelProgetto() {
    assertThat(SearchThresholds.ZC).isEqualTo(3.5f)
    assertThat(SearchThresholds.ZL).isEqualTo(2.0f)
    assertThat(SearchThresholds.S_MIN).isEqualTo(0f)
    assertThat(SearchThresholds.MAX_LIKELY).isEqualTo(300)
    assertThat(SearchThresholds.COVERAGE_WARN_BELOW).isEqualTo(0.98f)
  }
}
