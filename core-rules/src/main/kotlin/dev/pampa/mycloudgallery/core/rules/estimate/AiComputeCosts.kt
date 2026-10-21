package dev.pampa.mycloudgallery.core.rules.estimate

import kotlin.math.ceil
import kotlin.math.max

// Fase 6 (docs/fase6-progetto.md §2.8, D10-D12, D15, D22; P5): il lavoro del CALCOLO AI per le stime, dal backlog vero. Il calcolo
// (core-ai, P5) conta i media che aspettano per tipo e per classe ([AiWork]); qui diventano un [Workload] di `AI_COMPUTE` per lo
// stimatore: "elementi" = secondi di calcolo x [DeviceMeasures.aiItemsPerSecond] (cosi' lo stimatore, che divide per la stessa
// velocita', ritrova i secondi), byte = le anteprime da scaricare.

/**
 * I media che aspettano il calcolo AI su **questo** dispositivo (solo numeri):
 *
 * @property photos foto (anche screenshot, animate, foto in movimento) senza vettore della ricetta corrente: un vettore ciascuna.
 * @property videos video senza vettore: un vettore per fotogramma ([DeviceMeasures.framesPerVideo], D11).
 * @property textReads media senza testo letto, **solo** se la lettura del testo e' attiva qui (D41); altrimenti 0.
 * @property buffered di quelli, quanti hanno gia' l'anteprima nel buffer o sul telefono (niente da scaricare, E-A6, E-I11).
 */
data class AiWork(
  val photos: Long = 0L,
  val videos: Long = 0L,
  val textReads: Long = 0L,
  val buffered: Long = 0L,
) {
  init {
    require(photos >= 0 && videos >= 0 && textReads >= 0 && buffered >= 0) { "Un conteggio non puo' essere negativo." }
  }

  val items: Long get() = photos + videos

  companion object {
    val NONE = AiWork()
  }
}

/**
 * Il costo del calcolo AI (§2.8):
 * - **tempo**: vettori (foto + video x fotogrammi) / foto al secondo sulla GPU; l'OCR gira sulla CPU in parallelo ai vettori, quindi
 *   il tempo e' il **maggiore** dei due (testi x secondi a immagine, piu' la seconda passata sull'HQ per la quota fitta);
 * - **traffico**: le anteprime che non sono gia' nel buffer (foto: `analysis`; video: `frames`), piu' l'HQ per la quota fitta del
 *   testo;
 * - **batteria**: lo stimatore conta gli "elementi" con `aiBatteryPercentPerThousandItems` (i vettori sono il grosso del consumo).
 */
object AiComputeCosts {

  /** I secondi di calcolo di [work] con le misure [measures]. */
  fun seconds(work: AiWork, measures: DeviceMeasures): Double {
    val vectors = work.photos + work.videos * measures.framesPerVideo
    val embed = vectors / measures.aiItemsPerSecond
    val ocr = work.textReads * measures.ocrSecondsPerItem * (1.0 + measures.ocrDenseShare)
    return max(embed, ocr)
  }

  /** Il traffico delle anteprime da scaricare, in byte. */
  fun bytes(work: AiWork, costs: NasCosts, denseShare: Double = DeviceMeasures.PHASE_0.ocrDenseShare): Long {
    val toFetch = (work.items - work.buffered).coerceAtLeast(0L)
    val share = if (work.items == 0L) 0.0 else toFetch.toDouble() / work.items
    val previews = (work.photos * costs.analysisBytes + work.videos * costs.videoFramesBytes) * share
    val hq = work.textReads * denseShare * costs.hqBytes * share
    return (previews + hq).toLong()
  }

  /** [work] come [Workload] di `AI_COMPUTE`: elementi equivalenti (vedi sopra) e byte. */
  fun workload(work: AiWork, measures: DeviceMeasures, costs: NasCosts): Workload {
    if (work.items == 0L && work.textReads == 0L) return Workload.NONE
    val items = ceil(seconds(work, measures) * measures.aiItemsPerSecond).toLong()
    return Workload(items, bytes(work, costs, measures.ocrDenseShare))
  }

  /** Le notti che servono (una notte utile = [hoursPerNight] ore in carica, §2.8: 23–07 sono ~6,4 ore utili). Almeno 1 se c'e' lavoro. */
  fun nights(work: AiWork, measures: DeviceMeasures, hoursPerNight: Double = 6.4): Int {
    val hours = seconds(work, measures) / 3600.0
    if (hours <= 0.0) return 0
    return ceil(hours / hoursPerNight).toInt().coerceAtLeast(1)
  }

  /** La batteria del calcolo di [work], in % (dalla presa con il preset Notte). */
  fun batteryPercent(work: AiWork, measures: DeviceMeasures): Double =
    (work.photos + work.videos * measures.framesPerVideo) / 1000.0 * measures.aiBatteryPercentPerThousandItems
}
