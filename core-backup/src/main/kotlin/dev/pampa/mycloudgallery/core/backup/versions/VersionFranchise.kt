package dev.pampa.mycloudgallery.core.backup.versions

import dev.pampa.mycloudgallery.core.backup.contract.UploadState

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
 * In concreto, per ogni lavoro della share: il `fileId` dell'attuale spostato in `Versioni/` (timbro dopo l'archiviazione), quello della
 * versione pubblicata (timbro finale) e, per uno scambio, quello della versione riportata al solito percorso. Un lavoro fermo o fallito
 * prima di spostare qualcosa non da' niente.
 */
class VersionFranchise(private val journal: VersionJournal) {

  /** I `fileId` dei file che le versioni hanno spostato o pubblicato in [share]. */
  suspend fun fileIds(share: String): Set<Long> {
    val out = LinkedHashSet<Long>()
    for (job in journal.inShare(share)) {
      job.archivedStamp?.fileId?.let(out::add)
      // Il timbro finale e' quello del file pubblicato (o riportato) da noi solo da `PUBLISHED`/`RESTORED` in poi: prima puo' essere vecchio.
      if (job.state in PLACED) job.finalStamp?.fileId?.let(out::add)
    }
    return out
  }

  private companion object {
    val PLACED = setOf(
      UploadState.PUBLISHED, UploadState.RESTORED, UploadState.LINKED, UploadState.DONE, UploadState.LINKED_EXISTING, UploadState.FAILED,
    )
  }
}
