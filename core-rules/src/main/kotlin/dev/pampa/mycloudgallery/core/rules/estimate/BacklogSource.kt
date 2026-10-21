package dev.pampa.mycloudgallery.core.rules.estimate

import dev.pampa.mycloudgallery.core.rules.BackgroundActivity

// L'ARRETRATO VERO per le stime (docs/fase4-progetto.md §2.8 e §2.10, P9). Finora le stime dell'onboarding partivano da una
// "libreria tipica" scritta nel codice; dalla Fase 4 partono da cio' che il catalogo e le teste dell'indice dicono davvero:
// quanti media aspettano ancora di essere rilevati, preparati, scaricati o messi al sicuro, con i loro byte.
//
// `core-rules` non vede il database ne' l'indice: `:app` (P11) implementa [BacklogFactsReader] sopra le code del catalogo
// (`MediaCatalog`, P2) e `ReplicaBlobs.estimate` (P4) e consegna solo numeri ([BacklogFacts]). Da qui in poi e' una
// funzione pura: [BacklogFacts] + [NasCosts] → [LibraryBacklog] per [ConsumptionEstimator]. Le costanti di
// [DeviceMeasures.PHASE_0] non cambiano: le ricalibra la settimana di prova (F18).

/** Dove lo stimatore prende l'arretrato di adesso e il flusso mensile. */
fun interface BacklogSource {
  suspend fun current(): LibraryBacklog
}

/** Una quantita' di media: foto e video, con i byte degli **originali** (dalla dimensione nel catalogo). */
data class MediaVolume(val photos: Workload = Workload.NONE, val videos: Workload = Workload.NONE) {
  val items: Long get() = photos.items + videos.items
  val bytes: Long get() = photos.bytes + videos.bytes

  companion object {
    val NONE = MediaVolume()
  }
}

/**
 * Quanto della replica tiene questo dispositivo (D16, Q2): rispecchia `QualityLevel` di `core-index`, che `core-rules` non
 * vede. `SAVER` = miniature S; `STANDARD` = S + M; `HIGH` = S + M + HQ.
 */
enum class ReplicaDepth { SAVER, STANDARD, HIGH }

/**
 * Cosa costa un media sul NAS, in byte di traffico (§2.10 di docs/fase4-progetto.md, numeri dell'inventario). Valgono dove
 * il catalogo e le teste non danno il numero esatto: il rilevamento, la preparazione, il flusso del mese. Sostituibili
 * dopo la settimana di prova.
 *
 * | Voce | Valore | Fonte |
 * |---|---|---|
 * | record `meta` | 0,5 KB | §2.10 |
 * | miniatura S / M | 6 / 22 KB | §2.10 |
 * | anteprima di analisi (foto) / fotogrammi (video) | 150 / 450 KB | §2.10, "analysis + frames" |
 * | anteprima HQ | 400 KB | §2.10 |
 * | rilevamento: foto / video | 128 KiB / 128 KiB + ~400 KB d'indice del contenitore | §2.10, "Prima scansione" |
 * | preparazione: lettura di un video | ~5 MB (indice + fotogrammi, mai il video intero) | §2.10 |
 */
data class NasCosts(
  val metaBytes: Long = 500L,
  val thumbSBytes: Long = 6_000L,
  val thumbMBytes: Long = 22_000L,
  val analysisBytes: Long = 150_000L,
  val hqBytes: Long = 400_000L,
  val videoFramesBytes: Long = 450_000L,
  val detectPhotoBytes: Long = 128L * 1024,
  val detectVideoBytes: Long = 128L * 1024 + 400_000L,
  val prepareVideoReadBytes: Long = 5_000_000L,
) {
  init {
    require(
      listOf(metaBytes, thumbSBytes, thumbMBytes, analysisBytes, hqBytes, videoFramesBytes, detectPhotoBytes, detectVideoBytes, prepareVideoReadBytes)
        .all { it >= 0 },
    ) { "Un costo non puo' essere negativo." }
  }

  /** Cio' che si scarica per ogni media a [depth] (`meta` e miniature; l'HQ e' a parte, vedi [hqBytes]). */
  fun replicaBytesPerMedia(depth: ReplicaDepth): Long =
    metaBytes + thumbSBytes + if (depth == ReplicaDepth.SAVER) 0L else thumbMBytes

  /** Il primo allineamento: [items] media a [depth]. */
  fun replica(items: Long, depth: ReplicaDepth): Workload = Workload(items, items * replicaBytesPerMedia(depth))

  /** Le anteprime HQ di [items] media (solo con la qualita' Alta). */
  fun hq(items: Long, depth: ReplicaDepth): Workload =
    if (depth == ReplicaDepth.HIGH) Workload(items, items * hqBytes) else Workload.NONE

  /** Il rilevamento (testa e coda, e per i video l'indice del contenitore): non dipende dalla dimensione dell'originale. */
  fun detection(volume: MediaVolume): Workload =
    Workload(volume.items, volume.photos.items * detectPhotoBytes + volume.videos.items * detectVideoBytes)

  /**
   * La preparazione, in traffico: legge l'originale di ogni foto per intero e di ogni video ~5 MB, e scrive tutte le classi
   * (foto: S, M, analisi, HQ; video: S, M, fotogrammi, HQ, piu' il record).
   */
  fun preparation(volume: MediaVolume): Workload {
    val read = volume.photos.bytes + volume.videos.items * prepareVideoReadBytes
    val written =
      volume.photos.items * (metaBytes + thumbSBytes + thumbMBytes + analysisBytes + hqBytes) +
        volume.videos.items * (metaBytes + thumbSBytes + thumbMBytes + videoFramesBytes + hqBytes)
    return Workload(volume.items, read + written)
  }

  /** Le anteprime di analisi da scaricare per [items] media (Fase 6). */
  fun analysis(items: Long): Workload = Workload(items, items * analysisBytes)

  companion object {
    /** I numeri di §2.10. */
    val DOC_2_10 = NasCosts()
  }
}

/** Quanto arriva in un mese tipico (media degli ultimi mesi, dal catalogo). */
data class MonthlyArrivals(
  /** Media nuovi sul NAS: li scarica la replica e li rileva la scansione. */
  val onNas: MediaVolume = MediaVolume.NONE,
  /** Di quelli, i media che prepara questo dispositivo (ruolo Preparatore); vuoto negli altri. */
  val toPrepare: MediaVolume = MediaVolume.NONE,
  /** Media nuovi del telefono da mettere al sicuro (Fase 5). */
  val onPhoneToProtect: MediaVolume = MediaVolume.NONE,
  /** Media nuovi da analizzare (Fase 6). */
  val toAnalyze: Long = 0L,
  /** Fase 6 (P5): i media nuovi del mese da analizzare per tipo e classe; se c'e', vale al posto di [toAnalyze]. */
  val analyze: AiWork? = null,
)

/**
 * I numeri del catalogo e delle teste, come li consegna `:app`: solo conteggi e byte, nessun nome, percorso o id. Una
 * attivita' senza lavoro ha il valore vuoto.
 *
 * @property replica dalle teste (E-I9): media e byte di `meta` e delle classi scelte ancora da scaricare.
 * @property hq dalle teste: media e byte delle anteprime HQ ancora da scaricare (solo con la qualita' Alta; con un'altra
 *   le regole spengono `HQ_PREVIEWS` e questo valore non pesa).
 * @property toDetect dal catalogo: file del NAS visti nell'elenco e non ancora rilevati ne' adottati dall'indice (D9).
 * @property toPrepare dal catalogo: media del NAS senza le classi di anteprima **che tocca preparare a questo
 *   dispositivo** (vuoto se non e' un Preparatore).
 * @property toProtect dal catalogo: media del telefono non ancora al sicuro sul NAS (backup, Fase 5).
 * @property toVerify dal catalogo (Fase 5): copie gia' sul NAS da **rileggere** senza caricare niente (le copie identiche da
 *   collegare, §2.11 punto 5; le copie private non ancora verificate): costano la sola rilettura e il suo traffico.
 * @property toFullHash dal catalogo (`WorkStage.FULL_HASH`): video del NAS in attesa dell'hash completo, byte da rileggere.
 * @property toAnalyze dal catalogo: media in attesa dell'anteprima di analisi (Fase 6).
 * @property analyze Fase 6 (P5): il backlog vero del calcolo AI, contato dal calcolo stesso (`AiBacklog` di core-ai sopra le stesse
 *   porte dei candidati): foto, video e testi da leggere. Se c'e', vale al posto di [toAnalyze] (tempo dai vettori e dall'OCR,
 *   traffico solo per le anteprime non ancora nel buffer: [AiComputeCosts]).
 */
data class BacklogFacts(
  val replica: Workload = Workload.NONE,
  val hq: Workload = Workload.NONE,
  val toDetect: MediaVolume = MediaVolume.NONE,
  val toPrepare: MediaVolume = MediaVolume.NONE,
  val toProtect: MediaVolume = MediaVolume.NONE,
  val toFullHash: Workload = Workload.NONE,
  val toAnalyze: Long = 0L,
  val monthly: MonthlyArrivals = MonthlyArrivals(),
  val toVerify: MediaVolume = MediaVolume.NONE,
  val analyze: AiWork? = null,
) {
  init {
    require(toAnalyze >= 0 && monthly.toAnalyze >= 0) { "Un conteggio non puo' essere negativo." }
  }

  /**
   * L'arretrato nella forma dello stimatore. Per attivita':
   *
   *  - `INDEX_SYNC` = replica da scaricare + rilevamento dei file nuovi (byte sommati);
   *  - `PREPARATION` = letture degli originali + scritture delle classi (traffico);
   *  - `HQ_PREVIEWS` = le anteprime HQ da scaricare;
   *  - `PHOTO_BACKUP` / `VIDEO_BACKUP` = media del telefono da mettere al sicuro (`pending`: byte dei media, il traffico e' il doppio
   *    perche' si rileggono, Fase 5); le copie gia' sul NAS da rileggere ([toVerify], e per i video gli hash completi [toFullHash])
   *    stanno in `verify` e costano la sola rilettura;
   *  - `AI_COMPUTE` = le anteprime di analisi da scaricare (Fase 6); con [analyze] il lavoro vero del calcolo ([AiComputeCosts]:
   *    vettori, fotogrammi e testo con le misure [measures]).
   *
   * Il flusso mensile usa gli stessi costi sui media nuovi di un mese ([MonthlyArrivals]).
   */
  fun toLibraryBacklog(
    depth: ReplicaDepth,
    costs: NasCosts = NasCosts.DOC_2_10,
    measures: DeviceMeasures = DeviceMeasures.PHASE_0,
  ): LibraryBacklog {
    val newOnNas = monthly.onNas
    val pending =
      mapOf(
        BackgroundActivity.INDEX_SYNC to syncAndDetect(replica, costs.detection(toDetect)),
        BackgroundActivity.PREPARATION to costs.preparation(toPrepare),
        BackgroundActivity.HQ_PREVIEWS to hq,
        BackgroundActivity.PHOTO_BACKUP to toProtect.photos,
        BackgroundActivity.VIDEO_BACKUP to toProtect.videos,
        BackgroundActivity.AI_COMPUTE to (analyze?.let { AiComputeCosts.workload(it, measures, costs) } ?: costs.analysis(toAnalyze)),
      )
    val monthlyNew =
      mapOf(
        BackgroundActivity.INDEX_SYNC to syncAndDetect(costs.replica(newOnNas.items, depth), costs.detection(newOnNas)),
        BackgroundActivity.PREPARATION to costs.preparation(monthly.toPrepare),
        BackgroundActivity.HQ_PREVIEWS to costs.hq(newOnNas.items, depth),
        BackgroundActivity.PHOTO_BACKUP to monthly.onPhoneToProtect.photos,
        BackgroundActivity.VIDEO_BACKUP to monthly.onPhoneToProtect.videos,
        BackgroundActivity.AI_COMPUTE to (monthly.analyze?.let { AiComputeCosts.workload(it, measures, costs) } ?: costs.analysis(monthly.toAnalyze)),
      )
    val verify =
      mapOf(
        BackgroundActivity.PHOTO_BACKUP to toVerify.photos,
        BackgroundActivity.VIDEO_BACKUP to toVerify.videos + toFullHash,
      )
    return LibraryBacklog(pending = pending, monthlyNew = monthlyNew, verify = verify)
  }
}

/** Chi legge i numeri veri: `:app` (P11), sopra le code del catalogo e `ReplicaBlobs.estimate`. */
fun interface BacklogFactsReader {
  suspend fun read(): BacklogFacts
}

/**
 * L'arretrato vero: [reader] conta, [depth] dice a che livello e' la replica (cambia se l'utente cambia la qualita'),
 * [costs] traduce i conteggi in byte dove non c'e' il numero esatto.
 */
class FactsBacklogSource(
  private val reader: BacklogFactsReader,
  private val depth: () -> ReplicaDepth,
  private val costs: NasCosts = NasCosts.DOC_2_10,
  private val measures: () -> DeviceMeasures = { DeviceMeasures.PHASE_0 },
) : BacklogSource {
  override suspend fun current(): LibraryBacklog = reader.read().toLibraryBacklog(depth(), costs, measures())
}

internal operator fun Workload.plus(other: Workload): Workload = Workload(items + other.items, bytes + other.bytes)

/**
 * Scaricare la replica e rilevare i file nuovi sono due lavori sugli stessi media: i byte si sommano, i media distinti si
 * stimano con il maggiore dei due (lo stimatore, per questa attivita', usa solo i byte).
 */
private fun syncAndDetect(replica: Workload, detection: Workload): Workload =
  Workload(maxOf(replica.items, detection.items), replica.bytes + detection.bytes)
