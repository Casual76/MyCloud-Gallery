package dev.pampa.mycloudgallery.core.faces.contract

import dev.pampa.mycloudgallery.core.model.StableCode
import kotlin.time.Duration
import kotlinx.coroutines.flow.StateFlow

// IL CALCOLO DEI VOLTI DELL'ACCOUNT (docs/fase8-progetto.md §1.2 "Calcolo", "Stato del lavoro", §2.3, D16-D19, E-F13-E-F19, E-A5, E-A6).
// Contratti di P0; il calcolo vero (compute/: unita' sotto la generazione, ingressi, passate, salvataggio subito, outbox) e' di P4, il lavoro
// `FacesComputeWorker` di P13. Gira sugli **indicizzatori dell'account** con Persone attiva qui, con le regole di `AI_COMPUTE` e tappe da 8
// minuti; **ogni account rifa' il calcolo per se'**, anche per le foto dell'area comune (proposta 13, D11). Un'area di un altro account non e'
// mai un ambito. Solo numeri e codici verso fuori (D37).

/** Le due aree di un'unita' dei volti (D16): mai il nome della share. Il codice e' quello del percorso della risorsa (`priv`, `comm`). */
enum class AreaKind(override val code: String) : StableCode {
  PRIVATE("priv"),
  COMMON("comm"),
}

/**
 * L'ambito di una tappa (§2.3): un'area dell'account ([share] e' l'area privata dichiarata o l'area comune del registro, **mai** l'area
 * privata di un altro account) o i media del solo telefono (risultati **solo locali** finche' il media non arriva sul NAS).
 */
sealed interface FacesScope {
  data class Area(val share: String, val kind: AreaKind) : FacesScope {
    override fun toString(): String = "FacesScope.Area(${kind.code})"
  }

  data object PhoneOnly : FacesScope
}

/** Perche' una tappa si e' fermata (§2.3). Un codice stabile: va in Diagnostica come `FACES_*`. */
enum class FacesStop(override val code: String) : StableCode {
  /** Niente piu' da fare in questo ambito. */
  DONE("done"),

  /** La tappa (8 minuti) e' finita: si riprende dal media dopo. */
  BUDGET("budget"),

  /** Le regole di `AI_COMPUTE` (batteria, caldo `SEVERE`, rete) dicono di fermarsi. */
  RULES("rules"),

  /** Persone non e' attiva qui (spenta, in attesa dell'avviso, esclusa, voce sconosciuta). */
  NOT_ACTIVE("not_active"),

  /** Questo dispositivo non e' un indicizzatore dell'account. */
  NOT_INDEXER("not_indexer"),

  /** La generazione e' cambiata a meta' (attivazione nuova, cancellazione): la tappa si ferma e si riconcilia (§2.8). */
  GENERATION_CHANGED("generation_changed"),

  /** I modelli dei volti non ci sono o non tornano con l'impronta (`FACES_MODEL_MISMATCH`). */
  MODELS_UNAVAILABLE("models_unavailable"),

  /** Il motore fallisce due volte di fila (`FACES_ENGINE_FAILED`). */
  ENGINE_FAILED("engine_failed"),

  /** Il NAS non risponde: fatto cio' che c'era in locale (E-A6). */
  NO_NAS("no_nas"),

  /** "Disattiva e cancella" in corso. */
  ERASING("erasing"),

  /** Sotto 1 GiB libero (E-A17): buffer svuotato e calcolo fermo. */
  STORAGE_LOW("storage_low"),

  /**
   * P4: il database dei volti non si apre adesso (Keystore che non risponde, file bloccato, database di una versione piu' nuova di
   * quella dell'app). Non e' un errore e non cancella niente: si riprova alla tappa dopo.
   */
  STORE_UNAVAILABLE("store_unavailable"),
}

/** Il resoconto di una tappa: solo numeri (D37). [waiting] = media che aspettano un'anteprima da nessuna parte (D17, `FACES_WAITING_PREVIEWS`). */
data class FacesRunResult(
  val analyzed: Int,
  val faces: Int,
  val skipped: Int,
  val unsupported: Int,
  val waiting: Int,
  val stop: FacesStop,
) {
  companion object {
    fun stopped(stop: FacesStop) = FacesRunResult(0, 0, 0, 0, 0, stop)
  }
}

/**
 * Una tappa del calcolo dei volti (§2.3): prima l'area privata, poi l'area comune, poi i media del solo telefono; dentro, dal mese piu'
 * recente. **Salva subito** per chiave di contenuto (un media gia' salvato non si rifa' dopo una morte del processo), poi l'outbox della
 * pubblicazione; a fine unita' il raggruppamento (custode) o l'assegnazione provvisoria. Idempotente; mai un'eccezione per un caso previsto.
 */
interface FacesComputeRun {
  suspend fun run(scope: FacesScope, budget: Duration): FacesRunResult
}

/** Perche' il calcolo aspetta (§1.2 `FacesStatus`). */
enum class FacesWait(override val code: String) : StableCode {
  NOTICE("notice"),
  EXCLUDED("excluded"),
  NOT_INDEXER("not_indexer"),
  RULES("rules"),
  NAS("nas"),
  PREVIEWS("previews"),
  MODELS("models"),
  ERASING("erasing"),
  NEEDS_UPDATE("needs_update"),

  /** P4: la tappa e' finita per tempo e ce n'e' un'altra in coda: "riprendo fra poco" (non e' un'attesa delle regole, come IX-2 della Fase 6). */
  NEXT_STAGE("next_stage"),

  /** P4: poco spazio sul telefono (sotto 1 GiB, E-A17) o database dei volti non disponibile adesso. */
  STORAGE("storage"),
}

/** La fase del lavoro in corso. */
enum class FacesPhase(override val code: String) : StableCode {
  DETECT("detect"),
  HQ("hq"),
  EMBED("embed"),
  GROUPING("grouping"),
  PUBLISH("publish"),
}

/** Lo stato del calcolo per la pagina delle Impostazioni e la Diagnostica (§1.2): solo numeri e codici. */
sealed interface FacesStatus {
  data object Off : FacesStatus

  data class Waiting(val reason: FacesWait) : FacesStatus

  data class Running(val done: Int, val total: Int, val phase: FacesPhase) : FacesStatus

  /** Fermo e in pari; [atNas] = l'ora del NAS dell'ultimo giro completo (null se non si sa). */
  data class Idle(val atNas: Long?) : FacesStatus

  data class Problem(val code: String) : FacesStatus
}

/** Chi espone lo stato del calcolo (P4, P13). */
interface FacesStatusSource {
  val status: StateFlow<FacesStatus>
}

/** I numeri del calcolo (§2.3, D16-D18). P4 li usa; un cambio passa dal progetto. */
object FacesComputeLimits {
  /** Media per unita' (parte di un mese, in ordine di chiave di contenuto), gli stessi confini su ogni dispositivo. */
  const val UNIT_MAX_ITEMS: Int = 200

  /** La tappa del lavoro (regole di `AI_COMPUTE`, `StagedRun`). */
  const val STAGE_MINUTES: Int = 8

  /** La prenotazione di un'unita' e del custode: 1 ora, rinnovo ogni 20 minuti. */
  const val LEASE_MILLIS: Long = 60L * 60 * 1000
  const val LEASE_RENEW_MILLIS: Long = 20L * 60 * 1000

  /** Fotogrammi di un video oltre la copertina (D18); i loro volti sono al piu' WEAK. */
  const val FRAMES_PER_VIDEO: Int = 3

  /** La ricetta corrente degli ingressi dei volti (un byte del corpo `faces`, 1..255). */
  const val RECIPE: Int = 1

  /** Due errori di fila del motore fermano la tappa (§2.3). */
  const val ENGINE_FAILURES_TO_STOP: Int = 2
}
