package dev.pampa.mycloudgallery.core.nas.ops

import dev.pampa.mycloudgallery.core.trust.TrustFormat
import dev.pampa.mycloudgallery.core.trust.crypto.TrustIds
import dev.pampa.mycloudgallery.core.trust.data.OwnFile
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import java.text.Normalizer

/**
 * Dove stanno i file dell'app e quali percorsi le primitive di scrittura ([NasFileOps]) ammettono (docs/fase4-progetto.md D2,
 * D25). Puro e senza effetti: lo usano le implementazioni, il fake e `core-nasops` per rifiutare un piano prima di toccare il NAS.
 */
object NasOpsPaths {

  /** Il percorso e' dentro `.mycloudgallery/` (anche con maiuscole o forme di compatibilita' diverse: come `parseMediaPath`). */
  fun isAppArea(path: SharePath): Boolean =
    Normalizer.normalize(path.components[0], Normalizer.Form.NFKC).equals(TrustFormat.ROOT_DIR, ignoreCase = true)

  /** Un percorso di media: fuori dall'area dell'app. */
  fun isMediaPath(path: SharePath): Boolean = !isAppArea(path)

  /**
   * `.mycloudgallery/trash/<dispositivo>/files/<id>` (`NasLayout.trashItemFile`): un file o una cartella nel cestino. Dispositivo
   * e id devono essere id validi. E' l'unico posto da cui una rinomina porta fuori dall'area dell'app (ripristino).
   */
  fun isTrashItem(path: SharePath): Boolean {
    val c = path.components
    return c.size == 5 && c[0] == TrustFormat.ROOT_DIR && c[1] == "trash" && TrustIds.isValid(c[2]) && c[3] == "files" &&
      TrustIds.isValid(c[4])
  }

  /**
   * Il cestino proprio di [deviceId] e quello che contiene: `.mycloudgallery/trash/<dispositivo>/files/<id>` e tutto cio' che sta
   * sotto (le cartelle cestinate, E-D10). E' l'unico pezzo dell'area dell'app in cui si tolgono cartelle ([NasFileOps.rmdirIfEmpty]).
   */
  fun isOwnTrashTree(path: SharePath, deviceId: String): Boolean {
    val c = path.components
    return c.size >= 5 && c[0] == TrustFormat.ROOT_DIR && c[1] == "trash" && c[2] == deviceId && c[3] == "files"
  }

  /** Il proprietario di un temporaneo `.mycloudgallery/tmp/<id>-<resto>`, o null se [path] non e' un temporaneo. */
  fun tempOwner(path: SharePath): String? = (OwnFile.classify(path) as? OwnFile.Temp)?.deviceId

  /** Vero se [path] e' un temporaneo proprio di [deviceId]. */
  fun isTempOf(path: SharePath, deviceId: String): Boolean = tempOwner(path) == deviceId
}
