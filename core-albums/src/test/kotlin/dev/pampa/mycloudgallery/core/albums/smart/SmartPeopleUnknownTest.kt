package dev.pampa.mycloudgallery.core.albums.smart

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.albums.contract.PeopleClass
import dev.pampa.mycloudgallery.core.albums.contract.PeopleMembership
import dev.pampa.mycloudgallery.core.albums.contract.PeopleRule
import dev.pampa.mycloudgallery.core.albums.contract.SmartOutcome
import dev.pampa.mycloudgallery.core.albums.contract.SmartRules
import dev.pampa.mycloudgallery.core.albums.testing.FakeAlbumClocks
import dev.pampa.mycloudgallery.core.albums.testing.FakePeopleMembership
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.PeopleMode
import dev.pampa.mycloudgallery.core.model.PersonId
import dev.pampa.mycloudgallery.core.search.contract.PeopleDictionary
import dev.pampa.mycloudgallery.core.search.contract.PeopleLookup
import dev.pampa.mycloudgallery.core.search.contract.PersonOption
import kotlin.time.Duration
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Correzioni dopo le revisioni della Fase 8, pacchetto D, **R10**: negli album smart con persone **"non so" non vale "nessuno"**. Se i volti non si leggono proprio
 * adesso (un Keystore che non risponde un momento, il dispositivo escluso a meta' valutazione) il motore non scrive niente e le righe automatiche restano; prima
 * una risposta vuota toglieva le righe dell'album (dati personali, sincronizzati sugli altri dispositivi), che tornavano al giro dopo. Solo id inventati.
 */
class SmartPeopleUnknownTest {
  private val marco = PersonId.fromBytes(ByteArray(PersonId.BYTES) { 1 })
  private val d1 = day(2026, 3, 10)

  private class Rig(rules: SmartRules, val people: PeopleMembership) {
    val world = MemoryWorld()
    val rows = InMemorySmartRows()
    val clocks = FakeAlbumClocks()
    val id = albumId(1)
    val album = rows.create(id, rules)
    val engine = RulesSmartEngine(rows, world, world, world, NoSmartContentSearch, DeviceNormalizer("Backup/questo-telefono"), Spots.LOOKUP, clocks, InMemorySeenMarks(), people)
  }

  private val onlyMarco get() = SmartRules(null, null, null, people = PeopleRule(listOf(marco), PeopleMode.ANY))

  private fun ids(range: IntRange): List<MediaId> = range.map { id(it) }

  private fun Rig.fill(count: Int = 40) {
    for (n in 0 until count) world.put(cand(n, d1, 600 + n))
  }

  @Test
  fun unArchivioCheNonSiLeggeAdessoNonToglieLeRigheAutomatiche() = runTest {
    val fake = FakePeopleMembership().sure(marco, ids(0..9)).likely(marco, ids(10..14))
    val rig = Rig(onlyMarco, fake).also { it.fill() }
    assertThat(rig.engine.evaluate(rig.id, Duration.INFINITE)).isEqualTo(SmartOutcome.Done(10, 5, 0))
    val before = rig.rows.snapshot(rig.id)
    val applies = rig.rows.applies
    // Il Keystore non risponde: la porta lancia, il motore non scrive (prima: una risposta vuota e le 15 righe automatiche sparivano).
    fake.failing = true
    assertThrows(IllegalStateException::class.java) { runBlockingEval(rig) }
    assertThrows(IllegalStateException::class.java) { runBlockingReeval(rig) }
    assertThat(rig.rows.snapshot(rig.id)).isEqualTo(before)
    assertThat(rig.rows.applies).isEqualTo(applies)
    // Torna a rispondere: il giro dopo vale come prima, le righe non sono mai sparite.
    fake.failing = false
    assertThat(rig.engine.evaluate(rig.id, Duration.INFINITE)).isEqualTo(SmartOutcome.Done(10, 5, 0))
    assertThat(rig.rows.snapshot(rig.id)).isEqualTo(before)
  }

  private fun runBlockingEval(rig: Rig) = kotlinx.coroutines.runBlocking { rig.engine.evaluate(rig.id, Duration.INFINITE) }

  private fun runBlockingReeval(rig: Rig) = kotlinx.coroutines.runBlocking { rig.engine.reevaluate(rig.id, listOf(d1)) }

  /** Una porta che all'inizio c'e' (la disponibilita' si chiede solo li') e a meta' valutazione sparisce rispondendo "nessuno": e' "non so", il motore non scrive. */
  private class VanishingMembership(private val inner: FakePeopleMembership) : PeopleMembership {
    override val available: MutableStateFlow<Boolean> = MutableStateFlow(true)

    override suspend fun classify(rule: PeopleRule, candidates: Collection<MediaId>): Map<MediaId, PeopleClass> {
      // Come un archivio che si chiude mentre si classifica e risponde "vuoto" senza lanciare.
      available.value = false
      return emptyMap()
    }

    override suspend fun unrecognized(rule: PeopleRule): Int = inner.unrecognized(rule)
  }

  @Test
  fun seIVoltiSpaisconoAMetaValutazioneIlMotoreNonScrive() = runTest {
    val fake = FakePeopleMembership().sure(marco, ids(0..9))
    val seeded = Rig(onlyMarco, fake).also { it.fill() }
    seeded.engine.evaluate(seeded.id, Duration.INFINITE)
    val rows = seeded.rows
    val before = rows.snapshot(seeded.id)
    // Lo stesso album, la stessa Libreria, ma la porta sparisce durante la classificazione.
    val vanishing = VanishingMembership(fake)
    val engine = RulesSmartEngine(rows, seeded.world, seeded.world, seeded.world, NoSmartContentSearch, DeviceNormalizer("Backup/questo-telefono"), Spots.LOOKUP, seeded.clocks, InMemorySeenMarks(), vanishing)
    val applies = rows.applies
    assertThat(engine.evaluate(seeded.id, Duration.INFINITE)).isEqualTo(SmartOutcome.Done(confident = 10, suggested = 0, newSinceSeen = 0))
    assertThat(rows.snapshot(seeded.id)).isEqualTo(before)
    assertThat(rows.applies).isEqualTo(applies)
  }

  @Test
  fun lAnteprimaConIVoltiIllegibiliDiceCheLePersoneNonCiSonoEnonLancia() = runTest {
    val fake = FakePeopleMembership().sure(marco, ids(0..9))
    val rig = Rig(onlyMarco, fake).also { it.fill() }
    fake.failing = true
    val preview = rig.engine.preview(onlyMarco)
    assertThat(preview.peopleAvailable).isFalse()
    assertThat(preview.confident + preview.suggested).isEqualTo(0)
  }

  /** La porta della ricerca: se le celle non si leggono la classificazione degli album lancia (prima una porta che sbagliava dava "nessuno" a chi non sapeva). */
  @Test
  fun laPortaDellaRicercaPropagaIlNonSoAgliAlbum() = runTest {
    val broken = object : PeopleLookup {
      override val available: StateFlow<Boolean> = MutableStateFlow(true)

      override suspend fun dictionary(): PeopleDictionary = PeopleDictionary.EMPTY

      override suspend fun cells(option: PersonOption, includeLikely: Boolean): Set<MediaId> = throw IllegalStateException("L'archivio non si apre.")
    }
    val membership = LookupPeopleMembership(broken)
    assertThrows(IllegalStateException::class.java) {
      kotlinx.coroutines.runBlocking { membership.classify(PeopleRule(listOf(marco), PeopleMode.ANY), ids(0..3)) }
    }
    // Senza candidati non c'e' niente da sapere.
    assertThat(membership.classify(PeopleRule(listOf(marco), PeopleMode.ANY), emptyList())).isEmpty()
  }
}
