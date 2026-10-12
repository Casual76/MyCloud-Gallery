package dev.pampa.mycloudgallery.core.qr

/**
 * I tetti di `:core-qr`, in un posto solo.
 *
 * Un QR serve a portare un invito o una chiave, non un documento: oltre il tetto il testo non e' uno dei
 * nostri, e chi lo ha scritto (o stampato) lo ha fatto con un altro scopo. Il tetto vale in due versi:
 *  - **in codifica**: [ZxingQrCodec][dev.pampa.mycloudgallery.core.qr.codec.ZxingQrCodec] rifiuta un testo piu'
 *    lungo, invece di disegnare un QR cosi' fitto che la fotocamera non lo leggerebbe;
 *  - **in lettura**: un QR piu' lungo non arriva mai a chi usa lo scanner (verdetto `NOT_OURS`), cosi' un
 *    codice ostile non fa lavorare i decodificatori a valle su megabyte di testo.
 */
object QrLimits {

  /** Caratteri massimi di un testo da codificare, o letto da un QR o digitato a mano. */
  const val MAX_CHARS: Int = 600
}
