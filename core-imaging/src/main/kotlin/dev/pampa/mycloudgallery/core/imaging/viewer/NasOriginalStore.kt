package dev.pampa.mycloudgallery.core.imaging.viewer

import android.system.ErrnoException
import android.system.OsConstants
import dev.pampa.mycloudgallery.core.model.NasStamp
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.SyncFailedException
import java.util.Locale

/**
 * I temporanei degli originali del NAS letti per il viewer (D19): un file per media, in una cartella che appartiene solo a
 * questo oggetto (di norma `cacheDir/viewer-nas`; il sistema puo' svuotarla quando vuole e il viewer lo regge).
 *
 * **Mai a meta'.** Un originale si scrive in `<nome>.part` e diventa `<nome>.orig` con una rinomina quando e' completo e
 * della dimensione dichiarata dal NAS: [get] non restituisce mai un file corto. I `.part` lasciati da un processo morto si
 * tolgono all'apertura della cartella. Un annullamento ([Writer.abort]) toglie il `.part`.
 *
 * **Nomi senza percorsi.** Il nome e' `<posizione>.<dimensione>.<data>.orig` (id della posizione e timbro): niente
 * nomi di file o di cartelle del NAS sul disco del telefono, nemmeno nel nome (§4.3, i percorsi sono dati sensibili).
 * Il timbro nel nome distingue le versioni: un file modificato dal PC non riusa il temporaneo vecchio, che esce per
 * ultimo dalla coda (il piu' vecchio per primo) o appena arriva quello nuovo.
 *
 * **Tetto.** [maxBytes] in tutto; oltre, escono i meno usati di recente (non quello appena scritto). Nessun orologio: l'ordine
 * d'uso e' quello delle chiamate.
 *
 * Thread-safe. Le funzioni toccano il disco: da chiamare fuori dal thread principale.
 */
class NasOriginalStore(
  private val dir: File,
  val maxBytes: Long = DEFAULT_MAX_BYTES,
  private val freeSpace: (File) -> Long = { it.usableSpace },
) {

  private class Entry(val file: File, val stamp: NasStamp)

  /** Per posizione, nell'ordine d'uso (il meno recente per primo). */
  private val entries = object : LinkedHashMap<Long, Entry>(16, 0.75f, true) {}
  private var total = 0L

  init {
    dir.mkdirs()
    for (file in dir.listFiles().orEmpty()) {
      when {
        file.name.endsWith(PART) -> file.delete()
        file.name.endsWith(DONE) -> parse(file)?.let { (id, entry) -> adopt(id, entry) }
      }
    }
  }

  /** Quanto occupano adesso i temporanei. */
  val totalBytes: Long get() = synchronized(this) { total }

  /** Quanti originali ci sono. */
  val count: Int get() = synchronized(this) { entries.size }

  /**
   * Il temporaneo completo della posizione [locationId], o null. Se [expected] (il timbro che il catalogo conosce) e' piu'
   * recente del temporaneo, o ne differisce a pari data, il file e' stato modificato dal PC dopo che lo abbiamo letto e il
   * temporaneo si butta. Se invece il temporaneo e' **piu' recente** del catalogo e' il catalogo a non aver ancora
   * rivisto il file: il temporaneo e' quello giusto e si tiene (altrimenti una scansione in ritardo farebbe rileggere
   * l'originale a ogni apertura). Un file sparito dal disco esce dall'indice.
   */
  fun get(locationId: Long, expected: NasStamp? = null): File? = synchronized(this) {
    val entry = entries[locationId] ?: return null
    if (!entry.file.isFile) {
      drop(locationId)
      return null
    }
    if (expected != null && isOlder(entry.stamp, expected)) {
      drop(locationId)
      return null
    }
    entry.file
  }

  /** Il temporaneo ([cached]) e' piu' vecchio di cio' che il catalogo sa del file ([known]), o a pari data e' un altro file. */
  private fun isOlder(cached: NasStamp, known: NasStamp): Boolean =
    cached.modifiedAtMillis < known.modifiedAtMillis ||
      (cached.modifiedAtMillis == known.modifiedAtMillis && !cached.sameAs(known))

  /**
   * Comincia a scrivere l'originale di [locationId] con timbro [stamp] e peso [size].
   *
   * @throws IOException se non c'e' posto: [size] piu' un margine di [SAFETY_MARGIN] oltre lo spazio libero, o la cartella
   *   non e' scrivibile. Il chiamante lo legge come "spazio esaurito" ([isNoSpace]).
   */
  fun begin(locationId: Long, stamp: NasStamp, size: Long): Writer {
    require(size >= 0) { "Dimensione negativa." }
    if (size > maxBytes) throw IOException("Originale oltre il tetto dei temporanei.")
    dir.mkdirs()
    if (freeSpace(dir) < size + SAFETY_MARGIN) throw IOException("ENOSPC: spazio insufficiente per l'originale.")
    synchronized(this) { trim(keep = locationId, room = size) }
    return Writer(locationId, stamp, size)
  }

  /** Toglie tutto (azzeramento dei dati locali, o la cache che il sistema ci chiede di liberare). */
  fun clear() {
    synchronized(this) {
      for (entry in entries.values) entry.file.delete()
      entries.clear()
      total = 0
      for (file in dir.listFiles().orEmpty()) if (file.name.endsWith(PART)) file.delete()
    }
  }

  /** La scrittura di un originale: [write] a blocchi, poi [commit] oppure [abort]. */
  inner class Writer internal constructor(
    private val locationId: Long,
    private val stamp: NasStamp,
    private val expectedSize: Long,
  ) : Closeable {

    private val part = File(dir, "${nameOf(locationId, stamp)}$PART")
    private var out: FileOutputStream? = FileOutputStream(part)
    private var written = 0L
    private var finished = false

    fun write(buffer: ByteArray, offset: Int, length: Int) {
      out!!.write(buffer, offset, length)
      written += length
    }

    /**
     * Chiude e rende il file un originale completo. Se i byte scritti non sono quelli dichiarati (il file e' cambiato
     * mentre si leggeva, o la lettura si e' fermata) non lo e': il `.part` si toglie e si lancia [IOException].
     */
    fun commit(): File {
      check(!finished) { "Scrittura gia' conclusa." }
      val stream = out!!
      try {
        stream.flush()
        try {
          stream.fd.sync()
        } catch (_: SyncFailedException) {
          // Non tutti i descrittori si sincronizzano: la chiusura basta.
        }
      } finally {
        stream.close()
        out = null
      }
      finished = true
      if (written != expectedSize) {
        part.delete()
        throw IOException("L'originale letto ($written B) non e' quello dichiarato ($expectedSize B).")
      }
      val done = File(dir, "${nameOf(locationId, stamp)}$DONE")
      if (!part.renameTo(done)) {
        part.delete()
        throw IOException("Il temporaneo non si e' potuto completare.")
      }
      synchronized(this@NasOriginalStore) {
        entries[locationId]?.let { drop(locationId) }
        adopt(locationId, Entry(done, stamp))
        trim(keep = locationId, room = 0)
      }
      return done
    }

    /** Annullata o fallita: il `.part` sparisce. Si puo' chiamare piu' volte e dopo [commit] non fa niente. */
    fun abort() {
      if (finished) return
      finished = true
      try {
        out?.close()
      } catch (_: IOException) {
        // Si sta gia' buttando via.
      } finally {
        out = null
        part.delete()
      }
    }

    override fun close() = abort()
  }

  // --- Interni (sempre sotto il lock) ---------------------------------------------------------------------------------

  private fun adopt(locationId: Long, entry: Entry) {
    entries[locationId]?.let { drop(locationId) }
    entries[locationId] = entry
    total += entry.file.length()
  }

  private fun drop(locationId: Long) {
    val old = entries.remove(locationId) ?: return
    total -= old.file.length()
    old.file.delete()
  }

  /** Libera posto finche' [total] + [room] sta nel tetto, dal meno usato; non tocca [keep]. */
  private fun trim(keep: Long, room: Long) {
    val iterator = entries.entries.iterator()
    while (total + room > maxBytes && iterator.hasNext()) {
      val (id, entry) = iterator.next()
      if (id == keep) continue
      iterator.remove()
      total -= entry.file.length()
      entry.file.delete()
    }
  }

  private fun parse(file: File): Pair<Long, Entry>? {
    val parts = file.name.removeSuffix(DONE).split('.')
    if (parts.size != 3) return null
    val id = parts[0].toLongOrNull() ?: return null
    val size = parts[1].toLongOrNull() ?: return null
    val modified = parts[2].toLongOrNull() ?: return null
    if (size < 0 || file.length() != size) {
      file.delete()
      return null
    }
    return id to Entry(file, NasStamp(size, modified))
  }

  companion object {
    /** 1 GiB di temporanei in tutto. */
    const val DEFAULT_MAX_BYTES: Long = 1L shl 30

    /** Lo spazio che deve restare libero oltre l'originale, perche' il telefono non resti senza respiro. */
    const val SAFETY_MARGIN: Long = 64L shl 20

    private const val PART = ".part"
    private const val DONE = ".orig"

    private fun nameOf(locationId: Long, stamp: NasStamp) = "$locationId.${stamp.size}.${stamp.modifiedAtMillis}"

    /** Vero se [e] (o una sua causa) e' "spazio esaurito": `ENOSPC`, o il testo del sistema. */
    fun isNoSpace(e: Throwable?): Boolean {
      var current = e
      var depth = 0
      while (current != null && depth++ < 6) {
        if (current is ErrnoException && current.errno == OsConstants.ENOSPC) return true
        if (current.message?.lowercase(Locale.ROOT)?.let { "enospc" in it || "no space left" in it } == true) return true
        current = current.cause
      }
      return false
    }
  }
}
