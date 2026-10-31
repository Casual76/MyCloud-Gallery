package dev.pampa.mycloudgallery.core.memories.plan

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.memories.contract.MemoryIds
import dev.pampa.mycloudgallery.core.memories.contract.MemoryKey
import dev.pampa.mycloudgallery.core.memories.contract.MemoryView
import dev.pampa.mycloudgallery.core.memories.contract.RefreshReason
import dev.pampa.mycloudgallery.core.memories.contract.ShelfState
import dev.pampa.mycloudgallery.core.memories.testing.InMemoryMemories
import dev.pampa.mycloudgallery.core.memories.testing.InMemoryShelfStore
import java.time.LocalDate
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test

/**
 * RP-2 (31/10): con l'istantanea della Libreria ancora `null` **"non lo so" non e' "non c'e'"**. `Memories.memory` non dice niente (mai `null`, che vorrebbe dire
 * "uscito, scartato, sotto 3 elementi"), e dice la verita' appena l'istantanea arriva; lo scaffale, intanto, si disegna com'e' nel file. Il motore e il riferimento
 * (`InMemoryMemories`) fanno la stessa cosa.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MemoryUnknownLibraryTest {

  private val rigs = ArrayList<EngineRig>()

  @After
  fun chiude() {
    rigs.forEach { it.close() }
  }

  private val shots = dayShots(LocalDate.of(2025, 11, 3), 9, series = 1, place = 3_100_001)

  private fun rig(): EngineRig = EngineRig(visibility = visibilityOver(shots)).also { rigs += it }

  @Test
  fun senzaIstantaneaIlRicordoNonDiceNienteEPoiDiceLaVerita() = runTest {
    val rig = rig()
    rig.refresh()
    val id = rig.engine.shelf.value.items.single().id
    // Come all'avvio a freddo: lo scaffale e' quello del file, la Libreria non e' ancora stata letta.
    rig.visibility.snapshot.value = null
    val answers = ArrayList<MemoryView?>()
    backgroundScope.launch { rig.engine.memory(id).collect { answers += it } }
    runCurrent()
    assertThat(answers).isEmpty()
    // Lo scaffale c'e' lo stesso (la sezione si disegna dall'ultimo scaffale senza aspettare la Libreria).
    assertThat(rig.engine.shelf.value.state).isEqualTo(ShelfState.Ready)
    assertThat(rig.engine.shelf.value.items).hasSize(1)
    // L'istantanea arriva: ora si sa, ed e' il ricordo vero.
    rig.visibility.snapshot.value = snapshotOf(shots, seq = 2)
    runCurrent()
    assertThat(answers).hasSize(1)
    assertThat(answers.single()).isNotNull()
    assertThat(answers.single()!!.memory.id).isEqualTo(id)
  }

  /** Un ricordo che non c'e' (id mai pubblicato) e' `null`, ma solo a Libreria letta. */
  @Test
  fun nonCeSoloALibreriaLetta() = runTest {
    val rig = rig()
    rig.refresh()
    val ghost = MemoryIds.of(MemoryKey.OnThisDay(LocalDate.of(2001, 1, 1)))
    rig.visibility.snapshot.value = null
    val answers = ArrayList<MemoryView?>()
    backgroundScope.launch { rig.engine.memory(ghost).collect { answers += it } }
    runCurrent()
    assertThat(answers).isEmpty()
    rig.visibility.snapshot.value = snapshotOf(shots, seq = 2)
    runCurrent()
    assertThat(answers).containsExactly(null)
  }

  /** Il riferimento dei contratti fa lo stesso: i due non possono dire cose diverse (parita'). */
  @Test
  fun ilRiferimentoFaLoStesso() = runTest {
    val rig = rig()
    val reference = referenceOver(rig)
    reference.refresh(RefreshReason.NIGHTLY, 60.seconds)
    val id = reference.shelf.value.items.single().id
    rig.visibility.snapshot.value = null
    val answers = ArrayList<MemoryView?>()
    backgroundScope.launch { reference.memory(id).collect { answers += it } }
    runCurrent()
    assertThat(answers).isEmpty()
    rig.visibility.snapshot.value = snapshotOf(shots, seq = 2)
    runCurrent()
    assertThat(answers.single()).isNotNull()
  }

  private fun referenceOver(rig: EngineRig): InMemoryMemories = InMemoryMemories(
    visible = rig.visibility,
    mask = rig.visibility,
    personal = rig.personal,
    store = InMemoryShelfStore(),
    clock = rig.clock,
    people = rig.people,
  )
}
