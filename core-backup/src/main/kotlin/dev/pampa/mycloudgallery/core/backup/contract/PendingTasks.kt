package dev.pampa.mycloudgallery.core.backup.contract

import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.StableCode
import dev.pampa.mycloudgallery.core.model.VersionId
import kotlinx.coroutines.flow.StateFlow

// Fase 5B (docs/fase5-progetto.md §1.2 "Attivita' in sospeso (5B)", §1.5 `PendingTasksState`, §2.6 "Da decidere", D20, D31; riga P0-B del
// §4.4): le ATTIVITA' IN SOSPESO, cose che l'app propone e che solo l'utente decide. La prima e' quella del video modificato e salvato sopra
// l'originale: "Hai modificato questo video. L'originale occupa X sul NAS: vuoi eliminarlo?". Contratto, null-object e fake di P0-B; la logica
// (creazione dal cammino `VERSION`, tabella `pending_task`) e' di P8.
//
// **Il backup non elimina mai niente da solo** (D2): "Elimina l'originale" non e' un metodo di questa porta. Passa dal foglio "Elimina…" di
// `feature-nas` (cestino del NAS per 30 giorni, `UserConfirmation` della 4B), che `:app` apre; a cancellazione avviata `:app` chiama [complete].

/** Il tipo di un'attivita' in sospeso (codici stabili: la colonna `kind` di `pending_task`). */
enum class PendingTaskKind(override val code: String) : StableCode {
  /** D20: un video modificato e salvato sopra; l'originale occupa spazio sul NAS e si puo' eliminare. */
  EDITED_VIDEO_ORIGINAL("edited_video_original"),
}

/** Lo stato di un'attivita' in sospeso (codici stabili: la colonna `state`). */
enum class PendingTaskState(override val code: String) : StableCode {
  /** Aperta: sta nella lista "Da decidere" (anche se l'utente ha ignorato l'avviso: resta finche' non decide). */
  OPEN("open"),

  /** L'utente ha detto "Tienilo": l'originale resta come versione e l'attivita' non si propone piu'. */
  KEPT("kept"),

  /** Decisa e fatta: l'originale e' stato mandato nel cestino del NAS. */
  DONE("done"),
}

/** Un'attivita' in sospeso (§1.5): tipo, media, byte, da quando. Nessun nome di file. Istanti in ora del NAS (F4-D32). */
data class PendingTask(
  val id: PendingTaskId,
  val kind: PendingTaskKind,
  val state: PendingTaskState,
  val mediaId: MediaId,
  /** La versione su cui si decide (l'originale che occupa spazio). */
  val versionId: VersionId,
  val bytes: Long,
  val createdAtNasMillis: Long,
) {
  init {
    require(bytes >= 0) { "Dimensione negativa." }
  }

  override fun toString(): String = "PendingTask(#$id, ${kind.code}, ${state.code}, $bytes B)"
}

/** L'id di un'attivita' in sospeso (riga di `pending_task`, positivo). */
@JvmInline
value class PendingTaskId(val value: Long) {
  init {
    require(value > 0) { "Id di attivita' non valido." }
  }

  override fun toString(): String = value.toString()
}

/** Le attivita' in sospeso (pagina Backup → "Da decidere"). */
interface PendingTasks {

  /** Le attivita' aperte ([PendingTaskState.OPEN]), dalla piu' vecchia: la lista "Da decidere". Si aggiorna da sola. */
  val tasks: StateFlow<List<PendingTask>>

  /** "Tienilo": l'originale resta come versione e l'attivita' passa a [PendingTaskState.KEPT]; non si propone piu'. Non tocca il NAS. */
  suspend fun dismiss(id: PendingTaskId)

  /**
   * L'originale e' stato mandato nel cestino del NAS dal foglio "Elimina…" di `feature-nas`: l'attivita' passa a [PendingTaskState.DONE]. La
   * chiama `:app` quando l'operazione della 4B e' partita; questa porta non cancella niente.
   */
  suspend fun complete(id: PendingTaskId)
}
