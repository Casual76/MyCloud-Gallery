package dev.pampa.mycloudgallery.core.family.onboarding

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.family.contract.OnboardingPath
import dev.pampa.mycloudgallery.core.family.contract.OnboardingProblem
import dev.pampa.mycloudgallery.core.family.contract.OnboardingProgress
import dev.pampa.mycloudgallery.core.family.contract.OnboardingStep
import dev.pampa.mycloudgallery.core.nas.contract.AuthBlockReason
import dev.pampa.mycloudgallery.core.nas.contract.InsecureKind
import dev.pampa.mycloudgallery.core.nas.contract.NasError
import dev.pampa.mycloudgallery.core.network.contract.DenyReason
import org.junit.Test

/** Le tabelle pure dell'onboarding: errore del NAS → problema da mostrare, e piani dei percorsi. */
class OnboardingLogicTest {

  @Test
  fun ogniErroreDelNasHaIlSuoProblema() {
    val table = listOf(
      NasError.Auth(AuthBlockReason.AUTH_FAILED) to OnboardingProblem.AUTH_FAILED,
      NasError.Auth(AuthBlockReason.ACCOUNT_LOCKED) to OnboardingProblem.ACCOUNT_LOCKED,
      NasError.Auth(AuthBlockReason.ACCOUNT_DISABLED) to OnboardingProblem.ACCOUNT_DISABLED,
      NasError.Auth(AuthBlockReason.PASSWORD_EXPIRED) to OnboardingProblem.PASSWORD_EXPIRED,
      NasError.Auth(AuthBlockReason.ACCOUNT_RESTRICTED) to OnboardingProblem.ACCOUNT_RESTRICTED,
      NasError.AuthBlocked(AuthBlockReason.ACCOUNT_LOCKED) to OnboardingProblem.ACCOUNT_LOCKED,
      NasError.InsecureSession(InsecureKind.GUEST) to OnboardingProblem.NAS_INSECURE,
      NasError.InsecureSession(InsecureKind.ANONYMOUS) to OnboardingProblem.NAS_INSECURE,
      NasError.InsecureSession(InsecureKind.NO_ENCRYPTION) to OnboardingProblem.NAS_INSECURE,
      NasError.InsecureSession(InsecureKind.DIALECT) to OnboardingProblem.NAS_INSECURE,
      NasError.InsecureSession(InsecureKind.SIGNING_NOT_REQUIRED) to OnboardingProblem.NAS_INSECURE,
      NasError.IdentityMismatch to OnboardingProblem.IDENTITY_MISMATCH,
      NasError.LocalNetworkBlocked to OnboardingProblem.LOCAL_NETWORK_DENIED,
      NasError.ServiceDown to OnboardingProblem.NAS_SERVICE_DOWN,
      NasError.Timeout to OnboardingProblem.NAS_NOT_FOUND,
      NasError.Stalled to OnboardingProblem.NAS_NOT_FOUND,
      NasError.SessionExpired to OnboardingProblem.NAS_NOT_FOUND,
      NasError.NotFound to OnboardingProblem.NAS_NOT_FOUND,
      NasError.Unexpected(0xC0000001L) to OnboardingProblem.NAS_NOT_FOUND,
      NasError.PermissionDenied to OnboardingProblem.NO_ACCESSIBLE_SHARE,
      NasError.ShareMissing("x") to OnboardingProblem.NO_ACCESSIBLE_SHARE,
      NasError.NasFull to OnboardingProblem.NAS_FULL,
      NasError.QuotaExceeded to OnboardingProblem.NAS_FULL,
      NasError.FileBusy to OnboardingProblem.NAS_WRITE_FAILED,
      NasError.Exists to OnboardingProblem.NAS_WRITE_FAILED,
      NasError.CredentialsMissing to OnboardingProblem.AUTH_FAILED,
    )
    for ((error, expected) in table) {
      assertWithMessage("$error").that(NasProblems.reach(error)).isEqualTo(expected)
    }
  }

  @Test
  fun unPercorsoNegatoDipendeDaDoveSiE() {
    val expected = mapOf(
      DenyReason.OFFLINE to (OnboardingProblem.NOT_ON_WIFI to OnboardingProblem.NOT_AT_HOME),
      DenyReason.CAPTIVE_PORTAL to (OnboardingProblem.NOT_ON_WIFI to OnboardingProblem.NOT_AT_HOME),
      DenyReason.UNTRUSTED_NETWORK to (OnboardingProblem.NOT_ON_WIFI to OnboardingProblem.NOT_AT_HOME),
      DenyReason.VPN_OFF_AWAY to (OnboardingProblem.NOT_ON_WIFI to OnboardingProblem.NOT_AT_HOME),
      DenyReason.NOT_CONFIGURED to (OnboardingProblem.NOT_ON_WIFI to OnboardingProblem.NOT_AT_HOME),
      DenyReason.FOREIGN_VPN to (OnboardingProblem.VPN_ACTIVE to OnboardingProblem.VPN_ACTIVE),
      DenyReason.LOCAL_NETWORK_DENIED to (OnboardingProblem.LOCAL_NETWORK_DENIED to OnboardingProblem.LOCAL_NETWORK_DENIED),
    )
    assertThat(expected.keys).containsExactlyElementsIn(DenyReason.entries)
    for ((reason, pair) in expected) {
      assertThat(NasProblems.reach(NasError.NotAllowedHere(reason))).isEqualTo(pair.first)
      assertThat(NasProblems.reach(NasError.NotAllowedHere(reason), joining = true)).isEqualTo(pair.second)
    }
  }

  @Test
  fun inScritturaUnGuastoDiReteESoloScritturaNonRiuscita() {
    for (error in listOf(NasError.Timeout, NasError.Stalled, NasError.SessionExpired, NasError.NotFound, NasError.ServiceDown, NasError.PermissionDenied, NasError.ShareMissing("x"))) {
      assertWithMessage("$error").that(NasProblems.write(error)).isEqualTo(OnboardingProblem.NAS_WRITE_FAILED)
    }
    assertThat(NasProblems.write(NasError.NasFull)).isEqualTo(OnboardingProblem.NAS_FULL)
    assertThat(NasProblems.write(NasError.QuotaExceeded)).isEqualTo(OnboardingProblem.NAS_FULL)
    assertThat(NasProblems.write(NasError.Auth(AuthBlockReason.AUTH_FAILED))).isEqualTo(OnboardingProblem.AUTH_FAILED)
    assertThat(NasProblems.write(NasError.LocalNetworkBlocked)).isEqualTo(OnboardingProblem.LOCAL_NETWORK_DENIED)
  }

  @Test
  fun soloIlNasChiusoOSenzaServizioOffreContinuaPiuTardi_E_O1() {
    assertThat(NasProblems.isDeferrable(OnboardingProblem.NAS_NOT_FOUND)).isTrue()
    assertThat(NasProblems.isDeferrable(OnboardingProblem.NAS_SERVICE_DOWN)).isTrue()
    OnboardingProblem.entries.filter { it != OnboardingProblem.NAS_NOT_FOUND && it != OnboardingProblem.NAS_SERVICE_DOWN }.forEach {
      assertWithMessage("$it").that(NasProblems.isDeferrable(it)).isFalse()
    }
  }

  @Test
  fun iPianiDeiPercorsiHannoSempreBenvenutoERiepilogo() {
    for (path in OnboardingPath.entries) {
      val plan = OnboardingPlans.planFor(path)
      assertThat(plan.first()).isEqualTo(OnboardingStep.WELCOME)
      assertThat(plan.last()).isEqualTo(OnboardingStep.SUMMARY)
      assertThat(plan).containsNoDuplicates()
      assertThat(plan.map { it.number }).isInOrder()
    }
    assertThat(OnboardingPlans.planFor(null)).isEqualTo(OnboardingStep.entries.toList())
    // I passi che "Continua piu' tardi" rimanda sono quelli che il contratto dice non locali.
    assertThat(OnboardingPlans.NAS_DEPENDENT).containsExactly(
      OnboardingStep.NAS_ADDRESS, OnboardingStep.ACCOUNT, OnboardingStep.AREAS, OnboardingStep.FOLDERS, OnboardingStep.FAMILY_KEY,
    )
    assertThat(OnboardingPlans.isAdminPath(OnboardingPath.FOUND_FAMILY)).isTrue()
    assertThat(OnboardingPlans.isAdminPath(OnboardingPath.SECOND_ADMIN)).isTrue()
    assertThat(OnboardingPlans.isAdminPath(OnboardingPath.JOIN_QR)).isFalse()
    assertThat(OnboardingPlans.isAdminPath(null)).isFalse()
  }

  @Test
  fun ilPassoSuccessivoSaltaQuelliFattiESaltatiEMentreSiRiprendeVaAiDaCompletare() {
    val plan = OnboardingStep.entries.toList()
    val done = setOf(OnboardingStep.WELCOME, OnboardingStep.NAS_ADDRESS, OnboardingStep.FOLDERS)
    val skipped = setOf(OnboardingStep.ACCOUNT, OnboardingStep.BATTERY)
    // Normale: dopo NAS_ADDRESS il primo che non e' ne' fatto ne' saltato e' AREAS.
    assertThat(OnboardingPlans.nextAfter(plan, done, skipped, OnboardingStep.NAS_ADDRESS, resuming = false)).isEqualTo(OnboardingStep.AREAS)
    // Dopo l'ultimo non c'e' altro.
    assertThat(OnboardingPlans.nextAfter(plan, plan.toSet(), emptySet(), OnboardingStep.SUMMARY, resuming = false)).isNull()
    // Riprendendo: il prossimo "da completare" dopo il passo, o niente.
    assertThat(OnboardingPlans.nextAfter(plan, done, skipped, OnboardingStep.ACCOUNT, resuming = true)).isEqualTo(OnboardingStep.BATTERY)
    assertThat(OnboardingPlans.nextAfter(plan, done, skipped, OnboardingStep.BATTERY, resuming = true)).isNull()
  }

  @Test
  fun completingAvanzaSenzaPerdereLeScelteEKeepPendingLasciaIlPassoDaCompletare() {
    val start = OnboardingSnapshot.EMPTY.copy(
      progress = OnboardingProgress(
        path = OnboardingPath.JOIN_QR,
        plan = OnboardingPlans.planFor(OnboardingPath.JOIN_QR),
        current = OnboardingStep.BATTERY,
        completed = setOf(OnboardingStep.WELCOME),
        skipped = emptySet(),
        startedAt = 7L,
      ),
      deferrable = true,
    )
    val (done, next) = start.completing(OnboardingStep.BATTERY)
    assertThat(next).isEqualTo(OnboardingStep.VPN)
    assertThat(done.progress.current).isEqualTo(OnboardingStep.VPN)
    assertThat(done.progress.completed).contains(OnboardingStep.BATTERY)
    assertThat(done.deferrable).isFalse()
    assertThat(done.progress.startedAt).isEqualTo(7L)

    val (unverified, _) = start.completing(OnboardingStep.BATTERY, keepPending = true)
    assertThat(unverified.progress.completed).doesNotContain(OnboardingStep.BATTERY)
    assertThat(unverified.progress.pending).containsExactly(OnboardingStep.BATTERY)
    assertThat(unverified.progress.current).isEqualTo(OnboardingStep.VPN)
  }
}
