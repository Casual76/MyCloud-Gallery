package dev.pampa.mycloudgallery.core.index.publish

import dev.pampa.mycloudgallery.core.index.contract.RemoteFailure
import dev.pampa.mycloudgallery.core.trust.data.ClassedSegment
import dev.pampa.mycloudgallery.core.trust.data.DeviceHead
import dev.pampa.mycloudgallery.core.trust.data.HeadKind
import dev.pampa.mycloudgallery.core.trust.data.HeadMark
import dev.pampa.mycloudgallery.core.trust.data.HeadPublish
import dev.pampa.mycloudgallery.core.trust.data.HeadSeries
import dev.pampa.mycloudgallery.core.trust.nas.NasFileSystem
import dev.pampa.mycloudgallery.core.trust.registry.RegistryState
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineDispatcher

/**
 * Scrive la testa `index` propria al primo nome libero dopo quella nota (pubblicazione e compattazione). Una testa
 * valida **mia** gia' presente a quel nome (un crash dopo la pubblicazione, prima del commit locale) si **adotta** se
 * elenca gli stessi segmenti; altrimenti se ne scrive una dopo di lei. Cosi' una pubblicazione ripetuta non lascia
 * teste in piu'.
 */
internal class OwnHeadWriter(
  private val identity: IndexIdentity,
  private val io: CoroutineDispatcher,
  private val junkSkipped: AtomicInteger,
) {
  sealed interface Result {
    class Done(val version: Long, val mark: HeadMark, val adopted: Boolean) : Result

    class Failed(val failure: RemoteFailure) : Result
  }

  suspend fun publish(fs: NasFileSystem, registry: RegistryState, after: Long, segments: List<ClassedSegment>, now: Long): Result {
    var base = after
    repeat(MAX_ATTEMPTS) {
      val result = nasCall(io) {
        HeadSeries.publish(fs, registry, identity.signer, HeadKind.INDEX, base, identity.nasAccount) { version ->
          DeviceHead(registry.registry.familyId, identity.deviceId, fs.shareName, HeadKind.INDEX, version, registry.registry.version, now, segments)
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
