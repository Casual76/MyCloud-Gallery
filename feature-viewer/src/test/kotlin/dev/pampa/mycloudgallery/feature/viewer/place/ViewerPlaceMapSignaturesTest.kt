package dev.pampa.mycloudgallery.feature.viewer.place

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.feature.viewer.ViewerServices
import dev.pampa.mycloudgallery.feature.viewer.testing.FakeViewerPlaceMap
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Fase 9 (P0, D29, D30, E-H9): la porta della mappa del viewer. Di serie non c'e' (nessuna riga "Mostra sulla mappa", nessuna mini-mappa); lo
 * stato non mostra niente nel `toString()`; il fake registra i media chiesti, cosi' P4 prova che dai Nascosti non se ne chiede nessuno.
 */
class ViewerPlaceMapSignaturesTest {

  private val media = MediaId.random()

  @Test
  fun diSerieNessunaMappa() {
    assertThat(ViewerServices().placeMap).isNull()
  }

  @Test
  fun ilFakeRispondeConLaPosizioneONessunaPosizione() = runTest {
    val fake = FakeViewerPlaceMap().locate(media)
    assertThat(fake.place(media, 320).first()).isInstanceOf(ViewerMapPlace.Located::class.java)
    assertThat(fake.place(MediaId.random(), 320).first()).isEqualTo(ViewerMapPlace.NoPosition)
    assertThat(fake.asked).hasSize(2)
    fake.show(media)
    assertThat(fake.shown).containsExactly(media)
    assertThrows(IllegalArgumentException::class.java) { fake.place(media, 0) }
  }

  @Test
  fun loStatoNonMostraNiente() {
    assertThat(ViewerMapPlace.Located(null).toString()).isEqualTo("ViewerMapPlace.Located(false)")
  }
}
