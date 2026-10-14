package dev.pampa.mycloudgallery.nas

import dev.pampa.mycloudgallery.core.common.log.GalleryLog
import dev.pampa.mycloudgallery.core.nas.session.ClockJumpGuard
import java.io.File
import java.io.IOException

/**
 * Dove si salva la guardia del salto dell'ora del NAS (D32, R7 della revisione 4A): un file piccolo in
 * `noBackupFilesDir/replica/` (fuori dal backup, tolto dall'azzeramento con i pacchi), scritto su un temporaneo e poi
 * rinominato. Solo numeri: l'ultima ancora (ora del NAS, orologio monotono, avvio, orologio del telefono) e il salto in corso.
 * Un file illeggibile vale "nessuna ancora": la prossima ancora riparte da li' (senza un salto, ma anche senza togliere
 * niente: la sospensione in memoria resta finche' il processo vive).
 */
class FileClockJumpStore(private val file: File) : ClockJumpGuard.Store {

  override fun load(): ClockJumpGuard.Saved? {
    if (!file.isFile) return null
    return try {
      val values = file.readLines().mapNotNull { line ->
        val key = line.substringBefore('=', "")
        val value = line.substringAfter('=', "")
        if (key.isEmpty()) null else key to value
      }.toMap()
      fun long(key: String): Long? = values[key]?.takeIf { it.isNotEmpty() }?.toLongOrNull()
      ClockJumpGuard.Saved(
        nasMs = long(NAS) ?: return null,
        elapsedMs = long(ELAPSED) ?: return null,
        bootId = long(BOOT),
        wallMs = long(WALL) ?: return null,
        jumpMs = long(JUMP),
        stableSinceNasMs = long(SINCE),
      )
    } catch (e: IOException) {
      GalleryLog.w(TAG) { "ora del NAS salvata non letta" }
      null
    }
  }

  override fun save(saved: ClockJumpGuard.Saved) {
    try {
      file.parentFile?.mkdirs()
      val temp = File(file.parentFile, file.name + ".tmp")
      temp.writeText(
        listOf(
          "$NAS=${saved.nasMs}",
          "$ELAPSED=${saved.elapsedMs}",
          "$BOOT=${saved.bootId ?: ""}",
          "$WALL=${saved.wallMs}",
          "$JUMP=${saved.jumpMs ?: ""}",
          "$SINCE=${saved.stableSinceNasMs ?: ""}",
        ).joinToString("\n"),
      )
      if (!temp.renameTo(file)) {
        file.delete()
        if (!temp.renameTo(file)) throw IOException("rinomina non riuscita")
      }
    } catch (e: IOException) {
      GalleryLog.w(TAG) { "ora del NAS non salvata: ${e.javaClass.simpleName}" }
    }
  }

  private companion object {
    const val TAG = "NasClockStore"
    const val NAS = "nas"
    const val ELAPSED = "elapsed"
    const val BOOT = "boot"
    const val WALL = "wall"
    const val JUMP = "jump"
    const val SINCE = "since"
  }
}
