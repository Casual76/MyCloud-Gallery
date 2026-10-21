package dev.pampa.mycloudgallery.core.index.ai

import dev.pampa.mycloudgallery.core.index.contract.FlushReason
import dev.pampa.mycloudgallery.core.index.contract.PublishOutcome
import dev.pampa.mycloudgallery.core.model.ContentKey
import dev.pampa.mycloudgallery.core.trust.data.AiClass
import dev.pampa.mycloudgallery.core.trust.data.OcrBody
import dev.pampa.mycloudgallery.core.trust.data.SegmentClass
import dev.pampa.mycloudgallery.core.trust.data.VecBody
import kotlin.time.Duration
import kotlinx.coroutines.flow.StateFlow

// I DATI AI nell'indice condiviso (docs/fase6-progetto.md §1.2 "core-index", §2.3, D6-D10). Contratti di P0; la pubblicazione
// (outbox e pubblicatore della testa `ai`), la replica con attestazione e fusione, `AiPublishedClasses`, `AiWorkSource` e lo
// scaricamento delle anteprime nel buffer sono di P2 (questo pacchetto `ai/`, e `WorkPass.AI` in contract/Work.kt).
//
// I tipi di questo file non nominano core-ai: il calcolo (core-ai, P5) li usa, non il contrario. Il buffer degli indicizzatori sta in
// core-ai e arriva qui come porta ([AiBufferSink]), cosi' il grafo resta senza cicli (§1.1).

/**
 * Una voce dei dati AI di un contenuto, per chiave di contenuto **intera** (D7: mai i primi 64 bit). `vec` e `ocr` sono classi
 * indipendenti (D9): si pubblicano, si attestano e si fondono separatamente.
 */
sealed interface AiEntry {
  val contentKey: ContentKey
  val cls: AiClass

  class Vector(override val contentKey: ContentKey, val body: VecBody) : AiEntry {
    override val cls: AiClass get() = AiClass.VEC

    override fun toString(): String = "AiEntry.Vector($body)"
  }

  class Text(override val contentKey: ContentKey, val body: OcrBody) : AiEntry {
    override val cls: AiClass get() = AiClass.OCR

    override fun toString(): String = "AiEntry.Text($body)"
  }
}

/** Quante voci AI aspettano: nell'outbox (pubblicabili) e in `ai_pending_publish` (share non pubblicabile, D8). */
data class AiPendingCounts(val staged: Int, val waitingForShare: Int) {
  init {
    require(staged >= 0 && waitingForShare >= 0) { "Conteggi negativi." }
  }

  companion object {
    val EMPTY = AiPendingCounts(0, 0)
  }
}

/**
 * Il pubblicatore della testa `ai` di **questo** dispositivo (D8): stessa meccanica di `OutboxIndexPublisher`, una testa per share.
 *
 * - [stage]: le voci di una share, **solo** per chiavi di contenuto di file di quella share nel catalogo locale, con timbro e `qh1`
 *   freschi (regola M3: l'implementazione rifiuta il resto e un test di proprieta' lo verifica). Sotto un blocco **permanente** (F4-D31,
 *   area in sola lettura) le voci vanno in `ai_pending_publish` e restano locali.
 * - [flush]: una testa al piu' ogni 5 minuti d'ora del NAS per share, salvo [FlushReason.UNIT_FINISHED]; stessi cancelli di
 *   `IndexWriteRules` (D1).
 * - [releasePending]: quando la share diventa pubblicabile, ripubblica le voci in attesa con il timbro ancora fresco, **senza
 *   ricalcolarle** (D8). Quante.
 */
interface AiIndexPublisher {
  suspend fun stage(share: String, entries: List<AiEntry>)

  suspend fun flush(share: String, reason: FlushReason): PublishOutcome

  suspend fun releasePending(share: String): Int

  val pending: StateFlow<AiPendingCounts>
}

/**
 * Una voce vincitrice per (share, chiave, classe) dopo attestazione e fusione (D7): ricetta piu' alta, poi id del dispositivo
 * minore. [device] e' l'autore (solo per la Diagnostica e per dimenticare un dispositivo revocato).
 */
class AiWinner(val share: String, val entry: AiEntry, val recipe: Int, val device: String) {
  override fun toString(): String = "AiWinner(${entry.cls.wire}, r$recipe)"
}

/**
 * Dove finiscono le voci AI lette dalle teste `ai` degli altri (e le proprie): l'indice locale di ricerca (P1/P6, legato da :app). Le
 * teste `ai` si leggono nello stesso giro di `IndexSyncEngine`, dopo le teste `index` (§1.4).
 */
interface AiReplicaSink {
  suspend fun apply(share: String, winners: List<AiWinner>)

  /** Un dispositivo revocato, perso o sconosciuto: le sue voci escono (§2.3). */
  suspend fun forgetDevice(device: String)

  /** Un'area persa (E-I8): le voci di quella share escono. */
  suspend fun forgetShare(share: String)
}

/**
 * La porta del buffer di analisi degli indicizzatori (D10), vista da core-index: lo scaricamento delle anteprime ci mette i blob
 * attestati. L'implementazione e' il buffer di core-ai (`AiBuffer`), legata da :app. Falso = buffer pieno o sotto 1 GiB libero: lo
 * scaricamento si ferma.
 */
fun interface AiBufferSink {
  suspend fun put(key: ContentKey, cls: SegmentClass, bytes: ByteArray): Boolean
}

/** Com'e' andato uno scaricamento di anteprime nel buffer. */
data class FetchReport(
  /** Blob messi nel buffer. */
  val fetched: Int,
  /** Chiavi senza un blob **attestato** di quella classe nella share (R1 della 4A): restano "in attesa della preparazione". */
  val missing: Int,
  /** Byte letti dal NAS (registrati nel `TrafficLedger` come `AI_COMPUTE`). */
  val bytes: Long,
  /** Vero se il tempo o il buffer sono finiti prima della fine: le chiavi restanti si riprendono al lotto dopo. */
  val stoppedEarly: Boolean,
) {
  init {
    require(fetched >= 0 && missing >= 0 && bytes >= 0) { "Conteggi negativi." }
  }
}

/**
 * Lo scaricamento delle anteprime dall'indice di una share nel buffer (D10): **solo** `analysis`, `frames` e `hq`, **solo** blob
 * attestati, letture a blocchi, traffico registrato. Mai i byte originali. Mai byte remoti ai decodificatori nativi: chi li usa li
 * passa da `ModelInputs` di core-ai (D5).
 */
interface AnalysisFetcher {
  suspend fun fetch(
    share: String,
    keys: List<ContentKey>,
    classes: Set<SegmentClass>,
    into: AiBufferSink,
    budget: Duration,
  ): FetchReport

  companion object {
    /** Le sole classi che si scaricano nel buffer. */
    val FETCHABLE: Set<SegmentClass> = setOf(SegmentClass.ANALYSIS, SegmentClass.FRAMES, SegmentClass.HQ)
  }
}

/**
 * Lo stato del lavoro AI di una share visto dall'indice condiviso (D9): per ogni chiave, le classi AI gia' pubblicate per la ricetta
 * corrente da **qualunque** dispositivo approvato (niente evidenza `thumbS`). Lo implementa P2 (`AiPublishedClasses`); il calcolo lo
 * interroga all'inizio di ogni unita' per saltare cio' che altri hanno gia' fatto.
 */
interface AiDoneClasses {
  /** Le classi AI fatte per ognuna delle [keys] (assente = niente di fatto). */
  suspend fun done(share: String, keys: Collection<ContentKey>, recipe: Int): Map<ContentKey, Set<AiClass>>
}
