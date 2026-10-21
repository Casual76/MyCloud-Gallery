package dev.pampa.mycloudgallery.core.ai.runtime

import dev.pampa.mycloudgallery.core.ai.contract.AiBackend
import dev.pampa.mycloudgallery.core.ai.contract.AiEngineError
import dev.pampa.mycloudgallery.core.ai.contract.AiEngineException
import kotlin.math.sqrt
import kotlinx.coroutines.delay

/** Sessioni finte per provare la POLITICA dei motori (ripiego, serializzazione, errori) sulla JVM, senza LiteRT-LM. */
internal class FakeSession(
  override val backend: AiBackend,
  private val factory: FakeSessionFactory,
) : VectorSession {
  var closed = false
    private set

  override suspend fun embedText(text: String): FloatArray {
    factory.texts += text
    return factory.run(this) { factory.textVector(this, text) }
  }

  override suspend fun embedImage(jpeg: ByteArray): FloatArray = factory.run(this) { factory.imageVector(this, jpeg) }

  override suspend fun close() {
    closed = true
    factory.closed += this
  }
}

internal class FakeSessionFactory(
  /** Per quanto tempo (virtuale) ogni inferenza resta in corso: serve a provare che le richieste non si sovrappongono. */
  var latencyMs: Long = 0,
) : SessionFactory {
  val opened = mutableListOf<FakeSession>()
  val closed = mutableListOf<FakeSession>()
  val texts = mutableListOf<String>()
  val attempts = mutableListOf<AiBackend>()

  /** Un errore da lanciare all'apertura di un backend. */
  val openFailure = mutableMapOf<AiBackend, Throwable>()

  /** Cosa fare di un'inferenza: lanciare, o restituire un vettore. Il default e' quello deterministico. */
  var onText: ((FakeSession, String) -> FloatArray)? = null
  var onImage: ((FakeSession, ByteArray) -> FloatArray)? = null

  private var inFlight = 0
  var maxConcurrent = 0
    private set

  override suspend fun open(backend: AiBackend): VectorSession {
    attempts += backend
    openFailure[backend]?.let { throw it }
    return FakeSession(backend, this).also { opened += it }
  }

  suspend fun run(session: FakeSession, body: () -> FloatArray): FloatArray {
    inFlight++
    maxConcurrent = maxOf(maxConcurrent, inFlight)
    try {
      if (latencyMs > 0) delay(latencyMs)
      return body()
    } finally {
      inFlight--
    }
  }

  fun textVector(session: FakeSession, text: String): FloatArray = onText?.invoke(session, text) ?: unitVector("t:$text")

  fun imageVector(session: FakeSession, jpeg: ByteArray): FloatArray = onImage?.invoke(session, jpeg) ?: unitVector("i:" + jpeg.contentHashCode())

  companion object {
    const val DIM = 768

    /** Un vettore di norma 1 che dipende solo dal seme. */
    fun unitVector(seed: String, dimension: Int = DIM): FloatArray {
      var state = seed.hashCode().toLong() * 6364136223846793005L + 1442695040888963407L
      val v = FloatArray(dimension) {
        state = state * 6364136223846793005L + 1442695040888963407L
        ((state ushr 33).toInt() / 1073741824f) - 1f
      }
      val norm = sqrt(v.sumOf { (it * it).toDouble() }).toFloat()
      return FloatArray(dimension) { v[it] / norm }
    }
  }
}

internal suspend fun engineError(block: suspend () -> Unit): AiEngineError? =
  try {
    block()
    null
  } catch (e: AiEngineException) {
    e.error
  }
