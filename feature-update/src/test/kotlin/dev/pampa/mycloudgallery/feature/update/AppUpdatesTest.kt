package dev.pampa.mycloudgallery.feature.update

import com.google.common.truth.Truth.assertThat
import dev.antigravity.fluidengine.foundation.AppUpdateInstallState
import dev.antigravity.fluidengine.foundation.AvailableAppUpdate
import dev.antigravity.fluidengine.foundation.UpdateChannel
import dev.pampa.mycloudgallery.feature.update.install.ApkTransfer
import dev.pampa.mycloudgallery.feature.update.install.ForegroundGate
import dev.pampa.mycloudgallery.feature.update.install.GuardedUpdateInstaller
import dev.pampa.mycloudgallery.feature.update.install.InstallPermission
import dev.pampa.mycloudgallery.feature.update.install.InstallStep
import dev.pampa.mycloudgallery.feature.update.install.PackageFactsSource
import dev.pampa.mycloudgallery.feature.update.install.UpdateInstaller
import dev.pampa.mycloudgallery.feature.update.policy.PackageFacts
import dev.pampa.mycloudgallery.feature.update.policy.UpdateProblem
import dev.pampa.mycloudgallery.feature.update.policy.UpdateRules
import dev.pampa.mycloudgallery.feature.update.source.UpdateSourceConfig
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test

/**
 * Pacchetto U (giri 1 e 2 delle critiche): quando si controlla (all'apertura al piu' ogni 24 ore, a mano sempre), il canale salvato, la versione trovata
 * ricordata, l'avviso con «Dopo» che rimanda (al terzo trenta giorni, poi riparte), la domanda dei dati mobili per tentativo (US-2), la ripresa dopo il
 * permesso anche dopo un riavvio (UU-3), «Pronta» quando l'app e' dietro dopo le verifiche (UU-1, US2-1: il cancello mosso dal ciclo di vita), «Sto
 * installando» dopo il passaggio ad Android con l'attesa dell'esito (UU2-1), «Aggiornata» alla riapertura (UU-2). Elenco e installatore finti; una
 * prova con l'installatore protetto vero sopra un engine finto.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppUpdatesTest {

  private val dir: File = Files.createTempDirectory("aggiornamenti").toFile()

  @After
  fun cleanUp() {
    dir.deleteRecursively()
  }

  private class MemoryStore : UpdateStore {
    var saved = UpdateChannel.STABLE
    var last: Long? = null
    var savedOffer: SavedOffer? = null
    var savedReminder = Reminder()
    var savedAttempt: Attempt? = null

    override fun channel() = saved

    override fun setChannel(channel: UpdateChannel) {
      saved = channel
    }

    override fun lastCheckedAt() = last

    override fun setLastCheckedAt(millis: Long) {
      last = millis
    }

    override fun offer() = savedOffer

    override fun setOffer(offer: SavedOffer?) {
      savedOffer = offer
    }

    override fun reminder() = savedReminder

    override fun setReminder(reminder: Reminder) {
      savedReminder = reminder
    }

    override fun attempt() = savedAttempt

    override fun setAttempt(attempt: Attempt?) {
      savedAttempt = attempt
    }
  }

  private class FakeChecker(var answer: Result<AvailableAppUpdate?>) : UpdateChecker {
    val calls = mutableListOf<Pair<String, UpdateChannel>>()

    override suspend fun check(currentVersionName: String, channel: UpdateChannel): Result<AvailableAppUpdate?> {
      calls += currentVersionName to channel
      return answer
    }
  }

  private inner class FakeInstaller : UpdateInstaller {
    val file: File = File(dir, "mycloud-gallery-1.1.0.apk").apply { writeBytes(ByteArray(10)) }
    var prepareSteps: List<InstallStep> = listOf(InstallStep.Downloading(1f, 10, 10), InstallStep.Verifying, InstallStep.Ready(file))
    var commitSteps: List<InstallStep> = listOf(InstallStep.Installing, InstallStep.AwaitingUser)

    /** Con un canale il secondo tempo resta vivo dopo [commitSteps], come quello vero che aspetta Android: la prova manda l'esito quando vuole. */
    var commitTail: Channel<InstallStep>? = null
    val prepares = mutableListOf<AvailableAppUpdate>()
    val commits = mutableListOf<File>()
    val discarded = mutableListOf<File>()

    override fun prepare(update: AvailableAppUpdate): Flow<InstallStep> {
      prepares += update
      return flowOf(*prepareSteps.toTypedArray())
    }

    override fun commit(update: AvailableAppUpdate, file: File): Flow<InstallStep> {
      commits += file
      val steps = commitSteps
      val tail = commitTail
      return flow {
        for (step in steps) emit(step)
        if (tail != null) emitAll(tail)
      }
    }

    override fun discard(file: File) {
      discarded += file
    }
  }

  private class FakePermission(var granted: Boolean = true) : InstallPermission {
    var opened = 0

    override fun granted() = granted

    override fun open(): Boolean {
      opened++
      return true
    }
  }

  private fun offer(version: String, sha256: String = "a".repeat(64)) = AvailableAppUpdate(
    version = version,
    changelog = "- Novita'",
    releaseTag = "stable-mycloud-gallery-v$version",
    apkAsset = "mycloud-gallery-$version.apk",
    downloadUrl = UpdateSourceConfig.downloadUrl("stable-mycloud-gallery-v$version", "mycloud-gallery-$version.apk"),
    sizeBytes = 115_000_000L,
    sha256 = sha256,
  )

  private val day = UpdateSourceConfig.CHECK_INTERVAL_MILLIS

  private inner class World(val scope: TestScope) {
    val store = MemoryStore()
    val checker = FakeChecker(Result.success(null))
    val installer = FakeInstaller()
    val permission = FakePermission()
    var metered = false
    var now = 10 * day
    var cleared = 0

    /** Il cancello: lo aprono e chiudono onResumed/onPaused (le prove li chiamano come farebbe il ciclo di vita dell'activity). */
    val gate = ForegroundGate()

    /** Un processo nuovo (stesso file delle preferenze). [using]: un altro installatore (quello protetto vero, nella prova d'integrazione). */
    fun updates(version: String = "1.0.0", using: UpdateInstaller = installer) = AppUpdates(
      checker = checker,
      installer = using,
      store = store,
      installedVersion = version,
      permission = permission,
      metered = { metered },
      gate = gate,
      now = { now },
      // Lo scope della prova (non `backgroundScope`, che `advanceUntilIdle` non fa girare): i lavori di AppUpdates finiscono tutti.
      scope = scope,
      io = StandardTestDispatcher(scope.testScheduler),
      clearCache = { cleared++ },
    )

    /** Un processo con la versione 1.1.0 gia' trovata. */
    fun found(version: String = "1.0.0"): AppUpdates {
      checker.answer = Result.success(offer("1.1.0"))
      return updates(version).also {
        it.checkNow()
        scope.advanceUntilIdle()
      }
    }
  }

  private fun world(block: suspend World.() -> Unit) = runTest { World(this).block() }

  // --- I controlli ------------------------------------------------------------------------------------------------------------------------------------

  @Test
  fun allAperturaAlPiuUnControlloOgni24Ore() = world {
    val updates = updates()
    updates.checkIfDue(); scope.advanceUntilIdle()
    assertThat(checker.calls).hasSize(1)
    now += day - 1
    updates.checkIfDue(); scope.advanceUntilIdle()
    assertThat(checker.calls).hasSize(1)
    now += 1
    updates.checkIfDue(); scope.advanceUntilIdle()
    assertThat(checker.calls).hasSize(2)
    // A mano si controlla sempre.
    updates.checkNow(); scope.advanceUntilIdle()
    assertThat(checker.calls).hasSize(3)
  }

  @Test
  fun anchUnControlloNonRiuscitoContaPerLe24Ore_eAllAvvioNonSiVede() = world {
    checker.answer = Result.failure(IllegalStateException("Richiesta non riuscita (404)."))
    val updates = updates()
    updates.checkIfDue(); scope.advanceUntilIdle()
    assertThat(store.last).isEqualTo(now)
    assertThat(updates.state.value.check).isEqualTo(CheckState.Idle)
    assertThat(updates.prompt.value).isFalse()
    // Riaprire l'app dieci volte nello stesso giorno non bussa dieci volte a GitHub.
    repeat(10) { updates.checkIfDue() }
    scope.advanceUntilIdle()
    assertThat(checker.calls).hasSize(1)
    // A mano l'errore si vede: «Non riesco a controllare adesso».
    updates.checkNow(); scope.advanceUntilIdle()
    assertThat(updates.state.value.check).isEqualTo(CheckState.Unavailable)
  }

  @Test
  fun laVersioneCorrenteEIlVersionNameIntero() = world {
    updates(version = "1.1.0-beta.02").checkNow(); scope.advanceUntilIdle()
    assertThat(checker.calls.single().first).isEqualTo("1.1.0-beta.02")
  }

  @Test
  fun allAvvioLaCacheSiSvuota() = world {
    updates(); scope.advanceUntilIdle()
    assertThat(cleared).isEqualTo(1)
  }

  @Test
  fun ilCanaleEStabileDiSerie_laSceltaSiSalvaESiUsa() = world {
    val updates = updates()
    scope.advanceUntilIdle()
    assertThat(updates.state.value.channel).isEqualTo(UpdateChannel.STABLE)
    updates.chooseChannel(UpdateChannel.BETA); scope.advanceUntilIdle()
    assertThat(store.saved).isEqualTo(UpdateChannel.BETA)
    assertThat(checker.calls.last().second).isEqualTo(UpdateChannel.BETA)
    // Un processo nuovo legge la scelta salvata.
    val again = updates()
    scope.advanceUntilIdle()
    assertThat(again.state.value.channel).isEqualTo(UpdateChannel.BETA)
    again.checkNow(); scope.advanceUntilIdle()
    assertThat(checker.calls.last().second).isEqualTo(UpdateChannel.BETA)
  }

  @Test
  fun laPaginaApertaControllaSeServe_senzaAvviso() = world {
    checker.answer = Result.success(offer("1.1.0"))
    val updates = updates()
    updates.refreshIfDue(); scope.advanceUntilIdle()
    assertThat(checker.calls).hasSize(1)
    assertThat(updates.state.value.found?.version).isEqualTo("1.1.0")
    assertThat(updates.prompt.value).isFalse()
  }

  // --- La versione trovata e l'avviso -------------------------------------------------------------------------------------------------------------

  @Test
  fun laVersioneTrovataSiRicordaDopoUnRiavvio() = world {
    found()
    assertThat(store.savedOffer?.update?.version).isEqualTo("1.1.0")
    // Il giorno stesso, l'app riaperta: senza un controllo la versione e' gia' li' (pagina, segno) e l'avviso torna.
    checker.answer = Result.failure(IllegalStateException("senza rete"))
    val again = updates()
    scope.advanceUntilIdle()
    assertThat(again.state.value.found?.version).isEqualTo("1.1.0")
    assertThat(again.prompt.value).isTrue()
    // Installata quella versione, la versione salvata non vale piu'.
    val updated = updates(version = "1.1.0")
    scope.advanceUntilIdle()
    assertThat(updated.state.value.found).isNull()
    assertThat(store.savedOffer).isNull()
  }

  @Test
  fun dopoRimandaDiTreGiorni_alTerzoTaceTrentaGiorni_poiTornaEIlContoRiparte() = world {
    checker.answer = Result.success(offer("1.1.0"))
    val first = updates()
    first.checkIfDue(); scope.advanceUntilIdle()
    assertThat(first.prompt.value).isTrue()
    first.later(); scope.advanceUntilIdle()
    assertThat(first.prompt.value).isFalse()
    // Il giorno dopo non torna; dopo tre giorni si'.
    now += day
    assertThat(updates().also { scope.advanceUntilIdle() }.prompt.value).isFalse()
    now += 2 * day
    val back = updates()
    scope.advanceUntilIdle()
    assertThat(back.prompt.value).isTrue()
    // Il secondo «Dopo» (tre giorni) e il terzo: trenta giorni di silenzio (resta la voce in cima alle Impostazioni).
    back.later(); scope.advanceUntilIdle()
    now += UpdateRules.SNOOZE_MILLIS
    updates().also { scope.advanceUntilIdle() }.later(); scope.advanceUntilIdle()
    assertThat(store.savedReminder.declines).isEqualTo(UpdateRules.MAX_DECLINES)
    now += UpdateRules.QUIET_MILLIS - 1
    val quiet = updates()
    scope.advanceUntilIdle()
    assertThat(quiet.prompt.value).isFalse()
    assertThat(quiet.state.value.found?.version).isEqualTo("1.1.0")
    // Giro 2: mai spento per sempre. Passati i trenta giorni torna, e il conto riparte: il «Dopo» seguente vale di nuovo tre giorni.
    now += 1
    val again = updates()
    scope.advanceUntilIdle()
    assertThat(again.prompt.value).isTrue()
    again.later(); scope.advanceUntilIdle()
    assertThat(store.savedReminder.declines).isEqualTo(1)
    assertThat(store.savedReminder.snoozedUntil).isEqualTo(now + UpdateRules.SNOOZE_MILLIS)
  }

  @Test
  fun chiudiSullAvvisoDiUnGuasto_loChiudeDavvero() = world {
    // Giro 2, UU2-2: «Chiudi» sull'avviso e' later(): la scheda se ne va (non torna «Nuova versione» con «Aggiorna»), rimandata come «Dopo».
    checker.answer = Result.success(offer("1.1.0"))
    val updates = updates()
    updates.checkIfDue(); scope.advanceUntilIdle()
    installer.commitSteps = listOf(InstallStep.Failed(UpdateProblem.OTHER_SIGNATURE))
    updates.install(); scope.advanceUntilIdle()
    assertThat(updates.prompt.value).isTrue()
    assertThat(updates.state.value.install).isEqualTo(InstallStep.Failed(UpdateProblem.OTHER_SIGNATURE))
    updates.later(); scope.advanceUntilIdle()
    assertThat(updates.prompt.value).isFalse()
    assertThat(updates.state.value.install).isNull()
    assertThat(store.savedReminder.declines).isEqualTo(1)
  }

  @Test
  fun lAvvisoNonTornaAlternandoDueNomi() = world {
    // US-3: chi scrive l'elenco alterna due versioni ogni giorno; «Dopo» vale per tutte e due.
    checker.answer = Result.success(offer("1.1.0"))
    val first = updates()
    first.checkIfDue(); scope.advanceUntilIdle()
    first.later(); scope.advanceUntilIdle()
    for (version in listOf("1.2.0", "1.1.0")) {
      now += day
      checker.answer = Result.success(offer(version))
      val next = updates()
      next.checkIfDue(); scope.advanceUntilIdle()
      assertThat(next.state.value.found?.version).isEqualTo(version)
      assertThat(next.prompt.value).isFalse()
    }
  }

  @Test
  fun unControlloAManoNonApreLAvviso() = world {
    val updates = found()
    assertThat(updates.prompt.value).isFalse()
  }

  @Test
  fun unaVersionePubblicataSenzaSha256NonSiOffreNeSiAnnuncia() = world {
    checker.answer = Result.success(offer("1.1.0", sha256 = ""))
    val updates = updates()
    updates.checkIfDue(); scope.advanceUntilIdle()
    assertThat(updates.state.value.check).isEqualTo(CheckState.Unusable(offer("1.1.0", sha256 = ""), UpdateProblem.CHECKSUM_MISSING))
    assertThat(updates.state.value.found).isNull()
    assertThat(updates.prompt.value).isFalse()
    updates.install(); scope.advanceUntilIdle()
    assertThat(installer.prepares).isEmpty()
    assertThat(store.savedOffer).isNull()
  }

  // --- L'installazione -----------------------------------------------------------------------------------------------------------------------------

  @Test
  fun nienteInstallazioneSenzaIlTocco_eSuiDatiMobiliPrimaSiChiede() = world {
    checker.answer = Result.success(offer("1.1.0"))
    val updates = updates()
    updates.checkIfDue(); scope.advanceUntilIdle()
    // Trovata e annunciata: niente parte da solo.
    assertThat(installer.prepares).isEmpty()
    metered = true
    updates.install(); scope.advanceUntilIdle()
    assertThat(updates.state.value.askMetered).isTrue()
    assertThat(installer.prepares).isEmpty()
    updates.cancelMetered()
    assertThat(updates.state.value.askMetered).isFalse()
    updates.install(acceptMetered = true); scope.advanceUntilIdle()
    assertThat(installer.prepares.single().version).isEqualTo("1.1.0")
    assertThat(installer.commits).containsExactly(installer.file)
    assertThat(updates.state.value.install).isEqualTo(InstallStep.AwaitingUser)
  }

  @Test
  fun wifiPoiDatiMobiliDuranteIlPermesso_alRitornoSiChiede() = world {
    // US-2: sul Wi-Fi non si chiede; manca il permesso; nel frattempo il Wi-Fi cade. Al ritorno non si scaricano 110 MB senza consenso.
    val updates = found()
    installer.prepareSteps = listOf(InstallStep.NeedsPermission)
    updates.install(); scope.advanceUntilIdle()
    assertThat(updates.state.value.install).isEqualTo(InstallStep.NeedsPermission)
    metered = true
    permission.granted = true
    updates.onResumed(); scope.advanceUntilIdle()
    assertThat(updates.state.value.askMetered).isTrue()
    assertThat(installer.prepares).hasSize(1)
    // «Scarica lo stesso»: adesso si'.
    installer.prepareSteps = listOf(InstallStep.Downloading(0.3f, 3, 10))
    updates.install(acceptMetered = true); scope.advanceUntilIdle()
    assertThat(installer.prepares).hasSize(2)
  }

  @Test
  fun datiMobiliConConsenso_poiIlPermesso_siRiparteSenzaChiedere() = world {
    // US-2, l'altro caso: il consenso dato per QUESTO tentativo vale anche dopo il permesso.
    val updates = found()
    metered = true
    installer.prepareSteps = listOf(InstallStep.NeedsPermission)
    updates.install(acceptMetered = true); scope.advanceUntilIdle()
    assertThat(updates.state.value.install).isEqualTo(InstallStep.NeedsPermission)
    updates.openInstallPermission()
    assertThat(permission.opened).isEqualTo(1)
    // Tornati senza concederlo: niente riparte.
    permission.granted = false
    updates.onResumed(); scope.advanceUntilIdle()
    assertThat(installer.prepares).hasSize(1)
    // Concesso: riparte senza un altro tocco e senza chiedere di nuovo.
    permission.granted = true
    installer.prepareSteps = listOf(InstallStep.Downloading(0.3f, 3, 10))
    updates.onResumed(); scope.advanceUntilIdle()
    assertThat(installer.prepares).hasSize(2)
    assertThat(updates.state.value.askMetered).isFalse()
    // Una sola volta.
    updates.onResumed(); scope.advanceUntilIdle()
    assertThat(installer.prepares).hasSize(2)
  }

  @Test
  fun ilPermessoSopravviveAlRiavvio() = world {
    // UU-3: su Android 11 concedere il permesso chiude l'app. Il tentativo e' salvato: al ritorno si riprende.
    val first = found()
    installer.prepareSteps = listOf(InstallStep.NeedsPermission)
    first.install(); scope.advanceUntilIdle()
    assertThat(store.savedAttempt?.stage).isEqualTo(AttemptStage.PERMISSION)
    // Processo nuovo, permesso concesso.
    permission.granted = true
    installer.prepareSteps = listOf(InstallStep.Downloading(0.3f, 3, 10))
    val again = updates()
    scope.advanceUntilIdle()
    assertThat(again.state.value.install).isEqualTo(InstallStep.NeedsPermission)
    assertThat(again.prompt.value).isTrue()
    again.onResumed(); scope.advanceUntilIdle()
    assertThat(installer.prepares).hasSize(2)
    assertThat(again.state.value.install).isEqualTo(InstallStep.Downloading(0.3f, 3, 10))
  }

  @Test
  fun unTentativoVecchioNonSiRiprende() = world {
    val first = found()
    installer.prepareSteps = listOf(InstallStep.NeedsPermission)
    first.install(); scope.advanceUntilIdle()
    now += UpdateRules.ATTEMPT_TTL_MILLIS + 1
    val later = updates()
    scope.advanceUntilIdle()
    assertThat(later.state.value.install).isNull()
    later.onResumed(); scope.advanceUntilIdle()
    assertThat(installer.prepares).hasSize(1)
    assertThat(store.savedAttempt).isNull()
  }

  @Test
  fun ilCicloDiVitaDellActivityMuoveIlCancello() = world {
    // Giro 2, US2-1: ON_RESUME apre, ON_PAUSE chiude. Nessun'altra fonte (non l'importanza del processo).
    val updates = updates()
    scope.advanceUntilIdle()
    assertThat(gate.inFront()).isFalse()
    updates.onResumed()
    assertThat(gate.inFront()).isTrue()
    updates.onPaused()
    assertThat(gate.inFront()).isFalse()
  }

  @Test
  fun ilCancelloChiuso_Pronta_eInstallaNonRiscarica() = world {
    // UU-1, US2-1: il secondo tempo torna «Pronta» (l'app era dietro dopo le verifiche): nessun tentativo «installando» salvato, «Installa» usa lo
    // stesso file senza riscaricare.
    val updates = found()
    installer.commitSteps = listOf(InstallStep.Verifying, InstallStep.Ready(installer.file))
    updates.install(); scope.advanceUntilIdle()
    assertThat(updates.state.value.install).isEqualTo(InstallStep.Ready(installer.file))
    assertThat(store.savedAttempt).isNull()
    installer.commitSteps = listOf(InstallStep.Installing, InstallStep.AwaitingUser)
    updates.install(); scope.advanceUntilIdle()
    assertThat(installer.prepares).hasSize(1)
    assertThat(installer.commits).containsExactly(installer.file, installer.file)
    assertThat(updates.state.value.install).isEqualTo(InstallStep.AwaitingUser)
  }

  /** Un engine finto sotto l'installatore protetto vero: conta i passaggi a `installFile`. */
  private inner class EngineFake : ApkTransfer {
    var installs = 0

    override suspend fun download(update: AvailableAppUpdate, onProgress: (AppUpdateInstallState.Downloading) -> Unit): File =
      File(dir, update.apkAsset).apply { writeBytes(ByteArray(10)) }

    override fun installFile(file: File, expectedVersionName: String, sha256: String): Flow<AppUpdateInstallState> {
      installs++
      return flowOf(AppUpdateInstallState.Installing(""), AppUpdateInstallState.AwaitingUserAction(""))
    }
  }

  private class SameApp : PackageFactsSource {
    private val me = PackageFacts("dev.pampa.mycloudgallery", 5, "1.0.0", setOf("ab".repeat(32)))

    override fun installed(): PackageFacts = me

    override fun archive(file: File): PackageFacts = me.copy(versionCode = 6, versionName = "1.1.0")
  }

  @Test
  fun integrazione_HomeMentreIlFileSiVerifica_nienteAdAndroid_poiInstallaDavanti() = world {
    // US2-1 sulla strada vera: «Aggiorna» con l'activity RESUMED; lo scaricamento finisce; mentre il file si ricontrolla prima della sessione l'utente
    // preme Home (ON_PAUSE). Zero passaggi a installFile, «Pronta». Di nuovo davanti, «Installa» passa.
    val engine = EngineFake()
    var hashes = 0
    lateinit var app: AppUpdates
    val guarded = GuardedUpdateInstaller(
      transfer = engine,
      facts = SameApp(),
      permission = permission,
      inFront = gate::inFront,
      checksum = {
        hashes++
        // Il primo SHA-256 e' quello dello scaricamento; il secondo, prima della sessione: li' arriva la pausa.
        if (hashes == 2) app.onPaused()
        "a".repeat(64)
      },
      io = StandardTestDispatcher(scope.testScheduler),
    )
    checker.answer = Result.success(offer("1.1.0"))
    app = updates(using = guarded)
    app.onResumed()
    app.checkNow(); scope.advanceUntilIdle()
    app.install(); scope.advanceUntilIdle()
    assertThat(engine.installs).isEqualTo(0)
    assertThat(app.state.value.install).isInstanceOf(InstallStep.Ready::class.java)
    app.onResumed(); scope.runCurrent()
    assertThat(app.state.value.install).isInstanceOf(InstallStep.Ready::class.java)
    app.install(); scope.runCurrent()
    assertThat(engine.installs).isEqualTo(1)
    assertThat(app.state.value.install).isEqualTo(InstallStep.AwaitingUser)
    app.onPaused(); scope.advanceUntilIdle()
  }

  @Test
  fun dopoIlSiNellaFinestraDiAndroid_alRitornoStoInstallando_maiPronta() = world {
    // Giro 2, UU2-1: la finestra di Android si chiude dopo «Aggiorna» e l'app torna davanti mentre Android installa: «Sto installando», senza tasti;
    // un secondo «Installa» non c'e' (e non aprirebbe un'altra sessione).
    val updates = found()
    installer.commitTail = Channel(Channel.UNLIMITED)
    updates.onResumed(); scope.runCurrent()
    updates.install(); scope.runCurrent()
    assertThat(updates.state.value.install).isEqualTo(InstallStep.AwaitingUser)
    // La finestra di Android sopra l'app (ON_PAUSE), poi il «si'» e l'app di nuovo davanti.
    updates.onPaused(); scope.runCurrent()
    updates.onResumed(); scope.runCurrent()
    assertThat(updates.state.value.install).isEqualTo(InstallStep.Installing)
    scope.advanceTimeBy(UpdateRules.OUTCOME_WAIT_MILLIS - 1); scope.runCurrent()
    assertThat(updates.state.value.install).isEqualTo(InstallStep.Installing)
    updates.install(); scope.runCurrent()
    assertThat(installer.commits).hasSize(1)
    installer.commitTail?.close()
  }

  @Test
  fun senzaEsito_dopoLAttesaConLAppDavanti_Pronta_eInstallaNonRiscarica() = world {
    // La finestra si era chiusa senza una scelta, o non si era aperta: dopo l'attesa «Pronta» con «Installa», mai un'attesa senza tasti.
    val updates = found()
    installer.commitTail = Channel(Channel.UNLIMITED)
    updates.onResumed(); scope.runCurrent()
    updates.install(); scope.runCurrent()
    updates.onPaused(); scope.runCurrent()
    updates.onResumed(); scope.runCurrent()
    scope.advanceTimeBy(UpdateRules.OUTCOME_WAIT_MILLIS); scope.runCurrent()
    assertThat(updates.state.value.install).isEqualTo(InstallStep.Ready(installer.file))
    installer.commitTail = null
    updates.install(); scope.advanceUntilIdle()
    assertThat(installer.prepares).hasSize(1)
    assertThat(installer.commits).hasSize(2)
    // «Annulla» su «Pronta»: il file si cancella e si torna alla versione nuova.
    updates.onResumed(); scope.advanceUntilIdle()
    assertThat(updates.state.value.install).isEqualTo(InstallStep.Ready(installer.file))
    updates.cancelReady()
    assertThat(installer.discarded).containsExactly(installer.file)
    assertThat(updates.state.value.install).isNull()
    assertThat(updates.state.value.found?.version).isEqualTo("1.1.0")
  }

  @Test
  fun unEsitoCheArrivaVinceSullAttesa() = world {
    // «Annulla» (o Play Protect) nella finestra: l'esito arriva durante l'attesa e resta, con la riga di Play Protect.
    val updates = found()
    installer.commitTail = Channel(Channel.UNLIMITED)
    updates.onResumed(); scope.runCurrent()
    updates.install(); scope.runCurrent()
    updates.onPaused(); scope.runCurrent()
    updates.onResumed(); scope.runCurrent()
    assertThat(updates.state.value.install).isEqualTo(InstallStep.Installing)
    installer.commitTail?.trySend(InstallStep.Ready(installer.file, interrupted = true))
    scope.runCurrent()
    assertThat(updates.state.value.install).isEqualTo(InstallStep.Ready(installer.file, interrupted = true))
    scope.advanceTimeBy(2 * UpdateRules.OUTCOME_WAIT_MILLIS); scope.runCurrent()
    assertThat(updates.state.value.install).isEqualTo(InstallStep.Ready(installer.file, interrupted = true))
    installer.commitTail?.close()
  }

  @Test
  fun lAttesaContaSoloConLAppDavanti() = world {
    // Play Protect o un'altra finestra sopra l'app durante l'attesa: il tempo si ferma e riparte da capo al ritorno.
    val updates = found()
    installer.commitTail = Channel(Channel.UNLIMITED)
    updates.onResumed(); scope.runCurrent()
    updates.install(); scope.runCurrent()
    updates.onPaused(); scope.runCurrent()
    updates.onResumed(); scope.runCurrent()
    scope.advanceTimeBy(UpdateRules.OUTCOME_WAIT_MILLIS / 2); scope.runCurrent()
    updates.onPaused(); scope.runCurrent()
    scope.advanceTimeBy(10 * UpdateRules.OUTCOME_WAIT_MILLIS); scope.runCurrent()
    assertThat(updates.state.value.install).isEqualTo(InstallStep.Installing)
    updates.onResumed(); scope.runCurrent()
    scope.advanceTimeBy(UpdateRules.OUTCOME_WAIT_MILLIS - 1); scope.runCurrent()
    assertThat(updates.state.value.install).isEqualTo(InstallStep.Installing)
    scope.advanceTimeBy(1); scope.runCurrent()
    assertThat(updates.state.value.install).isEqualTo(InstallStep.Ready(installer.file))
  }

  @Test
  fun unGuastoCheTieneIlFile_riprovaSenzaRiscaricare() = world {
    val updates = found()
    installer.commitSteps = listOf(InstallStep.Failed(UpdateProblem.NO_SPACE))
    updates.install(); scope.advanceUntilIdle()
    assertThat(updates.state.value.install).isEqualTo(InstallStep.Failed(UpdateProblem.NO_SPACE))
    installer.commitSteps = listOf(InstallStep.Installing)
    updates.install(); scope.advanceUntilIdle()
    assertThat(installer.prepares).hasSize(1)
    assertThat(installer.commits).hasSize(2)
  }

  @Test
  fun aggiornataAllaPrimaApertura() = world {
    // UU-2: la sessione parte (il tentativo e' salvato prima); Android chiude l'app e la riapre la versione nuova.
    val updates = found()
    updates.install(); scope.advanceUntilIdle()
    assertThat(store.savedAttempt?.stage).isEqualTo(AttemptStage.INSTALLING)
    assertThat(store.savedAttempt?.version).isEqualTo("1.1.0")
    // Riaperta con la versione vecchia (annullata, o Android non l'ha installata): niente «Aggiornata».
    val notYet = updates()
    scope.advanceUntilIdle()
    assertThat(notYet.state.value.justUpdated).isNull()
    // Con la versione nuova: «Aggiornata», una volta. (Di nuovo davanti: «Sto installando», senza esito «Pronta»; «Installa» riparte la sessione e
    // risalva il tentativo.)
    updates.onResumed(); scope.advanceUntilIdle()
    updates.install(); scope.advanceUntilIdle()
    assertThat(store.savedAttempt?.stage).isEqualTo(AttemptStage.INSTALLING)
    val updated = updates(version = "1.1.0")
    scope.advanceUntilIdle()
    assertThat(updated.state.value.justUpdated).isEqualTo("1.1.0")
    assertThat(updated.prompt.value).isTrue()
    assertThat(store.savedAttempt).isNull()
    updated.later(); scope.advanceUntilIdle()
    assertThat(updated.state.value.justUpdated).isNull()
    assertThat(updated.prompt.value).isFalse()
    assertThat(updates(version = "1.1.0").also { scope.advanceUntilIdle() }.state.value.justUpdated).isNull()
  }

  @Test
  fun unSecondoToccoMentreLavoraNonRiparte() = world {
    val updates = found()
    installer.prepareSteps = listOf(InstallStep.Downloading(0.3f, 3, 10))
    updates.install(); scope.advanceUntilIdle()
    updates.install(); scope.advanceUntilIdle()
    assertThat(installer.prepares).hasSize(1)
    // E il canale non cambia mentre scarica.
    updates.chooseChannel(UpdateChannel.BETA); scope.advanceUntilIdle()
    assertThat(updates.state.value.channel).isEqualTo(UpdateChannel.STABLE)
  }
}
