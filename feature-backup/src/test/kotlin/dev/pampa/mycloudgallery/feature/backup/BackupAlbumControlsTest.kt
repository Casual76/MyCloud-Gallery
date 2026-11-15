package dev.pampa.mycloudgallery.feature.backup

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.backup.contract.BackupSource
import dev.pampa.mycloudgallery.core.backup.contract.BackupSources
import dev.pampa.mycloudgallery.feature.backup.contract.PhoneFolder
import dev.pampa.mycloudgallery.feature.backup.settings.BackupSettingsScreen
import dev.pampa.mycloudgallery.feature.backup.settings.FoldersState
import dev.pampa.mycloudgallery.feature.backup.settings.SettingsActions
import dev.pampa.mycloudgallery.feature.backup.settings.SettingsUi
import org.junit.Test

/** Long album lists must retain working touch targets without composing the entire phone. */
class BackupAlbumControlsTest : ComposeBackupTest() {
  @Test
  fun tailAlbumsCanBeEnabledAndOnlyNearbyRowsAreComposed() {
    val folders = (0 until 160).map { index ->
      PhoneFolder(BackupSource.Folder("external_primary", "Pictures/Album $index/"), 160 - index, 100L)
    }
    val ui = mutableStateOf(SettingsUi(FoldersState.Ready(folders), BackupSources.NONE, null, true, true, true))
    show {
      BackupSettingsScreen(ui.value, SettingsActions(setSource = { source, on ->
        val previous = ui.value.chosen.sources
        ui.value = ui.value.copy(chosen = BackupSources(if (on) previous + source else previous - source))
      }))
    }
    val albumRows = SemanticsMatcher("album row") { node ->
      node.name().contains("Album ")
    }
    assertThat(composeRule.onAllNodes(albumRows).fetchSemanticsNodes().size).isLessThan(40)
    composeRule.onNode(hasScrollAction()).performScrollToNode(hasText("Album 159"))
    composeRule.onNodeWithText("Album 159").performTouchInput { click() }
    composeRule.runOnIdle { assertThat(ui.value.chosen.sources).contains(folders.last().source) }
  }
}
