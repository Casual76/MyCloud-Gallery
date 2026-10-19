package dev.pampa.mycloudgallery.feature.viewer

import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.backup.contract.RestoreAvailability
import dev.pampa.mycloudgallery.feature.viewer.menu.BackupActionsSamples
import dev.pampa.mycloudgallery.feature.viewer.menu.BackupMenuAction
import dev.pampa.mycloudgallery.feature.viewer.menu.BackupMenuCapabilities
import dev.pampa.mycloudgallery.feature.viewer.menu.BackupNotice
import dev.pampa.mycloudgallery.feature.viewer.menu.BackupShareUi
import dev.pampa.mycloudgallery.feature.viewer.menu.FakeBackupMediaActions
import org.junit.Test

/**
 * Il viewer vero con le voci del backup (Fase 5B, P12; `docs/fase5-progetto.md` §1.3 "viewer", §2.7, D13, D26): il menu ⋮ le mostra solo se
 * `:app` le ha legate, ogni voce e' una richiesta a `:app` per il media di adesso, "Condividi" passa da `:app` e dice se toglie la posizione, e una
 * condivisione ferma ha il suo foglio, che non condivide niente da solo.
 */
class ViewerBackupMenuTest : ViewerTestBase() {

  private fun openMenu() {
    composeRule.onNodeWithContentDescription("Altre azioni").performClick()
    composeRule.waitUntil(TIMEOUT) { composeRule.onAllNodesWithText("Informazioni").fetchSemanticsNodes().isNotEmpty() }
  }

  private fun hasText(text: String) = composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

  private fun showWith(backup: FakeBackupMediaActions?) {
    show(services = services(backupActions = backup))
  }

  @Test
  fun senzaIlBackupLegatoLeVociNonCiSono_D13() {
    showWith(null)
    openMenu()
    for (label in listOf("Versioni", "Ripristina originale", "Correggi data e luogo", "Ruota", "Modifica con…")) assertThat(hasText(label)).isFalse()
    assertThat(hasText("Condividi")).isTrue()
    assertThat(hasText("Informazioni")).isTrue()
  }

  @Test
  fun conIlBackupLegatoLeVociCompaiono() {
    val backup = FakeBackupMediaActions()
    showWith(backup)
    openMenu()
    composeRule.waitUntil(TIMEOUT) { hasText("Versioni") }
    for (label in listOf("Versioni", "Vedi originale", "Ripristina originale", "Correggi data e luogo", "Ruota", "Modifica con…")) assertThat(hasText(label)).isTrue()
    // Il menu di prima c'e' ancora.
    assertThat(hasText("Informazioni")).isTrue()
  }

  @Test
  fun senzaVersioniNonCeNeVersioniNeRipristino() {
    val backup = FakeBackupMediaActions(plan = BackupActionsSamples.single())
    showWith(backup)
    openMenu()
    composeRule.waitUntil(TIMEOUT) { hasText("Correggi data e luogo") }
    assertThat(hasText("Versioni")).isFalse()
    assertThat(hasText("Ripristina originale")).isFalse()
  }

  @Test
  fun scegliereUnaVoceChiedeAAppIlMediaDiAdesso() {
    val backup = FakeBackupMediaActions()
    showWith(backup)
    openMenu()
    composeRule.waitUntil(TIMEOUT) { hasText("Versioni") }
    composeRule.onNodeWithText("Versioni").performClick()
    composeRule.waitForIdle()
    assertThat(backup.performed.map { it.first }).containsExactly(BackupMenuAction.VERSIONS)
    assertThat(backup.performed.single().second).isEqualTo(fixture.request().start)
  }

  @Test
  fun unaVoceNonPartePerIlSoloFattoDiAprireIlMenu() {
    val backup = FakeBackupMediaActions()
    showWith(backup)
    openMenu()
    composeRule.waitUntil(TIMEOUT) { hasText("Versioni") }
    assertThat(backup.performed).isEmpty()
    assertThat(backup.shared).isEmpty()
  }

  @Test
  fun ilRipristinoNonPossibileNonSiOffre() {
    val backup = FakeBackupMediaActions(plan = BackupActionsSamples.plan(RestoreAvailability.ALREADY_ORIGINAL))
    showWith(backup)
    openMenu()
    composeRule.waitUntil(TIMEOUT) { hasText("Versioni") }
    assertThat(hasText("Ripristina originale")).isFalse()
  }

  @Test
  fun senzaLeCapacitaDiCorreggereEModificareRestanoSoloLeVersioni() {
    val backup = FakeBackupMediaActions(capabilities = BackupMenuCapabilities(share = true))
    showWith(backup)
    openMenu()
    composeRule.waitUntil(TIMEOUT) { hasText("Versioni") }
    assertThat(hasText("Correggi data e luogo")).isFalse()
    assertThat(hasText("Ruota")).isFalse()
    assertThat(hasText("Modifica con…")).isFalse()
  }

  // --- Condividi --------------------------------------------------------------------------------------------------------------------

  @Test
  fun condividiPassaDaAppEIlViewerNonUsaPiuIlSuoPercorso() {
    val backup = FakeBackupMediaActions()
    showWith(backup)
    openMenu()
    composeRule.onNodeWithText("Condividi").performClick()
    composeRule.waitForIdle()
    assertThat(backup.shared).containsExactly(listOf(fixture.request().start))
    assertThat(intents.shares).isEmpty()
  }

  @Test
  fun conRimuoviLaPosizioneAccesaLaVoceLoDice() {
    val backup = FakeBackupMediaActions()
    backup.removeLocation.value = true
    showWith(backup)
    openMenu()
    assertThat(hasText("Condividi senza posizione")).isTrue()
    assertThat(hasText("Condividi")).isFalse()
  }

  @Test
  fun senzaIlBackupCondividiResteQuelloDiPrima() {
    showWith(null)
    openMenu()
    composeRule.onNodeWithText("Condividi").performClick()
    composeRule.waitForIdle()
    assertThat(intents.shares).isNotEmpty()
  }

  @Test
  fun mentreSiPreparaLaCopiaSiVedeLAvanzamentoEAnnullareFermaLaPreparazione() {
    val backup = FakeBackupMediaActions()
    showWith(backup)
    backup.shareState.value = BackupShareUi.Preparing(1, 3, removingLocation = true)
    composeRule.waitUntil(TIMEOUT) { hasText("Tolgo la posizione e controllo il file") }
    assertThat(hasText("1 di 3")).isTrue()
    composeRule.onNodeWithText("Annulla").performClick()
    composeRule.waitForIdle()
    assertThat(backup.dismissShareCalls).isEqualTo(1)
  }

  @Test
  fun unaCondivisioneFermaHaIlSuoFoglio_ENonCondivideDaSola() {
    val backup = FakeBackupMediaActions()
    showWith(backup)
    backup.shareState.value = BackupShareUi.Refused(
      "Questa foto non si può condividere senza posizione: condividila con la posizione o annulla.",
      canShareWithLocation = true,
    )
    composeRule.waitUntil(TIMEOUT) { hasText("Condivisione ferma") }
    assertThat(hasText("Condividi con la posizione")).isTrue()
    // Il foglio e' aperto, ma niente e' stato condiviso: ne' con ne' senza posizione.
    assertThat(backup.shared).isEmpty()
    assertThat(backup.shareWithLocationCalls).isEqualTo(0)
    assertThat(intents.shares).isEmpty()
    composeRule.onNodeWithText("Condividi con la posizione").performClick()
    composeRule.waitForIdle()
    assertThat(backup.shareWithLocationCalls).isEqualTo(1)
  }

  @Test
  fun unaFraseDopoUnAzioneSiVedeESiSpegneDaSola() {
    val backup = FakeBackupMediaActions()
    showWith(backup)
    backup.notice.value = BackupNotice("Nessun cambiamento: non ho salvato niente.", ok = true)
    composeRule.waitUntil(TIMEOUT) { hasText("Nessun cambiamento: non ho salvato niente.") }
    composeRule.mainClock.advanceTimeBy(4_000)
    composeRule.waitForIdle()
    assertThat(backup.dismissNoticeCalls).isEqualTo(1)
  }

  private companion object {
    const val TIMEOUT = 5_000L
  }
}
