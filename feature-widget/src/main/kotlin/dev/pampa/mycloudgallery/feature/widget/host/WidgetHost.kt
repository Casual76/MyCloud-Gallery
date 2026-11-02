package dev.pampa.mycloudgallery.feature.widget.host

import dev.pampa.mycloudgallery.core.common.log.GalleryLog
import dev.pampa.mycloudgallery.core.widget.contract.WidgetClock
import kotlin.coroutines.cancellation.CancellationException

// IL POSTO DEL WIDGET NEL PROCESSO (Fase 10, P3, docs/fase10-progetto.md §1.4, §2.1). Un `GlanceAppWidgetReceiver` e un `Worker` non si iniettano con Hilt come
// una schermata: nascono dal sistema, spesso in un processo appena creato per loro. `:app` (P13) mette qui, **all'avvio del processo** (`Application.onCreate`), il
// fornitore delle porte; chi arriva prima del grafo lo chiama e aspetta (al piu' il budget a freddo).

/**
 * Lo slot del processo. [install] lo mette `:app` (P13), una volta; [services] lo chiamano il ricevitore, il lavoro periodico e il ridisegno. **Senza un fornitore
 * installato risponde null** (fallire chiusi): il widget non disegna niente di nuovo e lascia il contenuto che il launcher ha gia' (era verificato). Mai un default
 * che mostri una foto: un widget legato a un grafo che non c'e' non sa cosa e' nascosto.
 */
object WidgetHost {

  @Volatile
  private var provider: WidgetServicesProvider? = null

  /** Lo lega `:app` (P13) all'avvio. Sostituisce il precedente (i test lo ripuliscono con [uninstall]). */
  fun install(provider: WidgetServicesProvider) {
    this.provider = provider
  }

  /** Toglie il fornitore (i test). */
  fun uninstall() {
    provider = null
  }

  /** L'orologio del fornitore (senza montare le porte), o null se non c'e' un fornitore. */
  fun clock(): WidgetClock? = provider?.clock

  /** C'e' un fornitore installato (non vuol dire che le porte siano pronte). */
  val isInstalled: Boolean get() = provider != null

  /**
   * Le porte, aspettando al piu' [budgetMs] che `:app` le monti (istantanea, maschera, scaffale). Null: nessun fornitore, non pronti nel tempo, o un errore del fornitore
   * (mai un'eccezione verso il sistema dei widget: nel log solo il tipo). Chi riceve null **non ridisegna**.
   */
  suspend fun services(budgetMs: Long): WidgetServices? {
    val current = provider ?: return null
    return try {
      current.await(budgetMs.coerceAtLeast(0))
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      GalleryLog.w(TAG) { "Porte del widget non pronte (${e.javaClass.simpleName}): nessun ridisegno." }
      null
    }
  }

  private const val TAG = "WidgetHost"
}
