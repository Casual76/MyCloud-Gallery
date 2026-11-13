package dev.pampa.mycloudgallery.feature.search

import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.ImageBitmap
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.imaging.contract.ThumbBucket
import dev.pampa.mycloudgallery.core.imaging.contract.ThumbKey
import dev.pampa.mycloudgallery.core.imaging.contract.ThumbRequest
import dev.pampa.mycloudgallery.core.imaging.contract.ThumbStats
import dev.pampa.mycloudgallery.core.imaging.contract.ThumbnailLoader
import dev.pampa.mycloudgallery.core.imaging.contract.PlaceholderCache
import dev.pampa.mycloudgallery.core.model.ThumbHash
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaRef
import dev.pampa.mycloudgallery.feature.search.contract.SearchRefs
import dev.pampa.mycloudgallery.feature.search.contract.SearchThumbnails
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Test

/**
 * XS3-4 (Pacchetto XF, giro 3): le miniature del nastro dell'analisi si chiedono al caricatore condiviso **con un proprietario** e si **rilasciano** quando la pagina
 * esce. Mai `window` (sostituirebbe la finestra della Libreria). Il caricatore vero tiene le richieste per proprietario (`keep`): qui si prova che il nastro le
 * dia e le tolga con lo stesso nome. Mutazione: `releaseAnalysis` che non svuota la lista, o `holdAnalysis` che chiama `window`.
 */
class SearchThumbnailsOwnerTest {

  private class RecordingLoader : ThumbnailLoader {
    val kept = LinkedHashMap<String, List<ThumbRequest>>()
    var windowCalls = 0

    override fun image(key: ThumbKey, ref: MediaRef): State<ImageBitmap?> = mutableStateOf(null)

    override fun peek(key: ThumbKey): ImageBitmap? = null

    override fun window(visible: List<ThumbRequest>, ahead: List<ThumbRequest>) {
      windowCalls++
    }

    override fun keep(owner: String, requests: List<ThumbRequest>) {
      if (requests.isEmpty()) kept.remove(owner) else kept[owner] = requests
    }

    override fun trim(level: Int) = Unit

    override val stats: StateFlow<ThumbStats> = MutableStateFlow(ThumbStats())
  }

  private object NoPlaceholders : PlaceholderCache {
    override fun image(h: ThumbHash): ImageBitmap = throw UnsupportedOperationException("non serve in questa prova")
  }

  private fun request(n: Long) = ThumbRequest(
    ThumbKey(MediaId(UUID(0L, n).toString()), n, ThumbBucket.M), MediaRef.Nas(locationId = n, contentKey = n, mime = null),
  )

  private fun thumbnails(loader: RecordingLoader) = SearchThumbnails(loader, NoPlaceholders, SearchRefs { _, _ -> null })

  @Test
  fun ilNastroTieneEPoiRilasciaLeSueRichiesteConIlSuoNomeSenzaToccareLaFinestra() {
    val loader = RecordingLoader()
    val thumbnails = thumbnails(loader)
    thumbnails.holdAnalysis(listOf(request(1), request(2)))
    assertThat(loader.kept.keys).containsExactly(SearchThumbnails.ANALYSIS_OWNER)
    assertThat(loader.kept.getValue(SearchThumbnails.ANALYSIS_OWNER)).hasSize(2)
    // Le miniature cambiano: la lista del proprietario si sostituisce.
    thumbnails.holdAnalysis(listOf(request(3)))
    assertThat(loader.kept.getValue(SearchThumbnails.ANALYSIS_OWNER)).containsExactly(request(3))
    // La pagina esce: niente piu' richieste (e quindi niente riprove), e la finestra della Libreria non e' mai stata toccata.
    thumbnails.releaseAnalysis()
    assertThat(loader.kept).isEmpty()
    thumbnails.releaseAnalysis()
    assertThat(loader.kept).isEmpty()
    assertThat(loader.windowCalls).isEqualTo(0)
  }

  @Test
  fun ilProprietarioDelNastroNonSiConfondeConQuelloDeiSuggerimenti() {
    val loader = RecordingLoader()
    val thumbnails = thumbnails(loader)
    loader.keep("suggerimenti", listOf(request(9)))
    thumbnails.holdAnalysis(listOf(request(1)))
    thumbnails.releaseAnalysis()
    assertThat(loader.kept.keys).containsExactly("suggerimenti")
  }
}
