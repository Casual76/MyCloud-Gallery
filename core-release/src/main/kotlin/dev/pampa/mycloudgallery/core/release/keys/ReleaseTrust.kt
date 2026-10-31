package dev.pampa.mycloudgallery.core.release.keys

import dev.pampa.mycloudgallery.core.release.contract.PinnedKey
import dev.pampa.mycloudgallery.core.release.contract.ReleaseChannel
import dev.pampa.mycloudgallery.core.release.contract.ReleaseKeyIds

/**
 * Le chiavi di cui una build si fida per il manifest (D31), come un **insieme chiuso e opaco**: chi sta fuori da core-release (:app, P13) lo
 * passa alla fabbrica `ReleaseStates.create` senza poter nominare ne' costruire una [PinnedKey] (Phase10ArchitectureTest, regola 3).
 * - [RELEASE]: K1 e K2, per ogni build;
 * - la build debug aggiunge KT con `ReleaseTestKeys.DEBUG_TRUST` (da `app/src/debug`, l'unico posto di :app che puo' nominarla);
 * - [forChannel] toglie KT a ogni build che non sia debug: anche se un errore di legatura la passasse a una beta o a una stabile, KT
 *   resterebbe una chiave sconosciuta (`UNKNOWN_KEY`).
 */
class ReleaseTrust internal constructor(keys: List<PinnedKey>) {
  internal val keys: List<PinnedKey> = keys.toList()

  init {
    require(this.keys.map { it.id }.toSet().size == this.keys.size) { "Due chiavi con lo stesso id." }
  }

  /** Gli id delle chiavi fidate (per la Diagnostica e i test: mai le chiavi). */
  val keyIds: Set<Int> get() = keys.map { it.id }.toSortedSet()

  /** Questo insieme per una build del canale [channel]: KT solo in debug. */
  fun forChannel(channel: ReleaseChannel): ReleaseTrust =
    if (channel == ReleaseChannel.DEBUG) this else ReleaseTrust(keys.filter { it.id != ReleaseKeyIds.KT })

  override fun toString(): String = "ReleaseTrust$keyIds"

  companion object {
    /** K1 e K2: le chiavi di ogni build. */
    val RELEASE: ReleaseTrust = ReleaseTrust(ReleaseKeys.ALL)
  }
}
