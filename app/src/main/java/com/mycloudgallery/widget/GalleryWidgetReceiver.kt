package com.mycloudgallery.widget

import androidx.glance.appwidget.GlanceAppWidgetReceiver

/**
 * BroadcastReceiver che gestisce gli aggiornamenti del widget.
 * Android chiama onUpdate() periodicamente secondo l'intervallo in gallery_widget_info.xml (15 min).
 * I dati vengono aggiornati da [SyncWorker] dopo ogni sincronizzazione.
 */
class GalleryWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget = GalleryWidget()
}
