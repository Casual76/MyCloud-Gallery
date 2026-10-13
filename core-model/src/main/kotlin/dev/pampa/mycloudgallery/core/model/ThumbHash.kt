package dev.pampa.mycloudgallery.core.model

import java.util.Base64

/**
 * Il segnaposto sfocato di un'immagine nell'algoritmo pubblico ThumbHash (D5 di
 * `docs/fase3-progetto.md`): circa 25 byte che bastano a disegnare una versione 32x32 sfocata mentre
 * la miniatura vera arriva. Sta nell'indice della timeline, quindi il primo fotogramma della Libreria
 * non e' mai vuoto.
 *
 * Il valore e' il Base64 **standard e canonico** dei byte (con il riempimento `=`): una forma sola per
 * gli stessi byte, cosi' due valori uguali sono lo stesso segnaposto. Al massimo [MAX_LENGTH]
 * caratteri (36 byte): un ThumbHash vero non ci arriva, un valore piu' lungo e' un errore di chi lo ha
 * prodotto. Nel database si salvano i byte ([bytes]), non il testo.
 */
@JvmInline
value class ThumbHash(val base64: String) {

  init {
    require(base64.length in 4..MAX_LENGTH && base64.length % 4 == 0 && BASE64.matches(base64)) {
      "Un ThumbHash e' Base64 standard di al massimo $MAX_LENGTH caratteri."
    }
    require(Base64.getEncoder().encodeToString(Base64.getDecoder().decode(base64)) == base64) {
      "Un ThumbHash si scrive in Base64 canonico."
    }
  }

  /** I byte del segnaposto (cio' che decodifica `ThumbHashCodec` e che si salva nel database). */
  fun bytes(): ByteArray = Base64.getDecoder().decode(base64)

  /** Solo la lunghezza: il segnaposto e' un'anteprima sfocata della foto e non finisce nei log. */
  override fun toString(): String = "ThumbHash(${base64.length})"

  companion object {
    const val MAX_LENGTH: Int = 48

    private val BASE64 = Regex("^[A-Za-z0-9+/]+={0,2}$")

    fun of(bytes: ByteArray): ThumbHash = ThumbHash(Base64.getEncoder().encodeToString(bytes))
  }
}
