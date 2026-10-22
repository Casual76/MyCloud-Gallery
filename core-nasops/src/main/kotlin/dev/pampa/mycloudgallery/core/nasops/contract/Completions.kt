package dev.pampa.mycloudgallery.core.nasops.contract

import dev.pampa.mycloudgallery.core.model.NasStamp
import dev.pampa.mycloudgallery.core.model.Sha256
import dev.pampa.mycloudgallery.core.trust.nas.SharePath

// Fase 7 (docs/fase7-progetto.md D29, §1.2 "core-nasops", §2.5 passo 5; C26): la PORTA DI COMPLETAMENTO degli spostamenti. Firma e
// null-object di P0; la chiamata dentro `NasOpsEngine` (dopo il passo `PUBLISHED`, prima di `SOURCE_RECORDED`) e il legame in :app
// (`MediaCatalog.attachMoved`) sono di P4 e P13.
//
// Rispetto a `engine.MoveCompletions` (correzioni dal campo, gruppo A: un annuncio "al meglio", il cui errore non ferma niente) questa
// porta e' **vincolante**: un errore ferma l'elemento in `PUBLISHED` e si ritenta, cosi' la sorgente non va mai nel cestino prima che
// il catalogo conosca la destinazione (E-SH2, invariante 3 e 5 del §5.5). P4 decide se `MoveCompletions` resta (catalogo dei media non
// condivisi) o diventa un adattatore di questa porta; le due non devono aggiornare il catalogo due volte in modo diverso.

/** Un file del NAS: share e percorso relativo alla share. Dati dell'utente: mai nei log. */
data class NasFileRef(val share: String, val path: SharePath) {
  override fun toString(): String = "NasFileRef"
}

/**
 * Chiamata dal motore per ogni file di uno spostamento tra share **dopo** `PUBLISHED` (la copia verificata e' al suo posto, [sha256] e'
 * quello verificato, [stamp] il timbro della destinazione letto adesso) e **prima** di `SOURCE_RECORDED`. Chi la lega (`:app`) collega
 * subito la posizione [dest] allo **stesso** media della sorgente [source] (stesso `MediaId`, stessa versione, D29): preferiti, nascosti,
 * album, vettori e gruppi restano collegati.
 *
 * Regole: **idempotente** (si richiama a ogni ripresa del passo, anche dopo la morte del processo, con gli stessi argomenti); un'eccezione
 * vuol dire "il catalogo non la conosce ancora": il motore lascia l'elemento in `PUBLISHED` e riprova, **mai** la sorgente nel cestino
 * prima del ritorno senza errori. Mai un'azione sul NAS: solo il catalogo locale. [index] e' la posizione dell'elemento nell'operazione
 * [op] (`OpItemRecord.index`).
 */
fun interface MoveCompletionSink {
  suspend fun published(op: OpId, index: Int, source: NasFileRef, dest: NasFileRef, stamp: NasStamp, sha256: Sha256)
}
