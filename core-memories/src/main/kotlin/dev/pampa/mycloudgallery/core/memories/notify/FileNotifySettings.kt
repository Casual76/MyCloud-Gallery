package dev.pampa.mycloudgallery.core.memories.notify

import dev.pampa.mycloudgallery.core.common.files.StateFiles
import dev.pampa.mycloudgallery.core.memories.contract.LockScreenMode
import dev.pampa.mycloudgallery.core.memories.contract.NotifyFrequency
import dev.pampa.mycloudgallery.core.memories.contract.NotifyPrefs
import dev.pampa.mycloudgallery.core.memories.contract.NotifySettings
import java.io.File
import java.io.IOException
import java.time.LocalTime
import java.util.Locale
import java.util.zip.CRC32
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal object StateFilesPrefsIo : LedgerIo {
  override fun read(file: File): ByteArray? = StateFiles.readBytes(file, LABEL, maxBytes = NotifyPrefsCodec.MAX_BYTES.toLong() + 1)

  override fun write(file: File, bytes: ByteArray) = StateFiles.writeBytes(file, bytes, LABEL)

  override fun delete(file: File): Boolean = StateFiles.delete(file, LABEL)

  private const val LABEL = "notify-prefs"
}

/**
 * Il formato delle preferenze delle notifiche (D10): testo ASCII, marcatore, quattro righe `chiave valore` e il CRC-32 di tutto cio' che precede. Un file che non
 * torna (CRC, righe, valori fuori scelta, ora non a passi di 15 minuti) vale "non c'e'": si riparte dalle scelte di serie, che sono le piu' riservate.
 */
internal object NotifyPrefsCodec {
  const val MAX_BYTES: Int = 512
  private const val MAGIC = "MCGS1"

  fun encode(prefs: NotifyPrefs): ByteArray {
    val body = "$MAGIC\nenabled ${if (prefs.enabled) 1 else 0}\nhour ${String.format(Locale.ROOT, "%02d:%02d", prefs.hour.hour, prefs.hour.minute)}\n" +
      "frequency ${prefs.frequency.code}\nlock ${prefs.lockScreen.code}\n"
    val crc = CRC32().apply { update(body.toByteArray(Charsets.US_ASCII)) }.value
    return (body + "crc " + String.format(Locale.ROOT, "%08x", crc) + "\n").toByteArray(Charsets.US_ASCII)
  }

  /** Le preferenze, o null se il file non torna. */
  fun decode(bytes: ByteArray): NotifyPrefs? {
    if (bytes.size > MAX_BYTES || bytes.any { it < 0 || it > 126 }) return null
    val text = bytes.toString(Charsets.US_ASCII)
    if (!text.endsWith("\n")) return null
    val lines = text.dropLast(1).split('\n')
    if (lines.size != 6 || lines[0] != MAGIC || !lines[5].startsWith("crc ")) return null
    val crcText = lines[5].removePrefix("crc ")
    if (crcText.length != 8 || crcText.any { it !in '0'..'9' && it !in 'a'..'f' }) return null
    val body = text.substring(0, text.length - 1 - lines[5].length)
    if (CRC32().apply { update(body.toByteArray(Charsets.US_ASCII)) }.value != crcText.toLong(16)) return null
    fun value(index: Int, key: String): String? = lines[index].takeIf { it.startsWith("$key ") }?.removePrefix("$key ")
    val enabled = when (value(1, "enabled")) {
      "1" -> true
      "0" -> false
      else -> return null
    }
    val hour = value(2, "hour")?.let(::parseHour) ?: return null
    val frequency = value(3, "frequency")?.let { NotifyFrequency.ofCode(it) } ?: return null
    val lock = value(4, "lock")?.let { LockScreenMode.ofCode(it) } ?: return null
    return try {
      NotifyPrefs(enabled, hour, frequency, lock)
    } catch (_: IllegalArgumentException) {
      null
    }
  }

  private fun parseHour(text: String): LocalTime? {
    val parts = text.split(':')
    if (parts.size != 2 || parts.any { it.length != 2 }) return null
    val hour = parts[0].toIntOrNull() ?: return null
    val minute = parts[1].toIntOrNull() ?: return null
    return if (hour in 0..23 && minute in 0..59) LocalTime.of(hour, minute) else null
  }
}

/**
 * Le preferenze delle notifiche di QUESTO dispositivo e profilo (docs/fase9-progetto.md D10, D15, E-NT10): accese, ora, frequenza, schermata di blocco. **Locali**:
 * `noBackupFilesDir/memories/notify-prefs`, mai sul NAS, mai nei dati personali (un tablet condiviso ha le sue notifiche per profilo). Si leggono **subito**, alla
 * costruzione (un file di poche decine di byte), cosi' la pagina delle impostazioni e il lavoro delle notifiche partono dalle scelte vere; si scrivono in modo atomico
 * (`StateFiles`) e il flusso cambia solo a scrittura riuscita (un errore lancia: la pagina dice "non riuscito" e resta com'era).
 *
 * Senza file, o con un file che non torna, valgono le scelte di serie: accese, 19:00, 2-3 a settimana (Q4), schermata di blocco generica (Q5 (a)). Nel `toString()` niente ora.
 */
class FileNotifySettings internal constructor(
  private val directory: File,
  private val io: LedgerIo,
  private val dispatcher: CoroutineDispatcher,
) : NotifySettings {

  /** [directory] e' `noBackupFilesDir/memories`: il file e' `notify-prefs`. */
  constructor(directory: File, dispatcher: CoroutineDispatcher = Dispatchers.IO) : this(directory, StateFilesPrefsIo, dispatcher)

  private val lock = Mutex()
  private val target: File get() = File(directory, FILE_NAME)
  private val flow = MutableStateFlow(readNow())

  override val prefs: StateFlow<NotifyPrefs> = flow

  override suspend fun set(prefs: NotifyPrefs) {
    withContext(dispatcher) {
      lock.withLock {
        io.write(target, NotifyPrefsCodec.encode(prefs))
        flow.value = prefs
      }
    }
  }

  /** Toglie il file (azzeramento): si torna alle scelte di serie. Se non ci riesce lo dice: `IOException`. */
  suspend fun clear() {
    withContext(dispatcher) {
      lock.withLock {
        if (!io.delete(target)) throw IOException("preferenze delle notifiche non tolte")
        flow.value = NotifyPrefs()
      }
    }
  }

  private fun readNow(): NotifyPrefs = try {
    io.read(target)?.let { NotifyPrefsCodec.decode(it) } ?: NotifyPrefs()
  } catch (_: IOException) {
    NotifyPrefs()
  }

  companion object {
    const val FILE_NAME: String = "notify-prefs"
  }
}
