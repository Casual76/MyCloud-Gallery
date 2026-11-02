package dev.pampa.mycloudgallery.feature.onboarding

import android.app.Application
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.onRoot
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.designsystem.testing.A11yAssertions
import dev.pampa.mycloudgallery.core.designsystem.testing.A11yConfig
import com.github.takahirom.roborazzi.checkRoboAccessibility
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Fase 10, P8 (D23, D25-D27, E-AC9, §2.7 "Onboarding, famiglia, QR", D28): **ogni** passo e ogni stato del catalogo ([ShotCatalog], gli stessi degli
 * screenshot di P6 della Fase 2) letto come lo legge TalkBack, a testo 1,0 e 2,0: nome e ruolo per ogni elemento attivo, bersagli da 48 dp misurati,
 * nessun id o nome di file, testo non tagliato. I codici si leggono a gruppi e il QR ha l'alternativa testuale (il percorso "Senza QR"): sono
 * gia' provati da `OnboardingAccessibilityTest`; qui c'e' il controllo comune su tutto il catalogo.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = Devices.PHONE_TALL)
internal class OnboardingCatalogAccessibilityTest(private val shot: Shot) : OnboardingComposeBase() {

  private fun root(): SemanticsNode = composeRule.onRoot().fetchSemanticsNode()

  private fun check(fontScale: Float?) {
    show(fontScale = fontScale) {
      OnboardingFrame(progress = shot.progress, sidebarStep = shot.step, ambientStep = shot.step) { shot.content() }
    }
    val found = A11yAssertions.check(root(), A11yConfig(density = composeRule.density))
    assertWithMessage("${shot.slug} a testo ${fontScale ?: 1.0f}: " + found.joinToString(" | ")).that(found).isEmpty()
  }

  @Test
  fun aTestoNormale() = check(null)

  @Test
  fun aTestoDoppio() = check(2.0f)

  companion object {
    @JvmStatic
    @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
    fun data(): List<Array<Any>> = ShotCatalog.all.map { arrayOf<Any>(it) }
  }

  // --- ATF (D28): il controllo automatico indipendente, sulle schermate principali del modulo -----------------------------------------

  @Test
  fun atf() {
    show { OnboardingFrame(progress = shot.progress, sidebarStep = shot.step, ambientStep = shot.step) { shot.content() } }
    composeRule.onRoot().checkRoboAccessibility()
  }

}

