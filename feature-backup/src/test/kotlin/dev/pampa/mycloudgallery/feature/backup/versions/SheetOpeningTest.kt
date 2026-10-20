package dev.pampa.mycloudgallery.feature.backup.versions

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.StateRestorationTester
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.feature.backup.ComposeBackupTallTest
import org.junit.Test

/**
 * VE-5 della verifica funzionale 5B: i fogli Versioni e Correggi si azzerano solo quando si aprono, non a ogni composizione nuova. Prima una
 * rotazione dello schermo (che ricrea la composizione) chiamava di nuovo `reset()`: il lavoro in corso si cancellava e il permesso appena dato
 * nel dialogo di sistema si perdeva.
 */
class SheetOpeningTest : ComposeBackupTallTest() {

  @Test
  fun ve5_unaRotazioneDelloSchermoNonAzzeraIlFoglio() {
    var opens = 0
    var returns = 0
    val visible = mutableStateOf(true)
    val tester = StateRestorationTester(composeRule)
    tester.setContent { SheetOpening("versioni-m", visible.value, onOpen = { opens++ }, onReturn = { returns++ }) }
    composeRule.waitForIdle()
    assertThat(opens).isEqualTo(1)

    // La rotazione: la composizione si ricrea dallo stato salvato.
    tester.emulateSavedInstanceStateRestore()
    composeRule.waitForIdle()
    assertThat(opens).isEqualTo(1)
    assertThat(returns).isEqualTo(1)

    // Chiuso e riaperto: quello si', riparte da capo.
    composeRule.runOnIdle { visible.value = false }
    composeRule.waitForIdle()
    composeRule.runOnIdle { visible.value = true }
    composeRule.waitForIdle()
    assertThat(opens).isEqualTo(2)
  }
}
