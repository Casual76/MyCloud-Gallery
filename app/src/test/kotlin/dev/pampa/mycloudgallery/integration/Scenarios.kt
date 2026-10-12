package dev.pampa.mycloudgallery.integration

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.family.contract.FamilyInvite
import dev.pampa.mycloudgallery.core.family.contract.InviteParams
import dev.pampa.mycloudgallery.core.family.contract.JoinStep
import dev.pampa.mycloudgallery.core.family.contract.OnboardingPath
import dev.pampa.mycloudgallery.core.family.contract.OnboardingStep
import dev.pampa.mycloudgallery.core.family.contract.PreviewQuality
import dev.pampa.mycloudgallery.core.family.contract.SetupState
import dev.pampa.mycloudgallery.core.family.contract.StepInput
import dev.pampa.mycloudgallery.core.family.contract.StepResult
import dev.pampa.mycloudgallery.core.network.contract.NasAddress
import dev.pampa.mycloudgallery.core.rules.Preset
import dev.pampa.mycloudgallery.graph.AppGraph

// I percorsi dell'onboarding fatti come li farebbe l'utente, sul grafo vero: li usano i test d'integrazione e gli
// screenshot dei flussi integrati (P9).

val TEST_NAS_ADDRESS = NasAddress(TestNas.IP, "MyCloudEX2Ultra.fritz.box")

private const val MAX_STEPS = 20

fun advanced(result: StepResult): StepResult.Advanced {
  assertThat(result).isInstanceOf(StepResult.Advanced::class.java)
  return result as StepResult.Advanced
}

/**
 * Il passo e' andato avanti e l'avanzamento salvato (DataStore) e' arrivato al coordinatore: come nell'app, dove la
 * pagina successiva compare solo quando `progress` cambia, il passo dopo parte solo da li'.
 */
suspend fun AppGraph.settle(result: StepResult): StepResult.Advanced {
  val advanced = advanced(result)
  // Senza un passo dopo si torna al riepilogo (l'ultimo passo di ogni percorso).
  val target = advanced.next ?: OnboardingStep.SUMMARY
  coordinator.progress.await { it.current == target }
  return advanced
}

/** I 14 passi del fondatore (account `test`, area comune `test-comune`, privata `test`) sul NAS simulato. */
suspend fun AppGraph.foundFamily() {
  settle(coordinator.submit(StepInput.ChoosePath(OnboardingPath.FOUND_FAMILY)))
  nasSteps()
  localSteps(until = OnboardingStep.FAMILY_KEY)
  familyKey()
  localSteps(until = OnboardingStep.SUMMARY)
  settle(coordinator.submit(StepInput.Finish))
  awaitSetup { it is SetupState.Ready }
}

/** I passi che usano il NAS (2, 3, 4, 5 e, se tocca, 12) a partire da quello corrente. */
suspend fun AppGraph.nasSteps() {
  val c = coordinator
  repeat(MAX_STEPS) {
    when (c.progress.value.current) {
      OnboardingStep.NAS_ADDRESS -> {
        settle(c.submit(StepInput.ConfirmHome(true)))
        settle(c.submit(StepInput.NasAddressChosen(TEST_NAS_ADDRESS)))
      }
      OnboardingStep.ACCOUNT -> settle(c.submit(StepInput.Credentials(TestNas.ADMIN_USER, TestNas.ADMIN_PASSWORD.toCharArray())))
      OnboardingStep.AREAS -> {
        val proposal = c.proposeAreas(listOf(TestNas.COMMON))
        assertThat(proposal.map { it.share }).contains(TestNas.COMMON)
        settle(c.submit(StepInput.Areas(TestNas.COMMON, TestNas.ADMIN_USER)))
      }
      OnboardingStep.FOLDERS -> settle(c.submit(StepInput.Folders(emptySet())))
      OnboardingStep.FAMILY_KEY -> familyKey()
      else -> return
    }
  }
}

/** Il passo 12: il codice mostrato, riscritto per intero; kit e fondazione sul NAS. */
suspend fun AppGraph.familyKey() {
  val code = coordinator.recoveryCode().use { shown -> shown.groups.joinToString("-") { String(it) } }
  settle(coordinator.submit(StepInput.RecoveryCodeTyped(code.toCharArray())))
}

/** I passi locali (6-11, 13) fino a [until]. */
suspend fun AppGraph.localSteps(until: OnboardingStep) {
  repeat(MAX_STEPS) {
    val step = coordinator.progress.value.current
    if (step == until) return
    val input = when (step) {
      OnboardingStep.BACKUP_SOURCES -> StepInput.BackupSources(camera = true, screenshots = false)
      OnboardingStep.ROLES -> StepInput.Roles(indexer = true, preparer = false)
      OnboardingStep.PRESET -> StepInput.PresetChoice(Preset.BALANCED, PreviewQuality.STANDARD)
      OnboardingStep.PERMISSIONS -> StepInput.PermissionsReviewed
      OnboardingStep.BATTERY -> StepInput.BatteryReviewed
      OnboardingStep.VPN -> StepInput.VpnSetup(remoteControl = false, tunnelName = null)
      OnboardingStep.SECURITY_NOTICE -> StepInput.SecurityNotice(externalBackupDone = true, routerChecklistDone = true)
      else -> error("passo non locale: $step")
    }
    settle(coordinator.submit(input))
  }
}

/** L'admin crea l'invito per `test2` (QR d'invito). */
suspend fun AppGraph.inviteMember(): FamilyInvite =
  registry.createInvite(InviteParams(TestNas.MEMBER_USER, "Tablet di prova", TestNas.MEMBER_USER, presetName = null))

/** Il familiare entra con l'invito, finisce i passi sul telefono e resta in attesa dell'approvazione. */
suspend fun AppGraph.joinWith(invite: FamilyInvite): JoinStep.AwaitingApproval {
  awaitSetup { it == SetupState.NotConfigured }
  settle(coordinator.submit(StepInput.ChoosePath(OnboardingPath.JOIN_QR)))
  assertThat(joinFlow.start(invite)).isEqualTo(JoinStep.NeedsPassword)
  assertThat(joinFlow.authenticate(TestNas.MEMBER_PASSWORD.toCharArray())).isInstanceOf(JoinStep.NeedsName::class.java)
  val awaiting = joinFlow.register("Tablet di prova") as JoinStep.AwaitingApproval
  coordinator.progress.await { it.current == OnboardingStep.ROLES }
  localSteps(until = OnboardingStep.SUMMARY)
  settle(coordinator.submit(StepInput.Finish))
  awaitSetup { it is SetupState.AwaitingApproval }
  return awaiting
}
