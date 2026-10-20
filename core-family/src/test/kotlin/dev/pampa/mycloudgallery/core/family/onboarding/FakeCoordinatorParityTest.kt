package dev.pampa.mycloudgallery.core.family.onboarding

import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.family.contract.OnboardingPath
import dev.pampa.mycloudgallery.core.family.contract.OnboardingProblem
import dev.pampa.mycloudgallery.core.family.contract.OnboardingProgress
import dev.pampa.mycloudgallery.core.family.contract.OnboardingStep
import dev.pampa.mycloudgallery.core.family.contract.PreviewQuality
import dev.pampa.mycloudgallery.core.family.contract.StepInput
import dev.pampa.mycloudgallery.core.family.contract.StepResult
import dev.pampa.mycloudgallery.core.family.contract.TrustStatus
import dev.pampa.mycloudgallery.core.family.support.OnboardingFixture
import dev.pampa.mycloudgallery.core.family.support.Reports
import dev.pampa.mycloudgallery.core.family.support.credentials
import dev.pampa.mycloudgallery.core.family.support.doStep
import dev.pampa.mycloudgallery.core.family.testing.FakeOnboardingCoordinator
import dev.pampa.mycloudgallery.core.nas.contract.NasError
import dev.pampa.mycloudgallery.core.nas.contract.Stage
import dev.pampa.mycloudgallery.core.rules.Preset
import dev.pampa.mycloudgallery.core.trust.nas.NasLayout
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Il coordinatore finto (quello dei test delle schermate) e quello vero fanno **gli stessi passi**: stesso piano, stesso
 * passo corrente, stessi passi fatti, saltati e da completare, stessi esiti (avanti o problema) per salta, indietro,
 * riprendi, cambio di percorso e "Vai alla Libreria". La verifica funzionale (ON-3, ON-7) ha trovato due difetti che il
 * finto nascondeva perche' aveva regole sue: questo test lo tiene allineato.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FakeCoordinatorParityTest {

  /** Il vero (con il NAS finto di `TestEnv`) e il finto, guidati dagli stessi gesti. */
  private class Both(scope: TestScope) {
    val f = OnboardingFixture(scope)
    val real = f.coordinator()
    val fake = FakeOnboardingCoordinator().apply { candidate = f.env.candidate }
    private var gesture = 0

    private fun OnboardingProgress.comparable() = copy(startedAt = null)

    fun same(what: String, real: StepResult?, fake: StepResult?) {
      gesture++
      val label = "gesto $gesture ($what)"
      assertWithMessage("$label: esito").that(fake?.let(::kind)).isEqualTo(real?.let(::kind))
      assertWithMessage("$label: avanzamento").that(this.fake.progress.value.comparable()).isEqualTo(f.store.snapshot.progress.comparable())
      assertWithMessage("$label: da completare").that(this.fake.progress.value.pending).isEqualTo(f.store.snapshot.progress.pending)
      assertWithMessage("$label: finito").that(this.fake.finished).isEqualTo(f.store.snapshot.finished)
    }

    /** "Avanti" o il problema: il passo dopo lo dice gia' l'avanzamento. */
    private fun kind(result: StepResult): Any = when (result) {
      is StepResult.Advanced -> "avanti"
      is StepResult.Problem -> result.problem
    }

    suspend fun submit(input: () -> StepInput) = same(input()::class.simpleName.orEmpty(), real.submit(input()), fake.submit(input()))

    /** Il passo corrente, fatto come lo fa l'utente (al passo 2, "e' casa?" e poi l'indirizzo). */
    suspend fun step() {
      val current = f.current
      val real = f.doStep(real)
      val fake = when (current) {
        OnboardingStep.NAS_ADDRESS -> {
          fake.submit(StepInput.ConfirmHome(true))
          fake.submit(StepInput.NasAddressChosen(f.env.address))
        }
        else -> fake.submit(inputFor(current))
      }
      same("passo $current", real, fake)
    }

    suspend fun stepsUntil(step: OnboardingStep) {
      var guard = 0
      while (f.current != step) {
        step()
        check(++guard < 30) { "il percorso non arriva a $step" }
      }
    }

    suspend fun skip(step: OnboardingStep) = same("salta $step", real.skip(step), fake.skip(step))

    suspend fun resume(step: OnboardingStep) = same("riprendi $step", real.resume(step), fake.resume(step))

    suspend fun back() {
      real.back()
      fake.back()
      same("indietro", null, null)
    }

    private fun inputFor(step: OnboardingStep): StepInput = when (step) {
      OnboardingStep.WELCOME -> StepInput.ChoosePath(OnboardingPath.FOUND_FAMILY)
      OnboardingStep.NAS_ADDRESS -> StepInput.NasAddressChosen(f.env.address)
      OnboardingStep.ACCOUNT -> credentials()
      OnboardingStep.AREAS -> StepInput.Areas("NAS", "alessio")
      OnboardingStep.FOLDERS -> StepInput.Folders(setOf("Musica", "Film/Serie"))
      OnboardingStep.BACKUP_SOURCES -> StepInput.BackupSources(camera = true, screenshots = false)
      OnboardingStep.ROLES -> StepInput.Roles(indexer = true, preparer = false)
      OnboardingStep.PRESET -> StepInput.PresetChoice(Preset.BALANCED, PreviewQuality.STANDARD)
      OnboardingStep.PERMISSIONS -> StepInput.PermissionsReviewed
      OnboardingStep.BATTERY -> StepInput.BatteryReviewed
      OnboardingStep.VPN -> StepInput.VpnSetup(remoteControl = false, tunnelName = null)
      OnboardingStep.FAMILY_KEY -> StepInput.RecoveryCodeTyped("finto".toCharArray())
      OnboardingStep.SECURITY_NOTICE -> StepInput.SecurityNotice(externalBackupDone = true, routerChecklistDone = false)
      OnboardingStep.SUMMARY -> StepInput.Finish
    }
  }

  @Test
  fun ilFondatoreSaltaRiprendeTornaIndietroEFinisceAlloStessoModo() = runTest(UnconfinedTestDispatcher()) {
    val p = Both(this)
    p.submit { StepInput.ChoosePath(OnboardingPath.FOUND_FAMILY) }
    p.stepsUntil(OnboardingStep.FOLDERS)
    // Un input di un altro passo non si accetta.
    p.submit { StepInput.Roles(indexer = true, preparer = true) }
    p.skip(OnboardingStep.FOLDERS)
    // Un passo non saltabile no.
    p.skip(OnboardingStep.BACKUP_SOURCES)
    p.stepsUntil(OnboardingStep.SUMMARY)
    // Dal riepilogo si riprende il passo saltato, non uno fatto.
    p.resume(OnboardingStep.VPN)
    p.resume(OnboardingStep.FOLDERS)
    // Fondata la famiglia, indietro si rivedono le aree ma non si cambiano.
    p.back()
    p.submit { StepInput.Areas("Public", "alessio") }
    p.resume(OnboardingStep.FOLDERS)
    p.step()
    p.submit { StepInput.Finish }
    // A onboarding finito: indietro non fa niente, un passo locale fatto si riapre (ON-7), uno del NAS no.
    p.back()
    p.resume(OnboardingStep.VPN)
    p.step()
    p.resume(OnboardingStep.AREAS)
    p.resume(OnboardingStep.SECURITY_NOTICE)
    p.step()
    // Il percorso non si cambia piu'.
    p.submit { StepInput.ChoosePath(OnboardingPath.JOIN_QR) }
  }

  @Test
  fun conIlNasSpentoSiRimandanoGliStessiPassi_E_O1() = runTest(UnconfinedTestDispatcher()) {
    val p = Both(this)
    p.submit { StepInput.ChoosePath(OnboardingPath.FOUND_FAMILY) }
    p.stepsUntil(OnboardingStep.ACCOUNT)
    p.f.env.tester.handler = { _, _, stages -> Reports.failed(stages, Stage.TCP, NasError.Timeout) }
    p.fake.scripted += StepResult.Problem(OnboardingProblem.NAS_NOT_FOUND)
    p.submit { credentials() }
    p.skip(OnboardingStep.ACCOUNT)
    p.stepsUntil(OnboardingStep.SUMMARY)
    // Senza la famiglia fondata non c'e' niente da finire.
    p.submit { StepInput.Finish }
  }

  @Test
  fun ilRecuperoDellAdminFinisceSoloConLAdminRecuperato() = runTest(UnconfinedTestDispatcher()) {
    val p = Both(this)
    // Il recupero ha senso dove c'e' una famiglia (punto 10): nell'area comune c'e' il registro.
    p.f.env.nas.share("NAS").put(NasLayout.registryFile(1), ByteArray(300) { 7 })
    p.submit { StepInput.ChoosePath(OnboardingPath.RECOVER_ADMIN) }
    p.stepsUntil(OnboardingStep.ROLES)
    assertWithMessage("recupero a meta'").that(p.fake.recoveryPending()).isEqualTo(p.real.recoveryPending())
    p.stepsUntil(OnboardingStep.SUMMARY)
    p.submit { StepInput.Finish }
    p.f.env.registry.status.value = TrustStatus.Approved(admin = true)
    p.fake.adminRecovered = true
    assertWithMessage("recupero fatto").that(p.fake.recoveryPending()).isEqualTo(p.real.recoveryPending())
    // Recuperato l'admin, le aree non si cambiano piu' (ON-12c).
    p.back()
    p.submit { StepInput.Finish }
  }

  /** Punto 10 delle prove sul campo: il recupero su un'area comune senza famiglia si ferma al passo 4 nei due, poi con un'altra area va avanti. */
  @Test
  fun ilRecuperoSuUnAreaSenzaFamigliaSiFermaAlloStessoModo() = runTest(UnconfinedTestDispatcher()) {
    val p = Both(this)
    p.fake.familyShares = setOf("Famiglia")
    p.f.env.probe.access = p.f.env.probe.access + ("Famiglia" to dev.pampa.mycloudgallery.core.nas.contract.NasResult.Ok(dev.pampa.mycloudgallery.core.nas.contract.ShareAccess.READ_WRITE))
    p.f.env.nas.share("Famiglia").put(NasLayout.registryFile(1), ByteArray(300) { 7 })
    p.submit { StepInput.ChoosePath(OnboardingPath.RECOVER_ADMIN) }
    p.stepsUntil(OnboardingStep.AREAS)
    p.submit { StepInput.Areas("NAS", "alessio") }
    p.submit { StepInput.Areas("Famiglia", "alessio") }
  }

  @Test
  fun ilCambioDiPercorsoRicominciaDaCapoNeiDue() = runTest(UnconfinedTestDispatcher()) {
    val p = Both(this)
    p.submit { StepInput.ChoosePath(OnboardingPath.FOUND_FAMILY) }
    p.stepsUntil(OnboardingStep.AREAS)
    p.submit { StepInput.ChoosePath(OnboardingPath.SECOND_ADMIN) }
    p.back()
    p.back()
    p.submit { StepInput.ChoosePath(OnboardingPath.JOIN_MANUAL) }
    // Nell'ingresso con invito indirizzo e account li fa JoinFlow: il coordinatore li rifiuta, vero e finto.
    p.submit { StepInput.NasAddressChosen(p.f.env.address) }
  }
}
