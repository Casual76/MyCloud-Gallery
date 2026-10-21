package dev.pampa.mycloudgallery.core.ai.compute

import java.util.Locale

/** Un dispositivo della famiglia come lo dice il suo stato firmato (`DeviceStatusReport`: ruoli, ultima volta visto, account). */
data class DeviceRoleView(val account: String, val indexer: Boolean, val lastSeenNasMillis: Long) {
  override fun toString(): String = "DeviceRoleView(indexer=$indexer)"
}

/** Un'area del NAS: l'area comune ([ownerAccount] null) o l'area privata di un account. */
data class AreaView(val share: String, val ownerAccount: String?) {
  override fun toString(): String = "AreaView(${if (ownerAccount == null) "comune" else "privata"})"
}

/**
 * Le aree senza indicizzatori (D14, E-I12): **solo un'informazione** per la pagina Ricerca e modelli e per Cerca, mai un'azione.
 * Un'area privata ne ha se un dispositivo **dello stesso account** e' Indicizzatore e si e' fatto vivo negli ultimi 7 giorni (ora del
 * NAS); l'area comune se ce n'e' uno qualunque. Nessun dispositivo indicizza un'area privata d'altri (D14), quindi un indicizzatore
 * di un altro account non la copre.
 */
object IndexerAreas {
  const val FRESH_MILLIS: Long = 7L * 24 * 60 * 60 * 1000

  fun uncovered(areas: List<AreaView>, devices: List<DeviceRoleView>, nasNowMillis: Long): List<AreaView> {
    val live = devices.filter { it.indexer && nasNowMillis - it.lastSeenNasMillis in 0..FRESH_MILLIS }
    val accounts = live.mapTo(HashSet()) { it.account.lowercase(Locale.ROOT) }
    return areas.filter { area ->
      val owner = area.ownerAccount
      if (owner == null) live.isEmpty() else owner.lowercase(Locale.ROOT) !in accounts
    }
  }
}
