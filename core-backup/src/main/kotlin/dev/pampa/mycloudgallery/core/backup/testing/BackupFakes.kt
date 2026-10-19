package dev.pampa.mycloudgallery.core.backup.testing

import dev.pampa.mycloudgallery.core.backup.contract.BackupCandidate
import dev.pampa.mycloudgallery.core.backup.contract.BackupCatalog
import dev.pampa.mycloudgallery.core.backup.contract.BackupClock
import dev.pampa.mycloudgallery.core.backup.contract.BackupEngine
import dev.pampa.mycloudgallery.core.backup.contract.BackupFence
import dev.pampa.mycloudgallery.core.backup.contract.BackupGate
import dev.pampa.mycloudgallery.core.backup.contract.BackupGateState
import dev.pampa.mycloudgallery.core.backup.contract.BackupKind
import dev.pampa.mycloudgallery.core.backup.contract.BackupPlanner
import dev.pampa.mycloudgallery.core.backup.contract.BackupRunResult
import dev.pampa.mycloudgallery.core.backup.contract.BackupSettings
import dev.pampa.mycloudgallery.core.backup.contract.BackupSource
import dev.pampa.mycloudgallery.core.backup.contract.BackupSources
import dev.pampa.mycloudgallery.core.backup.contract.BackupStatus
import dev.pampa.mycloudgallery.core.backup.contract.BackupSummary
import dev.pampa.mycloudgallery.core.backup.contract.BackupTrigger
import dev.pampa.mycloudgallery.core.backup.contract.CandidateQuery
import dev.pampa.mycloudgallery.core.backup.contract.DeviceFolder
import dev.pampa.mycloudgallery.core.backup.contract.ExistingCopiesProgress
import dev.pampa.mycloudgallery.core.backup.contract.ExistingCopiesState
import dev.pampa.mycloudgallery.core.backup.contract.GuardReport
import dev.pampa.mycloudgallery.core.backup.contract.GuardScope
import dev.pampa.mycloudgallery.core.backup.contract.PrivateCopyGuardian
import dev.pampa.mycloudgallery.core.backup.contract.RunStop
import dev.pampa.mycloudgallery.core.backup.contract.SafetyVerifier
import dev.pampa.mycloudgallery.core.backup.contract.StopBy
import dev.pampa.mycloudgallery.core.backup.contract.SummaryCount
import dev.pampa.mycloudgallery.core.backup.contract.VerifyReport
import dev.pampa.mycloudgallery.core.backup.contract.VerifyScope
import dev.pampa.mycloudgallery.core.backup.plan.DefaultBackupPlanner
import dev.pampa.mycloudgallery.core.backup.settings.BackupSourceFence
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import kotlin.time.Duration
import kotlinx.coroutines.flow.MutableStateFlow

// Fase 5A (P0): i fake semplici di core-backup, per i test di P2-P6 e per la UI di P5 (stati scelti dal test, nessun NAS). Mai nella
// release: tools/check.ps1 vieta i package `testing` nel dex.

/** Il cancello del backup, per share; di default aperto. */
class FakeBackupGate(var default: BackupGateState = BackupGateState.OPEN) : BackupGate {
  val byShare: MutableMap<String, BackupGateState> = HashMap()

  override fun stateOf(share: String): BackupGateState = byShare[share] ?: default
}

/** Gli orologi del backup: il monotono e l'ora del NAS li sposta il test (mai l'orologio del telefono). */
class FakeBackupClock(var monotonic: Long = 1_000L, var nasNow: Long? = DEFAULT_NAS_NOW) : BackupClock {
  override fun monotonicMs(): Long = monotonic

  override fun nasNowMs(): Long? = nasNow

  fun advance(ms: Long) {
    monotonic += ms
    nasNow = nasNow?.plus(ms)
  }

  companion object {
    /** 2026-10-16T00:00:00Z: un'ora del NAS plausibile. */
    const val DEFAULT_NAS_NOW: Long = 1_792_108_800_000L
  }
}

/** Il confronto con cio' che c'e' sul NAS (§2.11): lo fa avanzare il test. */
class FakeExistingCopies(var state: ExistingCopiesState = ExistingCopiesState.SETTLED) : ExistingCopiesProgress {
  override suspend fun current(): ExistingCopiesState = state
}

/**
 * Le impostazioni del backup in memoria. Con [fence] (BK-14) valgono le stesse regole di D13 delle impostazioni vere ([BackupSourceFence]): le
 * sorgenti sono solo quelle ammesse per l'account di adesso, nessuna senza account; [refresh] le ricalcola quando l'account arriva.
 * Senza recinzione (di serie) nessuna differenza: i test che scrivono `sources.value` a mano restano come erano.
 */
class FakeBackupSettings(
  sources: BackupSources = BackupSources.DEFAULT,
  folder: DeviceFolder? = null,
  private val fence: BackupFence? = null,
  private val account: () -> String? = { null },
) : BackupSettings {
  private var chosen: BackupSources = sources

  override val sources: MutableStateFlow<BackupSources> = MutableStateFlow(BackupSourceFence.restrict(sources, fence, account()))

  override val deviceFolder: MutableStateFlow<DeviceFolder?> = MutableStateFlow(folder)

  override suspend fun setSources(sources: BackupSources) {
    chosen = sources
    this.sources.value = BackupSourceFence.restrict(sources, fence, account())
  }

  /** Come `DefaultBackupSettings.refresh`: le sorgenti ammesse con l'account di adesso. */
  fun refresh() {
    sources.value = BackupSourceFence.restrict(chosen, fence, account())
  }

  override suspend fun chooseFolder(folder: DeviceFolder) {
    // D7: la cartella si sceglie una volta; una seconda scelta diversa e' un errore di programmazione (E-B7).
    check(deviceFolder.value == null || deviceFolder.value == folder) { "La cartella del dispositivo e' gia' scelta." }
    deviceFolder.value = folder
  }
}

/**
 * Il motore del backup per la UI (P5) e gli screenshot: lo stato lo sceglie il test ([status]), i giri si ricordano ([runs]) e "Ferma"
 * porta a `Stopped`. Nessun NAS, nessun diario.
 */
class ScriptedBackupEngine(initial: BackupStatus = BackupStatus.TestOnly) : BackupEngine {
  override val status: MutableStateFlow<BackupStatus> = MutableStateFlow(initial)

  /** I giri chiesti: (tipo, chi l'ha chiesto); `resumeAll` vale (null, null). */
  val runs: MutableList<Pair<BackupKind?, BackupTrigger?>> = ArrayList()

  /** Le richieste di "Ferma". */
  val stops: MutableList<StopBy> = ArrayList()

  /** L'esito che i giri restituiscono. */
  var result: BackupRunResult = BackupRunResult.nothing(RunStop.COMPLETE)

  override suspend fun run(kind: BackupKind, budget: Duration, trigger: BackupTrigger): BackupRunResult {
    runs += kind to trigger
    return result
  }

  override suspend fun resumeAll(budget: Duration): BackupRunResult {
    runs += null to null
    return result
  }

  override suspend fun stop(by: StopBy) {
    stops += by
    status.value = BackupStatus.Stopped(by)
  }
}

/**
 * Il piano di riferimento (per il motore di riferimento e il banco, prima di P4): candidati dal catalogo, destinazione
 * `Backup/<cartella>/AAAA/MM/<nome>` per la fotocamera, `Backup/<cartella>/<Sorgente>/AAAA/MM/<nome>` per screenshot e cartelle
 * aggiuntive (Q8), `Senza data/` per una data assente o prima del 1990 (E-B17). Il riepilogo conta solo i "nuovi": i tre numeri veri
 * (collegati, simili, nuovi) sono di P4.
 */
class ReferenceBackupPlanner(
  private val catalog: BackupCatalog,
  private val sources: () -> BackupSources = { BackupSources.DEFAULT },
  private val existing: ExistingCopiesProgress = FakeExistingCopies(),
  /** Per "appena scattate" di D34 (gli ultimi giorni, dall'ora del NAS): senza orologio non c'e' orizzonte, resta il tetto sul numero. */
  private val clock: BackupClock? = null,
) : BackupPlanner {

  override suspend fun summary(): BackupSummary {
    var fresh = SummaryCount.ZERO
    for (kind in BackupKind.entries) {
      for (c in catalog.candidates(CandidateQuery(sources(), kind, after = null, limit = CandidateQuery.MAX_LIMIT))) {
        fresh += if (kind == BackupKind.VIDEOS) SummaryCount(0, 1, 0, c.size) else SummaryCount(1, 0, c.size, 0)
      }
    }
    return BackupSummary.EMPTY.copy(existing = existing.current(), fresh = fresh)
  }

  override suspend fun next(kind: BackupKind, limit: Int): List<BackupCandidate> =
    catalog.candidates(CandidateQuery(sources(), kind, after = null, limit = limit.coerceIn(1, CandidateQuery.MAX_LIMIT)))
      .sortedWith(compareByDescending<BackupCandidate> { it.original }.thenByDescending { it.sortTime })

  /**
   * Il trigger come lo legge il piano vero (BK-1, D34): con [BackupTrigger.QUICK_NEW_PHOTOS] solo le **foto** della **fotocamera**, mai i
   * video, al massimo [DefaultBackupPlanner.QUICK_MAX_ITEMS] e, se c'e' l'orologio, quelle degli ultimi [DefaultBackupPlanner.QUICK_RECENT_DAYS]
   * giorni. Prima la firma ignorava il trigger e un giro "foto nuove" proponeva (e il motore guidava) tutto l'arretrato.
   */
  override suspend fun next(kind: BackupKind, limit: Int, trigger: BackupTrigger): List<BackupCandidate> {
    if (trigger != BackupTrigger.QUICK_NEW_PHOTOS) return next(kind, limit)
    if (kind != BackupKind.PHOTOS) return emptyList()
    val camera = BackupSources(sources().sources.filterTo(HashSet()) { it == BackupSource.Camera })
    if (camera.isEmpty) return emptyList()
    val horizon = clock?.nasNowMs()?.let { it - DefaultBackupPlanner.QUICK_RECENT_DAYS * MILLIS_PER_DAY }
    return catalog.candidates(CandidateQuery(camera, kind, after = null, limit = CandidateQuery.MAX_LIMIT))
      .filter { horizon == null || it.sortTime >= horizon }
      .sortedWith(compareByDescending<BackupCandidate> { it.sortTime }.thenByDescending { it.original })
      .take(minOf(limit.coerceAtLeast(1), DefaultBackupPlanner.QUICK_MAX_ITEMS))
  }

  override fun destination(candidate: BackupCandidate, folder: DeviceFolder): SharePath {
    var dir = SharePath.of("Backup", folder.name)
    when (val source = candidate.source) {
      BackupSource.Camera -> Unit
      BackupSource.Screenshots -> dir = dir.child("Screenshot")
      is BackupSource.Folder -> dir = dir.child(source.relativePath.trimEnd('/').substringAfterLast('/').ifBlank { "Altro" })
    }
    val day = candidate.localDay
    dir = if (day == null || day / 10_000 < 1990) {
      dir.child("Senza data")
    } else {
      dir.child("%04d".format(day / 10_000)).child("%02d".format(day / 100 % 100))
    }
    return dir.child(candidate.fileName)
  }
}

private const val MILLIS_PER_DAY: Long = 86_400_000L

/** Le verifiche finte: restituiscono [report] e ricordano gli ambiti chiesti. */
class FakeSafetyVerifier(var report: VerifyReport = VerifyReport(0, 0, 0, 0, 0, RunStop.COMPLETE)) : SafetyVerifier {
  val scopes: MutableList<VerifyScope> = ArrayList()

  override suspend fun verify(scope: VerifyScope, budget: Duration): VerifyReport {
    scopes += scope
    return report
  }
}

/** Il guardiano finto: restituisce [report] e ricorda gli ambiti chiesti. */
class FakePrivateCopyGuardian(var report: GuardReport = GuardReport(0, 0, 0, 0, 0, RunStop.COMPLETE)) : PrivateCopyGuardian {
  val scopes: MutableList<GuardScope> = ArrayList()

  override suspend fun ensure(scope: GuardScope, budget: Duration): GuardReport {
    scopes += scope
    return report
  }
}
