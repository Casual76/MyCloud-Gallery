package dev.pampa.mycloudgallery.core.backup.contract

import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.trust.nas.SharePath

// Fase 7 (docs/fase7-progetto.md D12, D28, §1.2 "core-backup", §2.5 passi 4 e 7; E-SH1, E-B13, E-B14): cio' che la condivisione di un
// album chiede al backup. **Solo firme** (P0): la destinazione "cartella dell'album" nel caricamento `INCOMING` e la richiesta del
// guardiano dopo uno spostamento li scrive P4; :app li lega alle porte di core-albums (`AlbumUploader`, `SafeCopyRequester`), che di
// core-backup non vede niente (Phase7ArchitectureTest). Nessuna di queste firme puo' partire da un dato letto dal NAS: le chiama solo
// l'orchestratore della condivisione dopo una conferma locale (`ShareConfirmation`, C26).

/** Dove va un caricamento `INCOMING` (D24 della Fase 5, D12 della Fase 7). */
sealed interface IncomingDestination {
  /** "Salva sul NAS" della Fase 5B: la cartella scelta con Cartelle NAS (area privata o comune). */
  data class Chosen(val destination: NasDestination) : IncomingDestination {
    override fun toString(): String = "IncomingDestination.Chosen"
  }

  /**
   * Fase 7 (E-SH1): la cartella di un album condiviso **nell'area comune** ([share] e' la share comune del registro, [folder] la cartella
   * dell'album, "Album condivisi/<nome>" per la risposta Q1 (a)). Per i media che stanno solo sul telefono: si caricano direttamente li',
   * con le stesse verifiche di `INCOMING` (copia, SHA-256, pubblicazione esclusiva, nomi alternativi). Viene solo da un piano di
   * condivisione confermato. Percorso e share sono dati dell'utente: mai nei log.
   */
  data class AlbumFolder(val share: String, val folder: SharePath) : IncomingDestination {
    override fun toString(): String = "IncomingDestination.AlbumFolder"
  }
}

/**
 * D28 ("al sicuro" garantito): mette **subito** in coda al guardiano (F5-D9) la copia privata di [media], anche se la copia del telefono non
 * e' appena sparita: e' il caso dei media **propri** gia' assenti dal telefono che uno spostamento ha lasciato con la sola copia comune (la
 * condizione di Q4 (b) della Fase 5 e' gia' vera). Durevole (la richiesta e' registrata prima del ritorno: dopo la morte del processo il
 * guardiano la ritrova) e idempotente (un media gia' in coda o gia' protetto non si ripete). Il guardiano decide con le sue regole e i suoi
 * tetti: un media non proprio o mai stato sul telefono si ignora (correzioni dal campo A, D2). Un'eccezione vuol dire "non registrata".
 */
interface SafeCopyRequests {
  suspend fun requestNow(media: List<MediaId>)
}
