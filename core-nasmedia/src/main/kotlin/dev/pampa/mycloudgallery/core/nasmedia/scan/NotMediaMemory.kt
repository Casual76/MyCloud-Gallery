package dev.pampa.mycloudgallery.core.nasmedia.scan

import dev.pampa.mycloudgallery.core.common.log.GalleryLog
import dev.pampa.mycloudgallery.core.model.LocationKey
import dev.pampa.mycloudgallery.core.model.NasStamp
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * Dove si ricordano i file gia' riconosciuti "non media" (E-M12) con il timbro con cui sono stati letti: finche' dimensione e data di modifica non
 * cambiano non si rileggono a ogni giro (un `.jpg` che dentro e' testo costerebbe 128-192 KiB di lettura ogni volta).
 *
 * D-16 delle prove sul campo: la memoria stava solo nel processo ([InMemoryNotMediaMemory]), quindi dopo ogni avvio a freddo (apertura, lavoro
 * periodico, morte del processo) la verifica riapriva tutti i file non media: 61,7 MB e 87-91 secondi su 1.429 documenti, contro 38,6 s a caldo.
 * [FileNotMediaMemory] la tiene anche fra un processo e l'altro.
 *
 * La garanzia e' la stessa: un file il cui timbro e' cambiato **si rilegge**, e un file che e' tornato a essere un media se ne dimentica
 * ([forget]). Le implementazioni sono sicure fra thread.
 */
interface NotMediaMemory {
  /** Il file [key] e' gia' stato riconosciuto "non media" con questa dimensione e questa data di modifica? */
  fun matches(key: LocationKey, stamp: NasStamp): Boolean

  /** Il file [key] e' "non media" con il timbro [stamp]. */
  fun remember(key: LocationKey, stamp: NasStamp)

  /** Il file [key] non e' piu' "non media" (e' stato letto come media): il ricordo cade. */
  fun forget(key: LocationKey)

  /** Fine di una scansione: cio' che e' cambiato si scrive dove si tiene. Mai un'eccezione. */
  fun save()
}

/** Solo del processo, con un tetto: dopo un riavvio si rileggono una volta. Di serie, e per i test. */
class InMemoryNotMediaMemory(private val limit: Int = NotMediaMemoryLimits.MAX_ENTRIES) : NotMediaMemory {
  private val entries = object : LinkedHashMap<LocationKey, NasStamp>(64, 0.75f, false) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<LocationKey, NasStamp>?): Boolean = size > limit
  }

  override fun matches(key: LocationKey, stamp: NasStamp): Boolean = synchronized(entries) {
    entries[key]?.let { it.size == stamp.size && it.modifiedAtMillis == stamp.modifiedAtMillis } == true
  }

  override fun remember(key: LocationKey, stamp: NasStamp) {
    synchronized(entries) { entries[key] = stamp }
  }

  override fun forget(key: LocationKey) {
    synchronized(entries) { entries.remove(key) }
  }

  override fun save() = Unit
}

object NotMediaMemoryLimits {
  /** Quanti file non media si ricordano, al piu' (i piu' vecchi cadono per primi). */
  const val MAX_ENTRIES = 50_000
}

/**
 * La memoria dei "non media" **su un file**, fra un processo e l'altro (D-16). Una riga per file: l'impronta SHA-256 del suo identificativo
 * (account, share e percorso: nessun nome o percorso viene scritto), la dimensione e la data di modifica. Il file si scrive su un temporaneo e si
 * rinomina, solo se qualcosa e' cambiato ([save]); un file illeggibile, di un'altra versione o con una riga che non si capisce vale "non ricordo
 * niente": si rilegge una volta, mai il contrario.
 *
 * @param generation chi ha classificato: la versione dell'app, del sistema e del riconoscitore ([dev.pampa.mycloudgallery.core.nasmedia.detect.NasDetector.CLASSIFIER_REVISION]).
 *   Un'app piu' nuova potrebbe riconoscere un formato che prima non conosceva: con un'altra generazione i ricordi non valgono, e i file si
 *   rileggono una volta con il riconoscitore di adesso.
 */
class FileNotMediaMemory(
  private val file: File,
  private val generation: String,
  private val limit: Int = NotMediaMemoryLimits.MAX_ENTRIES,
) : NotMediaMemory {

  private class Stamp(val size: Long, val modifiedAtMillis: Long)

  private val lock = Any()
  private var entries: LinkedHashMap<String, Stamp>? = null
  private var dirty = false

  override fun matches(key: LocationKey, stamp: NasStamp): Boolean = synchronized(lock) {
    loaded()[digestOf(key)]?.let { it.size == stamp.size && it.modifiedAtMillis == stamp.modifiedAtMillis } == true
  }

  override fun remember(key: LocationKey, stamp: NasStamp) {
    synchronized(lock) {
      val map = loaded()
      val digest = digestOf(key)
      val known = map[digest]
      if (known != null && known.size == stamp.size && known.modifiedAtMillis == stamp.modifiedAtMillis) return
      map.remove(digest)
      map[digest] = Stamp(stamp.size, stamp.modifiedAtMillis)
      while (map.size > limit) map.remove(map.keys.first())
      dirty = true
    }
  }

  override fun forget(key: LocationKey) {
    synchronized(lock) {
      if (loaded().remove(digestOf(key)) != null) dirty = true
    }
  }

  override fun save() {
    synchronized(lock) {
      if (!dirty) return
      val map = entries ?: return
      try {
        if (map.isEmpty()) {
          file.delete()
        } else {
          file.parentFile?.mkdirs()
          val temp = File(file.parentFile, file.name + ".tmp")
          temp.bufferedWriter().use { out ->
            out.write(HEADER_PREFIX + generation)
            out.newLine()
            for ((digest, stamp) in map) {
              out.write("$digest ${stamp.size} ${stamp.modifiedAtMillis}")
              out.newLine()
            }
          }
          if (!temp.renameTo(file)) {
            file.delete()
            if (!temp.renameTo(file)) throw IOException("rinomina non riuscita")
          }
        }
        dirty = false
      } catch (e: IOException) {
        // Non e' grave: al processo dopo i file si rileggono, come prima di D-16.
        GalleryLog.w(TAG) { "ricordo dei non media non salvato: ${e.javaClass.simpleName}" }
      }
    }
  }

  /** Il contenuto, letto dal file la prima volta. Sempre sotto [lock]. */
  private fun loaded(): LinkedHashMap<String, Stamp> {
    entries?.let { return it }
    val map = LinkedHashMap<String, Stamp>()
    try {
      if (file.isFile) {
        val lines = file.readLines()
        if (lines.firstOrNull() == HEADER_PREFIX + generation) {
          for (line in lines.drop(1)) parse(line)?.let { (digest, stamp) -> map[digest] = stamp }
          while (map.size > limit) map.remove(map.keys.first())
        } else {
          // Un'altra generazione (o un file che non e' nostro): i ricordi non valgono, il file si riscrive al primo salvataggio.
          dirty = true
        }
      }
    } catch (e: IOException) {
      GalleryLog.w(TAG) { "ricordo dei non media non letto: ${e.javaClass.simpleName}" }
      map.clear()
      dirty = true
    }
    entries = map
    return map
  }

  private fun parse(line: String): Pair<String, Stamp>? {
    val parts = line.split(' ')
    if (parts.size != FIELDS) return null
    val digest = parts[0]
    if (digest.length != DIGEST_CHARS || !digest.all { it in '0'..'9' || it in 'a'..'f' }) return null
    val size = parts[1].toLongOrNull() ?: return null
    val modified = parts[2].toLongOrNull() ?: return null
    if (size < 0) return null
    return digest to Stamp(size, modified)
  }

  private fun digestOf(key: LocationKey): String {
    val bytes = MessageDigest.getInstance("SHA-256").digest(key.value.toByteArray(Charsets.UTF_8))
    return buildString(DIGEST_CHARS) { for (i in 0 until DIGEST_BYTES) append("%02x".format(bytes[i])) }
  }

  private companion object {
    const val TAG = "NotMediaMemory"
    const val HEADER_PREFIX = "mcg-notmedia 1 "
    const val FIELDS = 3
    const val DIGEST_BYTES = 16
    const val DIGEST_CHARS = DIGEST_BYTES * 2
  }
}
