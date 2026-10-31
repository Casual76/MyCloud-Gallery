package dev.pampa.mycloudgallery.core.map.testing

import dev.pampa.mycloudgallery.core.map.contract.GeoBox
import dev.pampa.mycloudgallery.core.map.contract.RegionEngine
import dev.pampa.mycloudgallery.core.map.contract.RegionEngineFailure
import dev.pampa.mycloudgallery.core.map.contract.RegionEngineListener
import dev.pampa.mycloudgallery.core.map.contract.RegionId

/**
 * Il motore degli scaricamenti finto (Fase 9, P5; §5.2): niente MapLibre, niente rete. Registra cosa gli si chiede ([starts], [stops], [deletes]) e i suoi eventi si pilotano a mano
 * ([progress], [complete], [fail]), come un `OfflineManager` che avanza quando lo dice la prova. Per `OfflineMapRegions` sulla JVM.
 */
class FakeRegionEngine : RegionEngine {

  class Start(val key: String, val box: GeoBox, val minZoom: Int, val maxZoom: Int)

  val starts = mutableListOf<Start>()
  val stops = mutableListOf<String>()
  val deletes = mutableListOf<String>()
  var deletedAll = 0
    private set

  /** Se non null, `start` lancia questo errore (il motore non parte). */
  var startFailure: Exception? = null

  private val listeners = mutableMapOf<String, RegionEngineListener>()

  override suspend fun start(key: String, box: GeoBox, minZoom: Int, maxZoom: Int, listener: RegionEngineListener) {
    startFailure?.let { throw it }
    starts += Start(key, box, minZoom, maxZoom)
    listeners[key] = listener
  }

  override suspend fun stop(key: String) {
    stops += key
  }

  override suspend fun delete(key: String) {
    deletes += key
    listeners.remove(key)
  }

  override suspend fun deleteAll() {
    deletedAll++
    listeners.clear()
  }

  fun progress(id: RegionId, completed: Long, required: Long) = listeners[id.code]?.onProgress(completed, required)

  fun complete(id: RegionId) = listeners[id.code]?.onComplete()

  fun fail(id: RegionId, failure: RegionEngineFailure) = listeners[id.code]?.onFailure(failure)

  fun isStarted(id: RegionId): Boolean = id.code in listeners
}
