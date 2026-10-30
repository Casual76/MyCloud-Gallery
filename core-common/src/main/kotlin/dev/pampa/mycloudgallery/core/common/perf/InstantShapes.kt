package dev.pampa.mycloudgallery.core.common.perf

import dev.pampa.mycloudgallery.core.common.files.StateFiles
import dev.pampa.mycloudgallery.core.common.log.GalleryLog
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Le **forme** delle pagine principali (Avvio istantaneo, 28/10): per ogni pagina un elenco di numeri (quanti blocchi, quante
 * righe, quante piastrelle), cosi' al primo ingresso dopo un avvio a freddo la pagina disegna uno scheletro con le dimensioni
 * dell'ultima volta invece di una rotella. Il formato dei numeri lo decide chi li usa (`InstantShape` di core-designsystem).
 *
 * **Solo numeri e codici fissi** (`raccolte`, `persone`, `cerca`): mai un titolo, un nome, un id o un media. Il file sta in
 * `noBackupFilesDir/replica/` (fuori dal backup del telefono, tolto con la replica dall'azzeramento) e passa da [StateFiles];
 * se la cartella non c'e' (un azzeramento appena finito) non si ricrea: si scrive solo dove la galleria esiste.
 *
 * Si legge una volta, fuori dal thread principale ([load], dall'avvio del processo); finche' non e' letto le pagine usano la
 * forma di serie. Le scritture si accorpano ([WRITE_DELAY_MS]): una pagina che si aggiorna spesso non scrive a ogni cambio.
 */
object InstantShapes {

  /** Il nome del file, in `noBackupFilesDir/replica/`. */
  const val FILE_NAME: String = "instant-shapes"

  /** L'attesa con cui si accorpano le scritture. */
  const val WRITE_DELAY_MS: Long = 2_000L

  private const val TAG = "InstantShapes"
  private const val MAX_CODES = 32
  private const val MAX_VALUES = 128
  private val CODE = Regex("[a-z][a-z0-9-]{0,31}")

  private val values = ConcurrentHashMap<String, List<Int>>()
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

  @Volatile private var file: File? = null
  @Volatile private var pending: Job? = null

  /** Vero quando il file e' stato letto (o non c'era). */
  @Volatile var loaded: Boolean = false
    private set

  /** La cartella del file: si chiama dall'avvio del processo, prima di [load]. */
  fun install(file: File) {
    this.file = file
  }

  /** Legge il file (bloccante: fuori dal thread principale). Un file illeggibile o rovinato vale "nessuna forma". */
  fun load() {
    val target = file
    if (target == null) {
      loaded = true
      return
    }
    try {
      StateFiles.readLines(target, label = FILE_NAME)?.take(MAX_CODES)?.forEach { line ->
        val code = line.substringBefore('=', "")
        val numbers = line.substringAfter('=', "").split(',').mapNotNull { it.trim().toIntOrNull() }
        if (CODE.matches(code) && numbers.size <= MAX_VALUES) values.putIfAbsent(code, numbers)
      }
    } catch (e: IOException) {
      GalleryLog.w(TAG) { "forme delle pagine non lette: ${e.javaClass.simpleName}" }
    }
    loaded = true
  }

  /** La forma ricordata per [code], o null. */
  operator fun get(code: String): List<Int>? = values[code]

  /** Ricorda la forma di [code] e la scrive piu' tardi. Un codice o dei numeri fuori dai limiti si ignorano. */
  fun put(code: String, shape: List<Int>) {
    if (!CODE.matches(code) || shape.size > MAX_VALUES) return
    if (values[code] == shape) return
    if (values.size >= MAX_CODES && !values.containsKey(code)) return
    values[code] = shape
    schedule()
  }

  /**
   * Dimentica la forma di [code] (R11, correzioni A: la forma della pagina Persone non resta dopo "Disattiva e cancella") e riscrive il file senza: la
   * prossima apertura a freddo non disegna piu' lo scheletro con i numeri di prima. Un codice che non c'era non fa niente.
   */
  fun remove(code: String) {
    if (values.remove(code) != null) schedule()
  }

  /** Dimentica tutto (l'azzeramento): niente si riscrive. */
  fun clear() {
    pending?.cancel()
    values.clear()
  }

  /** Scrive subito cio' che e' in attesa (i test; l'app lascia fare all'accorpamento). */
  fun flushNow() {
    pending?.cancel()
    writeNow()
  }

  private fun schedule() {
    if (pending?.isActive == true) return
    pending = scope.launch {
      delay(WRITE_DELAY_MS)
      writeNow()
    }
  }

  private fun writeNow() {
    val target = file ?: return
    // Dopo un azzeramento la cartella non c'e': non si ricrea per dei numeri.
    if (target.parentFile?.isDirectory != true) return
    val text = values.entries.sortedBy { it.key }.joinToString("\n") { (code, numbers) -> code + "=" + numbers.joinToString(",") }
    try {
      StateFiles.writeText(target, text, Charsets.US_ASCII, label = FILE_NAME, durable = false)
    } catch (e: IOException) {
      GalleryLog.w(TAG) { "forme delle pagine non scritte: ${e.javaClass.simpleName}" }
    }
  }
}
