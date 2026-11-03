package dev.pampa.mycloudgallery.feature.integrations.drag

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.widget.Toast
import dev.pampa.mycloudgallery.core.designsystem.adaptive.DropTarget
import dev.pampa.mycloudgallery.core.designsystem.adaptive.IncomingDrag
import dev.pampa.mycloudgallery.core.designsystem.adaptive.MediaDropPort
import dev.pampa.mycloudgallery.feature.integrations.R
import dev.pampa.mycloudgallery.feature.integrations.contract.EntryDecision
import dev.pampa.mycloudgallery.feature.integrations.receive.ReceiveRequest
import dev.pampa.mycloudgallery.feature.integrations.receive.ShareReceiverActivity

// IL TRASCINAMENTO IN ARRIVO (Fase 10, P6, D21, C437, E-T15, E-T16, E-X2, E-X7; docs/fase10-progetto.md §2.5 "Dentro l'app"). Una foto trascinata da Archivio, dalla Galleria di Samsung o da un'altra
// app su la Libreria, su un album o su una cartella di Cartelle NAS **non apre una strada nuova verso il NAS**: porta i flussi al **foglio della ricezione** che c'e' gia' (`ShareReceiverActivity`:
// "Salva sul NAS", "Aggiungi ad album...") e da li' vale quello che vale per ogni condivisione in arrivo (C18, E-X2):
//  - i flussi si **copiano subito** in `noBackupFilesDir/incoming/` con i tetti, il tipo dai byte e il rifiuto di `file://` e dei nostri provider (`IncomingStager`): **niente di tutto questo si rifa'
//    qui**, e nessun flusso si apre prima che il foglio lo faccia;
//  - **niente parte senza un tocco**: il rilascio apre il foglio con il conteggio e basta (la conferma nasce solo da "Salva sul NAS");
//  - con il blocco dell'app attivo il foglio chiede prima lo sblocco (E-X7): un rilascio con il cancello chiuso non fa niente;
//  - un trascinamento **partito da noi** non si riceve (il segno [MediaDragLocalState]): una foto della Libreria trascinata sull'album non e' "una foto da un'altra app" (sarebbe un caricamento
//    sul NAS di un media che c'e' gia'); per quello c'e' "Aggiungi ad album".
// L'evento interno [DropHandoff] porta la destinazione al foglio (mai nell'intent: un'altra app non puo' guidarlo).

/** Dove il rilascio vuole che si cominci: la scelta iniziale del foglio, che l'utente puo' cambiare. Mai nell'intento. */
sealed interface DropHint {

  /** La Libreria: il foglio di sempre ("Salva sul NAS"). */
  data object Library : DropHint

  /** Un album: il foglio apre "Aggiungi ad album" con questo album scelto (se e' fra quelli dove si puo' aggiungere, E-T16). [albumId] e' il valore dell'`AlbumId`. */
  data class Album(val albumId: String) : DropHint {
    override fun toString(): String = "DropHint.Album"
  }

  /** Una cartella di Cartelle NAS: il foglio apre su quella cartella, se sta fra quelle dove "Salva sul NAS" puo' scrivere. */
  data class Folder(val share: String, val path: String) : DropHint {
    override fun toString(): String = "DropHint.Folder"
  }

  companion object {
    fun of(target: DropTarget): DropHint = when (target) {
      DropTarget.Library -> Library
      is DropTarget.Album -> Album(target.albumId)
      is DropTarget.NasFolder -> Folder(target.share, target.path)
    }
  }
}

/**
 * Il passaggio della destinazione dal rilascio al foglio: **un posto, fresco, una volta sola** (come `WindowStarts` di P4). Chi rilascia lo offre un istante prima di aprire il foglio; il foglio
 * lo prende appena nasce. Se nessuno lo prende entro [FRESH_MS] (orologio monotono) scade: un foglio aperto da un'altra app non eredita la destinazione di un trascinamento vecchio.
 */
object DropHandoff {

  /** Quanto resta fresca l'offerta: il tempo di avviare un'activity. */
  const val FRESH_MS: Long = 10_000L

  private val lock = Any()
  private var hint: DropHint? = null
  private var offeredAt: Long = 0L

  fun offer(value: DropHint, nowMs: Long) {
    synchronized(lock) {
      hint = value
      offeredAt = nowMs
    }
  }

  /** La destinazione offerta, se c'e' ed e' fresca; poi non c'e' piu'. Un orologio che torna indietro la fa scadere. */
  fun take(nowMs: Long): DropHint? = synchronized(lock) {
    val value = hint
    val age = nowMs - offeredAt
    hint = null
    if (value != null && age in 0..FRESH_MS) value else null
  }

  fun reset() {
    synchronized(lock) { hint = null }
  }
}

/** Chi apre il foglio della ricezione con un intento. Un'interfaccia: Robolectric non ha un sistema che avvia, e i test registrano. */
fun interface ReceiveLauncher {
  fun launch(activity: Activity?, intent: Intent): Boolean
}

/** Il foglio vero: `startActivity` dall'activity che ha ricevuto il rilascio. */
object ActivityReceiveLauncher : ReceiveLauncher {
  override fun launch(activity: Activity?, intent: Intent): Boolean {
    activity ?: return false
    return try {
      activity.startActivity(intent)
      true
    } catch (_: RuntimeException) {
      false
    }
  }
}

class IncomingDrops(
  context: Context,
  /** Il cancello d'ingresso **adesso**: senza "pronta e sbloccata" un rilascio non apre niente (E-X7). */
  private val gate: () -> EntryDecision,
  /** L'orologio monotono (`SystemClock.elapsedRealtime`): mai l'ora del telefono. */
  private val monotonicMs: () -> Long,
  private val launcher: ReceiveLauncher = ActivityReceiveLauncher,
  private val notice: (Int) -> Unit = { Toast.makeText(context.applicationContext, it, Toast.LENGTH_LONG).show() },
) : MediaDropPort {

  private val context: Context = context.applicationContext

  override fun accepts(target: DropTarget, drag: IncomingDrag): Boolean {
    // Un trascinamento partito da noi non si riceve (D21): lo stato locale e' il nostro segno.
    if (drag.own) {
      DragCounters.add(DragCounter.IN_OWN_IGNORED)
      return false
    }
    // Solo foto e video: lo si vede dai tipi che l'altra app dichiara (il tipo vero lo dicono poi i byte, `IncomingMime`).
    val description = drag.description ?: return false
    return description.hasMimeType("image/*") || description.hasMimeType("video/*")
  }

  override fun drop(target: DropTarget, drag: IncomingDrag, activity: Activity?): Boolean {
    if (drag.own) return false
    // Il blocco dell'app (E-X7): con il cancello chiuso il rilascio non porta niente al foglio (l'app non mostrerebbe nemmeno la destinazione).
    // CA-2 (Fase 10): con la versione fermata dal manifest il motivo e' detto ("aggiorna l'app"), non "trascina foto o video".
    when (gate()) {
      EntryDecision.ALLOWED -> Unit
      EntryDecision.STOPPED -> return refuse(R.string.integrations_drag_in_stopped)
      else -> return refuse()
    }
    val clip = drag.clip ?: return refuse()
    // Il permesso di lettura del trascinamento vale per l'activity (per l'app, finche' vive): serve alla copia, che parte appena il foglio e' su.
    drag.grantAccess()
    val intent = Intent(context, ShareReceiverActivity::class.java).apply {
      action = Intent.ACTION_SEND_MULTIPLE
      clipData = clip
      addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    // Lo stesso filtro dell'activity: azione, tipo e indirizzi dei flussi; niente da leggere = non e' per noi.
    if (ReceiveRequest.from(intent) == null) return refuse()
    DropHandoff.offer(DropHint.of(target), monotonicMs())
    if (!launcher.launch(activity, intent)) {
      DropHandoff.reset()
      return refuse()
    }
    DragCounters.add(DragCounter.IN_DROPPED)
    return true
  }

  private fun refuse(text: Int = R.string.integrations_drag_in_refused): Boolean {
    DragCounters.add(DragCounter.IN_REFUSED)
    notice(text)
    return false
  }
}
