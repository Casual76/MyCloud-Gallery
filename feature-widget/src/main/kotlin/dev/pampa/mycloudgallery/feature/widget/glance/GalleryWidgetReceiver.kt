package dev.pampa.mycloudgallery.feature.widget.glance

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.Context
import android.os.Bundle
import dev.pampa.mycloudgallery.core.common.log.GalleryLog
import dev.pampa.mycloudgallery.core.widget.contract.WidgetSlot
import dev.pampa.mycloudgallery.feature.widget.host.WidgetHost
import dev.pampa.mycloudgallery.feature.widget.refresh.WidgetEngines
import dev.pampa.mycloudgallery.feature.widget.work.WidgetWork
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Il ricevitore del widget "MyCloud Gallery" (Fase 10, P3, docs/fase10-progetto.md D6, D8, D10, D40, §6.4). **Non esportato** (la manda il sistema dei widget, uid di sistema; la
 * stessa forma del widget di prova di debug) e dichiara **solo la Home** (`widgetCategory="home_screen"`: mai la schermata di blocco, Q4 (a)) e `updatePeriodMillis="0"`
 * (D6: nessuna sveglia di Android, il ridisegno e' del lavoro periodico e degli eventi). `verifyReleaseManifest` lo pretende sul manifest fuso.
 *
 * E' un `AppWidgetProvider` e non un `GlanceAppWidgetReceiver`: Glance qui costruisce i `RemoteViews` (vedi [GalleryWidget]) e non gestisce il ciclo di vita, quindi nessuna
 * sessione di Glance e nessun `SessionWorker`. Il ricevitore fa soltanto quello che deve:
 * - **nasce un widget o il sistema ne chiede l'aggiornamento** ([onUpdate], dopo un riavvio o un aggiornamento dell'app): il giro per quel widget. In un processo freddo
 *   il fornitore monta solo istantanea, maschera e scaffale, al piu' 1,5 s ([WidgetHost.services]); se non sono pronti **non disegna niente di nuovo** e resta quello che
 *   il launcher ha (E-W4: la lezione di NT-1, il lavoro non si fida di un grafo montato da altri);
 * - **il launcher lo ridimensiona** ([onAppWidgetOptionsChanged]): un piano nuovo per la misura nuova (il registro deve sapere quali celle ci sono);
 * - **e' tolto dalla Home** ([onDeleted]): il registro lo dimentica; **l'ultimo** ([onDisabled]) toglie anche il lavoro periodico, **il primo** ([onEnabled]) lo programma.
 *
 * Tutto cio' che e' lento gira fuori dal thread del ricevitore con `goAsync` e un tetto di tempo: un ricevitore che non finisce viene ucciso dal sistema.
 */
class GalleryWidgetReceiver : AppWidgetProvider() {

  override fun onEnabled(context: Context) {
    inBackground("lavoro programmato") { reschedule(context) }
  }

  override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
    val slots = slotsOf(appWidgetIds)
    inBackground("aggiornamento") {
      // Un widget nuovo (o un riavvio): il lavoro periodico c'e' (un aggiornamento dell'app lo conserva, ma e' economico verificarlo).
      reschedule(context)
      val engine = WidgetEngines.of(context)
      for (slot in slots) engine.refresh(slot)
    }
  }

  override fun onAppWidgetOptionsChanged(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int, newOptions: Bundle) {
    val slot = slotsOf(intArrayOf(appWidgetId)).firstOrNull() ?: return
    inBackground("ridimensionamento") { WidgetEngines.of(context).refresh(slot) }
  }

  override fun onDeleted(context: Context, appWidgetIds: IntArray) {
    val slots = slotsOf(appWidgetIds)
    inBackground("widget tolto") { WidgetEngines.of(context).forget(slots) }
  }

  override fun onDisabled(context: Context) {
    // D6: nessun widget nostro, nessun lavoro.
    guarded("lavoro tolto") { WidgetWork.cancel(context) }
  }

  private fun slotsOf(ids: IntArray): List<WidgetSlot> = ids.filter { it > 0 }.map { WidgetSlot(it) }

  /**
   * Il lavoro periodico alla frequenza **scelta** (WG-2: `WidgetHost.period()`, che non monta le porte; quella di serie solo se non c'e' un fornitore), senza spostare un
   * lavoro che c'e' gia' (`KEEP`). Gira fuori dal thread del ricevitore: la scelta sta in un file di preferenze.
   */
  private fun reschedule(context: Context) {
    guarded("lavoro programmato") { WidgetWork.schedule(context, WidgetHost.period(), WidgetHost.clock()?.nowLocal(), replace = false) }
  }

  /** Un ricevitore che lancia fa cadere il processo: WorkManager non pronto o un orologio che cade non devono mai uscire da qui (nel log solo il tipo, D39). */
  private fun guarded(what: String, block: () -> Unit) {
    try {
      block()
    } catch (e: Exception) {
      GalleryLog.w(TAG) { "Widget ($what) non riuscito (${e.javaClass.simpleName})." }
    }
  }

  /** Il lavoro lento, fuori dal thread del ricevitore e con un tetto: il ricevitore non finisce prima del suo lavoro ma neanche resta appeso. */
  private fun BroadcastReceiver.inBackground(what: String, block: suspend () -> Unit) {
    // `goAsync` e' null fuori da `onReceive` (un test che chiama `onUpdate` da solo): il lavoro gira lo stesso.
    val pending: BroadcastReceiver.PendingResult? = goAsync()
    scope.launch {
      try {
        withTimeoutOrNull(RECEIVER_BUDGET_MS) { block() }
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        // Mai un'eccezione verso il sistema dei widget e nel log solo il tipo (D39).
        GalleryLog.w(TAG) { "Widget ($what) non riuscito (${e.javaClass.simpleName})." }
      } finally {
        pending?.finish()
      }
    }
  }

  internal companion object {
    private const val TAG = "GalleryWidgetReceiver"

    /** Il sistema da' a un ricevitore circa 10 s: un giro intero ne usa al piu' 2 + 1,5; con piu' widget si ferma qui e riprende al giro dopo. */
    const val RECEIVER_BUDGET_MS = 8_000L

    /** Dove gira il lavoro lento del ricevitore (di processo). I test mettono un esecutore che gira subito. */
    @Volatile
    var scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
  }
}
