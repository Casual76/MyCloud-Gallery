package dev.pampa.mycloudgallery.backup

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.backup.contract.BackupCatalog
import dev.pampa.mycloudgallery.core.backup.contract.BackupKind
import dev.pampa.mycloudgallery.core.backup.contract.BackupProblemCode
import dev.pampa.mycloudgallery.core.backup.contract.BackupStatus
import dev.pampa.mycloudgallery.core.backup.contract.BackupSummary
import dev.pampa.mycloudgallery.core.backup.contract.BackupTrigger
import dev.pampa.mycloudgallery.core.backup.contract.CopyToVerify
import dev.pampa.mycloudgallery.core.backup.contract.ExistingCopiesState
import dev.pampa.mycloudgallery.core.backup.contract.NasCopyRef
import dev.pampa.mycloudgallery.core.backup.contract.SummaryCount
import dev.pampa.mycloudgallery.core.backup.contract.VerifyScope
import dev.pampa.mycloudgallery.core.backup.contract.WaitReason as BackupWait
import dev.pampa.mycloudgallery.core.backup.testing.InMemoryBackupCatalog
import dev.pampa.mycloudgallery.core.library.contract.LibraryBackupStatus
import dev.pampa.mycloudgallery.core.library.contract.LibraryBackupWait
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.rules.NetworkClass
import dev.pampa.mycloudgallery.core.rules.RuleDecision
import dev.pampa.mycloudgallery.core.rules.WaitReason
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import dev.pampa.mycloudgallery.notify.NasDailySummary
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * I pezzi puri del backup in `:app` (Fase 5A, P7): lo stato ridotto per il piede della Libreria, il perche' dell'attesa, la divisione delle
 * verifiche per peso e il riepilogo giornaliero (solo numeri; "fermo da 3 giorni" parte dall'ultimo giro o da quando il backup e' attivo).
 */
class BackupPiecesTest {

  // --- BackupStatus -> LibraryBackupStatus ---------------------------------------------------------------------------------------

  @Test
  fun ilPiedeDiceSoloCioCheServe() {
    assertThat(BackupStatusReducers.footer(BackupStatus.NotConfigured(emptySet()), 0)).isEqualTo(LibraryBackupStatus.Idle)
    assertThat(BackupStatusReducers.footer(BackupStatus.TestOnly, 0)).isEqualTo(LibraryBackupStatus.TestOnly)
    assertThat(BackupStatusReducers.footer(BackupStatus.UpToDate(10, 5L), 0)).isEqualTo(LibraryBackupStatus.AllSafe(5L))
    assertThat(BackupStatusReducers.footer(BackupStatus.Waiting(312, 1L, BackupWait.CHARGING), 0))
      .isEqualTo(LibraryBackupStatus.Queued(312, LibraryBackupWait.CHARGING))
    assertThat(BackupStatusReducers.footer(BackupStatus.Stopped(dev.pampa.mycloudgallery.core.backup.contract.StopBy.USER), 0))
      .isEqualTo(LibraryBackupStatus.Idle)
    assertThat(BackupStatusReducers.footer(BackupStatus.Problem(BackupProblemCode.QUOTA, 0), 0))
      .isEqualTo(LibraryBackupStatus.Problem("quota", 0))
  }

  @Test
  fun ilRiepilogoDelPrimoBackupDiceQuantoCeDaCaricareEIlConfrontoInCorso() {
    val settled = BackupSummary.EMPTY.copy(
      fresh = SummaryCount(photos = 8, videos = 2, photoBytes = 10, videoBytes = 90),
      linked = SummaryCount(photos = 3, videos = 0, photoBytes = 3, videoBytes = 0),
    )
    assertThat(BackupStatusReducers.footer(BackupStatus.FirstBackupReady(settled), 0)).isEqualTo(LibraryBackupStatus.Queued(13, LibraryBackupWait.NEXT_RUN))
    val comparing = settled.copy(existing = ExistingCopiesState(privateAreaScanned = true, similarPending = 7))
    assertThat(BackupStatusReducers.footer(BackupStatus.FirstBackupReady(comparing), similarPending = 7)).isEqualTo(LibraryBackupStatus.Comparing(7))
    // Finche' la scansione dell'area privata non e' finita il numero dei confronti non si dice (non e' definitivo).
    val scanning = settled.copy(existing = ExistingCopiesState(privateAreaScanned = false, similarPending = 7))
    assertThat(BackupStatusReducers.footer(BackupStatus.FirstBackupReady(scanning), similarPending = 7)).isEqualTo(LibraryBackupStatus.Comparing(null))
    assertThat(BackupStatusReducers.footer(BackupStatus.FirstBackupReady(BackupSummary.EMPTY), 0)).isEqualTo(LibraryBackupStatus.Idle)
  }

  @Test
  fun ilPercheDellAttesaDalleRegole() {
    fun wait(reason: WaitReason) = BackupStatusReducers.waitReasonOf(RuleDecision.Wait(reason))
    assertThat(wait(WaitReason.NeedsPower)).isEqualTo(BackupWait.CHARGING)
    assertThat(wait(WaitReason.BatteryLow(10, 20))).isEqualTo(BackupWait.BATTERY)
    assertThat(wait(WaitReason.HeavyNeedsHomeWifi)).isEqualTo(BackupWait.HOME_WIFI)
    assertThat(wait(WaitReason.NetworkNotAllowed(NetworkClass.MOBILE))).isEqualTo(BackupWait.WIFI)
    assertThat(wait(WaitReason.NetworkNotAllowed(NetworkClass.EXTERNAL_WIFI))).isEqualTo(BackupWait.HOME_WIFI)
    assertThat(wait(WaitReason.NasUnreachable)).isEqualTo(BackupWait.NAS)
    assertThat(wait(WaitReason.Roaming)).isEqualTo(BackupWait.NEXT_RUN)
    assertThat(BackupStatusReducers.waitReasonOf(RuleDecision.Run(network = NetworkClass.HOME_WIFI))).isEqualTo(BackupWait.NEXT_RUN)
  }

  // --- Le verifiche per peso -----------------------------------------------------------------------------------------------------

  private fun copy(size: Long) = CopyToVerify(
    NasCopyRef(null, MediaId("00000000-0000-4000-8000-000000000001"), null, "test", SharePath.parse("Backup/a.jpg"), null),
    size = size, expected = null, verifiedAtMillis = null,
  )

  /** Un catalogo che serve una lista fissa a pagine, come il vero (`limit` rispettato). */
  private class Pages(private val all: List<CopyToVerify>) : BackupCatalog by InMemoryBackupCatalog("test") {
    var reads = 0

    override suspend fun verifyCandidates(scope: VerifyScope, limit: Int): List<CopyToVerify> {
      reads++
      return all.take(limit)
    }
  }

  @Test
  fun leVerificheLeggereEPesantiSiDividonoPerDimensione() = runBlocking {
    val heavy = SizeFilteredCatalog.HEAVY_BYTES
    val all = listOf(copy(1_000), copy(heavy + 1), copy(2_000), copy(heavy), copy(3_000))
    val light = SizeFilteredCatalog(Pages(all), maxBytes = heavy - 1)
    val video = SizeFilteredCatalog(Pages(all), minBytes = heavy)
    assertThat(light.verifyCandidates(VerifyScope.Unverified, 10).map { it.size }).containsExactly(1_000L, 2_000L, 3_000L).inOrder()
    assertThat(video.verifyCandidates(VerifyScope.Rotation, 10).map { it.size }).containsExactly(heavy + 1, heavy).inOrder()
  }

  @Test
  fun unaPaginaFiltrataNonDiceFinitoSeCeAltro() = runBlocking {
    // 10 pesanti davanti e 3 leggere dietro: chiedendone 2 il filtro rilegge con un limite doppio finche' ne trova.
    val heavy = SizeFilteredCatalog.HEAVY_BYTES
    val all = List(10) { copy(heavy + it) } + List(3) { copy(100L + it) }
    val pages = Pages(all)
    val light = SizeFilteredCatalog(pages, maxBytes = heavy - 1)
    assertThat(light.verifyCandidates(VerifyScope.Unverified, 2).map { it.size }).containsExactly(100L, 101L).inOrder()
    assertThat(pages.reads).isGreaterThan(1)
  }

  // --- Il riepilogo giornaliero --------------------------------------------------------------------------------------------------

  private val day = 24L * 60 * 60 * 1000

  private fun facts(
    queued: Int = 0,
    lastBeatAt: Long? = null,
    startedAt: Long? = null,
    problem: BackupProblemCode? = null,
    lost: Int = 0,
    notIntact: Int = 0,
    common: Int = 0,
  ) = BackupDailyFacts(queued, lastBeatAt, startedAt, problem, lost, notIntact, common)

  @Test
  fun ilBackupFermoDaTreGiorniSoloSeCeQualcosaInCoda() {
    val now = 100 * day
    // Un backup iniziato oggi non e' fermo da settimane.
    assertThat(NasDailySummary.backupProblems(facts(queued = 5, startedAt = now - day), now)).isEmpty()
    assertThat(NasDailySummary.backupProblems(facts(queued = 5, startedAt = now - 4 * day), now)).containsExactly(NasDailySummary.Problem.BackupStalled(4))
    // Conta l'ultimo giro che ha fatto strada, non l'inizio.
    assertThat(NasDailySummary.backupProblems(facts(queued = 5, lastBeatAt = now - 2 * day, startedAt = now - 30 * day), now)).isEmpty()
    assertThat(NasDailySummary.backupProblems(facts(queued = 5, lastBeatAt = now - 3 * day, startedAt = now - 30 * day), now))
      .containsExactly(NasDailySummary.Problem.BackupStalled(3))
    // Senza niente in coda e' al passo, non fermo.
    assertThat(NasDailySummary.backupProblems(facts(queued = 0, lastBeatAt = now - 10 * day), now)).isEmpty()
    // Il backup non attivo (nessuna cartella): nessuna voce.
    assertThat(NasDailySummary.backupProblems(null, now)).isEmpty()
  }

  @Test
  fun quotaNasPienoPerseCopieNonIntegreECopiaComunePersa() {
    val now = 5 * day
    assertThat(NasDailySummary.backupProblems(facts(problem = BackupProblemCode.QUOTA), now)).containsExactly(NasDailySummary.Problem.BackupQuota)
    assertThat(NasDailySummary.backupProblems(facts(problem = BackupProblemCode.NAS_FULL), now)).containsExactly(NasDailySummary.Problem.BackupNasFull)
    // Gli altri problemi che fermano un giro non entrano nel riepilogo (li raccontano la pagina e la Diagnostica).
    assertThat(NasDailySummary.backupProblems(facts(problem = BackupProblemCode.NAS_UNREACHABLE), now)).isEmpty()
    assertThat(NasDailySummary.backupProblems(facts(lost = 3, notIntact = 2, common = 1), now)).containsExactly(
      NasDailySummary.Problem.BackupLost(3),
      NasDailySummary.Problem.BackupNotIntact(2),
      NasDailySummary.Problem.BackupCommonCopyLost(1),
    ).inOrder()
  }

  @Test
  fun iNumeriDelPrimoBackupNonSonoMaiNegativiEILiberiDaNomi() {
    // BackupTrigger / BackupKind sono enumerati con codici stabili: nessun nome di file puo' arrivarci.
    assertThat(BackupTrigger.entries.map { it.code }).doesNotContain("")
    assertThat(BackupKind.entries.map { it.code }).containsExactly("photos", "videos")
  }
}
