package dev.pampa.mycloudgallery.core.ai.runtime

import dev.pampa.mycloudgallery.core.trust.nas.ImageBounds

// LE INTESTAZIONI DELLE IMMAGINI che arrivano ai motori (docs/fase6-progetto.md D5, §4.3 "Ingressi difesi"; "Fatto quando" di P4:
// `ModelInputs` rifiuta JPEG e WebP ostili PRIMA di decodificare). Codice puro (nessuna classe di Android): legge solo le poche
// decine di byte che dicono formato e dimensioni, con ogni lunghezza controllata contro i byte che ci sono davvero. La decodifica vera
// la fa `ModelInputs` con `ImageDecoder` e solo dopo che qui il file ha passato tutto.
//
// Cosa si rifiuta (null, mai un'eccezione):
//  - un formato che non e' JPEG (FF D8 FF) o WebP (RIFF....WEBP);
//  - un JPEG senza marcatore SOF prima di SOS/EOI, con un SOF doppio, con una lunghezza di segmento che esce dai byte, con codifica
//    aritmetica / senza perdita / differenziale (Skia non le decodifica), con precisione diversa da 8 bit, con componenti diverse da 1
//    o 3, con campionamenti fuori da 1..4, con altezza zero (definita da un DNL che non seguiamo);
//  - un WebP con RIFF piu' lungo dei byte, senza chunk VP8 / VP8L / VP8X per primo, con il VP8 senza fotogramma chiave o senza il
//    codice di avvio, con il VP8L senza firma 0x2F o con versione diversa da 0, animato (VP8X con il bit di animazione, o con chunk
//    ANIM / ANMF), con la tela del VP8X diversa dalle dimensioni del chunk dell'immagine;
//  - dimensioni fuori da `ImageBounds` (16 mila pixel per lato, 16,7 milioni di pixel).

/** I due formati che gli ingressi dei motori ammettono (le anteprime sono JPEG, l'HQ e' WebP). */
enum class InputFormat { JPEG, WEBP }

/** Formato e dimensioni **dichiarate** nell'intestazione di un file che ha passato i controlli di [ImageHeaders]. */
class ImageHeader(val format: InputFormat, val width: Int, val height: Int) {
  val longSide: Int get() = maxOf(width, height)

  override fun toString(): String = "ImageHeader($format, ${width}x$height)"
}

object ImageHeaders {

  /** Quanti marcatori di un JPEG si scorrono al massimo prima di arrendersi (un file onesto ne ha una dozzina). */
  private const val MAX_JPEG_MARKERS = 512

  /** Quanti chunk di un WebP si scorrono al massimo. */
  private const val MAX_WEBP_CHUNKS = 64

  /** L'intestazione di [bytes], o null se non e' un JPEG o un WebP ammessi (vedi sopra). Nessuna allocazione da lunghezze dichiarate. */
  fun read(bytes: ByteArray): ImageHeader? {
    val header = when {
      isJpeg(bytes) -> readJpeg(bytes)
      isWebp(bytes) -> readWebp(bytes)
      else -> null
    } ?: return null
    return header.takeIf { ImageBounds.isDecodable(it.width, it.height) }
  }

  fun isJpeg(bytes: ByteArray): Boolean =
    bytes.size >= 4 && u8(bytes, 0) == 0xFF && u8(bytes, 1) == 0xD8 && u8(bytes, 2) == 0xFF

  fun isWebp(bytes: ByteArray): Boolean =
    bytes.size >= 16 && fourCc(bytes, 0) == "RIFF" && fourCc(bytes, 8) == "WEBP"

  // --- JPEG -----------------------------------------------------------------------------------------------------------------

  private fun readJpeg(b: ByteArray): ImageHeader? {
    var i = 2
    var found: ImageHeader? = null
    var markers = 0
    while (i < b.size && markers++ < MAX_JPEG_MARKERS) {
      // Un marcatore e' FF seguito da un codice non zero e non FF; gli FF in piu' sono riempimento.
      if (u8(b, i) != 0xFF) return null
      while (i < b.size && u8(b, i) == 0xFF) i++
      if (i >= b.size) return null
      val marker = u8(b, i++)
      when (marker) {
        0x00 -> return null // FF 00 fuori dai dati compressi: non e' un marcatore
        0xD8 -> return null // un secondo SOI
        0xD9 -> return found // EOI: se prima non c'e' stato un SOF, null
        0x01, in 0xD0..0xD7 -> continue // marcatori senza lunghezza
      }
      if (i + 2 > b.size) return null
      val length = (u8(b, i) shl 8) or u8(b, i + 1)
      if (length < 2 || i + length > b.size) return null
      when (marker) {
        0xC0, 0xC1, 0xC2 -> { // SOF baseline, esteso, progressivo: i soli che Skia decodifica con Huffman a 8 bit
          if (found != null) return null // due SOF: ambiguo
          if (length < 8) return null
          val precision = u8(b, i + 2)
          val height = (u8(b, i + 3) shl 8) or u8(b, i + 4)
          val width = (u8(b, i + 5) shl 8) or u8(b, i + 6)
          val components = u8(b, i + 7)
          if (precision != 8 || width < 1 || height < 1) return null
          if (components != 1 && components != 3) return null
          if (length != 8 + 3 * components) return null
          for (c in 0 until components) {
            val at = i + 8 + 3 * c
            val sampling = u8(b, at + 1)
            val h = sampling shr 4
            val v = sampling and 0x0F
            if (h !in 1..4 || v !in 1..4 || u8(b, at + 2) > 3) return null
          }
          found = ImageHeader(InputFormat.JPEG, width, height)
        }
        0xC3, 0xC5, 0xC6, 0xC7, 0xC9, 0xCA, 0xCB, 0xCD, 0xCE, 0xCF -> return null // senza perdita, differenziale, aritmetica
        0xDC -> return null // DNL: l'altezza arriva dopo i dati, non la seguiamo
        0xDA -> return found // SOS: l'intestazione e' finita; senza un SOF prima, null
      }
      i += length
    }
    return null // troppi marcatori o file finito senza EOI/SOS
  }

  // --- WebP -----------------------------------------------------------------------------------------------------------------

  private fun readWebp(b: ByteArray): ImageHeader? {
    val declared = u32le(b, 4)
    // Il RIFF dichiara quanti byte seguono: se sono piu' di quelli che ci sono il file e' troncato; se sono di meno, il resto non conta.
    if (declared < 4 + 8 || declared + 8L > b.size) return null
    val end = (declared + 8L).toInt()
    var i = 12
    var canvas: Pair<Int, Int>? = null
    var image: Pair<Int, Int>? = null
    var seen = 0
    while (i + 8 <= end && seen++ < MAX_WEBP_CHUNKS) {
      val type = fourCc(b, i)
      val size = u32le(b, i + 4)
      val payload = i + 8
      if (size > end - payload) return null
      val padded = size + (size and 1L)
      if (seen == 1 && type != "VP8 " && type != "VP8L" && type != "VP8X") return null
      when (type) {
        "VP8X" -> {
          if (seen != 1 || size < 10) return null
          if ((u8(b, payload) and 0x02) != 0) return null // animazione
          canvas = (u24le(b, payload + 4) + 1) to (u24le(b, payload + 7) + 1)
        }
        "ANIM", "ANMF" -> return null
        "VP8 " -> {
          if (image != null || size < 10) return null
          val tag = u8(b, payload) or (u8(b, payload + 1) shl 8) or (u8(b, payload + 2) shl 16)
          if ((tag and 1) != 0) return null // non e' un fotogramma chiave
          if (u8(b, payload + 3) != 0x9D || u8(b, payload + 4) != 0x01 || u8(b, payload + 5) != 0x2A) return null
          val w = (u8(b, payload + 6) or (u8(b, payload + 7) shl 8)) and 0x3FFF
          val h = (u8(b, payload + 8) or (u8(b, payload + 9) shl 8)) and 0x3FFF
          if (w < 1 || h < 1) return null
          image = w to h
        }
        "VP8L" -> {
          if (image != null || size < 5) return null
          if (u8(b, payload) != 0x2F) return null
          val bits = u32le(b, payload + 1)
          val w = (bits and 0x3FFF).toInt() + 1
          val h = ((bits shr 14) and 0x3FFF).toInt() + 1
          val version = ((bits shr 29) and 0x7).toInt()
          if (version != 0) return null
          image = w to h
        }
      }
      if (padded > (end - payload).toLong()) {
        // L'ultimo chunk puo' mancare del riempimento, ma non di byte veri (gia' controllato sopra).
        i = end
      } else {
        i = payload + padded.toInt()
      }
    }
    val dims = image ?: return null
    if (canvas != null && canvas != dims) return null
    return ImageHeader(InputFormat.WEBP, dims.first, dims.second)
  }

  // --- Byte ---------------------------------------------------------------------------------------------------------------

  private fun u8(b: ByteArray, at: Int): Int = b[at].toInt() and 0xFF

  private fun u24le(b: ByteArray, at: Int): Int = u8(b, at) or (u8(b, at + 1) shl 8) or (u8(b, at + 2) shl 16)

  private fun u32le(b: ByteArray, at: Int): Long =
    (u8(b, at).toLong()) or (u8(b, at + 1).toLong() shl 8) or (u8(b, at + 2).toLong() shl 16) or (u8(b, at + 3).toLong() shl 24)

  private fun fourCc(b: ByteArray, at: Int): String =
    String(charArrayOf((b[at].toInt() and 0xFF).toChar(), (b[at + 1].toInt() and 0xFF).toChar(), (b[at + 2].toInt() and 0xFF).toChar(), (b[at + 3].toInt() and 0xFF).toChar()))
}
