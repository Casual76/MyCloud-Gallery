package dev.pampa.mycloudgallery.feature.integrations.share

import java.io.File
import java.io.IOException
import java.security.SecureRandom

// Fase 5B (P11, docs/fase5-progetto.md §2.7, D25, D26, C17, E-V7): le DUE cartelle che il `FileProvider` serve e nient'altro: `cacheDir/share/`
// (copie da condividere, anche senza posizione) e `cacheDir/edit/` (copie per "Modifica con…"). Qui si decide come si chiamano i file (token
// casuali, mai un nome dell'utente nel percorso), che non si esce mai dalle due radici, e cosa si butta all'avvio.
//
// Niente orologio: i nomi vengono da `SecureRandom`, la pulizia all'avvio non guarda l'eta' dei file (D31, §4.3). Dopo un riavvio del processo
// una copia da condividere non serve piu' a nessuno (la condivisione e' un gesto di pochi secondi) e una sessione di modifica di cui il
// processo non ricorda piu' niente non si puo' piu' attribuire; le sessioni ancora aperte nel database tengono le loro copie (`keepEdit`).

class ShareCache(
  cacheDir: File,
  private val token: () -> String = { randomToken() },
  /** I byte liberi del telefono; di default quelli della cartella della cache (i test ne danno altri). */
  private val free: (() -> Long)? = null,
) {

  /** `cacheDir/share/`. */
  val shareRoot: File = File(cacheDir, SHARE)

  /** `cacheDir/edit/`. */
  val editRoot: File = File(cacheDir, EDIT)

  /** Una cartella nuova per una condivisione: `share/<token>/`. Lancia `IOException` se non si puo' creare. */
  fun newShareDir(): File {
    val dir = File(shareRoot, token())
    if (!dir.mkdirs() && !dir.isDirectory) throw IOException("Cartella di condivisione non creata.")
    return dir
  }

  /** Il nome (opaco) di una copia per "Modifica con…": `edit/<nome>`, 32 cifre esadecimali. */
  fun newEditName(): String = token()

  /**
   * Il file di una sessione di modifica dal suo nome opaco, o null se il nome non e' uno dei nostri (niente separatori, niente `..`): un nome
   * che arriva dal database o da fuori non esce mai da `edit/`.
   */
  fun editFile(name: String): File? {
    if (!EDIT_NAME.matches(name)) return null
    return File(editRoot, name)
  }

  /** I byte liberi della cache (per il tetto "almeno 1 GiB libero dopo la copia"). */
  fun usableBytes(): Long = free?.invoke() ?: (shareRoot.parentFile?.usableSpace ?: 0L)

  /**
   * Alla partenza dell'app: butta tutto cio' che sta in `share/` e in `edit/`, salvo le copie delle sessioni ancora aperte ([keepEdit], nomi
   * opachi). Idempotente; un file che non si toglie resta per il giro dopo.
   */
  fun sweepAtStart(keepEdit: Set<String>) {
    deleteChildren(shareRoot) { true }
    deleteChildren(editRoot) { it.name !in keepEdit }
  }

  /** Toglie [file] e, se e' una cartella, tutto cio' che contiene. Mai fuori dalle due radici. */
  fun deleteTree(file: File) {
    if (!isInside(file)) return
    file.deleteRecursively()
  }

  /** [file] sta dentro `share/` o `edit/` (per percorso canonico: un collegamento simbolico non porta fuori). */
  fun isInside(file: File): Boolean = try {
    val canonical = file.canonicalFile
    canonical.startsWith(shareRoot.canonicalFile) || canonical.startsWith(editRoot.canonicalFile)
  } catch (_: IOException) {
    false
  }

  private fun deleteChildren(root: File, selector: (File) -> Boolean) {
    val children = root.listFiles() ?: return
    for (child in children) if (selector(child)) child.deleteRecursively()
  }

  companion object {
    private const val SHARE = "share"
    private const val EDIT = "edit"
    private val EDIT_NAME = Regex("^[0-9a-f]{32}$")

    /** 32 cifre esadecimali da `SecureRandom`. */
    fun randomToken(): String {
      val bytes = ByteArray(16)
      random.nextBytes(bytes)
      return bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private val random = SecureRandom()
  }
}
