package dev.pampa.mycloudgallery.core.faces.store

import dev.pampa.mycloudgallery.core.faces.contract.FaceEntry
import dev.pampa.mycloudgallery.core.model.FaceRef
import dev.pampa.mycloudgallery.core.model.FaceSpaceCode
import java.nio.ByteBuffer
import java.nio.ByteOrder

// LA PARTE SEGRETA DI UN CORPO `faces` (docs/fase8-progetto.md D14, C39): cio' che nel database dei volti non sta mai in chiaro. Per ogni
// corpo un solo blocco sigillato con la chiave dati della generazione (`face_body.secret`): i landmark (cinque punti, geometria del volto)
// e i vettori di **tutti** i suoi volti. Un'operazione AES per contenuto, non per volto. Il resto di un volto (riquadro, livello, punteggio)
// sta in chiaro nella tabella `face`, perche' le query lo chiedono senza decifrare (D14 cifra vettori, centroidi e nomi; i landmark li
// aggiunge P6 per minimizzazione, C40).
//
// Formato del blocco in chiaro (big endian, **prima** di sigillarlo):
//
//     0       1   versione (1)
//     1       1   N volti (0..64)
//     2..     N x [ 10 x 2 byte landmark | 1 byte "ha un vettore" (0/1) | se 1: D x 2 byte float16 (D = dimensione dello spazio) ]
//
// Il formato e' autodescrittivo (non dipende dalle righe `face`): chi lo legge confronta il risultato con le righe e scarta il corpo se non
// tornano. I `aad` legano ogni blocco alla sua riga: un blocco spostato in un'altra riga, o una colonna di metadati alterata (ricetta,
// impronta, numero di volti), non si apre piu'. Mai valori nei log (D37).

internal object FaceSecrets {

  private const val FORMAT: Int = 1
  private const val LANDMARK_SHORTS = 10

  /** Landmark e vettore (float16) di un volto, come stanno nel blocco. */
  class Secret(val landmarks: ShortArray, val vector: ShortArray?) {
    override fun toString(): String = "Secret"
  }

  private val dimension: Int get() = FaceSpaceCode.CURRENT.dimension

  fun encode(faces: List<FaceEntry>): ByteArray {
    require(faces.size <= FaceRef.MAX_FACES) { "Troppi volti." }
    val size = 2 + faces.sumOf { 2 * LANDMARK_SHORTS + 1 + (if (it.tier.hasVector) 2 * dimension else 0) }
    val buffer = ByteBuffer.allocate(size).order(ByteOrder.BIG_ENDIAN)
    buffer.put(FORMAT.toByte())
    buffer.put(faces.size.toByte())
    for (face in faces) {
      val landmarks = face.landmarks
      for (value in landmarks) buffer.putShort(value)
      val vector = face.vector
      if (vector == null) {
        buffer.put(0)
      } else {
        buffer.put(1)
        for (value in vector) buffer.putShort(value)
      }
    }
    return buffer.array()
  }

  /** I segreti di [count] volti, o null se il blocco non ha la forma attesa (versione, lunghezza, numero di volti). */
  fun decode(bytes: ByteArray, count: Int): List<Secret>? {
    if (bytes.size < 2 || bytes[0].toInt() != FORMAT || (bytes[1].toInt() and 0xff) != count) return null
    val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
    buffer.position(2)
    val out = ArrayList<Secret>(count)
    repeat(count) {
      if (buffer.remaining() < 2 * LANDMARK_SHORTS + 1) return null
      val landmarks = ShortArray(LANDMARK_SHORTS) { buffer.short }
      val vector = when (buffer.get().toInt()) {
        0 -> null
        1 -> {
          if (buffer.remaining() < 2 * dimension) return null
          ShortArray(dimension) { buffer.short }
        }
        else -> return null
      }
      out += Secret(landmarks, vector)
    }
    return if (buffer.hasRemaining()) null else out
  }

  /** Il `aad` del blocco di un corpo: chiave di contenuto, autore, impronta, ricetta e numero di volti. */
  fun bodyAad(contentKey: String, author: String, digest: Long, recipe: Int, faceCount: Int): ByteArray =
    "mcg-faces/v1/face_body.secret|$contentKey|$author|$digest|$recipe|$faceCount".toByteArray(Charsets.UTF_8)

  /** Il `aad` del nome di una persona: soggetto e campo della voce. */
  fun nameAad(subject: String, field: String): ByteArray =
    "mcg-faces/v1/people_record.sealed_value|$subject|$field".toByteArray(Charsets.UTF_8)

  /** Il `aad` del controllo della chiave (`faces_meta`): vedi [KEY_CHECK_PLAIN]. */
  fun keyCheckAad(): ByteArray = "mcg-faces/v1/faces_meta.key_check".toByteArray(Charsets.UTF_8)

  /** Un testo noto che si sigilla alla creazione del database: se con la chiave di oggi non si apre, il database e' di un'altra chiave. */
  val KEY_CHECK_PLAIN: ByteArray = "mcg-faces-key-check-v1".toByteArray(Charsets.UTF_8)
}
