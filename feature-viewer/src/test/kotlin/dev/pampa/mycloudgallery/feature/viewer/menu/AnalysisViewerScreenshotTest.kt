package dev.pampa.mycloudgallery.feature.viewer.menu

import android.app.Application
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudDone
import dev.pampa.mycloudgallery.core.designsystem.design.GalleryFooterNote
import dev.pampa.mycloudgallery.core.designsystem.design.GalleryFooterTone
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import dev.antigravity.fluidengine.ui.fluid.rememberGlassBackdrop
import dev.pampa.mycloudgallery.core.designsystem.chrome.GalleryMenuPanel
import dev.pampa.mycloudgallery.core.designsystem.chrome.GalleryMenuSurface
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.feature.viewer.R
import dev.pampa.mycloudgallery.feature.viewer.ViewerActionHandlers
import dev.pampa.mycloudgallery.feature.viewer.ViewerCapabilities
import dev.pampa.mycloudgallery.feature.viewer.details.ViewerFormat
import dev.pampa.mycloudgallery.feature.viewer.info.AnalysisCapsule
import dev.pampa.mycloudgallery.feature.viewer.info.InfoContent
import dev.pampa.mycloudgallery.feature.viewer.info.InfoInputs
import dev.pampa.mycloudgallery.feature.viewer.info.analysisBadge
import dev.pampa.mycloudgallery.feature.viewer.info.buildInfoModel
import dev.pampa.mycloudgallery.feature.viewer.sampleDetails
import dev.pampa.mycloudgallery.feature.viewer.viewerMenuEntries
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Grafica XU (12/11/2026): «Analizza adesso» nel visore. Il pannello informazioni vero con la capsula dell'analisi (in coda: il tasto), tutti i
 * segni della capsula uno sotto l'altro (pronta, in coda, prima in coda con il perche', in analisi, un altro dispositivo, manca il modello, non si
 * puo'), e il menu ⋮ con la voce. Chiaro e scuro, telefono e tablet, piu' il testo grande. PNG in docs/screenshots/beta-grafica/X-indice-visibile.
 */
abstract class AnalysisViewerScreenshotBase(private val dark: Boolean, private val device: String) : NasActionsComposeBase() {

  private val look: String get() = if (dark) "scuro" else "chiaro"

  private fun shoot(name: String, fontScale: Float? = null, content: @androidx.compose.runtime.Composable () -> Unit) {
    show(dark = dark, fontScale = fontScale) { content() }
    val suffix = if (fontScale != null) "-testo-grande" else ""
    composeRule.onRoot().captureRoboImage("$DIR/visore-$name-$look-$device$suffix.png")
  }

  @androidx.compose.runtime.Composable
  private fun Panel(media: MediaAnalysis, backup: GalleryFooterNote? = null) {
    val context = LocalContext.current
    val format = ViewerFormat(context)
    val id = MediaId("00000000-0000-0000-0000-000000000042")
    val model = buildInfoModel(format, InfoInputs(kind = MediaKind.PHOTO, details = sampleDetails(id)))
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerHighest), contentAlignment = Alignment.BottomCenter) {
      Box(
        Modifier
          .fillMaxWidth()
          .widthIn(max = 560.dp)
          .background(MaterialTheme.colorScheme.surface)
          .padding(20.dp),
      ) {
        InfoContent(
          model = model,
          backup = backup,
          extras = emptyList(),
          onOpenMaps = null,
          analysis = analysisBadge(format, media, video = false),
          onAnalysis = if (media.kind == MediaAnalysisKind.QUEUED) ({}) else null,
        )
      }
    }
  }

  @Test
  fun pannelloInCoda() = shoot("info-analizza-adesso") { Panel(MediaAnalysis(MediaAnalysisKind.QUEUED)) }

  @Test
  fun pannelloInCodaTestoGrande() {
    org.junit.Assume.assumeTrue(device == "telefono" && !dark)
    shoot("info-analizza-adesso", fontScale = NAS_LARGE_TEXT) { Panel(MediaAnalysis(MediaAnalysisKind.QUEUED)) }
  }

  /** Giro 2 (XU-10): la capsula del backup e quella dell'analisi insieme, sulla stessa riga. */
  @Test
  fun pannelloConIlBackupELAnalisi() = shoot("info-backup-e-analisi") {
    Panel(
      MediaAnalysis(MediaAnalysisKind.QUEUED),
      backup = GalleryFooterNote(tone = GalleryFooterTone.Calm, label = "Al sicuro", glyph = Icons.Rounded.CloudDone),
    )
  }

  /** Giro 2 (XU-7): chiesta dai dati mobili, la capsula dice perche' non parte subito. */
  @Test
  fun pannelloPrimaInCodaSulWifi() = shoot("info-parte-sul-wifi") { Panel(MediaAnalysis(MediaAnalysisKind.FIRST, MediaAnalysisHold.WIFI)) }

  @Test
  fun pannelloPronta() = shoot("info-pronta") { Panel(MediaAnalysis(MediaAnalysisKind.READY)) }

  /** Tutti i segni della capsula, uno sotto l'altro: il vocabolario in una foto. */
  @Test
  fun tuttiISegni() = shoot("capsule-analisi") {
    val format = ViewerFormat(LocalContext.current)
    val now = stringResource(R.string.analysis_now)
    Column(
      Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).padding(24.dp),
      verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
      listOf(
        MediaAnalysis(MediaAnalysisKind.QUEUED) to true,
        MediaAnalysis(MediaAnalysisKind.FIRST, MediaAnalysisHold.HOT) to false,
        MediaAnalysis(MediaAnalysisKind.FIRST, MediaAnalysisHold.WIFI) to false,
        MediaAnalysis(MediaAnalysisKind.ANALYZING) to false,
        MediaAnalysis(MediaAnalysisKind.READY) to false,
        MediaAnalysis(MediaAnalysisKind.NOT_HERE) to false,
        MediaAnalysis(MediaAnalysisKind.MODEL_MISSING) to false,
        MediaAnalysis(MediaAnalysisKind.UNSUPPORTED) to false,
      ).forEach { (media, tappable) ->
        AnalysisCapsule(analysisBadge(format, media, video = false)!!, onClick = if (tappable) ({}) else null, actionLabel = now)
      }
    }
  }

  @Test
  fun menuConAnalizzaAdesso() = shoot("menu-analizza-adesso") {
    val resources = LocalContext.current.resources
    val menu = viewerMenuEntries(
      resources,
      ViewerCapabilities(favorite = true, hide = true, share = true, copy = true, trash = false),
      isFavorite = false,
      secure = false,
      handlers = ViewerActionHandlers({}, {}, {}, {}, {}, {}, {}),
      analysisActions = analysisMenuEntries(resources, MediaAnalysis(MediaAnalysisKind.QUEUED)) {},
    )
    val backdrop = rememberGlassBackdrop()
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(24.dp), contentAlignment = Alignment.TopEnd) {
      GalleryMenuSurface(backdrop = backdrop) { GalleryMenuPanel(entries = menu) }
    }
  }

  companion object {
    const val DIR = "../docs/screenshots/beta-grafica/X-indice-visibile"
    fun looks(): List<Array<Any>> = listOf(arrayOf<Any>(false), arrayOf<Any>(true))
  }
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = "w360dp-h780dp-xhdpi")
class AnalysisViewerPhoneScreenshotTest(dark: Boolean) : AnalysisViewerScreenshotBase(dark, "telefono") {
  companion object {
    @JvmStatic
    @ParameterizedRobolectricTestRunner.Parameters(name = "scuro={0}")
    fun data(): List<Array<Any>> = looks()
  }
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = NAS_TABLET)
class AnalysisViewerTabletScreenshotTest(dark: Boolean) : AnalysisViewerScreenshotBase(dark, "tablet") {
  companion object {
    @JvmStatic
    @ParameterizedRobolectricTestRunner.Parameters(name = "scuro={0}")
    fun data(): List<Array<Any>> = looks()
  }
}
