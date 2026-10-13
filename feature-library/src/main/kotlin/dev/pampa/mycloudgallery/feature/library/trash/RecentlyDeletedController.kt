package dev.pampa.mycloudgallery.feature.library.trash

import android.content.IntentSender
import androidx.compose.runtime.Immutable
import dev.pampa.mycloudgallery.core.mediastore.contract.PhoneMediaIntents
import dev.pampa.mycloudgallery.core.mediastore.contract.RecentlyDeletedSource
import dev.pampa.mycloudgallery.core.mediastore.contract.TrashedItem
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaRef
import dev.pampa.mycloudgallery.core.security.lock.AuthPromptText
import dev.pampa.mycloudgallery.core.security.lock.SystemCredentialAuthenticator
import dev.pampa.mycloudgallery.feature.library.hidden.HiddenAccess
import dev.pampa.mycloudgallery.feature.library.hidden.HiddenAccessGate
import dev.pampa.mycloudgallery.feature.library.hidden.HiddenDenial
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Quali tra dei riferimenti del telefono sono **nascosti** nell'app.
 *
 * Il cestino di sistema si legge dal MediaStore, non dall'indice: i nascosti eliminati ci sono, e vanno **riconosciuti**
 * per mostrarli col loro segno ("Nascosto") **solo dopo lo sblocco** (E-H1). Chi risponde (`:app`, dal catalogo) dice
 * solo **se** un riferimento e' nascosto, non lo apre. Se non riesce a rispondere lancia: la pagina dice che non
 * riesce a leggere il cestino, mai un elenco senza i segni.
 */
fun interface TrashHiddenMask {
  suspend fun hiddenAmong(refs: List<MediaRef.Phone>): Set<MediaRef.Phone>
}

/**
 * Un elemento del cestino di sistema pronto per la griglia: un'identita' per la miniatura, i giorni che restano e se e'
 * un nascosto.
 */
@Immutable
data class TrashEntry(
  /** Un'identita' **solo per la cache delle miniature**: deriva dal riferimento, non e' quella del catalogo. */
  val id: MediaId,
  val item: TrashedItem,
  /** Quanti giorni interi restano prima che il sistema lo cancelli; 0 = meno di un giorno ("Scade oggi"). */
  val daysLeft: Int,
  /** Nascosto nell'app: si vede solo qui, dopo lo sblocco, col segno "Nascosto". Ripristinato resta nascosto. */
  val hidden: Boolean = false,
)

/** Cosa mostra la pagina degli Eliminati di recente. Solo [Loaded] ha degli elementi, e solo da sbloccata. */
sealed interface TrashUiState {

  /** Android 10: il cestino di sistema non c'e'. */
  data object Unsupported : TrashUiState

  /** Chiusa: dietro la credenziale di sistema, come i Nascosti. Non e' stato letto niente. */
  data object Locked : TrashUiState

  /** La schermata di sistema e' aperta. */
  data object Authenticating : TrashUiState

  /** Lo sblocco non e' riuscito. */
  data class Denied(val reason: HiddenDenial) : TrashUiState

  data object Loading : TrashUiState

  /** Il cestino o il riconoscimento dei nascosti non hanno risposto: non si mostra niente. */
  data object Failed : TrashUiState

  /** [entries] puo' essere vuoto: la pagina dice che non c'e' niente di eliminato di recente. */
  data class Loaded(val entries: List<TrashEntry>) : TrashUiState
}

/**
 * La logica degli Eliminati di recente (D14, D17), senza Android a parte `IntentSender`.
 *
 * **Sta dietro lo stesso cancello dei Nascosti** ([HiddenAccessGate]: la credenziale di sistema a ogni ingresso, un minuto
 * di tolleranza condiviso fra le due schermate), perche' nel cestino ci sono anche i nascosti eliminati:
 *
 *  - **Mai prima dello sblocco.** Chiusa, non legge il cestino ne' chiede quali sono nascosti: niente da trovare.
 *  - **Mai dopo l'uscita.** [hidden] dimentica elenco e selezione.
 *  - Sbloccata mostra tutto, i nascosti col segno. **"Ripristina" non cambia il nascosto**: e' un dato personale legato
 *    all'identita' del media (`media_personal`), il sistema restituisce il file e la Libreria non lo mostra.
 *
 * Legge il cestino di sistema, calcola i giorni rimasti, tiene la selezione e prepara la richiesta di **ripristino** (il
 * dialogo di sistema `createTrashRequest(..., false)`). **L'app non cancella mai niente da sola**: non c'e' "Elimina per
 * sempre", il sistema svuota il cestino dopo 30 giorni.
 *
 * Non e' il cestino della Galleria di Samsung (E-P11): quello toglie la riga dal MediaStore e qui non si vede.
 */
class RecentlyDeletedController(
  private val gate: HiddenAccessGate,
  private val source: RecentlyDeletedSource,
  private val mask: TrashHiddenMask,
  private val intents: PhoneMediaIntents,
  private val scope: CoroutineScope,
  private val nowMs: () -> Long = System::currentTimeMillis,
) {

  private val canTrash: Boolean = intents.canTrash

  // Loading / Failed / Loaded: cio' che si e' letto. Conta solo con il cancello aperto.
  private val content = MutableStateFlow<TrashUiState>(TrashUiState.Loading)

  /** Cosa mostrare. Senza il cancello aperto non c'e' mai un elenco; senza il cestino di sistema c'e' [TrashUiState.Unsupported]. */
  val state: StateFlow<TrashUiState> = combine(gate.state, content) { access, read ->
    when {
      !canTrash -> TrashUiState.Unsupported
      else -> when (access) {
        HiddenAccess.Locked -> TrashUiState.Locked
        HiddenAccess.Authenticating -> TrashUiState.Authenticating
        is HiddenAccess.Denied -> TrashUiState.Denied(access.reason)
        HiddenAccess.Unlocked -> read
      }
    }
  }.stateIn(scope, SharingStarted.Eagerly, if (canTrash) TrashUiState.Locked else TrashUiState.Unsupported)

  private val _selection = MutableStateFlow<Set<MediaId>>(emptySet())

  /** Gli elementi scelti per il ripristino. */
  val selection: StateFlow<Set<MediaId>> = _selection.asStateFlow()

  private var loading: Job? = null

  /** Cresce a ogni lettura e a ogni uscita: l'esito di una lettura di un'epoca vecchia si butta. */
  private var epoch = 0

  /** Se questa pagina e' visibile: il cancello conta le schermate protette, quindi ingresso e uscita vanno a coppie. */
  private var visible = false

  /** La pagina e' visibile (ingresso, o ritorno in primo piano): se la tolleranza vale ancora, legge. */
  fun shown() {
    if (!visible) {
      visible = true
      gate.onShown()
    }
    refresh()
  }

  /** La pagina non e' piu' visibile: si dimentica tutto e, se non resta visibile altro, comincia la tolleranza. */
  fun hidden() {
    epoch++
    loading?.cancel()
    loading = null
    content.value = TrashUiState.Loading
    _selection.value = emptySet()
    if (visible) {
      visible = false
      gate.onHidden()
    }
  }

  /** Chiede la credenziale; se va a buon fine legge. */
  fun unlock(authenticator: SystemCredentialAuthenticator, prompt: AuthPromptText) {
    scope.launch {
      if (gate.unlock(authenticator, prompt) == HiddenAccess.Unlocked) refresh()
    }
  }

  /** Non c'e' modo di mostrare la schermata di sistema (nessuna activity adatta): resta chiusa. */
  fun unlockUnavailable() {
    gate.deny(HiddenDenial.UNAVAILABLE)
  }

  /**
   * Rilegge il cestino, **solo con il cancello aperto**. Si chiama all'ingresso, al ritorno in primo piano e dopo un
   * ripristino: il contenuto cambia mentre si e' altrove.
   */
  fun refresh() {
    if (!canTrash || !gate.isUnlocked) return
    loading?.cancel()
    val mine = ++epoch
    loading = scope.launch {
      // La prima lettura mostra l'attesa; le successive lasciano la lista com'e' finche' non arriva quella nuova.
      if (content.value !is TrashUiState.Loaded) content.value = TrashUiState.Loading
      val entries = try {
        val items = source.list()
        val hiddenRefs = if (items.isEmpty()) emptySet() else mask.hiddenAmong(items.map { it.ref })
        val now = nowMs()
        items.asSequence()
          .sortedWith(compareByDescending<TrashedItem> { it.trashedAtMs }.thenBy { it.ref.volume }.thenBy { it.ref.mediaStoreId })
          .map { TrashEntry(id = trashEntryId(it.ref), item = it, daysLeft = daysLeft(it.expiresAtMs, now), hidden = it.ref in hiddenRefs) }
          .toList()
      } catch (e: CancellationException) {
        throw e
      } catch (_: Exception) {
        if (mine == epoch && gate.isUnlocked) content.value = TrashUiState.Failed
        return@launch
      }
      // Mentre si leggeva ci si puo' essere richiusi o essere usciti: l'esito di una lettura vecchia non si mostra.
      if (mine != epoch || !gate.isUnlocked) return@launch
      content.value = TrashUiState.Loaded(entries)
      // Quello che era scelto e non c'e' piu' (ripristinato, scaduto) esce dalla selezione.
      val present = entries.mapTo(HashSet()) { it.id }
      _selection.update { current -> current.filterTo(LinkedHashSet()) { it in present } }
    }
  }

  /** Tocca una cella: la sceglie o la toglie. */
  fun toggle(id: MediaId) {
    if (!gate.isUnlocked) return
    _selection.update { if (id in it) it - id else it + id }
  }

  /** Sceglie tutto cio' che si vede. */
  fun selectAll() {
    if (!gate.isUnlocked) return
    val loaded = content.value as? TrashUiState.Loaded ?: return
    _selection.value = loaded.entries.mapTo(LinkedHashSet()) { it.id }
  }

  fun clearSelection() {
    _selection.value = emptySet()
  }

  /**
   * La richiesta di ripristino di [ids] (default: la selezione): il dialogo di sistema che chiede conferma. Null se
   * non c'e' niente da ripristinare, il cestino non esiste o la pagina e' chiusa. La lancia la UI con
   * `startIntentSenderForResult`. I nascosti si ripristinano **nascosti**: qui non si tocca nessun dato personale.
   */
  fun restoreRequest(ids: Set<MediaId> = _selection.value): IntentSender? {
    if (!gate.isUnlocked) return null
    return intents.trashRequest(refsOf(ids), trash = false)
  }

  /** I riferimenti che il ripristino di [ids] riguarda (per i test e per il riepilogo). */
  fun refsOf(ids: Set<MediaId>): List<MediaRef.Phone> {
    val loaded = content.value as? TrashUiState.Loaded ?: return emptyList()
    return loaded.entries.filter { it.id in ids }.map { it.item.ref }
  }

  fun close() {
    hidden()
  }

  companion object {
    private const val DAY_MS = 86_400_000L

    /**
     * I giorni **interi** che restano: 29,5 giorni sono "29", meno di un giorno e' "0" ("Scade oggi"), gia' scaduto e'
     * "0". Per difetto, cosi' non si promette mai piu' tempo di quello che c'e'.
     */
    fun daysLeft(expiresAtMs: Long, nowMs: Long): Int {
      val remaining = expiresAtMs - nowMs
      if (remaining <= 0) return 0
      return (remaining / DAY_MS).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    /** L'identita' di una miniatura del cestino: stabile per riferimento, mai uguale a quella di un media del catalogo. */
    fun trashEntryId(ref: MediaRef.Phone): MediaId =
      MediaId(UUID.nameUUIDFromBytes("trash|${ref.volume}|${ref.mediaStoreId}".toByteArray(Charsets.UTF_8)).toString())
  }
}
