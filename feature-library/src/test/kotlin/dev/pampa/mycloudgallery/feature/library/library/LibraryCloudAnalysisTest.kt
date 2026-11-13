package dev.pampa.mycloudgallery.feature.library.library

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.designsystem.design.GalleryCloudState
import dev.pampa.mycloudgallery.core.library.contract.LibraryAnalysisStatus
import dev.pampa.mycloudgallery.core.library.contract.LibraryAnalysisWait
import dev.pampa.mycloudgallery.core.library.contract.LibraryBackupStatus
import dev.pampa.mycloudgallery.core.library.contract.LibraryNasStatus
import org.junit.Test

/**
 * Pacchetto X: l'analisi delle foto nella nuvola — una riga di lavoro nel pannello, senza una seconda riga fissa nel piede. Il glifo
 * resta la salute di rete, NAS e backup: l'analisi in corso non deve nascondere "Tutto al sicuro"; un suo problema pero' si vede.
 */
class LibraryCloudAnalysisTest {

  private val upToDate = LibraryNasStatus.UpToDate(atNasMillis = 1_000L)
  private val allSafe = LibraryBackupStatus.AllSafe(atNasMillis = 2_000L)
  private val running = LibraryAnalysisStatus.Running(remaining = 300, etaMinutes = 40, ready = 700, total = 1_000)

  @Test
  fun inCorsoEntraNelPannelloSenzaCambiareIlGlifo() {
    val cloud = libraryCloud(null, upToDate, allSafe, running)
    assertThat(cloud).isNotNull()
    assertThat(cloud!!.state).isEqualTo(GalleryCloudState.Saved)
    assertThat(cloud.activity.map { it::class.simpleName }).contains("Analysis")
    assertThat(cloud.problems).isEmpty()
  }

  @Test
  fun daSolaTieneLaNuvola() {
    val cloud = libraryCloud(null, LibraryNasStatus.Idle, LibraryBackupStatus.Idle, running)
    assertThat(cloud).isNotNull()
    assertThat(cloud!!.state).isEqualTo(GalleryCloudState.Syncing)
    val paused = libraryCloud(null, LibraryNasStatus.Idle, LibraryBackupStatus.Idle, LibraryAnalysisStatus.Paused(LibraryAnalysisWait.THERMAL, 9, 1, 10))
    assertThat(paused!!.state).isEqualTo(GalleryCloudState.Waiting)
    assertThat(paused.activity).hasSize(1)
  }

  @Test
  fun unProblemaSiVedeConLaSuaAzione() {
    val cloud = libraryCloud(null, upToDate, allSafe, LibraryAnalysisStatus.Problem("AI_STORAGE_LOW"))
    assertThat(cloud!!.state).isEqualTo(GalleryCloudState.Attention)
    assertThat(cloud.problems.map { it::class.simpleName }).containsExactly("Analysis")
    assertThat(cloud.withAction).hasSize(1)
  }

  @Test
  fun prontaOSpentaNonDiceNiente() {
    assertThat(libraryCloud(null, LibraryNasStatus.Idle, LibraryBackupStatus.Idle, LibraryAnalysisStatus.AllReady)).isNull()
    assertThat(libraryCloud(null, LibraryNasStatus.Idle, LibraryBackupStatus.Idle, LibraryAnalysisStatus.NotHere(3, 4))).isNull()
    assertThat(libraryCloud(null, LibraryNasStatus.Idle, LibraryBackupStatus.Idle)).isNull()
  }
}
