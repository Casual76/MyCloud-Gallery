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

  /**
   * [entries] sono le foto del telefono; [nas] il cestino del NAS (Fase 7, D26). Tutti e due possono essere vuoti: la pagina dice che non c'e' niente
   * di eliminato di recente.
   */
  data class Loaded(val entries: List<TrashEntry>, val nas: NasTrashSection = NasTrashSection.None) : TrashUiState {
    /** Quanti elementi in tutto, nei due cestini. */
    val total: Int get() = entries.size + ((nas as? NasTrashSection.Rows)?.entries?.size ?: 0)

    /** Non c'e' niente da mostrare in nessuno dei due. */
    val isEmpty: Boolean get() = total == 0
  }
}

/** Una voce del cestino del NAS pronta per la pagina: un'identita' per la selezione e la riga. */
@Immutable
data class NasTrashEntry(
  /** Un'identita' **solo per la selezione**: deriva dalla chiave della voce, mai uguale a quella di un media del catalogo. */
  val id: MediaId,
  val row: NasTrashRow,
)

/** La parte della pagina che viene dal NAS (D26). */
sealed interface NasTrashSection {

  /** Nessun NAS collegato: la sezione non c'e'. */
  data object None : NasTrashSection

  data object Loading : NasTrashSection

  /** [offline]: il NAS non risponde, ci sono solo le voci proprie dell'ultima lettura. [writable] falso: ripristino ed "Elimina subito" spenti. */
  data class Rows(val entries: List<NasTrashEntry>, val writable: Boolean, val offline: Boolean = false) : NasTrashSection

  data object Denied : NasTrashSection

  data object Failed : NasTrashSection
}

/**
 * La logica degli Eliminati di recente (D14, D17, Fase 7 D26), senza Android a parte `IntentSender`: **i due cestini insieme**, quello di Android per
 * le foto del telefono e quello del NAS ([NasTrashPort], 30 giorni), con i giorni rimasti di ogni voce.
 *
 * **Sta dietro lo stesso cancello dei Nascosti** ([HiddenAccessGate]: la credenziale di sistema a ogni ingresso, un minuto
 * di tolleranza condiviso fra le due schermate), perche' nel cestino ci sono anche i nascosti eliminati (e il NAS ne ha i nomi):
 *
 *  - **Mai prima dello sblocco.** Chiusa, non legge il cestino ne' chiede quali sono nascosti, e non tocca il cestino del NAS: niente da trovare.
 *  - **Mai dopo l'uscita.** [hidden] dimentica elenco e selezione.
 *  - Sbloccata mostra tutto, i nascosti del telefono col segno. **"Ripristina" non cambia il nascosto**: e' un dato personale legato
 *    all'identita' del media (`media_personal`), il sistema restituisce il file e la Libreria non lo mostra.
 *
 * Legge il cestino di sistema, calcola i giorni rimasti, tiene la selezione e prepara le azioni:
 *  - **Ripristina** il telefono: il dialogo di sistema `createTrashRequest(..., false)` ([restoreRequest]);
 *  - **Ripristina** il NAS: la porta, dopo il tocco ([restoreNas]);
 *  - **Elimina subito** (solo le voci **proprie** del NAS, Q8): la porta, con la doppia conferma ([purgeNas]).
 * **L'app non cancella mai niente da sola** e non c'e' un "Elimina per sempre" per il telefono: il sistema svuota il cestino dopo 30 giorni.
 *
 * Non e' il cestino della Galleria di Samsung (E-P11, D-3): quello toglie la riga dal MediaStore e qui non si vede; la pagina lo dice.
 */
class RecentlyDeletedController(
  private val gate: HiddenAccessGate,
  private val source: RecentlyDeletedSource,
  private val mask: TrashHiddenMask,
  private val intents: PhoneMediaIntents,
  private val scope: CoroutineScope,
  private val nasTrash: NasTrashPort = NoNasTrash,
  private val nowMs: () -> Long = System::currentTimeMillis,
) {

  private val canTrash: Boolean = intents.canTrash

  /** C'e' un cestino del NAS da mostrare (un adattatore e' legato). Senza NAS e senza cestino di sistema la pagina e' "non disponibile". */
  private val hasNas: Boolean = nasTrash !== NoNasTrash

  // Loading / Failed / Loaded: cio' che si e' letto del telefono. Conta solo con il cancello aperto.
  private val content = MutableStateFlow<TrashUiState>(TrashUiState.Loading)

  // Il cestino del NAS: cio' che la porta dice, solo con il cancello aperto.
  private val nasContent = MutableStateFlow<NasTrashSection>(if (hasNas) NasTrashSection.Loading else NasTrashSection.None)

  /** Cosa mostrare. Senza il cancello aperto non c'e' mai un elenco; senza nessun cestino c'e' [TrashUiState.Unsupported]. */
  val state: StateFlow<TrashUiState> = combine(gate.state, content, nasContent) { access, read, nas ->
    when {
      !canTrash && !hasNas -> TrashUiState.Unsupported
      else -> when (access) {
        HiddenAccess.Locked -> TrashUiState.Locked
        HiddenAccess.Authenticating -> TrashUiState.Authenticating
        is HiddenAccess.Denied -> TrashUiState.Denied(access.reason)
        HiddenAccess.Unlocked -> if (read is TrashUiState.Loaded) read.copy(nas = nas) else read
      }
    }
  }.stateIn(scope, SharingStarted.Eagerly, if (canTrash || hasNas) TrashUiState.Locked else TrashUiState.Unsupported)

  private val _selection = MutableStateFlow<Set<MediaId>>(emptySet())

  /** Gli elementi scelti (dei due cestini). */
  val selection: StateFlow<Set<MediaId>> = _selection.asStateFlow()

  private var loading: Job? = null
  private var nasJob: Job? = null

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
    nasJob?.cancel()
    nasJob = null
    content.value = TrashUiState.Loading
    nasContent.value = if (hasNas) NasTrashSection.Loading else NasTrashSection.None
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
   * Rilegge i cestini, **solo con il cancello aperto**. Si chiama all'ingresso, al ritorno in primo piano e dopo un
   * ripristino: il contenuto cambia mentre si e' altrove.
   */
  fun refresh() {
    if ((!canTrash && !hasNas) || !gate.isUnlocked) return
    loading?.cancel()
    val mine = ++epoch
    loading = scope.launch {
      // La prima lettura mostra l'attesa; le successive lasciano la lista com'e' finche' non arriva quella nuova.
      if (content.value !is TrashUiState.Loaded) content.value = TrashUiState.Loading
      val entries = try {
        val items = if (canTrash) source.list() else emptyList()
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
      pruneSelection()
    }
    if (hasNas) startNas(mine)
  }

  /**
   * Segue il cestino del NAS finche' la pagina e' aperta e sbloccata: ogni aggiornamento della porta e, in parallelo, la sua lettura. Se la lettura
   * non riesce la sezione dice "Failed" (e si smette di seguire un aggiornamento che potrebbe essere vecchio) finche' l'utente riprova.
   */
  private fun startNas(mine: Int) {
    nasJob?.cancel()
    nasJob = scope.launch {
      val follower = launch {
        nasTrash.feed.collect { feed ->
          // Un aggiornamento arrivato dopo l'uscita o con il cancello chiuso non si mostra.
          if (mine == epoch && gate.isUnlocked) {
            nasContent.value = sectionOf(feed)
            pruneSelection()
          }
        }
      }
      try {
        nasTrash.refresh()
      } catch (e: CancellationException) {
        throw e
      } catch (_: Exception) {
        follower.cancel()
        if (mine == epoch && gate.isUnlocked) nasContent.value = NasTrashSection.Failed
      }
    }
  }

  private fun sectionOf(feed: NasTrashFeed): NasTrashSection = when (feed) {
    NasTrashFeed.Unavailable -> NasTrashSection.None
    NasTrashFeed.Loading -> NasTrashSection.Loading
    NasTrashFeed.Denied -> NasTrashSection.Denied
    NasTrashFeed.Failed -> NasTrashSection.Failed
    is NasTrashFeed.Ready -> NasTrashSection.Rows(entriesOf(feed.rows), feed.writable)
    is NasTrashFeed.Offline -> NasTrashSection.Rows(entriesOf(feed.cachedOwn), writable = false, offline = true)
  }

  private fun entriesOf(rows: List<NasTrashRow>): List<NasTrashEntry> =
    rows.sortedWith(compareBy<NasTrashRow>({ it.daysLeft ?: Int.MAX_VALUE }, { it.key })).map { NasTrashEntry(nasEntryId(it.key), it) }

  /** Quello che era scelto e non c'e' piu' (ripristinato, scaduto) esce dalla selezione. */
  private fun pruneSelection() {
    val loaded = content.value as? TrashUiState.Loaded ?: return
    val present = HashSet<MediaId>()
    loaded.entries.mapTo(present) { it.id }
    (nasContent.value as? NasTrashSection.Rows)?.entries?.mapTo(present) { it.id }
    _selection.update { current -> current.filterTo(LinkedHashSet()) { it in present } }
  }

  /** Tocca una cella o una riga: la sceglie o la toglie. */
  fun toggle(id: MediaId) {
    if (!gate.isUnlocked) return
    _selection.update { if (id in it) it - id else it + id }
  }

  /** Sceglie tutto cio' che si vede, nei due cestini. */
  fun selectAll() {
    if (!gate.isUnlocked) return
    val loaded = content.value as? TrashUiState.Loaded ?: return
    val all = LinkedHashSet<MediaId>()
    loaded.entries.mapTo(all) { it.id }
    (nasContent.value as? NasTrashSection.Rows)?.entries?.mapTo(all) { it.id }
    _selection.value = all
  }

  fun clearSelection() {
    _selection.value = emptySet()
  }

  /**
   * La richiesta di ripristino delle foto del telefono di [ids] (default: la selezione): il dialogo di sistema che chiede conferma. Null se
   * non c'e' niente da ripristinare sul telefono, il cestino non esiste o la pagina e' chiusa. La lancia la UI con
   * `startIntentSenderForResult`. I nascosti si ripristinano **nascosti**: qui non si tocca nessun dato personale.
   */
  fun restoreRequest(ids: Set<MediaId> = _selection.value): IntentSender? {
    if (!gate.isUnlocked) return null
    return intents.trashRequest(refsOf(ids), trash = false)
  }

  /** I riferimenti del telefono che il ripristino di [ids] riguarda (per i test e per il riepilogo). */
  fun refsOf(ids: Set<MediaId>): List<MediaRef.Phone> {
    val loaded = content.value as? TrashUiState.Loaded ?: return emptyList()
    return loaded.entries.filter { it.id in ids }.map { it.item.ref }
  }

  /** Le voci del cestino del NAS di [ids] (default: la selezione), nell'ordine della pagina. */
  fun nasEntriesOf(ids: Set<MediaId> = _selection.value): List<NasTrashEntry> =
    (nasContent.value as? NasTrashSection.Rows)?.entries.orEmpty().filter { it.id in ids }

  /**
   * Ripristina le voci del NAS scelte che si possono ripristinare (record valido, share scrivibile). Il tocco sul pulsante "Ripristina" e' la
   * conferma e la prova ([tap]) la porta il composable che lo disegna: ripristinare non toglie niente e non sovrascrive mai. Null se nella selezione non
   * c'e' niente del NAS o la pagina e' chiusa.
   *
   * ED-2 (revisione di Fase 7): una selezione con voci del NAS che **non si possono ripristinare** (senza record valido, share in sola lettura, NAS che
   * non risponde) non e' piu' un silenzio: risponde [NasTrashOutcome.REJECTED] e la pagina lo dice. Con una selezione mista le voci ripristinabili partono
   * e quelle che non lo sono si contano con [unrestorableNas], per l'avviso.
   */
  suspend fun restoreNas(tap: UserTap, ids: Set<MediaId> = _selection.value): NasTrashOutcome? {
    if (!gate.isUnlocked) return null
    val chosen = nasEntriesOf(ids)
    if (chosen.isEmpty()) return null
    val section = nasContent.value as? NasTrashSection.Rows ?: return NasTrashOutcome.REJECTED
    if (!section.writable) return NasTrashOutcome.REJECTED
    val acted = chosen.filter { it.row.restorable }
    val keys = acted.mapTo(LinkedHashSet()) { it.row.key }
    if (keys.isEmpty()) return NasTrashOutcome.REJECTED
    return afterNasGesture(acted.mapTo(HashSet()) { it.id }) { nasTrash.restore(keys, tap) }
  }

  /** Quante voci del NAS di [ids] (default: la selezione) "Ripristina" non puo' ripristinare adesso: senza record valido, o il NAS in sola lettura o spento. */
  fun unrestorableNas(ids: Set<MediaId> = _selection.value): Int {
    val chosen = nasEntriesOf(ids)
    if (chosen.isEmpty()) return 0
    val section = nasContent.value as? NasTrashSection.Rows
    return if (section == null || !section.writable) chosen.size else chosen.count { !it.row.restorable }
  }

  /**
   * Se "Elimina subito" e' possibile per [ids] (default: la selezione): solo voci **del NAS**, **tutte proprie** (Q8, D25), con il NAS che risponde e
   * almeno una share scrivibile. Una voce del telefono o di un altro dispositivo nella selezione lo spegne.
   */
  fun canPurge(ids: Set<MediaId> = _selection.value): Boolean {
    if (!gate.isUnlocked || ids.isEmpty()) return false
    val section = nasContent.value as? NasTrashSection.Rows ?: return false
    if (!section.writable || section.offline) return false
    val chosen = nasEntriesOf(ids)
    return chosen.size == ids.size && chosen.all { it.row.deletableNow }
  }

  /** Quanti elementi e quanti byte toglierebbe "Elimina subito" per [ids]: per il riepilogo del foglio. */
  fun purgeSummary(ids: Set<MediaId> = _selection.value): Pair<Int, Long> {
    val chosen = nasEntriesOf(ids)
    return chosen.size to chosen.sumOf { it.row.sizeBytes }
  }

  /**
   * "Elimina subito": elimina per sempre le voci proprie scelte. Serve la prova dei due tocchi del foglio ([proof], Q8): senza non si fa niente. Null
   * se non e' possibile ([canPurge]) o la pagina e' chiusa.
   */
  suspend fun purgeNas(proof: UserTap, ids: Set<MediaId> = _selection.value): NasTrashOutcome? {
    if (proof.taps < DoubleTapConfirm.REQUIRED || !canPurge(ids)) return null
    val acted = nasEntriesOf(ids)
    val keys = acted.mapTo(LinkedHashSet()) { it.row.key }
    return afterNasGesture(acted.mapTo(HashSet()) { it.id }) { nasTrash.purgeNow(keys, proof) }
  }

  /** Esegue un gesto sul NAS: se parte, quello su cui ha agito ([acted]) esce dalla selezione; poi si rilegge. */
  private suspend fun afterNasGesture(acted: Set<MediaId>, gesture: suspend () -> NasTrashOutcome): NasTrashOutcome {
    val outcome = try {
      gesture()
    } catch (e: CancellationException) {
      throw e
    } catch (_: Exception) {
      NasTrashOutcome.FAILED
    }
    if (outcome == NasTrashOutcome.STARTED) _selection.update { current -> current - acted }
    if (gate.isUnlocked) {
      try {
        nasTrash.refresh()
      } catch (e: CancellationException) {
        throw e
      } catch (_: Exception) {
        // La prossima lettura lo dira'.
      }
    }
    return outcome
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

    /** L'identita' di una voce del cestino del NAS nella selezione: stabile per chiave, diversa da quelle del telefono. */
    fun nasEntryId(key: String): MediaId = MediaId(UUID.nameUUIDFromBytes("nastrash|$key".toByteArray(Charsets.UTF_8)).toString())
  }
}
