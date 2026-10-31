package dev.pampa.mycloudgallery.core.release.state

import dev.pampa.mycloudgallery.core.release.contract.BlockLevel
import dev.pampa.mycloudgallery.core.release.contract.ReleaseClock
import dev.pampa.mycloudgallery.core.release.contract.ReleaseManifest
import dev.pampa.mycloudgallery.core.release.contract.ReleaseMessage
import dev.pampa.mycloudgallery.core.release.contract.ReleaseStatus
import java.time.Instant

/** Le regole pure del manifest per una versione dell'app (D29, D30, E-REL4, E-REL5, E-REL6). */
internal object ReleaseRules {
  /**
   * Lo stato che [manifest] da' alla `versionCode` [code]: il primo blocco `ALL` che la contiene, poi il primo `WRITES`, poi la versione minima
   * (sola lettura con "Aggiorna"); altrimenti `Normal`. I blocchi non scadono (E-REL4): la scadenza la guarda solo chi applica.
   */
  fun statusFor(manifest: ReleaseManifest, code: Int): ReleaseStatus {
    manifest.blocks.firstOrNull { it.level == BlockLevel.ALL && code in it }?.let { return ReleaseStatus.Stopped(it.message) }
    manifest.blocks.firstOrNull { it.level == BlockLevel.WRITES && code in it }?.let { return ReleaseStatus.ReadOnly(it.message) }
    val min = manifest.minVersionCode
    if (min != null && code < min) return ReleaseStatus.ReadOnly(ReleaseMessage.UPDATE)
    return ReleaseStatus.Normal
  }

  /** Scaduto secondo l'ora del NAS (`ReleaseClock.bestNow`, E-REL9): mai l'ora del telefono letta qui. */
  fun expired(manifest: ReleaseManifest, clock: ReleaseClock): Boolean = clock.bestNow().isAfter(manifest.expires)

  /** Gli indirizzi aggiuntivi per un modello con lo **stesso** id e la **stessa** impronta, solo da un manifest non scaduto (regola 6). */
  fun extraUrls(manifest: ReleaseManifest?, id: String, sha256: String, now: Instant): List<String> {
    if (manifest == null || now.isAfter(manifest.expires)) return emptyList()
    return manifest.models.filter { it.id == id && it.sha256 == sha256 }.flatMap { it.urls }.distinct()
  }
}

/**
 * L'ora per la scadenza del manifest (E-REL9): l'ora del NAS quando c'e'; altrimenti la piu' tarda fra l'ultima ora del NAS vista **in questo
 * processo** e l'orologio del telefono. Un telefono con l'orologio indietro non fa rivivere gli indirizzi di un manifest scaduto se il NAS ha
 * gia' detto un'ora piu' tarda. :app (P13) passa le due letture: core-release non legge mai l'orologio del telefono da se'
 * (NoWallClockSourcesTest).
 */
class NasFirstReleaseClock(
  private val nasNow: () -> Instant?,
  private val phoneNow: () -> Instant,
) : ReleaseClock {
  @Volatile private var lastNas: Instant? = null

  override fun bestNow(): Instant {
    val nas = try {
      nasNow()
    } catch (e: RuntimeException) {
      null
    }
    if (nas != null) {
      val seen = lastNas
      if (seen == null || nas.isAfter(seen)) lastNas = nas
      return nas
    }
    val phone = phoneNow()
    val seen = lastNas ?: return phone
    return if (seen.isAfter(phone)) seen else phone
  }
}
