package dev.pampa.mycloudgallery.feature.people

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.faces.contract.FacesDiag
import dev.pampa.mycloudgallery.core.faces.contract.FacesPhase
import dev.pampa.mycloudgallery.core.faces.contract.FacesState
import dev.pampa.mycloudgallery.core.faces.contract.FacesStatus
import dev.pampa.mycloudgallery.core.faces.contract.FacesWait
import dev.pampa.mycloudgallery.core.faces.contract.HereState
import dev.pampa.mycloudgallery.core.faces.contract.People
import dev.pampa.mycloudgallery.core.faces.contract.PeopleList
import dev.pampa.mycloudgallery.core.faces.contract.PersonCard
import dev.pampa.mycloudgallery.core.model.FaceGeneration
import dev.pampa.mycloudgallery.core.model.PersonId
import dev.pampa.mycloudgallery.feature.people.R
import dev.pampa.mycloudgallery.feature.people.contract.PeopleSectionState
import dev.pampa.mycloudgallery.feature.people.contract.PeopleUnavailable
import dev.pampa.mycloudgallery.feature.people.nav.PeopleEntryTarget
import dev.pampa.mycloudgallery.feature.people.section.PeopleSectionInputs
import dev.pampa.mycloudgallery.feature.people.section.PeopleSectionSources
import dev.pampa.mycloudgallery.feature.people.section.PeopleSectionStates
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
    // DR-1 (correzioni B): sulle share vere (D34) i nomi non viaggiano: il testo resta vero in tutti e due i casi, senza dire dove stanno.
    assertThat(row.subtitle).doesNotContain("viaggia")
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

  // --- Correzioni B della Fase 8 ---------------------------------------------------------------------------------------------------------

  private val generation = FaceGeneration.random()

  private val everyStatus: List<FacesStatus> =
    listOf(
      FacesStatus.Off,
      FacesStatus.Idle(atNas = 1L),
      FacesStatus.Running(0, 0, FacesPhase.DETECT),
      FacesStatus.Running(5, 10, FacesPhase.GROUPING),
      FacesStatus.Problem(FacesDiag.FACES_ENGINE_FAILED),
      FacesStatus.Problem(FacesDiag.FACES_MODEL_MISMATCH),
    ) + FacesWait.entries.map { FacesStatus.Waiting(it) }

  /**
   * AT-1: con Persone accesa la sezione dice **sempre qualcosa**: per ogni stato del calcolo, con o senza recinzione (D34), con o senza il ruolo di Indicizzatore,
   * a lista vuota, con persone nascoste o con una persona, le **righe** di `sectionOf` non sono mai zero (le Raccolte scartano una sezione senza voci) e ogni riga
   * porta a una pagina. Provare lo stato non basta: e' dalle righe che la sezione spariva.
   */
  @Test
  fun at1_conPersoneAccesaLeRigheNonSonoMaiZero() {
    val one = card("Anna", 3)
    val lists = listOf(
      PeopleList.EMPTY,
      PeopleList(emptyList(), emptyList(), 0, 2, null),
      PeopleList(listOf(one), emptyList(), 0, 0, null),
      PeopleList(emptyList(), emptyList(), 4, 0, null),
    )
    for (here in listOf(HereState.ACTIVE, HereState.LOCAL_ONLY)) {
      for (status in everyStatus) for (computes in listOf(null, true, false)) for (list in lists) {
        val state = PeopleSectionStates.of(FacesState.On(generation, here), list, status, computesHere = computes)
        val entries = sectionOf(state, list).entries
        assertWithMessage("$here $status computa=$computes $state").that(entries).isNotEmpty()
        for (entry in entries) assertWithMessage(entry.code).that(PeopleEntryTarget.parse(entry.code)).isNotNull()
      }
    }
  }

  /** AT-1: a calcolo in pari e senza persone la riga lo dice e apre "Tutte le persone"; con la recinzione c'e' anche la nota. */
  @Test
  fun at1_senzaPersone_laRigaDiceAncoraNessunaPersona() {
    val active = FacesState.On(generation, HereState.ACTIVE)
    val idle = FacesStatus.Idle(atNas = 1L)
    val row = sectionOf(PeopleSectionStates.of(active, PeopleList.EMPTY, idle), PeopleList.EMPTY).entries.single()
    assertThat(row.code).isEqualTo(PeopleEntryTarget.ALL)
    assertThat(row.title).isEqualTo("Ancora nessuna persona")
    assertThat(PeopleEntryTarget.parse(row.code)).isEqualTo(PeopleEntryTarget.All)

    val local = sectionOf(PeopleSectionStates.of(FacesState.On(generation, HereState.LOCAL_ONLY), PeopleList.EMPTY, idle), PeopleList.EMPTY)
    assertThat(local.entries.map { it.code }).containsExactly(PeopleEntryTarget.ALL, PeopleEntryTarget.LOCAL).inOrder()

    // Prima della prima tappa (stato del calcolo spento) non e' "nessuna persona": e' il primo calcolo che aspetta.
    val first = sectionOf(PeopleSectionStates.of(active, PeopleList.EMPTY, FacesStatus.Off), PeopleList.EMPTY).entries.single()
    assertThat(first.code).isEqualTo(PeopleEntryTarget.PROGRESS)
    assertThat(first.title).isEqualTo("Sto per riconoscere i volti")

    // Con persone nascoste la riga e' quella di sempre.
    val hidden = PeopleList(emptyList(), emptyList(), 0, 2, null)
    val hiddenRow = sectionOf(PeopleSectionStates.of(active, hidden, FacesStatus.Off), hidden).entries.single()
    assertThat(hiddenRow.subtitle).isEqualTo("Le persone che hai sono nascoste")
  }

  /**
   * DR-1: la riga di un dispositivo che non analizza le foto dice che qui le persone non si calcolano, dice **il ruolo che serve** (Indicizzatore) e **dove si accende**
   * (Impostazioni → Ricerca e modelli), e porta a Impostazioni → Persone. Mai nomi, mai "Sto per riconoscere i volti".
   */
  @Test
  fun dr1_unDispositivoCheNonAnalizzaDiceCheQuiNonSiCalcola() {
    val row = sectionOf(PeopleSectionState.NotComputedHere).entries.single()
    assertThat(row.code).isEqualTo(PeopleEntryTarget.NOT_COMPUTED)
    assertThat(row.title).isEqualTo("Qui le persone non si calcolano")
    assertThat(row.subtitle).contains("Indicizzatore")
    assertThat(row.subtitle).contains("Analizza le foto di questo dispositivo")
    assertThat(row.subtitle).contains("Impostazioni → Ricerca e modelli")
    assertThat(PeopleEntryTarget.parse(row.code)).isEqualTo(PeopleEntryTarget.Settings)

    // Dagli stati veri, con D34 e senza: lo stesso testo.
    for (here in listOf(HereState.ACTIVE, HereState.LOCAL_ONLY)) {
      val state = PeopleSectionStates.of(FacesState.On(generation, here), PeopleList.EMPTY, FacesStatus.Off, computesHere = false)
      assertThat(sectionOf(state).entries.single().title).isEqualTo("Qui le persone non si calcolano")
    }
  }

  /** DR-1: i testi che dicevano che i nomi viaggiano fra i dispositivi (falso con la recinzione D34) non lo dicono piu'. */
  @Test
  fun dr1_iTestiNonDiconoCheINomiViaggiano() {
    assertThat(resources.getString(R.string.persone_row_enable_detail)).doesNotContain("viaggia")
    assertThat(resources.getString(R.string.persone_name_message)).doesNotContain("viaggia")
  }

  /**
   * AT-2: **finche' le persone non sono montate la sezione non c'e'** (ne' "Attiva riconoscimento dei volti" a chi ha gia' Persone accesa, ne' niente altro), poi c'e'
   * quella giusta in un colpo solo; se le persone si smontano (l'azzeramento) torna assente.
   */
  @Test
  fun at2_primaDelMontaggioLaSezioneNonCe() = runTest(UnconfinedTestDispatcher()) {
    val inputs = MutableStateFlow<PeopleSectionInputs?>(null)
    val sources = PeopleSectionSources(resources, inputs, backgroundScope)
    assertThat(sources.section.value).isNull()
    assertThat(sources.sectionState.value).isNull()

    inputs.value = PeopleSectionInputs(FacesState.On(generation, HereState.ACTIVE), PeopleList.EMPTY, FacesStatus.Off)
    assertThat(sources.sectionState.value).isEqualTo(PeopleSectionState.FirstRun(null))
    assertThat(sources.section.value!!.entries.single().code).isEqualTo(PeopleEntryTarget.PROGRESS)

    inputs.value = null
    assertThat(sources.section.value).isNull()

    // Persone spenta e montata: la riga "Attiva" c'e' (e' la verita'), ma solo dopo il montaggio.
    inputs.value = PeopleSectionInputs(FacesState.Off, PeopleList.EMPTY, FacesStatus.Off)
    assertThat(sources.section.value!!.entries.single().code).isEqualTo(PeopleEntryTarget.ENABLE)
  }

  /** AT-1 e DR-1 dai flussi separati: la sezione segue il ruolo di Indicizzatore e lo stato del calcolo, e sparisce mai. */
  @Test
  fun laSezioneSegueIlRuoloEIlCalcolo() = runTest(UnconfinedTestDispatcher()) {
    val none = MutableStateFlow(PeopleList.EMPTY)
    val people = object : People by PeopleFixture().people {
      override val list: StateFlow<PeopleList> = none
    }
    val state = MutableStateFlow<FacesState>(FacesState.On(generation, HereState.LOCAL_ONLY))
    val status = MutableStateFlow<FacesStatus>(FacesStatus.Off)
    val computes = MutableStateFlow(false)
    val sources = PeopleSectionSources(resources, people, state, status, backgroundScope, computesHere = computes)
    assertThat(sources.sectionState.value).isEqualTo(PeopleSectionState.NotComputedHere)
    assertThat(sources.section.value!!.entries.single().code).isEqualTo(PeopleEntryTarget.NOT_COMPUTED)

    computes.value = true
    assertThat(sources.sectionState.value).isEqualTo(PeopleSectionState.FirstRun(null))
    status.value = FacesStatus.Idle(atNas = 1L)
    assertThat(sources.sectionState.value).isInstanceOf(PeopleSectionState.LocalOnly::class.java)
    assertThat(sources.section.value!!.entries.map { it.code }).containsExactly(PeopleEntryTarget.ALL, PeopleEntryTarget.LOCAL).inOrder()
  }
}
