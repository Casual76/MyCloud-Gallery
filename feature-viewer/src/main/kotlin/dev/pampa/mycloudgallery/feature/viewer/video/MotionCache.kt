package dev.pampa.mycloudgallery.feature.viewer.video

import java.io.File

/**
 * La cache della parte video delle foto in movimento solo-NAS (D51): `cacheDir/motion/`, **64 MB**, la piu' vecchia esce per
 * prima. Dentro ci sono solo file MP4 gia' interi (un file arriva qui solo a lettura finita: prima sta in un `.part`) che nel
 * nome portano cio' che serve a riusarli senza rileggere il NAS: il media, il suo timbro, l'istante della foto e la durata.
 * Nessun percorso, nome di file o id del catalogo in chiaro oltre al numero della posizione.
 *
 * La cache e' di cache: il sistema puo' toglierla in ogni momento e "Azzera" dell'app la svuota con [clear]. Funzioni
 * sincrone, da chiamare fuori dal thread principale.
 */
internal class MotionCache(parent: File, private val capBytes: Long = DEFAULT_CAP_BYTES) {

  private val dir = File(parent, DIRECTORY)

  /** Il file gia' pronto per [prefix], o null. Un uso lo rende il piu' recente: esce per ultimo. */
  fun find(prefix: String): File? {
    val file = dir.listFiles()?.firstOrNull { it.isFile && it.name.startsWith(prefix) && it.name.endsWith(SUFFIX) } ?: return null
    file.setLastModified(System.currentTimeMillis())
    return file
  }

  /** Un file temporaneo dove scrivere; sparisce con [discard] o diventa un file pronto con [commit]. */
  fun begin(prefix: String): File {
    dir.mkdirs()
    return File(dir, prefix + System.nanoTime() + PART)
  }

  fun discard(part: File) {
    part.delete()
  }

  /** Il file temporaneo diventa il file pronto [name]; poi si fa posto. Null se non si e' potuto rinominare. */
  fun commit(part: File, name: String): File? {
    val target = File(dir, name + SUFFIX)
    target.delete()
    if (!part.renameTo(target)) {
      part.delete()
      return null
    }
    trim()
    return target
  }

  /** Toglie i file piu' vecchi finche' il totale sta entro il tetto; e i temporanei rimasti da un processo morto. */
  fun trim() {
    val files = dir.listFiles()?.filter { it.isFile } ?: return
    files.filter { it.name.endsWith(PART) && System.currentTimeMillis() - it.lastModified() > STALE_PART_MS }.forEach { it.delete() }
    var total = files.filter { it.exists() }.sumOf { it.length() }
    for (file in files.filter { it.exists() && it.name.endsWith(SUFFIX) }.sortedBy { it.lastModified() }) {
      if (total <= capBytes) break
      total -= file.length()
      file.delete()
    }
  }

  /** Il totale di adesso, per la prova del tetto. */
  fun totalBytes(): Long = dir.listFiles()?.filter { it.isFile }?.sumOf { it.length() } ?: 0L

  /** L'azzeramento dell'app: la cache sparisce. */
  fun clear() {
    dir.listFiles()?.forEach { it.delete() }
  }

  companion object {
    const val DIRECTORY: String = "motion"
    const val DEFAULT_CAP_BYTES: Long = 64L * 1024L * 1024L
    private const val SUFFIX = ".mp4"
    private const val PART = ".part"
    private const val STALE_PART_MS = 10L * 60L * 1000L
  }
}
