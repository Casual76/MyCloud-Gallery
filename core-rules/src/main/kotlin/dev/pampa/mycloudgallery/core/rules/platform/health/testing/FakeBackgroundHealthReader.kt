package dev.pampa.mycloudgallery.core.rules.platform.health.testing

import dev.pampa.mycloudgallery.core.rules.platform.health.BackgroundHealth
import dev.pampa.mycloudgallery.core.rules.platform.health.BackgroundHealthReader

/**
 * Il fake di [BackgroundHealthReader] (D6: in src/main, per i test dei moduli a valle; mai nel dex di
 * release). [health] si cambia dal test; [reads] conta le letture.
 */
class FakeBackgroundHealthReader(var health: BackgroundHealth = BackgroundHealth.UNKNOWN) : BackgroundHealthReader {

  var reads: Int = 0
    private set

  override suspend fun read(): BackgroundHealth {
    reads++
    return health
  }
}
