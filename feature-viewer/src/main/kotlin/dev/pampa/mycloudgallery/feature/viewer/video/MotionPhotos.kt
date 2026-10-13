package dev.pampa.mycloudgallery.feature.viewer.video

import dev.pampa.mycloudgallery.core.metadata.contract.ByteRange
import dev.pampa.mycloudgallery.core.metadata.contract.MotionPhotoLocator
import dev.pampa.mycloudgallery.feature.viewer.details.ViewerBytes
import dev.pampa.mycloudgallery.core.model.MediaRef

/**
 * Dove sta la parte video di una motion photo (E-M5), cercata **quando serve** (alla pressione lunga), non in anticipo:
 * il catalogo sa che una foto e' una motion photo, ma non salva l'intervallo (cambia con il formato e con le correzioni).
 *
 * `Found` porta l'intervallo; `Unrecognized` e' E-M16 (un formato che nessun localizzatore riconosce, per esempio
 * l'HEIC di Samsung): il viewer lascia la foto ferma e lo scrive nelle informazioni, senza errori.
 */
sealed interface MotionVideo {

  data class Found(val range: ByteRange) : MotionVideo

  data object Unrecognized : MotionVideo
}

/** Trova la parte video di una motion photo. Un'interfaccia: nei test restituisce un intervallo scelto dal test. */
fun interface MotionVideoLocator {
  suspend fun locate(ref: MediaRef): MotionVideo
}

/**
 * Il localizzatore vero: legge la testa del file (dove sta l'XMP) e la coda (dove Samsung scrive il suo trailer SEF) e
 * li passa a [MotionPhotoLocator] di core-metadata, che conosce i tre formati (Google v1, MicroVideo, SEF).
 *
 * Legge poche centinaia di KiB, mai il file: un JPEG da 200 MP pesa 60 MB.
 */
class BytesMotionVideoLocator(private val bytes: ViewerBytes) : MotionVideoLocator {

  override suspend fun locate(ref: MediaRef): MotionVideo {
    val file = bytes.open(ref) ?: return MotionVideo.Unrecognized
    val size = file.size
    if (size <= 0L) return MotionVideo.Unrecognized
    val head = file.read(0L until minOf(size, HEAD_BYTES))
    val tail = file.read(maxOf(0L, size - TAIL_BYTES) until size)
    val part = MotionPhotoLocator.locate(XmpPacket.find(head), tail, size) ?: return MotionVideo.Unrecognized
    val range = part.videoRange
    // Un intervallo che esce dal file o e' vuoto non e' un video: meglio la foto ferma che un decoder in errore.
    if (range.isEmpty || range.endExclusive > size) return MotionVideo.Unrecognized
    return MotionVideo.Found(range)
  }

  private companion object {
    const val HEAD_BYTES = 256L * 1024L
    const val TAIL_BYTES = 128L * 1024L
  }
}

/** Il blocco XMP di un file, cercato nei primi byte. Funzione pura, provata con file sintetici. */
object XmpPacket {

  private const val OPEN = "<x:xmpmeta"
  private const val CLOSE = "</x:xmpmeta>"

  /** L'XMP in [head] (UTF-8), o null se non c'e' un pacchetto completo. */
  fun find(head: ByteArray): String? {
    // ISO-8859-1 mappa un byte su un carattere: gli indici del testo sono gli indici dei byte.
    val text = String(head, Charsets.ISO_8859_1)
    val start = text.indexOf(OPEN)
    if (start < 0) return null
    val closeAt = text.indexOf(CLOSE, startIndex = start)
    if (closeAt < 0) return null
    val end = closeAt + CLOSE.length
    return String(head, start, end - start, Charsets.UTF_8)
  }
}
