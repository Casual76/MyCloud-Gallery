package dev.pampa.mycloudgallery.core.index.contract

import dev.pampa.mycloudgallery.core.model.ContentKey
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.model.NasStamp
import dev.pampa.mycloudgallery.core.model.VersionId
import dev.pampa.mycloudgallery.core.trust.data.SegmentClass
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import kotlinx.coroutines.flow.StateFlow

// Le UNITA' DI LAVORO con prenotazioni (docs/fase4-progetto.md §2.1 "Unita' di lavoro", D13, E-I1, E-I2, E-I15, E-I16;
// docs/formato-nas.md §5.6, M4). Contratto di P0, implementazione di P3 (`work/`, sopra `LeaseManager`). La usa la
// preparazione (P6, `PreparationRun`).

/** Il passaggio di lavoro di un'unita'. Il nome finisce nella chiave (M4). */
enum class WorkPass(val wire: String) {
  /** La preparazione della Fase 4 (miniature, anteprime, fotogrammi). */
  PREP("prep"),

  /**
   * Fase 6 (D9, formato-nas.md §5.6 A5): il calcolo dei dati AI, `work/1/ai/<mese>/<parte>`. Stesse parti da 200 media per chiave
   * di contenuto, stessa prenotazione di 1 ora rinnovata ogni 20 minuti; "fatto" per classe da `AiPublishedClasses`.
   */
  AI("ai"),
}

/**
 * Un'unita' di lavoro: (share, passaggio, mese di scatto, parte). La parte si ricava ordinando per chiave di contenuto i
 * media del mese che mancano delle classi richieste e dividendo per `TrustLimits.WORK_UNIT_MAX_ITEMS` (200).
 *
 * @property month `aaaa-mm` del mese di scatto, `0000-00` per i media senza data.
 */
data class WorkUnitKey(val share: String, val pass: WorkPass, val month: String, val part: Int) {
  init {
    require(MONTH.matches(month)) { "Mese non valido: aaaa-mm." }
    require(part >= 0) { "Parte negativa." }
  }

  /**
   * La risorsa della prenotazione (formato-nas.md §5.6, M4): `work/1/<passaggio>/<aaaa-mm>/<parte>`. **Mai nomi di
   * cartelle o di file**: la chiave sta in chiaro nel record firmato e nel nome della cartella delle prenotazioni.
   */
  val resource: String get() = "work/1/${pass.wire}/$month/$part"

  override fun toString(): String = "WorkUnitKey($resource)"

  companion object {
    private val MONTH = Regex("^(0000-00|[0-9]{4}-(0[1-9]|1[0-2]))$")

    const val NO_DATE: String = "0000-00"
  }
}

/** Un media da lavorare dentro un'unita'. */
data class WorkItem(
  val contentKey: ContentKey,
  /** Il percorso nella share, dal catalogo locale (mai da un record remoto, D8): per aprire l'originale. */
  val path: SharePath,
  /** Il timbro dell'ultimo elenco: chi apre controlla che non sia cambiato (altrimenti salta e la scansione rilevera'). */
  val stamp: NasStamp,
  val kind: MediaKind,
  val mime: String?,
  val mediaId: MediaId?,
  val versionId: VersionId?,
  /** Le classi che mancano per questa chiave (gia' tolte quelle che altri hanno pubblicato). */
  val missing: Set<SegmentClass>,
  /** D14: le famiglie di decoder che hanno gia' fallito con `DECODER_MISSING` (dai record degli altri). */
  val failedDecoders: Set<String> = emptySet(),
) {
  override fun toString(): String = "WorkItem($kind, ${stamp.size} B, missing=$missing)"
}

/** Lo stato della prenotazione di un'unita'. */
sealed interface UnitLease {
  /** Prenotata con la generazione [generation] fino a [untilNasMillis] (ora del NAS). */
  data class Held(val generation: Long, val untilNasMillis: Long) : UnitLease

  /** Cartella delle prenotazioni anomala (`Contended`): si lavora senza prenotazione e si conta in Diagnostica. */
  data object Unleased : UnitLease
}

/** Un'unita' presa da questo dispositivo. */
data class UnitClaim(val key: WorkUnitKey, val items: List<WorkItem>, val lease: UnitLease) {
  init {
    require(items.isNotEmpty()) { "Un'unita' senza lavoro non si prende." }
  }
}

/** Esito di [WorkUnits.keepAlive]. */
sealed interface KeepAliveResult {
  data class Renewed(val claim: UnitClaim) : KeepAliveResult

  /** Un altro l'ha presa (la nostra e' scaduta): si finisce il media in corso e si lascia l'unita'. */
  data object Lost : KeepAliveResult
}

/** Com'e' finita un'unita'. */
sealed interface UnitOutcome {
  data class Completed(val prepared: Int, val failed: Int) : UnitOutcome

  /**
   * Interrotta (tempo della tappa finito, regole, NAS irraggiungibile): [code] stabile, mai un messaggio. [STALLED] (XS2-2): l'unita' e' stata
   * tentata ma **non ha dato lavoro** (ogni media in attesa: anteprima non pronta); chi sceglie le unita' la mette in fondo per un po'.
   */
  data class Interrupted(val code: String) : UnitOutcome {
    companion object {
      const val STALLED: String = "STALLED"
    }
  }
}

/**
 * Cosa c'e' sullo schermo adesso, come chiavi di contenuto ridotte (`MediaRef.Nas.contentKey`): :app lo lega alla
 * finestra visibile della griglia (come `MetaPriority` della Fase 3), cosi' le unita' con media visibili vengono prima
 * (D12, proposta 11).
 */
interface UnitPriority {
  /** Le chiavi che passano **davanti** (e a turno): sullo schermo, o le poche richieste che hanno il loro turno adesso. Dice la quota e l'ordine del servizio prioritario. */
  val visible: StateFlow<Set<Long>>

  /**
   * XS3-1 (Pacchetto XF, giro 3): l'**insieme completo** delle chiavi che l'utente ha chiesto (sono di piu' di [visible]: il registro ne fa passare davanti al piu' 16 e
   * le altre aspettano il loro turno). Dice **quali mesi appartengono alla classe delle richieste**: un mese di una richiesta che aspetta non e' un mese ordinario, e non
   * deve riempire la finestra dei mesi ordinari al posto del mese vecchio con il lavoro pronto. Di serie coincide con [visible] (chi ha solo quelle: la preparazione, le prove).
   */
  val requested: StateFlow<Set<Long>> get() = visible
}

/**
 * Le unita' di lavoro di questo dispositivo (D13).
 *
 * - [next]: sceglie l'unita' piu' recente con lavoro (prima quelle con media visibili), **rilegge le teste** della share
 *   e toglie cio' che altri hanno gia' pubblicato, poi prenota la risorsa per 1 ora. `Busy`/`Lost` → l'unita'
 *   successiva; `Contended` → [UnitLease.Unleased]. `null` = niente da fare (o scrittura bloccata da [IndexWriteGate]:
 *   in quel caso si lavora in solo locale senza prenotare, e l'implementazione restituisce comunque l'unita' con
 *   [UnitLease.Unleased]).
 * - [keepAlive]: ogni 20 minuti, generazione nuova (M4).
 * - [finish]: rilascia (durata 0).
 */
interface WorkUnits {
  suspend fun next(share: String, pass: WorkPass, priority: UnitPriority): UnitClaim?

  suspend fun keepAlive(claim: UnitClaim): KeepAliveResult

  suspend fun finish(claim: UnitClaim, outcome: UnitOutcome)
}
