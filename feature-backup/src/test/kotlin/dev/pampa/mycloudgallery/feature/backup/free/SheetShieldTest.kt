package dev.pampa.mycloudgallery.feature.backup.free

import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalView
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.designsystem.sheet.LocalConfirmSheetShield
import dev.pampa.mycloudgallery.feature.backup.ComposeBackupTest
import dev.pampa.mycloudgallery.feature.backup.versions.CorrectionDraft
import dev.pampa.mycloudgallery.feature.backup.versions.CorrectionMode
import dev.pampa.mycloudgallery.feature.backup.versions.CorrectionSheet
import dev.pampa.mycloudgallery.feature.backup.versions.CorrectionSheetActions
import dev.pampa.mycloudgallery.feature.backup.versions.CorrectionSubject
import dev.pampa.mycloudgallery.feature.backup.versions.CorrectionUi
import dev.pampa.mycloudgallery.feature.backup.versions.VersionSamples
import dev.pampa.mycloudgallery.feature.backup.versions.VersionsSheet
import dev.pampa.mycloudgallery.feature.backup.versions.VersionsSheetActions
import dev.pampa.mycloudgallery.feature.backup.versions.VersionsUi
import org.junit.Test

/**
 * R7 della revisione 4B (`docs/sicurezza.md` L34): **ogni** foglio della 5B e' un foglio di conferma del design system, quindi mentre e' aperto
 * la finestra nasconde le sovrapposizioni delle altre app (lo scudo che `:app` lega alla radice) e scarta i tocchi oscurati; chiuso, niente.
 * Qui lo scudo e' un contatore: e' chiamato quando il foglio e' visibile e mai quando e' chiuso.
 */
class SheetShieldTest : ComposeBackupTest() {

  private var shieldCalls = 0
  private var window: View? = null
  private val visible = mutableStateOf(false)

  @Composable
  private fun Shielded(content: @Composable () -> Unit) {
    val view = LocalView.current
    SideEffect { window = view }
    CompositionLocalProvider(LocalConfirmSheetShield provides { SideEffect { shieldCalls++ } }) { content() }
  }

  /** Mostra [sheet] chiuso, controlla che lo scudo non ci sia, lo apre e controlla che ci sia e che la finestra scarti i tocchi oscurati. */
  private fun assertShielded(sheet: @Composable (Boolean) -> Unit) {
    visible.value = false
    show { Shielded { sheet(visible.value) } }
    assertThat(shieldCalls).isEqualTo(0)
    assertThat(window!!.filterTouchesWhenObscured).isFalse()
    composeRule.runOnIdle { visible.value = true }
    composeRule.waitForIdle()
    assertThat(shieldCalls).isGreaterThan(0)
    assertThat(window!!.filterTouchesWhenObscured).isTrue()
    composeRule.runOnIdle { visible.value = false }
    composeRule.waitForIdle()
    assertThat(window!!.filterTouchesWhenObscured).isFalse()
  }

  @Test
  fun ilFoglioDiLiberaSpazio() {
    val plan = FreeSpaceSamples.plan(items = 3)
    val taps = ConfirmTaps(plan) { 0L }
    assertShielded { open ->
      FreeSheets(if (open) FreeSheet.Confirm(plan) else FreeSheet.None, taps, FreeSpaceActions())
    }
  }

  @Test
  fun ilFoglioDiLiberaSubito() {
    val plan = FreeSpaceSamples.plan(items = 3, permanent = true)
    val taps = ConfirmTaps(plan) { 0L }
    assertShielded { open ->
      FreeSheets(if (open) FreeSheet.ConfirmNow(plan) else FreeSheet.None, taps, FreeSpaceActions())
    }
  }

  @Test
  fun ilFoglioDelleVersioni() {
    assertShielded { open ->
      VersionsSheet(
        visible = open,
        state = VersionsUi.Listing(VersionSamples.plan(), canView = false, canDeleteIntermediates = false),
        taps = null,
        actions = VersionsSheetActions(),
        onDismissRequest = {},
      )
    }
  }

  @Test
  fun ilFoglioDelRiepilogoDelRipristino() {
    val plan = VersionSamples.plan()
    assertShielded { open ->
      VersionsSheet(
        visible = open,
        state = VersionsUi.ConfirmRestore(plan),
        taps = ConfirmTaps(plan) { 0L },
        actions = VersionsSheetActions(),
        onDismissRequest = {},
      )
    }
  }

  @Test
  fun ilFoglioDelleCorrezioni() {
    val subject = CorrectionSubject(VersionSamples.media)
    assertShielded { open ->
      CorrectionSheet(
        visible = open,
        state = CorrectionUi.Editing(subject, CorrectionMode.ALL, CorrectionDraft(), emptySet(), plan = null),
        taps = null,
        actions = CorrectionSheetActions(),
        onDismissRequest = {},
      )
    }
  }

  @Test
  fun ilFoglioDiEliminaSoloDalTelefono() {
    assertShielded { open ->
      PhoneOnlySheet(visible = open, state = PhoneOnlyUi.Loading, onChoose = {}, onSafeguard = {}, onContinue = {}, onDismiss = {})
    }
  }
}
