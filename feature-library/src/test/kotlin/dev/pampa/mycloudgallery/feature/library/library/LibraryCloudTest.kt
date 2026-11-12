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
      // CU-1: la pubblicazione limitata dalle recinzioni e' la stessa pausa del backup vero non ancora acceso.
      LibraryNasStatus.LocalOnly to GalleryCloudState.Paused,
    )
    for ((nas, expected) in cases) assertWithMessage("$nas").that(stateOf(nas = nas)).isEqualTo(expected)
    assertThat(stateOf(nas = LibraryNasStatus.Idle)).isNull()
  }

  @Test
  fun ogniStatoDelBackupHaIlSuo() {
    val cases = mapOf(
      // I4: il backup vero non ancora acceso e' una pausa, come nella pagina Backup.
      LibraryBackupStatus.TestOnly to GalleryCloudState.Paused,
      LibraryBackupStatus.Comparing(pending = 3) to GalleryCloudState.Syncing,
      allSafe to GalleryCloudState.Saved,
      LibraryBackupStatus.Queued(items = 312, wait = LibraryBackupWait.CHARGING) to GalleryCloudState.Waiting,
      LibraryBackupStatus.FirstBackup(done = 1, total = 10, etaMillis = null) to GalleryCloudState.Syncing,
      LibraryBackupStatus.Running(done = 1, total = 10, etaMillis = null) to GalleryCloudState.Syncing,
      // OA-10: la pausa dell'utente ha il suo stato; il lavoro fermato da Android e' un'attesa (riprende da solo).
      LibraryBackupStatus.Paused(byUser = true) to GalleryCloudState.Paused,
      LibraryBackupStatus.Paused(byUser = false) to GalleryCloudState.Waiting,
    )
    for ((backup, expected) in cases) assertWithMessage("$backup").that(stateOf(backup = backup)).isEqualTo(expected)
  }

  /**
   * OA-10 (review della beta): con il backup fermo la nuvola **non dice mai "tutto salvato"**, qualunque cosa dicano il NAS e la rete.
   * Prima il backup fermo non diceva niente e, con il NAS aggiornato, restavano la spunta, "NAS aggiornato" e "Libera spazio".
   */
  @Test
  fun conIlBackupFermoLaNuvolaNonDiceMaiTuttoSalvato() {
    val networks = listOf(null, network(LibraryNetworkSeverity.Fine))
    val nases = listOf(LibraryNasStatus.Idle, upToDate, LibraryNasStatus.LocalOnly)
    for (net in networks) for (nas in nases) for (byUser in listOf(true, false)) {
      val paused = LibraryBackupStatus.Paused(byUser)
      val cloud = checkNotNull(libraryCloud(net, nas, paused))
      val where = "rete $net, NAS $nas, $paused"
      assertWithMessage(where).that(cloud.state).isNotEqualTo(GalleryCloudState.Saved)
      assertWithMessage(where).that(cloud.savedByBackup).isFalse()
      if (byUser) {
        // La pausa: il suo stato, la sua voce con il tasto verso la pagina Backup, e niente spazio ne' "Libera spazio".
        assertWithMessage(where).that(cloud.state).isEqualTo(GalleryCloudState.Paused)
        assertWithMessage(where).that(cloud.paused).containsExactly(CloudItem.Backup(paused, GalleryCloudState.Paused))
        assertWithMessage(where).that(cloud.withAction).containsExactly(CloudItem.Backup(paused, GalleryCloudState.Paused))
        assertWithMessage(where).that(cloud.problems).isEmpty()
        assertWithMessage(where).that(cloud.showsSpace).isFalse()
      } else {
        // Fermato da Android: un'attesa fra i lavori (riprende da solo), che si tocca e porta alla pagina Backup. Con l'indice del NAS fermo dalle
        // recinzioni (CU-1) la nuvola e' in pausa per quello, ma il backup resta un'attesa.
        val fenced = nas == LibraryNasStatus.LocalOnly
        assertWithMessage(where).that(cloud.state).isEqualTo(if (fenced) GalleryCloudState.Paused else GalleryCloudState.Waiting)
        assertWithMessage(where).that(cloud.activity).contains(CloudItem.Backup(paused, GalleryCloudState.Waiting))
        assertWithMessage(where).that(cloud.paused).isEqualTo(if (fenced) listOf(CloudItem.Nas(LibraryNasStatus.LocalOnly, GalleryCloudState.Paused)) else emptyList())
      }
    }
  }

  /** Gli altri stati "fermi" non dicono "tutto salvato": scritture sul NAS ferme, backup congelato, account di prova, NAS in prova. */
  @Test
  fun gliAltriStatiFermiNonDiconoTuttoSalvato() {
    val still = listOf(
      LibraryBackupStatus.Problem("writes_stopped", 0),
      LibraryBackupStatus.Problem("frozen", 0),
      LibraryBackupStatus.Problem("read_only_area", 0),
      LibraryBackupStatus.TestOnly,
      LibraryBackupStatus.Queued(items = 3, wait = LibraryBackupWait.NEXT_RUN),
    )
    for (backup in still) assertWithMessage("$backup").that(stateOf(network(LibraryNetworkSeverity.Fine), upToDate, backup)).isNotEqualTo(GalleryCloudState.Saved)
    assertThat(stateOf(network(LibraryNetworkSeverity.Fine), LibraryNasStatus.LocalOnly, allSafe)).isNotEqualTo(GalleryCloudState.Saved)
    assertThat(stateOf(network(LibraryNetworkSeverity.Fine), LibraryNasStatus.Frozen(FreezeReason.CHANGED, 0L), allSafe)).isEqualTo(GalleryCloudState.Alarm)
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
    // OA-10: la pausa sta sopra i lavori in corso (finche' dura niente si salva) e sotto i problemi.
    val pause = LibraryBackupStatus.Paused(byUser = true)
    assertThat(stateOf(network(LibraryNetworkSeverity.Working), LibraryNasStatus.FirstScan(1, null, null), pause)).isEqualTo(GalleryCloudState.Paused)
    assertThat(stateOf(network(LibraryNetworkSeverity.Attention), upToDate, pause)).isEqualTo(GalleryCloudState.Attention)
    assertThat(stateOf(network(LibraryNetworkSeverity.Offline), upToDate, pause)).isEqualTo(GalleryCloudState.Offline)
    assertThat(stateOf(network(LibraryNetworkSeverity.Fine), frozen, pause)).isEqualTo(GalleryCloudState.Alarm)
    // Con un problema e la pausa insieme il pop-up dice tutti e due, il problema per primo.
    val both = checkNotNull(libraryCloud(network(LibraryNetworkSeverity.Fine), frozen, pause))
    assertThat(both.withAction.map { it.state }).containsExactly(GalleryCloudState.Alarm, GalleryCloudState.Paused).inOrder()
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
    val queued = LibraryBackupStatus.Queued(items = 3, wait = LibraryBackupWait.CHARGING)
    val cloud = checkNotNull(libraryCloud(network(LibraryNetworkSeverity.Fine), scan, queued))
    assertThat(cloud.showsSpace).isTrue()
    assertThat(cloud.problems).isEmpty()
    assertThat(cloud.activity.map { it.state }).containsExactly(GalleryCloudState.Syncing, GalleryCloudState.Waiting).inOrder()

    // I4: il backup vero non ancora acceso e' una pausa che l'utente non ha chiesto: lo spazio resta, e la pausa la dice il titolo (una volta).
    val notYet = checkNotNull(libraryCloud(network(LibraryNetworkSeverity.Fine), upToDate, LibraryBackupStatus.TestOnly))
    assertThat(notYet.state).isEqualTo(GalleryCloudState.Paused)
    assertThat(notYet.showsSpace).isTrue()
    assertThat(notYet.pauseInTitle).isEqualTo(CloudItem.Backup(LibraryBackupStatus.TestOnly, GalleryCloudState.Paused))
    // Con un problema piu' grave la pausa torna una capsula fra le voci (il titolo dice il problema).
    val withProblem = checkNotNull(libraryCloud(network(LibraryNetworkSeverity.Fine), LibraryNasStatus.Frozen(FreezeReason.CHANGED, 0L), LibraryBackupStatus.TestOnly))
    assertThat(withProblem.pauseInTitle).isNull()

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

  /** CU-1 (fase I): con le recinzioni accese tutte insieme la pausa e' una sola voce (quella del backup), nel titolo, e lo spazio resta. */
  @Test
  fun conLeRecinzioniAcceseLaPausaEUnaSola() {
    val cloud = checkNotNull(libraryCloud(network(LibraryNetworkSeverity.Fine), LibraryNasStatus.LocalOnly, LibraryBackupStatus.TestOnly))
    assertThat(cloud.state).isEqualTo(GalleryCloudState.Paused)
    assertThat(cloud.paused).containsExactly(CloudItem.Backup(LibraryBackupStatus.TestOnly, GalleryCloudState.Paused))
    assertThat(cloud.activity).isEmpty()
    assertThat(cloud.pauseInTitle).isEqualTo(CloudItem.Backup(LibraryBackupStatus.TestOnly, GalleryCloudState.Paused))
    assertThat(cloud.showsSpace).isTrue()

    // L'indice fermo da solo (il backup gia' a posto): la stessa pausa, detta dal NAS nel titolo.
    val onlyNas = checkNotNull(libraryCloud(network(LibraryNetworkSeverity.Fine), LibraryNasStatus.LocalOnly, allSafe))
    assertThat(onlyNas.pauseInTitle).isEqualTo(CloudItem.Nas(LibraryNasStatus.LocalOnly, GalleryCloudState.Paused))
    assertThat(onlyNas.showsSpace).isTrue()
  }
}
