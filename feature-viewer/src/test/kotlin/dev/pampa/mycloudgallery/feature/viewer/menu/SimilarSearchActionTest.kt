package dev.pampa.mycloudgallery.feature.viewer.menu

import android.app.Application
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.designsystem.chrome.GalleryMenuEntry
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.feature.viewer.R
import dev.pampa.mycloudgallery.feature.viewer.ViewerActionHandlers
import dev.pampa.mycloudgallery.feature.viewer.ViewerCapabilities
import dev.pampa.mycloudgallery.feature.viewer.ViewerMessage
import dev.pampa.mycloudgallery.feature.viewer.ViewerServices
import dev.pampa.mycloudgallery.feature.viewer.viewerMenuEntries
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * La voce "Trova simili" del menu ⋮ (Fase 6, P8, D30, E-Q14): presente se il media ha un vettore, spiegata se non ce l'ha, assente per i
 * nascosti e senza ricerca (D13). Mai una voce che lancia la ricerca senza che si possa.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "it")
class SimilarSearchActionTest {

  private val resources get() = BackupActionsSamples.resources
  private val media = MediaId("00000000-0000-4000-8000-000000000042")

  private class Search(val availability: SimilarAvailability) : ViewerSimilarSearch {
    val found = mutableListOf<MediaId>()

    override fun availability(id: MediaId): SimilarAvailability = availability

    override fun findSimilar(id: MediaId) {
      found += id
    }
  }

  private fun items(entries: List<GalleryMenuEntry>) = entries.filterIsInstance<GalleryMenuEntry.Item>()

  @Test
  fun presenteLaVoceApreLaRicercaESoloQuella() {
    var found = 0
    var explained = 0
    val entries = similarMenuEntries(resources, SimilarAvailability.AVAILABLE, { found++ }, { explained++ })
    assertThat(items(entries).map { it.label }).containsExactly("Trova simili")
    items(entries).single().onClick()
    assertThat(found).isEqualTo(1)
    assertThat(explained).isEqualTo(0)
  }

  @Test
  fun spiegataLaVoceCeMaAlTocconNonLanciaLaRicerca() {
    var found = 0
    var explained = 0
    val entries = similarMenuEntries(resources, SimilarAvailability.EXPLAINED, { found++ }, { explained++ })
    assertThat(items(entries).map { it.label }).containsExactly("Trova simili")
    items(entries).single().onClick()
    assertThat(found).isEqualTo(0)
    assertThat(explained).isEqualTo(1)
  }

  @Test
  fun assenteNonCeNessunaVoce() {
    assertThat(similarMenuEntries(resources, SimilarAvailability.HIDDEN, {}, {})).isEmpty()
  }

  @Test
  fun senzaRicercaNonCompareMai() {
    assertThat(NoViewerSimilarSearch.availability(media)).isEqualTo(SimilarAvailability.HIDDEN)
    assertThat(ViewerServices().similarSearch.availability(media)).isEqualTo(SimilarAvailability.HIDDEN)
  }

  @Test
  fun laSpiegazioneHaIlSuoMessaggioInItaliano() {
    assertThat(resources.getString(ViewerMessage.SimilarUnavailable.title)).isEqualTo("Non posso trovare simili")
    assertThat(resources.getString(checkNotNull(ViewerMessage.SimilarUnavailable.detail))).contains("analizzata")
  }

  @Test
  fun nelMenuDelViewerStaNelGruppoDiCondividiPrimaDiInformazioni() {
    val caps = ViewerCapabilities(favorite = true, hide = true, share = true, copy = true, trash = true)
    val handlers = ViewerActionHandlers({}, {}, {}, {}, {}, {}, {})
    val similar = similarMenuEntries(resources, SimilarAvailability.AVAILABLE, {}, {})
    val with = viewerMenuEntries(resources, caps, isFavorite = false, secure = false, handlers = handlers, similarActions = similar)
    val shown = BackupActionsSamples.labels(with)
    assertThat(shown.indexOf("Trova simili")).isGreaterThan(shown.indexOf(resources.getString(R.string.viewer_action_copy)))
    assertThat(shown.indexOf("Trova simili")).isLessThan(shown.indexOf(resources.getString(R.string.viewer_action_info)))
    assertThat(shown.last()).isEqualTo(resources.getString(R.string.viewer_action_delete))
    // Senza la voce il menu e' quello di prima.
    val without = viewerMenuEntries(resources, caps, isFavorite = false, secure = false, handlers = handlers)
    assertThat(BackupActionsSamples.labels(without)).isEqualTo(shown - "Trova simili")
  }

  @Test
  fun laPortaPassaIlMediaGiusto() {
    val search = Search(SimilarAvailability.AVAILABLE)
    search.findSimilar(media)
    assertThat(search.found).containsExactly(media)
  }
}
