package dev.pampa.mycloudgallery.core.qr.codec

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.EncodeHintType
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.WriterException
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.encoder.Encoder
import dev.pampa.mycloudgallery.core.qr.QrLimits
import dev.pampa.mycloudgallery.core.qr.contract.QrBits
import dev.pampa.mycloudgallery.core.qr.contract.QrCodec
import java.util.EnumMap

/**
 * L'esito di una lettura: nessun QR, un testo, oppure un QR troppo lungo per essere uno dei nostri.
 *
 * Il testo non compare mai in `toString` (un esito finito per sbaglio in un log o in un messaggio di test non
 * deve portarsi dietro un invito).
 */
internal sealed interface QrRead {

  /** Nessun QR leggibile in questa immagine. */
  data object None : QrRead

  /** Un QR piu' lungo di [QrLimits.MAX_CHARS]: il testo non viene consegnato. */
  data object TooLong : QrRead

  /** Il testo di un QR, entro il tetto. */
  class Found(val text: String) : QrRead {
    override fun toString(): String = "Found(${text.length} caratteri)"
  }
}

/**
 * Codifica e decodifica dei QR con ZXing core (Apache-2.0): il [QrCodec] vero di `:core-qr`.
 *
 * **Codifica.** Correzione d'errore M (15%): il QR si legge da uno schermo, con riflessi e moire, e M e' il
 * compromesso fra robustezza e densita' (con L un testo da 600 caratteri e' piu' rado, ma basta un riflesso).
 * La matrice e' quella dei soli moduli: la zona di rispetto (4 moduli) la aggiunge chi disegna
 * ([dev.pampa.mycloudgallery.core.qr.render.QrGeometry]), cosi' il margine segue la dimensione reale a schermo.
 * Un testo ASCII va senza intestazione di codifica (e' il piu' compatto e lo leggono tutti); uno con accenti
 * o altri caratteri va in UTF-8 con la sua intestazione ECI, che ZXing legge senza indovinare.
 *
 * **Decodifica.** Il QR e' invariante alla rotazione (lo trova dai tre riquadri d'angolo), quindi 90, 180 e
 * 270 gradi non chiedono niente di speciale. Ogni errore di ZXing, e ogni input senza senso (dimensioni
 * nulle o incoerenti, array troppo corto), e' "nessun QR": mai un'eccezione, perche' arriva da una
 * fotocamera o da un file e puo' essere qualunque cosa.
 */
object ZxingQrCodec : QrCodec {

  private val decodeHints: Map<DecodeHintType, Any> = EnumMap<DecodeHintType, Any>(DecodeHintType::class.java).apply {
    put(DecodeHintType.POSSIBLE_FORMATS, listOf(BarcodeFormat.QR_CODE))
    // Piu' lento di qualche millisecondo, ma trova i QR piccoli, storti e a bassa risoluzione: con lo scanner
    // che gira a pochi fotogrammi al secondo il costo non si vede.
    put(DecodeHintType.TRY_HARDER, true)
  }

  /**
   * Il QR di [text]. Lancia [IllegalArgumentException] se il testo e' vuoto o supera [QrLimits.MAX_CHARS]:
   * meglio fermarsi che disegnare un QR che nessuno leggerebbe (o, peggio, uno troncato).
   */
  override fun encode(text: String): QrBits {
    require(text.isNotEmpty()) { "testo vuoto" }
    require(text.length <= QrLimits.MAX_CHARS) { "testo di ${text.length} caratteri: il tetto e' ${QrLimits.MAX_CHARS}" }
    val hints = EnumMap<EncodeHintType, Any>(EncodeHintType::class.java)
    if (text.any { it.code > MAX_ASCII }) hints[EncodeHintType.CHARACTER_SET] = "UTF-8"
    val matrix = try {
      Encoder.encode(text, ErrorCorrectionLevel.M, hints).matrix
    } catch (e: WriterException) {
      // Non dovrebbe succedere entro il tetto (la versione 40-M porta 2331 byte); se succede e' un
      // input che non conosciamo, e il chiamante lo vede come un rifiuto, non come un errore di libreria.
      throw IllegalArgumentException("testo non codificabile", e)
    }
    val size = matrix.width
    val bits = BooleanArray(size * size)
    for (y in 0 until size) for (x in 0 until size) bits[y * size + x] = matrix.get(x, y).toInt() == 1
    return QrBits(size, bits)
  }

  /**
   * Il testo del QR in un'immagine in scala di grigi (un byte per pixel, righe una dopo l'altra), o null:
   * se non c'e' un QR, se e' rovinato oltre la correzione d'errore, o se il testo supera
   * [QrLimits.MAX_CHARS] (un QR cosi' lungo non e' uno dei nostri).
   */
  override fun decode(luma: ByteArray, width: Int, height: Int): String? =
    (read(luma, width, height) as? QrRead.Found)?.text

  /** Come [decode], ma distingue "nessun QR" da "QR troppo lungo": lo scanner mostra un motivo nel secondo caso. */
  internal fun read(luma: ByteArray, width: Int, height: Int): QrRead {
    if (width <= 0 || height <= 0) return QrRead.None
    if (luma.size.toLong() < width.toLong() * height.toLong()) return QrRead.None
    val text = try {
      val source = PlanarYUVLuminanceSource(luma, width, height, 0, 0, width, height, false)
      QRCodeReader().decode(BinaryBitmap(HybridBinarizer(source)), decodeHints).text
    } catch (_: ReaderException) {
      return QrRead.None // NotFound, Format, Checksum: e' il caso normale di quasi ogni fotogramma
    } catch (_: RuntimeException) {
      return QrRead.None // un'immagine ostile non deve fermare lo scanner
    }
    if (text == null) return QrRead.None
    return if (text.length > QrLimits.MAX_CHARS) QrRead.TooLong else QrRead.Found(text)
  }

  private const val MAX_ASCII = 127
}
