package dev.pampa.mycloudgallery.core.albums.contract

import dev.pampa.mycloudgallery.core.model.AlbumId
import dev.pampa.mycloudgallery.core.model.ContentKey
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.ShareLevel
import dev.pampa.mycloudgallery.core.model.StableCode

// IL DIARIO DELLA CONDIVISIONE (docs/fase7-progetto.md §2.5, D29, D34: tabelle `album_share_op` e `album_share_item` di P1). Contratto di
// P0: i passi di ogni elemento, le transizioni ammesse e i record che l'orchestratore (P4) salva **prima** di ogni azione, cosi' la
// ripresa e' idempotente e il banco delle interruzioni (P5) controlla gli invarianti del §5.5 sul diario. `InMemoryShareDiary` (testing/)
// e' il diario dell'esecutore di riferimento; P4 scrive quello su Room.

/** Il passo di un elemento (§2.5). I codici non cambiano. */
enum class ShareItemState(override val code: String, val terminal: Boolean = false) : StableCode {
  /** Nel diario, prima di ogni azione (§2.5 passo 3). */
  PLANNED("planned"),

  /** Il trasferimento e' chiesto alla porta (`AlbumMover`/`AlbumUploader`), con la chiave registrata prima della chiamata. */
  TRANSFERRING("transferring"),

  /** Il file e' nell'area comune e il catalogo locale lo sa (stesso `MediaId`, D29): solo da qui si accoda la voce `item:`. */
  IN_COMMON("in_common"),

  /** "Riporta": il file e' tornato nell'area privata e il catalogo lo sa. */
  IN_PRIVATE("in_private"),

  /** La voce (`item:` o la sua lapide) e' in coda al pubblicatore ([StageTicket] registrato). */
  ENTRY_STAGED("entry_staged"),

  /** La voce e' dentro una testa pubblicata. */
  PUBLISHED("published"),

  DONE("done", terminal = true),

  /** Con un [ShareFailure]: "Riprova" fa un piano nuovo. */
  FAILED("failed", terminal = true),

  CANCELLED("cancelled", terminal = true),
}

/** I cammini di un elemento per azione (§2.5): ogni passo si salva prima di quello dopo. */
object ShareStateMachine {

  private val transfersIn = listOf(
    ShareItemState.PLANNED, ShareItemState.TRANSFERRING, ShareItemState.IN_COMMON, ShareItemState.ENTRY_STAGED, ShareItemState.PUBLISHED,
    ShareItemState.DONE,
  )
  private val alreadyCommon = listOf(ShareItemState.PLANNED, ShareItemState.IN_COMMON, ShareItemState.ENTRY_STAGED, ShareItemState.PUBLISHED, ShareItemState.DONE)
  private val bringBack = listOf(
    ShareItemState.PLANNED, ShareItemState.TRANSFERRING, ShareItemState.IN_PRIVATE, ShareItemState.ENTRY_STAGED, ShareItemState.PUBLISHED,
    ShareItemState.DONE,
  )

  /** I passi di [action], nell'ordine. */
  fun steps(action: ShareItemAction): List<ShareItemState> = when (action) {
    ShareItemAction.MOVE_IN, ShareItemAction.UPLOAD -> transfersIn
    ShareItemAction.REFERENCE, ShareItemAction.ADOPT -> alreadyCommon
    ShareItemAction.MOVE_OUT -> bringBack
  }

  /**
   * Se da [from] si puo' passare a [to]: il passo dopo lungo il cammino; `FAILED` da ogni passo non terminale; `CANCELLED` solo prima che
   * il file sia arrivato (`PLANNED`, `TRANSFERRING`); per "Riporta" con l'album non piu' attivo `IN_PRIVATE` → `DONE` (nessuna lapide).
   */
  fun allowed(action: ShareItemAction, from: ShareItemState, to: ShareItemState): Boolean {
    if (from.terminal) return false
    if (to == ShareItemState.FAILED) return true
    if (to == ShareItemState.CANCELLED) return from == ShareItemState.PLANNED || from == ShareItemState.TRANSFERRING
    if (action == ShareItemAction.MOVE_OUT && from == ShareItemState.IN_PRIVATE && to == ShareItemState.DONE) return true
    val steps = steps(action)
    val at = steps.indexOf(from)
    return at >= 0 && at + 1 < steps.size && steps[at + 1] == to
  }
}

/** Un'operazione nel diario. [title], [level] e [nonceHex] servono a un album nuovo (la definizione si accoda allo `start`). */
data class ShareOpRecord(
  val id: ShareOpId,
  val album: AlbumId,
  val kind: SharePlanKind,
  val planHash: SharePlanHash,
  val title: String,
  val level: ShareLevel?,
  val nonceHex: String?,
  val folder: ShareFolder?,
  val hidden: HiddenChoice,
  val finished: Boolean = false,
) {
  override fun toString(): String = "ShareOpRecord(${kind.code}, finished=$finished)"
}

/**
 * Un elemento nel diario. **Ogni valore di un passo si scrive qui prima della chiamata che lo usa**: la chiave del trasferimento
 * ([transferKey]) prima di chiamare la porta, il biglietto ([ticket]) appena la porta risponde, il numero d'ordine della voce
 * ([stageSeq]) prima di considerarla accodata. [mine]: il media e' del mio account (guardiano D28, "Riporta").
 */
data class ShareItemRecord(
  val op: ShareOpId,
  val index: Int,
  val media: MediaId,
  val action: ShareItemAction,
  val state: ShareItemState,
  val mine: Boolean,
  val size: Long,
  val contentKey: ContentKey? = null,
  val source: NasItemRef? = null,
  val dest: NasItemRef? = null,
  val transferKey: TransferKey? = null,
  val ticket: TransferTicket? = null,
  val stageSeq: Long? = null,
  val safeCopyRequested: Boolean = false,
  val failure: ShareFailure? = null,
  val wait: ShareWait? = null,
) {
  init {
    require(state != ShareItemState.FAILED || failure != null) { "Un elemento fallito ha il suo motivo." }
  }

  override fun toString(): String = "ShareItemRecord(#$index, ${action.code}, ${state.code})"
}

/**
 * Il diario (`album_share_op`, `album_share_item`, D34). Ogni scrittura e' atomica; [create] scrive operazione ed elementi insieme. Un
 * diario e' **locale** (mai sincronizzato) e si toglie dall'azzeramento solo a operazioni ferme (§1.4).
 */
interface ShareDiary {
  suspend fun create(op: ShareOpRecord, items: List<ShareItemRecord>)

  suspend fun opByPlan(planHash: SharePlanHash): ShareOpRecord?

  suspend fun openOps(): List<ShareOpRecord>

  suspend fun items(op: ShareOpId): List<ShareItemRecord>

  /** Salva il passo nuovo di un elemento; un passo non ammesso da [ShareStateMachine] e' un errore di programmazione. */
  suspend fun update(item: ShareItemRecord)

  suspend fun finish(op: ShareOpId)
}
