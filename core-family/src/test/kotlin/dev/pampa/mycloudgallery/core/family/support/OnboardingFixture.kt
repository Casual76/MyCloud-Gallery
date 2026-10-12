package dev.pampa.mycloudgallery.core.family.support

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.family.contract.OnboardingPath
import dev.pampa.mycloudgallery.core.family.contract.OnboardingStep
import dev.pampa.mycloudgallery.core.family.contract.PreviewQuality
import dev.pampa.mycloudgallery.core.family.contract.StepInput
import dev.pampa.mycloudgallery.core.family.contract.StepResult
import dev.pampa.mycloudgallery.core.family.onboarding.DefaultOnboardingCoordinator
import dev.pampa.mycloudgallery.core.family.testing.FakeFolderSurvey
import dev.pampa.mycloudgallery.core.rules.Preset
import kotlinx.coroutines.test.TestScope

/** Il coordinatore con tutti i suoi fake: [coordinator] ne costruisce uno nuovo sullo stesso avanzamento (il processo e' morto e riparte). */
class OnboardingFixture(
  private val scope: TestScope,
  val env: TestEnv = TestEnv(),
  val store: InMemoryOnboardingStateStore = InMemoryOnboardingStateStore(),
) {
  val survey = FakeFolderSurvey()

  fun coordinator(): DefaultOnboardingCoordinator = DefaultOnboardingCoordinator(store, env.ports(), survey, scope.backgroundScope)

  val current: OnboardingStep get() = store.snapshot.progress.current
}

fun expectAdvanced(result: StepResult): StepResult.Advanced {
  assertThat(result).isInstanceOf(StepResult.Advanced::class.java)
  return result as StepResult.Advanced
}

/** Il codice che la schermata mostra, scritto come lo riscrive l'utente. */
suspend fun DefaultOnboardingCoordinator.shownCode(): String {
  val display = recoveryCode()
  return display.groups.joinToString("-") { String(it) }
}

/**
 * Fa il passo corrente del percorso del fondatore con risposte "normali" (tutto va bene) e restituisce l'esito.
 * Ai passi con piu' tempi (2 e 12) fa tutti i tempi.
 */
suspend fun OnboardingFixture.doStep(c: DefaultOnboardingCoordinator): StepResult =
  when (current) {
    OnboardingStep.WELCOME -> c.submit(StepInput.ChoosePath(OnboardingPath.FOUND_FAMILY))
    OnboardingStep.NAS_ADDRESS -> {
      expectAdvanced(c.submit(StepInput.ConfirmHome(true)))
      c.submit(StepInput.NasAddressChosen(env.address))
    }
    OnboardingStep.ACCOUNT -> c.submit(credentials())
    OnboardingStep.AREAS -> c.submit(StepInput.Areas("NAS", "alessio"))
    OnboardingStep.FOLDERS -> c.submit(StepInput.Folders(setOf("Musica", "Film/Serie")))
    OnboardingStep.BACKUP_SOURCES -> c.submit(StepInput.BackupSources(camera = true, screenshots = false))
    OnboardingStep.ROLES -> c.submit(StepInput.Roles(indexer = true, preparer = false))
    OnboardingStep.PRESET -> c.submit(StepInput.PresetChoice(Preset.BALANCED, PreviewQuality.STANDARD))
    OnboardingStep.PERMISSIONS -> c.submit(StepInput.PermissionsReviewed)
    OnboardingStep.BATTERY -> c.submit(StepInput.BatteryReviewed)
    OnboardingStep.VPN -> c.submit(StepInput.VpnSetup(remoteControl = false, tunnelName = null))
    OnboardingStep.FAMILY_KEY -> {
      val code = c.shownCode()
      c.submit(StepInput.RecoveryCodeTyped(code.toCharArray()))
    }
    OnboardingStep.SECURITY_NOTICE -> c.submit(StepInput.SecurityNotice(externalBackupDone = true, routerChecklistDone = false))
    OnboardingStep.SUMMARY -> c.submit(StepInput.Finish)
  }

/** Fa i passi del fondatore finche' il passo corrente non e' [until]. */
suspend fun OnboardingFixture.runFounderUntil(c: DefaultOnboardingCoordinator, until: OnboardingStep) {
  var guard = 0
  while (current != until) {
    expectAdvanced(doStep(c))
    check(++guard < 40) { "il percorso non arriva a $until" }
  }
}
