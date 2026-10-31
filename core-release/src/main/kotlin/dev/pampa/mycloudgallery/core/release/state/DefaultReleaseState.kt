package dev.pampa.mycloudgallery.core.release.state

import dev.pampa.mycloudgallery.core.release.contract.AppVersion
import dev.pampa.mycloudgallery.core.release.contract.ManifestLimits
import dev.pampa.mycloudgallery.core.release.contract.ManifestOrigin
import dev.pampa.mycloudgallery.core.release.contract.ManifestVerifier
import dev.pampa.mycloudgallery.core.release.contract.OfferResult
import dev.pampa.mycloudgallery.core.release.contract.RejectReason
import dev.pampa.mycloudgallery.core.release.contract.ReleaseClock
import dev.pampa.mycloudgallery.core.release.contract.ReleaseDiag
import dev.pampa.mycloudgallery.core.release.contract.ReleaseState
import dev.pampa.mycloudgallery.core.release.contract.ReleaseStateStore
import dev.pampa.mycloudgallery.core.release.contract.ReleaseStatus
import dev.pampa.mycloudgallery.core.release.contract.StoredRelease
import dev.pampa.mycloudgallery.core.release.contract.Verified
import java.util.EnumMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Lo stato vero del manifest per **questa** versione dell'app (D29, D30; P1). Le sette regole sono quelle del KDoc di [ReleaseState]; la suite
 * `ReleaseStateContract` le prova qui come sul riferimento `InMemoryReleaseState`, con cui questo stato coincide su storie casuali.
 *
 * In breve: un rifiuto del verificatore non cambia niente (si conta per motivo); `seq` minore = `ROLLBACK`, uguale = `SAME`; maggiore e non
 * scaduto (ora del NAS, [ReleaseClock]) = `APPLIED` e lo stato diventa quello del manifest per questa `versionCode` (anche meno severo: solo
 * un manifest **piu' nuovo** toglie un blocco); maggiore ma scaduto = `EXPIRED_KEPT_BLOCKS`, solo piu' severo. Lo stato si **salva prima** di
 * pubblicarlo; se il salvataggio muore l'eccezione passa, lo stato salvato resta quello di prima e un blocco piu' severo vale comunque in
 * memoria. Una `versionCode` diversa da quella salvata ricalcola dall'ultimo manifest valido. Le operazioni sono in fila ([Mutex]): il giro di
 * sincronizzazione e l'avvio possono offrire insieme.
 */
internal class DefaultReleaseState(
  private val store: ReleaseStateStore,
  private val version: AppVersion,
  private val clock: ReleaseClock,
  private val verifier: ManifestVerifier,
  private val app: String = ManifestLimits.APP_ID,
) : ReleaseState {

  private val lock = Mutex()
  @Volatile private var stored: StoredRelease? = null
  private val rejected = EnumMap<RejectReason, Int>(RejectReason::class.java)
  private val mutableStatus = MutableStateFlow<ReleaseStatus>(ReleaseStatus.Normal)
  private val mutableDiag = MutableStateFlow(ReleaseDiag(0, null, ReleaseStatus.Normal, 0, emptyMap()))

  override val status: StateFlow<ReleaseStatus> = mutableStatus
  override val diag: StateFlow<ReleaseDiag> = mutableDiag

  override suspend fun load() = lock.withLock {
    val read = store.read()
    stored = when {
      read == null -> null
      read.versionCode == version.code -> read
      else -> read.copy(versionCode = version.code, status = read.manifest?.let { ReleaseRules.statusFor(it, version.code) } ?: ReleaseStatus.Normal)
    }
    publish(stored?.status ?: ReleaseStatus.Normal)
  }

  override suspend fun offer(bytes: ByteArray, origin: ManifestOrigin): OfferResult = lock.withLock {
    val verified = verifier.verify(bytes, app)
    if (verified is Verified.Rejected) {
      rejected[verified.reason] = (rejected[verified.reason] ?: 0) + 1
      publish(mutableStatus.value)
      return@withLock OfferResult.REJECTED
    }
    val ok = verified as Verified.Ok
    val manifest = ok.manifest
    val current = stored
    val seenSeq = current?.seq ?: 0L
    if (manifest.seq < seenSeq) return@withLock OfferResult.ROLLBACK
    if (manifest.seq == seenSeq) return@withLock OfferResult.SAME
    val fresh = ReleaseRules.statusFor(manifest, version.code)
    val expired = ReleaseRules.expired(manifest, clock)
    // "Solo piu' severo" rispetto allo stato IN VIGORE: anche quello rimasto in memoria dopo un salvataggio morto (regola 4).
    val now = mutableStatus.value
    val next = if (expired) ReleaseStatus.strictest(now, fresh) else fresh
    val candidate = StoredRelease(manifest.seq, manifest, ok.keyId, version.code, next)
    try {
      store.write(candidate)
    } catch (e: Throwable) {
      // Regola 4: lo stato salvato resta quello di prima; un blocco piu' severo vale comunque in memoria finche' il processo vive.
      publish(ReleaseStatus.strictest(mutableStatus.value, next))
      throw e
    }
    stored = candidate
    publish(next)
    if (expired) OfferResult.EXPIRED_KEPT_BLOCKS else OfferResult.APPLIED
  }

  override fun extraUrls(id: String, sha256: String): List<String> = ReleaseRules.extraUrls(stored?.manifest, id, sha256, clock.bestNow())

  override suspend fun clear() = lock.withLock {
    store.clear()
    stored = null
    rejected.clear()
    publish(ReleaseStatus.Normal)
  }

  private fun publish(status: ReleaseStatus) {
    mutableStatus.value = status
    val manifest = stored?.manifest
    val urls = if (manifest == null || ReleaseRules.expired(manifest, clock)) 0 else manifest.models.sumOf { it.urls.size }
    mutableDiag.value = ReleaseDiag(stored?.seq ?: 0, manifest?.issued, status, urls, rejected.toMap())
  }

  override fun toString(): String = "DefaultReleaseState(versione=${version.code}, ${mutableStatus.value})"
}
