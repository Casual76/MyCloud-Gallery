package dev.pampa.mycloudgallery.core.nasops.contract

import dev.pampa.mycloudgallery.core.trust.nas.SharePath

// Fase 7, revisione B (R3; docs/fase7-progetto.md §2.4, righe "Eliminare il file" e "Nuova versione", E-D16, D39): le righe della tabella dei
// permessi degli album condivisi che per progetto controlla **solo chi scrive** (sul NAS non c'e' un permesso da leggere, L57). Prima non le
// applicava nessuno: il viewer, Cartelle NAS, "Ovunque" e le versioni mandavano nel cestino il file di un elemento altrui a ogni livello. Il
// punto in comune a tutte quelle strade e' il piano di core-nasops: qui si chiede, per ogni file che l'utente vuole togliere dal suo posto
// (cestino, spostamento, rinomina), se e' protetto.

/**
 * I file che un'operazione dell'utente non puo' togliere dal loro posto (cestino, spostamento, rinomina) perche' sono elementi di un album
 * condiviso e la tabella dei permessi (riga "Eliminare il file": a "Gli altri aggiungono" il creatore e chi l'ha aggiunto, a "Gli altri guardano"
 * solo il creatore) non lo permette a questo account. :app la lega agli album (replica, tabella `AlbumPermissions`); di serie nessun file e'
 * protetto (le prove della 4B, un'app senza album).
 *
 * Il piano lascia fuori un elemento protetto con [SkipReason.SHARED_ALBUM_ITEM] (e il foglio lo dice); un piano che resta vuoto si rifiuta con
 * [PlanRejection.SHARED_ALBUM_ITEM]; una rinomina di un file protetto si rifiuta. Un errore di chi risponde vale "protetto" (si fallisce chiusi).
 */
fun interface ProtectedFiles {
  /** Fra [items] di [share] (file e cartelle scelti dall'utente), quelli da lasciare dove sono: un file protetto o una cartella che ne contiene. */
  suspend fun protectedAmong(share: String, items: List<NasItem>): Set<SharePath>

  companion object {
    val NONE: ProtectedFiles = ProtectedFiles { _, _ -> emptySet() }
  }
}
