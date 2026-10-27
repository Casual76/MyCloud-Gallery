package dev.pampa.mycloudgallery.core.faces.chaos

import dev.pampa.mycloudgallery.core.faces.contract.FacesClock

/**
 * L'orologio del banco: l'ora del NAS e l'orologio monotono avanzano insieme, solo quando il banco (o un motore finto) lo dice. Il simulatore SMB
 * e la sessione di produzione lo leggono da qui (`nasTime`, `MonotonicClock`): un solo tempo per tutto il mondo.
 */
internal class ChaosClock(var monotonicMs: Long = START_MONOTONIC_MS, var nasMs: Long = START_NAS_MS) : FacesClock {
  override fun monotonicNanos(): Long = monotonicMs * 1_000_000L

  override fun nasNowMillis(): Long? = nasMs

  fun advance(millis: Long) {
    monotonicMs += millis
    nasMs += millis
  }

  companion object {
    const val START_MONOTONIC_MS: Long = 10_000_000L
    const val START_NAS_MS: Long = 1_800_000_000_000L
  }
}
