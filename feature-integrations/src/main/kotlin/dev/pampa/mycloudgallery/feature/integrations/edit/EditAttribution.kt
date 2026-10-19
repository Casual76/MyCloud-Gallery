package dev.pampa.mycloudgallery.feature.integrations.edit

import dev.pampa.mycloudgallery.core.backup.contract.EditSessionRules
import dev.pampa.mycloudgallery.core.mediastore.contract.MediaStoreRow
import dev.pampa.mycloudgallery.core.mediastore.contract.RowType

// Fase 5B (P11, docs/fase5-progetto.md §2.7, D25, D31, E-E5, E-X3): le REGOLE con cui un cambiamento appartiene a una sessione di "Modifica con…".
// Pure, provate una per una. Samsung Gallery non risponde ad `ACTION_EDIT` (scoperta 11): l'esito si legge dal MediaStore.
//
//  - La riga **cambiata** (Samsung "Salva" scrive sopra) la riconosce la lettura del telefono, che crea la versione (D18): qui non si confrontano
//    i byte, si guarda cosa dice il catalogo dopo averlo messo in pari.
//  - Una **copia** ("Salva copia") e' una riga nuova. E' dello stesso media solo se, insieme: nasce **durante** la sessione, sta nella stessa
//    cartella, ha la **stessa data di scatto** ed e' dello stesso genere (foto o video). Senza la data di scatto non si riconosce niente: una
//    foto scattata nel frattempo nella stessa cartella non e' una versione.
//  - "Durante" non si misura con l'orologio del telefono (D31, §4.3): si usa la **generazione** del MediaStore (cresce a ogni cambiamento del
//    volume; una riga nata dopo l'inizio ha una generazione maggiore di quella letta all'inizio). Senza generazioni (Android 10) si confronta la data
//    di modifica della riga con quella della riga di partenza, due dati dello stesso MediaStore.
//  - **Nel dubbio non si unisce**: piu' di una candidata = nessuna e' attribuita.

object EditAttribution {

  /** Quante pagine di righe si guardano al massimo cercando una copia (500 righe l'una): senza generazioni (Android 10) si scorre tutto il volume. */
  const val SEARCH_PAGE: Int = 500
  const val SEARCH_PAGES: Int = 12

  /**
   * [row] puo' essere la copia che l'editor ha scritto per la sessione?
   *
   * @param ownId la riga di partenza (la stessa riga non e' mai la sua copia), o null per un media solo-NAS.
   * @param sameFolderOnly vero per le sessioni sul telefono (la copia sta accanto all'originale); falso per un media solo-NAS, dove l'editor
   *   puo' salvare in qualunque cartella.
   * @param toleranceMs lo scarto ammesso sulla data di scatto: zero per il telefono (la copia porta la data della riga di partenza, identica);
   *   [NAS_TAKEN_TOLERANCE_MS] per un media solo-NAS, la cui data viene dall'EXIF e non da una riga del MediaStore.
   */
  fun isCopyCandidate(
    snapshot: EditSnapshot,
    row: MediaStoreRow,
    isVideo: Boolean,
    ownId: Long?,
    sameFolderOnly: Boolean,
    toleranceMs: Long = 0,
  ): Boolean {
    if (ownId != null && row.id == ownId) return false
    if ((row.type == RowType.VIDEO) != isVideo) return false
    val taken = snapshot.takenAtUtcMs ?: return false
    val rowTaken = row.dateTakenMs ?: return false
    if (kotlin.math.abs(rowTaken - taken) > toleranceMs) return false
    if (sameFolderOnly && row.relativePath != snapshot.relativePath) return false
    return bornAfterStart(snapshot, row)
  }

  /** La riga nasce dopo l'inizio della sessione (o e' stata riscritta dopo): per generazione, o per data di modifica senza generazioni. */
  fun bornAfterStart(snapshot: EditSnapshot, row: MediaStoreRow): Boolean {
    val start = snapshot.startGeneration
    val generation = row.generationModified
    return if (start != null && generation != null) generation > start else row.dateModifiedMs >= snapshot.startModifiedMs
  }

  /** La sessione e' scaduta: oltre la finestra un cambiamento non e' piu' attribuito (E-E5). */
  fun expired(nowNasMillis: Long, expiresAtNasMillis: Long): Boolean = nowNasMillis > expiresAtNasMillis

  /** Per un media solo-NAS la data di scatto viene dall'EXIF: un secondo di scarto non basta a dire che e' un'altra foto. */
  const val NAS_TAKEN_TOLERANCE_MS: Long = 1_000

  /** La finestra di una sessione, in millisecondi (30 minuti, D25). */
  const val WINDOW_MS: Long = EditSessionRules.WINDOW_MS
}
