package dev.pampa.mycloudgallery.feature.library.library

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.pampa.mycloudgallery.core.imaging.contract.ViewerImageSource
import dev.pampa.mycloudgallery.core.imaging.noop.NoPlaceholderCache
import dev.pampa.mycloudgallery.core.imaging.noop.NoThumbnailLoader
import dev.pampa.mycloudgallery.core.imaging.noop.NoViewerImageSource
import dev.pampa.mycloudgallery.core.library.contract.LibraryBackupStatus
import dev.pampa.mycloudgallery.core.library.contract.LibraryFilter
import dev.pampa.mycloudgallery.core.library.contract.LibraryNasStatus
import dev.pampa.mycloudgallery.core.library.contract.LibraryPreferences
import dev.pampa.mycloudgallery.core.library.contract.LocalMediaActions
import dev.pampa.mycloudgallery.core.library.contract.TimelineRepository
import dev.pampa.mycloudgallery.core.library.contract.ViewerItems
import dev.pampa.mycloudgallery.core.library.noop.EmptyTimelineView
import dev.pampa.mycloudgallery.core.library.noop.EmptyViewerItems
import dev.pampa.mycloudgallery.core.library.noop.NoGridLayoutEngine
import dev.pampa.mycloudgallery.core.library.noop.NoLibraryPreferences
import dev.pampa.mycloudgallery.core.library.noop.NoLocalMediaActions
import dev.pampa.mycloudgallery.core.mediastore.contract.MediaAccess
import dev.pampa.mycloudgallery.core.mediastore.contract.SyncStatus
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.places.noop.NoPlaceNames
import dev.pampa.mycloudgallery.feature.library.ActionNotice
import dev.pampa.mycloudgallery.feature.library.grid.MediaGridState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow

/**
 * Lo stato del telefono che la Libreria racconta (§2.4, "Stati"): l'accesso ai media, la lettura in corso e le foto in
 * attesa del permesso della posizione. Lo fornisce `:app` dai contratti di `core-mediastore` (`PhoneAccessReader`,
 * `PhoneLibrarySync`, il bilancio dell'ultimo giro).
 *
 * @property pendingLocation le foto nuove in attesa di `ACCESS_MEDIA_LOCATION` (E-P21): mai un'impronta oscurata.
 * @property selectionPending accesso parziale (E-P1) e la prima lettura non ha ancora detto quali foto sono accessibili:
 *   la Libreria non mostra niente (nemmeno per un istante cio' che il permesso non da' piu') e dice che sta leggendo.
 */
@Immutable
data class LibraryPhoneStatus(
  val access: MediaAccess = MediaAccess.FULL,
  val sync: SyncStatus = SyncStatus.Idle(0L),
  val pendingLocation: Int = 0,
  val selectionPending: Boolean = false,
) {
  /** La prima lettura (o una scansione completa): la Libreria mostra l'avanzamento in fondo. */
  val firstRead: SyncStatus.Scanning? get() = (sync as? SyncStatus.Scanning)?.takeIf { it.full }
}

/**
 * Le richieste che la Libreria fa a chi la ospita: permessi, condivisione, eliminazione dal telefono. Tutte partono da un
 * tocco dell'utente; i permessi e i dialoghi di sistema (`createTrashRequest`) vivono in `:app`, che ha l'activity.
 */
interface LibraryCommands {

  /** "Consenti accesso" / "Consenti tutto" (E-P1): la richiesta di sistema dei permessi di lettura dei media. */
  fun requestMediaAccess() {}

  /** "Consenti" per le foto in attesa (E-P21): `ACCESS_MEDIA_LOCATION`. */
  fun requestMediaLocation() {}

  /**
   * Quali fra [ids] hanno una copia sul telefono, cioe' su quali [share] e [deleteFromPhone] possono fare davvero qualcosa (GA-8). I
   * media che sono solo sul NAS non si condividono ne' si eliminano da qui: la Libreria lo dice invece di far credere che sia andata.
   * Senza un legame con il telefono (anteprime, test) vale per tutti.
   */
  fun onPhone(ids: Set<MediaId>): Set<MediaId> = ids

  /**
   * Condividi: l'intent di sistema con gli URI del MediaStore (`PhoneMediaIntents.share`). Vero se il selettore e' partito;
   * falso se Android lo ha rifiutato (troppi URI per la transazione Binder, nessuna app): la Libreria lo dice (I8).
   */
  fun share(ids: Set<MediaId>): Boolean = true

  /** Il massimo di elementi che [share] accetta in una volta. Oltre, la Libreria avvisa e non chiama [share] (I8). */
  val shareLimit: Int get() = Int.MAX_VALUE

  /**
   * Correzioni 5B (R4, MC-2): con "Rimuovi la posizione" acceso [share] prepara una copia senza posizione di ogni media, e puo' durare: qui
   * l'avanzamento e il rifiuto con il motivo. Senza un legame (anteprime, test) resta [LibraryShare.Idle].
   */
  val shareProgress: StateFlow<LibraryShare> get() = NoLibraryShare

  /** "Annulla" mentre si prepara, o la chiusura del foglio di una condivisione ferma: niente parte. */
  fun cancelShare() {}

  /** "Condividi con la posizione" nel foglio di una condivisione ferma: la scelta esplicita dell'utente, mai un ripiego. */
  fun shareWithLocation() {}

  /** Se eliminare dal telefono si puo' (D14: su Android 10 il cestino di sistema non c'e', e l'azione nemmeno). */
  val canDeleteFromPhone: Boolean get() = false

  /**
   * Elimina dal telefono, nel cestino di sistema (D14): il dialogo di sistema conferma, poi 30 giorni per ripristinare.
   * Vero se la richiesta e' partita; falso altrimenti (I8).
   */
  fun deleteFromPhone(ids: Set<MediaId>): Boolean = true

  /** Il massimo di elementi che una richiesta di eliminazione accetta (limite di Android). Oltre, la Libreria avvisa. */
  val deleteLimit: Int get() = Int.MAX_VALUE

  /**
   * FAB-7: l'azione chiesta per la selezione e' **andata avanti**: il dialogo del cestino di sistema ha avuto la risposta positiva, oppure il foglio
   * "Prima mettilo al sicuro" ha portato l'utente a un'altra pagina o al cestino. La Libreria chiude la selezione **qui** e non quando apre il dialogo o il
   * foglio: se l'utente annulla, o preme "Chiudi", la selezione resta com'era e si puo' riprovare (come la pagina dei Doppioni con `onPhoneResult`).
   * Senza un legame con il telefono (anteprime, test) non emette mai.
   */
  val selectionSettled: Flow<Unit> get() = emptyFlow()

  /**
   * 5B I3 (decisione dell'utente, fase grafica G2): con il backup attivo "Elimina dal telefono" della selezione passa dal foglio della 5B
   * ("Prima mettilo al sicuro"), lo stesso del viewer, invece del foglio della 4B che porta dritto al cestino di Android. Falso (backup
   * spento, non legato, o senza i dialoghi di sistema): resta il foglio di sempre.
   */
  val deletesThroughSafeSheet: Boolean get() = false

  /**
   * Apre il foglio della 5B per [ids] (media con una copia sul telefono): dice quanti non sono ancora al sicuro e offre la stessa scelta del
   * viewer ("Prima mettilo al sicuro" o "Togli solo quelli gia' al sicuro"; per quelli che non sono sul NAS il cestino di Android, dopo
   * l'avviso). Il foglio lo mostra `:app` alla radice; non elimina niente da solo.
   */
  fun deleteFromPhoneSafely(ids: Set<MediaId>) {}

  /** Fase 5B (P14, D21): "Libera spazio" sulla selezione c'e' (il backup e' legato). Senza, l'azione non compare. */
  val canFreeSpace: Boolean get() = false

  /**
   * Fase 5B (P14): apre "Libera spazio" per [ids] (la pagina di feature-backup verifica e chiede la conferma; qui niente si toglie).
   * Con [ids] vuoto (il tasto del pop-up della nuvola, B2 della beta) apre "Libera spazio" su tutto cio' che e' al sicuro, come la voce
   * delle Raccolte.
   */
  fun freeSpace(ids: Set<MediaId>) {}

  /**
   * B2 della beta: lo spazio del NAS per il pop-up della nuvola, la stessa misura della pagina "Spazio NAS". Si chiama solo quando
   * l'utente apre il pop-up (mai in background); deve poter girare sul thread principale. Null = nessun NAS (la sezione non c'e').
   */
  suspend fun nasSpace(): LibraryNasSpace? = null

  /** Fase 7 (P10, P13, D35): "Aggiungi ad album" sulla selezione c'e' (gli album sono legati). Senza, l'azione non compare. */
  val canAddToAlbum: Boolean get() = false

  /** Fase 7 (P13): apre la scelta dell'album per [ids] sopra la Libreria (`:app` mostra `AddToAlbumHost`); qui niente si scrive. */
  fun addToAlbum(ids: Set<MediaId>) {}

  /** Correzioni F (TS-4, D30): "Trova simili" sulla selezione c'e' (la ricerca per contenuto e' legata). Senza, l'azione non compare. */
  val canFindSimilar: Boolean get() = false

  /**
   * Correzioni F (TS-4): quanti media al massimo danno la query di "Trova simili" dalla selezione (il centroide di troppe foto diverse non somiglia a
   * nessuna). Oltre, la Libreria dice di sceglierne meno e non chiama [findSimilar].
   */
  val findSimilarLimit: Int get() = Int.MAX_VALUE

  /**
   * Correzioni F (TS-4): cerca i media vicini a quelli scelti e apre i risultati in Cerca, con il chip "Simili a". Niente si scrive e niente si sposta. I media
   * non ancora analizzati sono lasciati fuori e Cerca lo dice; se nessuno e' stato analizzato la ricerca non parte e la risposta e' [SimilarSelectionOutcome.NOT_ANALYZED].
   */
  fun findSimilar(ids: Set<MediaId>): SimilarSelectionOutcome = SimilarSelectionOutcome.STARTED

  /** Fase H (D10): «aggiorna» tirando oltre il fondo della Libreria si puo' chiedere. Falso (anteprime, test, variante di misura): il gesto non c'e'. */
  val canRefresh: Boolean get() = false

  /**
   * Fase H (D10, richiesta dell'utente del 31/10): rilegge il telefono e il NAS e chiede un giro di backup, **con i comandi e le regole di sempre**
   * (`:app`, `LibraryRefresh`). Ritorna quando la parte veloce e' finita: la griglia tiene aperto lo spazio fino ad allora (al piu' 2,5 s); il
   * resto lo racconta la nuvola in alto. Se un aggiornamento e' gia' in corso aspetta quello, non ne fa partire un altro.
   */
  suspend fun refresh() {}
}

/** Com'e' andato "Trova simili" dalla selezione (correzioni F, TS-4): la Libreria sceglie le parole. */
enum class SimilarSelectionOutcome {
  /** La ricerca e' partita: Cerca mostra i simili. */
  STARTED,

  /** Nessun media della scelta e' ancora stato analizzato: non c'e' un vettore da cui partire. */
  NOT_ANALYZED,
}

/** Nessuna richiesta: la Libreria vuota della release prima del grafo vero. */
object NoLibraryCommands : LibraryCommands

/**
 * Solo debug: le manopole della Libreria sintetica (M1, `docs/bench/M1.md`). In release e' sempre null e non compare
 * niente. [select] ricostruisce la Libreria con un altro numero di elementi.
 */
@Stable
class LibraryDebugHooks(
  val sizes: List<Int>,
  current: Int,
  private val onSelect: (Int) -> Unit,
) {
  var current: Int by mutableIntStateOf(current)
    private set

  fun select(size: Int) {
    if (size == current) return
    current = size
    onSelect(size)
  }
}

/**
 * Cio' che [LibraryTab] mostra e cio' a cui chiede: la griglia, il viewer, il repository della timeline, le preferenze, le
 * azioni locali e lo stato del telefono.
 *
 * Tutti i parametri dopo i conteggi hanno un default neutro: `:app` li riempie dal grafo vero (P11); la release prima del
 * grafo usa [emptyLibraryTabState], il debug e i test la Libreria sintetica (`testing`).
 *
 * @property timeline la timeline da cui la Libreria prende la vista per il filtro scelto (D16); null = la vista della
 *   griglia resta quella che e' (niente filtro).
 * @property preferences filtro, livello e stile **ricordati** (DataStore in `noBackupFilesDir`).
 * @property phone lo stato del telefono (permessi, lettura, in attesa).
 * @property nas lo stato del NAS (Fase 4, §1.5, `LibraryNasStatus`). Dalla beta (B2) lo dice la nuvola nella capsula in alto, non
 *   piu' una voce in fondo alla griglia (`LibraryCloud.kt`). Di default [LibraryNasStatus.Idle] (nessun NAS: non dice niente).
 * @property backup lo stato del backup (Fase 5A, §1.5, D16, `LibraryBackupStatus`), anche lui nella nuvola. Di default
 *   [LibraryBackupStatus.Idle] (backup non configurato: non dice niente). Lo riduce `:app` da `BackupStatus` di core-backup, che la
 *   Libreria non vede.
 */
@Stable
class LibraryTabState(
  val grid: MediaGridState,
  val viewerItems: ViewerItems,
  val viewerImages: ViewerImageSource,
  photoCount: Int,
  videoCount: Int,
  val timeline: TimelineRepository? = null,
  val preferences: LibraryPreferences = NoLibraryPreferences,
  val actions: LocalMediaActions = NoLocalMediaActions,
  val phone: StateFlow<LibraryPhoneStatus> = MutableStateFlow(LibraryPhoneStatus()),
  val commands: LibraryCommands = NoLibraryCommands,
  val debug: LibraryDebugHooks? = null,
  val nas: StateFlow<LibraryNasStatus> = MutableStateFlow(LibraryNasStatus.Idle),
  val backup: StateFlow<LibraryBackupStatus> = MutableStateFlow(LibraryBackupStatus.Idle),
  /** Pacchetto X: lo stato dell'analisi delle foto, una riga nel pannello della nuvola. Di default niente da dire. */
  val analysis: StateFlow<dev.pampa.mycloudgallery.core.library.contract.LibraryAnalysisStatus> =
    MutableStateFlow(dev.pampa.mycloudgallery.core.library.contract.LibraryAnalysisStatus.Idle),
) {
  /** Quante foto e quanti video ci sono nella vista (il riepilogo in fondo). Si ricontano a ogni vista nuova. */
  var photoCount: Int by mutableIntStateOf(photoCount)
    internal set

  var videoCount: Int by mutableIntStateOf(videoCount)
    internal set

  /** Il filtro rapido mostrato adesso (D16). */
  var filter: LibraryFilter by mutableStateOf(preferences.filter.value)
    internal set

  /** L'azione che non e' riuscita o non e' partita, finche' non si chiude da solo o con "OK" (R8, I8). Null = nessuna. */
  internal var notice: ActionNotice? by mutableStateOf(null)

  /**
   * Vero in modalita' selezione: la barra delle azioni prende il posto della pillola. `:app` lo legge per nascondere la
   * pillola (§2.4: "al posto della pillola").
   */
  val selectionActive: Boolean get() = grid.selecting
}

/** Una Libreria vuota, tutta sui null-object: nessun media, nessuna miniatura, nessun luogo. */
fun emptyLibraryTabState(): LibraryTabState = LibraryTabState(
  grid = MediaGridState(
    view = EmptyTimelineView(LibraryFilter.ALL),
    loader = NoThumbnailLoader,
    placeholders = NoPlaceholderCache,
    placeNames = NoPlaceNames,
    engine = NoGridLayoutEngine,
    refOf = { throw NoSuchElementException("Nessun elemento nella Libreria vuota.") },
  ),
  viewerItems = EmptyViewerItems,
  viewerImages = NoViewerImageSource,
  photoCount = 0,
  videoCount = 0,
)

@Composable
fun rememberEmptyLibraryTabState(): LibraryTabState = remember { emptyLibraryTabState() }
