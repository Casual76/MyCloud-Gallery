package dev.pampa.mycloudgallery.core.ai.runtime

import dev.pampa.mycloudgallery.core.common.files.StateFiles
import java.io.File
import java.io.IOException

/**
 * La memoria, **su disco**, di una GPU che fa cadere il processo (R9 della revisione di sicurezza della Fase 6, E-A12). Il ripiego su CPU di
 * [LiteRtLmImageEmbedder] vale solo in memoria e si attiva quando la GPU **lancia** o da' un vettore non valido; se invece la GPU fa cadere il
 * processo (driver, memoria) il ripiego non arriva mai, e al processo dopo si riprova la GPU: il calcolo muore a ogni tappa senza lasciare
 * traccia.
 *
 * Prima di aprire una sessione GPU si scrive il segno "in prova" ([begin]); dopo il primo vettore valido lo si toglie ([passed], che azzera
 * anche il conto); un fallimento gestito (la GPU lancia) o una chiusura pulita lo tolgono senza contare ([handled]). Se all'apertura del
 * processo successivo il segno c'e' ancora, il processo e' morto durante la prova: si conta una caduta. Dopo [MAX_CRASHES] cadute di fila la
 * GPU non si riprova (`allowed` falso) e il motore parte su CPU, con `AI_GPU_FALLBACK`.
 *
 * I due file stanno **accanto** alla cartella dei programmi compilati (`models/cache/<sha256>-gpu.trial` e `.crashes`), non dentro: quella cartella
 * si toglie a ogni inizializzazione fallita, e con lei se ne andrebbe la memoria delle cadute. [base] null = niente su disco (i test sulla JVM).
 */
internal class GpuTrial(private val base: File?) {

  private val trial: File? get() = base?.let { File(it.path + ".trial") }
  private val crashes: File? get() = base?.let { File(it.path + ".crashes") }

  /** Quante volte di fila il processo e' morto durante una prova della GPU. */
  val crashCount: Int

  init {
    var count = readCount()
    if (trial?.exists() == true) {
      // Il processo di prima e' morto con la GPU in prova (nessuno ha tolto il segno): una caduta.
      count++
      writeCount(count)
      removeTrial()
    }
    crashCount = count
  }

  /** La GPU si puo' provare. */
  val allowed: Boolean get() = crashCount < MAX_CRASHES

  /** Si sta per aprire una sessione GPU: se il processo muore da qui al primo vettore, e' una caduta. */
  fun begin() {
    val file = trial ?: return
    try {
      StateFiles.writeText(file, "1", Charsets.US_ASCII)
    } catch (_: IOException) {
      // Senza il segno la GPU si riprova come prima: al peggio un'altra tappa che cade (l'errore e' nel log di `StateFiles`).
    }
  }

  /** Un vettore valido dalla GPU: la prova e' riuscita, nessuna caduta da ricordare. */
  fun passed() {
    removeTrial()
    crashes?.let { StateFiles.delete(it) }
  }

  /** La prova e' finita senza cadere (un'eccezione gestita, il ripiego, la chiusura del motore): il segno si toglie, il conto resta com'e'. */
  fun handled() {
    removeTrial()
  }

  /**
   * Toglie il segno "in prova". Se resta (il log di `StateFiles` lo dice, dopo i tentativi) il processo dopo conterebbe una caduta che non
   * c'e' stata: per questo non si da' per tolto alla leggera.
   */
  private fun removeTrial() {
    trial?.let { StateFiles.delete(it) }
  }

  private fun readCount(): Int =
    try {
      crashes?.takeIf { it.isFile && it.length() < 16 }?.let { StateFiles.readText(it, Charsets.US_ASCII) }?.trim()?.toIntOrNull()?.coerceIn(0, MAX_CRASHES) ?: 0
    } catch (_: IOException) {
      0
    }

  private fun writeCount(count: Int) {
    val file = crashes ?: return
    try {
      StateFiles.writeText(file, count.toString(), Charsets.US_ASCII)
    } catch (_: IOException) {
      // Resta solo in memoria per questo processo.
    }
  }

  companion object {
    /** Due cadute di fila e la GPU non si prova piu' su questo dispositivo (finche' il modello non si toglie o l'app si azzera). */
    const val MAX_CRASHES: Int = 2
  }
}
