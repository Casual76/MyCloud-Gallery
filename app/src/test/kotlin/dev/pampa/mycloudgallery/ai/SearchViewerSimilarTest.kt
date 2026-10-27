package dev.pampa.mycloudgallery.ai

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.database.CellContent
import dev.pampa.mycloudgallery.core.database.CellContentPage
import dev.pampa.mycloudgallery.core.database.MemberContent
import dev.pampa.mycloudgallery.core.model.ContentKey
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.search.index.CellKeySource
import dev.pampa.mycloudgallery.core.search.index.CellKeys
import dev.pampa.mycloudgallery.feature.viewer.menu.SimilarAvailability
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * Fase 6, P11b (E-Q14, §1.5 "Azione Trova simili"): la voce "Trova simili" del viewer c'e' per ogni cella della Libreria; se il media non ha
 * ancora un vettore la voce e' **spiegata** (al tocco dice perche' non si puo') invece di aprire una ricerca vuota. Mai per un nascosto.
 */
class SearchViewerSimilarTest {

  private val a = media(1)

  private fun key(n: Int) = ContentKey(n.toString(16).padStart(64, '0'))

  private fun media(n: Int) = MediaId("00000000-0000-4000-8000-%012d".format(n))

  private fun cell(id: MediaId, own: List<ContentKey>, members: List<List<ContentKey>> = emptyList()) =
    CellContent(0, id, own, members.mapIndexed { i, keys -> MemberContent(media(100 + i), keys) })

  private fun cellKeys(vararg cells: CellContent): CellKeys {
    val source = object : CellKeySource {
      override suspend fun page(afterSeq: Long, limit: Int) = CellContentPage(if (afterSeq == 0L) cells.toList() else emptyList(), null)

      override suspend fun cellsOf(ids: Collection<MediaId>) = cells.filter { it.mediaId in ids }
    }
    return CellKeys(source).also { runBlocking { it.sync() } }
  }

  @Test
  fun unMediaConIlVettoreHaLaVoceCheApreLaRicerca() {
    val similar = SearchViewerSimilar(cellKeys(cell(a, listOf(key(1)))), SimilarRequests()) { it == key(1) }

    assertThat(similar.availability(a)).isEqualTo(SimilarAvailability.AVAILABLE)
  }

  @Test
  fun unMediaSenzaVettoreHaLaVoceSpiegata() {
    val similar = SearchViewerSimilar(cellKeys(cell(a, listOf(key(1)))), SimilarRequests()) { false }

    assertThat(similar.availability(a)).isEqualTo(SimilarAvailability.EXPLAINED)
  }

  @Test
  fun bastaUnaChiaveDellaCellaCheLaCopertinaTieneIMembriDelGruppo() {
    // La ricerca dei simili parte da tutti i vettori della cella: anche quello di un membro del gruppo (raffica) vale.
    val keys = cellKeys(cell(a, listOf(key(1), key(2)), members = listOf(listOf(key(3)))))
    val withMember = SearchViewerSimilar(keys, SimilarRequests()) { it == key(3) }
    val withOldVersion = SearchViewerSimilar(keys, SimilarRequests()) { it == key(2) }

    assertThat(withMember.availability(a)).isEqualTo(SimilarAvailability.AVAILABLE)
    assertThat(withOldVersion.availability(a)).isEqualTo(SimilarAvailability.AVAILABLE)
  }

  @Test
  fun unNascostoOUnMediaCheNonEUnaCellaNonHaLaVoce() {
    val similar = SearchViewerSimilar(cellKeys(cell(a, listOf(key(1)))), SimilarRequests()) { true }

    assertThat(similar.availability(media(99))).isEqualTo(SimilarAvailability.HIDDEN)
  }

  @Test
  fun laRichiestaPortaAllaSchedaCerca() {
    val requests = SimilarRequests()
    val similar = SearchViewerSimilar(cellKeys(cell(a, listOf(key(1)))), requests)

    similar.findSimilar(a)

    assertThat(requests.pending.value).isEqualTo(listOf(a))
    assertThat(requests.consume()).isEqualTo(listOf(a))
    assertThat(requests.pending.value).isNull()
  }
}
