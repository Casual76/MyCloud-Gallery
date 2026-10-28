package dev.pampa.mycloudgallery.gallery

import dev.pampa.mycloudgallery.core.common.files.StateFiles
import dev.pampa.mycloudgallery.core.common.log.GalleryLog
import dev.pampa.mycloudgallery.core.common.perf.AvvioTrace
import dev.pampa.mycloudgallery.core.library.contract.TimelineRepository
import dev.pampa.mycloudgallery.core.library.data.CatalogTimelineRepository
import dev.pampa.mycloudgallery.core.library.data.DataStoreLibraryPreferences
import dev.pampa.mycloudgallery.core.library.layout.TimelineLayoutEngine
import dev.pampa.mycloudgallery.feature.library.grid.GridWarmup
import dev.pampa.mycloudgallery.feature.library.grid.SavedGridPosition
import java.io.File
import java.io.IOException
import java.util.UUID

/**
 * **L'istantanea della Libreria all'avvio** (Avvio istantaneo, 28/10): alla partenza la Libreria si disegna com'era l'ultima volta,
 * dallo stesso punto, e i dati vivi la sostituiscono senza salti.
 *
 * Tre pezzi, tutti fuori dal thread principale e tutti **mentre il processo nasce** ([warm], dall'avvio della galleria):
 * 1. l'istantanea su file dell'indice (`replica/timeline.idx`, E-H1: mai un nascosto) si legge e si pubblica subito
 *    ([CatalogTimelineRepository.preload]), senza aspettare la famiglia e il catalogo: servono solo a riconciliarla;
 * 2. filtro, livello e stile ricordati, e **il punto in cui si era** ([POSITION_FILE]: l'elemento ancorato, mai un indice);
 * 3. il layout del livello ricordato, impaginato come lo fara' la griglia ([GridWarmup]): la prima composizione disegna subito le
 *    celle, e la finestra delle miniature (quelle del telefono e della replica, gia' sul disco) parte subito.
 *
 * Il punto si scrive quando l'app va in secondo piano ([writePosition]), con [StateFiles], in `replica/` (fuori dal backup del
 * telefono, tolto con la replica dall'azzeramento). Contiene l'id del media ancorato e tre numeri: nessun nome, nessun luogo,
 * nessuna data. Un id che non c'e' piu' nella vista (nascosto, eliminato) non si mostra: la griglia torna sui piu' recenti.
 *
 * **Blocco app**: qui non si decodifica nessuna immagine; le miniature le chiede la griglia, che con l'app bloccata non e' in
 * composizione (`AppLockGate`). **Dati personali**: nessuno nei log (solo il tipo dell'errore).
 */
object LibraryInstant {

  /** Il file del punto della Libreria, in `noBackupFilesDir/replica/`. */
  const val POSITION_FILE: String = "library-position"

  private const val TAG = "LibraryInstant"
  private const val MAGIC = "MCGP1"

  /**
   * Prepara la Libreria dell'ultima volta: istantanea pubblicata, posizione letta, layout del livello ricordato. Ritorna la posizione
   * da cui ripartire, o null (si parte dai piu' recenti, come sempre).
   */
  suspend fun warm(
    repository: CatalogTimelineRepository,
    timeline: TimelineRepository,
    preferences: DataStoreLibraryPreferences,
    engine: TimelineLayoutEngine,
    replicaDir: File,
    screen: Screen,
  ): SavedGridPosition? = AvvioTrace.asyncSection("MCG:avvio:preparaLibreria") {
    val position = readPosition(File(replicaDir, POSITION_FILE))
    repository.preload()
    val (filter, level, style) = preferences.read()
    val view = timeline.view(filter).value
    GridWarmup.prepare(view, style, level, screen.widthPx, screen.density, screen.fontScale, engine)
    position
  }

  /** La finestra in cui la Libreria si disegnera': larghezza in pixel, densita', scala del testo. */
  data class Screen(val widthPx: Int, val density: Float, val fontScale: Float)

  /** Il punto salvato, o null (nessun file, file di un'altra versione o rovinato). */
  fun readPosition(file: File): SavedGridPosition? = try {
    StateFiles.readLines(file, Charsets.US_ASCII, label = POSITION_FILE)?.let(::decode)
  } catch (e: IOException) {
    GalleryLog.w(TAG) { "punto della Libreria non letto: ${e.javaClass.simpleName}" }
    null
  }

  /** Scrive il punto [position] (atomico). Falso se non e' riuscito: alla prossima partenza si riparte dai piu' recenti. */
  fun writePosition(file: File, position: SavedGridPosition): Boolean = try {
    StateFiles.writeText(file, encode(position), Charsets.US_ASCII, label = POSITION_FILE, durable = false)
    true
  } catch (e: IOException) {
    GalleryLog.w(TAG) { "punto della Libreria non scritto: ${e.javaClass.simpleName}" }
    false
  }

  internal fun encode(p: SavedGridPosition): String =
    listOf(MAGIC, p.mediaId, p.fy.toString(), p.screenFraction.toString(), if (p.atNewest) "1" else "0").joinToString("\n")

  internal fun decode(lines: List<String>): SavedGridPosition? {
    if (lines.size < 5 || lines[0] != MAGIC) return null
    val id = lines[1].takeIf { runCatching { UUID.fromString(it) }.isSuccess } ?: return null
    val fy = lines[2].toFloatOrNull()?.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: return null
    val fraction = lines[3].toFloatOrNull()?.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: return null
    val atNewest = when (lines[4]) {
      "1" -> true
      "0" -> false
      else -> return null
    }
    return SavedGridPosition(id, fy, fraction, atNewest)
  }
}
