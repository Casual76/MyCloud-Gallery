package dev.pampa.mycloudgallery.core.backup.contract

import dev.pampa.mycloudgallery.core.model.FileStamp
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.NasStamp
import dev.pampa.mycloudgallery.core.model.QuickHash
import dev.pampa.mycloudgallery.core.model.Sha256
import dev.pampa.mycloudgallery.core.model.StableCode
import dev.pampa.mycloudgallery.core.model.VersionId
import dev.pampa.mycloudgallery.core.trust.nas.SharePath

// Fase 5A (docs/fase5-progetto.md §2.2, D3, D4, D5, D33; schema v4 `backup_item`, P1): il DIARIO del backup e la macchina a stati di
// ogni elemento, come dati (sul modello di `OpStateMachine` della 4B). Tipi, tabella delle transizioni e porta di P0; l'adattatore
// Room (`RoomBackupStore`) e' di P2, l'esecuzione vera di P2 e P3; l'esecutore di riferimento (`InMemoryBackupEngine`) sta in
// `testing`.
//
// **Ogni valore di un passo si scrive prima di usarlo, ogni passo e' idempotente.** Il temporaneo e il punto di ripresa prima di
// scrivere sul NAS, il nome finale prima di pubblicare. Gli stati in "-ING" si scrivono **prima** della chiamata al NAS (la ripresa
// sa che deve controllare cosa e' successo), gli altri **dopo**. Il diario nasce solo da dati locali (catalogo, impostazioni, UI):
// niente di quanto si legge dal NAS crea elementi (E-S11).

/** Il tipo di un elemento (`backup_item.kind`). I codici non cambiano. */
enum class UploadKind(override val code: String, val phase5B: Boolean = false) : StableCode {
  /** Una copia nuova dal telefono nell'area privata (§2.2). */
  NEW_COPY("new_copy"),

  /** Il guardiano (D9): copia lato server dall'area comune a un temporaneo dell'area privata, verifica, pubblicazione. */
  GUARD_COPY("guard_copy"),

  /** 5B (D17): una versione nuova al solito percorso, l'attuale in `Versioni/`. Dichiarato: nessun esecutore della 5A lo accetta. */
  VERSION("version", phase5B = true),

  /** 5B (D18): "Ripristina originale" come scambio di due rinomine, senza caricare. Dichiarato. */
  SWAP("swap", phase5B = true),

  /** 5B (D24): un caricamento in arrivo da una condivisione, nella cartella scelta. Dichiarato. */
  INCOMING("incoming", phase5B = true),
}

/** Lo stato di un elemento (`backup_item.state`): i passi del §2.2 (5A) e del §2.4 (5B, solo dichiarati). I codici non cambiano. */
enum class UploadState(override val code: String, val terminal: Boolean = false) : StableCode {
  PLANNED("planned"),

  /** Letto per intero dal telefono: SHA-256 (e `qh1`) dei byte originali nel diario, preparazione dagli stessi byte (D10). */
  HASHED("hashed"),

  /** Si scrive prima: il temporaneo `<io>-bk-<id>` e il punto di ripresa (0, poi ogni 64 MiB). */
  UPLOADING("uploading"),

  /** `finish(size)`: lunghezza giusta sullo stesso handle. */
  UPLOADED("uploaded"),

  /** Guardiano (D9): si scrive prima il temporaneo, poi la copia lato server dall'area comune. */
  COPYING("copying"),

  /** Guardiano: la copia lato server e' finita con la dimensione giusta. */
  COPIED("copied"),

  /** Riletto per intero dal NAS (D4): SHA-256 del temporaneo uguale a quello del telefono; il timbro del temporaneo nel diario. */
  VERIFIED("verified"),

  /** Si scrive prima: il nome finale (D6; "nome (n)" dopo una collisione con un file diverso, E-B3). */
  PUBLISHING("publishing"),

  /** Pubblicato senza sostituire; il timbro del finale nel diario. */
  PUBLISHED("published"),

  /** `attachCopy(verifiedAt)` fatto nel catalogo: il media e' "al sicuro". */
  LINKED("linked"),

  // --- 5B (§2.4): solo dichiarati -------------------------------------------------------------------------------------------
  ARCHIVING("archiving"),
  ARCHIVED("archived"),
  RESTORING("restoring"),
  RESTORED("restored"),

  // --- Uscite ---------------------------------------------------------------------------------------------------------------
  DONE("done", terminal = true),

  /** D8: una copia privata identica byte per byte c'era gia' (deduplica, o il nome occupato da un file identico): collegata. */
  LINKED_EXISTING("linked_existing", terminal = true),

  /** E-B4, E-B15: la versione non e' piu' sul telefono (cancellata, o riscritta prima del caricamento). */
  GONE("gone", terminal = true),

  /** Un errore che chiede l'utente (verifica fallita tre volte, E-B18): mai "al sicuro". */
  FAILED("failed", terminal = true),
}

/** Il cammino di un elemento. Deriva dal tipo ([UploadStateMachine.flowOf]): non si salva. */
enum class UploadFlow(override val code: String, val steps: List<UploadState>, val phase5B: Boolean = false) : StableCode {
  NEW_COPY(
    "new_copy",
    listOf(
      UploadState.PLANNED, UploadState.HASHED, UploadState.UPLOADING, UploadState.UPLOADED, UploadState.VERIFIED,
      UploadState.PUBLISHING, UploadState.PUBLISHED, UploadState.LINKED, UploadState.DONE,
    ),
  ),
  GUARD_COPY(
    "guard_copy",
    listOf(
      UploadState.PLANNED, UploadState.COPYING, UploadState.COPIED, UploadState.VERIFIED, UploadState.PUBLISHING,
      UploadState.PUBLISHED, UploadState.LINKED, UploadState.DONE,
    ),
  ),

  /** 5B: versione nuova con l'attuale da archiviare in `Versioni/` (§2.4). */
  VERSION(
    "version",
    listOf(
      UploadState.PLANNED, UploadState.HASHED, UploadState.UPLOADING, UploadState.UPLOADED, UploadState.VERIFIED,
      UploadState.ARCHIVING, UploadState.ARCHIVED, UploadState.PUBLISHING, UploadState.PUBLISHED, UploadState.LINKED, UploadState.DONE,
    ),
    phase5B = true,
  ),

  /** 5B: versione nuova senza un'attuale da spostare (il solito percorso e' libero), o da salvare direttamente in `Versioni/`. */
  VERSION_DIRECT(
    "version_direct",
    listOf(
      UploadState.PLANNED, UploadState.HASHED, UploadState.UPLOADING, UploadState.UPLOADED, UploadState.VERIFIED,
      UploadState.PUBLISHING, UploadState.PUBLISHED, UploadState.LINKED, UploadState.DONE,
    ),
    phase5B = true,
  ),

  /** 5B: "Ripristina originale" (§2.4, E-E2, E-E10): due rinomine, nessun byte caricato. */
  SWAP(
    "swap",
    listOf(
      UploadState.PLANNED, UploadState.ARCHIVING, UploadState.ARCHIVED, UploadState.RESTORING, UploadState.RESTORED,
      UploadState.LINKED, UploadState.DONE,
    ),
    phase5B = true,
  ),

  /** 5B (D24): come una copia nuova, nella cartella scelta. */
  INCOMING(
    "incoming",
    listOf(
      UploadState.PLANNED, UploadState.HASHED, UploadState.UPLOADING, UploadState.UPLOADED, UploadState.VERIFIED,
      UploadState.PUBLISHING, UploadState.PUBLISHED, UploadState.LINKED, UploadState.DONE,
    ),
    phase5B = true,
  ),
}

/**
 * La macchina a stati, come dati (§2.2): i cammini ([UploadFlow.steps]) e le transizioni ammesse. P2 la esegue, il diario (Room e
 * fake) rifiuta ogni transizione che non sia qui, il banco delle 500 interruzioni (P6) controlla che il diario non ne contenga mai una.
 *
 * Una transizione e' ammessa se e':
 *  1. lo stesso stato (riscrittura idempotente: un punto di ripresa nuovo, un nome "(n)" nuovo), anche terminale;
 *  2. il passo dopo lungo il cammino;
 *  3. un **ritorno** documentato ([backEdges]): verifica non tornata → di nuovo il caricamento da zero (D4, al massimo 3 tentativi);
 *     temporaneo sparito → di nuovo il caricamento; finale sparito o cambiato dal PC prima del collegamento → di nuovo il caricamento
 *     (mai collegare byte non verificati); file del telefono cambiato fra la lettura e il caricamento → di nuovo la lettura;
 *  4. un'**uscita** ([exits]): `LINKED_EXISTING` dalla deduplica o da una collisione con un file identico, `GONE` finche' la versione
 *     serve dal telefono (non dopo che il temporaneo completo e' stato scritto: quei byte si salvano comunque), `FAILED` da qualunque
 *     stato non terminale.
 * Uno stato terminale non si lascia mai.
 */
object UploadStateMachine {

  /** Il cammino di un elemento di [kind]. [archivesCurrent] (5B) dice se una versione nuova sposta l'attuale in `Versioni/`. */
  fun flowOf(kind: UploadKind, archivesCurrent: Boolean = true): UploadFlow = when (kind) {
    UploadKind.NEW_COPY -> UploadFlow.NEW_COPY
    UploadKind.GUARD_COPY -> UploadFlow.GUARD_COPY
    UploadKind.VERSION -> if (archivesCurrent) UploadFlow.VERSION else UploadFlow.VERSION_DIRECT
    UploadKind.SWAP -> UploadFlow.SWAP
    UploadKind.INCOMING -> UploadFlow.INCOMING
  }

  /** Il passo successivo lungo il cammino, o null se [state] e' l'ultimo o non e' sul cammino. */
  fun next(flow: UploadFlow, state: UploadState): UploadState? {
    val steps = flow.steps
    val at = steps.indexOf(state)
    return if (at < 0 || at == steps.lastIndex) null else steps[at + 1]
  }

  /** I ritorni ammessi lungo il cammino (oltre al passo dopo). */
  fun backEdges(flow: UploadFlow): Set<Pair<UploadState, UploadState>> = when (flow) {
    UploadFlow.NEW_COPY, UploadFlow.INCOMING, UploadFlow.VERSION, UploadFlow.VERSION_DIRECT -> setOf(
      // D4/E-B18: la verifica non torna (o il temporaneo e' sparito): di nuovo il caricamento, da zero.
      UploadState.UPLOADED to UploadState.UPLOADING,
      UploadState.VERIFIED to UploadState.UPLOADING,
      // Risposta della pubblicazione persa e temporaneo assente, ma il finale non ha i byte giusti: il caricamento si rifa'.
      UploadState.PUBLISHING to UploadState.UPLOADING,
      // Il finale e' sparito o cambiato (dal PC) fra la pubblicazione e il collegamento: si ricarica, con un nome nuovo se occupato.
      UploadState.PUBLISHED to UploadState.UPLOADING,
      // E-B15: il file del telefono e' cambiato dopo la lettura ma prima del caricamento completo: si rilegge.
      UploadState.UPLOADING to UploadState.HASHED,
    )
    UploadFlow.GUARD_COPY -> setOf(
      UploadState.COPIED to UploadState.COPYING,
      UploadState.VERIFIED to UploadState.COPYING,
      UploadState.PUBLISHING to UploadState.COPYING,
      UploadState.PUBLISHED to UploadState.COPYING,
    )
    UploadFlow.SWAP -> emptySet()
  }

  /** Le uscite non di errore: da quali stati si puo' arrivare a `LINKED_EXISTING` e a `GONE`. */
  fun exits(flow: UploadFlow): Map<UploadState, Set<UploadState>> = when (flow) {
    UploadFlow.NEW_COPY, UploadFlow.INCOMING, UploadFlow.VERSION, UploadFlow.VERSION_DIRECT -> mapOf(
      UploadState.LINKED_EXISTING to setOf(UploadState.HASHED, UploadState.PUBLISHING),
      UploadState.GONE to setOf(UploadState.PLANNED, UploadState.HASHED, UploadState.UPLOADING),
    )
    UploadFlow.GUARD_COPY -> mapOf(
      UploadState.LINKED_EXISTING to setOf(UploadState.PLANNED, UploadState.PUBLISHING),
      // La copia comune non c'e' piu' prima che la copia lato server sia finita (E-B13 al contrario: niente da proteggere).
      UploadState.GONE to setOf(UploadState.PLANNED, UploadState.COPYING),
    )
    UploadFlow.SWAP -> emptyMap()
  }

  fun canTransition(flow: UploadFlow, from: UploadState, to: UploadState): Boolean {
    if (from == to) return true // riscrittura idempotente, anche di uno stato terminale
    if (from.terminal) return false
    if (from !in flow.steps) return false
    if (to == UploadState.FAILED) return true
    if (next(flow, from) == to) return true
    if ((from to to) in backEdges(flow)) return true
    return exits(flow)[to]?.contains(from) == true
  }

  /** Gli stati in cui un elemento si riprende dal diario: tutti i non terminali. */
  val resumable: Set<UploadState> = UploadState.entries.filterNot { it.terminal }.toSet()

  /** I tentativi di verifica prima di `FAILED` con `VERIFY_FAILED` (D4, E-B18). */
  const val MAX_VERIFY_ATTEMPTS: Int = 3
}

/** L'id di un elemento del diario (riga di `backup_item`, positivo): entra nel nome del temporaneo `<io>-bk-<id>`. */
@JvmInline
value class BackupItemId(val value: Long) {
  init {
    require(value > 0) { "Id di elemento non valido." }
  }

  override fun toString(): String = value.toString()
}

/** La sorgente di una copia del guardiano (D9): la copia dell'area comune, con il timbro visto quando si e' pianificato. */
class GuardSource(val share: String, val path: SharePath, val stamp: NasStamp?) {
  override fun equals(other: Any?): Boolean = other is GuardSource && other.share == share && other.path == path && other.stamp == stamp

  override fun hashCode(): Int = (share.hashCode() * 31 + path.hashCode()) * 31 + stamp.hashCode()

  override fun toString(): String = "GuardSource"
}

/**
 * Un elemento del diario (`backup_item`, schema v4). **Ogni valore di un passo si scrive qui prima della chiamata al NAS** (D3). I
 * percorsi, i nomi e le impronte sono dati dell'utente: mai nei log ([toString] dice solo id, tipo e stato).
 *
 * @property phone la riga del telefono (aggiornata se il MediaStore rinumera gli `_ID`, E-P12); null per il guardiano.
 * @property expected il timbro del file del telefono quando si e' pianificato: se cambia, la versione non c'e' piu' (E-B15).
 * @property share la share di destinazione (area privata del proprietario).
 * @property planned la destinazione prevista dal piano (D6): `Backup/<dispositivo>/AAAA/MM/<nome>`.
 * @property finalPath il nome finale scritto prima di `PUBLISHING`; "nome (n)" dopo una collisione con un file diverso.
 * @property nameAttempt quante collisioni con file diversi (il prossimo nome e' "nome (nameAttempt + 1)").
 * @property temp il temporaneo `.mycloudgallery/tmp/<io>-bk-<id>` (D33), scritto prima di `UPLOADING`/`COPYING`.
 * @property resumeAt il punto di ripresa (byte gia' sul NAS e controllati sullo stesso handle, D5): 0 = da capo.
 * @property sha256 lo SHA-256 dei byte del telefono (o, per il guardiano, quello atteso della versione).
 * @property quickHash `qh1` dei byte del telefono (la deduplica, D8).
 * @property tempStamp il timbro del temporaneo verificato (`VERIFIED`): dopo la pubblicazione basta confrontare questo (D4).
 * @property finalStamp il timbro del finale (`PUBLISHED`).
 * @property verifyAttempts le verifiche non tornate (al terzo `FAILED` con `VERIFY_FAILED`).
 * @property problem il problema registrato con un'uscita (`FAILED`, `GONE`): un codice di [BackupProblemCode].
 */
data class BackupItem(
  val id: BackupItemId,
  val kind: UploadKind,
  val state: UploadState,
  val mediaId: MediaId,
  val versionId: VersionId,
  val size: Long,
  val phone: PhoneRef?,
  val expected: FileStamp?,
  val share: String,
  val planned: SharePath,
  val finalPath: SharePath? = null,
  val nameAttempt: Int = 0,
  val temp: SharePath? = null,
  val resumeAt: Long = 0,
  val sha256: Sha256? = null,
  val quickHash: QuickHash? = null,
  val tempStamp: NasStamp? = null,
  val finalStamp: NasStamp? = null,
  val verifyAttempts: Int = 0,
  val guardSource: GuardSource? = null,
  val problem: BackupProblemCode? = null,
) {
  init {
    require(size >= 0) { "Dimensione negativa." }
    require(resumeAt in 0..size) { "Punto di ripresa fuori dal file." }
    require(nameAttempt >= 0 && verifyAttempts >= 0) { "Contatori negativi." }
  }

  val flow: UploadFlow get() = UploadStateMachine.flowOf(kind)

  override fun toString(): String = "BackupItem(#$id, ${kind.code}, ${state.code})"
}

/** Un elemento da creare (`PLANNED`), dal piano (P4) o dal guardiano (P3). */
class NewBackupItem(
  val kind: UploadKind,
  val mediaId: MediaId,
  val versionId: VersionId,
  val size: Long,
  val phone: PhoneRef?,
  val expected: FileStamp?,
  val share: String,
  val planned: SharePath,
  val quickHash: QuickHash? = null,
  /** Il guardiano: lo SHA-256 atteso della versione, se il catalogo lo conosce. */
  val sha256: Sha256? = null,
  val guardSource: GuardSource? = null,
) {
  init {
    require(size >= 0) { "Dimensione negativa." }
  }

  override fun toString(): String = "NewBackupItem(${kind.code}, $size B)"
}

/** Quanti elementi per stato (pagina del backup, Diagnostica): soli numeri. */
data class BackupDiaryCounts(val byState: Map<UploadState, Int>, val bytesOpen: Long) {
  val open: Int get() = byState.filterKeys { !it.terminal }.values.sum()

  companion object {
    val EMPTY: BackupDiaryCounts = BackupDiaryCounts(emptyMap(), 0)
  }
}

/**
 * La porta del diario (`backup_item`, schema v4). P2 la implementa sopra Room (`RoomBackupStore`); il fake in memoria
 * (`InMemoryBackupStore`) sta in `testing` e fa le stesse verifiche. Le scritture sono transazioni.
 */
interface BackupStore {

  /**
   * Scrive gli elementi (tutti `PLANNED`). **Idempotente per versione e tipo**: se c'e' gia' un elemento aperto (non terminale) per la
   * stessa versione e lo stesso tipo si restituisce quello, mai un secondo (nessun doppione, invariante 2 del §5.4). Un tipo della 5B
   * ([UploadKind.phase5B]) nella 5A e' un errore di programmazione.
   */
  suspend fun create(items: List<NewBackupItem>): List<BackupItem>

  suspend fun item(id: BackupItemId): BackupItem?

  /** Gli elementi da riprendere ([UploadStateMachine.resumable]), dal piu' vecchio, al massimo [limit]. */
  suspend fun itemsToResume(limit: Int): List<BackupItem>

  /**
   * Riscrive un elemento (il passo si scrive **prima** di farlo). Una transizione fuori da [UploadStateMachine], un elemento
   * sconosciuto o un cambio di tipo, media, versione, share o temporaneo gia' scritto sono errori di programmazione
   * (`IllegalStateException`/`IllegalArgumentException`).
   */
  suspend fun update(item: BackupItem)

  /** L'elemento aperto per la versione [versionId], se c'e'. */
  suspend fun openFor(versionId: VersionId): BackupItem?

  /**
   * D33: i temporanei citati dagli elementi non finiti. `:app` li passa (con quelli del diario delle operazioni) alla pulizia dei
   * propri file di core-nasops (`CitedTemps`): finche' un elemento li cita, nessuno li toglie.
   */
  suspend fun citedTemps(): Set<SharePath>

  /** Quanti elementi per stato. */
  suspend fun counts(): BackupDiaryCounts
}
