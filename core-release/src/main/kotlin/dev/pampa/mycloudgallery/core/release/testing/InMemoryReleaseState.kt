package dev.pampa.mycloudgallery.core.release.testing

import dev.pampa.mycloudgallery.core.release.contract.AppVersion
import dev.pampa.mycloudgallery.core.release.contract.BlockLevel
import dev.pampa.mycloudgallery.core.release.contract.ManifestLimits
import dev.pampa.mycloudgallery.core.release.contract.ManifestOrigin
import dev.pampa.mycloudgallery.core.release.contract.ManifestVerifier
import dev.pampa.mycloudgallery.core.release.contract.OfferResult
import dev.pampa.mycloudgallery.core.release.contract.RejectReason
import dev.pampa.mycloudgallery.core.release.contract.ReleaseClock
import dev.pampa.mycloudgallery.core.release.contract.ReleaseDiag
import dev.pampa.mycloudgallery.core.release.contract.ReleaseManifest
import dev.pampa.mycloudgallery.core.release.contract.ReleaseMessage
import dev.pampa.mycloudgallery.core.release.contract.ReleaseState
import dev.pampa.mycloudgallery.core.release.contract.ReleaseStateStore
import dev.pampa.mycloudgallery.core.release.contract.ReleaseStatus
import dev.pampa.mycloudgallery.core.release.contract.StoredRelease
import dev.pampa.mycloudgallery.core.release.contract.Verified
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Lo stato dei blocchi di riferimento (§5.2): le regole di D30 scritte nel modo piu' diretto (vedi il KDoc di [ReleaseState]). P1 confronta con
 * lui lo stato vero su 2.000 storie casuali di manifest (validi, rotti, rigiocati, scaduti, aggiornamenti dell'app): stesso stato.
 */
class InMemoryReleaseState(
  private val store: ReleaseStateStore,
  private val version: AppVersion,
  private val clock: ReleaseClock,
  private val verifier: ManifestVerifier,
  private val app: String = ManifestLimits.APP_ID,
) : ReleaseState {

  private var stored: StoredRelease? = null
  private val rejected = LinkedHashMap<RejectReason, Int>()
  private val mutableStatus = MutableStateFlow<ReleaseStatus>(ReleaseStatus.Normal)
  private val mutableDiag = MutableStateFlow(ReleaseDiag(0, null, ReleaseStatus.Normal, 0, emptyMap()))

  override val status: StateFlow<ReleaseStatus> = mutableStatus
  override val diag: StateFlow<ReleaseDiag> = mutableDiag

  override suspend fun load() {
    val read = store.read()
    stored = when {
      read == null -> null
      read.versionCode == version.code -> read
      // L'app si e' aggiornata: lo stato si ricalcola dall'ultimo manifest valido per la versione nuova (D30).
      else -> read.copy(versionCode = version.code, status = read.manifest?.let { statusFor(it, version.code) } ?: ReleaseStatus.Normal)
    }
    publish(stored?.status ?: ReleaseStatus.Normal)
  }

  override suspend fun offer(bytes: ByteArray, origin: ManifestOrigin): OfferResult {
    val verified = verifier.verify(bytes, app)
    if (verified is Verified.Rejected) {
      rejected[verified.reason] = (rejected[verified.reason] ?: 0) + 1
      publish(mutableStatus.value)
      return OfferResult.REJECTED
    }
    val ok = verified as Verified.Ok
    val manifest = ok.manifest
    val current = stored
    val seenSeq = current?.seq ?: 0L
    if (manifest.seq < seenSeq) return OfferResult.ROLLBACK
    if (manifest.seq == seenSeq) return OfferResult.SAME
    val fresh = statusFor(manifest, version.code)
    val expired = expired(manifest)
    // P1: "solo piu' severo" rispetto allo stato in vigore, anche quello rimasto in memoria dopo un salvataggio morto (regole 3 e 4).
    val now = mutableStatus.value
    val next = if (expired) ReleaseStatus.strictest(now, fresh) else fresh
    val candidate = StoredRelease(manifest.seq, manifest, ok.keyId, version.code, next)
    try {
      store.write(candidate)
    } catch (e: Exception) {
      // Il salvataggio e' morto: lo stato salvato resta quello di prima; un blocco piu' severo vale comunque in memoria (regola 4).
      publish(ReleaseStatus.strictest(mutableStatus.value, next))
      throw e
    }
    stored = candidate
    publish(next)
    return if (expired) OfferResult.EXPIRED_KEPT_BLOCKS else OfferResult.APPLIED
  }

  override fun extraUrls(id: String, sha256: String): List<String> {
    val manifest = stored?.manifest ?: return emptyList()
    if (expired(manifest)) return emptyList()
    return manifest.models.filter { it.id == id && it.sha256 == sha256 }.flatMap { it.urls }.distinct()
  }

  override suspend fun clear() {
    store.clear()
    stored = null
    rejected.clear()
    publish(ReleaseStatus.Normal)
  }

  private fun expired(manifest: ReleaseManifest): Boolean = clock.bestNow().isAfter(manifest.expires)

  private fun publish(status: ReleaseStatus) {
    mutableStatus.value = status
    val manifest = stored?.manifest
    val urls = if (manifest == null || expired(manifest)) 0 else manifest.models.sumOf { it.urls.size }
    mutableDiag.value = ReleaseDiag(stored?.seq ?: 0, manifest?.issued, status, urls, rejected.toMap())
  }

  companion object {
    /** Lo stato che [manifest] da' alla versione [code]: un blocco `ALL`, poi un blocco `WRITES`, poi la versione minima; altrimenti `Normal`. */
    fun statusFor(manifest: ReleaseManifest, code: Int): ReleaseStatus {
      manifest.blocks.firstOrNull { it.level == BlockLevel.ALL && code in it }?.let { return ReleaseStatus.Stopped(it.message) }
      manifest.blocks.firstOrNull { it.level == BlockLevel.WRITES && code in it }?.let { return ReleaseStatus.ReadOnly(it.message) }
      val min = manifest.minVersionCode
      if (min != null && code < min) return ReleaseStatus.ReadOnly(ReleaseMessage.UPDATE)
      return ReleaseStatus.Normal
    }
  }
}
