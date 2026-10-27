package dev.pampa.mycloudgallery.feature.people.activation

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.faces.contract.ActivateOutcome
import dev.pampa.mycloudgallery.core.faces.contract.FacesActivationConsent
import dev.pampa.mycloudgallery.core.faces.contract.FacesRequirements
import dev.pampa.mycloudgallery.core.faces.contract.FacesState
import dev.pampa.mycloudgallery.core.faces.contract.FacesSwitch
import dev.pampa.mycloudgallery.core.faces.contract.HereState
import dev.pampa.mycloudgallery.core.faces.contract.IndexerState
import dev.pampa.mycloudgallery.core.faces.contract.PrivateAreaState
import dev.pampa.mycloudgallery.core.faces.noop.NoFacesRequirements
import dev.pampa.mycloudgallery.core.faces.noop.NoFacesSwitch
import dev.pampa.mycloudgallery.core.faces.testing.FakeFacesRequirements
import dev.pampa.mycloudgallery.core.faces.testing.FakeFacesSwitch
import dev.pampa.mycloudgallery.core.model.FacesSetting
import dev.pampa.mycloudgallery.feature.people.contract.ActivationProblem
import dev.pampa.mycloudgallery.feature.people.contract.ActivationRequirements
import dev.pampa.mycloudgallery.feature.people.contract.FacesActivationState
import kotlinx.coroutines.CompletableDeferred
import org.junit.Test

/**
 * La logica della pagina di attivazione (docs/fase8-progetto.md §2.1, D3, D5, D34) sull'interruttore finto di P0 e su uno che risponde come voglio:
 * i requisiti ("Serve un'area privata sul NAS", "Nessun tuo dispositivo analizza le foto" con "Fallo su questo dispositivo"), gli esiti dell'attivazione
 * e il ruolo di indicizzatore. Il consenso lo costruisce solo la pagina: il controllore lo porta all'interruttore tale e quale.
 */
class FacesActivationControllerTest {

  private fun controller(
    switch: FacesSwitch = FakeFacesSwitch(),
    requirements: FakeFacesRequirements = metRequirements(),
    role: ActivationIndexerRole? = null,
  ) = FacesActivationController(switch, requirements, role, immediateScope())

  @Test
  fun senzaLeggereIRequisitiNonSiSaCosaMostrare() {
    val c = controller()
    assertThat(c.state.value).isNull()
  }

  @Test
  fun conIRequisitiSpiegaEPortaLaVersioneDelTesto() {
    val c = controller(requirements = metRequirements(localOnly = false))
    c.load()
    val state = c.state.value as FacesActivationState.Explain
    assertThat(state.textVersion).isEqualTo(FacesSwitch.CURRENT_TEXT_VERSION)
    assertThat(state.requirements).isEqualTo(
      ActivationRequirements(privateArea = true, indexers = 1, thisDeviceIndexer = true, canBecomeIndexer = true, localOnly = false),
    )
    assertThat(state.requirements.met).isTrue()
  }

  @Test
  fun sulleShareVereLaPaginaDiceCheOgniDispositivoHaLeSue() {
    val c = controller(requirements = metRequirements(localOnly = true))
    c.load()
    assertThat((c.state.value as FacesActivationState.Explain).requirements.localOnly).isTrue()
  }

  @Test
  fun senzaAreaPrivataDiceSoloQuello_E10() {
    for (area in listOf(PrivateAreaState.Missing, PrivateAreaState.SameAsCommon)) {
      val requirements = metRequirements().also { it.privateArea = area }
      val c = controller(requirements = requirements)
      c.load()
      assertThat(c.state.value).isEqualTo(FacesActivationState.NoPrivateArea)
    }
  }

  @Test
  fun senzaIndicizzatoriProponeIlRuoloSoloSeAppLoLega_E10() {
    val noIndexer = metRequirements().also { it.indexers = IndexerState(accountIndexers = 0, thisDevice = false, canBecomeIndexer = true) }
    val offered = controller(requirements = noIndexer, role = ActivationIndexerRole { true })
    offered.load()
    assertThat(offered.state.value).isEqualTo(FacesActivationState.NeedsIndexer(canBecomeIndexer = true))
    // Senza il ruolo legato, o se questo dispositivo non puo' diventarlo, non si offre "Fallo su questo dispositivo".
    val unbound = controller(requirements = noIndexer, role = null)
    unbound.load()
    assertThat(unbound.state.value).isEqualTo(FacesActivationState.NeedsIndexer(canBecomeIndexer = false))
    val cannot = metRequirements().also { it.indexers = IndexerState(0, thisDevice = false, canBecomeIndexer = false) }
    val c = controller(requirements = cannot, role = ActivationIndexerRole { true })
    c.load()
    assertThat(c.state.value).isEqualTo(FacesActivationState.NeedsIndexer(canBecomeIndexer = false))
  }

  @Test
  fun questoDispositivoCheAnalizzaBastaAnchePrimaDelSuoStatoFirmato() {
    val requirements = metRequirements().also { it.indexers = IndexerState(accountIndexers = 0, thisDevice = true, canBecomeIndexer = true) }
    val c = controller(requirements = requirements)
    c.load()
    assertThat(c.state.value).isInstanceOf(FacesActivationState.Explain::class.java)
  }

  @Test
  fun falloSuQuestoDispositivoAccendeIlRuoloERileggeIRequisiti() {
    val requirements = metRequirements().also { it.indexers = IndexerState(0, thisDevice = false, canBecomeIndexer = true) }
    var enabled = 0
    val role = ActivationIndexerRole {
      enabled++
      requirements.indexers = IndexerState(accountIndexers = 1, thisDevice = true, canBecomeIndexer = true)
      true
    }
    val c = controller(requirements = requirements, role = role)
    c.load()
    assertThat(c.state.value).isInstanceOf(FacesActivationState.NeedsIndexer::class.java)
    c.becomeIndexer()
    assertThat(enabled).isEqualTo(1)
    assertThat(c.state.value).isInstanceOf(FacesActivationState.Explain::class.java)
  }

  @Test
  fun seIlRuoloNonSiAccendeLaPaginaRestaCom_era_ENonLancia() {
    val requirements = metRequirements().also { it.indexers = IndexerState(0, thisDevice = false, canBecomeIndexer = true) }
    val c = controller(requirements = requirements, role = ActivationIndexerRole { error("non si accende") })
    c.load()
    c.becomeIndexer()
    assertThat(c.state.value).isEqualTo(FacesActivationState.NeedsIndexer(canBecomeIndexer = true))
  }

  // --- "Attiva" -----------------------------------------------------------------------------------------------------------------------

  @Test
  fun attivaPortaIlConsensoAllInterruttoreETornaAttivata_ConLaScelteDaDatare() {
    val switch = FakeFacesSwitch()
    val c = controller(switch = switch)
    c.load()
    val given = consent()
    c.activate(given)
    assertThat(c.state.value).isEqualTo(FacesActivationState.Activated(syncPending = true))
    assertThat(switch.state.value).isInstanceOf(FacesState.On::class.java)
    assertThat(switch.setting).isInstanceOf(FacesSetting.On::class.java)
    assertThat((switch.setting as FacesSetting.On).textVersion).isEqualTo(given.textVersion)
  }

  /** Il consenso e' quello che la pagina ha costruito: il controllore non ne crea uno suo. */
  @Test
  fun ilControlloreNonCostruisceIlConsenso_LoPortaTaleEQuale() {
    val seen = mutableListOf<FacesActivationConsent>()
    val recording = object : FacesSwitch by FakeFacesSwitch() {
      override suspend fun activate(consent: FacesActivationConsent): ActivateOutcome {
        seen += consent
        return ActivateOutcome.Unavailable
      }
    }
    val c = controller(switch = recording)
    val given = FacesActivationConsent(FacesSwitch.CURRENT_TEXT_VERSION, 42L)
    c.activate(given)
    assertThat(seen).hasSize(1)
    assertThat(seen.single()).isSameInstanceAs(given)
  }

  @Test
  fun durantelAttivazioneLaPaginaDiceCheAttiva() {
    val gate = CompletableDeferred<ActivateOutcome>()
    val slow = object : FacesSwitch by FakeFacesSwitch() {
      override suspend fun activate(consent: FacesActivationConsent): ActivateOutcome = gate.await()
    }
    val c = controller(switch = slow)
    c.activate(consent())
    assertThat(c.state.value).isEqualTo(FacesActivationState.Activating)
    gate.complete(ActivateOutcome.NoPrivateArea)
    assertThat(c.state.value).isEqualTo(FacesActivationState.NoPrivateArea)
  }

  @Test
  fun ogniEsitoHaIlSuoStato() {
    fun outcomeOf(outcome: ActivateOutcome): FacesActivationState? {
      val fixed = object : FacesSwitch by FakeFacesSwitch() {
        override suspend fun activate(consent: FacesActivationConsent): ActivateOutcome = outcome
      }
      val c = controller(switch = fixed, role = ActivationIndexerRole { true })
      c.activate(consent())
      return c.state.value
    }
    assertThat(outcomeOf(ActivateOutcome.NoPrivateArea)).isEqualTo(FacesActivationState.NoPrivateArea)
    assertThat(outcomeOf(ActivateOutcome.NoIndexer(canBecomeIndexer = true))).isEqualTo(FacesActivationState.NeedsIndexer(true))
    assertThat(outcomeOf(ActivateOutcome.NoIndexer(canBecomeIndexer = false))).isEqualTo(FacesActivationState.NeedsIndexer(false))
    assertThat(outcomeOf(ActivateOutcome.StaleConsent)).isEqualTo(FacesActivationState.Failed(ActivationProblem.STALE_CONSENT))
    assertThat(outcomeOf(ActivateOutcome.NeedsUpdate)).isEqualTo(FacesActivationState.Failed(ActivationProblem.NEEDS_UPDATE))
    assertThat(outcomeOf(ActivateOutcome.Unavailable)).isEqualTo(FacesActivationState.Failed(ActivationProblem.UNAVAILABLE))
  }

  @Test
  fun unConsensoDiUnAltroTestoRiapreLaPagina() {
    val c = controller()
    c.activate(consent(version = FacesSwitch.CURRENT_TEXT_VERSION + 1))
    assertThat(c.state.value).isEqualTo(FacesActivationState.Failed(ActivationProblem.STALE_CONSENT))
    c.retry()
    assertThat(c.state.value).isInstanceOf(FacesActivationState.Explain::class.java)
  }

  @Test
  fun unaEccezioneDellInterruttoreNonEUnaEccezioneDellaPagina() {
    val broken = object : FacesSwitch by FakeFacesSwitch() {
      override suspend fun activate(consent: FacesActivationConsent): ActivateOutcome = error("guasto")
    }
    val c = controller(switch = broken)
    c.activate(consent())
    assertThat(c.state.value).isEqualTo(FacesActivationState.Failed(ActivationProblem.UNAVAILABLE))
  }

  @Test
  fun senzaPorteLegatePersoneNonSiAccende() {
    val c = FacesActivationController(NoFacesSwitch, NoFacesRequirements, null, immediateScope())
    c.load()
    // I null-object dicono "nessuna area privata": la pagina non offre niente da accendere.
    assertThat(c.state.value).isEqualTo(FacesActivationState.NoPrivateArea)
    c.activate(consent())
    assertThat(c.state.value).isEqualTo(FacesActivationState.Failed(ActivationProblem.UNAVAILABLE))
  }

  @Test
  fun unGuastoNellaLetturaDeiRequisitiNonAccendeNiente() {
    val failing = object : FacesRequirements {
      override suspend fun privateArea(): PrivateAreaState = error("guasto")

      override suspend fun indexers(): IndexerState = error("guasto")

      override suspend fun localOnly(share: String): Boolean = error("guasto")
    }
    val c = FacesActivationController(FakeFacesSwitch(), failing, null, immediateScope())
    c.load()
    assertThat(c.state.value).isEqualTo(FacesActivationState.NoPrivateArea)
  }

  // --- La pagina aperta con Persone gia' in qualche stato --------------------------------------------------------------------------

  @Test
  fun seQuiPersoneEGiaAttivaLaPaginaLoDice() {
    val c = controller(switch = activeSwitch())
    c.load()
    assertThat(c.state.value).isInstanceOf(FacesActivationState.Activated::class.java)
  }

  /** L'account l'ha acceso altrove: la pagina serve a leggerla; non si chiedono requisiti (non si crea niente). */
  @Test
  fun conLAvvisoDaVedereLaPaginaSpiegaSenzaChiedereRequisiti() {
    val requirements = metRequirements().also { it.privateArea = PrivateAreaState.Missing }
    val c = controller(switch = awaitingSwitch(), requirements = requirements)
    c.load()
    assertThat(c.state.value).isInstanceOf(FacesActivationState.Explain::class.java)
  }

  @Test
  fun unDispositivoEsclusoPuoRiattivarsiDallaPagina() {
    val c = controller(switch = activeSwitch(here = HereState.EXCLUDED))
    c.load()
    assertThat(c.state.value).isInstanceOf(FacesActivationState.Explain::class.java)
  }

  @Test
  fun unaVoceSconosciutaODaCancellareNonSiAccende() {
    val unknown = FakeFacesSwitch(initial = FacesState.NeedsUpdate)
    val a = controller(switch = unknown)
    a.load()
    assertThat(a.state.value).isEqualTo(FacesActivationState.Failed(ActivationProblem.NEEDS_UPDATE))
    val erasing = FakeFacesSwitch(initial = FacesState.Erasing(sampleGeneration))
    val b = controller(switch = erasing)
    b.load()
    assertThat(b.state.value).isEqualTo(FacesActivationState.Failed(ActivationProblem.UNAVAILABLE))
  }

  @Test
  fun unaPaginaGiaAttivataNonTornaIndietroRicaricando() {
    val c = controller()
    c.load()
    c.activate(consent())
    val activated = c.state.value
    c.load()
    assertThat(c.state.value).isEqualTo(activated)
  }

}
