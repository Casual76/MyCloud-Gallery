package dev.pampa.mycloudgallery.core.index.ai

import dev.pampa.mycloudgallery.core.index.contract.HeadFetch
import dev.pampa.mycloudgallery.core.index.contract.IndexHeadView
import dev.pampa.mycloudgallery.core.index.contract.RemoteFailure
import dev.pampa.mycloudgallery.core.index.contract.RemoteResult
import dev.pampa.mycloudgallery.core.index.publish.IndexIdentity
import dev.pampa.mycloudgallery.core.index.publish.Nas
import dev.pampa.mycloudgallery.core.index.publish.nasCall
import dev.pampa.mycloudgallery.core.trust.RejectReason
import dev.pampa.mycloudgallery.core.trust.Verdict
import dev.pampa.mycloudgallery.core.trust.data.AiClass
import dev.pampa.mycloudgallery.core.trust.data.AiSegmentContent
import dev.pampa.mycloudgallery.core.trust.data.AiSegments
import dev.pampa.mycloudgallery.core.trust.data.ClassedSegment
import dev.pampa.mycloudgallery.core.trust.data.DeviceHead
import dev.pampa.mycloudgallery.core.trust.data.HeadKind
import dev.pampa.mycloudgallery.core.trust.data.HeadMark
import dev.pampa.mycloudgallery.core.trust.data.HeadPublish
import dev.pampa.mycloudgallery.core.trust.data.HeadRead
import dev.pampa.mycloudgallery.core.trust.data.HeadReadBudget
import dev.pampa.mycloudgallery.core.trust.data.HeadSeries
import dev.pampa.mycloudgallery.core.trust.nas.NasFileSystem
import dev.pampa.mycloudgallery.core.trust.nas.NasLayout
import dev.pampa.mycloudgallery.core.trust.nas.NasRead
import dev.pampa.mycloudgallery.core.trust.registry.RegistryState
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/** Esito dello scaricamento di un segmento `vec` o `ocr` elencato da una testa `ai` accettata. */
sealed interface AiSegmentFetch {
  data class Ok(val content: AiSegmentContent) : AiSegmentFetch

  /** Elencato ma assente sul NAS (E-I17): i dati gia' nella replica restano finche' il dispositivo non ripubblica. */
  data object Missing : AiSegmentFetch

  /** Dimensione o impronta diverse, classe sbagliata, tabella incoerente, un corpo ostile (E-I20): scartato intero. */
  data class Rejected(val reason: RejectReason) : AiSegmentFetch
}

/**
 * Le teste `ai` degli altri dispositivi in una share, lette difensivamente (C9, E-I20). La testa accettata usa la stessa vista delle
 * teste `index` ([IndexHeadView], qui con segmenti `vec` e `ocr`), con lo stesso anti-rollback per (share, dispositivo, tipo).
 */
interface AiRemoteIndex {
  /** La testa `ai` piu' recente di [deviceId] in [share] dopo [previous] (per nome, salti doppi e bisezione, M3). */
  suspend fun latestHead(share: String, deviceId: String, previous: HeadMark?): RemoteResult<HeadFetch>

  /** Scarica, verifica e decodifica un segmento della testa `ai` accettata [head]. */
  suspend fun segment(head: IndexHeadView, segment: ClassedSegment): RemoteResult<AiSegmentFetch>
}

/**
 * [AiRemoteIndex] sul NAS: teste per nome ([HeadSeries] con [HeadKind.AI]: firma, posto, versione 2, sole classi `vec` e `ocr`,
 * anti-rollback) e segmenti verificati con il codec ostile ([AiSegments.decode]). Ogni errore del NAS diventa [RemoteResult.Failed];
 * ogni dato ostile un rifiuto con il suo motivo; mai un'eccezione verso fuori per un dato remoto. I segmenti si leggono con il tetto
 * della dimensione dichiarata dalla testa firmata (al massimo 16 MiB).
 */
class NasAiRemoteIndex(
  private val fileSystem: (String) -> NasFileSystem,
  private val registry: suspend () -> RegistryState?,
  private val readerAccount: String,
  private val io: CoroutineDispatcher = Dispatchers.IO,
  private val listingHint: (share: String, deviceId: String, previous: HeadMark?) -> Boolean = { _, _, previous -> previous == null },
) : AiRemoteIndex {

  private val budgets = HashMap<String, HeadReadBudget>()

  /** Teste lette (per i test: "un lettore senza la Fase 6 non apre `ai/`" si prova contando queste). */
  val headReads = AtomicInteger()

  /** Segmenti scaricati. */
  val segmentReads = AtomicInteger()

  /** Un giro nuovo: il budget di letture delle teste di ogni share riparte (M9). */
  fun startRound() {
    synchronized(budgets) { budgets.clear() }
  }

  private fun budget(share: String): HeadReadBudget =
    synchronized(budgets) { budgets.getOrPut(share.lowercase(Locale.ROOT)) { HeadReadBudget() } }

  override suspend fun latestHead(share: String, deviceId: String, previous: HeadMark?): RemoteResult<HeadFetch> {
    val state = registry() ?: return RemoteResult.Failed(RemoteFailure.NOT_ALLOWED)
    val fs = fileSystem(share)
    val hint = listingHint(share, deviceId, previous)
    headReads.incrementAndGet()
    val scan = when (val result = nasCall(io) { HeadSeries.readLatest(fs, state, deviceId, HeadKind.AI, previous, readerAccount, budget(share), hint) }) {
      is Nas.Ok -> result.value
      is Nas.Failed -> return RemoteResult.Failed(result.failure)
    }
    val fetch = when (val read = scan.result) {
      HeadRead.NoChange -> HeadFetch.NoChange
      HeadRead.None -> HeadFetch.None
      is HeadRead.Rejected -> HeadFetch.Rejected(read.reason)
      HeadRead.OutOfBudget -> return RemoteResult.Failed(RemoteFailure.OTHER)
      is HeadRead.Accepted -> {
        val head = read.head.head
        // Difesa in profondita': HeadVerifier accetta una testa `ai` solo con `vec` e `ocr`.
        if (head.kind != HeadKind.AI || head.segments.any { AiClass.of(it.cls) == null }) {
          HeadFetch.Rejected(RejectReason.INVALID_FIELD)
        } else {
          HeadFetch.Accepted(
            IndexHeadView(
              share = head.share,
              deviceId = head.deviceId,
              version = head.version,
              registryVersion = head.registryVersion,
              createdAtNasMillis = head.createdAt,
              mark = read.head.mark,
              segments = head.segments,
              registryBehind = read.head.registryBehind,
            ),
          )
        }
      }
    }
    return RemoteResult.Ok(fetch)
  }

  override suspend fun segment(head: IndexHeadView, segment: ClassedSegment): RemoteResult<AiSegmentFetch> {
    val cls = AiClass.of(segment.cls) ?: return RemoteResult.Ok(AiSegmentFetch.Rejected(RejectReason.WRONG_TYPE))
    val fs = fileSystem(head.share)
    segmentReads.incrementAndGet()
    val read = when (val result = nasCall(io) { fs.read(NasLayout.aiSegment(head.deviceId, segment.ref.hash), segment.ref.size.toInt()) }) {
      is Nas.Ok -> result.value
      is Nas.Failed -> return RemoteResult.Failed(result.failure)
    }
    val fetch = when (read) {
      NasRead.NotFound -> AiSegmentFetch.Missing
      NasRead.TooLarge -> AiSegmentFetch.Rejected(RejectReason.HASH_MISMATCH)
      is NasRead.Data -> when (val verdict = AiSegments.decode(read.bytes, segment.ref, cls)) {
        is Verdict.Accepted -> AiSegmentFetch.Ok(verdict.value)
        is Verdict.Rejected -> AiSegmentFetch.Rejected(verdict.reason)
      }
    }
    return RemoteResult.Ok(fetch)
  }
}

/**
 * Scrive la testa `ai` propria al primo nome libero dopo quella nota (come `OwnHeadWriter` per le teste `index`). Una testa valida
 * **mia** gia' presente a quel nome (un crash dopo la pubblicazione, prima del commit locale) si **adotta** se elenca gli stessi
 * segmenti; altrimenti se ne scrive una dopo di lei. Cosi' una pubblicazione ripetuta non lascia teste in piu'.
 */
internal class AiHeadWriter(
  private val identity: IndexIdentity,
  private val io: CoroutineDispatcher,
) {
  sealed interface Result {
    class Done(val version: Long, val mark: HeadMark, val adopted: Boolean) : Result

    class Failed(val failure: RemoteFailure) : Result
  }

  /** Nomi di testa occupati da file non validi, scavalcati (Diagnostica). */
  val junkSkipped = AtomicInteger()

  suspend fun publish(fs: NasFileSystem, registry: RegistryState, after: Long, segments: List<ClassedSegment>, now: Long): Result {
    var base = after
    repeat(MAX_ATTEMPTS) {
      val result = nasCall(io) {
        HeadSeries.publish(fs, registry, identity.signer, HeadKind.AI, base, identity.nasAccount) { version ->
          DeviceHead(registry.registry.familyId, identity.deviceId, fs.shareName, HeadKind.AI, version, registry.registry.version, now, segments)
            .sign(identity.signer)
        }
      }
      when (result) {
        is Nas.Failed -> return Result.Failed(result.failure)
        is Nas.Ok -> when (val published = result.value) {
          is HeadPublish.Published -> {
            junkSkipped.addAndGet(published.skipped.size)
            return Result.Done(published.version, published.mark, adopted = false)
          }
          is HeadPublish.ExistingOwn -> {
            if (published.head.head.segments == segments) return Result.Done(published.version, published.head.mark, adopted = true)
            base = published.version
          }
          is HeadPublish.Blocked -> {
            junkSkipped.addAndGet(published.junk.size)
            return Result.Failed(RemoteFailure.OTHER)
          }
          HeadPublish.NoRoom -> return Result.Failed(RemoteFailure.OTHER)
        }
      }
    }
    return Result.Failed(RemoteFailure.OTHER)
  }

  private companion object {
    const val MAX_ATTEMPTS = 3
  }
}
