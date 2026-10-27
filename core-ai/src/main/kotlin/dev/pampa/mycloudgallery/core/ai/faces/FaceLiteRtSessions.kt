package dev.pampa.mycloudgallery.core.ai.faces

import dev.pampa.mycloudgallery.core.ai.contract.AiEngineError
import dev.pampa.mycloudgallery.core.ai.contract.AiEngineException
import dev.pampa.mycloudgallery.core.ai.contract.FaceEngineLimits
import dev.pampa.mycloudgallery.core.model.FaceSpaceCode
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.tensorflow.lite.Interpreter

// LE SESSIONI DEI VOLTI SU LITERT (docs/fase8-progetto.md D6, §2.2 "Nel telefono"; Phase6ArchitectureTest e Phase8ArchitectureTest): la SOLA
// classe dei volti che nomina il runtime (`org.tensorflow.lite`, l'API classica dell'AAR `litert` 2.3.0), su CPU a 4 thread, senza delegati
// (GPU e NNAPI non servono: YuNet ha 76 mila parametri e SFace pochi milioni, D6) e senza rete. Il modello arriva come `ByteBuffer`
// mappato sul descrittore verificato (D16: l'interprete legge i byte su cui si e' calcolata l'impronta). Tutte le classi di questo file
// stanno dentro [FaceLiteRtSessions]: `tools/check.ps1` cerca nel bytecode di release le chiamate a LiteRT e ammette, fra le classi dei
// volti, solo quelle che partono da `FaceLiteRtSessions` (e dalle sue classi interne).
//
// Le classi di LiteRT non si caricano sulla JVM dei test: la politica sta in `YuNetDetector` e `SFaceEmbedder`, che parlano con le
// interfacce `DetectorSession` e `EmbedderSession`; queste due sessioni si provano solo sul dispositivo (`core-ai/src/androidTest/.../faces/`),
// e la forma dei due file `.tflite` (ingressi, uscite, nomi e operatori) la controlla sulla JVM `FaceModelContractTest` leggendo gli asset.
//
// Le forme attese sono quelle consegnate da P1 (docs/decisioni.md, "Fase 8 P1"): se il file non le ha, e' un altro modello e la sessione non
// parte (`MODEL_UNAVAILABLE`, che la Diagnostica dice `FACES_MODEL_MISMATCH`), invece di leggere uscite sbagliate.

internal object FaceLiteRtSessions {

  /** YuNet: ingresso `[1, 1024, 1024, 3]` float32 (BGR 0-255), dodici uscite float32 `[1, N, C]` nell'ordine di [YuNetLayout.OUTPUT_NAMES]. */
  fun detector(model: ByteBuffer, threads: Int): DetectorSession {
    val interpreter = Interpreter(model, options(threads))
    try {
      return Detector(interpreter)
    } catch (t: Throwable) {
      interpreter.close()
      throw t
    }
  }

  /** SFace: ingresso `[1, 112, 112, 3]` float32 (RGB 0-255), uscita `[1, 128]` float32 non normalizzata. */
  fun embedder(model: ByteBuffer, threads: Int): EmbedderSession {
    val interpreter = Interpreter(model, options(threads))
    try {
      return Embedder(interpreter)
    } catch (t: Throwable) {
      interpreter.close()
      throw t
    }
  }

  private class Detector(private val interpreter: Interpreter) : DetectorSession {
    private val input = direct(YuNetLayout.INPUT_SIDE * YuNetLayout.INPUT_SIDE * 3 * 4)
    private val outputs = YuNetOutputs.allocate()
    private val buffers: Array<ByteBuffer>
    private val targets: Array<FloatArray>
    private val outputMap: Map<Int, Any>

    init {
      val side = YuNetLayout.INPUT_SIDE
      mismatchUnless(interpreter.inputTensorCount == 1 && interpreter.getInputTensor(0).shape().contentEquals(intArrayOf(1, side, side, 3)))
      mismatchUnless(interpreter.getInputTensor(0).numBytes() == side * side * 3 * 4)
      val count = interpreter.outputTensorCount
      mismatchUnless(count == YuNetLayout.OUTPUT_NAMES.size)
      // L'uscita i del file e' quella con il suo nome; senza nomi riconoscibili vale la posizione (P1 le ha scritte nell'ordine canonico).
      val named = IntArray(count) { YuNetLayout.OUTPUT_NAMES.indexOf(interpreter.getOutputTensor(it).name()) }
      val byName = named.all { it >= 0 } && named.toSet().size == count
      val slot = IntArray(count) { if (byName) named[it] else it }
      buffers = Array(count) { i ->
        val tensor = interpreter.getOutputTensor(i)
        val shape = YuNetLayout.expectedShape(slot[i])
        mismatchUnless(tensor.shape().contentEquals(shape))
        mismatchUnless(tensor.numBytes() == shape.fold(1) { a, b -> a * b } * 4)
        direct(tensor.numBytes())
      }
      targets = Array(count) { i -> targetOf(outputs, slot[i]) }
      outputMap = buffers.indices.associateWith { buffers[it] }
    }

    override fun run(frame: FloatArray): YuNetOutputs {
      require(frame.size == YuNetLayout.INPUT_SIDE * YuNetLayout.INPUT_SIDE * 3) { "Quadro di un'altra taglia." }
      input.clear()
      input.asFloatBuffer().put(frame)
      for (b in buffers) b.clear()
      interpreter.runForMultipleInputsOutputs(arrayOf<Any>(input), outputMap)
      for (i in buffers.indices) {
        buffers[i].rewind()
        buffers[i].asFloatBuffer().get(targets[i])
      }
      return outputs
    }

    override fun close() {
      interpreter.close()
    }
  }

  private class Embedder(private val interpreter: Interpreter) : EmbedderSession {
    private val dimension = FaceSpaceCode.CURRENT.dimension
    private val input = direct(FaceVectors.INPUT_VALUES * 4)
    private val output = direct(dimension * 4)

    init {
      val side = FaceEngineLimits.ALIGNED_SIDE
      mismatchUnless(interpreter.inputTensorCount == 1 && interpreter.getInputTensor(0).shape().contentEquals(intArrayOf(1, side, side, 3)))
      mismatchUnless(interpreter.getInputTensor(0).numBytes() == FaceVectors.INPUT_VALUES * 4)
      mismatchUnless(interpreter.outputTensorCount == 1 && interpreter.getOutputTensor(0).shape().contentEquals(intArrayOf(1, dimension)))
      mismatchUnless(interpreter.getOutputTensor(0).numBytes() == dimension * 4)
    }

    override fun run(rgb: FloatArray): FloatArray {
      require(rgb.size == FaceVectors.INPUT_VALUES) { "Ingresso di un'altra taglia." }
      input.clear()
      input.asFloatBuffer().put(rgb)
      output.clear()
      interpreter.run(input, output)
      output.rewind()
      return FloatArray(dimension).also { output.asFloatBuffer().get(it) }
    }

    override fun close() {
      interpreter.close()
    }
  }

  /** La matrice dove finisce l'uscita [slot] (0..11) di YuNet: `cls`, `obj`, `bbox`, `kps` per scala. */
  private fun targetOf(outputs: YuNetOutputs, slot: Int): FloatArray {
    val scale = slot % YuNetLayout.SCALES
    return when (slot / YuNetLayout.SCALES) {
      0 -> outputs.cls[scale]
      1 -> outputs.obj[scale]
      2 -> outputs.bbox[scale]
      else -> outputs.kps[scale]
    }
  }

  /** Il file non e' il modello atteso: un altro modello non si legge come se fosse questo. */
  private fun mismatchUnless(ok: Boolean) {
    if (!ok) throw AiEngineException(AiEngineError.MODEL_UNAVAILABLE)
  }

  private fun options(threads: Int): Interpreter.Options = Interpreter.Options().setNumThreads(threads)

  private fun direct(bytes: Int): ByteBuffer = ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder())
}
