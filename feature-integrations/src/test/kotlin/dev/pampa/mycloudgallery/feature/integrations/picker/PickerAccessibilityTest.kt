package dev.pampa.mycloudgallery.feature.integrations.picker

import android.app.Application
import androidx.compose.runtime.Composable
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.onRoot
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.designsystem.testing.A11yAssertions
import dev.pampa.mycloudgallery.core.designsystem.testing.A11yConfig
import dev.pampa.mycloudgallery.feature.integrations.provider.NasUnreachableReason
import com.github.takahirom.roborazzi.checkRoboAccessibility
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Fase 10, P8 (D23, D25-D27, §2.7 "Fogli di conferma" e "Ovunque", D28): il selettore per le altre app letto come lo legge TalkBack. Gli stati del
 * catalogo (griglia a scelta multipla e a un tocco, avvisi, bloccata, non pronta, in consegna), a testo 1,0 e 2,0: nome e ruolo per ogni elemento
 * attivo, bersagli da 48 dp misurati, nessun id o nome di file, testo non tagliato.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = PHONE_QUALIFIERS)
class PickerAccessibilityTest : PickerComposeBase() {

  private val library = PickerSamples.library(count = 60)
  private val env = PickerSamples.env(library)
  private val multi = PickerSamples.request(multiple = true)
  private val single = PickerSamples.request(multiple = false)

  private fun root(): SemanticsNode = composeRule.onRoot().fetchSemanticsNode()

  private fun config() = A11yConfig(density = composeRule.density)

  @Composable
  private fun Screen(ui: PickerUi, request: PickerRequest = multi) = PickerScreen(ui = ui, request = request, env = env, actions = PickerActions())

  /** Mostra [content] e controlla le regole a testo 1,0 e poi a 2,0 sulla stessa scena (la base ha la scala come stato). */
  private fun clean(name: String, content: @Composable () -> Unit) {
    show(content = content)
    for (value in listOf(1f, 2f)) {
      fontScale.value = value
      composeRule.waitForIdle()
      val found = A11yAssertions.check(root(), config())
      assertWithMessage("$name a testo $value: " + found.joinToString(" | ")).that(found).isEmpty()
    }
  }

  @Test fun treScelti() = clean("tre scelti") { Screen(PickerSamples.grid(library, multi, selected = 3)) }

  @Test fun nessunoScelto() = clean("nessuno scelto") { Screen(PickerSamples.grid(library, multi)) }

  @Test fun unTocco() = clean("un tocco") { Screen(PickerSamples.grid(library, single), single) }

  @Test
  fun ilNasNonRisponde() =
    clean("il NAS non risponde") { Screen(PickerSamples.grid(library, multi, selected = 2, notice = PickerNotice.NasUnreachable(NasUnreachableReason.OFFLINE))) }

  @Test
  fun troppiSoloSulNas() =
    clean("troppi solo sul NAS") { Screen(PickerSamples.grid(library, multi, selected = 3, notice = PickerNotice.TooManyNasOnly(120, 3L * 1024 * 1024 * 1024))) }

  @Test fun bloccata() = clean("bloccata") { Screen(PickerUi(PickerScreenState.LOCKED)) }

  @Test fun sbloccoNonRiuscito() = clean("sblocco non riuscito") { Screen(PickerUi(PickerScreenState.LOCKED, unlockFailed = true)) }

  @Test fun nonPronta() = clean("non pronta") { Screen(PickerUi(PickerScreenState.NOT_READY)) }

  @Test fun vuota() = clean("vuota") { Screen(PickerUi(PickerScreenState.GRID)) }

  @Test fun inConsegna() = clean("in consegna") { Screen(PickerUi(PickerScreenState.DELIVERING)) }

  // --- ATF (D28): il controllo automatico indipendente, sulle schermate principali del modulo -----------------------------------------

  @Test
  fun atfTreScelti() {
    show { Screen(PickerSamples.grid(library, multi, selected = 3)) }
    composeRule.onRoot().checkRoboAccessibility()
  }
}

