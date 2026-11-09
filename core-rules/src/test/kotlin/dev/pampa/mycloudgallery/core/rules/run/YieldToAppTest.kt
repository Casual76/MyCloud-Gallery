package dev.pampa.mycloudgallery.core.rules.run

import android.os.Process
import com.google.common.truth.Truth.assertThat
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Review generale, OB-3: la priorita' dei thread del lavoro pesante di fondo ([YieldToApp]). Con l'app in uso ogni thread su cui il lavoro
 * gira scende a `THREAD_PRIORITY_BACKGROUND` e torna com'era quando il lavoro lo lascia; in secondo piano niente si tocca. Con priorita'
 * finte per thread (la JVM dei test non ha il nice di Linux); l'adattatore di Android e' provato in fondo con Robolectric.
 */
@RunWith(RobolectricTestRunner::class)
class YieldToAppTest {

  private companion object {
    /** Il nome del thread corrente, senza il segno che le coroutine gli aggiungono nei test (" @coroutine#12"). */
    fun threadName(): String = Thread.currentThread().name.substringBefore(" @")
  }

  /** La priorita' di ogni thread (0 di serie) e la storia dei cambi. */
  private class FakePriorities : ThreadPriorities {
    val byThread = ConcurrentHashMap<Thread, Int>()
    val changes = CopyOnWriteArrayList<Pair<String, Int>>()

    @Volatile var untouchable: Thread? = null

    override fun current(): Int? = if (Thread.currentThread() === untouchable) null else here()

    override fun set(priority: Int) {
      byThread[Thread.currentThread()] = priority
      changes += threadName() to priority
    }

    fun here(): Int = byThread[Thread.currentThread()] ?: 0
  }

  private val priorities = FakePriorities()
  private val inUse = AtomicBoolean(true)
  private val dispatchers = ArrayList<ExecutorCoroutineDispatcher>()

  private fun single(name: String): ExecutorCoroutineDispatcher =
    Executors.newSingleThreadExecutor { Thread(it, name).apply { isDaemon = true } }.asCoroutineDispatcher().also { dispatchers += it }

  private fun element() = YieldToApp({ inUse.get() }, priorities)

  @After
  fun close() = dispatchers.forEach { it.close() }

  @Test
  fun conLAppInUsoIlThreadDelLavoroScendeDiPriorita_eTornaComEraQuandoIlLavoroLoLascia() = runBlocking<Unit> {
    val worker = single("lavoro")
    withContext(worker + element()) {
      assertThat(threadName()).isEqualTo("lavoro")
      assertThat(priorities.here()).isEqualTo(Process.THREAD_PRIORITY_BACKGROUND)
    }
    // Lo stesso thread, per un altro lavoro (senza l'elemento): la priorita' di prima.
    withContext(worker) { assertThat(priorities.here()).isEqualTo(0) }
    assertThat(priorities.changes).containsExactly("lavoro" to 10, "lavoro" to 0).inOrder()
  }

  @Test
  fun conLAppInSecondoPianoNessunThreadSiTocca() = runBlocking<Unit> {
    inUse.set(false)
    val worker = single("lavoro")
    withContext(worker + element()) {
      assertThat(priorities.here()).isEqualTo(0)
      withContext(Dispatchers.Default) { assertThat(priorities.here()).isEqualTo(0) }
    }
    assertThat(priorities.changes).isEmpty()
  }

  @Test
  fun ancheLeFiglieEIThreadDeiMotoriScendono_eOgniThreadTornaComEra() = runBlocking<Unit> {
    val worker = single("lavoro")
    val engine = single("motore")
    val helper = single("aiuto")
    withContext(worker + element()) {
      // Il thread di un motore (`withContext(engine.dispatcher)`, come YuNet, SFace e l'embedder delle immagini).
      withContext(engine) {
        assertThat(threadName()).isEqualTo("motore")
        assertThat(priorities.here()).isEqualTo(10)
      }
      // Una figlia in parallelo (i media preparati insieme).
      async(helper) { priorities.here() }.await().let { assertThat(it).isEqualTo(10) }
      assertThat(priorities.here()).isEqualTo(10)
    }
    for (d in listOf(worker, engine, helper)) withContext(d) { assertThat(priorities.here()).isEqualTo(0) }
  }

  @Test
  fun seLAppVaInSecondoPianoAMetaLavoroSiTornaAPieno_eSeTornaInUsoSiRallentaDiNuovo() = runBlocking<Unit> {
    val worker = single("lavoro")
    withContext(worker + element()) {
      assertThat(priorities.here()).isEqualTo(10)
      inUse.set(false)
      delay(5)
      // Alla prima ripresa dopo il cambio: priorita' piena.
      assertThat(priorities.here()).isEqualTo(0)
      assertThat(YieldToApp.yielding(currentCoroutineContext())).isFalse()
      inUse.set(true)
      delay(5)
      assertThat(priorities.here()).isEqualTo(10)
      assertThat(YieldToApp.yielding(currentCoroutineContext())).isTrue()
    }
    withContext(worker) { assertThat(priorities.here()).isEqualTo(0) }
  }

  @Test
  fun unThreadGiaDiFondoNonSiAlzaENonSiRimetteANiente() = runBlocking<Unit> {
    val worker = single("lavoro")
    withContext(worker) { priorities.byThread[Thread.currentThread()] = 19 }
    withContext(worker + element()) { assertThat(priorities.here()).isEqualTo(19) }
    withContext(worker) { assertThat(priorities.here()).isEqualTo(19) }
    assertThat(priorities.changes).isEmpty()
  }

  @Test
  fun ilThreadCheNonSiDeveToccareRestaComE() = runBlocking<Unit> {
    val main = single("principale")
    withContext(main) { priorities.untouchable = Thread.currentThread() }
    val worker = single("lavoro")
    withContext(worker + element()) {
      withContext(main) { assertThat(priorities.here()).isEqualTo(0) }
      assertThat(priorities.here()).isEqualTo(10)
    }
    assertThat(priorities.changes.map { it.first }.toSet()).containsExactly("lavoro")
  }

  @Test
  fun iThreadDiInferenza_menoConLAppInUso_tuttiAltrimenti_eFuoriDalLavoroDiFondoSempreTutti() = runBlocking<Unit> {
    assertThat(YieldToApp.inferenceThreads(yielding = true, full = 4)).isEqualTo(2)
    assertThat(YieldToApp.inferenceThreads(yielding = true, full = 1)).isEqualTo(1)
    assertThat(YieldToApp.inferenceThreads(yielding = false, full = 4)).isEqualTo(4)
    // Senza l'elemento (la ricerca, il ritaglio: cio' che l'utente aspetta) niente si fa da parte, anche con l'app in uso.
    assertThat(YieldToApp.yielding(currentCoroutineContext())).isFalse()
    withContext(element()) { assertThat(YieldToApp.yielding(currentCoroutineContext())).isTrue() }
    inUse.set(false)
    withContext(element()) { assertThat(YieldToApp.yielding(currentCoroutineContext())).isFalse() }
  }

  // --- L'adattatore di Android (Robolectric: `Process.setThreadPriority` e `getThreadPriority` per thread) ------------------------

  @Test
  fun suAndroidUnThreadDiLavoroScendeATHREAD_PRIORITY_BACKGROUND_eTorna() {
    var before: Int? = null
    var lowered: Int? = null
    var restored: Int? = null
    thread(name = "lavoro-android") {
      before = AndroidThreadPriorities.current()
      val element = YieldToApp({ true })
      val old = element.updateThreadContext(element)
      lowered = AndroidThreadPriorities.current()
      element.restoreThreadContext(element, old)
      restored = AndroidThreadPriorities.current()
    }.join()
    assertThat(before).isEqualTo(Process.THREAD_PRIORITY_DEFAULT)
    assertThat(lowered).isEqualTo(Process.THREAD_PRIORITY_BACKGROUND)
    assertThat(restored).isEqualTo(Process.THREAD_PRIORITY_DEFAULT)
  }

  @Test
  fun suAndroidIlThreadPrincipaleNonSiToccaMai() {
    // Il thread del test e' il thread principale di Robolectric.
    assertThat(AndroidThreadPriorities.current()).isNull()
    val element = YieldToApp({ true })
    assertThat(element.updateThreadContext(element)).isNull()
    assertThat(Process.getThreadPriority(Process.myTid())).isEqualTo(Process.THREAD_PRIORITY_DEFAULT)
  }
}
