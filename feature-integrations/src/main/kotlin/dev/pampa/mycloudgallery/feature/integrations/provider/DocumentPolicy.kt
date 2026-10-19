package dev.pampa.mycloudgallery.feature.integrations.provider

import android.os.SystemClock
import java.util.concurrent.atomic.AtomicLongArray

// Fase 5B (P10, docs/fase5-progetto.md §2.7, §4.3): i numeri che governano le porte d'ingresso delle integrazioni, l'orologio con cui si
// misurano e i contatori per la Diagnostica. Un posto solo, cosi' i test e la guida di P14 parlano degli stessi tetti.

/** I tetti e le scadenze del selettore e del provider dei documenti. */
object DocumentPolicy {

  /** E-X1: aprire un documento (il NAS puo' dover aprire una sessione) non aspetta piu' di cosi'. Poi `FileNotFoundException`, mai un'attesa infinita. */
  const val OPEN_DEADLINE_MS: Long = 15_000

  /** E-X1: **ogni** lettura di un pezzo di un documento solo-NAS ha questa scadenza (scadenza a ogni lettura, 15 s, §2.7). */
  const val READ_DEADLINE_MS: Long = 15_000

  /** Quanto aspetta il selettore per sapere se il NAS risponde, prima di dire all'utente che non risponde (E-X1). */
  const val NAS_PROBE_DEADLINE_MS: Long = 5_000

  /**
   * Quanto vale (a scorrimento: si rinnova a ogni uso) un id nato dallo sfoglio di un selettore di documenti. Lo sfoglio dura pochi minuti; un id
   * piu' vecchio e' di una schermata dimenticata e non si serve piu' (E-X1: "il permesso e' scaduto").
   */
  const val BROWSE_ID_TTL_MS: Long = 30L * 60_000

  /**
   * Quanto vale (a scorrimento) un id **consegnato** dal selettore: l'altra app puo' tenerlo in una bozza. Sei ore di orologio monotono; un
   * riavvio del processo lo invalida comunque (la tabella e' in memoria: "vale solo per questo avvio").
   */
  const val DELIVERED_ID_TTL_MS: Long = 6L * 60 * 60_000

  /** Quanti id da sfoglio e quanti consegnati la tabella tiene (i meno usati escono per primi; i consegnati non scalzano mai gli id da sfoglio e viceversa). */
  const val BROWSE_ID_CAPACITY: Int = 3_000
  const val DELIVERED_ID_CAPACITY: Int = 2_000

  /** Quanti elementi al massimo risponde una cartella al selettore di documenti. */
  const val MAX_CHILDREN: Int = 500

  /**
   * Quanti descrittori a richiesta restano aperti insieme (uno per media solo-NAS letto da un'altra app, ognuno con il suo thread). Oltre,
   * le aperture aspettano un posto fino alla scadenza (E-X6).
   */
  const val MAX_OPEN_DESCRIPTORS: Int = 8

  /**
   * Quante aperture al massimo possono essere **in attesa** di un posto. Ogni attesa tiene occupato un thread dei 16 del binder: oltre questo
   * tetto il rifiuto e' immediato e motivato (E-X6), per non lasciare l'app senza thread per il sistema.
   */
  const val MAX_WAITING_OPENS: Int = 6

  /** I tetti del selettore (E-X6): quanti elementi al massimo si consegnano insieme e quanti di questi possono stare solo sul NAS. */
  const val MAX_PICKED_ITEMS: Int = 500
  const val MAX_NAS_ONLY_ITEMS: Int = 100
  const val MAX_NAS_ONLY_BYTES: Long = 2L * 1024 * 1024 * 1024
}

/**
 * L'orologio di queste porte: **monotono** (`SystemClock.elapsedRealtime`, conta anche con il telefono in sospensione e non si sposta a mano). Mai
 * l'ora da muro: una scadenza non puo' scattare prima del tempo, ne' non scattare, perche' qualcuno ha cambiato l'ora (D31, §4.3).
 */
fun interface DocumentClock {
  fun elapsedMs(): Long

  companion object {
    val Monotonic: DocumentClock = DocumentClock { SystemClock.elapsedRealtime() }
  }
}

/** Cio' che le integrazioni contano per la Diagnostica: **solo numeri, con un codice fisso** (§4.3: mai nomi, percorsi, share, account, id). */
enum class IntegrationCounter(val code: String) {
  PICKER_OPENED("picker_opened"),
  PICKER_LOCKED("picker_locked"),
  PICKER_NOT_READY("picker_not_ready"),
  PICKER_REFUSED_REQUEST("picker_refused_request"),
  PICKER_DELIVERED("picker_delivered"),
  PICKER_DELIVERED_NAS_ONLY("picker_delivered_nas_only"),
  PICKER_CANCELLED("picker_cancelled"),
  PICKER_NAS_UNREACHABLE("picker_nas_unreachable"),
  PICKER_TOO_MANY("picker_too_many"),
  PICKER_SKIPPED("picker_skipped"),
  PROVIDER_QUERIES("provider_queries"),
  PROVIDER_OPENED("provider_opened"),
  PROVIDER_OPEN_REFUSED("provider_open_refused"),
  PROVIDER_STRANGERS_REFUSED("provider_strangers_refused"),
  PROVIDER_EXPIRED("provider_expired"),
  PROVIDER_LOCKED("provider_locked"),
  PROVIDER_READ_TIMEOUTS("provider_read_timeouts"),
  PROVIDER_READ_CANCELLED("provider_read_cancelled"),
  PROVIDER_READ_ERRORS("provider_read_errors"),

  /** Quante volte un media nascosto, sparito o non piu' visibile e' stato fermato prima di uscire (E-H1). */
  HIDDEN_STOPPED("hidden_stopped"),
}

/**
 * I contatori delle integrazioni, in memoria. `:app` li legge con [snapshot] per la Diagnostica (P14) e li scrive come `codice=numero`: nessuna
 * stringa dell'utente passa di qui, per costruzione (il solo input e' un [IntegrationCounter]).
 */
object IntegrationCounters {

  private val values = AtomicLongArray(IntegrationCounter.entries.size)

  fun add(counter: IntegrationCounter, amount: Long = 1) {
    if (amount > 0) values.addAndGet(counter.ordinal, amount)
  }

  fun get(counter: IntegrationCounter): Long = values.get(counter.ordinal)

  /** Tutti i contatori, per codice. */
  fun snapshot(): Map<String, Long> = IntegrationCounter.entries.associate { it.code to values.get(it.ordinal) }

  /** Azzera tutto (azzeramento dell'app, test). */
  fun reset() {
    for (i in 0 until values.length()) values.set(i, 0)
  }
}
