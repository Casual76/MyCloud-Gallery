package dev.pampa.mycloudgallery.core.index.publish

import dev.pampa.mycloudgallery.core.index.contract.HeadFetch
import dev.pampa.mycloudgallery.core.index.contract.IndexHeadView
import dev.pampa.mycloudgallery.core.index.contract.RemoteFailure
import dev.pampa.mycloudgallery.core.index.contract.RemoteIndex
import dev.pampa.mycloudgallery.core.index.contract.RemoteResult
import dev.pampa.mycloudgallery.core.index.contract.SegmentFetch
import dev.pampa.mycloudgallery.core.trust.RejectReason
import dev.pampa.mycloudgallery.core.trust.Verdict
import dev.pampa.mycloudgallery.core.trust.data.ClassedSegment
import dev.pampa.mycloudgallery.core.trust.data.HeadKind
import dev.pampa.mycloudgallery.core.trust.data.HeadMark
import dev.pampa.mycloudgallery.core.trust.data.HeadRead
import dev.pampa.mycloudgallery.core.trust.data.HeadReadBudget
import dev.pampa.mycloudgallery.core.trust.data.HeadSeries
import dev.pampa.mycloudgallery.core.trust.data.IndexSegments
import dev.pampa.mycloudgallery.core.trust.nas.NasFileSystem
import dev.pampa.mycloudgallery.core.trust.nas.NasLayout
import dev.pampa.mycloudgallery.core.trust.nas.NasRead
import dev.pampa.mycloudgallery.core.trust.registry.RegistryState
import java.util.Locale
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * L'indice degli altri dispositivi letto dal NAS (contratto [RemoteIndex] di P0): `devices/` filtrato dal registro,
 * teste per nome ([HeadSeries]) e segmenti verificati con il codec stretto ([IndexSegments.decode]). Ogni errore del
 * NAS diventa [RemoteResult.Failed]; ogni dato ostile un rifiuto con il suo motivo. Mai un'eccezione verso fuori per
 * un dato remoto.
 *
 * I segmenti si leggono con `NasFileSystem.read` con il tetto della dimensione dichiarata dalla testa firmata (al
 * massimo 16 MiB): mai un byte in piu' in memoria. P11 puo' sostituire la lettura con `NasMediaReader.readAll` (4
 * flussi) senza cambiare la verifica.
 */
class NasRemoteIndex(
  private val fileSystem: (String) -> NasFileSystem,
  private val registry: suspend () -> RegistryState?,
  /** L'account del NAS di chi legge (serve alle teste `personal`; per l'indice e' indifferente). */
  private val readerAccount: String,
  private val io: CoroutineDispatcher = Dispatchers.IO,
  /** Quando chiedere l'elenco della cartella delle teste come indizio dei buchi: di default alla prima lettura. */
  private val listingHint: (share: String, deviceId: String, previous: HeadMark?) -> Boolean = { _, _, previous -> previous == null },
) : RemoteIndex {

  private val budgets = HashMap<String, HeadReadBudget>()

  /** Un giro nuovo: il budget di letture delle teste di ogni share riparte (M9: 1.024 per share e per giro). */
  fun startRound() {
    synchronized(budgets) { budgets.clear() }
  }

  private fun budget(share: String): HeadReadBudget =
    synchronized(budgets) { budgets.getOrPut(share.lowercase(Locale.ROOT)) { HeadReadBudget() } }

  override suspend fun devices(share: String): RemoteResult<List<String>> {
    val state = registry() ?: return RemoteResult.Failed(RemoteFailure.NOT_ALLOWED)
    val fs = fileSystem(share)
    return when (val result = nasCall(io) { HeadSeries.devices(fs, state) }) {
      is Nas.Ok -> RemoteResult.Ok(result.value.ids)
      is Nas.Failed -> RemoteResult.Failed(result.failure)
    }
  }

  override suspend fun latestHead(share: String, deviceId: String, previous: HeadMark?): RemoteResult<HeadFetch> {
    val state = registry() ?: return RemoteResult.Failed(RemoteFailure.NOT_ALLOWED)
    val fs = fileSystem(share)
    val hint = listingHint(share, deviceId, previous)
    val scan = when (val result = nasCall(io) { HeadSeries.readLatest(fs, state, deviceId, HeadKind.INDEX, previous, readerAccount, budget(share), hint) }) {
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
    return RemoteResult.Ok(fetch)
  }

  override suspend fun segment(head: IndexHeadView, segment: ClassedSegment): RemoteResult<SegmentFetch> {
    val fs = fileSystem(head.share)
    val path = NasLayout.segmentFile(head.deviceId, HeadKind.INDEX.wire, segment.ref.hash)
    val read = when (val result = nasCall(io) { fs.read(path, segment.ref.size.toInt()) }) {
      is Nas.Ok -> result.value
      is Nas.Failed -> return RemoteResult.Failed(result.failure)
    }
    val fetch = when (read) {
      NasRead.NotFound -> SegmentFetch.Missing
      NasRead.TooLarge -> SegmentFetch.Rejected(RejectReason.HASH_MISMATCH)
      is NasRead.Data -> when (val verdict = IndexSegments.decode(read.bytes, segment.ref, segment.cls)) {
        is Verdict.Accepted -> SegmentFetch.Ok(verdict.value)
        is Verdict.Rejected -> SegmentFetch.Rejected(verdict.reason)
      }
    }
    return RemoteResult.Ok(fetch)
  }
}
