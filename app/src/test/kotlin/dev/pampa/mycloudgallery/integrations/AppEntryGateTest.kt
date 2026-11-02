package dev.pampa.mycloudgallery.integrations

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.security.lock.AppLockController
import dev.pampa.mycloudgallery.core.security.AppLockMode
import dev.pampa.mycloudgallery.core.security.lock.AppLockSettings
import dev.pampa.mycloudgallery.core.security.lock.AppLockTimeout
import dev.pampa.mycloudgallery.core.security.lock.MonotonicClock
import dev.pampa.mycloudgallery.feature.integrations.contract.EntryDecision
import dev.pampa.mycloudgallery.core.release.contract.ReleaseMessage
import dev.pampa.mycloudgallery.core.release.contract.ReleaseStatus
import dev.pampa.mycloudgallery.release.GatedReleaseState
import dev.pampa.mycloudgallery.release.ReleaseGraph
import dev.pampa.mycloudgallery.release.testReleaseGraph
import java.nio.file.Files
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Il cancello delle activity d'ingresso (P14; nota di P10, `docs/sicurezza.md` §4-bis, C17, E-X7): valuta il blocco **adesso**. Il buco che
 * chiude: prima il blocco si richiudeva solo al ritorno in primo piano della `MainActivity`; con lei in secondo piano il selettore e il
 * provider dei documenti restavano sbloccati per sempre per le altre app.
 */
class AppEntryGateTest {

  private var now = 1_000_000L
  private val lock = AppLockController(MonotonicClock { now })
  private var ready = true
  private var loading = false
  private val changes = MutableSharedFlow<Unit>(replay = 1)
  private val gate = AppEntryGate(lock, familyReady = { ready }, familyLoading = { loading }, familyChanges = changes)

  private fun enable(timeout: AppLockTimeout) = lock.updateSettings(AppLockSettings(AppLockMode.SYSTEM_CREDENTIAL, timeout))

  /** La `MainActivity` in primo piano e lo sblocco con la credenziale (come fa `AppLockGate`). */
  private fun unlockInApp() {
    lock.onAppForegrounded()
    assertThat(lock.beginAuthentication()).isTrue()
    lock.endAuthentication(true)
  }

  @Test
  fun bloccoSpentoFamigliaProntaEntra() {
    lock.updateSettings(AppLockSettings())
    assertThat(gate.decide()).isEqualTo(EntryDecision.ALLOWED)
    ready = false
    assertThat(gate.decide()).isEqualTo(EntryDecision.NOT_READY)
  }

  @Test
  fun impostazioniNonLetteNonEntraNessuno() {
    // A processo freddo, prima che le impostazioni del blocco siano lette: non si sa ancora (IN-3), e intanto non entra nessuno.
    assertThat(gate.decide()).isEqualTo(EntryDecision.LOADING)
  }

  /**
   * IN-3 della verifica funzionale 5B: a processo freddo la configurazione salvata e le impostazioni del blocco si stanno ancora leggendo. Prima il
   * cancello rispondeva "non pronta" (la schermata: "Prima configura MyCloud Gallery") o "chiusa" per una corsa. Adesso risponde `LOADING` e
   * `decideAfterLoading` aspetta che lo stato sia letto.
   */
  @Test
  fun in3_aFreddoIlCancelloNonDecideFinoAQuandoLoStatoNonELetto() = runTest {
    loading = true
    assertThat(gate.decide()).isEqualTo(EntryDecision.LOADING)
    // Anche con le impostazioni del blocco gia' lette: la famiglia non lo e'.
    lock.updateSettings(AppLockSettings())
    assertThat(gate.decide()).isEqualTo(EntryDecision.LOADING)

    // Lo stato arriva mentre si aspetta: la decisione e' quella vera.
    val waiting = async { gate.decideAfterLoading(10_000) }
    runCurrent()
    assertThat(waiting.isCompleted).isFalse()
    loading = false
    changes.emit(Unit)
    assertThat(waiting.await()).isEqualTo(EntryDecision.ALLOWED)
  }

  @Test
  fun in3_leImpostazioniDelBloccoCheArrivanoDopoSbloccanoL_Attesa() = runTest {
    val waiting = async { gate.decideAfterLoading(10_000) }
    runCurrent()
    assertThat(waiting.isCompleted).isFalse()
    enable(AppLockTimeout.AFTER_5_MINUTES) // le impostazioni sono lette: acceso e, a freddo, bloccato
    assertThat(waiting.await()).isEqualTo(EntryDecision.NEEDS_UNLOCK)
  }

  @Test
  fun in3_seLoStatoNonArrivaMaiL_AttesaFinisceEResta_LOADING() = runTest {
    loading = true
    assertThat(gate.decideAfterLoading(500)).isEqualTo(EntryDecision.LOADING)
  }

  @Test
  fun in3_unaFamigliaNonProntaNonAspettaLeImpostazioniDelBlocco() {
    ready = false
    assertThat(gate.decide()).isEqualTo(EntryDecision.NOT_READY)
  }

  /** IN-2: le decisioni (da cui le radici dei documenti si notificano) cambiano quando cambia lo stato o il blocco, e non si ripetono. */
  @Test
  fun in2_leDecisioniSegueLoStatoELoSbloccoSenzaRipetersi() = runTest {
    loading = true
    val seen = ArrayList<EntryDecision>()
    val job = launch(kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler)) { gate.decisions().collect { seen += it } }
    assertThat(seen).containsExactly(EntryDecision.LOADING)
    loading = false
    lock.updateSettings(AppLockSettings())
    changes.emit(Unit)
    assertThat(seen.last()).isEqualTo(EntryDecision.ALLOWED)
    enable(AppLockTimeout.AFTER_1_MINUTE)
    assertThat(seen.last()).isEqualTo(EntryDecision.ALLOWED) // acceso mentre si e' dentro l'app: non la richiude
    lock.lockNow()
    assertThat(seen.last()).isEqualTo(EntryDecision.NEEDS_UNLOCK)
    assertThat(seen.zipWithNext().none { (a, b) -> a == b }).isTrue()
    job.cancel()
  }

  /** Il cancello con il manifest firmato di [state] (CA-2): come lo lega `IntegrationsGraph`. */
  private fun kotlinx.coroutines.test.TestScope.releaseGate(state: GatedReleaseState): Pair<AppEntryGate, ReleaseGraph> {
    val release = testReleaseGraph(Files.createTempDirectory("release").toFile(), state, StandardTestDispatcher(testScheduler))
    val gate = AppEntryGate(
      lock = lock,
      familyReady = { true },
      releaseLoading = { !release.isLoaded },
      releaseStopped = { release.status.value is ReleaseStatus.Stopped },
      releaseChanges = release.changes,
    )
    return gate to release
  }

  /**
   * CA-2 e R1 delle revisioni della Fase 10: il cancello conosce il manifest firmato. Con la versione fermata (`ALL`) risponde `STOPPED` (selettore, provider, ricezione
   * e rilasci si chiudono con il motivo), anche con il blocco dell'app acceso; prima che lo stato salvato sia letto aspetta (mai "fermata" per sbaglio, mai "aperta"
   * per una corsa). Prima il cancello non lo guardava e le altre app ricevevano foto da una versione fermata.
   */
  @Test
  fun ca2_conLaVersioneFermataIlCancelloRispondeStopped_ePrimaDelloStatoLettoAspetta() = runTest {
    lock.updateSettings(AppLockSettings())
    val state = GatedReleaseState(ReleaseStatus.Normal, open = false)
    val (gate, release) = releaseGate(state)
    assertThat(gate.decide()).isEqualTo(EntryDecision.LOADING)
    val waiting = async { gate.decideAfterLoading(10_000) }
    launch { release.load() }
    runCurrent()
    assertThat(waiting.isCompleted).isFalse()

    // Lo stato salvato da un processo di prima: la versione e' fermata.
    state.status.value = ReleaseStatus.Stopped(ReleaseMessage.SECURITY)
    state.loadGate.complete(Unit)
    assertThat(waiting.await()).isEqualTo(EntryDecision.STOPPED)
    // Con il blocco dell'app acceso non chiede lo sblocco per poi non dare niente.
    enable(AppLockTimeout.IMMEDIATELY)
    assertThat(gate.decide()).isEqualTo(EntryDecision.STOPPED)
  }

  /** CA-2: le decisioni (e quindi le radici del provider dei documenti) cambiano quando arriva il blocco del manifest, senza aspettare un altro evento. */
  @Test
  fun ca2_leDecisioniSeguonoIlManifest() = runTest {
    lock.updateSettings(AppLockSettings())
    val state = GatedReleaseState(ReleaseStatus.Normal)
    val (gate, release) = releaseGate(state)
    release.load()
    val seen = ArrayList<EntryDecision>()
    val job = launch(kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler)) { gate.decisions().collect { seen += it } }
    assertThat(seen).containsExactly(EntryDecision.ALLOWED)
    state.status.value = ReleaseStatus.ReadOnly(ReleaseMessage.UPDATE)
    assertThat(seen.last()).isEqualTo(EntryDecision.ALLOWED) // la sola lettura non chiude gli ingressi (si legge, non si scrive)
    state.status.value = ReleaseStatus.Stopped(ReleaseMessage.SECURITY)
    assertThat(seen.last()).isEqualTo(EntryDecision.STOPPED)
    job.cancel()
  }

  @Test
  fun conLaMainActivityInSecondoPianoIlBloccoSiRichiudeAdessoNonAlRitorno() {
    enable(AppLockTimeout.AFTER_1_MINUTE)
    assertThat(gate.decide()).isEqualTo(EntryDecision.NEEDS_UNLOCK)
    unlockInApp()
    assertThat(gate.decide()).isEqualTo(EntryDecision.ALLOWED)

    // L'utente esce dall'app: per un minuto un'altra app puo' ancora scegliere le foto (come la MainActivity al ritorno).
    lock.onAppBackgrounded()
    now += 59_000
    assertThat(gate.decide()).isEqualTo(EntryDecision.ALLOWED)
    // Passato il timeout il cancello e' chiuso **senza** che la MainActivity torni in primo piano (il buco di prima).
    now += 1_000
    assertThat(gate.decide()).isEqualTo(EntryDecision.NEEDS_UNLOCK)
    // E resta chiuso: la MainActivity che torna trova la schermata di blocco.
    lock.onAppForegrounded()
    assertThat(gate.decide()).isEqualTo(EntryDecision.NEEDS_UNLOCK)
  }

  @Test
  fun unOrologioCheTornaIndietroRichiude() {
    enable(AppLockTimeout.AFTER_15_MINUTES)
    unlockInApp()
    lock.onAppBackgrounded()
    now -= 1
    assertThat(gate.decide()).isEqualTo(EntryDecision.NEEDS_UNLOCK)
  }

  @Test
  fun unoSbloccoDallIngressoValeDueMinutiAncheConSubito() {
    enable(AppLockTimeout.IMMEDIATELY)
    // La MainActivity non c'e' (un'altra app ha aperto il selettore a processo freddo): lo sblocco lo fa l'activity d'ingresso.
    assertThat(gate.decide()).isEqualTo(EntryDecision.NEEDS_UNLOCK)
    assertThat(lock.beginAuthentication()).isTrue()
    lock.endEntryAuthentication(true)
    assertThat(gate.decide()).isEqualTo(EntryDecision.ALLOWED)
    now += AppLockController.ENTRY_GRACE_MS - 1
    assertThat(gate.decide()).isEqualTo(EntryDecision.ALLOWED)
    now += 1
    assertThat(gate.decide()).isEqualTo(EntryDecision.NEEDS_UNLOCK)
  }

  @Test
  fun unoSbloccoNonRiuscitoNonApre() {
    enable(AppLockTimeout.AFTER_5_MINUTES)
    assertThat(lock.beginAuthentication()).isTrue()
    lock.endEntryAuthentication(false)
    assertThat(gate.decide()).isEqualTo(EntryDecision.NEEDS_UNLOCK)
  }
}
