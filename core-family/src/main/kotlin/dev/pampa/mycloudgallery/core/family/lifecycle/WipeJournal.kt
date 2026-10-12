package dev.pampa.mycloudgallery.core.family.lifecycle

import dev.pampa.mycloudgallery.core.family.contract.WipePhase
import dev.pampa.mycloudgallery.core.family.contract.WipeReason
import dev.pampa.mycloudgallery.core.family.trust.writeAtomically
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Il segno "azzeramento in corso" e l'ultima fase completata (§2.3, fase (0)).
 *
 * @property startedAt ora del dispositivo (ms) del segno (0).
 * @property finishedAt ora del dispositivo della fase (7): e' il `SetupState.Wiped(at)`.
 */
data class WipeRecord(val reason: WipeReason, val phase: WipePhase, val startedAt: Long, val finishedAt: Long?)

/**
 * Dove si conserva [WipeRecord] tra un avvio e l'altro. **Deve sopravvivere al `LocalDataEraser`**: e' il
 * file che fa riprendere l'azzeramento se il processo muore a meta' e che, alla fine, fa mostrare
 * "dispositivo azzerato" invece dell'onboarding. Si cancella solo quando l'utente riconfigura il dispositivo
 * ([DeviceSelfWipe.forgetCompletedWipe]).
 */
interface WipeJournal {
  suspend fun read(): WipeRecord?

  suspend fun write(record: WipeRecord)

  suspend fun clear()
}

class InMemoryWipeJournal(var record: WipeRecord? = null) : WipeJournal {
  override suspend fun read(): WipeRecord? = record

  override suspend fun write(record: WipeRecord) {
    this.record = record
  }

  override suspend fun clear() {
    record = null
  }
}

/**
 * Il diario dell'azzeramento in un file (`noBackupFilesDir`, nome [FILE_NAME]): una riga di testo, scritta in
 * modo atomico. `:app` esclude questo file da cio' che cancella il `LocalDataEraser`. Un file illeggibile vale
 * "azzeramento iniziato" (si riprende da capo: e' idempotente), mai "nessun azzeramento".
 */
class FileWipeJournal(private val file: File, private val io: CoroutineDispatcher = Dispatchers.IO) : WipeJournal {

  private val mutex = Mutex()

  override suspend fun read(): WipeRecord? = withContext(io) {
    mutex.withLock {
      if (!file.exists()) return@withLock null
      val text = try {
        file.readText(Charsets.US_ASCII)
      } catch (e: IOException) {
        return@withLock UNREADABLE
      }
      parse(text) ?: UNREADABLE
    }
  }

  override suspend fun write(record: WipeRecord) = withContext(io) {
    mutex.withLock {
      val line = listOf(VERSION, record.reason.name, record.phase.name, record.startedAt, record.finishedAt ?: -1).joinToString(";")
      writeAtomically(file, line.toByteArray(Charsets.US_ASCII))
    }
  }

  override suspend fun clear() = withContext(io) {
    mutex.withLock {
      file.delete()
      Unit
    }
  }

  companion object {
    const val FILE_NAME: String = "wipe-journal"
    private const val VERSION = "w1"

    /** Un diario che c'e' ma non si legge: si riparte dal segno (0), cioe' si rifa' tutto. */
    private val UNREADABLE = WipeRecord(WipeReason.MARKED_LOST, WipePhase.MARKED, 0L, null)

    internal fun parse(text: String): WipeRecord? {
      val parts = text.trim().split(';')
      if (parts.size != 5 || parts[0] != VERSION) return null
      val reason = WipeReason.entries.firstOrNull { it.name == parts[1] } ?: return null
      val phase = WipePhase.entries.firstOrNull { it.name == parts[2] } ?: return null
      val started = parts[3].toLongOrNull() ?: return null
      val finished = parts[4].toLongOrNull()?.takeIf { it >= 0 }
      return WipeRecord(reason, phase, started, finished)
    }
  }
}
