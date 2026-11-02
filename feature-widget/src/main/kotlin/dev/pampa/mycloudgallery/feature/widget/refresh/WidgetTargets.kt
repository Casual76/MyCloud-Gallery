package dev.pampa.mycloudgallery.feature.widget.refresh

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.res.Configuration
import android.widget.RemoteViews
import dev.pampa.mycloudgallery.core.widget.contract.WidgetSlot
import dev.pampa.mycloudgallery.feature.widget.frame.WidgetSizing
import dev.pampa.mycloudgallery.feature.widget.glance.GalleryWidgetReceiver

/**
 * I widget "MyCloud Gallery" che sono davvero sulla Home e dove consegnare il loro disegno. La porta fra il motore del ridisegno e `AppWidgetManager` (i test di logica
 * ne usano una finta, quelli con Glance quella vera su Robolectric).
 */
internal interface WidgetTargets {
  /** I widget nostri sulla Home adesso (D6: nessuno = niente lavoro e il lavoro periodico si annulla). */
  fun slots(): List<WidgetSlot>

  /** La grandezza in dp (larghezza, altezza) del widget [slot], dalle opzioni che il launcher ha dato; (0, 0) se non le ha date (vale la misura piccola). */
  fun sizeOf(slot: WidgetSlot): Pair<Int, Int>

  /** Consegna il disegno a [slot]. */
  fun push(slot: WidgetSlot, views: RemoteViews)
}

/** I widget veri: `AppWidgetManager` e il nostro ricevitore (non esportato). */
internal class AppWidgetTargets(context: Context) : WidgetTargets {
  private val app: Context = context.applicationContext
  private val manager: AppWidgetManager = AppWidgetManager.getInstance(app)
  private val component = ComponentName(app, GalleryWidgetReceiver::class.java)

  override fun slots(): List<WidgetSlot> = manager.getAppWidgetIds(component).filter { it > 0 }.map { WidgetSlot(it) }

  override fun sizeOf(slot: WidgetSlot): Pair<Int, Int> {
    val options = manager.getAppWidgetOptions(slot.appWidgetId) ?: return 0 to 0
    val portrait = app.resources.configuration.orientation != Configuration.ORIENTATION_LANDSCAPE
    return WidgetSizing.fromOptions(
      minWidthDp = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0),
      maxWidthDp = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 0),
      minHeightDp = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0),
      maxHeightDp = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0),
      portrait = portrait,
    )
  }

  override fun push(slot: WidgetSlot, views: RemoteViews) {
    manager.updateAppWidget(slot.appWidgetId, views)
  }
}
