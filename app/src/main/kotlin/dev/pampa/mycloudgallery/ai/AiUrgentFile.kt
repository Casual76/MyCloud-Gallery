package dev.pampa.mycloudgallery.ai

import dev.pampa.mycloudgallery.core.ai.compute.AiUrgentKeys
import dev.pampa.mycloudgallery.core.common.files.StateFiles
import dev.pampa.mycloudgallery.core.model.ContentKey
import java.io.File
import java.io.IOException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Il registro dei media che l'utente ha chiesto di analizzare adesso ("Analizza adesso" dal visore, Pacchetto X): solo chiavi di
 * contenuto, in un file di righe di `noBackupFilesDir` (come `AiStatusMemory` e i segni "non supportato"). Sopravvive alla morte del
 * processo perche' il calcolo gira quando WorkManager lo avvia, anche in un processo nato per lui: la richiesta dell'utente non deve
 * perdersi perche' l'app si e' chiusa. Mai sul NAS: e' una precedenza **di questo dispositivo**, non un dato dell'indice.
 *
 * Ha un tetto ([MAX_KEYS]): oltre, i piu' vecchi escono per primi (e' una comodita', non un elenco da conservare). L'azzeramento lo
 * svuota ([clear] da `AiGraph.stop`, e il file sta nella cartella che l'eraser toglie).
 */
class AiUrgentFile(private val file: File) : AiUrgentKeys {
  private val lock = Any()
  private val keys = LinkedHashSet<ContentKey>()

  private val flow = MutableStateFlow<Set<ContentKey>>(emptySet())

  /** Le urgenze di adesso, come flusso: la pagina "Analisi" e il visore lo seguono mentre cambiano. */
  val state: StateFlow<Set<ContentKey>> = flow.asStateFlow()

  init {
    synchronized(lock) {
      loadLocked()
      flow.value = keys.toSet()
    }
  }

  /** "Analizza adesso": il media va in testa alle richieste (l'ultimo chiesto e' il primo in coda). */
  fun add(key: ContentKey) {
    synchronized(lock) {
      keys.remove(key)
      keys += key
      trimLocked()
      saveLocked()
    }
  }

  override fun urgent(): Set<ContentKey> = synchronized(lock) { keys.toSet() }

  override fun done(key: ContentKey) {
    synchronized(lock) {
      if (keys.remove(key)) saveLocked()
    }
  }

  /** Togli quelle che sono gia' a posto (chi osserva l'indice le riconosce: il calcolo le salterebbe comunque). */
  fun remove(keys: Collection<ContentKey>) {
    if (keys.isEmpty()) return
    synchronized(lock) {
      if (this.keys.removeAll(keys.toSet())) saveLocked()
    }
  }

  /** L'azzeramento: dimentica tutto e toglie il file. */
  fun clear() {
    synchronized(lock) {
      keys.clear()
      flow.value = emptySet()
      StateFiles.delete(file)
    }
  }

  private fun trimLocked() {
    while (keys.size > MAX_KEYS) keys.remove(keys.first())
  }

  private fun loadLocked() {
    try {
      if (!file.isFile) return
      file.readLines().forEach { line ->
        runCatching { ContentKey(line.trim()) }.getOrNull()?.let { keys += it }
      }
      trimLocked()
    } catch (e: IOException) {
      // Nel dubbio nessuna urgenza: la coda torna quella di sempre (l'errore e' nel log di `StateFiles`).
    }
  }

  private fun saveLocked() {
    flow.value = keys.toSet()
    try {
      StateFiles.writeText(file, keys.joinToString("") { "${it.hex}\n" })
    } catch (e: IOException) {
      // Resta in memoria per questo processo (l'errore e' nel log di `StateFiles`).
    }
  }

  companion object {
    const val FILE = "ai-urgent"

    /** Al piu' cosi' tante richieste ricordate: oltre, il tocco piu' vecchio vale come mai fatto. */
    const val MAX_KEYS: Int = 64
  }
}
