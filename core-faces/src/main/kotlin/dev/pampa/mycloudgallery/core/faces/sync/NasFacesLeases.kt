package dev.pampa.mycloudgallery.core.faces.sync

import dev.pampa.mycloudgallery.core.common.featureflags.FeatureFlags
import dev.pampa.mycloudgallery.core.faces.contract.FacesClock
import dev.pampa.mycloudgallery.core.faces.contract.FacesLease
import dev.pampa.mycloudgallery.core.faces.contract.FacesLeases
import dev.pampa.mycloudgallery.core.faces.contract.FacesResource
import dev.pampa.mycloudgallery.core.faces.contract.FacesSwitch
import dev.pampa.mycloudgallery.core.faces.contract.FacesWriteGate
import dev.pampa.mycloudgallery.core.model.FaceGeneration
import dev.pampa.mycloudgallery.core.trust.TrustLimits
import dev.pampa.mycloudgallery.core.trust.data.AcquireResult
import dev.pampa.mycloudgallery.core.trust.data.LeaseManager
import dev.pampa.mycloudgallery.core.trust.data.LeaseRoot
import dev.pampa.mycloudgallery.core.trust.data.LeaseStatus
import dev.pampa.mycloudgallery.core.trust.nas.NasClock
import dev.pampa.mycloudgallery.core.trust.nas.NasFileSystem
import dev.pampa.mycloudgallery.core.trust.nas.NasLayout
import dev.pampa.mycloudgallery.core.trust.registry.RegistryState
import java.io.IOException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * LE PRENOTAZIONI DEI VOLTI SOTTO LA GENERAZIONE (Fase 8, P3; docs/fase8-progetto.md D16, D21; formato §5.6 "Prenotazioni dei volti", A5):
 * il contratto [FacesLeases] sopra `LeaseManager` di core-trust con la radice `faces/<G>/leases/` ([LeaseRoot.Faces]) nella share privata
 * [fs]: le unita' di calcolo `faces/<G>/work/<area>/<mese>/<parte>` (P4) e il custode `faces/<G>/cluster` (P5). "Disattiva e cancella" le
 * toglie con la cartella della generazione. Durata 1 ora, rinnovo con una generazione nuova (ogni 20 minuti, lo decide chi la tiene).
 *
 * Gli stessi cancelli della pubblicazione ([FacesSyncGuard], D30, D34, D35, L23): mai nell'area comune, mai sulle share vere con la recinzione
 * accesa ([FacesLease.Blocked]), mai senza lo stato fresco o per una generazione che non e' la corrente ([FacesLease.Failed]), mai con il
 * cancello delle scritture chiuso. Una prenotazione non protegge niente: evita solo il lavoro doppio (E-I1).
 */
class NasFacesLeases(
  private val identity: FacesSyncIdentity,
  private val switch: FacesSwitch,
  private val fs: NasFileSystem,
  private val clock: FacesClock,
  private val gate: FacesWriteGate,
  private val flags: FeatureFlags = FeatureFlags.RELEASE,
  private val io: CoroutineDispatcher = Dispatchers.IO,
) : FacesLeases {

  private val guard = FacesSyncGuard(identity, switch, { fs.shareName }, flags)

  override suspend fun acquire(generation: FaceGeneration, resource: FacesResource): FacesLease =
    withManager(generation) { manager, registry -> toLease(manager.tryAcquire(registry, resourceName(generation, resource), DURATION_MILLIS)) }

  /** Il rinnovo: una generazione nuova se la prenotazione e' ancora mia (o libera); se un altro l'ha presa, [FacesLease.Lost]. */
  override suspend fun renew(generation: FaceGeneration, resource: FacesResource, held: FacesLease.Held): FacesLease =
    withManager(generation) { manager, registry ->
      val name = resourceName(generation, resource)
      val (status, _) = manager.inspect(registry, name) ?: return@withManager FacesLease.Contended
      if (status is LeaseStatus.HeldByOther) return@withManager FacesLease.Lost
      when (val result = toLease(manager.tryAcquire(registry, name, DURATION_MILLIS))) {
        FacesLease.Busy -> FacesLease.Lost
        else -> result
      }
    }

  override suspend fun release(generation: FaceGeneration, resource: FacesResource, held: FacesLease.Held) {
    withManager(generation) { manager, registry ->
      manager.release(registry, resourceName(generation, resource))
      held
    }
  }

  private suspend fun withManager(generation: FaceGeneration, block: (LeaseManager, RegistryState) -> FacesLease): FacesLease {
    val open = when (val checked = guard.check()) {
      is FacesGate.Closed -> return checked.block?.let { FacesLease.Blocked(it) } ?: FacesLease.Failed
      is FacesGate.Open -> checked
    }
    // Mai una prenotazione in una generazione che non e' la corrente (una cancellata non si ripopola, D30).
    if (open.generation != generation) return FacesLease.Failed
    gate.blockFor(fs.shareName)?.let { return FacesLease.Blocked(it) }
    if (clock.nasNowMillis() == null) return FacesLease.Failed
    val nas = NasClock { clock.nasNowMillis() ?: throw IOException("Ora del NAS non disponibile.") }
    val manager = LeaseManager(fs, identity.signer, nas, LeaseRoot.Faces(generation))
    return try {
      withContext(io) { block(manager, open.registry) }
    } catch (e: IOException) {
      FacesLease.Failed
    }
  }

  private fun toLease(result: AcquireResult): FacesLease = when (result) {
    is AcquireResult.Acquired -> FacesLease.Held(result.generation, result.until)
    is AcquireResult.Busy -> FacesLease.Busy
    AcquireResult.Lost -> FacesLease.Lost
    AcquireResult.Contended -> FacesLease.Contended
  }

  companion object {
    /** Un'ora, come le unita' della Fase 4 e il custode (D21). */
    const val DURATION_MILLIS: Long = TrustLimits.MAX_LEASE_MILLIS

    /** La risorsa in chiaro nel record firmato: mai nomi di share, cartelle o file (A5). */
    fun resourceName(generation: FaceGeneration, resource: FacesResource): String = when (resource) {
      is FacesResource.WorkUnit -> NasLayout.facesWorkResource(generation, resource.area.code, resource.month, resource.part)
      FacesResource.Cluster -> NasLayout.facesClusterResource(generation)
    }
  }
}
