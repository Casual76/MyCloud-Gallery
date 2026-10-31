package dev.pampa.mycloudgallery.core.memories.settings.less

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.memories.contract.Memories
import dev.pampa.mycloudgallery.core.memories.contract.MemoryKind
import dev.pampa.mycloudgallery.core.memories.contract.RefreshReport
import dev.pampa.mycloudgallery.core.memories.plan.people.PeopleRig
import dev.pampa.mycloudgallery.core.memories.testing.FakePeopleSwitch
import dev.pampa.mycloudgallery.core.model.PersonId
import dev.pampa.mycloudgallery.core.personal.contract.MemoriesRecords
import dev.pampa.mycloudgallery.core.personal.contract.MemoryLessLevel
import dev.pampa.mycloudgallery.core.personal.contract.PersonalValue
import dev.pampa.mycloudgallery.core.trust.crypto.TrustIds
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test

/**
 * `PeopleLessJanitor`, `AliasFollowingSettings` e le voci `memories_less` (docs/fase9-progetto.md §2.3, D11, D37, E-MEM10, P8): Persone spenta o
 * in cancellazione mette le lapidi su **tutte** le scelte e toglie i ricordi `PERSON`; un'unione porta la scelta sull'id nuovo; nessuna
 * cancellazione per un'assenza che puo' essere solo un ritardo. Sempre con il motore vero e le scelte su un registro condiviso.
 */
class PeopleLessJanitorTest {

  private val author = TrustIds.random()
  private val rigs = ArrayList<PeopleRig>()
  private val scopes = ArrayList<CoroutineScope>()

  @After
  fun chiude() {
    rigs.forEach { it.close() }
    scopes.forEach { it.cancel() }
  }

  private fun rig(): PeopleRig = PeopleRig().also { rigs += it }

  /** I livelli vivi delle scelte sulle persone nel registro (le lapidi non ci sono). */
  private fun live(rig: PeopleRig): Map<PersonId, MemoryLessLevel> = rig.raw.current().people

  private fun tombstones(rig: PeopleRig, person: PersonId): Boolean =
    rig.register.entries(author)[MemoriesRecords.lessKey(person)]?.value == PersonalValue.Removed

  /** Una persona con un ricordo `PERSON` sullo scaffale e due persone con "Mostra meno" e "Mai". */
  private suspend fun worldWithPersonMemory(rig: PeopleRig): Triple<PersonId, PersonId, PersonId> {
    val (anna) = rig.person("Anna", PeopleRig.DAYS_2025)
    val (marco) = rig.person("Marco", listOf(rig.clock.today().minusDays(400)), perDay = 3)
    val (sara) = rig.person("Sara", listOf(rig.clock.today().minusDays(500)), perDay = 3)
    rig.refresh()
    assertThat(rig.personMemories()).hasSize(1)
    rig.settings.setLess(marco, MemoryLessLevel.LESS)
    rig.settings.setLess(sara, MemoryLessLevel.NEVER)
    return Triple(anna, marco, sara)
  }

  /** D11, D37, E-MEM10: Persone spenta o in cancellazione = lapidi su tutte le scelte, ricordi `PERSON` fuori dallo scaffale. */
  @Test
  fun conPersoneSpentaOInCancellazioneLeSceltaDiventanoLapidi() = runTest {
    for (phase in listOf(PeoplePhase.OFF, PeoplePhase.ERASING)) {
      val rig = rig()
      val (_, marco, sara) = worldWithPersonMemory(rig)
      // L'altro dispositivo conosce una persona che questo no: la lapide la toglie lo stesso.
      val stranger = PersonId.random()
      rig.register.inject(MemoriesRecords.lessKey(stranger), MemoriesRecords.lessValue(MemoryLessLevel.LESS))
      rig.raw.pull()
      assertThat(live(rig).keys).containsExactly(marco, sara, stranger)

      rig.switch.set(phase)
      // Con ERASING l'adattatore sotto e' ancora in ritardo (disponibile): ci pensa il cancello di Persone; con OFF e' gia' falso.
      if (phase == PeoplePhase.OFF) rig.people.available.value = false
      val report = rig.janitor.sweep()

      assertWithMessage("$phase").that(report.dropped).isEqualTo(3)
      assertThat(live(rig)).isEmpty()
      for (person in listOf(marco, sara, stranger)) assertThat(tombstones(rig, person)).isTrue()
      assertThat(rig.personMemories()).isEmpty()
      assertThat(rig.engine.shelf.value.items.map { it.kind }).doesNotContain(MemoryKind.PERSON)
      // Un secondo giro non scrive niente (nessuna lapide a vuoto).
      val writes = rig.register.writes()
      assertThat(rig.janitor.sweep().dropped).isEqualTo(0)
      assertThat(rig.register.writes()).isEqualTo(writes)
    }
  }

  /** Le lapidi viaggiano: un altro dispositivo dello stesso account non vede piu' le scelte e non le rimette. */
  @Test
  fun leLapidiRestanoEVannoAgliAltriDispositivi() = runTest {
    val rig = rig()
    val (_, marco, _) = worldWithPersonMemory(rig)
    val tablet = PeopleRig(register = rig.register).also { rigs += it }
    tablet.raw.pull()
    assertThat(live(tablet)).containsKey(marco)
    rig.switch.set(PeoplePhase.ERASING)
    rig.janitor.sweep()
    tablet.raw.pull()
    assertThat(live(tablet)).isEmpty()
    // Un dispositivo rimasto indietro che riscrive la sua vecchia scelta non vince sulla lapide piu' recente? Vince l'ultima scrittura: qui
    // nessuno riscrive, e la lapide resta quella che e'.
    assertThat(tombstones(tablet, marco)).isTrue()
  }

  /**
   * Senza una porta legata, o con lo stato ancora ignoto, il janitor non cancella niente (neppure con `available` falso) **e nemmeno i ricordi di persone** (MO-1,
   * 31/10): "Persone non ancora montata" non e' "Persone spenta" e i ricordi non si potano finche' lo stato non e' noto. Prima il test diceva il contrario (E-MEM10 letto
   * come "non disponibile = fuori"): era l'errore, non la regola.
   */
  @Test
  fun conLoStatoIgnotoNonCancellaNiente() = runTest {
    val rig = PeopleRig(switch = FakePeopleSwitch(PeoplePhase.UNKNOWN)).also { rigs += it }
    val (_, marco, sara) = worldWithPersonMemory(rig)
    rig.people.available.value = false
    val writes = rig.register.writes()
    val shelfBefore = rig.engine.current()
    val report = rig.janitor.sweep()
    assertThat(report.dropped).isEqualTo(0)
    assertThat(rig.register.writes()).isEqualTo(writes)
    assertThat(live(rig).keys).containsExactly(marco, sara)
    // I ricordi di persone restano, e lo scaffale non si e' riscritto (nessuna pulizia a vuoto): un riavvio a freddo prima di Persone non li butta.
    assertThat(rig.personMemories()).hasSize(1)
    assertThat(rig.engine.current()).isEqualTo(shelfBefore)
    // Anche il giro che li rigenera (la notte, Raccolte) li lascia dov'erano.
    rig.refresh()
    assertThat(rig.personMemories()).hasSize(1)
    // E il null-object non fa niente di suo.
    val none = PeopleLessJanitor(rig.settings, rig.gated, NoPeopleSwitch, rig.engine)
    none.sweep()
    assertThat(live(rig).keys).containsExactly(marco, sara)
    assertThat(rig.personMemories()).hasSize(1)
  }

  /**
   * MO-1, il resto: con Persone **spenta davvero** (OFF o ERASING) i ricordi di persone escono; con lo stato ignoto escono solo per una scelta esplicita
   * ("Ricordi con le persone" spento): non per cio' che dipende da Persone.
   */
  @Test
  fun iRicordiDiPersoneEsconoSoloConPersoneSpentaOUnaSceltaEsplicita() = runTest {
    val unknown = PeopleRig(switch = FakePeopleSwitch(PeoplePhase.UNKNOWN)).also { rigs += it }
    worldWithPersonMemory(unknown)
    unknown.people.available.value = false
    unknown.engine.prune()
    assertThat(unknown.personMemories()).hasSize(1)
    // Una scelta esplicita vale anche con lo stato ignoto.
    unknown.engine.setPeopleMemories(false)
    assertThat(unknown.personMemories()).isEmpty()

    // Lo stato diventa noto e spento: escono.
    val off = PeopleRig(switch = FakePeopleSwitch(PeoplePhase.UNKNOWN)).also { rigs += it }
    worldWithPersonMemory(off)
    off.people.available.value = false
    off.engine.prune()
    assertThat(off.personMemories()).hasSize(1)
    off.switch.set(PeoplePhase.OFF)
    off.engine.prune()
    assertThat(off.personMemories()).isEmpty()

    // Con lo stato noto e acceso ("ON") ma Persone non disponibile qui, come prima: escono (E-MEM10).
    val on = rig()
    worldWithPersonMemory(on)
    on.people.available.value = false
    on.engine.prune()
    assertThat(on.personMemories()).isEmpty()
  }

  /** E-MEM10: una persona unita a un'altra porta la scelta sull'id nuovo (la piu' forte), la vecchia diventa una lapide. */
  @Test
  fun unaPersonaUnitaPortaLaSceltaSulNuovoId() = runTest {
    val cases = listOf(
      Triple(MemoryLessLevel.LESS, null, MemoryLessLevel.LESS),
      Triple(MemoryLessLevel.NEVER, MemoryLessLevel.LESS, MemoryLessLevel.NEVER),
      Triple(MemoryLessLevel.LESS, MemoryLessLevel.NEVER, MemoryLessLevel.NEVER),
      Triple(MemoryLessLevel.LESS, MemoryLessLevel.LESS, MemoryLessLevel.LESS),
    )
    for ((oldLevel, newLevel, expected) in cases) {
      val rig = rig()
      val (old) = rig.person("Vecchia", listOf(rig.clock.today().minusDays(300)), perDay = 3)
      val (new) = rig.person("Nuova", listOf(rig.clock.today().minusDays(310)), perDay = 3)
      rig.settings.setLess(old, oldLevel)
      newLevel?.let { rig.settings.setLess(new, it) }
      rig.people.merge(old, new)
      val report = rig.janitor.sweep()
      assertThat(report.moved).isEqualTo(1)
      assertWithMessage("$oldLevel + $newLevel").that(live(rig)).containsExactly(new, expected)
      assertThat(tombstones(rig, old)).isTrue()
      assertThat(rig.janitor.sweep().moved).isEqualTo(0)
    }
  }

  /** Scrivere "Mostra meno" per una persona unita va direttamente sull'id di adesso e lascia la lapide dell'id vecchio. */
  @Test
  fun scrivereMostraMenoSegueLAlias() = runTest {
    val rig = rig()
    val (old) = rig.person("Vecchia", listOf(rig.clock.today().minusDays(300)), perDay = 3)
    val (new) = rig.person("Nuova", listOf(rig.clock.today().minusDays(310)), perDay = 3)
    rig.settings.setLess(old, MemoryLessLevel.LESS)
    rig.people.merge(old, new)
    rig.settings.setLess(old, MemoryLessLevel.NEVER)
    assertThat(live(rig)).containsExactly(new, MemoryLessLevel.NEVER)
    assertThat(tombstones(rig, old)).isTrue()
    // Togliere una scelta e' sempre sull'id che l'elenco mostra.
    rig.settings.setLess(new, null)
    assertThat(live(rig)).isEmpty()
    // Con Persone non disponibile qui non si sa chi e' chi: l'id si scrive com'e'.
    rig.people.available.value = false
    rig.settings.setLess(old, MemoryLessLevel.LESS)
    assertThat(live(rig)).containsExactly(old, MemoryLessLevel.LESS)
  }

  /** Il ricordo `PERSON` di una persona nascosta esce, ma la sua scelta non diventa una lapide (potrebbe essere solo un ritardo). */
  @Test
  fun unaPersonaNascostaFaUscireIlRicordoMaNonCancellaLaScelta() = runTest {
    val rig = rig()
    val (anna, marco, sara) = worldWithPersonMemory(rig)
    rig.people.hide(anna)
    rig.people.hide(sara)
    val writes = rig.register.writes()
    val report = rig.janitor.sweep()
    assertThat(report.dropped).isEqualTo(0)
    assertThat(report.moved).isEqualTo(0)
    assertThat(rig.register.writes()).isEqualTo(writes)
    assertThat(live(rig).keys).containsExactly(marco, sara)
    assertThat(rig.personMemories()).isEmpty()
  }

  /** `watch` segue lo stato e le scelte da solo. */
  @Test
  fun watchSegueIlCambioDiStato() = runTest {
    val rig = rig()
    val (_, marco, _) = worldWithPersonMemory(rig)
    val scope = CoroutineScope(Dispatchers.Unconfined + Job()).also { scopes += it }
    rig.janitor.watch(scope)
    assertThat(live(rig)).containsKey(marco)
    rig.switch.set(PeoplePhase.OFF)
    assertThat(live(rig)).isEmpty()
    assertThat(rig.personMemories()).isEmpty()
    // Una scelta fatta mentre Persone e' spenta (un dispositivo rimasto indietro) viene tolta al cambio delle scelte.
    rig.raw.setLess(PersonId.random(), MemoryLessLevel.LESS)
    assertThat(live(rig)).isEmpty()
  }

  /** Un guasto della pulizia non ferma il janitor: si riprova al cambiamento dopo (e non scrive il messaggio del guasto). */
  @Test
  fun unGuastoDellaPuliziaSiRiprova() = runTest {
    val rig = rig()
    val (_, marco, _) = worldWithPersonMemory(rig)
    val failures = AtomicInteger(1)
    val flaky = object : Memories by rig.engine {
      override suspend fun prune(): RefreshReport {
        if (failures.getAndDecrement() > 0) error("database guasto con un nome Anna")
        return rig.engine.prune()
      }
    }
    val janitor = PeopleLessJanitor(rig.settings, rig.gated, rig.switch, flaky)
    val scope = CoroutineScope(Dispatchers.Unconfined + Job()).also { scopes += it }
    janitor.watch(scope)
    assertThat(live(rig)).containsKey(marco)
    rig.switch.set(PeoplePhase.OFF)
    // Le lapidi sono scritte anche se la pulizia e' fallita; lo scaffale si ripulisce al giro dopo.
    assertThat(live(rig)).isEmpty()
    // La prima pulizia e' fallita, le altre (ai cambiamenti dopo) sono andate.
    assertThat(failures.get()).isAtMost(-1)
    rig.people.available.value = false
    assertThat(rig.personMemories()).isEmpty()
  }
}
