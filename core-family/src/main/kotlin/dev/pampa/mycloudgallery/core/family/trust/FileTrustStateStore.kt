package dev.pampa.mycloudgallery.core.family.trust

import dev.pampa.mycloudgallery.core.family.contract.TrustSnapshot
import dev.pampa.mycloudgallery.core.family.contract.TrustStateStore
import dev.pampa.mycloudgallery.core.trust.TrustLimits
import dev.pampa.mycloudgallery.core.trust.data.HeadMark
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Lo stato fidato del registro in un file locale (contratto P0, implementazione P3): va in
 * `noBackupFilesDir` (escluso da backup e trasferimenti). E' la memoria dell'anti-rollback: i byte firmati
 * dell'ultimo registro accettato, l'impronta di chi l'ha firmato, l'ancora e i segni delle serie lette.
 *
 * Scrittura atomica (file temporaneo poi rinomina con sostituzione): un processo che muore a meta' lascia il
 * file vecchio o quello nuovo, mai uno a meta'. Un file illeggibile vale "nessuno stato" (si lavora chiusi:
 * `TrustGuard` risponde "non configurato", mai "approvato"). Il formato e' locale e versionato (`MCGT` 1).
 */
class FileTrustStateStore(
  private val file: File,
  private val io: CoroutineDispatcher = Dispatchers.IO,
) : TrustStateStore {

  private val mutex = Mutex()

  override suspend fun load(): TrustSnapshot? = withContext(io) {
    mutex.withLock {
      if (!file.exists()) return@withLock null
      try {
        decode(file.readBytes())
      } catch (e: IOException) {
        null
      } catch (e: RuntimeException) {
        null
      }
    }
  }

  override suspend fun save(snapshot: TrustSnapshot) = withContext(io) {
    mutex.withLock { writeAtomically(file, encode(snapshot)) }
  }

  override suspend fun clear() = withContext(io) {
    mutex.withLock {
      file.delete()
      Unit
    }
  }

  internal companion object {
    private val MAGIC = byteArrayOf('M'.code.toByte(), 'C'.code.toByte(), 'G'.code.toByte(), 'T'.code.toByte())
    private const val VERSION = 1
    private const val MAX_HEADS = 4096
    private const val MAX_REGISTRY_BYTES = TrustLimits.MAX_REGISTRY_PAYLOAD + 1024

    fun encode(s: TrustSnapshot): ByteArray {
      val out = ByteArrayOutputStream()
      DataOutputStream(out).use { d ->
        d.write(MAGIC)
        d.writeByte(VERSION)
        d.writeUTF(s.familyId)
        d.writeUTF(s.familyKeyFingerprint)
        d.writeUTF(s.signerFingerprint)
        d.writeLong(s.minRegistryVersion)
        val registry = s.registryBytes
        d.writeInt(registry.size)
        d.write(registry)
        d.writeInt(s.ownHeads.size)
        for ((key, mark) in s.ownHeads.toSortedMap()) {
          d.writeUTF(key)
          d.writeLong(mark.version)
          d.writeUTF(mark.contentHash)
        }
      }
      return out.toByteArray()
    }

    fun decode(bytes: ByteArray): TrustSnapshot? {
      val d = DataInputStream(ByteArrayInputStream(bytes))
      val magic = ByteArray(MAGIC.size).also { d.readFully(it) }
      if (!magic.contentEquals(MAGIC) || d.readUnsignedByte() != VERSION) return null
      val familyId = d.readUTF()
      val familyKeyFingerprint = d.readUTF()
      val signerFingerprint = d.readUTF()
      val minVersion = d.readLong()
      val registrySize = d.readInt()
      if (registrySize !in 1..MAX_REGISTRY_BYTES) return null
      val registry = ByteArray(registrySize).also { d.readFully(it) }
      val headCount = d.readInt()
      if (headCount !in 0..MAX_HEADS) return null
      val heads = LinkedHashMap<String, HeadMark>()
      repeat(headCount) { heads[d.readUTF()] = HeadMark(d.readLong(), d.readUTF()) }
      if (d.read() != -1) return null
      return TrustSnapshot(registry, signerFingerprint, familyId, familyKeyFingerprint, minVersion, heads)
    }
  }
}

/** Scrive [bytes] in [target] passando da un temporaneo nella stessa cartella e una rinomina atomica. */
internal fun writeAtomically(target: File, bytes: ByteArray) {
  target.parentFile?.mkdirs()
  val tmp = File(target.parentFile, target.name + ".tmp")
  tmp.writeBytes(bytes)
  try {
    Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
  } catch (e: IOException) {
    tmp.delete()
    throw e
  }
}
