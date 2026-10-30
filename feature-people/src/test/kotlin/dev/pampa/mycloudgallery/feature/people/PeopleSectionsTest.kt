package dev.pampa.mycloudgallery.feature.people

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.faces.contract.FacesState
import dev.pampa.mycloudgallery.core.faces.contract.FacesStatus
import dev.pampa.mycloudgallery.core.faces.contract.HereState
import dev.pampa.mycloudgallery.core.faces.contract.PeopleList
import dev.pampa.mycloudgallery.core.faces.contract.PersonCard
import dev.pampa.mycloudgallery.core.model.FaceGeneration
import dev.pampa.mycloudgallery.core.model.PersonId
import dev.pampa.mycloudgallery.feature.people.contract.PeopleSectionState
import dev.pampa.mycloudgallery.feature.people.contract.PeopleUnavailable
import dev.pampa.mycloudgallery.feature.people.nav.PeopleEntryTarget
import dev.pampa.mycloudgallery.feature.people.section.PeopleSectionSources
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * La sezione Persone delle Raccolte come dato (Fase 8, P9; §1.5 `PeopleSectionState`, §2.6 "Cosa si vede", Q2 (b), D4, D25, D34, D37): le voci di ogni stato,
 * i loro codici stabili (mai un nome: solo l'id della persona) e il fatto che la sezione c'e' sempre, anche spenta, con la riga "Attiva riconoscimento
 * dei volti". Il legame con le Raccolte vere e' in `PeopleSectionBindingTest`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class PeopleSectionsTest {

  private val resources get() = ApplicationProvider.getApplicationContext<Application>().resources

  private fun card(name: String?, photos: Int) = PersonCard(PersonId.random(), name, photos, null)

  private fun sectionOf(state: PeopleSectionState, list: PeopleList = PeopleList.EMPTY) = PeopleSectionSources.sectionOf(resources, state, list)

  @Test
  fun ilCodiceDellaSezioneEQuelloStabile() {
    assertThat(PeopleSectionSources.CODE).isEqualTo("people")
    assertThat(sectionOf(PeopleSectionState.Off).code).isEqualTo("people")
    assertThat(sectionOf(PeopleSectionState.Off).hideable).isTrue()
  }

  /** Da spenta la sezione c'e' lo stesso, con la sola riga che spiega e porta all'attivazione. */
  @Test
  fun spenta_unaRigaSolaCheAttiva() {
    val section = sectionOf(PeopleSectionState.Off)
    val row = section.entries.single()
    assertThat(row.code).isEqualTo(PeopleEntryTarget.ENABLE)
    assertThat(row.title).isEqualTo("Attiva riconoscimento dei volti")
    // Il testo non dice piu' "solo su questo telefono": i nomi viaggiano fra i dispositivi dell'account.
    assertThat(row.subtitle).doesNotContain("solo su questo telefono")
    assertThat(PeopleEntryTarget.parse(row.code)).isEqualTo(PeopleEntryTarget.Activation)
  }

  /** CE-3: la riga che dice perche' sta sopra "Attiva riconoscimento dei volti"; tutte e due portano alla pagina di attivazione. */
  @Test
  fun ce3_spentaDaUnAltroDispositivo_LaRigaCheLoDiceStaSopraAttiva() {
    val entries = sectionOf(PeopleSectionState.OffElsewhere).entries
    assertThat(entries.map { it.code }).containsExactly(PeopleEntryTarget.OFF_ELSEWHERE, PeopleEntryTarget.ENABLE).inOrder()
    assertThat(entries[0].title).isEqualTo("Persone è stata disattivata da un altro tuo dispositivo")
    assertThat(entries[1].title).isEqualTo("Attiva riconoscimento dei volti")
    for (entry in entries) assertThat(PeopleEntryTarget.parse(entry.code)).isEqualTo(PeopleEntryTarget.Activation)
    // Nessun nome, nessun id: solo testo fisso.
    assertThat(entries[0].subtitle).doesNotContain("Anna")
  }

  /** CE-3: la sezione vera segue il segno dell'interruttore, e la riga sparisce con la riattivazione. */
  @Test
  fun ce3_laSezioneSegueIlSegnoDellInterruttore() = runTest(UnconfinedTestDispatcher()) {
    val world = PeopleFixture()
    val state = MutableStateFlow<FacesState>(FacesState.Off)
    val off = MutableStateFlow(true)
    val sources = PeopleSectionSources(resources, world.people, state, MutableStateFlow(FacesStatus.Off), backgroundScope, off)
    assertThat(sources.sectionState.value).isEqualTo(PeopleSectionState.OffElsewhere)
    assertThat(sources.section.value!!.entries.map { it.code }).containsExactly(PeopleEntryTarget.OFF_ELSEWHERE, PeopleEntryTarget.ENABLE).inOrder()
    off.value = false
    assertThat(sources.sectionState.value).isEqualTo(PeopleSectionState.Off)
    assertThat(sources.section.value!!.entries.map { it.code }).containsExactly(PeopleEntryTarget.ENABLE)
  }

  @Test
  fun leRigheDegliStatiCheNonSonoPersone() {
    val notice = sectionOf(PeopleSectionState.AwaitingNotice).entries.single()
    assertThat(notice.code).isEqualTo(PeopleEntryTarget.NOTICE)
    assertThat(PeopleEntryTarget.parse(notice.code)).isEqualTo(PeopleEntryTarget.Activation)

    val excluded = sectionOf(PeopleSectionState.Excluded).entries.single()
    assertThat(excluded.title).isEqualTo("Non attiva su questo dispositivo")
    assertThat(PeopleEntryTarget.parse(excluded.code)).isEqualTo(PeopleEntryTarget.Settings)

    assertThat(sectionOf(PeopleSectionState.FirstRun(35)).entries.single().title).isEqualTo("Sto riconoscendo i volti: 35%")
    assertThat(sectionOf(PeopleSectionState.FirstRun(null)).entries.single().title).isEqualTo("Sto per riconoscere i volti")

    for (reason in PeopleUnavailable.entries) {
      val row = sectionOf(PeopleSectionState.Unavailable(reason)).entries.single()
      assertWithMessage("$reason").that(PeopleEntryTarget.parse(row.code)).isEqualTo(PeopleEntryTarget.Settings)
    }
  }

  /** Q2 (b): le persone con un nome, "Tutte le persone" e "Altri volti (N)" a un tocco. */
  @Test
  fun pronta_lePersoneTuttoEAltriVolti() {
    val anna = card("Anna", 120)
    val senza = card(null, 12)
    val section = sectionOf(PeopleSectionState.Ready(listOf(anna, senza), 2, 5), PeopleList(listOf(anna), emptyList(), 5, 0, null))
    assertThat(section.entries.map { it.code }).containsExactly(
      PeopleEntryTarget.PERSON_PREFIX + anna.id.value, PeopleEntryTarget.PERSON_PREFIX + senza.id.value, PeopleEntryTarget.ALL, PeopleEntryTarget.OTHERS,
    ).inOrder()
    assertThat(section.entries[0].title).isEqualTo("Anna")
    assertThat(section.entries[0].subtitle).isEqualTo("120 foto")
    assertThat(section.entries[1].title).isEqualTo("Persona senza nome")
    assertThat(section.entries.last().title).isEqualTo("Altri volti (5)")
  }

  @Test
  fun senzaGruppiSenzaNome_nonCeLaRigaAltriVolti() {
    val anna = card("Anna", 3)
    val section = sectionOf(PeopleSectionState.Ready(listOf(anna), 1, 0), PeopleList(listOf(anna), emptyList(), 0, 0, null))
    assertThat(section.entries.map { it.code }).doesNotContain(PeopleEntryTarget.OTHERS)
  }

  /** Mai un nome (D37): il codice di una persona porta solo il suo id e si riconosce per l'apertura. */
  @Test
  fun iCodiciNonPortanoMaiUnNome() {
    val anna = card("Anna Rossi", 7)
    val section = sectionOf(PeopleSectionState.Ready(listOf(anna), 1, 2), PeopleList(listOf(anna), emptyList(), 2, 0, null))
    for (entry in section.entries) {
      assertWithMessage(entry.code).that(entry.code).doesNotContain("Anna")
      assertWithMessage(entry.code).that(entry.code).doesNotContain("Rossi")
      assertWithMessage(entry.code).that(PeopleEntryTarget.parse(entry.code)).isNotNull()
    }
    assertThat(PeopleEntryTarget.parse(PeopleEntryTarget.PERSON_PREFIX + anna.id.value)).isEqualTo(PeopleEntryTarget.Person(anna.id))
  }

  @Test
  fun unCodiceDiUnAltraVersioneONonValidoNonSiRiconosce() {
    assertThat(PeopleEntryTarget.parse("people/qualcosa-di-nuovo")).isNull()
    assertThat(PeopleEntryTarget.parse("people/person/non-un-id")).isNull()
    assertThat(PeopleEntryTarget.parse("people/person/")).isNull()
    assertThat(PeopleEntryTarget.parse("album/xyz")).isNull()
    assertThat(PeopleEntryTarget.parse("")).isNull()
  }

  @Test
  fun laRecinzione_haLaNotaInFondo() {
    val anna = card("Anna", 7)
    val section = sectionOf(PeopleSectionState.LocalOnly(listOf(anna), 1, 0), PeopleList(listOf(anna), emptyList(), 0, 0, null))
    assertThat(section.entries.last().code).isEqualTo(PeopleEntryTarget.LOCAL)
    assertThat(section.entries.last().title).isEqualTo("Solo su questo dispositivo")
    assertThat(PeopleEntryTarget.parse(PeopleEntryTarget.LOCAL)).isEqualTo(PeopleEntryTarget.Settings)
  }

  /** Il dato cambia da solo con lo stato di Persone e con le persone, e nessuna sezione e' mai assente. */
  @Test
  fun laSezioneSeguelLoStatoDiPersone() = runTest(UnconfinedTestDispatcher()) {
    val world = PeopleFixture()
    world.people.rename(world.marco, "Marco")
    val state = MutableStateFlow<FacesState>(FacesState.Off)
    val status = MutableStateFlow<FacesStatus>(FacesStatus.Off)
    val sources = PeopleSectionSources(resources, world.people, state, status, backgroundScope)
    assertThat(sources.sectionState.value).isEqualTo(PeopleSectionState.Off)
    assertThat(sources.section.value!!.entries.single().code).isEqualTo(PeopleEntryTarget.ENABLE)

    state.value = FacesState.On(FaceGeneration.random(), HereState.ACTIVE)
    status.value = FacesStatus.Idle(atNas = 1L)
    val ready = sources.sectionState.value as PeopleSectionState.Ready
    assertThat(ready.top.map { it.name }).containsExactly("Marco")
    assertThat(ready.othersCount).isEqualTo(2) // Anna e Bruno: gruppi senza nome con almeno tre foto mostrabili (Q2 b)
    assertThat(sources.section.value!!.entries.map { it.code }).containsAtLeast(PeopleEntryTarget.ALL, PeopleEntryTarget.OTHERS)

    world.people.rename(world.anna, "Anna")
    assertThat((sources.sectionState.value as PeopleSectionState.Ready).top.map { it.name }).containsExactly("Marco", "Anna").inOrder()

    state.value = FacesState.On(FaceGeneration.random(), HereState.EXCLUDED)
    assertThat(sources.sectionState.value).isEqualTo(PeopleSectionState.Excluded)
    assertThat(sources.section.value!!.entries.single().code).isEqualTo(PeopleEntryTarget.EXCLUDED)
  }
}
