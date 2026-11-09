package dev.pampa.mycloudgallery.feature.library.library

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.designsystem.design.GalleryCloudState
import dev.pampa.mycloudgallery.core.designsystem.design.GalleryFooterTone
import dev.pampa.mycloudgallery.core.library.contract.LibraryBackupStatus
import dev.pampa.mycloudgallery.core.library.contract.LibraryBackupWait
import dev.pampa.mycloudgallery.core.library.contract.LibraryNasStatus
import dev.pampa.mycloudgallery.core.model.FreezeReason
import dev.pampa.mycloudgallery.core.model.NasArea
import org.junit.Test

/**
 * La mappa dagli stati veri alla nuvola (B2 della beta): ogni stato della rete, del NAS e del backup diventa uno stato della nuvola
 * (il glifo), e il pop-up mostra i problemi (con l'azione) oppure lo spazio, l'ultima sincronizzazione e "Libera spazio". Funzione
 * pura: niente Compose. Le frasi di ogni caso sono in `LibraryCloudPanelTest`.
 */
class LibraryCloudTest {

  private fun network(severity: LibraryNetworkSeverity, action: LibraryNetworkAction = LibraryNetworkAction.DIAGNOSTICS) =
    LibraryNetworkStatus(severity = severity, label = "Rete ${severity.name}", action = action)

  private val upToDate = LibraryNasStatus.UpToDate(atNasMillis = 1_000L)
  private val allSafe = LibraryBackupStatus.AllSafe(atNasMillis = 2_000L)

  private fun stateOf(network: LibraryNetworkStatus? = null, nas: LibraryNasStatus = LibraryNasStatus.Idle, backup: LibraryBackupStatus = LibraryBackupStatus.Idle) =
    libraryCloud(network, nas, backup)?.state

  @Test
  fun senzaNienteDaDireLaNuvolaNonCe() {
    assertThat(libraryCloud(null, LibraryNasStatus.Idle, LibraryBackupStatus.Idle)).isNull()
    // La rete a posto da sola non e' una notizia: senza NAS e backup la nuvola non compare.
    assertThat(libraryCloud(network(LibraryNetworkSeverity.Fine), LibraryNasStatus.Idle, LibraryBackupStatus.Idle)).isNull()
  }

  @Test
  fun ogniStatoDellaReteHaIlSuo() {
    assertThat(stateOf(network(LibraryNetworkSeverity.Fine), upToDate)).isEqualTo(GalleryCloudState.Saved)
    assertThat(stateOf(network(LibraryNetworkSeverity.Working), upToDate)).isEqualTo(GalleryCloudState.Syncing)
    assertThat(stateOf(network(LibraryNetworkSeverity.Offline), upToDate)).isEqualTo(GalleryCloudState.Offline)
    assertThat(stateOf(network(LibraryNetworkSeverity.Attention), upToDate)).isEqualTo(GalleryCloudState.Attention)
    assertThat(stateOf(network(LibraryNetworkSeverity.Alarm), upToDate)).isEqualTo(GalleryCloudState.Alarm)
    // Senza NAS ne' backup la rete che ha un problema lo dice lo stesso.
    assertThat(stateOf(network(LibraryNetworkSeverity.Offline))).isEqualTo(GalleryCloudState.Offline)
  }

  @Test
  fun ogniStatoDelNasHaIlSuo() {
    val cases = mapOf(
      LibraryNasStatus.FirstScan(done = 10, total = 100, etaMillis = null) to GalleryCloudState.Syncing,
      LibraryNasStatus.Aligning(bytesDone = 1, bytesTotal = 2, etaMillis = null) to GalleryCloudState.Syncing,
      upToDate to GalleryCloudState.Saved,
      LibraryNasStatus.Frozen(FreezeReason.VANISHED, sinceNasMillis = 0L) to GalleryCloudState.Alarm,
      LibraryNasStatus.AreasUnavailable(setOf(NasArea.COMMON)) to GalleryCloudState.Attention,
      LibraryNasStatus.LocalOnly to GalleryCloudState.Waiting,
    )
    for ((nas, expected) in cases) assertWithMessage("$nas").that(stateOf(nas = nas)).isEqualTo(expected)
    assertThat(stateOf(nas = LibraryNasStatus.Idle)).isNull()
  }

  @Test
  fun ogniStatoDelBackupHaIlSuo() {
    val cases = mapOf(
      LibraryBackupStatus.TestOnly to GalleryCloudState.Waiting,
      LibraryBackupStatus.Comparing(pending = 3) to GalleryCloudState.Syncing,
      allSafe to GalleryCloudState.Saved,
      LibraryBackupStatus.Queued(items = 312, wait = LibraryBackupWait.CHARGING) to GalleryCloudState.Waiting,
      LibraryBackupStatus.FirstBackup(done = 1, total = 10, etaMillis = null) to GalleryCloudState.Syncing,
      LibraryBackupStatus.Running(done = 1, total = 10, etaMillis = null) to GalleryCloudState.Syncing,
    )
    for ((backup, expected) in cases) assertWithMessage("$backup").that(stateOf(backup = backup)).isEqualTo(expected)
  }

  /** Ogni problema del backup prende il tono della sua riga del piede: allarme, attenzione, o attesa se riparte da solo. */
  @Test
  fun ogniProblemaDelBackupPrendeIlTonoDellaSuaRiga() {
    for (problem in BackupFooterProblems.all + BackupFooterProblems.generic) {
      val code = if (problem.code == "generic") "un_problema_futuro" else problem.code
      val expected = when (problem.tone) {
        GalleryFooterTone.Alarm -> GalleryCloudState.Alarm
        GalleryFooterTone.Waiting -> GalleryCloudState.Waiting
        else -> GalleryCloudState.Attention
      }
      assertWithMessage(code).that(stateOf(backup = LibraryBackupStatus.Problem(code, count = 0))).isEqualTo(expected)
    }
    // Le richieste della beta: NAS irraggiungibile e pieno, scritture ferme, backup fermo sono problemi.
    for (code in listOf("nas_unreachable", "nas_full", "quota", "writes_stopped", "frozen", "phone_storage_low")) {
      assertWithMessage(code).that(stateOf(backup = LibraryBackupStatus.Problem(code, 0))!!.isProblem).isTrue()
    }
  }

  @Test
  fun vinceLoStatoPiuGrave() {
    val frozen = LibraryNasStatus.Frozen(FreezeReason.CHANGED, 0L)
    val running = LibraryBackupStatus.Running(done = 1, total = 2, etaMillis = null)
    val queued = LibraryBackupStatus.Queued(items = 1, wait = LibraryBackupWait.WIFI)
    assertThat(stateOf(network(LibraryNetworkSeverity.Offline), frozen, running)).isEqualTo(GalleryCloudState.Alarm)
    assertThat(stateOf(network(LibraryNetworkSeverity.Offline), LibraryNasStatus.AreasUnavailable(setOf(NasArea.PRIVATE)), running))
      .isEqualTo(GalleryCloudState.Offline)
    assertThat(stateOf(network(LibraryNetworkSeverity.Attention), upToDate, running)).isEqualTo(GalleryCloudState.Attention)
    assertThat(stateOf(network(LibraryNetworkSeverity.Fine), upToDate, running)).isEqualTo(GalleryCloudState.Syncing)
    assertThat(stateOf(network(LibraryNetworkSeverity.Fine), upToDate, queued)).isEqualTo(GalleryCloudState.Waiting)
    assertThat(stateOf(network(LibraryNetworkSeverity.Fine), upToDate, allSafe)).isEqualTo(GalleryCloudState.Saved)
  }

  /** Con un problema il pop-up dice i problemi (il piu' grave per primo) e non lo spazio; i lavori restano, sotto. */
  @Test
  fun conUnProblemaIlPopUpDiceIlProblemaENonLoSpazio() {
    val net = network(LibraryNetworkSeverity.Attention, LibraryNetworkAction.WIREGUARD)
    val frozen = LibraryNasStatus.Frozen(FreezeReason.CHANGED, 0L)
    val queued = LibraryBackupStatus.Queued(items = 312, wait = LibraryBackupWait.CHARGING)
    val cloud = checkNotNull(libraryCloud(net, frozen, queued))
    assertThat(cloud.showsSpace).isFalse()
    assertThat(cloud.problems).containsExactly(CloudItem.Nas(frozen, GalleryCloudState.Alarm), CloudItem.Network(net, GalleryCloudState.Attention)).inOrder()
    assertThat(cloud.activity).containsExactly(CloudItem.Backup(queued, GalleryCloudState.Waiting))
  }

  /** Senza problemi il pop-up mostra lo spazio, l'ultima sincronizzazione (la piu' recente) e i lavori in corso prima delle attese. */
  @Test
  fun senzaProblemiIlPopUpMostraLoSpazioELUltimaSincronizzazione() {
    val scan = LibraryNasStatus.FirstScan(done = 1, total = null, etaMillis = null)
    val cloud = checkNotNull(libraryCloud(network(LibraryNetworkSeverity.Fine), scan, LibraryBackupStatus.TestOnly))
    assertThat(cloud.showsSpace).isTrue()
    assertThat(cloud.problems).isEmpty()
    assertThat(cloud.activity.map { it.state }).containsExactly(GalleryCloudState.Syncing, GalleryCloudState.Waiting).inOrder()

    val saved = checkNotNull(libraryCloud(null, upToDate, allSafe))
    assertThat(saved.state).isEqualTo(GalleryCloudState.Saved)
    assertThat(saved.activity).isEmpty()
    assertThat(saved.lastSyncNasMillis).isEqualTo(2_000L)
    assertThat(saved.savedByBackup).isTrue()
    assertThat(lastSyncOf(upToDate, LibraryBackupStatus.Idle)).isEqualTo(1_000L)
    assertThat(lastSyncOf(LibraryNasStatus.Idle, LibraryBackupStatus.Running(1, 2, null))).isNull()
  }

  @Test
  fun iByteDelloSpazioHannoUnDecimaleEUnitaDecimali() {
    assertThat(tenths(3_600_000_000_000L, 1_000_000_000_000L)).isEqualTo("3,6")
    assertThat(tenths(1_250_000_000_000L, 1_000_000_000_000L)).isEqualTo("1,2")
    assertThat(tenths(12_340_000_000L, 1_000_000_000L)).isEqualTo("12,3")
  }
}
