package dev.pampa.mycloudgallery.debugtools.probes

import android.content.Context
import java.io.File
import java.util.Properties
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * SOLO build debug (Fase 5B, P0-B): il resoconto delle prove guidate e lo stato che le attraversa. **Solo numeri**: ogni valore e' un `Long` sotto
 * una chiave di [FieldProbeKey], salvato in `noBackupFilesDir/field-probe/counters.properties` a ogni modifica, cosi' sopravvive alla morte del
 * processo (T-15 la provoca apposta). In piu' tiene l'elenco degli indirizzi delle condivisioni ricevute (T-15) per riprovare ad aprirli piu'
 * tardi: sono indirizzi (non numeri) e restano nel file privato del processo, **mai nel resoconto**.
 *
 * Lo scrivono i componenti di prova (activity e provider, che il sistema crea in questo stesso processo) e la schermata: tutto passa dal blocco
 * [lock]. Niente rete, niente orologio da muro nelle decisioni.
 */
internal class FieldProbeStore private constructor(private val dir: File, private val deferred: Boolean) {

  private val lock = Any()
  private val values = LinkedHashMap<String, Long>()
  private val countersFile = File(dir, COUNTERS)
  private val sharesFile = File(dir, SHARES)

  /** Ci sono numeri in memoria non ancora scritti sul file (solo con la scrittura differita). */
  private var dirty = false
  private var scheduled = false

  /** Quante volte il file dei numeri e' stato riscritto (per provare che una lettura misurata non lo riscrive ogni volta, LA-8). */
  var writes: Int = 0
    private set

  private val flusher by lazy {
    Executors.newSingleThreadScheduledExecutor { runnable -> Thread(runnable, "field-probe-store").apply { isDaemon = true } }
  }

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

  /**
   * Scrive [value] sotto [key] **solo se non c'e' gia'** fra le righe indicizzate di [key]: un valore per ogni cosa diversa vista (LA-8, i chiamanti
   * di T-14: prima `docs_caller` aveva una riga sola e ogni app nuova cancellava la precedente).
   */
  fun recordDistinct(key: FieldProbeKey, value: Long) {
    synchronized(lock) {
      val prefix = key.code + "."
      if (values.entries.any { it.key.startsWith(prefix) && it.value == value }) return
      values[FieldProbeKey.lineKey(key, nextIndex(key))] = value
      save()
    }
  }

  /** Il resoconto: `chiave=valore`, una per riga, in ordine di chiave. */
  fun render(): String = FieldProbeReport.render(synchronized(lock) { values.toMap() })

  /**
   * Azzera le **misure** (non gli indirizzi delle condivisioni: servono ancora a T-15, ne' lo stato delle prove a piu' tempi, [saveSession]).
   * Le **scelte** della schermata (forma, latenza, numero di file, modo di T-15, [CHOICES]) restano: non sono misure, la schermata le mostra
   * ancora e provider e activity le leggono da qui; azzerate qui, la schermata diceva una forma e i componenti ne usavano un'altra (LA-6).
   */
  fun resetNumbers() {
    synchronized(lock) {
      val kept = CHOICES.mapNotNull { key -> values[key.code]?.let { key.code to it } }
      values.clear()
      kept.forEach { (code, value) -> values[code] = value }
      save()
    }
  }

  /**
   * Scrive le scelte della schermata che non sono state ancora fatte, con il valore di partenza (quello che provider e activity usano senza una
   * scelta). Cosi' il resoconto dice sempre quale forma, latenza e numero di file sono stati usati, anche senza un tocco su un chip (LA-6).
   */
  fun seedChoices() {
    synchronized(lock) {
      var changed = false
      for ((key, default) in CHOICE_DEFAULTS) {
        if (key.code !in values) {
          values[key.code] = default
          changed = true
        }
      }
      if (changed) save()
    }
  }

  // --- Lo stato delle prove a piu' tempi (LA-7) -----------------------------------------------------------------------------------

  /**
   * Salva lo stato di una prova che si fa in piu' passaggi ([name] e' `[a-z0-9_]+`): la fotografia di riferimento di T-09 e T-16, le righe
   * cestinate, la copia in modifica. Sono **numeri e nomi di file di prova** (`_ID`, dimensioni, date), su un file privato accanto ai numeri e
   * **mai nel resoconto**. Servono perche' T-09 e T-16 mandano in Samsung Gallery: Android puo' chiudere l'app, o la schermata ricrearsi, e al
   * ritorno "Confronta" non trovava piu' il riferimento.
   */
  fun saveSession(name: String, lines: List<String>) {
    synchronized(lock) {
      val file = sessionFile(name)
      if (lines.isEmpty()) {
        file.delete()
        return
      }
      val temp = File(dir, file.name + ".tmp")
      temp.writeText(lines.joinToString("\n") + "\n")
      if (!temp.renameTo(file)) {
        file.delete()
        temp.renameTo(file)
      }
    }
  }

  /** Lo stato salvato di [name], o null se non c'e'. */
  fun loadSession(name: String): List<String>? = synchronized(lock) {
    sessionFile(name).takeIf { it.isFile }?.readLines()?.filter { it.isNotEmpty() }
  }

  private fun sessionFile(name: String): File {
    require(SESSION_NAME.matches(name)) { "Nome dello stato non valido." }
    return File(dir, "session-$name.txt")
  }

  /** Scrive subito quello che e' ancora in memoria (la scrittura differita lo fa da sola dopo [FLUSH_DELAY_MS]). */
  fun flush() {
    synchronized(lock) {
      scheduled = false
      if (dirty) {
        write()
        dirty = false
      }
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

  /**
   * Chi ha cambiato un numero chiama questo, sotto [lock]. Con la scrittura **differita** (lo store del processo) il file si riscrive una volta
   * sola ogni [FLUSH_DELAY_MS], fuori dal thread di chi misura: una lettura di T-14 cambiava fino a quattro numeri e riscriveva il file ogni volta,
   * proprio dove si misurano i tempi (LA-8). Nei test (`forTest`) si scrive subito, come prima.
   */
  private fun save() {
    if (!deferred) {
      write()
      return
    }
    dirty = true
    if (scheduled) return
    scheduled = true
    flusher.schedule({ flush() }, FLUSH_DELAY_MS, TimeUnit.MILLISECONDS)
  }

  private fun write() {
    val properties = Properties()
    values.forEach { (k, v) -> properties.setProperty(k, v.toString()) }
    val temp = File(dir, "$COUNTERS.tmp")
    temp.outputStream().use { properties.store(it, null) }
    if (!temp.renameTo(countersFile)) {
      countersFile.delete()
      temp.renameTo(countersFile)
    }
    writes++
  }

  companion object {
    private const val COUNTERS = "counters.properties"
    private const val SHARES = "t15-uris.txt"
    private val SESSION_NAME = Regex("^[a-z0-9_]{1,32}$")

    /** Dopo quanti ms dall'ultimo cambiamento il file dei numeri si riscrive (scrittura differita). Abbastanza poco perche' una morte del processo non lo perda. */
    private const val FLUSH_DELAY_MS = 150L

    /** Le scelte della schermata di T-14 e T-15: configurazione della prova, non misure (restano all'azzeramento). */
    private val CHOICES: List<FieldProbeKey> = listOf(
      FieldProbeKey.T14_FORM,
      FieldProbeKey.T14_LATENCY_MS,
      FieldProbeKey.T14_COUNT,
      FieldProbeKey.T15_CLOSED_FIRST,
    )

    /** I valori di partenza delle scelte: gli stessi che provider e activity usano quando non ce n'e' una (`FieldProbeForm.of(null)`, 0 ms, 1 file, legge subito). */
    private val CHOICE_DEFAULTS: List<Pair<FieldProbeKey, Long>> = listOf(
      FieldProbeKey.T14_FORM to FieldProbeForm.of(null).code,
      FieldProbeKey.T14_LATENCY_MS to 0L,
      FieldProbeKey.T14_COUNT to 1L,
      FieldProbeKey.T15_CLOSED_FIRST to 0L,
    )

    /** Un numero che cambia a ogni avvio del processo: la sua identita' per T-15 (il processo e' ripartito dalla ricezione?). */
    private val PROCESS_MARKER: Long = System.nanoTime()

    @Volatile
    private var instance: FieldProbeStore? = null

    /** Lo store del processo, nella cartella privata `noBackupFilesDir/field-probe/`. */
    fun of(context: Context): FieldProbeStore =
      instance ?: synchronized(this) {
        instance ?: FieldProbeStore(File(context.applicationContext.noBackupFilesDir, "field-probe"), deferred = true).also { instance = it }
      }

    /** Per i test: uno store in una cartella scelta, non condiviso, che scrive subito. */
    fun forTest(dir: File): FieldProbeStore = FieldProbeStore(dir, deferred = false)

    /** Per i test della scrittura differita: come quello del processo, in una cartella scelta. */
    fun forTestDeferred(dir: File): FieldProbeStore = FieldProbeStore(dir, deferred = true)
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
