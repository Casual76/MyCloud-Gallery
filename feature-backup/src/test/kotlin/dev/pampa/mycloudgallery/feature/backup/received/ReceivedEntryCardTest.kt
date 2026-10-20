package dev.pampa.mycloudgallery.feature.backup.received

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.backup.contract.BackupStatus
import dev.pampa.mycloudgallery.feature.backup.ComposeBackupTallTest
import dev.pampa.mycloudgallery.feature.backup.contract.BackupScreen
import dev.pampa.mycloudgallery.feature.backup.page.BackupPageActions
import dev.pampa.mycloudgallery.feature.backup.page.BackupPageScreen
import dev.pampa.mycloudgallery.feature.backup.page.BackupPageUi
import dev.pampa.mycloudgallery.feature.backup.page.BackupPageViewModel
import dev.pampa.mycloudgallery.feature.backup.testing.BackupSamples
import dev.pampa.mycloudgallery.feature.backup.testing.FakeReceivedQueue
import dev.pampa.mycloudgallery.feature.backup.testing.ReceivedSamples
import dev.pampa.mycloudgallery.feature.backup.testing.RecordingBackupActions
import java.util.Optional
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Test

/**
 * La scheda "Ricevuti in coda" della pagina Backup (correzioni 5B, IN-7): compare solo con almeno un file, conta quelli in attesa, in caricamento e
 * con un problema, e un tocco apre l'elenco.
 */
class ReceivedEntryCardTest : ComposeBackupTallTest() {

  private val actions = RecordingBackupActions()
  private val ui = mutableStateOf(page(ReceivedCounts.NONE))

  private fun page(received: ReceivedCounts) =
    BackupPageUi(BackupStatus.UpToDate(6_658, 1_792_119_120_000), BackupSamples.sources, BackupSamples.deviceFolder, sheetOpen = false, received = received)

  private fun start(received: ReceivedCounts) {
    ui.value = page(received)
    show { BackupPageScreen(state = ui.value, actions = BackupPageActions(open = actions::open), onBack = {}) }
  }

  private fun set(received: ReceivedCounts) {
    composeRule.runOnIdle { ui.value = page(received) }
    composeRule.waitForIdle()
  }

  @Test
  fun senzaFileLaSchedaNonCe() {
    start(ReceivedCounts.NONE)
    assertThat(composeRule.onAllNodesWithText("Ricevuti in coda").fetchSemanticsNodes()).isEmpty()
  }

  @Test
  fun conAlmenoUnFileCompareEContaIPunti() {
    start(ReceivedCounts.NONE)
    set(ReceivedCounts(waiting = 2, uploading = 0, problems = 0))
    composeRule.onNodeWithText("Ricevuti in coda").assertExists()
    composeRule.onNodeWithText("2 in attesa").assertExists()

    set(ReceivedCounts(waiting = 2, uploading = 1, problems = 3))
    composeRule.onNodeWithText("2 in attesa · 1 in caricamento · 3 con un problema").assertExists()

    set(ReceivedCounts(waiting = 0, uploading = 0, problems = 1))
    composeRule.onNodeWithText("1 con un problema").assertExists()

    set(ReceivedCounts(waiting = 1_240, uploading = 0, problems = 0))
    composeRule.onNodeWithText("1.240 in attesa").assertExists()

    // Finiti tutti: la scheda se ne va da sola.
    set(ReceivedCounts.NONE)
    assertThat(composeRule.onAllNodesWithText("Ricevuti in coda").fetchSemanticsNodes()).isEmpty()
  }

  @Test
  fun unTaccoApreLElenco() {
    start(ReceivedCounts(waiting = 1, uploading = 0, problems = 1))
    composeRule.onNode(hasText("Vedi") and hasClickAction()).performClick()
    assertThat(actions.opened).containsExactly(BackupScreen.RECEIVED)
  }

  @Test
  fun lAltraSchedaDellaPaginaRestaDoveEra() {
    // "Da decidere" e "Ricevuti in coda" stanno insieme, una sopra l'altra, senza coprirsi.
    ui.value = page(ReceivedCounts(waiting = 1, uploading = 0, problems = 0)).copy(pendingTasks = 2)
    show { BackupPageScreen(state = ui.value, actions = BackupPageActions(open = actions::open), onBack = {}) }
    composeRule.onNodeWithText("Da decidere").assertExists()
    composeRule.onNodeWithText("Ricevuti in coda").assertExists()
    val views = composeRule.onAllNodes(hasText("Vedi") and hasClickAction()).fetchSemanticsNodes()
    assertThat(views).hasSize(2)
  }
}

/** La pagina Backup legge i conti dalla porta dei ricevuti (e senza la porta non mostra niente). */
@OptIn(ExperimentalCoroutinesApi::class)
class BackupPageReceivedViewModelTest {

  private val dispatcher = UnconfinedTestDispatcher()

  private fun viewModel(queue: FakeReceivedQueue?): BackupPageViewModel = BackupPageViewModel(
    overview = Optional.empty(),
    settings = Optional.empty(),
    actions = Optional.empty(),
    tasks = Optional.empty(),
    received = Optional.ofNullable(queue),
  )

  @Test
  fun senzaLaPortaNonCeNiente() {
    Dispatchers.setMain(dispatcher)
    try {
      runTest(dispatcher) {
        val vm = viewModel(null)
        backgroundScope.launch { vm.state.collect {} }
        assertThat(vm.state.value.received).isEqualTo(ReceivedCounts.NONE)
      }
    } finally {
      Dispatchers.resetMain()
    }
  }

  @Test
  fun iContiSeguonoLaPortaPerPunto() {
    Dispatchers.setMain(dispatcher)
    try {
      runTest(dispatcher) {
        val queue = FakeReceivedQueue(ReceivedSamples.few)
        val vm = viewModel(queue)
        backgroundScope.launch { vm.state.collect {} }
        assertThat(vm.state.value.received).isEqualTo(ReceivedCounts(waiting = 1, uploading = 1, problems = 1))
        queue.items.value = emptyList()
        assertThat(vm.state.value.received).isEqualTo(ReceivedCounts.NONE)
      }
    } finally {
      Dispatchers.resetMain()
    }
  }
}
