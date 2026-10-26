package dev.pampa.mycloudgallery.ai

import dev.pampa.mycloudgallery.core.common.files.StateFiles
import dev.pampa.mycloudgallery.core.rules.platform.health.ExitReasonKind
import dev.pampa.mycloudgallery.core.rules.platform.health.ExitRecord
import java.io.File
import java.io.IOException

/**
 * Il segno "una tappa del calcolo e' in corso" (DG-3 della verifica funzionale della Fase 6, E-A16): un file con l'ora del telefono di quando
 * la tappa e' partita, scritto all'inizio e tolto alla fine, anche se la tappa e' fermata o annullata. Se alla tappa dopo il file c'e' ancora, il
 * processo e' morto durante la tappa: [AiKilledByMemory] guarda i motivi delle uscite del processo (`ApplicationExitInfo`) e dice se Android
 * l'ha chiuso per memoria. Prima `AI_KILLED_MEMORY` era dichiarato nel catalogo ma non lo emetteva nessuno, e F62 non poteva passare.
 *
 * L'ora e' quella del telefono perche' `ApplicationExitInfo` e' scritto con la stessa; non decide niente, serve solo a confrontare due istanti
 * dello stesso orologio per una voce di Diagnostica (D39).
 */
class AiStageMark(private val file: File) {

  /** Scrive il segno con l'istante [atMillis]. Un errore del disco non ferma la tappa. */
  fun begin(atMillis: Long) {
    try {
      StateFiles.writeText(file, atMillis.toString(), Charsets.US_ASCII)
    } catch (e: IOException) {
      // Senza il segno una morte della tappa non si attribuisce: niente altro (l'errore e' nel log di `StateFiles`).
    }
  }

  /**
   * La tappa e' finita (bene, fermata o annullata): il segno si toglie. Se non si riesce (il log di `StateFiles` lo dice) il segno resta, e la
   * tappa dopo lo prenderebbe per una morte: per questo si riprova prima di arrendersi.
   */
  fun end() {
    StateFiles.delete(file)
  }

  /** L'istante del segno lasciato da una tappa morta, o null se non ce n'e'. Lo toglie: si dice una volta sola. */
  fun interruptedAt(): Long? {
    val at = try {
      if (file.isFile && file.length() < 32) StateFiles.readText(file, Charsets.US_ASCII)?.trim()?.toLongOrNull() else null
    } catch (e: IOException) {
      null
    }
    StateFiles.delete(file)
    return at
  }

  companion object {
    const val FILE = "ai-stage-running"
  }
}

/** Android ha chiuso il processo per memoria durante la tappa partita a [markedAt]? (E-A16, F62.) */
object AiKilledByMemory {
  private val MEMORY = setOf(ExitReasonKind.LOW_MEMORY, ExitReasonKind.EXCESSIVE_RESOURCE_USAGE)

  /** Un'uscita per memoria avvenuta dopo che la tappa e' partita (la tappa e' partita prima dell'uscita, che e' la sua morte). */
  fun wasKilled(markedAt: Long, exits: List<ExitRecord>): Boolean = exits.any { it.reason in MEMORY && it.atMillis >= markedAt }
}
