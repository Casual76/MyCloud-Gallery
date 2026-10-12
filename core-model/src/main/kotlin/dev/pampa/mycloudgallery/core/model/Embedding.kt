package dev.pampa.mycloudgallery.core.model

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Come sono codificati i numeri di un vettore. */
enum class VectorPrecision(override val code: String, val bytesPerComponent: Int) : StableCode {
  FLOAT32("f32", 4),
  FLOAT16("f16", 2),
  INT8("i8", 1),
}

private val SPACE_ID = Regex("^[a-z0-9][a-z0-9.-]{0,63}$")

/**
 * Uno spazio vettoriale: il modello che produce i vettori **piu'** dimensione e precisione.
 *
 * `docs/decisioni.md` (12/10): "ogni vettore porta l'etichetta dello spazio
 * (`embeddinggemma2-float-v1` + dimensione + precisione). I vettori di spazi diversi non si mescolano
 * mai" (E-I4). Per questo l'uguaglianza considera tutti e tre i campi: lo stesso modello ridotto a 256
 * dimensioni (MRL) e' un altro spazio, e confrontarlo con uno a 768 darebbe risultati senza senso.
 *
 * La dimensione non e' fissata qui: la decide la misura di Fase 0.3/6.1 ancora aperta.
 */
data class EmbeddingSpace(
  val id: String,
  val dimension: Int,
  val precision: VectorPrecision,
) {

  init {
    require(SPACE_ID.matches(id)) { "Etichetta di spazio vettoriale non valida." }
    require(dimension in 1..8192) { "Dimensione del vettore fuori intervallo." }
  }

  /** Quanti byte occupa un vettore di questo spazio. */
  val vectorBytes: Int get() = dimension * precision.bytesPerComponent

  companion object {
    /** L'etichetta del modello deciso in Fase 0 (spazio standard float, GPU/CPU). */
    const val EMBEDDING_GEMMA2_FLOAT_V1: String = "embeddinggemma2-float-v1"
  }
}

/**
 * Un vettore di un media in uno spazio. [index] distingue piu' vettori dello stesso media (i
 * fotogrammi chiave di un video, 6.2); per una foto e' 0. [computedFrom] e' la versione da cui e'
 * stato calcolato: dopo una modifica il vettore vecchio resta utilizzabile finche' quello nuovo non
 * c'e' (la ricerca non perde il media nel frattempo, E-A10).
 *
 * I byte sono copiati in entrata e in uscita: un array condiviso potrebbe essere cambiato da fuori.
 */
class EmbeddingVector(
  val space: EmbeddingSpace,
  val index: Int,
  bytes: ByteArray,
  val computedFrom: VersionId?,
) {

  private val data: ByteArray = bytes.copyOf()

  init {
    require(index >= 0) { "Indice del vettore negativo." }
    require(data.size == space.vectorBytes) {
      "Il vettore ha ${data.size} byte, lo spazio ne prevede ${space.vectorBytes}."
    }
  }

  val bytes: ByteArray get() = data.copyOf()

  override fun equals(other: Any?): Boolean =
    other is EmbeddingVector && other.space == space && other.index == index &&
      other.computedFrom == computedFrom && other.data.contentEquals(data)

  override fun hashCode(): Int = ((space.hashCode() * 31 + index) * 31 + computedFrom.hashCode()) * 31 + data.contentHashCode()

  override fun toString(): String = "EmbeddingVector(${space.id}/${space.dimension}/${space.precision.code}#$index)"

  companion object {
    /** Un vettore float32 in little-endian (l'ordine dei byte di ARM e x86: nessuna conversione). */
    fun ofFloats(space: EmbeddingSpace, index: Int, values: FloatArray, computedFrom: VersionId?): EmbeddingVector {
      require(space.precision == VectorPrecision.FLOAT32) { "ofFloats vale solo per spazi FLOAT32." }
      val buffer = ByteBuffer.allocate(values.size * 4).order(ByteOrder.LITTLE_ENDIAN)
      values.forEach { buffer.putFloat(it) }
      return EmbeddingVector(space, index, buffer.array(), computedFrom)
    }
  }

  /** I componenti di un vettore FLOAT32. */
  fun toFloats(): FloatArray {
    require(space.precision == VectorPrecision.FLOAT32) { "toFloats vale solo per spazi FLOAT32." }
    val buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
    return FloatArray(space.dimension) { buffer.getFloat() }
  }
}

/**
 * I dati personali di un media (piano §1 "Dati personali"): preferito e nascosto. Stanno separati dai
 * dati del media perche' **non si condividono mai**: dalla Fase 4.7 si sincronizzano solo tra i
 * dispositivi della stessa persona, nella sua area privata.
 */
data class PersonalFlags(
  val favorite: Boolean = false,
  val hidden: Boolean = false,
) {

  /**
   * Quando due media si rivelano lo stesso: preferito se lo era uno dei due, **nascosto se lo era uno
   * dei due**. Per i nascosti si sbaglia dal lato della riservatezza: mostrare per errore una foto che
   * l'utente aveva nascosto e' peggio che nasconderne una in piu' (E-H1).
   */
  fun mergedWith(other: PersonalFlags): PersonalFlags =
    PersonalFlags(favorite = favorite || other.favorite, hidden = hidden || other.hidden)
}
