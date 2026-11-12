package dev.pampa.mycloudgallery.feature.update

import com.google.common.truth.Truth.assertThat
import dev.antigravity.fluidengine.foundation.AvailableAppUpdate
import dev.antigravity.fluidengine.foundation.UpdateChannel
import dev.pampa.mycloudgallery.feature.update.install.InstallPermission
import dev.pampa.mycloudgallery.feature.update.install.InstallStep
import dev.pampa.mycloudgallery.feature.update.install.UpdateRunner
import dev.pampa.mycloudgallery.feature.update.policy.UpdateProblem
import dev.pampa.mycloudgallery.feature.update.source.UpdateSourceConfig
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Pacchetto U: quando si controlla (all'apertura al piu' ogni 24 ore, a mano sempre), il canale salvato su questo dispositivo, l'avviso una volta per
 * versione, la domanda dei dati mobili, la ripresa dopo il permesso. Elenco e installatore finti.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppUpdatesTest {

  private class MemoryStore : UpdateStore {
    var saved = UpdateChannel.STABLE
    var last: Long? = null
    var prompted: String? = null

    override fun channel() = saved

    override fun setChannel(channel: UpdateChannel) {
      saved = channel
    }

    override fun lastCheckedAt() = last

    override fun setLastCheckedAt(millis: Long) {
      last = millis
    }

    override fun promptedVersion() = prompted

    override fun setPromptedVersion(version: String) {
      prompted = version
    }
  }

  private class FakeChecker(var answer: Result<AvailableAppUpdate?>) : UpdateChecker {
    val calls = mutableListOf<Pair<String, UpdateChannel>>()

    override suspend fun check(currentVersionName: String, channel: UpdateChannel): Result<AvailableAppUpdate?> {
      calls += currentVersionName to channel
      return answer
    }
  }

  private class FakeRunner(var steps: List<InstallStep> = listOf(InstallStep.Downloading(1f, 10, 10), InstallStep.AwaitingUser)) : UpdateRunner {
    val runs = mutableListOf<AvailableAppUpdate>()

    override fun run(update: AvailableAppUpdate): Flow<InstallStep> {
      runs += update
      return flowOf(*steps.toTypedArray())
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
    val runner = FakeRunner()
    val permission = FakePermission()
    var metered = false
    var now = 10 * day

    fun updates(version: String = "1.0.0") = AppUpdates(
      checker = checker,
      runner = runner,
      store = store,
      installedVersion = version,
      permission = permission,
      metered = { metered },
      now = { now },
      // Lo scope della prova (non `backgroundScope`, che `advanceUntilIdle` non fa girare): i lavori di AppUpdates finiscono tutti.
      scope = scope,
      io = StandardTestDispatcher(scope.testScheduler),
    )
  }

  private fun world(block: suspend World.() -> Unit) = runTest { World(this).block() }

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
    updates(version = "1.1.0-beta.2").checkNow(); scope.advanceUntilIdle()
    assertThat(checker.calls.single().first).isEqualTo("1.1.0-beta.2")
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
  fun lAvvisoUnaVoltaPerVersione() = world {
    checker.answer = Result.success(offer("1.1.0"))
    val first = updates()
    first.checkIfDue(); scope.advanceUntilIdle()
    assertThat(first.state.value.found?.version).isEqualTo("1.1.0")
    assertThat(first.prompt.value).isTrue()
    assertThat(store.prompted).isEqualTo("1.1.0")
    first.dismissPrompt()
    assertThat(first.prompt.value).isFalse()
    // Il giorno dopo, un altro avvio: la stessa versione non si annuncia piu'.
    now += day
    val second = updates()
    second.checkIfDue(); scope.advanceUntilIdle()
    assertThat(second.state.value.found?.version).isEqualTo("1.1.0")
    assertThat(second.prompt.value).isFalse()
    // Una versione nuova si'.
    now += day
    checker.answer = Result.success(offer("1.2.0"))
    val third = updates()
    third.checkIfDue(); scope.advanceUntilIdle()
    assertThat(third.prompt.value).isTrue()
  }

  @Test
  fun unControlloAManoNonApreLAvvisoMaSegnaLaVersione() = world {
    checker.answer = Result.success(offer("1.1.0"))
    val updates = updates()
    updates.checkNow(); scope.advanceUntilIdle()
    assertThat(updates.prompt.value).isFalse()
    assertThat(store.prompted).isEqualTo("1.1.0")
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
    assertThat(runner.runs).isEmpty()
  }

  @Test
  fun nienteInstallazioneSenzaIlTocco_eSuiDatiMobiliPrimaSiChiede() = world {
    checker.answer = Result.success(offer("1.1.0"))
    val updates = updates()
    updates.checkIfDue(); scope.advanceUntilIdle()
    // Trovata e annunciata: niente parte da solo.
    assertThat(runner.runs).isEmpty()
    metered = true
    updates.install(); scope.advanceUntilIdle()
    assertThat(updates.state.value.askMetered).isTrue()
    assertThat(runner.runs).isEmpty()
    updates.cancelMetered()
    assertThat(updates.state.value.askMetered).isFalse()
    updates.install(acceptMetered = true); scope.advanceUntilIdle()
    assertThat(runner.runs.single().version).isEqualTo("1.1.0")
    assertThat(updates.state.value.install).isEqualTo(InstallStep.AwaitingUser)
  }

  @Test
  fun dopoIlPermessoSiRiparteDaSoli() = world {
    checker.answer = Result.success(offer("1.1.0"))
    runner.steps = listOf(InstallStep.NeedsPermission)
    val updates = updates()
    updates.checkNow(); scope.advanceUntilIdle()
    updates.install(); scope.advanceUntilIdle()
    assertThat(updates.state.value.install).isEqualTo(InstallStep.NeedsPermission)
    updates.openInstallPermission()
    assertThat(permission.opened).isEqualTo(1)
    // Tornati senza concederlo: niente riparte.
    permission.granted = false
    updates.onResumed(); scope.advanceUntilIdle()
    assertThat(runner.runs).hasSize(1)
    // Concesso: riparte senza un altro tocco (e senza chiedere di nuovo per i dati mobili).
    permission.granted = true
    metered = true
    runner.steps = listOf(InstallStep.Downloading(0.3f, 3, 10))
    updates.onResumed(); scope.advanceUntilIdle()
    assertThat(runner.runs).hasSize(2)
    assertThat(updates.state.value.askMetered).isFalse()
    // Una sola volta.
    updates.onResumed(); scope.advanceUntilIdle()
    assertThat(runner.runs).hasSize(2)
  }

  @Test
  fun unSecondoToccoMentreLavoraNonRiparte() = world {
    checker.answer = Result.success(offer("1.1.0"))
    runner.steps = listOf(InstallStep.Downloading(0.3f, 3, 10))
    val updates = updates()
    updates.checkNow(); scope.advanceUntilIdle()
    updates.install(); scope.advanceUntilIdle()
    updates.install(); scope.advanceUntilIdle()
    assertThat(runner.runs).hasSize(1)
    // E il canale non cambia mentre scarica.
    updates.chooseChannel(UpdateChannel.BETA); scope.advanceUntilIdle()
    assertThat(updates.state.value.channel).isEqualTo(UpdateChannel.STABLE)
  }
}
