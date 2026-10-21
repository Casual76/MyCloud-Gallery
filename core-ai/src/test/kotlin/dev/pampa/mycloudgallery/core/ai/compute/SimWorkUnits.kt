package dev.pampa.mycloudgallery.core.ai.compute

import dev.pampa.mycloudgallery.core.ai.contract.AiRecipe
import dev.pampa.mycloudgallery.core.ai.contract.ModelImage
import dev.pampa.mycloudgallery.core.ai.contract.OcrImage
import dev.pampa.mycloudgallery.core.ai.testing.AiTestInputs
import dev.pampa.mycloudgallery.core.index.contract.KeepAliveResult
import dev.pampa.mycloudgallery.core.index.contract.UnitClaim
import dev.pampa.mycloudgallery.core.index.contract.UnitLease
import dev.pampa.mycloudgallery.core.index.contract.UnitOutcome
import dev.pampa.mycloudgallery.core.index.contract.UnitPriority
import dev.pampa.mycloudgallery.core.index.contract.VideoFrame
import dev.pampa.mycloudgallery.core.index.contract.WorkItem
import dev.pampa.mycloudgallery.core.index.contract.WorkPass
import dev.pampa.mycloudgallery.core.index.contract.WorkUnitKey
import dev.pampa.mycloudgallery.core.index.contract.WorkUnits
import dev.pampa.mycloudgallery.core.index.contract.WriteBlock
import dev.pampa.mycloudgallery.core.index.testing.MapWriteGate
import dev.pampa.mycloudgallery.core.model.ContentKey
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.model.NasStamp
import dev.pampa.mycloudgallery.core.model.OcrSource
import dev.pampa.mycloudgallery.core.trust.TrustLimits
import dev.pampa.mycloudgallery.core.trust.data.AiClass
import dev.pampa.mycloudgallery.core.trust.nas.SharePath

/** I byte finti delle anteprime: il motore finto ne ricava i concetti ("FAKE:cane ..."). */
object SimBytes {
  fun preview(kind: MediaKind, concepts: String): ByteArray =
    if (kind == MediaKind.VIDEO) {
      FramesBlob.encode((0 until 4).map { VideoFrame(it * 30_000, "FAKE:$concepts f$it".toByteArray()) })
    } else {
      "FAKE:$concepts".toByteArray()
    }

  /** Un'anteprima che `AiInputs` rifiuta (formato non decodificabile). */
  val BAD: ByteArray = "BAD:non decodificabile".toByteArray()
}

/** Gli ingressi dei motori nei test (`ModelInputs` vero e' di P4): rifiuta i byte che cominciano con "BAD". */
class SimInputs : AiInputs {
  private fun bad(b: ByteArray) = b.size >= 3 && String(b, 0, 3, Charsets.US_ASCII) == "BAD"

  override fun image(analysis: ByteArray, recipe: AiRecipe): ModelImage? = if (bad(analysis)) null else AiTestInputs.imageOf(analysis)

  override fun frames(frames: List<VideoFrame>, recipe: AiRecipe): List<ModelImage> =
    frames.filterNot { bad(it.jpeg) }.map { AiTestInputs.imageOf(it.jpeg, frameMs = it.atMs) }

  override fun ocr(bytes: ByteArray, source: OcrSource): OcrImage? = if (bad(bytes)) null else AiTestInputs.ocrOf(bytes, source)
}

object NoPriority : UnitPriority {
  override val visible = kotlinx.coroutines.flow.MutableStateFlow(emptySet<Long>())
}

/**
 * Le unita' di lavoro sul NAS finto, con la semantica di `LeasedWorkUnits` (D9, D13): parti di [SimNas.unitSize] media per mese
 * ordinati per chiave, dal mese piu' recente; "manca" = classi volute qui meno quelle fatte nella share (teste attestate di
 * chiunque) o non supportate qui; prenotazione di 1 ora sull'ora del NAS (salvo blocco di scrittura: senza prenotazione);
 * `keepAlive` perde l'unita' se un altro l'ha presa; `finish` non rilascia se l'outbox ha ancora voci dell'unita'.
 * Le unita' prese stanno in memoria del processo ([active]): un processo nuovo ne crea un'altra istanza.
 */
class SimWorkUnits(
  private val nas: SimNas,
  private val device: String,
  private val gate: MapWriteGate,
  private val wanted: () -> Set<AiClass>,
  private val recipe: () -> AiRecipe,
  private val unsupported: Set<Pair<ContentKey, AiClass>>,
  private val publisher: SimPublisher,
) : WorkUnits {
  private val active = HashSet<WorkUnitKey>()
  val claims = ArrayList<UnitClaim>()
  var busy = 0
    private set

  private fun missing(share: String, key: ContentKey): Set<AiClass> {
    val r = recipe()
    return wanted().filterTo(LinkedHashSet()) { cls ->
      val rr = if (cls == AiClass.VEC) r.version else r.ocrVersion
      !nas.doneIn(share, key, rr, cls) && (key to cls) !in unsupported
    }
  }

  private fun item(share: String, media: SimMedia): WorkItem? {
    val miss = missing(share, media.key)
    if (miss.isEmpty()) return null
    return WorkItem(
      media.key, SharePath.parseMediaPath("${media.month.replace('-', '/')}/${media.key.hex.takeLast(8)}.jpg"),
      NasStamp(1_000L, nas.now, 1), media.kind, null, null, null, miss.mapTo(LinkedHashSet()) { it.segmentClass },
    )
  }

  override suspend fun next(share: String, pass: WorkPass, priority: UnitPriority): UnitClaim? {
    require(pass == WorkPass.AI)
    nas.touch(device, share)
    val leasing = gate.blockFor(share) == null
    return nas.nas {
      val s = nas.of(share)
      val months = s.media.values.map { it.month }.distinct().sortedDescending()
      for (month in months) {
        val all = s.media.values.filter { it.month == month }.sortedBy { it.key.hex }
        for ((part, items) in all.chunked(nas.unitSize).withIndex()) {
          val key = WorkUnitKey(share, WorkPass.AI, month, part)
          if (key in active) continue
          val work = items.mapNotNull { item(share, it) }
          if (work.isEmpty()) continue
          val lease = if (!leasing) {
            UnitLease.Unleased
          } else {
            val held = s.leases[key.resource]
            if (held != null && held.first != device && held.second > nas.now) {
              busy++
              continue
            }
            s.leases[key.resource] = device to nas.now + TrustLimits.MAX_LEASE_MILLIS
            UnitLease.Held(1, nas.now + TrustLimits.MAX_LEASE_MILLIS)
          }
          active += key
          return@nas UnitClaim(key, work, lease).also { claims += it }
        }
      }
      null
    }
  }

  override suspend fun keepAlive(claim: UnitClaim): KeepAliveResult {
    val share = claim.key.share
    nas.touch(device, share)
    return nas.nas {
      val s = nas.of(share)
      if (claim.lease is UnitLease.Held) {
        val held = s.leases[claim.key.resource]
        if (held != null && held.first != device && held.second > nas.now) {
          active -= claim.key
          return@nas KeepAliveResult.Lost
        }
        s.leases[claim.key.resource] = device to nas.now + TrustLimits.MAX_LEASE_MILLIS
      }
      val remaining = claim.items.mapNotNull { w -> s.media[w.contentKey]?.let { item(share, it) } }
      if (remaining.isEmpty()) {
        active -= claim.key
        s.leases.remove(claim.key.resource)
        KeepAliveResult.Lost
      } else {
        KeepAliveResult.Renewed(claim.copy(items = remaining))
      }
    }
  }

  override suspend fun finish(claim: UnitClaim, outcome: UnitOutcome) {
    active -= claim.key
    if (claim.lease !is UnitLease.Held) return
    val share = claim.key.share
    if (publisher.hasPending(share, claim.items.mapTo(HashSet()) { it.contentKey })) return
    nas.nas {
      val s = nas.of(share)
      if (s.leases[claim.key.resource]?.first == device) s.leases.remove(claim.key.resource)
    }
  }

  /** Il blocco permanente di una share vera con F4-D31 acceso. */
  companion object {
    val LOCAL_ONLY = WriteBlock.LOCAL_ONLY
  }
}
