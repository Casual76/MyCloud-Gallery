package dev.pampa.mycloudgallery.core.release.contract

import dev.pampa.mycloudgallery.core.model.StableCode
import java.time.Instant
import kotlinx.coroutines.flow.StateFlow

// LO STATO DEI BLOCCHI (docs/fase10-progetto.md §1.2 "Stato", §2.8, D29, D30; C441). Contratto di P0, riferimento `InMemoryReleaseState` in
// testing/, quello vero (`state/`, file atomico in `noBackupFilesDir/release/`) di P1. `ReleaseStatus` e' l'**unica** cosa del manifest che
// entra nei cancelli di scrittura esistenti e nella radice della UI (P13); gli indirizzi aggiuntivi escono solo da [ReleaseState.extraUrls].

/** Che cosa puo' fare questa versione dell'app adesso. Solo restrittivo: il manifest non apre mai niente che l'app non faccia gia'. */
sealed interface ReleaseStatus {
  /** Quanto e' severo: [Normal] 0, [ReadOnly] 1, [Stopped] 2. */
  val severity: Int

  data object Normal : ReleaseStatus {
    override val severity: Int = 0
  }

  /** Sotto la versione minima o in un blocco `WRITES`: sola lettura e l'avviso "Aggiorna" con [message] (E-REL5). */
  data class ReadOnly(val message: ReleaseMessage) : ReleaseStatus {
    override val severity: Int = 1
  }

  /** In un blocco `ALL`: solo la pagina "Questa versione e' stata fermata", nessuna sessione del NAS, nessun lavoro (E-REL6). */
  data class Stopped(val message: ReleaseMessage) : ReleaseStatus {
    override val severity: Int = 2
  }

  companion object {
    /** Il piu' severo dei due; a pari severita' [a]. */
    fun strictest(a: ReleaseStatus, b: ReleaseStatus): ReleaseStatus = if (b.severity > a.severity) b else a
  }
}

/** Che cosa e' successo a un manifest offerto (§1.2 "Stato"). */
enum class OfferResult(override val code: String) : StableCode {
  /** Valido, con `seq` piu' alto: sostituisce lo stato (puo' anche togliere un blocco). */
  APPLIED("applied"),

  /** Lo stesso `seq` gia' visto: niente cambia. */
  SAME("same"),

  /** `seq` piu' basso di quello gia' visto (rigiocato o vecchio, E-REL3): ignorato, un blocco non si toglie cosi'. */
  ROLLBACK("rollback"),

  /** Valido, `seq` piu' alto, ma **gia' scaduto**: i suoi blocchi valgono solo se piu' severi, i suoi indirizzi no (E-REL4). */
  EXPIRED_KEPT_BLOCKS("expired_kept_blocks"),

  /** Rifiutato dal verificatore (E-REL2): niente cambia, il motivo si conta. */
  REJECTED("rejected"),
}

/** I numeri per `DiagSection.RELEASE` (D39, §2.13): mai il contenuto del manifest oltre ai numeri, mai URL. */
data class ReleaseDiag(
  val seq: Long,
  val issued: Instant?,
  val status: ReleaseStatus,
  val extraUrls: Int,
  val rejected: Map<RejectReason, Int>,
)

/**
 * Lo stato del manifest per **questa** versione dell'app (D30). Regole (le prova la suite `ReleaseStateContract`):
 * 1. [status] parte da cio' che e' salvato ([load]); senza nulla, `Normal` (E-REL1);
 * 2. [offer] verifica la busta; un rifiuto non cambia niente e si conta; `seq` minore = `ROLLBACK`, uguale = `SAME`;
 * 3. `seq` maggiore e non scaduto (con `ReleaseClock.bestNow`) = `APPLIED`: lo stato diventa quello del manifest per questa `versionCode`
 *    (anche meno severo: lo toglie un manifest **piu' nuovo**); scaduto = `EXPIRED_KEPT_BLOCKS`, solo piu' severo dello stato **in vigore**
 *    (anche di quello che vale solo in memoria dopo un salvataggio morto, regola 4: precisazione di P1);
 * 4. lo stato si **salva prima** di essere pubblicato (file atomico); se il salvataggio muore, resta lo stato salvato di prima, ma un blocco
 *    piu' severo vale comunque in memoria finche' il processo vive;
 * 5. una `versionCode` diversa da quella salvata (l'app si e' aggiornata) ricalcola lo stato dall'ultimo manifest valido;
 * 6. [extraUrls] solo per un modello con lo **stesso** id e la **stessa** impronta, solo da un manifest non scaduto; nessun controllo
 *    dell'host qui (lo fa `PinnedUrl.of` in :app);
 * 7. [clear] (azzeramento, D42) torna a `Normal` e dimentica tutto; la prima lettura dopo rimette il blocco.
 */
interface ReleaseState {
  val status: StateFlow<ReleaseStatus>

  val diag: StateFlow<ReleaseDiag>

  suspend fun load()

  suspend fun offer(bytes: ByteArray, origin: ManifestOrigin): OfferResult

  fun extraUrls(id: String, sha256: String): List<String>

  suspend fun clear()
}
