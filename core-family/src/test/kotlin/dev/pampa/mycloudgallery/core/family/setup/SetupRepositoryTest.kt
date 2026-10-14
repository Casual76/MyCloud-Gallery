package dev.pampa.mycloudgallery.core.family.setup

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.family.contract.BlockReason
import dev.pampa.mycloudgallery.core.family.contract.OnboardingPath
import dev.pampa.mycloudgallery.core.family.contract.OnboardingProgress
import dev.pampa.mycloudgallery.core.family.contract.OnboardingStep
import dev.pampa.mycloudgallery.core.family.contract.SetupState
import dev.pampa.mycloudgallery.core.family.contract.StaleReason
import dev.pampa.mycloudgallery.core.family.contract.StepInput
import dev.pampa.mycloudgallery.core.family.contract.TrustStatus
import dev.pampa.mycloudgallery.core.family.contract.WipePhase
import dev.pampa.mycloudgallery.core.family.onboarding.JoinRecord
import dev.pampa.mycloudgallery.core.family.onboarding.OnboardingSnapshot
import dev.pampa.mycloudgallery.core.family.onboarding.StoreState
import dev.pampa.mycloudgallery.core.family.support.InMemoryOnboardingStateStore
import dev.pampa.mycloudgallery.core.family.support.OnboardingFixture
import dev.pampa.mycloudgallery.core.family.support.doStep
import dev.pampa.mycloudgallery.core.family.support.expectAdvanced
import dev.pampa.mycloudgallery.core.family.testing.FakeRegistryService
import dev.pampa.mycloudgallery.core.family.testing.RecordingSelfWipe
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Da cosa si ricava [SetupState]: la tabella delle decisioni di [SetupResolver] e il collegamento ai flussi di
 * [DefaultFamilySetupRepository] (avanzamento di P4, registro firmato e azzeramento di P3).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SetupRepositoryTest {

  private fun snapshot(path: OnboardingPath? = null, finished: Boolean = false, requestedAt: Long? = null): OnboardingSnapshot {
    val base = OnboardingSnapshot.EMPTY.copy(progress = OnboardingProgress.START.copy(path = path), finished = finished)
    return if (requestedAt != null) base.copy(join = JoinRecord("f", manual = false, minRegistryVersion = 1, registered = true, requestedAt = requestedAt)) else base
  }

  private fun resolve(
    stored: StoreState = StoreState.Ready(OnboardingSnapshot.EMPTY),
    trust: TrustStatus = TrustStatus.NotConfigured,
    wipe: WipePhase? = null,
    wipedAt: Long = 0L,
  ) = SetupResolver.resolve(stored, trust, wipe, wipedAt)

  @Test
  fun unDispositivoMaiConfiguratoEUnoCheNonHaLettoNienteAncora() {
    assertThat(resolve()).isEqualTo(SetupState.NotConfigured)
    assertThat(resolve(stored = StoreState.Loading)).isEqualTo(SetupState.Loading)
    // Telefono appena riavviato e non sbloccato (BFU): l'archivio non si legge.
    assertThat(resolve(stored = StoreState.Unavailable)).isEqualTo(SetupState.Blocked(BlockReason.STORAGE_LOCKED))
  }

  @Test
  fun fuoriDalloOnboardingDecideIlRegistroFirmato() {
    val finished = StoreState.Ready(snapshot(OnboardingPath.FOUND_FAMILY, finished = true))
    assertThat(resolve(finished, TrustStatus.Approved(admin = true))).isEqualTo(SetupState.Ready(admin = true))
    assertThat(resolve(StoreState.Ready(snapshot(OnboardingPath.JOIN_QR, finished = true)), TrustStatus.Approved(admin = false)))
      .isEqualTo(SetupState.Ready(admin = false))
    assertThat(resolve(StoreState.Ready(snapshot(OnboardingPath.JOIN_QR, finished = true, requestedAt = 123L)), TrustStatus.Pending("ABCD-EFGH-JKMN-PQRS")))
      .isEqualTo(SetupState.AwaitingApproval("ABCD-EFGH-JKMN-PQRS", since = 123L))
    // Anche senza la data della richiesta (archivio perso) si resta in attesa, in sola lettura.
    assertThat(resolve(finished, TrustStatus.Pending("ABCD"))).isEqualTo(SetupState.AwaitingApproval("ABCD", since = 0L))
    assertThat(resolve(finished, TrustStatus.Stale(StaleReason.NEEDS_APP_UPDATE))).isEqualTo(SetupState.Blocked(BlockReason.NEEDS_APP_UPDATE))
    assertThat(resolve(finished, TrustStatus.Stale(StaleReason.EQUIVOCATION))).isEqualTo(SetupState.Blocked(BlockReason.REGISTRY_FORK))
  }

  @Test
  fun unRegistroNonRiletto_NonTogliaLaGalleriaAChiLaGiaAveva() {
    val admin = StoreState.Ready(snapshot(OnboardingPath.FOUND_FAMILY, finished = true))
    val member = StoreState.Ready(snapshot(OnboardingPath.JOIN_QR, finished = true))
    for (reason in listOf(StaleReason.NOT_REFRESHED, StaleReason.NEWER_UNVERIFIED, StaleReason.LISTING_TRUNCATED)) {
      assertWithMessage("$reason").that(resolve(admin, TrustStatus.Stale(reason))).isEqualTo(SetupState.Ready(admin = true))
      assertWithMessage("$reason").that(resolve(member, TrustStatus.Stale(reason))).isEqualTo(SetupState.Ready(admin = false))
    }
    // Chi non ha mai configurato niente non diventa configurato per un registro vecchio.
    assertThat(resolve(trust = TrustStatus.Stale(StaleReason.NOT_REFRESHED))).isEqualTo(SetupState.NotConfigured)
  }

  @Test
  fun unDispositivoRevocatoODismessoRicominciaDaCapo() {
    val finished = StoreState.Ready(snapshot(OnboardingPath.JOIN_QR, finished = true))
    assertThat(resolve(finished, TrustStatus.Revoked)).isEqualTo(SetupState.NotConfigured)
    assertThat(resolve(finished, TrustStatus.Retired)).isEqualTo(SetupState.NotConfigured)
    assertThat(resolve(finished, TrustStatus.NotConfigured)).isEqualTo(SetupState.NotConfigured)
  }

  @Test
  fun loOnboardingInCorsoVinceSuUnRegistroGiaApprovato() {
    // L'admin e' approvato dal passo 12, ma i passi 13 e 14 restano: finche' non preme "Vai alla Libreria" e' onboarding.
    val running = snapshot(OnboardingPath.FOUND_FAMILY, finished = false)
    assertThat(resolve(StoreState.Ready(running), TrustStatus.Approved(admin = true))).isEqualTo(SetupState.Onboarding(running.progress))
    assertThat(resolve(StoreState.Ready(running), TrustStatus.Pending("X"))).isEqualTo(SetupState.Onboarding(running.progress))
    assertThat(resolve(StoreState.Ready(running), TrustStatus.Revoked)).isEqualTo(SetupState.Onboarding(running.progress))
  }

  @Test
  fun lAzzeramentoVinceSuTutto_eIlDispositivoAzzeratoSiRiconosce() {
    val running = StoreState.Ready(snapshot(OnboardingPath.FOUND_FAMILY))
    val finished = StoreState.Ready(snapshot(OnboardingPath.FOUND_FAMILY, finished = true))
    // A meta': le chiavi sono gia' distrutte e il resto no. Qualunque cosa dica il registro, non si usa.
    for (phase in WipePhase.entries.filter { it != WipePhase.DONE }) {
      assertWithMessage("$phase").that(resolve(finished, TrustStatus.Approved(true), wipe = phase)).isEqualTo(SetupState.Blocked(BlockReason.WIPE_IN_PROGRESS))
      assertWithMessage("$phase").that(resolve(running, TrustStatus.NotConfigured, wipe = phase)).isEqualTo(SetupState.Blocked(BlockReason.WIPE_IN_PROGRESS))
    }
    assertThat(resolve(finished, TrustStatus.Lost)).isEqualTo(SetupState.Blocked(BlockReason.WIPE_IN_PROGRESS))
    // Finito e nulla di nuovo iniziato: "dispositivo azzerato".
    assertThat(resolve(wipe = WipePhase.DONE, wipedAt = 99L)).isEqualTo(SetupState.Wiped(99L))
    // Chi ricomincia dopo un azzeramento ha scelto un percorso nuovo: si riconfigura (E-S2).
    assertThat(resolve(running, wipe = WipePhase.DONE)).isEqualTo(SetupState.Onboarding((running as StoreState.Ready).snapshot.progress))
    assertThat(resolve(finished, TrustStatus.Approved(true), wipe = WipePhase.DONE)).isEqualTo(SetupState.Ready(true))
  }

  // --- Il collegamento ai flussi ------------------------------------------------------------------------

  @Test
  fun ilRepositorySegueAvanzamentoRegistroEAzzeramento() = runTest(UnconfinedTestDispatcher()) {
    val store = InMemoryOnboardingStateStore()
    val registry = FakeRegistryService()
    val wipe = RecordingSelfWipe()
    var now = 1_000L
    val repo = DefaultFamilySetupRepository(store, registry, wipe, backgroundScope) { now }
    assertThat(repo.state.value).isEqualTo(SetupState.NotConfigured)

    store.setState(StoreState.Loading)
    assertThat(repo.state.value).isEqualTo(SetupState.Loading)
    store.setState(StoreState.Ready(snapshot(OnboardingPath.JOIN_QR, finished = true, requestedAt = 5L)))
    registry.status.value = TrustStatus.Pending("CODE")
    assertThat(repo.state.value).isEqualTo(SetupState.AwaitingApproval("CODE", since = 5L))
    registry.status.value = TrustStatus.Approved(admin = false)
    assertThat(repo.state.value).isEqualTo(SetupState.Ready(admin = false))

    // Segnato perso: azzeramento in corso, poi finito, con l'ora del primo sguardo.
    wipe.progress.value = WipePhase.MARKED
    assertThat(repo.state.value).isEqualTo(SetupState.Blocked(BlockReason.WIPE_IN_PROGRESS))
    store.setState(StoreState.Ready(OnboardingSnapshot.EMPTY)) // il LocalDataEraser ha cancellato anche l'avanzamento
    now = 2_000L
    wipe.progress.value = WipePhase.DONE
    assertThat(repo.state.value).isEqualTo(SetupState.Wiped(2_000L))
    now = 9_000L
    store.setState(StoreState.Ready(OnboardingSnapshot.EMPTY.copy(deferrable = true))) // un'altra emissione: l'ora non cambia
    assertThat(repo.state.value).isEqualTo(SetupState.Wiped(2_000L))
    // Riconfigurazione dopo l'azzeramento (E-S2): un percorso nuovo.
    store.setState(StoreState.Ready(snapshot(OnboardingPath.JOIN_QR)))
    assertThat(repo.state.value).isInstanceOf(SetupState.Onboarding::class.java)
  }

  /**
   * P11: "Configura di nuovo" toglie il diario dell'azzeramento finito un istante prima che il motore della fiducia
   * ricalcoli il suo stato (che dice ancora "perso"). Nessun lampo di "azzeramento in corso" in mezzo: da "azzerato" si
   * passa a "non configurato". Un dispositivo segnato perso che non ha ancora cominciato l'azzeramento resta bloccato.
   */
  @Test
  fun dimenticatoLAzzeramentoNessunLampoDiAzzeramentoInCorso() = runTest(UnconfinedTestDispatcher()) {
    val store = InMemoryOnboardingStateStore()
    val registry = FakeRegistryService()
    val wipe = RecordingSelfWipe()
    val repo = DefaultFamilySetupRepository(store, registry, wipe, backgroundScope) { 1_000L }
    val seen = mutableListOf<SetupState>()
    backgroundScope.launch { repo.state.collect { seen += it } }

    // Perso: azzeramento finito, il motore dice ancora "perso" (finche' il diario c'e').
    registry.status.value = TrustStatus.Lost
    wipe.progress.value = WipePhase.DONE
    assertThat(repo.state.value).isEqualTo(SetupState.Wiped(1_000L))
    seen.clear()

    // forgetCompletedWipe: prima sparisce la fase, poi il motore ricalcola.
    wipe.progress.value = null
    registry.status.value = TrustStatus.NotConfigured
    assertThat(repo.state.value).isEqualTo(SetupState.NotConfigured)
    assertThat(seen).doesNotContain(SetupState.Blocked(BlockReason.WIPE_IN_PROGRESS))

    // Una configurazione nuova, poi di nuovo perso prima che l'azzeramento parta: bloccato, come sempre.
    registry.status.value = TrustStatus.Approved(admin = false)
    registry.status.value = TrustStatus.Lost
    assertThat(repo.state.value).isEqualTo(SetupState.Blocked(BlockReason.WIPE_IN_PROGRESS))
  }

  @Test
  fun dalPrimoBenvenutoAllaLibreriaLoStatoSegueIlCoordinatore() = runTest(UnconfinedTestDispatcher()) {
    val f = OnboardingFixture(this)
    val repo = DefaultFamilySetupRepository(f.store, f.env.registry, RecordingSelfWipe(), backgroundScope)
    val c = f.coordinator()
    assertThat(repo.state.value).isEqualTo(SetupState.NotConfigured)

    expectAdvanced(f.doStep(c)) // percorso scelto: da qui e' onboarding
    val seen = mutableListOf<OnboardingStep>()
    while (f.current != OnboardingStep.SUMMARY) {
      val state = repo.state.value
      assertThat(state).isInstanceOf(SetupState.Onboarding::class.java)
      assertThat((state as SetupState.Onboarding).progress).isEqualTo(f.store.snapshot.progress)
      seen += state.progress.current
      // La famiglia e' fondata al passo 12: il registro dice "approvato", ma l'onboarding non e' finito.
      if (state.progress.current == OnboardingStep.SECURITY_NOTICE) f.env.registry.status.value = TrustStatus.Approved(admin = true)
      expectAdvanced(f.doStep(c))
    }
    assertThat(seen).containsAtLeast(OnboardingStep.NAS_ADDRESS, OnboardingStep.FAMILY_KEY, OnboardingStep.SECURITY_NOTICE)
    assertThat(repo.state.value).isInstanceOf(SetupState.Onboarding::class.java)
    expectAdvanced(c.submit(StepInput.Finish))
    assertThat(repo.state.value).isEqualTo(SetupState.Ready(admin = true))
  }
}
