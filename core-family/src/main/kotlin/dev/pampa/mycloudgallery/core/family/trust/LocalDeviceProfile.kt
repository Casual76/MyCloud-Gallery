package dev.pampa.mycloudgallery.core.family.trust

import dev.pampa.mycloudgallery.core.nas.contract.NasError
import dev.pampa.mycloudgallery.core.nas.contract.NasIoException
import dev.pampa.mycloudgallery.core.nas.contract.NasResult
import dev.pampa.mycloudgallery.core.nas.contract.NasSessionManager
import dev.pampa.mycloudgallery.core.nas.contract.Purpose
import dev.pampa.mycloudgallery.core.trust.data.DeviceRole
import dev.pampa.mycloudgallery.core.trust.nas.NasFileSystem

/**
 * Cio' che la fiducia deve sapere di **questo** dispositivo e che non sta nel registro firmato: le scelte
 * dell'onboarding (P4: account, aree, nome, ruoli) e la versione dell'app. Lo fornisce `:app` (P9) adattando
 * cio' che l'onboarding ha salvato. Mai un dato letto dal NAS.
 *
 * @property commonShare dove stanno registro, richieste e segnalazioni.
 * @property privateShare l'area privata dell'account: li' l'admin tiene il kit e il proprietario pubblica la
 *   copia delle segnalazioni "perso" (prova di possesso dell'account, revisione B6). Null se non scelta.
 */
data class LocalDeviceProfile(
  val nasUser: String,
  val commonShare: String,
  val privateShare: String?,
  val deviceName: String,
  val deviceModel: String,
  val roles: Set<DeviceRole>,
  val appVersion: String,
) {
  override fun toString(): String = "LocalDeviceProfile(${roles.size} ruoli)"
}

/** Da dove arriva [LocalDeviceProfile] (null: dispositivo non ancora configurato). */
fun interface LocalDeviceProfileSource {
  suspend fun load(): LocalDeviceProfile?
}

/**
 * Esegue [block] (operazioni bloccanti di `core-trust`) su [share], convertendo in un esito anche gli errori che
 * il `NasFileSystem` di `core-nas` lancia come [NasIoException]. Nessuna eccezione NAS esce da qui.
 */
internal suspend fun <T> NasSessionManager.onShare(share: String, purpose: Purpose, block: (NasFileSystem) -> T): NasResult<T> =
  try {
    val outer = withShare(share, purpose) { scope ->
      try {
        NasResult.Ok(block(scope.fileSystem))
      } catch (e: NasIoException) {
        NasResult.Err(e.error)
      }
    }
    when (outer) {
      is NasResult.Ok -> outer.value
      is NasResult.Err -> outer
    }
  } catch (e: NasIoException) {
    NasResult.Err(e.error)
  }

/** Un errore NAS che non ha un codice SMB da mostrare (eccezione inattesa di una libreria). */
internal val UNEXPECTED: NasError = NasError.Unexpected(null)
