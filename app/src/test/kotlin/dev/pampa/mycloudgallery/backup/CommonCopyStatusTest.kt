package dev.pampa.mycloudgallery.backup

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.backup.contract.BackupStatus
import dev.pampa.mycloudgallery.core.library.contract.LibraryBackupStatus
import org.junit.Test

class CommonCopyStatusTest {
  @Test
  fun completedCommonCopiesAreNeitherQueuedNorDeclaredAllSafe() {
    for (privateCopies in listOf(0, 7_124)) {
      assertThat(BackupStatusReducers.footer(BackupStatus.UpToDate(privateCopies, 123L, onlyCommonCopy = 3)))
        .isEqualTo(LibraryBackupStatus.CompletedWithCommon(3, 123L))
    }
    assertThat(BackupStatusReducers.footer(BackupStatus.UpToDate(7_124, 123L)))
      .isEqualTo(LibraryBackupStatus.AllSafe(123L))
  }
}
