package dev.pampa.mycloudgallery.core.ai.runtime

import dev.pampa.mycloudgallery.core.ai.contract.DeviceProbe
import dev.pampa.mycloudgallery.core.ai.contract.ProbeResult
import kotlinx.coroutines.sync.Mutex

/** Il processo ha un solo tentativo: riaprire la pagina non accoda misure a un driver bloccato. */
class SingleFlightDeviceProbe(private val delegate: DeviceProbe) : DeviceProbe {
  private val lock = Mutex()
  override suspend fun measure(): ProbeResult {
    check(lock.tryLock()) { "Una misura deve ancora liberare il motore." }
    try { return delegate.measure() } finally { lock.unlock() }
  }
}
