package dev.pampa.mycloudgallery.core.albums.shared

import dev.pampa.mycloudgallery.core.albums.contract.SharedState
import dev.pampa.mycloudgallery.core.model.AccountId
import dev.pampa.mycloudgallery.core.model.AlbumId
import dev.pampa.mycloudgallery.core.trust.nas.SharePath

// I SEGNI LOCALI DI QUESTO DISPOSITIVO SUGLI ALBUM CONDIVISI (R1 della revisione di sicurezza della Fase 7; D31, Q5 b). Cio' che questo
// dispositivo ha **visto** di un album, e che una definizione letta dopo dal NAS non puo' cambiare: se l'album e' stato attivo nella sua
// proiezione (solo allora una sua chiusura con "ognuno tiene una copia" fa nascere una copia personale, e solo allora se ne da' l'avviso), la
// prima chiusura vista (definitiva per quell'id: una definizione `active` dopo si scarta, finche' chi ha chiuso resta fidato), e la copia
// personale gia' scritta (gli elementi si fissano alla prima scrittura) o ritirata (la revoca di chi l'ha causata). Locali: mai sincronizzati,
// mai pubblicati; :app li tiene su file (`StateFiles`), l'azzeramento li toglie.

/** La prima chiusura vista di un album: stato, scelta per gli altri (Q5), ora del NAS, il dispositivo che l'ha firmata, titolo e cartella. */
data class ClosureMark(
  val state: SharedState,
  val afterClose: String?,
  val at: Long,
  val author: String,
  val title: String,
  val folder: SharePath,
) {
  init {
    require(state != SharedState.ACTIVE) { "Una chiusura non e' attiva." }
  }

  override fun toString(): String = "ClosureMark(${state.code})"
}

/**
 * La copia personale di Q5 (b) di un album chiuso, gia' scritta nei dati personali: [copy] il suo id, [creator] il creatore dell'album (i
 * tetti per creatore), [items] quanti elementi aveva alla prima scrittura, [cause] il dispositivo che ha firmato la chiusura (la sua revoca la
 * ritira), [withdrawn] ritirata.
 */
data class CopyMark(val copy: AlbumId, val creator: AccountId, val items: Int, val cause: String?, val withdrawn: Boolean = false) {
  override fun toString(): String = "CopyMark($items, ritirata=$withdrawn)"
}

/** Cio' che questo dispositivo ha visto di un album condiviso. */
data class AlbumMarks(val seenActive: Boolean = false, val closure: ClosureMark? = null, val copy: CopyMark? = null) {
  override fun toString(): String = "AlbumMarks(visto=$seenActive, chiuso=${closure != null}, copia=${copy != null})"
}

/** I segni locali (R1): letti tutti insieme, modificati in un colpo solo (una scrittura del file per ricalcolo, non per album). */
interface SharedAlbumMarks {
  suspend fun all(): Map<AlbumId, AlbumMarks>

  suspend fun update(change: (MutableMap<AlbumId, AlbumMarks>) -> Unit)
}

/** I segni in memoria (prove; in :app quelli su file). */
class MemorySharedAlbumMarks : SharedAlbumMarks {
  private val lock = Any()
  private val marks = LinkedHashMap<AlbumId, AlbumMarks>()

  override suspend fun all(): Map<AlbumId, AlbumMarks> = synchronized(lock) { LinkedHashMap(marks) }

  override suspend fun update(change: (MutableMap<AlbumId, AlbumMarks>) -> Unit) {
    synchronized(lock) { change(marks) }
  }
}

/**
 * I tetti delle copie personali di Q5 (b) (R1): per creatore, in tutta la vita di questo dispositivo, al piu' [maxCopiesPerCreator] copie e
 * [maxItemsPerCreator] elementi in tutto. Un dispositivo compromesso non riempie i dati personali di ogni familiare di album "chiusi".
 * Una copia che non ci sta non nasce (si conta), e l'avviso di chiusura non la promette.
 */
data class ClosedCopyLimits(val maxCopiesPerCreator: Int = 32, val maxItemsPerCreator: Int = 20_000)
