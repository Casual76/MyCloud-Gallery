package dev.pampa.mycloudgallery.core.ai.faces

import dev.pampa.mycloudgallery.core.ai.contract.AlignedFace
import dev.pampa.mycloudgallery.core.ai.contract.FaceEngineLimits
import dev.pampa.mycloudgallery.core.model.FaceSpaceCode
import kotlin.math.abs
import kotlin.math.sqrt

// LE SESSIONI DEI MOTORI DEI VOLTI (docs/fase8-progetto.md D6, §2.2 "Nel telefono"; come `cutout/MaskSessions.kt` per MagicTouch): una
// sessione e' un modello caricato e pronto. Questo file e' puro: tiene le interfacce, l'ingresso di SFace e il controllo dei vettori.
// La SOLA classe dei volti che nomina il runtime (`org.tensorflow.lite`) e' `FaceLiteRtSessions.kt`; la politica (una richiesta alla
// volta, errori come codici, un volto che fa lanciare l'interprete non fa perdere gli altri, rilascio idempotente, riapertura da sola) sta
// in `YuNetDetector` e `SFaceEmbedder` ed e' provata sulla JVM con sessioni finte (il modello vero gira solo sul dispositivo).

/** YuNet caricato. Una chiamata alla volta, sul thread del motore. */
internal interface DetectorSession : AutoCloseable {
  /**
   * La corsa del modello sul quadro (1024x1024x3, BGR 0-255, riga per riga: [FrameBuilder.frame]). Le uscite valgono fino alla corsa
   * dopo. Puo' lanciare qualunque eccezione della libreria: la mappa `EngineErrors`.
   */
  fun run(frame: FloatArray): YuNetOutputs
}

/** SFace caricato. Una chiamata alla volta, sul thread del motore. */
internal interface EmbedderSession : AutoCloseable {
  /** Il vettore **grezzo** (128 valori, non normalizzato: norma circa 11-12) del volto [rgb] (112x112x3, RGB 0-255, riga per riga). */
  fun run(rgb: FloatArray): FloatArray
}

/** Apre la sessione (bloccante: gira sul thread del motore). Lancia `AiEngineException(MODEL_UNAVAILABLE)` se il modello non c'e' o non passa la verifica. */
internal fun interface DetectorOpener {
  suspend fun open(): DetectorSession
}

internal fun interface EmbedderOpener {
  suspend fun open(): EmbedderSession
}

/** L'ingresso di SFace e i vettori che ne escono. */
internal object FaceVectors {

  /** Il numero di valori dell'ingresso (112x112x3). */
  const val INPUT_VALUES: Int = FaceEngineLimits.ALIGNED_SIDE * FaceEngineLimits.ALIGNED_SIDE * 3

  /** Fuori da questa banda la norma del vettore grezzo non e' quella di un volto (zeri, valori assurdi): `FACES_VECTOR_INVALID`. */
  private const val MIN_RAW_NORM = 1e-3
  private const val MAX_RAW_NORM = 1e4

  /** Il volto allineato come lo vuole SFace: RGB 0-255 (la media 127,5 e la scala 1/128 sono dentro il modello). Scrive in [into] e lo ritorna. */
  fun input(aligned: AlignedFace, into: FloatArray = FloatArray(INPUT_VALUES)): FloatArray {
    require(into.size == INPUT_VALUES) { "Ingresso di un'altra taglia." }
    val pixels = aligned.raw()
    var o = 0
    for (p in pixels) {
      into[o] = ((p shr 16) and 0xff).toFloat()
      into[o + 1] = ((p shr 8) and 0xff).toFloat()
      into[o + 2] = (p and 0xff).toFloat()
      o += 3
    }
    return into
  }

  /**
   * Il vettore [raw] normalizzato in float32 (norma 1), o null se non e' un vettore valido: dimensione sbagliata, valori non finiti,
   * norma grezza nulla o assurda. Il vettore si salva poi in float16 (D7); qui resta in float32.
   */
  fun normalizedOrNull(raw: FloatArray, space: FaceSpaceCode = FaceSpaceCode.CURRENT): FloatArray? {
    if (raw.size != space.dimension) return null
    var sum = 0.0
    for (v in raw) {
      if (!v.isFinite()) return null
      sum += v.toDouble() * v
    }
    val norm = sqrt(sum)
    if (!(norm > MIN_RAW_NORM && norm < MAX_RAW_NORM)) return null
    val out = FloatArray(raw.size) { (raw[it] / norm).toFloat() }
    var check = 0.0
    for (v in out) check += v.toDouble() * v
    return if (abs(sqrt(check) - 1.0) <= FaceEngineLimits.NORM_TOLERANCE) out else null
  }
}
