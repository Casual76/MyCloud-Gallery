package dev.pampa.mycloudgallery.ai

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.database.CellContent
import dev.pampa.mycloudgallery.core.database.CellContentPage
import dev.pampa.mycloudgallery.core.model.ContentKey
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.search.index.CellKeySource
import dev.pampa.mycloudgallery.core.search.index.CellKeys
import dev.pampa.mycloudgallery.feature.library.library.SimilarSelectionOutcome
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * "Trova simili" dalla selezione della Libreria (Fase 7, correzioni F, risposta dell'utente del 27/10, TS-4, D30): ogni media scelto che e' una cella della Libreria
 * entra nella richiesta (mai un Nascosto, che non e' una cella); la ricerca parte se **almeno uno** ha un vettore e lascia fuori gli altri (Cerca lo dice); se
 * nessuno ne ha, non parte e la Libreria spiega perche' invece di aprire Cerca vuota. La richiesta arriva alla scheda Cerca con tutta la scelta.
 */
class SearchSelectionSimilarTest {

  private fun key(n: Int) = ContentKey(n.toString(16).padStart(64, '0'))

  private fun media(n: Int) = MediaId("00000000-0000-4000-8000-%012d".format(n))

  private fun cellKeys(vararg cells: CellContent): CellKeys {
    val source = object : CellKeySource {
      override suspend fun page(afterSeq: Long, limit: Int) = CellContentPage(if (afterSeq == 0L) cells.toList() else emptyList(), null)

      override suspend fun cellsOf(ids: Collection<MediaId>) = cells.filter { it.mediaId in ids }
    }
    return CellKeys(source).also { runBlocking { it.sync() } }
  }

  private fun cell(n: Int) = CellContent(n.toLong(), media(n), listOf(key(n)), emptyList())

  @Test
  fun conAlmenoUnVettoreLaRicercaParteConTuttiIMediaDellaCella() {
    val requests = SimilarRequests()
    val selection = SearchSelectionSimilar(cellKeys(cell(1), cell(2), cell(3)), requests) { it == key(1) }

    val outcome = selection.find(listOf(media(1), media(2), media(3)))

    // Parte, e porta anche i due senza vettore: li lascia fuori il motore, con il suo avviso.
    assertThat(outcome).isEqualTo(SimilarSelectionOutcome.STARTED)
    assertThat(requests.pending.value).containsExactly(media(1), media(2), media(3)).inOrder()
  }

  @Test
  fun senzaNessunVettoreNonParteEDiceChePrimaVannoAnalizzate() {
    val requests = SimilarRequests()
    val selection = SearchSelectionSimilar(cellKeys(cell(1), cell(2)), requests) { false }

    val outcome = selection.find(listOf(media(1), media(2)))

    assertThat(outcome).isEqualTo(SimilarSelectionOutcome.NOT_ANALYZED)
    assertThat(requests.pending.value).isNull()
  }

  @Test
  fun unNascostoOUnMediaCheNonEUnaCellaNonEntraNellaRichiesta() {
    val requests = SimilarRequests()
    val selection = SearchSelectionSimilar(cellKeys(cell(1), cell(2)), requests) { true }

    // Il 9 non e' una cella (un nascosto non lo e'): fuori dalla richiesta, e da solo non fa partire niente.
    assertThat(selection.find(listOf(media(1), media(9), media(2)))).isEqualTo(SimilarSelectionOutcome.STARTED)
    assertThat(requests.consume()).containsExactly(media(1), media(2)).inOrder()
    assertThat(selection.find(listOf(media(9)))).isEqualTo(SimilarSelectionOutcome.NOT_ANALYZED)
    assertThat(requests.pending.value).isNull()
  }

  @Test
  fun laRichiestaPortaTuttaLaSceltaECosumaUnaVoltaSola() {
    val requests = SimilarRequests()

    requests.request(listOf(media(4), media(5)))
    requests.request(emptyList())

    // Una richiesta vuota non cancella quella in attesa.
    assertThat(requests.pending.value).containsExactly(media(4), media(5)).inOrder()
    assertThat(requests.consume()).containsExactly(media(4), media(5)).inOrder()
    assertThat(requests.consume()).isNull()
  }
}
