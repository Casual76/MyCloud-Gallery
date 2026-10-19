package dev.pampa.mycloudgallery.core.backup.contract

import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.NasStamp
import dev.pampa.mycloudgallery.core.model.StableCode
import dev.pampa.mycloudgallery.core.model.VersionId
import kotlin.time.Duration

// Fase 5B (docs/fase5-progetto.md §1.2 "Libera spazio (5B)", §1.5 `FreeSpaceState`, §2.5, D21, D22, D31, E-B8, E-B9, E-B14, E-B22, E-S11,
// L39; riga P0-B del §4.4): "LIBERA SPAZIO", l'unico flusso che toglie copie dal telefono. Contratto, null-object e fake di P0-B; la logica
// (guardiano, verifica sul momento, controllo del timbro, lotti, diario `free_space_run`) e' di P9.
//
// **Le regole che il contratto rende visibili** (le fa rispettare P9, le provano i suoi test e il "Fatto quando" di 5.5):
//  - un elemento finisce in un piano solo se ha una copia privata **riletta e verificata** per OGNI versione da proteggere (`SafetyRules`);
//  - la lista nasce dalla scelta dell'utente sul catalogo locale, **mai** da un dato letto dal NAS: una verifica puo' togliere un elemento, mai
//    aggiungerne (E-S11);
//  - l'avviso forte sull'assenza di backup esterno e' parte del piano ([FreeSpacePlan.warnings]): la UI lo mostra sempre (C13);
//  - la conferma ([PhoneConfirmation]) e' legata all'impronta del piano; il definitivo vuole due gesti distinti (R6 della 4B);
//  - la verifica sul momento vale 6 ore di orologio monotono e non sopravvive a un riavvio (D21, D31);
//  - appena prima di ogni dialogo si controlla il timbro di ogni copia del lotto: diverso o assente, l'elemento esce (L39);
//  - il dialogo di sistema ha lotti di al massimo 2.000 elementi; per default e' il cestino di Android (30 giorni).

/** Che cosa propone "Libera spazio" (Q9): i filtri della scheda. */
enum class FreeSpaceKind(override val code: String) : StableCode {
  /** Tutto il sicuro. */
  ALL("all"),

  /** Solo i video (i piu' pesanti). */
  VIDEOS_ONLY("videos_only"),

  /** Solo i media piu' vecchi di un anno. */
  OLDER_THAN_A_YEAR("older_than_a_year"),
}

/**
 * Il filtro del piano. Di default tutto il sicuro **tranne gli ultimi 30 giorni** (Q9: le foto recenti si guardano di piu'); [selection] e'
 * la scelta dell'utente dalla Libreria ("Libera spazio" sulla selezione): se c'e', il piano contiene al massimo quei media.
 */
data class FreeSpaceFilter(
  val kind: FreeSpaceKind = FreeSpaceKind.ALL,
  val excludeRecentDays: Int = FreeSpaceRules.DEFAULT_EXCLUDE_RECENT_DAYS,
  val selection: Set<MediaId>? = null,
) {
  init {
    require(excludeRecentDays >= 0) { "I giorni esclusi non sono negativi." }
  }

  override fun toString(): String = "FreeSpaceFilter(${kind.code}, ${excludeRecentDays}g, ${selection?.size ?: "tutti"})"
}

/** Perche' un media non entra nel piano o ne esce (la tabella "Escluso perche'" del §2.5). */
enum class FreeSpaceExclusion(override val code: String) : StableCode {
  /** Non verificato, o la verifica non e' tornata (E-B8). */
  NOT_VERIFIED("not_verified"),

  /** Una versione da proteggere (originale o attuale) senza copia privata verificata. */
  VERSION_NOT_SAFE("version_not_safe"),

  /** In caricamento (elemento aperto nel diario) o in errore (`BACKUP_PROBLEM`). */
  IN_PROGRESS_OR_ERROR("in_progress_or_error"),

  /** Area privata congelata o scritture ferme (E-B22, D32). */
  AREA_FROZEN("area_frozen"),

  /** Il NAS non risponde (E-B9). */
  NAS_UNREACHABLE("nas_unreachable"),

  /** Debug con gli account di prova: tutto cio' che non sta in `mcg-prova` (D13). */
  OUTSIDE_TEST_FENCE("outside_test_fence"),

  /** Negli ultimi giorni esclusi dal filtro (Q9). */
  RECENT("recent"),

  /** La copia sul NAS e' cambiata dopo la verifica, o e' sparita (L39): il controllo del timbro prima del dialogo. */
  COPY_CHANGED("copy_changed"),

  /** La riga del telefono non c'e' piu' (cancellata, gia' liberata). */
  GONE_FROM_PHONE("gone_from_phone"),
}

/** Gli avvisi che il foglio mostra sempre (C13). */
enum class FreeSpaceWarning(override val code: String) : StableCode {
  /** "Il NAS non ha un backup esterno: dopo, l'unica copia sara' sul NAS." Presente in **ogni** piano. */
  NO_EXTERNAL_BACKUP("no_external_backup"),

  /** "Libera subito" e' definitivo: niente cestino di Android, niente 30 giorni. */
  PERMANENT("permanent"),
}

/**
 * Un elemento liberabile: la copia del telefono ([phone]) di una versione, e il timbro della copia privata contro cui e' stata decisa
 * ([safeCopy], il controllo finale prima del dialogo). Nessun nome di file.
 */
class FreeSpaceItem(
  val mediaId: MediaId,
  val versionId: VersionId,
  val phone: PhoneRef,
  val mime: String?,
  val size: Long,
  /** Il timbro della copia privata riletta e verificata, o null finche' la verifica sul momento non l'ha riletta. */
  val safeCopy: NasStamp?,
  /** `kind` del media: i video pesano di piu' e si possono filtrare. */
  val isVideo: Boolean,
  /** Il giorno di scatto (`aaaammgg`), null se senza data: serve ai filtri "piu' vecchi di un anno" e "ultimi 30 giorni". */
  val localDay: Int?,
) {
  init {
    require(size >= 0) { "Dimensione negativa." }
  }

  override fun toString(): String = "FreeSpaceItem($size B)"
}

/**
 * Il piano di "Libera spazio" (§2.5 punto 1): gli elementi liberabili, quanti e quanti GB, gli esclusi per motivo, gli avvisi. E' il piano
 * che la conferma firma: [hash] copre il filtro, il modo, ogni elemento (media, versione, riga, dimensione, timbro verificato), quindi un piano
 * ricalcolato vuole un altro tocco. **Il piano non legge il NAS per aggiungere elementi** (E-S11): nasce dal catalogo locale.
 *
 * @property permanent "Libera subito" (`createDeleteRequest`): due gesti distinti; altrimenti il cestino di Android per 30 giorni.
 */
class FreeSpacePlan(
  val filter: FreeSpaceFilter,
  val permanent: Boolean,
  val items: List<FreeSpaceItem>,
  val excluded: Map<FreeSpaceExclusion, Int>,
  /** I media propri con la sola copia comune, per cui parte prima il guardiano (D9, E-B14): sono dentro [items] se il guardiano riesce. */
  val needsGuardian: Int = 0,
) : ConfirmablePlan {

  /** Gli avvisi: **sempre** [FreeSpaceWarning.NO_EXTERNAL_BACKUP] (C13), piu' [FreeSpaceWarning.PERMANENT] per "Libera subito". */
  val warnings: Set<FreeSpaceWarning> = buildSet {
    add(FreeSpaceWarning.NO_EXTERNAL_BACKUP)
    if (permanent) add(FreeSpaceWarning.PERMANENT)
  }

  val itemCount: Int get() = items.size

  val totalBytes: Long get() = items.sumOf { it.size }

  val isEmpty: Boolean get() = items.isEmpty()

  override val requiredConfirmations: Int get() = if (permanent) 2 else 1

  override val hash: PhonePlanHash = PhonePlanHash.builder("free-space")
    .string(filter.kind.code)
    .int(filter.excludeRecentDays)
    .list((filter.selection ?: emptySet()).map { it.value }.sorted()) { id -> string(id) }
    .bool(filter.selection != null)
    .bool(permanent)
    .list(items) { item ->
      string(item.mediaId.value)
      string(item.versionId.value)
      string(item.phone.volume)
      long(item.phone.mediaStoreId)
      long(item.size)
      long(item.safeCopy?.size)
      long(item.safeCopy?.modifiedAtMillis)
      long(item.safeCopy?.fileId)
    }
    .build()

  override fun toString(): String = "FreeSpacePlan($itemCount elementi, $totalBytes B, permanent=$permanent)"
}

/** L'id di un giro di "Libera spazio" (riga di `free_space_run`, positivo). */
@JvmInline
value class FreeSpaceRunId(val value: Long) {
  init {
    require(value > 0) { "Id di giro non valido." }
  }

  override fun toString(): String = value.toString()
}

/** A che punto e' un giro (§1.5 `FreeSpaceState`: `Guarding`, `Verifying`, `Ready`, `Confirming`, `Done`, `Blocked`). */
enum class FreeSpaceRunState(override val code: String, val terminal: Boolean = false) : StableCode {
  /** Il guardiano crea le copie private dei media con la sola copia comune (D9). */
  GUARDING("guarding"),

  /** La verifica sul momento: rilettura completa di ogni copia privata (D21 punto 3). */
  VERIFYING("verifying"),

  /** Verificato: i lotti aspettano il dialogo di sistema. */
  READY("ready"),

  /** Tutti i lotti sono stati presentati e risolti. */
  DONE("done", terminal = true),

  /** Il giro non puo' proseguire ([FreeSpaceRun.blocked]). */
  BLOCKED("blocked", terminal = true),

  /** La verifica e' scaduta (6 ore) o il processo e' stato riavviato: si rifa' dal piano. */
  EXPIRED("expired", terminal = true),

  /** L'utente ha rifiutato il dialogo di sistema: niente altro viene chiesto. */
  CANCELLED("cancelled", terminal = true),
}

/** Perche' un giro non parte o si ferma. */
enum class FreeSpaceBlock(override val code: String) : StableCode {
  /** Area privata congelata o scritture ferme (E-B22). */
  AREA_FROZEN("area_frozen"),

  /** Il NAS non risponde (E-B9). */
  NAS_UNREACHABLE("nas_unreachable"),

  /** La conferma non vale per il piano di adesso (cambiato, scaduta, troppo pochi tocchi). */
  CONFIRMATION_REJECTED("confirmation_rejected"),

  /** Nessuna implementazione legata (null-object). */
  NOT_AVAILABLE("not_available"),

  /** Android 10: senza `createTrashRequest` e `createDeleteRequest` non c'e' il dialogo di sistema e "Libera spazio" non esiste (D14). */
  NO_SYSTEM_DIALOG("no_system_dialog"),
}

/**
 * Un giro di "Libera spazio": cio' che la UI mostra (`Guarding(done, total)`, `Verifying(done, total, bytes)`, `Ready(batches, items, bytes)`,
 * `Confirming(batch, of)`, `Done(freedBytes, items, toAndroidTrash)`, `Blocked(reason)`). Solo numeri e codici.
 *
 * @property validUntilMonotonicMs fino a quando vale la verifica sul momento (orologio **monotono**, 6 ore); null prima che finisca.
 */
data class FreeSpaceRun(
  val id: FreeSpaceRunId,
  val planHash: PhonePlanHash,
  val state: FreeSpaceRunState,
  val permanent: Boolean,
  val guardedDone: Int,
  val guardedTotal: Int,
  val verifiedDone: Int,
  val verifiedTotal: Int,
  val verifiedBytes: Long,
  /** Gli elementi che hanno superato la verifica e aspettano i lotti. */
  val readyItems: Int,
  val readyBytes: Long,
  val batchesTotal: Int,
  val batchesDone: Int,
  val freedItems: Int,
  val freedBytes: Long,
  /** Gli elementi usciti durante il giro, per motivo (una copia non tornata, cambiata dopo la verifica...). */
  val dropped: Map<FreeSpaceExclusion, Int> = emptyMap(),
  val validUntilMonotonicMs: Long? = null,
  val blocked: FreeSpaceBlock? = null,
) {
  /** Il giro va ancora avanti con `verify`/`resume` o con i lotti. */
  val isActive: Boolean get() = !state.terminal
}

/** In che modo Android libera il lotto (il dialogo di sistema da lanciare). */
enum class TrashMode(override val code: String) : StableCode {
  /** `createTrashRequest`: il cestino di Android, 30 giorni. */
  ANDROID_TRASH("android_trash"),

  /** `createDeleteRequest`: "Libera subito", definitivo. */
  DELETE_NOW("delete_now"),
}

/** Un elemento di un lotto: la riga da liberare. */
class TrashItem(val mediaId: MediaId, val versionId: VersionId, val phone: PhoneRef, val mime: String?, val size: Long) {
  override fun toString(): String = "TrashItem($size B)"
}

/**
 * Un lotto di al massimo [FreeSpaceRules.MAX_BATCH] elementi, **gia' ricontrollato** (il timbro di ogni copia privata e' ancora quello
 * verificato) e pronto per il dialogo di sistema ([mode]). La UI lo traduce in `PhoneMediaIntents.trashRequest`/`deleteRequest` e lancia il
 * dialogo; la risposta torna con [FreeSpace.recordOutcome].
 */
class TrashBatch(
  val runId: FreeSpaceRunId,
  val index: Int,
  val of: Int,
  val mode: TrashMode,
  val items: List<TrashItem>,
) {
  init {
    require(items.isNotEmpty() && items.size <= FreeSpaceRules.MAX_BATCH) { "Un lotto ha da 1 a ${FreeSpaceRules.MAX_BATCH} elementi." }
    require(index in 1..of) { "Lotto fuori dal giro." }
  }

  val bytes: Long get() = items.sumOf { it.size }

  override fun toString(): String = "TrashBatch($index/$of, ${items.size} elementi, ${mode.code})"
}

/** Cosa ha risposto Android al dialogo di un lotto. */
sealed interface TrashOutcome {
  /** L'utente ha confermato per [confirmed] (tutte le righe del lotto o solo una parte): quelle sono ora fuori dal telefono. */
  data class Confirmed(val confirmed: Set<PhoneRef>) : TrashOutcome {
    override fun toString(): String = "Confirmed(${confirmed.size})"
  }

  /** L'utente ha rifiutato il dialogo: niente e' cambiato, nessun altro lotto viene proposto. */
  data object Refused : TrashOutcome

  /**
   * L'app e' stata uccisa durante il dialogo e non ha ricevuto la risposta: il lotto resta "in dubbio" finche' la lettura del telefono non
   * dice quali righe ci sono ancora (le altre sono state liberate): mai un elemento dato per liberato senza prova.
   */
  data object Unknown : TrashOutcome
}

/** Le costanti di "Libera spazio" (D21, Q9, D31). */
object FreeSpaceRules {
  /** Di default si escludono gli ultimi 30 giorni (Q9). */
  const val DEFAULT_EXCLUDE_RECENT_DAYS: Int = 30

  /** Il dialogo di sistema accetta al massimo 2.000 URI (come il cestino). */
  const val MAX_BATCH: Int = 2_000

  /** La verifica sul momento vale 6 ore di orologio monotono e non sopravvive a un riavvio (D21, D31). */
  const val VERIFICATION_VALID_MS: Long = 6L * 60 * 60 * 1000

  /** "Piu' vecchi di un anno" (Q9). */
  const val OLDER_THAN_DAYS: Int = 365

  /** Il guardiano di un giro di "Libera spazio" copia al massimo 20 GB (D9). */
  const val GUARD_MAX_BYTES: Long = 20L * 1024 * 1024 * 1024
}

/**
 * "Libera spazio" e "Elimina solo dal telefono" (D21, D22). **Niente si toglie dal NAS**: l'unico effetto e' un dialogo di sistema sul telefono,
 * lanciato dalla UI dopo un tocco e dopo il controllo del timbro di ogni copia. I media non propri, non verificati, in errore, in caricamento, con
 * una versione scoperta o con l'area congelata non entrano mai in un piano.
 */
interface FreeSpace {

  /**
   * Il piano di [filter] com'e' adesso (dal catalogo locale). Con [permanent] e' "Libera subito": due conferme distinte. Un piano vuoto e' un
   * piano valido (con l'avviso forte e i motivi degli esclusi).
   */
  suspend fun plan(filter: FreeSpaceFilter, permanent: Boolean = false): FreeSpacePlan

  /**
   * Comincia un giro per [plan], **confermato**: controlla [confirmation] contro il piano ricalcolato adesso, fa partire il guardiano per i
   * media con la sola copia comune e poi la verifica sul momento (rilettura completa di ogni copia privata), per un tempo di [budget] (una
   * tappa del lavoro avviato dall'utente). Restituisce il giro com'e': se non ha finito, [resume] lo continua.
   */
  suspend fun verify(plan: FreeSpacePlan, confirmation: PhoneConfirmation, budget: Duration): FreeSpaceRun

  /**
   * Continua un giro gia' confermato per un'altra tappa di [budget]. La conferma non si chiede di nuovo (e' dentro il giro), ma la verifica
   * resta valida solo [FreeSpaceRules.VERIFICATION_VALID_MS] dal suo inizio e un riavvio la annulla ([FreeSpaceRunState.EXPIRED]).
   */
  suspend fun resume(run: FreeSpaceRun, budget: Duration): FreeSpaceRun

  /**
   * Il prossimo lotto del giro, **ricontrollato adesso** (il timbro di ogni copia privata e' ancora quello verificato; gli elementi che non
   * tornano escono dal giro, [FreeSpaceExclusion.COPY_CHANGED]), o null se non ce ne sono piu' o il giro non e' `READY`.
   */
  suspend fun nextBatch(run: FreeSpaceRun): TrashBatch?

  /** Registra la risposta di Android al dialogo di [batch] e restituisce il giro aggiornato. */
  suspend fun recordOutcome(batch: TrashBatch, outcome: TrashOutcome): FreeSpaceRun
}
