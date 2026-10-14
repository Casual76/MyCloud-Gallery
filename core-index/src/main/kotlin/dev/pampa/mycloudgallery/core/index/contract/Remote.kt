package dev.pampa.mycloudgallery.core.index.contract

import dev.pampa.mycloudgallery.core.trust.RejectReason
import dev.pampa.mycloudgallery.core.trust.data.ClassedSegment
import dev.pampa.mycloudgallery.core.trust.data.HeadMark
import dev.pampa.mycloudgallery.core.trust.data.SegmentContent

// La LETTURA dell'indice degli altri dispositivi dal NAS (docs/fase4-progetto.md §2.1, §2.2; docs/formato-nas.md §5.5,
// §5.5-bis). Contratto di P0. La usano le unita' di lavoro (P3: "cio' che altri hanno gia' pubblicato") e la
// sincronizzazione della replica (P4). L'implementazione vera la scrive P3 (`publish/`, accanto al codec e a
// `HeadSeries`): legge `devices/` e le teste per nome con `NasFileSystem`, scarica i segmenti con `NasMediaReader.readAll`
// (4 flussi), li verifica con `Segments.unwrap` e li decodifica con `IndexSegments.decode`.
//
// Niente tipi di core-nas qui: gli errori del NAS arrivano come [RemoteFailure].

/** Perche' il NAS non ha dato una risposta utile (dalla tabella di `NasError`, senza dettagli). */
enum class RemoteFailure {
  /** Rete, NAS spento, sessione caduta, tempo scaduto: si riprova piu' tardi. */
  UNREACHABLE,

  /** `ACCESS_DENIED` sulla share o sulla cartella (E-I8, E-R10: conta su due giorni diversi di ora del NAS). */
  ACCESS_DENIED,

  /** `BAD_NETWORK_NAME`: la share non c'e' piu' (E-I8, E-R10). */
  SHARE_MISSING,

  /** NAS pieno (E-R9, E-I6). */
  DISK_FULL,

  /** Quota dell'account esaurita (E-N13, E-I6). */
  QUOTA,

  /** File in uso (E-N13): si riprova. */
  BUSY,

  /** Percorso negato dal cancello, interruttore aperto, recinzione D33: nessuna richiesta partita. */
  NOT_ALLOWED,

  OTHER,
}

/** L'esito di una lettura remota. */
sealed interface RemoteResult<out T> {
  data class Ok<T>(val value: T) : RemoteResult<T>

  data class Failed(val failure: RemoteFailure) : RemoteResult<Nothing>
}

fun <T> RemoteResult<T>.okOrNull(): T? = (this as? RemoteResult.Ok<T>)?.value

/**
 * Una testa `index` **accettata** (firma di un dispositivo approvato o dismesso con le teste congelate, posto giusto,
 * anti-rollback rispetto al [HeadMark] noto): cio' che serve a chi la usa.
 */
data class IndexHeadView(
  val share: String,
  val deviceId: String,
  val version: Long,
  val registryVersion: Long,
  val createdAtNasMillis: Long,
  /** Da salvare nella stessa transazione che applica la testa (anti-rollback, E-I5). */
  val mark: HeadMark,
  /** In ordine di impronta, con la classe (M1). */
  val segments: List<ClassedSegment>,
  /** La testa cita un registro piu' nuovo del nostro: va riletto. */
  val registryBehind: Boolean,
)

/** Esito della lettura della testa piu' recente di un dispositivo in una share. */
sealed interface HeadFetch {
  /** Nessuna testa piu' nuova di quella nota: di solito UNA lettura che risponde "non trovato" (§2.2). */
  data object NoChange : HeadFetch

  /** Il dispositivo non ha mai pubblicato in questa share. */
  data object None : HeadFetch

  data class Accepted(val head: IndexHeadView) : HeadFetch

  /**
   * Rifiutata: firma, posto, formato piu' nuovo (E-I3), anti-rollback ([RejectReason.ROLLBACK]: un `HeadMark` piu' alto
   * di cio' che si trova vale come "rollback" e va in Diagnostica, E-I5), revocati e persi (E-I15). Si tiene la replica.
   */
  data class Rejected(val reason: RejectReason) : HeadFetch
}

/** Esito dello scaricamento di un segmento elencato da una testa accettata. */
sealed interface SegmentFetch {
  data class Ok(val content: SegmentContent) : SegmentFetch

  /** Elencato ma assente sul NAS (E-I17): i dati gia' nella replica restano finche' il dispositivo non ripubblica. */
  data object Missing : SegmentFetch

  /** Dimensione o impronta diverse, classe sbagliata, tabella incoerente, corpi oltre i tetti (E-I14): scartato intero. */
  data class Rejected(val reason: RejectReason) : SegmentFetch
}

/** L'indice degli altri dispositivi in una share, letto difensivamente (C9, E-I14). */
interface RemoteIndex {

  /**
   * Gli id dei dispositivi con una cartella in `devices/` (al massimo `TrustLimits.MAX_INDEX_DEVICES_PER_SHARE` nomi),
   * tenuti solo se nel registro accettato sono approvati o dismessi (E-I15, E-S14). Questo dispositivo compreso: la
   * sincronizzazione legge anche la propria testa (ricostruzione da zero, E-I7).
   */
  suspend fun devices(share: String): RemoteResult<List<String>>

  /** La testa piu' recente di [deviceId] in [share] dopo [previous] (per nome, salti doppi e bisezione, M3). */
  suspend fun latestHead(share: String, deviceId: String, previous: HeadMark?): RemoteResult<HeadFetch>

  /** Scarica, verifica e decodifica un segmento della testa accettata [head]. */
  suspend fun segment(head: IndexHeadView, segment: ClassedSegment): RemoteResult<SegmentFetch>
}
