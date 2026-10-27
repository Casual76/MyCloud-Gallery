package dev.pampa.mycloudgallery.core.faces.erase

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.faces.contract.EraseStart
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * "Ogni passo del §2.8 e' idempotente, con la morte del processo a ogni salvataggio del diario e `FaultPlan` a ogni chiamata" (riga P8 del §4.4).
 *
 * - **Morte del processo**: in una storia pulita il diario si salva N volte; per ogni salvataggio, prima e dopo la scrittura, il processo di chi
 *   cancella muore, riparte (interruttore, recinto e cancellazione nuovi; tutto cio' che e' su disco resta) e la storia continua. Due storie: tutto
 *   acceso, e un dispositivo spento che torna a cancellazione gia' fatta sul NAS.
 * - **Guasto a ogni chiamata**: in una storia pulita le porte della cancellazione si chiamano M volte; per ogni chiamata, prima e dopo l'effetto,
 *   quella fallisce. Le riprese arrivano in fondo.
 *
 * Dopo ogni passo e alla fine: i sei invarianti ([EraseInvariants]).
 */
class EraseResumeTest {

  private class Story(val offline: Boolean) {
    val world = EraseWorld()
    val invariants = EraseInvariants(world)
    lateinit var a: EraseDevice
    lateinit var c: EraseDevice

    suspend fun setUp(faults: PortFaults? = null) {
      val devices = world.activeFamily("A", "B", "C", invariants = invariants)
      a = devices[0]
      c = devices[2]
      if (faults != null) {
        a.faults = faults
        a.restart()
        a.personalRound()
      }
      if (offline) c.online = false
    }

    /** Un passo: una morte del processo fa ripartire chi cancella; gli invarianti dopo. */
    suspend fun step(label: String, block: suspend () -> Unit) {
      try {
        block()
      } catch (e: Death) {
        a.journal.dieAt = -1
        a.restart()
      }
      invariants.check(label)
    }

    suspend fun run(): Boolean {
      step("conferma") { if (a.confirmErase() == EraseStart.Unavailable) Unit }
      // Una morte prima del primo salvataggio: nessun diario, la cancellazione non e' partita; l'utente ritocca.
      if (a.journal.stored == null) step("conferma di nuovo") { a.confirmErase() }
      repeat(10) { n -> if (a.journal.stored?.done != true) step("giro $n") { world.turn(invariants) } }
      if (offline) {
        c.restart()
        c.online = true
        c.publish()
        invariants.check("ritorno")
        repeat(10) { n -> if (a.journal.stored?.done != true) step("dopo il ritorno $n") { world.turn(invariants) } }
      }
      val journal = a.journal.stored
      check(journal == null || journal.done || a.eraser.status.value?.waiting != null) {
        "fine senza motivo: $journal, stato ${a.eraser.status.value}, guasti ${a.faults?.fired}, chiamate ${a.ops.calls.size}"
      }
      invariants.final("fine", a)
      return a.journal.stored?.done == true
    }

    fun close() = world.close()
  }

  private suspend fun cleanSaves(offline: Boolean): Int {
    val story = Story(offline)
    story.setUp()
    check(story.run()) { "la storia pulita non arriva in fondo" }
    return story.a.journal.saves.also { story.close() }
  }

  @Test
  fun laMorteDelProcessoAOgniSalvataggioDelDiario() = runTest {
    for (offline in listOf(false, true)) {
      val saves = cleanSaves(offline)
      assertThat(saves).isAtLeast(8)
      for (k in 1..saves) {
        for (after in listOf(false, true)) {
          val story = Story(offline)
          story.setUp()
          story.a.journal.dieAt = k
          story.a.journal.dieAfter = after
          val done = story.run()
          assertWithMessage("morte al salvataggio $k (${if (after) "dopo" else "prima"}), spento=$offline: non arriva in fondo").that(done).isTrue()
          story.close()
        }
      }
    }
  }

  @Test
  fun unGuastoAOgniChiamataPrimaEDopo() = runTest {
    val clean = Story(offline = false)
    val counter = PortFaults()
    clean.setUp(counter)
    check(clean.run())
    val calls = counter.calls
    clean.close()
    assertThat(calls).isAtLeast(20)
    var fired = 0
    for (k in 1..calls) {
      for (after in listOf(false, true)) {
        val story = Story(offline = false)
        val faults = PortFaults().apply {
          failAt = k
          this.after = after
        }
        story.setUp(faults)
        val done = story.run()
        fired += faults.fired.size
        assertWithMessage("guasto alla chiamata $k (${faults.fired}, ${if (after) "dopo" else "prima"}): non arriva in fondo").that(done).isTrue()
        story.close()
      }
    }
    assertThat(fired).isAtLeast(calls)
  }
}
