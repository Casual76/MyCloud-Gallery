package dev.pampa.mycloudgallery.nas

import dev.pampa.mycloudgallery.core.nas.contract.CredentialLoad
import dev.pampa.mycloudgallery.core.nas.contract.NasCredentialSource
import dev.pampa.mycloudgallery.core.security.credentials.LoadResult

/**
 * L'adattatore da `CredentialVault` (core-security) a [NasCredentialSource] (core-nas): `core-nas` non
 * conosce il Keystore e `core-security` non conosce il NAS. Traduce gli esiti uno a uno, senza aprire le
 * credenziali (chi le riceve le chiude dopo l'autenticazione).
 *
 * [loadFromVault] e' `CredentialVault.load` (in produzione) o una funzione finta (nei test).
 */
class VaultNasCredentialSource(private val loadFromVault: suspend (String) -> LoadResult) : NasCredentialSource {

  override suspend fun load(slot: String): CredentialLoad = when (val result = loadFromVault(slot)) {
    is LoadResult.Found -> CredentialLoad.Found(result.credentials)
    LoadResult.Absent -> CredentialLoad.Absent
    LoadResult.NeedsPassword -> CredentialLoad.NeedsPassword
    LoadResult.Unavailable -> CredentialLoad.Unavailable
  }
}
