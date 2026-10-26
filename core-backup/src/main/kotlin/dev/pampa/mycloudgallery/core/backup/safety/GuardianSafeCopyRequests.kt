package dev.pampa.mycloudgallery.core.backup.safety

import dev.pampa.mycloudgallery.core.backup.contract.GuardReport
import dev.pampa.mycloudgallery.core.backup.contract.GuardScope
import dev.pampa.mycloudgallery.core.backup.contract.PrivateCopyGuardian
import dev.pampa.mycloudgallery.core.backup.contract.RunStop
import dev.pampa.mycloudgallery.core.backup.contract.SafeCopyRequests
import dev.pampa.mycloudgallery.core.common.log.GalleryLog
import dev.pampa.mycloudgallery.core.model.MediaId
import kotlin.time.Duration

/**
 * Il registro **durevole** delle richieste del guardiano dopo una condivisione (Fase 7, D28): quali media aspettano il giro del guardiano chiesto
 * "subito". :app lo lega a una tabella o a un file (P13; finche' non c'e', [MemorySafeCopyRequestLog]). Ogni scrittura e' atomica.
 */
interface SafeCopyRequestLog {
  suspend fun add(media: Set<MediaId>)

  suspend fun pending(): Set<MediaId>

  suspend fun remove(media: Set<MediaId>)
}

/** Il registro in memoria (prove; in :app solo finche' P13 non lega quello su disco). */
class MemorySafeCopyRequestLog : SafeCopyRequestLog {
  private val lock = Any()
  private val ids = LinkedHashSet<MediaId>()

  override suspend fun add(media: Set<MediaId>) {
    synchronized(lock) { ids += media }
  }

  override suspend fun pending(): Set<MediaId> = synchronized(lock) { ids.toSet() }

  override suspend fun remove(media: Set<MediaId>) {
    synchronized(lock) { ids -= media }
  }
}

/**
 * D28 ("al sicuro" garantito), Q4 (b) della Fase 5, correzioni dal campo A (D2): [SafeCopyRequests] vero. L'orchestratore della condivisione lo
 * chiama per i suoi media **spostati** dall'area privata che il telefono **non ha piu'** (la condizione di Q4 (b) e' gia' vera): la richiesta si
 * registra prima del ritorno ([SafeCopyRequestLog], durevole: dopo la morte del processo c'e' ancora) e sveglia il lavoro del guardiano ([wake],
 * :app lo lega al lavoro della tappa `safety`), cosi' la copia privata parte **subito** invece di aspettare la prossima lettura del telefono.
 *
 * Il giro chiesto ([runRequested]) e' quello di sempre, con le regole di sempre: [GuardScope.All], cioe' solo i media propri con la sola copia
 * comune che il telefono **ha avuto** e non ha piu' (un media mai stato sul telefono, messo sul NAS dal PC, si ignora: resta il cestino del NAS
 * per 30 giorni, L44, L62), con i tetti del guardiano (20 GB per giro). Le richieste si tolgono solo a giro **finito**: un giro fermato (rete, NAS
 * pieno, budget) le lascia per il prossimo. Nessuna richiesta nasce da un dato letto dal NAS: la fa solo l'orchestratore, dopo una conferma.
 */
class GuardianSafeCopyRequests(
  private val log: SafeCopyRequestLog,
  private val guardian: PrivateCopyGuardian,
  private val wake: () -> Unit,
) : SafeCopyRequests {

  override suspend fun requestNow(media: List<MediaId>) {
    if (media.isEmpty()) return
    log.add(media.toSet())
    GalleryLog.d(TAG) { "copia privata chiesta subito per ${media.size} media (D28)" }
    wake()
  }

  /** Ci sono richieste in attesa (`guardianHasWork` di :app le conta). */
  suspend fun hasPending(): Boolean = log.pending().isNotEmpty()

  /** Il giro chiesto: null se non c'e' niente in attesa; a giro finito le richieste di prima sono servite (copiate o fuori dalle regole). */
  suspend fun runRequested(budget: Duration): GuardReport? {
    val pending = log.pending()
    if (pending.isEmpty()) return null
    val report = guardian.ensure(GuardScope.All, budget)
    if (report.stoppedBy == RunStop.COMPLETE) log.remove(pending)
    return report
  }

  private companion object {
    const val TAG = "BackupGuardiano"
  }
}
