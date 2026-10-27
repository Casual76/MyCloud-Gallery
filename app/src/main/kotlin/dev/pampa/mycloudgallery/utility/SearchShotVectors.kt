package dev.pampa.mycloudgallery.utility

import dev.pampa.mycloudgallery.core.common.log.GalleryLog
import dev.pampa.mycloudgallery.core.library.duplicates.ShotVectorSource
import dev.pampa.mycloudgallery.core.model.EmbeddingSpace
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.SpaceCode
import dev.pampa.mycloudgallery.core.search.contract.SearchData
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * I vettori della ricerca (Fase 6, `SearchData.vectors`) per i Doppioni simili (Fase 7, P8, D25; debito del 27/10: la porta `ShotVectorSource` non era
 * legata in produzione e la pagina giudicava solo con la firma visiva).
 *
 * - **Stesso spazio della ricerca** ([space]: lo spazio corrente di `SpaceCode`). Un indice in un altro spazio non risponde (`IndexSearchData`), e i
 *   vettori di spazi diversi non si confrontano mai (E-I4).
 * - **Un vettore per scatto.** Una foto ha un vettore; una cella con piu' vettori (un video, un gruppo) non e' un candidato dei Doppioni: se arriva lo
 *   stesso, non si sceglie un vettore a caso e vale la firma ("simili" solo se si sa, mai nel dubbio).
 * - **Ripiego sulla firma.** Chi non ha il vettore (indice non ancora arrivato, dispositivo che non indicizza, ricetta piu' vecchia) manca dalla mappa e
 *   `CatalogSimilarShots` lo giudica con la firma visiva come prima. Un errore della ricerca vale "nessun vettore", mai un errore della pagina.
 * - **Mai sul thread principale.** Sia la costruzione tardiva della ricerca ([data]: l'Hilt la crea al primo uso) sia la lettura dei vettori (file
 *   dell'indice, chiavi delle celle) girano su [compute].
 *
 * Non filtra i nascosti: i vettori sono numeri e non escono dalla pagina; il filtro finale e' di `CatalogSimilarShots` (D22), l'ultimo passo prima di consegnare.
 *
 * @param data la ricerca, al primo uso (null = non c'e': nessun vettore).
 */
class SearchShotVectors(
  private val data: () -> SearchData?,
  private val space: EmbeddingSpace = SpaceCode.CURRENT.space,
  private val compute: CoroutineDispatcher = Dispatchers.Default,
) : ShotVectorSource {

  override suspend fun vectorsOf(media: Collection<MediaId>): Map<MediaId, FloatArray> {
    if (media.isEmpty()) return emptyMap()
    return try {
      withContext(compute) {
        val search = data() ?: return@withContext emptyMap()
        val out = HashMap<MediaId, FloatArray>()
        for ((id, vectors) in search.vectors(media, space)) {
          vectors.singleOrNull()?.let { out[id] = it }
        }
        out
      }
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      GalleryLog.w(UtilityGraph.TAG) { "vettori dei doppioni simili non letti: ${e.javaClass.simpleName}" }
      emptyMap()
    }
  }
}
