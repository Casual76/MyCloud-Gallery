package dev.pampa.mycloudgallery.feature.backup

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.backup.contract.BackupProblemCode
import dev.pampa.mycloudgallery.core.backup.contract.BackupStatus
import dev.pampa.mycloudgallery.core.backup.contract.MediaBackupState
import dev.pampa.mycloudgallery.feature.backup.contract.BackupScreen
import dev.pampa.mycloudgallery.feature.backup.testing.BackupSamples
import org.junit.Test

/**
 * Gli stati di esempio coprono ogni caso del contratto (§1.5 della 5A) e ogni codice del §2.6: quando il contratto cresce, gli
 * screenshot e i test di P5 non restano indietro in silenzio. Le rotte sono stabili e senza dati dell'utente.
 */
class BackupSamplesTest {

  private val statusCases = setOf(
    BackupStatus.NotConfigured::class, BackupStatus.TestOnly::class, BackupStatus.FirstBackupReady::class, BackupStatus.UpToDate::class,
    BackupStatus.Waiting::class, BackupStatus.Running::class, BackupStatus.Stopped::class, BackupStatus.Problem::class,
  )

  private val mediaCases = setOf(
    MediaBackupState.NotMine::class, MediaBackupState.NotOnNas::class, MediaBackupState.Queued::class, MediaBackupState.Uploading::class,
    MediaBackupState.Verifying::class, MediaBackupState.Safe::class, MediaBackupState.OnlyCommonCopy::class,
    MediaBackupState.Unverified::class, MediaBackupState.Problem::class, MediaBackupState.LostBeforeBackup::class,
  )

  @Test
  fun ogniCasoDelloStatoHaUnEsempio() {
    val samples = (BackupSamples.statuses + BackupSamples.problems).map { it::class }.toSet()
    assertThat(samples).containsExactlyElementsIn(statusCases)
    assertThat(BackupSamples.problems.map { it.code }).containsExactlyElementsIn(BackupProblemCode.entries)
    assertThat(BackupSamples.mediaStates.map { it::class }.toSet()).containsExactlyElementsIn(mediaCases)
    assertThat(BackupSamples.comparingSummary.comparing).isTrue()
    // Fase I: il confronto delle copie simili non ferma il primo backup; la ricerca sul NAS si'.
    assertThat(BackupSamples.comparingSummary.canStart).isTrue()
    assertThat(BackupSamples.comparingSummary.similarToCompare).isEqualTo(120)
    assertThat(BackupSamples.searchingSummary.canStart).isFalse()
    assertThat(BackupSamples.searchingSummary.similarToCompare).isEqualTo(0)
    assertThat(BackupSamples.firstBackupSummary.comparing).isFalse()
    assertThat(BackupSamples.firstBackupSummary.toUpload.items).isEqualTo(312 + 4_782 + 324)
  }

  /** Se qualcuno aggiunge un caso al contratto senza un esempio, questo lo dice (un `when` esaustivo non compila senza il ramo). */
  @Test
  fun iCasiElencatiSonoTuttiQuelliDelContratto() {
    fun name(status: BackupStatus): String = when (status) {
      is BackupStatus.NotConfigured -> "NotConfigured"
      BackupStatus.TestOnly -> "TestOnly"
      is BackupStatus.FirstBackupReady -> "FirstBackupReady"
      is BackupStatus.UpToDate -> "UpToDate"
      is BackupStatus.Waiting -> "Waiting"
      is BackupStatus.Running -> "Running"
      is BackupStatus.Stopped -> "Stopped"
      is BackupStatus.Problem -> "Problem"
    }
    assertThat((BackupSamples.statuses + BackupSamples.problems).map(::name).toSet()).hasSize(statusCases.size)
  }

  @Test
  fun leRotteSonoStabiliEUniche() {
    val routes = BackupScreen.entries.map { it.route }
    assertThat(routes.toSet()).hasSize(routes.size)
    routes.forEach { assertThat(it).matches("^backup/[a-z-]+$") }
    assertThat(BackupScreen.entries.filter { it.phase5B }).containsExactly(BackupScreen.FREE_SPACE, BackupScreen.PENDING_TASKS, BackupScreen.RECEIVED)
  }
}
