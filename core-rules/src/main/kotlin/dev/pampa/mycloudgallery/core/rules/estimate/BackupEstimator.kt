package dev.pampa.mycloudgallery.core.rules.estimate

import dev.pampa.mycloudgallery.core.rules.BackgroundActivity
import dev.pampa.mycloudgallery.core.rules.PowerPolicy
import dev.pampa.mycloudgallery.core.rules.RuleSet
import kotlin.math.ceil

// LO STIMATORE DEL BACKUP (Fase 5A, P4; docs/fase5-progetto.md §2.9, docs/regole-attivita.md §6 "Backup").
//
// Il backup non e' un semplice trasferimento: ogni file si SCRIVE sul NAS e poi si RILEGGE per intero prima del nome definitivo (D4).
// Percio' (T = tempo, B = byte dei media, N = file, W = scrittura SMB, R = rilettura, o = costo per file):
//
//     T = B / W + B / R + N · o          traffico = 2 · B   (caricamento + rilettura)
//
// Le copie gia' sul NAS da collegare (§2.11) costano la sola rilettura: C / R e C byte di traffico.
//
// Le misure sono ipotesi dichiarate (`DeviceMeasures`): si ricalibrano con M2 e il primo backup vero (F22, F32); l'obiettivo del piano
// e' una stima entro ±30%.

/** Le formule del costo del backup (§2.9), usate dallo stimatore generale e da [BackupEstimator]. */
object BackupCosts {

  fun uploadSeconds(bytes: Long, measures: DeviceMeasures): Double = bytes / measures.smbWriteBytesPerSecond

  fun rereadSeconds(bytes: Long, measures: DeviceMeasures): Double = bytes / measures.verifyReadBytesPerSecond

  fun perFileSeconds(items: Long, measures: DeviceMeasures): Double = items * measures.perFileOverheadSeconds

  /**
   * Il tempo di lavoro: [upload] da caricare (scrittura, rilettura del temporaneo e costo per file) e [verify], le copie gia' sul NAS da
   * rileggere (solo la rilettura: aprire, leggere e chiudere un file costano pochi viaggi, trascurabili e non misurati).
   */
  fun seconds(upload: Workload, verify: Workload, measures: DeviceMeasures): Double =
    uploadSeconds(upload.bytes, measures) +
      rereadSeconds(upload.bytes + verify.bytes, measures) +
      perFileSeconds(upload.items, measures)

  /** Il traffico verso il NAS: i byte caricati, i byte riletti per verificarli e quelli delle copie gia' presenti riletti. */
  fun trafficBytes(upload: Workload, verify: Workload): Long = 2 * upload.bytes + verify.bytes

  /** Quanti byte di media stanno in [traffic] byte di traffico (con un tetto di dati mobili: "10 GB sono circa 5 GB di media"). */
  fun mediaBytesWithin(traffic: Long): Long = traffic / 2
}

/**
 * Il lavoro del backup da stimare: i media da caricare ([upload]) e le copie gia' sul NAS da rileggere ([reread]), foto e video. Per
 * ogni gruppo `items` = file e `bytes` = byte dei media (le dimensioni vere del MediaStore).
 */
data class BackupWork(val upload: MediaVolume = MediaVolume.NONE, val reread: MediaVolume = MediaVolume.NONE) {

  val isEmpty: Boolean get() = upload.items == 0L && reread.items == 0L

  fun photosOnly(): BackupWork = BackupWork(MediaVolume(photos = upload.photos), MediaVolume(photos = reread.photos))

  fun videosOnly(): BackupWork = BackupWork(MediaVolume(videos = upload.videos), MediaVolume(videos = reread.videos))

  /** L'arretrato nella forma dello stimatore generale: il caricamento in `pending`, la sola rilettura in `verify`. */
  fun toBacklog(): LibraryBacklog =
    LibraryBacklog(
      pending = mapOf(BackgroundActivity.PHOTO_BACKUP to upload.photos, BackgroundActivity.VIDEO_BACKUP to upload.videos),
      verify = mapOf(BackgroundActivity.PHOTO_BACKUP to reread.photos, BackgroundActivity.VIDEO_BACKUP to reread.videos),
    )
}

/** Il costo di un lavoro, senza le regole: le righe della tabella del §2.9 ("Caricamento", "Rilettura", "Costo per file"). */
data class BackupCost(
  val uploadSeconds: Double,
  val rereadSeconds: Double,
  val perFileSeconds: Double,
  val uploadedBytes: Long,
  val rereadBytes: Long,
) {
  /** Il tempo di lavoro vero. */
  val workSeconds: Double get() = uploadSeconds + rereadSeconds + perFileSeconds

  /** Il traffico verso il NAS: caricato + riletto. */
  val trafficBytes: Long get() = uploadedBytes + rereadBytes
}

/**
 * La stima del backup con le regole di adesso.
 *
 * @property cost il costo totale; [photosCost] e [videosCost] le due colonne della tabella del §2.9.
 * @property daysToFinish giorni per finire con queste regole (le attivita' di rete si dividono la banda, come nello stimatore
 *   generale); 0 = niente da fare; null = non finisce mai ([neverFinishes]).
 * @property nights le notti (o i giorni) che servono: `ceil(daysToFinish)`, almeno 1 se c'e' lavoro; 0 = niente da fare o da subito.
 * @property neverFinishes le regole non lasciano nessuna ora utile, o spengono un'attivita' che ha lavoro: il lavoro non parte.
 * @property wifiBytes traffico Wi-Fi e [mobileBytes] traffico sui dati mobili (entro il tetto) di tutto il lavoro.
 * @property energyPercent batteria che costerebbe se fosse **tutto a batteria** (traffico x %/GB, x il fattore radio per i dati mobili).
 * @property batteryDrainPercent la parte che pesa davvero sulla batteria con queste regole: solo le attivita' che possono girare anche
 *   a batteria. Con il preset Notte e' zero (dalla presa).
 */
data class BackupPlanEstimate(
  val cost: BackupCost,
  val photosCost: BackupCost,
  val videosCost: BackupCost,
  val daysToFinish: Double?,
  val nights: Int,
  val neverFinishes: Boolean,
  val wifiBytes: Long,
  val mobileBytes: Long,
  val energyPercent: Double,
  val batteryDrainPercent: Double,
  val perActivity: Map<BackgroundActivity, ActivityEstimate>,
)

/**
 * Lo stimatore del backup (P4): il costo di un lavoro ([cost]), la stima che segue le regole attuali ([scheduled], la scelta
 * "Stanotte") e quella di "Inizia ora" ([startNow], lavoro avviato dall'utente che scavalca fascia e carica). Le regole sono quelle
 * dello stimatore generale (`DefaultConsumptionEstimator`): il riepilogo del primo backup e la tabella dei preset dicono gli stessi numeri.
 */
class BackupEstimator(
  private val estimator: ConsumptionEstimator = DefaultConsumptionEstimator(),
  private val measures: DeviceMeasures = DeviceMeasures.PHASE_0,
  private val profile: UsageProfile = UsageProfile(),
) {

  fun cost(work: BackupWork): BackupCost {
    val upload = work.upload.photos + work.upload.videos
    val reread = work.reread.photos + work.reread.videos
    return BackupCost(
      uploadSeconds = BackupCosts.uploadSeconds(upload.bytes, measures),
      rereadSeconds = BackupCosts.rereadSeconds(upload.bytes + reread.bytes, measures),
      perFileSeconds = BackupCosts.perFileSeconds(upload.items, measures),
      uploadedBytes = upload.bytes,
      rereadBytes = upload.bytes + reread.bytes,
    )
  }

  /** "Stanotte" (predefinita): il backup segue le regole attuali: fascia, alimentazione, reti ammesse, tetti. */
  fun scheduled(rules: RuleSet, work: BackupWork): BackupPlanEstimate {
    val estimate = estimator.estimate(rules, work.toBacklog(), measures, profile)
    val backup = listOf(BackgroundActivity.PHOTO_BACKUP, BackgroundActivity.VIDEO_BACKUP)
    val withWork =
      backup.filter { activity ->
        val photos = activity == BackgroundActivity.PHOTO_BACKUP
        val upload = if (photos) work.upload.photos else work.upload.videos
        val reread = if (photos) work.reread.photos else work.reread.videos
        upload.items + reread.items > 0
      }
    val rows = withWork.associateWith { estimate.perActivity.getValue(it) }
    // Un'attivita' spenta con lavoro da fare non parte mai; le regole che non lasciano ore utili neppure.
    val never = rows.values.any { !it.enabled || it.daysToFinish == null }
    val days = if (never) null else estimate.daysToFinish
    val nights = if (days == null || work.isEmpty) 0 else ceil(days).toInt().coerceAtLeast(1)
    return BackupPlanEstimate(
      cost = cost(work),
      photosCost = cost(work.photosOnly()),
      videosCost = cost(work.videosOnly()),
      daysToFinish = days,
      nights = nights,
      neverFinishes = never,
      wifiBytes = estimate.wifiBytesFirstMonth,
      mobileBytes = estimate.mobileBytesFirstMonth,
      energyPercent = rows.values.filter { it.enabled }.sumOf { it.pendingEnergyPercent },
      batteryDrainPercent =
        rows.filter { (activity, row) -> row.enabled && rules.ruleFor(activity).power == PowerPolicy.BATTERY_ALLOWED }
          .values.sumOf { it.pendingEnergyPercent },
      perActivity = rows,
    )
  }

  /**
   * "Inizia ora": un lavoro avviato dall'utente (UIDT) parte subito e va avanti senza fascia ne' carica, quindi il tempo e' quello del
   * lavoro (senza l'efficienza di una giornata di regole). Tutto il traffico passa dalla rete di adesso: [onMobileData] vero = dati
   * mobili (conferma con la stima e fattore radio sull'energia, proposta 10); i tetti li controlla il valutatore al momento della
   * conferma. [BackupPlanEstimate.batteryDrainPercent] e' la batteria se il telefono e' a batteria.
   */
  fun startNow(work: BackupWork, onMobileData: Boolean = false): BackupPlanEstimate {
    val cost = cost(work)
    val traffic = cost.trafficBytes
    val factor = if (onMobileData) measures.mobileEnergyFactor else 1.0
    val energy = traffic / BYTES_PER_GB * measures.transferBatteryPercentPerGigabyte * factor
    return BackupPlanEstimate(
      cost = cost,
      photosCost = cost(work.photosOnly()),
      videosCost = cost(work.videosOnly()),
      daysToFinish = 0.0,
      nights = 0,
      neverFinishes = false,
      wifiBytes = if (onMobileData) 0L else traffic,
      mobileBytes = if (onMobileData) traffic else 0L,
      energyPercent = energy,
      batteryDrainPercent = energy,
      perActivity = emptyMap(),
    )
  }

  private companion object {
    const val BYTES_PER_GB = 1.0e9
  }
}
