package dev.pampa.mycloudgallery.faces

import dev.pampa.mycloudgallery.core.ai.compute.AiBlob
import dev.pampa.mycloudgallery.core.ai.contract.AiBuffer
import dev.pampa.mycloudgallery.core.ai.compute.AiInputOrigin
import dev.pampa.mycloudgallery.core.ai.compute.AiOriginBuffer
import dev.pampa.mycloudgallery.core.ai.compute.AiPhoneMedia
import dev.pampa.mycloudgallery.core.ai.compute.FramesBlob
import dev.pampa.mycloudgallery.core.ai.compute.asFetchSink
import dev.pampa.mycloudgallery.core.ai.contract.FaceEngineLimits
import dev.pampa.mycloudgallery.core.ai.runtime.ModelInputs
import dev.pampa.mycloudgallery.core.faces.compute.FacesPrefetching
import dev.pampa.mycloudgallery.core.faces.contract.FaceInput
import dev.pampa.mycloudgallery.core.faces.contract.FaceInputs
import dev.pampa.mycloudgallery.core.faces.contract.FacesArea
import dev.pampa.mycloudgallery.core.faces.contract.FacesItem
import dev.pampa.mycloudgallery.core.faces.contract.InputWait
import dev.pampa.mycloudgallery.core.index.ai.AnalysisFetcher
import dev.pampa.mycloudgallery.core.model.ContentKey
import dev.pampa.mycloudgallery.core.model.FaceSource
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.prepare.contract.PrepFailCode
import dev.pampa.mycloudgallery.core.prepare.contract.PrepOpen
import dev.pampa.mycloudgallery.core.prepare.contract.PrepOutput
import dev.pampa.mycloudgallery.core.prepare.contract.PrepResult
import dev.pampa.mycloudgallery.core.prepare.contract.PrepSpec
import dev.pampa.mycloudgallery.core.prepare.contract.Preparer
import dev.pampa.mycloudgallery.core.trust.data.SegmentClass
import java.io.IOException
import kotlin.time.Duration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// GLI INGRESSI DEI VOLTI (Fase 8, P13; docs/fase8-progetto.md §1.4, §2.3, D17): l'anteprima di analisi (1024 px) dal buffer degli indicizzatori, poi
// dai byte del telefono (preparati ora), poi da un blob attestato dell'indice della share (scaricato nel buffer); la seconda passata sull'HQ (2048 px),
// scaricato nel buffer al primo bisogno; copertina e fotogrammi di un video dal blob `frames`. **Mai byte remoti al decodificatore**: ogni immagine
// passa da `ModelInputs.forFaces` (intestazione letta prima di decodificare). Senza anteprima da nessuna parte il media aspetta (E-F18): la
// ri-preparazione dall'originale del NAS resta alla preparazione normale dei Preparatori, che riempie il buffer (punto aperto di P13).
// Nessun log: solo i codici di attesa che il contratto prevede (D37).

/**
 * [FaceInputs] sopra il buffer di analisi (`DiskAiBuffer`), l'indice della share ([fetcher], blob **attestati**) e i byte del telefono ([phone] e
 * [preparer], con il controllo dell'impronta rapida di `CheckedPreparer`). Le share dell'area privata e comune le dice [shares]; per i media del
 * solo telefono nessuna share (risultati solo locali).
 */
class AppFaceInputs(
  private val buffer: AiBuffer,
  private val fetcher: () -> AnalysisFetcher?,
  private val phone: AiPhoneMedia?,
  private val preparer: () -> Preparer?,
  private val shares: FacesShares,
  /** Il NAS si puo' leggere adesso (il cancello di rete e le regole le giudica il lavoro): se no, niente scaricamento. */
  private val mayFetch: () -> Boolean = { true },
) : FaceInputs, FacesPrefetching {

  private val origin: AiOriginBuffer? get() = buffer as? AiOriginBuffer

  override suspend fun preview(item: FacesItem): FaceInput {
    val share = shares.of(item.area)
    val bytes = primary(item, share, SegmentClass.ANALYSIS) ?: return waiting(item)
    return image(bytes, FaceEngineLimits.DETECT_SIDE, frame = 0, FaceSource.ANALYSIS)
  }

  override suspend fun hq(item: FacesItem): FaceInput {
    val share = shares.of(item.area)
    var blob = blobOf(item.contentKey, SegmentClass.HQ, share)
    if (blob == null && share != null && mayFetch()) {
      fetch(share, listOf(item.contentKey), SegmentClass.HQ)
      blob = blobOf(item.contentKey, SegmentClass.HQ, share)
    }
    val bytes = blob?.bytes ?: return FaceInput.Waiting(InputWait.NO_PREVIEW)
    return image(bytes, FaceEngineLimits.HQ_SIDE, frame = 0, FaceSource.HQ_TILES)
  }

  override suspend fun frames(item: FacesItem): List<FaceInput> {
    val share = shares.of(item.area)
    val bytes = primary(item, share, SegmentClass.FRAMES) ?: return listOf(waiting(item))
    val frames = FramesBlob.split(bytes) ?: return listOf(FaceInput.Unsupported)
    // La copertina e fino a FRAMES_PER_VIDEO fotogrammi dopo di lei (D18): i volti dei video sono al piu' WEAK.
    return frames.take(MAX_FRAMES).mapIndexed { index, frame -> image(frame.jpeg, FaceEngineLimits.DETECT_SIDE, index, FaceSource.FRAMES) }
  }

  /** La sorgente piu' nitida gia' in mano, senza scaricare niente: l'HQ del buffer, altrimenti l'anteprima del buffer. */
  override suspend fun sharpest(item: FacesItem): FaceInput {
    val share = shares.of(item.area)
    blobOf(item.contentKey, SegmentClass.HQ, share)?.let { return image(it.bytes, FaceEngineLimits.HQ_SIDE, 0, FaceSource.HQ_TILES) }
    val cls = if (item.kind == MediaKind.VIDEO) return FaceInput.Waiting(InputWait.NO_PREVIEW) else SegmentClass.ANALYSIS
    val blob = blobOf(item.contentKey, cls, share) ?: return FaceInput.Waiting(InputWait.NO_PREVIEW)
    return image(blob.bytes, FaceEngineLimits.DETECT_SIDE, 0, FaceSource.ANALYSIS)
  }

  override suspend fun prefetch(items: List<FacesItem>) {
    if (!mayFetch() || items.isEmpty()) return
    for (area in listOf(FacesArea.PRIVATE, FacesArea.COMMON)) {
      val share = shares.of(area) ?: continue
      val ofArea = items.filter { it.area == area && !it.excluded }
      for (cls in listOf(SegmentClass.ANALYSIS, SegmentClass.FRAMES)) {
        val wanted = ofArea.filter { (it.kind == MediaKind.VIDEO) == (cls == SegmentClass.FRAMES) }.map { it.contentKey }
          .filter { blobOf(it, cls, share) == null }
        for (chunk in wanted.chunked(FETCH_BATCH)) if (!fetch(share, chunk, cls)) return
      }
    }
  }

  // --- Dentro -------------------------------------------------------------------------------------------------------

  /** Un blob proprio del buffer, poi i byte del telefono preparati ora (e messi nel buffer), poi uno attestato nella share, poi scaricato. */
  private suspend fun primary(item: FacesItem, share: String?, cls: SegmentClass): ByteArray? {
    blobOf(item.contentKey, cls, null)?.let { return it.bytes }
    preparePhone(item, cls)?.let { return it }
    if (share == null) return null
    blobOf(item.contentKey, cls, share)?.let { return it.bytes }
    if (!mayFetch()) return null
    fetch(share, listOf(item.contentKey), cls)
    return blobOf(item.contentKey, cls, share)?.bytes
  }

  private suspend fun blobOf(key: ContentKey, cls: SegmentClass, share: String?): AiBlob? {
    origin?.let { return it.getFor(key, cls, share) }
    // Un buffer che non ricorda la provenienza vale "tutto proprio": per una share non si distingue, ma niente e' attestato in piu'.
    return buffer.get(key, cls)?.let { AiBlob(it, AiInputOrigin.Own) }
  }

  /** Vero se si puo' continuare (nessun arresto anticipato: spazio finito, NAS spento). */
  private suspend fun fetch(share: String, keys: List<ContentKey>, cls: SegmentClass): Boolean {
    val source = fetcher() ?: return false
    return try {
      !source.fetch(share, keys, setOf(cls), buffer.asFetchSink(), Duration.INFINITE).stoppedEarly
    } catch (e: IOException) {
      false
    }
  }

  private suspend fun preparePhone(item: FacesItem, cls: SegmentClass): ByteArray? {
    val media = phone ?: return null
    val prepare = preparer() ?: return null
    val open = try {
      media.open(item.contentKey, item.kind)
    } catch (e: IOException) {
      return null
    }
    val ready = open as? PrepOpen.Ready ?: return null
    val result = try {
      prepare.prepare(ready.source, PrepSpec(classes = setOf(cls)))
    } catch (e: CancellationException) {
      throw e
    } catch (e: IOException) {
      null
    } finally {
      runCatching { ready.close() }
    }
    val done = result as? PrepResult.Done ?: return null
    val bytes = done.outputs.firstNotNullOfOrNull { output ->
      when {
        output is PrepOutput.Image && output.cls == SegmentClass.ANALYSIS && cls == SegmentClass.ANALYSIS -> output.bytes
        output is PrepOutput.Frames && cls == SegmentClass.FRAMES -> runCatching { FramesBlob.encode(output.frames) }.getOrNull()
        else -> null
      }
    } ?: return null
    // La preparazione e' dei nostri byte: il buffer la tiene (propria), cosi' una ripresa non la rifa'.
    buffer.put(item.contentKey, cls, bytes)
    return bytes
  }

  private suspend fun image(bytes: ByteArray, maxSide: Int, frame: Int, source: FaceSource): FaceInput {
    val decoded = withContext(Dispatchers.Default) { ModelInputs.forFaces(bytes, maxSide, frame) }
    return if (decoded == null) FaceInput.Unsupported else FaceInput.Ready(decoded, source)
  }

  /** Il media del solo telefono che non si apre piu' come nel catalogo e' cambiato; per un media del NAS e' solo "nessuna anteprima" (E-F18). */
  private suspend fun waiting(item: FacesItem): FaceInput {
    if (item.area == FacesArea.PHONE_ONLY) {
      val media = phone
      val open = media?.let { runCatching { it.open(item.contentKey, item.kind) }.getOrNull() }
      if (open is PrepOpen.Unavailable && open.code == PrepFailCode.SOURCE_CHANGED) return FaceInput.Changed
      (open as? PrepOpen.Ready)?.let { runCatching { it.close() } }
    }
    return FaceInput.Waiting(if (mayFetch()) InputWait.NO_PREVIEW else InputWait.NAS)
  }

  override fun toString(): String = "AppFaceInputs"

  private companion object {
    /** La copertina e 3 fotogrammi (D18). */
    const val MAX_FRAMES = 4

    /** Anteprime scaricate insieme (D17: lotti di 50). */
    const val FETCH_BATCH = 50
  }
}
