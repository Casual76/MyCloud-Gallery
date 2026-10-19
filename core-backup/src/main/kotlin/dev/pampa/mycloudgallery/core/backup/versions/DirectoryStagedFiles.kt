package dev.pampa.mycloudgallery.core.backup.versions

import android.media.MediaDataSource
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.Sha256
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.UUID

/**
 * I file locali delle versioni in una cartella dell'app ([root]: `noBackupFilesDir/versions/`, P14): le copie di lavoro (`work-<caso>`) e i
 * file preparati, chiamati con il loro SHA-256 (`<64 cifre>`), mai con un nome dell'utente. Una copia preparata si rende tale con una
 * rinomina nella stessa cartella ([keep]): o c'e' intera o non c'e'. Nessuna rete, nessun orologio.
 */
class DirectoryStagedFiles(private val root: File) : StagedVersionFiles {

  private fun ensureRoot(): File {
    if (!root.isDirectory && !root.mkdirs() && !root.isDirectory) throw IOException("Cartella delle versioni non creata.")
    return root
  }

  override fun newWorkFile(): File = File(ensureRoot(), "work-" + UUID.randomUUID().toString())

  override fun keep(file: File, sha256: Sha256, mediaId: MediaId): File {
    val target = File(ensureRoot(), sha256.hex)
    // Prima la nota (a chi appartiene), poi il file: un file preparato senza nota non si ritroverebbe piu'.
    val note = File(root, sha256.hex + NOTE)
    if (!note.isFile) {
      val draft = File(root, sha256.hex + NOTE + ".work")
      draft.writeText(mediaId.value)
      if (!draft.renameTo(note) && !note.isFile) throw IOException("Nota del file preparato non salvata.")
    }
    if (target.isFile) {
      // Gia' preparato (una ripresa): la copia di lavoro non serve piu'.
      if (file != target) file.delete()
      return target
    }
    if (!file.renameTo(target)) throw IOException("File preparato non salvato.")
    return target
  }

  override fun file(sha256: Sha256): File? = File(root, sha256.hex).takeIf { it.isFile }

  override fun open(sha256: Sha256): StagedBytes? {
    val file = file(sha256) ?: return null
    return try {
      FileStagedBytes(file)
    } catch (_: IOException) {
      null
    }
  }

  override fun pending(): List<StagedEntry> = root.listFiles()?.filter { it.isFile && it.name.endsWith(NOTE) }?.mapNotNull { note ->
    val hex = note.name.removeSuffix(NOTE)
    val sha = try {
      Sha256(hex)
    } catch (_: IllegalArgumentException) {
      return@mapNotNull null
    }
    val file = File(root, hex)
    val media = try {
      MediaId(note.readText().trim())
    } catch (_: Exception) {
      return@mapNotNull null
    }
    // La nota senza il file (la morte e' arrivata fra le due, o una correzione la sta scrivendo adesso): non e' ancora un file preparato.
    if (!file.isFile) return@mapNotNull null
    StagedEntry(sha, media, file.length())
  }.orEmpty()

  override fun discard(sha256: Sha256) {
    File(root, sha256.hex).delete()
    File(root, sha256.hex + NOTE).delete()
  }

  private companion object {
    /** La nota di un file preparato: `<sha>.media`, con l'id del media. */
    const val NOTE = ".media"
  }

  /**
   * All'avvio, prima di ogni correzione (P14): toglie le copie di lavoro e le note rimaste senza file (un processo morto a meta' di una
   * correzione). I file preparati restano: li riprende il motore delle versioni.
   */
  fun dropLeftoverWork() {
    val files = root.listFiles() ?: return
    for (f in files) {
      if (!f.isFile) continue
      val orphanNote = f.name.endsWith(NOTE) && !File(root, f.name.removeSuffix(NOTE)).isFile
      if (f.name.startsWith("work-") || f.name.endsWith(".work") || orphanNote) f.delete()
    }
  }
}

/** I byte di un file locale a intervalli. */
internal class FileStagedBytes(file: File) : StagedBytes {
  private val raf = RandomAccessFile(file, "r")
  private val lock = Any()

  override val size: Long = raf.length()

  override suspend fun read(range: LongRange): ByteArray = synchronized(lock) {
    if (range.isEmpty() || range.first >= size || range.last < 0) return ByteArray(0)
    val from = maxOf(range.first, 0L)
    val to = minOf(range.last, size - 1)
    val out = ByteArray((to - from + 1).toInt())
    raf.seek(from)
    raf.readFully(out)
    out
  }

  override fun dataSource(): MediaDataSource = object : MediaDataSource() {
    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int = synchronized(lock) {
      if (position >= this@FileStagedBytes.size) return -1
      val n = minOf(size.toLong(), this@FileStagedBytes.size - position).toInt()
      raf.seek(position)
      raf.readFully(buffer, offset, n)
      n
    }

    override fun getSize(): Long = this@FileStagedBytes.size

    override fun close() = Unit
  }

  override fun close() = raf.close()
}
