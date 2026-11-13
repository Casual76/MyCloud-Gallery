package dev.pampa.mycloudgallery.feature.viewer.menu

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.feature.viewer.details.ViewerFormat
import dev.pampa.mycloudgallery.feature.viewer.info.analysisNote
import dev.pampa.mycloudgallery.core.designsystem.icon.GalleryIconStatus
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Pacchetto X: "Analizza adesso" nel visore — la voce c'e' solo per un media in coda (gia' pronto o gia' chiesto: nessun tasto inutile),
 * e la capsula delle informazioni dice lo stato prima del tocco, con il motivo vero se una richiesta non parte.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class AnalysisActionTest {

  private val app: Application = ApplicationProvider.getApplicationContext()
  private val format = ViewerFormat(app)

  @Test
  fun laVoceCeSoloInCoda() {
    var taps = 0
    val queued = analysisMenuEntries(app.resources, MediaAnalysis(MediaAnalysisKind.QUEUED)) { taps++ }
    assertThat(queued).hasSize(1)
    for (kind in MediaAnalysisKind.entries - MediaAnalysisKind.QUEUED) {
      assertThat(analysisMenuEntries(app.resources, MediaAnalysis(kind)) { taps++ }).isEmpty()
    }
  }

  @Test
  fun laCapsulaDiceLoStatoPrimaDelTocco() {
    assertThat(analysisNote(format, MediaAnalysis(MediaAnalysisKind.READY), video = false)?.label).isEqualTo("Pronta per la ricerca")
    assertThat(analysisNote(format, MediaAnalysis(MediaAnalysisKind.READY), video = true)?.label).isEqualTo("Pronto per la ricerca")
    assertThat(analysisNote(format, MediaAnalysis(MediaAnalysisKind.QUEUED), video = false)?.label).isEqualTo("Da analizzare")
    assertThat(analysisNote(format, MediaAnalysis(MediaAnalysisKind.NOT_HERE), video = false)?.label).contains("altro dispositivo")
    assertThat(analysisNote(format, MediaAnalysis.UNKNOWN, video = false)).isNull()
  }

  @Test
  fun chiestaMaFermaDicePerche() {
    val hot = analysisNote(format, MediaAnalysis(MediaAnalysisKind.FIRST, MediaAnalysisHold.HOT), video = false)
    assertThat(hot?.label).isEqualTo("Prima in coda")
    assertThat(hot?.detail).contains("caldo")
    val nas = analysisNote(format, MediaAnalysis(MediaAnalysisKind.FIRST, MediaAnalysisHold.NAS), video = true)
    assertThat(nas?.label).isEqualTo("Primo in coda")
    assertThat(nas?.detail).contains("NAS")
  }

  /** Grafica XU: un vocabolario solo di segni (lo stesso della testata dell'analisi e della nuvola). */
  @Test
  fun ogniCasoHaIlSuoSegno() {
    assertThat(dev.pampa.mycloudgallery.feature.viewer.info.analysisMark(MediaAnalysisKind.READY)).isEqualTo(GalleryIconStatus.Done)
    assertThat(dev.pampa.mycloudgallery.feature.viewer.info.analysisMark(MediaAnalysisKind.QUEUED)).isEqualTo(GalleryIconStatus.Waiting)
    assertThat(dev.pampa.mycloudgallery.feature.viewer.info.analysisMark(MediaAnalysisKind.FIRST)).isEqualTo(GalleryIconStatus.Waiting)
    assertThat(dev.pampa.mycloudgallery.feature.viewer.info.analysisMark(MediaAnalysisKind.ANALYZING)).isEqualTo(GalleryIconStatus.Running)
    assertThat(dev.pampa.mycloudgallery.feature.viewer.info.analysisMark(MediaAnalysisKind.NOT_HERE)).isEqualTo(GalleryIconStatus.Stopped)
    assertThat(dev.pampa.mycloudgallery.feature.viewer.info.analysisMark(MediaAnalysisKind.UNSUPPORTED)).isEqualTo(GalleryIconStatus.Stopped)
    assertThat(dev.pampa.mycloudgallery.feature.viewer.info.analysisMark(MediaAnalysisKind.MODEL_MISSING)).isEqualTo(GalleryIconStatus.Attention)
  }
}
