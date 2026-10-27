package dev.pampa.mycloudgallery.core.albums.smart

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.albums.contract.PeopleRule
import dev.pampa.mycloudgallery.core.albums.contract.SmartOutcome
import dev.pampa.mycloudgallery.core.albums.contract.SmartRules
import dev.pampa.mycloudgallery.core.albums.contract.SmartSource
import dev.pampa.mycloudgallery.core.albums.noop.NoPeopleMembership
import dev.pampa.mycloudgallery.core.albums.testing.FakeAlbumClocks
import dev.pampa.mycloudgallery.core.albums.testing.FakePeopleMembership
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.PeopleMode
import dev.pampa.mycloudgallery.core.model.PersonId
import dev.pampa.mycloudgallery.core.search.testing.FakePeopleLookup
import kotlin.time.Duration
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Fase 8 (P10, D27, §2.7 "Album smart", E-F23, E-F27, E-F28, E-F37, E-F38): la regola delle persone nel motore degli smart. **Sicuro** = un volto assegnato a ciascuna
 * persona richiesta ("tutte") o a una ("una qualunque"); **suggerito** = almeno un volto nella fascia "forse" (S6); con le altre regole vale il "e"; dove i volti non ci
 * sono la regola non inserisce niente e non toglie niente; una persona dimenticata si conta e non fa entrare niente con "tutte"; mai un nascosto. Solo nomi e id inventati.
 */
class SmartPeopleTest {
  private val marco = PersonId.fromBytes(ByteArray(PersonId.BYTES) { 1 })
  private val anna = PersonId.fromBytes(ByteArray(PersonId.BYTES) { 2 })
  private val giulia = PersonId.fromBytes(ByteArray(PersonId.BYTES) { 3 })
  private val d1 = day(2026, 3, 10)
  private val oslo = around(Spots.OSLO, 25)

  private class Rig(rules: SmartRules, val people: FakePeopleMembership = FakePeopleMembership()) {
    val world = MemoryWorld()
    val rows = InMemorySmartRows()
    val clocks = FakeAlbumClocks()
    val id = albumId(1)
    val album = rows.create(id, rules)
    val engine = RulesSmartEngine(rows, world, world, world, NoSmartContentSearch, DeviceNormalizer("Backup/questo-telefono"), Spots.LOOKUP, clocks, InMemorySeenMarks(), people)
  }

  private fun Rig.sure(): Set<MediaId> = rows.albums.getValue(id).auto.keys

  private fun Rig.suggested(): Map<MediaId, String> = rows.albums.getValue(id).suggestions.mapValues { it.value.reason }

  /** Quaranta foto mie e senza luogo nel giorno [d1]: gli indici 0..39. */
  private fun Rig.fill(count: Int = 40) {
    for (n in 0 until count) world.put(cand(n, d1, 600 + n))
  }

  private fun ids(range: IntRange): List<MediaId> = range.map { id(it) }

  private val onlyMarco get() = SmartRules(null, null, null, people = PeopleRule(listOf(marco), PeopleMode.ANY))

  @Test
  fun lePersoneDaSoleDannoISicuriEISuggeritiDelMotore() = runTest {
    val rig = Rig(onlyMarco).also { it.fill() }
    rig.people.sure(marco, ids(0..9)).likely(marco, ids(10..14))
    assertThat(rig.engine.evaluate(rig.id, Duration.INFINITE)).isEqualTo(SmartOutcome.Done(confident = 10, suggested = 5, newSinceSeen = 0))
    assertThat(rig.sure()).containsExactlyElementsIn(ids(0..9))
    // Un volto "forse": il sicuro delle altre regole diventa suggerito, con la ragione delle persone (S6).
    assertThat(rig.suggested().keys).containsExactlyElementsIn(ids(10..14))
    assertThat(rig.suggested().values.toSet()).containsExactly("s6")
    // Un media senza nessuna delle persone non c'e' mai.
    assertThat(rig.sure() + rig.suggested().keys).containsNoneIn(ids(15..39))
  }

  @Test
  fun unFamiliareEUnoScreenshotRestanoSuggeritiAncheConLaPersonaSicura() = runTest {
    val rig = Rig(onlyMarco)
    rig.world.put(
      cand(0, d1, 600),
      cand(1, d1, 601, source = SmartSource.FAMILY, device = null),
      cand(2, d1, 602, screen = true),
    )
    rig.people.sure(marco, ids(0..2))
    rig.engine.evaluate(rig.id, Duration.INFINITE)
    assertThat(rig.sure()).containsExactly(id(0))
    assertThat(rig.suggested()).containsExactly(id(1), "s1", id(2), "s2")
  }

  @Test
  fun tutteEUnaQualunque() = runTest {
    val people = FakePeopleMembership().sure(marco, ids(0..9)).sure(anna, ids(5..14)).likely(anna, ids(20..22))
    val all = Rig(SmartRules(null, null, null, people = PeopleRule(listOf(marco, anna), PeopleMode.ALL)), people).also { it.fill() }
    all.engine.evaluate(all.id, Duration.INFINITE)
    assertThat(all.sure()).containsExactlyElementsIn(ids(5..9))
    assertThat(all.suggested()).isEmpty()
    val any = Rig(SmartRules(null, null, null, people = PeopleRule(listOf(marco, anna), PeopleMode.ANY)), people).also { it.fill() }
    any.engine.evaluate(any.id, Duration.INFINITE)
    assertThat(any.sure()).containsExactlyElementsIn(ids(0..14))
    assertThat(any.suggested().keys).containsExactlyElementsIn(ids(20..22))
  }

  @Test
  fun conIlPeriodoEIlLuogoVaIlE() = runTest {
    val rules = SmartRules(ranges(d1 to d1.plusDays(1)), oslo, null, people = PeopleRule(listOf(marco), PeopleMode.ANY))
    val rig = Rig(rules)
    val inOslo = Spots.north(Spots.OSLO, 3.0)
    rig.world.put(
      // Oslo, nel periodo: 0 con Marco (sicuro), 1 senza, 2 con Marco "forse".
      cand(0, d1, 600, gps = inOslo, placeId = Spots.OSLO_ID),
      cand(1, d1, 610, gps = inOslo, placeId = Spots.OSLO_ID),
      cand(2, d1, 620, gps = inOslo, placeId = Spots.OSLO_ID),
      // Con Marco ma fuori dal periodo e a Bergen: fuori comunque.
      cand(3, d1.plusDays(5), 600, gps = inOslo, placeId = Spots.OSLO_ID),
      cand(4, d1, 630, gps = Spots.BERGEN, placeId = Spots.BERGEN_ID),
    )
    rig.people.sure(marco, listOf(id(0), id(3), id(4))).likely(marco, listOf(id(2)))
    rig.engine.evaluate(rig.id, Duration.INFINITE)
    assertThat(rig.sure()).containsExactly(id(0))
    assertThat(rig.suggested().keys).containsExactly(id(2))
  }

  @Test
  fun laRegolaBNonDipendeDallePersoneDeiPuntiFermi() = runTest {
    // Una foto senza GPS fra due foto di Oslo: B la fa entrare se anche lei e' di Marco; i punti fermi (A) non devono essere di Marco.
    val rules = SmartRules(ranges(d1 to d1), oslo, null, people = PeopleRule(listOf(marco), PeopleMode.ANY))
    val rig = Rig(rules)
    val inOslo = Spots.north(Spots.OSLO, 3.0)
    rig.world.put(
      cand(0, d1, 9 * 60, gps = inOslo, placeId = Spots.OSLO_ID),
      cand(1, d1, 13 * 60 + 30),
      cand(2, d1, 15 * 60, gps = inOslo, placeId = Spots.OSLO_ID),
      cand(3, d1, 14 * 60),
    )
    rig.people.sure(marco, listOf(id(1)))
    rig.engine.evaluate(rig.id, Duration.INFINITE)
    assertThat(rig.sure()).containsExactly(id(1))
    assertThat(rig.suggested()).isEmpty()
  }

  // --- Dove i volti non ci sono (E-F37) ---------------------------------------------------------------------------------------------

  @Test
  fun senzaVoltiLaRegolaNonInserisceNienteENonTogliePiuNiente() = runTest {
    val rig = Rig(onlyMarco).also { it.fill() }
    rig.people.sure(marco, ids(0..9)).likely(marco, ids(10..14))
    rig.engine.evaluate(rig.id, Duration.INFINITE)
    val before = rig.rows.snapshot(rig.id)
    val applies = rig.rows.applies
    rig.people.available.value = false
    assertThat(rig.engine.evaluate(rig.id, Duration.INFINITE)).isEqualTo(SmartOutcome.Done(10, 5, 0))
    assertThat(rig.engine.reevaluate(rig.id, listOf(d1))).isEqualTo(SmartOutcome.Done(10, 5, 0))
    assertThat(rig.rows.snapshot(rig.id)).isEqualTo(before)
    assertThat(rig.rows.applies).isEqualTo(applies)
    assertThat(rig.engine.peopleUnavailable(rig.id)).isTrue()
    val preview = rig.engine.preview(onlyMarco.let { SmartRules(null, null, null, people = it.people) })
    assertThat(preview.peopleAvailable).isFalse()
    assertThat(preview.confident + preview.suggested).isEqualTo(0)
    rig.people.available.value = true
    assertThat(rig.engine.peopleUnavailable(rig.id)).isFalse()
  }

  @Test
  fun unAlbumConLePersoneNatoSenzaVoltiResteVuotoENullObjectLoFaIgualmente() = runTest {
    val rig = Rig(onlyMarco, FakePeopleMembership().also { it.available.value = false }).also { it.fill() }
    assertThat(rig.engine.evaluate(rig.id, Duration.INFINITE)).isEqualTo(SmartOutcome.Done(0, 0, 0))
    assertThat(rig.sure()).isEmpty()
    // Il motore di serie (senza porta) non conosce nessuna persona: stessa cosa.
    val plain = RulesSmartEngine(rig.rows, rig.world, rig.world, rig.world, NoSmartContentSearch, DeviceNormalizer(null), Spots.LOOKUP, rig.clocks)
    assertThat(plain.evaluate(rig.id, Duration.INFINITE)).isEqualTo(SmartOutcome.Done(0, 0, 0))
    assertThat(NoPeopleMembership.available.value).isFalse()
  }

  @Test
  fun leAltreRegoleValgonoMaDaSoleNonRiempionoLAlbum() = runTest {
    // Un album "Marco a Oslo" su un dispositivo senza volti: il periodo e il luogo non fanno entrare niente (sarebbe un altro album).
    val rules = SmartRules(ranges(d1 to d1), oslo, null, people = PeopleRule(listOf(marco), PeopleMode.ANY))
    val rig = Rig(rules, FakePeopleMembership().also { it.available.value = false })
    rig.world.put(cand(0, d1, 600, gps = Spots.north(Spots.OSLO, 3.0), placeId = Spots.OSLO_ID))
    rig.engine.evaluate(rig.id, Duration.INFINITE)
    assertThat(rig.sure()).isEmpty()
    // Senza la regola delle persone lo stesso album lo prenderebbe.
    rig.album.rules = rules.copy(people = null)
    rig.engine.evaluate(rig.id, Duration.INFINITE)
    assertThat(rig.sure()).containsExactly(id(0))
  }

  // --- Persone unite, nascoste, dimenticate (E-F23, E-F27, E-F28) -----------------------------------------------------------------

  @Test
  fun unaPersonaDimenticataSiContaETutteNonDaNiente() = runTest {
    val lookup = FakePeopleLookup().name(marco, "Marco").name(anna, "Anna").sure(marco, ids(0..9)).sure(anna, ids(5..14))
    val forgotten = giulia
    val membership = LookupPeopleMembership(lookup) { if (it == forgotten) null else it }
    // "Tutte" con una persona dimenticata: nessuna foto (non puo' essere in nessuna).
    val all = Rig(SmartRules(null, null, null, people = PeopleRule(listOf(marco, forgotten), PeopleMode.ALL)), FakePeopleMembership()).also { it.fill() }
    val engineAll = RulesSmartEngine(all.rows, all.world, all.world, all.world, NoSmartContentSearch, DeviceNormalizer(null), Spots.LOOKUP, all.clocks, InMemorySeenMarks(), membership)
    engineAll.evaluate(all.id, Duration.INFINITE)
    assertThat(all.sure()).isEmpty()
    assertThat(engineAll.peopleLost(all.id)).isEqualTo(1)
    // "Una qualunque": le altre persone contano ancora, e la pagina dice che una non si riconosce piu'.
    val any = Rig(SmartRules(null, null, null, people = PeopleRule(listOf(marco, forgotten), PeopleMode.ANY))).also { it.fill() }
    val engineAny = RulesSmartEngine(any.rows, any.world, any.world, any.world, NoSmartContentSearch, DeviceNormalizer(null), Spots.LOOKUP, any.clocks, InMemorySeenMarks(), membership)
    engineAny.evaluate(any.id, Duration.INFINITE)
    assertThat(any.sure()).containsExactlyElementsIn(ids(0..9))
    assertThat(engineAny.peopleLost(any.id)).isEqualTo(1)
    assertThat(engineAny.preview(any.album.rules).peopleLost).isEqualTo(1)
    // Una persona unita o nascosta si riconosce ancora: non conta.
    assertThat(membership.unrecognized(PeopleRule(listOf(marco, anna), PeopleMode.ALL))).isEqualTo(0)
  }

  @Test
  fun laPortaDellaRicercaFaDaPortaDegliAlbum() = runTest {
    val lookup = FakePeopleLookup().sure(marco, ids(0..9)).likely(marco, ids(10..12)).sure(anna, ids(5..14))
    val membership = LookupPeopleMembership(lookup)
    val rule = PeopleRule(listOf(marco, anna), PeopleMode.ALL)
    val classes = membership.classify(rule, ids(0..39))
    assertThat(classes.filterValues { it == dev.pampa.mycloudgallery.core.albums.contract.PeopleClass.SURE }.keys).containsExactlyElementsIn(ids(5..9))
    assertThat(classes.filterValues { it == dev.pampa.mycloudgallery.core.albums.contract.PeopleClass.LIKELY }.keys).containsExactlyElementsIn(ids(10..12))
    lookup.available.value = false
    assertThat(membership.available.value).isFalse()
    assertThat(membership.classify(rule, ids(0..39))).isEmpty()
  }

  // --- Nascosti mai (D28, E-F38) --------------------------------------------------------------------------------------------------

  @Test
  fun unNascostoNonEntraMaiNemmenoSeLaPortaLoDaPerErrore() = runTest {
    val rig = Rig(onlyMarco).also { it.fill() }
    // Il 3 e' nascosto; il 5 e il 6 stanno in un gruppo e il 6 e' nascosto: il gruppo intero non si vede. La porta li da' lo stesso a Marco.
    rig.world.groups += setOf(id(5), id(6))
    rig.world.hide(listOf(id(3), id(6)))
    rig.people.sure(marco, ids(0..20))
    rig.engine.evaluate(rig.id, Duration.INFINITE)
    assertThat(rig.sure() + rig.suggested().keys).containsNoneOf(id(3), id(5), id(6))
    assertThat(rig.sure()).containsAtLeastElementsIn(ids(0..2) + ids(7..20))
    // Un media nascosto un attimo fa non diventa un elemento: il filtro finale guarda ancora.
    val late = Rig(onlyMarco).also { it.fill() }
    late.people.sure(marco, ids(0..9))
    late.world.hide(id(4))
    late.engine.evaluate(late.id, Duration.INFINITE)
    assertThat(late.sure()).doesNotContain(id(4))
    // Anche l'anteprima.
    assertThat(late.engine.preview(onlyMarco.let { SmartRules(null, null, null, people = it.people) }).sample).doesNotContain(id(4))
  }

  @Test
  fun lAnteprimaConLePersone() = runTest {
    val rig = Rig(onlyMarco).also { it.fill() }
    rig.people.sure(marco, ids(0..9)).likely(marco, ids(10..14))
    val preview = rig.engine.preview(SmartRules(null, null, null, people = PeopleRule(listOf(marco), PeopleMode.ANY)))
    assertThat(preview.confident).isEqualTo(10)
    assertThat(preview.suggested).isEqualTo(5)
    assertThat(preview.peopleAvailable).isTrue()
    assertThat(preview.sample).isNotEmpty()
    assertThat(rig.rows.applies).isEqualTo(0)
  }

  @Test
  fun leClassiCambiatePortanoAlRicalcoloEQuestoAlbumNonScrive_SeNienteCambia() = runTest {
    val rig = Rig(onlyMarco).also { it.fill() }
    rig.people.sure(marco, ids(0..9))
    rig.engine.evaluate(rig.id, Duration.INFINITE)
    val applies = rig.rows.applies
    rig.engine.evaluate(rig.id, Duration.INFINITE)
    assertThat(rig.rows.applies).isEqualTo(applies)
    // Marco compare in altre tre foto (un'assegnazione nuova): la rivalutazione intera le prende.
    rig.people.sure(marco, ids(10..12))
    rig.engine.evaluate(rig.id, Duration.INFINITE)
    assertThat(rig.sure()).containsExactlyElementsIn(ids(0..12))
  }
}
