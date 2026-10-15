package dev.pampa.mycloudgallery.core.nasops.store

import dev.pampa.mycloudgallery.core.nasops.contract.TrashManifest
import dev.pampa.mycloudgallery.core.trust.crypto.TrustIds
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.util.zip.CRC32

/**
 * Gli inventari delle cartelle cestinate (R3 della revisione 4B) su file, accanto al diario Room: uno per voce in una cartella di
 * `noBackupFilesDir` scelta da `:app` (fuori dal backup, tolta dall'azzeramento). Solo numeri (identita', dimensioni, date): nessun nome
 * ne' percorso. Scritti su un temporaneo e poi rinominati, con un CRC: un file rovinato vale "nessun inventario", cioe' la cartella non si
 * svuota da sola (mai il contrario).
 */
internal class TrashManifestFiles(private val dir: File) {

  private fun fileOf(trashId: String): File {
    require(TrustIds.isValid(trashId)) { "Id di cestino non valido." }
    return File(dir, "$trashId$SUFFIX")
  }

  fun write(trashId: String, manifest: TrashManifest) {
    val target = fileOf(trashId)
    dir.mkdirs()
    val temp = File(dir, target.name + ".tmp")
    temp.writeBytes(encode(manifest))
    if (!temp.renameTo(target)) {
      target.delete()
      if (!temp.renameTo(target)) throw IOException("inventario del cestino non salvato")
    }
  }

  fun read(trashId: String): TrashManifest? {
    val file = fileOf(trashId)
    if (!file.isFile) return null
    return try {
      decode(file.readBytes())
    } catch (e: IOException) {
      null
    }
  }

  fun remove(trashId: String) {
    fileOf(trashId).delete()
  }

  companion object {
    private const val SUFFIX = ".mcgtm"
    private const val MAGIC = "MCGTM1"
    private const val NO_ID = Long.MIN_VALUE

    /** Oltre questi numeri il file non e' un inventario nostro. */
    private const val MAX_ENTRIES = 2_000_000

    fun encode(manifest: TrashManifest): ByteArray {
      val bytes = ByteArrayOutputStream()
      DataOutputStream(bytes).use { out ->
        out.writeBytes(MAGIC)
        out.writeLong(manifest.rootFileId ?: NO_ID)
        out.writeInt(manifest.folders.size)
        manifest.folders.sorted().forEach { out.writeLong(it) }
        out.writeInt(manifest.files.size)
        manifest.files.entries.sortedBy { it.key }.forEach { (id, file) ->
          out.writeLong(id)
          out.writeLong(file.size)
          out.writeLong(file.modifiedAt)
        }
      }
      val body = bytes.toByteArray()
      val crc = CRC32().apply { update(body) }.value
      return body + ByteArrayOutputStream().also { DataOutputStream(it).use { o -> o.writeLong(crc) } }.toByteArray()
    }

    fun decode(raw: ByteArray): TrashManifest? {
      if (raw.size < MAGIC.length + Long.SIZE_BYTES * 2 + Int.SIZE_BYTES * 2) return null
      val body = raw.copyOf(raw.size - Long.SIZE_BYTES)
      val stored = DataInputStream(ByteArrayInputStream(raw, raw.size - Long.SIZE_BYTES, Long.SIZE_BYTES)).readLong()
      if (CRC32().apply { update(body) }.value != stored) return null
      DataInputStream(ByteArrayInputStream(body)).use { input ->
        val magic = ByteArray(MAGIC.length).also { input.readFully(it) }
        if (String(magic, Charsets.US_ASCII) != MAGIC) return null
        val root = input.readLong().takeIf { it != NO_ID }
        val folderCount = input.readInt()
        if (folderCount !in 0..MAX_ENTRIES) return null
        val folders = HashSet<Long>(folderCount * 2)
        repeat(folderCount) { folders += input.readLong() }
        val fileCount = input.readInt()
        if (fileCount !in 0..MAX_ENTRIES) return null
        val files = HashMap<Long, TrashManifest.ManifestFile>(fileCount * 2)
        repeat(fileCount) {
          val id = input.readLong()
          val size = input.readLong()
          val modified = input.readLong()
          if (size < 0) return null
          files[id] = TrashManifest.ManifestFile(size, modified)
        }
        if (input.available() != 0) return null
        return TrashManifest(root, folders, files)
      }
    }
  }
}
