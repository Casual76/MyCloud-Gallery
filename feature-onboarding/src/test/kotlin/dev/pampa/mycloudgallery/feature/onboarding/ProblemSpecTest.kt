package dev.pampa.mycloudgallery.feature.onboarding

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.family.contract.OnboardingProblem
import dev.pampa.mycloudgallery.feature.onboarding.ui.NoticeTone
import dev.pampa.mycloudgallery.feature.onboarding.ui.ProblemAction
import dev.pampa.mycloudgallery.feature.onboarding.ui.spec
import org.junit.Test

/** Le schede dei problemi: ognuno dei 29 ne ha una, con il tono giusto e i bottoni che servono. */
class ProblemSpecTest {

  @Test
  fun ognunoDeiProblemiHaUnaScheda() {
    assertThat(OnboardingProblem.entries.map { it.spec() }).hasSize(OnboardingProblem.entries.size)
  }

  @Test
  fun gliErroriDiSicurezzaSonoErroriNonAvvisi() {
    listOf(
      OnboardingProblem.NAS_INSECURE,
      OnboardingProblem.IDENTITY_MISMATCH,
      OnboardingProblem.AUTH_FAILED,
      OnboardingProblem.ACCOUNT_LOCKED,
      OnboardingProblem.INVITE_OTHER_FAMILY,
      OnboardingProblem.REGISTRY_UNTRUSTED,
    ).forEach { assertThat(it.spec().tone).isEqualTo(NoticeTone.Danger) }
  }

  @Test
  fun unNasSpentoSiPuoRiprovareOLasciarePerDopo() {
    val actions = OnboardingProblem.NAS_NOT_FOUND.spec().actions
    assertThat(actions).containsExactly(ProblemAction.RETRY, ProblemAction.CONTINUE_LATER).inOrder()
  }

  @Test
  fun quiCeGiaUnaFamigliaOffreEntrareORecuperare() {
    val actions = OnboardingProblem.FAMILY_ALREADY_EXISTS.spec().actions
    assertThat(actions).containsExactly(ProblemAction.JOIN_AS_MEMBER, ProblemAction.RECOVER_ADMIN).inOrder()
  }

  @Test
  fun unaPasswordSbagliataNonOffreUnRiproviAutomatico() {
    // Un solo tentativo (E-R8): nessun bottone "Riprova" per gli errori d'autenticazione.
    listOf(
      OnboardingProblem.AUTH_FAILED,
      OnboardingProblem.ACCOUNT_LOCKED,
      OnboardingProblem.ACCOUNT_DISABLED,
      OnboardingProblem.PASSWORD_EXPIRED,
      OnboardingProblem.ACCOUNT_RESTRICTED,
    ).forEach { assertThat(it.spec().actions).doesNotContain(ProblemAction.RETRY) }
  }

  @Test
  fun iProblemiCheFermanoLaPaginaOccupanoLaPagina() {
    listOf(
      OnboardingProblem.NO_ACCESSIBLE_SHARE,
      OnboardingProblem.FAMILY_ALREADY_EXISTS,
      OnboardingProblem.INVITE_INVALID,
      OnboardingProblem.INVITE_OTHER_FAMILY,
      OnboardingProblem.INVITE_EXPIRED,
      OnboardingProblem.NOT_AT_HOME,
    ).forEach { assertThat(it.spec().big).isTrue() }
  }
}
