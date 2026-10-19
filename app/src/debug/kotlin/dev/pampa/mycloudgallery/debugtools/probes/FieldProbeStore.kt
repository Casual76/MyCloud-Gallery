package dev.pampa.mycloudgallery.debugtools.probes

import android.content.Context
import java.io.File
import java.util.Properties

/**
 * SOLO build debug (Fase 5B, P0-B): il resoconto delle prove guidate e lo stato che le attraversa. **Solo numeri**: ogni valore e' un `Long` sotto
 * una chiave di [FieldProbeKey], salvato in `noBackupFilesDir/field-probe/counters.properties` a ogni modifica, cosi' sopravvive alla morte del
 * processo (T-15 la provoca apposta). In piu' tiene l'elenco degli indirizzi delle condivisioni ricevute (T-15) per riprovare ad aprirli piu'
 * tardi: sono indirizzi (non numeri) e restano nel file privato del processo, **mai nel resoconto**.
 *
 * Lo scrivono i componenti di prova (activity e provider, che il sistema crea in questo stesso processo) e la schermata: tutto passa dal blocco
 * [lock]. Niente rete, niente orologio da muro nelle decisioni.
 */
internal class FieldProbeStore private constructor(private val dir: File) {

  private val lock = Any()
  private val values = LinkedHashMap<String, Long>()
  private val countersFile = File(dir, COUNTERS)
  private val sharesFile = File(dir, SHARES)

  init {
    dir.mkdirs()
    load()
  }

  /** Una condivisione ricevuta: l'indirizzo, quando (ms dall'avvio del telefono) e da quale avvio del processo (per capire se e' ripartito). */
  class ShareRecord(val uri: String, val receivedAtElapsedMs: Long, val processMarker: Long) {
    override fun toString(): String = "ShareRecord"
  }

  fun get(key: FieldProbeKey, index: Int? = null): Long? = synchronized(lock) { values[FieldProbeKey.lineKey(key, index)] }

  fun set(key: FieldProbeKey, value: Long, index: Int? = null) {
    synchronized(lock) {
      values[FieldProbeKey.lineKey(key, index)] = value
      save()
    }
  }

  fun add(key: FieldProbeKey, delta: Long = 1L, index: Int? = null) {
    synchronized(lock) {
      val k = FieldProbeKey.lineKey(key, index)
      values[k] = (values[k] ?: 0L) + delta
      save()
    }
  }

  /** Tiene il massimo (per i tempi: il piu' lungo visto). */
  fun max(key: FieldProbeKey, value: Long, index: Int? = null) {
    synchronized(lock) {
      val k = FieldProbeKey.lineKey(key, index)
      if (value > (values[k] ?: Long.MIN_VALUE)) {
        values[k] = value
        save()
      }
    }
  }

  /** Scrive solo se la misura non c'e' ancora (il primo valore visto: il primo byte letto). */
  fun setIfAbsent(key: FieldProbeKey, value: Long, index: Int? = null) {
    synchronized(lock) {
      val k = FieldProbeKey.lineKey(key, index)
      if (k !in values) {
        values[k] = value
        save()
      }
    }
  }

  /** Il prossimo indice libero per le misure ripetute di [key] (1 la prima volta). */
  fun nextIndex(key: FieldProbeKey): Int = synchronized(lock) {
    val prefix = key.code + "."
    (values.keys.filter { it.startsWith(prefix) }.mapNotNull { it.removePrefix(prefix).toIntOrNull() }.maxOrNull() ?: 0) + 1
  }

  /** Il resoconto: `chiave=valore`, una per riga, in ordine di chiave. */
  fun render(): String = FieldProbeReport.render(synchronized(lock) { values.toMap() })

  /** Azzera i numeri (non gli indirizzi delle condivisioni: servono ancora a T-15). */
  fun resetNumbers() {
    synchronized(lock) {
      values.clear()
      save()
    }
  }

  // --- T-15: gli indirizzi delle condivisioni ricevute -------------------------------------------------------------------------

  fun addShares(uris: List<String>, receivedAtElapsedMs: Long) {
    synchronized(lock) {
      val lines = uris.joinToString("\n") { "$receivedAtElapsedMs\t$PROCESS_MARKER\t$it" }
      if (lines.isNotEmpty()) sharesFile.appendText(lines + "\n")
    }
  }

  fun shares(): List<ShareRecord> = synchronized(lock) {
    if (!sharesFile.isFile) return emptyList()
    sharesFile.readLines().mapNotNull { line ->
      val parts = line.split('\t', limit = 3)
      if (parts.size < 3) return@mapNotNull null
      val at = parts[0].toLongOrNull() ?: return@mapNotNull null
      val marker = parts[1].toLongOrNull() ?: return@mapNotNull null
      ShareRecord(parts[2], at, marker)
    }
  }

  fun clearShares() {
    synchronized(lock) { sharesFile.delete() }
  }

  /** Il processo di adesso (un numero diverso per ogni avvio): confrontato con quello della ricezione dice se e' ripartito. */
  val processMarker: Long get() = PROCESS_MARKER

  private fun load() {
    if (!countersFile.isFile) return
    val properties = Properties()
    countersFile.inputStream().use { properties.load(it) }
    for (name in properties.stringPropertyNames()) {
      val value = properties.getProperty(name).toLongOrNull() ?: continue
      // Solo chiavi del resoconto (con un indice facoltativo): un file toccato a mano non fa entrare nient'altro.
      if (FieldProbeKey.isKnownLine(name)) values[name] = value
    }
  }

  private fun save() {
    val properties = Properties()
    values.forEach { (k, v) -> properties.setProperty(k, v.toString()) }
    val temp = File(dir, "$COUNTERS.tmp")
    temp.outputStream().use { properties.store(it, null) }
    if (!temp.renameTo(countersFile)) {
      countersFile.delete()
      temp.renameTo(countersFile)
    }
  }

  companion object {
    private const val COUNTERS = "counters.properties"
    private const val SHARES = "t15-uris.txt"

    /** Un numero che cambia a ogni avvio del processo: la sua identita' per T-15 (il processo e' ripartito dalla ricezione?). */
    private val PROCESS_MARKER: Long = System.nanoTime()

    @Volatile
    private var instance: FieldProbeStore? = null

    /** Lo store del processo, nella cartella privata `noBackupFilesDir/field-probe/`. */
    fun of(context: Context): FieldProbeStore =
      instance ?: synchronized(this) {
        instance ?: FieldProbeStore(File(context.applicationContext.noBackupFilesDir, "field-probe")).also { instance = it }
      }

    /** Per i test: uno store in una cartella scelta, non condiviso. */
    fun forTest(dir: File): FieldProbeStore = FieldProbeStore(dir)
  }
}

/**
 * Il resoconto delle prove guidate come testo: **una riga `chiave=numero`** per ogni misura, mai altro. Un valore che non e' un numero o una chiave
 * che non e' del resoconto non arrivano qui (lo store accetta solo `Long` sotto [FieldProbeKey]); [render] rifiuta comunque ogni riga che non
 * abbia quella forma, e `FieldProbeReportTest` lo prova.
 */
internal object FieldProbeReport {

  private val LINE = Regex("^[a-z0-9_.]+=-?\\d+$")

  fun render(values: Map<String, Long>): String =
    values.entries.sortedBy { it.key }
      .map { "${it.key}=${it.value}" }
      .onEach { check(LINE.matches(it)) { "Una riga del resoconto non e' chiave=numero." } }
      .joinToString("\n")

  /** Vero se [text] e' un resoconto di soli numeri (ogni riga `chiave=numero`). */
  fun isNumbersOnly(text: String): Boolean = text.isEmpty() || text.lineSequence().all { LINE.matches(it) }
}
