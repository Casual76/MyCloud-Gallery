package dev.pampa.mycloudgallery.core.ai.compute

import dev.pampa.mycloudgallery.core.index.contract.VideoFrame
import dev.pampa.mycloudgallery.core.trust.TrustLimits
import dev.pampa.mycloudgallery.core.trust.data.FramesBody
import java.nio.ByteBuffer

/**
 * I fotogrammi di un video nel buffer (D10, D11): gli stessi byte del corpo `frames` dell'indice (`FramesBody`: conteggio, poi per
 * ogni fotogramma istante, lunghezza e JPEG), sia che arrivino dallo scaricamento (blob attestato di una testa) sia dalla
 * preparazione di questo dispositivo ([BufferAnalysisSink]).
 *
 * [split] rilegge con i tetti del formato (al piu' 9 fotogrammi, niente lunghezze oltre i byte, niente byte in piu'): un buffer
 * rovinato sul disco da' null, mai un'allocazione da una lunghezza dichiarata. I JPEG non si decodificano qui: li prepara
 * [AiInputs] (D5).
 */
object FramesBlob {
  fun encode(frames: List<VideoFrame>): ByteArray = FramesBody.encode(frames.map { it.atMs to it.jpeg })

  fun split(bytes: ByteArray): List<VideoFrame>? {
    if (bytes.isEmpty() || bytes.size > TrustLimits.MAX_FRAMES_BODY_BYTES) return null
    val count = bytes[0].toInt() and 0xff
    if (count !in 1..TrustLimits.MAX_FRAMES_PER_MEDIA) return null
    val out = ArrayList<VideoFrame>(count)
    var p = 1
    repeat(count) {
      if (p + 8 > bytes.size) return null
      val at = ByteBuffer.wrap(bytes, p, 4).int
      val length = ByteBuffer.wrap(bytes, p + 4, 4).int.toLong() and 0xffffffffL
      if (at < 0 || length <= 0 || length > TrustLimits.MAX_BLOB_BYTES || p + 8 + length > bytes.size) return null
      out += VideoFrame(at, bytes.copyOfRange(p + 8, p + 8 + length.toInt()))
      p += 8 + length.toInt()
    }
    return if (p == bytes.size) out else null
  }

  /**
   * I fotogrammi da leggere con l'OCR (D11): la copertina (il primo) e quello centrale, senza doppioni. [wanted] e' quanti
   * (`AiRecipe.ocrFramesPerVideo`, oggi 2).
   */
  fun forOcr(frames: List<VideoFrame>, wanted: Int): List<VideoFrame> {
    if (frames.isEmpty() || wanted <= 0) return emptyList()
    if (wanted == 1 || frames.size == 1) return listOf(frames.first())
    val picks = LinkedHashSet<Int>()
    picks += 0
    picks += frames.size / 2
    var i = 1
    while (picks.size < minOf(wanted, frames.size)) {
      picks += i
      i++
    }
    return picks.sorted().map { frames[it] }
  }
}
