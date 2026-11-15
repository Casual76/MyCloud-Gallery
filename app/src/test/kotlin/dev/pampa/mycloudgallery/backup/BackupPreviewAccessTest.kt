package dev.pampa.mycloudgallery.backup

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.library.contract.HiddenMask
import dev.pampa.mycloudgallery.core.library.contract.TimelineSnapshot
import dev.pampa.mycloudgallery.core.library.contract.VisibleMedia
import dev.pampa.mycloudgallery.core.library.index.TimelineIndexBuilder
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.gallery.AnalysisVisibilityBoundary
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Test

class BackupPreviewAccessTest {
  @Test fun invalidationIsSynchronousAndLateApprovalCannotReopenTheOldGeneration() = runTest {
    val id = MediaId("00000000-0000-0000-0000-000000000001")
    val snapshot = MutableStateFlow<TimelineSnapshot?>(TimelineIndexBuilder(16).build(1))
    val wait = CompletableDeferred<Unit>()
    var slow = false
    val visible = object : VisibleMedia {
      override val snapshot = snapshot
      override suspend fun visible(ids: Collection<MediaId>): Set<MediaId> { if (slow) wait.await(); return ids.toSet() }
    }
    var hidden = emptySet<MediaId>()
    val boundary = AnalysisVisibilityBoundary({ true }, { snapshot.value })
    val access = AppBackupPreviewAccess(visible, HiddenMask { hidden }, boundary)
    val lease = access.open()
    access.approve("lane", setOf(id))
    assertThat(access.allows(id)).isTrue()
    slow = true
    val resolving = async { access.approve("lane", setOf(id)) }
    runCurrent()
    boundary.contentChanged()
    assertThat(access.allows(id)).isFalse() // Nessun collector o dispatcher ha ancora girato.
    wait.complete(Unit)
    resolving.await()
    assertThat(access.allows(id)).isFalse()
    hidden = setOf(id)
    boundary.rereadFinished(boundary.noticeOrder(), 1)
    access.approve("lane", setOf(id))
    assertThat(access.allows(id)).isFalse()
    hidden = emptySet()
    access.approve("lane", setOf(id))
    assertThat(access.allows(id)).isTrue()
    snapshot.value = TimelineIndexBuilder(16).build(2)
    assertThat(access.allows(id)).isFalse()
    access.approve("lane", setOf(id))
    lease.close()
    assertThat(access.allows(id)).isFalse()
    access.open().use { assertThat(access.allows(id)).isFalse() }
  }
}
