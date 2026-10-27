package dev.pampa.mycloudgallery.core.faces.chaos

import dev.pampa.mycloudgallery.core.faces.contract.FacesEraseOps
import dev.pampa.mycloudgallery.core.faces.contract.NasEraseCheck
import dev.pampa.mycloudgallery.core.faces.contract.NasEraseReason
import dev.pampa.mycloudgallery.core.faces.contract.NasEraseRound
import dev.pampa.mycloudgallery.core.faces.erase.FacesAccountFacts
import dev.pampa.mycloudgallery.core.faces.erase.FacesEraseCodes
import dev.pampa.mycloudgallery.core.model.FaceGeneration
import dev.pampa.mycloudgallery.core.nasops.contract.FacesCurrent
import dev.pampa.mycloudgallery.core.nasops.contract.FacesEraseCheck
import dev.pampa.mycloudgallery.core.nasops.contract.FacesEraseFacts
import dev.pampa.mycloudgallery.core.nasops.contract.FacesEraseReason
import dev.pampa.mycloudgallery.core.nasops.contract.FacesGenerations
import dev.pampa.mycloudgallery.core.nasops.contract.FacesNasEraser
import kotlin.time.Duration

// I legami che `:app` (P13) scrive fra core-faces e core-nasops, qui per il banco: cio' che chi cancella sa (la generazione corrente dallo stato fresco e i
// dispositivi revocati) e la porta `FacesEraseOps` di core-faces sopra il vero `FacesNasEraser`. La traduzione degli esiti sta in `FacesEraseCodes`
// (un posto solo, provato): qui non si riscrive.

/** Cio' che chi cancella sa: la generazione corrente (stato fresco) e i dispositivi revocati o persi, come li lega `:app`. */
internal class SmbEraseFacts(private val facts: FacesAccountFacts) : FacesEraseFacts {
  override suspend fun current(): FacesCurrent = when (val c = facts.current()) {
    is FacesAccountFacts.Current.Known -> FacesCurrent.Known(c.generation)
    FacesAccountFacts.Current.Unknown -> FacesCurrent.Unknown
  }

  override suspend fun revokedOrLost(deviceId: String): Boolean = facts.revokedOrLost(deviceId)
}

/** La porta `FacesEraseOps` di core-faces sopra il vero `FacesNasEraser` dell'area privata [share]. */
internal class SmbEraseOps(private val eraser: FacesNasEraser, private val share: String) : FacesEraseOps {
  override suspend fun generations(): List<FaceGeneration>? = when (val r = eraser.listGenerations(share)) {
    is FacesGenerations.Listed -> r.generations
    is FacesGenerations.Stopped -> null
  }

  override suspend fun eraseGeneration(generation: FaceGeneration, reason: NasEraseReason, budget: Duration): NasEraseRound {
    val r = eraser.eraseGeneration(share, generation, reasonOf(reason), budget)
    return FacesEraseCodes.round(r.stoppedBy.code, r.removedFiles, r.remainingFiles)
  }

  override suspend fun eraseDevice(generation: FaceGeneration, deviceId: String, reason: NasEraseReason, budget: Duration): NasEraseRound {
    val r = eraser.eraseDevice(share, generation, deviceId, reasonOf(reason), budget)
    return FacesEraseCodes.round(r.stoppedBy.code, r.removedFiles, r.remainingFiles)
  }

  override suspend fun verify(generation: FaceGeneration): NasEraseCheck? = when (val c = eraser.verify(share, generation)) {
    is FacesEraseCheck.Checked -> FacesEraseCodes.check(false, c.generationPresent, c.recycleBinCopy)
    is FacesEraseCheck.Stopped -> FacesEraseCodes.check(true, false, false)
  }

  private fun reasonOf(reason: NasEraseReason): FacesEraseReason = when (reason) {
    NasEraseReason.USER_CONFIRMED -> FacesEraseReason.USER_CONFIRMED
    NasEraseReason.NOT_CURRENT -> FacesEraseReason.NOT_CURRENT
    NasEraseReason.DEVICE_REVOKED -> FacesEraseReason.DEVICE_REVOKED
  }
}
