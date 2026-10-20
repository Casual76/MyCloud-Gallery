package dev.pampa.mycloudgallery.core.backup.versions

import dev.pampa.mycloudgallery.core.backup.contract.UploadState
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.NasStamp
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days

/**
 * D32 (franchigia del congelamento per le versioni, come R8 della 4B): i file che **le versioni di questo dispositivo** hanno spostato o
 * messo in una share, per `fileId`, dal **diario locale** delle versioni (azioni nate da dati locali e, per "Ripristina originale", da una
 * conferma dell'utente; mai da dati letti dal NAS).
 *
 * Chi la usa: la guardia dei cambiamenti di massa della scansione (`MassChangeGuard`, core-nasmedia), legata da `:app` (P14) accanto a
 * `OwnRemovals`. Un solito percorso che cambia perche' una versione nuova vi e' stata pubblicata, un file che "sparisce" perche' e' stato
 * archiviato in `Versioni/`, l'originale riportato al suo posto: nessuno di questi conta fra i "cambiati" o gli "spariti" che congelano la
 * share. Un file cambiato o sparito per altra via (il PC, un altro dispositivo, un ransomware) conta come prima: serve lo **stesso**
 * `fileId` che il diario ricorda. Le copie nuove in `Versioni/` sono file nuovi e non contano comunque.
 *
 * **R17 della revisione 5B: solo la rinomina attesa, e per un tempo limitato.** Prima valeva per `fileId` e per sempre: un ransomware che cifra
 * sul posto (stesso `fileId`) un file che le versioni avevano toccato mesi prima non contava fra i "cambiati". Adesso un file e' in franchigia
 * solo se:
 *  - il lavoro che l'ha spostato e' **recente** ([window] d'ora del NAS dall'ultimo passo del diario; senza l'ora del NAS, nessuna franchigia:
 *    la guardia conta tutto, un falso allarme e' meglio di uno mancato);
 *  - il timbro e' **uno che l'app ha prodotto** (dopo l'archiviazione, la pubblicazione o il ripristino; mai il file trovato gia' al suo posto di
 *    un `LINKED_EXISTING`);
 *  - il catalogo **non ha ancora registrato** quella rinomina: per quel `fileId` non ha nessuna copia, o solo il timbro di prima della
 *    rinomina. Con il timbro dell'app e' in pari (il motore la segue subito dopo la rinomina, o l'ha messa in pari la scansione): da li' in
 *    poi la scansione confronta con il timbro dell'app, e un cambiamento sul posto e' di qualcun altro e conta come sempre. Con un altro
 *    timbro ancora, la scansione ha gia' visto un cambiamento che non e' dell'app.
 * In pratica la franchigia copre solo la finestra fra la rinomina sul NAS e il catalogo che la segue (una scansione che arriva in mezzo, un
 * `relocate` non riuscito che la scansione mette in pari).
 *
 * In concreto, per ogni lavoro della share: il `fileId` dell'attuale spostato in `Versioni/` (timbro dopo l'archiviazione), quello della
 * versione pubblicata (timbro finale) e, per uno scambio, quello della versione riportata al solito percorso. Un lavoro fermo o fallito
 * prima di spostare qualcosa non da' niente.
 *
 * @param catalog il catalogo delle versioni di adesso (null = non c'e': nessuna franchigia).
 * @param nasNowMs l'ora del NAS (null = sconosciuta: nessuna franchigia).
 */
class VersionFranchise(
  private val journal: VersionJournal,
  private val catalog: suspend () -> VersionCatalog?,
  private val nasNowMs: () -> Long?,
  private val window: Duration = WINDOW,
) {

  /** I `fileId` dei file che le versioni hanno spostato o pubblicato in [share] e che il catalogo non ha ancora seguito. */
  suspend fun fileIds(share: String): Set<Long> {
    val now = nasNowMs() ?: return emptySet()
    val versions = catalog() ?: return emptySet()
    val out = LinkedHashSet<Long>()
    val seen = HashMap<MediaId, Map<Long, List<NasStamp>>>()
    for (job in journal.inShareSince(share, now - window.inWholeMilliseconds)) {
      val stamps = seen.getOrPut(job.mediaId) { catalogStamps(versions, job.mediaId, share) }
      // L'attuale spostato in `Versioni/`: prima della rinomina aveva il timbro del piano.
      job.archivedStamp?.let { if (pending(it, job.current?.stamp, stamps)) out += it.fileId!! }
      // Il timbro finale e' quello del file pubblicato (o riportato) da noi solo da `PUBLISHED`/`RESTORED` in poi: prima puo' essere vecchio.
      // `LINKED_EXISTING`: il finale e' un file trovato gia' li', non prodotto dall'app.
      if (job.state in PLACED) {
        val before = (job.source as? VersionSource.InVersions)?.stamp
        job.finalStamp?.let { if (pending(it, before, stamps)) out += it.fileId!! }
      }
    }
    return out
  }

  /**
   * La rinomina che ha dato [produced] non e' ancora nel catalogo: per quel `fileId` il catalogo non ha nessuna copia, o ha solo il timbro di
   * prima della rinomina ([before]). Se ha il timbro dell'app e' in pari; se ne ha un altro, la scansione ha gia' visto un cambiamento che non e'
   * dell'app: in entrambi i casi niente franchigia.
   */
  private fun pending(produced: NasStamp, before: NasStamp?, stamps: Map<Long, List<NasStamp>>): Boolean {
    val id = produced.fileId ?: return false
    val known = stamps[id] ?: return true
    if (known.any { it.size == produced.size && it.modifiedAtMillis == produced.modifiedAtMillis }) return false
    return before != null && known.all { it.size == before.size && it.modifiedAtMillis == before.modifiedAtMillis }
  }

  /** I timbri delle copie presenti del media in [share] che il catalogo conosce, per `fileId`. */
  private suspend fun catalogStamps(versions: VersionCatalog, mediaId: MediaId, share: String): Map<Long, List<NasStamp>> {
    val media = versions.media(mediaId) ?: return emptyMap()
    val out = HashMap<Long, MutableList<NasStamp>>()
    for (v in media.versions) for (copy in v.nas) {
      if (!copy.present || !copy.share.equals(share, ignoreCase = true)) continue
      val id = copy.stamp.fileId ?: continue
      out.getOrPut(id) { ArrayList(1) } += copy.stamp
    }
    return out
  }

  companion object {
    /** Quanto a lungo un lavoro delle versioni da' la franchigia: una settimana d'ora del NAS (come la conferma del congelamento, NA-4). */
    val WINDOW: Duration = 7.days

    private val PLACED = setOf(UploadState.PUBLISHED, UploadState.RESTORED, UploadState.LINKED, UploadState.DONE, UploadState.FAILED)
  }
}
