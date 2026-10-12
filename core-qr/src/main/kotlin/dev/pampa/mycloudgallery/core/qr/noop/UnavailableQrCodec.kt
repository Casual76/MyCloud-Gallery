package dev.pampa.mycloudgallery.core.qr.noop

import dev.pampa.mycloudgallery.core.qr.contract.QrBits
import dev.pampa.mycloudgallery.core.qr.contract.QrCodec

/**
 * Il null-object di [QrCodec] (release-safe, fuori da `testing`): non legge nessun QR e non ne genera.
 * [encode] fallisce rumorosamente invece di disegnare un QR finto, che qualcuno potrebbe scansionare.
 */
object UnavailableQrCodec : QrCodec {
  override fun encode(text: String): QrBits = throw UnsupportedOperationException("codifica QR non ancora disponibile (P5)")

  override fun decode(luma: ByteArray, width: Int, height: Int): String? = null
}
