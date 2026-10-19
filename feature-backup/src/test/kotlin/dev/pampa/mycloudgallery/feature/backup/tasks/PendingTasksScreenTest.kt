package dev.pampa.mycloudgallery.feature.backup.tasks

import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.backup.contract.PendingTask
import dev.pampa.mycloudgallery.core.backup.contract.PendingTaskId
import dev.pampa.mycloudgallery.core.backup.contract.PendingTaskKind
import dev.pampa.mycloudgallery.core.backup.contract.PendingTaskState
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.VersionId
import dev.pampa.mycloudgallery.feature.backup.ComposeBackupTallTest
import java.util.UUID
import org.junit.Test

/** La lista "Da decidere" (`docs/fase5-progetto.md` §1.5 `PendingTasksState`, D20): vuota, con una o piu' attivita', con e senza il tasto "Elimina l'originale". */
class PendingTasksScreenTest : ComposeBackupTallTest() {

  private val deleted = ArrayList<PendingTask>()
  private val kept = ArrayList<PendingTask>()

  private fun task(id: Long, bytes: Long = 1_200_000_000L, at: Long = 1_792_067_520_000L) = PendingTask(
    id = PendingTaskId(id),
    kind = PendingTaskKind.EDITED_VIDEO_ORIGINAL,
    state = PendingTaskState.OPEN,
    mediaId = MediaId(UUID(0x7A5C, id).toString()),
    versionId = VersionId(UUID(0x7A5D, id).toString()),
    bytes = bytes,
    createdAtNasMillis = at,
  )

  private fun showTasks(tasks: List<PendingTask>, canDelete: Boolean = true, fontScale: Float? = null) {
    show(fontScale = fontScale) {
      PendingTasksScreen(
        state = PendingTasksUi(tasks, canDeleteOriginal = canDelete),
        actions = PendingTasksActions(deleteOriginal = { deleted += it }, keep = { kept += it }),
        onBack = {},
      )
    }
  }

  @Test
  fun senzaNienteDaDecidereLoDice() {
    showTasks(emptyList())
    composeRule.onNodeWithText("Non c’è niente da decidere").assertExists()
    composeRule.onNodeWithText("Quando l’app ha bisogno di una tua scelta la trovi qui.").assertExists()
  }

  @Test
  fun ilVideoModificatoDiceQuantoPesaEDaQuando() {
    showTasks(listOf(task(1)))
    composeRule.onNodeWithText("Hai modificato questo video").assertExists()
    composeRule.onNodeWithText("l’originale occupa 1,2 GB sul NAS. Vuoi eliminarlo?", substring = true).assertExists()
    composeRule.onNodeWithText("Dal 15 ottobre 2026", substring = true).assertExists()
    composeRule.onNodeWithText("Se lo elimini, l’originale va nel cestino del NAS per 30 giorni.", substring = true).assertExists()
  }

  @Test
  fun ilTitoloDiceQuanteCoseAspettano() {
    showTasks(listOf(task(1), task(2)))
    composeRule.onNodeWithText("2 cose aspettano una tua scelta.").assertExists()
    assertThat(composeRule.onAllNodesWithText("Hai modificato questo video").fetchSemanticsNodes()).hasSize(2)
  }

  @Test
  fun tienilo_EEliminaLOriginale_ChiamanoLaPortaGiusta() {
    val first = task(1)
    val second = task(2)
    showTasks(listOf(first, second))
    composeRule.onAllNodes(hasText("Tienilo") and hasClickAction())[0].performClick()
    composeRule.onAllNodes(hasText("Elimina l’originale") and hasClickAction())[1].performClick()
    assertThat(kept).containsExactly(first)
    assertThat(deleted).containsExactly(second)
  }

  @Test
  fun senzaIlFoglioDiAppIlTastoEliminaNonCe() {
    showTasks(listOf(task(1)), canDelete = false)
    composeRule.onNodeWithText("Tienilo").assertExists()
    assertThat(composeRule.onAllNodes(hasText("Elimina l’originale") and hasClickAction()).fetchSemanticsNodes()).isEmpty()
  }
}
