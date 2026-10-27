package dev.pampa.mycloudgallery.nas

import dev.pampa.mycloudgallery.core.common.files.StateFiles
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
 *
 * R1 (revisione 4B): ricorda anche **quando l'ultimo salto e' finito** ([trustedSinceNasMs], l'ora del NAS dell'ancora che ha tolto la
 * sospensione dopo 24 ore stabili, o del gesto dell'utente): il cestino del NAS non conta i suoi 30 giorni da prima di quell'istante.
 * Si scrive nello stesso file, nella stessa scrittura dell'ancora che chiude il salto: non si perde fra un processo e l'altro.
 */
class FileClockJumpStore(private val file: File) : ClockJumpGuard.Store {

  /** L'ultimo stato salvato (o letto), per riconoscere il passaggio "salto in corso" -> "nessun salto". */
  @Volatile private var last: ClockJumpGuard.Saved? = null

  @Volatile private var ended: Long? = null

  @Volatile private var endedLoaded = false

  /** R1: l'ora del NAS in cui l'ultimo salto dell'ora e' finito, o null se non se ne ricorda nessuno. */
  fun trustedSinceNasMs(): Long? {
    if (!endedLoaded) load()
    return ended
  }

  override fun load(): ClockJumpGuard.Saved? {
    endedLoaded = true
    return try {
      val values = (StateFiles.readLines(file) ?: return null).mapNotNull { line ->
        val key = line.substringBefore('=', "")
        val value = line.substringAfter('=', "")
        if (key.isEmpty()) null else key to value
      }.toMap()
      fun long(key: String): Long? = values[key]?.takeIf { it.isNotEmpty() }?.toLongOrNull()
      ended = long(ENDED)
      ClockJumpGuard.Saved(
        nasMs = long(NAS) ?: return null,
        elapsedMs = long(ELAPSED) ?: return null,
        bootId = long(BOOT),
        wallMs = long(WALL) ?: return null,
        jumpMs = long(JUMP),
        stableSinceNasMs = long(SINCE),
      ).also { last = it }
    } catch (e: IOException) {
      GalleryLog.w(TAG) { "ora del NAS salvata non letta" }
      null
    }
  }

  override fun save(saved: ClockJumpGuard.Saved) {
    if (!endedLoaded) load()
    // R1: il salto e' appena finito (24 ore stabili o il gesto): da quest'ora del NAS il cestino riparte con i suoi 30 giorni.
    if (last?.jumpMs != null && saved.jumpMs == null) ended = maxOf(ended ?: Long.MIN_VALUE, saved.nasMs)
    last = saved
    try {
      StateFiles.writeText(
        file,
        listOf(
          "$NAS=${saved.nasMs}",
          "$ELAPSED=${saved.elapsedMs}",
          "$BOOT=${saved.bootId ?: ""}",
          "$WALL=${saved.wallMs}",
          "$JUMP=${saved.jumpMs ?: ""}",
          "$SINCE=${saved.stableSinceNasMs ?: ""}",
          "$ENDED=${ended ?: ""}",
        ).joinToString("\n"),
      )
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
    const val ENDED = "ended"
  }
}
