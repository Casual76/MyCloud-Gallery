package dev.pampa.mycloudgallery.main

import android.content.Context
import dev.pampa.mycloudgallery.core.mediastore.contract.SyncReason
import dev.pampa.mycloudgallery.core.mediastore.contract.SyncStatus
import dev.pampa.mycloudgallery.gallery.GalleryGraph
import dev.pampa.mycloudgallery.work.NasWorkScheduling
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Fase H (D10, richiesta dell'utente del 31/10): **«aggiorna»** della Libreria, il gesto di tirare oltre il fondo. Non e' un percorso nuovo: usa
 * gli stessi comandi che l'app usa gia', con le stesse regole.
 *  - **NAS**: `NasWorkScheduling.scanNow`, la verifica di quando si apre l'app (poi la sincronizzazione dell'indice e dei dati personali): lavori
 *    unici di WorkManager (`KEEP`, un giro gia' in coda non si sposta), con i vincoli di rete e le regole delle attivita'.
 *  - **Telefono**: un giro della lettura del MediaStore (`PhoneLibrarySync.syncNow(MANUAL)`, il motivo che il contratto riserva al tocco
 *    dell'utente), se non ne sta gia' girando uno.
 *  - **Backup**: `BackupGraph.afterPhoneRead`, quello che la lettura di primo piano chiede gia' alla fine di ogni giro: i lavori del backup
 *    che servono, unici e con le regole di sempre (rete, batteria, recinzioni). Niente parte se le regole dicono di aspettare.
 * L'esito si vede nella nuvola in alto, come per ogni giro: qui nessun messaggio.
 *
 * Un aggiornamento alla volta: se il gesto arriva mentre uno lavora, aspetta quello invece di farne partire un altro. Il lavoro gira nello scope
 * della galleria (vive quanto il processo): se la Libreria esce di scena a meta', il giro arriva in fondo lo stesso.
 */
class LibraryRefresh(private val context: Context, private val gallery: GalleryGraph) {

  private var running: Job? = null

  /** Se l'aggiornamento si puo' chiedere: mai nella variante di misura (il dataset e' sintetico), come i lavori in background. */
  val available: Boolean get() = gallery.backgroundWorkAllowed

  /** Avvia l'aggiornamento (o si aggancia a quello in corso) e ritorna quando la lettura del telefono e la richiesta dei lavori sono finite. */
  suspend fun refresh() {
    if (!available) return
    val job = synchronized(this) {
      running?.takeIf { it.isActive } ?: gallery.scope.launch { work() }.also { running = it }
    }
    job.join()
  }

  private suspend fun work() {
    if (gallery.nas != null) attempt { NasWorkScheduling.scanNow(context) }
    val sync = gallery.coreOrNull?.phone?.sync
    if (sync != null && sync.status.value !is SyncStatus.Scanning) attempt { sync.syncNow(SyncReason.MANUAL) }
    gallery.backup?.let { backup -> attempt { backup.afterPhoneRead() } }
  }

  private inline fun attempt(block: () -> Unit) {
    try {
      block()
    } catch (e: CancellationException) {
      throw e
    } catch (_: Exception) {
      // Un pezzo che non riesce (WorkManager, il MediaStore) non ferma gli altri: la nuvola dira' com'e' andata.
    }
  }
}
