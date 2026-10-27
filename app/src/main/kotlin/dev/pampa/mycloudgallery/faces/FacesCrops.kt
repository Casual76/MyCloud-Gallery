package dev.pampa.mycloudgallery.faces

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.Rect
import dev.pampa.mycloudgallery.ai.CutoutSources
import dev.pampa.mycloudgallery.core.ai.contract.AiBuffer
import dev.pampa.mycloudgallery.core.ai.compute.AiOriginBuffer
import dev.pampa.mycloudgallery.core.ai.compute.FramesBlob
import dev.pampa.mycloudgallery.core.ai.cutout.CutoutSource
import dev.pampa.mycloudgallery.core.database.MediaCatalog
import dev.pampa.mycloudgallery.core.model.FaceBox
import dev.pampa.mycloudgallery.core.model.FaceLocator
import dev.pampa.mycloudgallery.core.trust.data.SegmentClass
import dev.pampa.mycloudgallery.feature.people.contract.PeopleCrops
import java.nio.ByteBuffer
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// LE COPERTINE E I RITAGLI DEI VOLTI (Fase 8, P13; docs/fase8-progetto.md D25, D28, §2.6 "Copertine e ritagli"): si ritagliano **al momento** dalla migliore
// immagine che il dispositivo ha gia' -- l'anteprima o l'HQ nel buffer, altrimenti l'originale del telefono, il file portato dal NAS o l'HQ della replica --
// e **non si salvano** (la cache e' solo in memoria, in `GuardedPeopleCrops`). Mai uno scaricamento solo per un ritaglio. Il cancello (media mostrabile) lo
// applica chi avvolge questa porta. Nessun log, nessun file, nessun `cacheDir` (D37).

/** La sorgente dei ritagli: nessun ritaglio fuori dalla memoria. */
class AppPeopleCropSource(
  private val cells: FacesCellIndex,
  private val buffer: AiBuffer,
  private val catalog: suspend () -> MediaCatalog?,
  private val sources: CutoutSources,
) : PeopleCrops {

  override suspend fun crop(face: FaceLocator, sizePx: Int): Bitmap? = withContext(Dispatchers.Default) {
    try {
      if (sizePx <= 0) return@withContext null
      fromBuffer(face, sizePx) ?: fromBestLocal(face, sizePx)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      null
    } catch (e: OutOfMemoryError) {
      null
    }
  }

  /** I byte gia' nel buffer di analisi: l'HQ, poi l'anteprima; per un fotogramma di un video il blob dei fotogrammi. */
  private suspend fun fromBuffer(face: FaceLocator, sizePx: Int): Bitmap? {
    val key = face.contentKey
    val origin = buffer as? AiOriginBuffer
    suspend fun bytesOf(cls: SegmentClass): ByteArray? = origin?.getFor(key, cls, null)?.bytes ?: buffer.get(key, cls)
    if (face.frame > 0) {
      val frames = bytesOf(SegmentClass.FRAMES)?.let { FramesBlob.split(it) } ?: return null
      return frames.getOrNull(face.frame)?.let { decodeAndCrop(ImageDecoder.createSource(ByteBuffer.wrap(it.jpeg)), face.box, sizePx) }
    }
    for (cls in listOf(SegmentClass.HQ, SegmentClass.ANALYSIS)) {
      bytesOf(cls)?.let { bytes -> decodeAndCrop(ImageDecoder.createSource(ByteBuffer.wrap(bytes)), face.box, sizePx)?.let { return it } }
    }
    val frames = bytesOf(SegmentClass.FRAMES)?.let { FramesBlob.split(it) }
    return frames?.firstOrNull()?.let { decodeAndCrop(ImageDecoder.createSource(ByteBuffer.wrap(it.jpeg)), face.box, sizePx) }
  }

  /** La migliore immagine locale del media (originale del telefono, file portato dal NAS, HQ della replica): mai una rete. */
  private suspend fun fromBestLocal(face: FaceLocator, sizePx: Int): Bitmap? {
    if (face.frame > 0) return null
    val cell = cells.cellOf(face.contentKey) ?: return null
    val current = catalog() ?: return null
    val ref = current.phoneRefs(listOf(cell))[cell] ?: current.nasRefs(listOf(cell))[cell] ?: return null
    val source = when (val picked = sources.of(ref)) {
      null -> return null
      is CutoutSource.OfBytes -> ImageDecoder.createSource(ByteBuffer.wrap(picked.bytes))
      is CutoutSource.OfFile -> ImageDecoder.createSource(picked.file)
      is CutoutSource.OfUri -> ImageDecoder.createSource(picked.resolver, picked.uri)
    }
    return decodeAndCrop(source, face.box, sizePx)
  }

  /** Decodifica a una taglia ragionevole (orientamento EXIF compreso), ritaglia un quadrato attorno al volto con un po' di margine e lo porta a [sizePx]. */
  private fun decodeAndCrop(source: ImageDecoder.Source, box: FaceBox, sizePx: Int): Bitmap? {
    val whole = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
      decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
      val side = max(info.size.width, info.size.height)
      if (side > MAX_SIDE) decoder.setTargetSampleSize(ceil(side.toDouble() / MAX_SIDE).toInt())
    }
    return try {
      squareAround(whole, box, sizePx)
    } finally {
      whole.recycle()
    }
  }

  private fun squareAround(image: Bitmap, box: FaceBox, sizePx: Int): Bitmap? {
    val w = image.width
    val h = image.height
    if (w <= 0 || h <= 0) return null
    val full = FaceBox.MAX.toFloat()
    val cx = (box.x0 + box.x1) / 2f / full * w
    val cy = (box.y0 + box.y1) / 2f / full * h
    val faceSide = max((box.x1 - box.x0) / full * w, (box.y1 - box.y0) / full * h)
    val side = min(max(faceSide * MARGIN, MIN_SIDE.toFloat()), min(w, h).toFloat())
    val rect = Rect(
      (cx - side / 2).toInt().coerceIn(0, w - side.toInt()),
      (cy - side / 2).toInt().coerceIn(0, h - side.toInt()),
      0,
      0,
    )
    rect.right = rect.left + side.toInt()
    rect.bottom = rect.top + side.toInt()
    val cropped = Bitmap.createBitmap(image, rect.left, rect.top, rect.width(), rect.height())
    if (cropped.width == sizePx) return cropped
    val scaled = Bitmap.createScaledBitmap(cropped, sizePx, sizePx, true)
    if (scaled !== cropped) cropped.recycle()
    return scaled
  }

  override fun toString(): String = "AppPeopleCropSource"

  private companion object {
    /** Lato lungo massimo dell'immagine decodificata per un ritaglio (un'avatar non chiede di piu'). */
    const val MAX_SIDE = 2048

    /** Il quadrato del ritaglio e' il volto con questo margine attorno (fronte e mento compresi). */
    const val MARGIN = 1.5f

    /** Mai un ritaglio piu' piccolo di cosi' (pixel): un volto minuscolo non diventa un ingrandimento sfocato. */
    const val MIN_SIDE = 24
  }
}
