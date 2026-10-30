package dev.pampa.mycloudgallery.feature.people

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.faces.contract.FacesDiag
import dev.pampa.mycloudgallery.core.faces.contract.FacesPhase
import dev.pampa.mycloudgallery.core.faces.contract.FacesState
import dev.pampa.mycloudgallery.core.faces.contract.FacesStatus
import dev.pampa.mycloudgallery.core.faces.contract.FacesWait
import dev.pampa.mycloudgallery.core.faces.contract.HereState
import dev.pampa.mycloudgallery.core.faces.contract.PeopleList
import dev.pampa.mycloudgallery.core.faces.contract.PersonCard
import dev.pampa.mycloudgallery.core.model.FaceGeneration
import dev.pampa.mycloudgallery.core.model.PersonId
import dev.pampa.mycloudgallery.feature.people.contract.PeopleSectionState
import dev.pampa.mycloudgallery.feature.people.contract.PeopleUnavailable
import dev.pampa.mycloudgallery.feature.people.section.PeopleSectionStates
import org.junit.Test

/**
 * Lo stato della sezione Persone delle Raccolte, caso per caso (Fase 8, P9; §1.5 `PeopleSectionState`, §2.6 "Cosa si vede", Q2 (b), D4, D34, E-F7, E-F12,
 * E-F38): e' una funzione pura da cio' che c'e' gia' (stato di Persone su questo dispositivo, persone, calcolo).
 */
class PeopleSectionStatesTest {

  private val generation = FaceGeneration.random()
  private val on = FacesState.On(generation, HereState.ACTIVE)

  private fun card(name: String?, photos: Int) = PersonCard(PersonId.random(), name, photos, null)

  private fun list(named: List<PersonCard> = emptyList(), unnamed: List<PersonCard> = emptyList(), others: Int = 0, hidden: Int = 0, progress: Int? = null) =
    PeopleList(named, unnamed, others, hidden, progress)

  private val idle = FacesStatus.Idle(atNas = 1L)

  @Test
  fun spentaEInAttesaDellAvviso() {
    assertThat(PeopleSectionStates.of(FacesState.Off, list(), FacesStatus.Off)).isEqualTo(PeopleSectionState.Off)
    assertThat(PeopleSectionStates.of(FacesState.AwaitingNotice(generation, 1), list(), FacesStatus.Waiting(FacesWait.NOTICE)))
      .isEqualTo(PeopleSectionState.AwaitingNotice)
  }

  /** CE-3: da spenta perche' l'ha spenta un altro dispositivo la sezione lo sa; il segno non vale se Persone non e' spenta. */
  @Test
  fun ce3_spentaDaUnAltroDispositivo() {
    assertThat(PeopleSectionStates.of(FacesState.Off, list(), FacesStatus.Off, offElsewhere = true)).isEqualTo(PeopleSectionState.OffElsewhere)
    assertThat(PeopleSectionStates.of(FacesState.Off, list(), FacesStatus.Off, offElsewhere = false)).isEqualTo(PeopleSectionState.Off)
    assertThat(PeopleSectionStates.of(FacesState.AwaitingNotice(generation, 1), list(), FacesStatus.Waiting(FacesWait.NOTICE), offElsewhere = true))
      .isEqualTo(PeopleSectionState.AwaitingNotice)
    assertThat(PeopleSectionStates.of(on, list(), idle, offElsewhere = true)).isNotEqualTo(PeopleSectionState.OffElsewhere)
  }

  /** Spenta o in attesa dell'avviso, la sezione non lascia trapelare nessuna persona anche se il custode ne tiene in memoria. */
  @Test
  fun seNonEAttiva_nonSiVedeNessunaPersona() {
    val some = list(named = listOf(card("Anna", 12)))
    assertThat(PeopleSectionStates.of(FacesState.Off, some, idle)).isEqualTo(PeopleSectionState.Off)
    assertThat(PeopleSectionStates.of(FacesState.AwaitingNotice(generation, 1), some, idle)).isEqualTo(PeopleSectionState.AwaitingNotice)
    assertThat(PeopleSectionStates.of(FacesState.On(generation, HereState.EXCLUDED), some, idle)).isEqualTo(PeopleSectionState.Excluded)
  }

  @Test
  fun cancellazioneEFormaSconosciuta() {
    assertThat(PeopleSectionStates.of(FacesState.Erasing(generation), list(), FacesStatus.Waiting(FacesWait.ERASING)))
      .isEqualTo(PeopleSectionState.Unavailable(PeopleUnavailable.ERASING))
    assertThat(PeopleSectionStates.of(FacesState.NeedsUpdate, list(), FacesStatus.Waiting(FacesWait.NEEDS_UPDATE)))
      .isEqualTo(PeopleSectionState.Unavailable(PeopleUnavailable.NEEDS_UPDATE))
  }

  @Test
  fun ilPrimoCalcolo_conLaPercentualeOSenza() {
    assertThat(PeopleSectionStates.of(on, list(progress = 35), idle)).isEqualTo(PeopleSectionState.FirstRun(35))
    assertThat(PeopleSectionStates.of(on, list(progress = 250), idle)).isEqualTo(PeopleSectionState.FirstRun(100))
    assertThat(PeopleSectionStates.of(on, list(), FacesStatus.Running(done = 25, total = 100, phase = FacesPhase.DETECT))).isEqualTo(PeopleSectionState.FirstRun(25))
    assertThat(PeopleSectionStates.of(on, list(), FacesStatus.Running(done = 0, total = 0, phase = FacesPhase.DETECT))).isEqualTo(PeopleSectionState.FirstRun(0))
    // Aspetta il Wi-Fi di casa (o le regole): ancora il primo calcolo, senza percentuale.
    assertThat(PeopleSectionStates.of(on, list(), FacesStatus.Waiting(FacesWait.NAS))).isEqualTo(PeopleSectionState.FirstRun(null))
    assertThat(PeopleSectionStates.of(on, list(), FacesStatus.Waiting(FacesWait.RULES))).isEqualTo(PeopleSectionState.FirstRun(null))
  }

  @Test
  fun iModelliMancanti_nonSonoUnPrimoCalcolo() {
    assertThat(PeopleSectionStates.of(on, list(), FacesStatus.Waiting(FacesWait.MODELS))).isEqualTo(PeopleSectionState.Unavailable(PeopleUnavailable.MODELS))
    assertThat(PeopleSectionStates.of(on, list(), FacesStatus.Problem(FacesDiag.FACES_MODEL_MISMATCH))).isEqualTo(PeopleSectionState.Unavailable(PeopleUnavailable.MODELS))
    // Con persone gia' calcolate un problema dei modelli non le toglie: la sezione resta com'era.
    val some = list(named = listOf(card("Anna", 12)))
    assertThat(PeopleSectionStates.of(on, some, FacesStatus.Waiting(FacesWait.MODELS))).isInstanceOf(PeopleSectionState.Ready::class.java)
  }

  /** Nessuna persona e il calcolo in pari: la sezione e' "pronta" e vuota (la riga "Ancora nessuna persona" lo dice, non sparisce: AT-1). */
  @Test
  fun inPariSenzaPersone_eProntaEVuota() {
    val ready = PeopleSectionStates.of(on, list(), idle) as PeopleSectionState.Ready
    assertThat(ready.top).isEmpty()
    assertThat(ready.total).isEqualTo(0)
    assertThat(ready.othersCount).isEqualTo(0)
  }

  /**
   * AT-1 (correzioni B): con Persone accesa e nessuna persona **solo a calcolo in pari** la sezione e' "pronta e vuota". Lo stato del calcolo parte da `Off` a ogni
   * processo e cambia solo quando una tappa parte: ogni altro stato (spento, in attesa dell'avviso, in attesa delle regole, un guasto) e' un calcolo da fare, in
   * corso o in attesa, e la sezione lo dice (`FirstRun`), invece di cadere su "pronta e vuota" (zero righe: la sezione spariva).
   */
  @Test
  fun at1_conNessunaPersonaSoloUnCalcoloInPariEPronto() {
    assertThat(PeopleSectionStates.of(on, list(), FacesStatus.Off)).isEqualTo(PeopleSectionState.FirstRun(null))
    assertThat(PeopleSectionStates.of(on, list(), FacesStatus.Problem(FacesDiag.FACES_ENGINE_FAILED))).isEqualTo(PeopleSectionState.FirstRun(null))
    for (reason in FacesWait.entries) {
      // I motivi che hanno una riga loro (modelli: Unavailable; il dispositivo che non analizza: NotComputedHere) non sono un "primo calcolo".
      if (reason == FacesWait.MODELS || reason == FacesWait.NOT_INDEXER) continue
      assertWithMessage("$reason").that(PeopleSectionStates.of(on, list(), FacesStatus.Waiting(reason))).isEqualTo(PeopleSectionState.FirstRun(null))
    }
    assertThat(PeopleSectionStates.of(on, list(), idle)).isInstanceOf(PeopleSectionState.Ready::class.java)
    // Anche con la recinzione (D34) e con Persone accesa da un altro dispositivo e qui ancora senza calcolo.
    val local = FacesState.On(generation, HereState.LOCAL_ONLY)
    assertThat(PeopleSectionStates.of(local, list(), FacesStatus.Off)).isEqualTo(PeopleSectionState.FirstRun(null))
    assertThat(PeopleSectionStates.of(local, list(), idle)).isInstanceOf(PeopleSectionState.LocalOnly::class.java)
  }

  /** AT-1: con persone nascoste le persone ci sono: la riga "Le persone che hai sono nascoste" resta com'era, qualunque sia lo stato del calcolo. */
  @Test
  fun at1_leSoloNascosteNonSonoUnPrimoCalcolo() {
    for (status in listOf(FacesStatus.Off, idle, FacesStatus.Waiting(FacesWait.RULES))) {
      assertWithMessage("$status").that(PeopleSectionStates.of(on, list(hidden = 2), status)).isInstanceOf(PeopleSectionState.Ready::class.java)
    }
  }

  /**
   * DR-1 (correzioni B): un dispositivo che non analizza le foto dice che qui le persone non si calcolano, **non** "Sto per riconoscere i volti": con D34 non
   * arriverebbe mai. Vale sia con il motivo di attesa del calcolo (`Waiting(NOT_INDEXER)`, dopo la prima tappa) sia prima, con il solo segno del ruolo.
   */
  @Test
  fun dr1_unDispositivoCheNonAnalizzaDiceCheQuiNonSiCalcola() {
    assertThat(PeopleSectionStates.of(on, list(), FacesStatus.Waiting(FacesWait.NOT_INDEXER))).isEqualTo(PeopleSectionState.NotComputedHere)
    for (status in listOf(FacesStatus.Off, idle, FacesStatus.Waiting(FacesWait.RULES), FacesStatus.Problem(FacesDiag.FACES_ENGINE_FAILED))) {
      assertWithMessage("$status").that(PeopleSectionStates.of(on, list(), status, computesHere = false)).isEqualTo(PeopleSectionState.NotComputedHere)
    }
    val local = FacesState.On(generation, HereState.LOCAL_ONLY)
    assertThat(PeopleSectionStates.of(local, list(), FacesStatus.Off, computesHere = false)).isEqualTo(PeopleSectionState.NotComputedHere)
    // Chi analizza non lo vede mai, nemmeno con uno stato del calcolo vecchio (il ruolo e' appena stato acceso); chi ha gia' persone da mostrare le vede comunque.
    assertThat(PeopleSectionStates.of(on, list(), FacesStatus.Off, computesHere = true)).isEqualTo(PeopleSectionState.FirstRun(null))
    assertThat(PeopleSectionStates.of(on, list(), FacesStatus.Waiting(FacesWait.NOT_INDEXER), computesHere = true)).isEqualTo(PeopleSectionState.FirstRun(null))
    assertThat(PeopleSectionStates.of(on, list(named = listOf(card("Anna", 12))), FacesStatus.Off, computesHere = false)).isInstanceOf(PeopleSectionState.Ready::class.java)
    // Il calcolo che gira o ha una percentuale vince (il ruolo e' appena stato tolto a meta' tappa), e un guasto dei modelli resta tale.
    assertThat(PeopleSectionStates.of(on, list(), FacesStatus.Running(done = 5, total = 10, phase = FacesPhase.DETECT), computesHere = false)).isEqualTo(PeopleSectionState.FirstRun(50))
    assertThat(PeopleSectionStates.of(on, list(), FacesStatus.Waiting(FacesWait.MODELS), computesHere = false)).isEqualTo(PeopleSectionState.Unavailable(PeopleUnavailable.MODELS))
    // Gli altri stati di Persone non cambiano per il ruolo.
    assertThat(PeopleSectionStates.of(FacesState.Off, list(), FacesStatus.Off, computesHere = false)).isEqualTo(PeopleSectionState.Off)
    assertThat(PeopleSectionStates.of(FacesState.On(generation, HereState.EXCLUDED), list(), idle, computesHere = false)).isEqualTo(PeopleSectionState.Excluded)
  }

  /** Q2 (b): in primo piano solo chi ha un nome, fino a 8; gli altri gruppi sono "Altri volti (N)". */
  @Test
  fun q2b_soloChiHaUnNomeInPrimoPiano() {
    val named = (1..11).map { card("Persona $it", 100 - it) }
    val ready = PeopleSectionStates.of(on, list(named = named, others = 4), idle) as PeopleSectionState.Ready
    assertThat(ready.top).hasSize(PeopleSectionStates.TOP)
    assertThat(ready.top).isEqualTo(named.take(PeopleSectionStates.TOP))
    assertThat(ready.total).isEqualTo(11)
    assertThat(ready.othersCount).isEqualTo(4)
  }

  /** Q2 (a): anche le persone senza nome stanno in Persone, dopo quelle con un nome. */
  @Test
  fun q2a_ancheLeSenzaNomeDopoQuelleConUnNome() {
    val named = listOf(card("Anna", 10))
    val unnamed = listOf(card(null, 9), card(null, 3))
    val ready = PeopleSectionStates.of(on, list(named = named, unnamed = unnamed), idle) as PeopleSectionState.Ready
    assertThat(ready.top).isEqualTo(named + unnamed)
    assertThat(ready.total).isEqualTo(3)
    assertThat(ready.othersCount).isEqualTo(0)
  }

  /** D34 (la recinzione, Q5): sulle share vere i dati dei volti restano qui; le persone sono com'erano e la sezione lo dice. */
  @Test
  fun laRecinzione_stessaCosaConLaNota() {
    val named = listOf(card("Anna", 10))
    val local = PeopleSectionStates.of(FacesState.On(generation, HereState.LOCAL_ONLY), list(named = named, others = 2), idle) as PeopleSectionState.LocalOnly
    assertThat(local.top).isEqualTo(named)
    assertThat(local.total).isEqualTo(1)
    assertThat(local.othersCount).isEqualTo(2)
  }

  @Test
  fun leSolePersoneNascoste_nonFannoSparireLaSezione() {
    val ready = PeopleSectionStates.of(on, list(hidden = 2), idle)
    assertThat(ready).isInstanceOf(PeopleSectionState.Ready::class.java)
  }
}
