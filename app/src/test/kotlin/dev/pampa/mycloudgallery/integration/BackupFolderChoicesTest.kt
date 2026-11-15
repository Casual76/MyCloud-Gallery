package dev.pampa.mycloudgallery.integration

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.backup.CatalogPhoneFolders
import dev.pampa.mycloudgallery.core.backup.contract.BackupSource
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class)
class BackupFolderChoicesTest {
  @get:Rule val temp = TemporaryFolder()

  @Test fun everyPresentFolderCanBeChosenIncludingCameraAndFoldersBeyondTwoHundred() = runBlocking<Unit> {
    val rig = AiRig(ApplicationProvider.getApplicationContext(), temp.root)
    try {
      val store = NasGalleries.newStore()
      val bytes = AiRig.jpeg(42)
      val paths = (1..220).map { "Pictures/Album$it/" } + listOf("DCIM/Camera/", "Pictures/Screenshots/")
      paths.forEachIndexed { i, path -> store.put(AiRig.photoRow(i + 1L, path, "IMG_$i.jpg", bytes, rig.time), bytes) }
      val phone = rig.founder(store, fence = null)
      val folders = CatalogPhoneFolders(phone.gallery.database, null) { TestNas.ADMIN_USER }.folders()
      assertThat(folders.map { (it.source as BackupSource.Folder).relativePath }).containsAtLeastElementsIn(paths)
    } finally { rig.close() }
  }
}
