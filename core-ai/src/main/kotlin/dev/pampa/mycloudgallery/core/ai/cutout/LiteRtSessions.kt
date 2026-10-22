package dev.pampa.mycloudgallery.core.ai.cutout

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.tensorflow.lite.Interpreter

// LE SESSIONI DI MAGICTOUCH SU LITERT (docs/fase6-progetto.md D44, Q11): la SOLA classe del ritaglio che nomina il runtime
// (`org.tensorflow.lite`, l'API classica dell'AAR `litert` 2.3.0), su CPU. Il modello arriva come `ByteBuffer` mappato sul
// descrittore verificato (D16: l'interprete legge i byte su cui e' stata calcolata l'impronta). Nessuna rete, nessun delegato: il
// runtime non parla con nessuno (F67 lo prova con una cattura).
//
// Le classi di LiteRT non si caricano sulla JVM dei test: la politica sta in `MagicTouchCutter`, che parla con [MaskSession]; queste
// due sessioni si provano solo sul dispositivo.

/** MagicTouch versione 1: un solo modello, ingresso `[1, 512, 512, 4]` (RGB + la mappa del punto), uscita `[1, 512, 512, 1]`. */
internal class V1Session(
  model: ByteBuffer,
  threads: Int,
  private val tuning: MagicTouchTuning,
) : MaskSession {

  override val label: String = "v1"
  override val side: Int = SIDE

  private val interpreter = Interpreter(model, options(threads))
  private val input = direct(SIDE * SIDE * CHANNELS * 4)
  private val output = direct(SIDE * SIDE * 4)
  private val scratch = FloatArray(SIDE * SIDE * CHANNELS)

  init {
    val shape = interpreter.getInputTensor(0).shape()
    check(shape.contentEquals(intArrayOf(1, SIDE, SIDE, CHANNELS))) { "Ingresso inatteso" }
  }

  override fun run(frame: ModelFrame, nx: Float, ny: Float, identity: Any?): FloatArray {
    require(frame.side == SIDE) { "Quadro di un'altra taglia." }
    val rgb = frame.rgb
    var o = 0
    var i = 0
    for (p in 0 until SIDE * SIDE) {
      scratch[o] = tuning.scale(rgb[i])
      scratch[o + 1] = tuning.scale(rgb[i + 1])
      scratch[o + 2] = tuning.scale(rgb[i + 2])
      scratch[o + 3] = 0f
      o += CHANNELS
      i += 3
    }
    PointMap.fill(SIDE, nx, ny, tuning, scratch, CHANNELS, 3)
    input.clear()
    input.asFloatBuffer().put(scratch)
    output.clear()
    interpreter.run(input, output)
    output.rewind()
    val raw = FloatArray(SIDE * SIDE)
    output.asFloatBuffer().get(raw)
    return Probabilities.of(raw)
  }

  override fun close() {
    interpreter.close()
  }

  companion object {
    const val SIDE: Int = 512
    private const val CHANNELS = 4
  }
}

/**
 * MagicTouch versione 2: un codificatore dell'immagine (`[1, 768, 768, 3]` -> cinque mappe) e un decodificatore (le cinque mappe e la
 * mappa del punto `[1, 768, 768, 3]` -> `[1, 768, 768, 1]`). Il codificatore e' il costo grosso e dipende solo dall'immagine: con lo
 * stesso quadro di prima ([identity]) si salta e si rifa solo il decodificatore.
 */
internal class V2Session(
  encoderModel: ByteBuffer,
  decoderModel: ByteBuffer,
  threads: Int,
  private val tuning: MagicTouchTuning,
) : MaskSession {

  override val label: String = "v2"
  override val side: Int = SIDE

  private val encoder = Interpreter(encoderModel, options(threads))
  private val decoder = try {
    Interpreter(decoderModel, options(threads))
  } catch (t: Throwable) {
    encoder.close()
    throw t
  }
  private val image = direct(SIDE * SIDE * 3 * 4)
  private val prompt = direct(SIDE * SIDE * 3 * 4)
  private val features: Array<ByteBuffer> = Array(encoder.outputTensorCount) { direct(encoder.getOutputTensor(it).numBytes()) }
  private val output = direct(SIDE * SIDE * 4)
  private val imageScratch = FloatArray(SIDE * SIDE * 3)
  private val promptScratch = FloatArray(SIDE * SIDE * 3)
  private var encoded: Any? = null

  init {
    check(encoder.getInputTensor(0).shape().contentEquals(intArrayOf(1, SIDE, SIDE, 3))) { "Ingresso del codificatore inatteso" }
    check(decoder.inputTensorCount == 1 + features.size) { "Ingressi del decodificatore inattesi" }
  }

  override fun run(frame: ModelFrame, nx: Float, ny: Float, identity: Any?): FloatArray {
    require(frame.side == SIDE) { "Quadro di un'altra taglia." }
    if (identity == null || identity !== encoded) {
      encoded = null
      val rgb = frame.rgb
      for (i in rgb.indices) imageScratch[i] = tuning.scale(rgb[i])
      image.clear()
      image.asFloatBuffer().put(imageScratch)
      val outputs = HashMap<Int, Any>()
      for (k in features.indices) {
        features[k].clear()
        outputs[k] = features[k]
      }
      encoder.runForMultipleInputsOutputs(arrayOf<Any>(image), outputs)
      encoded = identity
    }
    java.util.Arrays.fill(promptScratch, 0f)
    when (tuning.v2Prompt) {
      MagicTouchTuning.V2Prompt.CHANNEL0 -> PointMap.fill(SIDE, nx, ny, tuning, promptScratch, 3, 0)
      MagicTouchTuning.V2Prompt.CHANNEL1 -> PointMap.fill(SIDE, nx, ny, tuning, promptScratch, 3, 1)
      MagicTouchTuning.V2Prompt.CHANNEL2 -> PointMap.fill(SIDE, nx, ny, tuning, promptScratch, 3, 2)
      MagicTouchTuning.V2Prompt.ALL -> for (c in 0..2) PointMap.fill(SIDE, nx, ny, tuning, promptScratch, 3, c)
      MagicTouchTuning.V2Prompt.NONE -> Unit
      MagicTouchTuning.V2Prompt.IMAGE -> System.arraycopy(imageScratch, 0, promptScratch, 0, promptScratch.size)
    }
    prompt.clear()
    prompt.asFloatBuffer().put(promptScratch)
    val inputs = ArrayList<Any>(1 + features.size)
    inputs += prompt
    for (f in features) {
      f.rewind()
      inputs += f
    }
    output.clear()
    decoder.runForMultipleInputsOutputs(inputs.toTypedArray(), mapOf(0 to output))
    output.rewind()
    val raw = FloatArray(SIDE * SIDE)
    output.asFloatBuffer().get(raw)
    return Probabilities.of(raw)
  }

  override fun close() {
    runCatching { decoder.close() }
    runCatching { encoder.close() }
  }

  companion object {
    const val SIDE: Int = 768
  }
}

private fun options(threads: Int): Interpreter.Options = Interpreter.Options().setNumThreads(threads)

private fun direct(bytes: Int): ByteBuffer = ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder())
