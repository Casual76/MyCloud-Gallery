package dev.pampa.mycloudgallery.core.mediastore.sync

import dev.pampa.mycloudgallery.core.mediastore.contract.SyncReason
import dev.pampa.mycloudgallery.core.mediastore.contract.SyncReport
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Perche' una riga del MediaStore non e' entrata in Libreria. Solo il codice finisce nella Diagnostica. */
enum class IgnoreReason {
  /** File vuoto (E-M12). */
  ZERO_BYTES,

  /** I primi byte non sono di un media (E-M12). */
  NOT_MEDIA,

  /** Nome o percorso che non possono essere un posto (caratteri di controllo, `..`, troppo lunghi). */
  INVALID_NAME,

  /** Non si e' potuta leggere adesso (si riprova): non e' un'esclusione definitiva. */
  UNREADABLE,
}

/** Perche' l'ultimo giro si e' fermato prima della fine, se non per un motivo di [dev.pampa.mycloudgallery.core.mediastore.contract.PauseReason]. */
enum class SyncFailure {
  /** Il MediaStore ha risposto con un errore o con un cursore nullo. */
  MEDIASTORE_ERROR,

  /** Disco pieno (E-P3). */
  DISK_FULL,

  /** I permessi sono stati ritirati a meta' giro (E-P15). */
  PERMISSION_LOST,

  /** Un errore inatteso: il giro ripartira' da dove era. */
  UNEXPECTED,
}

/**
 * Lo stato della lettura del telefono per la Diagnostica: **solo numeri ed enumerati**, mai nomi di file, percorsi o id
 * di volume. In memoria: i conteggi degli ignorati valgono dall'avvio del processo (una riga ignorata si rivede solo se
 * cambia, o alla scansione completa successiva).
 *
 * @property ignored le righe ignorate per motivo, dall'avvio del processo.
 * @property deletedFilesAvailable se l'accelerazione `deleted_files` ha risposto all'ultimo incrementale (null: non usata).
 * @property absencesBlocked quante volte un'ondata di assenze sospette e' stata fermata (MediaStore vuoto o assenze piu'
 *   dei file vivi).
 * @property suspectedAbsences pacchetto F: le posizioni che il MediaStore non mostra, annotate e in attesa della seconda
 *   osservazione (dopo l'ultimo giro).
 */
data class PhoneLibrarySnapshot(
  val lastRunAtMs: Long? = null,
  val lastReason: SyncReason? = null,
  val lastReport: SyncReport? = null,
  val lastFailure: SyncFailure? = null,
  val ignored: Map<IgnoreReason, Long> = emptyMap(),
  val deletedFilesAvailable: Boolean? = null,
  val absencesBlocked: Int = 0,
  val fullScansCompleted: Int = 0,
  val reconcileVanished: Int = 0,
  val purgedAbsences: Int = 0,
  val suspectedAbsences: Int = 0,
  val metaDone: Long = 0,
  val metaFailed: Long = 0,
  val metaWaitingForLocation: Int = 0,
)

/** Il contenitore osservabile di [PhoneLibrarySnapshot], condiviso da sincronizzazione, META e Diagnostica. */
class PhoneLibraryStats {
  private val flow = MutableStateFlow(PhoneLibrarySnapshot())

  val snapshot: StateFlow<PhoneLibrarySnapshot> = flow.asStateFlow()

  internal fun update(change: (PhoneLibrarySnapshot) -> PhoneLibrarySnapshot) {
    flow.update(change)
  }
}
