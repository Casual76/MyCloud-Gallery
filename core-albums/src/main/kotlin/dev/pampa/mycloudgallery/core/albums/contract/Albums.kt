package dev.pampa.mycloudgallery.core.albums.contract

import dev.pampa.mycloudgallery.core.common.diagnostics.DiagCode
import dev.pampa.mycloudgallery.core.model.AlbumId
import dev.pampa.mycloudgallery.core.model.AlbumKind
import dev.pampa.mycloudgallery.core.model.AlbumSort
import dev.pampa.mycloudgallery.core.model.MediaId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

// L'ELENCO E LE MODIFICHE DEGLI ALBUM (docs/fase7-progetto.md §1.2 "Elenco", §2.1, D3-D5, D12, D21, D22, D35, D39). Contratto di P0;
// gli album personali (proiezione, ordine, copertina) sono di P3, le schermate di P10, l'integrazione di P13. L'esecutore di riferimento e'
// `InMemoryAlbums` (testing/) e la suite di contratto `AlbumsContract` (src/test) la passano entrambi.
//
// Regole che ogni implementazione rispetta (le prova `AlbumsContract`):
// - **mai un nascosto** in elenco, conteggi, copertine, contenuto (D22): un media nascosto resta elemento dell'album ma non si mostra;
// - **togliere non e' eliminare** (D39): nessuna modifica di un album tocca un file;
// - **un album condiviso non riceve media senza conferma** (D12): `add` restituisce il piano da confermare, mai un'aggiunta;
// - ogni modifica e' idempotente (due volte = una) e vale subito su questo dispositivo, senza aspettare la sincronizzazione.

/**
 * Gli album dell'account: personali (manuali e smart) e condivisi (miei e dei familiari), senza quelli lasciati (E-SH21). Le modifiche
 * di un album condiviso rispettano la tabella dei permessi ([AlbumPermissions]): un'azione non permessa da' [EditOutcome.NotAllowed].
 */
interface Albums {
  val all: StateFlow<AlbumList>

  val status: StateFlow<AlbumsStatus>

  /** Il contenuto di [id], aggiornato quando cambiano l'album o la Libreria; null se l'album non c'e' (piu'). */
  fun content(id: AlbumId): Flow<AlbumContent?>

  /**
   * Un album personale nuovo, con l'id legato al mio account (`AlbumId.derived`, D7). [rules] e' obbligatorio per [AlbumKind.SMART] e
   * vietato per [AlbumKind.MANUAL]. Gli album condivisi nascono da `SharedAlbums.create` (con il foglio di conferma).
   */
  suspend fun create(title: String, kind: AlbumKind, rules: SmartRules? = null): CreateOutcome

  suspend fun rename(id: AlbumId, title: String): EditOutcome

  /** Elimina un album **personale** (lapide della definizione); i media restano dove sono. Un condiviso si chiude con `SharedAlbums.close`. */
  suspend fun delete(id: AlbumId): EditOutcome

  /**
   * Aggiunge [media] a [id]. Personale: una voce `item:` con impronta per media, subito (anche un nascosto, che non si mostra finche' e'
   * nascosto, D22). Condiviso: **mai** un'aggiunta diretta, ma [AddOutcome.NeedsShareConfirmation] con il piano (D12, D35).
   */
  suspend fun add(id: AlbumId, media: List<MediaId>): AddOutcome

  /** "Rimuovi dall'album" (D39): toglie le voci; il file resta dov'e'. Su un album smart un elemento automatico diventa un'esclusione. */
  suspend fun remove(id: AlbumId, media: List<MediaId>): EditOutcome

  /** La copertina scelta; null = automatica (D21). Un media che non e' un elemento mostrabile dell'album da' [EditOutcome.Invalid]. */
  suspend fun setCover(id: AlbumId, media: MediaId?): EditOutcome

  suspend fun setSort(id: AlbumId, sort: AlbumSort): EditOutcome

  /** Ordine personalizzato: [moved] subito dopo [after] (null = in testa). Posizioni a intervalli (D21). Vale con [AlbumSort.CUSTOM]. */
  suspend fun reorder(id: AlbumId, moved: MediaId, after: MediaId?): EditOutcome

  /** L'ordine dell'elenco (impostazione sincronizzata `albums_sort`). */
  suspend fun setListSort(sort: AlbumListSort)

  /** Ordine personalizzato dell'elenco: [id] subito dopo [after] (null = in testa), voce `order`. */
  suspend fun moveInList(id: AlbumId, after: AlbumId?): EditOutcome
}

/** L'esito di [Albums.create]. */
sealed interface CreateOutcome {
  data class Created(val id: AlbumId) : CreateOutcome

  data class Invalid(val problem: TitleProblem) : CreateOutcome

  /** Senza account non si deriva l'id (nessuna famiglia pronta). */
  data object Unavailable : CreateOutcome
}

/** L'esito di una modifica. Mai un'eccezione per un caso previsto. */
sealed interface EditOutcome {
  data object Done : EditOutcome

  data object NotFound : EditOutcome

  /** La tabella dei permessi non lo permette a me su questo album, a questo livello ([op]); o l'operazione non vale per questo tipo di album. */
  data class NotAllowed(val op: AlbumOp?) : EditOutcome

  /** Un titolo non valido, o un argomento che non ha senso per l'album (copertina che non e' un suo elemento mostrabile). */
  data class Invalid(val problem: TitleProblem?) : EditOutcome

  /** La scrittura e' ferma (cancelli di D38, recinzione D30): niente e' cambiato. */
  data class Blocked(val reason: AlbumWriteBlock) : EditOutcome
}

/** L'esito di [Albums.add]. */
sealed interface AddOutcome {
  /** Album personale: [added] voci nuove, [alreadyThere] gia' elementi (idempotenza). */
  data class Added(val added: Int, val alreadyThere: Int) : AddOutcome

  /** Album condiviso: il piano da confermare nel foglio (D12). Senza conferma non si sposta niente. */
  data class NeedsShareConfirmation(val plan: SharePlan) : AddOutcome

  /** Il piano non si puo' fare adesso (recinzione D30, cancelli, spazio, album chiuso...). */
  data class Blocked(val reason: ShareBlock) : AddOutcome

  data class NotAllowed(val op: AlbumOp) : AddOutcome

  /** Nessuno dei media si puo' aggiungere (tutti esclusi dal piano, con il motivo). */
  data class NothingToAdd(val excluded: Map<ShareExclusion, Int>) : AddOutcome

  data object NotFound : AddOutcome
}

/** Lo stato del lavoro degli album, per Attivita' e Diagnostica: solo numeri e codici (D37). */
sealed interface AlbumsStatus {
  data object Ready : AlbumsStatus

  /** Ricalcolo degli album smart (D16). */
  data class Recomputing(val done: Int, val total: Int) : AlbumsStatus

  /** Condivisioni in corso ([ops] operazioni non finite). */
  data class Sharing(val ops: Int) : AlbumsStatus

  /** Condivisioni ferme per un motivo (spazio, congelamento, file aperti...). */
  data class Waiting(val reason: ShareWait) : AlbumsStatus

  data class Problem(val code: DiagCode) : AlbumsStatus
}
