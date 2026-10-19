package dev.pampa.mycloudgallery.feature.integrations.share

import java.util.concurrent.atomic.AtomicLongArray

// Fase 5B (P11, docs/fase5-progetto.md §4.3 "Log", D30): cio' che ricevere, "Modifica con…" e condividere senza posizione contano per la
// Diagnostica. **Solo numeri, con un codice fisso**: mai un nome di file, un percorso, una share, un account, un id di media ne' un'impronta. Un
// insieme chiuso per area (`ReceiveCounter`, `EditCounter`, `ShareCounter`), contato in memoria dal processo; P14 li esporta come le altre chiavi.

/** Un contatore con un codice stabile (`minuscole_e_trattini_bassi`): e' il solo nome con cui compare in Diagnostica. */
interface CounterCode {
  val code: String
}

/** I contatori di un'area: un insieme chiuso, solo incrementi positivi. */
class CodeCounters<E>(private val all: List<E>) where E : Enum<E>, E : CounterCode {

  private val values = AtomicLongArray(all.size)

  fun add(counter: E, by: Long = 1) {
    if (by > 0) values.addAndGet(counter.ordinal, by)
  }

  fun get(counter: E): Long = values.get(counter.ordinal)

  /** Codice → numero, tutti i contatori dell'area (anche quelli a zero). */
  fun snapshot(): Map<String, Long> = all.associate { it.code to values.get(it.ordinal) }

  fun reset() {
    for (i in 0 until values.length()) values.set(i, 0)
  }
}

/** Ricevere condivisioni (D24, E-X2, E-X8). */
enum class ReceiveCounter(override val code: String) : CounterCode {
  OPENED("receive_opened"),
  LOCKED("receive_locked"),
  NOT_READY("receive_not_ready"),
  REFUSED_REQUEST("receive_refused_request"),
  STAGED_ITEMS("receive_staged_items"),
  REJECTED_FILE_SCHEME("receive_rejected_file_scheme"),
  REJECTED_OWN_PROVIDER("receive_rejected_own_provider"),
  REJECTED_TOO_LARGE("receive_rejected_too_large"),
  REJECTED_TOO_MANY("receive_rejected_too_many"),
  REJECTED_STAGE_TOO_BIG("receive_rejected_stage_too_big"),
  REJECTED_NO_SPACE("receive_rejected_no_space"),
  REJECTED_NOT_MEDIA("receive_rejected_not_media"),
  REJECTED_UNREADABLE("receive_rejected_unreadable"),
  SAVED("receive_saved"),
  SAVE_REJECTED("receive_save_rejected"),
  DISCARDED("receive_discarded"),
}

/** "Modifica con…" (D25, E-X3, E-E3, E-E7). */
enum class EditCounter(override val code: String) : CounterCode {
  BEGUN("edit_begun"),
  BEGIN_UNAVAILABLE("edit_begin_unavailable"),
  SETTLED_UNCHANGED("edit_settled_unchanged"),
  SETTLED_NEW_VERSION("edit_settled_new_version"),
  SETTLED_FROM_COPY("edit_settled_from_copy"),
  SETTLED_NEW_MEDIA("edit_settled_new_media"),
  SETTLED_RESTORED("edit_settled_restored"),
  EXPIRED("edit_expired"),
  UNREADABLE("edit_unreadable"),
  RESULT_REFUSED("edit_result_refused"),
}

/** Condividere senza posizione (D26, E-X5, L41). */
enum class ShareCounter(override val code: String) : CounterCode {
  PREPARED("share_prepared"),
  ITEMS_STRIPPED("share_items_stripped"),
  ITEMS_DIRECT("share_items_direct"),
  STOPPED_FORMAT("share_stopped_format"),
  STOPPED_LOCATION_REMAINS("share_stopped_location_remains"),
  STOPPED_UNAVAILABLE("share_stopped_unavailable"),
  STOPPED_LIMITS("share_stopped_limits"),
  STOPPED_FAILED("share_stopped_failed"),
  RELEASED("share_released"),
}

/** I contatori di tutta l'area delle integrazioni di P11, in memoria. */
object FlowCounters {
  val receive = CodeCounters(ReceiveCounter.entries)
  val edit = CodeCounters(EditCounter.entries)
  val share = CodeCounters(ShareCounter.entries)

  /** Tutti i numeri con il loro codice, per la Diagnostica. */
  fun snapshot(): Map<String, Long> = receive.snapshot() + edit.snapshot() + share.snapshot()

  fun reset() {
    receive.reset()
    edit.reset()
    share.reset()
  }
}
