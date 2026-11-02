package dev.pampa.mycloudgallery.feature.widget.glance

import android.content.Context
import android.os.Bundle
import android.widget.RemoteViews
import androidx.compose.ui.unit.DpSize
import androidx.glance.ExperimentalGlanceApi
import androidx.glance.GlanceId
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.compose
import androidx.glance.appwidget.provideContent
import dev.antigravity.fluidengine.foundation.EngineSettings
import dev.antigravity.fluidengine.widget.EngineWidgetLayout
import dev.antigravity.fluidengine.widget.EngineWidgetPalette
import dev.antigravity.fluidengine.widget.engineWidgetPalette
import dev.antigravity.fluidengine.widget.resolveEngineWidgetLayout
import dev.pampa.mycloudgallery.core.designsystem.theme.GalleryAccent
import dev.pampa.mycloudgallery.feature.widget.frame.WidgetFrame
import dev.pampa.mycloudgallery.feature.widget.tap.WidgetTapIntents
import dev.pampa.mycloudgallery.feature.widget.ui.GalleryWidgetContent
import dev.pampa.mycloudgallery.feature.widget.ui.WidgetActions

// IL WIDGET GLANCE (Fase 10, P3, docs/fase10-progetto.md D3, D10, §2.1 passo 8). Un `GlanceAppWidget` che disegna **un fotogramma gia' pronto** (`WidgetFrame`: piano verificato,
// bitmap locali, testi) con la palette del kit di `engine-widget` e il budget delle righe `resolveEngineWidgetLayout`.
//
// **Glance e' usato come costruttore di `RemoteViews`**, non come gestore del widget: `compose` produce i `RemoteViews` di un fotogramma e il ridisegno li consegna ad
// `AppWidgetManager` (`GlanceWidgetRefresher`, `WidgetEngine`). Non parte nessuna `GlanceSession` ne' il `SessionWorker` di Glance (la sua corsa passa da WorkManager e non da'
// la garanzia dei 100 ms di D5 per lo svuotamento): niente scelte da fare a ogni ridisegno dentro la composizione, niente stato nascosto dentro Glance. Il disegno e' una funzione
// del fotogramma e della palette.
//
// **`SizeMode.Single`** (D3 diceva `Responsive`: scostamento motivato in `docs/decisioni.md`): il registro tiene un piano per widget (D9) e le bitmap sono 7 al piu' per 2 MiB
// (D7): con `Responsive` Glance consegnerebbe al launcher tre disegni, ognuno con le sue bitmap, e il launcher sceglierebbe da solo quale mostrare. Qui la misura (`WidgetSizing`)
// si decide **prima**, il piano e' fatto per quella misura, e un ridimensionamento ripianifica (`GalleryWidgetReceiver.onAppWidgetOptionsChanged`).
internal class GalleryWidget(
  private val frame: WidgetFrame,
  private val palette: EngineWidgetPalette,
  private val layout: EngineWidgetLayout,
  private val actions: WidgetActions,
) : GlanceAppWidget() {

  override val sizeMode: SizeMode = SizeMode.Single

  override suspend fun provideGlance(context: Context, id: GlanceId) {
    provideContent { GalleryWidgetContent(frame, palette, layout, actions) }
  }
}

/** Compone il fotogramma di un widget in `RemoteViews`: la porta fra `WidgetEngine` e Glance (i test di logica ne usano una finta). */
internal fun interface WidgetComposer {
  suspend fun compose(frame: WidgetFrame, size: DpSize, settings: EngineSettings): RemoteViews
}

/** La composizione vera, con Glance. */
internal class GlanceWidgetComposer(private val context: Context) : WidgetComposer {

  @OptIn(ExperimentalGlanceApi::class)
  override suspend fun compose(frame: WidgetFrame, size: DpSize, settings: EngineSettings): RemoteViews {
    val palette = engineWidgetPalette(context, settings, GalleryAccent.Amethyst)
    val layout = resolveEngineWidgetLayout(size)
    val widget = GalleryWidget(frame, palette, layout, TapActions(context, frame))
    val id = GlanceAppWidgetManager(context).getGlanceIdBy(frame.slot.appWidgetId)
    return widget.compose(context, id, Bundle(), size)
  }

  /** Le azioni di tocco di un fotogramma: da `tap/` (D9), una per cella e quella di "Apri MyCloud Gallery". */
  private class TapActions(private val context: Context, private val frame: WidgetFrame) : WidgetActions {
    override fun cell(index: Int) = WidgetTapIntents.cellAction(context, frame.slot, index)

    override val openApp get() = WidgetTapIntents.openAppAction(context)
  }
}
