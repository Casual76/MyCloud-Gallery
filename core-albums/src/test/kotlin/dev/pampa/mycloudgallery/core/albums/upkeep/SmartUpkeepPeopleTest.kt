package dev.pampa.mycloudgallery.core.albums.upkeep

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.albums.contract.PeopleRule
import dev.pampa.mycloudgallery.core.albums.contract.SmartRules
import dev.pampa.mycloudgallery.core.albums.smart.DeviceNormalizer
import dev.pampa.mycloudgallery.core.albums.smart.FlakyWorld
import dev.pampa.mycloudgallery.core.albums.smart.InMemorySeenMarks
import dev.pampa.mycloudgallery.core.albums.smart.InMemorySmartRows
import dev.pampa.mycloudgallery.core.albums.smart.MemoryWorld
import dev.pampa.mycloudgallery.core.albums.smart.RulesSmartEngine
import dev.pampa.mycloudgallery.core.albums.smart.ScriptedContent
import dev.pampa.mycloudgallery.core.albums.smart.SmartRecompute
import dev.pampa.mycloudgallery.core.albums.smart.Spots
import dev.pampa.mycloudgallery.core.albums.smart.albumId
import dev.pampa.mycloudgallery.core.albums.smart.cand
import dev.pampa.mycloudgallery.core.albums.smart.day
import dev.pampa.mycloudgallery.core.albums.smart.id
import dev.pampa.mycloudgallery.core.albums.smart.ranges
import dev.pampa.mycloudgallery.core.albums.testing.FakeAlbumClocks
import dev.pampa.mycloudgallery.core.albums.testing.FakePeopleMembership
import dev.pampa.mycloudgallery.core.model.AlbumId
import dev.pampa.mycloudgallery.core.model.PeopleMode
import dev.pampa.mycloudgallery.core.model.PersonId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Le persone cambiano (Fase 8, P10; D27, E-AL7, E-F37): gli smart **con la regola delle persone** si rivalutano interi, gli altri no; una persona che prende un volto nuovo
 * fa entrare la sua foto; dove i volti non ci sono l'album non cambia.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SmartUpkeepPeopleTest {
  private val d1 = day(2026, 3, 10)
  private val marco = PersonId.fromBytes(ByteArray(PersonId.BYTES) { 1 })

  private class Sink : SmartWorkSink {
    val finished = ArrayList<AlbumId>()

    override fun smartWorkStarted(albums: Collection<AlbumId>) = Unit

    override suspend fun smartWorkFinished(albums: Collection<AlbumId>) {
      finished += albums
    }

    override suspend fun smartRowsChanged() = Unit
  }

  private class Rig(scope: kotlinx.coroutines.CoroutineScope) {
    val world = MemoryWorld()
    val flaky = FlakyWorld(world)
    val rows = InMemorySmartRows()
    val clocks = FakeAlbumClocks()
    val people = FakePeopleMembership()
    val sink = Sink()
    val engine = RulesSmartEngine(rows, world, flaky, flaky, ScriptedContent(ready = false), DeviceNormalizer(null), Spots.LOOKUP, clocks, InMemorySeenMarks(), people)
    val recompute = SmartRecompute(rows, world, engine, clocks)
    val upkeep = SmartUpkeep(scope, rows, recompute, sink)
  }

  private val withMarco = SmartRules(ranges(d1 to d1.plusDays(5)), null, null, people = PeopleRule(listOf(marco), PeopleMode.ANY))
  private val withoutPeople = SmartRules(ranges(d1 to d1.plusDays(5)), null, null)

  private fun Rig.fill() {
    for (n in 0 until 10) world.put(cand(n, d1.plusDays((n % 4).toLong()), 600 + n))
  }

  @Test
  fun soloGliSmartConLePersoniSiRivalutano() = runTest {
    val rig = Rig(backgroundScope).also { it.fill() }
    val a = albumId(1)
    val b = albumId(2)
    rig.rows.create(a, withMarco)
    rig.rows.create(b, withoutPeople)
    rig.people.sure(marco, (0..3).map { id(it) })
    rig.upkeep.peopleChanged()
    assertThat(rig.rows.albums.getValue(a).auto.keys).containsExactlyElementsIn((0..3).map { id(it) })
    // L'altro smart non ha le persone: non e' stato toccato (nessuna riga, nessuna fine di lavoro).
    assertThat(rig.rows.albums[b]?.auto.orEmpty()).isEmpty()
    assertThat(rig.sink.finished).containsExactly(a)
  }

  @Test
  fun unaPersonaCheCambiaFaEntrareOUscireLeFoto() = runTest {
    val rig = Rig(backgroundScope).also { it.fill() }
    val a = albumId(1)
    rig.rows.create(a, withMarco)
    rig.people.sure(marco, listOf(id(0), id(1)))
    rig.upkeep.peopleChanged()
    assertThat(rig.rows.albums.getValue(a).auto.keys).containsExactly(id(0), id(1))
    // Un'assegnazione nuova: la foto 2 e' di Marco.
    rig.people.sure(marco, listOf(id(2)))
    rig.upkeep.peopleChanged()
    assertThat(rig.rows.albums.getValue(a).auto.keys).containsExactly(id(0), id(1), id(2))
  }

  @Test
  fun senzaVoltiNienteCambiaE_F37() = runTest {
    val rig = Rig(backgroundScope).also { it.fill() }
    val a = albumId(1)
    rig.rows.create(a, withMarco)
    rig.people.sure(marco, listOf(id(0), id(1)))
    rig.upkeep.peopleChanged()
    val before = rig.rows.albums.getValue(a).auto.keys.toSet()
    rig.people.available.value = false
    rig.upkeep.peopleChanged()
    assertThat(rig.rows.albums.getValue(a).auto.keys).containsExactlyElementsIn(before)
    // Tornano i volti: l'album si rifa'.
    rig.people.available.value = true
    rig.people.sure(marco, listOf(id(3)))
    rig.upkeep.peopleChanged()
    assertThat(rig.rows.albums.getValue(a).auto.keys).containsExactly(id(0), id(1), id(3))
  }

  @Test
  fun unoSmartCongelatoNonSiRivaluta() = runTest {
    val rig = Rig(backgroundScope).also { it.fill() }
    val a = albumId(1)
    rig.rows.create(a, withMarco)
    rig.people.sure(marco, listOf(id(0)))
    rig.upkeep.peopleChanged()
    rig.rows.albums.getValue(a).frozen = true
    rig.people.sure(marco, listOf(id(1)))
    rig.upkeep.peopleChanged()
    assertThat(rig.rows.albums.getValue(a).auto.keys).containsExactly(id(0))
  }
}
