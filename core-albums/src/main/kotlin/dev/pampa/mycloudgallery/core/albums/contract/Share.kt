package dev.pampa.mycloudgallery.core.albums.contract

import dev.pampa.mycloudgallery.core.model.AlbumId
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.ShareLevel
import dev.pampa.mycloudgallery.core.model.StableCode
import kotlin.time.Duration
import kotlinx.coroutines.flow.StateFlow

// LA CONDIVISIONE (docs/fase7-progetto.md §1.2 "Condivisione", §2.5, §2.6; D12, D28, D29, D31, D38, D39; E-SH1-E-SH4, E-SH8,
// E-SH12, E-SH18, E-SH19). Contratto di P0; l'orchestratore vero (share/) e' di P4, il banco delle 500 interruzioni di P5. L'esecutore di
// riferimento `InMemoryShareRunner` e la suite `ShareRunnerContract` gli invarianti del §5.5 li provano da subito.
//
// **La regola piu' importante della fase** (D12, C10, C26): uno spostamento nell'area comune parte **solo** da un'azione locale
// confermata: `plan` (niente scritto) → foglio → `ShareConfirmation` (costruttore `internal`, solo dal foglio) → `start` (diario prima
// di ogni azione, porte chiamate con la conferma). Nessun dato letto dal NAS (definizioni, indice, cestini) entra qui.

/** I nascosti fra i media da condividere (D12, risposta Q3 (a)). */
enum class HiddenChoice(override val code: String) : StableCode {
  /** «Lascia fuori i N nascosti» (preselezionata, come R11). */
  LEAVE_OUT("leave_out"),

  /** «Condividi anche i nascosti», con l'avviso E-SH9: "diventeranno visibili ai familiari". */
  SHARE_TOO("share_too"),
  ;

  companion object {
    val DEFAULT: HiddenChoice = LEAVE_OUT
  }
}

/** Cosa chiede l'utente (§1.2). Ogni richiesta diventa un piano da confermare. */
sealed interface ShareRequest {
  /** Un album condiviso nuovo con [media]: l'id nasce legato al mio account (D7). */
  data class NewAlbum(val title: String, val level: ShareLevel, val media: List<MediaId>, val hidden: HiddenChoice = HiddenChoice.DEFAULT) : ShareRequest {
    override fun toString(): String = "ShareRequest.NewAlbum(${media.size})"
  }

  /**
   * "Condividi" su un album personale (manuale o smart): un album condiviso **nuovo** (id suo, nonce nuovo) con gli elementi mostrabili
   * dell'album; la voce personale `shared` collega i due (D4). Per uno smart le regole restano personali e i nuovi sicuri vanno in "Da
   * condividere" (D17, Q4 (a)).
   */
  data class FromPersonal(val album: AlbumId, val level: ShareLevel, val hidden: HiddenChoice = HiddenChoice.DEFAULT) : ShareRequest {
    override fun toString(): String = "ShareRequest.FromPersonal"
  }

  /** "Aggiungi ad album" su un album condiviso attivo (D35), "Da condividere" compreso. */
  data class AddToShared(val album: AlbumId, val media: List<MediaId>, val hidden: HiddenChoice = HiddenChoice.DEFAULT) : ShareRequest {
    override fun toString(): String = "ShareRequest.AddToShared(${media.size})"
  }

  /** "Riporta nelle mie cartelle" (D31): solo media **propri** con la cartella d'origine; verso `shared_origin` o `Backup/<dispositivo>/AAAA/MM/`. */
  data class BringBack(val album: AlbumId, val media: List<MediaId>) : ShareRequest {
    override fun toString(): String = "ShareRequest.BringBack(${media.size})"
  }

  /** D39: adotta i file aggiunti dal PC nella cartella dell'album (gia' nell'area comune: nessuno spostamento). */
  data class FolderAdoption(val album: AlbumId, val media: List<MediaId>) : ShareRequest {
    override fun toString(): String = "ShareRequest.FolderAdoption(${media.size})"
  }
}

/** Che lavoro fa un piano. */
enum class SharePlanKind(override val code: String) : StableCode {
  SHARE("share"),
  BRING_BACK("bring_back"),
  ADOPT("adopt"),
}

/** Cosa succede a un media del piano (§2.5 passo 1). */
enum class ShareItemAction(override val code: String) : StableCode {
  /** Dall'area privata propria alla cartella dell'album (`AlbumMover`, spostamento tra share di core-nasops). */
  MOVE_IN("move_in"),

  /** Solo sul telefono: caricamento diretto nella cartella (`AlbumUploader`, `INCOMING`, E-SH1). */
  UPLOAD("upload"),

  /** Gia' nell'area comune: resta dov'e', la voce porta il suo percorso (E-SH11). */
  REFERENCE("reference"),

  /** Un file aggiunto dal PC nella cartella dell'album (D39): resta dov'e'. */
  ADOPT("adopt"),

  /** "Riporta": dall'area comune all'area privata propria (`AlbumMover`). */
  MOVE_OUT("move_out"),
  ;

  /** Se l'azione sposta o carica un file (e quindi chiede spazio e una porta). */
  val transfers: Boolean get() = this == MOVE_IN || this == UPLOAD || this == MOVE_OUT
}

/** Un media del piano. [hidden]: nascosto e condiviso lo stesso (solo con [HiddenChoice.SHARE_TOO]). */
data class SharePlanItem(val media: MediaId, val action: ShareItemAction, val size: Long, val isVideo: Boolean, val hidden: Boolean) {
  override fun toString(): String = "SharePlanItem(${action.code})"
}

/** Perche' un media resta fuori dal piano (§2.5 passo 1). */
enum class ShareExclusion(override val code: String) : StableCode {
  /** Nell'area privata di un altro account: non si puo' aggiungere. */
  OTHERS_AREA("others_area"),

  /** Nascosto, con «Lascia fuori» (Q3). */
  HIDDEN_LEFT_OUT("hidden_left_out"),

  /** Gia' nell'album. */
  ALREADY_IN_ALBUM("already_in_album"),

  /** Non piu' nel catalogo, o senza una copia leggibile. */
  UNAVAILABLE("unavailable"),

  /** "Riporta": non e' un media mio, o non ha la cartella d'origine. */
  NOT_MINE("not_mine"),

  /** "Riporta" o adozione: non e' (piu') nell'area comune. */
  NOT_IN_COMMON("not_in_common"),

  /**
   * Revisione B, SG-1 e I4: solo sul telefono, ma il caricamento non puo' partire: il cancello degli arrivi del backup lo ferma (D12: area non di
   * prova; R2 della 5A: release con un account di prova) o la cartella dell'album non e' sotto la radice dei caricamenti ("Album condivisi", dopo
   * E-SH16 verso una cartella fuori). Il foglio lo dice invece di promettere un caricamento che non partirebbe.
   */
  UPLOAD_BLOCKED("upload_blocked"),
}

/**
 * Il piano unico da confermare (D12): conteggi, spazio richiesto, cartella, livello, nascosti; [hash] lega la conferma a **questo**
 * piano. Scade dopo [ShareConfirmationRules.MAX_AGE_MS] dalla creazione (come le conferme). Niente percorsi: [folderLabel] e' il nome che
 * il foglio mostra ("Album condivisi/Oslo 2026"). [nonceHex] solo per un album nuovo (D7: l'id si ricalcola da account e nonce).
 *
 * Revisione B:
 * - [attempt] (SH-1): un valore casuale per ogni piano chiesto con `ShareRunner.plan`, nell'impronta. Lo stesso piano (lo stesso oggetto,
 *   la stessa conferma) non parte due volte; un piano nuovo con gli stessi media, dopo un'operazione finita con dei problemi ("Riprova", lo
 *   stesso "Aggiungi" ripetuto), ha un'impronta sua e parte. Due operazioni aperte sugli stessi media restano impossibili per un'altra regola:
 *   un media che una mia operazione aperta sta portando resta fuori da ogni altro piano. Vuoto = nessun tentativo (piani dei fake vecchi).
 * - [safeCopyBlocked] (SG-1): quanti miei media, spostati dall'area privata senza copia sul telefono, non avranno subito la copia privata del
 *   guardiano (D28) perche' il cancello del backup sull'area privata la ferma: il foglio lo dice invece di prometterla.
 */
class SharePlan(
  val request: ShareRequest,
  val kind: SharePlanKind,
  val album: AlbumId,
  val title: String,
  val folderLabel: String,
  val level: ShareLevel?,
  val nonceHex: String?,
  val items: List<SharePlanItem>,
  val excluded: Map<ShareExclusion, Int>,
  val hidden: HiddenChoice,
  val createdAtMonotonicMs: Long,
  val attempt: String = "",
  val safeCopyBlocked: Int = 0,
) {
  init {
    require(items.isNotEmpty()) { "Un piano senza media." }
    require(items.map { it.media }.toSet().size == items.size) { "Un media due volte nello stesso piano." }
    require(attempt.length <= 64 && attempt.all { it in '0'..'9' || it in 'a'..'f' }) { "Un tentativo e' esadecimale minuscolo, al piu' 64 cifre." }
    require(safeCopyBlocked in 0..items.size) { "Copie del guardiano ferme fuori misura." }
  }

  val photos: Int get() = items.count { !it.isVideo }
  val videos: Int get() = items.count { it.isVideo }

  /** I byte che si spostano o si caricano. */
  val bytes: Long get() = items.filter { it.action.transfers }.sumOf { it.size }

  /** Lo spazio richiesto a destinazione: i byte piu' 1 GiB, come gli spostamenti della 4B (0 se niente si sposta). */
  val requiredBytes: Long get() = if (bytes == 0L) 0L else bytes + ShareConfirmationRules.SPACE_MARGIN_BYTES

  val hiddenIncluded: Int get() = items.count { it.hidden }
  val hiddenLeftOut: Int get() = excluded[ShareExclusion.HIDDEN_LEFT_OUT] ?: 0

  val hash: SharePlanHash = SharePlanHash.of(this)

  override fun toString(): String = "SharePlan(${kind.code}, ${items.size} media, $bytes B)"
}

/** Perche' un piano non si puo' fare adesso (il foglio lo dice e non offre la conferma). */
enum class ShareBlock(override val code: String) : StableCode {
  /** D30: "Arriva dopo le prove". */
  TEST_SHARES_ONLY("test_shares_only"),

  /** Cancelli di D38 (congelamento, emergenza, salto dell'ora, fiducia), E-SH19. */
  WRITES_BLOCKED("writes_blocked"),

  /** Area comune o privata in sola lettura (E-SH19). */
  READ_ONLY("read_only"),
  NO_NAS("no_nas"),

  /** Oltre [SharedAlbumLimits.MAX_ITEMS] elementi (detto nel foglio). */
  TOO_MANY_ITEMS("too_many_items"),

  /** L'album non e' piu' attivo o non c'e'. */
  ALBUM_NOT_ACTIVE("album_not_active"),
}

sealed interface SharePlanOutcome {
  data class Ready(val plan: SharePlan) : SharePlanOutcome

  data class Blocked(val reason: ShareBlock) : SharePlanOutcome

  data class NotAllowed(val op: AlbumOp) : SharePlanOutcome

  /** Nessun media si puo' condividere: tutti esclusi, con il motivo. */
  data class NothingToShare(val excluded: Map<ShareExclusion, Int>) : SharePlanOutcome

  /** Il titolo di un album nuovo non va bene ([AlbumTitles]). */
  data class InvalidTitle(val problem: TitleProblem) : SharePlanOutcome
}

/** L'identita' di un'operazione di condivisione nel diario (`album_share_op`). Mai nei log. */
@JvmInline
value class ShareOpId(val value: String) {
  init {
    require(value.isNotEmpty() && value.length <= 64 && value.all { it.isLetterOrDigit() || it == '-' }) { "Id di operazione non valido." }
  }

  override fun toString(): String = "ShareOpId"
}

sealed interface ShareStartOutcome {
  /** Il diario ha l'operazione (`PLANNED`) e i trasferimenti sono chiesti: il resto lo fa [ShareRunner.resumeAll]. */
  data class Started(val op: ShareOpId) : ShareStartOutcome

  /** Lo stesso piano era gia' partito (idempotenza): nessun secondo spostamento. */
  data class AlreadyStarted(val op: ShareOpId) : ShareStartOutcome

  data class ConfirmationRejected(val check: ShareConfirmationCheck) : ShareStartOutcome

  /** Il catalogo e' cambiato dopo il piano (media spostati, nascosti, spariti): serve un piano e un foglio nuovi. */
  data object PlanChanged : ShareStartOutcome

  data class Blocked(val reason: ShareBlock) : ShareStartOutcome
}

/** Perche' un'operazione aspetta (§1.5 `ShareProgress.Paused`). Si riprende da sola quando il motivo passa. */
enum class ShareWait(override val code: String) : StableCode {
  /** Spazio finito sull'area comune (E-SH8): `SHARE_PAUSED_SPACE`. */
  NO_SPACE("no_space"),

  /** Area congelata o cancelli di D38 (E-SH19): `SHARE_PAUSED_FROZEN`. */
  WRITES_BLOCKED("writes_blocked"),

  /** File aperti dal PC (fino a 24 ore, F4): `SHARE_WAITING_LOCKED`. */
  LOCKED("locked"),

  NAS_UNREACHABLE("nas_unreachable"),

  /** Il guardiano, il caricamento o il pubblicatore aspettano le loro regole (Wi-Fi di casa, attivita' pesante). */
  RULES("rules"),

  /**
   * Revisione B, I2: la copia e' nell'area comune ma il catalogo non la riconosce ancora come lo stesso media (porta di completamento, D29): la
   * sorgente resta al suo posto e si riprova a intervalli crescenti, al piu' per 24 ore (poi l'elemento si ferma e le due copie restano).
   */
  CATALOG("catalog"),
}

/** Perche' un elemento non e' andato (§2.5 "Errori"). "Riprova" fa un piano nuovo. */
enum class ShareFailure(override val code: String) : StableCode {
  /** E-SH18: il file sorgente e' cambiato (dal PC): entrambe le copie restano. `SHARE_SOURCE_CHANGED`. */
  SOURCE_CHANGED("source_changed"),

  /** Il file non c'e' piu'. */
  SOURCE_GONE("source_gone"),

  /** E-SH13: il livello non lo permette piu' (il file gia' spostato resta, "Riporta" e' offerto). */
  NOT_ALLOWED("not_allowed"),

  /** Il trasferimento e' fallito per un motivo non passeggero. */
  TRANSFER_FAILED("transfer_failed"),

  /** Il processo e' morto prima che il trasferimento partisse e la conferma non c'e' piu': niente si e' mosso. */
  NOT_STARTED("not_started"),
}

/** Lo stato di un'operazione per la UI (§1.5 `ShareProgress`): pagina dell'album e Attivita'. */
sealed interface ShareOpState {
  val op: ShareOpId
  val album: AlbumId
  val kind: SharePlanKind

  data class Running(override val op: ShareOpId, override val album: AlbumId, override val kind: SharePlanKind, val done: Int, val total: Int) : ShareOpState

  data class Paused(
    override val op: ShareOpId,
    override val album: AlbumId,
    override val kind: SharePlanKind,
    val reason: ShareWait,
    val done: Int,
    val total: Int,
    /**
     * Revisione B (SH-2): quanti elementi aspettano **prima** di partire (rifiutati dalla porta: spazio, scritture ferme). Partono da soli solo
     * finche' la conferma vale (10 minuti): poi diventano "da riprovare". La striscia lo dice invece di promettere "riprendo da sola".
     */
    val notStarted: Int = 0,
  ) : ShareOpState

  data class Done(override val op: ShareOpId, override val album: AlbumId, override val kind: SharePlanKind, val done: Int) : ShareOpState

  data class PartlyDone(
    override val op: ShareOpId,
    override val album: AlbumId,
    override val kind: SharePlanKind,
    val done: Int,
    val failures: Map<ShareFailure, Int>,
    val cancelled: Int,
  ) : ShareOpState
}

/** Perche' [ShareRunner.resumeAll] si e' fermato. */
enum class ShareRunStop { FINISHED, BUDGET, WAITING }

data class ShareRunResult(val ops: Int, val itemsDone: Int, val itemsFailed: Int, val itemsWaiting: Int, val stoppedBy: ShareRunStop)

/**
 * L'orchestratore della condivisione e del "Riporta" (§2.5, §2.6). [operations]: le operazioni non ancora viste finite e, dopo la revisione B
 * (SH-2), anche quelle finite con elementi da riprovare (lette dal diario: restano dopo un riavvio, finche' c'e' qualcosa da riprovare). [resumeAll]
 * avanza ogni elemento lungo il suo cammino ([ShareStateMachine]) dal diario: idempotente, riprende dopo la morte del processo, un guasto
 * passeggero non diventa mai un errore permanente (invariante 8).
 */
interface ShareRunner {
  val operations: StateFlow<List<ShareOpState>>

  /** Il piano (§2.5 passo 1): nulla scritto, nessuna porta chiamata. */
  suspend fun plan(request: ShareRequest): SharePlanOutcome

  /** Parte solo con una conferma valida per **questo** piano (D12, C26): diario `PLANNED`, poi i trasferimenti con la conferma. */
  suspend fun start(plan: SharePlan, confirmation: ShareConfirmation): ShareStartOutcome

  suspend fun resumeAll(budget: Duration): ShareRunResult

  /** Annulla cio' che non e' ancora partito; un trasferimento in corso lo ferma la sua porta (nessun file a meta', §5.5). */
  suspend fun cancel(op: ShareOpId)

  /**
   * Revisione B, SH-2 ("Riprova", E-SH18, §2.5 "Errori"): la richiesta per gli elementi di [op] non andati e ancora da riprovare (non fatti, non
   * in viaggio in un'altra operazione, non tornati da soli); null se non c'e' niente. **Non parte niente**: la richiesta diventa un piano nuovo
   * (`plan`), un foglio e una conferma dell'utente, come ogni condivisione.
   */
  suspend fun retryRequest(op: ShareOpId): ShareRequest? = null

  /** Revisione B, SH-2: la striscia dei problemi di [op] non si mostra piu' (su questo dispositivo). Niente si sposta. */
  suspend fun dismiss(op: ShareOpId) = Unit
}
