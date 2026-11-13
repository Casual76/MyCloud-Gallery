package dev.pampa.mycloudgallery.di

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.library.contract.HiddenMask
import dev.pampa.mycloudgallery.core.library.contract.VisibleMedia
import dev.pampa.mycloudgallery.core.library.contract.TimelineSnapshot
import java.io.File
import kotlinx.coroutines.flow.StateFlow
import org.junit.Test

/**
 * XS-1 (Pacchetto XF): **il collegamento della pagina "Analisi delle foto" non puo' tornare all'istantanea di base**. Il nastro riceve dalla
 * produzione `VisibleMedia` (la vista autorizzata, `visibleTimeline`) e `HiddenMask` (il filtro finale condiviso), mai un `StateFlow` di
 * istantanea: con un accesso parziale o revocato l'istantanea di base conserva elementi che la Libreria non mostra. La prova funzionale e'
 * `AnalysisVisibilityIntegrationTest`; qui si guarda il legame (porte tipizzate e sorgenti, come `HiddenMaskWiringTest`).
 */
class AnalysisWiringTest {

  private val provider = AiModule::class.java.methods.single { it.name == "analysisLive" }

  /** Il fornitore del nastro chiede le due porte condivise e **nessuna** istantanea. Mutazione: tornare a passare `gallery.repository.snapshot`. */
  @Test
  fun ilNastroRiceveLaVistaAutorizzataEIlFiltroFinaleNonUnaIstantanea() {
    val types = provider.parameterTypes.toList()
    assertThat(types).contains(VisibleMedia::class.java)
    assertThat(types).contains(HiddenMask::class.java)
    assertThat(types.none { StateFlow::class.java.isAssignableFrom(it) || TimelineSnapshot::class.java.isAssignableFrom(it) }).isTrue()
  }

  private fun source(path: String): String = File(path).readText()

  @Test
  fun ilModuloNonPassaPiuLIstantaneaDiBaseAlNastro() {
    val module = source("src/main/kotlin/dev/pampa/mycloudgallery/di/AiModule.kt")
    val body = module.substringAfter("fun analysisLive(").substringBefore("@Provides fun deviceProbe")
    assertThat(body).doesNotContain("repository.snapshot")
    assertThat(body).contains("AppAnalysisLive(")
    assertThat(body).contains("visible")
    assertThat(body).contains("mask")
  }

  /** `VisibleMedia` della galleria sta sulla vista autorizzata (`visibleTimeline`), non sul repository di base. */
  @Test
  fun visibleMediaDellaGalleriaStaSullaVistaAutorizzata() {
    val graph = source("src/main/kotlin/dev/pampa/mycloudgallery/gallery/GalleryGraph.kt")
    val line = graph.lines().single { it.contains("val visibleMedia: VisibleMedia") }
    assertThat(line).contains("visibleTimeline.snapshot")
    assertThat(line).doesNotContain("repository.snapshot")
  }

  /** Il nastro non tocca piu' le richieste (XS-5): non c'e' nessuna dipendenza dal registro nel file della pagina. */
  @Test
  fun ilNastroNonPulisceLeRichieste() {
    val live = source("src/main/kotlin/dev/pampa/mycloudgallery/ai/AnalysisLive.kt")
    assertThat(live).doesNotContain("urgent")
    assertThat(live).doesNotContain("hasVectors")
    assertThat(live).doesNotContain("forgetDone")
  }
}
