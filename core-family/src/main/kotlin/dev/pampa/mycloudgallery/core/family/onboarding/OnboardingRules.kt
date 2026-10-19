package dev.pampa.mycloudgallery.core.family.onboarding

import dev.pampa.mycloudgallery.core.family.contract.OnboardingPath
import dev.pampa.mycloudgallery.core.family.contract.OnboardingProgress
import dev.pampa.mycloudgallery.core.family.contract.OnboardingStep
import dev.pampa.mycloudgallery.core.family.contract.StepInput

/**
 * Le regole dei passi, pure (senza I/O): quale input si accetta, "salta", "indietro", "riprendi", il cambio di percorso
 * e cio' che serve per dire "fatto". Le usa [DefaultOnboardingCoordinator] e, **identiche**, il coordinatore finto dei test
 * delle schermate (`FakeOnboardingCoordinator`): prima il finto aveva regole sue e nascondeva ON-3 e ON-7 della verifica
 * funzionale (riprendeva i passi "saltati" invece di quelli "da completare", tornava indietro a onboarding finito, finiva
 * sempre).
 *
 * [adminApproved] e' lo stato di questo dispositivo nel registro ("approvato, admin"): lo legge chi chiama.
 */
internal object OnboardingRules {

  /** Indirizzo, account e aree: dopo la fondazione, la richiesta inviata o l'admin recuperato stanno nella configurazione firmata. */
  val SETTLED_STEPS: Set<OnboardingStep> = setOf(OnboardingStep.NAS_ADDRESS, OnboardingStep.ACCOUNT, OnboardingStep.AREAS)

  /**
   * I passi che a onboarding finito si possono rifare anche se fatti (dalle Impostazioni: il controllo di WireGuard, la
   * checklist del backup, ON-7): quelli locali, 6-11 e 13. Mai quelli del NAS ne' il benvenuto e il riepilogo.
   */
  val REOPENABLE_WHEN_FINISHED: Set<OnboardingStep> =
    OnboardingStep.entries.filter { it.local && it != OnboardingStep.WELCOME && it != OnboardingStep.SUMMARY }.toSet()

  fun isRecoveryPath(path: OnboardingPath?): Boolean = path == OnboardingPath.RECOVER_ADMIN || path == OnboardingPath.SECOND_ADMIN

  /** Fondata la famiglia, inviata la richiesta o recuperato l'admin (ON-12c): percorso e passi 2-4 non si rifanno. */
  fun settled(s: OnboardingSnapshot, adminApproved: Boolean): Boolean =
    s.foundation?.stage == FamilyKeyStage.FOUNDED || s.join?.registered == true || (isRecoveryPath(s.path) && adminApproved)

  fun stepOf(input: StepInput): OnboardingStep = when (input) {
    is StepInput.ChoosePath -> OnboardingStep.WELCOME
    is StepInput.ConfirmHome, is StepInput.NasAddressChosen -> OnboardingStep.NAS_ADDRESS
    is StepInput.Credentials -> OnboardingStep.ACCOUNT
    is StepInput.Areas -> OnboardingStep.AREAS
    is StepInput.Folders -> OnboardingStep.FOLDERS
    is StepInput.BackupSources -> OnboardingStep.BACKUP_SOURCES
    is StepInput.Roles -> OnboardingStep.ROLES
    is StepInput.PresetChoice -> OnboardingStep.PRESET
    is StepInput.PermissionsReviewed -> OnboardingStep.PERMISSIONS
    is StepInput.BatteryReviewed -> OnboardingStep.BATTERY
    is StepInput.VpnSetup -> OnboardingStep.VPN
    is StepInput.RecoveryCodeTyped -> OnboardingStep.FAMILY_KEY
    is StepInput.SecurityNotice -> OnboardingStep.SECURITY_NOTICE
    is StepInput.Finish -> OnboardingStep.SUMMARY
  }

  /**
   * L'input si accetta: e' del passo corrente (nel piano) e non rifa' un passo gia' fissato. Il cambio di percorso si
   * accetta anche dal passo in cui compare "Qui c'e' gia' una famiglia" (4 o 12(e)), fino a che niente e' fissato.
   */
  fun accepts(s: OnboardingSnapshot, input: StepInput, adminApproved: Boolean): Boolean {
    val settled = settled(s, adminApproved)
    if (input is StepInput.ChoosePath) return !settled
    val step = stepOf(input)
    val p = s.progress
    if (p.current != step || step !in p.plan) return false
    return !(settled && step in SETTLED_STEPS)
  }

  /** Il percorso scelto: si riparte da capo con il suo piano (al passo 2), tenendo solo l'ora d'inizio. */
  fun choosePath(s: OnboardingSnapshot, path: OnboardingPath, now: Long): OnboardingSnapshot {
    val plan = OnboardingPlans.planFor(path)
    return OnboardingSnapshot.EMPTY.copy(
      progress = OnboardingProgress(
        path = path,
        plan = plan,
        current = plan[1],
        completed = setOf(OnboardingStep.WELCOME),
        skipped = emptySet(),
        startedAt = s.progress.startedAt ?: now,
      ),
    )
  }

  /**
   * "Salta" un passo saltabile, oppure (E-O1, "Continua piu' tardi") il passo corrente con tutti quelli che hanno bisogno
   * del NAS quando l'ultimo tentativo non l'ha trovato. Null se non si puo'.
   *
   * @return lo snapshot nuovo e il passo dopo (null = il riepilogo).
   */
  fun skip(s: OnboardingSnapshot, step: OnboardingStep): Pair<OnboardingSnapshot, OnboardingStep?>? {
    val p = s.progress
    if (step != p.current || step !in p.plan) return null
    val later = step in OnboardingPlans.NAS_DEPENDENT && s.deferrable
    if (!step.skippable && !later) return null
    val skipping = if (later) OnboardingPlans.NAS_DEPENDENT.filter { it in p.plan && it !in p.completed }.toSet() else setOf(step)
    val skipped = p.skipped + skipping
    // I passi appena rimandati non sono "il prossimo" nemmeno quando si sta riprendendo un passo dal riepilogo: con il
    // NAS ancora spento si torna ai passi locali o al riepilogo, non al passo 3, poi al 4...
    val next = OnboardingPlans.nextAfter(p.plan, p.completed + (skipping - step), skipped, step, s.resuming)
    val updated = s.copy(
      progress = p.copy(skipped = skipped, current = next ?: p.plan.last()),
      resuming = s.resuming && next != null,
      deferrable = false,
    )
    return updated to next
  }

  /**
   * "Indietro": il passo prima nel piano, mai prima del benvenuto e mai a onboarding finito. Fissati indirizzo, account e aree
   * (famiglia fondata, richiesta inviata, admin recuperato) non si torna a quei passi: non si possono piu' confermare, e la
   * pagina restava senza un "avanti" (ON-12c: con l'admin recuperato le aree non si rifanno).
   */
  fun back(s: OnboardingSnapshot, adminApproved: Boolean): OnboardingSnapshot {
    val p = s.progress
    val index = p.plan.indexOf(p.current)
    if (s.finished || index <= 0) return s
    val previous = p.plan[index - 1]
    if (settled(s, adminApproved) && previous in SETTLED_STEPS) return s
    return s.copy(progress = p.copy(current = previous), resuming = false)
  }

  /**
   * Il passo si puo' riaprire: e' da completare (saltato e non fatto), oppure, a onboarding finito, e' un passo locale
   * del piano gia' fatto ([REOPENABLE_WHEN_FINISHED], ON-7).
   */
  fun canResume(s: OnboardingSnapshot, step: OnboardingStep): Boolean {
    val p = s.progress
    if (step in p.pending) return true
    return s.finished && step in p.plan && step in p.completed && step in REOPENABLE_WHEN_FINISHED
  }

  /** "Riprendi": il passo diventa quello corrente; finito quello si va al prossimo da completare (o si esce). */
  fun resume(s: OnboardingSnapshot, step: OnboardingStep): OnboardingSnapshot? =
    if (canResume(s, step)) s.copy(progress = s.progress.copy(current = step), resuming = true) else null

  /** Cio' che deve esserci per dire "fatto": la famiglia fondata, la richiesta inviata o l'admin recuperato. */
  fun essentialsDone(s: OnboardingSnapshot, adminApproved: Boolean): Boolean = when (s.path) {
    OnboardingPath.FOUND_FAMILY -> s.foundation?.stage == FamilyKeyStage.FOUNDED
    OnboardingPath.JOIN_QR, OnboardingPath.JOIN_MANUAL -> s.join?.registered == true
    OnboardingPath.RECOVER_ADMIN, OnboardingPath.SECOND_ADMIN -> adminApproved
    null -> false
  }

  /**
   * Il recupero dell'admin (o il secondo admin) e' a meta': aree scelte (il kit si cerca nell'area privata, il registro in
   * quella comune), l'admin non ancora recuperato, l'onboarding non finito (ON-3).
   */
  fun recoveryPending(s: OnboardingSnapshot, adminApproved: Boolean): Boolean =
    isRecoveryPath(s.path) && !s.finished && OnboardingStep.AREAS in s.progress.completed && !adminApproved
}
