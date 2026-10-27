package dev.pampa.mycloudgallery.faces

import android.content.ComponentCallbacks2
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.models.contract.ModelCatalog
import dev.pampa.mycloudgallery.work.modelsWanted
import org.junit.Test

/**
 * Fase 8, P13 (§2.2 "Rilascio", D8): i modelli dei volti si preparano solo dove Persone e' attiva qui, e rilevatore ed embedder si rilasciano a memoria
 * scarsa con la stessa regola del motore delle immagini (IX-5 della Fase 6).
 */
class FacesMemoryAndModelsTest {

  private val facesIds = ModelCatalog.FACES.map { it.id }

  @Test
  fun iModelliDeiVoltiNonSiPreparanoSePersoneNonEAttivaQui() {
    for (indexer in listOf(false, true)) {
      val wanted = modelsWanted(indexer = indexer, facesActive = false)
      assertThat(wanted).containsNoneIn(facesIds)
      assertThat(wanted).contains(ModelCatalog.QUERY_270M.id)
      assertThat(wanted).contains(ModelCatalog.CUTOUT_MAGICTOUCH.id)
      assertThat(wanted.contains(ModelCatalog.INDEX_440M.id)).isEqualTo(indexer)
    }
  }

  @Test
  fun doveSonoAttiviSiPreparanoEsattamenteIDueModelli() {
    for (indexer in listOf(false, true)) {
      val wanted = modelsWanted(indexer = indexer, facesActive = true)
      assertThat(wanted).containsAtLeastElementsIn(facesIds)
      assertThat(facesIds).hasSize(2)
      assertThat(wanted).containsNoDuplicates()
      // Gli altri modelli restano quelli di prima, nello stesso ordine.
      assertThat(wanted.filterNot { it in facesIds }).containsExactlyElementsIn(modelsWanted(indexer = indexer, facesActive = false)).inOrder()
    }
  }

  @Test
  fun aMemoriaScarsaIMotoriSiRilascianoCOmeQuelloDelleImmagini() {
    val release = listOf(
      ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW,
      ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL,
      ComponentCallbacks2.TRIM_MEMORY_MODERATE,
      ComponentCallbacks2.TRIM_MEMORY_COMPLETE,
    )
    val keep = listOf(
      ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE,
      ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN,
      ComponentCallbacks2.TRIM_MEMORY_BACKGROUND,
    )
    for (level in release) assertThat(FacesGraph.releasesEnginesAt(level)).isTrue()
    // Il passaggio in secondo piano non rilascia i motori: e' li' che gira il calcolo notturno.
    for (level in keep) assertThat(FacesGraph.releasesEnginesAt(level)).isFalse()
  }

  @Test
  fun leCopertineInMemoriaSiButtanoAppenaLAppNonSiVedePiu() {
    assertThat(FacesGraph.dropsCoversAt(ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE)).isFalse()
    assertThat(FacesGraph.dropsCoversAt(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN)).isTrue()
    assertThat(FacesGraph.dropsCoversAt(ComponentCallbacks2.TRIM_MEMORY_BACKGROUND)).isTrue()
    assertThat(FacesGraph.dropsCoversAt(ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW)).isTrue()
  }
}
