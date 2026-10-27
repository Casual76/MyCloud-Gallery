package dev.pampa.mycloudgallery.feature.albums.contract

import dev.pampa.mycloudgallery.core.albums.contract.Album
import dev.pampa.mycloudgallery.core.albums.contract.AlbumContent
import dev.pampa.mycloudgallery.core.albums.contract.AlbumListSort
import dev.pampa.mycloudgallery.core.albums.contract.AlbumOp
import dev.pampa.mycloudgallery.core.albums.contract.CloseMode
import dev.pampa.mycloudgallery.core.albums.contract.HiddenChoice
import dev.pampa.mycloudgallery.core.albums.contract.LocalDayRange
import dev.pampa.mycloudgallery.core.albums.contract.OthersAfterClose
import dev.pampa.mycloudgallery.core.albums.contract.PlaceAlternative
import dev.pampa.mycloudgallery.core.albums.contract.ProposalNote
import dev.pampa.mycloudgallery.core.albums.contract.ShareBlock
import dev.pampa.mycloudgallery.core.albums.contract.ShareExclusion
import dev.pampa.mycloudgallery.core.albums.contract.ShareOpId
import dev.pampa.mycloudgallery.core.albums.contract.ShareOpState
import dev.pampa.mycloudgallery.core.albums.contract.SharePlan
import dev.pampa.mycloudgallery.core.albums.contract.SharedAlbumNotice
import dev.pampa.mycloudgallery.core.albums.contract.SmartPreview
import dev.pampa.mycloudgallery.core.albums.contract.SmartRules
import dev.pampa.mycloudgallery.core.albums.contract.TitleProblem
import dev.pampa.mycloudgallery.core.albums.contract.TripProposal
import dev.pampa.mycloudgallery.core.library.contract.OrderedTimelineView
import dev.pampa.mycloudgallery.core.model.AccountId
import dev.pampa.mycloudgallery.core.model.AlbumId
import dev.pampa.mycloudgallery.core.model.AlbumSort
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.ShareLevel

// Gli STATI delle schermate degli album (docs/fase7-progetto.md §1.5, D32). Contratto di P0: la Fase 7 decide COSA si mostra e QUANDO,
// il filone grafico decide COME. P10 scrive ViewModel e composable con i soli componenti G2 esistenti; il filone grafico rifinisce senza
// toccare questi tipi. Solo dati gia' pronti da mostrare: niente NAS, niente percorsi, **mai un nascosto** (le viste vengono da
// core-albums, che non li ha). Utility, Eliminati di recente e Raccolte (`UtilityState`, `RecentlyDeletedState`,
// `CollectionsLayoutState`) sono di feature-library (P8, P11).

/** Raccolte → Album e "Tutti gli album" (§1.5 `AlbumListState`, D33). */
sealed interface AlbumListState {
  data object Loading : AlbumListState

  /**
   * [mineAndShared]: i miei (manuali) e i condivisi insieme, con il badge "Condiviso" e il creatore per quelli degli altri; [smart] a
   * parte (si comportano diversamente: "Suggerite"). [notices]: gli avvisi una volta sola (album chiuso, con o senza copia, Q5).
   */
  data class Ready(
    val mineAndShared: List<Album>,
    val smart: List<Album.Smart>,
    val sort: AlbumListSort,
    val notices: List<SharedAlbumNotice>,
  ) : AlbumListState

  data object Empty : AlbumListState
}

/** Una striscia in testa alla pagina dell'album (§2.5, D9, D17, D39, E-SH13, E-SH16). */
sealed interface AlbumBanner {
  /** "In arrivo": miei elementi ancora in viaggio verso l'area comune (solo sui miei dispositivi). */
  data class Incoming(val count: Int) : AlbumBanner

  /** "Da condividere" di uno smart condiviso (D17, Q4 (a)): si condividono con un tocco che apre la conferma. */
  data class ToShare(val count: Int) : AlbumBanner

  /** "Nuove: 12" di uno smart (E-AL7). */
  data class NewItems(val count: Int) : AlbumBanner

  /** D39: "N file nuovi nella cartella: aggiungili". */
  data class FilesFromPc(val count: Int) : AlbumBanner

  /** E-SH16: la cartella e' cambiata dal PC. */
  data object FolderChanged : AlbumBanner

  /**
   * E-SH16 (P13): la cartella e' cambiata dal PC e gli elementi stanno ora in [folder] (relativa all'area comune, dato dell'utente): il creatore la puo'
   * adottare con un tocco ("Usa la nuova cartella", `SharedAlbums.useNewFolder`).
   */
  data class UseNewFolder(val folder: String) : AlbumBanner {
    override fun toString(): String = "AlbumBanner.UseNewFolder"
  }

  /** E-SH20 (P13): [count] elementi di un mio dispositivo revocato da ripristinare ("Ripristina gli elementi", `SharedAlbums.restoreRevoked`). */
  data class RevokedItems(val count: Int) : AlbumBanner

  /** E-SH13: "Non aggiunte: l'album ora e' in sola visione", con "Riporta nelle mie cartelle". */
  data class NotAdded(val count: Int) : AlbumBanner

  /** Una condivisione in corso o ferma (§1.5 `ShareProgress`). */
  data class Sharing(val progress: ShareOpState) : AlbumBanner

  /** D11: "Sei il nuovo responsabile di questo album" (una volta). */
  data object Inherited : AlbumBanner

  /** Fase 8 (E-F37): l'album ha la regola delle persone e qui i volti non ci sono: "Le persone non sono attive su questo dispositivo"; l'album non cambia. */
  data object PeopleOff : AlbumBanner

  /** Fase 8 (E-F27): [count] persone della regola dimenticate o sconosciute: "Una persona della regola non e' piu' riconosciuta". */
  data class PeopleLost(val count: Int) : AlbumBanner
}

/**
 * Cosa posso fare su un album condiviso (§1.5 `PermissionInfo`, §2.4): il livello, chi l'ha creato, le operazioni permesse a me (la
 * tabella `AlbumPermissions`, la stessa di chi legge) e la dichiarazione "I permessi li fa rispettare l'app: dal PC chi accede all'area
 * comune puo' fare tutto" (sempre, L57: la stringa `share_footnote` di core-designsystem).
 */
data class PermissionInfo(val level: ShareLevel, val creator: AccountId, val mine: Boolean, val allowed: Set<AlbumOp>) {
  /** La dichiarazione si mostra sempre su un album condiviso (D10, L57). */
  val showDeclaration: Boolean get() = true
}

/** Perche' una pagina d'album non si puo' mostrare. */
enum class AlbumUnavailableReason { NOT_FOUND, LEFT, NO_FAMILY }

/** La pagina di un album (§1.5 `AlbumPageState`). */
sealed interface AlbumPageState {
  data class Ready(
    val content: AlbumContent,
    val sort: AlbumSort,
    /** Solo per un album condiviso. */
    val permissions: PermissionInfo?,
    val banners: List<AlbumBanner>,
  ) : AlbumPageState

  data class Recomputing(val album: Album, val done: Int, val total: Int) : AlbumPageState

  /** L'album e' stato chiuso mentre lo guardavo (E-SH7): l'avviso, con la mia copia se c'e' (Q5 b). */
  data class Closed(val notice: SharedAlbumNotice.Closed) : AlbumPageState

  data class Unavailable(val reason: AlbumUnavailableReason) : AlbumPageState
}

/** Come restringere regole troppo larghe (E-AL6). */
enum class BroadHint { NARROW_PERIOD, SMALLER_RADIUS, ADD_PLACE }

/** L'editor delle regole di un album smart (§1.5 `SmartEditorState`, D13-D16). */
sealed interface SmartEditorState {
  data class Interpreting(val title: String) : SmartEditorState

  data class Proposal(
    val rules: SmartRules,
    val alternatives: List<PlaceAlternative>,
    val series: List<LocalDayRange>,
    val preview: SmartPreview,
    val notes: Set<ProposalNote>,
  ) : SmartEditorState

  /** Oltre 3.000 elementi o oltre il 20% della Libreria: lo dice e propone di restringere. */
  data class TooBroad(val rules: SmartRules, val preview: SmartPreview, val hints: Set<BroadHint>) : SmartEditorState

  /** Solo descrizione senza motore di ricerca (E-AL9, D15): "La descrizione si usera' quando la ricerca per contenuto sara' pronta". */
  data class NoSearch(val rules: SmartRules, val preview: SmartPreview) : SmartEditorState
}

/** "Suggerite" e "Da condividere" della pagina dell'album (§1.5 `SuggestionsState`). */
sealed interface SuggestionsState {
  data object None : SuggestionsState

  data class Some(val count: Int, val view: OrderedTimelineView) : SuggestionsState

  data class ToShare(val count: Int, val view: OrderedTimelineView) : SuggestionsState
}

/**
 * Il foglio di condivisione (§1.5 `ShareSheetState`, D12): "N foto e M video (X GB) verranno spostati nell'area comune, nella cartella
 * «…», e li vedranno tutti i familiari", il livello (alla creazione), i nascosti (Q3 (a): «Lascia fuori» preselezionata) e la
 * dichiarazione dei permessi. Si mostra con `GalleryShareSheetContent`. Il tocco di conferma passa da `ShareConfirmationSheet`.
 */
sealed interface ShareSheetState {
  data class Plan(
    val plan: SharePlan,
    val hiddenChoice: HiddenChoice,
    val level: ShareLevel?,
    /** Perche' il foglio e' stato rifatto sotto gli occhi dell'utente (P10): il catalogo e' cambiato, o il foglio era scaduto. */
    val notice: ShareSheetNotice? = null,
  ) : ShareSheetState {
    /** E-SH9: con «Condividi anche i nascosti» l'avviso "diventeranno visibili ai familiari". */
    val warnHiddenBecomeVisible: Boolean get() = plan.hiddenIncluded > 0
  }

  /** Non si puo' condividere adesso; con [ShareBlock.TEST_SHARES_ONLY] il foglio dice "Arriva dopo le prove" (D30). */
  data class Blocked(val reason: ShareBlock) : ShareSheetState

  /** Nessun media si puo' condividere: [excluded] dice perche' (le aree di altri, nascosti lasciati fuori, gia' nell'album...). */
  data class NothingToShare(val excluded: Map<ShareExclusion, Int>) : ShareSheetState

  /** La tabella dei permessi non lo permette a me (il livello e' cambiato mentre il foglio era aperto, E-SH13). */
  data class Refused(val op: AlbumOp) : ShareSheetState

  /** Il titolo di un album nuovo non va bene. */
  data class InvalidTitle(val problem: TitleProblem) : ShareSheetState

  data object Confirming : ShareSheetState

  data class Started(val op: ShareOpId) : ShareSheetState
}

/** Perche' il piano che l'utente vede non e' quello di prima: il foglio lo dice una volta, sopra i conteggi. */
enum class ShareSheetNotice {
  /** Il catalogo e' cambiato dopo il piano (media spostati, nascosti, spariti): il foglio e' nuovo, rileggi i conteggi. */
  CHANGED,

  /** Il foglio era aperto da piu' di 10 minuti: la conferma non vale piu', ecco quello nuovo. */
  EXPIRED,
}

/**
 * Il foglio del creatore "Smetti di condividere" / "Elimina l'album" (§1.5 `CloseSheetState`, D31 adeguata a Q5). [forOthers] e' null
 * finche' chi chiude non sceglie fra "Sparisce per gli altri" e "Ognuno tiene una copia" (Q5: lo decide chi lo fa; nessuna scelta
 * preselezionata, la conferma resta spenta). "Riporta nelle mie cartelle ([bringBackCount] miei)" e' un'opzione a parte; con la copia
 * per gli altri il foglio avvisa che i media riportati spariscono anche dalle loro copie ([warnCopiesLoseBroughtBack]).
 */
data class CloseSheetState(
  val album: AlbumId,
  val title: String,
  val mode: CloseMode,
  val forOthers: OthersAfterClose?,
  val bringBackCount: Int,
  val bringBack: Boolean,
) {
  val canConfirm: Boolean get() = forOthers != null

  val warnCopiesLoseBroughtBack: Boolean get() = bringBack && bringBackCount > 0 && forOthers == OthersAfterClose.KEEP_COPY
}

/** Raccolte → Viaggi (§1.5 `TripsState`, D20). */
sealed interface TripsState {
  /** Dal piu' recente; [hasOlder]: "Mostra piu' vecchi" (oltre 2 anni). */
  data class Proposals(val proposals: List<TripProposal>, val hasOlder: Boolean) : TripsState

  /** Nessuna casa stimabile (E-AL16): lo dice una volta e propone gli eventi, che non vogliono il GPS. Solo dopo aver cercato (VG-1). */
  data class NoGps(val events: List<TripProposal>) : TripsState

  /**
   * I viaggi non sono ancora stati cercati (VG-1): prima della prima rilevazione "nessuna casa" non vuol dire niente e la pagina non dice che servono
   * foto con la posizione. La sezione delle Raccolte non ha righe, la pagina dice che sta cercando.
   */
  data object Searching : TripsState

  data object Empty : TripsState
}

/**
 * Le azioni che le schermate chiedono fuori da se' (le esegue :app, P13): aprire il viewer su una vista d'album, la pagina di un album,
 * il selettore dei media, la Diagnostica. I `feature-*` non si conoscono fra loro.
 */
interface AlbumActions {
  fun openViewer(view: OrderedTimelineView, start: MediaId)

  fun openAlbum(id: AlbumId)

  /**
   * Il selettore dei media per "Aggiungi foto" (a un album che c'e': [forAlbum]) o per le foto d'esempio di un album smart ([forAlbum] **null**, sia
   * per uno ancora da creare sia per uno da modificare: AS-8). Al piu' [max] media; mai i nascosti (D22). Chiama [onPicked] con i media scelti, solo se l'utente ne
   * ha scelto (P10: prima la firma era `pickMedia(forAlbum)` senza risposta, e le schermate non avrebbero saputo cosa e' stato scelto).
   */
  fun pickMedia(forAlbum: AlbumId?, max: Int, onPicked: (List<MediaId>) -> Unit)

  fun openDiagnostics()
}
