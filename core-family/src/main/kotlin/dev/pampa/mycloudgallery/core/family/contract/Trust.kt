package dev.pampa.mycloudgallery.core.family.contract

import dev.pampa.mycloudgallery.core.nas.contract.NasError
import dev.pampa.mycloudgallery.core.nas.contract.Purpose
import dev.pampa.mycloudgallery.core.trust.data.HeadMark

/**
 * Il permesso di scrivere dati firmati sul NAS: esiste solo se questo dispositivo risulta approvato
 * nell'ultimo registro accettato. Chi pubblica (indice, backup) lo chiede a [TrustGuard.writePermit].
 */
class WritePermit(val deviceId: String, val registryVersion: Long, val grantedAt: Long) {
  override fun toString(): String = "WritePermit(v$registryVersion)"
}

/** Perche' si lavora in sola lettura. */
enum class ReadOnlyReason { AWAITING_APPROVAL, RETIRED, REGISTRY_STALE }

/** Perche' non si lavora affatto. */
enum class StopReason { NOT_CONFIGURED, REVOKED, NEEDS_APP_UPDATE, REGISTRY_UNTRUSTED, NAS_UNAVAILABLE }

/** L'esito del controllo che precede ogni sessione di lavoro. */
sealed interface PreflightResult {
  /** Si lavora, anche in scrittura, con [permit]. */
  data class Proceed(val permit: WritePermit) : PreflightResult

  data class ReadOnly(val reason: ReadOnlyReason) : PreflightResult

  data class Stop(val reason: StopReason, val error: NasError? = null) : PreflightResult

  /** Il registro firmato e accettato dice "perso": l'azzeramento e' partito (C15). */
  data object WipeStarted : PreflightResult
}

/**
 * La guardia della fiducia (P3). [preflight] va chiamato prima di **ogni** sessione di lavoro (al massimo
 * ogni 15 minuti rilegge il registro). Un registro non firmato, un rollback o un'equivocazione **non** fanno
 * azzerare (test obbligatorio di P3): solo lo stato `lost` in un registro firmato e accettato.
 */
interface TrustGuard {
  suspend fun preflight(purpose: Purpose): PreflightResult

  /** Il permesso di scrittura corrente, senza toccare il NAS; null se non approvato. */
  fun writePermit(): WritePermit?
}

/**
 * Cio' che serve all'anti-rollback, salvato in locale (`noBackupFilesDir`): i byte dell'ultimo registro
 * accettato (si ricaricano con `RegistryVerifier.restore`), l'impronta di chi l'ha firmato, l'ancora di
 * fiducia, le ultime "teste" proprie accettate.
 */
class TrustSnapshot(
  registryBytes: ByteArray,
  val signerFingerprint: String,
  val familyId: String,
  val familyKeyFingerprint: String,
  val minRegistryVersion: Long,
  val ownHeads: Map<String, HeadMark>,
) {
  private val bytes = registryBytes.copyOf()

  val registryBytes: ByteArray get() = bytes.copyOf()

  override fun toString(): String = "TrustSnapshot(${bytes.size} byte, v>=$minRegistryVersion)"
}

/** Dove si conserva [TrustSnapshot] (contratto P0, implementazione P3). */
interface TrustStateStore {
  suspend fun load(): TrustSnapshot?

  suspend fun save(snapshot: TrustSnapshot)

  suspend fun clear()
}
