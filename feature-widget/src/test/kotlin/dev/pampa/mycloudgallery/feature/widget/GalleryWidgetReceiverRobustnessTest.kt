package dev.pampa.mycloudgallery.feature.widget

import android.app.Application
import android.appwidget.AppWidgetManager
import androidx.test.core.app.ApplicationProvider
import dev.pampa.mycloudgallery.feature.widget.glance.GalleryWidgetReceiver
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Un ricevitore che lancia fa cadere il processo (Fase 10, P3, D39): con WorkManager **non pronto** (qui mai inizializzato, come in un processo dove il suo avvio non e' ancora passato)
 * la nascita e la morte di un widget non lanciano niente. Il test passa se non esce nessuna eccezione.
 */
@RunWith(RobolectricTestRunner::class)
class GalleryWidgetReceiverRobustnessTest {

  private val context: Application get() = ApplicationProvider.getApplicationContext()

  @Test
  fun conWorkManagerNonProntoNiente_Lancia() {
    val receiver = GalleryWidgetReceiver()
    receiver.onEnabled(context)
    receiver.onUpdate(context, AppWidgetManager.getInstance(context), intArrayOf(5))
    receiver.onDisabled(context)
  }
}
