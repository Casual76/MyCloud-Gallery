package dev.pampa.mycloudgallery.backup

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.backup.contract.BackupStatus
import dev.pampa.mycloudgallery.core.library.contract.LibraryBackupStatus
import dev.pampa.mycloudgallery.notify.IncomingProblemNotices
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * IN-7: chi avvisa quando un file ricevuto non si carica, e il piede della Libreria. Il custode non avvisa alla prima lettura del processo, non avvisa con
 * l'app davanti, avvisa quando il conto sale con l'app in secondo piano (e chiede che al ritorno si apra la pagina Backup) e toglie la notifica a zero.
 */
class IncomingProblemWatcherTest {

  private class Recorder : IncomingProblemNotices {
    val events = ArrayList<String>()

    override fun problems(count: Int) {
      events += "problemi:$count"
    }

    override fun clear() {
      events += "via"
    }
  }

  private fun watch(counts: List<Int>, foreground: Boolean = false): Pair<List<String>, Int> {
    val notices = Recorder()
    var opened = 0
    runBlocking { IncomingProblemWatcher(flowOf(*counts.toTypedArray()), notices, { foreground }, { opened++ }).run() }
    return notices.events to opened
  }

  @Test
  fun allaPrimaLetturaDelProcessoNonAvvisa() {
    val (events, opened) = watch(listOf(3))
    assertThat(events).isEmpty()
    assertThat(opened).isEqualTo(0)
  }

  @Test
  fun unContoCheSaleConLAppInSecondoPianoAvvisaEChiedeLaPagina() {
    val (events, opened) = watch(listOf(0, 1, 2))
    assertThat(events).containsExactly("via", "problemi:1", "problemi:2").inOrder()
    assertThat(opened).isEqualTo(2)
  }

  @Test
  fun conLAppDavantiNonServeLaNotifica() {
    val (events, opened) = watch(listOf(0, 1), foreground = true)
    assertThat(events).containsExactly("via")
    assertThat(opened).isEqualTo(0)
  }

  @Test
  fun quandoIlContoScendeIlNumeroSiAggiornaEAZeroLaNotificaSeNeVa() {
    val (events, opened) = watch(listOf(1, 3, 2, 0))
    assertThat(events).containsExactly("problemi:3", "problemi:2", "via").inOrder()
    assertThat(opened).isEqualTo(1)
  }

  @Test
  fun unaNotificaDiUnProcessoPrecedenteNonSiRiscriveMaSiTogliePerFinire() {
    // Il conto parte da 2 (di prima) e scende a 1: questo processo non ha postato niente e non resuscita la notifica.
    val (events, _) = watch(listOf(2, 1))
    assertThat(events).isEmpty()
    val (cleared, _) = watch(listOf(2, 0))
    assertThat(cleared).containsExactly("via")
  }

  // --- Il piede della Libreria --------------------------------------------------------------------------------------------------------------------

  private val problem = LibraryBackupStatus.Problem(BackupStatusReducers.INCOMING_FAILED_CODE, 2)

  @Test
  fun ilPiedeDiceIFileRicevutiNonSalvatiQuandoNonHaNienteDiPiuUrgente() {
    // Il piede muto, "Tutto al sicuro" e "N in coda" lasciano il posto ai file non salvati.
    assertThat(BackupStatusReducers.footer(BackupStatus.NotConfigured(emptySet()), incomingProblems = 2)).isEqualTo(problem)
    assertThat(BackupStatusReducers.footer(BackupStatus.UpToDate(10, 5L), incomingProblems = 2)).isEqualTo(problem)
    assertThat(BackupStatusReducers.footer(BackupStatus.Waiting(312, 1L, dev.pampa.mycloudgallery.core.backup.contract.WaitReason.CHARGING), incomingProblems = 2))
      .isEqualTo(problem)
    assertThat(BackupStatusReducers.footer(BackupStatus.Stopped(dev.pampa.mycloudgallery.core.backup.contract.StopBy.USER), incomingProblems = 2)).isEqualTo(problem)
  }

  @Test
  fun ilPiedeNonCopreUnProblemaDelBackupNeUnGiroInCorso() {
    val quota = BackupStatus.Problem(dev.pampa.mycloudgallery.core.backup.contract.BackupProblemCode.QUOTA, 0)
    assertThat(BackupStatusReducers.footer(quota, incomingProblems = 2)).isEqualTo(LibraryBackupStatus.Problem("quota", 0))
    assertThat(BackupStatusReducers.footer(BackupStatus.TestOnly, incomingProblems = 2)).isEqualTo(LibraryBackupStatus.TestOnly)
    val running = BackupStatus.Running(
      dev.pampa.mycloudgallery.core.backup.contract.BackupKind.PHOTOS, 12, 40, 1, 2, null, dev.pampa.mycloudgallery.core.backup.contract.BackupTrigger.USER_NOW,
    )
    assertThat(BackupStatusReducers.footer(running, incomingProblems = 2)).isEqualTo(LibraryBackupStatus.Running(12, 40, null))
  }

  @Test
  fun senzaFileNonSalvatiIlPiedeEQuelloDiSempre() {
    assertThat(BackupStatusReducers.footer(BackupStatus.UpToDate(10, 5L), 0)).isEqualTo(LibraryBackupStatus.AllSafe(5L))
    assertThat(BackupStatusReducers.footer(BackupStatus.UpToDate(10, 5L), incomingProblems = 0)).isEqualTo(LibraryBackupStatus.AllSafe(5L))
    assertThat(BackupStatusReducers.footer(BackupStatus.NotConfigured(emptySet()), incomingProblems = 0)).isEqualTo(LibraryBackupStatus.Idle)
  }
}
