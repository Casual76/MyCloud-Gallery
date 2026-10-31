package dev.pampa.mycloudgallery.core.release.state

import dev.pampa.mycloudgallery.core.release.testing.FragileReleaseStore
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlinx.coroutines.Dispatchers

/** La morte del processo in un punto del salvataggio: dopo, non gira piu' niente di quel salvataggio (nessuna pulizia). */
internal class SimulatedDeath(val point: SavePoint) : IOException("morte simulata: $point")

/**
 * Il deposito vero su file ([FileReleaseStateStore]) con la morte comandata: [dieAt] fa morire il prossimo salvataggio nel punto scelto,
 * [dieDuringNextWrite] fra il temporaneo forzato su disco e la rinomina (il caso della suite), [corrupt] rovina un byte del file.
 */
internal class FragileFileStore(val dir: File) : FragileReleaseStore {
  @Volatile private var deathAt: SavePoint? = null
  var saves: Int = 0
    private set

  private val real = FileReleaseStateStore(dir, Dispatchers.IO) { point ->
    if (point == SavePoint.BEFORE_TEMP) saves++
    if (deathAt == point) {
      deathAt = null
      throw SimulatedDeath(point)
    }
  }

  val file: File get() = File(dir, FileReleaseStateStore.FILE_NAME)
  val temp: File get() = File(dir, FileReleaseStateStore.FILE_NAME + ".tmp")

  fun dieAt(point: SavePoint?) {
    deathAt = point
  }

  override fun dieDuringNextWrite() = dieAt(SavePoint.BEFORE_RENAME)

  override fun corrupt() {
    val bytes = Files.readAllBytes(file.toPath())
    bytes[bytes.size / 2] = (bytes[bytes.size / 2].toInt() xor 0x40).toByte()
    Files.write(file.toPath(), bytes)
  }

  override suspend fun read() = real.read()

  override suspend fun write(state: dev.pampa.mycloudgallery.core.release.contract.StoredRelease) = real.write(state)

  override suspend fun clear() = real.clear()

  /** I byte del file e del temporaneo, per rimettere la cartella com'era (una fotografia). */
  fun snapshot(): Pair<ByteArray?, ByteArray?> =
    (if (file.isFile) file.readBytes() else null) to (if (temp.isFile) temp.readBytes() else null)

  fun restore(snapshot: Pair<ByteArray?, ByteArray?>) {
    snapshot.first?.let { file.writeBytes(it) } ?: Files.deleteIfExists(file.toPath())
    snapshot.second?.let { temp.writeBytes(it) } ?: Files.deleteIfExists(temp.toPath())
  }

  companion object {
    fun tempDir(): File = Files.createTempDirectory("mcg-release-state").toFile().also { it.deleteOnExit() }
  }
}
