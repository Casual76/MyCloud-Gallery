package dev.pampa.mycloudgallery.feature.backup

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.pampa.mycloudgallery.feature.backup.contract.BackupPreviewAccess
import dev.pampa.mycloudgallery.feature.backup.free.FreePreviews
import kotlinx.coroutines.flow.MutableStateFlow
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.backup.contract.BackupKind
import dev.pampa.mycloudgallery.core.backup.contract.BackupLive
import dev.pampa.mycloudgallery.core.backup.contract.BackupStatus
import dev.pampa.mycloudgallery.core.backup.contract.BackupTrigger
import dev.pampa.mycloudgallery.core.imaging.testing.FakeThumbnailLoader
import dev.pampa.mycloudgallery.core.library.contract.TimelineView
import dev.pampa.mycloudgallery.core.library.testing.FakeViewerItems
import dev.pampa.mycloudgallery.core.library.testing.SyntheticLibrary
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.feature.backup.contract.BackupCoverPorts
import dev.pampa.mycloudgallery.feature.backup.contract.SourceNewestMedia
import dev.pampa.mycloudgallery.feature.backup.page.BackupPageActions
import dev.pampa.mycloudgallery.feature.backup.page.BackupPageScreen
import dev.pampa.mycloudgallery.feature.backup.page.BackupPageUi
import dev.pampa.mycloudgallery.feature.backup.testing.BackupSamples
import dev.pampa.mycloudgallery.feature.backup.ui.BackupCovers
import dev.pampa.mycloudgallery.feature.backup.ui.LocalBackupCovers
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = "w400dp-h900dp")
class BackupLaneTest : BackupComposeBase() {
  private class PreviewAccess : BackupPreviewAccess {
    override val revision = MutableStateFlow(0L)
    var active = 0
    var hidden: MediaId? = null
    private var approved = emptySet<MediaId>()
    override fun open(): AutoCloseable {
      active++; revision.value++
      return AutoCloseable { active--; approved = emptySet(); revision.value++ }
    }
    override suspend fun approve(owner: String, ids: Set<MediaId>) {
      val allowed = if (active > 0) ids.filterTo(LinkedHashSet()) { it != hidden } else emptySet()
      if (approved != allowed) { approved = allowed; revision.value++ }
    }
    override fun release(owner: String) { approved = emptySet(); revision.value++ }
    override fun allows(id: MediaId) = active > 0 && id in approved && id != hidden
  }

  @Test fun freeSpacePreviewsAuthorizeOnlyWhileStartedAndDropRevokedRequests() {
    val library = SyntheticLibrary(seed = 7L, count = 8)
    val view = library.view()
    val ids = (0..3).map { view.snapshot.idAt(view.snapshotIndex(it)) }
    val loader = FakeThumbnailLoader()
    val access = PreviewAccess()
    val covers = BackupCovers(BackupCoverPorts(SourceNewestMedia { _, _ -> emptyMap() }, FakeViewerItems(view) { library.ref(it) }, loader, access))
    val owner = object : LifecycleOwner {
      override val lifecycle = LifecycleRegistry(this)
    }
    composeRule.runOnUiThread { owner.lifecycle.currentState = Lifecycle.State.RESUMED }
    show {
      CompositionLocalProvider(LocalBackupCovers provides covers, LocalLifecycleOwner provides owner) { FreePreviews(ids, 4) }
    }
    composeRule.waitForIdle()
    assertThat(access.active).isEqualTo(1)
    assertThat(loader.kept["libera-spazio"].orEmpty()).hasSize(4)
    composeRule.runOnIdle { access.hidden = ids[0]; access.revision.value++ }
    composeRule.waitForIdle()
    assertThat(loader.kept["libera-spazio"].orEmpty().map { it.key.mediaId }).doesNotContain(ids[0])
    composeRule.runOnIdle { owner.lifecycle.currentState = Lifecycle.State.CREATED }
    assertThat(access.active).isEqualTo(0)
    assertThat(loader.kept["libera-spazio"].orEmpty()).isEmpty()
    composeRule.runOnIdle { owner.lifecycle.currentState = Lifecycle.State.RESUMED }
    composeRule.waitForIdle()
    assertThat(access.active).isEqualTo(1)
    assertThat(loader.kept["libera-spazio"].orEmpty()).hasSize(3)
  }

  private class Without(private val base: TimelineView, private val gone: MediaId) : TimelineView by base {
    override fun viewIndexOf(id: MediaId) = if (id == gone) -1 else base.viewIndexOf(id)
  }

  @Test fun showsRealActivityAndDropsAHiddenPhotoEvenDuringAnimation() {
    val library = SyntheticLibrary(seed = 7L, count = 20)
    val view = library.view()
    val ids = (0..6).map { view.snapshot.idAt(view.snapshotIndex(it)) }
    val items = FakeViewerItems(view) { library.ref(it) }
    val loader = FakeThumbnailLoader()
    val covers = BackupCovers(BackupCoverPorts(SourceNewestMedia { _, _ -> emptyMap() }, items, loader))
    val live = BackupLive(active = listOf(ids[0]), recent = ids.subList(1, 4), next = ids.subList(4, 7), finished = 3L)
    val state = mutableStateOf(BackupPageUi(
      BackupStatus.Running(BackupKind.PHOTOS, 3, 20, 30, 200, null, BackupTrigger.USER_NOW),
      BackupSamples.sources, BackupSamples.deviceFolder, false, live = live,
    ))
    show {
      CompositionLocalProvider(LocalBackupCovers provides covers) {
        BackupPageScreen(state.value, BackupPageActions(), onBack = {})
      }
    }
    assertThat(loader.kept["backup-lane"].orEmpty().map { it.key.mediaId }).contains(ids[0])
    composeRule.onRoot().captureRoboImage("build/queue-truth/backup-running.png")
    composeRule.runOnIdle { items.publish(Without(view, ids[0])) }
    composeRule.waitForIdle()
    assertThat(loader.kept["backup-lane"].orEmpty().map { it.key.mediaId }).doesNotContain(ids[0])
    composeRule.runOnIdle { state.value = state.value.copy(status = BackupStatus.UpToDate(20, null), sources = state.value.sources.map { it.copy(safe = it.media, queued = 0) }, live = live.copy(active = emptyList(), next = emptyList())) }
    composeRule.waitForIdle()
    assertThat(loader.kept["backup-lane"].orEmpty().map { it.key.mediaId }).containsNoneIn(ids.subList(4, 7))
    composeRule.onRoot().captureRoboImage("build/queue-truth/backup-finished.png")
  }
}
