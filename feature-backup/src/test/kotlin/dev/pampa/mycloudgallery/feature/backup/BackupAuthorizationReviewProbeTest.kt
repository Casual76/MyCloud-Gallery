package dev.pampa.mycloudgallery.feature.backup
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.imaging.testing.FakeThumbnailLoader
import dev.pampa.mycloudgallery.core.library.contract.TimelineView
import dev.pampa.mycloudgallery.core.library.testing.FakeViewerItems
import dev.pampa.mycloudgallery.core.library.testing.SyntheticLibrary
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.feature.backup.contract.BackupCoverPorts
import dev.pampa.mycloudgallery.feature.backup.contract.SourceNewestMedia
import dev.pampa.mycloudgallery.feature.backup.ui.BackupCovers
import org.junit.Test
import org.junit.runner.RunWith
@RunWith(AndroidJUnit4::class)
class BackupAuthorizationReviewProbeTest {
 @Test fun staleCollectedViewDoesNotAuthorizeAPhotoMissingFromCurrentView() {
  val library = SyntheticLibrary(seed=7L,count=5)
  val held = library.view()
  val id = held.snapshot.idAt(held.snapshotIndex(1))
  val items = FakeViewerItems(held) { library.ref(it) }
  val covers = BackupCovers(BackupCoverPorts(SourceNewestMedia { _, _ -> emptyMap() },items,FakeThumbnailLoader()))
  items.publish(object: TimelineView by held { override fun viewIndexOf(media: MediaId): Int = if(media==id) -1 else held.viewIndexOf(media) })
  assertThat(covers.request(held,id)).isNull()
 }
}
