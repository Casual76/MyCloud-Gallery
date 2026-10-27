package dev.pampa.mycloudgallery.faces

import dev.pampa.mycloudgallery.core.faces.compute.ComputeStoreOpen
import dev.pampa.mycloudgallery.core.faces.compute.FacesStoreSource
import dev.pampa.mycloudgallery.core.faces.compute.FacesStoresSource
import dev.pampa.mycloudgallery.core.faces.contract.FacesArchive
import dev.pampa.mycloudgallery.core.faces.contract.FacesEraseOps
import dev.pampa.mycloudgallery.core.faces.contract.NasEraseCheck
import dev.pampa.mycloudgallery.core.faces.contract.NasEraseReason
import dev.pampa.mycloudgallery.core.faces.contract.NasEraseRound
import dev.pampa.mycloudgallery.core.faces.contract.EraseWait
import dev.pampa.mycloudgallery.core.faces.erase.FacesAccountFacts
import dev.pampa.mycloudgallery.core.faces.erase.FacesEraseCodes
import dev.pampa.mycloudgallery.core.faces.erase.FacesEraseFence
import dev.pampa.mycloudgallery.core.faces.store.FacesStores
import dev.pampa.mycloudgallery.core.faces.store.FacesUnavailable
import dev.pampa.mycloudgallery.core.faces.sync.FacesArchives
import dev.pampa.mycloudgallery.core.faces.sync.FacesPublishState
import dev.pampa.mycloudgallery.core.faces.sync.FacesPublishStore
import dev.pampa.mycloudgallery.core.faces.sync.FacesReplicaState
import dev.pampa.mycloudgallery.core.faces.sync.FacesReplicaStore
import dev.pampa.mycloudgallery.core.model.FaceGeneration
import dev.pampa.mycloudgallery.core.nasops.contract.FacesCurrent
import dev.pampa.mycloudgallery.core.nasops.contract.FacesEraseCheck
import dev.pampa.mycloudgallery.core.nasops.contract.FacesEraseFacts
import dev.pampa.mycloudgallery.core.nasops.contract.FacesEraseReason
import dev.pampa.mycloudgallery.core.nasops.contract.FacesGenerations
import dev.pampa.mycloudgallery.core.nasops.contract.FacesNasEraser
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration

// I RECINTI E LE PORTE DELLA CANCELLAZIONE (Fase 8, P13; docs/fase8-progetto.md §1.4, §2.8, D29-D31, D35): **ogni** apertura dei dati dei volti passa dal
// recinto ([FacesEraseFence]) -- il calcolo, la replica, il pubblicatore, le persone, il raggruppamento -- cosi' una generazione che si sta cancellando
// (o che questo dispositivo non tratta) non rinasce con una chiave nuova. E la cancellazione stretta del NAS di core-nasops vista da core-faces: sempre per
// generazione, dispositivo e motivo, mai per percorso. Nessun log (D37).

/** L'archivio della generazione, aperto solo se il recinto lo permette; ricorda l'ultimo aperto (per chi guarda un archivio **gia'** aperto). */
class FencedArchives(private val fence: FacesEraseFence, inner: FacesArchives) : FacesArchives {
  private val gated = fence.archives(inner)
  private val last = AtomicReference<FacesArchive?>(null)

  override suspend fun open(generation: FaceGeneration): FacesArchive? = gated.open(generation)?.also { last.set(it) }

  /** L'ultimo archivio aperto (mai riaperto da qui): il buffer lo guarda senza aprire il database. Puo' essere chiuso: chi lo usa prende l'eccezione. */
  fun opened(): FacesArchive? = last.get()

  /** Dopo una cancellazione o uno stop: nessun archivio da guardare. */
  fun forget() {
    last.set(null)
  }
}

/** Lo stato della replica dietro il recinto: una generazione che il recinto non ammette e' vuota in lettura e un errore in scrittura. */
class FencedReplicaStore(private val fence: FacesEraseFence, private val inner: FacesReplicaStore) : FacesReplicaStore {
  override suspend fun load(generation: FaceGeneration): FacesReplicaState = fence.open(generation) { inner.load(generation) } ?: FacesReplicaState.EMPTY

  override suspend fun save(generation: FaceGeneration, state: FacesReplicaState) {
    fence.open(generation) { inner.save(generation, state) } ?: throw IllegalStateException("Generazione non trattata qui.")
  }
}

/** Lo stato del pubblicatore dietro il recinto (come [FencedReplicaStore]). */
class FencedPublishStore(private val fence: FacesEraseFence, private val inner: FacesPublishStore) : FacesPublishStore {
  override suspend fun load(generation: FaceGeneration): FacesPublishState = fence.open(generation) { inner.load(generation) } ?: FacesPublishState.EMPTY

  override suspend fun save(generation: FaceGeneration, state: FacesPublishState) {
    fence.open(generation) { inner.save(generation, state) } ?: throw IllegalStateException("Generazione non trattata qui.")
  }
}

/** Il negozio del calcolo dietro il recinto: una generazione non ammessa e' "non adesso" (non e' un errore e non cancella niente). */
class FencedStoreSource(private val fence: FacesEraseFence, stores: FacesStores) : FacesStoreSource {
  private val inner = FacesStoresSource(stores)

  override suspend fun open(generation: FaceGeneration): ComputeStoreOpen =
    fence.open(generation) { inner.open(generation) } ?: ComputeStoreOpen.Unavailable(FacesUnavailable.STORAGE)
}

/**
 * La cancellazione stretta del NAS ([FacesNasEraser] di core-nasops) come la vede core-faces (`FacesEraseOps`): solo l'area privata dichiarata, sempre
 * per generazione e motivo. Gli esiti si traducono con `FacesEraseCodes` (una tabella sola, provata); null da [generations] e [verify] = non si sa adesso.
 */
class NasFacesEraseOps(private val eraser: FacesNasEraser, private val privateShare: () -> String?) : FacesEraseOps {

  override suspend fun generations(): List<FaceGeneration>? {
    val share = privateShare() ?: return null
    return when (val listed = eraser.listGenerations(share)) {
      is FacesGenerations.Listed -> listed.generations
      is FacesGenerations.Stopped -> null
    }
  }

  override suspend fun eraseGeneration(generation: FaceGeneration, reason: NasEraseReason, budget: Duration): NasEraseRound {
    val share = privateShare() ?: return NasEraseRound.Waiting(EraseWait.NAS)
    val report = eraser.eraseGeneration(share, generation, reason.toNasops(), budget)
    return FacesEraseCodes.round(report.stoppedBy.code, report.removedFiles, report.remainingFiles)
  }

  override suspend fun eraseDevice(generation: FaceGeneration, deviceId: String, reason: NasEraseReason, budget: Duration): NasEraseRound {
    val share = privateShare() ?: return NasEraseRound.Waiting(EraseWait.NAS)
    val report = eraser.eraseDevice(share, generation, deviceId, reason.toNasops(), budget)
    return FacesEraseCodes.round(report.stoppedBy.code, report.removedFiles, report.remainingFiles)
  }

  override suspend fun verify(generation: FaceGeneration): NasEraseCheck? {
    val share = privateShare() ?: return null
    return when (val check = eraser.verify(share, generation)) {
      is FacesEraseCheck.Checked -> FacesEraseCodes.check(stopped = false, generationPresent = check.generationPresent, recycleBinCopy = check.recycleBinCopy)
      is FacesEraseCheck.Stopped -> null
    }
  }

  private fun NasEraseReason.toNasops(): FacesEraseReason = when (this) {
    NasEraseReason.USER_CONFIRMED -> FacesEraseReason.USER_CONFIRMED
    NasEraseReason.NOT_CURRENT -> FacesEraseReason.NOT_CURRENT
    NasEraseReason.DEVICE_REVOKED -> FacesEraseReason.DEVICE_REVOKED
  }

  override fun toString(): String = "NasFacesEraseOps"
}

/** Cio' che sa chi cancella sul NAS (`FacesEraseFacts` di core-nasops) dalle regole di core-faces ([FacesAccountFacts]: stato fresco, voce capita, registro). */
class AccountEraseFacts(private val facts: FacesAccountFacts) : FacesEraseFacts {
  override suspend fun current(): FacesCurrent = when (val current = facts.current()) {
    is FacesAccountFacts.Current.Known -> FacesCurrent.Known(current.generation)
    FacesAccountFacts.Current.Unknown -> FacesCurrent.Unknown
  }

  override suspend fun revokedOrLost(deviceId: String): Boolean = facts.revokedOrLost(deviceId)
}
