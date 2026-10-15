package dev.pampa.mycloudgallery.core.nasops.contract

import dev.pampa.mycloudgallery.core.model.NasStamp
import dev.pampa.mycloudgallery.core.model.Sha256
import dev.pampa.mycloudgallery.core.model.StableCode
import dev.pampa.mycloudgallery.core.trust.crypto.TrustIds
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import kotlinx.coroutines.flow.Flow

// Fase 4B (docs/fase4-progetto.md §2.7 "Il diario", D24, D25; schema v3: `nas_op`, `nas_op_item`, `nas_trash_entry`): il diario
// delle operazioni e la macchina a stati di ogni elemento. Tipi, tabella delle transizioni e porta di P0-B; l'esecuzione e
// l'adattatore Room (sopra `NasOpsDao`) sono di P12.
//
// **Ogni passo si scrive prima di farlo, ogni passo e' idempotente.** Il diario e' l'unico ingresso, oltre alla conferma
// dell'utente, delle operazioni distruttive: una ripresa dopo un'interruzione rilegge da qui e nient'altro. Niente di quanto si
// legge dal NAS (record del cestino, indice, cartelle) crea righe del diario: una riga nasce solo da [OpsStore.create], che vuole un
// [AcceptedPlan], cioe' un piano con la conferma dell'utente verificata.

/** Lo stato di un'operazione (`nas_op.state`). I codici non cambiano. */
enum class OpState(override val code: String, val terminal: Boolean = false) : StableCode {
  PLANNED("planned"),
  RUNNING("running"),
  PAUSED("paused"),
  DONE("done", terminal = true),
  FAILED("failed", terminal = true),
  CANCELLED("cancelled", terminal = true),
}

/**
 * Lo stato di un elemento (`nas_op_item.state`): i passi di §2.7. I nomi in "-ING" si scrivono **prima** di chiamare il NAS (il
 * passo e' in corso: la ripresa sa che deve controllare cosa e' successo), gli altri **dopo**. Quale cammino fa un elemento lo dice il suo
 * [ItemFlow].
 *
 * L'attesa di un file bloccato dal PC (E-N13, "WAITING_LOCKED" del §2.7) **non e' uno stato**: e' una condizione dell'elemento
 * (passo non terminale + `nextAttemptAt` futuro + [OpErrorCode.LOCKED], vedi [OpItemRecord.waitingLocked]). Cosi' la ripresa sa a
 * quale passo tornare senza colonne nuove nello schema v3.
 */
enum class OpItemState(override val code: String, val terminal: Boolean = false) : StableCode {
  PLANNED("planned"),
  RENAMING("renaming"),

  /** Rinomina solo di maiuscole (E-N7): il file ha il nome temporaneo scritto nel diario, manca la seconda rinomina. */
  TEMP_NAME("temp_name"),
  COPYING("copying"),
  COPIED("copied"),
  VERIFIED("verified"),
  PUBLISHED("published"),
  SOURCE_RECORDED("source_recorded"),
  SOURCE_TRASHED("source_trashed"),
  RECORDED("recorded"),
  TRASHED("trashed"),
  RESTORING("restoring"),
  PURGING("purging"),
  DONE("done", terminal = true),
  FAILED("failed", terminal = true),
  CANCELLED("cancelled", terminal = true),
}

/** Il cammino di un elemento. Deriva da [OpKind] e dalle share/nomi dell'elemento ([OpStateMachine.flowOf]): non si salva. */
enum class ItemFlow(override val code: String, val steps: List<OpItemState>) : StableCode {
  CREATE_FOLDER("create_folder", listOf(OpItemState.PLANNED, OpItemState.DONE)),

  /** Rinomina o sposta nella stessa share: una rinomina atomica. */
  RENAME("rename", listOf(OpItemState.PLANNED, OpItemState.RENAMING, OpItemState.DONE)),

  /** Rinomina che cambia solo le maiuscole: due rinomine passando da un nome temporaneo nella stessa cartella (E-N7). */
  RENAME_CASE_ONLY("rename_case_only", listOf(OpItemState.PLANNED, OpItemState.TEMP_NAME, OpItemState.DONE)),

  /** Sposta tra share: copia lato server su un temporaneo, verifica completa, pubblicazione, sorgente nel cestino (D24). */
  MOVE_ACROSS_SHARES(
    "move_across_shares",
    listOf(
      OpItemState.PLANNED, OpItemState.COPYING, OpItemState.COPIED, OpItemState.VERIFIED, OpItemState.PUBLISHED,
      OpItemState.SOURCE_RECORDED, OpItemState.SOURCE_TRASHED, OpItemState.DONE,
    ),
  ),

  /** Elimina: prima il record e la voce del diario locale, poi la rinomina nel cestino (un record senza file e' innocuo). */
  TRASH("trash", listOf(OpItemState.PLANNED, OpItemState.RECORDED, OpItemState.TRASHED, OpItemState.DONE)),

  RESTORE("restore", listOf(OpItemState.PLANNED, OpItemState.RESTORING, OpItemState.DONE)),

  /** "Elimina subito" di una voce del proprio cestino. */
  PURGE("purge", listOf(OpItemState.PLANNED, OpItemState.PURGING, OpItemState.DONE)),
}

/** Perche' un elemento o un'operazione e' ferma o fallita. I codici si salvano in `error_code`: non cambiano. Nessun testo libero. */
enum class OpErrorCode(override val code: String) : StableCode {
  SOURCE_MISSING("source_missing"),

  /** Il file non e' piu' quello confermato: modificato o spostato da un altro (E-D13, E-N11). Nessun file nel cestino. */
  SOURCE_CHANGED("source_changed"),
  NAME_TAKEN("name_taken"),
  READ_ONLY("read_only"),
  NO_SPACE("no_space"),
  QUOTA("quota"),

  /** L'impronta della copia non e' quella della sorgente. */
  VERIFY_FAILED("verify_failed"),
  INVALID_NAME("invalid_name"),
  UNREACHABLE("unreachable"),

  /** File aperto dal PC: si riprova (E-N13). E' una condizione di attesa, non un errore finale. */
  LOCKED("locked"),
  LOCKED_TOO_LONG("locked_too_long"),
  PERMISSION_DENIED("permission_denied"),
  UNEXPECTED("unexpected"),
}

/**
 * La macchina a stati, come dati: i cammini ([ItemFlow.steps]) e le transizioni ammesse. P12 la esegue, il banco delle 500
 * interruzioni (P15) controlla che il diario non contenga mai una transizione che non sia qui.
 *
 * Una transizione e' ammessa se e' (a) lo stesso stato (riscrittura idempotente), (b) il passo dopo lungo il cammino, (c) verso
 * [OpItemState.FAILED] da qualunque stato non terminale, (d) verso [OpItemState.CANCELLED] solo da un passo in cui annullare non
 * lascia doppioni visibili ([cancellable]).
 */
object OpStateMachine {

  /** Il cammino di un elemento di [kind] da [srcShare] a [dstShare] (se c'e'), con i nomi origine/destinazione. */
  fun flowOf(kind: OpKind, srcShare: String, dstShare: String?, srcPath: SharePath, dstPath: SharePath?): ItemFlow = when (kind) {
    OpKind.CREATE_FOLDER -> ItemFlow.CREATE_FOLDER
    OpKind.RENAME, OpKind.MOVE ->
      if (dstShare != null && !dstShare.equals(srcShare, ignoreCase = true)) {
        ItemFlow.MOVE_ACROSS_SHARES
      } else if (dstPath != null && srcPath.parent() == dstPath.parent() && srcPath != dstPath && srcPath.name.equals(dstPath.name, ignoreCase = true)) {
        ItemFlow.RENAME_CASE_ONLY
      } else {
        ItemFlow.RENAME
      }
    OpKind.TRASH -> ItemFlow.TRASH
    OpKind.RESTORE -> ItemFlow.RESTORE
    OpKind.PURGE_NOW -> ItemFlow.PURGE
  }

  /** Il passo successivo lungo il cammino, o null se [state] e' l'ultimo o non e' sul cammino. */
  fun next(flow: ItemFlow, state: OpItemState): OpItemState? {
    val steps = flow.steps
    val at = steps.indexOf(state)
    return if (at < 0 || at == steps.lastIndex) null else steps[at + 1]
  }

  /**
   * Da questi stati annullare e' sicuro (D24, "nessun doppione visibile"): prima che la copia venga pubblicata, o prima che
   * l'elemento sia partito. Dopo [OpItemState.PUBLISHED] l'elemento va portato a termine: annullare lascerebbe due copie visibili.
   */
  fun cancellable(flow: ItemFlow, state: OpItemState): Boolean {
    if (state.terminal) return false
    if (state == OpItemState.PLANNED) return true
    return flow == ItemFlow.MOVE_ACROSS_SHARES && state in setOf(OpItemState.COPYING, OpItemState.COPIED, OpItemState.VERIFIED)
  }

  fun canTransition(flow: ItemFlow, from: OpItemState, to: OpItemState): Boolean {
    if (from == to) return true // la riscrittura dello stesso stato e' idempotente, anche se terminale
    if (from.terminal) return false
    if (to == OpItemState.FAILED) return true
    if (to == OpItemState.CANCELLED) return cancellable(flow, from)
    return next(flow, from) == to
  }

  /** Gli stati in cui un elemento si riprende dal diario: tutti i non terminali. */
  val resumable: Set<OpItemState> = OpItemState.entries.filterNot { it.terminal }.toSet()
}

/** L'operazione nel diario (`nas_op`). Tempi in ora del NAS (D32). */
data class OpRecord(
  val id: OpId,
  val kind: OpKind,
  val state: OpState,
  val planHash: PlanHash,
  val itemCount: Int,
  val totalBytes: Long,
  val errorCode: OpErrorCode?,
  val createdAtNas: Long,
  val updatedAtNas: Long,
)

/**
 * Un elemento nel diario (`nas_op_item`). **Ogni valore di un passo (nome alternativo, temporaneo, impronta verificata, id del
 * cestino) si scrive qui prima della chiamata al NAS**, cosi' la ripresa e' idempotente. I percorsi sono dati dell'utente: mai nei log.
 *
 * @property kind il tipo dell'operazione a cui appartiene (da cui il [flow]).
 * @property tempName il temporaneo `.mycloudgallery/tmp/<io>-op-...` (spostamento tra share) o il nome temporaneo della rinomina
 *   solo di maiuscole, scritto prima di usarlo.
 * @property stamp il timbro atteso della sorgente (E-D13); dopo `VERIFIED` e' quello su cui e' stata fatta la verifica.
 * @property sha256 l'impronta della sorgente verificata contro quella del temporaneo.
 */
data class OpItemRecord(
  val opId: OpId,
  val index: Int,
  val kind: OpKind,
  val state: OpItemState,
  val isFolder: Boolean,
  val accountId: String,
  val srcShare: String,
  val srcPath: SharePath,
  val dstShare: String?,
  val dstPath: SharePath?,
  val tempName: SharePath?,
  val size: Long?,
  val stamp: NasStamp?,
  val sha256: Sha256?,
  val trashId: String?,
  val attempts: Int,
  val nextAttemptAtNas: Long?,
  val errorCode: OpErrorCode?,
  val updatedAtNas: Long,
) {
  init {
    require(trashId == null || TrustIds.isValid(trashId)) { "Id di cestino non valido." }
  }

  val flow: ItemFlow get() = OpStateMachine.flowOf(kind, srcShare, dstShare, srcPath, dstPath)

  /** In attesa perche' il file e' aperto dal PC (E-N13): passo non terminale, riprova a [nextAttemptAtNas]. */
  val waitingLocked: Boolean get() = !state.terminal && errorCode == OpErrorCode.LOCKED && nextAttemptAtNas != null

  override fun toString(): String = "OpItemRecord(#$index, ${state.code})"
}

/** Perche' un file e' nel cestino (formato §5.7): eliminato dall'utente o sorgente di uno spostamento tra share. */
enum class TrashReason(override val code: String) : StableCode {
  DELETE("delete"),
  MOVE("move"),
}

/** Lo stato di una voce del diario locale del cestino. */
enum class TrashEntryState(override val code: String) : StableCode {
  TRASHED("trashed"),
  RESTORED("restored"),
  PURGED("purged"),
}

/**
 * Una voce del diario **locale** del cestino (`nas_trash_entry`, D25): esiste solo perche' su questo dispositivo l'utente ha
 * confermato l'eliminazione (o uno spostamento tra share). La pulizia dopo 30 giorni di ora del NAS parte solo da qui (E-D4).
 * Non si costruisce mai da un `TrashRecord` letto dal NAS.
 */
data class LocalTrashRecord(
  val trashId: String,
  val accountId: String,
  val shareKey: String,
  val shareName: String,
  val originalPath: SharePath,
  val trashedPath: SharePath,
  val reason: TrashReason,
  val isFolder: Boolean,
  val size: Long,
  val fileCount: Int?,
  val trashedAtNas: Long,
  val state: TrashEntryState,
  val opId: OpId?,
  val updatedAtNas: Long,
) {
  init {
    require(TrustIds.isValid(trashId)) { "Id di cestino non valido." }
    require(size >= 0) { "Dimensione negativa." }
  }

  override fun toString(): String = "LocalTrashRecord(${state.code}, ${reason.code}, folder=$isFolder)"
}

/**
 * La porta del diario (`nas_op`, `nas_op_item`, `nas_trash_entry`). P12 la implementa sopra `NasOpsDao` (Room); il fake in memoria
 * sta in `testing`. Le scritture di un'operazione sono transazioni: [create] scrive l'operazione e **tutti** i suoi elementi o niente.
 *
 * **Unico ingresso delle operazioni:** [create] vuole un [AcceptedPlan], che si ottiene solo da [ConfirmationGate.accept] con la
 * [UserConfirmation] dell'utente. Nessun'altra funzione crea operazioni ed elementi.
 */
interface OpsStore {

  /** Scrive l'operazione e i suoi elementi (tutti `PLANNED`). Idempotente sull'[OpPlan.hash] gia' presente: restituisce l'esistente. */
  suspend fun create(accepted: AcceptedPlan, id: OpId, accountId: String, nowNas: Long): OpRecord

  suspend fun op(id: OpId): OpRecord?

  suspend fun opByPlanHash(hash: PlanHash): OpRecord?

  suspend fun ops(states: Set<OpState>): List<OpRecord>

  fun observeOps(): Flow<List<OpRecord>>

  suspend fun items(id: OpId): List<OpItemRecord>

  /** Gli elementi da riprendere: in uno stato di [OpStateMachine.resumable] e pronti (`nextAttemptAtNas` nullo o scaduto a [nowNas]). */
  suspend fun itemsToResume(nowNas: Long, limit: Int): List<OpItemRecord>

  /** Riscrive un elemento (il passo si scrive **prima** di farlo). Una transizione fuori da [OpStateMachine] e' un errore di programmazione. */
  suspend fun updateItem(item: OpItemRecord)

  suspend fun setOpState(id: OpId, state: OpState, errorCode: OpErrorCode?, nowNas: Long)

  // --- Diario locale del cestino ---------------------------------------------------------------------------------------

  /** Scrive la voce **prima** della rinomina nel cestino. Solo per un elemento di un'operazione gia' nel diario. */
  suspend fun recordTrash(entry: LocalTrashRecord)

  suspend fun trashEntry(trashId: String): LocalTrashRecord?

  fun observeTrash(): Flow<List<LocalTrashRecord>>

  suspend fun markTrash(trashId: String, state: TrashEntryState, nowNas: Long)

  /** Le voci proprie nel cestino da prima di [beforeNas] (ora del NAS), le piu' vecchie prima, al massimo [limit] (E-D4). */
  suspend fun expiredTrash(beforeNas: Long, limit: Int): List<LocalTrashRecord>
}
