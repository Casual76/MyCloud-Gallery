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
