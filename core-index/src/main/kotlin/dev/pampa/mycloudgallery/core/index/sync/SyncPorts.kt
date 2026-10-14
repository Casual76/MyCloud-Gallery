package dev.pampa.mycloudgallery.core.index.sync

import dev.pampa.mycloudgallery.core.index.contract.RemoteFailure
import dev.pampa.mycloudgallery.core.index.replica.store.ReplicaStore
import dev.pampa.mycloudgallery.core.index.replica.store.ShareAccessRow
import dev.pampa.mycloudgallery.core.model.StableCode
import dev.pampa.mycloudgallery.core.trust.nas.NasClock

// Le porte della SINCRONIZZAZIONE (docs/fase4-progetto.md §2.2 "Prima di partire", E-I8, E-I15). Le compone :app (P11)
// dai pezzi che gia' esistono: TrustGuard.preflight, NasRouteGate, ActivityRulesEngine, registro accettato.

/** Perche' un giro di sincronizzazione non parte. */
enum class SyncBlock(override val code: String) : StableCode {
  /** `TrustGuard.preflight`: registro non fresco (oltre 15 minuti) o dispositivo non approvato. */
  NOT_TRUSTED("not_trusted"),

  /** `NasRouteGate`: percorso verso il NAS non consentito (fuori casa senza VPN, interruttore aperto). */
  ROUTE_CLOSED("route_closed"),

  /** `ActivityRulesEngine.evaluate(INDEX_SYNC)`: le regole dicono di aspettare. */
  RULES_WAIT("rules_wait"),
}

/** Il controllo prima di partire (§2.2): `null` = si puo' sincronizzare. */
fun interface SyncGate {
  suspend fun check(): SyncBlock?
}

/** Lo stato di un dispositivo nel registro accettato (formato §4.6). */
enum class DeviceStanding { APPROVED, RETIRED, REVOKED, LOST, UNKNOWN }

/**
 * Il registro accettato, per i dispositivi che hanno dati nella replica ma non compaiono piu' in `devices/`: approvato o
 * dismesso = l'indice e' stato cancellato (E-I5, la replica resta); revocato, perso o sconosciuto = i dati spariscono
 * (E-I15).
 */
fun interface DeviceStandings {
  fun standing(deviceId: String): DeviceStanding
}

/**
 * E-I8, E-R10: una share che risponde `ACCESS_DENIED` o `BAD_NETWORK_NAME` in **due giorni diversi** di ora del NAS si
 * toglie dalla replica (solo i dati locali, con avviso); un errore solo resta "non raggiungibile". Una risposta buona
 * azzera il conto. [dayOf] e' il giorno di un istante del NAS (UTC per default: il NAS non dice il suo fuso).
 */
class ShareAccessPolicy(
  private val store: ReplicaStore,
  private val nasClock: NasClock,
  private val dayOf: (Long) -> Long = { Math.floorDiv(it, DAY_MILLIS) },
) {
  enum class Verdict { KEEP, DROP }

  suspend fun onFailure(shareKey: String, failure: RemoteFailure): Verdict {
    if (failure != RemoteFailure.ACCESS_DENIED && failure != RemoteFailure.SHARE_MISSING) return Verdict.KEEP
    val today = dayOf(nasClock.nowMillis())
    val row = store.shareAccess(shareKey)
    return when {
      row == null -> {
        store.setShareAccess(shareKey, ShareAccessRow(shareKey, failure, today, dropped = false))
        Verdict.KEEP
      }
      row.dropped -> Verdict.KEEP
      row.firstFailureDay != today -> {
        store.setShareAccess(shareKey, row.copy(failure = failure, dropped = true))
        Verdict.DROP
      }
      else -> Verdict.KEEP
    }
  }

  suspend fun onSuccess(shareKey: String) {
    if (store.shareAccess(shareKey) != null) store.setShareAccess(shareKey, null)
  }

  companion object {
    const val DAY_MILLIS: Long = 24L * 60 * 60 * 1000
  }
}
