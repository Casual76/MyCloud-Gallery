package dev.pampa.mycloudgallery.core.memories.plan

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.memories.contract.DayRange
import dev.pampa.mycloudgallery.core.memories.contract.LessLevel
import dev.pampa.mycloudgallery.core.memories.contract.LessTarget
import dev.pampa.mycloudgallery.core.memories.contract.RefreshReason
import dev.pampa.mycloudgallery.core.memories.contract.ShelfRead
import dev.pampa.mycloudgallery.core.memories.shelf.CrashException
import dev.pampa.mycloudgallery.core.memories.shelf.DeathPoint
import dev.pampa.mycloudgallery.core.memories.shelf.FaultyIo
import dev.pampa.mycloudgallery.core.memories.shelf.FileShelfStore
import dev.pampa.mycloudgallery.core.memories.shelf.StateFilesIo
import dev.pampa.mycloudgallery.core.memories.testing.SyntheticMemoryLibrary
import dev.pampa.mycloudgallery.core.personal.testing.MemoriesRegister
import java.io.File
import java.time.LocalDate
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * **La morte del processo a ogni salvataggio dello scaffale, con il motore vero** (docs/fase9-progetto.md §4.3 "Stabilita'", D10): una storia di
 * giri, pulizie e scelte scrive lo scaffale piu' volte; per ognuna delle scritture e per ognuno dei tre punti in cui il processo puo' morire
 * (temporaneo a meta', temporaneo intero, dopo la rinomina) un nuovo processo trova lo scaffale **intero** (quello di prima o quello nuovo), riparte
 * e, ripetendo il passo, arriva allo **stesso scaffale finale** della storia senza morti. Mai un file a meta', mai un ricordo perso o doppio.
 */
class MemoryEngineCrashTest {

  @get:Rule
  val temp = TemporaryFolder()

  private fun d(year: Int, month: Int, day: Int) = LocalDate.of(year, month, day)

  private suspend fun readShelf(dir: File): ShelfRead = FileShelfStore(dir, Dispatchers.Unconfined).read()

  /**
   * Un dispositivo che puo' morire e ripartire sulla stessa cartella, con la stessa Libreria, le stesse scelte e lo stesso orologio.
   * [onCrash] riceve lo scaffale su file **subito** dopo la morte, prima che il nuovo processo faccia qualunque cosa.
   */
  private inner class Story(private val dir: File, io: FaultyIo, private val onCrash: suspend (ShelfRead) -> Unit = {}) {
    val lib = SyntheticMemoryLibrary(7).apply {
      batch {
        photos(LocalDate.of(2025, 11, 3), 9)
        photos(LocalDate.of(2024, 11, 3), 9)
        photos(LocalDate.of(2023, 11, 3), 9)
      }
    }
    val register = MemoriesRegister()
    var rig: EngineRig = newRig(io)
    var crashes = 0

    private fun newRig(io: FaultyIo) = EngineRig(
      visibility = lib.visibility, store = FileShelfStore(dir, io, Dispatchers.Unconfined), register = register, today = LocalDate.of(2026, 11, 3),
    )

    val engine get() = rig.engine

    fun setToday(day: LocalDate) {
      rig.clock.today = day
    }

    /** Un passo del motore; se il processo muore, ne parte uno nuovo sulla stessa cartella e il passo si ripete. */
    suspend fun act(step: suspend (MemoryEngine) -> Unit) {
      val today = rig.clock.today
      val before = rig.engine.current()
      try {
        step(rig.engine)
      } catch (_: CrashException) {
        crashes++
        // Il processo che muore non cambia il suo scaffale in memoria (e comunque non esiste piu').
        assertThat(rig.engine.current()).isEqualTo(before)
        onCrash(readShelf(dir))
        rig.close()
        rig = newRig(FaultyIo(StateFilesIo))
        rig.clock.today = today
        step(rig.engine)
      }
    }
  }

  private fun script(s: Story) = runBlocking {
    s.act { it.refresh(RefreshReason.NIGHTLY, 60.seconds) }
    s.lib.hide(s.engine.current()!!.memories.first().members[1])
    s.act { it.prune() }
    s.setToday(d(2026, 11, 4))
    s.act { it.refresh(RefreshReason.LIGHT, 5.seconds) }
    val last = s.engine.current()!!.memories.last().id
    s.act { it.dismiss(last) }
    s.lib.delete(s.engine.current()!!.memories.first().members[2])
    s.act { it.setLess(LessTarget.Days(DayRange.of(d(2024, 11, 3))), LessLevel.NEVER) }
    s.setToday(d(2026, 12, 5))
    s.act { it.refresh(RefreshReason.NIGHTLY, 60.seconds) }
  }

  @Test
  fun laMorteDelProcessoAOgniSalvataggioNonRovinaLoScaffale() {
    // La storia senza morti: gli scaffali dopo ogni scrittura e quello finale.
    val referenceDir = File(temp.newFolder(), "memories")
    val referenceIo = FaultyIo(StateFilesIo)
    val states = ArrayList<ShelfRead>()
    val reference = Story(referenceDir, referenceIo)
    referenceIo.onWritten = { states += runBlocking { readShelf(referenceDir) } }
    script(reference)
    val writes = referenceIo.writes
    val expectedFinal = runBlocking { readShelf(referenceDir) }
    reference.rig.close()
    assertThat(writes).isAtLeast(4)
    assertThat(reference.crashes).isEqualTo(0)
    assertThat(states).hasSize(writes)
    assertThat(expectedFinal).isInstanceOf(ShelfRead.Ready::class.java)

    for (point in DeathPoint.entries) for (k in 1..writes) {
      val dir = File(temp.newFolder(), "memories")
      val io = FaultyIo(StateFilesIo).also {
        it.dieOnWrite = k
        it.dieOnWritePoint = point
      }
      var seenAfterCrash: ShelfRead? = null
      val story = Story(dir, io) { seenAfterCrash = it }
      script(story)
      // Subito dopo la morte: lo scaffale di prima (o nessuno se era la prima scrittura), o quello nuovo se la rinomina era avvenuta.
      val expectedAfterCrash = when {
        point == DeathPoint.AFTER_RENAME -> states[k - 1]
        k == 1 -> ShelfRead.Missing
        else -> states[k - 2]
      }
      assertWithMessage("$point, scrittura $k: scaffale subito dopo la morte").that(seenAfterCrash).isEqualTo(expectedAfterCrash)
      assertWithMessage("$point, scrittura $k: il processo e' morto una volta").that(story.crashes).isEqualTo(1)
      assertWithMessage("$point, scrittura $k: scaffale finale uguale a quello senza morti").that(runBlocking { readShelf(dir) }).isEqualTo(expectedFinal)
      assertThat(dir.list()!!.toList()).containsExactly(FileShelfStore.FILE_NAME)
      story.rig.close()
    }
  }
}
