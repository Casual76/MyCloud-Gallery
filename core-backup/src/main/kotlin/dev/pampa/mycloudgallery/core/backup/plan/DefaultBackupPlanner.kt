package dev.pampa.mycloudgallery.core.backup.plan

import dev.pampa.mycloudgallery.core.backup.contract.BackupCandidate
import dev.pampa.mycloudgallery.core.backup.contract.BackupCatalog
import dev.pampa.mycloudgallery.core.backup.contract.BackupClock
import dev.pampa.mycloudgallery.core.backup.contract.BackupEstimate
import dev.pampa.mycloudgallery.core.backup.contract.BackupFence
import dev.pampa.mycloudgallery.core.backup.contract.BackupKind
import dev.pampa.mycloudgallery.core.backup.contract.BackupPlanner
import dev.pampa.mycloudgallery.core.backup.contract.BackupSettings
import dev.pampa.mycloudgallery.core.backup.contract.BackupSource
import dev.pampa.mycloudgallery.core.backup.contract.BackupSources
import dev.pampa.mycloudgallery.core.backup.contract.BackupSummary
import dev.pampa.mycloudgallery.core.backup.contract.BackupTrigger
import dev.pampa.mycloudgallery.core.backup.contract.CandidateCursor
import dev.pampa.mycloudgallery.core.backup.contract.CandidateQuery
import dev.pampa.mycloudgallery.core.backup.contract.DeviceFolder
import dev.pampa.mycloudgallery.core.backup.contract.ExistingCopiesProgress
import dev.pampa.mycloudgallery.core.backup.contract.SummaryCount
import dev.pampa.mycloudgallery.core.backup.contract.SummaryWarning
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.SafetyStatus
import dev.pampa.mycloudgallery.core.model.VersionId
import dev.pampa.mycloudgallery.core.rules.RuleSet
import dev.pampa.mycloudgallery.core.rules.estimate.BackupEstimator
import dev.pampa.mycloudgallery.core.rules.estimate.BackupPlanEstimate
import dev.pampa.mycloudgallery.core.rules.estimate.BackupWork
import dev.pampa.mycloudgallery.core.rules.estimate.MediaVolume
import dev.pampa.mycloudgallery.core.rules.estimate.Workload
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import kotlin.math.roundToInt

/**
 * Il piano del backup (Fase 5A, P4; `docs/fase5-progetto.md` §2.1, §2.9, §2.11, D6, D7, D11, D34): quali media caricare, in che ordine,
 * dove sul NAS e quanto ci vorra'. Parte **solo** dal catalogo locale, dalle impostazioni e dalle porte: nessuna lettura del NAS, nessun
 * orologio del telefono (per "oltre domani" l'ora del NAS, [BackupClock.nasNowMs]).
 *
 * **Candidati** ([next]): media propri del catalogo nelle sorgenti scelte, non ancora al sicuro. L'ordine e' quello di D11: dal piu'
 * recente (`sort_time` decrescente); a parita' di media l'originale prima della sua versione (E-E5). Le foto vengono prima dei video
 * ([nextOverall]; il motore chiede un tipo alla volta, prima `PHOTOS`). Non restituisce:
 * - nulla finche' la scansione dell'area privata non e' finita (§2.11 punto 1): senza sapere cosa c'e' gia' sul NAS si ricaricherebbero
 *   i vecchi backup;
 * - i media che hanno gia' un elemento aperto nel diario ([alreadyPlanned]), cosi' una pagina piena di elementi in attesa non blocca
 *   quelli dopo;
 * - i media la cui sola copia sul NAS e' nell'area comune (§2.11 punto 3, Q4 (b)): non si ricaricano nell'area privata;
 * - le righe di una cartella che la recinzione di D13 non ammette (debug con gli account di prova), anche se la sorgente e' scelta.
 *
 * **Foto nuove** (D34): con [BackupTrigger.QUICK_NEW_PHOTOS] solo `PHOTOS` della **fotocamera**, mai video, al massimo [QUICK_MAX_ITEMS] e,
 * se l'ora del NAS e' nota, solo quelle degli ultimi [QUICK_RECENT_DAYS] giorni: "appena scattate", non l'arretrato del primo backup.
 *
 * **Riepilogo** ([summary]): numero e GB veri (dimensioni del MediaStore) per foto e video e per sorgente, i **tre numeri** del §2.11,
 * lo stato "sto ancora confrontando" letto da [ExistingCopiesProgress] e la stima con le regole attuali: "Stanotte" ([BackupSummary.estimate],
 * segue il preset) e "Inizia ora" ([BackupSummary.estimateNow]).
 *
 * @param rules le regole attuali (tempo, notti, reti, batteria): `RuleSettingsStore.current().toRuleSet()`.
 * @param privateShare l'area privata dell'account (`LocalDeviceProfile.privateShare`), null se non scelta.
 * @param fence D13: la recinzione del telefono in debug con gli account di prova; null in release. Con una recinzione e senza account
 *   il piano non propone niente (chiuso).
 * @param account l'account NAS di questo dispositivo, per la recinzione.
 * @param alreadyPlanned la versione ha gia' un elemento aperto nel diario (`BackupStore.openFor`).
 */
class DefaultBackupPlanner(
  private val catalog: BackupCatalog,
  private val settings: BackupSettings,
  private val existing: ExistingCopiesProgress,
  private val rules: suspend () -> RuleSet,
  private val privateShare: () -> String?,
  private val clock: BackupClock,
  private val census: ExistingCopyCensus = CatalogExistingCopyCensus(catalog),
  private val estimator: BackupEstimator = BackupEstimator(),
  private val conditions: PhoneConditionsSource = PhoneConditionsSource.UNKNOWN,
  private val fence: BackupFence? = null,
  private val account: () -> String? = { null },
  private val alreadyPlanned: suspend (VersionId) -> Boolean = { false },
  private val pageSize: Int = DEFAULT_PAGE,
) : BackupPlanner {

  init {
    // Una pagina piena puo' spezzare i candidati di uno stesso media (originale e versione): serve spazio per rimandare l'ultimo gruppo.
    require(pageSize in MIN_PAGE..CandidateQuery.MAX_LIMIT) { "Pagina di candidati fuori misura." }
  }

  // --- Candidati -----------------------------------------------------------------------------------------------------------

  override suspend fun next(kind: BackupKind, limit: Int): List<BackupCandidate> = next(kind, limit, BackupTrigger.SCHEDULED)

  override suspend fun next(kind: BackupKind, limit: Int, trigger: BackupTrigger): List<BackupCandidate> {
    require(limit >= 1) { "Serve almeno un candidato." }
    val quick = trigger == BackupTrigger.QUICK_NEW_PHOTOS
    if (quick && kind != BackupKind.PHOTOS) return emptyList() // D34: mai video
    val sources = candidateSources(quick)
    if (sources.isEmpty) return emptyList()
    // §2.11 punto 1: il piano aspetta la fine della scansione dell'area privata.
    if (!existing.current().privateAreaScanned) return emptyList()

    val wanted = if (quick) minOf(limit, QUICK_MAX_ITEMS) else limit
    val horizon = if (quick) clock.nasNowMs()?.let { it - QUICK_RECENT_DAYS * MILLIS_PER_DAY } else null
    val onlyCommonCopy = HashMap<MediaId, Boolean>()
    val chosen = ArrayList<BackupCandidate>()
    forEachPage(sources, kind) { page ->
      for (candidate in ordered(page)) {
        // Le pagine scendono nel tempo: oltre l'orizzonte sono tutte piu' vecchie.
        if (horizon != null && candidate.sortTime < horizon) return@forEachPage false
        if (!accepts(candidate, kind, sources, onlyCommonCopy)) continue
        chosen += candidate
        if (chosen.size >= wanted) return@forEachPage false
      }
      true
    }
    return chosen
  }

  /** Il piano nell'ordine di D11 su tutto il telefono: prima le foto (dal piu' recente), poi i video, fino a [limit] candidati. */
  suspend fun nextOverall(limit: Int, trigger: BackupTrigger = BackupTrigger.SCHEDULED): List<BackupCandidate> {
    val photos = next(BackupKind.PHOTOS, limit, trigger)
    if (photos.size >= limit) return photos
    return photos + next(BackupKind.VIDEOS, limit - photos.size, trigger)
  }

  /** Le sorgenti da cui prendere candidati: quelle scelte (e ammesse dalla recinzione); per le foto nuove solo la fotocamera. */
  private fun candidateSources(quick: Boolean): BackupSources {
    val allowed = allowedSources()
    return if (quick) BackupSources(allowed.sources.filterTo(HashSet()) { it == BackupSource.Camera }) else allowed
  }

  /** Le sorgenti scelte che la recinzione di D13 ammette. Con una recinzione e senza account: nessuna (si fallisce chiusi). */
  private fun allowedSources(): BackupSources {
    val chosen = settings.sources.value
    val fence = fence ?: return chosen
    val account = account() ?: return BackupSources.NONE
    return BackupSources(chosen.sources.filterTo(HashSet()) { fence.allowsPhoneFolder(account, BackupPaths.phoneFolderOf(it)) })
  }

  private suspend fun accepts(
    candidate: BackupCandidate,
    kind: BackupKind,
    sources: BackupSources,
    onlyCommonCopy: MutableMap<MediaId, Boolean>,
  ): Boolean {
    if (candidate.backupKind != kind || candidate.source !in sources) return false
    if (!sourceAllowed(candidate.source)) return false
    if (alreadyPlanned(candidate.versionId)) return false
    // §2.11 punto 3: la sola copia nell'area comune non si ricarica; ci pensa il guardiano quando serve (D9).
    val common = onlyCommonCopy.getOrPut(candidate.mediaId) { catalog.mediaSafety(candidate.mediaId)?.status == SafetyStatus.ONLY_COMMON_COPY }
    return !common
  }

  private fun sourceAllowed(source: BackupSource): Boolean {
    val fence = fence ?: return true
    val account = account() ?: return false
    return fence.allowsPhoneFolder(account, BackupPaths.phoneFolderOf(source))
  }

  /** D11: dal piu' recente; l'originale prima della sua versione (E-E5). */
  private fun ordered(page: List<BackupCandidate>): List<BackupCandidate> =
    page.sortedWith(
      compareByDescending<BackupCandidate> { it.sortTime }.thenByDescending { it.mediaId }.thenByDescending { it.original },
    )

  /**
   * Scorre i candidati di [kind] a pagine di [pageSize], dal piu' recente. Una pagina piena puo' spezzare i candidati di uno stesso media
   * (originale e versione): l'ultimo gruppo si rimanda alla pagina dopo, cosi' non se ne perde nessuno. [visit] restituisce falso per fermarsi.
   */
  private suspend fun forEachPage(sources: BackupSources, kind: BackupKind, visit: suspend (List<BackupCandidate>) -> Boolean) {
    var cursor: CandidateCursor? = null
    while (true) {
      val page = catalog.candidates(CandidateQuery(sources, kind, cursor, pageSize))
      if (page.isEmpty()) return
      val full = page.size >= pageSize
      val usable = if (full) withoutTrailingGroup(page) else page
      if (!visit(usable)) return
      if (!full) return
      val last = usable.last()
      cursor = CandidateCursor(last.sortTime, last.mediaId)
    }
  }

  /** La pagina senza l'ultimo gruppo (stesso `sort_time` e stesso media); tutta se e' un gruppo solo (non succede con [MIN_PAGE]). */
  private fun withoutTrailingGroup(page: List<BackupCandidate>): List<BackupCandidate> {
    val last = page.last()
    val complete = page.takeWhile { it.sortTime != last.sortTime || it.mediaId != last.mediaId }
    return complete.ifEmpty { page }
  }

  // --- Destinazione ------------------------------------------------------------------------------------------------------

  /**
   * D6, Q8, E-B17, E-N6: `Backup/<cartella>/[<sorgente>/]AAAA/MM/<nome>` (o `Senza data/`). Pura: l'ora del NAS serve solo a sapere cos'e'
   * "domani" per scartare le date assurde. Con la recinzione di D13 la radice e' quella di prova (`mcg-campo/Backup/...`).
   */
  override fun destination(candidate: BackupCandidate, folder: DeviceFolder): SharePath =
    BackupPaths.destination(
      folder = folder,
      source = candidate.source,
      localDay = candidate.localDay,
      latestDay = clock.nasNowMs()?.let(BackupPaths::tomorrowOf),
      fileName = candidate.fileName,
      fenceRoot = fenceRoot(),
    )

  private fun fenceRoot(): SharePath? {
    val fence = fence ?: return null
    val account = checkNotNull(account()) { "Recinzione attiva senza account: nessuna destinazione." }
    return fence.destinationRoot(account)
  }

  // --- Riepilogo del primo backup -------------------------------------------------------------------------------------------

  override suspend fun summary(): BackupSummary {
    val state = existing.current()
    val facts = conditions.current()
    val sources = allowedSources()
    if (sources.isEmpty) return BackupSummary.EMPTY.copy(existing = state, warnings = warningsOf(facts, null, null))

    // Finche' la scansione dell'area privata non e' finita non si sa cosa c'e' gia': tutto conta fra i nuovi (provvisorio, "sto
    // ancora confrontando"); senza area privata dichiarata neppure.
    val share = privateShare()?.takeIf { state.privateAreaScanned }
    var linked = SummaryCount.ZERO
    var similar = SummaryCount.ZERO
    var fresh = SummaryCount.ZERO
    var inCommon = SummaryCount.ZERO
    val bySource = LinkedHashMap<String, SummaryCount>()
    for (kind in BackupKind.entries) {
      forEachPage(sources, kind) { page ->
        val classes = if (share == null) page.map { ExistingCopyClass.FRESH } else census.classify(page, share)
        check(classes.size == page.size) { "Il censimento deve dare una classe per candidato." }
        page.forEachIndexed { index, candidate ->
          if (!sourceAllowed(candidate.source)) return@forEachIndexed
          val one = countOf(candidate)
          when (classes[index]) {
            ExistingCopyClass.IDENTICAL -> linked += one
            ExistingCopyClass.COMMON_AREA -> inCommon += one
            ExistingCopyClass.SIMILAR -> {
              similar += one
              bySource.merge(candidate.source.id, one, SummaryCount::plus)
            }
            ExistingCopyClass.FRESH -> {
              fresh += one
              bySource.merge(candidate.source.id, one, SummaryCount::plus)
            }
          }
        }
        true
      }
    }

    // Quelli la cui sola copia e' nell'area comune: il NUMERO viene dal catalogo (`safetyCounts`), non dai candidati, perche' il catalogo li
    // esclude dai candidati (seconda difesa: li toglie anche il piano). Dai candidati che il catalogo restituisce comunque si sanno tipo e
    // byte; il resto e' contato come foto di dimensione ignota (0 byte): il numero e' giusto, i GB sono per difetto.
    val commonCount = catalog.safetyCounts(sources).onlyCommonCopy
    val unseenCommon = commonCount - inCommon.items
    if (unseenCommon > 0) inCommon += SummaryCount(photos = unseenCommon, videos = 0, photoBytes = 0, videoBytes = 0)

    // Da caricare = copie simili + nuovi; le identiche si rileggono soltanto (collegamento, D8); quelle dell'area comune non si toccano.
    val toUpload = similar + fresh
    val work = BackupWork(upload = volumeOf(toUpload), reread = volumeOf(linked))
    // Niente da caricare ne' da rileggere: niente da stimare.
    if (work.isEmpty) {
      return BackupSummary.EMPTY.copy(existing = state, inCommonArea = inCommon, warnings = warningsOf(facts, null, null))
    }
    val scheduled = estimator.scheduled(rules(), work)
    val now = estimator.startNow(work, onMobileData = facts.onMobileData == true)
    return BackupSummary(
      existing = state,
      linked = linked,
      similar = similar,
      fresh = fresh,
      inCommonArea = inCommon,
      bySource = bySource,
      estimate = estimateOf(scheduled, batteryPercent = scheduled.batteryDrainPercent),
      warnings = warningsOf(facts, scheduled, now),
      estimateNow = estimateOf(now, batteryPercent = if (facts.pluggedIn == true) 0.0 else now.batteryDrainPercent),
    )
  }

  private fun countOf(candidate: BackupCandidate): SummaryCount =
    if (candidate.backupKind == BackupKind.VIDEOS) SummaryCount(0, 1, 0, candidate.size) else SummaryCount(1, 0, candidate.size, 0)

  private fun volumeOf(count: SummaryCount): MediaVolume =
    MediaVolume(Workload(count.photos.toLong(), count.photoBytes), Workload(count.videos.toLong(), count.videoBytes))

  private fun estimateOf(plan: BackupPlanEstimate, batteryPercent: Double): BackupEstimate =
    BackupEstimate(
      durationMillis = (plan.cost.workSeconds * MILLIS_PER_SECOND).toLong(),
      nights = plan.nights,
      wifiBytes = plan.wifiBytes,
      mobileBytes = plan.mobileBytes,
      // Meno di mezzo punto e' "dalla presa o niente": null.
      batteryPercent = batteryPercent.roundToInt().takeIf { it > 0 },
      neverFinishes = plan.neverFinishes,
    )

  /** Gli avvisi del riepilogo: sono solo avvisi, nessuna decisione ne dipende. */
  private fun warningsOf(facts: PhoneConditions, scheduled: BackupPlanEstimate?, now: BackupPlanEstimate?): Set<SummaryWarning> {
    val warnings = LinkedHashSet<SummaryWarning>()
    if (facts.onMobileData == true) warnings += SummaryWarning.MOBILE_DATA
    // "Meglio in carica e sul Wi-Fi di casa": per "Inizia ora" quando non c'e' la corrente (o non si sa) e il lavoro pesa sulla batteria.
    if (facts.pluggedIn != true && now != null && now.energyPercent >= ON_BATTERY_WARNING_PERCENT) warnings += SummaryWarning.ON_BATTERY
    if (facts.phoneStorageLow) warnings += SummaryWarning.PHONE_STORAGE_LOW
    if (facts.partialMediaAccess) warnings += SummaryWarning.PARTIAL_ACCESS
    if (facts.mediaLocationMissing) warnings += SummaryWarning.MEDIA_LOCATION
    if (scheduled != null && scheduled.nights > 1) warnings += SummaryWarning.LONG_FIRST_BACKUP
    return warnings
  }

  companion object {
    const val DEFAULT_PAGE: Int = 500

    /** Una pagina piu' corta dei candidati di un solo media (originale e versione) non permetterebbe di non spezzarli. */
    const val MIN_PAGE: Int = 4

    /** D34: al massimo tante foto per corsa "foto nuove". */
    const val QUICK_MAX_ITEMS: Int = 24

    /** D34: "appena scattate" = degli ultimi giorni (misurati dall'ora del NAS, mai da quella del telefono). */
    const val QUICK_RECENT_DAYS: Long = 3

    /** Sotto questa spesa il riepilogo non avverte che "Inizia ora" pesa sulla batteria. */
    const val ON_BATTERY_WARNING_PERCENT: Double = 1.0

    private const val MILLIS_PER_DAY: Long = 86_400_000L
    private const val MILLIS_PER_SECOND: Double = 1_000.0
  }
}
