package dev.pampa.mycloudgallery.core.faces.erase

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.faces.compute.FacesSignals
import dev.pampa.mycloudgallery.core.faces.contract.FacesDiag
import dev.pampa.mycloudgallery.core.faces.contract.EraseStart
import dev.pampa.mycloudgallery.core.faces.contract.PeopleAlbumsChoice
import dev.pampa.mycloudgallery.core.faces.contract.PeopleEdit
import dev.pampa.mycloudgallery.core.faces.contract.PeopleRecord
import dev.pampa.mycloudgallery.core.faces.switch.LocalEraseWhy
import dev.pampa.mycloudgallery.core.faces.testing.FakeFacesSwitch
import dev.pampa.mycloudgallery.core.faces.testing.FakeSearchHistoryPurge
import dev.pampa.mycloudgallery.core.faces.testing.InMemoryFacesArchive
import dev.pampa.mycloudgallery.core.model.FaceGeneration
import dev.pampa.mycloudgallery.core.model.PersonId
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Correzioni dopo le revisioni della Fase 8, pacchetto D, **R2** sugli altri dispositivi (la cancellazione dal gancio di D30, l'esclusione, il cambio di generazione):
 *  - i nomi si leggono **fino a tre volte** (un Keystore che non risponde un attimo non fa perdere i nomi: poi la chiave si distrugge e non si leggono piu');
 *  - si tolgono dalla cronologia per nome **e per id** della persona;
 *  - se i nomi non si leggono lo stesso si toglie ogni ricerca nata da un chip "Persona", e la Diagnostica dice che la cronologia potrebbe tenere un nome
 *    (`FACES_HISTORY_KEPT`, L190 esteso); lo stesso se la cronologia non risponde.
 * La cancellazione dei dati non aspetta mai la cronologia. **Solo nomi inventati.**
 */
class CorrezioniDEraseTest {

  private val generation = FaceGeneration.fromBytes(ByteArray(16) { 6 })
  private val marco = PersonId.fromBytes(ByteArray(PersonId.BYTES) { 1 })
  private val anna = PersonId.fromBytes(ByteArray(PersonId.BYTES) { 2 })
  private val author = "0".repeat(26)

  private class Rig(generation: FaceGeneration, records: List<PeopleRecord>) {
    val data = WorldLocalData()
    val history = FakeSearchHistoryPurge()
    val signals = mutableListOf<Pair<String, Int>>()
    val fence = FacesEraseFence(FakeFacesSwitch())
    val erase = FacesLocalErase(data, fence, history = history, signals = FacesSignals { code, count -> signals += code to count })

    init {
      val gen = WorldLocalData.Gen(InMemoryFacesArchive(generation))
      runBlocking { gen.archive.addPeople(records) }
      data.gens[generation] = gen
    }
  }

  private fun records() = listOf(
    PeopleRecord(PeopleEdit.Name(marco, "Marco Quarzo"), 1L, author),
    PeopleRecord(PeopleEdit.Name(anna, "Анна"), 1L, author),
    PeopleRecord(PeopleEdit.Hidden(anna, true), 1L, author),
  )

  @Test
  fun iNomiSiLeggonoAnchePiuVoltePrimaDiRinunciareESiTolgonoPerNomeEPerId() = runBlocking<Unit> {
    val r = Rig(generation, records())
    // Il Keystore non risponde le prime due volte: prima si perdevano i nomi (una lettura sola), ora al terzo tentativo ci sono.
    r.data.failPeople = 2
    assertThat(r.erase.erase(generation, LocalEraseWhy.ACCOUNT_OFF)).isTrue()
    assertThat(r.history.namesAsked).containsExactly("Marco Quarzo", "Анна")
    assertThat(r.history.personsAsked).containsExactly(marco, anna)
    assertThat(r.history.personSearchesPurged).isEqualTo(0)
    assertThat(r.signals).isEmpty()
    // I dati ci sono andati: la cronologia non li tiene fermi.
    assertThat(r.data.has(generation)).isFalse()
  }

  @Test
  fun senzaINomiSiTolgonoLeRicercheConUnaPersonaELaDiagnosticaLoDice() = runBlocking<Unit> {
    val r = Rig(generation, records())
    r.data.failPeople = 3
    assertThat(r.erase.erase(generation, LocalEraseWhy.ACCOUNT_OFF)).isTrue()
    assertThat(r.history.namesAsked).isEmpty()
    assertThat(r.history.personSearchesPurged).isEqualTo(1)
    assertThat(r.signals).containsExactly(FacesDiag.FACES_HISTORY_KEPT to 1)
    assertThat(r.data.has(generation)).isFalse()
  }

  @Test
  fun seLaCronologiaNonRispondeLaDiagnosticaLoDiceEIDatiSiTolgonoLoStesso() = runBlocking<Unit> {
    val r = Rig(generation, records())
    r.history.failing = true
    assertThat(r.erase.erase(generation, LocalEraseWhy.ACCOUNT_OFF)).isTrue()
    assertThat(r.signals).containsExactly(FacesDiag.FACES_HISTORY_KEPT to 1)
    assertThat(r.data.has(generation)).isFalse()
  }

  @Test
  fun senzaVociPeopleNonServeLaCronologiaNeLaDiagnostica() = runBlocking<Unit> {
    val r = Rig(generation, emptyList())
    assertThat(r.erase.erase(generation, LocalEraseWhy.ACCOUNT_OFF)).isTrue()
    assertThat(r.history.namesAsked).isEmpty()
    assertThat(r.history.personSearchesPurged).isEqualTo(0)
    assertThat(r.signals).isEmpty()
  }

  @Test
  fun lePersoneDelleVociSonoQuelleCheLeVociNominano() {
    val a = PersonId.fromBytes(ByteArray(PersonId.BYTES) { 10 })
    val b = PersonId.fromBytes(ByteArray(PersonId.BYTES) { 11 })
    val c = PersonId.fromBytes(ByteArray(PersonId.BYTES) { 12 })
    val persons = EraseRules.persons(
      listOf(
        PeopleRecord(PeopleEdit.Name(a, null), 1L, author),
        PeopleRecord(PeopleEdit.Alias(b, a), 1L, author),
        PeopleRecord(PeopleEdit.Born(c, b), 1L, author),
        PeopleRecord(PeopleEdit.Forget(c), 1L, author),
      ),
    )
    assertThat(persons).containsExactly(a, b, c)
    // Mai un nome: `names` resta solo dei valori di `name`.
    assertThat(EraseRules.names(listOf(PeopleRecord(PeopleEdit.Name(a, "Marco Quarzo"), 1L, author), PeopleRecord(PeopleEdit.Name(b, null), 1L, author))))
      .containsExactly("Marco Quarzo")
  }

  @Test
  fun ilDiarioDiDisattivaECancellaTogliePerNomeEPerIdLeRicercheDelleVociNominate() = runTest {
    val world = EraseWorld()
    try {
      val invariants = EraseInvariants(world)
      val (a, _, _) = world.activeFamily("A", "B", "C", invariants = invariants)
      val g = a.generation()
      requireNotNull(a.local.archive(g, create = false)).addPeople(records())
      assertThat(a.confirmErase(PeopleAlbumsChoice.KEEP_PHOTOS)).isEqualTo(EraseStart.Started)
      // Il passo 4 del diario (e poi la cancellazione locale, che li ripete): i nomi (anche quelli vecchi) e gli id delle persone.
      assertThat(a.history.namesAsked).containsAtLeast("Marco Quarzo", "Анна")
      assertThat(a.history.personsAsked).containsAtLeast(marco, anna)
    } finally {
      world.close()
    }
  }
}
