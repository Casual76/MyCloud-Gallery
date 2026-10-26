package dev.pampa.mycloudgallery.debugtools.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.os.CancellationSignal
import android.widget.RemoteViews
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import dev.pampa.mycloudgallery.R
import dev.pampa.mycloudgallery.core.common.log.GalleryLog
import dev.pampa.mycloudgallery.core.library.contract.LibraryFilter
import dev.pampa.mycloudgallery.gallery.GalleryGraph
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Cio' che il widget prende dal grafo (un `AppWidgetProvider` non si inietta senza `@AndroidEntryPoint`: si chiede all'applicazione). */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface FieldProbeWidgetEntryPoint {
  fun gallery(): GalleryGraph
}

/**
 * SOLO build debug (Fase 7, P9, D22, L65, F73): il **widget di prova dei Nascosti**. Quattro foto della Libreria che gli da' `WidgetFeed`, la
 * stessa sorgente che usera' il widget vero della Fase 10: mai un nascosto, mai un nome di file. Serve a provare sulla schermata Home che
 * nessun elemento nascosto compare fuori dall'app. Si aggiorna quando Android lo chiede (al massimo ogni mezz'ora) e subito dopo ogni
 * "Nascondi" ([refreshAll], da `DeveloperTools.hiddenChanged`): un media nascosto un attimo fa non resta sulla Home.
 *
 * Esiste solo in `app/src/debug`: la release non lo contiene e `tools/check.ps1` lo cerca nel dex di release. Il ricevitore del manifest di debug
 * non e' esportato: lo raggiunge il sistema dei widget, nessun'altra app.
 */
class FieldProbeHiddenWidgetProvider : AppWidgetProvider() {

  override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
    val pending = goAsync()
    val app = context.applicationContext
    scope.launch {
      try {
        update(app, appWidgetManager, appWidgetIds)
      } finally {
        pending.finish()
      }
    }
  }

  companion object {
    private const val TAG = "ProbeWidget"

    /** Quanto si aspetta una miniatura: il widget non deve tenere il ricevitore in vita oltre il tempo concesso da Android. */
    private const val THUMBNAIL_TIMEOUT_MS = 4_000L

    /** Il lato lungo delle miniature, in pixel: quattro celle piccole, lontano dal tetto dei `RemoteViews`. */
    private const val THUMBNAIL_PX = 200

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Ridisegna tutte le istanze del widget (dopo un "Nascondi"). Senza istanze non fa niente. */
    fun refreshAll(context: Context) {
      val app = context.applicationContext
      val manager = AppWidgetManager.getInstance(app)
      val ids = manager.getAppWidgetIds(ComponentName(app, FieldProbeHiddenWidgetProvider::class.java))
      if (ids.isEmpty()) return
      scope.launch { update(app, manager, ids) }
    }

    private suspend fun update(context: Context, manager: AppWidgetManager, ids: IntArray) {
      val views = try {
        render(context)
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        GalleryLog.w(TAG, e) { "Widget di prova non disegnato: si mostra vuoto." }
        FieldProbeWidgetViews.build(context, emptyList())
      }
      manager.updateAppWidget(ids, views)
    }

    private suspend fun render(context: Context): RemoteViews {
      val gallery = EntryPointAccessors.fromApplication(context, FieldProbeWidgetEntryPoint::class.java).gallery()
      val items = gallery.viewerItems(LibraryFilter.ALL)
      val cells = FieldProbeWidgetContent.cells(gallery.widgetFeed, gallery.visibleMedia.snapshot.value) { id -> items.refOrNull(id) }
      val photo = context.getString(R.string.probe_widget_photo)
      val video = context.getString(R.string.probe_widget_video)
      val rendered = cells.map { cell ->
        val bitmap: Bitmap? = cell.ref?.let { ref ->
          try {
            withTimeoutOrNull(THUMBNAIL_TIMEOUT_MS) { gallery.thumbnailSource.load(ref, THUMBNAIL_PX, CancellationSignal()) }
          } catch (e: CancellationException) {
            throw e
          } catch (_: Exception) {
            null
          }
        }
        FieldProbeRenderedCell(cell.description(photo, video), bitmap)
      }
      return FieldProbeWidgetViews.build(context, rendered)
    }
  }
}
