package dev.pampa.mycloudgallery.core.albums.noop

import dev.pampa.mycloudgallery.core.albums.contract.TripDigest
import dev.pampa.mycloudgallery.core.albums.contract.TripMemories
import java.time.LocalDate
import kotlin.time.Duration

/** Fase 9 (P0, D12): nessun viaggio per i ricordi (i ricordi di viaggio tacciono). E' quello che c'e' finche' P8 non scrive la porta vera. */
object NoTripMemories : TripMemories {
  override suspend fun past(until: LocalDate, budget: Duration): List<TripDigest> = emptyList()
}
