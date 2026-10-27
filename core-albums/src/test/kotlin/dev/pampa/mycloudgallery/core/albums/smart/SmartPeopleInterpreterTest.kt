package dev.pampa.mycloudgallery.core.albums.smart

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.albums.contract.PeopleRule
import dev.pampa.mycloudgallery.core.albums.contract.RuleProposal
import dev.pampa.mycloudgallery.core.model.PeopleMode
import dev.pampa.mycloudgallery.core.model.PersonId
import dev.pampa.mycloudgallery.core.search.parse.ItalianQueryParser
import dev.pampa.mycloudgallery.core.search.testing.FakePeopleLookup
import java.time.LocalDate
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Fase 8 (P10, D27, §2.7 "Creazione"): il nome dell'album diventa anche la regola delle **persone**, con la stessa porta e lo stesso analizzatore di Cerca. "Marco 2025" =
 * Marco nel 2025; "Marco e Anna" = tutte e due; "Marco o Anna" = una qualunque; senza volti (porta spenta) il nome resta testo e non nasce nessuna regola delle persone;
 * un nome che e' anche un luogo lo decide chi ha piu' media visibili (E-F36). Solo nomi inventati.
 */
class SmartPeopleInterpreterTest {
  private val today = LocalDate.of(2026, 12, 20)
  private val marco = PersonId.fromBytes(ByteArray(PersonId.BYTES) { 1 })
  private val anna = PersonId.fromBytes(ByteArray(PersonId.BYTES) { 2 })
  private val giulia = PersonId.fromBytes(ByteArray(PersonId.BYTES) { 3 })

  /** Un mondo con 12 foto nostre, tutte nel 2025, nessun luogo: le persone hanno le loro celle. */
  private fun world(): MemoryWorld = MemoryWorld(emptyList(), Spots.PLACES).also { w ->
    for (n in 0 until 12) w.put(cand(n, day(2025, 5, 1 + n), 600 + n))
    w.publish()
  }

  private fun people(): FakePeopleLookup = FakePeopleLookup()
    .name(marco, "Marco").sure(marco, (0..7).map { id(it) })
    .name(anna, "Anna").sure(anna, (4..9).map { id(it) })
    .name(giulia, "Giulia").sure(giulia, listOf(id(10)))

  private suspend fun propose(title: String, lookup: FakePeopleLookup? = people(), world: MemoryWorld = world()): RuleProposal {
    val interpreter = if (lookup == null) {
      RuleSmartNameInterpreter(ItalianQueryParser(), world, world, world.candidates(), world.placeLookup)
    } else {
      RuleSmartNameInterpreter(ItalianQueryParser(), world, world, world.candidates(), world.placeLookup, people = lookup)
    }
    return interpreter.propose(title, today)
  }

  @Test
  fun marcoDuemilaVentiCinqueDiventaMarcoNelPeriodo() = runTest {
    val rules = propose("Marco 2025").rules
    assertThat(rules.people).isEqualTo(PeopleRule(listOf(marco), PeopleMode.ANY))
    assertThat(rules.period).isNotNull()
    assertThat(rules.text).isNull()
  }

  @Test
  fun marcoEAnnaDiventaTutteEDue() = runTest {
    val rules = propose("Marco e Anna").rules
    assertThat(rules.people!!.mode).isEqualTo(PeopleMode.ALL)
    assertThat(rules.people!!.ids).containsExactly(marco, anna)
    assertThat(rules.text).isNull()
  }

  @Test
  fun marcoOAnnaDiventaUnaQualunque() = runTest {
    val rules = propose("Marco o Anna").rules
    assertThat(rules.people!!.mode).isEqualTo(PeopleMode.ANY)
    assertThat(rules.people!!.ids).containsExactly(marco, anna)
  }

  @Test
  fun treNomiInEFannoTutteETre() = runTest {
    val rules = propose("Marco, Anna e Giulia").rules
    assertThat(rules.people!!.mode).isEqualTo(PeopleMode.ALL)
    assertThat(rules.people!!.ids).containsExactly(marco, anna, giulia)
  }

  @Test
  fun unNomeAccantoAlRestoDelTitoloLasciaIlTesto() = runTest {
    val rules = propose("Marco al mare").rules
    assertThat(rules.people).isEqualTo(PeopleRule(listOf(marco), PeopleMode.ANY))
    assertThat(rules.text).isNotNull()
    assertThat(rules.text).doesNotContain("Marco")
  }

  @Test
  fun senzaVoltiIlNomeRestaTestoENonNasceLaRegola() = runTest {
    val off = people().also { it.available.value = false }
    for (lookup in listOf(off, null)) {
      val rules = propose("Marco 2025", lookup).rules
      assertThat(rules.people).isNull()
      assertThat(rules.text).isNotNull()
      assertThat(rules.text!!.lowercase()).contains("marco")
      assertThat(rules.period).isNotNull()
    }
  }

  @Test
  fun unNomeSconosciutoNonDiventaUnaRegola() = runTest {
    val rules = propose("Ludovica 2025").rules
    assertThat(rules.people).isNull()
    assertThat(rules.text).isNotNull()
  }

  @Test
  fun unaPersonaSenzaCelleSicureNonSiRiconosce() = runTest {
    val lonely = FakePeopleLookup().name(marco, "Marco").likely(marco, listOf(id(0)))
    val rules = propose("Marco 2025", lonely).rules
    assertThat(rules.people).isNull()
  }

  @Test
  fun unNomeCheELuogoLoDecideChiHaPiuMediaVisibili() = runTest {
    // "Oslo" e' una citta' con 8 foto e una persona con 3: vince il luogo; con 3 foto di citta' e 9 della persona vince la persona.
    val city = MemoryWorld(emptyList(), Spots.PLACES).also { w ->
      for (n in 0 until 8) w.put(cand(n, day(2026, 3, 1 + n), 600, gps = Spots.north(Spots.OSLO, 1.0 + n), placeId = Spots.OSLO_ID))
      w.publish()
    }
    val few = FakePeopleLookup().name(giulia, "Oslo").sure(giulia, (0..2).map { id(it) })
    val placeWins = propose("Oslo", few, city).rules
    assertThat(placeWins.place).isNotNull()
    assertThat(placeWins.people).isNull()

    val town = MemoryWorld(emptyList(), Spots.PLACES).also { w ->
      for (n in 0 until 3) w.put(cand(n, day(2026, 3, 1 + n), 600, gps = Spots.north(Spots.OSLO, 1.0 + n), placeId = Spots.OSLO_ID))
      w.publish()
    }
    val many = FakePeopleLookup().name(giulia, "Oslo").sure(giulia, (0..2).map { id(it) } + (3..8).map { id(100 + it) })
    val personWins = propose("Oslo", many, town).rules
    assertThat(personWins.people).isEqualTo(PeopleRule(listOf(giulia), PeopleMode.ANY))
    assertThat(personWins.place).isNull()
  }

  @Test
  fun laPortaCheSbagliaNonRompeIlTitolo() = runTest {
    val broken = object : dev.pampa.mycloudgallery.core.search.contract.PeopleLookup by people() {
      override suspend fun dictionary(): dev.pampa.mycloudgallery.core.search.contract.PeopleDictionary = throw IllegalStateException("boom")
    }
    val w = world()
    val interpreter = RuleSmartNameInterpreter(ItalianQueryParser(), w, w, w.candidates(), w.placeLookup, people = broken)
    val rules = interpreter.propose("Marco 2025", today).rules
    assertThat(rules.people).isNull()
    assertThat(rules.period).isNotNull()
  }
}
