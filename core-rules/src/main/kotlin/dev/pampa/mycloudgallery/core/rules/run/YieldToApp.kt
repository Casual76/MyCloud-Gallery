package dev.pampa.mycloudgallery.core.rules.run

import android.os.Looper
import android.os.Process
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.ThreadContextElement

// IL LAVORO PESANTE DI FONDO SI FA DA PARTE QUANDO L'APP E' IN USO (review generale, OB-3; primo requisito dell'utente: «l'app deve sempre
// rimanere fluida, e i caricamenti [...] non si sentono durante l'utilizzo»). Il calcolo AI, i volti e la preparazione sono `CoroutineWorker`
// nello stesso processo dell'interfaccia: con l'app in primo piano non devono rubare CPU al thread principale e al RenderThread, ma devono
// continuare ad avanzare (chi usa molto l'app deve vedere finire l'indicizzazione). Tre pezzi, ognuno al suo posto:
//
//  1. il **ritmo** (lotti piccoli e pause) lo decidono le regole: `Pace.APP_IN_USE` per le attivita' `cpuIntensive` (`RuleEvaluator`);
//  2. la **priorita' dei thread** e' qui: [YieldToApp], un elemento del contesto della coroutine del lavoro;
//  3. i **thread di inferenza** dei motori che si riaprono in pochi millisecondi (i volti) li legge chi apre il motore, da questo stesso
//     elemento ([YieldToApp.inferenceThreads]).
//
// Niente di tutto questo vale con l'app in secondo piano: li' il lavoro va a ritmo pieno, con la priorita' di sempre e tutti i thread.

/**
 * La priorita' del thread corrente come la vede Linux (il valore "nice": piu' alto = meno CPU; 0 e' il predefinito, 10 e'
 * `THREAD_PRIORITY_BACKGROUND`). Su Android e' [AndroidThreadPriorities]; nei test una finta.
 */
interface ThreadPriorities {
  /** La priorita' del thread corrente, o null se non si puo' leggere o se **questo thread non si deve toccare** (il thread principale). */
  fun current(): Int?

  /** Cambia la priorita' del thread corrente. Mai un'eccezione. */
  fun set(priority: Int)
}

/**
 * `android.os.Process`: `setThreadPriority` cambia il nice del thread e, da `THREAD_PRIORITY_BACKGROUND` in su, lo sposta nel gruppo di
 * scheduling dei thread di fondo (meno quota di CPU quando c'e' contesa, niente quando non c'e'). Il thread principale non si tocca mai:
 * se un pezzo del lavoro ci passa (un `withContext(Dispatchers.Main)`), resta com'e'.
 */
object AndroidThreadPriorities : ThreadPriorities {
  override fun current(): Int? = try {
    if (Looper.getMainLooper()?.thread === Thread.currentThread()) null else Process.getThreadPriority(Process.myTid())
  } catch (e: RuntimeException) {
    null
  }

  override fun set(priority: Int) {
    try {
      Process.setThreadPriority(priority)
    } catch (e: RuntimeException) {
      // Un thread che sta finendo o una priorita' non concessa: il lavoro continua con la priorita' che ha.
    }
  }
}

/**
 * L'elemento del contesto di una coroutine di **lavoro pesante di fondo**: finche' [appInUse] dice che l'app e' in primo piano, ogni
 * thread su cui la coroutine (e ogni sua figlia: i `withContext`, gli `async`, i thread dei motori) riprende a girare scende a
 * [YIELDING_PRIORITY], e torna alla priorita' che aveva quando la coroutine lo lascia (i thread di `Dispatchers.Default` e `IO` sono di
 * tutti: il resto dell'app li ritrova com'erano). Con l'app in secondo piano non tocca niente: una lettura in memoria per ripresa.
 *
 * Si mette intorno al lavoro (`withContext(YieldToApp(...)) { ... }`), mai intorno a cio' che l'utente aspetta sullo schermo. Un thread
 * nativo creato da un thread gia' abbassato (il pool di un interprete aperto con l'app in uso) nasce con la stessa priorita': per questo i
 * motori che si riaprono in fretta si riaprono quando lo stato dell'app cambia ([yielding]).
 *
 * @param appInUse l'app e' in primo piano adesso? Si chiama a ogni ripresa della coroutine, da qualunque thread: deve costare niente
 *   (una lettura di uno `StateFlow`) e non lanciare.
 */
class YieldToApp(
  private val appInUse: () -> Boolean,
  private val priorities: ThreadPriorities = AndroidThreadPriorities,
) : ThreadContextElement<Int?>, AbstractCoroutineContextElement(Key) {

  /** Il lavoro si sta facendo da parte adesso (l'app e' in uso). */
  val yielding: Boolean get() = appInUse()

  /** Abbassa il thread su cui la coroutine riprende; rende la priorita' di prima (da rimettere), o null se non ha toccato niente. */
  override fun updateThreadContext(context: CoroutineContext): Int? {
    if (!appInUse()) return null
    val before = priorities.current() ?: return null
    // Gia' un thread di fondo (o gia' abbassato da un contesto piu' esterno): non si alza e non c'e' niente da rimettere.
    if (before >= YIELDING_PRIORITY) return null
    priorities.set(YIELDING_PRIORITY)
    return before
  }

  override fun restoreThreadContext(context: CoroutineContext, oldState: Int?) {
    if (oldState != null) priorities.set(oldState)
  }

  override fun toString(): String = "YieldToApp"

  companion object Key : CoroutineContext.Key<YieldToApp> {
    /** `Process.THREAD_PRIORITY_BACKGROUND`: sotto l'interfaccia (0 e -10 per il thread principale e il RenderThread in primo piano). */
    const val YIELDING_PRIORITY: Int = Process.THREAD_PRIORITY_BACKGROUND

    /** I thread di inferenza di un motore su CPU mentre l'app e' in uso: abbastanza per avanzare, senza occupare i core dell'interfaccia. */
    const val YIELDING_INFERENCE_THREADS: Int = 2

    /** Il lavoro di [context] si sta facendo da parte adesso. Falso fuori da un lavoro di fondo (nessun elemento: cio' che l'utente aspetta). */
    fun yielding(context: CoroutineContext): Boolean = context[Key]?.yielding == true

    /** Quanti thread di inferenza usare adesso: [full] di norma, al piu' [YIELDING_INFERENCE_THREADS] mentre il lavoro si fa da parte. */
    fun inferenceThreads(yielding: Boolean, full: Int): Int = if (yielding) minOf(full, YIELDING_INFERENCE_THREADS) else full
  }
}
