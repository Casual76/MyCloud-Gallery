package dev.pampa.mycloudgallery.core.albums.upkeep

import dev.pampa.mycloudgallery.core.model.AlbumId
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// IL BUDGET DELLE RICERCHE PER CONTENUTO (Fase 7, correzioni F, risposta dell'utente del 27/10 su R19 e L146). Ogni album smart con una descrizione o delle foto
// d'esempio costa una ricerca per contenuto sull'intera Libreria a ogni rivalutazione. L'utente non vuole un tetto a quanti se ne creano; ma un dispositivo
// guasto o compromesso dello stesso account potrebbe farne arrivare centinaia, e rivalutarli tutti insieme terrebbe il telefono occupato. Qui non si rifiuta
// niente e non si dice niente all'utente: **in ogni giro (una finestra di tempo) partono al piu' [perRound] ricerche; gli altri smart aspettano in fila e
// toccano al giro dopo, nell'ordine in cui sono arrivati**. Con pochi album non cambia niente (stanno tutti nel primo giro); con tanti si aggiornano un po'
// piu' lentamente. Niente titoli, descrizioni o id nei log (D37): questa classe non scrive log e porta soli numeri (`waiting`).
//
// Il giro non ha un orologio proprio: la finestra e' un `delay` dell'ambito, quindi i test la guidano con il tempo virtuale di `runTest` (come
// [ChangeBatcher] e [Debouncer]). La fila non si perde: sta in memoria, e dopo la morte del processo la rifanno `resumeAll` e la notte (ogni smart si valuta
// una volta al montaggio), che passano dallo stesso budget.

/**
 * Concede [perRound] ricerche per contenuto per giro di [round] e mette in fila le altre.
 *
 * - [admit] dice quali fra gli smart chiesti possono cercare **adesso**; gli altri entrano in fila (una volta sola: chiederne uno gia' in fila non lo sposta).
 * - Quando il giro finisce, i primi della fila ricevono i posti del giro nuovo e [run] li valuta; gli altri restano in fila. Nessuno scavalca la fila: finche'
 *   ce n'e', i posti del giro nuovo sono dei primi della fila, non di chi arriva dopo.
 * - Il giro parte alla prima ricerca concessa e non si ferma finche' ci sono ricerche fatte o in fila; con la Libreria calma si spegne da solo.
 */
class ContentBudget(
  private val scope: CoroutineScope,
  val perRound: Int = PER_ROUND,
  private val round: Duration = ROUND,
  /** Valuta questi smart (hanno gia' il loro posto: non passano da [admit]). Un errore lo conta chi valuta, mai questa classe. */
  private val run: suspend (List<AlbumId>) -> Unit,
) {
  init {
    require(perRound >= 1) { "Un giro senza ricerche non finirebbe mai." }
    require(round > Duration.ZERO) { "Un giro senza durata si richiamerebbe da solo all'infinito." }
  }

  private val lock = Any()
  private val queue = LinkedHashSet<AlbumId>()
  private var used = 0
  private var timer: Job? = null

  /** Quanti smart aspettano il loro giro (Diagnostica: solo il numero). */
  val waiting: Int get() = synchronized(lock) { queue.size }

  /** Quante ricerche ha concesso il giro in corso (per i test). */
  val usedNow: Int get() = synchronized(lock) { used }

  /** Fra [ids] (nell'ordine) chi puo' cercare adesso; gli altri sono in fila. */
  fun admit(ids: Collection<AlbumId>): Set<AlbumId> {
    if (ids.isEmpty()) return emptySet()
    synchronized(lock) {
      val granted = LinkedHashSet<AlbumId>()
      for (id in ids) {
        if (id in granted) continue
        if (used < perRound && (queue.isEmpty() || id in queue)) {
          used++
          queue.remove(id)
          granted += id
        } else {
          queue += id
        }
      }
      if ((used > 0 || queue.isNotEmpty()) && timer == null) arm()
      return granted
    }
  }

  /** Lo smart non c'e' piu' o non ha piu' bisogno della ricerca: esce dalla fila. */
  fun forget(id: AlbumId) {
    synchronized(lock) { queue.remove(id) }
  }

  private fun arm() {
    timer = scope.launch {
      delay(round)
      rollOver()
    }
  }

  /** Il giro finisce: i primi della fila prendono i posti del giro nuovo e si valutano; se non c'e' piu' niente da fare il giro si spegne. */
  private suspend fun rollOver() {
    val batch = synchronized(lock) {
      timer = null
      val next = queue.take(perRound)
      queue.removeAll(next.toSet())
      used = next.size
      if (used > 0) arm()
      next
    }
    if (batch.isEmpty()) return
    try {
      run(batch)
    } catch (e: CancellationException) {
      throw e
    } catch (_: Exception) {
      // Chi valuta conta i suoi errori; un'eccezione non deve arrivare al gestore dell'ambito (chiuderebbe l'app).
    }
  }

  companion object {
    /** Le ricerche per contenuto concesse a ogni giro. Con meno album non si aspetta mai; con 300 servono 38 giri (circa 19 minuti). */
    const val PER_ROUND: Int = 8

    /** La durata di un giro. */
    val ROUND: Duration = 30.seconds
  }
}
