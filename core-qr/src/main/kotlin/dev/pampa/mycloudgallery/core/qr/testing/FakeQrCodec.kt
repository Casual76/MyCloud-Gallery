package dev.pampa.mycloudgallery.core.qr.testing

import dev.pampa.mycloudgallery.core.qr.contract.QrBits
import dev.pampa.mycloudgallery.core.qr.contract.QrCodec

/**
 * Codec finto (D6): [encode] produce una matrice deterministica (non un QR vero) e ricorda il testo;
 * [decode] restituisce [nextDecoded], qualunque immagine riceva. Per i test delle schermate, non della
 * decodifica (quella la prova P5 con ZXing vero).
 */
class FakeQrCodec(var nextDecoded: String? = null) : QrCodec {

  val encoded: MutableList<String> = mutableListOf()

  override fun encode(text: String): QrBits {
    encoded += text
    val size = SIZE
    val seed = text.hashCode()
    return QrBits(size, BooleanArray(size * size) { i -> ((i * PRIME) xor seed) and 1 == 1 })
  }

  override fun decode(luma: ByteArray, width: Int, height: Int): String? = nextDecoded

  private companion object {
    const val SIZE = 21
    const val PRIME = 31
  }
}
