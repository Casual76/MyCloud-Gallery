package dev.pampa.mycloudgallery.faces

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.albums.contract.PeopleClass
import dev.pampa.mycloudgallery.core.albums.contract.PeopleMembership
import dev.pampa.mycloudgallery.core.albums.contract.PeopleRule
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.PeopleMode
import dev.pampa.mycloudgallery.core.model.PersonId
import dev.pampa.mycloudgallery.core.search.contract.PeopleDictionary
import dev.pampa.mycloudgallery.core.search.contract.PeopleLookup
import dev.pampa.mycloudgallery.core.search.contract.PersonOption
import dev.pampa.mycloudgallery.integration.until
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Fase 8, P13 (E-F37, "fallisce chiuso"): il ponte fra le persone (montate dopo) e chi le usa (ricerca e album smart, nati prima). Prima del legame, e dopo
 * lo scollegamento, risponde come i null-object: nessun dizionario, nessun candidato, la regola delle persone non inserisce niente e non toglie niente.
 */
class PeopleBridgeTest {

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

  @After
  fun tearDown() = scope.cancel()

  private val person = PersonId("00000000-0000-4000-8000-000000000001")
  private val media = MediaId("00000000-0000-4000-8000-0000000000a1")
  private val rule = PeopleRule(listOf(person), PeopleMode.ANY)

  private class FakeLookup(override val available: MutableStateFlow<Boolean>, private val cells: Set<MediaId>) : PeopleLookup {
    override val progressPercent: MutableStateFlow<Int?> = MutableStateFlow(null)

    override suspend fun dictionary(): PeopleDictionary = PeopleDictionary(emptyList())

    override suspend fun cells(option: PersonOption, includeLikely: Boolean): Set<MediaId> = cells
  }

  private class FakeMembership(private val answer: Map<MediaId, PeopleClass>) : PeopleMembership {
    override val available: StateFlow<Boolean> = MutableStateFlow(true)

    override suspend fun classify(rule: PeopleRule, candidates: Collection<MediaId>): Map<MediaId, PeopleClass> = answer

    override suspend fun unrecognized(rule: PeopleRule): Int = 3
  }

  @Test
  fun primaDelLegameFallisceChiuso() = runBlocking<Unit> {
    val bridge = PeopleBridge()
    assertThat(bridge.available.value).isFalse()
    assertThat(bridge.progressPercent.value).isNull()
    assertThat(bridge.dictionary().entries).isEmpty()
    assertThat(bridge.cells(PersonOption(listOf(person), PeopleMode.ANY, "x"), includeLikely = true)).isEmpty()
    // R10: "le persone non sono montate" e' "non so", non "nessuna foto": lancia, e il motore degli album non scrive niente.
    assertThrows(IllegalStateException::class.java) { runBlocking { bridge.classify(rule, listOf(media)) } }
    assertThat(bridge.unrecognized(rule)).isEqualTo(0)
  }

  @Test
  fun legatoRispondeConLeVerePersoneESegueLaDisponibilita() = runBlocking<Unit> {
    val bridge = PeopleBridge()
    val available = MutableStateFlow(true)
    val lookup = FakeLookup(available, setOf(media))
    bridge.bind(lookup, FakeMembership(mapOf(media to PeopleClass.SURE)), scope)

    assertThat(bridge.available.value).isTrue()
    assertThat(bridge.cells(PersonOption(listOf(person), PeopleMode.ANY, "x"), includeLikely = false)).containsExactly(media)
    assertThat(bridge.classify(rule, listOf(media))).containsExactly(media, PeopleClass.SURE)
    assertThat(bridge.unrecognized(rule)).isEqualTo(3)

    // Persone si spegne (o il dispositivo si esclude): chi usa il ponte lo sa subito, senza essere ricreato.
    available.value = false
    until("il ponte segue la disponibilita'") { !bridge.available.value }
    lookup.progressPercent.value = 40
    until("il ponte segue l'avanzamento") { bridge.progressPercent.value == 40 }
  }

  @Test
  fun scollegatoTornaAiNullObject() = runBlocking<Unit> {
    val bridge = PeopleBridge()
    bridge.bind(FakeLookup(MutableStateFlow(true), setOf(media)), FakeMembership(mapOf(media to PeopleClass.LIKELY)), scope)
    assertThat(bridge.available.value).isTrue()

    bridge.unbind()
    assertThat(bridge.available.value).isFalse()
    assertThat(bridge.progressPercent.value).isNull()
    assertThat(bridge.cells(PersonOption(listOf(person), PeopleMode.ANY, "x"), includeLikely = true)).isEmpty()
    // R10: "le persone non sono montate" e' "non so", non "nessuna foto": lancia, e il motore degli album non scrive niente.
    assertThrows(IllegalStateException::class.java) { runBlocking { bridge.classify(rule, listOf(media)) } }
    assertThat(bridge.unrecognized(rule)).isEqualTo(0)
  }

  @Test
  fun unNuovoLegameSostituisceIlPrimoESmetteDiSeguirlo() = runBlocking<Unit> {
    val bridge = PeopleBridge()
    val first = MutableStateFlow(true)
    bridge.bind(FakeLookup(first, setOf(media)), FakeMembership(emptyMap()), scope)
    val second = MutableStateFlow(false)
    bridge.bind(FakeLookup(second, emptySet()), FakeMembership(emptyMap()), scope)
    assertThat(bridge.available.value).isFalse()

    // Il primo non conta piu': cambiarlo non tocca il ponte.
    first.value = false
    first.value = true
    second.value = true
    until("il ponte segue il secondo legame") { bridge.available.value }
  }
}
