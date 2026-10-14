package dev.pampa.mycloudgallery.core.nas.session

/**
 * Chi decide, **prima di qualunque richiesta**, se l'account in uso puo' toccare una share (D33 della Fase 4).
 *
 * Il gestore della sessione la consulta per ogni via d'accesso: `withShare`, `NasFileSystem`, `NasBrowser`,
 * `NasMediaReader`, la copia lato server (sorgente e destinazione), la prova della share, la pulizia e (4B) `NasFileOps`.
 * Una share rifiutata da' `NasError.PermissionDenied` senza aprire socket ne' mandare `TREE_CONNECT`.
 *
 * In produzione non c'e' (null): l'unica implementazione, `fence.ShareFence`, la lega :app **solo nella build debug**
 * (P11), cosi' R8 la toglie dalla release.
 */
fun interface ShareGuard {
  /** Vero se l'account [user] puo' usare la share [share]. */
  fun allows(user: String, share: String): Boolean
}

/**
 * La recinzione D33 **di processo** (R11 della revisione 4A): `:app` la installa una volta, all'avvio, con la stessa guardia
 * del gestore principale (`DeveloperTools.shareGuard()`: `ShareFence` in debug, null in release). Vale da default per ogni
 * altra via che apre una share con una connessione sua: il test di connessione dell'onboarding e della Diagnostica
 * ([dev.pampa.mycloudgallery.core.nas.tools.SmbConnectionTester], prima della fase della share) e i gestori costruiti per
 * un altro account (la sessione "estranea" del kit di recupero). Cosi' nessuna strada resta fuori dalla recinzione anche
 * se chi la costruisce non la passa.
 *
 * In release resta null: `ShareFence` non e' referenziata e R8 la toglie (`tools/check.ps1` lo verifica).
 */
object ShareFences {
  @Volatile
  var installed: ShareGuard? = null
    private set

  /** Installa la recinzione del processo (una volta, prima di costruire le sessioni). */
  fun install(guard: ShareGuard?) {
    installed = guard
  }
}
