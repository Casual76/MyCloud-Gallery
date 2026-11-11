package dev.pampa.mycloudgallery.core.ai.runtime

import dev.pampa.mycloudgallery.core.ai.contract.DeviceProbe
import dev.pampa.mycloudgallery.core.ai.contract.ProbeResult
import kotlinx.coroutines.sync.Mutex

/** Il processo ha un solo tentativo: riaprire la pagina non accoda misure a un driver bloccato. */
class SingleFlightDeviceProbe(private val delegate: DeviceProbe) : DeviceProbe {
  private val lock = Mutex()
  override suspend fun availability() = if (lock.isLocked) dev.pampa.mycloudgallery.core.ai.contract.ProbeAvailability.BUSY else delegate.availability()
  override suspend fun measure(): ProbeResult {
    if (!lock.tryLock()) throw dev.pampa.mycloudgallery.core.ai.contract.ProbeUnavailable(dev.pampa.mycloudgallery.core.ai.contract.ProbeAvailability.BUSY)
    try { return delegate.measure() } finally { lock.unlock() }
  }
}
