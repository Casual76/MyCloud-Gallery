package dev.pampa.mycloudgallery.core.nasops.noop

import dev.pampa.mycloudgallery.core.model.FaceGeneration
import dev.pampa.mycloudgallery.core.nasops.contract.EraseRoundReport
import dev.pampa.mycloudgallery.core.nasops.contract.FacesEraseCheck
import dev.pampa.mycloudgallery.core.nasops.contract.FacesEraseReason
import dev.pampa.mycloudgallery.core.nasops.contract.FacesEraseStop
import dev.pampa.mycloudgallery.core.nasops.contract.FacesGenerations
import dev.pampa.mycloudgallery.core.nasops.contract.FacesNasEraser
import kotlin.time.Duration

/**
 * Fase 8 (P0, D31): nessun NAS per i volti. Fallisce CHIUSO: non tocca niente e risponde "irraggiungibile", cosi' "Disattiva e cancella"
 * resta in attesa del NAS (passi 7-11 del §2.8) invece di dichiarare finito cio' che non ha fatto.
 */
object NoFacesNasEraser : FacesNasEraser {
  private val unreachable = EraseRoundReport(removedFiles = 0, removedDirs = 0, remainingFiles = null, stoppedBy = FacesEraseStop.UNREACHABLE)

  override suspend fun listGenerations(share: String): FacesGenerations = FacesGenerations.Stopped(FacesEraseStop.UNREACHABLE)

  override suspend fun eraseGeneration(share: String, generation: FaceGeneration, reason: FacesEraseReason, budget: Duration): EraseRoundReport = unreachable

  override suspend fun eraseDevice(share: String, generation: FaceGeneration, deviceId: String, reason: FacesEraseReason, budget: Duration): EraseRoundReport =
    unreachable

  override suspend fun verify(share: String, generation: FaceGeneration): FacesEraseCheck = FacesEraseCheck.Stopped(FacesEraseStop.UNREACHABLE)
}
