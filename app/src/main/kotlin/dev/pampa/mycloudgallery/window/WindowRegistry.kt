package dev.pampa.mycloudgallery.window

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

// LE FINESTRE DELL'APP (Fase 10, P4, D16, D17, D18, E-P18, E-T6; docs/fase10-progetto.md §2.4). Una finestra e' un **task** di `MainActivity`
// (`singleInstancePerTask`: un'istanza per task). Il processo ne tiene al piu' due; la terza e' rifiutata. Qui la contabilita', pura e senza Android.

/** Il ruolo di una finestra: la **principale** serve gli ingressi (notifiche, widget); la seconda e' un sostegno (confronto, trascinamento fra album). */
enum class WindowRole {
  /** La finestra viva piu' vecchia del processo: a lei arrivano notifiche e widget (D16: "aprono la finestra principale"). */
  PRIMARY,

  /** L'altra: stessi dati, selezioni e navigazione proprie (D18), non serve gli ingressi. */
  SECONDARY,
  ;

  /** Chi apre i Ricordi dal tocco di una notifica e, con P3, la foto del widget: una finestra sola, la principale (E-NT8, E-W7). */
  val servesEntryRequests: Boolean get() = this == PRIMARY
}

/** L'esito della richiesta di una finestra a `MainActivity.onCreate`. */
sealed interface WindowAdmission {
  /** Una finestra nuova, ammessa. */
  data class Admitted(val role: WindowRole) : WindowAdmission

  /** Una finestra gia' conosciuta (la stessa che si ricrea: densita', lingua, tema, processo ripreso dai recenti). */
  data class Known(val role: WindowRole) : WindowAdmission

  /** La terza finestra (E-T6): non nasce; si porta davanti quella che c'e', con un messaggio. */
  data object Refused : WindowAdmission
}

/** Le regole delle finestre che non dipendono dal sistema (provate sulla JVM). */
object WindowPolicy {
  /** Al piu' due finestre dell'app (D16): bastano per confrontare e per trascinare fra due album, e tengono la memoria sotto controllo con la mappa e il viewer. */
  const val MAX_WINDOWS: Int = 2

  /** `singleInstancePerTask` e le finestre libere vogliono Android 12 (API 31): sotto, una finestra sola (L546). */
  const val MULTI_WINDOW_SDK: Int = 31

  fun maxWindows(sdkInt: Int): Int = if (sdkInt >= MULTI_WINDOW_SDK) MAX_WINDOWS else 1

  /**
   * "Apri in una nuova finestra" si offre solo da una finestra **estesa** (840 dp o piu') o con piu' finestre sullo schermo (DeX, finestra libera, schermo diviso), e solo
   * dove l'app puo' avere piu' di una finestra. Il riconoscimento esatto di DeX e' di Samsung e non e' verificato (D15, [da verificare sul Tab S9]): una finestra libera
   * stretta passa dal "piu' finestre sullo schermo".
   */
  fun offersNewWindow(widthDp: Int, multiWindowMode: Boolean, sdkInt: Int): Boolean =
    sdkInt >= MULTI_WINDOW_SDK && (widthDp >= EXPANDED_DP || multiWindowMode)

  /** Il limite delle finestre estese (D12): la stessa soglia di `GalleryWidth.EXPANDED`. */
  const val EXPANDED_DP: Int = 840
}

/**
 * Le finestre vive del processo, **nell'ordine in cui sono nate** (D16, D17, E-T6). Una finestra e' un task (`taskId`): una ricreazione dell'activity
 * (densita', lingua, tema) ha lo stesso task, quindi la stessa finestra, e non occupa un posto in piu'.
 *
 * - **Ammissione** ([admit]): la finestra e' nuova se il suo task non e' ancora qui; e' ammessa se i posti non sono finiti, altrimenti **rifiutata**. Prima di contare, i task che il
 *   sistema non elenca piu' (chiusi dai recenti mentre l'activity era gia' distrutta, e nessuno ce l'ha detto) si tolgono: un posto non resta occupato per sempre.
 * - **Ruolo**: la principale e' la piu' vecchia ancora viva; se si chiude, l'altra prende il suo posto ([roleFlow]).
 * - **Il tetto sta qui, non nell'intent**: `MainActivity` e' esportata, quindi un'altra app puo' provare ad avviarne molte istanze con `NEW_TASK | MULTIPLE_TASK`; la terza non
 *   supera [admit] qualunque intent la porti.
 *
 * Solo dal thread principale (le activity); sincronizzata comunque. Uno per processo.
 */
@Singleton
class WindowRegistry @Inject constructor() {

  private val order = MutableStateFlow<List<Int>>(emptyList())

  /** I task delle finestre vive, dalla piu' vecchia. */
  val windows: StateFlow<List<Int>> = order

  /**
   * La finestra del task [taskId] chiede un posto. [live] sono i task di `MainActivity` che il sistema elenca adesso (`null` = non lo so: non si toglie niente). [max] viene da
   * [WindowPolicy.maxWindows].
   */
  @Synchronized
  fun admit(taskId: Int, max: Int, live: Set<Int>? = null): WindowAdmission {
    prune(live, keep = taskId)
    val current = order.value
    if (taskId in current) return WindowAdmission.Known(roleIn(current, taskId))
    // Le altre finestre sono quelle vive qui e quelle che il sistema elenca ancora (anche addormentate nei recenti, dopo la morte del processo): sono tutte finestre dell'utente.
    if (knownOthers(taskId, live).size >= max) return WindowAdmission.Refused
    order.value = current + taskId
    return WindowAdmission.Admitted(roleIn(order.value, taskId))
  }

  /**
   * C'e' posto per una finestra in piu' (chiesta da [requester], che e' gia' una finestra)? Toglie prima i task che il sistema non elenca piu'. Le finestre che ci sono sono quelle
   * vive qui **e quelle che il sistema elenca ancora** (anche addormentate nei recenti dopo la morte del processo): una finestra e' un task, anche se dorme.
   */
  @Synchronized
  fun hasRoom(max: Int, live: Set<Int>?, requester: Int): Boolean {
    prune(live, keep = requester)
    return knownOthers(requester, live).size + 1 < max
  }

  /** La finestra e' finita per sempre (`onDestroy` di un'activity che finisce, non di una che si ricrea). */
  @Synchronized
  fun release(taskId: Int) {
    if (taskId in order.value) order.value = order.value - taskId
  }

  /** Il ruolo del task, o `null` se non e' una finestra viva. */
  fun roleOf(taskId: Int): WindowRole? {
    val current = order.value
    return if (taskId in current) roleIn(current, taskId) else null
  }

  /** Il ruolo di una finestra nel tempo (cambia quando si chiude l'altra). Una finestra non registrata conta come principale: e' sola. */
  fun roleFlow(taskId: Int): Flow<WindowRole> = order.map { roleIn(it, taskId) }.distinctUntilChanged()

  /** Il task della finestra principale, se c'e'. */
  val primary: Int? get() = order.value.firstOrNull()

  /** I task delle altre finestre (non quello dato), dalla piu' vecchia: prima quelle vive qui, poi (con [live]) quelle che il sistema elenca ancora e qui dormono. */
  fun othersThan(taskId: Int, live: Set<Int>? = null): List<Int> = (order.value + live.orEmpty().sorted()).distinct().filter { it != taskId }

  /** Quante finestre ci sono. */
  val count: Int get() = order.value.size

  /** Le finestre diverse da [taskId] che conosciamo: quelle vive qui e quelle che il sistema elenca ancora ([live]). */
  private fun knownOthers(taskId: Int, live: Set<Int>?): Set<Int> = (order.value.toSet() + live.orEmpty()) - taskId

  private fun roleIn(list: List<Int>, taskId: Int): WindowRole =
    if (list.isEmpty() || list.first() == taskId || taskId !in list) WindowRole.PRIMARY else WindowRole.SECONDARY

  /** Toglie le finestre che il sistema non elenca piu'; [keep] (la finestra che chiede) resta sempre. */
  private fun prune(live: Set<Int>?, keep: Int) {
    if (live == null) return
    val kept = order.value.filter { it in live || it == keep }
    if (kept.size != order.value.size) order.value = kept
  }
}
