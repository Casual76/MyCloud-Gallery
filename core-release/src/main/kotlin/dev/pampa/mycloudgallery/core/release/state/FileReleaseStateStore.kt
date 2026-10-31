package dev.pampa.mycloudgallery.core.release.state

import dev.pampa.mycloudgallery.core.common.files.StateFiles
import dev.pampa.mycloudgallery.core.release.codec.CodecException
import dev.pampa.mycloudgallery.core.release.codec.JInt
import dev.pampa.mycloudgallery.core.release.codec.JObj
import dev.pampa.mycloudgallery.core.release.codec.JStr
import dev.pampa.mycloudgallery.core.release.codec.JValue
import dev.pampa.mycloudgallery.core.release.codec.ManifestCodec
import dev.pampa.mycloudgallery.core.release.codec.ReleaseJson
import dev.pampa.mycloudgallery.core.release.contract.ReleaseMessage
import dev.pampa.mycloudgallery.core.release.contract.ReleaseStateStore
import dev.pampa.mycloudgallery.core.release.contract.ReleaseStatus
import dev.pampa.mycloudgallery.core.release.contract.StoredRelease
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.util.zip.CRC32
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** I punti di un salvataggio dove il processo puo' morire (la prova della morte a ogni salvataggio, §4.3 "Stabilita'"). */
internal enum class SavePoint { BEFORE_TEMP, HALF_TEMP, BEFORE_RENAME, AFTER_RENAME }

/**
 * Lo stato del manifest su file (D30, §1.2 "Porte"; P1): `<dir>/release-state.mcgrs`, in `noBackupFilesDir/release/` (lo sceglie :app).
 * Scrittura **atomica**: temporaneo `release-state.mcgrs.tmp` scritto e forzato su disco, poi rinomina con `ATOMIC_MOVE` (`StateFiles.replace`):
 * chi legge vede lo stato di prima intero o quello nuovo intero. Il file porta una somma di controllo (CRC32) e una versione: un file troncato,
 * rovinato o di una versione sconosciuta si legge come **assente** (`null`), mai un crollo; il temporaneo rimasto da una morte si ignora e si
 * sovrascrive al salvataggio dopo.
 *
 * Formato: `MCGRS1` ‖ lunghezza (4 byte) ‖ JSON canonico dello stato (`seq`, `versionCode`, `status`, `message`, `keyId`) ‖ lunghezza (4 byte,
 * 0 senza manifest) ‖ JSON canonico del manifest (lo stesso della busta, gia' verificato quando e' stato accettato) ‖ CRC32 (4 byte) di tutto
 * cio' che precede. La firma non si salva: il file e' privato dell'app e la busta si riverificherebbe con chiavi che un aggiornamento puo'
 * cambiare.
 */
class FileReleaseStateStore internal constructor(
  dir: File,
  private val io: CoroutineDispatcher,
  private val faults: (SavePoint) -> Unit,
) : ReleaseStateStore {

  constructor(dir: File) : this(dir, Dispatchers.IO, {})

  private val file = File(dir, FILE_NAME)
  private val temp = File(dir, "$FILE_NAME.tmp")

  override suspend fun read(): StoredRelease? = withContext(io) {
    val bytes = try {
      StateFiles.readBytes(file, maxBytes = MAX_FILE_BYTES.toLong())
    } catch (e: IOException) {
      null
    }
    bytes?.let(StoredReleaseCodec::decode)
  }

  override suspend fun write(state: StoredRelease) = withContext(io) {
    val bytes = StoredReleaseCodec.encode(state)
    faults(SavePoint.BEFORE_TEMP)
    file.parentFile?.mkdirs()
    FileChannel.open(temp.toPath(), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE).use { channel ->
      val half = bytes.size / 2
      writeFully(channel, ByteBuffer.wrap(bytes, 0, half))
      faults(SavePoint.HALF_TEMP)
      writeFully(channel, ByteBuffer.wrap(bytes, half, bytes.size - half))
      channel.force(true)
    }
    faults(SavePoint.BEFORE_RENAME)
    StateFiles.replace(temp, file, label = FILE_NAME)
    faults(SavePoint.AFTER_RENAME)
  }

  override suspend fun clear() = withContext(io) {
    if (!StateFiles.delete(file, label = FILE_NAME)) throw IOException("stato del manifest non tolto")
  }

  private fun writeFully(channel: FileChannel, buffer: ByteBuffer) {
    while (buffer.hasRemaining()) channel.write(buffer)
  }

  override fun toString(): String = "FileReleaseStateStore"

  companion object {
    const val FILE_NAME: String = "release-state.mcgrs"
    internal const val MAX_FILE_BYTES: Int = 256 * 1024
  }
}

/** La codifica del file di stato (vedi [FileReleaseStateStore]). */
internal object StoredReleaseCodec {
  private val MAGIC = "MCGRS1".toByteArray(Charsets.US_ASCII)

  fun encode(state: StoredRelease): ByteArray {
    val meta = linkedMapOf<String, JValue>(
      "seq" to JInt(state.seq),
      "versionCode" to JInt(state.versionCode.toLong()),
      "status" to JStr(kindOf(state.status)),
    )
    messageOf(state.status)?.let { meta["message"] = JStr(it.code) }
    state.keyId?.let { meta["keyId"] = JInt(it.toLong()) }
    val metaBytes = ReleaseJson.encode(JObj(meta))
    val manifestBytes = state.manifest?.let(ManifestCodec::canonical) ?: ByteArray(0)
    val body = ByteBuffer.allocate(MAGIC.size + 4 + metaBytes.size + 4 + manifestBytes.size)
      .put(MAGIC).putInt(metaBytes.size).put(metaBytes).putInt(manifestBytes.size).put(manifestBytes).array()
    val crc = CRC32().apply { update(body) }.value.toInt()
    return body + ByteBuffer.allocate(4).putInt(crc).array()
  }

  /** Lo stato, o null se il file non e' intero, non e' di questa versione o non torna. */
  fun decode(bytes: ByteArray): StoredRelease? = try {
    decodeOrThrow(bytes)
  } catch (e: CodecException) {
    null
  } catch (e: RuntimeException) {
    null
  }

  private fun decodeOrThrow(bytes: ByteArray): StoredRelease? {
    if (bytes.size < MAGIC.size + 12 || bytes.size > FileReleaseStateStore.MAX_FILE_BYTES) return null
    val body = bytes.copyOfRange(0, bytes.size - 4)
    val crc = ByteBuffer.wrap(bytes, bytes.size - 4, 4).int
    if (CRC32().apply { update(body) }.value.toInt() != crc) return null
    if (!body.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) return null
    val buffer = ByteBuffer.wrap(body, MAGIC.size, body.size - MAGIC.size)
    val metaBytes = ByteArray(buffer.int.also { if (it < 2 || it > buffer.remaining()) return null }).also { buffer.get(it) }
    val manifestBytes = ByteArray(buffer.int.also { if (it < 0 || it != buffer.remaining()) return null }).also { buffer.get(it) }
    val meta = ReleaseJson.parseCanonical(metaBytes) as? JObj ?: return null
    val seq = (meta.members["seq"] as? JInt)?.value ?: return null
    val versionCode = (meta.members["versionCode"] as? JInt)?.value?.takeIf { it in 1..Int.MAX_VALUE.toLong() } ?: return null
    val keyId = meta.members["keyId"]?.let { (it as? JInt)?.value?.takeIf { v -> v in 0..255 } ?: return null }
    val message = meta.members["message"]?.let { m -> ReleaseMessage.entries.firstOrNull { it.code == (m as? JStr)?.value } ?: return null }
    val status = when ((meta.members["status"] as? JStr)?.value) {
      "normal" -> ReleaseStatus.Normal.takeIf { message == null }
      "readonly" -> message?.let { ReleaseStatus.ReadOnly(it) }
      "stopped" -> message?.let { ReleaseStatus.Stopped(it) }
      else -> null
    } ?: return null
    val manifest = if (manifestBytes.isEmpty()) {
      null
    } else {
      val root = ReleaseJson.parseCanonical(manifestBytes) as? JObj ?: return null
      ManifestCodec.fromJson(root, (root.members["app"] as? JStr)?.value ?: return null)
    }
    if ((manifest == null) != (keyId == null) || (manifest != null && manifest.seq != seq)) return null
    return StoredRelease(seq, manifest, keyId?.toInt(), versionCode.toInt(), status)
  }

  private fun kindOf(status: ReleaseStatus): String = when (status) {
    ReleaseStatus.Normal -> "normal"
    is ReleaseStatus.ReadOnly -> "readonly"
    is ReleaseStatus.Stopped -> "stopped"
  }

  private fun messageOf(status: ReleaseStatus): ReleaseMessage? = when (status) {
    ReleaseStatus.Normal -> null
    is ReleaseStatus.ReadOnly -> status.message
    is ReleaseStatus.Stopped -> status.message
  }
}
