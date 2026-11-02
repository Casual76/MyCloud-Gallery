package dev.pampa.mycloudgallery.feature.integrations.drag

import android.content.Context
import android.widget.Toast
import androidx.annotation.StringRes
import dev.pampa.mycloudgallery.core.designsystem.adaptive.MediaDrag
import dev.pampa.mycloudgallery.core.designsystem.adaptive.MediaDragPort
import dev.pampa.mycloudgallery.core.library.contract.DragLimits
import dev.pampa.mycloudgallery.core.library.contract.DragOrigin
import dev.pampa.mycloudgallery.core.library.contract.DragPrepared
import dev.pampa.mycloudgallery.core.library.contract.DragRefusal
import dev.pampa.mycloudgallery.core.library.contract.DragRequest
import dev.pampa.mycloudgallery.core.library.contract.MediaDragHandler
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.feature.integrations.R
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// LA PORTA DELLE SCHERMATE SOPRA `MediaDragHandler` (Fase 10, P6, D20; docs/fase10-progetto.md §2.5 "Preparazione"). Le schermate (feature-*) parlano con `MediaDragPort` del
// design system (id come stringhe); qui diventano una `DragRequest` per la porta di `core-library`, e l'esito diventa i dati per Android o un messaggio per l'utente.
//
//  - se la preparazione dura **piu' di 300 ms** ([DragLimits.PREPARING_NOTICE_MS]) si dice "Preparo..." e il trascinamento parte quando e' pronto (il dito deve restare giu');
//  - un rifiuto si dice con una frase breve e chiara (`strings_integrations_drag.xml`), una per motivo; **mai un id, un nome o un percorso**;
//  - una cancellazione (il dito si alza) non lascia niente: lo garantisce [MediaDragHandler] (ogni sospensione e' prima di creare id e copie).
// Gli id che non sono `MediaId` validi, o ripetuti, o un'origine sconosciuta: niente da trascinare (fallire chiusi).

/** Cosa si dice all'utente durante e dopo la preparazione. Un'interfaccia: i test registrano, l'app mostra un messaggio breve. */
interface DragNotices {
  /** La preparazione dura: "Preparo...". */
  fun preparing()

  /** La preparazione e' finita (pronta, rifiutata o annullata): il "Preparo..." non serve piu'. */
  fun finished()

  /** Il trascinamento non parte, per [reason]. */
  fun refused(reason: DragRefusal)
}

/** I messaggi come avvisi di sistema (`Toast`): niente da disegnare dentro le schermate. Le frasi stanno in `strings_integrations_drag.xml`. */
class ToastDragNotices(context: Context) : DragNotices {
  private val context = context.applicationContext
  private var preparingToast: Toast? = null

  override fun preparing() {
    preparingToast?.cancel()
    preparingToast = Toast.makeText(context, R.string.integrations_drag_preparing, Toast.LENGTH_LONG).also { it.show() }
  }

  override fun finished() {
    preparingToast?.cancel()
    preparingToast = null
  }

  override fun refused(reason: DragRefusal) {
    Toast.makeText(context, textOf(reason), Toast.LENGTH_LONG).show()
  }

  companion object {
    /** La frase di ogni motivo (una risorsa, un posto solo: la prova controlla che ogni [DragRefusal] ne abbia una). */
    @StringRes
    fun textOf(reason: DragRefusal): Int = when (reason) {
      DragRefusal.TOO_MANY -> R.string.integrations_drag_too_many
      DragRefusal.TOO_LARGE -> R.string.integrations_drag_too_large
      DragRefusal.HIDDEN -> R.string.integrations_drag_hidden
      DragRefusal.LOCATION_NOT_REMOVABLE -> R.string.integrations_drag_location
      DragRefusal.NAS_UNREACHABLE -> R.string.integrations_drag_nas
      DragRefusal.LOCKED -> R.string.integrations_drag_locked
      DragRefusal.UNAVAILABLE -> R.string.integrations_drag_unavailable
    }
  }
}

class HandlerDragPort(
  private val handler: MediaDragHandler,
  private val notices: DragNotices,
  private val noticeAfterMs: Long = DragLimits.PREPARING_NOTICE_MS,
) : MediaDragPort {

  override suspend fun prepare(origin: String, ids: List<String>): MediaDrag? {
    val from = DragOrigin.entries.firstOrNull { it.code == origin } ?: return null
    val media = ids.map { raw -> runCatching { MediaId(raw) }.getOrNull() ?: return null }
    if (media.isEmpty() || media.distinct().size != media.size) return null
    // `removeLocation = false`: la scelta "Rimuovi la posizione" non e' delle schermate: la legge il provider dall'impostazione (una sola) e il gestore la rispetta.
    val request = DragRequest(from, media, removeLocation = false)
    return coroutineScope {
      val notice = launch {
        delay(noticeAfterMs)
        notices.preparing()
      }
      try {
        when (val prepared = handler.prepare(request)) {
          is DragPrepared.Ready -> MediaDrag(prepared.clip, prepared.flags, prepared.count) { prepared.lease.release() }
          is DragPrepared.Refused -> {
            notices.refused(prepared.reason)
            null
          }
        }
      } finally {
        notice.cancel()
        notices.finished()
      }
    }
  }
}
