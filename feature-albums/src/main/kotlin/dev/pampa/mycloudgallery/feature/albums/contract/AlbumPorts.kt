package dev.pampa.mycloudgallery.feature.albums.contract

import dev.pampa.mycloudgallery.core.imaging.contract.PlaceholderCache
import dev.pampa.mycloudgallery.core.imaging.contract.ThumbRequest
import dev.pampa.mycloudgallery.core.imaging.contract.ThumbnailLoader
import dev.pampa.mycloudgallery.core.library.contract.TimelineSnapshot
import dev.pampa.mycloudgallery.core.model.AccountId
import dev.pampa.mycloudgallery.core.model.AlbumId
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaRef
import java.time.LocalDate

// Le PORTE delle schermate degli album (Fase 7, P10; docs/fase7-progetto.md §1.1, §1.5, D32). feature-albums vede di core-albums solo i
// contratti (Phase7ArchitectureTest): quello che serve alle schermate e non e' un contratto di core-albums sta qui, e `:app` lo lega (P13).
// Tutte **opzionali per Dagger** ([AlbumPortsModule]): finche' `:app` non le lega, le schermate partono con i null-object e falliscono
// chiuse (nessun album, nessuna miniatura, nessuna proposta), e il grafo di `:app` compila.

/**
 * Da dove le schermate degli album prendono le miniature: **solo cio' che e' gia' sul dispositivo**, come in Cerca (D28, D33). Il [loader]
 * e' quello della Libreria (o un caricatore finto nei test); un media senza sorgente locale mostra il segnaposto sfocato ([placeholders]).
 */
class AlbumThumbnails(
  val loader: ThumbnailLoader,
  val placeholders: PlaceholderCache,
  val refs: AlbumRefs,
)

/** La sorgente di una miniatura: per una cella di una vista (come la Libreria) e per un media dato solo per id (copertina, esempio, anteprima). */
interface AlbumRefs {
  /** La sorgente della miniatura per la cella [index] dell'istantanea, o null se il media non ha una sorgente locale. */
  fun refAt(snapshot: TimelineSnapshot, index: Int): MediaRef?

  /** La richiesta di miniatura di [media] (chiave e sorgente), o null se il catalogo non lo conosce o non ha una sorgente locale. */
  fun request(media: MediaId): ThumbRequest?
}

/** Nessuna sorgente: ogni miniatura e' un segnaposto. */
object NoAlbumRefs : AlbumRefs {
  override fun refAt(snapshot: TimelineSnapshot, index: Int): MediaRef? = null

  override fun request(media: MediaId): ThumbRequest? = null
}

/**
 * L'orologio delle schermate: "oggi" per i periodi (giorni locali dello scatto, D36) e l'orologio monotono per la comparsa del foglio di
 * conferma (il secondo colpo di un doppio tocco non conta, R6 della 4B) e per la scadenza di un piano. `:app` lo lega a `Today` e a
 * `SystemClock.elapsedRealtime`; nei sorgenti non c'e' mai l'ora del telefono (NoWallClockSourcesTest).
 */
interface AlbumScreensClock {
  fun today(): LocalDate

  fun monotonicMs(): Long
}

/** Un orologio fermo: per i test e per le anteprime. */
class FixedAlbumScreensClock(private val day: LocalDate, var nowMs: Long = 1_000_000L) : AlbumScreensClock {
  override fun today(): LocalDate = day

  override fun monotonicMs(): Long = nowMs
}

/**
 * Il nome con cui si mostra un familiare ("Creato da Marco", "Aggiunta da Marco": Q6). Di serie l'account com'e'. `:app` lo lega al registro
 * della famiglia. Il nome e' un dato dell'utente: mai nei log.
 */
fun interface AlbumPeople {
  fun nameOf(account: AccountId): String
}

/** Il nome e' l'account com'e'. */
object AccountNamesAsIs : AlbumPeople {
  override fun nameOf(account: AccountId): String = account.value
}

/**
 * Chi ha aggiunto un elemento a un album condiviso (Q6: "Aggiunta da Marco", e serve ai permessi). `:app` lo lega a `SharedAlbumFacts.members`.
 * Null = non si sa (album personale, elemento non ancora pubblicato). Mai nei log.
 */
fun interface AlbumMembers {
  suspend fun addedBy(album: AlbumId, media: MediaId): AccountId?
}

/** Nessuno sa chi ha aggiunto cosa: la riga "Aggiunta da" non compare. */
object NoAlbumMembers : AlbumMembers {
  override suspend fun addedBy(album: AlbumId, media: MediaId): AccountId? = null
}

/**
 * I media che una striscia della pagina di un album condiviso puo' portare in un piano, quando l'avviso ne da' solo il numero: i file che il
 * catalogo vede nella cartella dell'album e non sono elementi (D39, "N file nuovi nella cartella: aggiungili") e le mie foto che il livello
 * nuovo non permette piu' (E-SH13, "Riporta nelle mie cartelle"). `:app` lo lega a `FolderAdoptions.pending` e a `BringBackCandidates`. Senza,
 * le strisce restano un'informazione (nessun bottone).
 */
interface AlbumPending {
  suspend fun filesFromFolder(album: AlbumId): List<MediaId>

  suspend fun notAdded(album: AlbumId): List<MediaId>
}

/** Niente da proporre: le strisce non hanno il bottone. */
object NoAlbumPending : AlbumPending {
  override suspend fun filesFromFolder(album: AlbumId): List<MediaId> = emptyList()

  override suspend fun notAdded(album: AlbumId): List<MediaId> = emptyList()
}

/**
 * Cosa si vuole aggiungere a un album (D35: dalla selezione in Libreria, dal viewer ⋮, dai risultati di Cerca, dalla condivisione in arrivo).
 * [fromHidden] e' vero solo dai Nascosti: li' l'album riceve il media ma non lo mostra finche' e' nascosto (D22); da ogni altro posto un
 * nascosto non si offre mai ("i nascosti non si offrono fuori dai Nascosti", §2.1).
 *
 * [album] (Fase 7, P13): l'album e' gia' scelto (la condivisione in arrivo l'ha scelto prima che i file fossero nel catalogo, P10-10): il foglio
 * aggiunge subito li', e per un album condiviso apre direttamente la conferma col conteggio (D12). Null = l'utente sceglie dall'elenco.
 */
class AddToAlbumRequest(val media: List<MediaId>, val fromHidden: Boolean = false, val album: AlbumId? = null) {
  override fun toString(): String = "AddToAlbumRequest(${media.size}, fromHidden=$fromHidden, preselected=${album != null})"
}
